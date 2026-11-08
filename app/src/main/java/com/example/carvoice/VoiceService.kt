package com.example.carvoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class VoiceService : Service(), RecognitionListener {
    companion object { var onLog: ((String) -> Unit)? = null }

    private var speech: SpeechService? = null
    private var wake = ""
    private var awaitingUntil = 0L

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

        wake = toCyr(getSharedPreferences("cfg", MODE_PRIVATE).getString("wake", "")!!.lowercase().trim())
        log("Загрузка модели…")
        StorageService.unpack(this, "model-ru", "model",
            { model: Model ->
                speech = SpeechService(Recognizer(model, 16000f), 16000f).also { it.startListening(this) }
                log("Слушаю. Слово активации: $wake")
            },
            { e -> log("Ошибка модели: ${e.message}") })
        return START_STICKY
    }

    override fun onResult(h: String?) {
        val t = JSONObject(h ?: return).optString("text").trim()
        if (t.isEmpty()) return
        log("Услышал: $t")
        val now = SystemClock.elapsedRealtime()
        val words = t.split(" ")
        val idx = if (wake.isBlank()) -1 else words.indexOf(wake)
        when {
            idx >= 0 -> {
                val cmd = words.drop(idx + 1).joinToString(" ")
                if (cmd.isBlank()) awaitingUntil = now + 6000 else handle(cmd)
            }
            now < awaitingUntil -> { awaitingUntil = 0; handle(t) }
        }
    }

    private fun handle(c: String) {
        val am = getSystemService(AudioManager::class.java)
        fun vol(dir: Int) = repeat(2) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        }
        val prefixes = listOf(
            "поехали на ", "поехали в ", "поехали к ", "поедем на ", "едем на ",
            "навигация на ", "маршрут до ", "маршрут на "
        )
        val p = prefixes.firstOrNull { c.startsWith(it) }
        when {
            p != null -> go(Intent(Intent.ACTION_VIEW,
                Uri.parse("yandexnavi://map_search?text=" + Uri.encode(c.removePrefix(p))))
                .setPackage("ru.yandex.yandexnavi"))
            "навигатор" in c -> packageManager.getLaunchIntentForPackage("ru.yandex.yandexnavi")?.let { go(it) }
            "громче" in c || "прибавь" in c -> vol(AudioManager.ADJUST_RAISE)
            "тише" in c || "убавь" in c -> vol(AudioManager.ADJUST_LOWER)
            "без звука" in c -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            else -> log("Не понял команду: $c")
        }
    }

    private fun go(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    // Vosk's Russian model writes the wake word in Cyrillic, so Latin input is transliterated
    private fun toCyr(s: String): String {
        val m = mapOf(
            'a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф", 'g' to "г",
            'h' to "х", 'i' to "и", 'j' to "дж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н",
            'o' to "о", 'p' to "п", 'q' to "к", 'r' to "р", 's' to "с", 't' to "т", 'u' to "у",
            'v' to "в", 'w' to "в", 'x' to "кс", 'y' to "и", 'z' to "з"
        )
        return s.map { m[it] ?: it.toString() }.joinToString("")
    }

    private fun log(s: String) { Handler(Looper.getMainLooper()).post { onLog?.invoke(s) } }

    override fun onPartialResult(h: String?) {}
    override fun onFinalResult(h: String?) {}
    override fun onError(e: Exception?) { log("Ошибка: ${e?.message}") }
    override fun onTimeout() {}

    override fun onDestroy() {
        speech?.stop(); speech?.shutdown()
        super.onDestroy()
    }
}
