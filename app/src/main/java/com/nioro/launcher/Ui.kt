package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Colores estilo One UI. */
object OneColors {
    /** color de acento (configurable; por defecto el azul One UI) */
    val ACCENT: Int get() = S.col(S.L_ACCENT, 0xFF3E91FF.toInt())
    const val TEXT = 0xFFFFFFFF.toInt()
    const val SUB = 0x99FFFFFF.toInt()
    const val TRACK = 0x40FFFFFF
    val CATS = intArrayOf(
        0xFF3E91FF.toInt(), // inicio
        0xFF7C5CFF.toInt(), // dock
        0xFF00B8A9.toInt(), // apps
        0xFFFF7A45.toInt(), // animaciones
        0xFFFF4D8D.toInt(), // recientes
        0xFFB45CFF.toInt(), // apariencia
        0xFF2FC26B.toInt(), // batería
        0xFF8A94A6.toInt(), // acerca de
        0xFFFFB020.toInt()  // gestos y vibración
    )
}

/** Iconos lineales dibujados con Canvas (sin imágenes, nítidos en cualquier tamaño). */
object Glyphs {
    const val HOME = 0
    const val DOCK = 1
    const val GRID = 2
    const val MOTION = 3
    const val RECENTS = 4
    const val PALETTE = 5
    const val BATTERY = 6
    const val INFO = 7
    const val WALLPAPER = 8
    const val GEAR = 9
    const val STYLE = 10
    const val SEARCH = 11
    const val MORE = 12
    const val CHEVRON = 13
    const val CHECK = 14
    const val BACK = 15
    const val CLOCK = 16
    const val TRASH = 17
    const val HIDE = 18
    const val PLUS = 19
    const val MINUS = 20
    const val RESIZE = 21

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()

