package com.example.data

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Kortana's "hands" — an AccessibilityService that lets her read the current
 * screen's actionable elements and dispatch taps / swipes / text / global
 * navigation, so she can act anywhere on the device on the owner's behalf.
 *
 * TRUST MODEL (read this): the owner must enable this ONCE in
 * Settings > Accessibility > Kortana. Android never grants it silently. While it
 * is on, Kortana can read on-screen content and tap on the owner's behalf.
 * Disabling it in that same screen instantly and completely revokes every bit of
 * her device-control ability. This service never runs unless explicitly enabled.
 */
class KortanaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility connected — Kortana's hands are online.")
    }

    // We act on demand (driven by her brain), not on every UI event.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** A compact, model-readable listing of the actionable elements on screen. */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "(screen unreadable — no active window)"
        val sb = StringBuilder()
        sb.append("Foreground app: ").append(root.packageName ?: "?").append('\n')
        var count = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || count >= MAX_NODES) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label = if (text.isNotEmpty()) text else desc
            if (label.isNotEmpty()) {
                val b = Rect(); node.getBoundsInScreen(b)
                val kind = when {
                    node.isEditable -> "input"
                    node.isClickable -> "button"
                    node.isCheckable -> "toggle"
                    else -> "text"
                }
                sb.append("• [").append(kind).append("] \"").append(label.take(60))
                    .append("\" @(").append(b.centerX()).append(',').append(b.centerY()).append(")\n")
                count++
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        if (count == 0) sb.append("(no labeled elements found)")
        return sb.toString()
    }

    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun global(action: String): Boolean {
        val code = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quicksettings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return false
            else -> return false
        }
        return performGlobalAction(code)
    }

    /** Type [text] into the editable field whose bounds contain (x, y). */
    fun setText(x: Int, y: Int, text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findEditableAt(root, x, y) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditableAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val b = Rect(); node.getBoundsInScreen(b)
        if (node.isEditable && b.contains(x, y)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findEditableAt(child, x, y)
            if (hit != null) return hit
        }
        return null
    }

    /** Execute a single structured action emitted by her brain. */
    fun execute(action: DeviceAction): Boolean = when (action.type.lowercase()) {
        "tap", "click" -> tap(action.x, action.y)
        "swipe" -> swipe(action.x, action.y, action.x2, action.y2)
        "type", "settext" -> setText(action.x, action.y, action.text)
        "global" -> global(action.text)
        else -> {
            Log.w(TAG, "Unknown device action: ${action.type}")
            false
        }
    }

    companion object {
        private const val TAG = "KortanaHands"
        private const val MAX_NODES = 80

        @Volatile
        var instance: KortanaAccessibilityService? = null
            private set

        /** True only while the owner has the service enabled and it is connected. */
        fun isEnabled(): Boolean = instance != null

        /** Open system Accessibility settings so the owner can enable Kortana. */
        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/**
 * One device action the brain can request via its JSON "deviceAction" field.
 * Coordinates are screen pixels (use the readScreen() listing or the screenshot
 * vision to choose them). type ∈ tap | swipe | type | global.
 */
data class DeviceAction(
    val type: String,
    val x: Int = 0,
    val y: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val text: String = ""
)
