/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R

/**
 * Foreground service that keeps the glasses session alive while streaming.
 *
 * Without it, Android may throttle or kill the process once the screen turns off or the app leaves
 * the foreground, which ends the glasses session. Mirrors the StreamingService in Meta's
 * CameraAccess sample: a connectedDevice-typed foreground service plus a partial wake lock.
 */
class StreamingService : Service() {

  companion object {
    private const val TAG = "CameraAccess:StreamingService"
    private const val CHANNEL_ID = "streaming_channel"
    private const val NOTIFICATION_ID = 1001
    private const val WAKELOCK_TAG = "CameraAccess::StreamingWakeLock"
    // Safety backstop for a leaked lock; stop() is the primary release.
    private const val WAKELOCK_TIMEOUT_MS = 60L * 60L * 1000L
    private const val ACTION_STOP =
        "com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.STOP"

    fun start(context: Context) {
      runCatching {
            context.startForegroundService(
                Intent(context, StreamingService::class.java).setPackage(context.packageName)
            )
          }
          .onFailure { Log.w(TAG, "Unable to start streaming foreground service", it) }
    }

    fun stop(context: Context) {
      // Route the stop through onStartCommand rather than stopService(): once
      // startForegroundService() is called, startForeground() must follow, and stopping before
      // that crashes with ForegroundServiceDidNotStartInTimeException.
      runCatching {
            context.startForegroundService(
                Intent(context, StreamingService::class.java)
                    .setPackage(context.packageName)
                    .setAction(ACTION_STOP)
            )
          }
          .onFailure { Log.w(TAG, "Unable to stop streaming foreground service", it) }
    }
  }

  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Always enter the foreground first, even for a STOP that raced ahead of a pending start, so
    // the startForegroundService() contract is satisfied.
    try {
      startForeground(
          NOTIFICATION_ID,
          createNotification(),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to enter foreground; stopping service", e)
      stopSelf()
      return START_NOT_STICKY
    }

    if (intent?.action == ACTION_STOP) {
      Log.d(TAG, "Service stopping")
      releaseWakeLock()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    Log.d(TAG, "Service started")
    acquireWakeLock()
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    releaseWakeLock()
    super.onDestroy()
  }

  private fun createNotificationChannel() {
    val channel =
        NotificationChannel(
                CHANNEL_ID,
                getString(R.string.streaming_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            .apply { setShowBadge(false) }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  private fun createNotification(): Notification {
    val pendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
              flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.streaming_notification_title))
        .setContentText(getString(R.string.streaming_notification_body))
        .setSmallIcon(R.drawable.camera_icon)
        .setOngoing(true)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()
  }

  private fun acquireWakeLock() {
    if (wakeLock != null) return
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock =
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
          acquire(WAKELOCK_TIMEOUT_MS)
        }
  }

  private fun releaseWakeLock() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }
}
