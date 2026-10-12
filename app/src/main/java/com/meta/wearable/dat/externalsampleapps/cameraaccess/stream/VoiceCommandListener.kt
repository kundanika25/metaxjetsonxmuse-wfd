/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Listens for spoken commands without retaining audio data.
 *
 * SpeechRecognizer owns the transient microphone stream. This class only inspects recognized text
 * and restarts listening while streaming is active. When several commands are configured, the one
 * closest to what was heard wins, so similar-sounding commands are not confused.
 */
class VoiceCommandListener(
  context: Context,
  private val commands: List<String>,
  private val onCommandDetected: (String) -> Unit,
  private val onSpeechRecognized: (String) -> Unit = {},
) {
  companion object {
    private const val TAG = "CameraAccess:VoiceCommandListener"
    private const val RESTART_DELAY_MS = 500L
    private const val MANUAL_RESTART_DELAY_MS = 200L
    private const val COMMAND_DEBOUNCE_MS = 1_500L

    // Tolerance for fuzzy matching, expressed as a fraction of the compared string's length.
    // SpeechRecognizer frequently mis-segments "hey meta" into things like "he metal" / "hemetal" /
    // "a meta" — these constants control how much edit-distance slack we allow before rejecting a
    // candidate as "not the command".
    private const val FUZZY_PHRASE_TOLERANCE_RATIO = 0.35
    private const val FUZZY_PHRASE_MIN_TOLERANCE = 2
  }

  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())
  private val collapsedCommands: List<Pair<String, String>> =
    commands.map { it to it.normalizeForCommandMatching().replace(" ", "") }

  private var speechRecognizer: SpeechRecognizer? = null
  private var isListening = false
  private var lastCommandDetectedAtMs = 0L

  fun start() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { start() }
      return
    }
    if (isListening) {
      Log.d(TAG, "Already listening, ignoring start() call")
      return
    }
    if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
      Log.w(TAG, "Speech recognition is not available on this device")
      return
    }

    Log.i(TAG, "Starting speech recognizer for commands: $commands")
    isListening = true
    mainHandler.removeCallbacksAndMessages(null)
    routeAudioToGlassesIfAvailable()
    ensureRecognizer()
    listen()
  }

  fun stop() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { stop() }
      return
    }
    isListening = false
    mainHandler.removeCallbacksAndMessages(null)
    try {
      speechRecognizer?.cancel()
      speechRecognizer?.destroy()
    } catch (e: Exception) {
      Log.w(TAG, "Error stopping speech recognizer: ${e.message}")
    }
    speechRecognizer = null
    try {
      audioManager.clearCommunicationDevice()
    } catch (e: Exception) {
      Log.w(TAG, "Error clearing communication device: ${e.message}")
    }
    Log.i(TAG, "Stopped speech recognizer")
  }

  fun restartListeningNow() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { restartListeningNow() }
      return
    }
    Log.d(TAG, "Manual restart requested")
    if (!isListening) {
      Log.d(TAG, "Not listening, starting instead")
      start()
      return
    }
    mainHandler.removeCallbacksAndMessages(null)
    try {
      speechRecognizer?.cancel()
    } catch (e: Exception) {
      Log.w(TAG, "Error canceling recognizer: ${e.message}")
    }
    mainHandler.postDelayed({
      Log.d(TAG, "Restarting listening after delay")
      listen()
    }, MANUAL_RESTART_DELAY_MS)
  }

  private fun ensureRecognizer() {
    if (speechRecognizer != null) {
      Log.d(TAG, "Speech recognizer already exists")
      return
    }

    try {
      Log.d(TAG, "Creating new SpeechRecognizer instance")
      speechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(appContext).apply {
          setRecognitionListener(
            object : RecognitionListener {
              override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Speech recognizer ready for speech")
              }

              override fun onBeginningOfSpeech() {
                Log.d(TAG, "User started speaking")
              }

              override fun onRmsChanged(rmsdB: Float) = Unit

              override fun onBufferReceived(buffer: ByteArray?) = Unit

              override fun onEndOfSpeech() {
                Log.d(TAG, "User stopped speaking")
              }

              override fun onError(error: Int) {
                Log.i(TAG, "Speech recognition error: $error (${getErrorDescription(error)})")
                scheduleRestart()
              }

              override fun onResults(results: Bundle?) {
                Log.d(TAG, "Speech recognition results received")
                handleRecognizedText(results)
                scheduleRestart()
              }

              override fun onPartialResults(partialResults: Bundle?) {
                Log.d(TAG, "Partial speech recognition results")
                handleRecognizedText(partialResults)
              }

              override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }
          )
        }
      Log.d(TAG, "SpeechRecognizer created successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create SpeechRecognizer: ${e.message}", e)
      speechRecognizer = null
    }
  }

  private fun getErrorDescription(errorCode: Int): String {
    return when (errorCode) {
      SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
      SpeechRecognizer.ERROR_CLIENT -> "Client side error"
      SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
      SpeechRecognizer.ERROR_NETWORK -> "Network error"
      SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
      SpeechRecognizer.ERROR_NO_MATCH -> "No speech input detected"
      SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
      SpeechRecognizer.ERROR_SERVER -> "Server error"
      SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
      else -> "Unknown error ($errorCode)"
    }
  }

  private fun listen() {
    if (!isListening) {
      Log.d(TAG, "Not listening, skipping listen()")
      return
    }

    if (speechRecognizer == null) {
      Log.w(TAG, "SpeechRecognizer is null, cannot start listening")
      return
    }

    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
          RecognizerIntent.EXTRA_LANGUAGE_MODEL,
          RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          // Hints the recognizer toward the command phrases; ignored by engines without support.
          putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(commands))
        }
      }

    try {
      Log.i(TAG, "Calling SpeechRecognizer.startListening")
      speechRecognizer?.startListening(intent)
    } catch (e: RuntimeException) {
      Log.e(TAG, "Failed to start speech recognition: ${e.message}", e)
      scheduleRestart()
    }
  }

  private fun scheduleRestart() {
    if (!isListening) return
    Log.i(TAG, "Scheduling speech recognizer restart")
    mainHandler.postDelayed({ listen() }, RESTART_DELAY_MS)
  }

  private fun handleRecognizedText(results: Bundle?) {
    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    if (matches.isEmpty()) {
      Log.d(TAG, "No speech recognition results available")
      return
    }

    val recognizedText = matches.first()
    Log.i(TAG, "Recognized speech: '$recognizedText'")
    Log.d(TAG, "All candidates: $matches")

    // Report the recognized text to the callback
    onSpeechRecognized(recognizedText)

    val command = bestMatchingCommand(matches)
    if (command == null) {
      Log.d(TAG, "Speech did not match any command: $commands")
      return
    }

    val now = System.currentTimeMillis()
    if (now - lastCommandDetectedAtMs < COMMAND_DEBOUNCE_MS) {
      Log.d(TAG, "Command detected too recently, ignoring (debounce)")
      return
    }

    lastCommandDetectedAtMs = now
    Log.i(TAG, "Voice command detected: $command")
    onCommandDetected(command)
  }

  private fun routeAudioToGlassesIfAvailable() {
    val scoDevice =
      audioManager.availableCommunicationDevices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
      }
    if (scoDevice == null) {
      Log.i(TAG, "No Bluetooth SCO device found; using default microphone for speech recognition")
      return
    }
    if (!audioManager.setCommunicationDevice(scoDevice)) {
      Log.w(TAG, "Failed to route speech recognition audio to Bluetooth SCO device")
    }
  }

  private fun String.normalizeForCommandMatching(): String =
    lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

  /**
   * Returns the command closest to any recognizer candidate, or null if none is close enough.
   *
   * Each command is compared against the whole utterance with spaces removed, using a sliding
   * window edit distance. This tolerates mis-segmentation such as "metta scan" or "metal scan"
   * while still rejecting unrelated speech. Picking the lowest relative distance across all
   * commands keeps "meta scan" and "meta question" from being confused.
   */
  private fun bestMatchingCommand(candidates: List<String>): String? {
    var best: String? = null
    var bestScore = Double.MAX_VALUE
    for (candidate in candidates) {
      val collapsedText = candidate.normalizeForCommandMatching().replace(" ", "")
      for ((command, collapsedCommand) in collapsedCommands) {
        val distance = phraseDistance(collapsedText, collapsedCommand) ?: continue
        val score = distance.toDouble() / collapsedCommand.length
        if (score < bestScore) {
          bestScore = score
          best = command
        }
      }
    }
    return best
  }

  /** Smallest edit distance of [collapsedCommand] against any window of [collapsedText], if within tolerance. */
  private fun phraseDistance(collapsedText: String, collapsedCommand: String): Int? {
    if (collapsedCommand.isEmpty() || collapsedText.length < collapsedCommand.length - 2) {
      return null
    }
    if (collapsedText.contains(collapsedCommand)) return 0

    val tolerance =
      maxOf(
        FUZZY_PHRASE_MIN_TOLERANCE,
        (collapsedCommand.length * FUZZY_PHRASE_TOLERANCE_RATIO).toInt(),
      )
    val minWindow = (collapsedCommand.length - 2).coerceAtLeast(1)
    val maxWindow = collapsedCommand.length + 2
    var best: Int? = null

    for (windowSize in minWindow..maxWindow) {
      if (windowSize > collapsedText.length) continue
      for (start in 0..(collapsedText.length - windowSize)) {
        val window = collapsedText.substring(start, start + windowSize)
        val distance = levenshteinDistance(window, collapsedCommand)
        if (distance <= tolerance && (best == null || distance < best)) {
          best = distance
        }
      }
    }
    return best
  }

  /** Standard iterative Levenshtein edit distance, O(a.length * b.length), no allocation per cell. */
  private fun levenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    var previousRow = IntArray(b.length + 1) { it }
    var currentRow = IntArray(b.length + 1)

    for (i in 1..a.length) {
      currentRow[0] = i
      for (j in 1..b.length) {
        val substitutionCost = if (a[i - 1] == b[j - 1]) 0 else 1
        currentRow[j] =
          minOf(
            currentRow[j - 1] + 1, // insertion
            previousRow[j] + 1, // deletion
            previousRow[j - 1] + substitutionCost, // substitution
          )
      }
      val swap = previousRow
      previousRow = currentRow
      currentRow = swap
    }
    return previousRow[b.length]
  }
}