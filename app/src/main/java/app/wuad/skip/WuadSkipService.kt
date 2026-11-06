package app.wuad.skip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.wuad.core.EventStore
import app.wuad.core.GkdItem
import app.wuad.core.GkdScope
import app.wuad.core.GkdSelector
import app.wuad.core.Prefs
import app.wuad.core.RuleStore
import app.wuad.core.SplashHunt
import java.util.concurrent.ConcurrentHashMap

class WuadSkipService : AccessibilityService() {
    private val lastHit = ConcurrentHashMap<String, Long>()
    private val actionCount = ConcurrentHashMap<String, Int>()
    private val lastGroupKey = ConcurrentHashMap<String, Int>()
    private val windowAt = ConcurrentHashMap<String, Long>()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var activity = ""
    @Volatile private var fgPkg = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        RuleStore.init(this)
        EventStore.ensure(this)
        EventStore.add("sys", "跳过引擎已接管", "这是启动。点上开屏才会出现「点击 淘宝」")
        GkdScope.refresh(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleEvent(event)
        } catch (e: Throwable) {
            EventStore.ensure(this)
            EventStore.add("sys", "跳过引擎吞掉一次异常", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        if (pkg == packageName) return
        if (!Prefs.skipOn) return
        if (Prefs.gkdIgnored(pkg)) return
        val now = System.currentTimeMillis()
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (fgPkg != pkg) {
                fgPkg = pkg
                windowAt[pkg] = now
                actionCount.clear()
                lastGroupKey.clear()
            }
            val cls = event.className?.toString().orEmpty()
            if (cls.isNotBlank() && !cls.startsWith("android.")) activity = cls
        } else if (fgPkg != pkg) {
            return
        }
        val items = RuleStore.activeItems(pkg)
        if (items.isEmpty()) return
        val appeared = windowAt[pkg] ?: now
        val age = now - appeared
        val launcher = GkdScope.isLauncher(pkg)
        val system = GkdScope.isSystem(this, pkg)
        val roots = collectRoots(pkg)
        if (roots.isEmpty()) return
        val label = SplashHunt.labelOf(pkg)
        try {
            for (item in items) {
                if (item.match.isBlank() && item.andMatch.isBlank()) continue
                if (!GkdScope.allow(item, pkg, launcher, system)) continue
                if (blockedByAppGroup(item, pkg)) continue
                if (!pageOk(item, age)) continue
                if (!preKeysReady(pkg, item)) continue
                val ck = pkg + ":" + item.id
                if (overLimit(item, ck, now)) continue
                val wait = item.delay.coerceIn(0, 5000)
                if (wait > 0) {
                    handler.postDelayed({ fire(pkg, item, ck, label) }, wait.toLong())
                } else if (clickNow(roots, item, pkg, ck, label)) {
                    return
                }
            }
        } finally {
            roots.forEach { r -> try { r.recycle() } catch (_: Exception) {} }
        }
    }

    override fun onInterrupt() = Unit

    private fun fire(pkg: String, item: GkdItem, ck: String, label: String) {
        try {
            val age = System.currentTimeMillis() - (windowAt[pkg] ?: System.currentTimeMillis())
            if (!pageOk(item, age)) return
            val roots = collectRoots(pkg)
            if (roots.isEmpty()) return
            try {
                clickNow(roots, item, pkg, ck, label)
            } finally {
                roots.forEach { r -> try { r.recycle() } catch (_: Exception) {} }
            }
        } catch (e: Throwable) {
            EventStore.ensure(this)
            EventStore.add("sys", "跳过引擎吞掉一次异常", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun clickNow(
        roots: List<AccessibilityNodeInfo>,
        item: GkdItem,
        pkg: String,
        ck: String,
        label: String
    ): Boolean {
        var found = false
        val clicked = if (item.action.contains("back", true)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } else {
            var ok = false
            for (root in roots) {
                if (item.excludeMatch.isNotBlank() && GkdSelector.find(root, item.excludeMatch) != null) continue
                val node = resolve(root, item) ?: continue
                found = true
                if (tap(node)) {
                    ok = true
                    break
                }
            }
            ok
        }
        if (clicked || found) {
            lastHit[ck] = System.currentTimeMillis()
            actionCount[ck] = (actionCount[ck] ?: 0) + 1
            lastGroupKey["$pkg:${item.group}"] = item.ruleKey
            EventStore.ensure(this)
            EventStore.add("click", "点击 $label · ${item.group}", pkg)
            EventStore.flush()
        }
        return clicked || found
    }

    private fun resolve(root: AccessibilityNodeInfo, item: GkdItem): AccessibilityNodeInfo? {
        if (item.andMatch.isNotBlank()) {
            var last: AccessibilityNodeInfo? = null
            for (part in item.andMatch.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
                last = GkdSelector.find(root, part) ?: return null
            }
            return last
        }
        if (item.match.isBlank()) return null
        return GkdSelector.find(root, item.match)
    }

    private fun pageOk(item: GkdItem, age: Long): Boolean {
        if (item.excludeActivity.isNotBlank() && actHit(activity, item.excludeActivity)) return false
        if (item.activity.isNotBlank() && !actHit(activity, item.activity)) return false
        if (item.matchTime > 0 && age > item.matchTime) return false
        return true
    }

    private fun overLimit(item: GkdItem, ck: String, now: Long): Boolean {
        if (item.actionMaximum > 0) return (actionCount[ck] ?: 0) >= item.actionMaximum
        return now - (lastHit[ck] ?: 0L) < 20000
    }

    private fun preKeysReady(pkg: String, item: GkdItem): Boolean {
        if (item.preKeys.isBlank()) return true
        val prev = lastGroupKey["$pkg:${item.group}"] ?: return false
        return item.preKeys.split(',').mapNotNull { it.trim().toIntOrNull() }.contains(prev)
    }

    private fun blockedByAppGroup(item: GkdItem, pkg: String): Boolean {
        if (item.pkg != "*" || item.disableIfGroup.isBlank()) return false
        return RuleStore.hasAppGroup(pkg, item.disableIfGroup)
    }

    private fun tap(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val p = node.parent
        if (p != null && p.isClickable && p.isVisibleToUser && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return clickCenter(node)
    }

    private fun clickCenter(node: AccessibilityNodeInfo): Boolean {
        val box = Rect()
        node.getBoundsInScreen(box)
        if (box.width() <= 1 || box.height() <= 1) return false
        val path = Path()
        path.moveTo(box.exactCenterX(), box.exactCenterY())
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun collectRoots(pkg: String): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            val active = rootInActiveWindow
            if (active != null && active.packageName?.toString() == pkg) out.add(active)
        } catch (_: Exception) {}
        try {
            windows?.forEach { w ->
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
                val r = w.root ?: return@forEach
                if (r.packageName?.toString() != pkg) return@forEach
                if (out.none { it == r }) out.add(r)
            }
        } catch (_: Exception) {}
        return out
    }

    private fun actHit(current: String, want: String): Boolean {
        if (current.isEmpty()) return true
        return want.split('|', ',', ';').map { it.trim() }.any { it.isNotEmpty() && current.contains(it) }
    }
}
