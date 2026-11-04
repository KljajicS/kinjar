package app.kinjar.ui.sync

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.data.sync.SyncPhase
import app.kinjar.domain.*
import app.kinjar.ui.WriteFeedback
import app.kinjar.ui.settings.ScreenHeader
import app.kinjar.ui.theme.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private data class Confirmation(val title: Int, val body: Int, val execute: () -> Unit)

@Composable
fun FamilyScreen(onBack: () -> Unit, onContinue: () -> Unit, onBackup: () -> Unit, vm: FamilyViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sync by vm.status.collectAsStateWithLifecycle()
    val writes by vm.writes.state.collectAsStateWithLifecycle()
    val c = LocalColors.current
    val activity = LocalActivity.current
    var email by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    fun ask(title: Int, body: Int, execute: () -> Unit) { confirmation = Confirmation(title, body, execute); confirmed = false }
    LaunchedEffect(writes.busy, writes.failed) { if (confirmed && !writes.busy && !writes.failed) confirmation = null }

    LazyColumn(Modifier.fillMaxSize().background(c.bg).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader(stringResource(R.string.fbs_family), onBack) }
        item {
            OutlinedBox(Modifier.fillMaxWidth()) {
                Text(state.account?.email ?: stringResource(R.string.fbs_optional), style = SectionStyle, color = c.text)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.fbs_privacy), style = CaptionStyle, color = c.muted)
            }
        }
        item {
            WriteFeedback(vm.writes, R.string.fbs_action_failed)
            Text(stringResource(sync.phase.label()), style = BodyStyle, color = c.muted)
            if (sync.pending > 0) Text(stringResource(R.string.fbs_pending, sync.pending), color = c.muted)
            sync.lastSuccess?.let { Text(stringResource(R.string.fbs_last_sync, displayTime(it)), style = CaptionStyle, color = c.muted) }
        }
        if (!vm.configured) item { Text(stringResource(R.string.fbs_unavailable), color = c.muted) }
        else if (state.account == null) item {
            PrimaryButton(stringResource(R.string.fbs_google), enabled = !writes.busy && activity != null) { activity?.let(vm::signIn) }
        } else {
            item { TextButton(onClick = vm::refresh, enabled = !writes.busy) { Text(stringResource(R.string.fbs_refresh)) } }
            val family = state.family
            if (family == null) {
                items(state.invitations, key = { it.familyId + it.slot }) { invite ->
                    OutlinedBox(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.fbs_invited_by, invite.inviterEmail), style = BodyStyle, color = c.text)
                        TextButton(onClick = { ask(R.string.fbs_join, R.string.fbs_join_warning) { vm.stage(invite) } }, enabled = !writes.busy) { Text(stringResource(R.string.fbs_join)) }
                    }
                }
                if (!state.enrolled) item {
                    PrimaryButton(stringResource(if (state.journalPhase == "CREATE") R.string.fbs_resume_setup else R.string.fbs_create), enabled = !writes.busy) { vm.create() }
                }
            } else {
                item {
                    when {
                        family.status == "DELETING" -> DangerLink(stringResource(R.string.fbs_resume_delete), enabled = !writes.busy) {
                            ask(R.string.fbs_delete, R.string.fbs_delete_warning, vm::deleteFamily)
                        }
                        state.journalPhase == "CREATE" -> PrimaryButton(stringResource(R.string.fbs_resume_setup), enabled = !writes.busy, onClick = vm::create)
                        state.journalPhase == "RESTORE" -> PrimaryButton(stringResource(R.string.fbs_resume_restore), enabled = !writes.busy, onClick = vm::resumeRestore)
                        state.journalPhase == "REPLACE" -> PrimaryButton(stringResource(R.string.fbs_use_family), enabled = !writes.busy) {
                            ask(R.string.fbs_replace, R.string.fbs_replace_warning, vm::confirmReplacement)
                        }
                        family.status == "ACTIVE" && (!state.enrolled || sync.phase == SyncPhase.REPLACEMENT_REQUIRED) ->
                            PrimaryButton(stringResource(R.string.fbs_recover), enabled = !writes.busy) { vm.stage() }
                        family.status == "ACTIVE" -> PrimaryButton(stringResource(R.string.fbs_sync_now), enabled = !writes.busy, onClick = vm::synchronize)
                    }
                }
                val uid = state.account?.uid
                if (state.journalPhase == "REPLACE") item {
                    TextButton(onClick = vm::cancelReplacement, enabled = !writes.busy) { Text(stringResource(R.string.dialog_cancel)) }
                }
                if (uid in family.admins && family.status != "DELETING") {
                    item {
                        Text(stringResource(R.string.fbs_invite), style = SectionStyle, color = c.text)
                        FieldBox(stringResource(R.string.fbs_email), email, { if (it.length <= 254) email = it })
                        TextButton(onClick = { vm.invite(email) }, enabled = !writes.busy && '@' in email && family.status == "ACTIVE") { Text(stringResource(R.string.fbs_send_invite)) }
                        TextButton(onClick = { ask(R.string.fbs_cancel_invites, R.string.fbs_cancel_invites_warning, vm::cancelInvitations) }, enabled = !writes.busy) {
                            Text(stringResource(R.string.fbs_cancel_invites))
                        }
                    }
                }
                item { Text(stringResource(R.string.fbs_members), style = SectionStyle, color = c.text) }
                items(family.members.entries.toList(), key = { it.key }) { (memberUid, memberId) ->
                    OutlinedBox(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(state.names[memberId] ?: stringResource(R.string.fbs_member), style = BodyStyle, color = c.text)
                                Text(stringResource(when (memberUid) { family.owner -> R.string.fbs_owner; in family.admins -> R.string.fbs_admin; else -> R.string.fbs_member }), style = CaptionStyle, color = c.muted)
                            }
                            if (uid in family.admins && memberUid != family.owner && family.status == "ACTIVE") {
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    RoundIconButton(Icons.Default.MoreVert, stringResource(R.string.fbs_member_actions), { menu = true })
                                    DropdownMenu(menu, { menu = false }) {
                                        if (uid == family.owner) {
                                            DropdownMenuItem(text = { Text(stringResource(if (memberUid in family.admins) R.string.fbs_remove_admin else R.string.fbs_make_admin)) }, onClick = {
                                                menu = false
                                                ask(R.string.fbs_roles, R.string.fbs_roles_warning) { vm.administrator(memberUid, memberUid !in family.admins) }
                                            }, enabled = !writes.busy)
                                            if (memberUid in family.admins) DropdownMenuItem(text = { Text(stringResource(R.string.fbs_transfer)) }, onClick = {
                                                menu = false; ask(R.string.fbs_transfer, R.string.fbs_transfer_warning) { vm.transfer(memberUid) }
                                            }, enabled = !writes.busy)
                                        }
                                        if (memberUid !in family.admins || uid == family.owner) DropdownMenuItem(text = { Text(stringResource(R.string.fbs_remove_member)) }, onClick = {
                                            menu = false; ask(R.string.fbs_remove_member, R.string.fbs_remove_warning) { vm.removeMember(memberUid) }
                                        }, enabled = !writes.busy)
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.devices.isNotEmpty()) item { Text(stringResource(R.string.fbs_devices), style = SectionStyle, color = c.text) }
                items(state.devices, key = { it.slot }) { device ->
                    OutlinedBox(Modifier.fillMaxWidth()) {
                        Text(device.name, style = BodyStyle, color = c.text)
                        Text(family.members[device.uid]?.let(state.names::get) ?: stringResource(R.string.fbs_member), style = CaptionStyle, color = c.muted)
                        if (uid in family.admins || uid == device.uid) TextButton(onClick = {
                            ask(R.string.fbs_remove_device, R.string.fbs_remove_device_warning) { vm.removeDevice(device.slot) }
                        }, enabled = !writes.busy) { Text(stringResource(R.string.fbs_remove_device)) }
                    }
                }
                if (uid in family.admins && family.status != "DELETING") item {
                    if (family.status == "RESTORING" || state.journalPhase == "RESTORE") TextButton(onClick = {
                        ask(R.string.fbs_cancel_restore, R.string.fbs_cancel_restore_warning, vm::cancelRestore)
                    }, enabled = !writes.busy) { Text(stringResource(R.string.fbs_cancel_restore)) }
                    if (family.status == "ACTIVE") TextButton(onClick = vm::cleanup, enabled = !writes.busy) { Text(stringResource(R.string.fbs_cleanup)) }
                    if (uid == family.owner) DangerLink(stringResource(R.string.fbs_delete), enabled = !writes.busy) {
                        ask(R.string.fbs_delete, R.string.fbs_delete_warning, vm::deleteFamily)
                    }
                }
            }
            item { TextButton(onClick = { activity?.let(vm::signOut) }, enabled = !writes.busy) { Text(stringResource(R.string.fbs_sign_out)) } }
        }
        if (state.conflicts.isNotEmpty()) item { Text(stringResource(R.string.fbs_conflicts), style = SectionStyle, color = c.text) }
        if (state.journalPhase in setOf("CREATE", "RESTORE")) item {
            TextButton(onClick = { ask(R.string.fbs_abandon, R.string.fbs_abandon_warning, vm::abandonRecovery) }, enabled = !writes.busy) { Text(stringResource(R.string.fbs_abandon)) }
        }
        if (state.quarantined > 0) item { Text(stringResource(R.string.fbs_quarantined, state.quarantined), color = c.orange) }
        items(state.conflicts, key = { it.operation.id }) { conflict ->
            OutlinedBox(Modifier.fillMaxWidth()) {
                val member = state.family?.members?.get(conflict.operation.authorUid)?.let(state.names::get)
                Text(member ?: stringResource(R.string.fbs_other_phone), style = SectionStyle, color = c.text)
                conflict.operation.events.forEach { Text(eventDescription(it.event), style = BodyStyle, color = c.text) }
                Text(stringResource(if (conflict.reason == "WAITING_FOR_DEPENDENCY") R.string.fbs_waiting_change else R.string.fbs_conflict_hint), style = CaptionStyle, color = c.muted)
                TextButton(onClick = { ask(R.string.fbs_discard, R.string.fbs_discard_warning) { vm.discard(conflict.operation.id) } }, enabled = !writes.busy && state.account != null) {
                    Text(stringResource(R.string.fbs_discard))
                }
            }
        }
        item { TextButton(onClick = onBackup, enabled = !writes.busy) { Text(stringResource(R.string.fbs_backups)) } }
        if (state.hasLocalMember) item { TextButton(onClick = onContinue, enabled = !writes.busy) { Text(stringResource(R.string.fbs_continue)) } }
    }
    confirmation?.let { dialog -> ConfirmDialog(stringResource(dialog.title), stringResource(R.string.dialog_ok), writes.busy,
        onConfirm = { confirmed = true; dialog.execute() }, onDismiss = { confirmation = null }) {
        Text(stringResource(dialog.body), color = c.text)
        WriteFeedback(vm.writes, R.string.fbs_action_failed)
    } }
}

