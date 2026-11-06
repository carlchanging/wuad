package app.wuad.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import app.wuad.R
import app.wuad.core.EventStore
import app.wuad.core.GkdCats
import app.wuad.core.Prefs
import app.wuad.core.RemoteFiles
import app.wuad.core.RuleStore
import app.wuad.core.RuleUpdater
import app.wuad.databinding.FragGkdBinding
import app.wuad.skip.WuadSkipService
import com.google.android.material.switchmaterial.SwitchMaterial

class GkdFragment : Fragment() {
    private var _b: FragGkdBinding? = null
    private val bind get() = _b!!
    private var bindingSwitches = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragGkdBinding.inflate(inflater, container, false)
        return bind.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bind.swSkip.setOnCheckedChangeListener { _, on ->
            if (bindingSwitches) return@setOnCheckedChangeListener
            Prefs.skipOn = on
            if (on && !skipEnabled()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(requireContext(), "打开「无广告 · 跳过引擎」", Toast.LENGTH_LONG).show()
            }
            paintA11y()
        }
        bind.a11yLine.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        fillCats()
        bind.btnIgnoreApps.setOnClickListener {
            startActivity(Intent(requireContext(), AppsActivity::class.java).putExtra("mode", "gkd"))
        }
        bind.btnRules.setOnClickListener {
            startActivity(Intent(requireContext(), GkdRulesActivity::class.java))
        }
        bind.btnSaveUrl.setOnClickListener {
            Prefs.gkdUrl = bind.url.text.toString().trim()
            Toast.makeText(requireContext(), "地址已保存", Toast.LENGTH_SHORT).show()
        }
        bind.btnUpdate.setOnClickListener {
            Prefs.gkdUrl = bind.url.text.toString().trim()
            bind.btnUpdate.isEnabled = false
            bind.btnUpdate.text = "更新中…"
            val ctx = requireContext().applicationContext
            Thread {
                val msg = try {
                    RuleUpdater.updateGkd(ctx)
                } catch (e: Exception) {
                    "更新失败：${e.message}"
                }
                Handler(Looper.getMainLooper()).post {
                    if (_b == null) return@post
                    bind.btnUpdate.isEnabled = true
                    bind.btnUpdate.text = "更新规则"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    paintMeta()
                }
            }.start()
        }
        bind.btnPreset.setOnClickListener {
            if (RuleStore.gkdPresetOn()) RuleStore.removeGkdPreset() else RuleStore.enableGkd(true)
            Toast.makeText(requireContext(), "预置状态已改", Toast.LENGTH_SHORT).show()
            paintMeta()
        }
        bind.btnRestore.setOnClickListener {
            RuleStore.restoreGkdPreset()
            Toast.makeText(requireContext(), "预置已恢复", Toast.LENGTH_SHORT).show()
            paintMeta()
        }
    }

    override fun onResume() {
        super.onResume()
        bindingSwitches = true
        bind.swSkip.isChecked = Prefs.skipOn
        syncCats()
        bindingSwitches = false
        paintA11y()
        bind.ignoreHint.text = if (Prefs.featureOff("gkd").isEmpty()) {
            "默认全部启用跳过。点「不启用的应用」自己关，比如 IDM。"
        } else {
            "已关闭 ${Prefs.featureOff("gkd").size} 个应用的跳过。"
        }
        paintMeta()
    }

    fun onBack(): Boolean = false

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    private fun paintMeta() {
        if (bind.url.text?.toString() != Prefs.gkdUrl) bind.url.setText(Prefs.gkdUrl)
        val on = RuleStore.gkdPresetOn()
        val n = RuleStore.gkdPresetCount()
        bind.presetLine.text = "预置 GKD $n 条" + (if (on) " · 已启用" else " · 已停用") +
            "。上次 ${RemoteFiles.fmtTime(Prefs.lastUpdate("gkd"))}"
        bind.btnPreset.text = if (on) "停用预置" else "启用预置"
        bind.rulesHint.text = "$n 条预置 · 今日跳过 ${EventStore.today("click")} 次 · 按应用打开"
    }

    private fun fillCats() {
        if (bind.catBox.childCount > 0) return
        val ctx = requireContext()
        val px = (52 * resources.displayMetrics.density).toInt()
        GkdCats.all.forEachIndexed { i, cat ->
            if (i > 0) {
                val line = View(ctx)
                line.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1
                )
                line.setBackgroundColor(ctx.getColor(R.color.line))
                bind.catBox.addView(line)
            }
            val sw = SwitchMaterial(ctx)
            sw.tag = cat.key
            sw.text = cat.name
            sw.minHeight = px
            sw.setTextColor(ctx.getColor(R.color.text))
            sw.textSize = 17f
            sw.trackTintList = ctx.getColorStateList(R.color.switch_track)
            sw.thumbTintList = ctx.getColorStateList(R.color.switch_thumb)
            sw.setOnCheckedChangeListener { _, on ->
                if (!bindingSwitches) Prefs.setGkdCatOn(cat.key, on)
            }
            bind.catBox.addView(
                sw,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun syncCats() {
        for (i in 0 until bind.catBox.childCount) {
            val sw = bind.catBox.getChildAt(i) as? SwitchMaterial ?: continue
            val key = sw.tag as? Int ?: continue
            sw.isChecked = Prefs.gkdCatOn(key)
        }
    }

    private fun paintA11y() {
        val on = skipEnabled()
        bind.a11yLine.text = if (on) "无障碍已开。点此可去系统页确认。" else "无障碍未开。点此打开「无广告 · 跳过引擎」。"
        bind.a11yLine.setTextColor(requireContext().getColor(if (on) R.color.success else R.color.warn))
        bind.a11yLine.setBackgroundResource(if (on) R.drawable.bg_card else R.drawable.bg_banner)
    }

    private fun skipEnabled(): Boolean {
        val flat = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val want = requireContext().packageName + "/" + WuadSkipService::class.java.name
        return flat.split(':').any { it.equals(want, true) || it.endsWith(WuadSkipService::class.java.simpleName) }
    }
}
