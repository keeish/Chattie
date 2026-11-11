package com.example.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class ContactItem(
    @DocumentId val id: String = "",
    val contactId: String = "",
    val userId: String = "",
    val contactUserId: String = "",
    val sixDigitId: String = "",
    val displayName: String = "",
    val username: String = "",
    val avatarUrl: String = "",
    @ServerTimestamp val addedAt: Timestamp? = null
)
