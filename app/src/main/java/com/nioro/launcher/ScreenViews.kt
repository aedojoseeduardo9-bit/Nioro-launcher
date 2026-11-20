package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Base de los diseños propios de la pantalla de inicio (Mosaico, Tarjetas, Barra lateral y Minimalista).
 * No son las vistas del cajón de apps: cada una tiene su dibujo y su forma de moverse.
 * Aquí está lo que comparten: tocar para abrir, mantener para el menú y desplazar con inercia.
 */
abstract class HomeModeView(ctx: Context) : AppsView(ctx) {
    override val iconArea: Int = S.IA_HOME
    protected val scroll = Scroll1D(ticker)
    /** true: se desplaza en horizontal (Tarjetas); false: en vertical */
    protected open val horizontal = false
    private var vt: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastPos = 0f
    private var dragging = false
    private var longFired = false
    protected var downIdx = -1
    private val longRun = Runnable {
        val i = downIdx
        if (i in shown.indices && !dragging) {
            longFired = true
            lift()
            listener?.onMenu(shown[i], itemRect(i))
        }
    }

    /** índice de la app bajo el dedo (-1 si no hay) */
    abstract fun hit(x: Float, y: Float): Int
    /** rectángulo de la app [i] en pantalla (para la animación de abrir y el menú) */
    abstract fun itemRect(i: Int): RectF
    abstract fun relayout()
    /** ¿se acepta un toque que no cae sobre ninguna app? (para poder desplazar desde los huecos) */
    protected open fun takesEmpty(x: Float, y: Float): Boolean = true
    /** al soltar: por defecto inercia libre */
    protected open fun released(vel: Float) = scroll.release(vel)

    // ---------------------------------------------------------------- Bordes con degradado
    /**
     * Las apps no se cortan de golpe contra el reloj o el dock: en cada borde se desvanecen con un
     * degradado. Solo aparece en el lado donde hay más contenido (arriba, si ya se bajó; abajo, si queda).
     */
    private val fadeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val fadeLen get() = dp(34f)
    private var gA: LinearGradient? = null
    private var gB: LinearGradient? = null
    private var gKey = ""

    /** ¿se desvanecen siempre los dos bordes? (Tarjetas: las vecinas asoman por los lados) */
    protected open val fadeAlways = false

    private fun gradients(top: Float, bottom: Float) {
        val key = "$top|$bottom|$width|$horizontal"
        if (key == gKey) return
        gKey = key
        val f = fadeLen
        val opaque = 0xFF000000.toInt()
        if (horizontal) {
            val w = width.toFloat()
            gA = LinearGradient(0f, 0f, f, 0f, opaque, 0, Shader.TileMode.CLAMP)
            gB = LinearGradient(w - f, 0f, w, 0f, 0, opaque, Shader.TileMode.CLAMP)
        } else {
            gA = LinearGradient(0f, top, 0f, top + f, opaque, 0, Shader.TileMode.CLAMP)
            gB = LinearGradient(0f, bottom - f, 0f, bottom, 0, opaque, Shader.TileMode.CLAMP)
        }
    }

    override fun draw(c: Canvas) {
        val top = insetTop
        val bottom = height - insetBottom
        if (!embedded || bottom - top < fadeLen * 2f || width <= 0) return super.draw(c)
        gradients(top, bottom)
        val save = c.saveLayer(0f, top, width.toFloat(), bottom, null)
        super.draw(c)
        val f = fadeLen
        // cuánto se desvanece cada borde (aparece poco a poco al empezar a desplazar)
        val ka = if (fadeAlways) 1f else clamp01((scroll.value - scroll.min) / f)
        val kb = if (fadeAlways) 1f else clamp01((scroll.max - scroll.value) / f)
        if (ka > 0.01f) {
            fadeP.shader = gA
            fadeP.alpha = (255f * ka).toInt()
            if (horizontal) c.drawRect(0f, top, f, bottom, fadeP) else c.drawRect(0f, top, width.toFloat(), top + f, fadeP)
        }
        if (kb > 0.01f) {
            fadeP.shader = gB
            fadeP.alpha = (255f * kb).toInt()
            if (horizontal) c.drawRect(width - f, top, width.toFloat(), bottom, fadeP) else c.drawRect(0f, bottom - f, width.toFloat(), bottom, fadeP)
        }
        c.restoreToCount(save)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        relayout()
    }

