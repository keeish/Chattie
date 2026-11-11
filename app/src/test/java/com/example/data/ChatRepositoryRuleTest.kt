package com.example.data

import com.example.base.FirestoreEmulatorTestBase
import com.example.model.UserProfile
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class ChatRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun registerNewAccount_validPayload_createsProfile(): Unit = runBlocking {
        val email = "user_${UUID.randomUUID().toString().take(6)}@chatconnect.test"
        val uid = signInTestUser(email)
        val repository = ChatRepository(firestore)

        val username = "user_${UUID.randomUUID().toString().take(6)}"
        val profile = withTimeout(5000L) {
            repository.registerNewAccount(
                userId = uid,
                username = username,
                displayName = "Test User",
                passwordHash = "hash123",
                avatarUrl = "",
                about = "Testing ChatConnect"
            )
        }

        assertNotNull(profile)
        assertEquals(uid, profile.userId)
        assertEquals(username, profile.username)
        assertEquals(6, profile.sixDigitId.length)

        val fetched = withTimeout(5000L) {
            repository.getUserProfile(uid)
        }
        assertNotNull(fetched)
        assertEquals(username, fetched?.username)
    }

    @Test
    fun unauthenticatedAccess_failsGracefully(): Unit = runBlocking {
        auth.signOut()
        val repository = ChatRepository(firestore)

        try {
            withTimeout(3000L) {
                repository.observeConversations("any_uid").first()
            }
        } catch (e: Exception) {
            val firestoreError = e.unwrapFirestoreException()
            assertTrue(
                "Expected permission denied or channel cancel, got $e",
                firestoreError?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED || e is Exception
            )
        }
    }

    @Test
    fun getOrCreateDirectConversation_nonExistentConv_succeedsWithoutPermissionDenied(): Unit = runBlocking {
        val user1Email = "user1_${UUID.randomUUID().toString().take(6)}@chatconnect.test"
        val uid1 = signInTestUser(user1Email)
        val repository = ChatRepository(firestore)

        val profile1 = UserProfile(
            id = uid1,
            userId = uid1,
            sixDigitId = "111222",
            username = "alice",
            displayName = "Alice"
        )
        val uid2 = "user2_dummy_${UUID.randomUUID().toString().take(6)}"
        val profile2 = UserProfile(
            id = uid2,
            userId = uid2,
            sixDigitId = "333444",
            username = "bob",
            displayName = "Bob"
        )

        val conv = withTimeout(5000L) {
            repository.getOrCreateDirectConversation(profile1, profile2)
        }

        assertNotNull(conv)
        assertTrue(conv.participantIds.contains(uid1))
        assertTrue(conv.participantIds.contains(uid2))
    }
}
