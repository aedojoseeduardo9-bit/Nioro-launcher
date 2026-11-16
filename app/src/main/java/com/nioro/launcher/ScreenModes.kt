package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Modos de pantalla: cambian toda la pantalla de inicio, no solo cómo se colocan los iconos.
 * Sin referencias a S al iniciarse (Reg.kt lee NAMES al declarar el ajuste).
 */
object ScreenModes {
    const val CLASSIC = 0
    const val NO_DRAWER = 1
    const val HONEY = 2
    const val WHEEL = 3
    const val MINIMAL = 4
    const val ZEN = 5
    const val LIST = 6

    val NAMES = arrayOf("Clásico", "Sin cajón", "Panal", "Rueda", "Minimalista", "Zen", "Lista")
    val DESC = arrayOf(
        "Páginas de iconos y dock. Desliza hacia arriba para ver todas las apps.",
        "Todas tus apps viven en las páginas del inicio, como en el iPhone. Deslizar hacia arriba abre el buscador. Si vuelves a otro modo, tu inicio queda como estaba.",
        "El inicio es una colmena con todas tus apps: arrástrala con el dedo y las del centro se ven más grandes.",
        "Una rueda con todas tus apps encima del dock: gírala con el dedo y toca la app grande para abrirla.",
        "Solo el reloj y una lista de texto con tus apps favoritas (las del dock y el inicio). Sin iconos ni dock.",
        "Pantalla limpia: solo el reloj y el dock. Desliza hacia arriba para ver tus apps.",
        "Tus apps en una lista estilo playlist encima del dock, con tu propio orden."
    )

    fun mode(): Int = S.i(S.H_SCREEN).coerceIn(0, NAMES.size - 1)
    /** sin páginas de iconos en el inicio */
    fun pagesOff(m: Int = mode()): Boolean = m >= HONEY
    /** sin dock */
    fun dockOff(m: Int = mode()): Boolean = m == MINIMAL
    /** el centro del inicio lo ocupa una vista de apps (ScreenHost) */
    fun embedded(m: Int): Boolean = m == HONEY || m == WHEEL || m == MINIMAL || m == LIST
}

/**
 * Aloja la vista de apps que hace de pantalla de inicio en los modos Panal, Rueda, Minimalista y
 * Lista. Va justo encima de HomeView (que sigue dibujando reloj y dock) y solo recibe los toques que
 * caen en su zona; el resto sigue llegando al inicio (deslizar, reloj, dock...).
 */
class ScreenHost(ctx: Context) : FrameLayout(ctx) {
    var listener: AppsView.Listener? = null
    private var view: AppsView? = null
    private var kind = -1
    private var areaTop = 0f
    private var areaBottom = 0f
    private var taking = false
    /** el inicio está en modo edición o con el menú del reloj: esta capa se aparta */
    private var cover = 0f
    private val enterSp = SpringValue(1f, 160f, 24f, 0.002f)
    private val ticker = Ticker { frame() }

    init {
        ticker.add(enterSp)
        visibility = GONE
    }

    val active: Boolean get() = view != null

    /** pone (o quita) la vista que toca para el modo [mode] */
    fun apply(mode: Int) {
        val want = if (ScreenModes.embedded(mode)) mode else -1
        if (want != kind) {
            view?.let { removeView(it) }
            view = null
            kind = want
            if (want >= 0) {
                val v: AppsView = when (want) {
                    ScreenModes.HONEY -> HoneyView(context)
                    ScreenModes.WHEEL -> WheelView(context)
                    ScreenModes.LIST -> PlaylistView(context)
                    else -> MinimalView(context)
                }
                v.embedded = true
                v.progress = 1f
                v.insetTop = areaTop
                v.insetBottom = max(0f, height - areaBottom)
                v.listener = object : AppsView.Listener {
                    override fun onLaunch(e: AppEntry, from: RectF) { listener?.onLaunch(e, from) }
                    override fun onMenu(e: AppEntry, anchor: RectF) { listener?.onMenu(e, anchor) }
                    // es la pantalla de inicio: no hay nada que cerrar
                    override fun onClose() {}
                    override fun onTouchStart() { listener?.onTouchStart() }
                    override fun onAddHome(e: AppEntry) { listener?.onAddHome(e) }
                }
                addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                view = v
                refreshData()
                post { if (view === v) v.onOpen() }
            }
        }
        visibility = if (view != null) VISIBLE else GONE
    }

