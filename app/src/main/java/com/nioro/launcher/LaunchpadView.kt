package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vista 1 - Launchpad: rejilla paginada a pantalla completa, como el Launchpad de macOS.
 * Al pasar de página las vecinas se alejan un poco (profundidad), los nombres aparecen
 * después de los iconos y deslizar hacia abajo cierra siguiendo al dedo.
 */
class LaunchpadView(ctx: Context) : AppsView(ctx) {
    override val iconArea = S.IA_LAUNCHPAD

    private val pageSp = ticker.add(SpringValue(0f, 240f, 27f))
    /** desplazamiento hacia abajo al cerrar con el dedo (px) */
    private val pull = ticker.add(SpringValue(0f, 300f, 30f, 0.3f))
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotsRect = RectF()
    private val dotRect = RectF()
    private var cols = 4
    private var rows = 5
    private var cap = 20
    private var cellW = 0f
    private var cellH = 0f
    private var top = 0f
    private var dotsY = 0f
    private var iconSize = 0f

    private var downX = 0f
    private var downY = 0f
    private var pageStart = 0f
    private var mode = 0 // 0 nada, 1 paginar, 2 cerrar con el dedo, 3 ya cerrando, 4 vertical sin efecto
    private var moved = false
    private var downIdx = -1
    private var vt: VelocityTracker? = null
    private var longFired = false
    private val longRun = Runnable {
        if (mode == 0 && !moved && downIdx in shown.indices) {
            longFired = true
            lift()
            Hx.fire(this, S.HE_LONG)
            val r = RectF()
            itemRect(downIdx, r)
            listener?.onMenu(shown[downIdx], r)
        }
    }

