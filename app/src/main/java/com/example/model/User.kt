package com.example.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val sixDigitId: String = "",
    val username: String = "",
    val displayName: String = "",
    val about: String = "",
    val avatarUrl: String = "",
    val passwordHash: String = "",
    val online: Boolean = false,
    @ServerTimestamp val lastSeen: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AccountIdRecord(
    @DocumentId val id: String = "",
    val sixDigitId: String = "",
    val userId: String = "",
    val username: String = "",
    val displayName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class UsernameRecord(
    @DocumentId val id: String = "",
    val username: String = "",
    val userId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)
