package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Vista previa animada de un estilo usando las MISMAS funciones de Motion que el launcher.
 * Se reproduce al elegir un estilo o al tocarla.
 */
class PreviewView(ctx: Context, private val kind: Int) : View(ctx) {
    companion object {
        const val OPEN = 0
        const val RETURN = 1
        const val DOCK = 2
        const val BOUNCE = 3
        const val PAGE = 4
    }

    var style = 0
    private val ticker = Ticker { onFrame(it) }
    private val sp = ticker.add(SpringValue(0f, 200f, 25f, 0.002f))
    private val bsp = ticker.add(SpringValue(0f, 120f, 5f, 0.002f))
    private var t = 0f
    private var playing = false
    private val glass = Glass()
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scr = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val r2 = RectF()
    private val of = Motion.OF()
    private val it0 = Motion.IT()
    private val pt = Motion.PT()
    private val colors = intArrayOf(
        0xFF5AC8FA.toInt(), 0xFFFF9F0A.toInt(), 0xFF34C759.toInt(),
        0xFFFF375F.toInt(), 0xFFBF5AF2.toInt(), 0xFFFFD60A.toInt()
    )

    init {
        setOnClickListener { play() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        scr.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF243B7A.toInt(), 0xFF4B2A7A.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(playRun, 300)
    }

    private val playRun = Runnable { play() }

    override fun onDetachedFromWindow() {
        removeCallbacks(playRun)
        ticker.stop()
        super.onDetachedFromWindow()
    }

    fun play() {
        t = 0f
        playing = true
        when (kind) {
            OPEN -> { Motion.openTune(style, sp); sp.snap(0f); sp.to(1f) }
            RETURN -> { Motion.enterTune(style, sp); sp.snap(0f); sp.to(1f) }
            PAGE -> { sp.tune(220f, 0.9f); sp.snap(0f); sp.to(1f) }
            BOUNCE -> {
                bsp.snap(0f)
                // mismos resortes y fuerza que el rebote real del dock (HomeView.bounce)
                val f = S.p(S.BOUNCES_BASE + style.coerceIn(0, 4))
                when (style) {
                    0 -> { Mo.tune(bsp, S.MO_B_JUMP, 120f, 0.23f); bsp.velocity = 6.5f * f }
                    1 -> { Mo.tune(bsp, S.MO_B_PULSE, 200f, 0.28f); bsp.velocity = 5f * f }
                    2 -> { Mo.tune(bsp, S.MO_B_SPIN, 90f, 0.37f); bsp.velocity = 3.4f * f }
                    3 -> { Mo.tune(bsp, S.MO_B_WAVE, 120f, 0.23f); bsp.velocity = 6.5f * f }
                    else -> { Mo.tune(bsp, S.MO_B_SHAKE, 160f, 0.24f); bsp.velocity = 5.5f * f }
                }
                bsp.to(0f)
            }
        }
        ticker.kick()
    }

    private fun onFrame(dt: Float): Boolean {
        t += dt * Prefs.speed
        invalidate()
        if (kind == DOCK) {
            if (t > 3.2f) { playing = false; return false }
            return true
        }
        if (kind == BOUNCE && style == 3 && t < 1.2f) return true
        return false
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, dp(20f), dp(20f), scr)
        c.save()
        c.clipRect(0f, 0f, w, h)
        when (kind) {
            OPEN -> drawOpen(c, w, h)
            RETURN -> drawReturn(c, w, h)
            DOCK -> drawDock(c, w, h)
            BOUNCE -> drawBounce(c, w, h)
            PAGE -> drawPage(c, w, h)
        }
        c.restore()
    }

    private fun sq(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, a: Float) {
        icon.color = color
        icon.alpha = (255 * clamp01(a)).toInt()
        r2.set(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f)
        c.drawRoundRect(r2, s * 0.24f, s * 0.24f, icon)
    }

    private fun drawOpen(c: Canvas, w: Float, h: Float) {
        // "teléfono" en miniatura
        val ph = h - dp(16f)
        val pw = ph * 0.5f
        val px = w / 2f - pw / 2f
        val screen = RectF(px, dp(8f), px + pw, dp(8f) + ph)
        glass.draw(c, screen, dp(14f), 0.8f, false)
        val s = pw / 5f
        for (i in 0 until 6) {
            sq(c, px + pw * (0.2f + (i % 3) * 0.3f), screen.top + ph * (0.3f + (i / 3) * 0.22f), s, colors[i], 1f)
        }
        val from = RectF(px + pw * 0.5f - s / 2f, screen.top + ph * 0.52f - s / 2f, px + pw * 0.5f + s / 2f, screen.top + ph * 0.52f + s / 2f)
        Motion.openFrame(style, sp.value, from, screen, of)
        r.set(of.l, of.t, of.r, of.b)
        c.save()
        if (of.rotY != 0f) Rot3D.apply(c, of.rotY, r.centerX(), r.centerY())
        icon.color = colors[4]
        icon.alpha = (255 * clamp01(max(of.bg, of.iconA))).toInt()
        val rad = max(dp(2f), min(of.corner, r.width() / 2f) * (pw / w))
        c.drawRoundRect(r, rad + dp(4f) * (1f - sp.value), rad + dp(4f) * (1f - sp.value), icon)
        if (style == 2) glass.draw(c, r, dp(8f), 0.8f, false)
        c.restore()
    }

