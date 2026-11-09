package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Vista 7 - Panal: todas las apps en un panal que se mueve libremente con el dedo (como el reloj de
 * Apple). Las del centro se ven grandes; hacia los bordes se encogen y se juntan, como mirar por una
 * lupa. Al abrir salen del centro en espiral, anillo a anillo. Doble toque en un hueco: vuelve al centro.
 */
class HoneyView(ctx: Context) : AppsView(ctx) {
    override val iconArea = S.IA_HONEY

    private class Cell(val e: AppEntry, val ring: Int)

    /** posición de cada app dentro del panal (al buscar, las que quedan se deslizan a su sitio nuevo) */
    private val posX = ticker.add(SpringMap())
    private val posY = ticker.add(SpringMap())
    private val cells = ArrayList<Cell>()
    private var d = 0f
    private var iconS = 0f
    private var maxR = 0f
    private var cx0 = 0f
    private var cy0 = 0f
    private var halfW = 1f
    private var halfH = 1f
    private var top = 0f
    private var bottom = 0f
    // dónde se dibujó cada app (para tocarla)
    private var hitX = FloatArray(0)
    private var hitY = FloatArray(0)
    private var hitS = FloatArray(0)
    private var focusIdx = -1
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pill = RectF()
    /**
     * Nombre de la app del centro: va en una cápsula de cristal fija abajo (antes iba bajo el icono y
     * tapaba a las vecinas). Al cambiar de app, el nombre viejo sube y se va y el nuevo entra desde abajo.
     */
    private var focusKey: String? = null
    private var prevName = ""
    private var curName = ""
    private val focusSp = ticker.add(SpringValue(1f, 300f, 26f, 0.002f))
    private val pillVis = ticker.add(SpringValue(0f, 260f, 26f, 0.002f))
    private val showNames: Boolean get() = Prefs.labels && S.b(S.DR_LABELS)

    // ---- desplazamiento libre en 2D con inercia y borde elástico
    private val pan = ticker.add(Pan())

    private inner class Pan : Stepper {
        var x = 0f
        var y = 0f
        var rawX = 0f
        var rawY = 0f
        var vx = 0f
        var vy = 0f
        var flinging = false
        val bx = SpringValue(0f, 170f, 26f, 0.3f)
        val by = SpringValue(0f, 170f, 26f, 0.3f)
        var back = false

        fun stop() {
            flinging = false
            back = false
            vx = 0f
            vy = 0f
            rawX = x
            rawY = y
            // si estaba estirado fuera del borde, el arrastre sigue desde ahí
            val r = hypot(x, y)
            if (r > maxR && maxR > 0f) {
                val k = (maxR + unrubber(r - maxR)) / r
                rawX = x * k
                rawY = y * k
            }
        }

        private fun unrubber(f: Float): Float {
            val dim = halfH * 2f
            val q = (f / dim).coerceAtMost(0.95f)
            return q / (1f - q) * dim / 0.55f
        }

        /** arrastre: dentro del panal sigue al dedo; fuera, se estira cada vez menos */
        fun drag(dx: Float, dy: Float) {
            flinging = false
            back = false
            rawX += dx
            rawY += dy
            val r = hypot(rawX, rawY)
            if (r > maxR && r > 0f) {
                val vis = maxR + rubber(r - maxR, halfH * 2f)
                x = rawX / r * vis
                y = rawY / r * vis
            } else {
                x = rawX
                y = rawY
            }
        }

        fun release(vx0: Float, vy0: Float) {
            if (hypot(x, y) > maxR + 0.5f) {
                springBack(vx0 * 0.4f, vy0 * 0.4f)
            } else {
                vx = vx0
                vy = vy0
                flinging = hypot(vx, vy) > 40f
            }
        }

        /** vuelve al borde (o al punto dado) con un resorte */
        fun springBack(v0x: Float, v0y: Float, tx: Float? = null, ty: Float? = null) {
            val r = hypot(x, y)
            val k = if (r > maxR && r > 0f) maxR / r else 1f
            Mo.tune(bx, S.MO_SCROLL, 170f, 0.9f)
            Mo.tune(by, S.MO_SCROLL, 170f, 0.9f)
            bx.snap(x)
            by.snap(y)
            bx.velocity = v0x
            by.velocity = v0y
            bx.to(tx ?: x * k)
            by.to(ty ?: y * k)
            back = true
            flinging = false
        }

        override fun step(dt: Float): Boolean {
            if (back) {
                bx.step(dt)
                by.step(dt)
                x = bx.value
                y = by.value
                rawX = x
                rawY = y
                if (!bx.active && !by.active) back = false
                return back
            }
            if (!flinging) return false
            val decay = exp(-2.4f * dt)
            x += vx * (1f - decay) / 2.4f
            y += vy * (1f - decay) / 2.4f
            vx *= decay
            vy *= decay
            rawX = x
            rawY = y
            if (hypot(x, y) > maxR) {
                // choca con el borde: rebota conservando parte de la velocidad
                springBack(vx * 0.5f, vy * 0.5f)
            } else if (hypot(vx, vy) < 20f) {
                flinging = false
            }
            return flinging || back
        }
    }

