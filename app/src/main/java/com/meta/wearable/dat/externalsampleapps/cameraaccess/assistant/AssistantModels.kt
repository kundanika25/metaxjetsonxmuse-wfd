/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant

import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer

enum class AssistantMode {
  PHASE_5A,
  PHASE_5B,
}

enum class ConversationRole {
  CUSTOMER,
  ASSISTANT,
}

data class ConversationTurn(
  val role: ConversationRole,
  val text: String,
  val timestampMs: Long = System.currentTimeMillis(),
)

data class AssistantUiState(
  val customers: List<Customer> = emptyList(),
  // Null until a face is matched or the RM swipes to a customer, so a stranger never inherits
  // another customer's card.
  val selectedCustomerIndex: Int? = null,
  val recognizedCustomer: Customer? = null,
  val mode: AssistantMode = AssistantMode.PHASE_5B,
  val isListening: Boolean = false,
  val speechStatus: String? = null,
  val partialTranscript: String? = null,
  val lastQuestion: String? = null,
  val answer: String? = null,
  val isAnswering: Boolean = false,
  val error: String? = null,
  val conversation: List<ConversationTurn> = emptyList(),
) {
  val customer: Customer?
    get() = recognizedCustomer ?: selectedCustomerIndex?.let { customers.getOrNull(it) }

  /**
   * Points the assistant at a different customer (or none). When the customer actually changes,
   * the previous customer's question, answer and history are dropped so they are neither shown
   * nor sent to the model for the next person.
   */
  fun withCustomerSelection(index: Int?, recognized: Customer?): AssistantUiState {
    val next = copy(selectedCustomerIndex = index, recognizedCustomer = recognized)
    if (next.customer?.id == customer?.id) return next
    return next.copy(
        isListening = false,
        speechStatus = null,
        partialTranscript = null,
        lastQuestion = null,
        answer = null,
        isAnswering = false,
        error = null,
        conversation = emptyList(),
    )
  }
}
