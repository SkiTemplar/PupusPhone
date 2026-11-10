package com.pupus.phone.tree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeNode(
    override val className: CharSequence? = "android.widget.FrameLayout",
    override val text: CharSequence? = null,
    override val contentDescription: CharSequence? = null,
    override val viewId: String? = null,
    override val packageName: CharSequence? = "com.example",
    override val bounds: IntArray = intArrayOf(0, 0, 100, 100),
    override val isClickable: Boolean = false,
    override val isScrollable: Boolean = false,
    override val isEditable: Boolean = false,
    override val isFocused: Boolean = false,
    override val isChecked: Boolean = false,
    override val isSelected: Boolean = false,
    override val isVisible: Boolean = true,
    children: List<FakeNode> = emptyList(),
) : A11yNode {
    private val kids = children
    private var parentNode: FakeNode? = null

    init {
        kids.forEach { it.parentNode = this }
    }

    override val childCount: Int get() = kids.size
    override fun child(index: Int): A11yNode? = kids.getOrNull(index)
    override fun parent(): A11yNode? = parentNode
}

class TreeSerializerTest {

    @Test
    fun shortensClassAndResourceIds() {
        assertEquals("Button", TreeSerializer.shortClass("android.widget.Button"))
        assertEquals("Plain", TreeSerializer.shortClass("Plain"))
        assertNull(TreeSerializer.shortClass(null))
        assertEquals("search", TreeSerializer.shortResId("com.spotify.music:id/search"))
        assertEquals("raw_id", TreeSerializer.shortResId("raw_id"))
        assertNull(TreeSerializer.shortResId(""))
        assertNull(TreeSerializer.shortResId("com.x:id/"))
    }

    @Test
    fun omitsEmptyNodesAndCollapsesChildrenToNearestIncludedAncestor() {
        val label = FakeNode(className = "android.widget.TextView", text = "Hola")
        val button = FakeNode(className = "android.widget.Button", isClickable = true)
        val wrapper = FakeNode(children = listOf(label, button))
        val root = FakeNode(viewId = "com.example:id/root", children = listOf(wrapper, FakeNode()))

        val nodes = TreeSerializer.serialize(listOf(root), visibleOnly = true).nodes

        assertEquals(3, nodes.size)
        assertEquals(listOf(0, 1, 2), nodes.map { it.id })
        assertEquals("root", nodes[0].resId)
        assertEquals(-1, nodes[0].parent)
        assertEquals("Hola", nodes[1].text)
        assertEquals(0, nodes[1].parent)
        assertEquals("Button", nodes[2].cls)
        assertEquals(0, nodes[2].parent)
    }

    @Test
    fun childrenOfOmittedRootHangFromMinusOne() {
        val root = FakeNode(children = listOf(FakeNode(text = "a"), FakeNode(contentDescription = "b")))
        val nodes = TreeSerializer.serialize(listOf(root), visibleOnly = true).nodes
        assertEquals(listOf(-1, -1), nodes.map { it.parent })
        assertEquals("b", nodes[1].desc)
    }

    @Test
    fun stateOnlyFlagsDoNotKeepANode() {
        val selectedOnly = FakeNode(isSelected = true, isFocused = true, isChecked = true)
        val nodes = TreeSerializer.serialize(listOf(selectedOnly), visibleOnly = true).nodes
        assertTrue(nodes.isEmpty())
    }

    @Test
    fun flagsListOnlyTrueValuesInFixedOrder() {
        val node = FakeNode(isClickable = true, isEditable = true, isChecked = true)
        assertEquals(listOf("clickable", "editable", "checked"), TreeSerializer.flagsOf(node))
        val all = FakeNode(
            isClickable = true, isScrollable = true, isEditable = true,
            isFocused = true, isChecked = true, isSelected = true,
        )
        assertEquals(
            listOf("clickable", "scrollable", "editable", "focused", "checked", "selected"),
            TreeSerializer.flagsOf(all),
        )
        assertTrue(TreeSerializer.flagsOf(FakeNode()).isEmpty())
    }

    @Test
    fun blankTextIsTreatedAsMissing() {
        val node = FakeNode(text = "   ", contentDescription = "")
        assertNull(TreeSerializer.toSerialized(node, 0, -1))
    }

    @Test
    fun visibleOnlySkipsInvisibleSubtrees() {
        val hidden = FakeNode(isVisible = false, text = "oculto", children = listOf(FakeNode(text = "hijo")))
        val root = FakeNode(text = "raiz", children = listOf(hidden))
        assertEquals(1, TreeSerializer.serialize(listOf(root), visibleOnly = true).nodes.size)
        assertEquals(3, TreeSerializer.serialize(listOf(root), visibleOnly = false).nodes.size)
    }

    @Test
    fun preorderAcrossWindowsAndSnapshotResolvesIds() {
        val first = FakeNode(text = "w1", children = listOf(FakeNode(text = "w1c")))
        val second = FakeNode(text = "w2")
        val snapshot = TreeSerializer.serialize(listOf(first, second), visibleOnly = true)
        assertEquals(listOf("w1", "w1c", "w2"), snapshot.nodes.map { it.text })
        assertEquals(-1, snapshot.nodes[2].parent)
        assertSame(second, snapshot.find(2))
        assertNull(snapshot.find(99))
    }

    @Test
    fun jsonUsesProtocolShape() {
        val node = FakeNode(className = "android.widget.Button", text = "OK", bounds = intArrayOf(1, 2, 3, 4), isClickable = true)
        val json = TreeSerializer.toJson(
            TreeSerializer.serialize(listOf(node), true), "com.spotify.music", 1080, 2400,
        )
        assertEquals("com.spotify.music", json.getString("package"))
        assertEquals(2400, json.getJSONObject("screen").getInt("height"))
        val n = json.getJSONArray("nodes").getJSONObject(0)
        assertEquals("Button", n.getString("cls"))
        assertTrue(n.isNull("desc"))
        assertTrue(n.isNull("resId"))
        assertEquals(4, n.getJSONArray("bounds").getInt(3))
        assertEquals("clickable", n.getJSONArray("flags").getString(0))
    }

    @Test
    fun clickableAncestorAndFirstScrollable() {
        val leaf = FakeNode(text = "x")
        val clickableParent = FakeNode(isClickable = true, children = listOf(leaf))
        val list = FakeNode(isScrollable = true, children = listOf(clickableParent))
        val hiddenList = FakeNode(isScrollable = true, isVisible = false)
        val root = FakeNode(children = listOf(hiddenList, list))

        assertSame(clickableParent, NodeSearch.clickableSelfOrAncestor(leaf))
        assertNull(NodeSearch.clickableSelfOrAncestor(FakeNode()))
        assertSame(list, NodeSearch.firstScrollable(listOf(root)))
        assertEquals(Pair(50, 100), NodeSearch.center(intArrayOf(0, 0, 100, 200)))
    }
}