    override fun onImeChanged() {
        relayout()
        invalidate()
    }

    override fun onDataChanged() {
        relayout()
    }

    override fun onOpen() {
        relayout()
        invalidate()
    }

    override fun onFrame(dt: Float): Boolean {
        invalidate()
        return false
    }

    /** entrada escalonada: 0 -> 1 para el elemento número [order] */
    protected fun enter(order: Int): Float {
        val t = clamp01(progress * 1.6f - min(order, 14) * 0.05f)
        val k = 1f - t
        return 1f - k * k * k
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        val pos = if (horizontal) x else y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val i = hit(x, y)
                if (i < 0 && !takesEmpty(x, y)) return false
                listener?.onTouchStart()
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
                downX = x
                downY = y
                lastPos = pos
                dragging = false
                longFired = false
                scroll.stop()
                downIdx = i
                if (i >= 0) {
                    press(shown[i])
                    uiHandler.removeCallbacks(longRun)
                    uiHandler.postDelayed(longRun, longMs)
                }
                ticker.kick()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (!dragging && !longFired) {
                    val d = if (horizontal) abs(x - downX) else abs(y - downY)
                    if (d > slop || abs(x - downX) > slop * 2f || abs(y - downY) > slop * 2f) {
                        dragging = true
                        uiHandler.removeCallbacks(longRun)
                        downIdx = -1
                        unpress()
                        lastPos = pos
                    }
                }
                if (dragging) {
                    scroll.drag(lastPos - pos)
                    lastPos = pos
                    ticker.kick()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longRun)
                vt?.addMovement(ev)
                vt?.computeCurrentVelocity(1000)
                val v = if (horizontal) -(vt?.xVelocity ?: 0f) else -(vt?.yVelocity ?: 0f)
                vt?.recycle()
                vt = null
                val i = downIdx
                downIdx = -1
                unpress()
                if (dragging) {
                    released(v)
                } else {
                    released(0f)
                    if (ev.actionMasked == MotionEvent.ACTION_UP && !longFired && i in shown.indices) {
                        listener?.onLaunch(shown[i], itemRect(i))
                    }
                }
                dragging = false
                ticker.kick()
                return true
            }
        }
        return true
    }
}

/**
 * Mosaico: baldosas de cristal de dos tamaños, como un muro de azulejos. Cada grupo de cinco apps
 * forma un bloque: una baldosa grande y cuatro pequeñas, y el siguiente bloque va al revés.
 * Se desplaza hacia arriba y abajo.
 */
class MosaicView(ctx: Context) : HomeModeView(ctx) {
    private val tiles = ArrayList<RectF>()
    private val tileP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val nameP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val names = HashMap<String, String>()
    private val tmp = RectF()
    private var cell = 0f
    private var gap = 0f
    private var top0 = 0f

    init {
        nameP.color = Color.WHITE
        nameP.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        nameP.setShadowLayer(dp(3f), 0f, dp(1f), 0x88000000.toInt())
        edgeP.strokeWidth = dp(1f)
    }

