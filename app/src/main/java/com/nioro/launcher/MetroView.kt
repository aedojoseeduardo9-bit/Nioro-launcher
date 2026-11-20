package com.nioro.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Modo Mosaico: la pantalla de inicio de Windows Phone 8.1, dibujada a mano.
 *
 *  - Cuadrícula de 4 o 6 columnas pequeñas (2 o 3 mosaicos medianos), con la misma separación
 *    entre mosaicos que en los bordes (12 px sobre 480 px en Windows Phone).
 *  - Cuatro tamaños: pequeño (1×1), mediano (2×2), ancho (4×2) y grande (4×4). Los pequeños rellenan
 *    los huecos de 2×2 y los grandes siempre empiezan en una casilla par, como en el teléfono.
 *  - Colores planos: los 20 colores de acento de Windows Phone 8 (o el color de cada app), fondo negro,
 *    blanco o el fondo de pantalla, y mosaicos transparentes que dejan ver el fondo (Windows Phone 8.1).
 *  - Iconos blancos de una sola tinta (los iconos temáticos de Android 13 cuando la app los trae).
 *  - Mosaicos vivos: de vez en cuando uno gira en 3D y enseña su reverso; el Reloj marca la hora y el
 *    Calendario el día.
 *  - Inclinación al tocar: el mosaico se hunde hacia el punto tocado (hasta 0,3 rad y 25 px, los valores
 *    del efecto original) y al soltar vuelve.
 *  - Torniquete: al abrir una app los mosaicos giran sobre el borde izquierdo de la pantalla, fila a
 *    fila, y el tocado sale el último; al volver entran girando al revés.
 *  - Mantener pulsado: modo edición. Los demás mosaicos se alejan y se apagan, el elegido muestra el
 *    botón de tamaño (abajo a la derecha) y el de opciones (arriba a la derecha) y se puede arrastrar; los
 *    demás se recolocan solos. Cerca del borde la lista se desplaza sola.
 *  - Al llegar al final de la lista, el contenido se comprime un poco en vez de estirarse.
 *
 * No usa la letra ni los logotipos de Microsoft: la letra es la del sistema.
 */
class MetroView(ctx: Context) : HomeModeView(ctx) {

    companion object {
        /** colores de acento de Windows Phone 8, en su orden */
        val ACCENTS = intArrayOf(
            0xFFA4C400.toInt(), 0xFF60A917.toInt(), 0xFF008A00.toInt(), 0xFF00ABA9.toInt(), 0xFF1BA1E2.toInt(),
            0xFF0050EF.toInt(), 0xFF6A00FF.toInt(), 0xFFAA00FF.toInt(), 0xFFF472D0.toInt(), 0xFFD80073.toInt(),
            0xFFA20025.toInt(), 0xFFE51400.toInt(), 0xFFFA6800.toInt(), 0xFFF0A30A.toInt(), 0xFFE3C800.toInt(),
            0xFF825A2C.toInt(), 0xFF6D8764.toInt(), 0xFF647687.toInt(), 0xFF76608A.toInt(), 0xFF87794E.toInt()
        )
        val ACCENT_NAMES = arrayOf(
            "Lima", "Verde", "Esmeralda", "Verde azulado", "Cian", "Cobalto", "Índigo", "Violeta", "Rosa", "Magenta",
            "Carmesí", "Rojo", "Naranja", "Ámbar", "Amarillo", "Marrón", "Oliva", "Acero", "Malva", "Topo",
            "Color de cada app"
        )
        val COL_NAMES = arrayOf("4 columnas (2 medianos)", "6 columnas (3 medianos, «más mosaicos»)")
        val BG_NAMES = arrayOf("Negro (tema oscuro)", "Blanco (tema claro)", "Fondo de pantalla")
        val ICON_NAMES = arrayOf("Blancos de una tinta", "Logotipo de la app", "Icono normal")

        const val SMALL = 0
        const val MEDIUM = 1
        const val WIDE = 2
        const val LARGE = 3
        private val UW = intArrayOf(1, 2, 4, 4)
        private val UH = intArrayOf(1, 2, 2, 4)

        /** grupos de la lista: «#», a-z y el globo (otros alfabetos) */
        const val GROUPS = 28
        private val GROUP_LABELS = arrayOf("#", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
            "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "")
        private val MARKS = Regex("\\p{Mn}+")

        private const val FLIP_MS = 820f
        private const val TURN_MS = 240f
        private const val TURN_SPREAD = 190f
    }

    // ---------------------------------------------------------------- Datos
    private val items = ArrayList<AppEntry>()
    private var sizes = HashMap<String, Int>()
    /** 0 nada, 1 reloj, 2 calendario */
    private var live = IntArray(0)
    private var cCol = IntArray(0)
    private var cRow = IntArray(0)
    private var cols = 6
    private var unit = 0f
    private var gap = 0f
    private var side = 0f
    private var top0 = 0f
    private var contentH = 0f
    private var laidOut = false
    private val grid = ArrayList<BooleanArray>()

    // posición animada de cada mosaico (al recolocar o cambiar de tamaño se deslizan)
    private val posX = ticker.add(SpringMap())
    private val posY = ticker.add(SpringMap())
    private val sizW = ticker.add(SpringMap())
    private val sizH = ticker.add(SpringMap())

    // ---------------------------------------------------------------- Pintura
    private val tileP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val monoP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labelP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bigP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val smallP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val badgeP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path()
    /** nombres ya recortados, por tamaño de mosaico (sin crear textos al dibujar) */
    private val labelNames = Array(4) { HashMap<String, String>() }
    private val backNames = Array(4) { HashMap<String, String>() }
    private val rect = RectF()
    private val m = Matrix()
    private val src = FloatArray(8)
    private val dstP = FloatArray(8)
    private val qx = FloatArray(4)
    private val qy = FloatArray(4)
    private val qz = FloatArray(4)

    // ---------------------------------------------------------------- Iconos de una tinta
    private var iconExec: ExecutorService? = null
    private val metro = HashMap<String, Bitmap?>()
    private val loading = HashSet<String>()
    private var iconMode = -1

    // ---------------------------------------------------------------- Mosaicos vivos
    private class Flip {
        var key = ""
        var t0 = 0L
        var hold = 0L
        /** -1 libre, 0 girando al reverso, 1 mostrando el reverso, 2 volviendo */
        var phase = -1
    }
    private val flips = Array(3) { Flip() }
    private val rnd = Random()
    private val flipRun = Runnable { startFlip(); scheduleFlip() }
    private val kickRun = Runnable { ticker.kick() }
    private val minuteRun = Runnable { updateLive(); scheduleMinute() }
    private var timeStr = ""
    private var dayStr = ""
    private var weekStr = ""
    private var visibleNow = false

    // ---------------------------------------------------------------- Toques
    private var vt: VelocityTracker? = null
    private var dX = 0f
    private var dY = 0f
    private var lastY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var moved = false
    private var scrolling = false
    private var longDone = false
    private var tilting = -1
    private var tiltX = 0f
    private var tiltY = 0f
    private val tiltSp = ticker.add(SpringValue(0f, 900f, 60f, 0.002f))
    /** 0 nada, 1 botón de opciones, 2 botón de tamaño */
    private var badgeDown = 0
    private val badgeOpt = RectF()
    private val badgeSize = RectF()

    // edición
    var editing = false
        private set
    private var sel = -1
    private val editSp = ticker.add(SpringValue(0f, 260f, 26f, 0.002f))
    private var draggingTile = false
    private var grabDx = 0f
    private var grabDy = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var lastSwapTo = -1
    private var lastSwapAt = 0L

    // torniquete al abrir
    private var turnOut = false
    private var turnT0 = 0L
    private var turnSel = -1
    private var turnFired = false
    private val turnRect = RectF()
    private val turnReset = Runnable { if (turnOut) { turnOut = false; turnFromList = false; invalidate() } }

    private val longRun = Runnable {
        val i = downIdx
        if (i in items.indices && !moved && !editing) {
            longDone = true
            releaseTilt()
            enterEdit(i)
        }
    }

    init {
        labelP.color = Color.WHITE
        labelP.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        bigP.color = Color.WHITE
        bigP.typeface = Typeface.create(Typeface.DEFAULT, 300, false)
        smallP.color = Color.WHITE
        smallP.typeface = Typeface.create(Typeface.DEFAULT, 400, false)
        monoP.color = Color.WHITE
        updateLive()
    }

    // ================================================================ Ajustes
    private fun bgMode(): Int = S.i(S.MZ_BG).coerceIn(0, 2)
    private fun windows(): Boolean = S.b(S.MZ_TRANSP) && bgMode() != 1
    private fun bgColor(): Int = if (bgMode() == 1) Color.WHITE else Color.BLACK
    override val fadeColor: Int get() = if (windows()) Color.BLACK else when (bgMode()) { 0 -> Color.BLACK; 1 -> Color.WHITE; else -> 0 }

    private fun accentOf(e: AppEntry?): Int {
        val a = S.i(S.MZ_ACCENT)
        if (a >= ACCENTS.size) {
            // letras y botones: el cian de siempre
            if (e == null) return ACCENTS[4]
            // color de cada app, un poco más oscuro y plano (los mosaicos no tienen degradado)
            return blend(e.tint or 0xFF000000.toInt(), Color.BLACK, 0.18f)
        }
        return ACCENTS[a.coerceIn(0, ACCENTS.size - 1)]
    }

    private fun sizeOf(i: Int): Int = (sizes[items[i].key] ?: MEDIUM).coerceIn(SMALL, LARGE)

    // ================================================================ Datos y colocación
    override fun onDataChanged() {
        rebuild()
        relayout()
    }

    private fun rebuild() {
        val byKey = HashMap<String, AppEntry>()
        for (e in shown) byKey[e.key] = e
        val order = Prefs.metroOrder()
        // versión 2: en el inicio solo las apps ancladas (las demás están en la lista). Si el Mosaico de la
        // versión anterior tenía todas las apps, se vuelve a empezar con las favoritas.
        if (!Prefs.metroV2) {
            Prefs.metroV2 = true
            if (order.size >= max(12, (shown.size * 0.85f).toInt())) order.clear()
        }
        val first = order.isEmpty()
        sizes = Prefs.metroSizes()
        items.clear()
        val seen = HashSet<String>()
        for (k in order) {
            val e = byKey[k] ?: continue
            if (seen.add(k)) items.add(e)
        }
        var favs = 0
        if (first) {
            // la primera vez: las favoritas (dock e inicio) arriba, en mediano y alguno ancho
            for (k in Prefs.dockKeys() + Prefs.homeKeys()) {
                val e = byKey[k] ?: continue
                if (!seen.add(k)) continue
                items.add(e)
                if (!sizes.containsKey(k)) sizes[k] = if (favs % 5 == 2) WIDE else MEDIUM
                favs++
            }
        }
        if (first) {
            // el Reloj y el Calendario se anclan también: son los mosaicos vivos
            for (e in shown) {
                val p = e.pkg.lowercase(Locale.ROOT)
                if ((p.contains("calendar") || p.contains("clock")) && seen.add(e.key)) items.add(e)
            }
            // sin favoritas, las primeras apps por orden alfabético
            if (items.isEmpty()) for (e in shown.take(8)) if (seen.add(e.key)) items.add(e)
        }
        val changed0 = first || items.size != order.size
        var changed = changed0
        live = IntArray(items.size)
        for ((i, e) in items.withIndex()) {
            val p = e.pkg.lowercase(Locale.ROOT)
            live[i] = when {
                p.contains("calendar") -> 2
                p.contains("clock") -> 1
                else -> 0
            }
            if (!sizes.containsKey(e.key)) {
                // apps sin tamaño: pequeñas en la primera colocación (rellenan bloques de 2×2), medianas si
                // se instalan después; el Reloj y el Calendario, medianos para que se vean vivos
                sizes[e.key] = MEDIUM
                changed = true
            }
        }
        if (changed) save()
        clearNames()
        buildList()
    }

    private fun clearNames() {
        for (mm in labelNames) mm.clear()
        for (mm in backNames) mm.clear()
    }

    private fun save() {
        val keys = ArrayList<String>(items.size)
        for (e in items) keys.add(e.key)
        Prefs.setMetroOrder(keys)
        Prefs.setMetroSizes(sizes)
    }

    override fun relayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        cols = if (S.i(S.MZ_COLS) == 0) 4 else 6
        // Windows Phone: 12 px de separación sobre 480 px de ancho, igual en los bordes
        gap = w * (if (cols == 4) 0.025f else 0.0185f)
        side = gap
        unit = (w - side * 2f - gap * (cols - 1)) / cols
        top0 = insetTop + gap
        labelP.textSize = if (cols == 4) dp(13.5f) else dp(12f)
        pack()
        val viewH = h - insetBottom - insetTop
        scroll.dim = viewH
        scroll.min = 0f
        scroll.max = max(0f, endY() - (h - insetBottom))
        if (scroll.value > scroll.max) scroll.jump(scroll.max)
        relayoutList()
        for (i in items.indices) target(i, !laidOut)
        laidOut = items.isNotEmpty()
        clearNames()
        applyBars()
        if (iconMode != S.i(S.MZ_ICONS)) {
            iconMode = S.i(S.MZ_ICONS)
            metro.clear()
            loading.clear()
        }
        ticker.kick()
    }

