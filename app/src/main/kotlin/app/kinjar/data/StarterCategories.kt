package app.kinjar.data

import app.kinjar.domain.Category
import app.kinjar.domain.CategoryKind

object StarterCategories {
    val all = listOf(
        Category("cat-groceries", "Groceries", "#3D4BD6"),
        Category("cat-bills", "Bills", "#D06A1E"),
        Category("cat-transport", "Transport", "#1A8A7B"),
        Category("cat-eating-out", "Eating out", "#7B55C7"),
        Category("cat-household", "Household", "#6E747D"),
        Category("cat-kids", "Kids", "#B8467F"),
        Category("cat-fun", "Fun", "#A88412"),
        Category("cat-health", "Health", "#2A7FC0"),
        Category("cat-other", "Other", "#6E747D"),
    )

    /** Seeded by a fresh setup only; existing families add income categories themselves, so phones never race to seed them. */
    val income = listOf(
        Category("cat-income-salary", "Salary", "#2E7D32", kind = CategoryKind.INCOME, icon = "work"),
        Category("cat-income-bonus", "Bonus", "#E09F00", kind = CategoryKind.INCOME, icon = "star"),
        Category("cat-income-freelance", "Freelance", "#0097A7", kind = CategoryKind.INCOME, icon = "laptop"),
        Category("cat-income-gifts", "Gifts", "#D81B60", kind = CategoryKind.INCOME, icon = "gift"),
        Category("cat-income-interest", "Interest", "#6B8E23", kind = CategoryKind.INCOME, icon = "percent"),
        Category("cat-income-other", "Other income", "#6E747D", kind = CategoryKind.INCOME, icon = "more"),
    )
}
