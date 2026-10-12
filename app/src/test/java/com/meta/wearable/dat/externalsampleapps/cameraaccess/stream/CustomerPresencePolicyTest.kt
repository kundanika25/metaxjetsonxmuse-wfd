package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerPresencePolicyTest {
  @Test
  fun keepsCustomerUntilUnrecognizedForFiveSeconds() {
    assertTrue(CustomerPresencePolicy.keepsMatchedCustomer(msSinceLastRecognized = 4_999L))
    assertFalse(CustomerPresencePolicy.keepsMatchedCustomer(msSinceLastRecognized = 5_000L))
  }

  @Test
  fun rechecksQuicklyAfterAMissWhileACustomerIsMatched() {
    assertEquals(
        CustomerPresencePolicy.MATCHED_RECHECK_INTERVAL_MS,
        CustomerPresencePolicy.recognitionIntervalMs(hasMatchedCustomer = true, consecutiveMisses = 0),
    )
    assertEquals(
        CustomerPresencePolicy.SEARCH_INTERVAL_MS,
        CustomerPresencePolicy.recognitionIntervalMs(hasMatchedCustomer = true, consecutiveMisses = 1),
    )
    assertEquals(
        CustomerPresencePolicy.SEARCH_INTERVAL_MS,
        CustomerPresencePolicy.recognitionIntervalMs(hasMatchedCustomer = false, consecutiveMisses = 0),
    )
  }
}
