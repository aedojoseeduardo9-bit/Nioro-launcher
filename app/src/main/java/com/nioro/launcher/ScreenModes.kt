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
    const val MOSAIC = 2
    const val CARDS = 3
    const val MINIMAL = 4
    const val ZEN = 5
    const val SIDEBAR = 6

    // los números (0..6) son los valores guardados: no se reordenan
    val NAMES = arrayOf("Clásico", "Sin cajón", "Mosaico", "Tarjetas", "Minimalista", "Zen", "Barra lateral")
    val DESC = arrayOf(
        "Páginas de iconos y dock. Desliza hacia arriba para ver todas las apps.",
        "Sin menú de apps: todas tus apps viven en las páginas del inicio, como en el iPhone. Deslizar hacia arriba no abre nada. Si vuelves a otro modo, tu inicio queda como estaba.",
        "Calcado de Windows Phone: mosaicos de colores lisos en cuatro tamaños, mosaicos vivos, inclinación al tocar y torniquete. Desliza a la izquierda para la lista de apps con lupa y letras. Mantén pulsado un mosaico para moverlo, cambiar su tamaño o desanclarlo.",
        "Una app por tarjeta grande: pásalas a los lados con el dedo y toca la del centro para abrirla. Sin menú de apps: el inicio ya lo es.",
        "Solo el reloj y todas tus apps en una lista de texto grande que se desplaza. Sin iconos, sin dock y sin menú de apps.",
        "Pantalla limpia: solo el reloj y el dock. Desliza hacia arriba para ver tus apps.",
        "Una columna con todas tus apps en el borde derecho, a mano del pulgar, con su nombre al lado. Sin menú de apps: el inicio ya lo es."
    )

    fun mode(): Int = S.i(S.H_SCREEN).coerceIn(0, NAMES.size - 1)
    /** sin páginas de iconos en el inicio */
    fun pagesOff(m: Int = mode()): Boolean = m >= MOSAIC
    /** sin dock: Minimalista nunca lo tiene; Mosaico, Tarjetas y Barra lateral, si se desactiva */
    fun dockOff(m: Int = mode()): Boolean = when (m) {
        MINIMAL -> true
        MOSAIC -> !S.b(S.H_DOCK_MOSAIC)
        CARDS -> !S.b(S.H_DOCK_CARDS)
        SIDEBAR -> !S.b(S.H_DOCK_SIDE)
        else -> false
    }
    /** ¿se puede quitar el dock en este modo? */
    fun dockOptional(m: Int): Boolean = m == MOSAIC || m == CARDS || m == SIDEBAR
    /** el Mosaico, como Windows Phone, no lleva el reloj grande del inicio (salvo que se pida) */
    fun clockOff(m: Int = mode()): Boolean = m == MOSAIC && !S.b(S.MZ_CLOCK)

    /** sin menú de apps: "Sin cajón" y los modos cuyo inicio ya muestra todas las apps */
    fun drawerOff(m: Int = mode()): Boolean = m == NO_DRAWER || embedded(m)
    /** el centro del inicio lo ocupa una vista de apps (ScreenHost) */
    fun embedded(m: Int): Boolean = m == MOSAIC || m == CARDS || m == MINIMAL || m == SIDEBAR
}

