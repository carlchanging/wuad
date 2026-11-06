package app.wuad.ui

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import app.wuad.core.EventStore
import app.wuad.core.MihomoApi
import app.wuad.core.MihomoEngine
import app.wuad.core.Prefs
import app.wuad.core.RouteToggle
import app.wuad.core.RouteStore
import app.wuad.core.RouteUpdater
import app.wuad.core.RuleUpdater
import app.wuad.core.TrafficFmt
import app.wuad.BuildConfig
import app.wuad.R
import app.wuad.databinding.FragHomeBinding
import app.wuad.skip.WuadSkipService
import app.wuad.vpn.WuadVpnService

class HomeFragment : Fragment() {
    private var _b: FragHomeBinding? = null
    private val bind get() = _b!!
    private var bindingSwitches = false
    private var awaitingVpn = false
    private var paintGen = 0
    private var nodeBusy = false

    private val vpnPermit = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        awaitingVpn = false
        if (r.resultCode == AppCompatActivity.RESULT_OK) {
            Prefs.vpnWanted = true
            WuadVpnService.start(requireContext())
        } else {
            Prefs.vpnWanted = false
            if (!WuadVpnService.running.get()) Prefs.routeOn = false
            toast("你拒绝了 VPN 授权")
        }
        refresh()
    }
    private val notifyPermit = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragHomeBinding.inflate(inflater, container, false)
        return bind.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= 33) notifyPermit.launch(Manifest.permission.POST_NOTIFICATIONS)
        bind.swPower.setOnCheckedChangeListener { _, on ->
            if (bindingSwitches) return@setOnCheckedChangeListener
            if (on) {
                awaitingVpn = true
                askVpn()
            } else {
                awaitingVpn = false
                Prefs.vpnWanted = false
                WuadVpnService.stop(requireContext())
                refresh()
            }
        }
        bind.guideWarn.setOnClickListener { startActivity(Intent(requireContext(), GuideActivity::class.java)) }
        bind.tileDns.setOnClickListener { openLogs("dns") }
        bind.tileRoute.setOnClickListener { openLogs("conn") }
        bind.tileGkd.setOnClickListener { openLogs("click") }
        bind.swRoute.setOnCheckedChangeListener { _, on ->
            if (bindingSwitches) return@setOnCheckedChangeListener
            val prep = RouteToggle.apply(requireContext(), on)
            if (prep != null) vpnPermit.launch(prep)
            toast(if (on) "分流开" else "分流关")
            refresh()
        }
        bind.modes.setOnCheckedChangeListener { _, id ->
            if (bindingSwitches) return@setOnCheckedChangeListener
            Prefs.mode = when (id) {
                bind.modeGlobal.id -> "global"
                bind.modeDirect.id -> "direct"
                else -> "rule"
            }
            Thread {
                try { if (MihomoEngine.running) MihomoApi.setMode(Prefs.mode) } catch (_: Exception) {}
                activity?.runOnUiThread { refresh() }
            }.start()
        }
        bind.btnNodes.setOnClickListener { startActivity(Intent(requireContext(), NodesActivity::class.java)) }
        bind.routeRow.setOnClickListener { startActivity(Intent(requireContext(), NodesActivity::class.java)) }
        bind.btnLogs.setOnClickListener { startActivity(Intent(requireContext(), LogsActivity::class.java)) }
        bind.btnGuide.setOnClickListener { startActivity(Intent(requireContext(), GuideActivity::class.java)) }
        if (Prefs.checkOnOpen) {
            Thread {
                try { RuleUpdater.updateAntiAd(requireContext()) } catch (_: Exception) {}
                try { RuleUpdater.updateGkd(requireContext()) } catch (_: Exception) {}
                try { RouteStore.init(requireContext()); RouteUpdater.updateEnabled(requireContext()) } catch (_: Exception) {}
                activity?.runOnUiThread { refresh() }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        bind.root.removeCallbacks(tick)
        bind.root.post(tick)
    }

    override fun onPause() {
        bind.root.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroyView() {
        bind.root.removeCallbacks(tick)
        _b = null
        super.onDestroyView()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (_b == null) return
            refresh()
            bind.root.postDelayed(this, 1200)
        }
    }

    private fun askVpn() {
        val prep = VpnService.prepare(requireContext())
        if (prep != null) vpnPermit.launch(prep) else {
            awaitingVpn = false
            Prefs.vpnWanted = true
            WuadVpnService.start(requireContext())
            refresh()
        }
    }

    private fun refresh() {
        if (_b == null) return
        paintGen++
        val gen = paintGen
        bindingSwitches = true
        val modeBtn = when (Prefs.mode) {
            "global" -> bind.modeGlobal
            "direct" -> bind.modeDirect
            else -> bind.modeRule
        }
        if (!modeBtn.isChecked) modeBtn.isChecked = true
        val vpn = WuadVpnService.running.get()
        val want = Prefs.vpnWanted
        val skip = Prefs.skipOn && skipEnabled()
        bind.heroTitle.text = when {
            vpn -> "已连接"
            want -> "连接中"
            skip -> "仅跳过"
            else -> "未连接"
        }
        bind.heroTitle.setTextColor(
            requireContext().getColor(
                when {
                    vpn -> R.color.success
                    want -> R.color.warn
                    skip -> R.color.warn
                    else -> R.color.text
                }
            )
        )
        val desc = comboDesc(vpn)
        if (bind.heroDesc.text?.toString() != desc) bind.heroDesc.text = desc
        if (!awaitingVpn && bind.swPower.isChecked != (vpn || want)) bind.swPower.isChecked = vpn || want
        if (bind.swRoute.isChecked != Prefs.routeOn) bind.swRoute.isChecked = Prefs.routeOn
        bind.kpiBlock.text = EventStore.today("dns").toString()
        bind.kpiClick.text = EventStore.today("click").toString()
        paintTile(bind.tileDns, bind.titleDns, Prefs.dnsOn)
        paintTile(bind.tileRoute, bind.titleRoute, Prefs.routeOn)
        paintTile(bind.tileGkd, bind.titleGkd, Prefs.skipOn)
        if (Prefs.routeOn && MihomoEngine.running) {
            if (bind.nodeLine.text.isNullOrBlank()) paintRouteNode(Prefs.node.ifBlank { "连接中" })
            if (!nodeBusy) {
                nodeBusy = true
                Thread {
                    val now = try { MihomoApi.groupNow().first } catch (_: Exception) { Prefs.node.ifBlank { "—" } }
                    val bytes = try { MihomoApi.trafficBytes() } catch (_: Exception) { -1L }
                    activity?.runOnUiThread {
                        nodeBusy = false
                        if (_b == null) return@runOnUiThread
                        paintRouteNode(now)
                        if (bytes >= 0) bind.kpiTraffic.text = TrafficFmt.human(bytes)
                    }
                }.start()
            }
        } else if (Prefs.routeOn) {
            paintRouteNode(Prefs.node.ifBlank { "内核还在起来" })
            bind.kpiTraffic.text = "—"
        } else {
            paintRouteNode("")
            bind.kpiTraffic.text = "—"
        }
        bind.versionLine.text = "无广告 ${BuildConfig.VERSION_NAME}"
        bind.root.post { if (gen == paintGen) bindingSwitches = false }
    }

    private fun paintRouteNode(now: String) {
        if (!Prefs.routeOn) {
            if (bind.nodeLine.visibility != View.INVISIBLE) bind.nodeLine.visibility = View.INVISIBLE
            if (bind.nodeLine.text?.isNotEmpty() == true) bind.nodeLine.text = ""
            return
        }
        val text = when {
            MihomoEngine.running && now.isNotBlank() && now != "—" -> now
            now.isNotBlank() && now != "—" -> now
            else -> "内核还在起来"
        }
        if (bind.nodeLine.visibility != View.VISIBLE) bind.nodeLine.visibility = View.VISIBLE
        if (bind.nodeLine.text?.toString() != text) bind.nodeLine.text = text
    }

    private fun paintTile(tile: android.view.View, title: android.widget.TextView, on: Boolean) {
        title.setTextColor(requireContext().getColor(if (on) R.color.accent else R.color.muted))
    }

    private fun modeLabel(): String = when (Prefs.mode) {
        "global" -> "全局"
        "direct" -> "直连"
        else -> "规则"
    }

    private fun comboDesc(vpn: Boolean): String {
        val d = Prefs.dnsOn
        val r = Prefs.routeOn
        val s = Prefs.skipOn
        return when {
            d && r && s && vpn -> "DNS 服务器拦广告，分流只做路由，GKD 点跳过。"
            d && r && vpn -> "DNS 服务器拦广告，分流只做路由。"
            d && !r && vpn -> "本地 DNS 服务器：广告域 0.0.0.0，流量不进隧道。"
            vpn -> "隧道在。DNS ${if (d) "开" else "关"} · 分流 ${if (r) "开" else "关"} · GKD ${if (s) "开" else "关"}。"
            s -> "只开了跳过，不占 VPN。"
            else -> "打开网络栈后，再去 DNS / 分流页把功能打开。"
        }
    }

    private fun skipEnabled(): Boolean {
        val flat = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val want = requireContext().packageName + "/" + WuadSkipService::class.java.name
        return flat.split(':').any { it.equals(want, true) || it.endsWith(WuadSkipService::class.java.simpleName) }
    }

    private fun openLogs(filter: String) {
        startActivity(Intent(requireContext(), LogsActivity::class.java).putExtra("filter", filter))
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
}
