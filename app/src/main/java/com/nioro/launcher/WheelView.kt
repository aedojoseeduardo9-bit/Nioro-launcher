package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Vista 8 - Rueda: las apps van en una rueda grande abajo (como la de un iPod). Se gira con el dedo,
 * con inercia, y se asienta en cada app con un "clic" (vibración suave). La app elegida aparece en
 * grande arriba, con su nombre; tocar cualquier app la abre.
 */
class WheelView(ctx: Context) : AppsView(ctx) {
    override val iconArea = S.IA_WHEEL

    private var iconS = 0f
    private var radius = 0f
    private var wcx = 0f
    private var wcy = 0f
    private var step = 0.2f
    private var topY = 0f
    private var bottomY = 0f
    private var bigCy = 0f
    /** tamaño del icono grande: se ajusta al hueco sobre la rueda (con el teclado abierto hay menos) */
    private var bigS = 0f
    /** borde de arriba de la rueda (el bloque grande nunca baja de aquí) */
    private var wheelTopY = 0f
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val letterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val rect = RectF()
    private var hitX = FloatArray(0)
    private var hitY = FloatArray(0)
    private var hitS = FloatArray(0)

    /** app elegida (la de arriba de la rueda) y la anterior, para el cambio con fundido */
    private var sel = 0
    private var prevSel = -1
    private var selDir = 1
    private val selSp = ticker.add(SpringValue(1f, 300f, 22f, 0.002f))
    private val bigPress = ticker.add(SpringValue(1f, 600f, 34f, 0.002f))

    // ---- giro con inercia y "clics" en cada app
    private val spin = ticker.add(Spin())

    private inner class Spin : Stepper {
        var pos = 0f
        var raw = 0f
        var vel = 0f
        var flinging = false
        var snapping = false
        val sp = SpringValue(0f, 240f, 26f, 0.0005f)

        private fun last(): Float = max(0, itemCount() - 1).toFloat()

        fun stop() {
            flinging = false
            snapping = false
            vel = 0f
            raw = pos
        }

        /** girar con el dedo; pasados los extremos se estira cada vez menos */
        fun drag(d: Float) {
            flinging = false
            snapping = false
            raw += d
            val l = last()
            pos = when {
                raw < 0f -> rubber(raw, 3f)
                raw > l -> l + rubber(raw - l, 3f)
                else -> raw
            }
        }

        fun release(v: Float) {
            val l = last()
            if (pos < 0f || pos > l) {
                snapTo(pos.coerceIn(0f, l), v * 0.4f)
            } else if (abs(v) > 0.8f) {
                vel = v
                flinging = true
            } else {
                snapTo(pos.roundToInt().toFloat(), v)
            }
        }

        fun snapTo(t: Float, v0: Float) {
            Mo.tune(sp, S.MO_SCROLL, 260f, 0.78f)
            sp.snap(pos)
            sp.velocity = v0
            sp.to(t)
            snapping = true
            flinging = false
        }

        override fun step(dt: Float): Boolean {
            if (snapping) {
                sp.step(dt)
                pos = sp.value
                raw = pos
                if (!sp.active) snapping = false
                return snapping
            }
            if (!flinging) return false
            val decay = exp(-3.4f * dt)
            pos += vel * (1f - decay) / 3.4f
            vel *= decay
            raw = pos
            val l = last()
            if (pos < -0.3f || pos > l + 0.3f) {
                snapTo(pos.coerceIn(0f, l), vel * 0.3f)
            } else if (abs(vel) < 1.6f) {
                // ya casi parada: se asienta en la app más cercana hacia donde iba
                snapTo((pos + vel * 0.12f).roundToInt().toFloat().coerceIn(0f, l), vel)
            }
            return flinging || snapping
        }
    }

    // ---- gestos
    private var downX = 0f
    private var downY = 0f
    private var lastAng = 0f
    private var lastT = 0L
    private var angVel = 0f
    private var moved = false
    private var wasMoving = false
    private var downIdx = -1
    private var onBig = false
    private var longFired = false
    private var vt: VelocityTracker? = null
    private val longRun = Runnable {
        if (!moved && downIdx in shown.indices) {
            longFired = true
            lift()
            Hx.fire(this, S.HE_LONG)
            val s = hitS[downIdx]
            listener?.onMenu(shown[downIdx], RectF(hitX[downIdx] - s / 2f, hitY[downIdx] - s / 2f, hitX[downIdx] + s / 2f, hitY[downIdx] + s / 2f))
        }
    }

