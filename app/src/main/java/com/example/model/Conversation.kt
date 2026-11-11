package com.example.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class Conversation(
    @DocumentId val id: String = "",
    val conversationId: String = "",
    @get:PropertyName("isGroup") val isGroup: Boolean = false,
    val name: String = "",
    val description: String = "",
    val avatarUrl: String = "",
    val creatorId: String = "",
    val adminIds: List<String> = emptyList(),
    val participantIds: List<String> = emptyList(),
    val participantNames: Map<String, String> = emptyMap(),
    val participantAvatars: Map<String, String> = emptyMap(),
    val lastMessage: String = "",
    val lastSenderName: String = "",
    val lastSenderId: String = "",
    @ServerTimestamp val lastMessageTime: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val isGroupChat: Boolean
        get() = isGroup || conversationId.startsWith("group_")

    fun getDisplayName(currentUserId: String): String {
        if (isGroupChat) return name.ifBlank { "Group Chat" }
        val otherId = participantIds.firstOrNull { it != currentUserId }
        if (otherId != null && participantNames.containsKey(otherId)) {
            val n = participantNames[otherId]
            if (!n.isNullOrBlank()) return n
        }
        return name.ifBlank { "Direct Chat" }
    }

    fun getDisplayAvatar(currentUserId: String): String {
        if (isGroupChat) return avatarUrl
        val otherId = participantIds.firstOrNull { it != currentUserId }
        if (otherId != null && participantAvatars.containsKey(otherId)) {
            val a = participantAvatars[otherId]
            if (!a.isNullOrBlank()) return a
        }
        return avatarUrl
    }
}