    /** coloca los mosaicos en orden en el primer hueco donde quepan (los grandes, en casillas pares) */
    private fun pack() {
        val n = items.size
        if (cCol.size != n) {
            cCol = IntArray(n)
            cRow = IntArray(n)
        }
        grid.clear()
        var maxRow = 0
        for (i in 0 until n) {
            val s = sizeOf(i)
            val w = min(UW[s], cols)
            val hh = UH[s]
            val big = s != SMALL
            var r = 0
            var placed = false
            while (!placed) {
                if (big && r % 2 != 0) {
                    r++
                    continue
                }
                var c = 0
                while (c + w <= cols) {
                    if (free(r, c, w, hh)) {
                        take(r, c, w, hh)
                        cCol[i] = c
                        cRow[i] = r
                        placed = true
                        break
                    }
                    c += if (big) 2 else 1
                }
                if (!placed) r++
            }
            maxRow = max(maxRow, cRow[i] + hh)
        }
        contentH = if (maxRow > 0) maxRow * (unit + gap) - gap else 0f
    }

    private fun rowAt(r: Int): BooleanArray {
        while (grid.size <= r) grid.add(BooleanArray(cols))
        return grid[r]
    }

    private fun free(r: Int, c: Int, w: Int, h: Int): Boolean {
        for (y in r until r + h) {
            val row = rowAt(y)
            for (x in c until c + w) if (row[x]) return false
        }
        return true
    }

    private fun take(r: Int, c: Int, w: Int, h: Int) {
        for (y in r until r + h) {
            val row = rowAt(y)
            for (x in c until c + w) row[x] = true
        }
    }

    private fun tx(i: Int): Float = side + cCol[i] * (unit + gap)
    private fun ty(i: Int): Float = top0 + cRow[i] * (unit + gap)
    private fun tw(i: Int): Float { val u = min(UW[sizeOf(i)], cols); return u * unit + (u - 1) * gap }
    private fun th(i: Int): Float { val u = UH[sizeOf(i)]; return u * unit + (u - 1) * gap }

    private fun target(i: Int, snap: Boolean) {
        val k = items[i].key
        val x = tx(i)
        val y = ty(i)
        val w = tw(i)
        val h = th(i)
        val a = posX.get(k, x, 320f, 32f)
        val b = posY.get(k, y, 320f, 32f)
        val c = sizW.get(k, w, 360f, 34f)
        val d = sizH.get(k, h, 360f, 34f)
        if (snap) {
            a.snap(x); b.snap(y); c.snap(w); d.snap(h)
        } else {
            a.to(x); b.to(y); c.to(w); d.to(h)
        }
    }

    // ================================================================ Barras del sistema
    /** con el fondo blanco los iconos de la barra de estado pasan a oscuros (si no, no se verían) */
    private fun applyBars() {
        val act = context as? Activity ?: return
        val light = bgMode() == 1 && isAttachedToWindow
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                act.window.insetsController?.setSystemBarsAppearance(
                    if (light) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                val dv = act.window.decorView
                @Suppress("DEPRECATION")
                dv.systemUiVisibility = if (light) dv.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                else dv.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        } catch (t: Throwable) {
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyBars()
    }

    override fun onDetachedFromWindow() {
        uiHandler.removeCallbacks(flipRun)
        uiHandler.removeCallbacks(minuteRun)
        iconExec?.shutdownNow()
        iconExec = null
        val act = context as? Activity
        if (act != null && bgMode() == 1) {
            try {
                if (Build.VERSION.SDK_INT >= 30) act.window.insetsController?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            } catch (t: Throwable) {
            }
        }
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        visibleNow = visibility == VISIBLE
        if (visibleNow) {
            updateLive()
            scheduleMinute()
            scheduleFlip()
        } else {
            // nada de trabajo mientras el inicio no se ve
            uiHandler.removeCallbacks(flipRun)
            uiHandler.removeCallbacks(minuteRun)
            uiHandler.removeCallbacks(kickRun)
            for (f in flips) f.phase = -1
            closeOverlays()
        }
    }

    // ================================================================ Mosaicos vivos
    private fun updateLive() {
        val cal = Calendar.getInstance()
        val h24 = android.text.format.DateFormat.is24HourFormat(context)
        val hr = cal.get(Calendar.HOUR_OF_DAY)
        val mi = cal.get(Calendar.MINUTE)
        timeStr = if (h24) String.format(Locale.ROOT, "%d:%02d", hr, mi)
        else String.format(Locale.ROOT, "%d:%02d", if (hr % 12 == 0) 12 else hr % 12, mi)
        dayStr = cal.get(Calendar.DAY_OF_MONTH).toString()
        weekStr = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault()) ?: ""
        invalidate()
    }

    private fun scheduleMinute() {
        uiHandler.removeCallbacks(minuteRun)
        if (!visibleNow) return
        val now = System.currentTimeMillis()
        uiHandler.postDelayed(minuteRun, 60_000L - now % 60_000L + 50L)
    }

    private fun scheduleFlip() {
        uiHandler.removeCallbacks(flipRun)
        if (!visibleNow || !S.b(S.MZ_LIVE) || S.b(S.P_REDUCE)) return
        uiHandler.postDelayed(flipRun, 5000L + rnd.nextInt(6000))
    }

    /** elige al azar un mosaico visible (mediano, ancho o grande) y lo hace girar */
    private fun startFlip() {
        if (editing || turnOut || items.isEmpty()) return
        val slot = flips.firstOrNull { it.phase < 0 } ?: return
        val off = scroll.value
        val top = insetTop
        val bottom = height - insetBottom
        var pick = -1
        var seenN = 0
        for (i in items.indices) {
            if (sizeOf(i) == SMALL || live[i] != 0) continue
            val y = ty(i) - off
            if (y + th(i) < top || y > bottom) continue
            val k = items[i].key
            if (flips.any { it.phase >= 0 && it.key == k }) continue
            seenN++
            if (rnd.nextInt(seenN) == 0) pick = i
        }
        if (pick < 0) return
        slot.key = items[pick].key
        slot.t0 = SystemClock.uptimeMillis()
        slot.phase = 0
        ticker.kick()
    }

    private fun easeInOut(t: Float): Float {
        val x = clamp01(t)
        return if (x < 0.5f) 2f * x * x else 1f - (-2f * x + 2f).let { it * it } / 2f
    }

    /** ángulo de giro (0..360) del mosaico [k] */
    private fun flipAngle(k: String, now: Long): Float {
        for (f in flips) {
            if (f.phase < 0 || f.key != k) continue
            val e = (now - f.t0).toFloat()
            return when (f.phase) {
                0 -> 180f * easeInOut(e / FLIP_MS)
                1 -> 180f
                else -> 180f + 180f * easeInOut(e / FLIP_MS)
            }
        }
        return 0f
    }

    /** avanza los giros; true mientras alguno se mueve */
    private fun stepFlips(now: Long): Boolean {
        var moving = false
        for (f in flips) {
            when (f.phase) {
                0 -> if (now - f.t0 >= FLIP_MS) {
                    f.phase = 1
                    f.t0 = now
                    f.hold = 3200L + rnd.nextInt(2800)
                    uiHandler.postDelayed(kickRun, f.hold + 20L)
                } else moving = true
                1 -> if (now - f.t0 >= f.hold) {
                    f.phase = 2
                    f.t0 = now
                    moving = true
                }
                2 -> if (now - f.t0 >= FLIP_MS) f.phase = -1 else moving = true
            }
        }
        return moving
    }

