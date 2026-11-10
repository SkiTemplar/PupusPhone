package com.pupus.phone.tree

/** Result of resolving a `nodeId` from the last tree snapshot. */
sealed class NodeLookup {
    data class Fresh(val node: A11yNode) : NodeLookup()
    object NotFound : NodeLookup()
    object Stale : NodeLookup()
}

/**
 * Guards against acting on a recycled view: a node is only used if it still exists
 * and its text, description and bounds match what the client saw in /screen/tree.
 */
object NodeFreshness {

    fun resolve(snapshot: TreeSnapshot, id: Int): NodeLookup {
        val node = snapshot.find(id) ?: return NodeLookup.NotFound
        val seen = snapshot.nodes.getOrNull(id)?.takeIf { it.id == id } ?: return NodeLookup.NotFound
        if (!node.refresh()) return NodeLookup.Stale
        return if (matches(seen, node)) NodeLookup.Fresh(node) else NodeLookup.Stale
    }

    fun matches(seen: SerializedNode, current: A11yNode): Boolean =
        seen.text == TreeSerializer.clean(current.text) &&
            seen.desc == TreeSerializer.clean(current.contentDescription) &&
            seen.bounds == current.bounds.toList()
}
