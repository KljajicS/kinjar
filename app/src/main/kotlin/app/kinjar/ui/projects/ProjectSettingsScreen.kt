package app.kinjar.ui.projects

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.domain.CategoryKind
import app.kinjar.domain.ProjectCategory
import app.kinjar.ui.CategoryPalette
import app.kinjar.ui.WriteFeedback
import app.kinjar.ui.categoryDisplayName
import app.kinjar.ui.isDuplicateCategoryName
import app.kinjar.ui.settings.PaletteNames
import app.kinjar.ui.unusedPaletteColor
import app.kinjar.ui.theme.ConfirmDialog
import app.kinjar.ui.theme.DangerLink
import app.kinjar.ui.theme.FieldBox
import app.kinjar.ui.theme.LoadingOrError
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.PrimaryButton
import app.kinjar.ui.theme.SectionStyle
import app.kinjar.ui.theme.colorOf
import app.kinjar.ui.theme.sans

@Composable
fun ProjectSettingsScreen(onBack: () -> Unit, onCurrency: () -> Unit, vm: ProjectSettingsViewModel = hiltViewModel()) {
    val loaded by vm.data.collectAsStateWithLifecycle()
    val failed by vm.loadFailed.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val write by vm.writes.state.collectAsStateWithLifecycle()
    var archiving by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(write.finished) { if (write.finished) onBack() }
    val back = { if (!write.busy) onBack() }
    val title = stringResource(R.string.project_settings)
    val d = loaded
    val project = d?.project(vm.id)
    val f = form
    if (d == null || project == null || f == null) {
        ProjectPage(title, back) {
            if (d == null || (project != null && f == null)) item { LoadingOrError(failed, vm::retry) }
            else section { Hint(stringResource(R.string.project_not_found)) }
        }
        return
    }
    val c = LocalColors.current
    if (project.archived) {
        ProjectPage(title, back) {
            section("archived") {
                Text(project.name, style = SectionStyle, color = c.text)
                Hint(stringResource(R.string.project_archived_notice), c.orange)
                WriteFeedback(vm.writes)
                PrimaryButton(stringResource(R.string.project_restore), !write.busy) { vm.archive(false) }
            }
        }
        return
    }
    val budgetValid = budgetOf(f.budget, f.base.currency).isSuccess
    ProjectPage(title, back, footer = {
        if (f.base != project) Hint(stringResource(R.string.project_settings_stale), c.orange)
        WriteFeedback(vm.writes)
        PrimaryButton(stringResource(R.string.project_save), vm.canSave(f) && !write.busy, vm::save)
    }) {
        section("form") {
            FieldBox(stringResource(R.string.project_name), f.name, { text -> vm.edit { it.copy(name = text.take(200)) } })
            AmountField(stringResource(R.string.project_budget_optional), f.budget, f.base.currency) { text -> vm.edit { it.copy(budget = text) } }
            if (!budgetValid) Hint(stringResource(R.string.project_budget_invalid), c.orange)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.project_currency_value, project.currency.name), style = sans(15, FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f))
                TextButton(onClick = onCurrency, enabled = !write.busy) { Text(stringResource(R.string.project_change_currency), color = c.primary) }
            }
        }
        section("sources") {
            Text(stringResource(R.string.project_sources), style = SectionStyle, color = c.text)
            Hint(stringResource(R.string.project_sources_hint))
            d.household.filter { it.kind == CategoryKind.EXPENSE && (!it.archived || it.id in f.sources) }.forEach { category ->
                val checked = category.id in f.sources
                Row(
                    Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) { on ->
                        vm.edit { it.copy(sources = if (on) it.sources + category.id else it.sources - category.id) }
                    },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = colorOf(category.color), uncheckedColor = c.muted))
                    ColorDot(colorOf(category.color))
                    Text(categoryDisplayName(category.id, category.name), style = sans(15), color = c.text)
                }
            }
        }
        section("categories") {
            Text(stringResource(R.string.project_categories), style = SectionStyle, color = c.text)
            Hint(stringResource(R.string.project_categories_hint))
            d.categories(project.id).sortedBy { it.archived }.forEach { category ->
                OutlinedBox(Modifier.fillMaxWidth(), radius = 14, onClick = { vm.categoryWrites.clearFailure(); editing = category.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ColorDot(colorOf(category.color), 12.dp)
                        Text(category.name, style = sans(15, FontWeight.Medium), color = if (category.archived) c.muted else c.text, modifier = Modifier.weight(1f))
                        if (category.archived) Hint(stringResource(R.string.project_category_archived))
                    }
                }
            }
            TextButton(onClick = { vm.categoryWrites.clearFailure(); editing = NEW }) { Text(stringResource(R.string.project_category_add), color = c.primary) }
        }
        section("archive") {
            Hint(stringResource(R.string.project_archive_hint))
            DangerLink(stringResource(R.string.project_archive), enabled = !write.busy) { vm.writes.clearFailure(); archiving = true }
        }
    }
    if (archiving) ConfirmDialog(stringResource(R.string.project_archive_title, project.name), stringResource(R.string.project_archive), write.busy,
        { vm.archive(true) }, { archiving = false }) {
        Text(stringResource(R.string.project_archive_hint), style = sans(14), color = c.text)
        WriteFeedback(vm.writes)
    }
    editing?.let { key ->
        val categories = d.categories(project.id)
        val existing = categories.find { it.id == key }
        if (key == NEW || existing != null) CategoryEditor(existing, categories, vm) { editing = null }
    }
}

