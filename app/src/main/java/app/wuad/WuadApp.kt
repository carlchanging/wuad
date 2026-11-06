package app.wuad

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import app.wuad.core.AppNames
import app.wuad.core.Blocklist
import app.wuad.core.DnsSubStore
import app.wuad.core.DnsUpStore
import app.wuad.core.EventStore
import app.wuad.core.GkdScope
import app.wuad.core.Prefs
import app.wuad.core.RouteStore
import app.wuad.core.RuleStore
import app.wuad.core.SubFetch
import app.wuad.core.SubStore
import kotlin.concurrent.thread

class WuadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        EventStore.init(this)
        AppNames.init(this)
        GkdScope.refresh(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("vpn", getString(R.string.channel_vpn), NotificationManager.IMPORTANCE_LOW)
            )
        }
        thread(name = "rules-load", isDaemon = true) {
            RuleStore.init(this)
            RouteStore.init(this)
            DnsUpStore.init(this)
            DnsSubStore.init(this)
            SubStore.init(this)
            SubFetch.init(this)
            Blocklist.load(this)
        }
    }

    override fun onTrimMemory(level: Int) {
        EventStore.flush()
        super.onTrimMemory(level)
    }
}
