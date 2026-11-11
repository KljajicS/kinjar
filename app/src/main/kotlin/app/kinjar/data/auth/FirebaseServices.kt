package app.kinjar.data.auth

import android.content.Context
import app.kinjar.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

@Singleton
class FirebaseServices @Inject constructor(@ApplicationContext private val context: Context) {
    val configured: Boolean = BuildConfig.FIREBASE_SYNC_ENABLED && listOf(
        BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_APP_ID,
        BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_WEB_CLIENT_ID,
    ).all { it.isNotBlank() }

    private val app: FirebaseApp by lazy {
        check(configured) { "Firebase sync is not configured" }
        FirebaseApp.initializeApp(context, firebaseOptions(
            BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_API_KEY,
        ), "kinjar-sync").also {
            it.setDataCollectionDefaultEnabled(false)
            FirebaseAppCheck.getInstance(it).installAppCheckProviderFactory(FirebaseAttestation.provider())
        }
    }
    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance(app) }
    suspend fun verifyAppCheck() {
        val check = appCheckStep(AppCheckStage.INITIALIZATION) { FirebaseAppCheck.getInstance(app) }
        appCheckStep(AppCheckStage.TOKEN_REQUEST) { check.getAppCheckToken(true).await() }
    }

    val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance(app).also {
            it.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
        }
    }
}

internal fun firebaseOptions(projectId: String, appId: String, apiKey: String): FirebaseOptions {
    val projectNumber = Regex("1:([1-9][0-9]*):android:[0-9a-fA-F]+").matchEntire(appId)?.groupValues?.get(1)
    if (projectNumber?.toLongOrNull() == null) throw AppCheckConfigurationException()
    return FirebaseOptions.Builder()
        .setProjectId(projectId)
        .setApplicationId(appId)
        .setApiKey(apiKey)
        // Play Integrity reads the numeric project number from this option, even without messaging.
        .setGcmSenderId(projectNumber)
        .build()
}
