package app.kinjar.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseOptionsTest {
    @Test fun `explicit Firebase options include numeric project number required by Play Integrity`() {
        val options = firebaseOptions("test-project", "1:123456789:android:abcdef1234", "test-api-key")
        assertEquals("test-project", options.projectId)
        assertEquals("1:123456789:android:abcdef1234", options.applicationId)
        assertEquals("test-api-key", options.apiKey)
        assertEquals("123456789", options.gcmSenderId)
        assertEquals(123456789L, java.lang.Long.parseLong(requireNotNull(options.gcmSenderId)))
    }

    @Test fun `malformed project numbers fail before constructing SDK configuration`() {
        for (appId in listOf("", "1::android:abcdef", "1:abc:android:abcdef", "1:-1:android:abcdef",
            "1:0:android:abcdef", "1:9223372036854775808:android:abcdef", "1:123:web:abcdef", "1:123:android:abcdef\n")) {
            val failure = runCatching { firebaseOptions("test-project", appId, "test-api-key") }.exceptionOrNull()
            assertTrue(appId, failure is AppCheckConfigurationException)
        }
    }
}