    override fun relayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val side = dp(16f)
        gap = dp(10f)
        cell = (w - side * 2f - gap * 3f) / 4f
        top0 = insetTop + dp(8f)
        tiles.clear()
        names.clear()
        var y = top0
        var k = 0
        var block = 0
        while (k < shown.size) {
            val bigLeft = block % 2 == 0
            val bigX = if (bigLeft) side else side + (cell + gap) * 2f
            val smallX = if (bigLeft) side + (cell + gap) * 2f else side
            tiles.add(RectF(bigX, y, bigX + cell * 2f + gap, y + cell * 2f + gap))
            k++
            for (s in 0 until 4) {
                if (k >= shown.size) break
                val sx = smallX + (s % 2) * (cell + gap)
                val sy = y + (s / 2) * (cell + gap)
                tiles.add(RectF(sx, sy, sx + cell, sy + cell))
                k++
            }
            y += cell * 2f + gap * 2f
            block++
        }
        val viewH = h - insetBottom - top0
        scroll.dim = viewH
        scroll.min = 0f
        scroll.max = max(0f, y - top0 - viewH + dp(12f))
        if (scroll.value > scroll.max) scroll.jump(scroll.max)
    }

    override fun hit(x: Float, y: Float): Int {
        val yy = y + scroll.value
        for (i in tiles.indices) {
            val r = tiles[i]
            if (x >= r.left && x <= r.right && yy >= r.top && yy <= r.bottom) return if (i < shown.size) i else -1
        }
        return -1
    }

    override fun itemRect(i: Int): RectF {
        val r = tiles.getOrNull(i) ?: return RectF()
        return RectF(r.left, r.top - scroll.value, r.right, r.bottom - scroll.value)
    }

    override fun onDraw(c: Canvas) {
        val off = scroll.value
        val top = insetTop
        val bottom = height - insetBottom
        for (i in tiles.indices) {
            if (i >= shown.size) break
            val r = tiles[i]
            val t = r.top - off
            val b = r.bottom - off
            if (b < top || t > bottom) continue
            val e = shown[i]
            val q = enter(i)
            if (q <= 0.01f) continue
            val big = r.width() > cell * 1.5f
            val pressed = i == downIdx
            val s = (if (pressed) pressSp.value else 1f) * (0.9f + 0.1f * q)
            tmp.set(r.left, t, r.right, b)
            c.save()
            c.scale(s, s, tmp.centerX(), tmp.centerY())
            c.translate(0f, (1f - q) * dp(26f))
            val rad = if (big) dp(30f) else dp(22f)
            // baldosa teñida con el color del icono
            tileP.color = blend(e.tint, 0xFF0B1026.toInt(), 0.45f)
            tileP.alpha = (200f * q).toInt()
            c.drawRoundRect(tmp, rad, rad, tileP)
            edgeP.color = 0x40FFFFFF
            edgeP.alpha = (90f * q).toInt()
            c.drawRoundRect(tmp, rad, rad, edgeP)
            val icon = if (big) cell * 0.95f else cell * 0.58f
            val iy = if (big) tmp.centerY() - dp(12f) else tmp.centerY() - (if (Prefs.labels) dp(7f) else 0f)
            drawIcon(c, e, tmp.centerX(), iy, icon, q)
            if (Prefs.labels) {
                nameP.textSize = if (big) dp(15f) else dp(10.5f)
                nameP.textAlign = Paint.Align.CENTER
                nameP.alpha = (255f * q).toInt()
                val maxW = tmp.width() - dp(14f)
                val key = e.key + if (big) "B" else "s"
                val n = names.getOrPut(key) { TextUtils.ellipsize(e.label, nameP, maxW, TextUtils.TruncateAt.END).toString() }
                c.drawText(n, tmp.centerX(), tmp.bottom - (if (big) dp(18f) else dp(9f)), nameP)
            }
            c.restore()
        }
    }
}

/**
 * Tarjetas: una app por tarjeta grande, en fila. Se pasan con el dedo a izquierda y derecha y se
 * asientan solas en la del centro; la de en medio se ve entera y las vecinas, más pequeñas detrás.
 */
class CardsView(ctx: Context) : HomeModeView(ctx) {
    override val horizontal = true
    override val fadeAlways = true
    private val cardP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shineP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nameP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val hintP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmp = RectF()
    private var cw = 0f
    private var ch = 0f
    private var cy = 0f
    private var stepX = 0f
    private var lastSnap = -1
    private val names = HashMap<String, String>()

    init {
        nameP.color = Color.WHITE
        nameP.textAlign = Paint.Align.CENTER
        nameP.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        nameP.setShadowLayer(dp(4f), 0f, dp(1f), 0x88000000.toInt())
        hintP.color = 0xB3FFFFFF.toInt()
        hintP.textAlign = Paint.Align.CENTER
        hintP.textSize = dp(12.5f)
        hintP.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }

    override fun relayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val areaH = max(dp(120f), h - insetBottom - insetTop - dp(30f))
        ch = min(areaH, dp(330f))
        cw = min(w * 0.64f, ch * 0.78f)
        cy = insetTop + dp(6f) + (areaH - ch) / 2f + ch / 2f
        stepX = cw + dp(14f)
        names.clear()
        scroll.dim = w
        scroll.min = 0f
        scroll.max = max(0f, (shown.size - 1) * stepX)
        if (scroll.value > scroll.max) scroll.jump(scroll.max)
    }

    /** al soltar se asienta en la tarjeta más cercana en la dirección del gesto */
    override fun released(vel: Float) {
        if (stepX <= 0f) return
        val proj = scroll.value + vel * 0.18f
        val idx = (proj / stepX).roundToInt().coerceIn(0, max(0, shown.size - 1))
        scroll.stop()
        Mo.tune(scroll.sp, S.MO_SCROLL, 220f, 0.85f)
        scroll.sp.to(idx * stepX)
        scroll.sp.velocity = vel * 0.4f
    }

    override fun takesEmpty(x: Float, y: Float): Boolean = y > cy - ch / 2f - dp(20f) && y < cy + ch / 2f + dp(30f)

    private fun cardX(i: Int): Float = width / 2f + i * stepX - scroll.value

    private fun scaleAt(dx: Float): Float = 1f - 0.16f * clamp01(abs(dx) / stepX)

    override fun hit(x: Float, y: Float): Int {
        if (stepX <= 0f) return -1
        // la de en medio primero (está delante)
        val centre = (scroll.value / stepX).roundToInt()
        for (i in intArrayOf(centre, centre - 1, centre + 1)) {
            if (i !in shown.indices) continue
            val cx = cardX(i)
            val s = scaleAt(cx - width / 2f)
            if (abs(x - cx) <= cw * s / 2f && abs(y - cy) <= ch * s / 2f) return i
        }
        return -1
    }

    override fun itemRect(i: Int): RectF {
        val cx = cardX(i)
        val s = scaleAt(cx - width / 2f)
        return RectF(cx - cw * s / 2f, cy - ch * s / 2f, cx + cw * s / 2f, cy + ch * s / 2f)
    }

    override fun onFrame(dt: Float): Boolean {
        // un "clic" suave cada vez que una tarjeta llega al centro
        if (stepX > 0f) {
            val n = (scroll.value / stepX).roundToInt()
            if (n != lastSnap) {
                if (lastSnap >= 0) Hx.fire(this, S.HE_PAGE)
                lastSnap = n
            }
        }
        return super.onFrame(dt)
    }

    override fun onDraw(c: Canvas) {
        if (shown.isEmpty() || stepX <= 0f) return
        val centreF = scroll.value / stepX
        val from = (centreF - 2f).toInt().coerceAtLeast(0)
        val to = (centreF + 2f).toInt().coerceAtMost(shown.size - 1)
        val q = enter(0)
        // de las de los lados hacia la del centro, para que la del centro quede delante (sin crear listas)
        val centre = centreF.roundToInt()
        for (pass in 2 downTo 0) for (side in 0..1) {
            if (pass == 0 && side == 1) continue
            val i = if (side == 0) centre - pass else centre + pass
            if (i < from || i > to) continue
            val e = shown[i]
            val cx = cardX(i)
            val d = cx - width / 2f
            val near = clamp01(1f - abs(d) / stepX)
            val s = scaleAt(d) * (if (i == downIdx) pressSp.value else 1f) * (0.85f + 0.15f * q)
            tmp.set(cx - cw * s / 2f, cy - ch * s / 2f, cx + cw * s / 2f, cy + ch * s / 2f)
            tmp.offset(0f, (1f - q) * dp(40f))
            val a = q * (0.45f + 0.55f * near)
            val rad = dp(34f) * s
            cardP.color = blend(e.tint, 0xFF0A0F24.toInt(), 0.35f)
            cardP.alpha = (235f * a).toInt()
            c.drawRoundRect(tmp, rad, rad, cardP)
            // brillo de cristal arriba
            shineP.color = 0x22FFFFFF
            shineP.alpha = (40f * a).toInt()
            c.drawRoundRect(tmp.left, tmp.top, tmp.right, tmp.top + tmp.height() * 0.45f, rad, rad, shineP)
            val icon = cw * 0.5f * s
            drawIcon(c, e, tmp.centerX(), tmp.top + tmp.height() * 0.42f, icon, a)
            nameP.alpha = (255f * a).toInt()
            // el nombre se recorta una vez al tamaño de la tarjeta del centro y luego solo se escala
            val n = names.getOrPut(e.key) {
                nameP.textSize = dp(19f)
                TextUtils.ellipsize(e.label, nameP, cw - dp(24f), TextUtils.TruncateAt.END).toString()
            }
            nameP.textSize = dp(19f) * s
            c.drawText(n, tmp.centerX(), tmp.top + tmp.height() * 0.78f, nameP)
            if (near > 0.9f) {
                hintP.alpha = (180f * q * near).toInt()
                c.drawText("Toca para abrir", tmp.centerX(), tmp.top + tmp.height() * 0.89f, hintP)
            }
        }
        // posición: puntito alargado para la tarjeta actual
        val n = shown.size
        if (n in 2..40) {
            val dy = cy + ch / 2f + dp(18f)
            val gapD = dp(10f)
            val x0 = width / 2f - (n - 1) * gapD / 2f
            for (i in 0 until n) {
                val on = clamp01(1f - abs(i - centreF))
                dotP.color = Color.WHITE
                dotP.alpha = ((70f + 185f * on) * q).toInt()
                val wDot = dp(3f) + dp(5f) * on
                c.drawRoundRect(x0 + i * gapD - wDot, dy - dp(3f), x0 + i * gapD + wDot, dy + dp(3f), dp(3f), dp(3f), dotP)
            }
        }
    }
}

