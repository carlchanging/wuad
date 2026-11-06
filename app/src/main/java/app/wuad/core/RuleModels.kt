package app.wuad.core

data class DnsItem(
    val id: String,
    var host: String,
    var action: String,
    val preset: Boolean,
    var deleted: Boolean = false
)

data class GkdItem(
    val id: String,
    var pkg: String,
    var app: String,
    var group: String,
    var match: String,
    val preset: Boolean,
    var enabled: Boolean,
    var deleted: Boolean = false,
    var activity: String = "",
    var action: String = "click",
    var delay: Int = 0,
    var matchTime: Int = 0,
    var actionMaximum: Int = 0,
    var resetMatch: String = "",
    var excludeActivity: String = "",
    var excludeMatch: String = "",
    var andMatch: String = "",
    var disableIfGroup: String = "",
    var matchAnyApp: Boolean = true,
    var matchLauncher: Boolean = false,
    var matchSystemApp: Boolean = false,
    var denyApps: String = "",
    var allowApps: String = "",
    var ruleKey: Int = 0,
    var preKeys: String = ""
) {
    val splash: Boolean get() = GkdCats.of(group).name == "开屏广告"
}