/**
 * Aloja el diseño que hace de pantalla de inicio en los modos Mosaico, Tarjetas, Minimalista y
 * Barra lateral. Va justo encima de HomeView (que sigue dibujando reloj y dock) y solo recibe los toques que
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
                    ScreenModes.MOSAIC -> MetroView(context)
                    ScreenModes.CARDS -> CardsView(context)
                    ScreenModes.SIDEBAR -> SideBarView(context)
                    else -> MinimalListView(context)
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

    /** botón atrás: el Mosaico cierra primero lo que tenga abierto (menú, letras, búsqueda, lista...) */
    fun back(): Boolean = (view as? MetroView)?.back() == true
    /** botón de inicio: el Mosaico vuelve a sus mosaicos; true si había algo que cerrar */
    fun home(): Boolean = (view as? MetroView)?.home() == true
    /** el launcher deja de verse */
    fun stopped() {
        (view as? MetroView)?.closeOverlays()
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
        // reloj (todos los modos menos el Mosaico, que lo dibuja todo encima)
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
            ScreenModes.MOSAIC -> {
                // fondo negro y mosaicos planos de color de acento, como Windows Phone
                p.color = Color.BLACK
                c.drawRect(r, p)
                val g = u * 0.22f
                val cell = (pw - g * 7f) / 6f
                val y0 = r.top + u * 1.2f
                val acc = intArrayOf(0xFF1BA1E2.toInt(), 0xFF0050EF.toInt(), 0xFF00ABA9.toInt(), 0xFFE51400.toInt(), 0xFF60A917.toInt(), 0xFFF0A30A.toInt())
                // (columna, fila, ancho, alto) en casillas pequeñas
                val tiles = intArrayOf(0, 0, 2, 2, 2, 0, 2, 2, 4, 0, 1, 1, 5, 0, 1, 1, 4, 1, 1, 1, 5, 1, 1, 1,
                    0, 2, 4, 2, 4, 2, 2, 2, 0, 4, 2, 2, 2, 4, 1, 1, 3, 4, 1, 1, 2, 5, 1, 1, 3, 5, 1, 1, 4, 4, 2, 2,
                    0, 6, 2, 2, 2, 6, 4, 2, 0, 8, 2, 2, 2, 8, 2, 2, 4, 8, 2, 2)
                var k = 0
                while (k + 3 < tiles.size) {
                    val x = r.left + g + tiles[k] * (cell + g)
                    val y = y0 + tiles[k + 1] * (cell + g)
                    val w = tiles[k + 2] * cell + (tiles[k + 2] - 1) * g
                    val h = tiles[k + 3] * cell + (tiles[k + 3] - 1) * g
                    if (y + h < r.bottom - u * 0.4f) {
                        p.color = if ((k / 4) % 5 == 3) acc[(k / 4) % acc.size] else acc[0]
                        c.drawRect(x, y, x + w, y + h, p)
                    }
                    k += 4
                }
            }
            ScreenModes.CARDS -> {
                val chh = (bottom - top) * 0.82f
                val cww = pw * 0.56f
                val cyy = (top + bottom) / 2f
                for (k in intArrayOf(-1, 1, 0)) {
                    val s = if (k == 0) 1f else 0.8f
                    val x = r.centerX() + k * cww * 0.95f
                    p.color = cols[(k + 3) % cols.size]
                    p.alpha = if (k == 0) 255 else 140
                    c.drawRoundRect(x - cww * s / 2f, cyy - chh * s / 2f, x + cww * s / 2f, cyy + chh * s / 2f, u * 0.8f, u * 0.8f, p)
                }
                p.alpha = 255
                p.color = 0xEEFFFFFF.toInt()
                c.drawRoundRect(r.centerX() - u * 1.2f, cyy - u * 1.8f, r.centerX() + u * 1.2f, cyy + u * 0.6f, u * 0.5f, u * 0.5f, p)
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
            ScreenModes.SIDEBAR -> {
                val ic = u * 1.3f
                val x = r.right - u * 1.2f
                p.color = 0x33FFFFFF
                c.drawRoundRect(x - ic * 0.8f, top - u * 0.3f, x + ic * 0.8f, bottom, u, u, p)
                var y = top + ic * 0.6f
                var k = 0
                while (y + ic / 2f < bottom) {
                    tile(c, x, y, ic, k)
                    p.color = 0x99FFFFFF.toInt()
                    val lw = u * (2f + (k * 3 % 4))
                    c.drawRoundRect(x - ic - lw, y - u * 0.25f, x - ic, y + u * 0.25f, u * 0.25f, u * 0.25f, p)
                    y += ic * 1.35f
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
