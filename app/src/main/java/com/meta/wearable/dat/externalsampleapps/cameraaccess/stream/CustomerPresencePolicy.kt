package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

/**
 * Decides when a matched customer has left the camera view. Misses (head turn, blur, nobody in
 * view, or an unknown face) keep the match until the customer has gone unrecognized for
 * [DEPARTURE_TIMEOUT_MS]; a different known customer replaces the match immediately.
 */
internal object CustomerPresencePolicy {
  const val DEPARTURE_TIMEOUT_MS = 5_000L
  const val SEARCH_INTERVAL_MS = 1_000L
  // Short enough that a customer swap in front of the RM is noticed within ~2s. Recognition is a
  // single ML Kit detect + MobileFaceNet pass on the phone, so this does not load the glasses.
  const val MATCHED_RECHECK_INTERVAL_MS = 2_000L

  fun keepsMatchedCustomer(msSinceLastRecognized: Long): Boolean =
      msSinceLastRecognized < DEPARTURE_TIMEOUT_MS

  // Recheck quickly after a miss so a departure is confirmed close to the timeout.
  fun recognitionIntervalMs(hasMatchedCustomer: Boolean, consecutiveMisses: Int): Long =
      if (hasMatchedCustomer && consecutiveMisses == 0) {
        MATCHED_RECHECK_INTERVAL_MS
      } else {
        SEARCH_INTERVAL_MS
      }
}
