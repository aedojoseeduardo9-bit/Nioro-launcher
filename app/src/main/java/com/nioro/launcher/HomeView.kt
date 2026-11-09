package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pantalla de inicio: páginas de iconos + dock de cristal estilo macOS.
 * Todo se dibuja en un único Canvas (una sola pasada) y solo se redibuja mientras algo se mueve.
 */
class HomeView(ctx: Context) : View(ctx) {

    interface Host {
        fun launch(e: AppEntry, from: RectF, fromDock: Boolean)
        fun openDrawer()
        fun openRecents()
        fun showMenu(e: AppEntry, anchor: RectF, where: Int) // 0 inicio, 1 dock
        fun dismissMenu()
        fun expandShade()
        fun showHomeMenu(x: Float, y: Float)
        /** Botones del modo edición: 0 Fondo, 1 Vista de apps, 2 Animaciones, 3 Ajustes. */
        fun editAction(i: Int)
        /** gesto configurable (S.G_*) */
        fun gesture(g: Int)
        /** 0 = abrir alarmas (tocar reloj), 1 = abrir calendario (tocar fecha) */
        fun special(i: Int)
        /** deslizar hacia arriba: la vista de apps sigue al dedo (0 empieza · 1 mueve · 2 suelta; p = 1 abrir) */
        fun drawerDrag(stage: Int, p: Float, velocity: Float) {}
        /** el dedo bajó sobre el botón de la vista de apps: se puede preparar antes de abrir */
        fun prewarmDrawer() {}
    }

    class Slot(val kind: Int, val key: String?) { // 0 app, 1 launchpad, 2 recientes, 3 separador
        val sc = SpringValue(1f, 520f, 40f, 0.002f)
        val by = SpringValue(0f, 120f, 5f, 0.002f)
        val bs = SpringValue(0f, 200f, 8f, 0.002f)
        val br = SpringValue(0f, 90f, 7f, 0.002f)
        val bx = SpringValue(0f, 160f, 6f, 0.002f)
        /** crecimiento al aparecer en el dock (0 -> 1 con rebote) */
        val gw = SpringValue(1f, 260f, 14f, 0.002f)
        /** puntito de "app abierta": aparece y se va con un pequeño rebote */
        val run = SpringValue(0f, 380f, 22f, 0.002f)
        var rest = 0f
        var cx = 0f
        var scale = 1f
        var running = false
    }

    var host: Host? = null

    private val ticker = Ticker { dt -> onFrame(dt) }
    private val handler = Handler(Looper.getMainLooper())
    private val glass = Glass()
    /** fondo del dock según su forma y diseño (el mismo pintor que las miniaturas de Ajustes) */
    private val dockArt = DockArt()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    // Estado
    private var homeKeys = ArrayList<String>()
    private var dockKeys = ArrayList<String>()
    private val slots = ArrayList<Slot>()
    private val pageSp = SpringValue(0f, 220f, 25f)
    private val enterSp = SpringValue(1f, 190f, 25f)
    private val pressSp = SpringValue(1f, 700f, 53f, 0.0005f)
    private var pressKey: String? = null
    private var pressSlot: Slot? = null
    private var tickSlot: Slot? = null
    private var datePillW = 0f
    private val editSp = SpringValue(0f, 260f, 26f, 0.0008f)
    /** icono arrastrado: se levanta (0 -> 1) y se inclina según hacia dónde se mueve */
    private val dragLift = SpringValue(0f, 420f, 24f, 0.002f)
    private val dragTilt = SpringValue(0f, 260f, 18f, 0.05f)
    /** hueco de destino al arrastrar: se desliza de una celda a otra */
    private val dropX = SpringValue(0f, 520f, 36f, 0.3f)
    private val dropY = SpringValue(0f, 520f, 36f, 0.3f)
    private var dropShown = false
    /** cambio de minuto: el número viejo sube y se va, el nuevo entra desde abajo */
    private val clockSp = SpringValue(1f, 160f, 22f, 0.002f)
    private var prevTime = ""
    /** hora y minuto actuales (para el reloj analógico, cuyas agujas giran al cambiar) */
    private var curHour24 = 0
    private var curMinute = 0
    private var prevHour24 = -1
    private var prevMinute = -1
    private var prevHour = ""
    private var prevMin = ""
    /** los puntos de página se iluminan mientras se desliza */
    private val dotsBoost = SpringValue(0f, 300f, 30f, 0.002f)
    // ---- editor del reloj (mantener pulsado el reloj)
    /** apertura del editor (0 -> 1, con rebote de burbuja) */
    private val clockEd = SpringValue(0f, 300f, 20f, 0.002f)
    /** "boing" de la burbuja: escala que rebota al soltar o cambiar algo */
    private val clockPop = SpringValue(1f, 420f, 14f, 0.002f)
    /** se estira en la dirección en que se arrastra, como una gota */
    private val clockSqu = SpringValue(0f, 260f, 12f, 0.001f)
    /** posición (dp) y tamaño mostrados: siguen al dedo con un poco de retraso elástico */
    private val ckX = SpringValue(0f, 260f, 18f, 0.05f)
    private val ckY = SpringValue(0f, 260f, 18f, 0.05f)
    private val ckS = SpringValue(1f, 260f, 18f, 0.001f)
    /** rebote de la burbuja-opción recién tocada */
    private val chipPulse = SpringValue(1f, 520f, 16f, 0.002f)
    // ---- menú dock del reloj
    /** indicador de pestaña: se desliza entre pestañas y se estira con la velocidad */
    private val tabSp = SpringValue(0f, 420f, 28f, 0.002f)
    /** el contenido de la pestaña nueva entra deslizándose y apareciendo */
    private val tabFade = SpringValue(1f, 320f, 30f, 0.002f)
    /** tirar del menú hacia abajo para cerrarlo */
    private val sheetPull = SpringValue(0f, 360f, 30f, 0.3f)
    /** los puntos de página aparecen/desaparecen suaves cuando cambia el número de pantallas */
    private val dotsVis = SpringValue(0f, 300f, 30f, 0.002f)
    /** cambio de diseño del dock: el fondo nuevo entra con un estirón elástico (0 → 1) */
    private val dockMorph = SpringValue(1f, 200f, 14f, 0.002f)
    /** huella del diseño del dock dibujado (forma, diseño y su cristal) para notar cuándo cambia */
    private var dockStamp = Long.MIN_VALUE
    private var lastDotsN = 2
    private val extraSprings = arrayOf(dragLift, dragTilt, dropX, dropY, clockSp, dotsBoost, clockEd, clockPop, clockSqu, ckX, ckY, ckS, chipPulse,
        tabSp, tabFade, sheetPull, dotsVis, dockMorph)