    init {
        namePaint.color = Color.WHITE
        namePaint.textAlign = Paint.Align.CENTER
        namePaint.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        subPaint.color = OneColors.SUB
        subPaint.textAlign = Paint.Align.CENTER
        letterPaint.color = Color.WHITE
        letterPaint.textAlign = Paint.Align.CENTER
        letterPaint.typeface = Typeface.create(Typeface.DEFAULT, 800, false)
        linePaint.color = Color.WHITE
    }

    private fun itemCount(): Int = shown.size

    // ---------------------------------------------------------------- Medidas
    private fun layout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        iconS = dp(56f)
        radius = w * 0.8f
        bottomY = h - bottomInset - dp(10f)
        topY = insetTop + (if (keyboardUp) dp(14f) else dp(76f))
        wcx = w / 2f
        // el centro de la rueda queda por debajo de la pantalla: se ve solo su arco de arriba
        wcy = bottomY + radius * 0.42f
        step = iconS * 1.42f / radius
        // bloque de la app elegida (icono + nombre + "n de N"): cabe siempre entre arriba y la rueda
        wheelTopY = wcy - radius - iconS * 0.95f
        val space = wheelTopY - topY
        bigS = (space - dp(92f)).coerceIn(0f, dp(104f))
        if (bigS < dp(40f)) bigS = 0f // sin sitio (teclado abierto en móviles bajos): solo la rueda
        val block = bigS + dp(68f)
        bigCy = topY + max(0f, (space - block) / 2f) + bigS / 2f
        namePaint.textSize = if (bigS < dp(72f)) dp(18f) else dp(22f)
        subPaint.textSize = dp(13.5f)
        letterPaint.textSize = min(dp(230f), max(dp(80f), space * 0.9f))
        ringPaint.strokeWidth = iconS * 1.6f
        ringPaint.shader = LinearGradient(0f, wcy - radius - iconS, 0f, wcy - radius * 0.3f, 0x26FFFFFF, 0x05FFFFFF, Shader.TileMode.CLAMP)
        hitX = FloatArray(shown.size)
        hitY = FloatArray(shown.size)
        hitS = FloatArray(shown.size)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layout()
    }

    override fun onImeChanged() {
        layout()
        invalidate()
    }

    override fun onDataChanged() {
        layout()
        spin.stop()
        spin.pos = 0f
        spin.raw = 0f
        sel = 0
        prevSel = -1
        selSp.snap(1f)
    }

    override fun onOpen() {
        layout()
        // al abrir, la rueda entra girando desde un lado y se asienta en la primera app
        spin.stop()
        spin.pos = -5f
        spin.raw = -5f
        spin.snapTo(0f, 0f)
        Mo.tune(spin.sp, S.MO_DRAWER_OPEN, 120f, 0.82f)
        sel = 0
        prevSel = -1
        selSp.snap(1f)
        ticker.kick()
    }

    /** cada vez que la rueda pasa por una app: "clic" y la app de arriba cambia con un fundido */
    private fun checkSelection(haptic: Boolean) {
        if (shown.isEmpty()) return
        val now = spin.pos.roundToInt().coerceIn(0, shown.size - 1)
        if (now != sel) {
            selDir = if (now > sel) 1 else -1
            prevSel = sel
            sel = now
            Mo.tune(selSp, S.MO_MENU, 300f, 0.62f)
            selSp.snap(0f)
            selSp.to(1f)
            if (haptic && progress > 0.9f) Hx.fire(this, S.HE_DOCK)
            ticker.kick()
        }
    }

    override fun onFrame(dt: Float): Boolean {
        checkSelection(spin.flinging || spin.snapping || moved)
        invalidate()
        return false
    }

    // ---------------------------------------------------------------- Dibujo
    private fun popCurve(q: Float): Float {
        val t = clamp01(q) - 1f
        return 1f + 2.7f * t * t * t + 1.7f * t * t
    }

    override fun onDraw(c: Canvas) {
        val p = progress
        val a = clamp01(p * 1.6f)
        if (shown.isEmpty()) return
        if (hitX.size != shown.size) layout()
        c.save()
        c.translate(0f, (1f - min(p, 1f)) * dp(140f))
        // pista de la rueda: un arco de cristal suave con dos filos
        oval.set(wcx - radius, wcy - radius, wcx + radius, wcy + radius)
        ringPaint.alpha = (255 * a).toInt()
        c.drawArc(oval, 190f, 160f, false, ringPaint)
        linePaint.strokeWidth = dp(1f)
        linePaint.alpha = (46 * a).toInt()
        val half = iconS * 0.8f
        oval.set(wcx - radius - half, wcy - radius - half, wcx + radius + half, wcy + radius + half)
        c.drawArc(oval, 190f, 160f, false, linePaint)
        oval.set(wcx - radius + half, wcy - radius + half, wcx + radius - half, wcy + radius - half)
        c.drawArc(oval, 190f, 160f, false, linePaint)
        // hueco de la app elegida (arriba del todo), de cristal
        val hs = iconS * 1.5f
        rect.set(wcx - hs / 2f, wcy - radius - hs / 2f, wcx + hs / 2f, wcy - radius + hs / 2f)
        glass.draw(c, rect, hs * 0.32f, a * 0.9f, true, S.SF_LISTPANEL)
        // apps en la rueda
        val pos = spin.pos
        val maxAng = 1.45f
        val first = max(0, floor(pos - maxAng / step).toInt() - 1)
        val last = min(shown.size - 1, (pos + maxAng / step).toInt() + 1)
        for (i in hitS.indices) hitS[i] = 0f
        for (i in first..last) {
            val th = (i - pos) * step
            val ath = abs(th)
            if (ath > maxAng) continue
            val x = wcx + radius * sin(th)
            val y = wcy - radius * cos(th)
            val near = 1f - min(1f, abs(i - pos))
            val sc = (1f - 0.42f * clamp01(ath / 1.3f).pow(1.2f)) * (1f + 0.16f * near)
            val al = a * (1f - smooth(1.05f, maxAng, ath))
            val size = iconS * sc
            if (al <= 0.01f) continue
            hitX[i] = x
            hitY[i] = y
            hitS[i] = size
            drawIcon(c, shown[i], x, y, size, al)
        }
        c.restore()
        drawBig(c, a, p)
    }

    /** la app elegida, en grande arriba: cambia con un fundido y un pequeño rebote */
    private fun drawBig(c: Canvas, a: Float, p: Float) {
        if (bigS <= 0f) return
        val f = clamp01(selSp.value)
        val pop = popCurve(clamp01(p * 1.4f))
        val bs = bigS * pop * bigPress.value
        val slide = dp(26f)
        // letra gigante y tenue detrás (como el índice de una lista de música), sin salirse del hueco
        val cur = shown.getOrNull(sel) ?: return
        val ch = cur.label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
        letterPaint.alpha = (16 * a * f).toInt()
        c.save()
        c.clipRect(0f, topY - dp(6f), width.toFloat(), wheelTopY)
        c.drawText(ch, wcx, bigCy + letterPaint.textSize * 0.36f, letterPaint)
        c.restore()
        val prev = shown.getOrNull(prevSel)
        if (prev != null && f < 1f) {
            val k = 1f - f
            val ox = -selDir * slide * f
            drawIcon(c, prev, wcx + ox, bigCy, bs * (0.9f + 0.1f * k), a * k)
            namePaint.alpha = (255 * a * k).toInt()
            c.drawText(nameOf(prev), wcx + ox, bigCy + bs / 2f + dp(36f), namePaint)
        }
        val ox = selDir * slide * (1f - selSp.value)
        val sc = 0.88f + 0.12f * selSp.value
        drawIcon(c, cur, wcx + ox, bigCy, bs * sc, a * f)
        namePaint.alpha = (255 * a * f).toInt()
        c.drawText(nameOf(cur), wcx + ox, bigCy + bs / 2f + dp(36f), namePaint)
        subPaint.alpha = (200 * a * f).toInt()
        if (bigCy + bs / 2f + dp(64f) < wheelTopY) c.drawText((sel + 1).toString() + " de " + shown.size, wcx, bigCy + bs / 2f + dp(58f), subPaint)
    }

    private val names = HashMap<String, String>()
    private fun nameOf(e: AppEntry): String = names.getOrPut(e.key) {
        TextUtils.ellipsize(e.label, namePaint, width * 0.8f, TextUtils.TruncateAt.END).toString()
    }

    private fun hitItem(x: Float, y: Float): Int {
        val oy = (1f - min(progress, 1f)) * dp(140f)
        var bi = -1
        var bd = Float.MAX_VALUE
        for (i in hitS.indices) {
            val s = hitS[i]
            if (s <= 0f) continue
            val dd = hypot(x - hitX[i], y - (hitY[i] + oy))
            if (dd < s * 0.62f && dd < bd) {
                bd = dd
                bi = i
            }
        }
        return bi
    }

    private fun angleAt(x: Float, y: Float): Float = atan2(x - wcx, wcy - y)

    // ---------------------------------------------------------------- Tacto
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                listener?.onTouchStart()
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
                wasMoving = spin.flinging || spin.snapping
                spin.stop()
                downX = x
                downY = y
                lastAng = angleAt(x, y)
                lastT = ev.eventTime
                angVel = 0f
                moved = false
                longFired = false
                downIdx = hitItem(x, y)
                onBig = downIdx < 0 && bigS > 0f && hypot(x - wcx, y - bigCy) < bigS * 0.7f
                uiHandler.removeCallbacks(longRun)
                unpress()
                if (downIdx >= 0) {
                    uiHandler.postDelayed(longRun, longMs)
                    press(shown[downIdx])
                } else if (onBig) {
                    Mo.tune(bigPress, S.MO_PRESS, 600f, 1f)
                    bigPress.to(0.92f)
                    ticker.kick()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (longFired) return true
                if (!moved && (abs(x - downX) > slop || abs(y - downY) > slop)) {
                    moved = true
                    uiHandler.removeCallbacks(longRun)
                    unpress()
                    if (onBig) {
                        bigPress.to(1f)
                        ticker.kick()
                    }
                }
                if (moved) {
                    // la rueda gira con el ángulo del dedo alrededor de su centro
                    val ang = angleAt(x, y)
                    var da = ang - lastAng
                    if (da > Math.PI) da -= (2 * Math.PI).toFloat()
                    if (da < -Math.PI) da += (2 * Math.PI).toFloat()
                    val dt = max(1L, ev.eventTime - lastT) / 1000f
                    angVel = lerp(angVel, da / dt, 0.5f)
                    spin.drag(-da / step)
                    lastAng = ang
                    lastT = ev.eventTime
                    checkSelection(true)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longRun)
                unpress()
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                if (onBig) {
                    Mo.tune(bigPress, S.MO_RELEASE, 420f, 0.45f)
                    bigPress.to(1f)
                }
                if (moved) {
                    // si el dedo se paró antes de soltar, no hay inercia
                    if (ev.eventTime - lastT > 80L) angVel = 0f
                    spin.release(-angVel / step)
                    ticker.kick()
                } else if (wasMoving) {
                    spin.release(0f)
                    ticker.kick()
                } else if (!longFired && up) {
                    if (downIdx in shown.indices) {
                        val s = hitS[downIdx]
                        val oy = (1f - min(progress, 1f)) * dp(140f)
                        listener?.onLaunch(shown[downIdx], RectF(hitX[downIdx] - s / 2f, hitY[downIdx] + oy - s / 2f, hitX[downIdx] + s / 2f, hitY[downIdx] + oy + s / 2f))
                    } else if (onBig && sel in shown.indices) {
                        val bs = bigS
                        listener?.onLaunch(shown[sel], RectF(wcx - bs / 2f, bigCy - bs / 2f, wcx + bs / 2f, bigCy + bs / 2f))
                    } else if (y < topY) {
                        listener?.onClose()
                    } else if (hypot(x - wcx, y - wcy) < radius - iconS * 1.2f || y < bigCy - dp(90f)) {
                        // fuera de la rueda y de la app elegida: se cierra
                        listener?.onClose()
                    }
                }
                moved = false
                onBig = false
                ticker.kick()
                vt?.recycle()
                vt = null
                invalidate()
                return true
            }
        }
        return true
    }
}
