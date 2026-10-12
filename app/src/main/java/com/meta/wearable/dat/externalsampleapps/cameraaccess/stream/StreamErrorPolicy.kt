package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import com.meta.wearable.dat.camera.types.StreamError

/**
 * Decides which stream errors end the session.
 *
 * Warnings such as THERMAL_HOT, BATTERY_LOW, PEAK_POWER_LIMIT and TIMEOUT are reported while the
 * glasses keep streaming (or throttle). Tearing the session down on them is what made sessions end
 * abruptly after a few minutes of warm-up. If the glasses really do stop, the stream state reaches
 * CLOSED and the state collector navigates back.
 */
internal object StreamErrorPolicy {
  fun isTerminal(error: StreamError): Boolean =
      when (error) {
        StreamError.CRITICAL_STREAM_ERROR,
        StreamError.HINGE_CLOSED,
        StreamError.PERMISSIONS_DENIED,
        StreamError.THERMAL_EMERGENCY -> true
        StreamError.STREAM_ERROR,
        StreamError.THERMAL_HOT,
        StreamError.BATTERY_LOW,
        StreamError.PEAK_POWER_LIMIT,
        StreamError.TIMEOUT -> false
      }
}
