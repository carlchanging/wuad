package app.wuad.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import app.wuad.core.Prefs
import app.wuad.core.SubStore
import app.wuad.databinding.FragRouteBinding
import app.wuad.databinding.ItemDnsBinding
import app.wuad.vpn.WuadVpnService

class RouteFragment : Fragment() {
    private var _b: FragRouteBinding? = null
    private val bind get() = _b!!
    private lateinit var rules: RulesPage
    private var bindingSwitches = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragRouteBinding.inflate(inflater, container, false)
        return bind.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        SubStore.init(requireContext())
        rules = RulesPage(this, bind.rules, "route")
        rules.attach()
        bind.swRoute.setOnCheckedChangeListener { _, on ->
            if (bindingSwitches) return@setOnCheckedChangeListener
            Prefs.routeOn = on
            Toast.makeText(requireContext(), if (on) "分流开" else "分流关", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.syncNetwork()
        }
        bind.btnSubAdd.setOnClickListener {
            val item = SubStore.add(bind.subNewName.text.toString(), bind.subNewUrl.text.toString())
            if (item == null) {
                Toast.makeText(requireContext(), "订阅得是 http/https", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            bind.subNewName.setText("")
            bind.subNewUrl.setText("")
            paintSubs()
            WuadVpnService.reload(requireContext())
        }
        bind.btnGeo.setOnClickListener { startActivity(Intent(requireContext(), ResourcesActivity::class.java)) }
        bind.btnApps.setOnClickListener { startActivity(Intent(requireContext(), AppsActivity::class.java)) }
        bind.btnIgnoreApps.setOnClickListener {
            startActivity(Intent(requireContext(), AppsActivity::class.java).putExtra("mode", "route"))
        }
    }

    override fun onResume() {
        super.onResume()
        bindingSwitches = true
        bind.swRoute.isChecked = Prefs.routeOn
        bindingSwitches = false
        paintSubs()
        bind.ignoreHint.text = if (Prefs.featureOff("route").isEmpty()) {
            "默认全部走分流。点「不启用的应用」关掉某个 App。"
        } else {
            "已关闭 ${Prefs.featureOff("route").size} 个应用的分流，它们直连。"
        }
        rules.paint()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    private fun paintSubs() {
        bind.subList.removeAllViews()
        val inf = LayoutInflater.from(requireContext())
        SubStore.all().forEach { s ->
            val row = ItemDnsBinding.inflate(inf, bind.subList, false)
            row.title.text = s.name
            row.name.setText(s.name)
            row.url.setText(s.url)
            row.btnUse.visibility = View.GONE
            row.btnSave.setOnClickListener {
                if (SubStore.edit(s.id, row.name.text.toString(), row.url.text.toString())) {
                    WuadVpnService.reload(requireContext())
                    paintSubs()
                }
            }
            row.btnDelete.setOnClickListener {
                SubStore.delete(s.id)
                WuadVpnService.reload(requireContext())
                paintSubs()
            }
            bind.subList.addView(row.root)
        }
        bind.subHint.text = if (SubStore.all().isEmpty()) {
            "还没机场订阅。PROXY=直连。多份进 PROXY，另有 AUTO。"
        } else {
            "已有 ${SubStore.all().size} 份订阅。"
        }
    }
}