internal fun displayTime(instant: java.time.Instant): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(instant.atZone(ZoneId.systemDefault()))

@Composable
private fun eventDescription(event: Event): String = when (event) {
    is TransactionAdded -> "${stringResource(R.string.fbs_transaction)}: ${event.transaction.money.amount.toPlainString()} ${event.transaction.money.currency} · ${event.transaction.date}"
    is TransactionEdited -> "${stringResource(R.string.fbs_transaction)}: ${event.transaction.money.amount.toPlainString()} ${event.transaction.money.currency} · ${event.transaction.date}"
    is TransactionDeleted -> stringResource(R.string.fbs_transaction_deleted)
    is AccountCreated -> event.account.name
    is AccountUpdated -> event.account.name
    is LoanCreated -> event.loan.name
    is LoanUpdated -> event.loan.name
    is GoalSet -> event.goal.name
    is CategoryCreated -> event.category.name
    is CategoryUpdated -> event.category.name
    is LoanPaymentAdded -> "${stringResource(R.string.fbs_payment)}: ${event.payment.total.amount.toPlainString()} ${event.payment.total.currency}"
    else -> stringResource(R.string.fbs_financial_change)
}

private fun SyncPhase.label(): Int = when (this) {
    SyncPhase.LOCAL -> R.string.fbs_local
    SyncPhase.SIGNED_OUT -> R.string.fbs_signed_out
    SyncPhase.READY -> R.string.fbs_ready
    SyncPhase.RUNNING -> R.string.fbs_running
    SyncPhase.REPLACEMENT_REQUIRED -> R.string.fbs_replacement_needed
    SyncPhase.RESTORING -> R.string.fbs_restoring
    SyncPhase.CLEANUP_REQUIRED -> R.string.fbs_cleanup_needed
    SyncPhase.REMOVED -> R.string.fbs_removed
    SyncPhase.CAPACITY -> R.string.fbs_capacity
    SyncPhase.UPDATE_REQUIRED -> R.string.fbs_update
    SyncPhase.ERROR -> R.string.fbs_error
}
