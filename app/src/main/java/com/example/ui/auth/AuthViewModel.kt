package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.ChatRepository
import com.example.data.LoginVerificationResult
import com.example.model.UserProfile
import com.example.util.PasswordSecurity
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed interface AuthUiState {
    data object CheckingSession : AuthUiState
    data object Welcome : AuthUiState
    data object IdPasswordLogin : AuthUiState
    data class RegistrationPassword(val user: FirebaseUser) : AuthUiState
    data class RegistrationProfile(
        val user: FirebaseUser,
        val passwordHash: String
    ) : AuthUiState
    data class RegistrationSuccess(val profile: UserProfile) : AuthUiState
    data class Authenticated(val profile: UserProfile) : AuthUiState
    data class Error(val message: String) : AuthUiState
}

class AuthViewModel(
    private val repository: ChatRepository
) : ViewModel() {

    private val auth: FirebaseAuth = Firebase.auth
    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.CheckingSession)
    val uiState = _uiState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    fun initAuthCheck() {
        viewModelScope.launch {
            val current = auth.currentUser
            if (current != null) {
                val profile = repository.getUserProfile(current.uid)
                if (profile != null) {
                    _uiState.value = AuthUiState.Authenticated(profile)
                } else {
                    _uiState.value = AuthUiState.RegistrationPassword(current)
                }
            } else {
                _uiState.value = AuthUiState.Welcome
            }
        }
    }

    fun showWelcome() {
        _uiState.value = AuthUiState.Welcome
        _errorMessage.value = null
    }

    fun showIdPasswordLogin() {
        _uiState.value = AuthUiState.IdPasswordLogin
        _errorMessage.value = null
    }

    fun attemptSilentSignIn(context: Context) {
        val credentialManager = CredentialManager.create(context)
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            initAuthCheck()
            return
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

        viewModelScope.launch {
            try {
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(idToken, null)
                    val authResult = auth.signInWithCredential(authCredential).await()
                    val user = authResult.user
                    if (user != null) {
                        handleAuthenticatedUser(user)
                    } else {
                        _uiState.value = AuthUiState.Welcome
                    }
                } else {
                    _uiState.value = AuthUiState.Welcome
                }
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Welcome
            }
        }
    }

    fun onGoogleSignInClicked(context: Context) {
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            _errorMessage.value = "Google Sign-In configuration missing"
            return
        }

        val credentialManager = CredentialManager.create(context)
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val result = credentialManager.getCredential(context as Activity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(idToken, null)
                    val authResult = auth.signInWithCredential(authCredential).await()
                    val user = authResult.user
                    if (user != null) {
                        handleAuthenticatedUser(user)
                    } else {
                        _errorMessage.value = "Sign in was unsuccessful"
                    }
                } else {
                    _errorMessage.value = "Unexpected credential returned"
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w("Auth", "Google Sign-In flow cancelled: ${e.message}")
            } catch (e: Exception) {
                Log.e("Auth", "Google Sign-In failed", e)
                _errorMessage.value = e.localizedMessage ?: "Google Sign-In failed"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun handleAuthenticatedUser(user: FirebaseUser) {
        val existingProfile = repository.getUserProfile(user.uid)
        if (existingProfile != null) {
            repository.updateOnlineStatus(user.uid, true)
            _uiState.value = AuthUiState.Authenticated(existingProfile)
        } else {
            // New user registration flow
            _uiState.value = AuthUiState.RegistrationPassword(user)
        }
    }

    fun onPasswordCreated(user: FirebaseUser, passwordInput: String, confirmPasswordInput: String) {
        if (passwordInput.length < 8) {
            _errorMessage.value = "Password must be at least 8 characters"
            return
        }
        if (passwordInput != confirmPasswordInput) {
            _errorMessage.value = "Passwords do not match"
            return
        }

        val passwordHash = PasswordSecurity.hashPassword(passwordInput)
        _errorMessage.value = null
        _uiState.value = AuthUiState.RegistrationProfile(user, passwordHash)
    }

    fun onCompleteRegistration(
        user: FirebaseUser,
        passwordHash: String,
        displayName: String,
        username: String,
        avatarUrl: String,
        about: String
    ) {
        val cleanUsername = username.trim().lowercase()
        val cleanDisplayName = displayName.trim()

        if (cleanDisplayName.isBlank()) {
            _errorMessage.value = "Display name cannot be empty"
            return
        }
        if (cleanUsername.length < 3 || cleanUsername.length > 30) {
            _errorMessage.value = "Username must be between 3 and 30 characters"
            return
        }
        if (!cleanUsername.matches(Regex("^[a-zA-Z0-9_]+$"))) {
            _errorMessage.value = "Username can only contain letters, numbers, and underscores"
            return
        }

        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val isAvailable = repository.isUsernameAvailable(cleanUsername)
                if (!isAvailable) {
                    _errorMessage.value = "Username '@$cleanUsername' is already taken"
                    _isLoading.value = false
                    return@launch
                }

                val profile = repository.registerNewAccount(
                    userId = user.uid,
                    username = cleanUsername,
                    displayName = cleanDisplayName,
                    passwordHash = passwordHash,
                    avatarUrl = avatarUrl,
                    about = about
                )

                // Mandatory Step 5: Account Success Screen
                _uiState.value = AuthUiState.RegistrationSuccess(profile)
            } catch (e: Exception) {
                val rawMsg = e.message ?: e.toString()
                Log.e("Registration", "Registration error", e)
                val userFriendlyMessage = when {
                    rawMsg.contains("offline", ignoreCase = true) ->
                        "Cannot connect to Cloud Firestore: Please click 'Create Database' under Firestore Database in Firebase Console."
                    rawMsg.contains("PERMISSION_DENIED", ignoreCase = true) ->
                        "Database Permission Denied: Please publish your Firestore rules in Firebase Console."
                    rawMsg.contains("NOT_FOUND", ignoreCase = true) ->
                        "Firestore Database Not Found: Please click 'Create Database' under Firestore Database in Firebase Console."
                    rawMsg.contains("UNAVAILABLE", ignoreCase = true) ->
                        "Could not connect to database. Please check your internet connection or verify Cloud Firestore is created."
                    else -> e.localizedMessage ?: "Registration failed: $rawMsg"
                }
                _errorMessage.value = userFriendlyMessage
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onContinueToChat(profile: UserProfile) {
        _uiState.value = AuthUiState.Authenticated(profile)
    }

    fun onLoginWithSixDigitId(sixDigitId: String, passwordInput: String) {
        val cleanId = sixDigitId.trim()
        if (cleanId.length != 6 || !cleanId.all { it.isDigit() }) {
            _errorMessage.value = "Please enter a valid 6-digit ChatConnect ID"
            return
        }
        if (passwordInput.isBlank()) {
            _errorMessage.value = "Please enter your ChatConnect password"
            return
        }

        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                when (val result = repository.verifySixDigitLoginResult(cleanId, passwordInput)) {
                    is LoginVerificationResult.Success -> {
                        repository.updateOnlineStatus(result.profile.userId, true)
                        _uiState.value = AuthUiState.Authenticated(result.profile)
                    }
                    is LoginVerificationResult.AccountNotFound -> {
                        _errorMessage.value = "ChatConnect ID '$cleanId' not found in this database. If you just linked this new database, tap 'Back to Google Sign-In' to register your account."
                    }
                    is LoginVerificationResult.IncorrectPassword -> {
                        _errorMessage.value = "Incorrect password for ChatConnect ID $cleanId. Please check and try again."
                    }
                    is LoginVerificationResult.Error -> {
                        _errorMessage.value = result.message
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage ?: "Login failed"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun signOut(context: Context) {
        val currentUid = auth.currentUser?.uid
        val credentialManager = CredentialManager.create(context)
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    if (currentUid != null) {
                        repository.updateOnlineStatus(currentUid, false)
                    }
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w("Auth", "Failed to clear credential state: ${e.message}")
                } finally {
                    auth.signOut()
                    _uiState.value = AuthUiState.Welcome
                }
            }
        }
    }
}
