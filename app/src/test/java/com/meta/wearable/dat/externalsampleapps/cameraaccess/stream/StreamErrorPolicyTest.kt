package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import com.meta.wearable.dat.camera.types.StreamError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamErrorPolicyTest {
  @Test
  fun warningsKeepTheSessionAlive() {
    assertFalse(StreamErrorPolicy.isTerminal(StreamError.STREAM_ERROR))
    assertFalse(StreamErrorPolicy.isTerminal(StreamError.THERMAL_HOT))
    assertFalse(StreamErrorPolicy.isTerminal(StreamError.BATTERY_LOW))
    assertFalse(StreamErrorPolicy.isTerminal(StreamError.PEAK_POWER_LIMIT))
    assertFalse(StreamErrorPolicy.isTerminal(StreamError.TIMEOUT))
  }

  @Test
  fun hardFailuresEndTheSession() {
    assertTrue(StreamErrorPolicy.isTerminal(StreamError.CRITICAL_STREAM_ERROR))
    assertTrue(StreamErrorPolicy.isTerminal(StreamError.HINGE_CLOSED))
    assertTrue(StreamErrorPolicy.isTerminal(StreamError.PERMISSIONS_DENIED))
    assertTrue(StreamErrorPolicy.isTerminal(StreamError.THERMAL_EMERGENCY))
  }
}
