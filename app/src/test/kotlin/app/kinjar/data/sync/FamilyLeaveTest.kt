package app.kinjar.data.sync

import app.kinjar.data.auth.FirebaseServices
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class FamilyLeaveTest {
    private val services = mock(FirebaseServices::class.java)
    private val auth = mock(FirebaseAuth::class.java)
    private val user = mock(FirebaseUser::class.java)
    private val db = mock(FirebaseFirestore::class.java, RETURNS_DEEP_STUBS)
    private val userRef = db.collection("users").document("member")
    private val familyRef = db.collection("families").document("family")
    private val installs = (0..5).map { familyRef.collection("installs").document("$it") }
    private val invites = (0..5).map { familyRef.collection("invites").document("$it") }
    private val tx = mock(Transaction::class.java)
    private val account = mock(DocumentSnapshot::class.java)
    private val root = mock(DocumentSnapshot::class.java)
    private val repository = FamilyRepository(services)
    private var beforeTransaction: () -> Unit = {}
    private var retry = false

    @Before fun prepare() {
        doReturn(auth).`when`(services).auth
        doReturn(db).`when`(services).firestore
        doReturn(user).`when`(auth).currentUser
        doReturn(true).`when`(user).isEmailVerified
        doReturn("member").`when`(user).uid
        doReturn(account).`when`(tx).get(userRef)
        doReturn("family").`when`(account).getString("familyId")
        doReturn("ACTIVE").`when`(account).getString("status")
        doReturn(root).`when`(tx).get(familyRef)
        doReturn(true).`when`(root).exists()
        doReturn("family").`when`(root).id
        doReturn("owner").`when`(root).getString("owner")
        doReturn("ACTIVE").`when`(root).getString("status")
        doReturn("dataset").`when`(root).getString("activeDataset")
        doReturn(1L).`when`(root).getLong("generation")
        doReturn(mapOf("owner" to "o", "member" to "m")).`when`(root).get("members")
        doReturn(listOf("owner", "member")).`when`(root).get("admins")
        for (slot in 0..5) {
            val install = mock(DocumentSnapshot::class.java)
            doReturn("member").`when`(install).getString("uid")
            doReturn(install).`when`(tx).get(installs[slot])
            val invite = mock(DocumentSnapshot::class.java)
            doReturn("member").`when`(invite).getString("inviter")
            doReturn("PENDING").`when`(invite).getString("status")
            doReturn(invite).`when`(tx).get(invites[slot])
        }
        doAnswer { call ->
            beforeTransaction()
            val block = call.getArgument<Transaction.Function<Unit>>(0)
            val result = block.apply(tx)
            if (retry) {
                doReturn("other").`when`(user).uid
                block.apply(tx)
            }
            Tasks.forResult(result)
        }.`when`(db).runTransaction<Unit>(any())
    }

    @Test fun `leave checks expected account before transaction and on retries`() = runTest {
        assertTrue(runCatching { repository.leave("family", "other") }.isFailure)
        verifyNoInteractions(tx)
        beforeTransaction = { doReturn("other").`when`(user).uid }
        assertTrue(runCatching { repository.leave("family", "member") }.isFailure)
        verifyNoInteractions(tx)
        beforeTransaction = {}
        doReturn("member").`when`(user).uid
        retry = true
        assertTrue(runCatching { repository.leave("family", "member") }.isFailure)
    }

    @Test fun `already removed leave succeeds without accessing inaccessible family`() = runTest {
        doReturn("REMOVED").`when`(account).getString("status")
        repository.leave("family", "member")
        verify(tx, never()).get(familyRef)
    }

    @Test fun `owner and administrative self removal are rejected`() = runTest {
        doReturn("member").`when`(root).getString("owner")
        assertTrue(runCatching { repository.leave("family", "member") }.isFailure)
        assertTrue(runCatching { repository.remove("family", "member") }.isFailure)
        verify(tx, never()).update(any(DocumentReference::class.java), anyMap<String, Any>())
    }

    @Test fun `leave removes all six installs and pending invitations atomically`() = runTest {
        repository.leave("family", "member")
        for (slot in 0..5) {
            verify(tx).delete(installs[slot])
            verify(tx).update(invites[slot], "status", "CANCELLED")
        }
        verify(tx).update(userRef, "status", "REMOVED")
        verify(tx).update(familyRef, mapOf(
            "members" to mapOf("owner" to "o"), "admins" to listOf("owner"), "action" to "remove", "target" to "member"))
    }
}
