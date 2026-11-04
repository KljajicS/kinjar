package app.kinjar.domain

data class OperationReplay(
    val applicable: List<SyncOperation>,
    val conflicts: Set<String>,
    val waiting: Set<String>,
    val rejected: Set<String>,
)

object ConflictResolver {
    fun parentsFor(operations: List<SyncOperation>, resources: Map<String, ResourceAccess>): Set<String> {
        val nodes = operations.associateBy { it.id }
        val relevant = operations.filter { op -> resources.any { (key, mode) ->
            op.resources[key]?.let { mode == ResourceAccess.WRITE || it == ResourceAccess.WRITE } == true
        } }.mapTo(mutableSetOf()) { it.id }
        val ancestors = mutableSetOf<String>()
        val pending = ArrayDeque(relevant.flatMap { nodes.getValue(it).parents })
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (ancestors.add(id)) nodes[id]?.parents?.let(pending::addAll)
        }
        return relevant - ancestors
    }

    fun replay(operations: Collection<SyncOperation>): OperationReplay {
        val nodes = operations.associateBy { it.id }
        require(nodes.size == operations.size) { "Duplicate operation" }
        require(operations.map { it.familyId to it.datasetId }.distinct().size <= 1)
        val ancestors = mutableMapOf<String, Set<String>>()
        val ordered = mutableListOf<SyncOperation>()
        val remaining = nodes.toMutableMap()
        while (true) {
            val ready = remaining.values.filter { op -> op.parents.all { it in ancestors } }.sortedBy { it.id }
            if (ready.isEmpty()) break
            ready.forEach { op ->
                ancestors[op.id] = op.parents.flatMapTo(mutableSetOf()) { ancestors.getValue(it) + it }
                ordered += op
                remaining.remove(op.id)
            }
        }
        val allResolutions = ordered.filter { it.rejected.isNotEmpty() }
        allResolutions.forEach { op -> require(op.rejected.all { it in ancestors.getValue(op.id) }) { "Resolution must observe rejected operations" } }
        val resolutions = allResolutions.filter { a -> allResolutions.none { b ->
            a.id in ancestors.getValue(b.id) && a.resources.keys.any { it in b.resources }
        } }
        val resolutionConflicts = resolutions.filter { a -> resolutions.any { b ->
            a.id != b.id && a.id !in ancestors.getValue(b.id) && b.id !in ancestors.getValue(a.id) &&
                a.resources.keys.any { it in b.resources }
        } }.mapTo(mutableSetOf()) { it.id }
        val rejected = resolutions.filter { it.id !in resolutionConflicts }.flatMapTo(mutableSetOf()) { it.rejected }
        val conflicts = resolutionConflicts.toMutableSet()
        val candidates = ordered.filter { it.id !in rejected && it.rejected.isEmpty() }
        candidates.forEachIndexed { index, a ->
            candidates.drop(index + 1).forEach { b ->
                if (a.id !in ancestors.getValue(b.id) && b.id !in ancestors.getValue(a.id) && collides(a, b)) {
                    conflicts += a.id
                    conflicts += b.id
                }
            }
        }
        val dependentConflicts = conflicts.filterTo(mutableSetOf()) { id ->
            id !in resolutionConflicts && ancestors.getValue(id).any { it in conflicts }
        }
        conflicts.removeAll(dependentConflicts)
        val blocked = (conflicts + dependentConflicts).toMutableSet()
        ordered.forEach { op ->
            if (op.parents.any { it in blocked } || (op.rejected.isEmpty() && op.parents.any { it in rejected })) blocked += op.id
        }
        return OperationReplay(
            applicable = ordered.filter { it.id !in blocked && it.id !in rejected },
            conflicts = conflicts,
            waiting = remaining.keys + (blocked - conflicts),
            rejected = rejected,
        )
    }

    private fun collides(a: SyncOperation, b: SyncOperation): Boolean = a.resources.any { (key, mode) ->
        b.resources[key]?.let { mode == ResourceAccess.WRITE || it == ResourceAccess.WRITE } == true
    }
}
