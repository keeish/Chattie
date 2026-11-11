package com.example.data

import android.content.Context
import android.util.Log
import com.example.R
import com.example.model.AccountIdRecord
import com.example.model.CallSession
import com.example.model.ChatMessage
import com.example.model.ContactItem
import com.example.model.Conversation
import com.example.model.UserProfile
import com.example.model.UsernameRecord
import com.example.util.OperationType
import com.example.util.PasswordSecurity
import com.example.util.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.UUID

sealed interface LoginVerificationResult {
    data class Success(val profile: UserProfile) : LoginVerificationResult
    data class AccountNotFound(val sixDigitId: String) : LoginVerificationResult
    data object IncorrectPassword : LoginVerificationResult
    data class Error(val message: String) : LoginVerificationResult
}

class ChatRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        context.applicationContext.getString(R.string.firestore_database_id).let { dbId ->
            if (dbId.isEmpty() || dbId == "(default)") FirebaseFirestore.getInstance()
            else FirebaseFirestore.getInstance(dbId)
        }
    )


    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    // ----------------------------------------------------
    // User Account & Profile Operations
    // ----------------------------------------------------

    suspend fun getUserProfile(userId: String): UserProfile? {
        val docRef = db.collection("users").document(userId)
        return try {
            val snapshot = docRef.get().await()
            if (snapshot.exists()) snapshot.toObject(UserProfile::class.java) else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            null
        }
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> {
        val docRef = db.collection("users").document(userId)
        return docRef.snapshots()
            .map { it.toObject(UserProfile::class.java) }
            .catch { e ->
                if (e is CancellationException) throw e
                handleFirestoreError(e, OperationType.GET, docRef.path)
                emit(null)
            }
    }

    suspend fun isUsernameAvailable(username: String): Boolean {
        val clean = username.trim().lowercase()
        val docRef = db.collection("usernames").document(clean)
        return try {
            val snapshot = docRef.get().await()
            !snapshot.exists()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            throw e
        }
    }

    suspend fun findAccountBySixDigitId(sixDigitId: String): AccountIdRecord? {
        val clean = sixDigitId.trim()
        val docRef = db.collection("accountIds").document(clean)
        return try {
            val snapshot = docRef.get().await()
            if (snapshot.exists()) snapshot.toObject(AccountIdRecord::class.java) else null
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            null
        }
    }

    suspend fun allocateUniqueSixDigitId(): String {
        val random = SecureRandom()
        for (attempt in 1..20) {
            val candidate = String.format("%06d", random.nextInt(1000000))
            val doc = db.collection("accountIds").document(candidate).get().await()
            if (!doc.exists()) {
                return candidate
            }
        }
        return String.format("%06d", (System.currentTimeMillis() % 1000000).toInt())
    }

    suspend fun registerNewAccount(
        userId: String,
        username: String,
        displayName: String,
        passwordHash: String,
        avatarUrl: String,
        about: String
    ): UserProfile {
        val cleanUsername = username.trim().lowercase()
        val sixDigitId = allocateUniqueSixDigitId()

        val batch = db.batch()

        val userDocRef = db.collection("users").document(userId)
        val accountIdDocRef = db.collection("accountIds").document(sixDigitId)
        val usernameDocRef = db.collection("usernames").document(cleanUsername)

        val userPayload = mapOf(
            "userId" to userId,
            "sixDigitId" to sixDigitId,
            "username" to cleanUsername,
            "displayName" to displayName.trim(),
            "about" to about.trim(),
            "avatarUrl" to avatarUrl,
            "passwordHash" to passwordHash,
            "online" to true,
            "lastSeen" to FieldValue.serverTimestamp(),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        val accountIdPayload = mapOf(
            "sixDigitId" to sixDigitId,
            "userId" to userId,
            "username" to cleanUsername,
            "displayName" to displayName.trim(),
            "createdAt" to FieldValue.serverTimestamp()
        )

        val usernamePayload = mapOf(
            "username" to cleanUsername,
            "userId" to userId,
            "createdAt" to FieldValue.serverTimestamp()
        )

        batch.set(userDocRef, userPayload)
        batch.set(accountIdDocRef, accountIdPayload)
        batch.set(usernameDocRef, usernamePayload)

        try {
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, userDocRef.path)
            throw e
        }

        return UserProfile(
            id = userId,
            userId = userId,
            sixDigitId = sixDigitId,
            username = cleanUsername,
            displayName = displayName.trim(),
            about = about.trim(),
            avatarUrl = avatarUrl,
            passwordHash = passwordHash,
            online = true
        )
    }

    suspend fun verifySixDigitLoginResult(sixDigitId: String, passwordInput: String): LoginVerificationResult {
        val clean = sixDigitId.trim()
        val account = try {
            val docRef = db.collection("accountIds").document(clean)
            val snapshot = docRef.get().await()
            if (snapshot.exists()) snapshot.toObject(AccountIdRecord::class.java) else null
        } catch (e: Exception) {
            return LoginVerificationResult.Error("Database lookup error: ${e.localizedMessage}")
        }

        if (account == null) {
            return LoginVerificationResult.AccountNotFound(clean)
        }

        val profile = try {
            val docRef = db.collection("users").document(account.userId)
            val snapshot = docRef.get().await()
            if (snapshot.exists()) snapshot.toObject(UserProfile::class.java) else null
        } catch (e: Exception) {
            return LoginVerificationResult.Error("Profile fetch error: ${e.localizedMessage}")
        }

        if (profile == null) {
            return LoginVerificationResult.AccountNotFound(clean)
        }

        if (!PasswordSecurity.verifyPassword(passwordInput, profile.passwordHash)) {
            return LoginVerificationResult.IncorrectPassword
        }

        return LoginVerificationResult.Success(profile)
    }

    suspend fun verifySixDigitLogin(sixDigitId: String, passwordInput: String): UserProfile? {
        val res = verifySixDigitLoginResult(sixDigitId, passwordInput)
        return (res as? LoginVerificationResult.Success)?.profile
    }

    suspend fun updateOnlineStatus(userId: String, isOnline: Boolean) {
        val docRef = db.collection("users").document(userId)
        try {
            docRef.update(
                mapOf(
                    "online" to isOnline,
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
        }
    }

    suspend fun updateUserProfile(userId: String, displayName: String, about: String, avatarUrl: String) {
        val docRef = db.collection("users").document(userId)
        try {
            docRef.update(
                mapOf(
                    "displayName" to displayName.trim(),
                    "about" to about.trim(),
                    "avatarUrl" to avatarUrl,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }

        // Propagate avatar and name to all conversations so other users immediately see the new avatar
        try {
            val convs = db.collection("conversations")
                .whereArrayContains("participantIds", userId)
                .get()
                .await()
            if (!convs.isEmpty) {
                val batch = db.batch()
                for (doc in convs.documents) {
                    batch.update(
                        doc.reference,
                        mapOf(
                            "participantAvatars.$userId" to avatarUrl,
                            "participantNames.$userId" to displayName.trim(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.commit().await()
            }
        } catch (e: Exception) {
            Log.w("ChatRepository", "Non-fatal: could not sync avatar to conversations: ${e.message}")
        }
    }

    suspend fun changePassword(userId: String, newPasswordHash: String) {
        val docRef = db.collection("users").document(userId)
        try {
            docRef.update(
                mapOf(
                    "passwordHash" to newPasswordHash,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    // ----------------------------------------------------
    // Contacts Operations
    // ----------------------------------------------------

    fun observeContacts(userId: String): Flow<List<ContactItem>> {
        val colRef = db.collection("users").document(userId).collection("contacts")
        return colRef.snapshots()
            .map { snapshot -> snapshot.toObjects(ContactItem::class.java) }
            .catch { e ->
                if (e is CancellationException) throw e
                handleFirestoreError(e, OperationType.LIST, colRef.path)
                emit(emptyList())
            }
    }

    suspend fun addContact(userId: String, contactUser: UserProfile) {
        val contactId = contactUser.userId
        val docRef = db.collection("users").document(userId).collection("contacts").document(contactId)
        val payload = mapOf(
            "contactId" to contactId,
            "userId" to userId,
            "contactUserId" to contactUser.userId,
            "sixDigitId" to contactUser.sixDigitId,
            "displayName" to contactUser.displayName,
            "username" to contactUser.username,
            "avatarUrl" to contactUser.avatarUrl,
            "addedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    suspend fun removeContact(userId: String, contactId: String) {
        val docRef = db.collection("users").document(userId).collection("contacts").document(contactId)
        try {
            docRef.delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    // ----------------------------------------------------
    // Conversations & Groups Operations
    // ----------------------------------------------------

    fun observeConversations(userId: String): Flow<List<Conversation>> {
        val query = db.collection("conversations")
            .whereArrayContains("participantIds", userId)

        return query.snapshots()
            .map { snapshot ->
                snapshot.toObjects(Conversation::class.java)
                    .sortedByDescending { it.lastMessageTime ?: it.createdAt }
            }
            .catch { e ->
                if (e is CancellationException) throw e
                handleFirestoreError(e, OperationType.LIST, "conversations")
                emit(emptyList())
            }
    }

    suspend fun getOrCreateDirectConversation(currentUser: UserProfile, otherUser: UserProfile): Conversation {
        val sortedPair = listOf(currentUser.userId, otherUser.userId).sorted()
        val convId = "direct_${sortedPair[0]}_${sortedPair[1]}"
        val docRef = db.collection("conversations").document(convId)

        val existing = try {
            val snapshot = docRef.get().await()
            if (snapshot.exists()) snapshot else null
        } catch (_: Exception) {
            null
        }

        if (existing != null && existing.exists()) {
            val conv = existing.toObject(Conversation::class.java) ?: Conversation(id = convId, conversationId = convId)
            // Ensure both participants' latest avatars and names are populated
            if (conv.participantAvatars[currentUser.userId] != currentUser.avatarUrl ||
                conv.participantAvatars[otherUser.userId] != otherUser.avatarUrl ||
                conv.participantNames[currentUser.userId] != currentUser.displayName ||
                conv.participantNames[otherUser.userId] != otherUser.displayName) {
                try {
                    docRef.update(
                        mapOf(
                            "participantAvatars.${currentUser.userId}" to currentUser.avatarUrl,
                            "participantAvatars.${otherUser.userId}" to otherUser.avatarUrl,
                            "participantNames.${currentUser.userId}" to currentUser.displayName,
                            "participantNames.${otherUser.userId}" to otherUser.displayName,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                } catch (_: Exception) {}
            }
            return conv
        }

        val payload = mapOf(
            "conversationId" to convId,
            "isGroup" to false,
            "group" to false,
            "name" to otherUser.displayName,
            "description" to "",
            "avatarUrl" to otherUser.avatarUrl,
            "creatorId" to currentUser.userId,
            "adminIds" to listOf(currentUser.userId, otherUser.userId),
            "participantIds" to listOf(currentUser.userId, otherUser.userId),
            "participantNames" to mapOf(
                currentUser.userId to currentUser.displayName,
                otherUser.userId to otherUser.displayName
            ),
            "participantAvatars" to mapOf(
                currentUser.userId to currentUser.avatarUrl,
                otherUser.userId to otherUser.avatarUrl
            ),
            "lastMessage" to "Started conversation",
            "lastSenderName" to currentUser.displayName,
            "lastMessageTime" to FieldValue.serverTimestamp(),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        try {
            docRef.set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }

        return Conversation(
            id = convId,
            conversationId = convId,
            isGroup = false,
            name = otherUser.displayName,
            avatarUrl = otherUser.avatarUrl,
            creatorId = currentUser.userId,
            participantIds = listOf(currentUser.userId, otherUser.userId),
            participantNames = mapOf(
                currentUser.userId to currentUser.displayName,
                otherUser.userId to otherUser.displayName
            ),
            participantAvatars = mapOf(
                currentUser.userId to currentUser.avatarUrl,
                otherUser.userId to otherUser.avatarUrl
            )
        )
    }

    suspend fun createGroupConversation(
        creator: UserProfile,
        groupName: String,
        description: String,
        avatarUrl: String,
        memberIds: List<String>
    ): Conversation {
        val convId = "group_${UUID.randomUUID().toString().take(12)}"
        val docRef = db.collection("conversations").document(convId)

        val allParticipants = (listOf(creator.userId) + memberIds).distinct()
        val payload = mapOf(
            "conversationId" to convId,
            "isGroup" to true,
            "group" to true,
            "name" to groupName.trim(),
            "description" to description.trim(),
            "avatarUrl" to avatarUrl,
            "creatorId" to creator.userId,
            "adminIds" to listOf(creator.userId),
            "participantIds" to allParticipants,
            "lastMessage" to "Group created by ${creator.displayName}",
            "lastSenderName" to creator.displayName,
            "lastMessageTime" to FieldValue.serverTimestamp(),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        try {
            docRef.set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }

        return Conversation(
            id = convId,
            conversationId = convId,
            isGroup = true,
            name = groupName.trim(),
            description = description.trim(),
            avatarUrl = avatarUrl,
            creatorId = creator.userId,
            adminIds = listOf(creator.userId),
            participantIds = allParticipants
        )
    }

    suspend fun updateGroupDetails(conversationId: String, name: String, description: String, avatarUrl: String) {
        val docRef = db.collection("conversations").document(conversationId)
        try {
            docRef.update(
                mapOf(
                    "name" to name.trim(),
                    "description" to description.trim(),
                    "avatarUrl" to avatarUrl,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun addGroupMember(conversationId: String, newMemberId: String) {
        val docRef = db.collection("conversations").document(conversationId)
        try {
            docRef.update("participantIds", FieldValue.arrayUnion(newMemberId)).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun removeGroupMember(conversationId: String, memberId: String) {
        val docRef = db.collection("conversations").document(conversationId)
        try {
            val batch = db.batch()
            batch.update(docRef, "participantIds", FieldValue.arrayRemove(memberId))
            batch.update(docRef, "adminIds", FieldValue.arrayRemove(memberId))
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun promoteAdmin(conversationId: String, memberId: String) {
        val docRef = db.collection("conversations").document(conversationId)
        try {
            docRef.update("adminIds", FieldValue.arrayUnion(memberId)).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun demoteAdmin(conversationId: String, memberId: String) {
        val docRef = db.collection("conversations").document(conversationId)
        try {
            docRef.update("adminIds", FieldValue.arrayRemove(memberId)).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun leaveGroup(conversationId: String, userId: String) {
        removeGroupMember(conversationId, userId)
    }

    suspend fun deleteGroup(conversationId: String) {
        val docRef = db.collection("conversations").document(conversationId)
        try {
            docRef.delete().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    // ----------------------------------------------------
    // Messages Operations
    // ----------------------------------------------------

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> {
        val colRef = db.collection("conversations").document(conversationId).collection("messages")
        return colRef.snapshots()
            .map { snapshot ->
                snapshot.toObjects(ChatMessage::class.java)
                    .sortedBy { it.createdAt }
            }
            .catch { e ->
                if (e is CancellationException) throw e
                handleFirestoreError(e, OperationType.LIST, colRef.path)
                emit(emptyList())
            }
    }

    suspend fun sendMessage(
        conversationId: String,
        sender: UserProfile,
        content: String,
        imageUrl: String = "",
        replyTo: ChatMessage? = null
    ) {
        val msgId = "msg_${UUID.randomUUID().toString().take(12)}"
        val msgDocRef = db.collection("conversations").document(conversationId).collection("messages").document(msgId)
        val convDocRef = db.collection("conversations").document(conversationId)

        val msgPayload = mapOf(
            "messageId" to msgId,
            "conversationId" to conversationId,
            "senderId" to sender.userId,
            "senderName" to sender.displayName,
            "senderSixDigitId" to sender.sixDigitId,
            "content" to content.trim(),
            "imageUrl" to imageUrl,
            "replyToId" to (replyTo?.messageId ?: ""),
            "replyToText" to (replyTo?.content?.take(100) ?: ""),
            "replyToSender" to (replyTo?.senderName ?: ""),
            "readBy" to listOf(sender.userId),
            "deleted" to false,
            "createdAt" to FieldValue.serverTimestamp()
        )

        val convUpdate = mapOf(
            "lastMessage" to if (content.isNotBlank()) content.take(150) else "📷 Image",
            "lastSenderName" to sender.displayName,
            "lastSenderId" to sender.userId,
            "lastMessageTime" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "participantAvatars.${sender.userId}" to sender.avatarUrl,
            "participantNames.${sender.userId}" to sender.displayName
        )

        val batch = db.batch()
        batch.set(msgDocRef, msgPayload)
        batch.update(convDocRef, convUpdate)

        try {
            batch.commit().await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, msgDocRef.path)
            throw e
        }
    }

    suspend fun deleteMessage(conversationId: String, messageId: String) {
        val docRef = db.collection("conversations").document(conversationId).collection("messages").document(messageId)
        try {
            docRef.update(
                mapOf(
                    "deleted" to true,
                    "content" to "This message was deleted"
                )
            ).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun markMessageRead(conversationId: String, messageId: String, userId: String) {
        val docRef = db.collection("conversations").document(conversationId).collection("messages").document(messageId)
        try {
            docRef.update("readBy", FieldValue.arrayUnion(userId)).await()
        } catch (e: Exception) {
            // Ignore benign read receipt conflicts
        }
    }

    // ----------------------------------------------------
    // Call Signaling (WebRTC Simulation / Signaling)
    // ----------------------------------------------------

    suspend fun startCall(
        caller: UserProfile,
        receiverId: String,
        receiverName: String,
        callType: String
    ): String {
        val callId = "call_${UUID.randomUUID().toString().take(12)}"
        val docRef = db.collection("calls").document(callId)
        val payload = mapOf(
            "callId" to callId,
            "callerId" to caller.userId,
            "callerName" to caller.displayName,
            "receiverId" to receiverId,
            "receiverName" to receiverName,
            "callType" to callType,
            "status" to "calling",
            "offer" to "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=ChatConnect WebRTC Call\r\nt=0 0",
            "answer" to "",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
        return callId
    }

    fun observeCallSession(callId: String): Flow<CallSession?> {
        val docRef = db.collection("calls").document(callId)
        return docRef.snapshots()
            .map { it.toObject(CallSession::class.java) }
            .catch { e ->
                if (e is CancellationException) throw e
                handleFirestoreError(e, OperationType.GET, docRef.path)
                emit(null)
            }
    }

    fun observeIncomingCalls(userId: String): Flow<List<CallSession>> {
        val query = db.collection("calls")
            .whereEqualTo("receiverId", userId)
            .whereEqualTo("status", "calling")
        return query.snapshots()
            .map { it.toObjects(CallSession::class.java) }
            .catch { e ->
                if (e is CancellationException) throw e
                handleFirestoreError(e, OperationType.LIST, "calls")
                emit(emptyList())
            }
    }

    suspend fun updateCallStatus(callId: String, status: String) {
        val docRef = db.collection("calls").document(callId)
        try {
            docRef.update(
                mapOf(
                    "status" to status,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
        }
    }
}
