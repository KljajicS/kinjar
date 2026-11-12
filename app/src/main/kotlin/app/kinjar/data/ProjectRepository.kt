package app.kinjar.data

import app.cash.sqldelight.coroutines.asFlow
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class ProjectBook(
    val projects: List<Project>,
    val categories: List<ProjectCategory>,
    val costs: List<ProjectCost>,
    val transactions: List<Transaction>,
) {
    private val byTransaction by lazy { transactions.associateBy { it.id } }

    /** Costs shown for [projectId]; a cost whose linked expense was deleted or is no longer an expense is left out. */
    fun costsFor(projectId: String): List<ProjectCost> = costs.filter {
        it.projectId == projectId && (it.transactionId == null || byTransaction[it.transactionId]?.type == TransactionType.EXPENSE)
    }.sortedWith(compareByDescending<ProjectCost> { it.date }.thenBy { it.id })

    fun needsReview(cost: ProjectCost): Boolean {
        val id = cost.transactionId ?: return false
        val tx = byTransaction[id] ?: return true
        return tx.type != TransactionType.EXPENSE || tx.money != cost.original || tx.date != cost.date
    }
}

class ProjectRepository(private val db: KinjarDb, private val store: EventStore, private val clock: Clock) {
    // SQLDelight notifies per table, so edits that keep every count still re-emit.
    val book: Flow<ProjectBook> = db.projectQueries.observe().asFlow().map {
        store.read { snapshot() }
    }.flowOn(Dispatchers.IO)

    suspend fun readBook(): ProjectBook = withContext(Dispatchers.IO) { store.read { snapshot() } }

    private fun snapshot() = ProjectBook(
        db.projectQueries.all().executeAsList().map { EventJson.decodeFromString<Project>(it.payload) },
        db.projectCategoryQueries.all().executeAsList().map { EventJson.decodeFromString<ProjectCategory>(it.payload) },
        db.projectCostQueries.all().executeAsList().map { EventJson.decodeFromString<ProjectCost>(requireNotNull(it.payload)) },
        db.txQueries.all().executeAsList().map { it.toDomain() },
    )

    private fun project(id: String): Project? = db.projectQueries.byId(id).executeAsOneOrNull()
        ?.let { EventJson.decodeFromString<Project>(it.payload) }

    private fun category(id: String): ProjectCategory? = db.projectCategoryQueries.byId(id).executeAsOneOrNull()
        ?.let { EventJson.decodeFromString<ProjectCategory>(it.payload) }

    private fun cost(id: String): ProjectCost? = db.projectCostQueries.byId(id).executeAsOneOrNull()
        ?.takeIf { it.removed == 0L }?.payload?.let { EventJson.decodeFromString<ProjectCost>(it) }

    private fun activeProject(id: String): Project {
        val project = requireNotNull(project(id)) { "Unknown project" }
        require(!project.archived) { "Restore project before editing" }
        return project
    }

    /** Creates a project, or edits one when [expected] is the stored version the edit started from. */
    suspend fun save(project: Project, expected: Project? = null) {
        store.append(ProjectSaved(project)) {
            val old = project(project.id)
            require(old == expected) { "Project changed; reopen its settings" }
            require(old != null || !project.archived) { "New projects start active" }
            require(old == null || old.currency == project.currency) { "Preview currency changes first" }
            require(old == null || !old.archived || project == old.copy(archived = false)) { "Restore project before editing" }
            // Only newly enabled categories are checked, so an archived source category never blocks other edits.
            require((project.sourceCategoryIds - old?.sourceCategoryIds.orEmpty()).all { id ->
                db.categoryQueries.byId(id).executeAsOneOrNull()?.let { it.kind == CategoryKind.EXPENSE.name && it.archived == 0L } == true
            }) { "Projects link active expense categories only" }
        }
    }

    suspend fun archive(id: String, archived: Boolean) {
        val old = requireNotNull(withContext(Dispatchers.IO) { store.read { project(id) } }) { "Unknown project" }
        if (old.archived != archived) save(old.copy(archived = archived), old)
    }

