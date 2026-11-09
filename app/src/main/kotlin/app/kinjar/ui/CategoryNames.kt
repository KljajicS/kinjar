package app.kinjar.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.kinjar.R
import app.kinjar.data.StarterCategories

@StringRes
fun categoryLabel(id: String): Int? = when (id) {
    "cat-groceries" -> R.string.cat_groceries
    "cat-bills" -> R.string.cat_bills
    "cat-transport" -> R.string.cat_transport
    "cat-eating-out" -> R.string.cat_eating_out
    "cat-household" -> R.string.cat_household
    "cat-kids" -> R.string.cat_kids
    "cat-fun" -> R.string.cat_fun
    "cat-health" -> R.string.cat_health
    "cat-other" -> R.string.cat_other
    "cat-income-salary" -> R.string.cat_income_salary
    "cat-income-bonus" -> R.string.cat_income_bonus
    "cat-income-freelance" -> R.string.cat_income_freelance
    "cat-income-gifts" -> R.string.cat_income_gifts
    "cat-income-interest" -> R.string.cat_income_interest
    "cat-income-other" -> R.string.cat_income_other
    else -> null
}

/** English names the fresh-setup income starters are seeded with; must match the seeded events exactly. */
internal val IncomeStarterNames = mapOf(
    "cat-income-salary" to "Salary",
    "cat-income-bonus" to "Bonus",
    "cat-income-freelance" to "Freelance",
    "cat-income-gifts" to "Gifts",
    "cat-income-interest" to "Interest",
    "cat-income-other" to "Other income",
)

private val starterIds = (StarterCategories.all.map { it.id } + IncomeStarterNames.keys).distinct()

internal fun starterSeedName(id: String): String? = StarterCategories.all.firstOrNull { it.id == id }?.name ?: IncomeStarterNames[id]

/** Starters keep their seeded order; user categories follow in their stored order. */
fun starterRank(id: String): Int = starterIds.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it }

/** Translated while a starter category keeps its seeded name; once renamed, the stored name wins. */
@Composable
fun categoryDisplayName(id: String, storedName: String?): String {
    val seed = starterSeedName(id)
    val label = categoryLabel(id)
    return if (seed != null && label != null && (storedName == null || storedName == seed)) stringResource(label) else storedName ?: id
}

/** Order is the swatch order; hex values stay stable because existing categories store them. */
val CategoryPalette = listOf(
    "#3D4BD6", "#D06A1E", "#1A8A7B", "#7B55C7", "#6E747D", "#B8467F", "#A88412", "#2A7FC0",
    "#C0392B", "#16A085", "#8E44AD", "#2C3E50",
    "#6B8E23", "#8B5A2B", "#2E7D32", "#0097A7", "#D81B60", "#E09F00", "#6A1B4D", "#9C8FD9",
)

/** First palette color no category uses yet, so a new category stands apart; reuse starts once all are taken. */
fun unusedPaletteColor(usedColors: Collection<String>): String =
    CategoryPalette.firstOrNull { hex -> usedColors.none { it.equals(hex, ignoreCase = true) } } ?: CategoryPalette.first()

/** Names collide after trimming, regardless of case. */
fun isDuplicateCategoryName(name: String, takenNames: Collection<String>): Boolean {
    val wanted = name.trim().lowercase()
    return wanted.isNotEmpty() && takenNames.any { it.trim().lowercase() == wanted }
}
