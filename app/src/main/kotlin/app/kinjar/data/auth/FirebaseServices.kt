package app.kinjar.data.auth

import android.content.Context
import app.kinjar.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseServices @Inject constructor(@ApplicationContext private val context: Context) {
    val configured: Boolean = BuildConfig.FIREBASE_SYNC_ENABLED && listOf(
        BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_APP_ID,
        BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_WEB_CLIENT_ID,
    ).all { it.isNotBlank() }

    private val app: FirebaseApp by lazy {
        check(configured) { "Firebase sync is not configured" }
        FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY).build(), "kinjar-sync").also {
            it.setDataCollectionDefaultEnabled(false)
            FirebaseAppCheck.getInstance(it).installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }
    }
    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance(app) }
    val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance(app).also {
            it.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
        }
    }
}
