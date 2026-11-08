package com.example.carvoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class VoiceService : Service(), RecognitionListener {
    companion object { var onLog: ((String) -> Unit)? = null }

    private val ui = Handler(Looper.getMainLooper())
    private var speech: SpeechService? = null
    private var wakes = listOf<String>()
    private var active = false
    private var overlay: LinearLayout? = null
    private var label: TextView? = null
    private val timeout = Runnable { hide() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val ch = "voice"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ch, "Голосовой помощник", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, ch)
            .setContentTitle("Голосовой помощник слушает")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        // В поле можно вписать несколько вариантов через запятую: вор, киа, кия
        wakes = getSharedPreferences("cfg", MODE_PRIVATE).getString("wake", "")!!
            .lowercase().split(",").map { toCyr(it.trim()) }.filter { it.isNotBlank() }
        log("Загрузка модели…")
        StorageService.unpack(this, "model-ru", "model",
            { model: Model ->
                speech = SpeechService(Recognizer(model, 16000f), 16000f).also { it.startListening(this) }
                log("Слушаю. Слова активации: ${wakes.joinToString()}")
            },
            { e -> log("Ошибка модели: ${e.message}") })
        return START_STICKY
    }

    // --- Оверлей (шар), как у Siri ---
    private fun show(text: String, hideAfter: Long) {
        ui.removeCallbacks(timeout)
        if (overlay == null) {
            try {
                val dp = resources.displayMetrics.density
                val orb = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#4F8CFF"))
                    }
                    layoutParams = LinearLayout.LayoutParams((90 * dp).toInt(), (90 * dp).toInt())
                    startAnimation(ScaleAnimation(
                        0.8f, 1.2f, 0.8f, 1.2f,
                        Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f
                    ).apply {
                        duration = 600
                        repeatCount = Animation.INFINITE
                        repeatMode = Animation.REVERSE
                    })
                }
                val tv = TextView(this).apply {
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setPadding(0, (16 * dp).toInt(), 0, 0)
                }
                label = tv
                overlay = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding((32 * dp).toInt(), (28 * dp).toInt(), (32 * dp).toInt(), (28 * dp).toInt())
                    background = GradientDrawable().apply {
                        cornerRadius = 32 * dp
                        setColor(Color.parseColor("#E6101828"))
                    }
                    addView(orb)
                    addView(tv)
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.CENTER }
                getSystemService(WindowManager::class.java).addView(overlay, lp)
            } catch (e: Exception) {
                overlay = null
                label = null
                log("Нет оверлея: ${e.message}")
            }
        }
        label?.text = text
        ui.postDelayed(timeout, hideAfter)
    }

    private fun hide() {
        ui.removeCallbacks(timeout)
        active = false
        overlay?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {}
        }
        overlay = null
        label = null
    }

    private fun activate() { active = true; show("Слушаю…", 8000) }
    private fun done(msg: String) { show(msg, 2500); active = false }

    // --- Распознавание ---
    override fun onPartialResult(h: String?) {
        if (active) return
        val t = JSONObject(h ?: return).optString("partial")
        if (t.split(" ").any { it in wakes }) ui.post { activate() }
    }

    override fun onResult(h: String?) {
        val t = JSONObject(h ?: return).optString("text").trim()
        if (t.isEmpty()) return
        log("Услышал: $t")
        val words = t.split(" ")
        val idx = words.indexOfFirst { it in wakes }
        ui.post {
            if (!active) {
                if (idx < 0) return@post
                activate()
            }
            val cmd = (if (idx >= 0) words.drop(idx + 1) else words).joinToString(" ")
            if (cmd.isNotBlank()) handle(cmd)
        }
    }

    // --- Команды ---
    private fun handle(c: String) {
        val am = getSystemService(AudioManager::class.java)
        fun vol(dir: Int, name: String) {
            val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            var after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (after == before) {
                am.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
                after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            done("$name: $before → $after")
        }
        val prefixes = listOf(
            "поехали на ", "поехали в ", "поехали к ", "поедем на ", "едем на ",
            "навигация на ", "маршрут до ", "маршрут на "
        )
        val p = prefixes.firstOrNull { c.startsWith(it) }
        when {
            p != null -> {
                val addr = c.removePrefix(p)
                done("Ищу: $addr")
                go(Intent(Intent.ACTION_VIEW, Uri.parse("yandexnavi://map_search?text=" + Uri.encode(addr)))
                    .setPackage("ru.yandex.yandexnavi"))
            }
            "навигатор" in c -> {
                val li = packageManager.getLaunchIntentForPackage("ru.yandex.yandexnavi")
                if (li != null) { done("Открываю навигатор"); go(li) } else done("Яндекс Навигатор не найден")
            }
            "громче" in c || "прибавь" in c -> vol(AudioManager.ADJUST_RAISE, "Громче")
            "тише" in c || "убавь" in c -> vol(AudioManager.ADJUST_LOWER, "Тише")
            "без звука" in c -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                done("Без звука")
            }
            else -> done("Не понял: $c")
        }
    }

    private fun go(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { log("Не открылось: ${e.message}") }
    }

    private fun toCyr(s: String): String {
        val m = mapOf(
            'a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф", 'g' to "г",
            'h' to "х", 'i' to "и", 'j' to "дж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н",
            'o' to "о", 'p' to "п", 'q' to "к", 'r' to "р", 's' to "с", 't' to "т", 'u' to "у",
            'v' to "в", 'w' to "в", 'x' to "кс", 'y' to "и", 'z' to "з"
        )
        return s.map { m[it] ?: it.toString() }.joinToString("")
    }

    private fun log(s: String) { ui.post { onLog?.invoke(s) } }

    override fun onFinalResult(h: String?) {}
    override fun onError(e: Exception?) { log("Ошибка: ${e?.message}") }
    override fun onTimeout() {}

    override fun onDestroy() {
        hide()
        speech?.stop(); speech?.shutdown()
        super.onDestroy()
    }
}