    // ---- cada icono del inicio tiene su posición y escala con resorte: así se desliza a su sitio
    // nuevo al reordenar, vuela desde el dedo al soltarlo, crece al llegar y se encoge al irse
    private class IconPos(val key: String) {
        val x = SpringValue(0f, 300f, 26f, 0.3f)
        val y = SpringValue(0f, 300f, 26f, 0.3f)
        val s = SpringValue(1f, 380f, 22f, 0.002f)
        var page = 0
        /** icono que se fue: se encoge en su sitio y desaparece */
        var ghost = false
        var e: AppEntry? = null
    }
    private val iconPos = HashMap<String, IconPos>()
    private val iconList = ArrayList<IconPos>()
    private val popKeys = HashSet<String>()
    private var homeReady = false
    private val iconStepper = object : Stepper {
        override fun step(dt: Float): Boolean {
            var any = false
            var i = 0
            while (i < iconList.size) {
                val p = iconList[i]
                if (p.x.step(dt)) any = true
                if (p.y.step(dt)) any = true
                if (p.s.step(dt)) any = true
                if (p.ghost && !p.s.active) {
                    iconList.removeAt(i)
                    continue
                }
                i++
            }
            return any
        }
    }
    /** carrusel de los 20 diseños (inercia y rebote en los bordes) */
    private val designScroll = Scroll1D(ticker)
    var isClockEditing = false
        private set
    private var ceMode = 0 // 0 nada, 1 mover, 2 tirador de tamaño, 3 pellizco, 4 opción
    private var ceDownX = 0f
    private var ceDownY = 0f
    private var ceLastX = 0f
    private var ceLastY = 0f
    private var ceStartX = 0f
    private var ceStartY = 0f
    private var ceStartS = 1f
    private var ceStartD = 1f
    private var ceMoved = false
    private var pulseIdx = -1
    private val bubble = RectF()
    private val clockBox = RectF()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheetRect = RectF()
    private var sheetTab = 0
    private var tabDir = 1f
    private val tabNames = arrayOf("Diseños", "Colores", "Formato", "Posición")
    /** orden de los 20 diseños en el carrusel (valor de Prefs.clockStyle; 2 = sin reloj no está) */
    private val designOrder = intArrayOf(0, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
    private val designNames = arrayOf(
        "Nioro", "Apilado", "Grande", "Analógico", "Fino", "Cápsula", "Neón", "Contorno", "Retro", "Minimal",
        "Anillo", "Palabras", "Lateral", "Burbujas", "Degradado", "Calendario", "Doble zona", "Puntos", "Segmentos", "Arco del día"
    )
    /** colores de la paleta S.PAL que se ofrecen: automático, blanco, cian, azul, índigo, morado, magenta, rosa, rojo, naranja, amarillo, verde */
    private val paletteMain = intArrayOf(0, 1, 4, 3, 14, 13, 12, 11, 10, 9, 8, 6)
    private val fmtLabels = arrayOf("Sistema", "12 h", "24 h", "Cero inicial", "Separador ·", "a. m./p. m.", "Fecha", "Saludo", "Píldora de fecha")
    private val alignLabels = arrayOf("Izquierda", "Centro", "Derecha")
    private val alignValues = intArrayOf(1, 0, 2)
    // registro de zonas tocables del menú (se rellena al dibujar)
    private val hitRects = Array(72) { RectF() }
    private val hitIds = IntArray(72)
    private var hitN = 0
    private var shPress = -1
    private var shMode = 0 // 0 toque, 1 carrusel, 2 deslizador, 3 tirar para cerrar, 4 nada
    private var shWasMoving = false
    private var shVt: VelocityTracker? = null
    private val tmpR = RectF()
    private val tmpR2 = RectF()
    private val cardR = RectF()
    private val sliderRect = RectF()
    private val sheetTitle = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val sheetSmall = TextPaint(Paint.ANTI_ALIAS_FLAG)
    // miniaturas: se dibuja el diseño real a escala dentro de su tarjeta
    private val thumbBounds = Array(21) { RectF() }
    private var styleOverride = -1
    private var thumb = false
    private val saveClockR = RectF()
    private val saveDateR = RectF()
    private val faces = ClockFaces()
    private val info = ClockInfo()
    private val chipText = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val chipFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint()
    // ---- pinturas de los diseños de reloj
    private val bigH = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bigM = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val thinPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val thinSmall = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pillTime = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val neonPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val neonDate = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sepPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val dialRect = RectF()
    private var hourStr = ""
    private var minStr = ""
    private val stackPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val minPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val greetPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fxPaint = Paint()
    private var topGrad: android.graphics.LinearGradient? = null
    private var botGrad: android.graphics.LinearGradient? = null
    private var greetStr = ""
    private var battery = -1
    private var gridLeft = 0f
    private val clockRect = RectF()
    private val dateRect = RectF()
    private var pressArea = S.IA_HOME
    // multitáctil y toques múltiples
    private val M_MULTI = 5
    /** deslizar hacia arriba con la vista de apps siguiendo al dedo */
    private val M_DRAWER = 6
    private val drawerDragDist: Float get() = dp(340f) * S.p(S.G_SWIPE_DIST)
    private var multiDone = false
    private var y0avg = 0f
    private var d0 = 0f
    private var yAvg = 0f
    private var dNow = 0f
    private var multiT0 = 0L
    private var tapCount = 0
    private var lastTapT = 0L
    // contador de FPS
    private var fpsLast = 0L
    private var fpsVal = 0f
    private val editLabel = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val editNames = arrayOf("Fondo", "Vista de apps", "Animaciones", "Ajustes")
    private val editGlyphs = intArrayOf(Glyphs.WALLPAPER, Glyphs.GRID, Glyphs.MOTION, Glyphs.GEAR)
    private var drawerP = 0f
    private var running: Set<String> = emptySet()

    // Medidas
    private var insetTop = 0f
    private var insetBottom = 0f
    private var iconPx = 0f
    private var dockIconPx = 0f
    private var dockUnit = 0f
    private var cols = 4
    private var rows = 5
    private var cap = 20
    private var cellW = 0f
    private var cellH = 0f
    private var gridTop = 0f
    /** dónde va cada celda según el estilo de organización (Cuadrícula, Panal, Sonrisa...) */
    private val hg = HomeLayout.Grid()
    private var dotsY = 0f
    private var dockCy = 0f
    private var dockH = 0f
    private var restTotal = 0f

    // Dibujo
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val clockPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val capRect = RectF()
    private val tmpRect = RectF()
    private val pt = Motion.PT()
    private val itx = Motion.IT()
    private val labelCache = HashMap<String, String>()
    private var timeStr = ""
    private var dateStr = ""
    private var lastMinute = -1L

    // Gestos
    private val M_NONE = 0
    private val M_PAGE = 1
    private val M_DRAG = 2
    private val M_VSWIPE = 3
    private val M_MENU = 4
    private var touchMode = M_NONE
    private var downX = 0f
    private var downY = 0f
    /** dónde empezó el último toque (para colocar el menú del inicio) */
    val touchX: Float get() = downX
    val touchY: Float get() = downY
    private var pageStart = 0f
    private var moved = false
    private var inDock = false
    private var downKey: String? = null
    private var vt: VelocityTracker? = null
    private var scrubbing = false
    private var scrubX = 0f
    private var vTriggered = false
    private var dragKey: String? = null
    private var dragFromDock = false
    private var dragX = 0f
    private var dragY = 0f
    private var edgeDir = 0

    private val longRun = Runnable {
        val k = downKey
        if (touchMode == M_NONE && !moved && k == null && !inDock) {
            touchMode = M_MENU
            unpress()
            if (Prefs.clockStyle != 2 && (clockRect.contains(downX, downY) || dateRect.contains(downX, downY))) {
                enterClockEdit(dragging = true)
            } else {
                host?.gesture(S.G_LONG)
            }
        } else if (touchMode == M_NONE && !moved && k != null) {
            val e = AppRepo.get(k)
            if (e != null) {
                touchMode = M_MENU
                scrubbing = false
                // se levanta un poco (como iOS) mientras se abre su menú
                Mo.tune(pressSp, S.MO_PRESS, 500f, 0.5f)
                pressSp.to(1.08f)
                Hx.fire(this, S.HE_LONG)
                val r = RectF()
                if (inDock) {
                    val s = slotOfKey(k)
                    if (s != null) {
                        val sz = dockIconPx * s.scale
                        r.set(s.cx - sz / 2f, dockCy - dockH / 2f - sz * 0.3f, s.cx + sz / 2f, dockCy + dockH / 2f)
                    }
                } else {
                    homeIconRect(homeKeys.indexOf(k), r)
                }
                host?.showMenu(e, r, if (inDock) 1 else 0)
                updateDockTargets()
                ticker.kick()
            }
        }
    }

    private val edgeRun = object : Runnable {
        override fun run() {
            if (edgeDir == 0 || touchMode != M_DRAG) return
            val n = pageCount()
            val cur = pageSp.value.roundToInt()
            val t = (cur + edgeDir).coerceIn(0, n - 1)
            Mo.tune(pageSp, S.MO_PAGE, 220f, 0.84f)
            pageSp.to(t.toFloat())
            ticker.kick()
            handler.postDelayed(this, 750)
        }
    }

    private val clockRun = object : Runnable {
        override fun run() {
            if (updateClock()) invalidate()
            handler.postDelayed(this, msToNextMinute())
        }
    }

    init {
        textPaint.color = Color.WHITE
        textPaint.textSize = dp(11.5f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.setShadowLayer(dp(3f), 0f, dp(1f), 0x99000000.toInt())
        clockPaint.color = Color.WHITE
        clockPaint.textSize = dp(76f)
        clockPaint.typeface = Typeface.create(Typeface.DEFAULT, 200, false)
        clockPaint.letterSpacing = -0.02f
        clockPaint.textAlign = Paint.Align.CENTER
        clockPaint.setShadowLayer(dp(10f), 0f, dp(2f), 0x66000000)
        datePaint.color = 0xF2FFFFFF.toInt()
        datePaint.textSize = dp(15f)
        datePaint.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        datePaint.textAlign = Paint.Align.CENTER
        datePaint.setShadowLayer(dp(6f), 0f, dp(1f), 0x66000000)
        hintPaint.color = Color.WHITE
        ticker.add(pageSp)
        ticker.add(enterSp)
        ticker.add(pressSp)
        ticker.add(editSp)
        for (x in extraSprings) ticker.add(x)
        ticker.add(iconStepper)
        ckX.snap(S.f(S.CK_DX))
        ckY.snap(S.f(S.CK_DY))
        ckS.snap(S.p(S.CK_SCALE))
        chipText.color = Color.WHITE
        chipText.textSize = dp(13.5f)
        chipText.textAlign = Paint.Align.CENTER
        chipText.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        sheetTitle.color = Color.WHITE
        sheetTitle.textSize = dp(21f)
        sheetTitle.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        sheetSmall.color = Color.WHITE
        sheetSmall.textSize = dp(12.5f)
        sheetSmall.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        stackPaint.color = Color.WHITE
        stackPaint.textSize = dp(96f)
        stackPaint.typeface = Typeface.create(Typeface.DEFAULT, 300, false)
        stackPaint.letterSpacing = -0.03f
        stackPaint.setShadowLayer(dp(10f), 0f, dp(2f), 0x55000000)
        editLabel.color = Color.WHITE
        editLabel.textSize = dp(12.5f)
        editLabel.textAlign = Paint.Align.CENTER
        editLabel.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        editLabel.setShadowLayer(dp(3f), 0f, dp(1f), 0x88000000.toInt())
        updateClock()
    }

    // ---------------------------------------------------------------- API
    fun setInsets(top: Float, bottom: Float) {
        insetTop = top
        insetBottom = bottom
        relayout()
        invalidate()
    }

    fun setDrawerProgress(p0: Float) {
        val p = if (p0 < 0.001f) 0f else p0
        if (abs(p - drawerP) < 0.0005f && (p == 0f) == (drawerP == 0f)) return
        drawerP = p
        // El inicio se encoge y se desvanece como una sola "foto" en la GPU (capa de hardware):
        // así no se vuelve a dibujar entero (reloj, sombras de texto, cristal) en cada fotograma
        // mientras se abre la vista de apps. Antes eso hacía la apertura lenta y a tirones.
        val s = 1f - S.p(S.H_SCALE_PANEL) * p
        pivotX = width / 2f
        pivotY = height / 2f
        scaleX = s
        scaleY = s
        val want = if (p > 0f) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE
        if (layerType != want) setLayerType(want, null)
    }

    fun setRunning(set: Set<String>) {
        running = set
        for (s in slots) {
            val k = s.key
            s.running = k != null && AppRepo.get(k)?.pkg in set
            setRunDot(s)
        }
        invalidate()
    }

    /** Vuelve a leer inicio y dock desde los ajustes guardados. */
    fun reload() {
        val old = homeKeys
        homeKeys = ArrayList()
        // los huecos se conservan; una app aún sin cargar también (no se dibuja hasta que exista)
        for (k in Prefs.homeKeys()) if (k == HomeSlots.BLANK || k !in homeKeys) homeKeys.add(k)
        diffHome(old, homeKeys)
        dockKeys = ArrayList()
        for (k in Prefs.dockKeys()) if (AppRepo.get(k) != null && k !in dockKeys) dockKeys.add(k)
        relayout()
        val maxPage = (pageCount() - 1).toFloat()
        if (pageSp.value > maxPage) pageSp.snap(maxPage)
        invalidate()
    }

    val isEditing: Boolean get() = editSp.target > 0.5f

    /** Modo edición estilo One UI: las páginas se encogen y aparece la barra inferior. */
    fun enterEdit() {
        if (isEditing) return
        editGesture = false
        Mo.tune(editSp, S.MO_EDIT_IN, 260f, 0.78f)
        editSp.to(1f)
        Hx.fire(this, S.HE_LONG)
        ticker.kick()
    }

    fun exitEdit(now: Boolean = false) {
        if (!isEditing && editSp.value <= 0.001f) return
        if (now) {
            editSp.snap(0f)
            // si estaba en la tarjeta "Añadir pantalla", vuelve a la última pantalla real
            val lastNow = (realPages() - 1).toFloat()
            if (pageSp.value > lastNow) pageSp.snap(lastNow)
            invalidate()
            return
        }
        Mo.tune(editSp, S.MO_EDIT_OUT, 300f, 1f)
        editSp.to(0f)
        val last = (realPages() - 1).toFloat()
        if (pageSp.target > last) {
            Mo.tune(pageSp, S.MO_PAGE, 220f, 0.84f)
            pageSp.to(last)
        }
        ticker.kick()
    }

    fun goToFirstPage() {
        val t = (S.i(S.H_DEFAULT_PAGE) - 1).coerceIn(0, pageCount() - 1).toFloat()
        if (abs(pageSp.value - t) > 0.01f) {
            Mo.tune(pageSp, S.MO_PAGE, 220f, 0.84f)
            pageSp.to(t)
            ticker.kick()
        }
    }

    fun playEnter() {
        Motion.enterTune(Prefs.returnAnim, enterSp)
        enterSp.snap(0f)
        enterSp.to(1f)
        ticker.kick()
        invalidate()
    }

    fun refreshStyle() {
        relayout()
        checkDockDesign()
        invalidate()
    }

    private fun dockDesignStamp(): Long {
        var h = S.i(S.K_SHAPE).toLong()
        h = h * 31 + S.i(S.K_DESIGN)
        h = h * 31 + (if (S.b(S.K_REFLECT)) 1 else 0) + (if (S.b(S.K_DOUBLE)) 2 else 0)
        for (p in intArrayOf(S.GP_TINT, S.GP_TINT2, S.GP_GRAD, S.GP_RIM_COLOR, S.GP_TINT_OP, S.GP_RIM_W)) h = h * 31 + S.i(S.gp(S.SF_DOCK, p))
        return h
    }

    /**
     * Si al volver de Ajustes el dock tiene otro diseño, se nota con una animación: el fondo nuevo
     * entra estirándose con rebote y los iconos se inflan uno tras otro (antes cambiaba de golpe).
     */
    private fun checkDockDesign() {
        val st = dockDesignStamp()
        val before = dockStamp
        dockStamp = st
        if (before == Long.MIN_VALUE || before == st || !S.b(S.K_SHOW)) return
        Mo.tune(dockMorph, S.MO_DOCK_MAG, 190f, 0.5f)
        dockMorph.snap(0f)
        dockMorph.to(1f)
        for ((i, s) in slots.withIndex()) {
            if (s.kind == 3) continue
            Mo.tune(s.gw, S.MO_DOCK_MAG, 240f, 0.45f)
            // cada icono empieza un poco más pequeño que el anterior: llegan en cascada
            s.gw.snap((0.55f - i * 0.05f).coerceAtLeast(0.2f))
            s.gw.to(1f)
        }
        ticker.kick()
    }

    // ---------------------------------------------------------------- Layout
    /** pantallas con contenido (o vacías añadidas a propósito) */
    private fun realPages(): Int {
        val c = if (cap < 1) 1 else cap
        return max(1, ceil(homeKeys.size / c.toFloat()).toInt())
    }

    /** en el modo edición hay una pantalla más al final: la tarjeta "Añadir pantalla" */
    private fun pageCount(): Int = realPages() + (if (isEditing) 1 else 0)

    // ---------------------------------------------------------------- Pantallas en el modo edición
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** pasa un toque de pantalla a coordenadas del contenido encogido del modo edición */
    private fun editScale(): Float = 1f - (1f - S.p(S.E_SCALE)) * editSp.value
    private fun toContentX(x: Float): Float = width / 2f + (x - width / 2f) / editScale()
    private fun toContentY(y: Float): Float = editPivotY() + (y - editPivotY()) / editScale()

    /** centro del botón "Quitar pantalla" (arriba a la derecha del marco) */
    private fun removePageX(): Float = width - dp(10f) - dp(30f)
    private fun removePageY(): Float = insetTop + dp(10f) + dp(30f)

    /** añade una pantalla vacía al final */
    private fun addPage() {
        val c = max(1, cap)
        while (homeKeys.size % c != 0) homeKeys.add(HomeSlots.BLANK)
        repeat(c) { homeKeys.add(HomeSlots.BLANK) }
        saveHome()
        Hx.fire(this, S.HE_SETTING)
        android.widget.Toast.makeText(context, "Pantalla añadida", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** quita la pantalla [pg] con sus iconos (las apps siguen en la vista de apps) */
    private fun removePage(pg: Int) {
        if (realPages() <= 1) return
        val c = max(1, cap)
        val from = pg * c
        val to = min(homeKeys.size, from + c)
        var apps = 0
        for (i in from until to) if (homeKeys[i] != HomeSlots.BLANK) {
            apps++
            makeGhost(homeKeys[i])
        }
        for (i in to - 1 downTo from) homeKeys.removeAt(i)
        saveHome()
        val last = (realPages() - 1).toFloat()
        if (pageSp.target > last) {
            Mo.tune(pageSp, S.MO_PAGE, 220f, 0.84f)
            pageSp.to(last)
        }
        Hx.fire(this, S.HE_SETTING)
        val msg = if (apps == 0) "Pantalla quitada" else "Pantalla quitada · sus apps siguen en la vista de apps"
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun saveHome() {
        Prefs.setHomeKeys(homeKeys)
        relayout()
        ticker.kick()
        invalidate()
    }

    private fun relayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        iconPx = dp(Prefs.iconDp.toFloat())
        cols = Prefs.columns.coerceIn(3, 6)
        applyStyles()
        val dockOn = S.b(S.K_SHOW)
        val dockBottom = h - insetBottom - dp(S.f(S.K_BOTTOM))
        dockH = if (dockOn) dp(Prefs.dockDp.toFloat()) + dp(S.f(S.K_EXTRA_H)) else 0f
        dockCy = dockBottom - dockH / 2f
        dotsY = if (dockOn) dockBottom - dockH - dp(22f) else h - insetBottom - dp(28f)
        gridTop = insetTop + when (Prefs.clockStyle) {
            1 -> dp(232f)
            2 -> dp(28f)
            3 -> dp(296f)
            4 -> dp(262f)
            6 -> dp(112f)
            else -> if (Prefs.clockStyle >= 8) dp(ClockFaces.gridTopDp(Prefs.clockStyle)) else dp(146f)
        } + dp(S.f(S.H_TOP)) + (if (S.b(S.DT_GREETING) && Prefs.clockStyle != 2) dp(22f) else 0f)
        val avail = dotsY - dp(16f) - gridTop - dp(S.f(S.H_BOTTOM))
        val labelH = if (Prefs.labels) dp(Prefs.labelSize + 8f) else 0f
        val minCell = iconPx + labelH + dp(24f)
        // filas: automáticas según el espacio, o las que elija el usuario
        rows = if (Prefs.rows > 0) Prefs.rows.coerceIn(3, 8) else (avail / minCell).toInt().coerceIn(2, 7)
        // si las filas elegidas no caben, se reducen en vez de encimar iconos y nombres
        while (rows > 2 && avail / rows < dp(28f) + labelH + dp(8f)) rows--
        cap = cols * rows
        gridLeft = dp(S.f(S.H_SIDE))
        cellW = (w - gridLeft * 2f) / cols
        cellH = avail / rows
        // el icono nunca se sale de su celda aunque la cuadrícula sea muy densa
        iconPx = min(iconPx, min(cellW - dp(12f), cellH - labelH - dp(8f))).coerceAtLeast(dp(28f))
        // estilo de organización: mismas celdas, otra colocación (las apps no cambian de sitio)
        HomeLayout.setup(hg, S.i(S.H_LAYOUT), cols, rows, gridLeft, w - gridLeft, gridTop, avail, iconPx + labelH + dp(12f))
        iconPx = min(iconPx, min(hg.step - dp(12f), hg.cellH - labelH - dp(8f))).coerceAtLeast(dp(28f))
        labelCache.clear()
        rebuildDock()
        // primera vez: se apunta el diseño actual del dock (para animar si luego cambia)
        if (dockStamp == Long.MIN_VALUE) dockStamp = dockDesignStamp()
    }

    private fun applyStyles() {
        if (!isClockEditing) {
            ckX.snap(S.f(S.CK_DX))
            ckY.snap(S.f(S.CK_DY))
            ckS.snap(S.p(S.CK_SCALE))
        }
        Tx.apply(textPaint, S.TX_LABEL_HOME, dp(Prefs.labelSize.toFloat()), 400, dp(3f), 0x99)
        Tx.apply(clockPaint, S.TX_CLOCK, dp(76f), 200, dp(10f), 0x66, -0.02f)
        Tx.apply(datePaint, S.TX_DATE, dp(15f), 500, dp(6f), 0x66)
        Tx.apply(stackPaint, S.TX_HOURS, dp(96f), 300, dp(10f), 0x55, -0.03f)
        Tx.apply(minPaint, S.TX_MINUTES, dp(96f), 300, dp(10f), 0x55, -0.03f)
        Tx.apply(editLabel, S.TX_EDIT, dp(12.5f), 500, dp(3f), 0x88)
        Tx.apply(greetPaint, S.TX_GREETING, dp(17f), 500, dp(6f), 0x66)
        // diseños nuevos: Grande (dos tonos), Fino, Cápsula y Neón
        Tx.apply(bigH, S.TX_HOURS, dp(118f), 400, dp(10f), 0x55, -0.04f)
        Tx.apply(bigM, S.TX_MINUTES, dp(118f), 400, dp(10f), 0x55, -0.04f)
        if (S.i(S.tp(S.TX_MINUTES, S.TP_COLOR)) == 0) bigM.color = blend(bigH.color, OneColors.ACCENT, 0.55f)
        Tx.apply(thinPaint, S.TX_CLOCK, dp(92f), 100, dp(8f), 0x55, -0.01f)
        Tx.apply(thinSmall, S.TX_DATE, dp(13f), 600, dp(4f), 0x55, 0.16f)
        Tx.apply(pillTime, S.TX_CLOCK, dp(40f), 500, 0f, 0)
        Tx.apply(neonPaint, S.TX_CLOCK, dp(80f), 300, 0f, 0, -0.01f)
        Tx.apply(neonDate, S.TX_DATE, dp(15f), 500, 0f, 0)
        val neon = if (S.i(S.tp(S.TX_CLOCK, S.TP_COLOR)) == 0) 0xFF7CF5FF.toInt() else neonPaint.color
        neonPaint.color = neon
        neonDate.color = neon
        neonPaint.setShadowLayer(dp(16f), 0f, 0f, (neon and 0x00FFFFFF) or (0xDD shl 24))
        neonDate.setShadowLayer(dp(8f), 0f, 0f, (neon and 0x00FFFFFF) or (0xBB shl 24))
        faces.refresh()
        datePillW = 0f
        lastMinute = -1L
        labelCache.clear()
    }

    fun setBattery(pct: Int) {
        if (pct != battery) {
            battery = pct
            lastMinute = -1L
            invalidate()
        }
    }

    /** el dock se ensancha o encoge con rebote en vez de saltar de tamaño */
    private val dockShrink = SpringValue(0f, 260f, 14f, 0.3f)

    private fun copySpring(from: SpringValue, to: SpringValue) {
        to.value = from.value
        to.target = from.target
        to.velocity = from.velocity
        to.active = from.active
    }

    private fun restWidth(): Float {
        var t = 0f
        for (s in slots) t += (if (s.kind == 3) dp(14f) else dockUnit) * s.gw.value
        return t
    }

    private fun rebuildDock() {
        // estado anterior: así un icono que ya estaba no vuelve a aparecer, y uno nuevo crece con rebote
        val old = HashMap<String, Slot>()
        for (s in slots) old[s.kind.toString() + "|" + (s.key ?: "")] = s
        val hadDock = slots.isNotEmpty() && dockUnit > 0f
        val oldRest = if (hadDock) restWidth() + dockShrink.value else 0f
        slots.clear()
        val lp = S.b(S.K_LAUNCHPAD)
        val rc = S.b(S.K_RECENTS)
        val lpRight = S.b(S.K_LP_RIGHT)
        if (lp && !lpRight) slots.add(Slot(1, null))
        for (k in dockKeys) if (AppRepo.get(k) != null) slots.add(Slot(0, k))
        // el separador solo va ENTRE dos grupos: si no hay nada a su izquierda, sobraría (una raya suelta)
        if ((rc || (lp && lpRight)) && S.b(S.K_SEP) && slots.isNotEmpty()) slots.add(Slot(3, null))
        if (lp && lpRight) slots.add(Slot(1, null))
        if (rc) slots.add(Slot(2, null))
        ticker.springs.clear()
        ticker.springs.add(pageSp)
        ticker.springs.add(enterSp)
        ticker.springs.add(pressSp)
        ticker.springs.add(editSp)
        ticker.springs.add(dockShrink)
        for (x in extraSprings) ticker.springs.add(x)
        ticker.springs.add(designScroll)
        ticker.springs.add(iconStepper)
        pressSlot = null
        tickSlot = null
        var grew = false
        for (s in slots) {
            val prev = old[s.kind.toString() + "|" + (s.key ?: "")]
            if (prev != null) {
                copySpring(prev.gw, s.gw)
                copySpring(prev.sc, s.sc)
                copySpring(prev.by, s.by)
                copySpring(prev.bs, s.bs)
                copySpring(prev.br, s.br)
                copySpring(prev.bx, s.bx)
                copySpring(prev.run, s.run)
            } else {
                // icono nuevo (p. ej. las apps terminan de cargar al arrancar): crece desde pequeño con rebote
                Mo.tune(s.gw, S.MO_DOCK_MAG, 240f, 0.42f)
                s.gw.snap(0.15f)
                s.gw.to(1f)
                grew = true
            }
            ticker.springs.add(s.gw)
            ticker.springs.add(s.run)
            ticker.springs.add(s.sc)
            ticker.springs.add(s.by)
            ticker.springs.add(s.bs)
            ticker.springs.add(s.br)
            ticker.springs.add(s.bx)
            if (Prefs.dockMode == 1) Mo.tune(s.sc, S.MO_DOCK_MAG, 210f, 0.42f) else Mo.tune(s.sc, S.MO_DOCK_MAG, 520f, 0.95f)
        }
        layoutDock()
        // si el dock perdió iconos, la cápsula se encoge con rebote desde su ancho anterior
        val newRest = restWidth()
        if (hadDock && oldRest > newRest + 1f) {
            Mo.tune(dockShrink, S.MO_DOCK_MAG, 240f, 0.45f)
            dockShrink.value = oldRest - newRest
            dockShrink.velocity = 0f
            dockShrink.to(0f)
            grew = true
        }
        // si el dock se reconstruye a mitad de una animación (p. ej. al volver de Ajustes), los resortes
        // copiados siguen activos: el bucle tiene que seguir para que no se queden congelados a medias
        if (grew || slots.any { it.gw.active || it.sc.active || it.by.active || it.bs.active || it.br.active || it.bx.active || it.run.active }) ticker.kick()
    }

    private fun layoutDock() {
        val w = width.toFloat()
        var icons = 0
        for (s in slots) if (s.kind != 3) icons++
        val sepW = dp(14f)
        val maxUnit = (w - dp(36f) - sepW) / max(1, icons)
        dockUnit = min(dp(Prefs.dockDp.toFloat()) + dp(8f), maxUnit)
        dockIconPx = min(dp(Prefs.dockDp.toFloat()), dockUnit - dp(6f))
        var total = 0f
        for (s in slots) total += if (s.kind == 3) sepW else dockUnit
        restTotal = total
        var x = (w - total) / 2f
        for (s in slots) {
            val sw = if (s.kind == 3) sepW else dockUnit
            s.rest = x + sw / 2f
            s.cx = s.rest
            x += sw
        }
        for (s in slots) {
            val k = s.key
            s.running = k != null && AppRepo.get(k)?.pkg in running
            setRunDot(s)
        }
    }

    private fun setRunDot(s: Slot) {
        val t = if (s.running) 1f else 0f
        if (s.run.target != t) {
            Mo.tune(s.run, S.MO_PRESS, 380f, 0.5f)
            s.run.to(t)
            ticker.kick()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        relayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumeClock()
    }

    fun resumeClock() {
        handler.removeCallbacks(clockRun)
        if (updateClock()) invalidate()
        handler.postDelayed(clockRun, msToNextMinute())
    }

    /** el reloj se despierta justo al empezar el minuto siguiente (antes podía llegar hasta 20 s tarde) */
    private fun msToNextMinute(): Long = 60_000L - System.currentTimeMillis() % 60_000L + 40L

    fun pauseClock() {
        handler.removeCallbacks(clockRun)
    }

    fun launchpadX(): Float {
        for (s in slots) if (s.kind == 1) return s.rest
        return width / 2f
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        ticker.stop()
        super.onDetachedFromWindow()
    }

    private fun updateClock(): Boolean {
        val now = System.currentTimeMillis()
        val minute = now / 60000L
        if (minute == lastMinute) return false
        lastMinute = minute
        val loc = Locale.getDefault()
        val d = Date(now)
        val is24 = when (S.i(S.CK_FORMAT)) {
            1 -> false
            2 -> true
            else -> DateFormat.is24HourFormat(context)
        }
        // cero a la izquierda: automático = 24 h con cero (09:05), 12 h sin cero (9:05)
        val zero = when (S.i(S.CK_ZERO)) { 1 -> true; 2 -> false; else -> is24 }
        val hp = if (is24) (if (zero) "HH" else "H") else (if (zero) "hh" else "h")
        val tp = hp + ":mm" + (if (!is24 && S.b(S.CK_AMPM)) " a" else "")
        val cal = java.util.Calendar.getInstance()
        prevHour24 = curHour24
        prevMinute = curMinute
        curHour24 = cal.get(java.util.Calendar.HOUR_OF_DAY)
        curMinute = cal.get(java.util.Calendar.MINUTE)
        val oldTime = timeStr
        val oldHour = hourStr
        val oldMin = minStr
        timeStr = SimpleDateFormat(tp, loc).format(d)
        if (!S.b(S.CK_BLINK)) timeStr = timeStr.replace(':', ' ')
        else if (S.i(S.CK_SEPCH) == 1) timeStr = timeStr.replace(':', '·')
        timeStr = Tx.str(S.TX_CLOCK, timeStr)
        hourStr = SimpleDateFormat(if (is24) (if (zero) "HH" else "H") else (if (zero) "hh" else "h"), loc).format(d)
        minStr = SimpleDateFormat("mm", loc).format(d)
        if (oldTime.isNotEmpty() && oldTime != timeStr) {
            prevTime = oldTime
            prevHour = oldHour
            prevMin = oldMin
            Mo.tune(clockSp, S.MO_PAGE, 160f, 0.8f)
            clockSp.snap(0f)
            clockSp.to(1f)
            ticker.kick()
        }
        val dp0 = when (S.i(S.DT_FORMAT)) {
            1 -> DateFormat.getBestDateTimePattern(loc, "EEEdMMM")
            2 -> "dd/MM"
            3 -> DateFormat.getBestDateTimePattern(loc, "dMMMM")
            4 -> "EEEE"
            5 -> "EEEE dd/MM/yyyy"
            else -> DateFormat.getBestDateTimePattern(loc, "EEEEdMMMM")
        }
        dateStr = SimpleDateFormat(dp0, loc).format(d)
        dateStr = dateStr.replaceFirstChar { if (it.isLowerCase()) it.titlecase(loc) else it.toString() }
        if (S.b(S.DT_BATTERY) && battery >= 0) dateStr += "  ·  $battery %"
        dateStr = Tx.str(S.TX_DATE, dateStr)
        val hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        greetStr = Tx.str(S.TX_GREETING, if (hr in 5..11) "Buenos días" else if (hr in 12..19) "Buenas tardes" else "Buenas noches")
        datePillW = 0f
        fillInfo(d, loc, is24, zero)
        return true
    }

    /** rellena lo que usan los diseños 8-20: palabras, día del mes, segunda ciudad... */
    private fun fillInfo(d: Date, loc: Locale, is24: Boolean, zero: Boolean) {
        info.time = timeStr
        info.prevTime = prevTime
        info.hour = hourStr
        info.prevHour = prevHour
        info.min = minStr
        info.prevMin = prevMin
        info.date = dateStr
        info.h24 = curHour24
        info.m = curMinute
        info.prevH24 = prevHour24
        info.prevM = prevMinute
        info.dayNum = SimpleDateFormat("d", loc).format(d)
        info.weekday = SimpleDateFormat("EEEE", loc).format(d).replaceFirstChar { it.titlecase(loc) }
        info.monthYear = SimpleDateFormat("MMMM yyyy", loc).format(d)
        val (w1, w2) = ClockFaces.words(curHour24, curMinute)
        if (w1 != info.words1 || w2 != info.words2) {
            info.prevWords1 = info.words1
            info.prevWords2 = info.words2
            info.words1 = w1
            info.words2 = w2
        }
        val zi = S.i(S.CK_ZONE2).coerceIn(0, ClockFaces.ZONES.size - 1)
        val tz = java.util.TimeZone.getTimeZone(ClockFaces.ZONES[zi])
        val f2 = SimpleDateFormat(if (is24) (if (zero) "HH:mm" else "H:mm") else (if (zero) "hh:mm" else "h:mm"), loc)
        f2.timeZone = tz
        info.zone2 = f2.format(d)
        info.zone2Label = ClockFaces.ZONE_NAMES[zi]
        val diff = (tz.getOffset(d.time) - java.util.TimeZone.getDefault().getOffset(d.time)) / 3_600_000f
        info.zone2Diff = when {
            abs(diff) < 0.01f -> "misma hora"
            diff == diff.toInt().toFloat() -> (if (diff > 0) "+" else "−") + abs(diff.toInt()) + " h"
            else -> (if (diff > 0) "+" else "−") + String.format(loc, "%.1f", abs(diff)) + " h"
        }
    }

    /** el icono sube un poco dentro de su celda para dejar sitio al nombre */
    private fun labelLift(): Float = (if (Prefs.labels) dp(Prefs.labelSize * 0.75f) else 0f) - dp(S.f(S.H_ICON_DY))

    /** centro de la celda [local] (0 until cap) según el estilo de organización */
    private fun cellX(local: Int): Float = HomeLayout.x(hg, local % cols, local / cols)
    private fun cellMidY(local: Int): Float = HomeLayout.y(hg, local % cols, local / cols)
    /** centro del icono de la celda (algo más arriba que el de la celda, por el nombre) */
    private fun cellIconY(local: Int): Float = cellMidY(local) - labelLift()

    /**
     * Celda de la pantalla bajo (x, y). Con [strict] solo si el punto cae dentro de una celda
     * (si no, -1); sin él, la más cercana. Sirve para cualquier estilo (Panal, Sonrisa, Dividida...).
     */
    private fun cellAt(x: Float, y: Float, strict: Boolean): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        val hx = hg.step * 0.5f
        val hy = hg.cellH * 0.5f
        for (i in 0 until cap) {
            val dx = x - cellX(i)
            val dy = y - cellMidY(i)
            if (strict && (abs(dx) > hx || abs(dy) > hy)) continue
            val d = dx * dx + dy * dy
            if (d < bd) {
                bd = d
                best = i
            }
        }
        return best
    }

    private fun homeIconRect(idx: Int, out: RectF) {
        if (idx < 0) {
            out.set(0f, 0f, 0f, 0f)
            return
        }
        val local = idx % cap
        val cx = cellX(local)
        val cy = cellIconY(local)
        out.set(cx - iconPx / 2f, cy - iconPx / 2f, cx + iconPx / 2f, cy + iconPx / 2f)
    }

    private fun dockTopY(): Float = dockCy - dockH / 2f - dp(8f)

    // ---------------------------------------------------------------- Iconos que se "hunden" al tocar
    private fun press(key: String?, slot: Slot?) {
        pressKey = key
        pressSlot = slot
        pressArea = if (slot != null) S.IA_DOCK else S.IA_HOME
        Mo.tune(pressSp, S.MO_PRESS, 700f, 1f)
        pressSp.to(1f - IconFx.pressDepth(pressArea))
        if (key != null || slot != null) Hx.fire(this, S.HE_ICON)
        ticker.kick()
    }

    private fun unpress() {
        if (pressKey == null && pressSlot == null && pressSp.target >= 1f) return
        Mo.tune(pressSp, S.MO_RELEASE, 420f, IconFx.releaseRatio(pressArea))
        pressSp.to(1f)
        ticker.kick()
        if (!pressSp.active) {
            pressKey = null
            pressSlot = null
        }
    }

    private fun pressScaleFor(key: String?): Float =
        if (key != null && key == pressKey) pressSp.value else 1f

    // ---------------------------------------------------------------- Física del dock
    private fun updateDockTargets() {
        val amt = Prefs.magnify / 100f * 0.9f
        val mode = Prefs.dockMode
        for (s in slots) {
            if (s.kind == 3) continue
            val rad = S.p(S.DOCKS_BASE + mode * 2).coerceAtLeast(0.1f)
            val t = if (scrubbing) Motion.dockScale(mode, (scrubX - s.rest) / (dockUnit * rad), amt).coerceAtMost(S.p(S.K_MAX_SCALE)) else 1f
            if (abs(t - s.sc.target) > 0.002f) s.sc.to(t)
        }
    }

    private fun onFrame(dt: Float): Boolean {
        if (scrubbing) updateDockTargets()
        // la inclinación del icono arrastrado vuelve sola a 0 cuando el dedo se para
        if (touchMode == M_DRAG && abs(dragTilt.target) > 0.05f) dragTilt.to(dragTilt.target * 0.82f)
        // (con target 1.08 el icono está "levantado" por una pulsación larga: no se suelta aún)
        if (!pressSp.active && pressSp.target == 1f) {
            pressKey = null
            pressSlot = null
        }
        invalidate()
        return scrubbing || isClockEditing
    }

    private fun slotOfKey(k: String): Slot? {
        for (s in slots) if (s.key == k) return s
        return null
    }

    private fun slotAt(x: Float): Slot? {
        var best: Slot? = null
        var bd = Float.MAX_VALUE
        for (s in slots) {
            if (s.kind == 3) continue
            val d = abs(x - s.cx)
            if (d < bd) {
                bd = d
                best = s
            }
        }
        return if (best != null && bd < dockUnit * best.scale * 0.62f) best else null
    }

    private fun bounce(i: Int) {
        val s = slots[i]
        val mode = Prefs.bounceAnim
        val f = S.p(S.BOUNCES_BASE + mode)
        when (mode) {
            0 -> { Mo.tune(s.by, S.MO_B_JUMP, 120f, 0.23f); s.by.velocity = 6.5f * f; s.by.to(0f) }
            1 -> { Mo.tune(s.bs, S.MO_B_PULSE, 200f, 0.28f); s.bs.velocity = 5f * f; s.bs.to(0f) }
            2 -> { Mo.tune(s.br, S.MO_B_SPIN, 90f, 0.37f); s.br.velocity = 3.4f * f; s.br.to(0f) }
            3 -> {
                for (j in slots.indices) {
                    if (slots[j].kind == 3) continue
                    val d = abs(j - i)
                    val v = max(1.8f, 6.5f - d * 1.3f) * f
                    handler.postDelayed({
                        if (j < slots.size) {
                            Mo.tune(slots[j].by, S.MO_B_WAVE, 120f, 0.23f)
                            slots[j].by.velocity = v
                            slots[j].by.to(0f)
                            ticker.kick()
                        }
                    }, d * 70L)
                }
            }
            else -> { Mo.tune(s.bx, S.MO_B_SHAKE, 160f, 0.24f); s.bx.velocity = 5.5f * f; s.bx.to(0f) }
        }
        ticker.kick()
    }

    // ---------------------------------------------------------------- Dibujo
    override fun onDraw(c: Canvas) {
        drawBackdrop(c)
        c.save()
        val enterOn = enterSp.value < 0.9995f || enterSp.active
        val ed = editSp.value
        if (ed > 0.001f) {
            val s = 1f - (1f - S.p(S.E_SCALE)) * ed
            c.save()
            c.scale(s, s, width / 2f, editPivotY())
        }
        drawClock(c, enterOn)
        drawPages(c, enterOn)
        drawDots(c)
        if (ed > 0.001f) c.restore()
        if (ed < 0.999f) {
            c.save()
            c.translate(0f, ed * dp(70f))
            // con el menú del reloj abierto el dock se aparta (si no, se transparenta por el panel)
            dockFade = (1f - clamp01(ed * 1.4f)) * (1f - clamp01(clockEd.value * 1.6f))
            drawDock(c, enterOn)
            dockFade = 1f
            c.restore()
        }
        if (ed > 0.001f) drawEditBar(c, ed)
        drawDrag(c)
        if (clockEd.value > 0.001f) drawClockEditor(c, clockEd.value)
        c.restore()
        if (S.b(S.P_FPS)) drawFps(c)
    }

    // ================================================================ Editor del reloj
    /** curva de "burbuja": pasa un poco de su tamaño y vuelve (como un globo que se infla) */
    private fun pop(q: Float): Float {
        val t = clamp01(q) - 1f
        return 1f + 2.7f * t * t * t + 1.7f * t * t
    }

    private fun is24(): Boolean = when (S.i(S.CK_FORMAT)) {
        1 -> false
        2 -> true
        else -> DateFormat.is24HourFormat(context)
    }

    private fun zeroOn(): Boolean = when (S.i(S.CK_ZERO)) { 1 -> true; 2 -> false; else -> is24() }

    private fun designName(st: Int): String {
        val j = designOrder.indexOf(st)
        return if (j >= 0) designNames[j] else ""
    }

    private fun hit(r: RectF, id: Int) {
        if (hitN < hitRects.size) {
            hitRects[hitN].set(r)
            hitIds[hitN] = id
            hitN++
        }
    }

    private fun hitAt(x: Float, y: Float): Int {
        for (i in hitN - 1 downTo 0) if (hitRects[i].contains(x, y)) return hitIds[i]
        return -1
    }

    /** escala de una zona: se hunde al tocarla y late al elegirla */
    private fun itemScale(id: Int): Float =
        (if (id == shPress && shMode == 0) 0.94f else 1f) * (if (id == pulseIdx) chipPulse.value else 1f)

    /** pastilla de cristal (rellena de color si está elegida) */
    private fun pill(c: Canvas, r: RectF, label: String, sel: Boolean, col: Int, a: Float, id: Int) {
        val s = itemScale(id)
        c.save()
        c.scale(s, s, r.centerX(), r.centerY())
        if (sel) {
            chipFill.color = col
            chipFill.alpha = (215 * a).toInt()
            c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, chipFill)
        }
        glass.draw(c, r, r.height() / 2f, a * (if (sel) 0.5f else 0.9f), false, S.SF_EDIT_BTN)
        chipText.alpha = (255 * a).toInt()
        c.drawText(label, r.centerX(), r.centerY() + dp(5f), chipText)
        c.restore()
        hit(r, id)
    }

    /** fila(s) de pastillas que se ajustan al ancho; devuelve la y donde termina */
    private fun pillRow(c: Canvas, l: Float, r: Float, y0: Float, labels: Array<String>, from: Int, to: Int, idBase: Int, a: Float): Float {
        val h = dp(38f)
        val gap = dp(8f)
        var y = y0
        var i = from
        while (i <= to) {
            var rowW = chipText.measureText(labels[i]) + dp(30f)
            var j = i + 1
            while (j <= to && rowW + gap + chipText.measureText(labels[j]) + dp(30f) <= r - l) {
                rowW += gap + chipText.measureText(labels[j]) + dp(30f)
                j++
            }
            var x = l
            for (k in i until j) {
                val w = chipText.measureText(labels[k]) + dp(30f)
                tmpR.set(x, y, x + w, y + h)
                pill(c, tmpR, labels[k], fmtSelected(idBase + k), OneColors.ACCENT, a, idBase + k)
                x += w + gap
            }
            y += h + dp(10f)
            i = j
        }
        return y
    }

    private fun fmtSelected(id: Int): Boolean = when (id) {
        4000, 4001, 4002 -> S.i(S.CK_FORMAT) == id - 4000
        4003 -> zeroOn()
        4004 -> S.i(S.CK_SEPCH) == 1
        4005 -> S.b(S.CK_AMPM)
        4006 -> S.b(S.DT_SHOW)
        4007 -> S.b(S.DT_GREETING)
        4008 -> S.b(S.DT_PILL)
        5000, 5001, 5002 -> S.i(S.CK_ALIGN) == id - 5000
        else -> false
    }

    /** círculo de color de la paleta (el automático lleva una "A") */
    private fun colorDot(c: Canvas, cx: Float, cy: Float, d: Float, pal: Int, sel: Boolean, a: Float, id: Int) {
        val s = itemScale(id)
        val rad = d / 2f * s
        chipFill.color = if (pal == 0) Color.WHITE else S.PAL[pal]
        chipFill.alpha = (240 * a).toInt()
        c.drawCircle(cx, cy, rad - dp(3f), chipFill)
        if (pal == 0) {
            chipText.color = 0xFF1B2440.toInt()
            chipText.alpha = (255 * a).toInt()
            c.drawText("A", cx, cy + dp(5f), chipText)
            chipText.color = Color.WHITE
        }
        ringPaint.color = Color.WHITE
        ringPaint.strokeWidth = if (sel) dp(2.5f) else dp(1f)
        ringPaint.alpha = ((if (sel) 255 else 90) * a).toInt()
        c.drawCircle(cx, cy, if (sel) rad + dp(1.5f) else rad - dp(3f), ringPaint)
        tmpR2.set(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f)
        hit(tmpR2, id)
    }

    private fun colorRow(c: Canvas, l: Float, r: Float, y0: Float, idBase: Int, current: Int, a: Float): Float {
        val d = dp(34f)
        val gap = dp(9f)
        val perRow = max(1, ((r - l + gap) / (d + gap)).toInt())
        var y = y0 + d / 2f
        for (n in paletteMain.indices) {
            val col = n % perRow
            if (n > 0 && col == 0) y += d + dp(8f)
            val pal = paletteMain[n]
            colorDot(c, l + col * (d + gap) + d / 2f, y, d, pal, current == pal, a, idBase + pal)
        }
        return y + d / 2f + dp(6f)
    }

    /** miniatura en vivo de un diseño: el reloj real dibujado a escala dentro de [r] */
    private fun thumbClock(c: Canvas, st: Int, r: RectF, a: Float) {
        saveClockR.set(clockRect)
        saveDateR.set(dateRect)
        val b = thumbBounds[st]
        if (b.isEmpty) b.set(0f, insetTop, width.toFloat(), insetTop + dp(if (st >= 8) ClockFaces.gridTopDp(st) else 220f))
        val k = min(r.width() / max(1f, b.width()), r.height() / max(1f, b.height())) * 0.86f
        c.save()
        c.clipRect(r)
        c.translate(r.centerX(), r.centerY())
        c.scale(k, k)
        c.translate(-b.centerX(), -b.centerY())
        styleOverride = st
        thumb = true
        drawClockRaw(c, false)
        thumb = false
        styleOverride = -1
        c.restore()
        // se recuerda lo que ocupa de verdad para centrarlo mejor en el próximo fotograma
        if (!clockRect.isEmpty) {
            tmpR2.set(clockRect)
            if (!dateRect.isEmpty) tmpR2.union(dateRect)
            b.set(tmpR2)
        }
        clockRect.set(saveClockR)
        dateRect.set(saveDateR)
    }

    /** el menú dock: panel de cristal que sube desde abajo con rebote */
    private fun drawSheet(c: Canvas, ce: Float, a: Float) {
        hitN = 0
        val sh = min(dp(392f), height * 0.56f)
        val left = dp(10f)
        val right = width - dp(10f)
        val bottom = height - insetBottom - dp(10f)
        // ce pasa un poco de 1 con el rebote: el menú sube de más y se asienta, como un dock
        val lift = (1f - ce) * (sh + dp(60f)) + max(0f, sheetPull.value)
        sheetRect.set(left, bottom - sh + lift, right, bottom + lift)
        chipFill.color = 0xFF0B1124.toInt()
        chipFill.alpha = (175 * a).toInt()
        c.drawRoundRect(sheetRect, dp(34f), dp(34f), chipFill)
        glass.draw(c, sheetRect, dp(34f), a, true, S.SF_DOCK)
        c.save()
        c.clipRect(sheetRect)
        val top = sheetRect.top
        // asa para tirar hacia abajo
        chipFill.color = Color.WHITE
        chipFill.alpha = (110 * a).toInt()
        tmpR.set(sheetRect.centerX() - dp(20f), top + dp(9f), sheetRect.centerX() + dp(20f), top + dp(14f))
        c.drawRoundRect(tmpR, dp(2.5f), dp(2.5f), chipFill)
        // título y diseño actual
        sheetTitle.textAlign = Paint.Align.LEFT
        sheetTitle.alpha = (255 * a).toInt()
        c.drawText("Reloj", left + dp(22f), top + dp(44f), sheetTitle)
        sheetSmall.textAlign = Paint.Align.LEFT
        sheetSmall.alpha = (170 * a).toInt()
        c.drawText(designName(Prefs.clockStyle), left + dp(30f) + sheetTitle.measureText("Reloj"), top + dp(43f), sheetSmall)
        // botón Listo
        val lw = chipText.measureText("Listo") + dp(34f)
        tmpR.set(right - dp(16f) - lw, top + dp(20f), right - dp(16f), top + dp(54f))
        pill(c, tmpR, "Listo", true, 0xFF2FC26B.toInt(), a, 6000)
        // pestañas con indicador que se desliza y se estira
        val tl = left + dp(16f)
        val tr = right - dp(16f)
        val ty = top + dp(66f)
        val tb = ty + dp(40f)
        tmpR.set(tl, ty, tr, tb)
        glass.draw(c, tmpR, dp(20f), a * 0.7f, false, S.SF_EDIT_BTN)
        val segW = (tr - tl) / tabNames.size
        val stretch = min(0.35f, abs(tabSp.velocity) * 0.05f) * segW
        val ix = tl + segW * tabSp.value
        tmpR.set(ix + dp(3f) - stretch / 2f, ty + dp(3f), ix + segW - dp(3f) + stretch / 2f, tb - dp(3f))
        chipFill.color = OneColors.ACCENT
        chipFill.alpha = (225 * a).toInt()
        c.drawRoundRect(tmpR, tmpR.height() / 2f, tmpR.height() / 2f, chipFill)
        for (t in tabNames.indices) {
            val cx = tl + segW * (t + 0.5f)
            chipText.alpha = ((if (t == sheetTab) 255 else 170) * a).toInt()
            c.drawText(tabNames[t], cx, ty + dp(25f), chipText)
            tmpR.set(tl + segW * t, ty, tl + segW * (t + 1), tb)
            hit(tmpR, 1000 + t)
        }
        // contenido de la pestaña
        val ct = tb + dp(14f)
        val cb = sheetRect.bottom - dp(12f)
        val fa = clamp01(tabFade.value)
        val ca = a * fa
        c.save()
        c.translate((1f - fa) * dp(28f) * tabDir, 0f)
        when (sheetTab) {
            0 -> drawDesignsTab(c, tl, tr, ct, cb, ca)
            1 -> drawColorsTab(c, tl, tr, ct, ca)
            2 -> drawFormatTab(c, tl, tr, ct, ca)
            else -> drawPositionTab(c, tl, tr, ct, ca)
        }
        c.restore()
        c.restore()
    }

    /** carrusel de 2 filas con los 20 diseños, cada tarjeta con su miniatura en vivo */
    private fun drawDesignsTab(c: Canvas, tl: Float, tr: Float, ct: Float, cb: Float, a: Float) {
        val cw = dp(98f)
        val chh = dp(88f)
        val gap = dp(12f)
        val rowH = chh + dp(28f)
        val colsN = (designOrder.size + 1) / 2
        designScroll.min = 0f
        designScroll.max = max(0f, colsN * (cw + gap) - gap - (tr - tl))
        designScroll.dim = tr - tl
        c.save()
        c.clipRect(tl - dp(6f), ct - dp(6f), tr + dp(6f), cb)
        for (j in designOrder.indices) {
            val col = j / 2
            val row = j % 2
            val x0 = tl + col * (cw + gap) - designScroll.value
            if (x0 > tr + dp(6f) || x0 + cw < tl - dp(6f)) continue
            val y0 = ct + row * rowH
            val st = designOrder[j]
            val id = 2000 + st
            tmpR.set(x0, y0, x0 + cw, y0 + chh)
            val s = itemScale(id)
            c.save()
            c.scale(s, s, tmpR.centerX(), tmpR.centerY())
            // fondo de la tarjeta, como un fondo de pantalla en miniatura
            chipFill.color = 0xFF17224D.toInt()
            chipFill.alpha = (215 * a).toInt()
            c.drawRoundRect(tmpR, dp(20f), dp(20f), chipFill)
            tmpR2.set(tmpR.left + dp(6f), tmpR.top + dp(6f), tmpR.right - dp(6f), tmpR.bottom - dp(6f))
            cardR.set(tmpR)
            thumbClock(c, st, tmpR2, a)
            glass.draw(c, cardR, dp(20f), a * 0.5f, false, S.SF_EDIT_BTN)
            if (Prefs.clockStyle == st) {
                ringPaint.color = OneColors.ACCENT
                ringPaint.strokeWidth = dp(2.5f)
                ringPaint.alpha = (255 * a).toInt()
                c.drawRoundRect(cardR.left - dp(2f), cardR.top - dp(2f), cardR.right + dp(2f), cardR.bottom + dp(2f), dp(22f), dp(22f), ringPaint)
                chipFill.color = OneColors.ACCENT
                chipFill.alpha = (255 * a).toInt()
                c.drawCircle(cardR.right - dp(12f), cardR.top + dp(12f), dp(9f), chipFill)
                Glyphs.draw(c, Glyphs.CHECK, cardR.right - dp(12f), cardR.top + dp(12f), dp(13f), Color.WHITE, a)
            }
            sheetSmall.textAlign = Paint.Align.CENTER
            sheetSmall.alpha = ((if (Prefs.clockStyle == st) 255 else 200) * a).toInt()
            c.drawText(designNames[j], cardR.centerX(), cardR.bottom + dp(17f), sheetSmall)
            c.restore()
            hit(cardR, id)
        }
        c.restore()
    }

    private fun sectionTitle(c: Canvas, text: String, x: Float, y: Float, a: Float) {
        sheetSmall.textAlign = Paint.Align.LEFT
        sheetSmall.alpha = (175 * a).toInt()
        c.drawText(text, x, y, sheetSmall)
    }

    private fun drawColorsTab(c: Canvas, tl: Float, tr: Float, ct: Float, a: Float) {
        sectionTitle(c, "Color de la hora", tl + dp(4f), ct + dp(12f), a)
        val y = colorRow(c, tl, tr, ct + dp(22f), 3000, S.i(S.tp(S.TX_CLOCK, S.TP_COLOR)), a)
        sectionTitle(c, "Segundo tono · minutos y agujas", tl + dp(4f), y + dp(14f), a)
        colorRow(c, tl, tr, y + dp(24f), 3100, S.i(S.tp(S.TX_MINUTES, S.TP_COLOR)), a)
    }

    private fun drawFormatTab(c: Canvas, tl: Float, tr: Float, ct: Float, a: Float) {
        var y = pillRow(c, tl, tr, ct, fmtLabels, 0, 2, 4000, a)
        y = pillRow(c, tl, tr, y, fmtLabels, 3, 5, 4000, a)
        y = pillRow(c, tl, tr, y, fmtLabels, 6, 8, 4000, a)
        val zi = S.i(S.CK_ZONE2).coerceIn(0, ClockFaces.ZONE_NAMES.size - 1)
        val label = "Segunda ciudad: " + ClockFaces.ZONE_NAMES[zi]
        val w = chipText.measureText(label) + dp(30f)
        tmpR.set(tl, y, tl + w, y + dp(38f))
        pill(c, tmpR, label, false, OneColors.ACCENT, a, 4009)
    }

    private fun drawPositionTab(c: Canvas, tl: Float, tr: Float, ct: Float, a: Float) {
        sectionTitle(c, "Alineación", tl + dp(4f), ct + dp(12f), a)
        var x = tl
        val y = ct + dp(22f)
        for (k in alignLabels.indices) {
            val w = chipText.measureText(alignLabels[k]) + dp(30f)
            tmpR.set(x, y, x + w, y + dp(38f))
            val id = 5000 + alignValues[k]
            pill(c, tmpR, alignLabels[k], fmtSelected(id), OneColors.ACCENT, a, id)
            x += w + dp(8f)
        }
        // deslizador de tamaño
        val sy = y + dp(80f)
        sectionTitle(c, "Tamaño · " + (ckS.target * 100f).toInt() + " %", tl + dp(4f), sy - dp(18f), a)
        sliderRect.set(tl + dp(12f), sy - dp(3f), tr - dp(12f), sy + dp(3f))
        chipFill.color = Color.WHITE
        chipFill.alpha = (60 * a).toInt()
        c.drawRoundRect(sliderRect, dp(3f), dp(3f), chipFill)
        val f = clamp01((ckS.value - 0.5f) / 1.5f)
        val tx = lerp(sliderRect.left, sliderRect.right, f)
        chipFill.color = OneColors.ACCENT
        chipFill.alpha = (255 * a).toInt()
        c.drawRoundRect(sliderRect.left, sliderRect.top, tx, sliderRect.bottom, dp(3f), dp(3f), chipFill)
        chipFill.color = Color.WHITE
        chipFill.alpha = (255 * a).toInt()
        c.drawCircle(tx, sy, dp(11f) * (if (shMode == 2) 1.18f else 1f), chipFill)
        tmpR.set(sliderRect.left - dp(12f), sy - dp(22f), sliderRect.right + dp(12f), sy + dp(22f))
        hit(tmpR, 6001)
        // restablecer
        val label = "Restablecer posición y tamaño"
        val w = chipText.measureText(label) + dp(30f)
        tmpR.set(tl, sy + dp(32f), tl + w, sy + dp(70f))
        pill(c, tmpR, label, false, OneColors.ACCENT, a, 6002)
        sectionTitle(c, "Arrastra el reloj de arriba para moverlo · pellizca para el tamaño", tl + dp(4f), sy + dp(96f), a)
    }

    private fun setSlider(x: Float) {
        val f = clamp01((x - sliderRect.left) / max(1f, sliderRect.width()))
        Mo.tune(ckS, S.MO_EDIT_IN, 260f, 0.55f)
        ckS.to(0.5f + 1.5f * f)
        ticker.kick()
    }

    private fun setTab(t: Int) {
        if (t == sheetTab) return
        tabDir = if (t > sheetTab) 1f else -1f
        sheetTab = t
        Mo.tune(tabSp, S.MO_EDIT_IN, 420f, 0.6f)
        tabSp.to(t.toFloat())
        Mo.tune(tabFade, S.MO_EDIT_IN, 320f, 0.9f)
        tabFade.snap(0f)
        tabFade.to(1f)
    }

    private fun drawClockEditor(c: Canvas, ce: Float) {
        val a = clamp01(ce)
        // 1) el resto del inicio se oscurece
        scrimPaint.color = Color.BLACK
        scrimPaint.alpha = (120 * a).toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        // 2) burbuja de cristal que "respira" alrededor del reloj
        if (!clockBox.isEmpty) {
            val t = android.os.SystemClock.uptimeMillis() / 1000f
            val breathe = 1f + 0.018f * kotlin.math.sin(t * 3.1f) * a
            val inflate = lerp(0.55f, 1f, ce) * breathe
            val pad = dp(18f)
            val bw = (clockBox.width() / 2f + pad) * inflate * (1f + clockSqu.value * 0.6f)
            val bh = (clockBox.height() / 2f + pad) * inflate * (1f - clockSqu.value * 0.6f)
            bubble.set(clockBox.centerX() - bw, clockBox.centerY() - bh, clockBox.centerX() + bw, clockBox.centerY() + bh)
            glass.draw(c, bubble, dp(34f), a, true, S.SF_EDIT_FRAME)
        }
        // 3) el reloj, nítido encima
        drawClock(c, false)
        // 4) tirador de tamaño en la esquina de la burbuja
        val hq = pop(clamp01((ce - 0.25f) / 0.6f))
        val hx = bubble.right
        val hy = bubble.bottom
        val hr = dp(19f) * hq
        if (!clockBox.isEmpty && hr > 0.5f) {
            capRect.set(hx - hr, hy - hr, hx + hr, hy + hr)
            glass.draw(c, capRect, hr, a, true, S.SF_EDIT_BTN)
            Glyphs.draw(c, Glyphs.RESIZE, hx, hy, dp(18f) * hq, Color.WHITE, a)
        }
        // 5) el menú dock con todas las opciones del reloj
        drawSheet(c, ce, a)
    }

    /** entra al editor; si viene de una pulsación larga, el mismo dedo ya puede arrastrar el reloj */
    fun enterClockEdit(dragging: Boolean = false) {
        if (Prefs.clockStyle == 2 || isEditing || isClockEditing) return
        isClockEditing = true
        host?.dismissMenu()
        Mo.tune(clockEd, S.MO_EDIT_IN, 300f, 0.5f)
        clockEd.to(1f)
        Mo.tune(clockPop, S.MO_EDIT_IN, 420f, 0.3f)
        clockPop.snap(0.86f)
        clockPop.to(1f)
        Hx.fire(this, S.HE_LONG)
        ceMode = if (dragging) 1 else 0
        ceMoved = false
        ceDownX = downX
        ceDownY = downY
        ceLastX = downX
        ceLastY = downY
        ceStartX = ckX.target
        ceStartY = ckY.target
        sheetPull.snap(0f)
        tabSp.snap(sheetTab.toFloat())
        tabFade.snap(1f)
        shPress = -1
        shMode = 0
        ticker.kick()
        invalidate()
    }

    fun exitClockEdit(now: Boolean = false) {
        if (!isClockEditing && clockEd.value <= 0.001f) return
        if (isClockEditing) saveClock()
        isClockEditing = false
        ceMode = 0
        clockSqu.to(0f)
        if (now) {
            clockEd.snap(0f)
            clockSqu.snap(0f)
            invalidate()
            return
        }
        Mo.tune(clockEd, S.MO_EDIT_OUT, 320f, 1f)
        clockEd.to(0f)
        ticker.kick()
    }

    private fun saveClock() {
        S.set(S.CK_DX, ckX.target)
        S.set(S.CK_DY, ckY.target)
        S.set(S.CK_SCALE, ckS.target * 100f)
    }

    /** rebote de globo de la burbuja (al soltar o al cambiar una opción) */
    private fun boing(strength: Float) {
        Mo.tune(clockPop, S.MO_EDIT_IN, 420f, 0.28f)
        clockPop.velocity = -strength
        clockPop.to(1f)
        ticker.kick()
    }

    private fun sheetAction(id: Int) {
        pulseIdx = id
        Mo.tune(chipPulse, S.MO_EDIT_IN, 520f, 0.3f)
        chipPulse.snap(0.82f)
        chipPulse.to(1f)
        Hx.fire(this, S.HE_SETTING)
        when {
            id == 6000 -> exitClockEdit()
            id in 1000..1003 -> setTab(id - 1000)
            id in 2000..2099 -> {
                val st = id - 2000
                if (Prefs.clockStyle != st) {
                    Prefs.clockStyle = st
                    relayout()
                    boing(4f)
                }
            }
            id in 3000..3099 -> {
                val pal = (id - 3000).toFloat()
                S.set(S.tp(S.TX_CLOCK, S.TP_COLOR), pal)
                S.set(S.tp(S.TX_HOURS, S.TP_COLOR), pal)
                applyStyles()
                boing(2.5f)
            }
            id in 3100..3199 -> {
                S.set(S.tp(S.TX_MINUTES, S.TP_COLOR), (id - 3100).toFloat())
                applyStyles()
                boing(2.5f)
            }
            id in 4000..4002 -> setFmt { S.set(S.CK_FORMAT, (id - 4000).toFloat()) }
            id == 4003 -> setFmt { S.set(S.CK_ZERO, if (zeroOn()) 2f else 1f) }
            id == 4004 -> setFmt { S.set(S.CK_SEPCH, if (S.i(S.CK_SEPCH) == 1) 0f else 1f) }
            id == 4005 -> setFmt { S.set(S.CK_AMPM, if (S.b(S.CK_AMPM)) 0f else 1f) }
            id == 4006 -> setFmt { S.set(S.DT_SHOW, if (S.b(S.DT_SHOW)) 0f else 1f) }
            id == 4007 -> {
                S.set(S.DT_GREETING, if (S.b(S.DT_GREETING)) 0f else 1f)
                relayout()
                boing(2.5f)
            }
            id == 4008 -> setFmt { S.set(S.DT_PILL, if (S.b(S.DT_PILL)) 0f else 1f) }
            id == 4009 -> setFmt { S.set(S.CK_ZONE2, ((S.i(S.CK_ZONE2) + 1) % ClockFaces.ZONES.size).toFloat()) }
            id in 5000..5002 -> {
                S.set(S.CK_ALIGN, (id - 5000).toFloat())
                boing(3f)
            }
            id == 6002 -> {
                ckX.to(0f)
                ckY.to(0f)
                ckS.to(1f)
                saveClock()
                boing(3f)
            }
        }
        ticker.kick()
        invalidate()
    }

    /** cambia un formato y vuelve a calcular la hora (con su animación de cambio) */
    private inline fun setFmt(change: () -> Unit) {
        change()
        lastMinute = -1L
        datePillW = 0f
        updateClock()
        boing(2.5f)
    }

    private fun sheetDown(ev: MotionEvent, x: Float, y: Float) {
        shVt?.recycle()
        shVt = VelocityTracker.obtain()
        shVt?.addMovement(ev)
        shMode = 0
        shPress = hitAt(x, y)
        shWasMoving = designScroll.isMoving
        designScroll.stop()
        if (shPress == 6001) {
            shMode = 2
            setSlider(x)
        }
    }

    private fun sheetMove(ev: MotionEvent, x: Float, y: Float) {
        shVt?.addMovement(ev)
        val dx = x - ceDownX
        val dy = y - ceDownY
        if (shMode == 0 && hypot(dx, dy) > slop) {
            shMode = when {
                sheetTab == 0 && abs(dx) > abs(dy) -> 1
                dy > 0f && abs(dy) > abs(dx) -> 3
                else -> 4
            }
            shPress = -1
        }
        when (shMode) {
            1 -> designScroll.drag(-(x - ceLastX))
            2 -> setSlider(x)
            3 -> sheetPull.snap(max(0f, dy))
        }
    }

    private fun sheetUp(ev: MotionEvent, up: Boolean, x: Float, y: Float) {
        shVt?.addMovement(ev)
        shVt?.computeCurrentVelocity(1000)
        val vx = shVt?.xVelocity ?: 0f
        val vy = shVt?.yVelocity ?: 0f
        when (shMode) {
            1 -> designScroll.release(-vx)
            2 -> {
                saveClock()
                boing(2f)
            }
            3 -> {
                Mo.tune(sheetPull, S.MO_EDIT_OUT, 360f, 0.7f)
                if (up && (sheetPull.value > dp(90f) || vy > 1500f)) exitClockEdit()
                sheetPull.to(0f)
            }
            0 -> if (up && !shWasMoving) {
                val id = hitAt(x, y)
                if (id >= 0 && id == shPress) sheetAction(id)
            }
        }
        shPress = -1
        shMode = 0
        shVt?.recycle()
        shVt = null
        ticker.kick()
    }

    private fun clockEditTouch(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        val d = resources.displayMetrics.density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ceDownX = x
                ceDownY = y
                ceLastX = x
                ceLastY = y
                ceMoved = false
                ceMode = 0
                if (clockEd.value > 0.5f && sheetRect.contains(x, y)) {
                    ceMode = 5
                    sheetDown(ev, x, y)
                    ticker.kick()
                    return true
                }
                if (ceMode == 0 && hypot(x - bubble.right, y - bubble.bottom) < dp(32f)) {
                    ceMode = 2
                    ceStartS = ckS.target
                    ceStartD = hypot(x - bubble.centerX(), y - bubble.centerY()).coerceAtLeast(dp(20f))
                } else if (ceMode == 0 && bubble.contains(x, y)) {
                    ceMode = 1
                    ceStartX = ckX.target
                    ceStartY = ckY.target
                    Mo.tune(clockPop, S.MO_PRESS, 600f, 1f)
                    clockPop.to(1.06f) // se "levanta" al tocarla
                    Hx.fire(this, S.HE_ICON)
                }
                ticker.kick()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2 && ceMode != 5) {
                    ceMode = 3
                    ceStartS = ckS.target
                    ceStartD = hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1)).coerceAtLeast(dp(20f))
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(x - ceDownX, y - ceDownY) > slop) ceMoved = true
                if (ceMode == 5) {
                    sheetMove(ev, x, y)
                    ceLastX = x
                    ceLastY = y
                    ticker.kick()
                    return true
                }
                when (ceMode) {
                    1 -> {
                        Mo.tune(ckX, S.MO_EDIT_IN, 260f, 0.55f)
                        Mo.tune(ckY, S.MO_EDIT_IN, 260f, 0.55f)
                        ckX.to((ceStartX + (x - ceDownX) / d).coerceIn(-180f, 180f))
                        ckY.to((ceStartY + (y - ceDownY) / d).coerceIn(-60f, 420f))
                        // se estira hacia donde va: horizontal ensancha, vertical alarga
                        val sdx = abs(x - ceLastX)
                        val sdy = abs(y - ceLastY)
                        Mo.tune(clockSqu, S.MO_EDIT_IN, 260f, 0.25f)
                        clockSqu.to(((sdx - sdy) * 0.006f).coerceIn(-0.14f, 0.14f))
                    }
                    2 -> {
                        val dd = hypot(x - bubble.centerX(), y - bubble.centerY())
                        Mo.tune(ckS, S.MO_EDIT_IN, 260f, 0.55f)
                        ckS.to((ceStartS * dd / ceStartD).coerceIn(0.5f, 2f))
                    }
                    3 -> if (ev.pointerCount >= 2) {
                        val dd = hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))
                        Mo.tune(ckS, S.MO_EDIT_IN, 260f, 0.55f)
                        ckS.to((ceStartS * dd / ceStartD).coerceIn(0.5f, 2f))
                    }
                }
                ceLastX = x
                ceLastY = y
                ticker.kick()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                when (ceMode) {
                    1, 2, 3 -> {
                        saveClock()
                        Mo.tune(clockSqu, S.MO_EDIT_IN, 260f, 0.22f)
                        clockSqu.to(0f)
                        // al soltarla la burbuja rebota como un globo
                        boing(if (ceMoved) 3.5f else 1.5f)
                        if (ceMoved) Hx.fire(this, S.HE_SETTING)
                    }
                    5 -> sheetUp(ev, up, x, y)
                    else -> if (up && !ceMoved) exitClockEdit()
                }
                ceMode = 0
                ticker.kick()
                return true
            }
        }
        return true
    }

    /** oscurecer el fondo de pantalla y degradados superior/inferior (estilo One UI) */
    private fun drawBackdrop(c: Canvas) {
        val dim = S.f(S.L_WALL_DIM)
        if (dim > 0.5f) c.drawColor(Color.argb((dim * 2.55f).toInt(), 0, 0, 0))
        if (S.b(S.L_TOP_GRAD)) {
            val h = insetTop + dp(150f)
            if (topGrad == null) topGrad = android.graphics.LinearGradient(0f, 0f, 0f, h, 0xFF000000.toInt(), 0x00000000, android.graphics.Shader.TileMode.CLAMP)
            fxPaint.shader = topGrad
            fxPaint.alpha = (255 * S.p(S.L_TOP_GRAD_S)).toInt()
            c.drawRect(0f, 0f, width.toFloat(), h, fxPaint)
        }
        if (S.b(S.L_BOT_GRAD)) {
            val h = dp(260f)
            val top = height - h
            if (botGrad == null) botGrad = android.graphics.LinearGradient(0f, 0f, 0f, h, 0x00000000, 0xFF000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
            fxPaint.shader = botGrad
            fxPaint.alpha = (255 * S.p(S.L_BOT_GRAD_S)).toInt()
            c.save()
            c.translate(0f, top)
            c.drawRect(0f, 0f, width.toFloat(), h, fxPaint)
            c.restore()
        }
    }

    private fun drawFps(c: Canvas) {
        val now = System.nanoTime()
        if (fpsLast != 0L) {
            val dt = (now - fpsLast) / 1e9f
            if (dt in 0.001f..0.1f) fpsVal = if (fpsVal == 0f) 1f / dt else fpsVal * 0.9f + (1f / dt) * 0.1f
        }
        fpsLast = now
        hintPaint.textSize = dp(13f)
        hintPaint.textAlign = Paint.Align.LEFT
        tmpRect.set(dp(12f), insetTop + dp(6f), dp(112f), insetTop + dp(32f))
        glass.draw(c, tmpRect, dp(13f), 0.9f, false)
        c.drawText("FPS " + fpsVal.toInt() + " · " + Screen.hz.toInt() + " Hz", tmpRect.left + dp(10f), tmpRect.centerY() + dp(4.5f), hintPaint)
        hintPaint.textAlign = Paint.Align.CENTER
    }

    private var dockFade = 1f

    private fun editPivotY(): Float = (insetTop + dotsY) / 2f

    private val editVisible = IntArray(4)
    private var editCount = 4

    private fun refreshEditButtons() {
        editCount = 0
        if (S.b(S.E_BTN_WALL)) editVisible[editCount++] = 0
        if (S.b(S.E_BTN_VIEW)) editVisible[editCount++] = 1
        if (S.b(S.E_BTN_MOTION)) editVisible[editCount++] = 2
        if (S.b(S.E_BTN_SETTINGS)) editVisible[editCount++] = 3
    }

    private fun editBtnCenter(slot: Int, out: FloatArray) {
        val n = max(1, editCount)
        val gap = width / n.toFloat()
        out[0] = gap * slot + gap / 2f
        out[1] = (if (S.b(S.K_SHOW)) dockCy else height - insetBottom - dp(50f)) - dp(8f)
    }

    private val btnTmp = FloatArray(2)

    private fun drawEditBar(c: Canvas, ed: Float) {
        val a = clamp01(ed * 1.3f)
        val dy = (1f - clamp01(ed)) * dp(60f)
        val r = dp(27f) * S.p(S.E_BTN_SIZE)
        refreshEditButtons()
        for (slot in 0 until editCount) {
            val i = editVisible[slot]
            editBtnCenter(slot, btnTmp)
            // aparición escalonada, como One UI
            val q = clamp01(ed * 1.6f - i * 0.12f)
            val cx = btnTmp[0]
            val cy = btnTmp[1] + dy + (1f - q) * dp(24f)
            val rr = r * lerp(0.7f, 1f, q)
            tmpRect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            glass.draw(c, tmpRect, rr, a * q, true, S.SF_EDIT_BTN)
            Glyphs.draw(c, editGlyphs[i], cx, cy, rr * 0.95f, Color.WHITE, a * q)
            editLabel.alpha = Tx.alpha(S.TX_EDIT, a * q)
            c.drawText(Tx.str(S.TX_EDIT, editNames[i]), cx, cy + rr + dp(19f) + Tx.dy(S.TX_EDIT), editLabel)
        }
    }

    private fun editBtnAt(x: Float, y: Float): Int {
        refreshEditButtons()
        for (slot in 0 until editCount) {
            editBtnCenter(slot, btnTmp)
            if (hypot(x - btnTmp[0], y - btnTmp[1]) < dp(40f) * S.p(S.E_BTN_SIZE)) return editVisible[slot]
        }
        return -1
    }

    /** punto alrededor del cual el reloj crece y se estira */
    private fun clockPivotX(): Float {
        val align = S.i(S.CK_ALIGN)
        return if (Prefs.clockStyle == 1) (if (align == 2) width - dp(96f) else dp(96f))
        else when (align) { 1 -> dp(110f); 2 -> width - dp(110f); else -> width / 2f }
    }

    private fun clockPivotY(): Float =
        insetTop + when (Prefs.clockStyle) {
            1 -> dp(130f); 3 -> dp(160f); 4 -> dp(124f); 6 -> dp(52f)
            else -> if (Prefs.clockStyle >= 8) dp(ClockFaces.pivotDp(Prefs.clockStyle)) else dp(80f)
        } +
            dp(ckY.value) + (if (S.b(S.DT_GREETING)) dp(22f) else 0f)

    /** pasa un rectángulo del reloj "sin transformar" a su posición real en pantalla */
    private fun mapClock(r: RectF, px: Float, py: Float, sx: Float, sy: Float, tx: Float) {
        if (r.isEmpty) return
        r.set(px + (r.left - px) * sx + tx, py + (r.top - py) * sy, px + (r.right - px) * sx + tx, py + (r.bottom - py) * sy)
    }

    private fun drawClock(c: Canvas, enterOn: Boolean) {
        if (Prefs.clockStyle == 2) {
            updateClock()
            clockRect.setEmpty()
            dateRect.setEmpty()
            clockBox.setEmpty()
            return
        }
        val px = clockPivotX()
        val py = clockPivotY()
        val s = ckS.value * clockPop.value
        val sq = clockSqu.value
        val sx = s * (1f + sq)
        val sy = s * (1f - sq)
        val tx = dp(ckX.value)
        c.save()
        c.translate(tx, 0f)
        c.scale(sx, sy, px, py)
        drawClockRaw(c, enterOn)
        c.restore()
        mapClock(clockRect, px, py, sx, sy, tx)
        mapClock(dateRect, px, py, sx, sy, tx)
        clockBox.set(clockRect)
        if (!dateRect.isEmpty) clockBox.union(dateRect)
    }

    private fun drawClockRaw(c: Canvas, enterOn: Boolean) {
        var a = 1f
        var dy = 0f
        if (enterOn) {
            Motion.enterItem(Prefs.returnAnim, enterSp.value, 0, 1, itx)
            a = itx.alpha
            dy = itx.dy * 0.5f
        }
        updateClock()
        clockRect.setEmpty()
        dateRect.setEmpty()
        val st = if (styleOverride >= 0) styleOverride else Prefs.clockStyle
        if (st == 2) return
        if (!thumb) dy += dp(ckY.value)
        val align = if (thumb) 0 else S.i(S.CK_ALIGN)
        val w = width.toFloat()
        val greet = !thumb && S.b(S.DT_GREETING)
        val gOff = if (greet) dp(22f) else 0f
        val al = when (align) { 1 -> Paint.Align.LEFT; 2 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
        when (st) {
            3 -> { drawBigClock(c, a, dy, gOff, greet, al); return }
            4 -> { drawAnalogClock(c, a, dy, gOff, greet, al); return }
            5 -> { drawThinClock(c, a, dy, gOff, greet, al); return }
            6 -> { drawPillClock(c, a, dy, gOff, greet, al); return }
            7 -> { drawNeonClock(c, a, dy, gOff, greet, al); return }
            else -> if (st >= 8) {
                drawGreeting(c, alignX(al, dp(28f)), al, a, dy, greet)
                info.q = clockSp.value
                faces.draw(c, st, info, a, alignX(al, dp(28f)), insetTop + gOff + dy, al, w, S.b(S.DT_SHOW),
                    isClockEditing, clockRect, dateRect)
                return
            }
        }
        if (st == 1) {
            // Reloj apilado de One UI: horas sobre minutos
            val x = when (align) { 0 -> dp(26f); 2 -> w - dp(26f); else -> dp(26f) }
            val sal = if (align == 2) Paint.Align.RIGHT else Paint.Align.LEFT
            if (greet) {
                greetPaint.textAlign = sal
                greetPaint.alpha = Tx.alpha(S.TX_GREETING, a)
                c.drawText(greetStr, x, insetTop + dp(26f) + dy + Tx.dy(S.TX_GREETING), greetPaint)
            }
            stackPaint.textAlign = sal
            val hy = insetTop + dp(100f) + gOff + dy + Tx.dy(S.TX_HOURS)
            rollText(c, stackPaint, hourStr, prevHour, x, hy, Tx.alpha(S.TX_HOURS, a))
            minPaint.textAlign = sal
            val my = insetTop + dp(184f) + gOff + dy + Tx.dy(S.TX_MINUTES)
            rollText(c, minPaint, minStr, prevMin, x, my, Tx.alpha(S.TX_MINUTES, a * 0.82f))
            clockRect.set(if (sal == Paint.Align.LEFT) x else x - dp(150f), insetTop + dp(20f) + gOff + dy, if (sal == Paint.Align.LEFT) x + dp(150f) else x, insetTop + dp(190f) + gOff + dy)
            if (S.b(S.DT_SHOW)) {
                datePaint.textAlign = sal
                datePaint.alpha = Tx.alpha(S.TX_DATE, a * 0.92f)
                val ty = insetTop + dp(214f) + gOff + dy + Tx.dy(S.TX_DATE)
                c.drawText(dateStr, x + (if (sal == Paint.Align.LEFT) dp(4f) else -dp(4f)), ty, datePaint)
                val tw = datePaint.measureText(dateStr)
                dateRect.set(if (sal == Paint.Align.LEFT) x else x - tw, ty - dp(20f), if (sal == Paint.Align.LEFT) x + tw else x, ty + dp(8f))
                datePaint.textAlign = Paint.Align.CENTER
            }
            return
        }
        val cx = when (align) { 1 -> dp(28f); 2 -> w - dp(28f); else -> w / 2f }
        if (greet) {
            greetPaint.textAlign = al
            greetPaint.alpha = Tx.alpha(S.TX_GREETING, a)
            c.drawText(greetStr, cx, insetTop + dp(26f) + dy + Tx.dy(S.TX_GREETING), greetPaint)
        }
        clockPaint.textAlign = al
        val ty = insetTop + dp(88f) + gOff + dy + Tx.dy(S.TX_CLOCK)
        rollText(c, clockPaint, timeStr, prevTime, cx, ty, Tx.alpha(S.TX_CLOCK, a))
        val cw = clockPaint.measureText(timeStr)
        val cl = when (al) { Paint.Align.LEFT -> cx; Paint.Align.RIGHT -> cx - cw; else -> cx - cw / 2f }
        clockRect.set(cl, ty - clockPaint.textSize * 0.8f, cl + cw, ty + dp(4f))
        if (!S.b(S.DT_SHOW)) return
        datePaint.textAlign = al
        datePaint.alpha = Tx.alpha(S.TX_DATE, a)
        if (datePillW <= 0f) datePillW = datePaint.measureText(dateStr) + dp(30f)
        val py = insetTop + dp(100f) + gOff + dy
        val pl = when (al) { Paint.Align.LEFT -> cx - dp(4f); Paint.Align.RIGHT -> cx - datePillW + dp(4f); else -> cx - datePillW / 2f }
        tmpRect.set(pl, py, pl + datePillW, py + dp(30f))
        dateRect.set(tmpRect)
        if (S.b(S.DT_PILL)) glass.draw(c, tmpRect, dp(15f), a * 0.85f, false, S.SF_DATE)
        val tx = when (al) { Paint.Align.LEFT -> pl + dp(15f); Paint.Align.RIGHT -> pl + datePillW - dp(15f); else -> cx }
        c.drawText(dateStr, tx, py + dp(20.5f) + Tx.dy(S.TX_DATE), datePaint)
        datePaint.textAlign = Paint.Align.CENTER
    }

    // ---------------------------------------------------------------- Diseños de reloj
    private fun alignX(al: Paint.Align, margin: Float): Float = when (al) {
        Paint.Align.LEFT -> margin
        Paint.Align.RIGHT -> width - margin
        else -> width / 2f
    }

    private fun drawGreeting(c: Canvas, x: Float, al: Paint.Align, a: Float, dy: Float, greet: Boolean) {
        if (!greet) return
        greetPaint.textAlign = al
        greetPaint.alpha = Tx.alpha(S.TX_GREETING, a)
        c.drawText(greetStr, x, insetTop + dp(26f) + dy + Tx.dy(S.TX_GREETING), greetPaint)
    }

    /** izquierda del texto [tw] según la alineación */
    private fun leftOf(x: Float, tw: Float, al: Paint.Align): Float = when (al) {
        Paint.Align.LEFT -> x
        Paint.Align.RIGHT -> x - tw
        else -> x - tw / 2f
    }

    private fun drawDateLine(c: Canvas, p: Paint, text: String, x: Float, y: Float, al: Paint.Align, alpha: Int) {
        p.textAlign = al
        p.alpha = alpha
        c.drawText(text, x, y, p)
        val tw = p.measureText(text)
        val l = leftOf(x, tw, al)
        dateRect.set(l, y - dp(20f), l + tw, y + dp(8f))
    }

    /** Grande: horas y minutos enormes en dos líneas, cada uno de un tono */
    private fun drawBigClock(c: Canvas, a: Float, dy: Float, gOff: Float, greet: Boolean, al: Paint.Align) {
        val x = alignX(al, dp(28f))
        drawGreeting(c, x, al, a, dy, greet)
        val y1 = insetTop + dp(126f) + gOff + dy + Tx.dy(S.TX_HOURS)
        val y2 = y1 + bigH.textSize * 0.9f + Tx.dy(S.TX_MINUTES)
        bigH.textAlign = al
        bigM.textAlign = al
        rollText(c, bigH, hourStr, prevHour, x, y1, Tx.alpha(S.TX_HOURS, a))
        rollText(c, bigM, minStr, prevMin, x, y2, Tx.alpha(S.TX_MINUTES, a))
        val tw = maxOf(bigH.measureText(hourStr), bigM.measureText(minStr))
        val l = leftOf(x, tw, al)
        clockRect.set(l, y1 - bigH.textSize * 0.75f, l + tw, y2 + dp(6f))
        if (S.b(S.DT_SHOW)) drawDateLine(c, datePaint, dateStr, x, y2 + dp(40f) + Tx.dy(S.TX_DATE), al, Tx.alpha(S.TX_DATE, a))
    }

    /** Analógico: esfera de cristal con marcas y agujas que giran con un resorte al cambiar el minuto */
    private fun drawAnalogClock(c: Canvas, a: Float, dy: Float, gOff: Float, greet: Boolean, al: Paint.Align) {
        val r = dp(84f) * S.p(S.tp(S.TX_CLOCK, S.TP_SIZE))
        val cx = when (al) { Paint.Align.LEFT -> dp(28f) + r; Paint.Align.RIGHT -> width - dp(28f) - r; else -> width / 2f }
        drawGreeting(c, alignX(al, dp(28f)), al, a, dy, greet)
        val cy = insetTop + dp(40f) + r + gOff + dy
        dialRect.set(cx - r, cy - r, cx + r, cy + r)
        glass.draw(c, dialRect, r, a * 0.9f, true, S.SF_DATE)
        val base = clockPaint.color
        tickPaint.color = base
        for (i in 0 until 12) {
            val big = i % 3 == 0
            tickPaint.strokeWidth = if (big) dp(3f) else dp(1.6f)
            tickPaint.alpha = ((if (big) 230 else 150) * a).toInt()
            val ang = Math.toRadians(i * 30.0)
            val s = kotlin.math.sin(ang).toFloat()
            val co = kotlin.math.cos(ang).toFloat()
            val r1 = r - dp(10f)
            val r0 = r1 - (if (big) dp(10f) else dp(5f))
            c.drawLine(cx + s * r0, cy - co * r0, cx + s * r1, cy - co * r1, tickPaint)
        }
        // las agujas pasan del minuto anterior al nuevo con el mismo resorte del cambio de minuto
        val q = clockSp.value
        var minA = curMinute * 6f
        var hourA = ((curHour24 % 12) + curMinute / 60f) * 30f
        if (q < 0.999f && prevMinute >= 0) {
            var pm = prevMinute * 6f
            if (minA < pm) pm -= 360f
            var ph = ((prevHour24.coerceAtLeast(0) % 12) + prevMinute / 60f) * 30f
            if (hourA < ph) ph -= 360f
            minA = lerp(pm, minA, q)
            hourA = lerp(ph, hourA, q)
        }
        fun hand(deg: Float, len: Float, wdt: Float, col: Int) {
            val ang = Math.toRadians(deg.toDouble())
            handPaint.strokeWidth = wdt
            handPaint.color = col
            handPaint.alpha = (255 * a).toInt()
            c.drawLine(cx, cy, cx + kotlin.math.sin(ang).toFloat() * len, cy - kotlin.math.cos(ang).toFloat() * len, handPaint)
        }
        val minCol = if (S.i(S.tp(S.TX_MINUTES, S.TP_COLOR)) == 0) OneColors.ACCENT else minPaint.color
        hand(hourA, r * 0.5f, dp(6f), base)
        hand(minA, r * 0.76f, dp(4f), minCol)
        centerPaint.color = minCol
        centerPaint.alpha = (255 * a).toInt()
        c.drawCircle(cx, cy, dp(6f), centerPaint)
        centerPaint.color = Color.WHITE
        centerPaint.alpha = (255 * a).toInt()
        c.drawCircle(cx, cy, dp(2.4f), centerPaint)
        clockRect.set(dialRect)
        if (S.b(S.DT_SHOW)) drawDateLine(c, datePaint, dateStr, cx, cy + r + dp(32f) + Tx.dy(S.TX_DATE), Paint.Align.CENTER, Tx.alpha(S.TX_DATE, a))
    }

    /** Fino: cifras muy finas con el separador del color de acento y la fecha arriba en versalitas */
    private fun drawThinClock(c: Canvas, a: Float, dy: Float, gOff: Float, greet: Boolean, al: Paint.Align) {
        val x = alignX(al, dp(28f))
        drawGreeting(c, x, al, a, dy, greet)
        if (S.b(S.DT_SHOW)) drawDateLine(c, thinSmall, dateStr.uppercase(), x, insetTop + dp(34f) + gOff + dy + Tx.dy(S.TX_DATE), al, Tx.alpha(S.TX_DATE, a))
        val ty = insetTop + dp(118f) + gOff + dy + Tx.dy(S.TX_CLOCK)
        val tw = thinPaint.measureText(timeStr)
        val l = leftOf(x, tw, al)
        val alpha = Tx.alpha(S.TX_CLOCK, a)
        thinPaint.textAlign = Paint.Align.LEFT
        val sepI = timeStr.indexOfFirst { it == ':' || it == '·' }
        if (clockSp.value < 0.999f || sepI < 0) {
            rollText(c, thinPaint, timeStr, prevTime, l, ty, alpha)
        } else {
            val p1 = timeStr.substring(0, sepI)
            val sp = timeStr.substring(sepI, sepI + 1)
            val p2 = timeStr.substring(sepI + 1)
            thinPaint.alpha = alpha
            c.drawText(p1, l, ty, thinPaint)
            val w1 = thinPaint.measureText(p1)
            sepPaint.set(thinPaint)
            sepPaint.color = OneColors.ACCENT
            sepPaint.alpha = alpha
            c.drawText(sp, l + w1, ty, sepPaint)
            c.drawText(p2, l + w1 + thinPaint.measureText(sp), ty, thinPaint)
        }
        clockRect.set(l, ty - thinPaint.textSize * 0.78f, l + tw, ty + dp(6f))
    }

    /** Cápsula: la hora y la fecha dentro de una píldora de cristal */
    private fun drawPillClock(c: Canvas, a: Float, dy: Float, gOff: Float, greet: Boolean, al: Paint.Align) {
        drawGreeting(c, alignX(al, dp(28f)), al, a, dy, greet)
        val tw = pillTime.measureText(timeStr)
        var showDate = S.b(S.DT_SHOW)
        val dw = if (showDate) datePaint.measureText(dateStr) else 0f
        var pw = tw + dp(48f) + (if (showDate) dw + dp(33f) else 0f)
        if (pw > width - dp(32f)) {
            showDate = false
            pw = tw + dp(48f)
        }
        val ph = dp(76f)
        val left = when (al) { Paint.Align.LEFT -> dp(16f); Paint.Align.RIGHT -> width - dp(16f) - pw; else -> (width - pw) / 2f }
        val top = insetTop + dp(16f) + gOff + dy
        dialRect.set(left, top, left + pw, top + ph)
        glass.draw(c, dialRect, ph / 2f, a, true, S.SF_DATE)
        pillTime.textAlign = Paint.Align.LEFT
        val ty = top + ph / 2f + pillTime.textSize * 0.36f + Tx.dy(S.TX_CLOCK)
        rollText(c, pillTime, timeStr, prevTime, left + dp(24f), ty, Tx.alpha(S.TX_CLOCK, a))
        if (showDate) {
            val sx = left + dp(24f) + tw + dp(16f)
            centerPaint.color = OneColors.ACCENT
            centerPaint.alpha = (230 * a).toInt()
            c.drawRoundRect(sx, top + dp(22f), sx + dp(2.5f), top + ph - dp(22f), dp(1.5f), dp(1.5f), centerPaint)
            datePaint.textAlign = Paint.Align.LEFT
            datePaint.alpha = Tx.alpha(S.TX_DATE, a)
            c.drawText(dateStr, sx + dp(15f), top + ph / 2f + dp(5.5f) + Tx.dy(S.TX_DATE), datePaint)
            datePaint.textAlign = Paint.Align.CENTER
        }
        clockRect.set(dialRect)
    }

    /** Neón: cifras que brillan con su propio color */
    private fun drawNeonClock(c: Canvas, a: Float, dy: Float, gOff: Float, greet: Boolean, al: Paint.Align) {
        val x = alignX(al, dp(28f))
        drawGreeting(c, x, al, a, dy, greet)
        neonPaint.textAlign = al
        val ty = insetTop + dp(92f) + gOff + dy + Tx.dy(S.TX_CLOCK)
        val alpha = Tx.alpha(S.TX_CLOCK, a)
        // dos pasadas: la segunda refuerza el brillo
        rollText(c, neonPaint, timeStr, prevTime, x, ty, alpha)
        rollText(c, neonPaint, timeStr, prevTime, x, ty, (alpha * 0.6f).toInt())
        val tw = neonPaint.measureText(timeStr)
        val l = leftOf(x, tw, al)
        clockRect.set(l, ty - neonPaint.textSize * 0.8f, l + tw, ty + dp(6f))
        if (S.b(S.DT_SHOW)) drawDateLine(c, neonDate, dateStr, x, ty + dp(40f) + Tx.dy(S.TX_DATE), al, Tx.alpha(S.TX_DATE, a))
    }

    /** dibuja [now]; durante el cambio de minuto [old] sube desvaneciéndose y [now] entra desde abajo */
    private fun rollText(c: Canvas, p: Paint, now: String, old: String, x: Float, y: Float, alpha: Int) {
        val q = clockSp.value
        if (q >= 0.999f || old.isEmpty() || old == now) {
            p.alpha = alpha
            c.drawText(now, x, y, p)
            return
        }
        val d = p.textSize * 0.28f
        p.alpha = (alpha * clamp01(1f - q * 1.4f)).toInt()
        c.drawText(old, x, y - d * clamp01(q), p)
        p.alpha = (alpha * clamp01(q * 1.3f)).toInt()
        c.drawText(now, x, y + d * (1f - q), p)
        p.alpha = alpha
    }

    private fun labelOf(e: AppEntry): String {
        var s = labelCache[e.key]
        if (s == null) {
            s = TextUtils.ellipsize(Tx.str(S.TX_LABEL_HOME, e.label), textPaint, hg.labelW, TextUtils.TruncateAt.END).toString()
            labelCache[e.key] = s
        }
        return s
    }

    /** posición animada de un icono del inicio; [tx],[ty] = centro de su celda */
    private fun posFor(key: String, e: AppEntry, tx: Float, ty: Float, pg: Int): IconPos {
        var p = iconPos[key]
        if (p == null) {
            p = IconPos(key)
            iconPos[key] = p
            iconList.add(p)
            p.x.snap(tx)
            p.y.snap(ty)
            p.page = pg
            if (popKeys.remove(key)) {
                // recién añadido: aparece inflándose
                Mo.tune(p.s, S.MO_EDIT_IN, 380f, 0.45f)
                p.s.snap(0f)
                p.s.to(1f)
                ticker.kick()
            }
        } else if (p.page != pg) {
            // cambió de pantalla: aparece en su sitio nuevo con un pequeño "pop"
            p.page = pg
            p.x.snap(tx)
            p.y.snap(ty)
            Mo.tune(p.s, S.MO_EDIT_IN, 380f, 0.5f)
            p.s.snap(0.6f)
            p.s.to(1f)
            ticker.kick()
        } else if (p.x.target != tx || p.y.target != ty) {
            // la celda cambió (reordenar, columnas, quitar otro icono): se desliza hasta ella
            Mo.tune(p.x, S.MO_EDIT_IN, 300f, 0.78f)
            Mo.tune(p.y, S.MO_EDIT_IN, 300f, 0.78f)
            p.x.to(tx)
            p.y.to(ty)
            ticker.kick()
        }
        p.e = e
        return p
    }

    /** un icono que se quita se queda encogiéndose donde estaba */
    private fun makeGhost(key: String) {
        val p = iconPos.remove(key) ?: return
        p.ghost = true
        Mo.tune(p.s, S.MO_EDIT_OUT, 420f, 1f)
        p.s.to(0f)
        ticker.kick()
    }

    /** compara el inicio de antes y el de ahora: los que se van se encogen, los nuevos se inflan */
    private fun diffHome(old: List<String>, now: List<String>) {
        if (!homeReady) {
            if (AppRepo.apps.isNotEmpty()) homeReady = true
            return
        }
        val oldSet = HashSet(old)
        val newSet = HashSet(now)
        for (k in oldSet) if (k != HomeSlots.BLANK && k !in newSet) makeGhost(k)
        for (k in newSet) if (k != HomeSlots.BLANK && k !in oldSet) popKeys.add(k)
    }

    private fun drawIconAt(c: Canvas, e: AppEntry, cx: Float, cy: Float, size: Float, a: Float, label: Boolean) {
        val b = e.icon ?: return
        val al = (255f * a).toInt().coerceIn(0, 255)
        if (al == 0) return
        val ps = pressScaleFor(e.key)
        val sz = size * ps * IconFx.scale(S.IA_HOME)
        IconFx.draw(c, b, e.tint, cx, cy, sz, a, S.IA_HOME, 0f, clamp01((1f - ps) / 0.14f))
        if (label && Prefs.labels) {
            textPaint.alpha = Tx.alpha(S.TX_LABEL_HOME, a)
            c.drawText(labelOf(e), cx, cy + size / 2f + dp(Prefs.labelSize + S.f(S.H_LABEL_GAP)) + Tx.dy(S.TX_LABEL_HOME), textPaint)
        }
    }

    private fun drawPages(c: Canvas, enterOn: Boolean) {
        val n = pageCount()
        val pv = pageSp.value
        val mode = Prefs.pageAnim
        val w = width.toFloat()
        val first = max(0, floor(pv).toInt())
        val last = min(n - 1, ceil(pv).toInt())
        val styleEnter = Prefs.returnAnim
        for (pg in first..last) {
            val o = pg - pv
            Motion.pageT(mode, o, w, 0.5f, pt)
            val pageAlpha = pt.alpha
            c.save()
            if (mode != 2) {
                c.translate(pt.tx, 0f)
                if (pt.sc != 1f) c.scale(pt.sc, pt.sc, w / 2f, (gridTop + dotsY) / 2f)
                if (pt.rotY != 0f) Rot3D.apply(c, pt.rotY, pt.pivot * w, height / 2f)
            }
            val ed = editSp.value
            if (ed > 0.001f) {
                tmpRect.set(dp(10f), insetTop + dp(10f), w - dp(10f), dotsY + dp(18f))
                glass.draw(c, tmpRect, dp(34f), clamp01(ed) * 0.75f * pageAlpha, false, S.SF_EDIT_FRAME)
                val ea = clamp01(ed) * pageAlpha
                if (pg >= realPages()) {
                    // tarjeta "Añadir pantalla"
                    val cx = tmpRect.centerX()
                    val cy = tmpRect.centerY()
                    capRect.set(cx - dp(44f), cy - dp(44f), cx + dp(44f), cy + dp(44f))
                    glass.draw(c, capRect, dp(44f), ea, true, S.SF_EDIT_BTN)
                    Glyphs.draw(c, Glyphs.PLUS, cx, cy, dp(40f), Color.WHITE, ea)
                    editLabel.alpha = Tx.alpha(S.TX_EDIT, ea)
                    c.drawText(Tx.str(S.TX_EDIT, "Añadir pantalla"), cx, cy + dp(72f), editLabel)
                } else if (realPages() > 1) {
                    // botón "Quitar pantalla"
                    val bx = removePageX()
                    val by = removePageY()
                    capRect.set(bx - dp(19f), by - dp(19f), bx + dp(19f), by + dp(19f))
                    glass.draw(c, capRect, dp(19f), ea, true, S.SF_EDIT_BTN)
                    Glyphs.draw(c, Glyphs.TRASH, bx, by, dp(20f), Color.WHITE, ea)
                }
            }
            val start = pg * cap
            val end = min(homeKeys.size, start + cap)
            for (idx in start until end) {
                val key = homeKeys[idx]
                if (touchMode == M_DRAG && !dragFromDock && key == dragKey) continue
                val e = AppRepo.get(key) ?: continue
                val local = idx - start
                val col = local % cols
                val row = local / cols
                // sitio de la celda; el icono llega a él con un resorte
                val ip = posFor(key, e, cellX(local), cellIconY(local), pg)
                var cx = ip.x.value
                var cy = ip.y.value
                var sc = ip.s.value
                var al = pageAlpha * clamp01(ip.s.value * 1.6f)
                if (mode == 2) {
                    val colFrac = if (cols > 1) col / (cols - 1f) else 0.5f
                    Motion.pageT(2, o, w, colFrac, pt)
                    cx += pt.tx
                    al = pt.alpha
                }
                if (enterOn) {
                    Motion.enterItem(styleEnter, enterSp.value, idx - start, min(cap, end - start), itx)
                    cx += itx.dx
                    cy += itx.dy
                    sc *= itx.sc
                    al *= itx.alpha
                }
                drawIconAt(c, e, cx, cy, iconPx * sc, al, true)
                if (ed > 0.01f) {
                    // botón "−" para quitar el icono: aparecen en cascada, inflándose
                    val bq = pop(clamp01((ed - 0.1f - min(local, 15) * 0.02f) / 0.5f))
                    val ba = clamp01(bq * 1.5f) * al
                    val bx = cx - iconPx * sc / 2f + dp(3f)
                    val by = cy - iconPx * sc / 2f + dp(3f)
                    badgePaint.color = Color.WHITE
                    badgePaint.alpha = (235 * ba).toInt().coerceIn(0, 255)
                    c.drawCircle(bx, by, dp(11f) * bq, badgePaint)
                    Glyphs.draw(c, Glyphs.MINUS, bx, by, dp(16f) * bq, 0xFF1B2440.toInt(), ba)
                }
            }
            // iconos que se fueron: se encogen en su sitio
            for (gi in iconList.indices) {
                val gp = iconList[gi]
                if (!gp.ghost || gp.page != pg) continue
                val ge = gp.e ?: continue
                val gs = gp.s.value
                if (gs > 0.01f) drawIconAt(c, ge, gp.x.value, gp.y.value, iconPx * gs, pageAlpha * clamp01(gs * 1.5f), false)
            }
            c.restore()
        }
        // marcador de destino mientras se arrastra un icono
        if (touchMode == M_DRAG && !dragFromDock) {
            val idx = dropIndex(dragX, dragY)
            val pg = pageSp.value.roundToInt()
            if (idx >= 0 && dragY > insetTop + dp(80f) && dragY < dockTopY()) {
                val local = idx - pg * cap
                if (local in 0 until cap) {
                    val tx = cellX(local) - (hg.step - dp(12f)) / 2f
                    val ty = cellMidY(local) - (hg.cellH - dp(8f)) / 2f
                    if (!dropShown) {
                        dropX.snap(tx)
                        dropY.snap(ty)
                        dropShown = true
                    } else if (dropX.target != tx || dropY.target != ty) {
                        dropX.to(tx)
                        dropY.to(ty)
                        ticker.kick()
                    }
                    tmpRect.set(dropX.value, dropY.value, dropX.value + hg.step - dp(12f), dropY.value + hg.cellH - dp(8f))
                    glass.draw(c, tmpRect, dp(20f), 0.45f, false, S.SF_DROP)
                } else dropShown = false
            } else dropShown = false
        } else dropShown = false
    }

    private fun drawDots(c: Canvas) {
        if (!S.b(S.D_SHOW)) return
        val nNow = pageCount()
        val want = if (nNow > 1) 1f else 0f
        if (dotsVis.target != want) {
            Mo.tune(dotsVis, S.MO_PAGE, 260f, 0.9f)
            dotsVis.to(want)
            ticker.kick()
        }
        if (nNow > 1) lastDotsN = nNow
        val vis = clamp01(dotsVis.value)
        if (vis < 0.01f) return
        val n = if (nNow > 1) nNow else lastDotsN
        val pv = pageSp.value
        val k = S.p(S.D_SIZE)
        val gap = dp(15f) * S.p(S.D_GAP) * max(1f, k)
        val y = dotsY + dp(S.f(S.D_DY))
        val x0 = width / 2f - (n - 1) * gap / 2f
        val style = S.i(S.D_STYLE)
        val act = S.col(S.D_COLOR, Color.WHITE)
        // mientras se desliza entre páginas los puntos inactivos se ven más
        val idle = lerp(S.p(S.D_IDLE), 0.75f, dotsBoost.value)
        if (style == 4) {
            tmpRect.set(x0 - dp(14f) * k, y - dp(11f) * k, x0 + (n - 1) * gap + dp(14f) * k, y + dp(11f) * k)
            glass.draw(c, tmpRect, dp(11f) * k, 0.7f * vis, false, S.SF_DOTS)
        }
        if (style == 3) {
            hintPaint.textSize = dp(13f) * k
            hintPaint.textAlign = Paint.Align.CENTER
            hintPaint.color = act
            hintPaint.alpha = (Color.alpha(act) * vis).toInt()
            c.drawText((pv.roundToInt().coerceIn(0, n - 1) + 1).toString() + " / " + n, width / 2f, y + dp(4.5f) * k, hintPaint)
            hintPaint.color = Color.WHITE
            return
        }
        for (i in 0 until n) {
            val near = 1f - min(1f, abs(i - pv))
            val col = blend((act and 0x00FFFFFF) or ((255 * idle).toInt() shl 24), act, near)
            val x = x0 + i * gap
            if (i == 0 && S.b(S.D_HOME)) {
                Glyphs.draw(c, Glyphs.HOME, x, y, dp(11f) * k, col, vis)
                continue
            }
            dotPaint.color = col
            dotPaint.alpha = (Color.alpha(col) * vis).toInt()
            when (style) {
                1 -> {
                    val lw = dp(9f) * k
                    tmpRect.set(x - lw / 2f, y - dp(1.5f) * k, x + lw / 2f, y + dp(1.5f) * k)
                    c.drawRoundRect(tmpRect, dp(1.5f) * k, dp(1.5f) * k, dotPaint)
                }
                2 -> {
                    val r = dp(3.2f) * k
                    val ww = r + near * dp(6f) * k
                    tmpRect.set(x - ww, y - r, x + ww, y + r)
                    c.drawRoundRect(tmpRect, r, r, dotPaint)
                }
                4 -> {
                    // píldora One UI: el punto activo se estira en una cápsula
                    val r = lerp(dp(3f), dp(3.6f), near) * k
                    val ww = r + near * dp(4.5f) * k
                    tmpRect.set(x - ww, y - r, x + ww, y + r)
                    c.drawRoundRect(tmpRect, r, r, dotPaint)
                }
                else -> c.drawCircle(x, y, lerp(dp(3f), dp(4.4f), near) * k, dotPaint)
            }
        }
    }

    private fun drawDock(c: Canvas, enterOn: Boolean) {
        if (slots.isEmpty() || !S.b(S.K_SHOW)) return
        val w = width.toFloat()
        val sepW = dp(14f)
        var total = 0f
        for (s in slots) {
            s.scale = s.sc.value
            total += (if (s.kind == 3) sepW else dockUnit * s.scale) * s.gw.value
        }
        var fit = 1f
        if (total > w - dp(8f)) fit = (w - dp(8f)) / total
        total *= fit
        var x = (w - total) / 2f
        for (s in slots) {
            val sw = (if (s.kind == 3) sepW else dockUnit * s.scale) * s.gw.value * fit
            s.cx = x + sw / 2f
            x += sw
        }
        var a = dockFade
        var dy = 0f
        if (enterOn) {
            Motion.enterItem(Prefs.returnAnim, enterSp.value, 0, 1, itx)
            a = itx.alpha * dockFade
            dy = (1f - clamp01(enterSp.value)) * dp(90f)
        }
        if (a <= 0.01f) return
        // cápsula: ancho en reposo animado (crece/encoge con rebote) o el de la magnificación si es mayor
        val capW = max(restWidth() + dockShrink.value * fit, total) + dp(S.f(S.K_EXTRA_W))
        // forma y material del dock (Ajustes → Dock → Diseños del dock)
        val form = S.i(S.K_SHAPE)
        val dm = dockArt.settingsMat()
        dockArt.bodyRect(form, capRect, w, capW, dockCy + dy, dockH, height.toFloat() + dy)
        val mv = dockMorph.value
        if (dm != null) {
            if (mv < 0.999f || dockMorph.active) {
                // diseño nuevo: el fondo sale del pie del dock estirándose con rebote (como gelatina)
                val sy = max(0.05f, mv)
                val sx = 0.78f + 0.22f * min(mv, 1.2f)
                c.save()
                c.scale(sx, sy, capRect.centerX(), dockCy + dockH / 2f + dy)
                dockArt.drawBody(c, form, capRect, dockH, a * clamp01(mv * 1.6f), dm, S.b(S.K_DOUBLE))
                c.restore()
            } else {
                dockArt.drawBody(c, form, capRect, dockH, a, dm, S.b(S.K_DOUBLE))
            }
        }
        val tiles = dm != null && DockDesigns.perIcon(form)
        val reflect = S.b(S.K_REFLECT) && form != DockDesigns.F_NONE
        // borde de abajo del dock (en la Pestaña el fondo llega hasta el final de la pantalla)
        val dockBottom = min(capRect.bottom, dockCy + dockH / 2f + dy)

        val baseBottom = dockCy + dockIconPx / 2f + dy
        val lf = Motion.liftFactor(Prefs.dockMode) * S.p(S.DOCKS_BASE + Prefs.dockMode * 2 + 1)
        val dkScale = IconFx.scale(S.IA_DOCK)
        for (s in slots) {
            if (s.kind == 3) {
                dotPaint.color = Color.argb((90 * a).toInt(), 255, 255, 255)
                var st = dockCy - dockIconPx * 0.4f + dy
                var sb = dockCy + dockIconPx * 0.4f + dy
                // con fondo, el separador nunca asoma fuera de él (en el Estante 3D se salía por arriba)
                if (dm != null && form != DockDesigns.F_NONE && form != DockDesigns.F_LINE && !DockDesigns.perIcon(form)) {
                    st = max(st, capRect.top + dp(6f))
                    sb = min(sb, dockBottom - dp(6f))
                }
                if (sb - st > dp(4f)) {
                    tmpRect.set(s.cx - dp(0.8f), st, s.cx + dp(0.8f), sb)
                    c.drawRoundRect(tmpRect, dp(1f), dp(1f), dotPaint)
                }
                continue
            }
            val bmp: android.graphics.Bitmap? = when (s.kind) {
                1 -> AppRepo.launchpadIcon
                2 -> AppRepo.recentsIcon
                else -> AppRepo.get(s.key ?: "")?.icon
            }
            if (bmp == null) continue
            if (touchMode == M_DRAG && dragFromDock && s.key == dragKey) continue
            val scale = s.scale * s.gw.value * (1f + s.bs.value) * (if (s === pressSlot) pressSp.value else 1f)
            val size = dockIconPx * scale * dkScale
            val lift = (s.scale - 1f) * dockIconPx * lf
            val bounceUp = max(0f, s.by.value) * dockIconPx
            val cx = s.cx + s.bx.value * dockIconPx * 0.5f
            val cy = baseBottom - size / 2f - lift - bounceUp
            val tint = if (s.kind == 0) AppRepo.get(s.key ?: "")?.tint ?: 0xFF3A5BA0.toInt() else 0xFF3A5BA0.toInt()
            val dk = if (s === pressSlot) clamp01((1f - pressSp.value) / 0.14f) else 0f
            // Burbujas / Baldosas: cada icono lleva su propio cristal, que crece con él
            if (tiles && dm != null) dockArt.drawTile(c, form, cx, cy, size, a, dm)
            if (reflect) {
                // reflejo: el icono invertido y muy tenue bajo su base, recortado al dock
                val foot = cy + size / 2f
                if (foot < dockBottom - dp(2f)) {
                    c.save()
                    c.clipRect(capRect.left, foot, capRect.right, dockBottom)
                    c.scale(1f, -1f, cx, foot)
                    IconFx.draw(c, bmp, tint, cx, cy, size, a * 0.22f, S.IA_DOCK, s.br.value * 360f, 0f)
                    c.restore()
                }
            }
            IconFx.draw(c, bmp, tint, cx, cy, size, a, S.IA_DOCK, s.br.value * 360f, dk)
            val rv = s.run.value
            if (rv > 0.01f && S.b(S.K_DOTS)) {
                dotPaint.color = S.col(S.K_DOT_COLOR, Color.WHITE)
                dotPaint.alpha = (220 * a * clamp01(rv)).toInt().coerceIn(0, 255)
                c.drawCircle(s.cx, dockBottom - dp(5f), dp(2f) * S.p(S.K_DOT_SIZE) * rv, dotPaint)
            }
        }
    }

    private fun drawDrag(c: Canvas) {
        val k = dragKey ?: return
        if (touchMode != M_DRAG) return
        val e = AppRepo.get(k) ?: return
        if (dragY < insetTop + dp(80f) && !dragFromDock && S.b(S.H_REMOVE_DRAG)) {
            tmpRect.set(width / 2f - dp(70f), insetTop + dp(16f), width / 2f + dp(70f), insetTop + dp(58f))
            glass.draw(c, tmpRect, dp(21f), 1f, true, S.SF_REMOVE)
            hintPaint.textSize = dp(14f)
            hintPaint.textAlign = Paint.Align.CENTER
            c.drawText("Quitar", tmpRect.centerX(), tmpRect.centerY() + dp(5f), hintPaint)
        }
        val lift = dragLift.value
        val size = (if (dragFromDock) dockIconPx else iconPx) * lerp(1f, 1.22f, lift)
        val cy = dragY - dp(14f) * lift
        c.save()
        c.rotate(dragTilt.value, dragX, cy)
        tmpRect.set(dragX - size * 0.62f, cy - size * 0.62f, dragX + size * 0.62f, cy + size * 0.62f)
        glass.draw(c, tmpRect, size * 0.5f, 0.35f * clamp01(lift), false, S.SF_DRAG)
        drawIconAt(c, e, dragX, cy, size, 1f, false)
        c.restore()
    }

    // ---------------------------------------------------------------- Tacto
    private fun homeKeyAt(x: Float, y: Float): String? {
        val pg = pageSp.value.roundToInt()
        val local = cellAt(x, y, true)
        if (local < 0) return null
        val idx = pg * cap + local
        if (idx !in 0 until homeKeys.size) return null
        val k = homeKeys[idx]
        // un hueco (o una app que ya no existe) es fondo vacío
        return if (k == HomeSlots.BLANK || AppRepo.get(k) == null) null else k
    }

    private fun dockKeyAt(x: Float): String? {
        val s = slotAt(x) ?: return null
        return s.key
    }

    /** celda bajo el dedo (puede estar más allá del final de la lista: una celda vacía) */
    private fun dropIndex(x: Float, y: Float): Int {
        val pg = pageSp.value.roundToInt().coerceIn(0, realPages() - 1)
        return pg * cap + cellAt(x, y, false).coerceAtLeast(0)
    }

    private fun cancelLong() {
        handler.removeCallbacks(longRun)
        unpress()
    }

    private fun setPageDrag(v: Float) {
        val n = (pageCount() - 1).toFloat()
        var t = v
        val rb = S.p(S.PAGE_RUBBER).coerceAtLeast(0.01f)
        if (t < 0f) t = rubber(t, rb)
        if (t > n) t = n + rubber(t - n, rb)
        pageSp.snap(t)
    }

    private fun settle(v: Float) {
        val n = pageCount() - 1
        // como en iPhone: un gesto pasa como mucho una pantalla, por rápido que sea
        val start = pageStart.roundToInt()
        val raw = (pageSp.value + v * 0.25f).roundToInt().coerceIn(start - 1, start + 1)
        var target = raw.coerceIn(0, n)
        if (S.b(S.PAGE_LOOP) && n > 0) {
            if (raw > n && pageStart.roundToInt() == n) target = 0
            if (raw < 0 && pageStart.roundToInt() == 0) target = n
        }
        if (target != pageStart.roundToInt()) Hx.fire(this, S.HE_PAGE)
        Mo.tune(pageSp, S.MO_PAGE, 220f, 0.84f)
        pageSp.to(target.toFloat())
        pageSp.velocity = v
        ticker.kick()
    }

    private fun endScrub() {
        scrubbing = false
        updateDockTargets()
        ticker.kick()
    }

    private var editPaging = false
    private var editGesture = false

    private fun editTouch(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        val s = S.p(S.E_SCALE)
        // el dedo que abrió el modo edición se ignora hasta que se levante
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) editGesture = true
        if (!editGesture) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
                downX = x
                downY = y
                moved = false
                editPaging = false
                pageStart = pageSp.value
                pageSp.snap(pageSp.value)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = x - downX
                if (!moved && (abs(dx) > slop || abs(y - downY) > slop)) {
                    moved = true
                    editPaging = abs(dx) > abs(y - downY)
                }
                if (editPaging) {
                    setPageDrag(pageStart - dx / (width * s))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(ev)
                if (editPaging) {
                    vt?.computeCurrentVelocity(1000)
                    settle(-(vt?.xVelocity ?: 0f) / (width * s))
                } else if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                    if (pageSp.value != pageSp.value.roundToInt().toFloat()) settle(0f)
                    val b = editBtnAt(x, y)
                    if (b >= 0) {
                        exitEdit()
                        Hx.fire(this, S.HE_PANEL)
                        host?.editAction(b)
                    } else if (!editContentTap(x, y)) {
                        exitEdit()
                    }
                }
                vt?.recycle()
                vt = null
            }
        }
        return true
    }

    /** toque dentro de las pantallas encogidas: añadir pantalla, quitar pantalla o quitar un icono */
    private fun editContentTap(x: Float, y: Float): Boolean {
        val cx = toContentX(x)
        val cy = toContentY(y)
        val pg = pageSp.value.roundToInt()
        if (cy < insetTop || cy > dotsY + dp(18f)) return false
        if (pg >= realPages()) {
            addPage()
            return true
        }
        if (realPages() > 1 && hypot(cx - removePageX(), cy - removePageY()) < dp(26f)) {
            removePage(pg)
            return true
        }
        // botón "−" de un icono (zona generosa alrededor de la esquina)
        val start = pg * cap
        val end = min(homeKeys.size, start + cap)
        for (idx in start until end) {
            val k = homeKeys[idx]
            if (k == HomeSlots.BLANK || AppRepo.get(k) == null) continue
            val local = idx - start
            val ix = cellX(local)
            val iy = cellIconY(local)
            val bx = ix - iconPx / 2f + dp(3f)
            val by = iy - iconPx / 2f + dp(3f)
            if (hypot(cx - bx, cy - by) < dp(22f)) {
                makeGhost(k)
                homeKeys[idx] = HomeSlots.BLANK
                saveHome()
                Hx.fire(this, S.HE_SETTING)
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (isClockEditing) return clockEditTouch(ev)
        // mientras el editor del reloj se cierra no se tocan iconos por accidente
        if (clockEd.value > 0.05f) return true
        if (isEditing) return editTouch(ev)
        // mientras se cierra el modo edición las páginas aún están encogidas: se ignoran toques
        if (editSp.value > 0.02f) return true
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                host?.dismissMenu()
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
                downX = x
                downY = y
                touchMode = M_NONE
                moved = false
                vTriggered = false
                inDock = S.b(S.K_SHOW) && y > dockTopY()
                pageStart = pageSp.value
                pageSp.snap(pageSp.value)
                downKey = if (inDock) dockKeyAt(x) else homeKeyAt(x, y)
                handler.removeCallbacks(longRun)
                if (downKey != null || !inDock) handler.postDelayed(longRun, if (downKey != null) S.f(S.G_ICON_LONG_MS).toLong() else S.f(S.G_LONG_MS).toLong())
                if (inDock) {
                    scrubbing = true
                    scrubX = x
                    tickSlot = slotAt(x)
                    if (tickSlot?.kind == 1) host?.prewarmDrawer()
                    press(null, tickSlot)
                    updateDockTargets()
                    ticker.kick()
                } else if (downKey != null) {
                    press(downKey, null)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2 && !inDock && !vTriggered && (touchMode == M_NONE || touchMode == M_PAGE || touchMode == M_VSWIPE)) {
                    cancelLong()
                    if (touchMode == M_PAGE) settle(0f)
                    touchMode = M_MULTI
                    multiDone = false
                    multiT0 = System.currentTimeMillis()
                    y0avg = (ev.getY(0) + ev.getY(1)) / 2f
                    d0 = hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1)).coerceAtLeast(1f)
                    yAvg = y0avg
                    dNow = d0
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (touchMode == M_MULTI && ev.pointerCount == 2) finishMulti()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = x - downX
                val dy = y - downY
                if (touchMode == M_MULTI) {
                    if (ev.pointerCount >= 2) {
                        yAvg = (ev.getY(0) + ev.getY(1)) / 2f
                        dNow = hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))
                    }
                    return true
                }
                when (touchMode) {
                    M_DRAG -> {
                        // se inclina hacia donde se mueve, como si tuviera peso
                        dragTilt.to(((x - dragX) * 0.9f).coerceIn(-14f, 14f))
                        ticker.kick()
                        dragX = x
                        dragY = y
                        val dir = if (!dragFromDock && x < dp(30f)) -1 else if (!dragFromDock && x > width - dp(30f)) 1 else 0
                        if (dir != edgeDir) {
                            edgeDir = dir
                            handler.removeCallbacks(edgeRun)
                            if (dir != 0) handler.postDelayed(edgeRun, 600L)
                        }
                        invalidate()
                        return true
                    }
                    M_MENU -> {
                        if (downKey != null && !S.b(S.H_LOCK) && hypot(dx, dy) > slop * 2.2f) {
                            host?.dismissMenu()
                            startDrag(x, y)
                        }
                        return true
                    }
                }
                if (inDock) {
                    scrubX = x
                    if (abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        cancelLong()
                    }
                    if (touchMode == M_NONE && dy < -dp(46f) * S.p(S.G_SWIPE_DIST) && abs(dy) > abs(dx) * 1.5f) {
                        touchMode = M_VSWIPE
                        endScrub()
                        host?.gesture(S.G_DOCK_UP)
                    }
                    if (scrubbing) {
                        updateDockTargets()
                        ticker.kick()
                        // vibración suave al pasar de un icono a otro (como el trackpad de un Mac)
                        val now = slotAt(scrubX)
                        if (now != null && now !== tickSlot && moved) {
                            tickSlot = now
                            Hx.fire(this, S.HE_DOCK)
                        }
                    }
                } else {
                    if (touchMode == M_NONE && (abs(dx) > slop || abs(dy) > slop)) {
                        cancelLong()
                        moved = true
                        touchMode = if (abs(dx) > abs(dy)) M_PAGE else M_VSWIPE
                        // hacia arriba y con la acción "vista de apps": la vista sigue al dedo desde el primer momento
                        if (touchMode == M_VSWIPE && dy < 0f && S.gestAction(S.G_UP) == S.A_DRAWER) {
                            touchMode = M_DRAWER
                            host?.drawerDrag(0, 0f, 0f)
                        }
                    }
                    if (touchMode == M_DRAWER) {
                        host?.drawerDrag(1, clamp01(-dy / drawerDragDist), 0f)
                        return true
                    }
                    if (touchMode == M_PAGE) {
                        if (dotsBoost.target < 1f) {
                            dotsBoost.to(1f)
                            ticker.kick()
                        }
                        setPageDrag(pageStart - dx / width)
                        invalidate()
                    } else if (touchMode == M_VSWIPE && !vTriggered) {
                        val gd = S.p(S.G_SWIPE_DIST)
                        if (dy < -dp(70f) * gd) {
                            vTriggered = true
                            host?.gesture(S.G_UP)
                        } else if (dy > dp(90f) * gd) {
                            vTriggered = true
                            host?.gesture(S.G_DOWN)
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLong()
                handler.removeCallbacks(edgeRun)
                edgeDir = 0
                val cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL
                vt?.addMovement(ev)
                when (touchMode) {
                    M_MULTI -> finishMulti()
                    M_DRAWER -> {
                        vt?.computeCurrentVelocity(1000)
                        val vy = vt?.yVelocity ?: 0f
                        val p = clamp01(-(y - downY) / drawerDragDist)
                        // se abre si pasó un tercio del recorrido o si el gesto fue rápido hacia arriba
                        val open = !cancelled && (p > 0.32f || vy < -900f) && vy < 600f
                        if (open) Hx.gesture(this, S.G_UP)
                        host?.drawerDrag(2, if (open) 1f else 0f, -vy / drawerDragDist)
                    }
                    M_DRAG -> finishDrag(x, y)
                    M_PAGE -> {
                        vt?.computeCurrentVelocity(1000)
                        val v = -(vt?.xVelocity ?: 0f) / width
                        val n = (pageCount() - 1).toFloat()
                        // gestos de borde: tirar más allá de la primera o la última página
                        if (pageStart.roundToInt() == 0 && pageSp.value < -0.12f && S.gestAction(S.G_LEFT_EDGE) != S.A_NONE && !S.b(S.PAGE_LOOP)) host?.gesture(S.G_LEFT_EDGE)
                        else if (pageStart.roundToInt().toFloat() == n && pageSp.value > n + 0.12f && S.gestAction(S.G_RIGHT_EDGE) != S.A_NONE && !S.b(S.PAGE_LOOP)) host?.gesture(S.G_RIGHT_EDGE)
                        settle(v)
                    }
                    M_NONE -> if (!moved && !cancelled) tap(x, y) else if (pageSp.value != pageSp.value.roundToInt().toFloat()) settle(0f)
                    else -> {}
                }
                touchMode = M_NONE
                dropShown = false
                if (dotsBoost.target > 0f) {
                    Mo.tune(dotsBoost, S.MO_PAGE, 120f, 1f)
                    dotsBoost.to(0f)
                }
                endScrub()
                vt?.recycle()
                vt = null
                return true
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        if (inDock) {
            val s = slotAt(x) ?: return
            when (s.kind) {
                1 -> {
                    bounce(slots.indexOf(s))
                    host?.openDrawer()
                }
                2 -> {
                    bounce(slots.indexOf(s))
                    host?.openRecents()
                }
                0 -> {
                    val e = AppRepo.get(s.key ?: return) ?: return
                    val i = slots.indexOf(s)
                    bounce(i)
                    val sz = dockIconPx * s.scale
                    val r = RectF(s.cx - sz / 2f, dockCy - sz / 2f, s.cx + sz / 2f, dockCy + sz / 2f)
                    host?.launch(e, r, true)
                }
            }
        } else {
            val k = homeKeyAt(x, y)
            if (k == null) {
                emptyTap(x, y)
                return
            }
            val e = AppRepo.get(k) ?: return
            val r = RectF()
            homeIconRect(homeKeys.indexOf(k), r)
            host?.launch(e, r, false)
        }
    }

    private val tapRun = Runnable {
        val n = tapCount
        tapCount = 0
        if (n == 2) host?.gesture(S.G_DTAP)
    }

    /** toque en un hueco: reloj, fecha, doble toque o triple toque */
    private fun emptyTap(x: Float, y: Float) {
        if (S.b(S.CK_TAP) && clockRect.contains(x, y)) {
            boing(2.2f)
            host?.special(0)
            return
        }
        if (S.b(S.DT_TAP) && dateRect.contains(x, y)) {
            boing(2.2f)
            host?.special(1)
            return
        }
        val now = System.currentTimeMillis()
        val win = S.f(S.G_DTAP_MS).toLong()
        tapCount = if (now - lastTapT <= win) tapCount + 1 else 1
        lastTapT = now
        handler.removeCallbacks(tapRun)
        val triple = S.gestAction(S.G_TRIPLE) != S.A_NONE
        if (tapCount >= 3 && triple) {
            tapCount = 0
            host?.gesture(S.G_TRIPLE)
        } else if (tapCount == 2) {
            if (triple) handler.postDelayed(tapRun, win) else {
                tapCount = 0
                host?.gesture(S.G_DTAP)
            }
        }
    }

    /** evalúa el gesto de dos dedos al levantar uno */
    private fun finishMulti() {
        if (multiDone) return
        multiDone = true
        val sens = S.p(S.G_PINCH).coerceAtLeast(0.2f)
        val ratio = dNow / d0
        val dy = yAvg - y0avg
        val dist = dp(60f) * S.p(S.G_SWIPE_DIST)
        when {
            ratio < 1f - 0.28f / sens -> host?.gesture(S.G_PINCH_IN)
            ratio > 1f + 0.35f / sens -> host?.gesture(S.G_PINCH_OUT)
            dy < -dist -> host?.gesture(S.G_TWO_UP)
            dy > dist -> host?.gesture(S.G_TWO_DOWN)
            System.currentTimeMillis() - multiT0 < 300 -> host?.gesture(S.G_TWO_TAP)
        }
    }

    private fun startDrag(x: Float, y: Float) {
        if (downKey == null) {
            touchMode = M_NONE
            return
        }
        dragKey = downKey
        dragFromDock = inDock
        dragX = x
        dragY = y
        touchMode = M_DRAG
        scrubbing = false
        dropShown = false
        Mo.tune(dragLift, S.MO_PRESS, 420f, 0.55f)
        dragLift.snap(0f)
        dragLift.to(1f)
        dragTilt.snap(0f)
        ticker.kick()
        Hx.fire(this, S.HE_LONG)
        invalidate()
    }

    /** pone el icono [key] donde está el dedo (para que vuele desde ahí a su celda o se encoja ahí) */
    private fun iconFromFinger(key: String, e: AppEntry?) {
        var p = iconPos[key]
        if (p == null) {
            p = IconPos(key)
            iconPos[key] = p
            iconList.add(p)
        }
        p.page = pageSp.value.roundToInt()
        p.e = e
        p.x.snap(dragX)
        p.y.snap(dragY - dp(14f) * dragLift.value)
        p.s.snap(lerp(1f, 1.22f, dragLift.value))
        Mo.tune(p.s, S.MO_PRESS, 420f, 0.55f)
        p.s.to(1f)
        ticker.kick()
    }

    private fun finishDrag(x: Float, y: Float) {
        val key = dragKey ?: return
        val de = AppRepo.get(key)
        if (!dragFromDock) {
            if (y < insetTop + dp(80f) && S.b(S.H_REMOVE_DRAG)) {
                iconFromFinger(key, de)
                makeGhost(key)
                HomeSlots.remove(homeKeys, key)
            } else if (S.b(S.K_SHOW) && y > dockTopY() - dp(4f)) {
                if (key !in dockKeys && dockKeys.size < S.i(S.K_MAX)) {
                    dockKeys.add(key)
                    HomeSlots.remove(homeKeys, key)
                    iconPos.remove(key)?.let { iconList.remove(it) }
                } else iconFromFinger(key, de)
            } else {
                iconFromFinger(key, de)
                val to = dropIndex(x, y)
                val from = homeKeys.indexOf(key)
                if (from >= 0 && to >= 0 && to != from) {
                    while (homeKeys.size <= to) homeKeys.add(HomeSlots.BLANK)
                    val there = homeKeys[to]
                    if (there == HomeSlots.BLANK || AppRepo.get(there) == null) {
                        // celda vacía: el icono se pone ahí y deja su hueco
                        homeKeys[to] = key
                        homeKeys[from] = HomeSlots.BLANK
                    } else {
                        homeKeys.removeAt(from)
                        homeKeys.add(min(to, homeKeys.size), key)
                    }
                }
            }
        } else {
            if (y < dockTopY() - dp(70f)) {
                dockKeys.remove(key)
                // al sacarla del dock vuelve al inicio (no se pierde)
                if (key !in homeKeys) {
                    HomeSlots.add(homeKeys, key)
                    iconFromFinger(key, de)
                }
            } else {
                val from = dockKeys.indexOf(key)
                var best = from
                var bd = Float.MAX_VALUE
                var count = 0
                for (s in slots) {
                    if (s.kind != 0) continue
                    val d = abs(x - s.rest)
                    if (d < bd) {
                        bd = d
                        best = count
                    }
                    count++
                }
                if (from >= 0 && best >= 0) {
                    dockKeys.removeAt(from)
                    dockKeys.add(min(best, dockKeys.size), key)
                }
            }
        }
        dragKey = null
        Prefs.setHomeKeys(homeKeys)
        Prefs.setDockKeys(dockKeys)
        relayout()
        val maxPage = (pageCount() - 1).toFloat()
        if (pageSp.value > maxPage) pageSp.to(maxPage)
        ticker.kick()
        invalidate()
    }
}