    // ---- gestos
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var wasMoving = false
    private var downIdx = -1
    private var longFired = false
    private var lastTap = 0L
    private var vt: VelocityTracker? = null
    private val longRun = Runnable {
        if (!moved && downIdx in cells.indices) {
            longFired = true
            lift()
            Hx.fire(this, S.HE_LONG)
            val s = hitS[downIdx]
            listener?.onMenu(cells[downIdx].e, RectF(hitX[downIdx] - s / 2f, hitY[downIdx] - s / 2f, hitX[downIdx] + s / 2f, hitY[downIdx] + s / 2f))
        }
    }

    init {
        labelPaint.color = Color.WHITE
        labelPaint.textAlign = Paint.Align.CENTER
        dotPaint.color = Color.WHITE
    }

    // ---------------------------------------------------------------- Panal
    private fun layout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        iconS = dp(60f)
        d = iconS * 1.16f
        top = insetTop + (if (keyboardUp) dp(20f) else dp(78f))
        // con nombres, abajo queda sitio para la cápsula del nombre
        bottom = h - bottomInset - (if (showNames) dp(70f) else dp(16f))
        cx0 = w / 2f
        cy0 = (top + bottom) / 2f
        halfW = w / 2f
        halfH = max(dp(120f), (bottom - top) / 2f)
        Tx.apply(labelPaint, S.TX_LABEL_DRAWER, dp(15f), 600, 0f, 0)
    }

    /** espiral de hexágonos: el centro, luego anillos de 6, 12, 18... (índice → q, r, anillo) */
    private fun buildCells(animate: Boolean) {
        cells.clear()
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(1, -1), intArrayOf(0, -1), intArrayOf(-1, 0), intArrayOf(-1, 1), intArrayOf(0, 1))
        val n = shown.size
        var placed = 0
        var maxRing = 0
        fun put(q: Int, r: Int, ring: Int) {
            if (placed >= n) return
            val e = shown[placed]
            cells.add(Cell(e, ring))
            val tx = d * (q + r / 2f)
            val ty = d * (sqrt(3f) / 2f) * r
            val sx = posX.get(e.key, 0f)
            val sy = posY.get(e.key, 0f)
            if (animate) {
                Mo.tune(sx, S.MO_EDIT_IN, 260f, 0.8f)
                Mo.tune(sy, S.MO_EDIT_IN, 260f, 0.8f)
                sx.to(tx)
                sy.to(ty)
            } else {
                sx.snap(tx)
                sy.snap(ty)
            }
            maxRing = ring
            placed++
        }
        put(0, 0, 0)
        var ring = 1
        while (placed < n) {
            var q = -ring
            var r = ring
            for (side in 0 until 6) for (step in 0 until ring) {
                put(q, r, ring)
                q += dirs[side][0]
                r += dirs[side][1]
            }
            ring++
        }
        maxR = maxRing * d
        hitX = FloatArray(cells.size)
        hitY = FloatArray(cells.size)
        hitS = FloatArray(cells.size)
        if (animate) ticker.kick()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layout()
        buildCells(false)
    }

    override fun onImeChanged() {
        layout()
        invalidate()
    }

    override fun onDataChanged() {
        layout()
        // al buscar, las que quedan se reacomodan deslizándose; el panal vuelve al centro
        buildCells(true)
        pan.stop()
        pan.springBack(0f, 0f, 0f, 0f)
        ticker.kick()
    }

    override fun onOpen() {
        layout()
        buildCells(false)
        pan.stop()
        pan.x = 0f
        pan.y = 0f
        pan.rawX = 0f
        pan.rawY = 0f
    }

    override fun onFrame(dt: Float): Boolean {
        invalidate()
        return false
    }

    // ---------------------------------------------------------------- Dibujo
    private fun easeOut(t: Float): Float {
        val k = 1f - clamp01(t)
        return 1f - k * k * k
    }

    private fun popCurve(q: Float): Float {
        val t = clamp01(q) - 1f
        return 1f + 2.7f * t * t * t + 1.7f * t * t
    }

    override fun onDraw(c: Canvas) {
        val p = progress
        val a = clamp01(p * 1.6f)
        if (cells.isEmpty()) return
        var best = Float.MAX_VALUE
        focusIdx = -1
        for ((i, cell) in cells.withIndex()) {
            val e = cell.e
            // entrada: anillo a anillo desde el centro, inflándose con un pequeño rebote
            val q = clamp01(p * 1.7f - cell.ring * 0.075f)
            val out = 0.35f + 0.65f * easeOut(q)
            val rx = (posX.get(e.key, 0f).value + pan.x) * out
            val ry = (posY.get(e.key, 0f).value + pan.y) * out
            // lupa: lejos del centro se encogen y se juntan un poco
            val dn = hypot(rx / halfW, ry / halfH)
            val squeeze = 1f - 0.16f * smooth(0.5f, 1.25f, dn)
            val sx = cx0 + rx * squeeze
            val sy = cy0 + ry * squeeze
            val sc = lerp(1f, 0.3f, smooth(0.38f, 1.08f, dn)) * popCurve(q)
            val al = a * clamp01(q * 1.8f) * (1f - smooth(1.02f, 1.32f, dn))
            val size = iconS * sc
            hitX[i] = sx
            hitY[i] = sy
            hitS[i] = if (al > 0.05f) size else 0f
            if (al <= 0.01f || sy < top - size || sy > bottom + size) continue
            drawIcon(c, e, sx, sy, size, al)
            if (dn < best) {
                best = dn
                focusIdx = i
            }
        }
        // la app del centro: un anillo de luz suave a su alrededor
        val f = focusIdx
        val near = 1f - smooth(0.05f, 0.24f, best)
        if (f in cells.indices && near > 0.01f) {
            ringPaint.strokeWidth = dp(2f)
            ringPaint.color = Color.WHITE
            ringPaint.alpha = (110 * a * near).toInt().coerceIn(0, 255)
            c.drawCircle(hitX[f], hitY[f], hitS[f] * 0.6f, ringPaint)
        }
        if (showNames) drawNamePill(c, a, if (f in cells.indices) cells[f].e else null, near)
    }

    private fun drawNamePill(c: Canvas, a: Float, e: AppEntry?, near: Float) {
        // cambió la app del centro: el nombre viejo se va hacia arriba y el nuevo entra
        if (e != null && e.key != focusKey) {
            // los nombres se recortan una sola vez, al cambiar (no en cada fotograma)
            prevName = curName
            curName = android.text.TextUtils.ellipsize(Tx.str(S.TX_LABEL_DRAWER, e.label), labelPaint, width - dp(104f), android.text.TextUtils.TruncateAt.END).toString()
            focusKey = e.key
            Mo.tune(focusSp, S.MO_MENU, 300f, 0.8f)
            focusSp.snap(0f)
            focusSp.to(1f)
            ticker.kick()
        }
        // la cápsula se ve mientras hay una app cerca del centro
        val want = if (e != null && near > 0.3f) 1f else 0f
        if (pillVis.target != want) {
            Mo.tune(pillVis, S.MO_MENU, 260f, 0.85f)
            pillVis.to(want)
            ticker.kick()
        }
        val v = clamp01(pillVis.value)
        if (v <= 0.01f || curName.isEmpty()) return
        val f = clamp01(focusSp.value)
        val hasPrev = prevName.isNotEmpty()
        // el ancho de la cápsula pasa suave del nombre viejo al nuevo
        val tw = lerp(labelPaint.measureText(if (hasPrev) prevName else curName), labelPaint.measureText(curName), f) + dp(40f)
        val ph = dp(40f)
        val cy = height - bottomInset - dp(36f) + (1f - v) * dp(14f)
        pill.set(width / 2f - tw / 2f, cy - ph / 2f, width / 2f + tw / 2f, cy + ph / 2f)
        glass.draw(c, pill, ph / 2f, a * v, true, S.SF_SEARCH)
        val base = cy + dp(5.5f) + Tx.dy(S.TX_LABEL_DRAWER)
        c.save()
        c.clipRect(pill)
        if (hasPrev && f < 1f) {
            labelPaint.alpha = Tx.alpha(S.TX_LABEL_DRAWER, a * v * (1f - f))
            c.drawText(prevName, width / 2f, base - f * dp(16f), labelPaint)
        }
        labelPaint.alpha = Tx.alpha(S.TX_LABEL_DRAWER, a * v * f)
        c.drawText(curName, width / 2f, base + (1f - f) * dp(16f), labelPaint)
        c.restore()
    }

    private fun hit(x: Float, y: Float): Int {
        var bi = -1
        var bd = Float.MAX_VALUE
        for (i in cells.indices) {
            val s = hitS[i]
            if (s <= dp(14f)) continue
            val dd = hypot(x - hitX[i], y - hitY[i])
            if (dd < s * 0.6f && dd < bd) {
                bd = dd
                bi = i
            }
        }
        return bi
    }

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
                wasMoving = pan.flinging || pan.back
                pan.stop()
                downX = x
                downY = y
                lastX = x
                lastY = y
                moved = false
                longFired = false
                downIdx = hit(x, y)
                uiHandler.removeCallbacks(longRun)
                unpress()
                if (downIdx >= 0) {
                    uiHandler.postDelayed(longRun, longMs)
                    press(cells[downIdx].e)
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
                }
                if (moved) {
                    pan.drag(x - lastX, y - lastY)
                    invalidate()
                }
                lastX = x
                lastY = y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longRun)
                unpress()
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                vt?.addMovement(ev)
                if (moved) {
                    vt?.computeCurrentVelocity(1000)
                    pan.release(vt?.xVelocity ?: 0f, vt?.yVelocity ?: 0f)
                    ticker.kick()
                } else if (wasMoving) {
                    // el toque solo frenó la inercia: vuelve dentro del borde si hacía falta
                    pan.release(0f, 0f)
                    ticker.kick()
                } else if (!longFired && up) {
                    if (downIdx in cells.indices) {
                        val s = hitS[downIdx]
                        listener?.onLaunch(cells[downIdx].e, RectF(hitX[downIdx] - s / 2f, hitY[downIdx] - s / 2f, hitX[downIdx] + s / 2f, hitY[downIdx] + s / 2f))
                    } else if (y < top || y > bottom) {
                        listener?.onClose()
                    } else {
                        // doble toque en un hueco: el panal vuelve al centro con un resorte
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastTap < S.f(S.G_DTAP_MS).toLong()) {
                            pan.springBack(0f, 0f, 0f, 0f)
                            Hx.fire(this, S.HE_PAGE)
                            ticker.kick()
                            lastTap = 0L
                        } else {
                            lastTap = now
                        }
                    }
                }
                vt?.recycle()
                vt = null
                invalidate()
                return true
            }
        }
        return true
    }
}
