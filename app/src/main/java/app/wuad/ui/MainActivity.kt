package app.wuad.ui

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import app.wuad.R
import app.wuad.core.GkdScope
import app.wuad.core.Prefs
import app.wuad.databinding.ActivityMainBinding
import app.wuad.vpn.WuadVpnService

class MainActivity : AppCompatActivity() {
    private lateinit var bind: ActivityMainBinding
    private val pages = HashMap<Int, Fragment>()

    private val vpnPermit = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            Prefs.vpnWanted = true
            WuadVpnService.start(this)
        } else {
            Prefs.vpnWanted = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        bind = ActivityMainBinding.inflate(layoutInflater)
        setContentView(bind.root)
        ViewCompat.setOnApplyWindowInsetsListener(bind.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        bind.nav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }
        val start = when (intent.getStringExtra("tab")) {
            "dns" -> TAB_DNS
            "route" -> TAB_ROUTE
            "gkd" -> TAB_GKD
            else -> TAB_HOME
        }
        bind.nav.selectedItemId = start
        if (pages.isEmpty()) show(start)
    }

    override fun onResume() {
        super.onResume()
        GkdScope.refresh(this)
        val prep = WuadVpnService.restoreIfWanted(this)
        if (prep != null) vpnPermit.launch(prep)
    }

    fun openTab(id: Int) {
        bind.nav.selectedItemId = id
    }

    fun syncNetwork() {
        val prep = app.wuad.core.RouteToggle.sync(this)
        if (prep != null) vpnPermit.launch(prep)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val gkd = pages[TAB_GKD] as? GkdFragment
        if (bind.nav.selectedItemId == TAB_GKD && gkd?.onBack() == true) return
        if (bind.nav.selectedItemId != TAB_HOME) {
            openTab(TAB_HOME)
            return
        }
        super.onBackPressed()
    }

    private fun show(id: Int) {
        val fm = supportFragmentManager.beginTransaction()
        pages.values.forEach { fm.hide(it) }
        val page = pages.getOrPut(id) {
            when (id) {
                TAB_DNS -> DnsFragment()
                TAB_ROUTE -> RouteFragment()
                TAB_GKD -> GkdFragment()
                else -> HomeFragment()
            }
        }
        if (!page.isAdded) fm.add(R.id.pager, page, id.toString())
        fm.show(page).commit()
    }

    companion object {
        val TAB_HOME = R.id.navHome
        val TAB_DNS = R.id.navDns
        val TAB_ROUTE = R.id.navRoute
        val TAB_GKD = R.id.navGkd
    }
}
