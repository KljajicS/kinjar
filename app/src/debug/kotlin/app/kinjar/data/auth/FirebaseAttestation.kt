package app.kinjar.data.auth

import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

internal object FirebaseAttestation {
    fun provider() = DebugAppCheckProviderFactory.getInstance()
}
