package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Drawable de cristal para vistas normales (barra de búsqueda, tarjetas de Ajustes). */
class GlassDrawable(private val radiusDp: Float, private val alphaK: Float = 1f, private val surf: Int = -1) : Drawable() {
    private val g = Glass()
    private val r = RectF()
    override fun draw(c: Canvas) {
        val b = bounds
        r.set(b.left + dp(2f), b.top + dp(2f), b.right - dp(2f), b.bottom - dp(2f))
        g.draw(c, r, dp(radiusDp), alphaK, false, surf)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Capa que dibuja la animación de apertura (la "ventana" de cristal que crece desde el icono).
 * Cuando el resorte llega al punto de lanzamiento se abre la app de verdad.
 */
class OpenOverlay(ctx: Context) : View(ctx) {
    private val sp = SpringValue(0f, 300f, 34f, 0.002f)
    private val ticker = Ticker { onFrame() }
    private val glass = Glass()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val of = Motion.OF()
    private val from = RectF()
    private val to = RectF()
    private val cur = RectF()
    private val tmp = RectF()
    private var entry: AppEntry? = null
    private var style = 0
    private var fired = false
    private var onLaunch: (() -> Unit)? = null
    var active = false
        private set

    init {
        ticker.add(sp)
        visibility = INVISIBLE
    }

    fun start(e: AppEntry, src: RectF, styleIdx: Int, launch: () -> Unit) {
        entry = e
        style = styleIdx
        from.set(src)
        val root = parent as? View
        val w = if (width > 0) width else root?.width ?: resources.displayMetrics.widthPixels
        val h = if (height > 0) height else root?.height ?: resources.displayMetrics.heightPixels
        to.set(0f, 0f, w.toFloat(), h.toFloat())
        fired = false
        onLaunch = launch
        active = true
        Motion.openTune(style, sp)
        sp.snap(0f)
        sp.to(1f)
        visibility = VISIBLE
        ticker.kick()
        invalidate()
    }

    fun hide() {
        active = false
        ticker.stop()
        visibility = INVISIBLE
        entry = null
        onLaunch = null
    }

    private fun onFrame(): Boolean {
        val p = sp.value
        // Se lanza cuando la ventana ya cubre casi toda la pantalla.
        // Se lanza pronto: la ventana de la app aparece mientras la animación sigue (menos espera).
        val fireAt = Motion.openFireAt(style)
        if (!fired && (p >= fireAt || !sp.active)) {
            fired = true
            onLaunch?.invoke()
            onLaunch = null
        }
        invalidate()
        return false
    }

    override fun onDraw(c: Canvas) {
        val e = entry ?: return
        val p = sp.value
        Motion.openFrame(style, p, from, to, of)
        cur.set(of.l, of.t, of.r, of.b)
        c.save()
        if (of.rotY != 0f) Rot3D.apply(c, of.rotY, cur.centerX(), cur.centerY())
        val rad = max(0f, of.corner)
        fill.color = e.tint
        fill.alpha = (255 * clamp01(of.bg)).toInt()
        c.drawRoundRect(cur, rad, rad, fill)
        if (style == 2) {
            glass.draw(c, cur, max(rad, dp(4f)), 1f, false)
            if (of.glow > 0f) {
                glow.color = Color.WHITE
                glow.alpha = (60 * of.glow).toInt()
                c.drawRoundRect(cur, rad, rad, glow)
            }
        }
        val b = e.icon
        if (b != null && of.iconA > 0.01f) {
            val s = min(from.width(), from.height()) * of.iconS
            tmp.set(cur.centerX() - s / 2f, cur.centerY() - s / 2f, cur.centerX() + s / 2f, cur.centerY() + s / 2f)
            bmp.alpha = (255 * clamp01(of.iconA)).toInt()
            c.drawBitmap(b, null, tmp, bmp)
        }
        c.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = active

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }
}

/** Menú contextual de cristal (mantener presionado), con aparición de resorte. */
class MenuView(ctx: Context) : View(ctx) {
    /** [glyph] = icono de la opción (Glyphs.*), -1 sin icono */
    class Item(val label: String, val glyph: Int = -1, val action: () -> Unit)

    private val sp = SpringValue(0f, 340f, 22f, 0.002f)
    private val ticker = Ticker { invalidate(); false }
    private val glass = Glass()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sep = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hi = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrim = Paint()
    private val box = RectF()
    private val anchor = RectF()
    private var items: List<Item> = emptyList()
    private var title = ""
    private var pressed = -1
    private var above = true
    private var headIcon: android.graphics.Bitmap? = null
    private val headDst = RectF()
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val hiRect = RectF()
    private val boxPath = android.graphics.Path()
    private val backing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF141A2E.toInt() }
    /** el resaltado se desliza hasta la opción tocada y aparece/desaparece suave */
    private val hiY = SpringValue(0f, 700f, 45f, 0.3f)
    private val hiA = SpringValue(0f, 600f, 45f, 0.003f)
    private val rowH get() = dp(S.f(S.M_ROW))
    private val headH get() = dp(46f)
    var showing = false
        private set

    init {
        ticker.add(sp)
        ticker.add(hiY)
        ticker.add(hiA)
        text.color = Color.WHITE
        text.textSize = dp(16f)
        sep.color = 0x30FFFFFF
        hi.color = 0x30FFFFFF
        scrim.color = Color.BLACK
        visibility = INVISIBLE
    }

    fun show(t: String, a: RectF, list: List<Item>, icon: android.graphics.Bitmap? = null) {
        title = t
        headIcon = icon
        hiA.snap(0f)
        items = list
        anchor.set(a)
        val vw = (if (width > 0) width else resources.displayMetrics.widthPixels).toFloat()
        val vh = (if (height > 0) height else resources.displayMetrics.heightPixels).toFloat()
        val w = min(dp(S.f(S.M_WIDTH)), vw - dp(24f))
        val h = headH + rowH * list.size
        var left = a.centerX() - w / 2f
        left = left.coerceIn(dp(12f), max(dp(12f), vw - w - dp(12f)))
        above = a.top - h - dp(12f) > dp(40f)
        val top = if (above) a.top - h - dp(10f) else max(dp(12f), min(a.bottom + dp(10f), vh - h - dp(12f)))
        box.set(left, top, left + w, top + h)
        pressed = -1
        showing = true
        visibility = VISIBLE
        Tx.apply(text, S.TX_MENU, dp(16f), 400, 0f, 0)
        Mo.tune(sp, S.MO_MENU, 340f, 0.6f)
        sp.snap(0f)
        sp.to(1f)
        ticker.kick()
    }

    fun dismiss() {
        if (!showing) return
        showing = false
        sp.to(0f)
        ticker.kick()
        postDelayed({ if (!showing) visibility = INVISIBLE }, 260)
    }

    override fun onDraw(c: Canvas) {
        val p = sp.value
        val a = clamp01(p)
        scrim.alpha = (70 * a * S.p(S.M_DIM)).toInt().coerceIn(0, 255)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        val s = lerp(0.6f, 1f, p)
        val py = if (above) box.bottom else box.top
        c.save()
        c.scale(s, s, anchor.centerX().coerceIn(box.left, box.right), py)
        // base oscura y casi opaca bajo el cristal: los iconos de detrás ya no se ven a través del texto
        Squircle.path(boxPath, box, dp(24f))
        backing.alpha = (205 * a).toInt().coerceIn(0, 255)
        c.drawPath(boxPath, backing)
        glass.draw(c, box, dp(24f), a, true, S.SF_MENU)
        val base = text.textSize
        // cabecera: icono de la app + nombre
        var tx = box.left + dp(20f)
        val ic = headIcon
        if (ic != null) {
            val s = dp(26f)
            headDst.set(box.left + dp(16f), box.top + headH / 2f - s / 2f, box.left + dp(16f) + s, box.top + headH / 2f + s / 2f)
            headPaint.alpha = (255 * a).toInt()
            c.drawBitmap(ic, null, headDst, headPaint)
            tx = headDst.right + dp(10f)
        }
        text.alpha = Tx.alpha(S.TX_MENU, a * 0.67f)
        text.textSize = base * 0.85f
        c.drawText(Tx.str(S.TX_MENU, title), tx, box.top + dp(29f), text)
        text.textSize = base
        // resaltado redondeado que se desliza entre opciones
        if (hiA.value > 0.01f) {
            hiRect.set(box.left + dp(8f), hiY.value + dp(3f), box.right - dp(8f), hiY.value + rowH - dp(3f))
            hi.alpha = (48 * hiA.value * a).toInt().coerceIn(0, 255)
            c.drawRoundRect(hiRect, dp(14f), dp(14f), hi)
        }
        val anyGlyph = items.any { it.glyph >= 0 }
        for (i in items.indices) {
            // las opciones entran escalonadas, una detrás de otra
            val q = clamp01(p * 1.5f - min(i, 5) * 0.1f)
            val top = box.top + headH + i * rowH + (1f - q) * dp(10f)
            sep.alpha = (48 * a * q).toInt()
            c.drawLine(box.left + dp(16f), top, box.right - dp(16f), top, sep)
            text.alpha = Tx.alpha(S.TX_MENU, a * q)
            val lx = if (anyGlyph) box.left + dp(54f) else box.left + dp(20f)
            if (items[i].glyph >= 0) Glyphs.draw(c, items[i].glyph, box.left + dp(31f), top + rowH / 2f, dp(20f), Color.WHITE, a * q * 0.9f)
            c.drawText(Tx.str(S.TX_MENU, items[i].label), lx, top + rowH / 2f + dp(6f) + Tx.dy(S.TX_MENU), text)
        }
        c.restore()
    }

    private fun idxAt(x: Float, y: Float): Int {
        if (!box.contains(x, y)) return -1
        val i = ((y - box.top - headH) / rowH).toInt()
        return if (y >= box.top + headH && i in items.indices) i else -1
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!showing) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val i = idxAt(ev.x, ev.y)
                if (i != pressed) {
                    val ty = box.top + headH + max(0, i) * rowH
                    if (pressed < 0 || hiA.value < 0.05f) hiY.snap(ty) else hiY.to(ty)
                    hiA.to(if (i >= 0) 1f else 0f)
                    pressed = i
                    ticker.kick()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val i = idxAt(ev.x, ev.y)
                dismiss()
                if (i >= 0) {
                    val act = items[i].action
                    post { act() }
                }
            }
            MotionEvent.ACTION_CANCEL -> dismiss()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }
}

