package dev.marklv.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional, user-enabled bridge for visible UI interactions.
 * It is deliberately limited to explicit text/scroll/back actions; it is not a shell or root bridge.
 */
class JarvisAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* State is read only when an action is requested. */ }
    override fun onInterrupt() {}

    fun clickVisibleText(label: String): Boolean {
        if (label.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(label)
        for (node in matches) {
            if (node.isVisibleToUser && clickNodeOrParent(node)) return true
        }
        return false
    }

    fun readVisibleScreen(): String {
        val root = rootInActiveWindow ?: return "No active app window is available."
        val lines = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo) {
            if (lines.size >= 160) return
            if (node.isVisibleToUser) {
                val label = node.text?.toString()?.trim().orEmpty()
                val description = node.contentDescription?.toString()?.trim().orEmpty()
                val value = if (label.isNotBlank()) label else description
                if (value.isNotBlank() && value !in lines) lines.add(value)
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it) }
            }
        }
        visit(root)
        return if (lines.isEmpty()) "No readable text is exposed by the active app." else lines.joinToString("\\n")
    }

    fun enterText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!focused.isEditable) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectScrollable(root, nodes)
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return nodes.firstOrNull { it.isVisibleToUser && it.isScrollable }?.performAction(action) == true
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val parent = node.parent ?: return false
        return clickNodeOrParent(parent)
    }

    private fun collectScrollable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isScrollable) out.add(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectScrollable(it, out) }
    }

    companion object {
        @Volatile var active: JarvisAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }
}
