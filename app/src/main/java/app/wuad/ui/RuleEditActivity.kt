package app.wuad.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.wuad.core.RouteStore
import app.wuad.core.RuleStore
import app.wuad.databinding.ActivityRuleEditBinding
import app.wuad.vpn.WuadVpnService

class RuleEditActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bind = ActivityRuleEditBinding.inflate(layoutInflater)
        setContentView(bind.root)
        val kind = intent.getStringExtra("kind") ?: "dns"
        val id = intent.getStringExtra("id")
        val creating = id.isNullOrBlank()

        if (kind == "route") {
            bind.pageTitle.text = if (creating) "添加分流规则" else "编辑分流规则"
            bind.l1.text = "类型：PROVIDER / GEOIP / GEOSITE / DOMAIN / DOMAIN-SUFFIX / MATCH"
            bind.l2.text = "内容：列表名 / CN / gfw / 域名 / *"
            bind.l3.text = "策略：PROXY / DIRECT / REJECT"
            bind.l4.text = "订阅地址（PROVIDER / GEOSITE / GEOIP）"
            bind.enabled.visibility = View.VISIBLE
            val item = if (creating) null else RouteStore.find(id ?: "")
            bind.fieldMain.setText(item?.type ?: "DOMAIN-SUFFIX")
            bind.fieldTwo.setText(item?.payload ?: "")
            bind.fieldThree.setText(item?.policy ?: "PROXY")
            bind.fieldFour.setText(item?.url ?: "")
            bind.enabled.isChecked = item?.enabled ?: true
            bind.btnSave.setOnClickListener {
                val type = bind.fieldMain.text.toString().trim()
                val payload = bind.fieldTwo.text.toString().trim()
                val policy = bind.fieldThree.text.toString().trim()
                val url = bind.fieldFour.text.toString().trim()
                if (type.isEmpty() || payload.isEmpty() || policy.isEmpty()) {
                    Toast.makeText(this, "类型、内容和策略都要填", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (creating) RouteStore.add(type, payload, policy, url, bind.enabled.isChecked)
                else RouteStore.edit(id!!, type, payload, policy, url, bind.enabled.isChecked)
                WuadVpnService.reload(this)
                finish()
            }
        } else if (kind == "dns") {
            bind.pageTitle.text = if (creating) "添加 DNS 规则" else "编辑 DNS 规则"
            bind.l1.text = "域名"
            bind.l2.text = "动作：block 或 allow"
            bind.l3.visibility = View.GONE
            bind.fieldThree.visibility = View.GONE
            bind.l4.visibility = View.GONE
            bind.fieldFour.visibility = View.GONE
            bind.enabled.visibility = View.GONE
            if (!creating) {
                val custom = RuleStore.dnsCustom().find { it.id == id || it.host == id }
                bind.fieldMain.setText(custom?.host ?: id)
                bind.fieldTwo.setText(custom?.action ?: "block")
            } else {
                bind.fieldTwo.setText("block")
            }
            bind.btnSave.setOnClickListener {
                val host = bind.fieldMain.text.toString().trim().lowercase()
                val action = bind.fieldTwo.text.toString().trim().lowercase().ifEmpty { "block" }
                if (host.isEmpty() || '.' !in host) {
                    Toast.makeText(this, "域名不对", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (creating) RuleStore.addDns(host, if (action == "allow") "allow" else "block")
                else RuleStore.editDns(id!!, host, if (action == "allow") "allow" else "block")
                finish()
            }
        } else {
            bind.pageTitle.text = if (creating) "添加跳过规则" else "编辑跳过规则"
            bind.l1.text = "包名（* 表示全局）"
            bind.l2.text = "应用名"
            bind.l3.text = "规则组名"
            bind.l4.text = "匹配文本，可用 | 分隔"
            bind.enabled.visibility = View.VISIBLE
            val item = if (creating) null else RuleStore.gkdVisible().find { it.id == id }
            bind.fieldMain.setText(item?.pkg ?: intent.getStringExtra("pkg").orEmpty())
            bind.fieldTwo.setText(item?.app ?: intent.getStringExtra("app").orEmpty())
            bind.fieldThree.setText(item?.group ?: "开屏广告")
            bind.fieldFour.setText(item?.match ?: "跳过")
            bind.enabled.isChecked = item?.enabled ?: true
            bind.btnSave.setOnClickListener {
                val pkg = bind.fieldMain.text.toString().trim()
                if (pkg.isEmpty()) {
                    Toast.makeText(this, "包名不能空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val appName = bind.fieldTwo.text.toString()
                val group = bind.fieldThree.text.toString()
                val match = bind.fieldFour.text.toString()
                val on = bind.enabled.isChecked
                if (creating) RuleStore.addGkd(pkg, appName, group, match, on)
                else RuleStore.editGkd(id!!, pkg, appName, group, match, on)
                finish()
            }
        }
    }
}
