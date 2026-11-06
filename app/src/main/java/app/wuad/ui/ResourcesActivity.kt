package app.wuad.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.wuad.R
import app.wuad.core.GeoStore
import app.wuad.core.Prefs
import app.wuad.core.RemoteFiles
import app.wuad.databinding.ActivityResourcesBinding

class ResourcesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bind = ActivityResourcesBinding.inflate(layoutInflater)
        setContentView(bind.root)
        paint(bind.box)
    }

    private fun paint(box: LinearLayout) {
        box.removeAllViews()
        val inf = LayoutInflater.from(this)
        GeoStore.packs.forEach { pack ->
            val card = inf.inflate(R.layout.item_geo, box, false)
            val title = card.findViewById<TextView>(R.id.title)
            val sub = card.findViewById<TextView>(R.id.sub)
            val url = card.findViewById<EditText>(R.id.url)
            val btnSave = card.findViewById<Button>(R.id.btnSave)
            val btnUp = card.findViewById<Button>(R.id.btnUpdate)
            val btnDel = card.findViewById<Button>(R.id.btnDelete)
            val local = GeoStore.file(this, pack)
            title.text = pack.title
            sub.text = pack.purpose + "\n" +
                (if (local.exists()) "本地 ${local.length() / 1024} KB · " else "尚未下载 · ") +
                RemoteFiles.fmtTime(Prefs.lastUpdate(pack.key))
            url.setText(Prefs.geoUrl(pack.key, pack.defaultUrl))
            btnSave.setOnClickListener {
                Prefs.setGeoUrl(pack.key, url.text.toString())
                Toast.makeText(this, "地址已保存", Toast.LENGTH_SHORT).show()
            }
            btnUp.setOnClickListener {
                Prefs.setGeoUrl(pack.key, url.text.toString())
                btnUp.isEnabled = false
                btnUp.text = "下载中…"
                Thread {
                    val msg = try {
                        GeoStore.update(this, pack)
                    } catch (e: Exception) {
                        "失败：${e.message}"
                    }
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        paint(box)
                    }
                }.start()
            }
            btnDel.setOnClickListener {
                GeoStore.deleteLocal(this, pack)
                Toast.makeText(this, "本地文件已删，下次再下", Toast.LENGTH_SHORT).show()
                paint(box)
            }
            box.addView(card)
        }
    }
}