private const val NEW = "new"

@Composable
private fun CategoryEditor(existing: ProjectCategory?, all: List<ProjectCategory>, vm: ProjectSettingsViewModel, onDone: () -> Unit) {
    val base = remember(existing?.id) { existing }
    val c = LocalColors.current
    val write by vm.categoryWrites.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(existing?.color ?: unusedPaletteColor(all.filter { !it.archived }.map { it.color })) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(write.busy) { if (submitted && !write.busy && !write.failed) onDone() }
    val taken = isDuplicateCategoryName(name, all.filter { !it.archived && it.id != existing?.id }.map { it.name })
    val canSave = name.isNotBlank() && name.length <= 100 && !taken && !write.busy
    fun submit(action: () -> Unit) { submitted = true; action() }
    AlertDialog(
        onDismissRequest = { if (!write.busy) onDone() },
        containerColor = c.bg,
        title = { Text(stringResource(if (existing == null) R.string.project_category_add else R.string.project_category_edit), style = sans(18, FontWeight.SemiBold), color = c.text) },
        text = {
            Column(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FieldBox(stringResource(R.string.project_category_name), name, { name = it.take(100) })
                if (taken) Hint(stringResource(R.string.category_name_taken), c.orange)
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CategoryPalette.forEachIndexed { index, hex ->
                        val selected = color.equals(hex, ignoreCase = true)
                        val label = stringResource(PaletteNames[index])
                        Box(
                            Modifier.selectable(selected = selected, role = Role.RadioButton) { color = hex }
                                .minimumInteractiveComponentSize().size(36.dp).clip(CircleShape)
                                .border(if (selected) 3.dp else 0.dp, if (selected) c.text else Color.Transparent, CircleShape)
                                .padding(if (selected) 5.dp else 0.dp)
                                .background(colorOf(hex), CircleShape)
                                .semantics { contentDescription = label },
                        )
                    }
                }
                if (existing != null) DangerLink(
                    stringResource(if (existing.archived) R.string.project_category_restore else R.string.project_category_archive), enabled = !write.busy,
                ) { submit { vm.saveCategory(requireNotNull(base).copy(archived = !base.archived), base) } }
                WriteFeedback(vm.categoryWrites)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                submit { if (base == null) vm.newCategory(name, color) else vm.saveCategory(base.copy(name = name.trim(), color = color), base) }
            }, enabled = canSave) { Text(stringResource(R.string.project_save), color = if (canSave) c.primary else c.muted) }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !write.busy) { Text(stringResource(R.string.dialog_cancel), color = c.text) } },
    )
}
