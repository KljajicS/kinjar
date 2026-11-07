package app.kinjar.ui.sync

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
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
fun FamilyScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onBackup: () -> Unit,
    startSignIn: Boolean = false,
    onCreate: (() -> Unit)? = null,
    vm: FamilyViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sync by vm.status.collectAsStateWithLifecycle()
    val writes by vm.writes.state.collectAsStateWithLifecycle()
    val c = LocalColors.current
    val activity = LocalActivity.current
    var email by remember { mutableStateOf("") }
    var inviting by rememberSaveable { mutableStateOf(false) }
    var phonesOpen by rememberSaveable { mutableStateOf(false) }
    var signInPending by rememberSaveable { mutableStateOf(startSignIn) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    val idle = !writes.busy
    val otherConflicts = state.conflicts.filter { conflict ->
        conflict.operation.events.isEmpty() || conflict.operation.events.any { it.event is BillSkipped } ||
            conflict.operation.resources.none { (key, access) -> key.startsWith("transaction:") && access == ResourceAccess.WRITE }
    }
    fun ask(title: Int, body: Int, execute: () -> Unit) { confirmation = Confirmation(title, body, execute); confirmed = false }
    fun createFamily() { if (state.journalPhase != "CREATE" && !state.hasLocalMember && onCreate != null) onCreate() else vm.create() }
    LaunchedEffect(writes.busy, writes.failed) { if (confirmed && !writes.busy && !writes.failed) confirmation = null }
    LaunchedEffect(state.completedEnrollment, writes.busy) { if (state.completedEnrollment && !writes.busy) onContinue() }
    var cloudSetupOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.needsCloudSetup, writes.busy) {
        if (state.needsCloudSetup && !writes.busy && !cloudSetupOpened && onCreate != null) {
            cloudSetupOpened = true
            onCreate()
        }
    }
    // Cleared before the attempt so a dismissed or failed Google prompt is not reopened.
    LaunchedEffect(signInPending, writes.busy, activity) {
        if (signInPending && !writes.busy && activity != null) {
            signInPending = false
            if (vm.configured) {
                if (state.account == null) vm.signIn(activity) else vm.continueSignIn()
            }
        }
    }
    androidx.activity.compose.BackHandler(enabled = writes.busy || state.hasLocalMember) { if (!writes.busy) onContinue() }

    LazyColumn(Modifier.fillMaxSize().background(c.bg).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader(stringResource(R.string.fbs_family)) { if (!writes.busy) { if (state.hasLocalMember) onContinue() else onBack() } } }
        item {
            OutlinedBox(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(10.dp).background(sync.phase.tone(), CircleShape))
                        Column(Modifier.weight(1f)) {
                            Text(state.account?.email ?: stringResource(R.string.fbs_optional), style = SectionStyle, color = c.text)
                            Text(stringResource(when {
                                state.account == null -> R.string.fbs_signed_out
                                state.family?.status == "ACTIVE" && !state.sessionMatchesCloud -> R.string.fbs_replacement_needed
                                state.family == null && !state.enrolled && sync.phase == SyncPhase.REPLACEMENT_REQUIRED -> R.string.fbs_local
                                else -> sync.phase.label()
                            }),
                                style = LabelStyle, color = c.muted)
                        }
                        if (state.account != null) Overflow(stringResource(R.string.fd_account_actions)) { close ->
                            MenuAction(stringResource(R.string.fbs_refresh), idle, close, onClick = vm::refresh)
                            MenuAction(stringResource(R.string.fbs_sign_out), idle, close) { activity?.let(vm::signOut) }
                        }
                    }
                    if (sync.pending > 0) Text(stringResource(R.string.fbs_pending, sync.pending), style = CaptionStyle, color = c.muted)
                    sync.lastSuccess?.let { Text(stringResource(R.string.fbs_last_sync, displayTime(it)), style = CaptionStyle, color = c.muted) }
                    state.notice?.let { Text(stringResource(it), style = BodyStyle, color = c.text) }
                    WriteFeedback(vm.writes, state.failureText)
                    if (state.quarantined > 0) Text(stringResource(R.string.fbs_quarantined, state.quarantined), style = CaptionStyle, color = c.orange)
                    if (state.account == null || state.family == null) Text(stringResource(R.string.fbs_privacy), style = CaptionStyle, color = c.muted)
                    if (!vm.configured) Text(stringResource(R.string.fbs_unavailable), style = BodyStyle, color = c.muted)
                    else if (state.account == null) PrimaryButton(stringResource(R.string.fbs_google), enabled = idle && activity != null) { activity?.let(vm::signIn) }
                    else if (state.recoveryOtherAccount) Text(stringResource(R.string.fbs_recovery_other_account), style = BodyStyle, color = c.orange)
                    else if (state.enrolled && !state.sessionMatchesAccount && state.family == null) {
                        Text(stringResource(R.string.fd_other_account), style = BodyStyle, color = c.orange)
                    }
                    else state.family?.let { family ->
                        when {
                            state.journalPhase == "SWITCH" -> {
                                Text(stringResource(R.string.fd_join_progress), style = BodyStyle, color = c.muted)
                                if (sync.phase == SyncPhase.ERROR || writes.failed) TextButton(onClick = vm::synchronize, enabled = idle) {
                                    Text(stringResource(R.string.fbs_retry))
                                }
                            }
                            family.status == "DELETING" -> DangerLink(stringResource(R.string.fbs_resume_delete), enabled = idle) {
                                ask(R.string.fbs_delete, R.string.fbs_delete_warning, vm::deleteFamily)
                            }
                            state.journalPhase == "CREATE" -> PrimaryButton(stringResource(R.string.fbs_resume_setup), enabled = idle, onClick = vm::create)
                            state.journalPhase == "RESTORE" -> {
                                Text(stringResource(R.string.fbs_restore_progress), style = BodyStyle, color = c.muted)
                                if (sync.phase == SyncPhase.ERROR || writes.failed) Row {
                                    TextButton(onClick = vm::resumeRestore, enabled = idle) { Text(stringResource(R.string.fbs_retry)) }
                                    TextButton(onClick = {
                                        ask(R.string.fbs_cancel_restore, R.string.fbs_cancel_restore_warning, vm::cancelRestore)
                                    }, enabled = idle) { Text(stringResource(R.string.dialog_cancel)) }
                                }
                            }
                            state.journalPhase == "REPLACE" -> PrimaryButton(stringResource(R.string.fbs_use_family), enabled = idle) {
                                ask(R.string.fbs_replace, R.string.fbs_replace_warning) { vm.stage() }
                            }
                            family.status == "ACTIVE" && (!state.sessionMatchesCloud || sync.phase == SyncPhase.REPLACEMENT_REQUIRED) ->
                                PrimaryButton(stringResource(R.string.fbs_use_family), enabled = idle) {
                                    ask(R.string.fbs_replace, R.string.fbs_replace_warning) { vm.stage() }
                                }
                            family.status == "RESTORING" -> {
                                Text(stringResource(R.string.fbs_restore_waiting), style = BodyStyle, color = c.muted)
                                if (state.account?.uid in family.admins) {
                                    Text(stringResource(R.string.fbs_restore_timeout), style = CaptionStyle, color = c.muted)
                                    TextButton(onClick = {
                                        ask(R.string.fbs_cancel_restore, R.string.fbs_cancel_restore_warning, vm::cancelRestore)
                                    }, enabled = idle) { Text(stringResource(R.string.dialog_cancel)) }
                                }
                            }
                            sync.phase in setOf(SyncPhase.ERROR, SyncPhase.CLEANUP_REQUIRED) -> TextButton(onClick = vm::synchronize, enabled = idle) { Text(stringResource(R.string.fbs_retry)) }
                        }
                        if (state.journalPhase == "REPLACE") TextButton(onClick = vm::cancelReplacement, enabled = idle) { Text(stringResource(R.string.dialog_cancel)) }
                    }
                    if (state.journalPhase == "CREATE" && writes.failed) TextButton(onClick = {
                        ask(R.string.fbs_abandon, R.string.fbs_abandon_warning, vm::abandonRecovery)
                    }, enabled = idle) { Text(stringResource(R.string.fbs_abandon)) }
                }
            }
        }
        val signedIn = vm.configured && state.account != null && !state.recoveryOtherAccount
        val family = state.family
        if (signedIn && (state.invitations.isNotEmpty() || (family == null && !state.enrolled))) item {
            OutlinedBox(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val invited = state.invitations.isNotEmpty()
                    Text(stringResource(if (invited) R.string.fbs_join_or_recover else R.string.fd_new_family), style = SectionStyle, color = c.text)
                    state.invitations.forEach { invite ->
                        key(invite.familyId + invite.slot) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.fbs_invited_by, invite.inviterEmail), style = BodyStyle, color = c.text, modifier = Modifier.weight(1f))
                                if (family == null || family.isPersonal(state.account?.uid)) TextButton(onClick = {
                                    ask(R.string.fbs_join, if (family == null) R.string.fbs_join_replace_warning else R.string.fd_join_personal_warning) { vm.stage(invite) }
                                }, enabled = idle && state.journalPhase == null) {
                                    Text(stringResource(R.string.fbs_join))
                                }
                            }
                        }
                    }
                    if (family == null && !state.enrolled) {
                        val label = stringResource(if (state.journalPhase == "CREATE") R.string.fbs_resume_setup else R.string.fd_enable)
                        if (!invited) Text(stringResource(R.string.fd_create_hint), style = CaptionStyle, color = c.muted)
                        if (!invited || state.journalPhase == "CREATE") PrimaryButton(label, enabled = idle, onClick = ::createFamily)
                        else TextButton(onClick = ::createFamily, enabled = idle) { Text(label) }
                    }
                }
            }
        }
        if (signedIn && family != null) {
            val uid = state.account?.uid
            val admin = uid in family.admins
            val canInvite = admin && family.status == "ACTIVE" && state.journalPhase == null
            val personal = family.isPersonal(uid)
            item {
                OutlinedBox(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (personal) stringResource(R.string.fd_personal_title) else stringResource(R.string.fd_count, stringResource(R.string.fbs_members), family.members.size),
                                style = SectionStyle, color = c.text, modifier = Modifier.weight(1f))
                            if (canInvite && !personal) RoundIconButton(Icons.Default.PersonAdd, stringResource(R.string.fbs_invite)) { inviting = !inviting }
                            if (admin && family.status != "DELETING") Overflow(stringResource(R.string.fd_family_actions)) { close ->
                                MenuAction(stringResource(R.string.fbs_cancel_invites), idle, close) {
                                    ask(R.string.fbs_cancel_invites, R.string.fbs_cancel_invites_warning, vm::cancelInvitations)
                                }
                                if (uid == family.owner) MenuAction(stringResource(R.string.fbs_delete), idle, close, danger = true) {
                                    ask(R.string.fbs_delete, R.string.fbs_delete_warning, vm::deleteFamily)
                                }
                            }
                        }
                        if (personal) {
                            Text(stringResource(R.string.fd_personal_hint), style = CaptionStyle, color = c.muted)
                            if (canInvite && !inviting) PrimaryButton(stringResource(R.string.fd_share), enabled = idle) { inviting = true }
                        }
                        if (inviting && canInvite) {
                            Text(stringResource(R.string.fbs_invite), style = LabelStyle, color = c.muted)
                            FieldBox(stringResource(R.string.fbs_email), email, { if (it.length <= 254) email = it })
                            TextButton(onClick = { vm.invite(email) }, enabled = idle && '@' in email) { Text(stringResource(R.string.fbs_send_invite)) }
                        }
                        if (!personal) family.members.entries.forEach { (memberUid, memberId) ->
                            key(memberUid) {
                                val name = state.names[memberId] ?: stringResource(R.string.fbs_member)
                                val role = when (memberUid) { family.owner -> R.string.fbs_owner; in family.admins -> R.string.fbs_admin; else -> R.string.fbs_member }
                                PersonRow(name, stringResource(role)) {
                                    if (admin && memberUid != family.owner && family.status == "ACTIVE") Overflow(stringResource(R.string.fbs_member_actions)) { close ->
                                        if (uid == family.owner) {
                                            MenuAction(stringResource(if (memberUid in family.admins) R.string.fbs_remove_admin else R.string.fbs_make_admin), idle, close) {
                                                ask(R.string.fbs_roles, R.string.fbs_roles_warning) { vm.administrator(memberUid, memberUid !in family.admins) }
                                            }
                                            if (memberUid in family.admins) MenuAction(stringResource(R.string.fbs_transfer), idle, close) {
                                                ask(R.string.fbs_transfer, R.string.fbs_transfer_warning) { vm.transfer(memberUid) }
                                            }
                                        }
                                        if (memberUid !in family.admins || uid == family.owner) MenuAction(stringResource(R.string.fbs_remove_member), idle, close, danger = true) {
                                            ask(R.string.fbs_remove_member, R.string.fbs_remove_warning) { vm.removeMember(memberUid) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.devices.isNotEmpty()) item {
                OutlinedBox(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    val stateText = stringResource(if (phonesOpen) R.string.fd_expanded else R.string.fd_collapsed)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { phonesOpen = !phonesOpen }.semantics { stateDescription = stateText },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.fd_count, stringResource(R.string.fbs_devices), state.devices.size), style = SectionStyle, color = c.text, modifier = Modifier.weight(1f))
                        Icon(if (phonesOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = c.muted)
                    }
                    if (phonesOpen) Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.devices.forEach { device ->
                            key(device.slot) {
                                PersonRow(device.name, family.members[device.uid]?.let(state.names::get) ?: stringResource(R.string.fbs_member)) {
                                    if (admin || uid == device.uid) Overflow(stringResource(R.string.fd_phone_actions)) { close ->
                                        MenuAction(stringResource(R.string.fbs_remove_device), idle, close, danger = true) {
                                            ask(R.string.fbs_remove_device, R.string.fbs_remove_device_warning) { vm.removeDevice(device.slot) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (otherConflicts.isNotEmpty()) item { Text(stringResource(R.string.fbs_conflicts), style = SectionStyle, color = c.text) }
        items(otherConflicts, key = { it.operation.id }) { conflict ->
            OutlinedBox(Modifier.fillMaxWidth()) {
                val member = state.family?.members?.get(conflict.operation.authorUid)?.let(state.names::get)
                Text(member ?: stringResource(R.string.fbs_other_phone), style = SectionStyle, color = c.text)
                conflict.operation.events.forEach { Text(eventDescription(it.event), style = BodyStyle, color = c.text) }
                Text(stringResource(if (conflict.reason == "WAITING_FOR_DEPENDENCY") R.string.fbs_waiting_change else R.string.fbs_conflict_hint), style = CaptionStyle, color = c.muted)
                TextButton(onClick = { ask(R.string.fbs_discard, R.string.fbs_discard_warning) { vm.discard(conflict.operation.id) } }, enabled = idle && state.account != null) {
                    Text(stringResource(R.string.fbs_discard))
                }
            }
        }
        item {
            OutlinedBox(Modifier.fillMaxWidth(), radius = 14, padding = PaddingValues(14.dp), onClick = { if (!writes.busy) onBackup() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.fbs_backups), style = sans(15, FontWeight.Medium), color = c.text)
                        Text(stringResource(R.string.fd_backup_hint), style = CaptionStyle, color = c.muted)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.muted)
                }
            }
        }
    }
    confirmation?.let { dialog -> ConfirmDialog(stringResource(dialog.title), stringResource(R.string.dialog_ok), writes.busy,
        onConfirm = { confirmed = true; dialog.execute() }, onDismiss = { confirmation = null }) {
        Text(stringResource(dialog.body), color = c.text)
        WriteFeedback(vm.writes, state.failureText)
    } }
}

@Composable
private fun PersonRow(title: String, subtitle: String, actions: @Composable () -> Unit) {
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).background(c.primarySoft, CircleShape), contentAlignment = Alignment.Center) {
            Text(title.firstOrNull()?.uppercase() ?: "?", style = sans(14, FontWeight.SemiBold), color = c.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = BodyStyle, color = c.text)
            Text(subtitle, style = CaptionStyle, color = c.muted)
        }
        actions()
    }
}

@Composable
private fun Overflow(description: String, items: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundIconButton(Icons.Default.MoreVert, description, { open = true })
        DropdownMenu(open, { open = false }) { items { open = false } }
    }
}

@Composable
private fun MenuAction(text: String, enabled: Boolean, close: () -> Unit, danger: Boolean = false, onClick: () -> Unit) {
    val c = LocalColors.current
    DropdownMenuItem(text = { Text(text, style = sans(14), color = if (danger && enabled) c.orange else if (enabled) c.text else c.muted) },
        onClick = { close(); onClick() }, enabled = enabled)
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

@Composable
private fun SyncPhase.tone() = LocalColors.current.let { c ->
    when (this) {
        SyncPhase.READY -> c.green
        SyncPhase.ERROR, SyncPhase.CAPACITY, SyncPhase.UPDATE_REQUIRED, SyncPhase.REMOVED -> c.orange
        else -> c.muted
    }
}

private fun SyncPhase.label(): Int = when (this) {
    SyncPhase.LOCAL -> R.string.fbs_local
    SyncPhase.SIGNED_OUT -> R.string.fbs_signed_out
    SyncPhase.READY -> R.string.fbs_ready
    SyncPhase.RUNNING -> R.string.fbs_running
    SyncPhase.REPLACEMENT_REQUIRED -> R.string.fbs_replacement_needed
    SyncPhase.RESTORING -> R.string.fbs_restore_progress
    SyncPhase.CLEANUP_REQUIRED -> R.string.fbs_restore_maintenance
    SyncPhase.REMOVED -> R.string.fbs_removed
    SyncPhase.CAPACITY -> R.string.fbs_capacity
    SyncPhase.UPDATE_REQUIRED -> R.string.fbs_update
    SyncPhase.ERROR -> R.string.fbs_error
}