    // ================================================================ Iconos
    /** icono de una tinta (o logotipo) cargado aparte; null mientras carga o si la app no lo tiene */
    private fun metroIcon(e: AppEntry): Bitmap? {
        if (iconMode == 2) return null
        if (metro.containsKey(e.key)) return metro[e.key]
        if (loading.add(e.key)) {
            val cur = iconExec
            val ex: ExecutorService = if (cur == null || cur.isShutdown) Executors.newSingleThreadExecutor() else cur
            iconExec = ex
            val app = context.applicationContext
            val mode = iconMode
            val k = e.key
            val pkg = e.pkg
            val cls = e.cls
            try {
                ex.execute {
                    val b = try { buildIcon(app, pkg, cls, mode) } catch (t: Throwable) { null }
                    uiHandler.post {
                        if (mode == iconMode) {
                            metro[k] = b
                            loading.remove(k)
                            invalidate()
                        }
                    }
                }
            } catch (t: Throwable) {
                loading.remove(k)
            }
        }
        return null
    }

    private fun buildIcon(app: Context, pkg: String, cls: String, mode: Int): Bitmap? {
        val d0 = app.packageManager.getActivityIcon(ComponentName(pkg, cls))
        val d = d0.mutate()
        if (d !is AdaptiveIconDrawable) return null
        if (mode == 0 && Build.VERSION.SDK_INT >= 33) {
            val mono = d.monochrome
            if (mono != null) return render(mono, (dp(80f)).toInt().coerceIn(96, 320), Bitmap.Config.ALPHA_8)
        }
        val fg = d.foreground ?: return null
        return render(fg, dp(64f).toInt().coerceIn(96, 256), Bitmap.Config.ARGB_8888)
    }

    /** dibuja la parte central de la capa del icono (lo que se ve dentro de la máscara) */
    private fun render(dr: Drawable, s: Int, cfg: Bitmap.Config): Bitmap {
        val b = Bitmap.createBitmap(s, s, cfg)
        val c = Canvas(b)
        val pad = (s * 0.25f).toInt()
        dr.setBounds(-pad, -pad, s + pad, s + pad)
        dr.draw(c)
        return b
    }

    // ================================================================ Dibujo
    override fun drawUnder(c: Canvas) {
        val win = windows()
        if (bgMode() == 2 && !win) return
        tileP.shader = null
        tileP.color = if (win) Color.BLACK else bgColor()
        tileP.alpha = 255
        val w = width.toFloat()
        // fuera de la zona de los mosaicos (barra de estado y, sin dock, la parte de abajo)
        c.drawRect(0f, 0f, w, insetTop, tileP)
        if (ScreenModes.dockOff()) c.drawRect(0f, height - insetBottom, w, height.toFloat(), tileP)
    }

    override fun onFrame(dt: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        var more = stepFlips(now)
        if (turnOut) {
            more = true
            val e = (now - turnT0).toFloat()
            if (!turnFired && e >= TURN_SPREAD + TURN_MS + 70f) {
                turnFired = true
                val i = turnSel
                val fromList = turnLaunch
                if (turnFromList && fromList != null) listener?.onLaunch(fromList, RectF(turnRect))
                else if (i in items.indices) listener?.onLaunch(items[i], RectF(turnRect))
                uiHandler.removeCallbacks(turnReset)
                uiHandler.postDelayed(turnReset, 2500L)
            }
            if (turnFired) more = false
        }
        if (jumpOpen && now - jumpT0 < 700L) more = true
        if (draggingTile) {
            // cerca del borde de arriba o de abajo, la lista se desplaza sola
            val edge = dp(72f)
            val top = insetTop
            val bottom = height - insetBottom
            val v = when {
                fingerY < top + edge -> -(1f - (fingerY - top) / edge)
                fingerY > bottom - edge -> 1f - (bottom - fingerY) / edge
                else -> 0f
            }
            if (v != 0f) {
                val nv = (scroll.value + v * dp(900f) * dt).coerceIn(scroll.min, scroll.max)
                if (nv != scroll.value) {
                    scroll.jump(nv)
                    reorderAt(fingerX, fingerY)
                }
            }
            more = true
        }
        invalidate()
        return more
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val top = insetTop
        val bottom = height - insetBottom
        val win = windows()
        val mode = bgMode()
        var layer = -1
        if (win) {
            // mosaicos transparentes: negro alrededor y el fondo de pantalla solo a través de los mosaicos
            layer = c.saveLayer(0f, top, width.toFloat(), bottom, null)
            c.drawColor(Color.BLACK)
        } else if (mode != 2) {
            c.drawColor(bgColor())
        }
        // dos pantallas, como en Windows Phone: los mosaicos y, a la derecha, la lista de apps
        val pg = pageSp.value
        val w = width.toFloat()
        if (pg < 0.999f) {
            c.save()
            c.translate(-pg * w, 0f)
            drawStart(c, now, top, bottom)
            c.restore()
        }
        if (pg > 0.001f) {
            c.save()
            c.translate((1f - pg) * w, 0f)
            drawList(c, now, top, bottom)
            drawMenu(c, top, bottom)
            c.restore()
        }
        if (jumpSp.value > 0.001f) drawJump(c, now, top, bottom)
        if (layer >= 0) c.restoreToCount(layer)
    }

    private fun drawStart(c: Canvas, now: Long, top: Float, bottom: Float) {
        val raw = scroll.value
        val off = raw.coerceIn(scroll.min, scroll.max)
        val over = raw - off
        val viewH = max(1f, bottom - top)
        // al pasarse del final, compresión (como las listas de Windows Phone) en vez de estiramiento
        val squish = (abs(over) / viewH * 0.55f).coerceAtMost(0.14f)
        val anchor = if (over < 0f) top else bottom
        val ek = editSp.value
        for (i in items.indices) {
            if (draggingTile && i == sel) continue
            val e = items[i]
            val k = e.key
            var x = posX.get(k, tx(i), 320f, 32f).value
            var y = posY.get(k, ty(i), 320f, 32f).value - off
            var w = sizW.get(k, tw(i), 360f, 34f).value
            var h = sizH.get(k, th(i), 360f, 34f).value
            if (squish > 0f) {
                y = anchor + (y - anchor) * (1f - squish)
                h *= 1f - squish
            }
            if (y + h < top - dp(40f) || y > bottom + dp(40f)) continue
            // modo edición: los demás se alejan y se apagan; el elegido se acerca un poco
            var a = 1f
            if (ek > 0.001f) {
                val s = if (i == sel) 1f + 0.03f * ek else 1f - 0.12f * ek
                if (i != sel) a = 1f - 0.45f * ek
                x += w * (1f - s) / 2f
                y += h * (1f - s) / 2f
                w *= s
                h *= s
            }
            drawTile(c, i, x, y, w, h, a, now, top, bottom)
        }
        // la flecha del final: lleva a la lista de todas las apps
        val d = arrowD()
        val ax = width - side - d / 2f
        val ay = top0 + contentH + gap * 2f + d / 2f - off
        arrowRect.set(ax - d * 0.75f, ay - d * 0.75f, ax + d * 0.75f, ay + d * 0.75f)
        if (ay - d < bottom && !(turnOut && !turnFromList)) {
            val al = (255f * (1f - 0.6f * ek) * clamp01(progress * 1.6f)).toInt().coerceIn(0, 255)
            ringP.color = fg()
            ringP.alpha = al
            ringP.strokeWidth = 2.6f * (width / 480f)
            c.drawCircle(ax, ay, d / 2f, ringP)
            val q = d * 0.2f
            c.drawLine(ax - q, ay, ax + q, ay, ringP)
            c.drawLine(ax + q * 0.1f, ay - q * 0.9f, ax + q, ay, ringP)
            c.drawLine(ax + q * 0.1f, ay + q * 0.9f, ax + q, ay, ringP)
        }
        if (draggingTile && sel in items.indices) {
            val k = items[sel].key
            val w = sizW.get(k, tw(sel)).value * 1.05f
            val h = sizH.get(k, th(sel)).value * 1.05f
            drawTile(c, sel, dragX - (w - w / 1.05f) / 2f, dragY - (h - h / 1.05f) / 2f, w, h, 0.92f, now, top, bottom)
        }
        if (editing && !draggingTile && sel in items.indices) drawBadges(c, off, ek)
    }

    /** dibuja un mosaico con sus efectos 3D (giro vivo, inclinación y torniquete) */
    private fun drawTile(c: Canvas, i: Int, x: Float, y: Float, w: Float, h: Float, alpha0: Float, now: Long, top: Float, bottom: Float) {
        val e = items[i]
        var alpha = alpha0
        // torniquete: al abrir, fila a fila, el tocado el último; al entrar, al revés
        var turn = 0f
        if (turnOut && !turnFromList) {
            val dly = if (i == turnSel) TURN_SPREAD + 60f else turnDelay(x, y, w, h, top, bottom, i)
            val t = clamp01(((now - turnT0).toFloat() - dly) / TURN_MS)
            turn = 90f * t * t
            alpha *= 1f - clamp01((t - 0.55f) / 0.45f)
            if (alpha <= 0.01f) return
        } else if (progress < 0.999f && S.b(S.MZ_TURN)) {
            val dly = turnDelay(x, y, w, h, top, bottom, i) / (TURN_SPREAD + TURN_MS)
            val t = clamp01((progress * 1.45f - dly * 0.45f))
            val k = 1f - t
            turn = -80f * k * k * k
            alpha *= clamp01(t * 2.5f)
            if (alpha <= 0.01f) return
        } else if (progress < 0.999f) {
            alpha *= clamp01(progress * 1.6f)
        }
        val flip = if (live[i] == 0) flipAngle(e.key, now) else 0f
        val tk = if (tilting == i) tiltSp.value else 0f
        val l = x
        val t = y
        val r = x + w
        val b = y + h
        // ¿qué cara se ve?
        val fm = ((flip % 360f) + 360f) % 360f
        val back = fm > 90f && fm < 270f
        val ang = when {
            back -> fm - 180f
            fm >= 270f -> fm - 360f
            else -> fm
        }
        c.save()
        if (ang != 0f || tk > 0.001f || turn != 0f) {
            if (!place(l, t, r, b, ang, tk, turn)) {
                c.restore()
                return
            }
            c.concat(m)
        }
        drawFace(c, i, e, l, t, r, b, back, alpha)
        c.restore()
    }