/**
 * Barra lateral: una columna de iconos pegada al borde derecho, a mano del pulgar, con el nombre en
 * una cápsula al lado. Se desplaza arriba y abajo; el resto de la pantalla queda libre para el reloj.
 */
class SideBarView(ctx: Context) : HomeModeView(ctx) {
    private val pillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val railP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nameP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val names = HashMap<String, String>()
    private val tmp = RectF()
    private var icon = 0f
    private var rowH = 0f
    private var cx = 0f
    private var top0 = 0f
    private var nameMax = 0f

    init {
        nameP.color = Color.WHITE
        nameP.textAlign = Paint.Align.RIGHT
        nameP.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        nameP.textSize = dp(13.5f)
        nameP.setShadowLayer(dp(3f), 0f, dp(1f), 0x88000000.toInt())
    }

    override fun relayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        icon = dp(46f)
        rowH = icon + dp(16f)
        cx = w - dp(18f) - icon / 2f
        top0 = insetTop + dp(10f)
        nameMax = w * 0.5f
        names.clear()
        val viewH = h - insetBottom - top0
        scroll.dim = viewH
        scroll.min = 0f
        scroll.max = max(0f, shown.size * rowH - viewH + dp(10f))
        if (scroll.value > scroll.max) scroll.jump(scroll.max)
    }

    /** la columna solo ocupa la franja derecha: el resto del inicio sigue respondiendo como siempre */
    override fun takesEmpty(x: Float, y: Float): Boolean = x > cx - icon

    private fun rowCy(i: Int): Float = top0 + i * rowH + rowH / 2f - scroll.value

    override fun hit(x: Float, y: Float): Int {
        if (rowH <= 0f) return -1
        val i = ((y - top0 + scroll.value) / rowH).toInt()
        if (i !in shown.indices) return -1
        if (x >= cx - icon / 2f - dp(10f)) return i
        // también vale tocar su nombre
        val n = names[shown[i].key] ?: return -1
        val tw = nameP.measureText(n)
        return if (x >= cx - icon / 2f - dp(22f) - tw) i else -1
    }

    override fun itemRect(i: Int): RectF {
        val y = rowCy(i)
        return RectF(cx - icon / 2f, y - icon / 2f, cx + icon / 2f, y + icon / 2f)
    }

    override fun onDraw(c: Canvas) {
        val top = insetTop
        val bottom = height - insetBottom
        // carril de cristal detrás de los iconos
        val q0 = enter(0)
        railP.color = 0x26FFFFFF
        railP.alpha = (60f * q0).toInt()
        tmp.set(cx - icon / 2f - dp(8f), top + dp(4f), cx + icon / 2f + dp(8f), bottom - dp(4f))
        c.drawRoundRect(tmp, dp(30f), dp(30f), railP)
        for (i in shown.indices) {
            val y = rowCy(i)
            if (y + rowH < top || y - rowH > bottom) continue
            val e = shown[i]
            val q = enter(i - (scroll.value / max(1f, rowH)).toInt())
            if (q <= 0.01f) continue
            val ox = (1f - q) * dp(60f)
            drawIcon(c, e, cx + ox, y, icon, q)
            if (Prefs.labels) {
                val n = names.getOrPut(e.key) { TextUtils.ellipsize(e.label, nameP, nameMax, TextUtils.TruncateAt.END).toString() }
                val tw = nameP.measureText(n)
                val right = cx - icon / 2f - dp(14f) + ox
                tmp.set(right - tw - dp(12f), y - dp(14f), right + dp(10f), y + dp(14f))
                pillP.color = 0x8C0B1026.toInt()
                pillP.alpha = ((if (i == downIdx) 220f else 140f) * q).toInt()
                c.drawRoundRect(tmp, dp(14f), dp(14f), pillP)
                nameP.alpha = (255f * q).toInt()
                c.drawText(n, right, y - (nameP.descent() + nameP.ascent()) / 2f, nameP)
            }
        }
    }
}