    fun draw(c: Canvas, kind: Int, cx: Float, cy: Float, size: Float, color: Int, alpha: Float = 1f) {
        val s = size / 24f // diseñados en una rejilla de 24
        p.color = color
        p.alpha = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * s
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        c.save()
        c.translate(cx - 12f * s, cy - 12f * s)
        c.scale(s, s)
        p.strokeWidth = 2f
        path.reset()
        when (kind) {
            HOME -> {
                path.moveTo(3.5f, 11f); path.lineTo(12f, 4f); path.lineTo(20.5f, 11f)
                c.drawPath(path, p)
                r.set(6f, 10f, 18f, 20f); c.drawRoundRect(r, 2.5f, 2.5f, p)
                r.set(10.5f, 14f, 13.5f, 20f); c.drawRoundRect(r, 1f, 1f, p)
            }
            DOCK -> {
                r.set(3f, 15f, 21f, 20f); c.drawRoundRect(r, 2.5f, 2.5f, p)
                for (i in 0 until 3) { r.set(4.5f + i * 5.5f, 5f, 8.5f + i * 5.5f, 9f + (if (i == 1) 3f else 0f)); c.drawRoundRect(r, 1.2f, 1.2f, p) }
            }
            GRID -> {
                for (i in 0 until 3) for (j in 0 until 3) { r.set(4f + i * 6f, 4f + j * 6f, 8f + i * 6f, 8f + j * 6f); c.drawRoundRect(r, 1.2f, 1.2f, p) }
            }
            MOTION -> {
                path.moveTo(3f, 16f)
                path.cubicTo(7f, 6f, 10f, 6f, 12f, 12f)
                path.cubicTo(14f, 18f, 17f, 18f, 21f, 8f)
                c.drawPath(path, p)
                p.style = Paint.Style.FILL; c.drawCircle(21f, 8f, 1.8f, p)
            }
            RECENTS -> {
                r.set(4f, 7f, 14f, 20f); c.drawRoundRect(r, 2.5f, 2.5f, p)
                path.moveTo(9f, 4f); path.lineTo(17.5f, 4f); path.quadTo(20f, 4f, 20f, 6.5f); path.lineTo(20f, 16f)
                c.drawPath(path, p)
            }
            PALETTE -> {
                c.drawCircle(12f, 12f, 8.5f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(8.5f, 10f, 1.4f, p); c.drawCircle(12f, 7.5f, 1.4f, p); c.drawCircle(15.5f, 10f, 1.4f, p)
                c.drawCircle(14f, 15.5f, 2f, p)
            }
            BATTERY -> {
                r.set(3f, 7.5f, 19f, 16.5f); c.drawRoundRect(r, 2.5f, 2.5f, p)
                r.set(20f, 10.5f, 21.5f, 13.5f); c.drawRoundRect(r, 0.7f, 0.7f, p)
                p.style = Paint.Style.FILL; r.set(5.5f, 10f, 13f, 14f); c.drawRoundRect(r, 1f, 1f, p)
            }
            INFO -> {
                c.drawCircle(12f, 12f, 8.5f, p)
                c.drawLine(12f, 11f, 12f, 16.5f, p)
                p.style = Paint.Style.FILL; c.drawCircle(12f, 7.8f, 1.3f, p)
            }
            WALLPAPER -> {
                r.set(3.5f, 4.5f, 20.5f, 19.5f); c.drawRoundRect(r, 3f, 3f, p)
                path.moveTo(4f, 17f); path.lineTo(9.5f, 11f); path.lineTo(13f, 14.5f); path.lineTo(15.5f, 12f); path.lineTo(20f, 16.5f)
                c.drawPath(path, p)
                p.style = Paint.Style.FILL; c.drawCircle(15.5f, 8.5f, 1.6f, p)
            }
            GEAR -> {
                c.drawCircle(12f, 12f, 3f, p)
                c.drawCircle(12f, 12f, 6.5f, p)
                for (i in 0 until 8) {
                    val a = i * Math.PI.toFloat() / 4f
                    c.drawLine(12f + cos(a) * 6.5f, 12f + sin(a) * 6.5f, 12f + cos(a) * 9f, 12f + sin(a) * 9f, p)
                }
            }
            STYLE -> {
                path.moveTo(19.5f, 4.5f); path.lineTo(10.5f, 13.5f)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(10f, 13f); path.cubicTo(6f, 13f, 7f, 18f, 4f, 19.5f); path.cubicTo(9f, 21f, 12.5f, 18f, 11.5f, 14.5f)
                c.drawPath(path, p)
            }
            SEARCH -> {
                c.drawCircle(10.5f, 10.5f, 6f, p)
                c.drawLine(15f, 15f, 20f, 20f, p)
            }
            MORE -> {
                p.style = Paint.Style.FILL
                c.drawCircle(12f, 5.5f, 1.7f, p); c.drawCircle(12f, 12f, 1.7f, p); c.drawCircle(12f, 18.5f, 1.7f, p)
            }
            CHEVRON -> {
                path.moveTo(9.5f, 6f); path.lineTo(15.5f, 12f); path.lineTo(9.5f, 18f)
                c.drawPath(path, p)
            }
            CHECK -> {
                path.moveTo(5f, 12.5f); path.lineTo(10f, 17.5f); path.lineTo(19.5f, 7f)
                c.drawPath(path, p)
            }
            BACK -> {
                path.moveTo(14.5f, 5.5f); path.lineTo(8f, 12f); path.lineTo(14.5f, 18.5f)
                c.drawPath(path, p)
            }
            CLOCK -> {
                c.drawCircle(12f, 12f, 8.5f, p)
                path.moveTo(12f, 7.5f); path.lineTo(12f, 12f); path.lineTo(15.5f, 14f)
                c.drawPath(path, p)
            }
            TRASH -> {
                c.drawLine(4.5f, 6.5f, 19.5f, 6.5f, p)
                path.moveTo(9.5f, 6.5f); path.lineTo(10f, 4f); path.lineTo(14f, 4f); path.lineTo(14.5f, 6.5f)
                c.drawPath(path, p)
                r.set(6.5f, 6.5f, 17.5f, 20f); c.drawRoundRect(r, 2f, 2f, p)
                c.drawLine(10.5f, 10f, 10.5f, 16.5f, p); c.drawLine(13.5f, 10f, 13.5f, 16.5f, p)
            }
            HIDE -> {
                path.moveTo(3f, 12f); path.quadTo(12f, 3.5f, 21f, 12f); path.quadTo(12f, 20.5f, 3f, 12f)
                c.drawPath(path, p)
                c.drawCircle(12f, 12f, 2.8f, p)
                c.drawLine(4.5f, 19.5f, 19.5f, 4.5f, p)
            }
            PLUS -> {
                c.drawLine(12f, 5f, 12f, 19f, p); c.drawLine(5f, 12f, 19f, 12f, p)
            }
            MINUS -> {
                c.drawLine(5f, 12f, 19f, 12f, p)
            }
            RESIZE -> {
                c.drawLine(6f, 18f, 18f, 6f, p)
                path.moveTo(12.5f, 6f); path.lineTo(18f, 6f); path.lineTo(18f, 11.5f)
                path.moveTo(6f, 12.5f); path.lineTo(6f, 18f); path.lineTo(11.5f, 18f)
                c.drawPath(path, p)
            }
        }
        c.restore()
    }
}

/** Drawable de un glifo (para EditText, botones...). */
class GlyphDrawable(private val kind: Int, private val sizeDp: Float, private val color: Int = Color.WHITE) : Drawable() {
    init {
        val px = dp(sizeDp).toInt()
        setBounds(0, 0, px, px)
    }
    private var a = 255
    override fun draw(c: Canvas) {
        if (a == 0) return
        val b = bounds
        val col = if (a >= 255) color else (color and 0x00FFFFFF) or ((((color ushr 24) * a) / 255) shl 24)
        Glyphs.draw(c, kind, b.exactCenterX(), b.exactCenterY(), b.width().toFloat(), col)
    }
    override fun getIntrinsicWidth(): Int = dp(sizeDp).toInt()
    override fun getIntrinsicHeight(): Int = dp(sizeDp).toInt()
    override fun setAlpha(alpha: Int) {
        val v = alpha.coerceIn(0, 255)
        if (v != a) {
            a = v
            invalidateSelf()
        }
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Icono de categoría estilo One UI / Smart Launcher: círculo de color con glifo blanco. */
class CategoryIconView(ctx: Context, private val kind: Int, private val color: Int) : View(ctx) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hi = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val r = width.coerceAtMost(height) / 2f
        bg.color = color
        c.drawCircle(width / 2f, height / 2f, r, bg)
        // brillo de cristal superior
        hi.shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(), 0x40FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(width / 2f, height / 2f, r, hi)
        Glyphs.draw(c, kind, width / 2f, height / 2f, r * 1.05f, Color.WHITE)
    }
}

/** Interruptor estilo One UI con animación de resorte. */
class OneSwitch(ctx: Context) : View(ctx) {
    var checked = false
        private set
    var onChange: ((Boolean) -> Unit)? = null
    private val ticker = Ticker { invalidate(); false }
    private val sp = ticker.add(SpringValue(0f, 520f, 34f, 0.002f))
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rr = RectF()