/**
 * Fondo esmerilado barato: al abrir un panel se toma UNA foto pequeña (1/6) del inicio,
 * se difumina una vez en CPU y luego solo se dibuja con transparencia. Cada fotograma cuesta
 * lo mismo que dibujar una imagen, así que la animación va fluida incluso en móviles modestos.
 */
class BackdropView(ctx: Context) : View(ctx) {
    private var bmp: android.graphics.Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    var progress = 0f
        set(v) {
            if (abs(v - field) > 0.002f) {
                field = v
                invalidate()
            }
        }

    init {
        visibility = INVISIBLE
    }

    val hasSnapshot: Boolean get() = bmp != null
    var tried = false
        private set

    fun capture(src: View) {
        if (tried) return
        tried = true
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return
        val s = intArrayOf(8, 6, 4)[S.i(S.P_BLUR_Q).coerceIn(0, 2)]
        try {
            val b = android.graphics.Bitmap.createBitmap(max(1, w / s), max(1, h / s), android.graphics.Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.scale(1f / s, 1f / s)
            val a = src.alpha
            src.alpha = 1f
            src.draw(c)
            src.alpha = a
            val old = bmp
            bmp = Blur.boxBlur(b, 4)
            old?.recycle()
            visibility = VISIBLE
            invalidate()
        } catch (t: Throwable) {
            bmp = null
        }
    }

    fun release() {
        tried = false
        visibility = INVISIBLE
        bmp?.recycle()
        bmp = null
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        paint.alpha = (255 * clamp01(progress)).toInt()
        if (paint.alpha == 0) return
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawBitmap(b, null, dst, paint)
    }
}
