package app.kinjar.domain

internal class ProjectIntegrity(
    private val projects: MutableMap<String, Project> = mutableMapOf(),
    private val categories: MutableMap<String, ProjectCategory> = mutableMapOf(),
    private val costs: MutableMap<String, ProjectCost> = mutableMapOf(),
    private val removed: MutableSet<String> = mutableSetOf(),
) {
    fun copy() = ProjectIntegrity(projects.toMutableMap(), categories.toMutableMap(), costs.toMutableMap(), removed.toMutableSet())

    fun apply(events: List<Event>, strict: Boolean, transactions: Map<String, Transaction>) {
        events.forEach { event ->
            when (event) {
                is ProjectSaved -> projects[event.project.id] = event.project
                is ProjectCategorySaved -> {
                    val value = event.category
                    if (strict) {
                        require(projects[value.projectId]?.archived == false) { "Unknown or archived project" }
                        require(categories[value.id]?.projectId?.let { it == value.projectId } != false) { "Category moved between projects" }
                    }
                    categories[value.id] = value
                }
                is ProjectCostSaved -> {
                    val value = event.cost
                    if (strict) {
                        require(projects[value.projectId]?.archived == false) { "Unknown or archived project" }
                        require(value.id !in removed) { "Project cost was removed" }
                        val old = costs[value.id]
                        require(old == null || (old.projectId == value.projectId && old.transactionId == value.transactionId)) { "Project cost identity changed" }
                        value.transactionId?.let { id ->
                            require(costs.values.none { it.id != value.id && it.transactionId == id }) { "Expense linked to multiple project costs" }
                            if (old == null || old.original != value.original || old.date != value.date) {
                                val transaction = transactions[id]
                                require(transaction?.type == TransactionType.EXPENSE && transaction.money == value.original && transaction.date == value.date) {
                                    "Project cost does not match its expense"
                                }
                            }
                        }
                    }
                    if (value.id !in removed) costs[value.id] = value
                }
                is ProjectCostRemoved -> {
                    val old = costs[event.costId]
                    if (strict) {
                        require(old != null && old.projectId == event.projectId && old.transactionId == event.transactionId) { "Project removal identity mismatch" }
                        require(projects[event.projectId]?.archived == false) { "Archived project" }
                    }
                    costs.remove(event.costId)
                    removed += event.costId
                }
                else -> Unit
            }
        }
        if (strict) validate()
    }

    fun validate() {
        categories.values.forEach { require(it.projectId in projects) { "Unknown project category owner" } }
        costs.values.forEach { cost ->
            require(projects[cost.projectId]?.currency == cost.converted.currency) { "Project currency conversion incomplete" }
            cost.categoryId?.let { require(categories[it]?.projectId == cost.projectId) { "Wrong project category" } }
        }
        val transactions = costs.values.mapNotNull { it.transactionId }
        require(transactions.distinct().size == transactions.size) { "Expense linked to multiple project costs" }
    }
}