    /** retraso del torniquete según la posición: de arriba abajo y un poco de izquierda a derecha */
    private fun turnDelay(x: Float, y: Float, w: Float, h: Float, top: Float, bottom: Float, i: Int): Float {
        val fy = clamp01((y + h / 2f - top) / max(1f, bottom - top))
        val fx = clamp01((x + w / 2f) / max(1f, width.toFloat()))
        // un toque de azar fijo por mosaico (la animación original no es perfectamente regular)
        val j = ((items[i].key.hashCode() and 0x7fffffff) % 100) / 100f
        return fy * TURN_SPREAD * 0.75f + fx * TURN_SPREAD * 0.15f + j * TURN_SPREAD * 0.1f
    }

    /**
     * Calcula las 4 esquinas en 3D y su proyección: [flipDeg] gira sobre el eje horizontal del mosaico,
     * [tk] lo inclina hacia el punto tocado y [turnDeg] lo gira sobre el borde izquierdo de la pantalla.
     */
    private fun place(l: Float, t: Float, r: Float, b: Float, flipDeg: Float, tk: Float, turnDeg: Float): Boolean {
        qx[0] = l; qy[0] = t
        qx[1] = r; qy[1] = t
        qx[2] = r; qy[2] = b
        qx[3] = l; qy[3] = b
        for (k in 0..3) qz[k] = 0f
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        if (flipDeg != 0f) {
            val a = Math.toRadians(flipDeg.toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            for (k in 0..3) {
                val dy = qy[k] - cy
                qy[k] = cy + dy * ca
                qz[k] += dy * sa
            }
        }
        if (tk > 0.001f) {
            // valores del efecto original: hasta 0,3 rad de giro y 25 px (sobre 480) de hundimiento
            val mag = clamp01(hypot(tiltX, tiltY))
            val dep = width * (25f / 480f) * tk * (1f - mag * 0.5f)
            val tn = tan(0.3f * mag * tk)
            val nx = if (mag > 0.001f) tiltX / max(mag, 0.001f) else 0f
            val ny = if (mag > 0.001f) tiltY / max(mag, 0.001f) else 0f
            for (k in 0..3) qz[k] += dep + ((qx[k] - cx) * nx + (qy[k] - cy) * ny) * tn
        }
        if (turnDeg != 0f) {
            val a = Math.toRadians(turnDeg.toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            for (k in 0..3) {
                val dx = qx[k]
                qx[k] = dx * ca
                qz[k] += dx * sa
            }
        }
        // perspectiva: con el torniquete, desde el centro de la pantalla; si no, desde el del mosaico
        val f = dp(1100f)
        val vx = if (turnDeg != 0f) width / 2f else cx
        val vy = if (turnDeg != 0f) (insetTop + height - insetBottom) / 2f else cy
        for (k in 0..3) {
            val d = f + qz[k]
            if (d < f * 0.15f) return false
            val s = f / d
            dstP[k * 2] = vx + (qx[k] - vx) * s
            dstP[k * 2 + 1] = vy + (qy[k] - vy) * s
        }
        src[0] = l; src[1] = t
        src[2] = r; src[3] = t
        src[4] = r; src[5] = b
        src[6] = l; src[7] = b
        return m.setPolyToPoly(src, 0, dstP, 0, 4)
    }

    /** la cara del mosaico: color plano, icono blanco, nombre abajo a la izquierda y contenido vivo */
    private fun drawFace(c: Canvas, i: Int, e: AppEntry, l: Float, t: Float, r: Float, b: Float, back: Boolean, alpha: Float) {
        val w = r - l
        val h = b - t
        val s = sizeOf(i)
        val al = (255f * alpha).toInt().coerceIn(0, 255)
        if (al == 0) return
        rect.set(l, t, r, b)
        val accent = accentOf(e)
        if (windows()) {
            // ventana al fondo de pantalla con un velo muy suave para que el texto se lea
            holeP.alpha = al
            c.drawRect(rect, holeP)
            tileP.color = 0x26000000
            tileP.alpha = (0x26 * alpha).toInt()
            c.drawRect(rect, tileP)
        } else {
            tileP.color = if (back) blend(accent, Color.BLACK, 0.22f) else accent
            tileP.alpha = al
            c.drawRect(rect, tileP)
        }
        val pad = max(dp(6f), min(w, h) * 0.065f)
        val showLabel = S.b(S.MZ_LABELS) && s != SMALL
        val labelSpace = if (showLabel) labelP.textSize + pad * 1.2f else 0f
        if (back) {
            // reverso: el nombre grande arriba a la izquierda y el icono pequeño abajo a la derecha
            bigP.textSize = min(h * 0.16f, dp(26f))
            bigP.alpha = al
            val n = backNames[s].getOrPut(e.key) {
                TextUtils.ellipsize(e.label, bigP, w - pad * 2f, TextUtils.TruncateAt.END).toString()
            }
            c.drawText(n, l + pad, t + pad - bigP.ascent(), bigP)
            drawGlyph(c, e, r - pad - h * 0.17f, b - pad - h * 0.17f, h * 0.30f, al)
            return
        }
        val lv = live[i]
        if (lv != 0 && s != SMALL) {
            if (lv == 1) {
                // Reloj: la hora en grande, fina, como el mosaico de Alarmas
                bigP.textSize = min(h * 0.34f, w * 0.30f)
                bigP.alpha = al
                c.drawText(timeStr, l + pad, t + pad - bigP.ascent() * 0.92f, bigP)
            } else {
                // Calendario: el número del día en grande y el día de la semana debajo
                bigP.textSize = min(h * 0.42f, w * 0.5f)
                bigP.alpha = al
                c.drawText(dayStr, l + pad, t + pad - bigP.ascent() * 0.88f, bigP)
                smallP.textSize = min(h * 0.11f, dp(16f))
                smallP.alpha = al
                c.drawText(weekStr, l + pad, t + pad - bigP.ascent() * 0.88f + smallP.textSize * 1.5f, smallP)
            }
        } else {
            val glyph = when (s) {
                SMALL -> h * 0.52f
                MEDIUM -> h * 0.40f
                WIDE -> h * 0.40f
                else -> h * 0.30f
            }
            val cy = if (showLabel) t + (h - labelSpace * 0.6f) / 2f else t + h / 2f
            drawGlyph(c, e, l + w / 2f, cy, glyph, al)
        }
        if (showLabel) {
            labelP.alpha = al
            val n = labelNames[s].getOrPut(e.key) {
                TextUtils.ellipsize(e.label, labelP, w - pad * 2f, TextUtils.TruncateAt.END).toString()
            }
            c.drawText(n, l + pad, b - pad * 0.95f, labelP)
        }
    }

    /** icono centrado en (cx, cy) con el dibujo del tamaño [glyph] */
    private fun drawGlyph(c: Canvas, e: AppEntry, cx: Float, cy: Float, glyph: Float, al: Int) {
        val mb = metroIcon(e)
        if (mb != null) {
            // de una tinta: el dibujo ocupa ~61 % del mapa; logotipo: ~70 %
            val d = if (mb.config == Bitmap.Config.ALPHA_8) glyph / 0.61f else glyph / 0.70f
            dst.set(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f)
            monoP.alpha = al
            c.drawBitmap(mb, null, dst, monoP)
            return
        }
        val ib = e.icon ?: return
        val d = glyph * 1.1f
        dst.set(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f)
        bmpPaint.alpha = al
        c.drawBitmap(ib, null, dst, bmpPaint)
    }

    /** botones redondos del modo edición: opciones arriba a la derecha y tamaño abajo a la derecha */
    private fun drawBadges(c: Canvas, off: Float, ek: Float) {
        val k = items[sel].key
        val x = posX.get(k, tx(sel)).value
        val y = posY.get(k, ty(sel)).value - off
        val w = sizW.get(k, tw(sel)).value * (1f + 0.03f * ek)
        val h = sizH.get(k, th(sel)).value * (1f + 0.03f * ek)
        val l = x - (w - sizW.get(k, tw(sel)).value) / 2f
        val t = y - (h - sizH.get(k, th(sel)).value) / 2f
        val rad = dp(15f) * ek
        if (rad < 1f) return
        val dark = bgMode() == 1
        val fill = if (dark) Color.BLACK else Color.WHITE
        val ink = if (dark) Color.WHITE else Color.BLACK
        // desanclar (la chincheta de Windows Phone)
        val ox = l + w
        val oy = t
        badgeOpt.set(ox - rad - dp(8f), oy - rad - dp(8f), ox + rad + dp(8f), oy + rad + dp(8f))
        badgeP.color = fill
        c.drawCircle(ox, oy, rad, badgeP)
        badgeLine.color = ink
        badgeLine.strokeWidth = dp(1.6f)
        badgeLine.style = Paint.Style.STROKE
        c.drawCircle(ox, oy, rad, badgeLine)
        badgeLine.strokeWidth = dp(1.8f)
        val pr = rad * 0.5f
        // cabeza de la chincheta, cuerpo y aguja, en diagonal
        arrow.reset()
        arrow.moveTo(ox + pr * 0.15f, oy - pr * 0.95f)
        arrow.lineTo(ox + pr * 0.95f, oy - pr * 0.15f)
        arrow.moveTo(ox + pr * 0.55f, oy - pr * 0.55f)
        arrow.lineTo(ox - pr * 0.05f, oy + pr * 0.05f)
        arrow.moveTo(ox - pr * 0.45f, oy - pr * 0.25f)
        arrow.lineTo(ox + pr * 0.25f, oy + pr * 0.45f)
        arrow.moveTo(ox - pr * 0.1f, oy + pr * 0.1f)
        arrow.lineTo(ox - pr * 0.9f, oy + pr * 0.9f)
        c.drawPath(arrow, badgeLine)
        badgeLine.strokeWidth = dp(1.6f)
        // tamaño: la flecha apunta hacia donde crecerá o se encogerá el mosaico
        val sx = l + w
        val sy = t + h
        badgeSize.set(sx - rad - dp(8f), sy - rad - dp(8f), sx + rad + dp(8f), sy + rad + dp(8f))
        badgeP.color = fill
        c.drawCircle(sx, sy, rad, badgeP)
        c.drawCircle(sx, sy, rad, badgeLine)
        val grow = nextSize(sizeOf(sel)) > sizeOf(sel)
        val q = rad * 0.42f
        arrow.reset()
        if (grow) {
            arrow.moveTo(sx - q, sy - q); arrow.lineTo(sx + q, sy + q)
            arrow.moveTo(sx + q, sy + q * 0.1f); arrow.lineTo(sx + q, sy + q); arrow.lineTo(sx + q * 0.1f, sy + q)
        } else {
            arrow.moveTo(sx + q, sy + q); arrow.lineTo(sx - q, sy - q)
            arrow.moveTo(sx - q, sy - q * 0.1f); arrow.lineTo(sx - q, sy - q); arrow.lineTo(sx - q * 0.1f, sy - q)
        }
        badgeLine.strokeWidth = dp(2f)
        c.drawPath(arrow, badgeLine)
    }

    /** mediano → pequeño → ancho → grande → mediano (el ciclo de Windows Phone, más el grande) */
    private fun nextSize(s: Int): Int = when (s) {
        MEDIUM -> SMALL
        SMALL -> WIDE
        WIDE -> LARGE
        else -> MEDIUM
    }

    // ================================================================ Posiciones en pantalla
    override fun hit(x: Float, y: Float): Int {
        val cy = y + scroll.value.coerceIn(scroll.min, scroll.max)
        for (i in items.indices) {
            val l = tx(i)
            val t = ty(i)
            if (x >= l && x <= l + tw(i) && cy >= t && cy <= t + th(i)) return i
        }
        return -1
    }

    override fun itemRect(i: Int): RectF {
        if (i !in items.indices) return RectF()
        val off = scroll.value.coerceIn(scroll.min, scroll.max)
        val k = items[i].key
        val x = posX.get(k, tx(i)).value
        val y = posY.get(k, ty(i)).value - off
        return RectF(x, y, x + sizW.get(k, tw(i)).value, y + sizH.get(k, th(i)).value)
    }

    // ================================================================ Edición
    private fun enterEdit(i: Int) {
        editing = true
        sel = i
        Mo.tune(editSp, S.MO_DRAWER_OPEN, 260f, 0.9f)
        editSp.to(1f)
        Hx.fire(this, S.HE_LONG)
        uiHandler.removeCallbacks(flipRun)
        for (f in flips) f.phase = -1
        listener?.onTouchStart()
        ticker.kick()
    }

    /** sale del modo edición (botón atrás, inicio o tocar un hueco); true si estaba editando */
    fun exitEdit(): Boolean {
        if (!editing) return false
        if (draggingTile) drop()
        editing = false
        sel = -1
        Mo.tune(editSp, S.MO_DRAWER_CLOSE, 260f, 1f)
        editSp.to(0f)
        save()
        scheduleFlip()
        ticker.kick()
        return true
    }

    private fun resizeSel() {
        if (sel !in items.indices) return
        val k = items[sel].key
        sizes[k] = nextSize(sizeOf(sel))
        pack()
        refreshBounds()
        for (i in items.indices) target(i, false)
        clearNames()
        save()
        Hx.fire(this, S.HE_SETTING)
        ticker.kick()
    }

    private fun refreshBounds() {
        val h = height.toFloat()
        scroll.max = max(0f, endY() - (h - insetBottom))
        scroll.clampNow()
    }

    /** final del contenido de los mosaicos: tras ellos va el botón de la flecha (a la lista de apps) */
    private fun endY(): Float = top0 + contentH + gap * 2f + arrowD() + gap * 3f

    private fun arrowD(): Float = 48f * (width / 480f)

    private fun startDrag(x: Float, y: Float) {
        val r = itemRect(sel)
        draggingTile = true
        grabDx = x - r.left
        grabDy = y - r.top
        dragX = r.left
        dragY = r.top
        lastSwapTo = -1
        Hx.fire(this, S.HE_DOCK)
        ticker.kick()
    }

    /** el mosaico arrastrado ocupa el sitio del que está bajo el dedo; los demás se recolocan */
    private fun reorderAt(x: Float, y: Float) {
        if (sel !in items.indices) return
        val cy = y + scroll.value.coerceIn(scroll.min, scroll.max)
        var to = -1
        for (i in items.indices) {
            if (i == sel) continue
            val l = tx(i)
            val t = ty(i)
            if (x >= l && x <= l + tw(i) && cy >= t && cy <= t + th(i)) {
                to = i
                break
            }
        }
        if (to < 0 || to == sel) return
        val now = SystemClock.uptimeMillis()
        if (to == lastSwapTo && now - lastSwapAt < 260L) return
        lastSwapTo = to
        lastSwapAt = now
        val e = items.removeAt(sel)
        items.add(to, e)
        // los datos vivos van con su app
        val lv = live.toMutableList()
        val moving = lv.removeAt(sel)
        lv.add(to, moving)
        live = lv.toIntArray()
        sel = to
        pack()
        refreshBounds()
        for (i in items.indices) if (i != sel) target(i, false)
        Hx.fire(this, S.HE_PAGE)
    }

    private fun drop() {
        if (sel !in items.indices) {
            draggingTile = false
            return
        }
        val k = items[sel].key
        val off = scroll.value.coerceIn(scroll.min, scroll.max)
        posX.get(k, dragX).snap(dragX)
        posY.get(k, dragY + off).snap(dragY + off)
        draggingTile = false
        target(sel, false)
        save()
        ticker.kick()
    }

    // ================================================================ Abrir
    private fun launch(i: Int) {
        val r = itemRect(i)
        if (!S.b(S.MZ_TURN) || S.b(S.P_REDUCE)) {
            listener?.onLaunch(items[i], r)
            return
        }
        turnRect.set(r)
        turnOut = true
        turnFromList = false
        turnFired = false
        turnSel = i
        turnT0 = SystemClock.uptimeMillis()
        for (f in flips) f.phase = -1
        ticker.kick()
    }

    override fun onOpen() {
        // al volver al inicio: los mosaicos entran girando (lo anima ScreenHost con progress)
        uiHandler.removeCallbacks(turnReset)
        turnOut = false
        turnFromList = false
        turnLaunch = null
        exitEdit()
        super.onOpen()
    }

    // ================================================================ Inclinación
    private fun startTilt(i: Int, x: Float, y: Float) {
        if (!S.b(S.MZ_TILT)) return
        val r = itemRect(i)
        ltilt = -1
        tilting = i
        tiltX = ((x - r.centerX()) / max(1f, r.width() / 2f)).coerceIn(-1f, 1f)
        tiltY = ((y - r.centerY()) / max(1f, r.height() / 2f)).coerceIn(-1f, 1f)
        tiltSp.tune(1400f, 1f)
        tiltSp.to(1f)
        Hx.fire(this, S.HE_ICON)
        ticker.kick()
    }

    private fun releaseTilt() {
        if (tilting < 0 && ltilt < 0) return
        tiltSp.tune(420f, 0.8f)
        tiltSp.to(0f)
        ticker.kick()
    }


    // ================================================================ Lista de apps (deslizar a la izquierda)
    /** fila de la lista: 0 lupa, 1 letra, 2 app, 3 título «nuevas», 4 cuadro de búsqueda, 5 sin resultados */
    private class LRow(val kind: Int, val group: Int, val e: AppEntry?) {
        var y = 0f
        var h = 0f
    }
    private val lrows = ArrayList<LRow>()
    private val alphaApps = ArrayList<AppEntry>()
    private val groupRow = IntArray(GROUPS) { -1 }
    private val lscroll = Scroll1D(ticker)
    private val pageSp = ticker.add(SpringValue(0f, 300f, 31f, 0.0005f))
    /** un píxel de Windows Phone (su pantalla medía 480 de ancho): todo se escala con el ancho */
    private var wpU = 1f
    private var lIcon = 0f
    private var lMargin = 0f
    private var lTop = 0f
    private var lContent = 0f
    private var imeH = 0f
    private val rowP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val headP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rowNames = HashMap<String, String>()
    private var lDown = -1
    private var ltilt = -1
    private var turnFromList = false
    private var turnRow = -1
    private val arrowRect = RectF()

    // búsqueda (la lupa)
    var searching = false
        private set
    private val qbuf = SpannableStringBuilder()
    private var queryNorm = ""
    private var caretOn = true
    private val caretRun = object : Runnable {
        override fun run() {
            caretOn = !caretOn
            invalidate()
            if (searching) uiHandler.postDelayed(this, 530L)
        }
    }

    // saltos por letra
    private var jumpOpen = false
    private val jumpSp = ticker.add(SpringValue(0f, 320f, 30f, 0.002f))
    private var jumpT0 = 0L
    private var jumpDown = -1
    private val jumpCell = RectF()
    private var jumpTile = 0f
    private var jumpGap = 0f
    private var jumpX0 = 0f
    private var jumpY0 = 0f

    // menú al mantener pulsada una app de la lista
    private var menuOpen = false
    private var menuRow = -1
    private val menuSp = ticker.add(SpringValue(0f, 420f, 34f, 0.002f))
    private val menuActs = IntArray(3)
    private var menuN = 0
    private val menuRect = RectF()
    private var menuItemH = 0f
    private var menuDown = -1
    private val longList = Runnable {
        val r = lDown
        if (r in lrows.indices && lrows[r].kind == 2 && !moved) {
            longDone = true
            releaseTilt()
            openMenu(r)
        }
    }

    private fun fg(): Int = if (bgMode() == 1) Color.BLACK else Color.WHITE

    /** grupo de la letra inicial: 0 «#», 1..26 a-z, 27 otros alfabetos (el globo) */
    private fun groupOf(label: String): Int {
        val ch = label.trim().firstOrNull() ?: return 0
        val n = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).firstOrNull()?.lowercaseChar() ?: ch
        if (n in 'a'..'z') return 1 + (n - 'a')
        if (n.isLetter()) return 27
        return 0
    }

    private fun norm(x: String): String =
        Normalizer.normalize(x.lowercase(Locale.getDefault()), Normalizer.Form.NFD).replace(MARKS, "")

    private fun buildList() {
        alphaApps.clear()
        alphaApps.addAll(shown)
        val col = java.text.Collator.getInstance(Locale.getDefault())
        col.strength = java.text.Collator.PRIMARY
        alphaApps.sortWith { a, b -> col.compare(a.label, b.label) }
        lrows.clear()
        for (g in 0 until GROUPS) groupRow[g] = -1
        if (searching) {
            lrows.add(LRow(4, 0, null))
            var n = 0
            if (queryNorm.isNotEmpty()) for (e in alphaApps) {
                if (norm(e.label).contains(queryNorm)) {
                    lrows.add(LRow(2, groupOf(e.label), e))
                    n++
                }
            }
            if (queryNorm.isNotEmpty() && n == 0) lrows.add(LRow(5, 0, null))
        } else {
            lrows.add(LRow(0, 0, null))
            // «nuevas»: lo instalado en los últimos 3 días, arriba del todo
            val now = System.currentTimeMillis()
            val fresh = alphaApps.filter { it.installed > 0L && now - it.installed in 0L..(3L * 24 * 3600 * 1000) }
                .sortedByDescending { it.installed }.take(6)
            if (fresh.isNotEmpty()) {
                lrows.add(LRow(3, 0, null))
                for (e in fresh) lrows.add(LRow(2, -1, e))
            }
            var g = -1
            for (e in alphaApps) {
                val gg = groupOf(e.label)
                if (gg != g) {
                    g = gg
                    groupRow[g] = lrows.size
                    lrows.add(LRow(1, g, null))
                }
                lrows.add(LRow(2, g, e))
            }
        }
        layoutRows()
        rowNames.clear()
    }

    private fun relayoutList() {
        wpU = width / 480f
        lIcon = 62f * wpU
        lMargin = 24f * wpU
        lTop = insetTop + 12f * wpU
        rowP.textSize = 25.33f * wpU
        rowP.typeface = Typeface.create(Typeface.DEFAULT, 400, false)
        headP.textSize = 34f * wpU
        headP.typeface = Typeface.create(Typeface.DEFAULT, 300, false)
        layoutRows()
        rowNames.clear()
    }

    private fun layoutRows() {
        var y = 0f
        for (r in lrows) {
            r.h = when (r.kind) {
                0 -> 72f * wpU
                3 -> 60f * wpU
                4 -> 84f * wpU
                else -> 74f * wpU
            }
            r.y = y
            y += r.h
        }
        lContent = y + 90f * wpU
        val bottom = height - max(insetBottom, imeH)
        lscroll.dim = max(1f, bottom - insetTop)
        lscroll.min = 0f
        lscroll.max = max(0f, lTop + lContent - bottom)
        if (lscroll.value > lscroll.max) lscroll.jump(lscroll.max)
    }

    private fun rowTop(i: Int): Float = lTop + lrows[i].y - lscroll.value.coerceIn(lscroll.min, lscroll.max)

    private fun listHit(x: Float, y: Float): Int {
        for (i in lrows.indices) {
            val t = rowTop(i)
            val r = lrows[i]
            if (y < t || y > t + r.h) continue
            return when (r.kind) {
                0 -> if (x <= lMargin + 60f * wpU) i else -1
                1 -> if (x <= lMargin + lIcon + 10f * wpU) i else -1
                2, 4 -> i
                else -> -1
            }
        }
        return -1
    }

    private fun iconRectOf(i: Int): RectF {
        val t = rowTop(i) + (lrows[i].h - lIcon) / 2f
        return RectF(lMargin, t, lMargin + lIcon, t + lIcon)
    }

    private fun drawList(c: Canvas, now: Long, top: Float, bottom: Float) {
        val fgc = fg()
        val dim = menuSp.value
        for (i in lrows.indices) {
            val r = lrows[i]
            val t = rowTop(i)
            if (t + r.h < top || t > bottom) continue
            var a = 1f
            var turn = 0f
            if (turnOut && turnFromList) {
                val dly = if (i == turnRow) TURN_SPREAD + 60f else clamp01((t - top) / max(1f, bottom - top)) * TURN_SPREAD
                val q = clamp01(((now - turnT0).toFloat() - dly) / TURN_MS)
                turn = 90f * q * q
                a = 1f - clamp01((q - 0.55f) / 0.45f)
                if (a <= 0.01f) continue
            }
            val tk = if (ltilt == i) tiltSp.value else 0f
            val l = lMargin
            val rr = width - lMargin
            val b = t + r.h
            c.save()
            if (tk > 0.001f || turn != 0f) {
                if (!place(l, t, rr, b, 0f, tk, turn)) {
                    c.restore()
                    continue
                }
                c.concat(m)
            }
            // con el menú abierto, todo menos la fila elegida se apaga
            val al = (255f * a * (if (dim > 0.001f && i != menuRow) 1f - 0.55f * dim else 1f)).toInt().coerceIn(0, 255)
            drawRow(c, i, r, t, al, fgc, now)
            c.restore()
        }
    }

    private fun drawRow(c: Canvas, i: Int, r: LRow, t: Float, al: Int, fgc: Int, now: Long) {
        val cy = t + r.h / 2f
        when (r.kind) {
            0 -> {
                // la lupa: un círculo con la lupa dentro, arriba a la izquierda
                val rad = 24f * wpU
                val cx = lMargin + rad
                ringP.color = fgc
                ringP.alpha = al
                ringP.strokeWidth = 2.6f * wpU
                c.drawCircle(cx, cy, rad, ringP)
                drawMagnifier(c, cx, cy, rad * 0.62f, al, fgc)
            }
            1 -> {
                // la letra: un cuadrado con el borde del color de acento y la letra abajo a la izquierda
                val top = cy - lIcon / 2f
                ringP.color = accentOf(null)
                ringP.alpha = al
                ringP.strokeWidth = 2.6f * wpU
                val h = ringP.strokeWidth / 2f
                c.drawRect(lMargin + h, top + h, lMargin + lIcon - h, top + lIcon - h, ringP)
                if (r.group == 27) drawGlobe(c, lMargin + lIcon / 2f, top + lIcon / 2f, lIcon * 0.3f, al, fgc)
                else {
                    headP.color = fgc
                    headP.alpha = al
                    c.drawText(GROUP_LABELS[r.group], lMargin + 7f * wpU, top + lIcon - 9f * wpU, headP)
                }
            }
            2 -> {
                val e = r.e ?: return
                val top = cy - lIcon / 2f
                rect.set(lMargin, top, lMargin + lIcon, top + lIcon)
                tileP.color = accentOf(e)
                tileP.alpha = al
                c.drawRect(rect, tileP)
                drawGlyph(c, e, rect.centerX(), rect.centerY(), lIcon * 0.56f, al)
                rowP.color = fgc
                rowP.alpha = al
                val x0 = lMargin + lIcon + 14f * wpU
                val n = rowNames.getOrPut(e.key) {
                    TextUtils.ellipsize(e.label, rowP, width - x0 - lMargin, TextUtils.TruncateAt.END).toString()
                }
                c.drawText(n, x0, cy - (rowP.descent() + rowP.ascent()) / 2f, rowP)
            }
            3 -> {
                headP.color = accentOf(null)
                headP.alpha = al
                c.drawText("nuevas", lMargin, t + r.h * 0.72f, headP)
            }
            4 -> {
                // cuadro de búsqueda de Windows Phone: blanco con texto negro mientras se escribe
                val l = lMargin
                val rr = width - lMargin
                val tt = t + 14f * wpU
                val bb = t + r.h - 14f * wpU
                tileP.color = if (bgMode() == 1) 0xFFEDEDED.toInt() else Color.WHITE
                tileP.alpha = al
                c.drawRect(l, tt, rr, bb, tileP)
                ringP.color = accentOf(null)
                ringP.alpha = al
                ringP.strokeWidth = 2.6f * wpU
                c.drawRect(l, tt, rr, bb, ringP)
                smallP.textSize = 22f * wpU
                val ty = (tt + bb) / 2f - (smallP.descent() + smallP.ascent()) / 2f
                val txt = queryShown
                if (txt.isEmpty()) {
                    smallP.color = 0xFF8A8A8A.toInt()
                    smallP.alpha = al
                    c.drawText("buscar aplicaciones", l + 12f * wpU, ty, smallP)
                } else {
                    smallP.color = Color.BLACK
                    smallP.alpha = al
                    c.drawText(txt, l + 12f * wpU, ty, smallP)
                }
                if (caretOn) {
                    val cx = l + 12f * wpU + (if (txt.isEmpty()) 0f else smallP.measureText(txt)) + 2f * wpU
                    tileP.color = Color.BLACK
                    tileP.alpha = al
                    c.drawRect(cx, tt + 12f * wpU, cx + 2f * wpU, bb - 12f * wpU, tileP)
                }
                smallP.color = Color.WHITE
            }
            5 -> {
                smallP.textSize = 22f * wpU
                smallP.color = 0xFF8A8A8A.toInt()
                smallP.alpha = al
                c.drawText("no hay aplicaciones con «$queryShown»", lMargin, cy, smallP)
                smallP.color = Color.WHITE
            }
        }
    }

    private var queryShown = ""

    private fun drawMagnifier(c: Canvas, cx: Float, cy: Float, s: Float, al: Int, col: Int) {
        ringP.color = col
        ringP.alpha = al
        val sw = ringP.strokeWidth
        ringP.strokeWidth = max(2f, s * 0.2f)
        val r = s * 0.5f
        val ox = cx - s * 0.14f
        val oy = cy - s * 0.14f
        c.drawCircle(ox, oy, r, ringP)
        val d = r * 0.707f
        c.drawLine(ox + d, oy + d, cx + s * 0.62f, cy + s * 0.62f, ringP)
        ringP.strokeWidth = sw
    }

    private fun drawGlobe(c: Canvas, cx: Float, cy: Float, r: Float, al: Int, col: Int) {
        ringP.color = col
        ringP.alpha = al
        val sw = ringP.strokeWidth
        ringP.strokeWidth = max(2f, r * 0.12f)
        c.drawCircle(cx, cy, r, ringP)
        rect.set(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r)
        c.drawOval(rect, ringP)
        c.drawLine(cx - r, cy, cx + r, cy, ringP)
        c.drawLine(cx, cy - r, cx, cy + r, ringP)
        ringP.strokeWidth = sw
    }

    private fun toPage(p: Int, vel: Float = 0f) {
        pageSp.tune(300f, 1f)
        pageSp.to(p.toFloat())
        pageSp.velocity = vel
        if (p == 0 && searching) endSearch()
        Hx.fire(this, S.HE_PAGE)
        ticker.kick()
    }

    override fun fadeTopK(): Float {
        val pg = clamp01(pageSp.value)
        val a = clamp01((scroll.value - scroll.min) / fadeLen)
        val b = clamp01((lscroll.value - lscroll.min) / fadeLen)
        return a + (b - a) * pg
    }

    override fun fadeBottomK(): Float {
        val pg = clamp01(pageSp.value)
        val a = clamp01((scroll.max - scroll.value) / fadeLen)
        val b = clamp01((lscroll.max - lscroll.value) / fadeLen)
        return a + (b - a) * pg
    }

    // ---------------------------------------------------------------- Saltos por letra
    private fun openJump() {
        if (jumpOpen) return
        jumpOpen = true
        jumpT0 = SystemClock.uptimeMillis()
        jumpSp.tune(320f, 1f)
        jumpSp.to(1f)
        Hx.fire(this, S.HE_PANEL)
        ticker.kick()
    }

    private fun closeJump() {
        if (!jumpOpen) return
        jumpOpen = false
        jumpSp.tune(420f, 1f)
        jumpSp.to(0f)
        ticker.kick()
    }

    private fun layoutJump(top: Float, bottom: Float) {
        jumpGap = 12f * wpU
        val m = 24f * wpU
        jumpTile = (width - m * 2f - jumpGap * 3f) / 4f
        val rows = (GROUPS + 3) / 4
        val gh = rows * jumpTile + (rows - 1) * jumpGap
        jumpX0 = m
        jumpY0 = if (gh < bottom - top - m * 2f) top + (bottom - top - gh) / 2f else top + m
    }

    private fun jumpHit(x: Float, y: Float): Int {
        for (g in 0 until GROUPS) {
            val l = jumpX0 + (g % 4) * (jumpTile + jumpGap)
            val t = jumpY0 + (g / 4) * (jumpTile + jumpGap)
            if (x >= l && x <= l + jumpTile && y >= t && y <= t + jumpTile) return g
        }
        return -1
    }

    private fun drawJump(c: Canvas, now: Long, top: Float, bottom: Float) {
        val k = jumpSp.value
        layoutJump(top, bottom)
        tileP.color = if (bgMode() == 1) Color.WHITE else Color.BLACK
        tileP.alpha = (235f * clamp01(k)).toInt()
        c.drawRect(0f, top, width.toFloat(), bottom, tileP)
        val dark = bgMode() != 1
        for (g in 0 until GROUPS) {
            val l = jumpX0 + (g % 4) * (jumpTile + jumpGap)
            val t = jumpY0 + (g / 4) * (jumpTile + jumpGap)
            // al abrir, las casillas giran hacia ti una tras otra
            val q = if (jumpOpen) clamp01(((now - jumpT0).toFloat() - g * 11f) / 260f) else clamp01(k)
            val e = 1f - (1f - q) * (1f - q) * (1f - q)
            if (e <= 0.01f) continue
            val has = groupRow[g] >= 0
            val ang = (1f - e) * 85f
            c.save()
            if (ang > 0.5f || (jumpDown == g)) {
                if (!place(l, t, l + jumpTile, t + jumpTile, ang, if (jumpDown == g) tiltSp.value else 0f, 0f)) {
                    c.restore()
                    continue
                }
                c.concat(m)
            }
            val al = (255f * e * clamp01(k * 1.5f)).toInt().coerceIn(0, 255)
            tileP.color = if (has) accentOf(null) else if (dark) 0xFF1F1F1F.toInt() else 0xFFE0E0E0.toInt()
            tileP.alpha = al
            c.drawRect(l, t, l + jumpTile, t + jumpTile, tileP)
            val lc = if (has) Color.WHITE else if (dark) 0xFF555555.toInt() else 0xFFA0A0A0.toInt()
            if (g == 27) drawGlobe(c, l + jumpTile / 2f, t + jumpTile / 2f, jumpTile * 0.26f, al, lc)
            else {
                headP.color = lc
                headP.alpha = al
                val hs = headP.textSize
                headP.textSize = jumpTile * 0.44f
                c.drawText(GROUP_LABELS[g], l + jumpTile * 0.09f, t + jumpTile * 0.86f, headP)
                headP.textSize = hs
            }
            c.restore()
        }
    }

    private fun jumpTouch(ev: MotionEvent): Boolean {
        val g = jumpHit(ev.x, ev.y)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                jumpDown = if (g >= 0 && groupRow[g] >= 0) g else -1
                tilting = -1
                ltilt = -1
                if (jumpDown >= 0) {
                    val l = jumpX0 + (g % 4) * (jumpTile + jumpGap)
                    val t = jumpY0 + (g / 4) * (jumpTile + jumpGap)
                    tiltX = ((ev.x - (l + jumpTile / 2f)) / (jumpTile / 2f)).coerceIn(-1f, 1f)
                    tiltY = ((ev.y - (t + jumpTile / 2f)) / (jumpTile / 2f)).coerceIn(-1f, 1f)
                    tiltSp.tune(1400f, 1f)
                    tiltSp.to(1f)
                    Hx.fire(this, S.HE_ICON)
                }
                ticker.kick()
            }
            MotionEvent.ACTION_UP -> {
                val d = jumpDown
                jumpDown = -1
                tiltSp.to(0f)
                if (d >= 0 && d == g) {
                    // salta a esa letra: queda arriba del todo
                    val r = groupRow[d]
                    if (r >= 0) lscroll.jump((lrows[r].y).coerceIn(lscroll.min, lscroll.max))
                    closeJump()
                } else if (g < 0) closeJump()
                ticker.kick()
            }
            MotionEvent.ACTION_CANCEL -> {
                jumpDown = -1
                tiltSp.to(0f)
            }
        }
        return true
    }

    // ---------------------------------------------------------------- Menú de una app
    private fun isPinned(e: AppEntry): Boolean {
        for (x in items) if (x.key == e.key) return true
        return false
    }

    private fun openMenu(row: Int) {
        val e = lrows[row].e ?: return
        menuRow = row
        menuN = 0
        menuActs[menuN++] = if (isPinned(e)) 1 else 0
        menuActs[menuN++] = 2
        menuActs[menuN++] = 3
        menuOpen = true
        menuSp.snap(0f)
        menuSp.to(1f)
        Hx.fire(this, S.HE_LONG)
        ticker.kick()
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        menuSp.to(0f)
        ticker.kick()
    }

    private fun menuLabel(a: Int): String = when (a) {
        0 -> "anclar a Inicio"
        1 -> "desanclar de Inicio"
        2 -> "información de la aplicación"
        else -> "desinstalar"
    }

    private fun layoutMenu(top: Float, bottom: Float) {
        menuItemH = 68f * wpU
        val ph = menuItemH * menuN + 20f * wpU
        if (menuRow !in lrows.indices) return
        val rt = rowTop(menuRow)
        val below = rt + lrows[menuRow].h
        val y0 = if (below + ph < bottom - 12f * wpU) below else max(top, rt - ph)
        menuRect.set(0f, y0, width.toFloat(), y0 + ph)
    }

    private fun drawMenu(c: Canvas, top: Float, bottom: Float) {
        val k = clamp01(menuSp.value)
        if (k <= 0.001f || menuRow !in lrows.indices) return
        layoutMenu(top, bottom)
        // el panel se despliega de arriba abajo, claro sobre el tema oscuro (como en Windows Phone)
        val h = menuRect.height() * (0.35f + 0.65f * k)
        val dark = bgMode() != 1
        tileP.color = if (dark) 0xFFF2F2F2.toInt() else 0xFF1F1F1F.toInt()
        tileP.alpha = (255f * k).toInt()
        c.drawRect(menuRect.left, menuRect.top, menuRect.right, menuRect.top + h, tileP)
        c.save()
        c.clipRect(menuRect.left, menuRect.top, menuRect.right, menuRect.top + h)
        rowP.color = if (dark) Color.BLACK else Color.WHITE
        val ts = rowP.textSize
        rowP.textSize = 28f * wpU
        for (j in 0 until menuN) {
            val y = menuRect.top + 10f * wpU + j * menuItemH
            if (j == menuDown) {
                tileP.color = accentOf(null)
                tileP.alpha = (255f * k).toInt()
                c.drawRect(menuRect.left, y, menuRect.right, y + menuItemH, tileP)
            }
            rowP.alpha = (255f * k).toInt()
            c.drawText(menuLabel(menuActs[j]), lMargin, y + menuItemH / 2f - (rowP.descent() + rowP.ascent()) / 2f, rowP)
        }
        rowP.textSize = ts
        c.restore()
    }

    private fun menuTouch(ev: MotionEvent): Boolean {
        val inside = menuRect.contains(ev.x, ev.y)
        val j = if (inside) ((ev.y - menuRect.top - 10f * wpU) / max(1f, menuItemH)).toInt().coerceIn(0, menuN - 1) else -1
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                menuDown = j
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val d = menuDown
                menuDown = -1
                val e = lrows.getOrNull(menuRow)?.e
                if (d >= 0 && d == j && e != null) {
                    closeMenu()
                    menuAction(menuActs[d], e)
                } else if (!inside) closeMenu()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> menuDown = -1
        }
        return true
    }

    private fun menuAction(a: Int, e: AppEntry) {
        when (a) {
            0 -> pin(e)
            1 -> unpin(e.key)
            2 -> try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg)))
            } catch (t: Throwable) {
            }
            else -> try {
                @Suppress("DEPRECATION")
                context.startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + e.pkg)))
            } catch (t: Throwable) {
            }
        }
    }

    /** anclar a Inicio: el mosaico aparece al final, en mediano, y se vuelve a los mosaicos para verlo */
    private fun pin(e: AppEntry) {
        if (isPinned(e)) return
        val order = Prefs.metroOrder()
        order.add(e.key)
        Prefs.setMetroOrder(order)
        val sz = Prefs.metroSizes()
        sz[e.key] = MEDIUM
        Prefs.setMetroSizes(sz)
        onDataChanged()
        toPage(0)
        scroll.stop()
        Mo.tune(scroll.sp, S.MO_SCROLL, 170f, 1f)
        scroll.sp.to(scroll.max)
        ticker.kick()
    }

    private fun unpin(key: String) {
        val order = Prefs.metroOrder()
        if (!order.remove(key)) return
        Prefs.setMetroOrder(order)
        if (editing) {
            editing = false
            sel = -1
            editSp.to(0f)
        }
        onDataChanged()
        Hx.fire(this, S.HE_SETTING)
    }

    // ---------------------------------------------------------------- Búsqueda
    private fun startSearch() {
        searching = true
        qbuf.clear()
        queryNorm = ""
        queryShown = ""
        buildList()
        lscroll.jump(0f)
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(this)
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        // algunas capas (MIUI) necesitan un segundo intento
        uiHandler.postDelayed({ if (searching) imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }, 180L)
        caretOn = true
        uiHandler.removeCallbacks(caretRun)
        uiHandler.postDelayed(caretRun, 530L)
        invalidate()
    }

    private fun endSearch() {
        if (!searching) return
        searching = false
        uiHandler.removeCallbacks(caretRun)
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
        } catch (t: Throwable) {
        }
        clearFocus()
        isFocusableInTouchMode = false
        qbuf.clear()
        queryNorm = ""
        queryShown = ""
        buildList()
        lscroll.jump(0f)
        invalidate()
    }

    private fun queryChanged() {
        queryShown = qbuf.toString()
        queryNorm = norm(queryShown.trim())
        buildList()
        lscroll.jump(0f)
        caretOn = true
        invalidate()
    }

    private fun launchFirst() {
        for (i in lrows.indices) if (lrows[i].kind == 2) {
            launchRow(i)
            return
        }
    }

    override fun onCheckIsTextEditor(): Boolean = searching

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!searching) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.initialSelStart = qbuf.length
        outAttrs.initialSelEnd = qbuf.length
        return object : BaseInputConnection(this@MetroView, true) {
            override fun getEditable(): Editable = qbuf
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val r = super.commitText(text, newCursorPosition)
                queryChanged()
                return r
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val r = super.setComposingText(text, newCursorPosition)
                queryChanged()
                return r
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val r = super.deleteSurroundingText(beforeLength, afterLength)
                queryChanged()
                return r
            }
            override fun finishComposingText(): Boolean {
                val r = super.finishComposingText()
                queryChanged()
                return r
            }
            override fun performEditorAction(actionCode: Int): Boolean {
                launchFirst()
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (searching) {
            when {
                keyCode == KeyEvent.KEYCODE_DEL -> {
                    if (qbuf.isNotEmpty()) qbuf.delete(qbuf.length - 1, qbuf.length)
                    queryChanged()
                    return true
                }
                keyCode == KeyEvent.KEYCODE_ENTER -> {
                    launchFirst()
                    return true
                }
                event.unicodeChar != 0 && keyCode != KeyEvent.KEYCODE_BACK -> {
                    qbuf.append(event.unicodeChar.toChar())
                    queryChanged()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 30) {
            val h = insets.getInsets(WindowInsets.Type.ime()).bottom.toFloat()
            if (h != imeH) {
                imeH = h
                layoutRows()
                invalidate()
            }
        }
        return super.onApplyWindowInsets(insets)
    }

    // ---------------------------------------------------------------- Abrir desde la lista
    private fun launchRow(i: Int) {
        val e = lrows.getOrNull(i)?.e ?: return
        val r = iconRectOf(i)
        if (searching) endSearchKeepRow()
        if (!S.b(S.MZ_TURN) || S.b(S.P_REDUCE)) {
            listener?.onLaunch(e, r)
            return
        }
        turnRect.set(r)
        turnOut = true
        turnFromList = true
        turnFired = false
        turnRow = lrows.indexOfFirst { it.e?.key == e.key }
        turnSel = -1
        turnLaunch = e
        turnT0 = SystemClock.uptimeMillis()
        ticker.kick()
    }

    /** al abrir desde la búsqueda se esconde el teclado pero la lista queda como estaba */
    private fun endSearchKeepRow() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
        } catch (t: Throwable) {
        }
    }

    private var turnLaunch: AppEntry? = null

    // ---------------------------------------------------------------- Atrás e inicio
    /** atrás: cierra lo último que se abrió; true si había algo */
    fun back(): Boolean {
        when {
            menuOpen -> closeMenu()
            jumpOpen -> closeJump()
            searching -> endSearch()
            pageSp.target > 0.5f -> toPage(0)
            editing -> exitEdit()
            else -> return false
        }
        return true
    }

    /** botón de inicio: vuelve a los mosaicos desde cualquier sitio */
    fun home(): Boolean {
        var any = false
        if (menuOpen) { closeMenu(); any = true }
        if (jumpOpen) { closeJump(); any = true }
        if (searching) { endSearch(); any = true }
        if (pageSp.target > 0.5f) { toPage(0); any = true }
        if (editing) { exitEdit(); any = true }
        return any
    }

    /** el inicio deja de verse: se cierra lo pasajero (la lista se queda, como en Windows Phone) */
    fun closeOverlays() {
        if (menuOpen) closeMenu()
        if (jumpOpen) closeJump()
        if (searching) endSearch()
        if (editing) exitEdit()
    }

    // ================================================================ Toques
    private var hdrag = false
    private var pageStart = 0f

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (turnOut) return true
        if (jumpOpen) return jumpTouch(ev)
        if (menuOpen || menuSp.value > 0.05f) return if (menuOpen) menuTouch(ev) else true
        val x = ev.x
        val y = ev.y
        val onList = pageSp.target > 0.5f
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                badgeDown = 0
                if (!onList && editing && !draggingTile) {
                    if (badgeOpt.contains(x, y)) badgeDown = 1 else if (badgeSize.contains(x, y)) badgeDown = 2
                    if (badgeDown != 0) {
                        Hx.fire(this, S.HE_ICON)
                        return true
                    }
                }
                listener?.onTouchStart()
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
                dX = x
                dY = y
                lastY = y
                fingerX = x
                fingerY = y
                moved = false
                scrolling = false
                hdrag = false
                longDone = false
                // si las pantallas se estaban deslizando, el dedo las atrapa
                if (pageSp.active) {
                    pageSp.snap(pageSp.value)
                    hdrag = true
                    moved = true
                    pageStart = pageSp.value
                }
                downIdx = -1
                lDown = -1
                if (onList) {
                    lscroll.stop()
                    if (!hdrag) {
                        val r = listHit(x, y)
                        lDown = r
                        if (r >= 0 && lrows[r].kind == 2) {
                            if (S.b(S.MZ_TILT)) {
                                val rt = rowTop(r)
                                tilting = -1
                                ltilt = r
                                tiltX = ((x - width / 2f) / (width / 2f)).coerceIn(-1f, 1f)
                                tiltY = ((y - (rt + lrows[r].h / 2f)) / (lrows[r].h / 2f)).coerceIn(-1f, 1f)
                                tiltSp.tune(1400f, 1f)
                                tiltSp.to(1f)
                            }
                            Hx.fire(this, S.HE_ICON)
                            uiHandler.removeCallbacks(longList)
                            uiHandler.postDelayed(longList, longMs)
                        }
                    }
                } else {
                    scroll.stop()
                    val i = if (hdrag) -1 else hit(x, y)
                    downIdx = i
                    if (i >= 0) {
                        if (editing) {
                            if (i != sel) {
                                sel = i
                                Hx.fire(this, S.HE_ICON)
                            }
                        } else {
                            startTilt(i, x, y)
                            uiHandler.removeCallbacks(longRun)
                            uiHandler.postDelayed(longRun, longMs)
                        }
                    }
                }
                ticker.kick()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (badgeDown != 0) return true
                vt?.addMovement(ev)
                fingerX = x
                fingerY = y
                val dx = x - dX
                val dy = y - dY
                if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                    moved = true
                    uiHandler.removeCallbacks(longRun)
                    uiHandler.removeCallbacks(longList)
                    if (!onList && editing && downIdx >= 0 && downIdx == sel) {
                        startDrag(dX, dY)
                    } else if (!editing && !searching && abs(dx) > abs(dy) * 1.2f && (if (onList) dx > 0f else dx < 0f)) {
                        // deslizar a los lados: de los mosaicos a la lista y al revés
                        hdrag = true
                        pageStart = pageSp.value
                        releaseTilt()
                    } else {
                        releaseTilt()
                        scrolling = true
                        lastY = y
                    }
                }
                when {
                    draggingTile -> {
                        dragX = x - grabDx
                        dragY = y - grabDy
                        reorderAt(x, y)
                        ticker.kick()
                    }
                    hdrag -> {
                        pageSp.snap((pageStart - dx / max(1f, width.toFloat())).coerceIn(0f, 1f))
                        ticker.kick()
                    }
                    scrolling -> {
                        if (onList) lscroll.drag(lastY - y) else scroll.drag(lastY - y)
                        lastY = y
                        ticker.kick()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longRun)
                uiHandler.removeCallbacks(longList)
                if (badgeDown != 0) {
                    val b = badgeDown
                    badgeDown = 0
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        if (b == 1 && badgeOpt.contains(x, y) && sel in items.indices) unpin(items[sel].key)
                        if (b == 2 && badgeSize.contains(x, y)) resizeSel()
                    }
                    return true
                }
                vt?.addMovement(ev)
                vt?.computeCurrentVelocity(1000)
                val vy = -(vt?.yVelocity ?: 0f)
                val vx = vt?.xVelocity ?: 0f
                vt?.recycle()
                vt = null
                val i = downIdx
                val lr = lDown
                downIdx = -1
                lDown = -1
                releaseTilt()
                when {
                    draggingTile -> drop()
                    hdrag -> {
                        val vp = -vx / max(1f, width.toFloat())
                        val go = if (abs(vp) > 0.5f) vp > 0f else pageSp.value > 0.5f
                        toPage(if (go) 1 else 0, vp)
                    }
                    scrolling -> if (onList) lscroll.release(vy) else scroll.release(vy)
                    else -> {
                        if (onList) lscroll.release(0f) else scroll.release(0f)
                        if (ev.actionMasked == MotionEvent.ACTION_UP && !longDone) {
                            if (onList) listTap(lr)
                            else if (editing) {
                                if (i < 0) exitEdit()
                            } else if (i in items.indices) {
                                launch(i)
                            } else if (arrowRect.contains(x, y)) {
                                toPage(1)
                            }
                        }
                    }
                }
                scrolling = false
                hdrag = false
                ticker.kick()
                return true
            }
        }
        return true
    }

    private fun listTap(r: Int) {
        if (r !in lrows.indices) return
        when (lrows[r].kind) {
            0 -> startSearch()
            1 -> openJump()
            2 -> launchRow(r)
            4 -> {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                requestFocus()
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}
