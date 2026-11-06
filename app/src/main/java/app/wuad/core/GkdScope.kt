package app.wuad.core

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * 正式 GKD 全局规则范围：
 * matchAnyApp 默认 true，matchLauncher / matchSystemApp 默认 false。
 * apps[].enable=false 排除，enable=true 在 matchAnyApp=false 时作白名单。
 * https://gkd.li/api/interfaces/rawglobalruleprops
 * 切换桌面后要打开本页才会重新识别桌面。
 */
object GkdScope {
    @Volatile var launchers: Set<String> = emptySet()
        private set

    fun refresh(ctx: Context) {
        val pm = ctx.packageManager
        val found = HashSet<String>()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        try {
            pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
                .forEach { found.add(it.activityInfo.packageName) }
        } catch (_: Exception) {
        }
        try {
            pm.queryIntentActivities(home, 0)
                .forEach { found.add(it.activityInfo.packageName) }
        } catch (_: Exception) {
        }
        launchers = found
    }

    fun isLauncher(pkg: String): Boolean = pkg.isNotEmpty() && pkg in launchers

    fun isSystem(ctx: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        return try {
            val flags = ctx.packageManager.getApplicationInfo(pkg, 0).flags
            (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (_: Exception) {
            false
        }
    }

    fun allow(item: GkdItem, pkg: String, launcher: Boolean, system: Boolean): Boolean {
        if (item.pkg != "*" && item.pkg != pkg) return false
        if (item.pkg != "*") return true
        if (listed(item.denyApps, pkg)) return false
        if (!item.matchAnyApp && !listed(item.allowApps, pkg)) return false
        if (!item.matchLauncher && launcher) return false
        if (!item.matchSystemApp && system) return false
        return true
    }

    fun listed(csv: String, pkg: String): Boolean {
        if (csv.isBlank() || pkg.isEmpty()) return false
        return csv.split(',').any { it.trim() == pkg }
    }
}
