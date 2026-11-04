package app.kinjar.data.auth

import android.app.Activity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import app.kinjar.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class GoogleAccount(val uid: String, val email: String, val displayName: String)

@Singleton
class FirebaseAuthRepository @Inject constructor(private val services: FirebaseServices) {
    val configured: Boolean get() = services.configured
    fun current(): GoogleAccount? {
        if (!configured) return null
        return services.auth.currentUser?.takeIf { it.isEmailVerified }?.let {
            GoogleAccount(it.uid, requireNotNull(it.email), it.displayName.orEmpty())
        }
    }

    suspend fun signIn(activity: Activity): GoogleAccount {
        check(configured) { "Firebase sync is not configured" }
        val credential = CredentialManager.create(activity).getCredential(activity,
            GetCredentialRequest.Builder().addCredentialOption(GetGoogleIdOption.Builder()
                .setServerClientId(BuildConfig.FIREBASE_WEB_CLIENT_ID)
                .setFilterByAuthorizedAccounts(false).setAutoSelectEnabled(false).build()).build()).credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
        services.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
        return requireNotNull(current()) { "Verified Google account required" }
    }

    suspend fun signOut(activity: Activity) {
        if (configured) services.auth.signOut()
        CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest())
    }
}
