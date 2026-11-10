package com.pupus.phone.tree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class MutableNode(
    override var text: CharSequence? = "Play",
    override var contentDescription: CharSequence? = null,
    override var bounds: IntArray = intArrayOf(0, 0, 100, 50),
    var isAlive: Boolean = true,
) : A11yNode {
    override val className: CharSequence? = "android.widget.Button"
    override val viewId: String? = null
    override val packageName: CharSequence? = "com.example"
    override val isClickable: Boolean = true
    override val isScrollable: Boolean = false
    override val isEditable: Boolean = false
    override val isFocused: Boolean = false
    override val isChecked: Boolean = false
    override val isSelected: Boolean = false
    override val isVisible: Boolean = true
    override val childCount: Int = 0
    override fun child(index: Int): A11yNode? = null
    override fun parent(): A11yNode? = null
    override fun refresh(): Boolean = isAlive
}

class NodeFreshnessTest {
    private fun snapshotOf(node: A11yNode) = TreeSerializer.serialize(listOf(node), visibleOnly = true)

    @Test
    fun unchangedNodeIsFresh() {
        val node = MutableNode()
        val lookup = NodeFreshness.resolve(snapshotOf(node), 0)
        assertTrue(lookup is NodeLookup.Fresh)
    }

    @Test
    fun unknownIdIsNotFound() {
        assertEquals(NodeLookup.NotFound, NodeFreshness.resolve(snapshotOf(MutableNode()), 7))
    }

    @Test
    fun recycledViewWithOtherTextIsStale() {
        val node = MutableNode()
        val snapshot = snapshotOf(node)
        node.text = "Delete"
        assertEquals(NodeLookup.Stale, NodeFreshness.resolve(snapshot, 0))
    }

    @Test
    fun movedOrRedescribedNodeIsStale() {
        val moved = MutableNode()
        val snapMoved = snapshotOf(moved)
        moved.bounds = intArrayOf(0, 200, 100, 250)
        assertEquals(NodeLookup.Stale, NodeFreshness.resolve(snapMoved, 0))

        val described = MutableNode()
        val snapDescribed = snapshotOf(described)
        described.contentDescription = "Borrar"
        assertEquals(NodeLookup.Stale, NodeFreshness.resolve(snapDescribed, 0))
    }

    @Test
    fun nodeThatNoLongerExistsIsStale() {
        val node = MutableNode()
        val snapshot = snapshotOf(node)
        node.isAlive = false
        assertEquals(NodeLookup.Stale, NodeFreshness.resolve(snapshot, 0))
    }
}
