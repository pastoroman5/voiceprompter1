package com.voiceprompter.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var scrollView: ScrollView
    private lateinit var textView: TextView
    private lateinit var countdownView: TextView
    private lateinit var micDot: TextView
    private lateinit var btnPlay: TextView
    private lateinit var btnJump: TextView

    private var model: Model? = null
    private var modelReady = false
    private var speechService: SpeechService? = null
    private var isPlaying = false

    private var jumpEnabled = true
    private var fontSize = 34f
    private var activeColor = Color.WHITE
    private var mirror = false

    private var rawText = ""
    private val wordsNorm = ArrayList<String>()
    private val wordStarts = ArrayList<Int>()
    private val wordEnds = ArrayList<Int>()
    private var currentIndex = 0
    private var missCount = 0
    private val recent = ArrayList<String>()
    private var partialProcessed = 0
    // Защита от ложного старта: после посторонних слов суфлёр не двигается,
    // пока не услышит ТРИ слова текста подряд
    private var confirmNeeded = false
    private var pendingIndex = -1
    private var pendingCount = 0

    private val handler = Handler(Looper.getMainLooper())

    private val demoText = "Добро пожаловать в ВойсПромптер — суфлёр, который слушает ваш голос.\n\nЧитайте этот текст вслух в обычном темпе. Строка сама поедет за вами. Если вы замолчите, суфлёр остановится и будет ждать вас на том же месте.\n\nПопробуйте сказать что-нибудь постороннее — текст останется на месте, потому что суфлёр следит именно за словами сценария.\n\nА это последний абзац для проверки перескока. Прочитайте несколько слов отсюда, и суфлёр найдёт это место сам."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("vp", MODE_PRIVATE)
        jumpEnabled = prefs.getBoolean("jump", true)
        fontSize = prefs.getFloat("font", 34f)
        activeColor = prefs.getInt("color", Color.WHITE)
        mirror = prefs.getBoolean("mirror", false)
        rawText = prefs.getString("text", demoText) ?: demoText

        val d = resources.displayMetrics.density
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        scrollView = ScrollView(this)
        textView = TextView(this)
        textView.setTextColor(activeColor)
        textView.textSize = fontSize
        textView.setLineSpacing(0f, 1.25f)
        textView.setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (500 * d).toInt())
        scrollView.addView(textView)
        column.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Панель управления — ВНИЗУ экрана, лента с горизонтальной прокруткой
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)

        micDot = makeBtn("●")
        btnPlay = makeBtn("▶")
        val btnRestart = makeBtn("⟲")
        btnJump = makeBtn("")
        val btnFontMinus = makeBtn("A−")
        val btnFontPlus = makeBtn("A+")
        val btnEdit = makeBtn("✎")
        val btnSettings = makeBtn("⚙")
        bar.addView(micDot); bar.addView(btnPlay); bar.addView(btnRestart)
        bar.addView(btnJump); bar.addView(btnFontMinus); bar.addView(btnFontPlus)
        bar.addView(btnEdit); bar.addView(btnSettings)

        val barScroll = HorizontalScrollView(this)
        barScroll.isHorizontalScrollBarEnabled = false
        barScroll.setBackgroundColor(Color.parseColor("#161616"))
        barScroll.addView(bar)
        column.addView(barScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(column)

        countdownView = TextView(this)
        countdownView.textSize = 110f
        countdownView.setTextColor(Color.WHITE)
        countdownView.visibility = View.GONE
        val cdlp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        root.addView(countdownView, cdlp)

        setContentView(root)
        applyMirror()
        setScriptText(rawText)
        updateJumpBtn()
        updateMicDot()

        btnPlay.setOnClickListener { togglePlay() }
        btnRestart.setOnClickListener { restart() }
        btnEdit.setOnClickListener { showEditor() }
        btnSettings.setOnClickListener { showSettings() }
        micDot.setOnClickListener { toast(micInfo()) }
        btnJump.setOnClickListener {
            jumpEnabled = !jumpEnabled
            prefs.edit().putBoolean("jump", jumpEnabled).apply()
            updateJumpBtn()
            toast(if (jumpEnabled)
                "Перескоки ВКЛЮЧЕНЫ: суфлёр может прыгать в другое место текста"
            else
                "Перескоки ВЫКЛЮЧЕНЫ: суфлёр идёт строго по порядку")
        }
        btnFontMinus.setOnClickListener { changeFont(-2f) }
        btnFontPlus.setOnClickListener { changeFont(2f) }

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { updateMicDot() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { updateMicDot() }
        }, handler)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) initModel()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    // Фон кнопки: квадратик с рамкой; активная — зелёная рамка и тёмно-зелёный фон
    private fun btnBg(active: Boolean): GradientDrawable {
        val d = resources.displayMetrics.density
        val g = GradientDrawable()
        g.setColor(Color.parseColor(if (active) "#1B3620" else "#222222"))
        g.cornerRadius = 10 * d
        g.setStroke((2 * d).toInt(),
            Color.parseColor(if (active) "#4CAF50" else "#555555"))
        return g
    }

    private fun makeBtn(label: String): TextView {
        val d = resources.displayMetrics.density
        val t = TextView(this)
        t.text = label
        t.textSize = 19f
        t.setTextColor(Color.parseColor("#EEEEEE"))
        t.gravity = Gravity.CENTER
        t.background = btnBg(false)
        t.minWidth = (40 * d).toInt()
        t.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, (40 * d).toInt())
        lp.setMargins((3 * d).toInt(), (5 * d).toInt(), (3 * d).toInt(), (5 * d).toInt())
        t.layoutParams = lp
        return t
    }

    private fun changeFont(delta: Float) {
        fontSize = min(80f, max(20f, fontSize + delta))
        textView.textSize = fontSize
        prefs.edit().putFloat("font", fontSize).apply()
    }

    private fun updateJumpBtn() {
        btnJump.text = if (jumpEnabled) "🔀вкл" else "🔀выкл"
        btnJump.setTextColor(if (jumpEnabled) Color.parseColor("#4CAF50") else Color.parseColor("#888888"))
        btnJump.background = btnBg(jumpEnabled)
    }

    // ---------- Текст ----------

    private fun norm(s: String): String =
        s.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    private fun setScriptText(t: String) {
        rawText = t
        wordsNorm.clear(); wordStarts.clear(); wordEnds.clear()
        for (m in Regex("\\S+").findAll(rawText)) {
            val n = norm(m.value)
            if (n.isNotEmpty()) {
                wordsNorm.add(n)
                wordStarts.add(m.range.first)
                wordEnds.add(m.range.last + 1)
            }
        }
        currentIndex = 0; missCount = 0; recent.clear(); partialProcessed = 0
        confirmNeeded = false; pendingIndex = -1; pendingCount = 0
        render()
    }

    private fun render() {
        val sp = SpannableString(rawText)
        if (currentIndex > 0 && currentIndex <= wordEnds.size) {
            sp.setSpan(ForegroundColorSpan(Color.parseColor("#555555")),
                0, wordEnds[currentIndex - 1], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textView.text = sp
        textView.post { autoScroll() }
    }

    // Активная строка — ТРЕТЬЯ сверху: над ней две затемнённые строки
    private fun autoScroll() {
        val layout = textView.layout ?: return
        val charPos = if (currentIndex < wordStarts.size) wordStarts[currentIndex] else rawText.length
        val line = layout.getLineForOffset(charPos)
        val target = max(0, layout.getLineTop(line) + textView.paddingTop - 2 * textView.lineHeight)
        scrollView.smoothScrollTo(0, target)
    }

    // ---------- Следование за голосом ----------

    private fun wordMatch(a: String, b: String): Boolean =
        a == b || (a.length >= 4 && b.length >= 4 && a.substring(0, 4) == b.substring(0, 4))

    private fun onWord(w: String) {
        if (wordsNorm.isEmpty()) return
        recent.add(w)
        if (recent.size > 4) recent.removeAt(0)

        // Режим подтверждения: были посторонние слова, суфлёр стоит на месте
        // и сдвинется только после ТРЁХ слов текста подряд
        if (confirmNeeded) {
            if (pendingIndex in 0 until wordsNorm.size && wordMatch(wordsNorm[pendingIndex], w)) {
                pendingCount++
                pendingIndex++
                if (pendingCount >= 3) {
                    // три слова текста подряд — это точно чтение
                    currentIndex = pendingIndex
                    confirmNeeded = false; pendingIndex = -1; pendingCount = 0; missCount = 0
                    render()
                }
                return
            }
            // Цепочка оборвалась — пробуем начать новую с этого слова.
            // Ищем ВПЕРЁД до 15 слов (пока подтверждение срывалось, чтец мог
            // уйти вперёд от маркера) и НАЗАД до 15 слов — чтобы можно было
            // вернуться к началу фразы и продолжить оттуда
            pendingIndex = -1; pendingCount = 0
            var found = -1
            val fwdEnd = min(currentIndex + 15, wordsNorm.size)
            for (j in currentIndex until fwdEnd) {
                if (wordMatch(wordsNorm[j], w)) { found = j; break }
            }
            if (found < 0) {
                val backLimit = max(0, currentIndex - 15)
                var j = min(currentIndex, wordsNorm.size) - 1
                while (j >= backLimit) {
                    if (wordMatch(wordsNorm[j], w)) { found = j; break }
                    j--
                }
            }
            if (found >= 0) {
                pendingIndex = found + 1
                pendingCount = 1
            } else {
                missCount++
                if (jumpEnabled && missCount >= 3) tryJump()
            }
            return
        }

        val end = min(currentIndex + 3, wordsNorm.size)
        for (j in currentIndex until end) {
            if (wordMatch(wordsNorm[j], w)) {
                currentIndex = j + 1; missCount = 0; render(); return
            }
        }
        missCount++
        if (missCount >= 2) { confirmNeeded = true; pendingIndex = -1; pendingCount = 0 }
        if (jumpEnabled && missCount >= 3) tryJump()
    }

    private fun tryJump() {
        for (len in min(4, recent.size) downTo 3) {
            val seq = recent.takeLast(len)
            val positions = ArrayList<Int>()
            var i = 0
            while (i <= wordsNorm.size - len) {
                var ok = true
                for (k in 0 until len) {
                    if (!wordMatch(wordsNorm[i + k], seq[k])) { ok = false; break }
                }
                if (ok) positions.add(i)
                i++
            }
            if (positions.isNotEmpty()) {
                val best = positions.minByOrNull { abs(it - currentIndex) }!!
                currentIndex = best + len; missCount = 0
                confirmNeeded = false; pendingIndex = -1; pendingCount = 0
                render(); return
            }
        }
    }

    // ---------- Распознавание ----------

    private fun initModel() {
        StorageService.unpack(this, "model-ru", "model",
            { m: Model -> model = m; modelReady = true; toast("Готово! Нажмите ▶ и читайте") },
            { e: IOException -> toast("Ошибка модели: " + e.message) })
    }

    private fun togglePlay() {
        if (isPlaying) { stopListening(); return }
        if (!modelReady) { toast("Модель ещё загружается, подождите пару секунд…"); return }
        startCountdown()
    }

    private fun startCountdown() {
        countdownView.visibility = View.VISIBLE
        countdownView.text = "3"
        var n = 3
        val r = object : Runnable {
            override fun run() {
                n--
                if (n > 0) {
                    countdownView.text = n.toString()
                    handler.postDelayed(this, 1000)
                } else {
                    countdownView.visibility = View.GONE
                    startListening()
                }
            }
        }
        handler.postDelayed(r, 1000)
    }

    private fun startListening() {
        try {
            val rec = Recognizer(model, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService!!.startListening(this)
            isPlaying = true
            btnPlay.text = "⏸"
            btnPlay.background = btnBg(true)
        } catch (e: Exception) {
            toast("Ошибка микрофона: " + e.message)
        }
    }

    private fun stopListening() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        isPlaying = false
        partialProcessed = 0
        btnPlay.text = "▶"
        btnPlay.background = btnBg(false)
    }

    private fun restart() {
        currentIndex = 0; missCount = 0; recent.clear(); partialProcessed = 0
        confirmNeeded = false; pendingIndex = -1; pendingCount = 0
        render()
        scrollView.smoothScrollTo(0, 0)
    }

    private fun processHyp(h: String?, key: String, final: Boolean) {
        if (h.isNullOrEmpty()) return
        val s = try { JSONObject(h).optString(key, "") } catch (e: Exception) { "" }
        val toks = s.trim().split(Regex("\\s+")).map { norm(it) }.filter { it.isNotEmpty() }
        // Если распознаватель пересмотрел фразу и слов стало меньше —
        // уже обработанные слова НЕ подаём заново (раньше это вызывало
        // скачки на повторяющиеся слова)
        if (partialProcessed > toks.size) {
            partialProcessed = if (final) 0 else toks.size
            return
        }
        for (i in partialProcessed until toks.size) onWord(toks[i])
        partialProcessed = if (final) 0 else toks.size
    }

    override fun onPartialResult(hypothesis: String?) { processHyp(hypothesis, "partial", false) }
    override fun onResult(hypothesis: String?) { processHyp(hypothesis, "text", true) }
    override fun onFinalResult(hypothesis: String?) { processHyp(hypothesis, "text", true) }
    override fun onError(exception: Exception?) { toast("Ошибка распознавания: " + exception?.message) }
    override fun onTimeout() {}

    // ---------- Микрофон ----------

    private fun externalMic(): Pair<Boolean, String> {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (dev in am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            when (dev.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                    return Pair(true, dev.productName.toString())
            }
        }
        return Pair(false, "встроенный микрофон")
    }

    private fun updateMicDot() {
        val ext = externalMic().first
        micDot.setTextColor(if (ext) Color.parseColor("#4CAF50") else Color.parseColor("#FF9800"))
    }

    private fun micInfo(): String {
        val (ext, name) = externalMic()
        return if (ext) "Внешний микрофон: $name"
        else "Встроенный микрофон телефона. Подключите петличку или гарнитуру — точка станет зелёной."
    }

    // ---------- Редактор и настройки ----------

    private fun showEditor() {
        val d = resources.displayMetrics.density
        val wrap = FrameLayout(this)
        val p = (16 * d).toInt()
        wrap.setPadding(p, p, p, 0)
        val et = EditText(this)
        et.setText(rawText)
        et.minLines = 8
        et.gravity = Gravity.TOP
        wrap.addView(et)
        AlertDialog.Builder(this)
            .setTitle("Текст сценария")
            .setView(wrap)
            .setPositiveButton("Сохранить") { _, _ ->
                setScriptText(et.text.toString())
                prefs.edit().putString("text", rawText).apply()
                scrollView.smoothScrollTo(0, 0)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showSettings() {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val p = (20 * d).toInt()
        box.setPadding(p, p, p, p)

        val colors = LinearLayout(this)
        fun colorBtn(name: String, c: Int): Button {
            val b = Button(this)
            b.text = name
            b.setOnClickListener { activeColor = c; textView.setTextColor(c); render() }
            return b
        }
        colors.addView(colorBtn("Белый", Color.WHITE))
        colors.addView(colorBtn("Жёлтый", Color.parseColor("#FFEB3B")))
        colors.addView(colorBtn("Зелёный", Color.parseColor("#4CAF50")))

        val cb = CheckBox(this)
        cb.text = "Зеркальный режим"
        cb.isChecked = mirror
        cb.setOnCheckedChangeListener { _, v -> mirror = v; applyMirror() }

        box.addView(colors); box.addView(cb)

        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                prefs.edit().putFloat("font", fontSize)
                    .putInt("color", activeColor)
                    .putBoolean("mirror", mirror).apply()
            }
            .show()
    }

    private fun applyMirror() {
        scrollView.scaleX = if (mirror) -1f else 1f
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) initModel()
        else toast("Без доступа к микрофону суфлёр не сможет вас слышать")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
    }
}