    private fun layout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        cols = Prefs.drawerCols.coerceIn(3, 6)
        iconSize = dp(Prefs.iconDp.toFloat() + 2f)
        top = insetTop + (if (keyboardUp) dp(24f) else dp(84f))
        dotsY = h - bottomInset - (if (keyboardUp) dp(22f) else dp(40f))
        val avail = dotsY - dp(20f) - top
        val minCell = iconSize + dp(36f) + dp(10f)
        rows = if (S.i(S.LP_ROWS) > 0) S.i(S.LP_ROWS) + 2 else (avail / minCell).toInt().coerceIn(3, 8)
        cap = cols * rows
        cellW = w / cols
        cellH = avail / rows
        iconSize = min(iconSize, cellW - dp(14f)).coerceAtLeast(dp(30f))
    }

    override fun onImeChanged() {
        layout()
        val last = (pageCount() - 1).toFloat()
        if (pageSp.value > last) pageSp.snap(last)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layout()
    }

    override fun onDataChanged() {
        layout()
        pageSp.snap(0f)
    }

    override fun onOpen() {
        layout()
        pageSp.snap(0f)
        pull.snap(0f)
    }

    override fun onFrame(dt: Float): Boolean {
        // el contenedor (fondo, barra, inicio detrás) sigue a lo que se tira hacia abajo
        listener?.onPull(clamp01(max(0f, pull.value) / max(1f, height * 0.45f)))
        invalidate()
        return false
    }

    private fun pageCount(): Int = max(1, ceil(shown.size / max(1, cap).toFloat()).toInt())

    private fun itemRect(idx: Int, out: RectF) {
        val local = idx % cap
        val col = local % cols
        val row = local / cols
        val cx = col * cellW + cellW / 2f
        val cy = top + row * cellH + cellH * 0.42f + max(0f, pull.value) * 0.6f
        out.set(cx - iconSize / 2f, cy - iconSize / 2f, cx + iconSize / 2f, cy + iconSize / 2f)
    }

    override fun onDraw(c: Canvas) {
        if (shown.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val pv = pageSp.value
        val n = pageCount()
        val p = progress
        // al tirar hacia abajo todo baja, se encoge un poco y se desvanece
        val pl = max(0f, pull.value)
        val pullK = clamp01(pl / (h * 0.45f))
        c.save()
        if (pl > 0.5f) {
            val s = 1f - 0.08f * pullK
            c.translate(0f, pl * 0.6f)
            c.scale(s, s, w / 2f, h / 2f)
        }
        val pullA = 1f - pullK * 0.75f
        val first = max(0, floor(pv).toInt())
        val last = min(n - 1, ceil(pv).toInt())
        for (pg in first..last) {
            val o = pg - pv
            val ao = min(1f, abs(o))
            val ox = o * w
            // profundidad: la página que se va se aleja y se desvanece un poco
            val depth = 1f - 0.1f * ao
            val pageA = 1f - 0.45f * ao
            val start = pg * cap
            val end = min(shown.size, start + cap)
            for (idx in start until end) {
                val local = idx - start
                val col = local % cols
                val row = local / cols
                val cx0 = col * cellW + cellW / 2f
                val cy0 = top + row * cellH + cellH * 0.42f
                val cx = w / 2f + (cx0 - w / 2f) * depth + ox
                // entrada tipo Launchpad: los iconos del centro llegan primero y suben un poco
                val d = hypot(cx0 - w / 2f, cy0 - h / 2f) / w
                val dc = (d * 0.3f * S.p(S.LP_CENTER)).coerceIn(0f, 0.9f)
                val q = (p - dc) / (1f - dc)
                val cy = h / 2f + (cy0 - h / 2f) * depth + (1f - clamp01(q)) * dp(22f)
                val sc = lerp(1f + 0.38f * S.p(S.LP_ZOOM), 1f, q) * depth
                val al = clamp01(q * 1.7f) * pageA * pullA
                val e = shown[idx]
                drawIcon(c, e, cx, cy, iconSize * sc, al)
                // los nombres aparecen un instante después que los iconos
                val la = clamp01((q - 0.35f) / 0.65f)
                drawLabel(c, e, cx, cy + iconSize * sc / 2f + dp(17f) * sc, cellW - dp(8f), al * la)
            }
        }
        c.restore()
        if (n > 1 && S.b(S.LP_DOTS)) drawDots(c, w, n, pv, clamp01(p) * pullA)
    }

    /** puntos de página: el activo se estira en una cápsula que se desliza entre páginas */
    private fun drawDots(c: Canvas, w: Float, n: Int, pv: Float, a: Float) {
        val gap = dp(15f)
        val x0 = w / 2f - (n - 1) * gap / 2f
        dotsRect.set(x0 - dp(16f), dotsY - dp(11f), x0 + (n - 1) * gap + dp(16f), dotsY + dp(11f))
        glass.draw(c, dotsRect, dp(11f), 0.7f * a, false, S.SF_LP_DOTS)
        for (i in 0 until n) {
            val near = 1f - min(1f, abs(i - pv))
            dotPaint.color = Color.argb(((90 + 165 * near) * a).toInt().coerceIn(0, 255), 255, 255, 255)
            val r = lerp(dp(3f), dp(3.6f), near)
            val hw = r + near * dp(4f)
            val x = x0 + i * gap
            dotRect.set(x - hw, dotsY - r, x + hw, dotsY + r)
            c.drawRoundRect(dotRect, r, r, dotPaint)
        }
    }

    private fun idxAt(x: Float, y: Float): Int {
        val pg = pageSp.value.roundToInt()
        val col = (x / cellW).toInt()
        val row = ((y - top) / cellH).toInt()
        if (y < top || col !in 0 until cols || row !in 0 until rows) return -1
        val i = pg * cap + row * cols + col
        return if (i in shown.indices) i else -1
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                listener?.onTouchStart()
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
                downX = x
                downY = y
                mode = 0
                moved = false
                longFired = false
                pageStart = pageSp.value
                pageSp.snap(pageSp.value)
                downIdx = idxAt(x, y)
                uiHandler.removeCallbacks(longRun)
                unpress()
                if (downIdx >= 0) uiHandler.postDelayed(longRun, longMs)
                if (downIdx >= 0) press(shown[downIdx])
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = x - downX
                val dy = y - downY
                if (longFired || mode == 3) return true
                if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                    moved = true
                    uiHandler.removeCallbacks(longRun)
                    unpress()
                    mode = if (abs(dx) > abs(dy)) 1 else if (dy > 0f && S.b(S.DR_SWIPE_CLOSE)) 2 else 4
                }
                if (mode == 1) {
                    val n = (pageCount() - 1).toFloat()
                    var t = pageStart - dx / width
                    if (t < 0f) t = rubber(t, 1f)
                    if (t > n) t = n + rubber(t - n, 1f)
                    pageSp.snap(t)
                    Mo.tune(pageSp, S.MO_LP_PAGE, 240f, 0.87f)
                    invalidate()
                } else if (mode == 2) {
                    // el contenido sigue al dedo
                    pull.snap(max(0f, dy - slop))
                    listener?.onPull(clamp01(pull.value / max(1f, height * 0.45f)))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longRun)
                unpress()
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                vt?.addMovement(ev)
                vt?.computeCurrentVelocity(1000)
                if (mode == 1) {
                    val v = -(vt?.xVelocity ?: 0f) / width
                    // un gesto pasa como mucho una página
                    val ps = pageStart.roundToInt()
                    val target = (pageSp.value + v * 0.25f).roundToInt().coerceIn(ps - 1, ps + 1).coerceIn(0, pageCount() - 1)
                    if (target != pageStart.roundToInt()) Hx.fire(this, S.HE_PAGE)
                    pageSp.to(target.toFloat())
                    pageSp.velocity = v
                    ticker.kick()
                } else if (mode == 2) {
                    val vy = vt?.yVelocity ?: 0f
                    if (up && (pull.value > dp(110f) || vy > 1600f)) {
                        mode = 3
                        listener?.onClose()
                    }
                    if (mode != 3) {
                        // no llegó a cerrar: vuelve a su sitio con rebote
                        Mo.tune(pull, S.MO_DRAWER_CLOSE, 300f, 0.75f)
                        pull.to(0f)
                        ticker.kick()
                    }
                } else if (mode == 0 && !moved && !longFired && up) {
                    if (downIdx >= 0) {
                        val r = RectF()
                        itemRect(downIdx, r)
                        listener?.onLaunch(shown[downIdx], r)
                    } else {
                        listener?.onClose()
                    }
                } else if (pageSp.value != pageSp.value.roundToInt().toFloat()) {
                    pageSp.to(pageSp.value.roundToInt().toFloat())
                    ticker.kick()
                }
                vt?.recycle()
                vt = null
                return true
            }
        }
        return true
    }
}
