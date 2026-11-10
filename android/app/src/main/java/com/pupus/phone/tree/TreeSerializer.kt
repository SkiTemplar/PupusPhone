package com.pupus.phone.tree

import org.json.JSONArray
import org.json.JSONObject

data class SerializedNode(
    val id: Int,
    val parent: Int,
    val cls: String?,
    val text: String?,
    val desc: String?,
    val resId: String?,
    val bounds: List<Int>,
    val flags: List<String>,
)

/** Result of one /screen/tree call; keeps node handles to resolve later `nodeId`s. */
class TreeSnapshot(
    val nodes: List<SerializedNode>,
    private val handles: Map<Int, A11yNode>,
) {
    fun find(id: Int): A11yNode? = handles[id]

    companion object {
        val EMPTY = TreeSnapshot(emptyList(), emptyMap())
    }
}

/**
 * Implements the "Árbol" rules of docs/protocol.md:
 * preorder over all roots, omission of nodes with no text, description, resId or
 * interactive flag (their included descendants hang from the closest included
 * ancestor), short class names and resource ids, and only-true flags.
 */
object TreeSerializer {
    const val FLAG_CLICKABLE = "clickable"
    const val FLAG_SCROLLABLE = "scrollable"
    const val FLAG_EDITABLE = "editable"
    const val FLAG_FOCUSED = "focused"
    const val FLAG_CHECKED = "checked"
    const val FLAG_SELECTED = "selected"

    private const val MAX_DEPTH = 256
    private val INTERACTIVE = setOf(FLAG_CLICKABLE, FLAG_SCROLLABLE, FLAG_EDITABLE)

    fun serialize(roots: List<A11yNode>, visibleOnly: Boolean): TreeSnapshot {
        val out = ArrayList<SerializedNode>()
        val handles = HashMap<Int, A11yNode>()
        roots.forEach { visit(it, -1, visibleOnly, 0, out, handles) }
        return TreeSnapshot(out, handles)
    }

    private fun visit(
        node: A11yNode,
        parentId: Int,
        visibleOnly: Boolean,
        depth: Int,
        out: MutableList<SerializedNode>,
        handles: MutableMap<Int, A11yNode>,
    ) {
        if (depth > MAX_DEPTH) return
        if (visibleOnly && !node.isVisible) return
        val serialized = toSerialized(node, out.size, parentId)
        val childParent = if (serialized != null) {
            out.add(serialized)
            handles[serialized.id] = node
            serialized.id
        } else {
            parentId
        }
        for (i in 0 until node.childCount) {
            node.child(i)?.let { visit(it, childParent, visibleOnly, depth + 1, out, handles) }
        }
    }

    /** Returns null when the node carries no information and must be omitted. */
    fun toSerialized(node: A11yNode, id: Int, parentId: Int): SerializedNode? {
        val text = clean(node.text)
        val desc = clean(node.contentDescription)
        val resId = shortResId(node.viewId)
        val flags = flagsOf(node)
        val meaningful = text != null || desc != null || resId != null || flags.any { it in INTERACTIVE }
        if (!meaningful) return null
        return SerializedNode(
            id = id,
            parent = parentId,
            cls = shortClass(node.className),
            text = text,
            desc = desc,
            resId = resId,
            bounds = node.bounds.toList(),
            flags = flags,
        )
    }

    fun flagsOf(node: A11yNode): List<String> = buildList {
        if (node.isClickable) add(FLAG_CLICKABLE)
        if (node.isScrollable) add(FLAG_SCROLLABLE)
        if (node.isEditable) add(FLAG_EDITABLE)
        if (node.isFocused) add(FLAG_FOCUSED)
        if (node.isChecked) add(FLAG_CHECKED)
        if (node.isSelected) add(FLAG_SELECTED)
    }

    /** `android.widget.Button` -> `Button`. */
    fun shortClass(cls: CharSequence?): String? {
        val value = clean(cls) ?: return null
        return value.substringAfterLast('.')
    }

    /** `com.spotify.music:id/search` -> `search`. */
    fun shortResId(viewId: String?): String? {
        val value = clean(viewId) ?: return null
        val marker = value.indexOf(":id/")
        return if (marker >= 0) value.substring(marker + 4).ifEmpty { null } else value
    }

    internal fun clean(value: CharSequence?): String? {
        val s = value?.toString() ?: return null
        return s.ifBlank { null }
    }

    fun toJson(snapshot: TreeSnapshot, pkg: String?, width: Int, height: Int): JSONObject {
        val nodes = JSONArray()
        snapshot.nodes.forEach { nodes.put(nodeJson(it)) }
        return JSONObject()
            .put("package", pkg ?: JSONObject.NULL)
            .put("screen", JSONObject().put("width", width).put("height", height))
            .put("nodes", nodes)
    }

    private fun nodeJson(n: SerializedNode): JSONObject = JSONObject()
        .put("id", n.id)
        .put("parent", n.parent)
        .put("cls", n.cls ?: JSONObject.NULL)
        .put("text", n.text ?: JSONObject.NULL)
        .put("desc", n.desc ?: JSONObject.NULL)
        .put("resId", n.resId ?: JSONObject.NULL)
        .put("bounds", JSONArray(n.bounds))
        .put("flags", JSONArray(n.flags))
}
