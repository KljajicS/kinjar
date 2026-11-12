package app.kinjar.ui.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.domain.GoalProgress
import app.kinjar.ui.DragHandle
import app.kinjar.ui.ReorderColumn
import app.kinjar.ui.WriteFeedback
import app.kinjar.ui.formatAmount
import app.kinjar.ui.theme.AddButton
import app.kinjar.ui.theme.BodyStyle
import app.kinjar.ui.theme.CaptionStyle
import app.kinjar.ui.theme.LoadingOrError
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.SectionStyle
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans

/** Savings goals as a group of a parent [LazyColumn] whose [list] state drives drag autoscroll. */
fun LazyListScope.goalsGroup(vm: GoalsViewModel, list: LazyListState, onNew: () -> Unit, onOpen: (String) -> Unit) {
    item(key = "goals-header") {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.projects_goals_title), style = SectionStyle, color = LocalColors.current.text, modifier = Modifier.weight(1f))
            AddButton(stringResource(R.string.goal_new_title), onNew)
        }
    }
    item(key = "goals") {
        val state by vm.state.collectAsStateWithLifecycle()
        val failed by vm.loadFailed.collectAsStateWithLifecycle()
        val s = state ?: return@item LoadingOrError(failed, vm::retryLoad)
        if (s.goals.isEmpty()) {
            Text(stringResource(R.string.goals_empty), style = BodyStyle, color = LocalColors.current.muted)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ReorderColumn(s.goals, { it.goal.id }, s.reorderFailures, vm::reorder, list, spacing = 12.dp) { goal, index, handle, actions ->
                    GoalCard(goal, index, actions, handle) { onOpen(goal.goal.id) }
                }
                WriteFeedback(vm.writes)
            }
        }
    }
}

@Composable
private fun GoalCard(p: GoalProgress, index: Int, modifier: Modifier, handle: Modifier, onClick: () -> Unit) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    val target = p.goal.target
    val ratio = p.ratio?.toFloat()?.coerceIn(0f, 1f)
    val color = when {
        p.reached -> c.green
        ratio == null || ratio == 0f -> c.muted
        else -> c.primary
    }
    val percent = if (p.reached) 100 else ratio?.let { (it * 100).toInt() }
    OutlinedBox(modifier.fillMaxWidth(), radius = 16, padding = PaddingValues(start = 4.dp, top = 12.dp, end = 16.dp, bottom = 14.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            DragHandle(handle.align(Alignment.CenterVertically))
            Box(Modifier.padding(top = 10.dp, end = 12.dp).size(28.dp).background(c.surface2, CircleShape), contentAlignment = Alignment.Center) {
                Text("${index + 1}", style = mono(13, FontWeight.SemiBold), color = c.text)
            }
            Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.goal.name, style = sans(16, FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
                    Text(percent?.let { "$it%" } ?: "-", style = mono(16, FontWeight.SemiBold), color = color)
                }
                Box(Modifier.fillMaxWidth().height(10.dp).background(c.track, RoundedCornerShape(5.dp))) {
                    if (ratio != null && ratio > 0f) Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().background(color, RoundedCornerShape(5.dp)))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${p.funded?.let { formatAmount(it, locale = locale) } ?: "?"} / ${formatAmount(target, locale = locale)} ${target.currency}",
                        style = mono(12), color = c.muted, modifier = Modifier.weight(1f),
                    )
                    val left = p.left
                    Text(
                        when {
                            left == null -> stringResource(R.string.goals_unknown)
                            p.reached -> stringResource(R.string.goals_funded)
                            p.waiting -> stringResource(R.string.goals_waiting)
                            else -> stringResource(R.string.goals_to_go, formatAmount(left, locale = locale), left.currency.name)
                        },
                        style = CaptionStyle, color = when {
                            left == null -> c.orange
                            p.reached -> c.green
                            else -> c.muted
                        },
                    )
                }
            }
        }
    }
}