/**
 * Minimalista: todas tus apps como una lista de texto grande, sin iconos ni dock. Se desplaza arriba y
 * abajo; no hay menú de apps porque esta lista ya lo es.
 */
class MinimalListView(ctx: Context) : HomeModeView(ctx) {
    private val nameP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val letterP = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val names = HashMap<String, String>()
    private var rowH = 0f
    private var x0 = 0f
    private var top0 = 0f

    init {
        nameP.color = Color.WHITE
        nameP.typeface = Typeface.create(Typeface.DEFAULT, 300, false)
        nameP.setShadowLayer(dp(6f), 0f, dp(1.5f), 0x66000000)
        letterP.color = 0x80FFFFFF.toInt()
        letterP.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        letterP.textSize = dp(12f)
    }

    override fun relayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        rowH = dp(54f)
        nameP.textSize = dp(25f)
        x0 = dp(30f)
        top0 = insetTop + dp(8f)
        names.clear()
        val viewH = h - insetBottom - top0
        scroll.dim = viewH
        scroll.min = 0f
        scroll.max = max(0f, shown.size * rowH - viewH + dp(16f))
        if (scroll.value > scroll.max) scroll.jump(scroll.max)
    }

    /** el borde derecho queda libre para que el inicio siga respondiendo ahí */
    override fun takesEmpty(x: Float, y: Float): Boolean = x < width * 0.82f

    private fun rowTop(i: Int): Float = top0 + i * rowH - scroll.value

    private fun nameOf(e: AppEntry): String = names.getOrPut(e.key) {
        TextUtils.ellipsize(e.label, nameP, width - x0 * 2f, TextUtils.TruncateAt.END).toString()
    }

    override fun hit(x: Float, y: Float): Int {
        if (rowH <= 0f) return -1
        val i = ((y - top0 + scroll.value) / rowH).toInt()
        if (i !in shown.indices || y < top0 - scroll.value) return -1
        return if (x >= x0 - dp(16f) && x <= x0 + nameP.measureText(nameOf(shown[i])) + dp(24f)) i else -1
    }

    override fun itemRect(i: Int): RectF {
        val t = rowTop(i)
        val w = if (i in shown.indices) nameP.measureText(nameOf(shown[i])) else dp(80f)
        return RectF(x0 - dp(10f), t + dp(4f), x0 + w + dp(10f), t + rowH - dp(4f))
    }

    override fun onDraw(c: Canvas) {
        val top = insetTop
        val bottom = height - insetBottom
        val first = ((scroll.value) / max(1f, rowH)).toInt()
        var lastLetter = ' '
        for (i in shown.indices) {
            val t = rowTop(i)
            if (t + rowH < top || t > bottom) {
                lastLetter = shown[i].label.firstOrNull()?.uppercaseChar() ?: ' '
                continue
            }
            val e = shown[i]
            val q = enter(i - first)
            if (q <= 0.01f) continue
            val ox = (1f - q) * -dp(36f)
            val pk = i == downIdx
            val sc = if (pk) pressSp.value else 1f
            val cy = t + rowH / 2f
            // la letra inicial al margen, solo cuando cambia (ayuda a orientarse en listas largas)
            val letter = e.label.firstOrNull()?.uppercaseChar() ?: ' '
            if (letter != lastLetter) {
                letterP.alpha = (130f * q).toInt()
                c.drawText(letter.toString(), dp(10f), cy - (letterP.descent() + letterP.ascent()) / 2f, letterP)
                lastLetter = letter
            }
            nameP.alpha = (255f * q * (if (pk) 0.7f else 1f)).toInt().coerceIn(0, 255)
            c.save()
            c.scale(sc, sc, x0, cy)
            c.drawText(nameOf(e), x0 + ox, cy - (nameP.descent() + nameP.ascent()) / 2f, nameP)
            c.restore()
        }
    }
}
