package app.kinjar.data.auth

import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

internal object FirebaseAttestation {
    fun provider() = PlayIntegrityAppCheckProviderFactory.getInstance()
}
