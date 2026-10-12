package com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant

import com.meta.wearable.dat.externalsampleapps.cameraaccess.data.Customer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantUiStateTest {
  private val alice = customer("CUST001", "Alice")
  private val bob = customer("CUST002", "Bob")

  private val aliceSession =
      AssistantUiState(
          customers = listOf(alice, bob),
          selectedCustomerIndex = 0,
          recognizedCustomer = alice,
          lastQuestion = "What is my balance?",
          answer = "Alice's balance is 100",
          isAnswering = true,
          isListening = true,
          error = "old error",
          conversation = listOf(ConversationTurn(ConversationRole.CUSTOMER, "What is my balance?")),
      )

  @Test
  fun noCustomerIsShownBeforeRecognitionOrSelection() {
    assertNull(AssistantUiState(customers = listOf(alice, bob)).customer)
  }

  @Test
  fun switchingToAnotherCustomerClearsThePreviousSession() {
    val next = aliceSession.withCustomerSelection(index = 1, recognized = bob)

    assertEquals("Bob", next.customer?.name)
    assertNull(next.lastQuestion)
    assertNull(next.answer)
    assertNull(next.error)
    assertFalse(next.isAnswering)
    assertFalse(next.isListening)
    assertTrue(next.conversation.isEmpty())
  }

  @Test
  fun customerLeavingClearsNameAndSession() {
    val next = aliceSession.withCustomerSelection(index = null, recognized = null)

    assertNull(next.customer)
    assertTrue(next.conversation.isEmpty())
    assertNull(next.answer)
  }

  @Test
  fun reselectingTheSameCustomerKeepsTheSession() {
    val next = aliceSession.withCustomerSelection(index = 0, recognized = alice)

    assertEquals(aliceSession.conversation, next.conversation)
    assertEquals(aliceSession.answer, next.answer)
    assertTrue(next.isAnswering)
  }

  private fun customer(id: String, name: String) =
      Customer(
          id = id,
          name = name,
          phone = "000",
          profile = "profile",
          faceImage = "$id.jpg",
          lastVisit = "2026-01-01",
      )
}