    /** Creates a category, or edits or archives one when [expected] is the stored version the edit started from. */
    suspend fun saveCategory(category: ProjectCategory, expected: ProjectCategory? = null) {
        store.append(ProjectCategorySaved(category)) {
            activeProject(category.projectId)
            val old = category(category.id)
            require(old == expected) { "Category changed; reopen it" }
            require(old == null || old.projectId == category.projectId) { "Category belongs to another project" }
            require(old != null || !category.archived) { "New categories start active" }
            require(category.archived || db.projectCategoryQueries.byProject(category.projectId).executeAsList()
                .map { EventJson.decodeFromString<ProjectCategory>(it.payload) }
                .none { it.id != category.id && !it.archived && it.name.trim().equals(category.name.trim(), ignoreCase = true) }
            ) { "Category name already used in this project" }
        }
    }

    /**
     * Adds costs to one project, all or none. Editing a stored cost needs the version the edit started from in
     * [expected], so a change synced from another device is never silently overwritten.
     */
    suspend fun record(costs: List<ProjectCost>, expected: List<ProjectCost> = emptyList()) {
        require(costs.isNotEmpty() && costs.size <= 200 && costs.map { it.id }.distinct().size == costs.size)
        require(costs.map { it.projectId }.distinct().size == 1)
        val linked = costs.mapNotNull { it.transactionId }
        require(linked.distinct().size == linked.size) { "Expense selected more than once" }
        val before = expected.associateBy { it.id }
        require(before.size == expected.size)
        store.appendAll(costs.map(::ProjectCostSaved)) {
            val target = activeProject(costs.first().projectId)
            costs.forEach { value ->
                require(value.converted.currency == target.currency) { "Preview currency changes first" }
                val row = db.projectCostQueries.byId(value.id).executeAsOneOrNull()
                require(row == null || row.removed == 0L) { "Cost was removed" }
                val previous = cost(value.id)
                require(previous == before[value.id]) { "Cost changed; reopen it" }
                require(previous == null || (previous.projectId == value.projectId && previous.transactionId == value.transactionId)) {
                    "Cost identity cannot change"
                }
                value.categoryId?.let { id ->
                    val category = category(id)
                    require(category != null && category.projectId == target.id && (!category.archived || previous?.categoryId == id)) {
                        "Choose an active category of this project"
                    }
                }
                val id = value.transactionId
                if (id == null) {
                    require(value.date <= clock.today()) { "Cost date is in the future" }
                } else {
                    val transaction = requireNotNull(db.projectCostQueries.expense(id).executeAsOneOrNull()?.toDomain()) { "Expense was deleted" }
                    require(transaction.type == TransactionType.EXPENSE && transaction.money == value.original && transaction.date == value.date) {
                        "Expense changed; select it again"
                    }
                    require(previous != null || transaction.categoryId in target.sourceCategoryIds) { "Expense category is not enabled" }
                    require(db.projectCostQueries.linkedTo(id).executeAsList().all { it == value.id }) { "Expense already linked" }
                }
            }
        }
    }

    suspend fun removeCost(id: String, expected: ProjectCost? = null) {
        val previous = expected ?: withContext(Dispatchers.IO) { store.read { cost(id) } } ?: return
        require(previous.id == id)
        store.append(ProjectCostRemoved(id, previous.projectId, previous.transactionId)) {
            activeProject(previous.projectId)
            require(cost(id) == previous) { "Cost changed; reopen it" }
        }
    }

    /**
     * Changes the project currency and every cost's conversion in one change. Each cost is converted from its
     * original amount, so converting back restores the earlier amounts exactly. [expected] is the project the
     * preview was computed from.
     */
    suspend fun changeCurrency(project: Project, costs: List<ProjectCost>, expected: Project? = null) {
        require(costs.size < 1_000) { "Project currency change too large" }
        require(costs.map { it.id }.distinct().size == costs.size)
        store.appendAll(listOf(ProjectSaved(project)) + costs.map(::ProjectCostSaved)) {
            val old = activeProject(project.id)
            expected?.let { require(old == it) { "Project changed; preview again" } }
            require(old.copy(currency = project.currency, budget = project.budget) == project) { "Project settings changed" }
            val existing = db.projectCostQueries.byProject(project.id).executeAsList()
                .map { EventJson.decodeFromString<ProjectCost>(requireNotNull(it.payload)) }.associateBy { it.id }
            require(existing.keys == costs.map { it.id }.toSet()) { "Project costs changed; preview again" }
            costs.forEach { next ->
                require(next.converted.currency == project.currency)
                require(existing.getValue(next.id).copy(converted = next.converted, ratesToRsd = next.ratesToRsd,
                    rateDate = next.rateDate, rateSource = next.rateSource) == next) { "Project cost changed; preview again" }
            }
        }
    }
}
