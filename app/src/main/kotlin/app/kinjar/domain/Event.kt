package app.kinjar.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.YearMonth

@Serializable
sealed interface Event

@Serializable @SerialName("ExchangeRateOverrideSet")
data class ExchangeRateOverrideSet(val currency: Currency, val rate: String?) : Event {
    init {
        require(currency != Currency.RSD)
        require(rate == null || parseRate(rate) != null)
    }
}

@Serializable @SerialName("MemberCreated")
data class MemberCreated(val member: Member) : Event

@Serializable @SerialName("AccountCreated")
data class AccountCreated(val account: Account) : Event

@Serializable @SerialName("AccountArchived")
data class AccountArchived(val accountId: String) : Event

/** Full display order of the accounts the device knew; accounts missing from the list sort after it. */
@Serializable @SerialName("AccountsReordered")
data class AccountsReordered(val accountIds: List<String>) : Event {
    init {
        require(accountIds.toSet().size == accountIds.size) { "Duplicate account in order" }
    }
}

/** Goal priority, first funded first; goals missing from the list sort after it. */
@Serializable @SerialName("GoalsReordered")
data class GoalsReordered(val goalIds: List<String>) : Event {
    init {
        require(goalIds.toSet().size == goalIds.size) { "Duplicate goal in order" }
    }
}

@Serializable @SerialName("CategoryCreated")
data class CategoryCreated(val category: Category) : Event

@Serializable @SerialName("CategoryUpdated")
data class CategoryUpdated(val category: Category) : Event

@Serializable @SerialName("CategoryArchived")
data class CategoryArchived(val categoryId: String) : Event

@Serializable @SerialName("AccountUpdated")
data class AccountUpdated(val account: Account) : Event

@Serializable @SerialName("MemberRenamed")
data class MemberRenamed(val memberId: String, val displayName: String) : Event

@Serializable @SerialName("TransactionAdded")
data class TransactionAdded(
    val transaction: Transaction,
    val receipt: Receipt? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val receiptDeletionId: String? = null,
) : Event

@Serializable @SerialName("TransactionEdited")
data class TransactionEdited(val transaction: Transaction) : Event

@Serializable @SerialName("TransactionDeleted")
data class TransactionDeleted(val transactionId: String) : Event

@Serializable @SerialName("BudgetSet")
data class BudgetSet(val budget: Budget) : Event

@Serializable @SerialName("BudgetRemoved")
data class BudgetRemoved(
    val categoryId: String,
    @Serializable(with = YearMonthSerializer::class) val month: YearMonth,
) : Event

@Serializable @SerialName("RecurringRuleSet")
data class RecurringRuleSet(val rule: RecurringRule) : Event

@Serializable @SerialName("RecurringRuleArchived")
data class RecurringRuleArchived(val ruleId: String) : Event

@Serializable @SerialName("BillSkipped")
data class BillSkipped(val ruleId: String, val period: String) : Event

@Serializable @SerialName("GoalSet")
data class GoalSet(val goal: Goal) : Event

@Serializable @SerialName("GoalArchived")
data class GoalArchived(val goalId: String) : Event

@Serializable @SerialName("GoalAllocated")
data class GoalAllocated(val allocation: GoalAllocation) : Event

@Serializable @SerialName("GoalAllocationDeleted")
data class GoalAllocationDeleted(val allocationId: String) : Event

data class EventRecord(
    val id: String,
    val deviceId: String,
    val seq: Long,
    val occurredAt: Instant,
    val event: Event,
)

val EventJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}