    private fun drawReturn(c: Canvas, w: Float, h: Float) {
        val s = dp(34f)
        val n = 8
        for (i in 0 until n) {
            Motion.enterItem(style, sp.value, i, n, it0)
            val cx = w * (0.2f + (i % 4) * 0.2f) + it0.dx * 0.5f
            val cy = h * (0.3f + (i / 4) * 0.4f) + it0.dy * 0.4f
            sq(c, cx, cy, s * it0.sc, colors[i % colors.size], it0.alpha)
        }
    }

    private fun drawDock(c: Canvas, w: Float, h: Float) {
        val n = 7
        val unit = dp(40f)
        val base = dp(34f)
        // un "dedo" que barre el dock de lado a lado
        val sweep = (t / 3f).coerceIn(0f, 1f)
        val finger = if (playing) w * 0.1f + w * 0.8f * (0.5f - 0.5f * kotlin.math.cos(sweep * Math.PI.toFloat() * 2f)) else -9999f
        val total = n * unit
        val x0 = (w - total) / 2f
        val amt = Prefs.magnify / 100f * 0.9f
        val scales = FloatArray(n)
        var sum = 0f
        for (i in 0 until n) {
            val rest = x0 + unit * (i + 0.5f)
            scales[i] = if (finger > -999f) Motion.dockScale(style, (finger - rest) / unit, amt) else 1f
            sum += unit * scales[i]
        }
        var x = (w - sum) / 2f
        val bottom = h - dp(26f)
        r.set(x0 - dp(10f), bottom - base - dp(10f), x0 + total + dp(10f), bottom + dp(10f))
        glass.draw(c, r, dp(18f), 1f)
        val lf = Motion.liftFactor(style)
        for (i in 0 until n) {
            val sw = unit * scales[i]
            val cx = x + sw / 2f
            val sz = base * scales[i]
            val lift = (scales[i] - 1f) * base * lf
            sq(c, cx, bottom - sz / 2f - lift, sz, colors[i % colors.size], 1f)
            x += sw
        }
    }

    private fun drawBounce(c: Canvas, w: Float, h: Float) {
        val base = dp(40f)
        val bottom = h - dp(30f)
        r.set(w / 2f - dp(120f), bottom - base - dp(10f), w / 2f + dp(120f), bottom + dp(10f))
        glass.draw(c, r, dp(18f), 1f)
        for (i in 0 until 5) {
            val cx = w / 2f + (i - 2) * dp(46f)
            var dy = 0f
            var sc = 1f
            var rot = 0f
            var dx = 0f
            val v = bsp.value
            if (i == 2 || style == 3) {
                val k = if (style == 3) {
                    val d = abs(i - 2)
                    val lt = t - d * 0.07f
                    if (lt < 0f) 0f else max(0f, kotlin.math.sin(lt * 9f) * kotlin.math.exp(-lt * 3f)) * (1f - d * 0.2f)
                } else v
                when (style) {
                    0, 3 -> dy = max(0f, if (style == 3) k * 0.9f else k) * base
                    1 -> sc = 1f + v
                    2 -> rot = v * 360f
                    else -> dx = v * base * 0.5f
                }
            }
            c.save()
            c.rotate(rot, cx + dx, bottom - base / 2f - dy)
            sq(c, cx + dx, bottom - base / 2f - dy, base * sc, colors[i], 1f)
            c.restore()
        }
    }

    private fun drawPage(c: Canvas, w: Float, h: Float) {
        val pv = sp.value
        val pw = w * 0.6f
        val left = (w - pw) / 2f
        for (pg in 0..1) {
            val o = pg - pv
            Motion.pageT(style, o, pw, 0.5f, pt)
            c.save()
            c.clipRect(left - dp(4f), 0f, left + pw + dp(4f), h)
            if (style != 2) {
                c.translate(pt.tx, 0f)
                if (pt.sc != 1f) c.scale(pt.sc, pt.sc, w / 2f, h / 2f)
                if (pt.rotY != 0f) Rot3D.apply(c, pt.rotY, left + pt.pivot * pw, h / 2f)
            }
            for (i in 0 until 8) {
                val colI = i % 4
                var cx = left + pw * (0.14f + colI * 0.24f)
                var a = pt.alpha
                if (style == 2) {
                    Motion.pageT(2, o, pw, colI / 3f, pt)
                    cx += pt.tx
                    a = pt.alpha
                }
                val cy = h * (0.32f + (i / 4) * 0.36f)
                sq(c, cx, cy, dp(30f), colors[(i + pg * 3) % colors.size], a)
            }
            c.restore()
        }
    }
}
