package com.voiceprompter.app

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.Crop
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    // Этап 2 (камера), шаг 3 (раздвоение звука): на Android 10+ система сама
    // раздаёт один поток микрофона и записи видео (CameraX), и распознаванию
    // (Vosk). «Сторожок тишины» ниже следит, что распознавание во время
    // записи действительно получает звук; если телефон отдаёт ему тишину —
    // предупреждаем и советуем режим АВТО. silentMs — сколько миллисекунд
    // подряд тишина при идущей записи; micConflictWarned — предупреждение
    // уже показано (показываем один раз за запись)
    @Volatile private var silentMs = 0L
    @Volatile private var micConflictWarned = false

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
    // (стандарт 2 — над ней одна затемнённая строка) и скорость плавной
    // прокрутки при чтении (стандарт 3, внутри используется как 0.03)
    private var activeLineFromTop = 2
    private var followSpeedStep = 3

    // Расширенные настройки (добавлено по заданию):
    // - цвет фона и его НАСТОЯЩАЯ прозрачность: 100% — плотный выбранный
    //   цвет, 0% — фон полностью исчезает и под текстом виден рабочий стол
    //   телефона (работает благодаря TransparentTheme в styles.xml и
    //   AndroidManifest.xml);
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

    // Ширина текста (по заданию): процент ширины экрана, который занимает
    // колонка текста (30..100, стандарт 100 — как раньше). Колонка всегда
    // по центру. Нужно, чтобы на планшете/широком телефоне сузить текст
    // ровно над объективом камеры — глаза не бегают по широкой строке.
    // Реализовано боковыми отступами TextView, поэтому жесты (тап/свайп)
    // продолжают работать по всей ширине экрана
    private var textWidthPercent = 100

    // Выбор микрофона вручную (по заданию): пользователь может указать,
    // с какого микрофона писать звук (встроенный / гарнитура / USB /
    // Bluetooth). Храним ТИП устройства и его имя — по ним находим
    // устройство при каждом старте записи (числовые id системой не
    // сохраняются между перезагрузками). -1 = «Авто (выбирает система)»
    private var micPrefType = -1
    private var micPrefName = ""

    // Этап 2 (камера), шаг 1: превью камеры. PreviewView — САМЫЙ НИЖНИЙ
    // слой окна (под текстом и кнопками), scrimView — цветная подложка
    // МЕЖДУ камерой и текстом. applyBackground() красит подложку, поэтому
    // существующие настройки «Цвет фона» и «Прозрачность фона» управляют
    // видимостью камеры: 100% — камеры не видно, 0% — видна полностью.
    // useFrontCamera: фронтальная (стандарт) или задняя, переключается
    // долгим нажатием на кнопку 🎥 и сохраняется
    private var previewView: PreviewView? = null
    private var scrimView: View? = null
    private var cameraOn = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var useFrontCamera = true
    private lateinit var btnCam: TextView

    // Этап 2 (камера), шаг 2: запись видео. VideoCapture привязывается к
    // камере вместе с превью; Recording — идущая запись. Видео со звуком
    // сохраняется в галерею (Movies/VoicePrompter). Во время записи кнопка
    // ⏺ становится красной и показывает время записи (recTimer)
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isRecording = false

    // Форматы видео (одна кнопка, перебор по кругу): 0 — «16:9» (только
    // широкий файл), 1 — «16:9+9:16» (плюс вертикальный шортс 9:16),
    // 2 — «16:9+6:19» (плюс узкий вертикальный файл 6:19). Вырезка идёт
    // ОФЛАЙН после остановки записи, вторым файлом (..._shorts.mp4)
    private var videoFormat = 0
    private val formatLabels = arrayOf("16:9", "16:9+9:16", "16:9+6:19")
    // Пропорции вертикального кадра каждого формата (0 — вырезки нет)
    private val formatW = intArrayOf(0, 9, 6)
    private val formatH = intArrayOf(1, 16, 19)
    private lateinit var btnAspect: TextView
    private var frameLeftView: View? = null
    private var frameRightView: View? = null
    private var transformer: Transformer? = null
    private var recStartMs = 0L
    private lateinit var btnRec: TextView
    private val recTimer = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val s = ((System.currentTimeMillis() - recStartMs) / 1000).toInt()
            btnRec.text = String.format(Locale.US, "⏺%d:%02d", s / 60, s % 60)
            handler.postDelayed(this, 1000)
        }
    }

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

    // Пометка «пропущено» (по заданию, пункт 4): куски текста, через которые
    // суфлёр перескочил (не были прочитаны), подсвечиваются своим оттенком —
    // при монтаже сразу видно, что осталось непрочитанным. Храним диапазоны
    // номеров слов [от, до). Список живёт в рамках сеанса чтения и очищается
    // при перезапуске ⟲ и смене текста
    private val skipRanges = ArrayList<Pair<Int, Int>>()
    private val skipColor = Color.parseColor("#8A5A2A")

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
        // Пункт 4: пользователь сам перемотал назад — пометки «пропущено»,
        // оказавшиеся впереди курсора, снимаем (он собирается это прочитать)
        skipRanges.removeAll { it.first >= currentIndex }
        render()
    }

    private val demoText = "Добро пожаловать в ВойсПромптер — суфлёр, который слушает ваш голос.\n\nЧитайте этот текст вслух в обычном темпе. Строка сама поедет за вами. Если вы замолчите, суфлёр остановится и будет ждать вас на том же месте.\n\nПопробуйте сказать что-нибудь постороннее — текст останется на месте, потому что суфлёр следит именно за словами сценария.\n\nА это последний абзац для проверки перескока. Прочитайте несколько слов отсюда, и суфлёр найдёт это место сам."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Поворот экрана (по заданию): у окна прозрачная тема (TransparentTheme),
        // а прозрачные окна Android сам НЕ поворачивает — они наследуют
        // ориентацию того, что под ними (рабочего стола, который обычно
        // закреплён вертикально). Поэтому явно просим поворот по датчику.
        // FULL_USER уважает системный переключатель автоповорота: если
        // автоповорот в шторке выключен — приложение тоже не вертится.
        // try/catch — страховка для Android 8.0, где у прозрачных окон
        // такой запрос мог вызывать ошибку
        try {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        } catch (e: Exception) { }
        prefs = getSharedPreferences("vp", MODE_PRIVATE)
        jumpEnabled = prefs.getBoolean("jump", true)
        fontSize = prefs.getFloat("font", 34f)
        activeColor = prefs.getInt("color", Color.WHITE)
        mirror = prefs.getBoolean("mirror", false)
        autoMode = prefs.getBoolean("autoMode", false)
        autoSpeed = prefs.getInt("autoSpeed", 30)
        activeLineFromTop = prefs.getInt("activeLine", 2)
        followSpeedStep = prefs.getInt("followSpeed", 3)
        // Расширенные настройки
        bgColor = prefs.getInt("bgColor", Color.BLACK)
        bgAlpha = prefs.getInt("bgAlpha", 100)
        lineSpacingStep = prefs.getInt("lineSpacing", 125)
        readColor = prefs.getInt("readColor", Color.parseColor("#555555"))
        confirmWordsNeeded = prefs.getInt("confirmWords", 3)
        searchWindow = prefs.getInt("searchWin", 15)
        // Ширина текста (по заданию): восстанавливаем сохранённое значение
        textWidthPercent = prefs.getInt("textWidth", 100)
        // Выбор микрофона (по заданию): восстанавливаем сохранённый выбор
        micPrefType = prefs.getInt("micType", -1)
        micPrefName = prefs.getString("micName", "") ?: ""
        // Этап 2 (камера), шаг 1: какая камера была выбрана в прошлый раз
        useFrontCamera = prefs.getBoolean("camFront", true)
        // Форматы: восстанавливаем сохранённый формат (по умолчанию 16:9)
        videoFormat = prefs.getInt("videoFormat", 0)
        if (videoFormat < 0 || videoFormat >= formatLabels.size) videoFormat = 0
        loadScripts()
        rawText = scriptTexts[currentScript]

        val d = resources.displayMetrics.density
        val root = FrameLayout(this)
        rootLayout = root

        // Этап 2 (камера), шаг 1: превью камеры — самый нижний слой окна.
        // Пока камера выключена — скрыто и не потребляет ресурсов
        val pv = PreviewView(this)
        pv.visibility = View.GONE
        previewView = pv
        root.addView(pv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))

        // Этап 2 (камера), шаг 1: цветная подложка МЕЖДУ камерой и текстом.
        // applyBackground() теперь красит её, а не корень окна — фон корня
        // всегда рисуется ПОД превью камеры, и камеру не было бы видно.
        // Поведение прозрачности БЕЗ камеры не изменилось: при < 100%
        // сквозь подложку виден рабочий стол (TransparentTheme)
        val scrim = View(this)
        scrimView = scrim
        root.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))
        applyBackground()

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        scrollView = ScrollView(this)
        textView = TextView(this)
        textView.setTextColor(activeColor)
        textView.textSize = fontSize
        textView.setLineSpacing(0f, lineSpacingStep / 100f)
        // Ширина текста (по заданию): отступы теперь ставит applyTextWidth() —
        // с учётом настройки «Ширина текста» (при 100% — ровно как раньше)
        applyTextWidth()
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
        // Этап 2 (камера), шаг 1: кнопка включения превью камеры
        btnCam = makeBtn("🎥")
        // Этап 2 (камера), шаг 2: кнопка записи видео
        btnRec = makeBtn("⏺")
        // Шортс: кнопка режима 16:9 / 16:9+9:16
        btnAspect = makeBtn("16:9")
        updateAspectBtn()
        val btnFontMinus = makeBtn("A−")
        val btnFontPlus = makeBtn("A+")
        val btnEdit = makeBtn("✎")
        val btnLibrary = makeBtn("📚")
        val btnSettings = makeBtn("⚙")
        bar.addView(micDot); bar.addView(btnPlay); bar.addView(btnRestart)
        bar.addView(btnJump); bar.addView(btnAuto); bar.addView(btnCam)
        bar.addView(btnRec); bar.addView(btnAspect)
        bar.addView(btnFontMinus); bar.addView(btnFontPlus)
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
        // Шортс: две вертикальные линии — границы кадра 9:16 (видны
        // только при включённой камере в режиме 16:9+9:16)
        val frameL = View(this)
        frameL.setBackgroundColor(Color.parseColor("#FFEB3B"))
        frameL.visibility = View.GONE
        val frameR = View(this)
        frameR.setBackgroundColor(Color.parseColor("#FFEB3B"))
        frameR.visibility = View.GONE
        frameLeftView = frameL
        frameRightView = frameR
        root.addView(frameL)
        root.addView(frameR)

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
        // Выбор микрофона (по заданию): нажатие на точку — меню выбора
        // микрофона; долгое нажатие — прежняя справка о микрофоне
        micDot.setOnClickListener { micSelectDialog() }
        micDot.setOnLongClickListener { toast(micInfo()); true }
        // Этап 2 (камера), шаг 1: нажатие — превью вкл/выкл; долгое
        // нажатие — переключение фронтальная/задняя камера
        btnCam.setOnClickListener { toggleCamera() }
        btnCam.setOnLongClickListener {
            // Шаг 2: при смене камеры идущую запись корректно завершаем
            if (isRecording) {
                stopRecording()
                toast("Запись остановлена (смена камеры)")
            }
            useFrontCamera = !useFrontCamera
            prefs.edit().putBoolean("camFront", useFrontCamera).apply()
            toast(if (useFrontCamera) "Камера: фронтальная" else "Камера: задняя")
            if (cameraOn) bindCamera()
            true
        }
        // Этап 2 (камера), шаг 2: нажатие на ⏺ — запись видео старт/стоп
        btnRec.setOnClickListener { toggleRecording() }
        // Форматы: одна кнопка перебирает форматы видео по кругу
        btnAspect.setOnClickListener {
            videoFormat = (videoFormat + 1) % formatLabels.size
            prefs.edit().putInt("videoFormat", videoFormat).apply()
            updateAspectBtn()
            updateShortsFrame()
            toast(if (videoFormat == 0)
                "Режим 16:9: записывается только широкий файл"
            else
                "Режим " + formatLabels[videoFormat] + ": после остановки записи будет вырезан второй файл — вертикальный " + formatW[videoFormat] + ":" + formatH[videoFormat] + " (офлайн)")
        }
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

    // Ширина текста (по заданию): манифест не пересоздаёт экран при повороте
    // (configChanges), поэтому при повороте пересчитываем отступы сами —
    // иначе после поворота колонка была бы не той ширины
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        textView.post { applyTextWidth() }
        // Шортс: при повороте пересчитываем положение рамки 9:16
        updateShortsFrame()
    }

    // Ширина текста (по заданию): применяем настройку. При 100% отступы
    // в точности прежние (16 dp по бокам). При меньших значениях к ним
    // добавляются равные боковые отступы — колонка текста сужается к
    // центру экрана. Нижний отступ 500 dp (запас прокрутки) сохранён
    private fun applyTextWidth() {
        val d = resources.displayMetrics.density
        val extra = resources.displayMetrics.widthPixels * (100 - textWidthPercent) / 200
        textView.setPadding((16 * d).toInt() + extra, (8 * d).toInt(),
            (16 * d).toInt() + extra, (500 * d).toInt())
        // Текст переложился по строкам — подъезжаем к активной строке заново
        textView.post { autoScroll() }
    }

    // Расширенные настройки: фон экрана. НАСТОЯЩАЯ прозрачность (по заданию):
    // процент применяется к альфа-каналу цветной подложки (scrimView). 100% —
    // плотный выбранный цвет; 0% — подложка полностью исчезает, и под текстом
    // видно то, что ниже: превью камеры (если включена кнопкой 🎥) или рабочий
    // стол телефона (TransparentTheme в styles.xml + AndroidManifest.xml).
    // Текст, кнопки и индикаторы прозрачность не затрагивает
    private fun applyBackground() {
        val a = (bgAlpha * 255 / 100).coerceIn(0, 255)
        scrimView?.setBackgroundColor(Color.argb(a,
            Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)))
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
        // Пункт 4: новый текст — старые пометки «пропущено» не действительны
        skipRanges.clear()
        render()
    }

    private fun render() {
        val sp = SpannableString(rawText)
        if (currentIndex > 0 && currentIndex <= wordEnds.size) {
            // Цвет «прочитанного» настраивается в Расширенных настройках
            sp.setSpan(ForegroundColorSpan(readColor),
                0, wordEnds[currentIndex - 1], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Пометка «пропущено» (по заданию, пункт 4): поверх «прочитанного»
        // подсвечиваем своим оттенком куски, через которые суфлёр перескочил.
        // Красим только ту часть диапазона, которая уже позади курсора
        for (r in skipRanges) {
            val e = min(r.second, currentIndex)
            if (r.first < e && r.first < wordStarts.size) {
                sp.setSpan(ForegroundColorSpan(skipColor),
                    wordStarts[r.first], wordEnds[e - 1], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
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

    // Нечёткое совпадение слов (по заданию, пункт 1): расстояние Левенштейна.
    // Считает минимальное число правок (замена/вставка/удаление буквы),
    // превращающих одно слово в другое: «привет»→«превет» = 1 правка
    private fun editDistance(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            for (j in 1..m) {
                cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1),
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    // Совпадение слова текста и услышанного слова (пункт 1):
    // 1) точное совпадение; 2) как раньше — одинаковые первые 4 буквы
    // (покрывает окончания: «красивый/красивая»); 3) НОВОЕ — нечёткое:
    // для слов 4-6 букв допускается 1 ошибка распознавания, для более
    // длинных — 2 ошибки. Короткие слова (до 3 букв) сравниваются только
    // точно, чтобы предлоги «на/но/не» не путались между собой
    private fun wordMatch(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 4 && b.length >= 4 && a.substring(0, 4) == b.substring(0, 4)) return true
        val shorter = min(a.length, b.length)
        if (shorter < 4) return false
        if (abs(a.length - b.length) > 2) return false
        val maxDist = if (shorter <= 6) 1 else 2
        return editDistance(a, b) <= maxDist
    }

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
                    // Пункт 4: если подтверждённое место впереди текущего —
                    // кусок между ними пропущен, помечаем его своим оттенком
                    val chainStart = pendingIndex - pendingCount
                    if (chainStart > currentIndex) skipRanges.add(Pair(currentIndex, chainStart))
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
            // Окно настраивается в Расширенных настройках (стандарт 15).
            // ИСПРАВЛЕНИЕ (по заданию): широкое окно поиска — это тоже
            // перескок, поэтому оно работает ТОЛЬКО при включённой кнопке 🔀.
            // При выключенных перескоках ищем строго рядом с курсором
            // (до 3 слов вперёд, как в обычном движении) и назад не ищем —
            // суфлёр идёт по порядку, как и обещает подсказка кнопки
            pendingIndex = -1; pendingCount = 0
            var found = -1
            val fwdEnd = if (jumpEnabled) min(currentIndex + searchWindow, wordsNorm.size)
                else min(currentIndex + 3, wordsNorm.size)
            for (j in currentIndex until fwdEnd) {
                if (wordMatch(wordsNorm[j], w)) { found = j; break }
            }
            if (found < 0 && jumpEnabled) {
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
                // Пункт 4: перескок вперёд — кусок между старым местом и новым
                // не был прочитан, помечаем его оттенком «пропущено»
                if (best > currentIndex) skipRanges.add(Pair(currentIndex, best))
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
            // Выбор микрофона вручную (по заданию, пункт 2): если пользователь
            // выбрал конкретный микрофон, просим систему писать именно с него.
            // Если выбранный микрофон сейчас не подключён — работает как «Авто»
            if (Build.VERSION.SDK_INT >= 23) {
                val prefDev = findPreferredMic()
                if (prefDev != null) ar.setPreferredDevice(prefDev)
                else if (micPrefType != -1) toast("Выбранный микрофон не найден — пишу с микрофона по умолчанию")
            }
            recognizer = rec
            audioRecord = ar
            listeningLoop = true
            // Шаг 3 (раздвоение звука): сторожок тишины — с чистого листа
            silentMs = 0
            micConflictWarned = false
            ar.startRecording()
            audioThread = Thread {
                val buf = ShortArray(1600) // ~100 мс звука при 16 кГц
                while (listeningLoop) {
                    val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                    if (n <= 0) {
                        // Шаг 3: микрофон не отдаёт данные (например, занят
                        // записью видео на некоторых телефонах) — не крутим
                        // цикл впустую и считаем это тишиной для сторожка
                        try { Thread.sleep(50) } catch (e: InterruptedException) { }
                        if (isRecording) checkSilence(50) else silentMs = 0
                        continue
                    }
                    // Громкость порции — пиковая амплитуда для квадратиков
                    var peak = 0
                    for (i in 0 until n) {
                        val v = abs(buf[i].toInt())
                        if (v > peak) peak = v
                    }
                    // Шаг 3 (раздвоение звука): во время записи распознавание
                    // должно получать живой звук; если подряд идёт полная
                    // тишина — телефон не делит микрофон между потребителями
                    if (isRecording && peak < 30) checkSilence(100) else silentMs = 0
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

    // Шаг 3 (раздвоение звука): учёт тишины во время записи видео. Если при
    // идущей записи распознавание 3 секунды подряд получает полную тишину —
    // значит, этот телефон не умеет отдавать один микрофон и записи, и
    // распознаванию одновременно. Предупреждаем ОДИН раз за запись и
    // советуем резервный режим АВТО (он микрофон не использует)
    private fun checkSilence(ms: Long) {
        silentMs += ms
        if (silentMs >= 3000 && !micConflictWarned) {
            micConflictWarned = true
            handler.post {
                toast("⚠ Во время записи телефон не передаёт звук распознаванию — следование за голосом работать не будет. Используйте режим АВТО.")
            }
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
        // Пункт 4: новый дубль — пометки «пропущено» прошлого дубля убираем
        skipRanges.clear()
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

    // Выбор микрофона (по заданию, пункт 2): понятное название типа устройства
    private fun micTypeLabel(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Встроенный"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Гарнитура (провод)"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-гарнитура"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        else -> "Другой"
    }

    // Выбор микрофона (по заданию, пункт 2): список микрофонов, из которых
    // можно писать звук. Берём только «настоящие» микрофоны (встроенный,
    // проводная гарнитура, USB, Bluetooth) и убираем дубли
    private fun micDevices(): List<AudioDeviceInfo> {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ok = intArrayOf(AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type in ok }
            .distinctBy { it.type.toString() + "|" + it.productName }
    }

    // Выбор микрофона (по заданию, пункт 2): найти выбранное пользователем
    // устройство среди подключённых сейчас. Сначала ищем точное совпадение
    // тип+имя, если не нашли — любое устройство того же типа
    private fun findPreferredMic(): AudioDeviceInfo? {
        if (micPrefType == -1) return null
        val devs = micDevices()
        return devs.firstOrNull { it.type == micPrefType && it.productName.toString() == micPrefName }
            ?: devs.firstOrNull { it.type == micPrefType }
    }

    // Выбор микрофона (по заданию, пункт 2): меню выбора. «Авто» — система
    // сама решает (как было раньше). Выбор сохраняется и применяется при
    // следующем нажатии ▶ (если чтение уже идёт — после паузы и старта)
    private fun micSelectDialog() {
        val devs = micDevices()
        val labels = ArrayList<String>()
        labels.add("Авто (выбирает система)")
        for (dev in devs) labels.add(micTypeLabel(dev.type) + ": " + dev.productName)
        var checked = 0
        for (i in devs.indices) {
            if (devs[i].type == micPrefType &&
                (devs[i].productName.toString() == micPrefName || checked == 0 && micPrefType != -1)) {
                checked = i + 1
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Микрофон для записи")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dlg, which ->
                if (which == 0) {
                    micPrefType = -1; micPrefName = ""
                    toast("Микрофон: авто (выбирает система)")
                } else {
                    val dev = devs[which - 1]
                    micPrefType = dev.type
                    micPrefName = dev.productName.toString()
                    toast("Микрофон: " + micTypeLabel(dev.type) + " — " + dev.productName)
                }
                prefs.edit().putInt("micType", micPrefType)
                    .putString("micName", micPrefName).apply()
                if (isPlaying) toast("Новый микрофон включится после паузы ⏸ и старта ▶")
                dlg.dismiss()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    // ---------- Камера (этап 2, шаг 1: превью) ----------

    // Кнопка 🎥: включить/выключить превью камеры. Пока это «зеркало» для
    // кадрирования; запись видео — кнопкой ⏺ (шаг 2).
    // Чтобы видеть себя, уменьшите «Прозрачность фона» в настройках:
    // подложка станет прозрачной и под текстом появится изображение камеры
    private fun toggleCamera() {
        if (cameraOn) { stopCamera(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 2)
            return
        }
        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
                previewView?.visibility = View.VISIBLE
                cameraOn = true
                btnCam.setTextColor(Color.parseColor("#4CAF50"))
                btnCam.background = btnBg(true)
                updateShortsFrame()
                if (bgAlpha > 50) toast("Камера включена. Чтобы видеть себя, уменьшите «Прозрачность фона» в ⚙")
            } catch (e: Exception) {
                toast("Ошибка камеры: " + e.message)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Привязка камеры к экрану. bindToLifecycle сам останавливает камеру
    // при сворачивании приложения и включает при возврате.
    // Шаг 2: вместе с превью привязывается и VideoCapture — готовность
    // писать видео. Если камера телефона не тянет превью+видео вместе,
    // привязываем только превью (кнопка ⏺ сообщит, что запись недоступна)
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        try {
            // Идущую запись нельзя пережить перепривязку — завершаем корректно
            if (isRecording) stopRecording()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView?.surfaceProvider)
            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA
            provider.unbindAll()
            // Шаг 2: качество видео — FullHD, при невозможности — ближайшее
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)))
                .build()
            val vc = VideoCapture.withOutput(recorder)
            try {
                provider.bindToLifecycle(this, selector, preview, vc)
                videoCapture = vc
            } catch (e: Exception) {
                // Резерв: камера не тянет превью+видео — работаем только с превью
                videoCapture = null
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview)
            }
        } catch (e: Exception) {
            toast("Ошибка камеры: " + e.message)
        }
    }

    private fun stopCamera() {
        // Шаг 2: если идёт запись — сначала корректно завершаем её,
        // чтобы файл сохранился в галерею
        if (isRecording) stopRecording()
        try { cameraProvider?.unbindAll() } catch (e: Exception) { }
        videoCapture = null
        previewView?.visibility = View.GONE
        cameraOn = false
        btnCam.setTextColor(Color.parseColor("#EEEEEE"))
        btnCam.background = btnBg(false)
        updateShortsFrame()
    }

    // ---------- Запись видео (этап 2, шаг 2) ----------

    // Кнопка ⏺: старт/стоп записи видео со звуком. Файл сохраняется в
    // галерею: Movies/VoicePrompter/VP_дата_время.mp4. Во время записи
    // кнопка красная и показывает время (recTimer).
    // Шаг 3 (раздвоение звука): один физический микрофон система (Android
    // 10+) сама раздаёт двум потребителям — записи видео (CameraX) и
    // распознаванию (Vosk). «Сторожок тишины» в цикле микрофона проверяет,
    // что распознавание при записи действительно получает звук, и честно
    // предупреждает, если конкретный телефон делить микрофон не умеет
    private fun toggleRecording() {
        if (isRecording) { stopRecording(); return }
        if (!cameraOn) { toast("Сначала включите камеру 🎥"); return }
        val vc = videoCapture
        if (vc == null) { toast("Запись недоступна: камера не поддерживает видео с превью"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            toast("Нет доступа к микрофону — звук записать не получится")
            return
        }
        val name = "VP_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val cv = ContentValues()
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, name)
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= 29) {
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VoicePrompter")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv)
            .build()
        try {
            recording = vc.output.prepareRecording(this, opts)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) { ev ->
                    when (ev) {
                        is VideoRecordEvent.Start -> {
                            isRecording = true
                            recStartMs = System.currentTimeMillis()
                            btnRec.text = "⏺0:00"
                            btnRec.setTextColor(Color.parseColor("#F44336"))
                            btnRec.background = btnBg(true)
                            handler.removeCallbacks(recTimer)
                            handler.postDelayed(recTimer, 1000)
                            // Шаг 3: новая запись — сторожок тишины с нуля
                            silentMs = 0
                            micConflictWarned = false
                        }
                        is VideoRecordEvent.Finalize -> {
                            isRecording = false
                            handler.removeCallbacks(recTimer)
                            btnRec.text = "⏺"
                            btnRec.setTextColor(Color.parseColor("#EEEEEE"))
                            btnRec.background = btnBg(false)
                            recording = null
                            if (ev.hasError())
                                toast("Ошибка записи видео (код " + ev.error + ")")
                            else {
                                toast("Видео сохранено: галерея → Movies/VoicePrompter/" + name)
                                // Форматы: если выбран формат с вырезкой — режем вертикальный файл
                                if (videoFormat != 0) makeShorts(ev.outputResults.outputUri, name)
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            toast("Ошибка записи: " + e.message)
        }
    }

    private fun stopRecording() {
        // Завершение записи: файл дописывается и приходит событие Finalize,
        // которое вернёт кнопке обычный вид и покажет, куда сохранено видео
        try { recording?.stop() } catch (e: Exception) { }
    }

    // ---------- Шортс 9:16 (функция «16:9 / 16:9+9:16») ----------

    // Кнопка формата: зелёная — после записи будет вырезан вертикальный файл
    private fun updateAspectBtn() {
        val on = videoFormat != 0
        btnAspect.text = formatLabels[videoFormat]
        btnAspect.setTextColor(if (on) Color.parseColor("#4CAF50") else Color.parseColor("#888888"))
        btnAspect.background = btnBg(on)
    }

    // Рамка границ вертикального кадра: две жёлтые линии по центру экрана.
    // Ширина области = высота экрана * 9/16. Если экран сам вертикальный
    // (область шире экрана) — рамка не нужна и скрывается
    private fun updateShortsFrame() {
        val l = frameLeftView ?: return
        val r = frameRightView ?: return
        val root = rootLayout ?: return
        root.post {
            val w = root.width
            val h = root.height
            val on = videoFormat != 0
            val frameW = if (on) (h * formatW[videoFormat].toFloat() / formatH[videoFormat]).toInt() else 0
            if (!cameraOn || !on || w <= 0 || h <= 0 || frameW >= w) {
                l.visibility = View.GONE
                r.visibility = View.GONE
                return@post
            }
            val d = resources.displayMetrics.density
            val left = (w - frameW) / 2
            val lpL = FrameLayout.LayoutParams((2 * d).toInt(), FrameLayout.LayoutParams.MATCH_PARENT)
            lpL.leftMargin = left
            l.layoutParams = lpL
            val lpR = FrameLayout.LayoutParams((2 * d).toInt(), FrameLayout.LayoutParams.MATCH_PARENT)
            lpR.leftMargin = left + frameW
            r.layoutParams = lpR
            l.visibility = View.VISIBLE
            r.visibility = View.VISIBLE
        }
    }

    // Вырезка шортса: из записанного файла берётся центральная полоса 9:16
    // и перекодируется библиотекой Media3 Transformer. Полностью ОФЛАЙН —
    // интернет не используется, всё считает процессор телефона
    private fun makeShorts(uri: Uri?, name: String) {
        if (uri == null) { toast("Шортс: не удалось найти записанный файл"); return }
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(this, uri)
            var w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            mmr.release()
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            if (w <= 0 || h <= 0) { toast("Шортс: не удалось прочитать размеры видео"); return }
            // Доля ширины кадра, которую занимает вертикальная полоса формата
            val frac = (h * formatW[videoFormat].toFloat() / formatH[videoFormat]) / w
            if (frac >= 1f) { toast("Шортс: видео уже вертикальное, вырезка не нужна"); return }
            val crop = Crop(-frac, frac, -1f, 1f)
            val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), listOf<Effect>(crop)))
                .build()
            val outName = name.removeSuffix(".mp4") + "_shorts.mp4"
            val tmp = File(cacheDir, outName)
            val t = Transformer.Builder(this)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        transformer = null
                        saveShortsToGallery(tmp, outName)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult,
                                         exportException: ExportException) {
                        transformer = null
                        tmp.delete()
                        toast("Шортс: ошибка вырезки — " + exportException.message)
                    }
                })
                .build()
            transformer = t
            toast("Вырезаю вертикальный файл " + formatW[videoFormat] + ":" + formatH[videoFormat] + "… Не закрывайте приложение до сообщения о готовности")
            t.start(item, tmp.absolutePath)
        } catch (e: Exception) {
            toast("Шортс: ошибка — " + e.message)
        }
    }

    // Готовый шортс переносим из временной папки в галерею
    private fun saveShortsToGallery(tmp: File, outName: String) {
        try {
            val cv = ContentValues()
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, outName)
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VoicePrompter")
            }
            val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            if (outUri == null) { toast("Шортс: не удалось сохранить в галерею"); return }
            contentResolver.openOutputStream(outUri)?.use { out ->
                tmp.inputStream().use { input -> input.copyTo(out) }
            }
            tmp.delete()
            toast("Шортс сохранён: галерея → Movies/VoicePrompter/" + outName)
        } catch (e: Exception) {
            toast("Шортс: ошибка сохранения — " + e.message)
        }
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

        // Цвет фона: применяется сразу (с учётом текущей прозрачности)
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

        // НАСТОЯЩАЯ прозрачность фона (по заданию): 100% — плотный выбранный
        // цвет, 0% — фон полностью исчезает и под текстом видна камера (если
        // включена) или рабочий стол телефона (TransparentTheme в styles.xml
        // + AndroidManifest.xml). Применяется сразу
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

        // Ширина текста (по заданию): 30..100% ширины экрана, стандарт 100%.
        // Колонка текста сужается к центру — на планшете/широком экране можно
        // поставить текст ровно над объективом камеры. Применяется сразу
        val twLabel = TextView(this)
        twLabel.text = "Ширина текста: $textWidthPercent%"
        twLabel.textSize = 16f
        val twSb = SeekBar(this)
        twSb.max = 70 // 30..100
        twSb.progress = textWidthPercent - 30
        twSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                textWidthPercent = v + 30
                twLabel.text = "Ширина текста: $textWidthPercent%"
                if (fromUser) applyTextWidth()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(twLabel); box.addView(twSb)

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

        // Пресеты оформления (по заданию, пункт 3): одна кнопка вместо ручной
        // настройки нескольких ползунков. Пресет меняет цвет текста, цвет
        // фона (делает его плотным, 100%) и цвет прочитанного. Остальные
        // настройки (шрифт, интервал, чувствительность) не трогает.
        // Строится здесь (когда ползунок прозрачности уже создан), но
        // вставляется В НАЧАЛО окна настроек
        val prTitle = TextView(this)
        prTitle.text = "Пресеты оформления:"
        prTitle.textSize = 16f
        val prRow = LinearLayout(this)
        prRow.orientation = LinearLayout.HORIZONTAL
        fun presetBtn(name: String, txt: Int, bg: Int, read: Int): Button {
            val b = Button(this)
            b.text = name
            b.setOnClickListener {
                activeColor = txt; bgColor = bg; bgAlpha = 100; readColor = read
                textView.setTextColor(activeColor)
                applyBackground()
                render()
                bgaSb.progress = 100 // ползунок прозрачности — к плотному фону
                toast("Пресет: $name")
            }
            return b
        }
        prRow.addView(presetBtn("Классика", Color.WHITE, Color.BLACK, Color.parseColor("#555555")))
        prRow.addView(presetBtn("Жёлтый", Color.parseColor("#FFEB3B"), Color.BLACK, Color.parseColor("#6E6420")))
        prRow.addView(presetBtn("Зелёный", Color.parseColor("#4CAF50"), Color.BLACK, Color.parseColor("#2A4A2E")))
        prRow.addView(presetBtn("Синий", Color.WHITE, Color.parseColor("#1A2A4A"), Color.parseColor("#3A5A8A")))
        // Кнопок четыре — заворачиваем в горизонтальную прокрутку,
        // чтобы поместились на любом экране
        val prScroll = HorizontalScrollView(this)
        prScroll.isHorizontalScrollBarEnabled = false
        prScroll.addView(prRow)
        box.addView(prTitle, 0)
        box.addView(prScroll, 1)

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
                    .putInt("textWidth", textWidthPercent)
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
        else if (requestCode == 1) toast("Без доступа к микрофону суфлёр не сможет вас слышать")
        // Этап 2 (камера), шаг 1: ответ на запрос разрешения «Камера»
        if (requestCode == 2) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera()
            else toast("Без доступа к камере превью не работает")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Шаг 2: идущую запись завершаем корректно, чтобы файл сохранился
        stopRecording()
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
