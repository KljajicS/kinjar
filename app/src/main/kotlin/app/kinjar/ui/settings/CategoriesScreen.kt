package app.kinjar.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import app.kinjar.ui.WriteFeedback
import app.kinjar.R
import app.kinjar.domain.Category
import app.kinjar.domain.CategoryKind
import app.kinjar.ui.CategoryIconCatalog
import app.kinjar.ui.CategoryPalette
import app.kinjar.ui.categoryDisplayName
import app.kinjar.ui.categoryIcon
import app.kinjar.ui.categoryIconKey
import app.kinjar.ui.isDuplicateCategoryName
import app.kinjar.ui.theme.BodyStyle
import app.kinjar.ui.theme.AddButton
import app.kinjar.ui.theme.ConfirmDialog
import app.kinjar.ui.theme.DangerLink
import app.kinjar.ui.theme.FieldBox
import app.kinjar.ui.theme.LabelStyle
import app.kinjar.ui.theme.LoadingOrError
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.PrimaryButton
import app.kinjar.ui.theme.SelectPill
import app.kinjar.ui.theme.colorOf
import app.kinjar.ui.theme.sans

@Composable
fun CategoriesScreen(onBack: () -> Unit, onNew: (CategoryKind) -> Unit, onEdit: (String) -> Unit, vm: CategoriesViewModel = hiltViewModel()) {
    val state by vm.categories.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val groups = state ?: return LoadingOrError(failed, vm::retry)
    val c = LocalColors.current

    LazyColumn(
        Modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ScreenHeader(stringResource(R.string.categories_title), onBack) }
                AddButton(stringResource(R.string.category_new_title)) { onNew(CategoryKind.EXPENSE) }
            }
        }
        categorySection(R.string.categories_expense, groups.expense, onEdit) {
            Text(stringResource(R.string.categories_empty), style = BodyStyle, color = c.muted)
        }
        categorySection(R.string.categories_income, groups.income, onEdit) {
            Text(stringResource(R.string.categories_income_empty), style = BodyStyle, color = c.muted)
            OutlinedBox(Modifier.fillMaxWidth().padding(top = 8.dp), radius = 14, padding = PaddingValues(14.dp), onClick = { onNew(CategoryKind.INCOME) }) {
                Text(stringResource(R.string.category_new_title), style = sans(15, FontWeight.Medium), color = c.primary)
            }
        }
    }
}

private fun LazyListScope.categorySection(title: Int, categories: List<Category>, onEdit: (String) -> Unit, empty: @Composable () -> Unit) {
    item(key = "header:$title") {
        Text(stringResource(title), style = sans(18, FontWeight.SemiBold), color = LocalColors.current.text, modifier = Modifier.padding(top = 12.dp))
    }
    if (categories.isEmpty()) item(key = "empty:$title") { Column { empty() } }
    items(categories, key = { it.id }) { cat ->
        OutlinedBox(Modifier.fillMaxWidth(), radius = 14, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), onClick = { onEdit(cat.id) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryBadge(cat.icon, cat.id, cat.kind, cat.color)
                Text(categoryDisplayName(cat.id, cat.name), style = sans(15, FontWeight.Medium), color = LocalColors.current.text)
            }
        }
    }
}