    fun refreshData() {
        view?.setData(AppRepo.sorted(AppRepo.visible()), AppRepo.sorted(AppRepo.apps))
    }

    /** zona libre entre el reloj y el dock (coordenadas de la pantalla) */
    fun setArea(top: Float, bottom: Float) {
        if (abs(top - areaTop) < 0.5f && abs(bottom - areaBottom) < 0.5f) return
        areaTop = top
        areaBottom = bottom
        val v = view ?: return
        v.insetTop = top
        v.insetBottom = max(0f, height - bottom)
        v.onImeChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        view?.let {
            it.insetTop = areaTop
            it.insetBottom = max(0f, h - areaBottom)
        }
    }

    /** al volver al inicio la vista entra con su animación de apertura */
    fun playEnter() {
        val v = view ?: return
        v.onOpen()
        Mo.tune(enterSp, S.MO_DRAWER_OPEN, 150f, 0.9f)
        enterSp.snap(0f)
        enterSp.to(1f)
        ticker.kick()
    }

    fun setCover(k: Float) {
        if (abs(k - cover) < 0.002f && (k == 0f) == (cover == 0f)) return
        cover = k
        applyFade()
    }

    private var base = 1f

    /** la vista de apps o los recientes se abren encima: esta capa se encoge y se desvanece con el inicio */
    fun follow(a: Float, scale: Float) {
        if (abs(a - base) < 0.001f && abs(scale - scaleX) < 0.0005f) return
        base = a
        pivotX = width / 2f
        pivotY = height / 2f
        scaleX = scale
        scaleY = scale
        // mientras se mueve, una "foto" en la GPU (no se redibuja la colmena en cada fotograma)
        val want = if (a < 0.999f || scale < 0.9995f) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE
        if (layerType != want) setLayerType(want, null)
        applyFade()
    }

    private fun applyFade() {
        alpha = base * (1f - clamp01(cover * 1.6f))
    }

    private fun frame(): Boolean {
        val v = view ?: return false
        v.progress = enterSp.value
        v.invalidate()
        return false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (view == null) return false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            taking = visibility == VISIBLE && cover < 0.02f && alpha > 0.5f && ev.y >= areaTop && ev.y <= areaBottom
        }
        if (!taking) return false
        val r = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !r) taking = false
        return r
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }
}

/**
 * Modo Minimalista: tus apps favoritas como una lista de texto grande, sin iconos. Los toques que no
 * caen sobre un nombre siguen al inicio (deslizar hacia arriba abre las apps, hacia abajo las notificaciones).
 */
class MinimalView(ctx: Context) : AppsView(ctx) {
    override val iconArea: Int = S.IA_HOME
    private val fav = ArrayList<AppEntry>()
    private val names = ArrayList<String>()
    private val name = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val hint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rowY = FloatArray(MAX)
    private val rowW = FloatArray(MAX)
    private var rowH = 0f
    private var x0 = 0f
    private var shownN = 0
    private var downIdx = -1
    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private val longRun = Runnable {
        val i = downIdx
        if (i in 0 until shownN) {
            longFired = true
            lift()
            listener?.onMenu(fav[i], rowRect(i))
        }
    }

    init {
        name.color = Color.WHITE
        name.typeface = Typeface.create(Typeface.DEFAULT, 300, false)
        name.setShadowLayer(dp(6f), 0f, dp(1.5f), 0x66000000)
        hint.color = 0x99FFFFFF.toInt()
        hint.textSize = dp(12.5f)
        hint.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        hint.setShadowLayer(dp(3f), 0f, dp(1f), 0x66000000)
    }

