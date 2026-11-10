package com.pupus.phone.tree

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Production [A11yNode] backed by a live AccessibilityNodeInfo. */
class AccessibilityNodeAdapter(val info: AccessibilityNodeInfo) : A11yNode {
    override val className: CharSequence? get() = info.className
    override val text: CharSequence? get() = info.text
    override val contentDescription: CharSequence? get() = info.contentDescription
    override val viewId: String? get() = info.viewIdResourceName
    override val packageName: CharSequence? get() = info.packageName

    override val bounds: IntArray
        get() {
            val rect = Rect()
            info.getBoundsInScreen(rect)
            return intArrayOf(rect.left, rect.top, rect.right, rect.bottom)
        }

    override val isClickable: Boolean get() = info.isClickable
    override val isScrollable: Boolean get() = info.isScrollable
    override val isEditable: Boolean get() = info.isEditable
    override val isFocused: Boolean get() = info.isFocused
    override val isChecked: Boolean get() = info.isChecked
    override val isSelected: Boolean get() = info.isSelected
    override val isVisible: Boolean get() = info.isVisibleToUser

    override val childCount: Int get() = info.childCount

    override fun child(index: Int): A11yNode? = info.getChild(index)?.let { AccessibilityNodeAdapter(it) }

    override fun parent(): A11yNode? = info.parent?.let { AccessibilityNodeAdapter(it) }

    override fun refresh(): Boolean = info.refresh()
}
