package app.tapwell

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo

class FocusService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val inspect = Runnable { inspectForeground() }
    private val expiry = Runnable { inspectForeground() }
    private var lastIntercept = Long.MIN_VALUE

    override fun onServiceConnected() {
        super.onServiceConnected()
        inspectForeground()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val locks = Store.get(this).locks()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.packageName?.toString() !in locks &&
            !(event.packageName?.toString() == YOUTUBE && SHORTS in locks)
        ) return
        if (!handler.hasCallbacks(inspect)) handler.postDelayed(inspect, 90)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun inspectForeground() {
        handler.removeCallbacks(expiry)
        val store = Store.get(this)
        val locks = store.locks()
        if (locks.isEmpty()) return
        val root = rootInActiveWindow ?: return
        val foreground = root.packageName?.toString() ?: return
        val target = when {
            foreground in locks -> foreground
            foreground == YOUTUBE && SHORTS in locks && showsShorts(root) -> SHORTS
            else -> return
        }
        val left = store.accessLeft(target)
        if (left > 0) {
            val budget = FocusUsage.budget(this, target, store.dailyLimit(target), System.currentTimeMillis())
            if (budget.blocked == null) {
                handler.postDelayed(expiry, minOf(left, budget.remaining) + 80)
                return
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (lastIntercept != Long.MIN_VALUE && now - lastIntercept < 500) {
            handler.postDelayed(expiry, 500 - (now - lastIntercept))
            return
        }
        lastIntercept = now
        if (target == SHORTS && performGlobalAction(GLOBAL_ACTION_BACK)) {
            // Give YouTube time to close the player before bringing the gate to the front.
            handler.postDelayed({ openGate(SHORTS) }, 300)
        } else {
            openGate(target)
        }
    }

    private fun openGate(target: String) {
        startActivity(
            Intent(this, GateActivity::class.java)
                .putExtra(GateActivity.EXTRA_TARGET, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun showsShorts(root: AccessibilityNodeInfo): Boolean {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 650) {
            val node = pending.removeLast()
            if (!node.isVisibleToUser) continue
            val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""
            if (isShortsPlayerId(id)) return true
            // The Shorts shortcut in the ordinary bottom bar must stay visible and usable.
            // Only its selected tab state indicates that the Shorts screen is actually open.
            if (node.isSelected && node.isClickable && node.contentDescription?.toString().equals("Shorts", true)) {
                return true
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
        }
        return false
    }

    companion object {
        internal fun enabled(context: Context): Boolean {
            val expected = ComponentName(context, FocusService::class.java)
            val manager = context.getSystemService(AccessibilityManager::class.java)
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == expected }
        }
    }
}