    override fun setData(list: List<AppEntry>, all: List<AppEntry>) {
        super.setData(list, all)
        fav.clear()
        val seen = HashSet<String>()
        // las favoritas: primero las del dock y luego las del inicio, en su orden
        for (k in Prefs.dockKeys() + Prefs.homeKeys()) {
            if (k == HomeSlots.BLANK || !seen.add(k)) continue
            val e = AppRepo.get(k) ?: continue
            fav.add(e)
            if (fav.size >= MAX) break
        }
        // si aún no hay favoritas, las primeras apps por orden alfabético
        if (fav.isEmpty()) for (e in list) {
            fav.add(e)
            if (fav.size >= 7) break
        }
        layoutRows()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutRows()
    }

    override fun onImeChanged() {
        layoutRows()
        invalidate()
    }

    private fun layoutRows() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val top = insetTop + dp(10f)
        val bottom = h - insetBottom - dp(44f)
        val avail = max(dp(60f), bottom - top)
        // filas grandes; si no caben todas se muestran menos (nunca se encima el texto)
        rowH = min(dp(62f), avail / max(1, fav.size)).coerceAtLeast(dp(44f))
        shownN = min(fav.size, (avail / rowH).toInt()).coerceIn(0, MAX)
        name.textSize = rowH * 0.5f
        x0 = dp(30f)
        names.clear()
        for (i in 0 until shownN) {
            val s = TextUtils.ellipsize(fav[i].label, name, w - x0 * 2f, TextUtils.TruncateAt.END).toString()
            names.add(s)
            rowY[i] = top + i * rowH
            rowW[i] = name.measureText(s)
        }
    }

    private fun rowRect(i: Int): RectF =
        RectF(x0 - dp(10f), rowY[i] + dp(4f), x0 + rowW[i] + dp(10f), rowY[i] + rowH - dp(4f))

    private fun rowAt(x: Float, y: Float): Int {
        for (i in 0 until shownN) {
            if (y >= rowY[i] && y < rowY[i] + rowH && x >= x0 - dp(16f) && x <= x0 + rowW[i] + dp(24f)) return i
        }
        return -1
    }

    private fun ease(t: Float): Float {
        val k = 1f - clamp01(t)
        return 1f - k * k * k
    }

    override fun onFrame(dt: Float): Boolean {
        invalidate()
        return false
    }

    override fun onDraw(c: Canvas) {
        val p = progress
        for (i in 0 until shownN) {
            // entran una tras otra deslizándose desde la izquierda
            val q = ease(p * 1.5f - i * 0.07f)
            if (q <= 0.001f) continue
            val ox = (1f - q) * -dp(36f)
            val pk = i == downIdx
            val s = if (pk) pressSp.value else 1f
            val cy = rowY[i] + rowH / 2f
            name.alpha = (255f * q * (if (pk) 0.75f else 1f)).toInt().coerceIn(0, 255)
            c.save()
            c.scale(s, s, x0, cy)
            c.drawText(names[i], x0 + ox, cy - (name.descent() + name.ascent()) / 2f, name)
            c.restore()
        }
        if (shownN > 0) {
            hint.alpha = (150f * clamp01(p * 1.2f - 0.4f)).toInt()
            val y = rowY[shownN - 1] + rowH + dp(22f)
            if (y < height - insetBottom - dp(8f)) c.drawText("Desliza hacia arriba para ver todas", x0, y, hint)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val i = rowAt(x, y)
                // fuera de los nombres el toque sigue al inicio
                if (i < 0) return false
                listener?.onTouchStart()
                downIdx = i
                downX = x
                downY = y
                longFired = false
                press(fav[i])
                uiHandler.removeCallbacks(longRun)
                uiHandler.postDelayed(longRun, longMs)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (downIdx >= 0 && (abs(x - downX) > slop || abs(y - downY) > slop)) {
                    uiHandler.removeCallbacks(longRun)
                    if (!longFired) {
                        downIdx = -1
                        unpress()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longRun)
                val i = downIdx
                downIdx = -1
                unpress()
                if (ev.actionMasked == MotionEvent.ACTION_UP && !longFired && i in 0 until shownN) {
                    listener?.onLaunch(fav[i], rowRect(i))
                }
                invalidate()
                return true
            }
        }
        return true
    }

    companion object {
        private const val MAX = 12
    }
}

