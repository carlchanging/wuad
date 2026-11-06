package app.wuad.core

import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.wuad.vpn.WuadVpnService

/** 分流开关：只改路由启停，节点列表自己还在。 */
object RouteToggle {
    fun apply(ctx: Context, on: Boolean): Intent? {
        Prefs.routeOn = on
        return sync(ctx)
    }

    fun sync(ctx: Context): Intent? {
        val need = Prefs.dnsOn || Prefs.routeOn
        val running = WuadVpnService.running.get()
        if (need && !running) {
            val prep = VpnService.prepare(ctx)
            if (prep != null) return prep
            Prefs.vpnWanted = true
            WuadVpnService.start(ctx)
            return null
        }
        if (need && running) {
            WuadVpnService.reload(ctx)
            return null
        }
        if (!need && running) {
            Prefs.vpnWanted = false
            WuadVpnService.stop(ctx)
        }
        return null
    }
}
