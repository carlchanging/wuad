package app.wuad.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import app.wuad.core.DnsSubStore
import app.wuad.core.DnsUpStore
import app.wuad.core.Prefs
import app.wuad.databinding.FragDnsBinding
import app.wuad.databinding.ItemDnsBinding
import app.wuad.vpn.WuadVpnService

class DnsFragment : Fragment() {
    private var _b: FragDnsBinding? = null
    private val bind get() = _b!!
    private lateinit var rules: RulesPage
    private var bindingSwitches = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragDnsBinding.inflate(inflater, container, false)
        return bind.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        DnsUpStore.init(requireContext())
        DnsSubStore.init(requireContext())
        rules = RulesPage(this, bind.rules, "dns")
        rules.attach()
        bind.swDns.setOnCheckedChangeListener { _, on ->
            if (bindingSwitches) return@setOnCheckedChangeListener
            Prefs.dnsOn = on
            Toast.makeText(requireContext(), if (on) "DNS 去广告开" else "DNS 去广告关", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.syncNetwork()
        }
        bind.btnDnsAdd.setOnClickListener {
            val item = DnsUpStore.add(bind.dnsNewName.text.toString(), bind.dnsNewUrl.text.toString())
            if (item == null) {
                Toast.makeText(requireContext(), "地址不对", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            bind.dnsNewName.setText("")
            bind.dnsNewUrl.setText("")
            DnsUpStore.select(item.id)
            paintUps()
            WuadVpnService.reload(requireContext())
        }
        bind.btnDnsRestore.setOnClickListener {
            DnsUpStore.restorePresets()
            paintUps()
        }
        bind.btnSubAdd.setOnClickListener {
            val item = DnsSubStore.add(bind.subNewName.text.toString(), bind.subNewUrl.text.toString())
            if (item == null) {
                Toast.makeText(requireContext(), "得是 http/https 订阅地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            bind.subNewName.setText("")
            bind.subNewUrl.setText("")
            paintSubs()
            Toast.makeText(requireContext(), "已添加，点下面「更新规则」才会拉名单", Toast.LENGTH_SHORT).show()
        }
        bind.btnSubRestore.setOnClickListener {
            DnsSubStore.restorePresets()
            paintSubs()
        }
        bind.btnSaveCustom.setOnClickListener {
            Prefs.customBlock = bind.customBlock.text.toString()
            Prefs.customAllow = bind.customAllow.text.toString()
            WuadVpnService.reload(requireContext())
            Toast.makeText(requireContext(), "自定义域名已保存", Toast.LENGTH_SHORT).show()
        }
        bind.btnIgnoreApps.setOnClickListener {
            startActivity(Intent(requireContext(), AppsActivity::class.java).putExtra("mode", "dns"))
        }
    }

    override fun onResume() {
        super.onResume()
        bindingSwitches = true
        bind.swDns.isChecked = Prefs.dnsOn
        bindingSwitches = false
        bind.customBlock.setText(Prefs.customBlock)
        bind.customAllow.setText(Prefs.customAllow)
        paintUps()
        paintSubs()
        bind.ignoreHint.text = if (Prefs.featureOff("dns").isEmpty()) {
            "默认全部启用去广告。点上面自己关某个 App。"
        } else {
            "已关闭 ${Prefs.featureOff("dns").size} 个应用的去广告。"
        }
        rules.paint()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    private fun paintUps() {
        bind.dnsNow.text = "当前上游：${Prefs.dnsUpstream}"
        bind.dnsList.removeAllViews()
        val inf = LayoutInflater.from(requireContext())
        DnsUpStore.all().forEach { d ->
            val row = ItemDnsBinding.inflate(inf, bind.dnsList, false)
            val on = d.id == Prefs.dnsUpId || d.url == Prefs.dnsUpstream
            row.title.text = d.name + (if (on) "  · 当前" else "") + (if (d.preset) "  · 预置" else "")
            row.name.setText(d.name)
            row.url.setText(d.url)
            row.btnUse.setOnClickListener {
                DnsUpStore.select(d.id)
                WuadVpnService.reload(requireContext())
                paintUps()
            }
            row.btnSave.setOnClickListener {
                if (DnsUpStore.edit(d.id, row.name.text.toString(), row.url.text.toString())) {
                    WuadVpnService.reload(requireContext())
                    paintUps()
                }
            }
            row.btnDelete.setOnClickListener {
                DnsUpStore.delete(d.id)
                paintUps()
            }
            bind.dnsList.addView(row.root)
        }
    }

    private fun paintSubs() {
        val on = DnsSubStore.enabled().size
        bind.subHint.text = "已启用 $on 份，点规则区「更新规则」合并进过滤。AdRules 默认关。"
        bind.subList.removeAllViews()
        val inf = LayoutInflater.from(requireContext())
        DnsSubStore.all().forEach { d ->
            val row = ItemDnsBinding.inflate(inf, bind.subList, false)
            row.title.text = d.name + (if (d.enabled) "  · 启用" else "  · 关闭") + (if (d.preset) "  · 预置" else "")
            row.name.setText(d.name)
            row.url.setText(d.url)
            row.btnUse.text = if (d.enabled) "停用" else "启用"
            row.btnUse.setOnClickListener {
                DnsSubStore.setEnabled(d.id, !d.enabled)
                paintSubs()
                rules.paint()
            }
            row.btnSave.setOnClickListener {
                if (DnsSubStore.edit(d.id, row.name.text.toString(), row.url.text.toString())) {
                    paintSubs()
                } else {
                    Toast.makeText(requireContext(), "地址不对", Toast.LENGTH_SHORT).show()
                }
            }
            row.btnDelete.setOnClickListener {
                if (d.preset) {
                    Toast.makeText(requireContext(), "预置只能停用，不能删", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                DnsSubStore.delete(d.id)
                paintSubs()
                rules.paint()
            }
            bind.subList.addView(row.root)
        }
    }
}
