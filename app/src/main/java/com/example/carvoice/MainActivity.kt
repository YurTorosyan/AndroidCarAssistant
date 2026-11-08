package com.example.carvoice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val wake = EditText(this).apply {
            hint = "Слово активации (например Kia)"
            setText(prefs.getString("wake", ""))
            setSingleLine()
        }
        val start = Button(this).apply { text = "Сохранить и запустить" }
        val stop = Button(this).apply { text = "Остановить" }
        status = TextView(this).apply { text = "Готово к запуску"; textSize = 16f }
        root.addView(wake); root.addView(start); root.addView(stop); root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })

        start.setOnClickListener {
            val w = wake.text.toString().trim()
            if (w.isEmpty()) { status.text = "Введите слово активации"; return@setOnClickListener }
            prefs.edit().putString("wake", w).apply()
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
                requestPermissions(perms.toTypedArray(), 1)
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                status.text = "Разрешите «Поверх других окон» и нажмите «Запустить» снова"
                return@setOnClickListener
            }
            startForegroundService(Intent(this, VoiceService::class.java))
        }
        stop.setOnClickListener {
            stopService(Intent(this, VoiceService::class.java))
            status.text = "Остановлено"
        }
        VoiceService.onLog = { s -> runOnUiThread { status.text = s } }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        status.text = "Разрешения обработаны. Нажмите «Сохранить и запустить» ещё раз"
    }
}
