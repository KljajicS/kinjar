package app.kinjar.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.vector.ImageVector
import app.kinjar.R
import app.kinjar.domain.CategoryKind

class CategoryIconOption(val key: String, val icon: ImageVector, @StringRes val label: Int)

/** Keys are stored in synced category events, so existing keys must never be renamed or removed. */
val CategoryIconCatalog = listOf(
    CategoryIconOption("cart", Icons.Outlined.ShoppingCart, R.string.category_icon_cart),
    CategoryIconOption("receipt", Icons.Outlined.Receipt, R.string.category_icon_receipt),
    CategoryIconOption("car", Icons.Outlined.DirectionsCar, R.string.category_icon_car),
    CategoryIconOption("restaurant", Icons.Outlined.Restaurant, R.string.category_icon_restaurant),
    CategoryIconOption("home", Icons.Outlined.Home, R.string.category_icon_home),
    CategoryIconOption("child", Icons.Outlined.ChildCare, R.string.category_icon_child),
    CategoryIconOption("celebration", Icons.Outlined.Celebration, R.string.category_icon_celebration),
    CategoryIconOption("health", Icons.Outlined.LocalHospital, R.string.category_icon_health),
    CategoryIconOption("more", Icons.Outlined.MoreHoriz, R.string.category_icon_more),
    CategoryIconOption("work", Icons.Outlined.Work, R.string.category_icon_work),
    CategoryIconOption("star", Icons.Outlined.Star, R.string.category_icon_star),
    CategoryIconOption("laptop", Icons.Outlined.Laptop, R.string.category_icon_laptop),
    CategoryIconOption("gift", Icons.Outlined.CardGiftcard, R.string.category_icon_gift),
    CategoryIconOption("percent", Icons.Outlined.Percent, R.string.category_icon_percent),
    CategoryIconOption("payments", Icons.Outlined.Payments, R.string.category_icon_payments),
    CategoryIconOption("category", Icons.Outlined.Category, R.string.category_icon_category),
    CategoryIconOption("savings", Icons.Outlined.Savings, R.string.category_icon_savings),
    CategoryIconOption("bank", Icons.Outlined.AccountBalance, R.string.category_icon_bank),
    CategoryIconOption("shopping", Icons.Outlined.ShoppingBag, R.string.category_icon_shopping),
    CategoryIconOption("clothes", Icons.Outlined.Checkroom, R.string.category_icon_clothes),
    CategoryIconOption("utilities", Icons.Outlined.Bolt, R.string.category_icon_utilities),
    CategoryIconOption("fuel", Icons.Outlined.LocalGasStation, R.string.category_icon_fuel),
    CategoryIconOption("phone", Icons.Outlined.PhoneAndroid, R.string.category_icon_phone),
    CategoryIconOption("coffee", Icons.Outlined.LocalCafe, R.string.category_icon_coffee),
    CategoryIconOption("movie", Icons.Outlined.Movie, R.string.category_icon_movie),
    CategoryIconOption("fitness", Icons.Outlined.FitnessCenter, R.string.category_icon_fitness),
    CategoryIconOption("school", Icons.Outlined.School, R.string.category_icon_school),
    CategoryIconOption("pets", Icons.Outlined.Pets, R.string.category_icon_pets),
    CategoryIconOption("flight", Icons.Outlined.Flight, R.string.category_icon_flight),
)

private val catalogByKey = CategoryIconCatalog.associateBy { it.key }

private val starterIconKeys = mapOf(
    "cat-groceries" to "cart",
    "cat-bills" to "receipt",
    "cat-transport" to "car",
    "cat-eating-out" to "restaurant",
    "cat-household" to "home",
    "cat-kids" to "child",
    "cat-fun" to "celebration",
    "cat-health" to "health",
    "cat-other" to "more",
    "cat-income-salary" to "work",
    "cat-income-bonus" to "star",
    "cat-income-freelance" to "laptop",
    "cat-income-gifts" to "gift",
    "cat-income-interest" to "percent",
    "cat-income-other" to "more",
)

/** The icon a category shows when it stores none, or one this build does not know. */
fun defaultIconKey(categoryId: String?, kind: CategoryKind): String =
    starterIconKeys[categoryId] ?: if (kind == CategoryKind.INCOME) "payments" else "category"

/** A stored key wins, then the starter icon for [categoryId], then a generic icon for [kind]. */
fun categoryIconKey(key: String?, categoryId: String?, kind: CategoryKind): String =
    key?.takeIf { it in catalogByKey } ?: defaultIconKey(categoryId, kind)

fun categoryIcon(key: String?, categoryId: String?, kind: CategoryKind): ImageVector =
    catalogByKey.getValue(categoryIconKey(key, categoryId, kind)).icon