@Composable
private fun CategoryBadge(icon: String?, id: String?, kind: CategoryKind, color: String) {
    Box(Modifier.size(32.dp).background(colorOf(color), CircleShape), contentAlignment = Alignment.Center) {
        Icon(categoryIcon(icon, id, kind), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun EditCategoryScreen(onClose: () -> Unit, vm: EditCategoryViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val write by vm.writes.state.collectAsStateWithLifecycle()
    val c = LocalColors.current
    var confirming by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.done) { if (s.done) onClose() }

    Column(
        Modifier.fillMaxSize().background(c.bg).imePadding().padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(stringResource(if (s.editing) R.string.category_edit_title else R.string.category_new_title), onClose)
        if (!s.loaded) return@Column LoadingOrError(s.loadFailed, vm::retryLoad)
        // Translated starter names count too, so "Namirnice" clashes with a seeded "Groceries".
        val nameTaken = s.nameTaken || isDuplicateCategoryName(s.name, s.sameKind.map { categoryDisplayName(it.id, it.name) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FieldBox(stringResource(R.string.category_name), s.name, vm::setName)
            if (nameTaken) Text(stringResource(R.string.category_name_taken), style = LabelStyle, color = c.orange)

            Text(stringResource(R.string.category_kind), style = LabelStyle, color = c.muted)
            if (s.editing) {
                Text(stringResource(kindLabel(s.kind)), style = sans(15, FontWeight.Medium), color = c.text)
                Text(stringResource(R.string.category_kind_locked), style = LabelStyle, color = c.muted)
            } else {
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryKind.entries.forEach { kind -> SelectPill(stringResource(kindLabel(kind)), s.kind == kind, { vm.setKind(kind) }) }
                }
            }

            Text(stringResource(R.string.category_icon), style = LabelStyle, color = c.muted)
            val selectedIcon = categoryIconKey(s.icon, s.id, s.kind)
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CategoryIconCatalog.forEach { option ->
                    val selected = option.key == selectedIcon
                    val label = stringResource(option.label)
                    val shape = RoundedCornerShape(12.dp)
                    Box(
                        Modifier.selectable(selected = selected, role = Role.RadioButton) { vm.setIcon(option.key) }
                            .size(48.dp).clip(shape)
                            .background(if (selected) colorOf(s.color) else c.surface2, shape)
                            .border(if (selected) 2.dp else 0.dp, if (selected) c.text else Color.Transparent, shape)
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) { Icon(option.icon, contentDescription = null, tint = if (selected) Color.White else c.text, modifier = Modifier.size(22.dp)) }
                }
            }

            Text(stringResource(R.string.category_color), style = LabelStyle, color = c.muted)
            // 48dp targets with 2dp gaps keep the original 40dp swatch on a 50dp pitch.
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CategoryPalette.forEachIndexed { index, hex ->
                    val selected = s.color.equals(hex, ignoreCase = true)
                    val name = stringResource(PaletteNames[index])
                    Box(
                        Modifier.selectable(selected = selected, role = Role.RadioButton) { vm.setColor(hex) }
                            .minimumInteractiveComponentSize().size(40.dp).clip(CircleShape)
                            .border(if (selected) 3.dp else 0.dp, if (selected) c.text else Color.Transparent, CircleShape)
                            .padding(if (selected) 5.dp else 0.dp)
                            .background(colorOf(hex), CircleShape)
                            .semantics { contentDescription = name },
                    )
                }
            }
            if (s.editing) {
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.category_delete_hint), style = LabelStyle, color = c.muted)
                DangerLink(stringResource(R.string.category_delete), enabled = !write.busy) { confirming = true }
            }
        }
        WriteFeedback(vm.writes)
        PrimaryButton(stringResource(R.string.category_save), enabled = s.canSave && !nameTaken && !write.busy, onClick = vm::save)
    }

    if (confirming && s.loaded) {
        ConfirmDialog(stringResource(R.string.audit_ui_delete_title, categoryDisplayName(s.id.orEmpty(), s.name.trim())), stringResource(R.string.category_delete), write.busy, vm::delete, { confirming = false }) {
            Text(stringResource(R.string.category_delete_hint), style = sans(14), color = c.text)
            Text(stringResource(R.string.audit_ui_delete_irreversible), style = sans(14), color = c.orange)
            WriteFeedback(vm.writes)
        }
    }
}

internal fun kindLabel(kind: CategoryKind) = when (kind) {
    CategoryKind.EXPENSE -> R.string.category_kind_expense
    CategoryKind.INCOME -> R.string.category_kind_income
}

/** Spoken names for [CategoryPalette], in the same order. */
internal val PaletteNames = listOf(
    R.string.recovery_color_indigo, R.string.recovery_color_orange, R.string.recovery_color_teal, R.string.recovery_color_purple,
    R.string.recovery_color_gray, R.string.recovery_color_pink, R.string.recovery_color_mustard, R.string.recovery_color_blue,
    R.string.recovery_color_red, R.string.recovery_color_green, R.string.recovery_color_violet, R.string.recovery_color_navy,
    R.string.category_color_olive, R.string.category_color_brown, R.string.category_color_forest, R.string.category_color_cyan,
    R.string.category_color_magenta, R.string.category_color_amber, R.string.category_color_plum, R.string.category_color_lavender,
)
