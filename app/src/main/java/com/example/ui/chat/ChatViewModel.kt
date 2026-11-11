package com.example.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ChatRepository
import com.example.model.CallSession
import com.example.model.ChatMessage
import com.example.model.ContactItem
import com.example.model.Conversation
import com.example.model.UserProfile
import com.example.util.NotificationHelper
import com.example.util.PasswordSecurity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MainTab(val title: String) {
    CHATS("Chats"),
    GROUPS("Groups"),
    CONTACTS("Contacts"),
    PROFILE("Profile"),
    SETTINGS("Settings")
}

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

class ChatViewModel(
    private val repository: ChatRepository,
    initialProfile: UserProfile
) : ViewModel() {

    private val _currentProfile = MutableStateFlow(initialProfile)
    val currentProfile = _currentProfile.asStateFlow()

    private val _currentTab = MutableStateFlow(MainTab.CHATS)
    val currentTab = _currentTab.asStateFlow()

    private val _activeConversation = MutableStateFlow<Conversation?>(null)
    val activeConversation = _activeConversation.asStateFlow()

    private val _replyingTo = MutableStateFlow<ChatMessage?>(null)
    val replyingTo = _replyingTo.asStateFlow()

    private val _activeCallSession = MutableStateFlow<CallSession?>(null)
    val activeCallSession = _activeCallSession.asStateFlow()

    private val _themeMode = MutableStateFlow(AppThemeMode.SYSTEM)
    val themeMode = _themeMode.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _statusNotice = MutableStateFlow<String?>(null)
    val statusNotice = _statusNotice.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = repository.observeConversations(initialProfile.userId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val contacts: StateFlow<List<ContactItem>> = repository.observeContacts(initialProfile.userId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeMessages: StateFlow<List<ChatMessage>> = _activeConversation
        .flatMapLatest { conv ->
            if (conv != null) repository.observeMessages(conv.conversationId)
            else flowOf(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val incomingCalls: StateFlow<List<CallSession>> = repository.observeIncomingCalls(initialProfile.userId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    init {
        // Observe user profile changes in real time
        viewModelScope.launch {
            repository.observeUserProfile(initialProfile.userId).collect { updated ->
                if (updated != null) {
                    _currentProfile.value = updated
                }
            }
        }
    }

    fun selectTab(tab: MainTab) {
        _currentTab.value = tab
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private val notifiedTimestamps = mutableMapOf<String, Long>()
    private var isNotificationsInitialized = false
    private var appContext: Context? = null

    fun setupNotifications(context: Context) {
        appContext = context.applicationContext
        if (isNotificationsInitialized) return
        isNotificationsInitialized = true
        NotificationHelper.createNotificationChannels(context)

        viewModelScope.launch {
            conversations.collect { convList ->
                for (conv in convList) {
                    val lastMsg = conv.lastMessage
                    val lastSender = conv.lastSenderId
                    val timeSeconds = conv.lastMessageTime?.seconds ?: conv.updatedAt?.seconds ?: 0L

                    if (lastMsg.isNotBlank() && lastSender.isNotBlank() && lastSender != _currentProfile.value.userId) {
                        val prevTime = notifiedTimestamps[conv.conversationId]
                        if (prevTime == null) {
                            // Seed timestamp so existing messages on app launch do not trigger duplicate notifications
                            notifiedTimestamps[conv.conversationId] = timeSeconds
                        } else if (timeSeconds > prevTime) {
                            notifiedTimestamps[conv.conversationId] = timeSeconds
                            // Only trigger notification if user is not currently inside this conversation
                            if (_activeConversation.value?.conversationId != conv.conversationId) {
                                val senderName = conv.lastSenderName.ifBlank {
                                    conv.getDisplayName(_currentProfile.value.userId)
                                }
                                NotificationHelper.showIncomingMessageNotification(
                                    context = context,
                                    conversationId = conv.conversationId,
                                    senderName = senderName,
                                    messageContent = lastMsg
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun openConversation(conv: Conversation) {
        _activeConversation.value = conv
        _replyingTo.value = null
        appContext?.let { NotificationHelper.cancelMessageNotification(it, conv.conversationId) }
    }

    fun openConversationById(conversationId: String) {
        val conv = conversations.value.firstOrNull { it.conversationId == conversationId }
        if (conv != null) {
            openConversation(conv)
        }
    }

    fun closeConversation() {
        _activeConversation.value = null
        _replyingTo.value = null
    }

    fun setReplyingTo(msg: ChatMessage?) {
        _replyingTo.value = msg
    }

    fun clearStatusNotice() {
        _statusNotice.value = null
    }

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
    }

    fun sendMessage(content: String, imageUrl: String = "") {
        val conv = _activeConversation.value ?: return
        if (content.isBlank() && imageUrl.isBlank()) return

        val reply = _replyingTo.value
        _replyingTo.value = null

        viewModelScope.launch {
            try {
                repository.sendMessage(
                    conversationId = conv.conversationId,
                    sender = _currentProfile.value,
                    content = content,
                    imageUrl = imageUrl,
                    replyTo = reply
                )
            } catch (e: Exception) {
                _statusNotice.value = "Failed to send message: ${e.message}"
            }
        }
    }

    fun deleteMessage(messageId: String) {
        val conv = _activeConversation.value ?: return
        viewModelScope.launch {
            try {
                repository.deleteMessage(conv.conversationId, messageId)
            } catch (e: Exception) {
                _statusNotice.value = "Failed to delete: ${e.message}"
            }
        }
    }

    fun startDirectChatBySixDigitId(
        sixDigitId: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val account = repository.findAccountBySixDigitId(sixDigitId)
                if (account == null) {
                    onError("No user found with ChatConnect ID $sixDigitId")
                    return@launch
                }
                if (account.userId == _currentProfile.value.userId) {
                    onError("You cannot start a chat with yourself")
                    return@launch
                }
                val otherProfile = repository.getUserProfile(account.userId)
                if (otherProfile == null) {
                    onError("Could not fetch user profile")
                    return@launch
                }
                val conv = repository.getOrCreateDirectConversation(_currentProfile.value, otherProfile)
                _activeConversation.value = conv
                onSuccess()
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Failed to start chat")
            }
        }
    }

    fun startDirectChatWithContact(contact: ContactItem) {
        viewModelScope.launch {
            try {
                val otherProfile = repository.getUserProfile(contact.contactUserId)
                if (otherProfile != null) {
                    val conv = repository.getOrCreateDirectConversation(_currentProfile.value, otherProfile)
                    _activeConversation.value = conv
                }
            } catch (e: Exception) {
                _statusNotice.value = "Could not open chat: ${e.message}"
            }
        }
    }

    fun createGroup(
        name: String,
        description: String,
        avatarUrl: String,
        memberIds: List<String>,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (name.isBlank()) {
            onError("Group name cannot be empty")
            return
        }
        viewModelScope.launch {
            try {
                val conv = repository.createGroupConversation(
                    creator = _currentProfile.value,
                    groupName = name,
                    description = description,
                    avatarUrl = avatarUrl,
                    memberIds = memberIds
                )
                _activeConversation.value = conv
                onSuccess()
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Failed to create group")
            }
        }
    }

    fun updateGroupDetails(name: String, description: String, avatarUrl: String) {
        val conv = _activeConversation.value ?: return
        viewModelScope.launch {
            try {
                repository.updateGroupDetails(conv.conversationId, name, description, avatarUrl)
                _activeConversation.value = conv.copy(name = name, description = description, avatarUrl = avatarUrl)
            } catch (e: Exception) {
                _statusNotice.value = "Failed to update group: ${e.message}"
            }
        }
    }

    fun addGroupMember(newMemberId: String) {
        val conv = _activeConversation.value ?: return
        viewModelScope.launch {
            try {
                repository.addGroupMember(conv.conversationId, newMemberId)
                _activeConversation.value = conv.copy(participantIds = conv.participantIds + newMemberId)
            } catch (e: Exception) {
                _statusNotice.value = "Failed to add member: ${e.message}"
            }
        }
    }

    fun removeGroupMember(memberId: String) {
        val conv = _activeConversation.value ?: return
        viewModelScope.launch {
            try {
                repository.removeGroupMember(conv.conversationId, memberId)
                _activeConversation.value = conv.copy(
                    participantIds = conv.participantIds.filter { it != memberId },
                    adminIds = conv.adminIds.filter { it != memberId }
                )
            } catch (e: Exception) {
                _statusNotice.value = "Failed to remove member: ${e.message}"
            }
        }
    }

    fun promoteAdmin(memberId: String) {
        val conv = _activeConversation.value ?: return
        viewModelScope.launch {
            try {
                repository.promoteAdmin(conv.conversationId, memberId)
                _activeConversation.value = conv.copy(adminIds = (conv.adminIds + memberId).distinct())
            } catch (e: Exception) {
                _statusNotice.value = "Failed to promote admin: ${e.message}"
            }
        }
    }

    fun demoteAdmin(memberId: String) {
        val conv = _activeConversation.value ?: return
        viewModelScope.launch {
            try {
                repository.demoteAdmin(conv.conversationId, memberId)
                _activeConversation.value = conv.copy(adminIds = conv.adminIds.filter { it != memberId })
            } catch (e: Exception) {
                _statusNotice.value = "Failed to demote admin: ${e.message}"
            }
        }
    }

    fun leaveGroup() {
        val conv = _activeConversation.value ?: return
        viewModelScope.launch {
            try {
                repository.leaveGroup(conv.conversationId, _currentProfile.value.userId)
                _activeConversation.value = null
            } catch (e: Exception) {
                _statusNotice.value = "Failed to leave group: ${e.message}"
            }
        }
    }

    fun deleteGroup() {
        val conv = _activeConversation.value ?: return
        viewModelScope.launch {
            try {
                repository.deleteGroup(conv.conversationId)
                _activeConversation.value = null
            } catch (e: Exception) {
                _statusNotice.value = "Failed to delete group: ${e.message}"
            }
        }
    }

    fun addContactBySixDigitId(
        sixDigitId: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val account = repository.findAccountBySixDigitId(sixDigitId)
                if (account == null) {
                    onError("No user found with ChatConnect ID $sixDigitId")
                    return@launch
                }
                if (account.userId == _currentProfile.value.userId) {
                    onError("You cannot add yourself as a contact")
                    return@launch
                }
                val otherProfile = repository.getUserProfile(account.userId)
                if (otherProfile == null) {
                    onError("Could not load user profile")
                    return@launch
                }
                repository.addContact(_currentProfile.value.userId, otherProfile)
                onSuccess("Added ${otherProfile.displayName} to contacts")
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Failed to add contact")
            }
        }
    }

    fun removeContact(contactId: String) {
        viewModelScope.launch {
            try {
                repository.removeContact(_currentProfile.value.userId, contactId)
            } catch (e: Exception) {
                _statusNotice.value = "Failed to remove contact: ${e.message}"
            }
        }
    }

    private var callListenerJob: kotlinx.coroutines.Job? = null

    private fun listenToCallSession(callId: String) {
        callListenerJob?.cancel()
        callListenerJob = viewModelScope.launch {
            repository.observeCallSession(callId).collect { session ->
                if (session == null) {
                    _activeCallSession.value = null
                } else {
                    _activeCallSession.value = session
                    if (session.status == "rejected" || session.status == "ended" || session.status == "missed") {
                        kotlinx.coroutines.delay(2000L)
                        _activeCallSession.value = null
                        callListenerJob?.cancel()
                    }
                }
            }
        }
    }

    fun startCall(otherUserId: String, otherUserName: String, callType: String) {
        viewModelScope.launch {
            try {
                val callId = repository.startCall(
                    caller = _currentProfile.value,
                    receiverId = otherUserId,
                    receiverName = otherUserName,
                    callType = callType
                )
                val session = CallSession(
                    id = callId,
                    callId = callId,
                    callerId = _currentProfile.value.userId,
                    callerName = _currentProfile.value.displayName,
                    receiverId = otherUserId,
                    receiverName = otherUserName,
                    callType = callType,
                    status = "calling"
                )
                _activeCallSession.value = session
                listenToCallSession(callId)
            } catch (e: Exception) {
                _statusNotice.value = "Call setup failed: ${e.message}"
            }
        }
    }

    fun answerCall(call: CallSession) {
        viewModelScope.launch {
            try {
                repository.updateCallStatus(call.callId, "connected")
                val connectedSession = call.copy(status = "connected")
                _activeCallSession.value = connectedSession
                listenToCallSession(call.callId)
            } catch (e: Exception) {
                _statusNotice.value = "Failed to answer: ${e.message}"
            }
        }
    }

    fun endCall() {
        val session = _activeCallSession.value
        callListenerJob?.cancel()
        _activeCallSession.value = null
        if (session != null) {
            viewModelScope.launch {
                try {
                    repository.updateCallStatus(session.callId, "ended")
                } catch (e: Exception) {
                    // Ignored on teardown
                }
            }
        }
    }

    fun updateCallStatus(callId: String, status: String) {
        viewModelScope.launch {
            try {
                repository.updateCallStatus(callId, status)
            } catch (e: Exception) {
                _statusNotice.value = "Failed to update call: ${e.message}"
            }
        }
    }


    fun updateProfile(displayName: String, about: String, avatarUrl: String) {
        viewModelScope.launch {
            try {
                repository.updateUserProfile(_currentProfile.value.userId, displayName, about, avatarUrl)
                _currentProfile.value = _currentProfile.value.copy(
                    displayName = displayName,
                    about = about,
                    avatarUrl = avatarUrl
                )
                _statusNotice.value = "Profile updated successfully"
            } catch (e: Exception) {
                _statusNotice.value = "Failed to update profile: ${e.message}"
            }
        }
    }

    fun changePassword(
        oldPasswordInput: String,
        newPasswordInput: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (newPasswordInput.length < 8) {
            onError("New password must be at least 8 characters")
            return
        }
        if (!PasswordSecurity.verifyPassword(oldPasswordInput, _currentProfile.value.passwordHash)) {
            onError("Current password is incorrect")
            return
        }

        val newHash = PasswordSecurity.hashPassword(newPasswordInput)
        viewModelScope.launch {
            try {
                repository.changePassword(_currentProfile.value.userId, newHash)
                _currentProfile.value = _currentProfile.value.copy(passwordHash = newHash)
                onSuccess()
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Failed to update password")
            }
        }
    }
}
