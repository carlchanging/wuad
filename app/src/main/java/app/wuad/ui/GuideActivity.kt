package app.wuad.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.wuad.databinding.ActivityGuideBinding

class GuideActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bind = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(bind.root)
        bind.btnAcc.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        bind.btnBattery.setOnClickListener {
            val uri = Uri.parse("package:$packageName")
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(uri))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        bind.btnNotify.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 26) {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        bind.btnAutostart.setOnClickListener {
            val tries = listOf(
                Intent().setClassName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.view.StartupAppListActivity"),
                Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName"))
            )
            var ok = false
            for (i in tries) {
                try {
                    startActivity(i)
                    ok = true
                    break
                } catch (_: Exception) {
                }
            }
            if (!ok) Toast.makeText(this, "自己去应用详情里找自启动", Toast.LENGTH_SHORT).show()
        }
    }
}