    init {
        setOnClickListener { set(!checked, true) }
    }

    fun set(v: Boolean, user: Boolean = false) {
        checked = v
        if (user) {
            Mo.tune(sp, S.MO_CONTROLS, 520f, 0.75f)
            sp.to(if (v) 1f else 0f)
            ticker.kick()
            Hx.fire(this, S.HE_SETTING)
            onChange?.invoke(v)
        } else {
            sp.snap(if (v) 1f else 0f)
            invalidate()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(dp(52f).toInt(), dp(30f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val t = sp.value
        rr.set(dp(2f), dp(4f), width - dp(2f), height - dp(4f))
        track.color = blend(0x55FFFFFF, OneColors.ACCENT, clamp01(t))
        c.drawRoundRect(rr, rr.height() / 2f, rr.height() / 2f, track)
        val rad = rr.height() / 2f - dp(3f)
        val x = lerp(rr.left + dp(3f) + rad, rr.right - dp(3f) - rad, t)
        // el pulgar se estira un poco al moverse (como One UI)
        val stretch = (1f - kotlin.math.abs(t - 0.5f) * 2f) * dp(4f)
        thumb.color = Color.WHITE
        thumb.setShadowLayer(dp(2f), 0f, dp(1f), 0x40000000)
        c.drawRoundRect(x - rad - stretch / 2f, rr.centerY() - rad, x + rad + stretch / 2f, rr.centerY() + rad, rad, rad, thumb)
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }
}

/** Deslizador estilo One UI: pista gruesa redondeada con relleno de acento y pulgar blanco. */
class OneSlider(ctx: Context, private val min: Int, private val max: Int, start: Int) : View(ctx) {
    var value = start.coerceIn(min, max)
        private set
    var onChange: ((Int) -> Unit)? = null
    private val ticker = Ticker { invalidate(); false }
    private val grow = ticker.add(SpringValue(0f, 500f, 36f, 0.002f))
    /** posición dibujada (0..1): sigue al valor con un resorte al tocar la pista */
    private val shown = ticker.add(SpringValue(-1f, 420f, 34f, 0.0005f))
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rr = RectF()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(36f).toInt())
    }

    private fun frac(): Float = if (max == min) 0f else (value - min).toFloat() / (max - min)

    override fun onDraw(c: Canvas) {
        val g = grow.value
        val th = dp(6f) + dp(4f) * g
        val pad = dp(14f)
        rr.set(pad, height / 2f - th / 2f, width - pad, height / 2f + th / 2f)
        track.color = OneColors.TRACK
        c.drawRoundRect(rr, th / 2f, th / 2f, track)
        if (shown.value < 0f && !shown.active) shown.snap(frac())
        val x = lerp(rr.left, rr.right, clamp01(shown.value))
        fill.color = OneColors.ACCENT
        c.drawRoundRect(rr.left, rr.top, x, rr.bottom, th / 2f, th / 2f, fill)
        thumb.color = Color.WHITE
        thumb.setShadowLayer(dp(3f), 0f, dp(1f), 0x50000000)
        c.drawCircle(x, height / 2f, dp(10f) + dp(2f) * g, thumb)
    }

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pad = dp(14f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // aún no se sabe si es desplazar la página o mover el deslizador
                downX = e.x
                downY = e.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = kotlin.math.abs(e.x - downX)
                    val dy = kotlin.math.abs(e.y - downY)
                    if (dy > slop && dy > dx) return false // la página se desplaza
                    if (dx <= slop) return true
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    grow.to(1f)
                    ticker.kick()
                }
                val f = clamp01((e.x - pad) / (width - pad * 2f))
                val v = Math.round(min + f * (max - min))
                if (v != value) {
                    value = v
                    if (max - min <= 40) Hx.fire(this, S.HE_SETTING)
                    onChange?.invoke(v)
                }
                shown.snap(frac())
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    // toque simple: salta al valor tocado
                    val f = clamp01((e.x - pad) / (width - pad * 2f))
                    val v = Math.round(min + f * (max - min))
                    if (v != value) {
                        value = v
                        onChange?.invoke(v)
                    }
                    Mo.tune(shown, S.MO_CONTROLS, 420f, 0.75f)
                    shown.to(frac())
                    ticker.kick()
                    invalidate()
                }
                dragging = false
                grow.to(0f)
                ticker.kick()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                grow.to(0f)
                ticker.kick()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }
}

fun blend(a: Int, b: Int, t: Float): Int {
    val ia = 1f - t
    return Color.argb(
        (Color.alpha(a) * ia + Color.alpha(b) * t).toInt(),
        (Color.red(a) * ia + Color.red(b) * t).toInt(),
        (Color.green(a) * ia + Color.green(b) * t).toInt(),
        (Color.blue(a) * ia + Color.blue(b) * t).toInt()
    )
}
