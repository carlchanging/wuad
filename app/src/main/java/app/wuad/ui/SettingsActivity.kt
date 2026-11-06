package app.wuad.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.wuad.core.DnsUpStore
import app.wuad.core.Prefs
import app.wuad.core.SubStore
import app.wuad.databinding.ActivitySettingsBinding
import app.wuad.databinding.ItemDnsBinding
import app.wuad.vpn.WuadVpnService

class SettingsActivity : AppCompatActivity() {
    private lateinit var bind: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(bind.root)
        DnsUpStore.init(this)
        SubStore.init(this)
        bind.customBlock.setText(Prefs.customBlock)
        bind.customAllow.setText(Prefs.customAllow)
        bind.checkOnOpen.isChecked = Prefs.checkOnOpen
        bind.btnGeo.setOnClickListener { startActivity(Intent(this, ResourcesActivity::class.java)) }
        bind.btnDnsAdd.setOnClickListener {
            val item = DnsUpStore.add(bind.dnsNewName.text.toString(), bind.dnsNewUrl.text.toString())
            if (item == null) {
                Toast.makeText(this, "地址不对。明文 IP、https:// DoH、tls:// DoT、quic:// 都能填", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            bind.dnsNewName.setText("")
            bind.dnsNewUrl.setText("")
            DnsUpStore.select(item.id)
            paintDns()
            Toast.makeText(this, "已添加并选用", Toast.LENGTH_SHORT).show()
        }
        bind.btnDnsRestore.setOnClickListener {
            DnsUpStore.restorePresets()
            paintDns()
            Toast.makeText(this, "预置上游回来了，自定义还在", Toast.LENGTH_SHORT).show()
        }
        bind.btnSubAdd.setOnClickListener {
            val item = SubStore.add(bind.subNewName.text.toString(), bind.subNewUrl.text.toString())
            if (item == null) {
                Toast.makeText(this, "订阅得是 http/https 地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            bind.subNewName.setText("")
            bind.subNewUrl.setText("")
            paintSubs()
            WuadVpnService.reload(this)
            Toast.makeText(this, "已添加 ${item.name}，隧道会重载", Toast.LENGTH_SHORT).show()
        }
        bind.btnSave.setOnClickListener {
            Prefs.customBlock = bind.customBlock.text.toString()
            Prefs.customAllow = bind.customAllow.text.toString()
            Prefs.checkOnOpen = bind.checkOnOpen.isChecked
            WuadVpnService.reload(this)
            Toast.makeText(this, "已保存。当前 DNS ${Prefs.dnsUpstream}", Toast.LENGTH_SHORT).show()
        }
        paintDns()
        paintSubs()
    }

    private fun paintDns() {
        bind.dnsNow.text = "当前：${Prefs.dnsUpstream}"
        bind.dnsList.removeAllViews()
        val inf = LayoutInflater.from(this)
        DnsUpStore.all().forEach { d ->
            val row = ItemDnsBinding.inflate(inf, bind.dnsList, false)
            val on = d.id == Prefs.dnsUpId || d.url == Prefs.dnsUpstream
            row.title.text = d.name + (if (on) "  · 当前" else "") + (if (d.preset) "  · 预置" else "  · 自定义")
            row.name.setText(d.name)
            row.url.setText(d.url)
            row.btnUse.setOnClickListener {
                DnsUpStore.select(d.id)
                WuadVpnService.reload(this)
                paintDns()
                Toast.makeText(this, "已选用 ${d.name}", Toast.LENGTH_SHORT).show()
            }
            row.btnSave.setOnClickListener {
                if (DnsUpStore.edit(d.id, row.name.text.toString(), row.url.text.toString())) {
                    WuadVpnService.reload(this)
                    paintDns()
                    Toast.makeText(this, "已改", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "地址不对", Toast.LENGTH_SHORT).show()
                }
            }
            row.btnDelete.setOnClickListener {
                DnsUpStore.delete(d.id)
                paintDns()
                Toast.makeText(this, "已删", Toast.LENGTH_SHORT).show()
            }
            bind.dnsList.addView(row.root)
        }
    }

    private fun paintSubs() {
        bind.subList.removeAllViews()
        val inf = LayoutInflater.from(this)
        SubStore.all().forEach { s ->
            val row = ItemDnsBinding.inflate(inf, bind.subList, false)
            row.title.text = s.name
            row.name.setText(s.name)
            row.url.setText(s.url)
            row.btnUse.visibility = View.GONE
            row.btnSave.setOnClickListener {
                if (SubStore.edit(s.id, row.name.text.toString(), row.url.text.toString())) {
                    WuadVpnService.reload(this)
                    paintSubs()
                    Toast.makeText(this, "订阅已改", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "地址不对", Toast.LENGTH_SHORT).show()
                }
            }
            row.btnDelete.setOnClickListener {
                SubStore.delete(s.id)
                WuadVpnService.reload(this)
                paintSubs()
                Toast.makeText(this, "订阅已删", Toast.LENGTH_SHORT).show()
            }
            bind.subList.addView(row.root)
        }
        bind.subHint.text = if (SubStore.all().isEmpty()) {
            "还没订阅。PROXY 等于直连。多份会进同一个 PROXY，并多一个 AUTO 测速组。"
        } else {
            "已有 ${SubStore.all().size} 份。节点页能看到 AUTO 和各节点。"
        }
    }
}
