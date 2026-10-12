/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer
import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.CustomerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
  private val customerRepository = CustomerRepository(application)
  private val assistantService = GemmaAssistantService(application)
  private var speechListener: AssistantSpeechListener? = null
  private var selectedCustomerId: String? = null
  // Bumped whenever the customer or session changes; answers from an older session are dropped.
  private var sessionGeneration = 0L

  var onAnswerReady: (suspend (String) -> Unit)? = null

  private val _uiState = MutableStateFlow(AssistantUiState())
  val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      val customers = customerRepository.loadCustomers()
      val selectedIndex =
          selectedCustomerId?.let { customerId ->
            customers.indexOfFirst { it.id == customerId }.takeIf { it >= 0 }
          }
      _uiState.update { it.copy(customers = customers, selectedCustomerIndex = selectedIndex) }
    }
  }

  fun selectCustomer(customer: Customer) {
    selectedCustomerId = customer.id
    val index =
        _uiState.value.customers.indexOfFirst { it.id == customer.id }.takeIf { it >= 0 }
    changeCustomer(index, customer)
  }

  /** The recognized customer left the camera view; manual (swiped) selections are kept. */
  fun clearRecognizedCustomer() {
    if (_uiState.value.recognizedCustomer == null) return
    selectedCustomerId = null
    changeCustomer(index = null, recognized = null)
  }

  fun selectCustomerOffset(offset: Int) {
    val state = _uiState.value
    if (state.customers.isEmpty()) return
    val start = state.selectedCustomerIndex ?: if (offset > 0) -1 else 0
    val nextIndex = (start + offset).floorMod(state.customers.size)
    selectedCustomerId = state.customers[nextIndex].id
    changeCustomer(nextIndex, recognized = null)
  }

  private fun changeCustomer(index: Int?, recognized: Customer?) {
    val current = _uiState.value
    val next = current.withCustomerSelection(index, recognized)
    if (next.customer?.id != current.customer?.id) {
      speechListener?.stop(cancel = true)
      speechListener = null
      sessionGeneration++
    }
    _uiState.value = next
  }

  fun setMode(mode: AssistantMode) {
    _uiState.update { it.copy(mode = mode) }
  }

  fun startListening() {
    val customer = _uiState.value.customer
    if (customer == null) {
      _uiState.update { it.copy(error = "Recognize or select a customer before asking") }
      return
    }
    speechListener?.stop(cancel = true)
    speechListener =
        AssistantSpeechListener(
            context = getApplication<Application>(),
            onReady = {
              _uiState.update {
                it.copy(
                    isListening = true,
                    speechStatus = "Listening to customer...",
                    partialTranscript = null,
                    error = null,
                )
              }
            },
            onPartialTranscript = { transcript ->
              _uiState.update {
                it.copy(
                    isListening = true,
                    speechStatus = "Capturing question",
                    partialTranscript = transcript,
                    error = null,
                )
              }
            },
            onFinalTranscript = { transcript ->
              speechListener = null
              val cleanedTranscript = transcript.trim()
              _uiState.update {
                it.copy(
                    isListening = false,
                    isAnswering = cleanedTranscript.isNotBlank(),
                    speechStatus = "Heard customer question",
                    partialTranscript = cleanedTranscript,
                    lastQuestion = cleanedTranscript,
                    error = null,
                )
              }
              submitQuestion(cleanedTranscript)
            },
            onError = { message ->
              speechListener = null
              _uiState.update {
                it.copy(
                    isListening = false,
                    speechStatus = null,
                    error = message,
                )
              }
            },
        )
    speechListener?.start()
  }

  fun stopListeningAndUsePartial() {
    speechListener?.stop(cancel = false)
  }

  fun cancelListening() {
    speechListener?.stop(cancel = true)
    speechListener = null
    _uiState.update { it.copy(isListening = false, speechStatus = null) }
  }

  fun endSession() {
    cancelListening()
    sessionGeneration++
    _uiState.update {
      it.copy(
          conversation = emptyList(),
          partialTranscript = null,
          lastQuestion = null,
          answer = null,
          isAnswering = false,
          error = null,
          speechStatus = "Session cleared",
      )
    }
  }

  private fun submitQuestion(question: String) {
    val cleanedQuestion = question.trim()
    if (cleanedQuestion.isBlank()) return
    val customer = _uiState.value.customer ?: return
    val mode = _uiState.value.mode
    val conversation = _uiState.value.conversation
    val generation = sessionGeneration
    _uiState.update {
      it.copy(
          isAnswering = true,
          error = null,
          answer = null,
          conversation = it.conversation + ConversationTurn(ConversationRole.CUSTOMER, cleanedQuestion),
      )
    }

    viewModelScope.launch {
      try {
        val answer =
            assistantService.answerCustomerQuestion(
                customer = customer,
                question = cleanedQuestion,
                mode = mode,
                conversation = if (mode == AssistantMode.PHASE_5B) conversation else emptyList(),
                onPartialText = { partialAnswer ->
                  if (generation == sessionGeneration) {
                    _uiState.update { it.copy(answer = partialAnswer) }
                  }
                },
            )
        // The customer changed or the session was cleared while this answer was generated.
        if (generation != sessionGeneration) return@launch
        _uiState.update {
          it.copy(
              isAnswering = false,
              answer = answer,
              conversation = it.conversation + ConversationTurn(ConversationRole.ASSISTANT, answer),
          )
        }
        onAnswerReady?.invoke(answer)
      } catch (e: Exception) {
        if (generation != sessionGeneration) return@launch
        _uiState.update {
          it.copy(
              isAnswering = false,
              error = e.message ?: "Assistant request failed",
          )
        }
      }
    }
  }

  override fun onCleared() {
    speechListener?.stop(cancel = true)
    speechListener = null
    super.onCleared()
  }

  private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor

  class Factory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(AssistantViewModel::class.java)) {
        return AssistantViewModel(application) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
  }
}
