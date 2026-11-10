package app.kinjar.ui

import app.kinjar.data.auth.GoogleAccount
import app.kinjar.data.sync.FamilyInfo
import app.kinjar.ui.sync.FamilyScreenState
import org.junit.Assert.*
import org.junit.Test

class FamilyScreenStateTest {
    @Test fun `members and administrators can leave but owners and pending recovery cannot`() {
        val account = GoogleAccount("member", "member@example.test", "Member")
        val family = FamilyInfo("family", "owner", listOf("owner"), mapOf("owner" to "o", "member" to "m"), "dataset", 1, "ACTIVE")
        val state = FamilyScreenState(account = account, family = family)
        assertTrue(state.canLeave)
        assertTrue(state.copy(family = family.copy(admins = listOf("owner", "member"))).canLeave)
        assertFalse(state.copy(account = account.copy(uid = "owner")).canLeave)
        assertFalse(state.copy(account = null).canLeave)
        assertFalse(state.copy(account = account.copy(uid = "outsider")).canLeave)
        assertFalse(state.copy(family = null).canLeave)
        assertFalse(state.copy(journalPhase = "RESTORE").canLeave)
        assertFalse(state.copy(recoveryOtherAccount = true).canLeave)
        assertFalse(state.copy(family = family.copy(status = "RESTORING")).canLeave)
    }

    @Test fun `shared replacement requires a loaded active administrator account`() {
        val account = GoogleAccount("member", "member@example.test", "Member")
        val family = FamilyInfo("family", "owner", listOf("owner"), mapOf("member" to "m"), "dataset", 1, "ACTIVE")
        val state = FamilyScreenState(account = account, family = family, enrolled = true)
        assertFalse(state.canRestore)
        assertFalse(state.copy(family = null).canRestore)
        val admin = state.copy(family = family.copy(admins = listOf("owner", "member")))
        assertTrue(admin.canRestore)
        assertFalse(admin.copy(family = admin.family!!.copy(status = "RESTORING")).canRestore)
        assertFalse(admin.copy(recoveryOtherAccount = true).canRestore)
        assertTrue(FamilyScreenState().canRestore)
    }
}
