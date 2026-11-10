package app.kinjar.ui.update

import app.kinjar.data.update.UpdateManifest
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateApkIdentityTest {
    private val release = UpdateManifest(1, 14, "0.3.11", 26,
        "https://github.com/KljajicS/kinjar/releases/download/v0.3.11/kinjar-release.apk", 1234, "a".repeat(64))
    private val identity = UpdateApkIdentity("app.kinjar", 14, "0.3.11", 26, false, setOf("retained-key"))

    private fun verify(apk: UpdateApkIdentity, installed: Long = 13, signers: Set<String> = setOf("retained-key")) =
        verifyUpdateIdentity(apk, release, "app.kinjar", installed, signers, 36)

    @Test fun `matching retained signer allows newer release`() = verify(identity)

    @Test fun `wrong missing or additional signer rejects update`() {
        for (signers in listOf(emptySet(), setOf("wrong-key"), setOf("retained-key", "extra-key"))) {
            assertThrows(IllegalArgumentException::class.java) { verify(identity.copy(signers = signers)) }
        }
        assertThrows(IllegalArgumentException::class.java) { verify(identity, signers = emptySet()) }
    }

    @Test fun `package version sdk and debug mismatches reject update`() {
        val rejected = listOf(
            identity.copy(packageName = "other.app"), identity.copy(versionCode = 15),
            identity.copy(versionName = "0.3.12"), identity.copy(minSdk = 27), identity.copy(debuggable = true),
        )
        rejected.forEach { apk -> assertThrows(IllegalArgumentException::class.java) { verify(apk) } }
        assertThrows(IllegalArgumentException::class.java) {
            verifyUpdateIdentity(identity.copy(minSdk = 37), release.copy(minSdk = 37), "app.kinjar", 13,
                setOf("retained-key"), 36)
        }
    }

    @Test fun `reinstall and downgrade rejected even with matching metadata and signing key`() {
        assertThrows(IllegalArgumentException::class.java) { verify(identity, installed = 14) }
        assertThrows(IllegalArgumentException::class.java) { verify(identity, installed = 15) }
    }
}
