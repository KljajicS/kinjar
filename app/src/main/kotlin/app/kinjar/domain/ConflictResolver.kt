package app.kinjar.domain

data class OperationReplay(
    val applicable: List<SyncOperation>,
    val conflicts: Set<String>,
    val waiting: Set<String>,
    val rejected: Set<String>,
    val invalid: Set<String> = emptySet(),
)

object ConflictResolver {
    fun parentsFor(operations: List<SyncOperation>, resources: Map<String, ResourceAccess>): Set<String> {
        val nodes = operations.associateBy { it.id }
        val relevant = operations.filter { op -> resources.any { (key, mode) ->
            op.resources[key]?.let { mode == ResourceAccess.WRITE || it == ResourceAccess.WRITE } == true
        } }.mapTo(mutableSetOf()) { it.id }
        val ancestors = mutableSetOf<String>()
        val pending = ArrayDeque(relevant.flatMap { nodes.getValue(it).parents.filter(nodes::containsKey) })
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (ancestors.add(id)) nodes[id]?.parents?.filter(nodes::containsKey)?.let(pending::addAll)
        }
        return relevant - ancestors
    }

    fun replay(operations: Collection<SyncOperation>): OperationReplay {
        val nodes = operations.associateBy { it.id }
        require(nodes.size == operations.size) { "Duplicate operation" }
        require(operations.map { it.familyId to it.datasetId }.distinct().size <= 1)
        val ordered = mutableListOf<SyncOperation>()
        val remaining = nodes.toMutableMap()
        val children = mutableMapOf<String, MutableList<String>>()
        val pendingParents = nodes.mapValues { it.value.parents.size }.toMutableMap()
        operations.forEach { op -> op.parents.forEach { if (it in nodes) children.getOrPut(it) { mutableListOf() }.add(op.id) } }
        var ready = operations.filter { it.parents.isEmpty() }.sortedBy { it.id }
        while (ready.isNotEmpty()) {
            val next = mutableListOf<SyncOperation>()
            ready.forEach { op ->
                ordered += op
                remaining.remove(op.id)
                children[op.id]?.forEach { child ->
                    val count = pendingParents.getValue(child) - 1
                    pendingParents[child] = count
                    if (count == 0) next += nodes.getValue(child)
                }
            }
            ready = next.sortedBy { it.id }
        }
        val ancestry = Ancestry(nodes, ordered)
        val invalidResolutions = ordered.filter { op -> op.rejected.any { !ancestry.contains(it, op.id) } }.mapTo(mutableSetOf()) { it.id }
        val allResolutions = ordered.filter { it.rejected.isNotEmpty() && it.id !in invalidResolutions }
        val resolutions = allResolutions.filter { a -> allResolutions.none { b ->
            a.resources.keys.any { it in b.resources } && ancestry.contains(a.id, b.id)
        } }
        val resolutionConflicts = resolutions.filter { a -> resolutions.any { b ->
            a.id != b.id && a.resources.keys.any { it in b.resources } &&
                !ancestry.contains(a.id, b.id) && !ancestry.contains(b.id, a.id)
        } }.mapTo(mutableSetOf()) { it.id }
        val rejected = resolutions.filter { it.id !in resolutionConflicts }.flatMapTo(mutableSetOf()) { it.rejected }
        val conflicts = ((resolutionConflicts + invalidResolutions) - rejected).toMutableSet()
        val candidates = ordered.filter { it.id !in rejected && it.rejected.isEmpty() }
        val readers = mutableMapOf<String, MutableList<SyncOperation>>()
        val writers = mutableMapOf<String, MutableList<SyncOperation>>()
        candidates.forEach { a ->
            val related = mutableMapOf<String, SyncOperation>()
            a.resources.forEach { (key, mode) ->
                writers[key]?.forEach { related[it.id] = it }
                if (mode == ResourceAccess.WRITE) readers[key]?.forEach { related[it.id] = it }
            }
            related.values.forEach { b ->
                if (collides(a, b) && !ancestry.contains(a.id, b.id) && !ancestry.contains(b.id, a.id)) {
                    conflicts += a.id
                    conflicts += b.id
                }
            }
            a.resources.forEach { (key, mode) ->
                val index = if (mode == ResourceAccess.WRITE) writers else readers
                index.getOrPut(key) { mutableListOf() }.add(a)
            }
        }
        val dependent = mutableSetOf<String>()
        ordered.forEach { op -> if (op.parents.any { it in conflicts || it in dependent }) dependent += op.id }
        val dependentConflicts = (conflicts intersect dependent) - resolutionConflicts
        conflicts.removeAll(dependentConflicts)
        val blocked = (conflicts + dependentConflicts).toMutableSet()
        ordered.forEach { op ->
            if (op.id !in rejected && (op.parents.any { it in blocked } || (op.rejected.isEmpty() && op.parents.any { it in rejected }))) blocked += op.id
        }
        return OperationReplay(
            applicable = ordered.filter { it.id !in blocked && it.id !in rejected },
            conflicts = conflicts,
            waiting = remaining.keys + (blocked - conflicts),
            rejected = rejected,
            invalid = invalidResolutions.intersect(conflicts),
        )
    }

    private class Ancestry(private val nodes: Map<String, SyncOperation>, ordered: List<SyncOperation>) {
        private val positions = ordered.mapIndexed { index, op -> op.id to index }.toMap()

        fun contains(ancestor: String, descendant: String): Boolean {
            val position = positions[ancestor] ?: return false
            if (position >= positions.getValue(descendant)) return false
            val parents = nodes.getValue(descendant).parents
            if (ancestor in parents) return true
            val visited = mutableSetOf<String>()
            val pending = ArrayDeque(parents)
            while (pending.isNotEmpty()) {
                val id = pending.removeLast()
                if (positions.getValue(id) <= position || !visited.add(id)) continue
                val next = nodes.getValue(id).parents
                if (ancestor in next) return true
                pending.addAll(next)
            }
            return false
        }
    }

    internal fun collides(a: SyncOperation, b: SyncOperation): Boolean {
        if (a.events.size == 1 && b.events.size == 1) {
            val first = a.events.single().event
            val second = b.events.single().event
            if (first is MemberCreated && second is MemberCreated && first.member.id == second.member.id) return false
            if (first is AccountsReordered && second is AccountsReordered) return false
            if (first is GoalsReordered && second is GoalsReordered) return false
        }
        return a.resources.any { (key, mode) ->
            b.resources[key]?.let { mode == ResourceAccess.WRITE || it == ResourceAccess.WRITE } == true
        }
    }
}
