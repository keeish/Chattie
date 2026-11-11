package com.example.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.model.UserProfile
import com.example.ui.theme.OnlineGreen
import com.example.ui.theme.TelegramBlue
import com.example.util.PermissionHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: ChatViewModel,
    onSignOut: () -> Unit
) {
    val profile by viewModel.currentProfile.collectAsState()
    val currentTab by viewModel.currentTab.collectAsState()
    val activeConversation by viewModel.activeConversation.collectAsState()
    val activeMessages by viewModel.activeMessages.collectAsState()
    val replyingTo by viewModel.replyingTo.collectAsState()
    val activeCallSession by viewModel.activeCallSession.collectAsState()
    val incomingCalls by viewModel.incomingCalls.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val statusNotice by viewModel.statusNotice.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showNewChatDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showGroupInfo by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var hasRequestedStartupPermissions by remember { mutableStateOf(false) }

    var hasNotificationPermission by remember {
        mutableStateOf(PermissionHelper.hasNotificationPermission(context))
    }
    var hasAudioPermission by remember {
        mutableStateOf(PermissionHelper.hasAudioPermission(context))
    }
    var hasCameraPermission by remember {
        mutableStateOf(PermissionHelper.hasCameraPermission(context))
    }

    var pendingCallAction by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        hasNotificationPermission = PermissionHelper.hasNotificationPermission(context)
        hasAudioPermission = PermissionHelper.hasAudioPermission(context)
        hasCameraPermission = PermissionHelper.hasCameraPermission(context)

        pendingCallAction?.let { (targetUid, name, isVideo) ->
            if (PermissionHelper.hasCallPermissions(context, isVideo)) {
                viewModel.startCall(targetUid, name, if (isVideo) "video" else "audio")
            } else {
                scope.launch {
                    val missing = if (isVideo) "Microphone and Camera" else "Microphone"
                    snackbarHostState.showSnackbar("$missing permission is required to place calls.")
                }
            }
            pendingCallAction = null
        }
    }

    fun requestPermissions() {
        permissionsLauncher.launch(PermissionHelper.getInitialPermissionsToRequest())
    }

    fun handleStartCall(targetUid: String, name: String, isVideo: Boolean) {
        if (PermissionHelper.hasCallPermissions(context, isVideo)) {
            viewModel.startCall(targetUid, name, if (isVideo) "video" else "audio")
        } else {
            pendingCallAction = Triple(targetUid, name, isVideo)
            val needed = mutableListOf<String>()
            if (!PermissionHelper.hasAudioPermission(context)) {
                needed.add(Manifest.permission.RECORD_AUDIO)
            }
            if (isVideo && !PermissionHelper.hasCameraPermission(context)) {
                needed.add(Manifest.permission.CAMERA)
            }
            permissionsLauncher.launch(needed.toTypedArray())
        }
    }

    LaunchedEffect(Unit) {
        if (!hasRequestedStartupPermissions) {
            hasRequestedStartupPermissions = true
            val ungranted = PermissionHelper.getInitialPermissionsToRequest().filter { perm ->
                ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
            }
            if (ungranted.isNotEmpty()) {
                permissionsLauncher.launch(ungranted.toTypedArray())
            }
        }
    }

    val allPermissionsGranted = hasNotificationPermission && hasAudioPermission && hasCameraPermission

    LaunchedEffect(statusNotice) {
        statusNotice?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusNotice()
        }
    }

    // Full-screen Active Call
    if (activeCallSession != null) {
        CallScreen(
            session = activeCallSession!!,
            currentUserId = profile.userId,
            onEndCall = { viewModel.endCall() }
        )
        return
    }

    // Full-screen Active Conversation
    if (activeConversation != null) {
        if (showGroupInfo && activeConversation!!.isGroupChat) {
            GroupInfoScreen(
                conversation = activeConversation!!,
                currentUserId = profile.userId,
                onAddMember = { uid -> viewModel.addGroupMember(uid) },
                onRemoveMember = { uid -> viewModel.removeGroupMember(uid) },
                onPromoteAdmin = { uid -> viewModel.promoteAdmin(uid) },
                onDemoteAdmin = { uid -> viewModel.demoteAdmin(uid) },
                onLeaveGroup = {
                    viewModel.leaveGroup()
                    showGroupInfo = false
                },
                onDeleteGroup = {
                    viewModel.deleteGroup()
                    showGroupInfo = false
                },
                onBack = { showGroupInfo = false }
            )
        } else {
            ChatConversationScreen(
                conversation = activeConversation!!,
                currentUserId = profile.userId,
                messages = activeMessages,
                replyingTo = replyingTo,
                onSendMessage = { content, img -> viewModel.sendMessage(content, img) },
                onDeleteMessage = { msgId -> viewModel.deleteMessage(msgId) },
                onSetReplyingTo = { msg -> viewModel.setReplyingTo(msg) },
                onStartCall = { targetUid, name, isVideo ->
                    handleStartCall(targetUid, name, isVideo)
                },
                onOpenInfo = { showGroupInfo = true },
                onBack = { viewModel.closeConversation() }
            )
        }
        return
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UserAvatar(
                            name = profile.displayName,
                            avatarUrl = profile.avatarUrl,
                            size = 38.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "ChatConnect",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "ID: ${profile.sixDigitId} • @${profile.username}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                MainTab.entries.forEach { tab ->
                    val selected = currentTab == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { viewModel.selectTab(tab) },
                        icon = {
                            when (tab) {
                                MainTab.CHATS -> Icon(Icons.Default.Chat, contentDescription = tab.title)
                                MainTab.GROUPS -> Icon(Icons.Default.Group, contentDescription = tab.title)
                                MainTab.CONTACTS -> Icon(Icons.Default.Person, contentDescription = tab.title)
                                MainTab.PROFILE -> Icon(Icons.Default.AccountCircle, contentDescription = tab.title)
                                MainTab.SETTINGS -> Icon(Icons.Default.Settings, contentDescription = tab.title)
                            }
                        },
                        label = { Text(tab.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TelegramBlue,
                            selectedTextColor = TelegramBlue,
                            indicatorColor = TelegramBlue.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        },
        floatingActionButton = {
            when (currentTab) {
                MainTab.CHATS -> {
                    FloatingActionButton(
                        onClick = { showNewChatDialog = true },
                        containerColor = TelegramBlue,
                        contentColor = Color.White,
                        modifier = Modifier.testTag("new_chat_fab")
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "New Chat")
                    }
                }
                MainTab.GROUPS -> {
                    FloatingActionButton(
                        onClick = { showCreateGroupDialog = true },
                        containerColor = TelegramBlue,
                        contentColor = Color.White,
                        modifier = Modifier.testTag("create_group_fab")
                    ) {
                        Icon(Icons.Default.GroupAdd, contentDescription = "New Group")
                    }
                }
                MainTab.CONTACTS -> {
                    FloatingActionButton(
                        onClick = { showNewChatDialog = true },
                        containerColor = TelegramBlue,
                        contentColor = Color.White,
                        modifier = Modifier.testTag("add_contact_fab")
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Add Contact")
                    }
                }
                else -> Unit
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                MainTab.CHATS -> {
                    ChatsTabScreen(
                        conversations = conversations,
                        currentUserId = profile.userId,
                        searchQuery = searchQuery,
                        onSearchChange = { viewModel.setSearchQuery(it) },
                        onOpenConversation = { conv -> viewModel.openConversation(conv) },
                        onStartNewChat = { showNewChatDialog = true },
                        hasPermissions = allPermissionsGranted,
                        onRequestPermissions = { requestPermissions() }
                    )
                }
                MainTab.GROUPS -> {
                    GroupsTabScreen(
                        conversations = conversations,
                        currentUserId = profile.userId,
                        searchQuery = searchQuery,
                        onSearchChange = { viewModel.setSearchQuery(it) },
                        onOpenConversation = { group -> viewModel.openConversation(group) },
                        onCreateGroup = { showCreateGroupDialog = true },
                        hasPermissions = allPermissionsGranted,
                        onRequestPermissions = { requestPermissions() }
                    )
                }
                MainTab.CONTACTS -> {
                    ContactsTabScreen(
                        contacts = contacts,
                        onAddContactBySixDigitId = { sixDigitId ->
                            viewModel.addContactBySixDigitId(
                                sixDigitId = sixDigitId,
                                onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                                onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } }
                            )
                        },
                        onOpenContactChat = { contact -> viewModel.startDirectChatWithContact(contact) },
                        onStartCall = { targetUid, name, isVideo ->
                            handleStartCall(targetUid, name, isVideo)
                        },
                        onRemoveContact = { contactId -> viewModel.removeContact(contactId) }
                    )
                }
                MainTab.PROFILE -> {
                    ProfileTabScreen(
                        profile = profile,
                        onUpdateProfile = { name, about, avatar ->
                            viewModel.updateProfile(name, about, avatar)
                        }
                    )
                }
                MainTab.SETTINGS -> {
                    SettingsTabScreen(
                        profile = profile,
                        themeMode = themeMode,
                        onThemeChange = { mode -> viewModel.setThemeMode(mode) },
                        onChangePassword = { old, new, onSucc, onErr ->
                            viewModel.changePassword(old, new, onSucc, onErr)
                        },
                        onSignOut = onSignOut,
                        hasNotificationPermission = hasNotificationPermission,
                        hasAudioPermission = hasAudioPermission,
                        hasCameraPermission = hasCameraPermission,
                        onRequestPermissions = { requestPermissions() },
                        onOpenAppSettings = { PermissionHelper.openAppSettings(context) }
                    )
                }
            }

            // In-app Incoming Call notification banner
            if (incomingCalls.isNotEmpty()) {
                val incoming = incomingCalls.first()
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = TelegramBlue,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.TopCenter)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Incoming ${if (incoming.callType == "video") "Video" else "Voice"} Call",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = incoming.callerName,
                                    color = Color.White.copy(alpha = 0.9f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Row {
                            Button(
                                onClick = { viewModel.answerCall(incoming) },
                                colors = ButtonDefaults.buttonColors(containerColor = OnlineGreen),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Answer", fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(onClick = { viewModel.updateCallStatus(incoming.callId, "rejected") }) {
                                Icon(Icons.Default.Close, contentDescription = "Decline", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewChatDialog) {
        var inputId by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var isSearching by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showNewChatDialog = false },
            title = { Text("Start Chat by 6-Digit ID", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Enter the 6-digit ChatConnect ID of the user you want to chat with:")
                    Spacer(modifier = Modifier.height(12.dp))
                    if (errorMessage != null) {
                        Text(text = errorMessage!!, color = Color.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    OutlinedTextField(
                        value = inputId,
                        onValueChange = {
                            if (it.length <= 6 && it.all { ch -> ch.isDigit() }) inputId = it
                        },
                        placeholder = { Text("e.g. 849201") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag("new_chat_six_digit_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isSearching = true
                        errorMessage = null
                        viewModel.startDirectChatBySixDigitId(
                            sixDigitId = inputId,
                            onSuccess = {
                                isSearching = false
                                showNewChatDialog = false
                            },
                            onError = { err ->
                                isSearching = false
                                errorMessage = err
                            }
                        )
                    },
                    enabled = inputId.length == 6 && !isSearching,
                    colors = ButtonDefaults.buttonColors(containerColor = TelegramBlue),
                    modifier = Modifier.testTag("start_chat_confirm_button")
                ) {
                    Text("Start Chat")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewChatDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCreateGroupDialog) {
        CreateGroupDialog(
            contacts = contacts,
            onCreateGroup = { name, desc, avatar, members ->
                viewModel.createGroup(
                    name = name,
                    description = desc,
                    avatarUrl = avatar,
                    memberIds = members,
                    onSuccess = { showCreateGroupDialog = false },
                    onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } }
                )
            },
            onDismiss = { showCreateGroupDialog = false }
        )
    }
}
