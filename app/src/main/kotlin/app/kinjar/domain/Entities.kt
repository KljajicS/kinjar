package app.kinjar.domain

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate
import java.time.YearMonth

/** Legacy: every active balance now counts as savings. Kept so older events and rows still decode. */
enum class AccountKind { SPENDING, SAVINGS }
enum class TransactionType { EXPENSE, INCOME, TRANSFER, LOAN_RECEIPT }
enum class TransactionSource { MANUAL, RECEIPT, RECURRING }
enum class CategoryKind { EXPENSE, INCOME }

@Serializable
data class Member(val id: String, val displayName: String, val deviceId: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Account(
    val id: String,
    val name: String,
    val currency: Currency,
    val kind: AccountKind = AccountKind.SPENDING,
    val archived: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val excludedFromGoals: Boolean = false,
)

/** [kind] never changes after creation. New fields are omitted at their defaults so legacy events keep their exact bytes. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Category(
    val id: String,
    val name: String,
    val color: String,
    val archived: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val kind: CategoryKind = CategoryKind.EXPENSE,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val icon: String? = null,
)

/**
 * [received] is what a transfer credits to its destination when the two accounts use different currencies; it is null
 * otherwise, and then the destination receives [money].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Transaction(
    val id: String,
    val type: TransactionType,
    val money: Money,
    val accountId: String,
    val toAccountId: String?,
    val categoryId: String?,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val note: String,
    val merchant: String,
    val paidBy: String,
    val source: TransactionSource,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val received: Money? = null,
) {
    init {
        require(!money.isNegative && !money.isZero) { "Transaction amount must be positive" }
        received?.let {
            require(type == TransactionType.TRANSFER) { "Only transfers receive another amount" }
            require(!it.isNegative && !it.isZero) { "Received amount must be positive" }
            require(it.currency != money.currency) { "Same currency transfers receive the sent amount" }
        }
        if (type == TransactionType.TRANSFER) {
            require(toAccountId != null && toAccountId != accountId) { "Transfer needs a different destination account" }
        } else {
            require(toAccountId == null) { "Only transfers have a destination account" }
        }
    }
}

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

enum class Cadence { MONTHLY, YEARLY }

@Serializable
data class RecurringRule(
    val id: String,
    val name: String,
    val money: Money,
    val accountId: String,
    val categoryId: String,
    val cadence: Cadence,
    @Serializable(with = LocalDateSerializer::class) val firstDue: LocalDate,
    val archived: Boolean = false,
) {
    init {
        require(!money.isNegative && !money.isZero) { "Bill amount must be positive" }
        // Match the date picker's range and bound recurrence expansion from imported history.
        require(firstDue.year in 1900..2100) { "Bill start date must be between 1900 and 2100" }
    }
}

@Serializable
data class Goal(
    val id: String,
    val name: String,
    val target: Money,
    /** Legacy contribution, no longer used; zero for new goals. */
    val monthly: Money = Money.zero(target.currency),
    val archived: Boolean = false,
) {
    init {
        require(!target.isNegative && !target.isZero) { "Goal target must be positive" }
        require(!monthly.isNegative) { "Goal contribution cannot be negative" }
        require(target.currency == monthly.currency) { "Goal target and contribution must share a currency" }
    }
}

/** Legacy explicit goal funding. Goals now fill from total savings; old events still replay into their table. */
@Serializable
data class GoalAllocation(
    val id: String,
    val goalId: String,
    val money: Money,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
) {
    init {
        require(!money.isNegative && !money.isZero) { "Allocation must be positive" }
    }
}

/** Legacy monthly limit. Budgets are gone from the app; old events still replay into their table. */
@Serializable
data class Budget(
    val categoryId: String,
    @Serializable(with = YearMonthSerializer::class) val month: YearMonth,
    val money: Money,
)

object YearMonthSerializer : KSerializer<YearMonth> {
    override val descriptor = PrimitiveSerialDescriptor("YearMonth", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: YearMonth) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): YearMonth = YearMonth.parse(decoder.decodeString())
}
