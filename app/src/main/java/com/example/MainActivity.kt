package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.ChatRepository
import com.example.ui.auth.AccountSuccessScreen
import com.example.ui.auth.AuthUiState
import com.example.ui.auth.AuthViewModel
import com.example.ui.auth.IdPasswordLoginScreen
import com.example.ui.auth.RegistrationPasswordScreen
import com.example.ui.auth.RegistrationProfileScreen
import com.example.ui.auth.WelcomeScreen
import com.example.ui.chat.AppThemeMode
import com.example.ui.chat.ChatViewModel
import com.example.ui.chat.MainAppScreen
import com.example.ui.theme.ChatConnectTheme
import com.example.ui.theme.TelegramBlue
import android.content.Intent
import com.example.util.NotificationHelper
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.createNotificationChannels(this)

        setContent {
            val context = LocalContext.current
            val db = remember {
                val dbId = getString(R.string.firestore_database_id)
                if (dbId.isEmpty() || dbId == "(default)") FirebaseFirestore.getInstance()
                else FirebaseFirestore.getInstance(dbId)
            }

            val repository = remember { ChatRepository(db) }

            val authViewModel: AuthViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { AuthViewModel(repository) }
                }
            )

            val authState by authViewModel.uiState.collectAsState()
            val isAuthLoading by authViewModel.isLoading.collectAsState()
            val authError by authViewModel.errorMessage.collectAsState()

            LaunchedEffect(Unit) {
                authViewModel.attemptSilentSignIn(context)
            }

            ChatConnectTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (val state = authState) {
                        is AuthUiState.CheckingSession -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = TelegramBlue, modifier = Modifier.size(48.dp))
                            }
                        }

                        is AuthUiState.Welcome -> {
                            WelcomeScreen(
                                isLoading = isAuthLoading,
                                errorMessage = authError,
                                onGoogleSignIn = { authViewModel.onGoogleSignInClicked(context) },
                                onLoginWithId = { authViewModel.showIdPasswordLogin() }
                            )
                        }

                        is AuthUiState.IdPasswordLogin -> {
                            IdPasswordLoginScreen(
                                isLoading = isAuthLoading,
                                errorMessage = authError,
                                onLogin = { id, pass -> authViewModel.onLoginWithSixDigitId(id, pass) },
                                onBackToGoogle = { authViewModel.showWelcome() }
                            )
                        }

                        is AuthUiState.RegistrationPassword -> {
                            RegistrationPasswordScreen(
                                user = state.user,
                                errorMessage = authError,
                                onPasswordSet = { pass, confirm ->
                                    authViewModel.onPasswordCreated(state.user, pass, confirm)
                                },
                                onCancel = { authViewModel.signOut(context) }
                            )
                        }

                        is AuthUiState.RegistrationProfile -> {
                            RegistrationProfileScreen(
                                user = state.user,
                                isLoading = isAuthLoading,
                                errorMessage = authError,
                                onComplete = { name, username, avatar, about ->
                                    authViewModel.onCompleteRegistration(
                                        user = state.user,
                                        passwordHash = state.passwordHash,
                                        displayName = name,
                                        username = username,
                                        avatarUrl = avatar,
                                        about = about
                                    )
                                },
                                onBack = {
                                    authViewModel.initAuthCheck()
                                }
                            )
                        }

                        is AuthUiState.RegistrationSuccess -> {
                            AccountSuccessScreen(
                                profile = state.profile,
                                onContinue = { authViewModel.onContinueToChat(state.profile) }
                            )
                        }

                        is AuthUiState.Authenticated -> {
                            key(state.profile.userId) {
                                val chatViewModel: ChatViewModel = viewModel(
                                    key = state.profile.userId,
                                    factory = viewModelFactory {
                                        initializer {
                                            ChatViewModel(repository, state.profile)
                                        }
                                    }
                                )

                                LaunchedEffect(state.profile.userId) {
                                    chatViewModel.setupNotifications(context)
                                    val targetConvId = intent?.getStringExtra("EXTRA_CONVERSATION_ID")
                                    if (!targetConvId.isNullOrBlank()) {
                                        chatViewModel.openConversationById(targetConvId)
                                        intent?.removeExtra("EXTRA_CONVERSATION_ID")
                                    }
                                }

                                val currentThemeMode by chatViewModel.themeMode.collectAsState()
                                val isDark = when (currentThemeMode) {
                                    AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                                    AppThemeMode.LIGHT -> false
                                    AppThemeMode.DARK -> true
                                }

                                ChatConnectTheme(darkTheme = isDark) {
                                    MainAppScreen(
                                        viewModel = chatViewModel,
                                        onSignOut = { authViewModel.signOut(context) }
                                    )
                                }
                            }
                        }

                        is AuthUiState.Error -> {
                            WelcomeScreen(
                                isLoading = false,
                                errorMessage = state.message,
                                onGoogleSignIn = { authViewModel.onGoogleSignInClicked(context) },
                                onLoginWithId = { authViewModel.showIdPasswordLogin() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