/** miniatura de un modo de pantalla para la galería de Ajustes (un teléfono dibujado a mano) */
class ScreenModeThumb(ctx: Context, val idx: Int) : View(ctx) {
    var picked = false
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val r = RectF()
    private val q = RectF()
    private val clip = Path()
    private val cols = intArrayOf(0xFF5AC8FA.toInt(), 0xFFFF9F0A.toInt(), 0xFF34C759.toInt(), 0xFFFF375F.toInt(), 0xFFBF5AF2.toInt(), 0xFFFFD60A.toInt())

    init {
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF2B4FC4.toInt(), 0xFF5B2FA8.toInt(), 0xFF1A1440.toInt()), null, Shader.TileMode.CLAMP)
    }

    private fun tile(c: Canvas, cx: Float, cy: Float, s: Float, k: Int) {
        p.color = cols[k % cols.size]
        c.drawRoundRect(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f, s * 0.28f, s * 0.28f, p)
    }

    override fun onDraw(c: Canvas) {
        val labelH = dp(22f)
        // el "teléfono": proporción vertical centrada
        val ph = height - labelH - dp(6f)
        val pw = min(width - dp(10f), ph * 0.52f)
        r.set((width - pw) / 2f, dp(3f), (width + pw) / 2f, dp(3f) + ph)
        clip.reset()
        clip.addRoundRect(r, pw * 0.16f, pw * 0.16f, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        c.drawRect(r, bg)
        val u = pw / 10f
        // reloj (todos los modos)
        p.color = 0xEEFFFFFF.toInt()
        val clockY = r.top + u * (if (idx == ScreenModes.MINIMAL || idx == ScreenModes.ZEN) 3.2f else 2.2f)
        val cw = if (idx == ScreenModes.ZEN) u * 5f else u * 3.6f
        val cx = if (idx == ScreenModes.MINIMAL) r.left + u * 1.4f + cw / 2f else r.centerX()
        c.drawRoundRect(cx - cw / 2f, clockY - u * 0.65f, cx + cw / 2f, clockY + u * 0.65f, u * 0.3f, u * 0.3f, p)
        // dock (menos en minimalista)
        val dockTop = r.bottom - u * 2.6f
        if (!ScreenModes.dockOff(idx)) {
            p.color = 0x40FFFFFF
            q.set(r.left + u * 0.8f, dockTop, r.right - u * 0.8f, r.bottom - u * 0.8f)
            c.drawRoundRect(q, u * 0.7f, u * 0.7f, p)
            for (k in 0 until 4) tile(c, q.left + q.width() * (k + 0.5f) / 4f, q.centerY(), u * 1.15f, k)
        }
        val top = clockY + u * 1.6f
        val bottom = if (ScreenModes.dockOff(idx)) r.bottom - u else dockTop - u * 0.5f
        when (idx) {
            ScreenModes.CLASSIC, ScreenModes.NO_DRAWER -> {
                val rows = if (idx == ScreenModes.NO_DRAWER) 5 else 3
                val gap = (bottom - top) / rows
                for (y in 0 until rows) for (x in 0 until 4) {
                    tile(c, r.left + u * (1.6f + x * 2.27f), top + gap * (y + 0.5f), u * 1.2f, x + y * 2)
                }
                if (idx == ScreenModes.CLASSIC) {
                    // flecha "desliza hacia arriba"
                    p.color = 0x99FFFFFF.toInt()
                    c.drawRoundRect(r.centerX() - u * 0.9f, dockTop - u * 0.45f, r.centerX() + u * 0.9f, dockTop - u * 0.25f, u, u, p)
                }
            }
            ScreenModes.HONEY -> {
                val d = u * 1.55f
                val cyy = (top + bottom) / 2f
                var k = 0
                for (row in -2..2) for (col in -3..3) {
                    val x = r.centerX() + (col + if (row % 2 != 0) 0.5f else 0f) * d
                    val y = cyy + row * d * 0.87f
                    val dist = sqrt(((x - r.centerX()) * (x - r.centerX()) + (y - cyy) * (y - cyy)).toDouble()).toFloat()
                    val s = (u * 1.35f * (1f - dist / (u * 7f))).coerceAtLeast(0f)
                    if (s > u * 0.25f) {
                        p.color = cols[k % cols.size]
                        c.drawCircle(x, y, s / 2f, p)
                    }
                    k++
                }
            }
            ScreenModes.WHEEL -> {
                val wcx = r.centerX()
                val rad = pw * 0.62f
                val wcy = bottom + rad * 0.55f
                for (k in -3..3) {
                    val a = Math.toRadians((-90 + k * 22).toDouble())
                    val x = wcx + cos(a).toFloat() * rad
                    val y = wcy + sin(a).toFloat() * rad
                    tile(c, x, y, u * (if (k == 0) 1.5f else 1.1f), k + 3)
                }
                tile(c, wcx, (top + wcy - rad) / 2f, u * 2.6f, 4)
            }
            ScreenModes.MINIMAL -> {
                p.color = 0xD9FFFFFF.toInt()
                val gap = u * 1.25f
                for (k in 0 until 6) {
                    val w = u * (3f + (k * 37 % 5))
                    val y = top + u * 0.4f + k * gap
                    if (y > bottom) break
                    c.drawRoundRect(r.left + u * 1.4f, y, r.left + u * 1.4f + w, y + u * 0.55f, u * 0.3f, u * 0.3f, p)
                }
            }
            ScreenModes.ZEN -> {
                p.color = 0x66FFFFFF
                c.drawRoundRect(r.centerX() - u * 2f, clockY + u * 1.1f, r.centerX() + u * 2f, clockY + u * 1.5f, u * 0.2f, u * 0.2f, p)
            }
            ScreenModes.LIST -> {
                p.color = 0x33FFFFFF
                q.set(r.left + u * 0.6f, top - u * 0.4f, r.right - u * 0.6f, bottom)
                c.drawRoundRect(q, u * 0.8f, u * 0.8f, p)
                // portada
                p.color = cols[4]
                c.drawRoundRect(q.left + u * 0.6f, q.top + u * 0.6f, q.left + u * 2.6f, q.top + u * 2.6f, u * 0.4f, u * 0.4f, p)
                val rowH = u * 1.25f
                var y = q.top + u * 3.4f
                var k = 0
                while (y + rowH < q.bottom) {
                    tile(c, q.left + u * 1.2f, y + rowH / 2f, u * 0.9f, k)
                    p.color = 0xBFFFFFFF.toInt()
                    c.drawRoundRect(q.left + u * 2.2f, y + rowH * 0.38f, q.right - u * (1.6f + (k % 3)), y + rowH * 0.62f, u * 0.2f, u * 0.2f, p)
                    y += rowH
                    k++
                }
            }
        }
        c.restore()
        if (picked) {
            ring.color = OneColors.ACCENT
            ring.strokeWidth = dp(2.5f)
            c.drawRoundRect(r.left - dp(1.5f), r.top - dp(1.5f), r.right + dp(1.5f), r.bottom + dp(1.5f), pw * 0.17f, pw * 0.17f, ring)
        }
        label.textSize = dp(12f)
        label.alpha = if (picked) 255 else 205
        c.drawText(ScreenModes.NAMES[idx], width / 2f, height - dp(6f), label)
    }
}
