package com.voiceprompter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.CountDownTimer
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), RecognitionListener {

    data class Token(val norm: String, val start: Int, val end: Int)

    private lateinit var scroll: ScrollView
    private lateinit var textView: TextView
    private lateinit var micDot: TextView
    private lateinit var btnPlay: Button
    private lateinit var countdownView: TextView
    private lateinit var statusView: TextView

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var listening = false

    private var scriptText = ""
    private var tokens = listOf<Token>()
    private var cursor = 0
    private var lastPartialCount = 0
    private val mismatchBuf = ArrayList<String>()

    private var fontSize = 34f
    private var colorIdx = 0
    private val colors = intArrayOf(Color.WHITE, Color.YELLOW, Color.rgb(90, 255, 130))
    private var mirrored = false
    private var micName = "Встроенный микрофон"

    private val prefs by lazy { getSharedPreferences("vp", Context.MODE_PRIVATE) }

    private val demoText = "Добро пожаловать в ВойсПромптер. Это ваш личный телесуфлёр, который слушает голос и движется вместе с вами.\n\nЧитайте текст спокойно, в своём привычном темпе. Как только вы замолчите или скажете что-то постороннее, дорожка остановится и будет ждать вас.\n\nПрочитанные слова затемняются, но остаются видимыми, чтобы вы не теряли строку.\n\nЕсли вы перескочите в другое место сценария и прочитаете несколько слов подряд, суфлёр сам найдёт это место и продолжит оттуда. Нажмите на карандаш сверху, чтобы вставить свой текст. Удачной записи!"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scroll = findViewById(R.id.scroll)
        textView = findViewById(R.id.promptText)
        micDot = findViewById(R.id.micDot)
        btnPlay = findViewById(R.id.btnPlay)
        countdownView = findViewById(R.id.countdown)
        statusView = findViewById(R.id.status)

        scriptText = prefs.getString("text", demoText) ?: demoText
        fontSize = prefs.getFloat("size", 34f)
        colorIdx = prefs.getInt("color", 0)
        mirrored = prefs.getBoolean("mirror", false)

        applyFont()
        applyMirror()
        resetScript()

        btnPlay.setOnClickListener { if (listening) stopListening() else startWithCountdown() }
        findViewById<Button>(R.id.btnRestart).setOnClickListener { resetScript() }
        findViewById<Button>(R.id.btnEdit).setOnClickListener { showEditor() }
        findViewById<Button>(R.id.btnPlus).setOnClickListener { changeFont(+4f) }
        findViewById<Button>(R.id.btnMinus).setOnClickListener { changeFont(-4f) }
        findViewById<Button>(R.id.btnColor).setOnClickListener {
            colorIdx = (colorIdx + 1) % colors.size
            prefs.edit().putInt("color", colorIdx).apply()
            render()
        }
        findViewById<Button>(R.id.btnMirror).setOnClickListener {
            mirrored = !mirrored
            prefs.edit().putBoolean("mirror", mirrored).apply()
            applyMirror()
        }
        micDot.setOnClickListener { Toast.makeText(this, micName, Toast.LENGTH_LONG).show() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        statusView.text = "Загрузка модели…"
        StorageService.unpack(this, "model-ru", "model",
            { m: Model ->
                model = m
                statusView.text = "Готов"
            },
            { e: IOException ->
                statusView.text = "Ошибка модели: " + e.message
            })

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = updateMic()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = updateMic()
        }, null)
        updateMic()
    }

    // ---------- Текст и токены ----------

    private fun tokenize() {
        val list = ArrayList<Token>()
        val re = Regex("[\\p{L}\\p{Nd}]+(?:-[\\p{L}\\p{Nd}]+)*")
        for (m in re.findAll(scriptText)) {
            val norm = m.value.lowercase().replace("ё", "е").replace("-", "")
            if (norm.isNotEmpty()) list.add(Token(norm, m.range.first, m.range.last + 1))
        }
        tokens = list
    }

    private fun resetScript() {
        tokenize()
        cursor = 0
        mismatchBuf.clear()
        lastPartialCount = 0
        render()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun render() {
        val sp = SpannableString(scriptText)
        val readEnd = if (cursor > 0) tokens[cursor - 1].end else 0
        if (readEnd > 0) {
            sp.setSpan(
                ForegroundColorSpan(Color.argb(115, 140, 140, 140)),
                0, readEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        textView.setTextColor(colors[colorIdx])
        textView.text = sp
    }

    private fun scrollToCursor() {
        textView.post {
            val layout = textView.layout ?: return@post
            val offset = if (cursor < tokens.size) tokens[cursor].start else scriptText.length
            val line = layout.getLineForOffset(offset.coerceIn(0, scriptText.length))
            val y = layout.getLineTop(line) + textView.top - scroll.height / 3
            scroll.smoothScrollTo(0, max(0, y))
        }
    }

    // ---------- Настройки вида ----------

    private fun applyFont() {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
    }

    private fun changeFont(d: Float) {
        fontSize = (fontSize + d).coerceIn(20f, 80f)
        prefs.edit().putFloat("size", fontSize).apply()
        applyFont()
        scrollToCursor()
    }

    private fun applyMirror() {
        scroll.scaleX = if (mirrored) -1f else 1f
    }

    private fun showEditor() {
        val et = EditText(this)
        et.setText(scriptText)
        et.minLines = 8
        et.gravity = Gravity.TOP
        AlertDialog.Builder(this)
            .setTitle("Текст сценария")
            .setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                scriptText = et.text.toString()
                prefs.edit().putString("text", scriptText).apply()
                resetScript()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- Микрофон ----------

    private fun updateMic() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val devs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val ext = devs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        runOnUiThread {
            if (ext != null) {
                micDot.setTextColor(Color.GREEN)
                micName = "Внешний микрофон: " + ext.productName
            } else {
                micDot.setTextColor(Color.rgb(255, 150, 0))
                micName = "Встроенный микрофон"
            }
        }
    }

    // ---------- Старт / стоп ----------

    private fun startWithCountdown() {
        if (model == null) {
            Toast.makeText(this, "Модель ещё загружается, подождите…", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        countdownView.visibility = View.VISIBLE
        btnPlay.isEnabled = false
        object : CountDownTimer(3000, 1000) {
            override fun onTick(ms: Long) {
                countdownView.text = ((ms / 1000) + 1).toString()
            }
            override fun onFinish() {
                countdownView.visibility = View.GONE
                btnPlay.isEnabled = true
                startListening()
            }
        }.start()
    }

    private fun startListening() {
        try {
            val rec = Recognizer(model, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService!!.startListening(this)
            listening = true
            lastPartialCount = 0
            mismatchBuf.clear()
            btnPlay.text = "⏸"
            statusView.text = "Слушаю"
        } catch (e: Exception) {
            statusView.text = "Ошибка микрофона: " + e.message
        }
    }

    private fun stopListening() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        listening = false
        btnPlay.text = "▶"
        statusView.text = "Пауза"
    }

    // ---------- Vosk callbacks ----------

    override fun onPartialResult(hypothesis: String?) {
        val words = extractWords(hypothesis, "partial")
        val stable = max(0, words.size - 1)
        var i = lastPartialCount
        while (i < stable) {
            processSpoken(words[i]); i++
        }
        if (stable > lastPartialCount) lastPartialCount = stable
    }

    override fun onResult(hypothesis: String?) {
        val words = extractWords(hypothesis, "text")
        var i = lastPartialCount
        while (i < words.size) {
            processSpoken(words[i]); i++
        }
        lastPartialCount = 0
    }

    override fun onFinalResult(hypothesis: String?) {
        lastPartialCount = 0
    }

    override fun onError(exception: Exception?) {
        statusView.text = "Ошибка: " + exception?.message
    }

    override fun onTimeout() {}

    private fun extractWords(json: String?, key: String): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val s = JSONObject(json).optString(key, "")
            if (s.isBlank()) emptyList() else s.trim().split(Regex("\\s+"))
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- Алгоритм следования ----------

    private fun processSpoken(raw: String) {
        val w = raw.lowercase().replace("ё", "е").filter { it.isLetterOrDigit() }
        if (w.isEmpty()) return
        val end = min(cursor + 10, tokens.size)
        for (i in cursor until end) {
            if (fuzzyEq(w, tokens[i].norm)) {
                cursor = i + 1
                mismatchBuf.clear()
                render()
                scrollToCursor()
                statusView.text = "Слушаю ✓"
                return
            }
        }
        mismatchBuf.add(w)
        if (mismatchBuf.size >= 3) tryJump()
    }

    private fun tryJump() {
        val n = 3
        val tail = mismatchBuf.takeLast(n)
        val candidates = ArrayList<Int>()
        var i = 0
        while (i <= tokens.size - n) {
            var ok = true
            for (k in 0 until n) {
                if (!fuzzyEq(tail[k], tokens[i + k].norm)) { ok = false; break }
            }
            if (ok) candidates.add(i)
            i++
        }
        if (candidates.isEmpty()) {
            if (mismatchBuf.size > 10) mismatchBuf.clear()
            return
        }
        val best = candidates.minByOrNull { abs(it - cursor) } ?: return
        cursor = best + n
        mismatchBuf.clear()
        render()
        scrollToCursor()
        statusView.text = "Перескок ↷"
    }

    private fun fuzzyEq(a: String, b: String): Boolean {
        if (a == b) return true
        val minLen = min(a.length, b.length)
        if (minLen <= 3) return false
        val thr = if (minLen <= 5) 1 else 2
        if (abs(a.length - b.length) > thr) return false
        return lev(a, b) <= thr
    }

    private fun lev(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
    }
}
