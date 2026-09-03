package com.voiceprompter.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var scrollView: ScrollView
    private lateinit var textView: TextView
    private lateinit var countdownView: TextView
    private lateinit var micDot: TextView
    private lateinit var btnPlay: TextView
    private lateinit var btnJump: TextView
    private lateinit var btnAuto: TextView

    private var model: Model? = null
    private var modelReady = false
    private var isPlaying = false

    // Шаг C1: собственный цикл чтения микрофона (замена SpeechService).
    // AudioRecord отдаёт звук порциями; каждую порцию мы подаём в тот же
    // распознаватель Vosk (распознавание не меняется) И считаем из неё
    // громкость для индикатора уровня звука
    private var audioRecord: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private var audioThread: Thread? = null
    @Volatile private var listeningLoop = false

    // Шаг C1: индикатор звука — тонкая полоска над панелью кнопок,
    // при звуке в ней загораются зелёные квадратики (как в звукозаписи).
    // По просьбе пользователя: ширина 50% экрана, по центру
    private lateinit var levelBar: LinearLayout
    private val levelCells = ArrayList<View>()

    // Шаг C2: очень тонкая строка контроля НАД индикатором звука —
    // в ней бегут слова, которые система реально слышит. Та же ширина
    // (50% экрана, по центру); при переполнении обрезается начало,
    // чтобы всегда были видны ПОСЛЕДНИЕ услышанные слова.
    // По просьбе пользователя: слова появляются БЫСТРО, из промежуточных
    // результатов распознавания (как было изначально)
    private lateinit var hypView: TextView

    private var jumpEnabled = true
    private var fontSize = 34f
    private var activeColor = Color.WHITE
    private var mirror = false

    // Настройки (добавлено по заданию): номер активной строки сверху
    // (стандарт 3 — над ней две затемнённые строки) и скорость плавной
    // прокрутки при чтении (стандарт 3, внутри используется как 0.03)
    private var activeLineFromTop = 3
    private var followSpeedStep = 3

    // Расширенные настройки (добавлено по заданию):
    // - цвет фона и его яркость («прозрачность»: 100% — чистый цвет,
    //   меньше — цвет гаснет к чёрному; для ЧЁРНОГО фона наоборот —
    //   осветляется к серому, иначе эффект был бы не виден);
    // - межстрочный интервал (хранится как число x100: 125 = 1.25);
    // - цвет «прочитанного» (затемнённого) текста;
    // - чувствительность: сколько слов текста подряд нужно для
    //   подтверждения после посторонних слов (стандарт 3) и окно поиска
    //   в словах вперёд/назад при подтверждении (стандарт 15)
    private var bgColor = Color.BLACK
    private var bgAlpha = 100
    private var lineSpacingStep = 125
    private var readColor = Color.parseColor("#555555")
    private var confirmWordsNeeded = 3
    private var searchWindow = 15
    private var rootLayout: FrameLayout? = null

    private var rawText = ""
    private val wordsNorm = ArrayList<String>()
    private val wordStarts = ArrayList<Int>()
    private val wordEnds = ArrayList<Int>()
    private var currentIndex = 0
    private var missCount = 0
    private val recent = ArrayList<String>()
    private var partialProcessed = 0
    // Защита от ложного старта: после посторонних слов суфлёр не двигается,
    // пока не услышит подряд столько слов текста, сколько задано в настройке
    // «Слов для подтверждения» (стандарт 3)
    private var confirmNeeded = false
    private var pendingIndex = -1
    private var pendingCount = 0

    // Библиотека сценариев (шаг A1): список хранится в prefs как JSON
    private val scriptNames = ArrayList<String>()
    private val scriptTexts = ArrayList<String>()
    private var currentScript = 0

    // Шаг B3: позиция чтения каждого сценария (номер слова + прокрутка ленты).
    // Сохраняется при выходе из приложения и при переключении сценария,
    // восстанавливается при запуске и при возврате к сценарию
    private val scriptPos = ArrayList<Int>()
    private val scriptScroll = ArrayList<Int>()

    // Шаг A3: ссылка на поле редактора, чтобы импорт .txt мог вставить в него текст
    private var editorEt: EditText? = null

    private val handler = Handler(Looper.getMainLooper())

    // Плавная прокрутка: лента каждый кадр подъезжает к цели на часть
    // оставшегося расстояния — без прыжков со строчки на строчку.
    // Чем больше отставание (быстрое чтение), тем быстрее едет.
    // Доля за кадр настраивается в Настройках (followSpeedStep / 100)
    private var targetScrollY = 0
    private var scrollAnimRunning = false
    private val scrollStep = object : Runnable {
        override fun run() {
            val cur = scrollView.scrollY
            val diff = targetScrollY - cur
            if (abs(diff) <= 1) {
                scrollView.scrollTo(0, targetScrollY)
                scrollAnimRunning = false
                return
            }
            val step = max(1, (abs(diff) * followSpeedStep / 100f).toInt())
            scrollView.scrollTo(0, cur + if (diff > 0) step else -step)
            handler.postDelayed(this, 16)
        }
    }

    private fun stopSmoothScroll() {
        handler.removeCallbacks(scrollStep)
        scrollAnimRunning = false
        handler.removeCallbacks(settleCheck)
    }

    // Шаг B1: резервная автопрокрутка — лента едет с постоянной скоростью,
    // микрофон не используется. Страховка на случай шумной площадки.
    // autoSpeed — скорость в dp/сек (долгое нажатие на АВТО — ползунок)
    private var autoMode = false
    private var autoSpeed = 30
    private var autoRunning = false
    private var autoPosF = 0f
    private val autoStep = object : Runnable {
        override fun run() {
            if (!autoRunning) return
            val d = resources.displayMetrics.density
            autoPosF += autoSpeed * d * 0.016f
            val maxY = max(0, textView.height - scrollView.height)
            if (autoPosF >= maxY) {
                scrollView.scrollTo(0, maxY)
                stopAuto()
                toast("Конец текста")
                return
            }
            scrollView.scrollTo(0, autoPosF.toInt())
            handler.postDelayed(this, 16)
        }
    }

    private fun startAuto() {
        stopSmoothScroll()
        autoPosF = scrollView.scrollY.toFloat()
        autoRunning = true
        btnPlay.text = "⏸"
        btnPlay.background = btnBg(true)
        handler.post(autoStep)
    }

    private fun stopAuto() {
        if (!autoRunning) return
        autoRunning = false
        handler.removeCallbacks(autoStep)
        btnPlay.text = "▶"
        btnPlay.background = btnBg(false)
    }

    // Шаг B2: жесты. Тап по тексту — пауза/старт. Свайп — ручная перемотка:
    // после остановки ленты курсор «подхватывает» новое место (в режиме ГОЛОС
    // чтение продолжается оттуда, в режиме АВТО прокрутка едет дальше оттуда).
    private var userTouching = false
    private var userDragged = false
    private var downX = 0f
    private var downY = 0f
    private var settleLastY = -1
    // После свайпа лента может ещё лететь по инерции — ждём, пока она
    // остановится (позиция не меняется), и только потом подхватываем место
    private val settleCheck = object : Runnable {
        override fun run() {
            if (userTouching) return
            val y = scrollView.scrollY
            if (y == settleLastY) {
                if (autoRunning) {
                    autoPosF = y.toFloat()
                    handler.post(autoStep)
                } else {
                    syncIndexToScroll()
                }
            } else {
                settleLastY = y
                handler.postDelayed(this, 60)
            }
        }
    }

    // Шаг B2: курсор подхватывает место, куда пользователь перемотал ленту.
    // Активной становится строка на настроенной позиции сверху (стандарт — третья)
    private fun syncIndexToScroll() {
        val layout = textView.layout ?: return
        if (wordsNorm.isEmpty()) return
        val y = max(0, scrollView.scrollY - textView.paddingTop + (activeLineFromTop - 1) * textView.lineHeight)
        val line = layout.getLineForVertical(y)
        val off = layout.getLineStart(line)
        var idx = wordsNorm.size
        for (i in wordStarts.indices) {
            if (wordStarts[i] >= off) { idx = i; break }
        }
        currentIndex = idx
        missCount = 0; recent.clear()
        confirmNeeded = false; pendingIndex = -1; pendingCount = 0
        render()
    }

    private val demoText = "Добро пожаловать в ВойсПромптер — суфлёр, который слушает ваш голос.\n\nЧитайте этот текст вслух в обычном темпе. Строка сама поедет за вами. Если вы замолчите, суфлёр остановится и будет ждать вас на том же месте.\n\nПопробуйте сказать что-нибудь постороннее — текст останется на месте, потому что суфлёр следит именно за словами сценария.\n\nА это последний абзац для проверки перескока. Прочитайте несколько слов отсюда, и суфлёр найдёт это место сам."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("vp", MODE_PRIVATE)
        jumpEnabled = prefs.getBoolean("jump", true)
        fontSize = prefs.getFloat("font", 34f)
        activeColor = prefs.getInt("color", Color.WHITE)
        mirror = prefs.getBoolean("mirror", false)
        autoMode = prefs.getBoolean("autoMode", false)
        autoSpeed = prefs.getInt("autoSpeed", 30)
        activeLineFromTop = prefs.getInt("activeLine", 3)
        followSpeedStep = prefs.getInt("followSpeed", 3)
        // Расширенные настройки
        bgColor = prefs.getInt("bgColor", Color.BLACK)
        bgAlpha = prefs.getInt("bgAlpha", 100)
        lineSpacingStep = prefs.getInt("lineSpacing", 125)
        readColor = prefs.getInt("readColor", Color.parseColor("#555555"))
        confirmWordsNeeded = prefs.getInt("confirmWords", 3)
        searchWindow = prefs.getInt("searchWin", 15)
        loadScripts()
        rawText = scriptTexts[currentScript]

        val d = resources.displayMetrics.density
        val root = FrameLayout(this)
        rootLayout = root
        applyBackground()

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        scrollView = ScrollView(this)
        textView = TextView(this)
        textView.setTextColor(activeColor)
        textView.textSize = fontSize
        textView.setLineSpacing(0f, lineSpacingStep / 100f)
        textView.setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (500 * d).toInt())
        scrollView.addView(textView)
        column.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Общая ширина нижних индикаторов: 50% экрана, по центру
        val indicatorWidth = resources.displayMetrics.widthPixels / 2

        // Шаг C2: очень тонкая строка контроля распознавания — в ней видно,
        // какие слова система слышит. Одна строка, мелкий приглушённый шрифт,
        // чтобы не отвлекать от чтения; при переполнении обрезается начало
        hypView = TextView(this)
        hypView.textSize = 11f
        hypView.setTextColor(Color.parseColor("#6E8F72"))
        hypView.setSingleLine(true)
        hypView.ellipsize = TextUtils.TruncateAt.START
        hypView.gravity = Gravity.CENTER
        hypView.setBackgroundColor(Color.parseColor("#101010"))
        hypView.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        val hlp = LinearLayout.LayoutParams(indicatorWidth,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        hlp.gravity = Gravity.CENTER_HORIZONTAL
        column.addView(hypView, hlp)

        // Шаг C1: тонкая полоска индикатора звука — прямо над панелью кнопок.
        // 30 квадратиков; чем громче звук с микрофона, тем больше их горит зелёным
        levelBar = LinearLayout(this)
        levelBar.orientation = LinearLayout.HORIZONTAL
        levelBar.setBackgroundColor(Color.parseColor("#101010"))
        levelBar.setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
        for (i in 0 until LEVEL_CELLS) {
            val cell = View(this)
            val clp = LinearLayout.LayoutParams(0, (5 * d).toInt(), 1f)
            clp.setMargins((1 * d).toInt(), 0, (1 * d).toInt(), 0)
            cell.layoutParams = clp
            cell.setBackgroundColor(Color.parseColor("#222222"))
            levelBar.addView(cell)
            levelCells.add(cell)
        }
        val llp = LinearLayout.LayoutParams(indicatorWidth,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        llp.gravity = Gravity.CENTER_HORIZONTAL
        column.addView(levelBar, llp)

        // Панель управления — ВНИЗУ экрана, лента с горизонтальной прокруткой
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)

        micDot = makeBtn("●")
        btnPlay = makeBtn("▶")
        val btnRestart = makeBtn("⟲")
        btnJump = makeBtn("")
        btnAuto = makeBtn("АВТО")
        val btnFontMinus = makeBtn("A−")
        val btnFontPlus = makeBtn("A+")
        val btnEdit = makeBtn("✎")
        val btnLibrary = makeBtn("📚")
        val btnSettings = makeBtn("⚙")
        bar.addView(micDot); bar.addView(btnPlay); bar.addView(btnRestart)
        bar.addView(btnJump); bar.addView(btnAuto); bar.addView(btnFontMinus); bar.addView(btnFontPlus)
        bar.addView(btnEdit); bar.addView(btnLibrary); bar.addView(btnSettings)

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
        // Шаг B3: возвращаемся на место, где остановились в прошлый раз
        restorePosition()
        updateJumpBtn()
        updateAutoBtn()
        updateMicDot()

        btnPlay.setOnClickListener { togglePlay() }
        btnRestart.setOnClickListener { restart() }
        btnEdit.setOnClickListener { showEditor() }
        btnLibrary.setOnClickListener { showLibrary() }
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
        btnAuto.setOnClickListener {
            if (isPlaying) stopListening()
            stopAuto()
            autoMode = !autoMode
            prefs.edit().putBoolean("autoMode", autoMode).apply()
            updateAutoBtn()
            toast(if (autoMode)
                "Режим АВТО: прокрутка с постоянной скоростью, микрофон не используется. Долгое нажатие на АВТО — скорость."
            else
                "Режим ГОЛОС: суфлёр следует за вашим чтением")
        }
        btnAuto.setOnLongClickListener { autoSpeedDialog(); true }
        btnFontMinus.setOnClickListener { changeFont(-2f) }
        btnFontPlus.setOnClickListener { changeFont(2f) }

        // Шаг B2: жесты на ленте текста.
        // Тап (палец не сдвинулся) — пауза/старт, как кнопка ▶.
        // Свайп — обычная ручная прокрутка ScrollView; когда лента остановится,
        // settleCheck подхватит новое место
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        scrollView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    userTouching = true
                    userDragged = false
                    downX = ev.x
                    downY = ev.y
                    // Останавливаем анимации, чтобы лента не вырывалась из-под пальца
                    handler.removeCallbacks(scrollStep)
                    scrollAnimRunning = false
                    handler.removeCallbacks(settleCheck)
                    if (autoRunning) handler.removeCallbacks(autoStep)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop)
                        userDragged = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    userTouching = false
                    if (!userDragged && ev.actionMasked == MotionEvent.ACTION_UP) {
                        // Тап — пауза/старт (во время отсчёта 3-2-1 игнорируем)
                        if (countdownView.visibility != View.VISIBLE) togglePlay()
                        // Если после тапа автопрокрутка всё ещё должна идти —
                        // продолжаем её (страховка)
                        if (autoRunning) {
                            autoPosF = scrollView.scrollY.toFloat()
                            handler.post(autoStep)
                        }
                    } else {
                        // Был свайп — ждём остановки ленты и подхватываем место
                        settleLastY = -1
                        handler.postDelayed(settleCheck, 60)
                    }
                }
            }
            false
        }

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { updateMicDot() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { updateMicDot() }
        }, handler)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) initModel()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    // Шаг B3: при сворачивании/закрытии приложения запоминаем позицию чтения
    override fun onPause() {
        super.onPause()
        savePosition()
    }

    // Расширенные настройки: фон экрана. Яркость (bgAlpha, %) гасит выбранный
    // цвет к чёрному: 100% — чистый цвет, 0% — полностью чёрный.
    // ИСПРАВЛЕНИЕ: если выбран ЧЁРНЫЙ фон, гасить его к чёрному бессмысленно
    // (эффект не виден) — поэтому для чёрного ползунок работает наоборот:
    // 100% — чистый чёрный, меньше — фон ОСВЕТЛЯЕТСЯ к серому
    private fun applyBackground() {
        val f = bgAlpha / 100f
        val c: Int
        if (Color.red(bgColor) < 16 && Color.green(bgColor) < 16 && Color.blue(bgColor) < 16) {
            // Чёрный (или почти чёрный) фон: осветляем к серому #555555
            val g = ((1f - f) * 0x55).toInt()
            c = Color.rgb(g, g, g)
        } else {
            c = Color.rgb(
                (Color.red(bgColor) * f).toInt(),
                (Color.green(bgColor) * f).toInt(),
                (Color.blue(bgColor) * f).toInt())
        }
        rootLayout?.setBackgroundColor(c)
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

    // Шаг B1: индикация режима АВТО на кнопке
    private fun updateAutoBtn() {
        btnAuto.setTextColor(if (autoMode) Color.parseColor("#4CAF50") else Color.parseColor("#888888"))
        btnAuto.background = btnBg(autoMode)
    }

    // Шаг B1: ползунок скорости автопрокрутки (5..120 dp/сек).
    // Скорость можно менять прямо во время прокрутки — лента отреагирует сразу
    private fun autoSpeedDialog() {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val p = (20 * d).toInt()
        box.setPadding(p, p, p, p)
        val label = TextView(this)
        label.text = "Скорость: $autoSpeed"
        label.textSize = 18f
        val sb = SeekBar(this)
        sb.max = 115
        sb.progress = autoSpeed - 5
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                autoSpeed = v + 5
                label.text = "Скорость: $autoSpeed"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(label); box.addView(sb)
        AlertDialog.Builder(this)
            .setTitle("Скорость автопрокрутки")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                prefs.edit().putInt("autoSpeed", autoSpeed).apply()
            }
            .show()
    }

    // ---------- Библиотека сценариев ----------

    // Загрузка списка сценариев из prefs. Если списка ещё нет (старая версия
    // приложения), единственный сохранённый текст переносится в "Сценарий 1"
    private fun loadScripts() {
        scriptNames.clear(); scriptTexts.clear()
        scriptPos.clear(); scriptScroll.clear()
        val json = prefs.getString("scripts", null)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    scriptNames.add(o.optString("name", "Сценарий " + (i + 1)))
                    scriptTexts.add(o.optString("text", ""))
                    // Шаг B3: сохранённая позиция чтения (у старых записей — 0)
                    scriptPos.add(o.optInt("pos", 0))
                    scriptScroll.add(o.optInt("scroll", 0))
                }
            } catch (e: Exception) { }
        }
        if (scriptNames.isEmpty()) {
            // Миграция: старый одиночный текст становится первым сценарием
            scriptNames.add("Сценарий 1")
            scriptTexts.add(prefs.getString("text", demoText) ?: demoText)
            scriptPos.add(0)
            scriptScroll.add(0)
        }
        currentScript = prefs.getInt("curScript", 0)
        if (currentScript < 0 || currentScript >= scriptNames.size) currentScript = 0
        saveScripts()
    }

    private fun saveScripts() {
        val arr = JSONArray()
        for (i in scriptNames.indices) {
            val o = JSONObject()
            o.put("name", scriptNames[i])
            o.put("text", scriptTexts[i])
            o.put("pos", scriptPos.getOrElse(i) { 0 })
            o.put("scroll", scriptScroll.getOrElse(i) { 0 })
            arr.put(o)
        }
        prefs.edit().putString("scripts", arr.toString())
            .putInt("curScript", currentScript).apply()
    }

    // Шаг B3: запомнить позицию чтения текущего сценария (слово + прокрутка)
    private fun savePosition() {
        if (currentScript in scriptPos.indices) {
            scriptPos[currentScript] = currentIndex
            scriptScroll[currentScript] = scrollView.scrollY
            saveScripts()
        }
    }

    // Шаг B3: вернуться на сохранённую позицию текущего сценария.
    // Сначала восстанавливаем номер слова (затемнение), затем — прокрутку
    // ленты (когда текст уже разложен по строкам)
    private fun restorePosition() {
        val pos = scriptPos.getOrElse(currentScript) { 0 }
        val scr = scriptScroll.getOrElse(currentScript) { 0 }
        currentIndex = min(max(0, pos), wordsNorm.size)
        missCount = 0; recent.clear(); partialProcessed = 0
        confirmNeeded = false; pendingIndex = -1; pendingCount = 0
        render()
        restoreScrollWhenReady(scr, 20)
    }

    // Прокрутку можно восстановить только после того, как TextView измерен
    // и разложен по строкам — иначе ScrollView обрежет позицию до нуля.
    // Поэтому ждём готовности (до 20 попыток по 50 мс)
    private fun restoreScrollWhenReady(scr: Int, tries: Int) {
        textView.post {
            if (textView.layout == null || textView.height == 0) {
                if (tries > 0) handler.postDelayed({ restoreScrollWhenReady(scr, tries - 1) }, 50)
                return@post
            }
            stopSmoothScroll()
            targetScrollY = max(0, scr)
            scrollView.scrollTo(0, max(0, scr))
        }
    }

    private fun showLibrary() {
        val items = scriptNames.mapIndexed { i, n ->
            if (i == currentScript) "▶ $n" else "   $n"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Сценарии (долгое нажатие — изменить)")
            .setItems(items) { _, which -> switchScript(which) }
            .setPositiveButton("+ Новый") { _, _ -> newScriptDialog() }
            .setNegativeButton("Закрыть", null)
            .show()
            .listView?.setOnItemLongClickListener { _, _, pos, _ ->
                scriptActionsDialog(pos)
                true
            }
    }

    // Шаг A2: долгое нажатие на сценарий в списке — переименовать или удалить
    private fun scriptActionsDialog(i: Int) {
        AlertDialog.Builder(this)
            .setTitle(scriptNames[i])
            .setItems(arrayOf("Переименовать", "Удалить")) { _, which ->
                if (which == 0) renameScriptDialog(i) else deleteScriptDialog(i)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun renameScriptDialog(i: Int) {
        val d = resources.displayMetrics.density
        val wrap = FrameLayout(this)
        val p = (16 * d).toInt()
        wrap.setPadding(p, p, p, 0)
        val et = EditText(this)
        et.hint = "Название сценария"
        et.setText(scriptNames[i])
        et.setSelection(et.text.length)
        wrap.addView(et)
        AlertDialog.Builder(this)
            .setTitle("Переименовать")
            .setView(wrap)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) {
                    scriptNames[i] = name
                    saveScripts()
                    toast("Переименовано: $name")
                }
                showLibrary()
            }
            .setNegativeButton("Отмена") { _, _ -> showLibrary() }
            .show()
    }

    private fun deleteScriptDialog(i: Int) {
        if (scriptNames.size <= 1) {
            toast("Нельзя удалить последний сценарий")
            showLibrary()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Удалить сценарий?")
            .setMessage("«" + scriptNames[i] + "» будет удалён безвозвратно.")
            .setPositiveButton("Удалить") { _, _ ->
                scriptNames.removeAt(i)
                scriptTexts.removeAt(i)
                scriptPos.removeAt(i)
                scriptScroll.removeAt(i)
                // Поправляем номер текущего сценария после удаления
                if (i == currentScript) {
                    currentScript = min(i, scriptNames.size - 1)
                    setScriptText(scriptTexts[currentScript])
                    stopSmoothScroll()
                    stopAuto()
                    // Шаг B3: соседний сценарий открывается на своём сохранённом месте
                    restorePosition()
                } else if (i < currentScript) {
                    currentScript--
                }
                saveScripts()
                toast("Сценарий удалён")
                showLibrary()
            }
            .setNegativeButton("Отмена") { _, _ -> showLibrary() }
            .show()
    }

    private fun switchScript(i: Int) {
        if (i == currentScript) return
        // Шаг B3: запоминаем место в старом сценарии и возвращаемся на
        // сохранённое место в новом (раньше лента уходила в начало)
        savePosition()
        currentScript = i
        setScriptText(scriptTexts[i])
        saveScripts()
        stopSmoothScroll()
        stopAuto()
        restorePosition()
        toast("Сценарий: " + scriptNames[i])
    }

    private fun newScriptDialog() {
        val d = resources.displayMetrics.density
        val wrap = FrameLayout(this)
        val p = (16 * d).toInt()
        wrap.setPadding(p, p, p, 0)
        val et = EditText(this)
        et.hint = "Название сценария"
        et.setText("Сценарий " + (scriptNames.size + 1))
        et.setSelection(et.text.length)
        wrap.addView(et)
        AlertDialog.Builder(this)
            .setTitle("Новый сценарий")
            .setView(wrap)
            .setPositiveButton("Создать") { _, _ ->
                // Шаг B3: сначала запоминаем позицию в текущем сценарии
                savePosition()
                var name = et.text.toString().trim()
                if (name.isEmpty()) name = "Сценарий " + (scriptNames.size + 1)
                scriptNames.add(name)
                scriptTexts.add("")
                scriptPos.add(0)
                scriptScroll.add(0)
                currentScript = scriptNames.size - 1
                setScriptText("")
                saveScripts()
                stopSmoothScroll()
                stopAuto()
                targetScrollY = 0
                scrollView.scrollTo(0, 0)
                // Сразу открываем редактор, чтобы ввести текст нового сценария
                showEditor()
            }
            .setNegativeButton("Отмена", null)
            .show()
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
            // Цвет «прочитанного» настраивается в Расширенных настройках
            sp.setSpan(ForegroundColorSpan(readColor),
                0, wordEnds[currentIndex - 1], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textView.text = sp
        textView.post { autoScroll() }
    }

    // Активная строка — на настроенной позиции сверху (стандарт: третья,
    // над ней две затемнённые строки; меняется в Настройках).
    // Лента подъезжает к цели ПЛАВНО (см. scrollStep), без прыжков
    private fun autoScroll() {
        if (userTouching) return // Шаг B2: пока палец на экране — не анимируем
        val layout = textView.layout ?: return
        val charPos = if (currentIndex < wordStarts.size) wordStarts[currentIndex] else rawText.length
        val line = layout.getLineForOffset(charPos)
        targetScrollY = max(0, layout.getLineTop(line) + textView.paddingTop - (activeLineFromTop - 1) * textView.lineHeight)
        if (!scrollAnimRunning) {
            scrollAnimRunning = true
            handler.post(scrollStep)
        }
    }

    // ---------- Следование за голосом ----------

    private fun wordMatch(a: String, b: String): Boolean =
        a == b || (a.length >= 4 && b.length >= 4 && a.substring(0, 4) == b.substring(0, 4))

    private fun onWord(w: String) {
        if (wordsNorm.isEmpty()) return
        recent.add(w)
        if (recent.size > 4) recent.removeAt(0)

        // Режим подтверждения: были посторонние слова, суфлёр стоит на месте
        // и сдвинется только после НАСТРОЕННОГО числа слов текста подряд
        // (стандарт 3, меняется в Расширенных настройках)
        if (confirmNeeded) {
            if (pendingIndex in 0 until wordsNorm.size && wordMatch(wordsNorm[pendingIndex], w)) {
                pendingCount++
                pendingIndex++
                if (pendingCount >= confirmWordsNeeded) {
                    // нужное число слов текста подряд — это точно чтение
                    currentIndex = pendingIndex
                    confirmNeeded = false; pendingIndex = -1; pendingCount = 0; missCount = 0
                    render()
                }
                return
            }
            // Цепочка оборвалась — пробуем начать новую с этого слова.
            // Ищем ВПЕРЁД до searchWindow слов (пока подтверждение срывалось,
            // чтец мог уйти вперёд от маркера) и НАЗАД до searchWindow слов —
            // чтобы можно было вернуться к началу фразы и продолжить оттуда.
            // Окно настраивается в Расширенных настройках (стандарт 15)
            pendingIndex = -1; pendingCount = 0
            var found = -1
            val fwdEnd = min(currentIndex + searchWindow, wordsNorm.size)
            for (j in currentIndex until fwdEnd) {
                if (wordMatch(wordsNorm[j], w)) { found = j; break }
            }
            if (found < 0) {
                val backLimit = max(0, currentIndex - searchWindow)
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
        // Шаг B1: в режиме АВТО кнопка ▶ управляет автопрокруткой, микрофон не нужен
        if (autoRunning) { stopAuto(); return }
        if (autoMode) { startCountdown(); return }
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
                    if (autoMode) startAuto() else startListening()
                }
            }
        }
        handler.postDelayed(r, 1000)
    }

    // Шаг C1: собственный цикл чтения микрофона вместо SpeechService.
    // Звук читается порциями по ~100 мс; каждая порция идёт В ТОТ ЖЕ
    // распознаватель Vosk (той же частотой 16 кГц), а её громкость —
    // на индикатор уровня. Логика следования за текстом не изменена:
    // partial/final обрабатывает тот же processHyp, что и раньше
    private fun startListening() {
        try {
            val rec = Recognizer(model, 16000.0f)
            val minBuf = AudioRecord.getMinBufferSize(16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, 9600) * 2)
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                rec.close()
                toast("Ошибка микрофона: не удалось открыть запись")
                return
            }
            recognizer = rec
            audioRecord = ar
            listeningLoop = true
            ar.startRecording()
            audioThread = Thread {
                val buf = ShortArray(1600) // ~100 мс звука при 16 кГц
                while (listeningLoop) {
                    val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                    if (n <= 0) continue
                    // Громкость порции — пиковая амплитуда для квадратиков
                    var peak = 0
                    for (i in 0 until n) {
                        val v = abs(buf[i].toInt())
                        if (v > peak) peak = v
                    }
                    handler.post { showLevel(peak) }
                    // Тот же распознаватель Vosk, что и раньше
                    val r = recognizer ?: break
                    if (!listeningLoop) break
                    if (r.acceptWaveForm(buf, n)) {
                        val res = r.result
                        handler.post { if (isPlaying) processHyp(res, "text", true) }
                    } else {
                        val res = r.partialResult
                        handler.post { if (isPlaying) processHyp(res, "partial", false) }
                    }
                }
            }
            audioThread!!.start()
            isPlaying = true
            btnPlay.text = "⏸"
            btnPlay.background = btnBg(true)
        } catch (e: Exception) {
            toast("Ошибка микрофона: " + e.message)
        }
    }

    private fun stopListening() {
        // Порядок важен: сначала гасим цикл, потом останавливаем запись
        // (это разблокирует read), дожидаемся конца потока и лишь затем
        // освобождаем распознаватель — чтобы не закрыть его под ногами у цикла
        listeningLoop = false
        try { audioRecord?.stop() } catch (e: Exception) { }
        try { audioThread?.join(700) } catch (e: InterruptedException) { }
        audioThread = null
        try { audioRecord?.release() } catch (e: Exception) { }
        audioRecord = null
        try { recognizer?.close() } catch (e: Exception) { }
        recognizer = null
        isPlaying = false
        partialProcessed = 0
        btnPlay.text = "▶"
        btnPlay.background = btnBg(false)
        showLevel(0)
        // Шаг C2: при остановке очищаем строку контроля распознавания
        hypView.text = ""
    }

    // Шаг C1: зажечь квадратики по громкости (0..32767).
    // Квадратный корень — чтобы тихая речь тоже была видна на индикаторе
    private fun showLevel(peak: Int) {
        val frac = sqrt(min(32767, max(0, peak)) / 32767f)
        val lit = min(LEVEL_CELLS, (frac * LEVEL_CELLS).toInt())
        for (i in levelCells.indices) {
            levelCells[i].setBackgroundColor(
                if (i < lit) Color.parseColor("#4CAF50") else Color.parseColor("#222222"))
        }
    }

    private fun restart() {
        currentIndex = 0; missCount = 0; recent.clear(); partialProcessed = 0
        confirmNeeded = false; pendingIndex = -1; pendingCount = 0
        stopSmoothScroll()
        stopAuto()
        targetScrollY = 0
        render()
        scrollView.smoothScrollTo(0, 0)
        // Шаг B3: возврат в начало тоже запоминаем — после перезапуска
        // приложение откроется с начала, как и ожидает пользователь
        savePosition()
    }

    private fun processHyp(h: String?, key: String, final: Boolean) {
        if (h.isNullOrEmpty()) return
        val s = try { JSONObject(h).optString(key, "") } catch (e: Exception) { "" }
        // Шаг C2 (возврат по заданию): слова показываются БЫСТРО — прямо из
        // промежуточных результатов, как было изначально. Пустые результаты
        // строку не затирают — последняя фраза остаётся видна
        if (s.isNotBlank()) hypView.text = s.trim()
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
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val p = (16 * d).toInt()
        box.setPadding(p, p, p, 0)

        // Шаг A3: кнопки быстрого наполнения — вставка из буфера и импорт .txt
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val bPaste = Button(this)
        bPaste.text = "📋 Из буфера"
        val bImport = Button(this)
        bImport.text = "📂 Импорт .txt"
        row.addView(bPaste); row.addView(bImport)

        val et = EditText(this)
        et.setText(rawText)
        et.minLines = 8
        et.gravity = Gravity.TOP
        editorEt = et
        box.addView(row)
        box.addView(et)

        bPaste.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val t = if (clip != null && clip.itemCount > 0)
                clip.getItemAt(0).coerceToText(this).toString() else ""
            if (t.isEmpty()) { toast("Буфер обмена пуст"); return@setOnClickListener }
            // Вставляем в позицию курсора (или вместо выделенного фрагмента)
            val a = max(0, min(et.selectionStart, et.selectionEnd))
            val b = max(0, max(et.selectionStart, et.selectionEnd))
            et.text.replace(a, b, t)
            toast("Вставлено из буфера")
        }
        bImport.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "text/*"
            try { startActivityForResult(intent, REQ_IMPORT_TXT) }
            catch (e: Exception) { toast("Не удалось открыть выбор файла") }
        }

        AlertDialog.Builder(this)
            .setTitle("Текст сценария: " + scriptNames[currentScript])
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                setScriptText(et.text.toString())
                scriptTexts[currentScript] = rawText
                // Шаг B3: текст изменился — старая позиция больше не действительна
                if (currentScript in scriptPos.indices) {
                    scriptPos[currentScript] = 0
                    scriptScroll[currentScript] = 0
                }
                saveScripts()
                stopSmoothScroll()
                stopAuto()
                targetScrollY = 0
                scrollView.smoothScrollTo(0, 0)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // Шаг A3: результат выбора .txt-файла — читаем его и кладём в поле редактора
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_TXT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (text.isEmpty()) { toast("Файл пуст"); return }
                val et = editorEt
                if (et != null) {
                    et.setText(text)
                    toast("Текст загружен из файла — нажмите «Сохранить»")
                }
            } catch (e: Exception) {
                toast("Ошибка чтения файла: " + e.message)
            }
        }
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

        // Настройка: номер активной строки от верха экрана (1..6, стандарт 3).
        // Применяется сразу — лента подъезжает к новому положению
        val lineLabel = TextView(this)
        lineLabel.text = "Активная строка сверху: $activeLineFromTop"
        lineLabel.textSize = 16f
        val lineSb = SeekBar(this)
        lineSb.max = 5
        lineSb.progress = activeLineFromTop - 1
        lineSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                activeLineFromTop = v + 1
                lineLabel.text = "Активная строка сверху: $activeLineFromTop"
                if (fromUser) textView.post { autoScroll() }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(lineLabel); box.addView(lineSb)

        // Настройка: скорость плавной прокрутки при чтении (1..15, стандарт 3).
        // Чем больше — тем резвее лента догоняет чтеца
        val spdLabel = TextView(this)
        spdLabel.text = "Скорость прокрутки при чтении: $followSpeedStep"
        spdLabel.textSize = 16f
        val spdSb = SeekBar(this)
        spdSb.max = 14
        spdSb.progress = followSpeedStep - 1
        spdSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                followSpeedStep = v + 1
                spdLabel.text = "Скорость прокрутки при чтении: $followSpeedStep"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(spdLabel); box.addView(spdSb)

        // ===== РАСШИРЕННЫЕ НАСТРОЙКИ (добавлено по заданию) =====
        val advTitle = TextView(this)
        advTitle.text = "— Расширенные —"
        advTitle.textSize = 16f
        advTitle.gravity = Gravity.CENTER
        advTitle.setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt())
        box.addView(advTitle)

        // Цвет фона: применяется сразу (с учётом текущей яркости)
        val bgLabel = TextView(this)
        bgLabel.text = "Цвет фона:"
        bgLabel.textSize = 16f
        val bgRow = LinearLayout(this)
        fun bgBtn(name: String, c: Int): Button {
            val b = Button(this)
            b.text = name
            b.setOnClickListener { bgColor = c; applyBackground() }
            return b
        }
        bgRow.addView(bgBtn("Чёрный", Color.BLACK))
        bgRow.addView(bgBtn("Серый", Color.parseColor("#3A3A3A")))
        bgRow.addView(bgBtn("Синий", Color.parseColor("#1A2A4A")))
        box.addView(bgLabel); box.addView(bgRow)

        // Прозрачность (яркость) фона: 100% — чистый выбранный цвет,
        // меньше — цвет гаснет к чёрному; для чёрного фона — осветляется
        // к серому (исправление: раньше на чёрном эффект был не виден).
        // Применяется сразу
        val bgaLabel = TextView(this)
        bgaLabel.text = "Прозрачность фона: $bgAlpha%"
        bgaLabel.textSize = 16f
        val bgaSb = SeekBar(this)
        bgaSb.max = 100
        bgaSb.progress = bgAlpha
        bgaSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                bgAlpha = v
                bgaLabel.text = "Прозрачность фона: $bgAlpha%"
                if (fromUser) applyBackground()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(bgaLabel); box.addView(bgaSb)

        // Межстрочный интервал: 1.00..2.00, стандарт 1.25. Применяется сразу
        val lsLabel = TextView(this)
        lsLabel.text = "Межстрочный интервал: " + String.format("%.2f", lineSpacingStep / 100f)
        lsLabel.textSize = 16f
        val lsSb = SeekBar(this)
        lsSb.max = 20 // шаг 0.05: 100 + v*5 = 100..200
        lsSb.progress = (lineSpacingStep - 100) / 5
        lsSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                lineSpacingStep = 100 + v * 5
                lsLabel.text = "Межстрочный интервал: " + String.format("%.2f", lineSpacingStep / 100f)
                if (fromUser) {
                    textView.setLineSpacing(0f, lineSpacingStep / 100f)
                    textView.post { autoScroll() }
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(lsLabel); box.addView(lsSb)

        // Цвет «прочитанного» (затемнённого) текста: применяется сразу
        val rcLabel = TextView(this)
        rcLabel.text = "Цвет прочитанного:"
        rcLabel.textSize = 16f
        val rcRow = LinearLayout(this)
        fun rcBtn(name: String, c: Int): Button {
            val b = Button(this)
            b.text = name
            b.setOnClickListener { readColor = c; render() }
            return b
        }
        rcRow.addView(rcBtn("Серый", Color.parseColor("#555555")))
        rcRow.addView(rcBtn("Тёмный", Color.parseColor("#333333")))
        rcRow.addView(rcBtn("Синий", Color.parseColor("#3A5A8A")))
        rcRow.addView(rcBtn("Зелёный", Color.parseColor("#2E6A38")))
        box.addView(rcLabel); box.addView(rcRow)

        // Чувствительность: сколько слов текста подряд нужно, чтобы суфлёр
        // «поверил», что вы вернулись к чтению после посторонних слов
        val cwLabel = TextView(this)
        cwLabel.text = "Слов для подтверждения: $confirmWordsNeeded"
        cwLabel.textSize = 16f
        val cwSb = SeekBar(this)
        cwSb.max = 4 // 1..5
        cwSb.progress = confirmWordsNeeded - 1
        cwSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                confirmWordsNeeded = v + 1
                cwLabel.text = "Слов для подтверждения: $confirmWordsNeeded"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(cwLabel); box.addView(cwSb)

        // Окно поиска при подтверждении: на сколько слов вперёд/назад от
        // текущего места суфлёр ищет произнесённое слово (стандарт 15)
        val swLabel = TextView(this)
        swLabel.text = "Окно поиска: $searchWindow слов"
        swLabel.textSize = 16f
        val swSb = SeekBar(this)
        swSb.max = 25 // 5..30
        swSb.progress = searchWindow - 5
        swSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                searchWindow = v + 5
                swLabel.text = "Окно поиска: $searchWindow слов"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(swLabel); box.addView(swSb)

        // Настроек стало много — оборачиваем в прокрутку, чтобы всё помещалось
        val scroll = ScrollView(this)
        scroll.addView(box)

        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(scroll)
            .setPositiveButton("Готово") { _, _ ->
                prefs.edit().putFloat("font", fontSize)
                    .putInt("color", activeColor)
                    .putBoolean("mirror", mirror)
                    .putInt("activeLine", activeLineFromTop)
                    .putInt("followSpeed", followSpeedStep)
                    .putInt("bgColor", bgColor)
                    .putInt("bgAlpha", bgAlpha)
                    .putInt("lineSpacing", lineSpacingStep)
                    .putInt("readColor", readColor)
                    .putInt("confirmWords", confirmWordsNeeded)
                    .putInt("searchWin", searchWindow).apply()
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
        stopSmoothScroll()
        stopAuto()
    }

    companion object {
        private const val REQ_IMPORT_TXT = 42
        // Шаг C1: количество квадратиков в индикаторе звука
        private const val LEVEL_CELLS = 30
    }
}
