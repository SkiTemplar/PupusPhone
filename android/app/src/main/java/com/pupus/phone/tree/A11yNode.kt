package com.pupus.phone.tree

/**
 * Minimal read-only view of an accessibility node. The production adapter wraps
 * AccessibilityNodeInfo; tests use plain fakes so the tree rules run on the JVM.
 */
interface A11yNode {
    val className: CharSequence?
    val text: CharSequence?
    val contentDescription: CharSequence?
    val viewId: String?
    val packageName: CharSequence?

    /** Screen bounds as [left, top, right, bottom] in physical pixels. */
    val bounds: IntArray

    val isClickable: Boolean
    val isScrollable: Boolean
    val isEditable: Boolean
    val isFocused: Boolean
    val isChecked: Boolean
    val isSelected: Boolean
    val isVisible: Boolean

    val childCount: Int
    fun child(index: Int): A11yNode?
    fun parent(): A11yNode?

    /** Re-reads the node from the system; false when it no longer exists. Fakes are always fresh. */
    fun refresh(): Boolean = true
}

/** Pure helpers over [A11yNode] used by the device controller. */
object NodeSearch {
    private const val MAX_ANCESTOR_DEPTH = 64

    /** The node itself if clickable, else its first clickable ancestor, else null. */
    fun clickableSelfOrAncestor(node: A11yNode): A11yNode? {
        var current: A11yNode? = node
        var depth = 0
        while (current != null && depth <= MAX_ANCESTOR_DEPTH) {
            if (current.isClickable) return current
            current = current.parent()
            depth++
        }
        return null
    }

    /** First scrollable and visible node in preorder across [roots]. */
    fun firstScrollable(roots: List<A11yNode>): A11yNode? {
        val stack = ArrayDeque<A11yNode>()
        roots.asReversed().forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (!node.isVisible) continue
            if (node.isScrollable) return node
            for (i in node.childCount - 1 downTo 0) {
                node.child(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    fun center(bounds: IntArray): Pair<Int, Int> =
        Pair((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2)
}
