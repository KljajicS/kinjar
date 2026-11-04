package app.kinjar.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstallationIdentityTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `restart preserves identity while new installation gets another`() {
        val original = File(directory.root, "first/install-id")
        val first = InstallationIdentity.load(original)
        assertEquals(first, InstallationIdentity.load(original))
        assertNotEquals(first, InstallationIdentity.load(File(directory.root, "second/install-id")))
    }

    @Test fun `corrupt identity fails closed without silently replacing writer`() {
        val file = directory.newFile("install-id")
        file.writeText("invalid")
        assertThrows(IllegalArgumentException::class.java) { InstallationIdentity.load(file) }
        assertEquals("invalid", file.readText())
    }
}
