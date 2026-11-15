package app.kinjar.domain

internal class ProjectIntegrity(
    private val projects: MutableMap<String, Project> = mutableMapOf(),
    private val categories: MutableMap<String, ProjectCategory> = mutableMapOf(),
    private val costs: MutableMap<String, ProjectCost> = mutableMapOf(),
    private val removed: MutableSet<String> = mutableSetOf(),
    private val finances: MutableMap<String, ProjectFinance> = mutableMapOf(),
) {
    fun copy() = ProjectIntegrity(projects.toMutableMap(), categories.toMutableMap(), costs.toMutableMap(), removed.toMutableSet(), finances.toMutableMap())

    fun apply(events: List<Event>, strict: Boolean, transactions: Map<String, Transaction>) {
        events.forEach { event ->
            when (event) {
                is ProjectFinanceSaved -> {
                    val value = event.finance
                    if (strict) {
                        require(projects[value.projectId]?.archived == false) { "Unknown or archived project" }
                        val previous = finances[value.projectId]
                        value.settlements.forEach { settlement ->
                            val old = previous?.settlements?.find { it.id == settlement.id }
                            require(old == null || old == settlement) { "Undo settlement before changing it" }
                            if (old == null) require(settlement.payment.matches(transactions[settlement.payment.transactionId], settlement.type)) { "Payment changed; select it again" }
                        }
                        value.repayments.forEach { repayment ->
                            val old = previous?.repayments?.find { it.id == repayment.id }
                            require(old == null || old == repayment) { "Remove repayment before changing it" }
                            if (old == null && repayment.payment.transactionId != null) {
                                require(repayment.payment.matches(transactions[repayment.payment.transactionId], TransactionType.INCOME)) { "Income changed; select it again" }
                            }
                        }
                    }
                    finances[value.projectId] = value
                }
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
                        require(finances.values.none { f -> f.settlements.any { value.id in it.allocations } } || old == value) { "Undo settlement before editing this cost" }
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
                        require(finances.values.none { f -> f.settlements.any { event.costId in it.allocations } }) { "Undo settlement before removing this cost" }
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
        finances.values.forEach { finance ->
            val project = requireNotNull(projects[finance.projectId]) { "Unknown project" }
            require(finance.reimbursement?.opening?.currency?.let { it == project.currency } != false) { "Reimbursement currency mismatch" }
            finance.payments().forEach { require(it.converted.currency == project.currency) { "Project payment currency mismatch" } }
            finance.settlements.forEach { settlement ->
                settlement.allocations.keys.forEach { id ->
                    val cost = costs[id]
                    require(cost != null && cost.projectId == project.id && cost.unpaid && cost.transactionId == null) { "Settlement needs unpaid project costs" }
                }
            }
            finance.repayments.forEach { require(it.payment.date >= requireNotNull(finance.reimbursement).start) { "Repayment predates tracking" } }
        }
        val transactions = costs.values.mapNotNull { it.transactionId } + finances.values.flatMap { it.payments().mapNotNull { p -> p.transactionId } }
        require(transactions.distinct().size == transactions.size) { "Expense linked to multiple project costs" }
    }
}
