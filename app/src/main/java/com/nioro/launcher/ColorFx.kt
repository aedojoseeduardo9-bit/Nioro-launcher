package com.nioro.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Efectos animados de color. Las estelas siguen la idea de las estelas de Color Flow; las
 * explosiones, la de las de StarNova.
 *  - una estela que sigue al dedo,
 *  - una explosión al tocar o al abrir una app,
 *  - un barrido de color al volver al inicio.
 */
object ColorFx {
    val TRAILS = arrayOf(
        "Sin estela", "Arcoíris", "Neón", "Chispas", "Aurora", "Prisma", "Galaxia", "Polvo de hadas",
        "Arcoíris líquido", "Estela infinita", "Confeti", "Burbujas", "Cometa"
    )
    val BURSTS = arrayOf("Nada", "Supernova", "Onda arcoíris", "Chispas", "Confeti", "Burbujas", "Flor de luz")
    val ENTERS = arrayOf("Nada", "Aurora", "Lluvia de estrellas", "Ola arcoíris", "Confeti")
    val PALS = arrayOf("Arcoíris", "Del tema", "Fuego", "Océano", "Aurora", "Neón", "Dorado", "Caramelo")

    /** cuánto dura un punto de cada estela (segundos) */
    val LIFE = floatArrayOf(0f, 0.5f, 0.42f, 0.5f, 0.6f, 0.42f, 0.7f, 0.8f, 0.55f, 0.6f, 0.9f, 0.9f, 0.4f)
    val BURST_DUR = floatArrayOf(0f, 0.75f, 0.7f, 0.85f, 1.1f, 1.2f, 0.9f)
    val ENTER_DUR = floatArrayOf(0f, 1.5f, 1.4f, 1.2f, 1.7f)

    private val STOPS: Array<IntArray> = arrayOf(
        IntArray(4), IntArray(4),
        intArrayOf(0xFFFFE066.toInt(), 0xFFFF9F1C.toInt(), 0xFFFF4D2E.toInt(), 0xFFD6246E.toInt()),
        intArrayOf(0xFF7CF5FF.toInt(), 0xFF00B4D8.toInt(), 0xFF3A86FF.toInt(), 0xFF6A5CFF.toInt()),
        intArrayOf(0xFF7CFFB2.toInt(), 0xFF2EE6A6.toInt(), 0xFF4CC9F0.toInt(), 0xFFB388FF.toInt()),
        intArrayOf(0xFFFF3CAC.toInt(), 0xFF9B5CFF.toInt(), 0xFF2B86C5.toInt(), 0xFF00F5D4.toInt()),
        intArrayOf(0xFFFFF3B0.toInt(), 0xFFFFD166.toInt(), 0xFFE9A23B.toInt(), 0xFFFFFFFF.toInt()),
        intArrayOf(0xFFFF9DE2.toInt(), 0xFFFFC6FF.toInt(), 0xFFBDB2FF.toInt(), 0xFFA0E7E5.toInt())
    )
    private val hsv = FloatArray(3)
    private var themeVer = -1

    /** los efectos son adorno: fuera con "Reducir movimiento" o en el modo seguro */
    fun allowed(): Boolean = !S.b(S.P_REDUCE) && !CrashGuard.safeMode

    /** color de la paleta elegida en la posición k (0..1, da la vuelta) */
    fun color(k: Float): Int {
        val pal = S.i(S.FX_PAL).coerceIn(0, STOPS.size - 1)
        val t = k - floor(k)
        if (pal == 0) {
            hsv[0] = t * 360f
            hsv[1] = 0.78f
            hsv[2] = 1f
            return Color.HSVToColor(hsv)
        }
        if (pal == 1 && themeVer != S.version) {
            themeVer = S.version
            val a = OneColors.ACCENT
            Color.colorToHSV(a, hsv)
            val h0 = hsv[0]
            val st = STOPS[1]
            st[0] = a
            hsv[0] = (h0 + 32f) % 360f
            st[1] = Color.HSVToColor(hsv)
            st[2] = blend(a, Color.WHITE, 0.55f)
            hsv[0] = (h0 + 328f) % 360f
            st[3] = Color.HSVToColor(hsv)
        }
        val s = STOPS[pal]
        val x = t * 4f
        val i = x.toInt().coerceIn(0, 3)
        return blend(s[i], s[(i + 1) % 4], x - i)
    }

    /** número pseudoaleatorio estable (0..1) para la pieza i, variante k */
    fun h(i: Int, k: Int): Float {
        val v = sin(i * 12.9898f + k * 78.233f) * 43758.547f
        return v - floor(v)
    }
}

/**
 * Capa transparente encima de todo el launcher que dibuja los efectos. No recibe toques: MainActivity
 * le pasa los del dedo. Solo pide fotogramas mientras queda algo vivo (cero trabajo en reposo).
 * Con [preview] = true (Ajustes) recibe sus propios toques y puede hacer una demostración sola.
 */
class ColorFxView(ctx: Context, private val preview: Boolean = false) : View(ctx) {

    // estela: anillo de puntos (x, y, nacimiento, número de serie para su "azar" estable)
    private val N = 72
    private val px = FloatArray(N)
    private val py = FloatArray(N)
    private val pb = FloatArray(N)
    private val ps = IntArray(N)
    private var head = 0
    private var cnt = 0
    private var serial = 0
    private var pointerId = -1
    private var lastX = 0f
    private var lastY = 0f

    // explosiones
    private val B = 10
    private val bType = IntArray(B)
    private val bx = FloatArray(B)
    private val by = FloatArray(B)
    private val bt = FloatArray(B)
    private val bk = FloatArray(B)
    private val bSeed = IntArray(B)
    private var bNext = 0

    // barrido al volver al inicio
    private var enterType = 0
    private var enterT0 = -100f

    // demostración (Ajustes): un dedo imaginario dibuja un ocho
    private var demoT0 = -100f
    private var demoTrail = 0

    private val t0 = SystemClock.uptimeMillis()
    private val ticker = Ticker { onFrame() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    /** estela elegida para la vista previa (en el inicio se lee de los ajustes) */
    var previewTrail = -1

    init {
        isClickable = preview
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun now(): Float = (SystemClock.uptimeMillis() - t0) / 1000f
    private fun unit(): Float = dp(9f) * S.p(S.FX_SIZE).coerceIn(0.5f, 2f)
    private fun trailStyle(): Int = (if (previewTrail >= 0) previewTrail else S.i(S.FX_TRAIL)).coerceIn(0, ColorFx.TRAILS.size - 1)

    // ---------------------------------------------------------------- Entrada
    /** toques del dedo: [trail] deja estela, [tap] lanza la explosión al apoyar el dedo */
    fun feed(ev: MotionEvent, trail: Boolean, tap: Boolean) {
        if (!ColorFx.allowed()) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = ev.getPointerId(0)
                lastX = ev.x
                lastY = ev.y
                if (trail) addPoint(ev.x, ev.y, true)
                if (tap) burst(S.i(S.FX_TAP), ev.x, ev.y, 1f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!trail || pointerId < 0) return
                val i = ev.findPointerIndex(pointerId)
                if (i < 0) return
                // los puntos intermedios del gesto hacen la estela suave aunque el dedo vaya rápido
                for (k in 0 until ev.historySize) addPoint(ev.getHistoricalX(i, k), ev.getHistoricalY(i, k), false)
                addPoint(ev.getX(i), ev.getY(i), false)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (ev.getPointerId(ev.actionIndex) == pointerId) pointerId = -1
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointerId = -1
        }
    }

    private fun addPoint(x: Float, y: Float, first: Boolean) {
        if (!first && cnt > 0 && hypot(x - lastX, y - lastY) < dp(3.5f)) return
        lastX = x
        lastY = y
        px[head] = x
        py[head] = y
        pb[head] = now()
        ps[head] = serial++
        head = (head + 1) % N
        if (cnt < N) cnt++
        ticker.kick()
    }

    /** explosión de color [type] en (x, y); [k] la agranda */
    fun burst(type: Int, x: Float, y: Float, k: Float) {
        if (type <= 0 || type >= ColorFx.BURSTS.size || !ColorFx.allowed()) return
        val i = bNext
        bNext = (bNext + 1) % B
        bType[i] = type
        bx[i] = x
        by[i] = y
        bt[i] = now()
        bk[i] = k
        bSeed[i] = serial++ * 7
        ticker.kick()
    }

    /** barrido de color a toda la pantalla al volver al inicio */
    fun enter(type: Int) {
        if (type <= 0 || type >= ColorFx.ENTERS.size || !ColorFx.allowed()) return
        enterType = type
        enterT0 = now()
        ticker.kick()
    }

    /** demostración de una estela en la vista previa de Ajustes */
    fun demo(trail: Int) {
        demoTrail = trail
        demoT0 = now()
        cnt = 0
        ticker.kick()
    }

    fun clear() {
        cnt = 0
        for (i in 0 until B) bType[i] = 0
        enterType = 0
        demoT0 = -100f
        ticker.stop()
        invalidate()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!preview) return false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        demoT0 = -100f
        feed(ev, trailStyle() > 0, S.i(S.FX_TAP) > 0)
        return true
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- Bucle
    private fun onFrame(): Boolean {
        val t = now()
        // dedo imaginario de la demostración: un ocho que cruza la vista previa
        if (demoT0 > -50f) {
            val e = t - demoT0
            if (e > 1.6f) demoT0 = -100f else {
                val a = e / 1.6f * 2f * PI.toFloat()
                val x = width / 2f + sin(a) * width * 0.36f
                val y = height / 2f + sin(a * 2f) * height * 0.28f
                if (demoTrail > 0) addPoint(x, y, e < 0.02f)
            }
        }
        val alive = prune(t) || demoT0 > -50f
        invalidate()
        return alive
    }

    /** quita lo que ya se apagó; true si queda algo que animar */
    private fun prune(t: Float): Boolean {
        val life = ColorFx.LIFE[trailStyle()].coerceAtLeast(0.2f)
        while (cnt > 0 && t - pb[idx(0)] > life) cnt--
        var any = cnt > 0
        for (i in 0 until B) {
            if (bType[i] == 0) continue
            if (t - bt[i] > ColorFx.BURST_DUR[bType[i]]) bType[i] = 0 else any = true
        }
        if (enterType > 0) {
            if (t - enterT0 > ColorFx.ENTER_DUR[enterType]) enterType = 0 else any = true
        }
        return any
    }

    private fun idx(i: Int): Int = (head - cnt + i + N * 2) % N

    // ---------------------------------------------------------------- Dibujo
    override fun onDraw(c: Canvas) {
        if (cnt == 0 && enterType == 0 && !anyBurst()) return
        val t = now()
        if (enterType > 0) drawEnter(c, enterType, (t - enterT0) / ColorFx.ENTER_DUR[enterType], t)
        if (cnt > 1 || (cnt == 1 && trailStyle() >= 3)) drawTrail(c, trailStyle(), t)
        for (i in 0 until B) if (bType[i] != 0) drawBurst(c, i, t)
    }

    private fun anyBurst(): Boolean {
        for (i in 0 until B) if (bType[i] != 0) return true
        return false
    }

    private fun ac(col: Int, a: Float): Int = (col and 0x00FFFFFF) or ((255f * a.coerceIn(0f, 1f)).toInt() shl 24)

    private fun seg(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, col: Int) {
        line.strokeWidth = max(0.8f, w)
        line.color = col
        c.drawLine(x0, y0, x1, y1, line)
    }

    private fun dot(c: Canvas, x: Float, y: Float, r: Float, col: Int) {
        fill.color = col
        c.drawCircle(x, y, max(0.6f, r), fill)
    }

    private fun ring(c: Canvas, x: Float, y: Float, r: Float, w: Float, col: Int) {
        line.strokeWidth = max(0.8f, w)
        line.color = col
        c.drawCircle(x, y, max(0.6f, r), line)
    }

    /** destello de cuatro puntas */
    private fun spark(c: Canvas, x: Float, y: Float, r: Float, col: Int) {
        line.color = col
        line.strokeWidth = max(0.8f, r * 0.28f)
        c.drawLine(x - r, y, x + r, y, line)
        c.drawLine(x, y - r, x, y + r, line)
        dot(c, x, y, r * 0.3f, col)
    }

    /** resplandor redondo y suave: una máscara de degradado teñida con el color del pincel (sin crear objetos) */
    private fun glow(c: Canvas, x: Float, y: Float, r: Float, col: Int) {
        val b = glowBmp ?: makeGlow() ?: return dot(c, x, y, r * 0.5f, col)
        glowPaint.color = col
        rect.set(x - r, y - r, x + r, y + r)
        c.drawBitmap(b, null, rect, glowPaint)
    }

    private fun confetti(c: Canvas, x: Float, y: Float, s: Float, ang: Float, col: Int) {
        fill.color = col
        c.save()
        c.rotate(ang, x, y)
        // al girar en 3D la pieza se ve más estrecha y más ancha alternando
        val w = s * (0.35f + 0.65f * abs(cos(ang * 0.05f)))
        c.drawRect(x - w / 2f, y - s * 0.32f, x + w / 2f, y + s * 0.32f, fill)
        c.restore()
    }

    private fun easeOut(x: Float): Float {
        val k = 1f - x.coerceIn(0f, 1f)
        return 1f - k * k * k
    }

    private var nx = 0f
    private var ny = 0f
    private fun normal(i: Int) {
        val a = idx(max(0, i - 1))
        val b = idx(i)
        var dx = px[b] - px[a]
        var dy = py[b] - py[a]
        if (i == 0 && cnt > 1) {
            val n1 = idx(1)
            dx = px[n1] - px[b]
            dy = py[n1] - py[b]
        }
        val l = sqrt(dx * dx + dy * dy)
        if (l < 0.01f) return
        nx = -dy / l
        ny = dx / l
    }

    private fun drawTrail(c: Canvas, st: Int, t: Float) {
        val life = ColorFx.LIFE[st]
        val R = unit()
        nx = 0f
        ny = 1f
        when (st) {
            1 -> for (i in 1 until cnt) { // arcoíris
                val a = idx(i - 1); val b = idx(i)
                val f = 1f - (t - pb[b]) / life
                if (f <= 0f) continue
                seg(c, px[a], py[a], px[b], py[b], R * 0.9f * (0.35f + 0.65f * f), ac(ColorFx.color(ps[b] * 0.025f + t * 0.3f), f * 0.92f))
            }
            2 -> for (i in 1 until cnt) { // neón
                val a = idx(i - 1); val b = idx(i)
                val f = 1f - (t - pb[b]) / life
                if (f <= 0f) continue
                val col = ColorFx.color(t * 0.25f + ps[b] * 0.008f)
                seg(c, px[a], py[a], px[b], py[b], R * 1.5f * f, ac(col, f * 0.16f))
                seg(c, px[a], py[a], px[b], py[b], R * 0.8f * f, ac(col, f * 0.42f))
                seg(c, px[a], py[a], px[b], py[b], R * 0.22f * f + 1f, ac(Color.WHITE, f))
            }
            3 -> for (i in 0 until cnt) { // chispas
                val b = idx(i)
                val age = t - pb[b]
                val f = 1f - age / life
                if (f <= 0f || ps[b] % 2 != 0) continue
                val s = ps[b]
                val spread = R * 1.6f * (0.4f + age * 2.4f)
                val x = px[b] + (ColorFx.h(s, 1) - 0.5f) * spread
                val y = py[b] + (ColorFx.h(s, 2) - 0.5f) * spread + age * age * dp(60f)
                val tw = 0.6f + 0.4f * sin(t * 24f + s)
                spark(c, x, y, R * (0.3f + 0.45f * f) * tw, ac(if (s % 6 == 0) Color.WHITE else ColorFx.color(ColorFx.h(s, 3)), f))
            }
            4 -> for (s in 0 until 3) { // aurora: tres bandas que ondulan
                for (i in 1 until cnt) {
                    val a = idx(i - 1); val b = idx(i)
                    val age = t - pb[b]
                    val f = 1f - age / life
                    if (f <= 0f) continue
                    normal(i)
                    val o = (s - 1) * R * 0.6f + sin(age * 9f + t * 2.2f + s * 1.7f) * R * 0.45f
                    val col = ColorFx.color(0.3f + s * 0.22f + t * 0.05f)
                    seg(c, px[a] + nx * o, py[a] + ny * o, px[b] + nx * o, py[b] + ny * o, R * 0.85f * f, ac(col, f * 0.34f))
                    seg(c, px[a] + nx * o, py[a] + ny * o, px[b] + nx * o, py[b] + ny * o, R * 0.22f * f + 0.8f, ac(col, f * 0.8f))
                }
            }
            5 -> for (i in 1 until cnt) { // prisma: tres colores separados
                val a = idx(i - 1); val b = idx(i)
                val f = 1f - (t - pb[b]) / life
                if (f <= 0f) continue
                normal(i)
                val o = R * 0.42f * (1f - f * 0.5f)
                val w = R * 0.24f * f + 1f
                seg(c, px[a] + nx * o, py[a] + ny * o, px[b] + nx * o, py[b] + ny * o, w, ac(ColorFx.color(0f), f))
                seg(c, px[a], py[a], px[b], py[b], w, ac(ColorFx.color(0.34f), f))
                seg(c, px[a] - nx * o, py[a] - ny * o, px[b] - nx * o, py[b] - ny * o, w, ac(ColorFx.color(0.67f), f))
            }
            6 -> for (i in 0 until cnt) { // galaxia: nubes de color y estrellas que titilan
                val b = idx(i)
                val age = t - pb[b]
                val f = 1f - age / life
                if (f <= 0f) continue
                val s = ps[b]
                val e = 1f - f
                val ox = (ColorFx.h(s, 47) - 0.5f) * R * 2f * e
                val oy = (ColorFx.h(s, 48) - 0.5f) * R * 2f * e
                if (s % 3 == 0) glow(c, px[b] + ox, py[b] + oy, R * (1.4f + e * 2.6f), ac(ColorFx.color(s * 0.07f), f * 0.55f))
                if (s % 5 == 0) {
                    val tw = 0.5f + 0.5f * sin(t * 11f + s * 2f)
                    spark(c, px[b] - ox * 1.6f, py[b] - oy * 1.6f, R * 0.3f * (0.5f + tw) + 1f, ac(Color.WHITE, f))
                }
            }
            7 -> for (i in 0 until cnt) { // polvo de hadas: chispitas que caen y titilan
                val b = idx(i)
                val age = t - pb[b]
                val f = 1f - age / life
                if (f <= 0f) continue
                val s = ps[b]
                for (k in 0 until 2) {
                    val x = px[b] + (ColorFx.h(s, 10 + k) - 0.5f) * R * 2.2f + sin(age * 6f + s) * R * 0.3f
                    val y = py[b] + (ColorFx.h(s, 20 + k) - 0.3f) * R * 1.2f + age * age * dp(110f)
                    val tw = 0.5f + 0.5f * sin(t * 18f + s * 3f + k)
                    val col = blend(ColorFx.color(ColorFx.h(s, 30 + k) * 0.25f + 0.1f), Color.WHITE, 0.35f)
                    dot(c, x, y, R * 0.16f * (0.6f + tw) * f + 0.6f, ac(col, f))
                    if (k == 0 && s % 4 == 0) spark(c, x, y, R * 0.42f * tw * f + 0.6f, ac(Color.WHITE, f * 0.85f))
                }
            }
            8 -> for (i in 1 until cnt) { // arcoíris líquido: cinta de cinco bandas que fluyen
                val a = idx(i - 1); val b = idx(i)
                val f = 1f - (t - pb[b]) / life
                if (f <= 0f) continue
                normal(i)
                val w = R * 1.7f * (0.35f + 0.65f * f)
                for (k in 0 until 5) {
                    val o = (k - 2f) / 5f * w
                    val col = ColorFx.color(k * 0.17f + ps[b] * 0.01f - t * 0.45f)
                    seg(c, px[a] + nx * o, py[a] + ny * o, px[b] + nx * o, py[b] + ny * o, w / 5f + 1.5f, ac(col, f * 0.9f))
                }
                seg(c, px[a] - nx * w * 0.25f, py[a] - ny * w * 0.25f, px[b] - nx * w * 0.25f, py[b] - ny * w * 0.25f, w * 0.1f + 1f, ac(Color.WHITE, f * 0.75f))
            }
            9 -> { // estela infinita: núcleo de luz, tres hebras en hélice y destellos
                for (i in 1 until cnt) {
                    val a = idx(i - 1); val b = idx(i)
                    val f = 1f - (t - pb[b]) / life
                    if (f <= 0f) continue
                    seg(c, px[a], py[a], px[b], py[b], R * 0.95f * f, ac(ColorFx.color(t * 0.2f), f * 0.32f))
                    seg(c, px[a], py[a], px[b], py[b], R * 0.24f * f + 1f, ac(Color.WHITE, f * 0.95f))
                }
                for (k in 0 until 3) {
                    var lx = 0f
                    var ly = 0f
                    var has = false
                    for (i in 0 until cnt) {
                        val b = idx(i)
                        val f = 1f - (t - pb[b]) / life
                        if (f <= 0f) {
                            has = false
                            continue
                        }
                        normal(i)
                        val o = sin(ps[b] * 0.42f - t * 7f + k * 2.094f) * R * 1.1f * (0.35f + 0.65f * (1f - f))
                        val x = px[b] + nx * o
                        val y = py[b] + ny * o
                        if (has) seg(c, lx, ly, x, y, R * 0.22f * f + 1.2f, ac(ColorFx.color(k * 0.33f + ps[b] * 0.02f - t * 0.5f), f * 0.95f))
                        lx = x
                        ly = y
                        has = true
                    }
                }
                for (i in 0 until cnt) {
                    val b = idx(i)
                    val f = 1f - (t - pb[b]) / life
                    if (f <= 0f || ps[b] % 7 != 0) continue
                    val s = ps[b]
                    val tw = 0.5f + 0.5f * sin(t * 14f + s)
                    spark(c, px[b] + (ColorFx.h(s, 53) - 0.5f) * R * 2.2f, py[b] + (ColorFx.h(s, 54) - 0.5f) * R * 2.2f, R * (0.3f + 0.3f * tw) * f + 1f, ac(Color.WHITE, f))
                }
            }
            10 -> for (i in 0 until cnt) { // confeti que cae girando
                val b = idx(i)
                val age = t - pb[b]
                val f = 1f - age / life
                if (f <= 0f || ps[b] % 2 != 0) continue
                val s = ps[b]
                val x = px[b] + (ColorFx.h(s, 5) - 0.5f) * R * 3f + sin(age * 5f + s) * R * 0.5f
                val y = py[b] + (ColorFx.h(s, 6) - 0.5f) * R * 1.5f + age * age * dp(150f)
                confetti(c, x, y, R * 0.75f, ColorFx.h(s, 7) * 360f + age * 540f, ac(ColorFx.color(ColorFx.h(s, 8)), min(1f, f * 2.2f)))
            }
            11 -> for (i in 0 until cnt) { // burbujas tornasoladas que suben y revientan
                val b = idx(i)
                val age = t - pb[b]
                val f = 1f - age / life
                if (f <= 0f || ps[b] % 4 != 0) continue
                val s = ps[b]
                val r = R * (0.35f + 0.9f * ColorFx.h(s, 9)) * (0.6f + age * 1.4f)
                val x = px[b] + (ColorFx.h(s, 10) - 0.5f) * R * 2f + sin(age * 7f + s) * R * 0.35f
                val y = py[b] - age * dp(46f)
                // al final revientan: el anillo se abre y se desvanece
                val pop = if (f < 0.18f) 1f + (0.18f - f) * 3f else 1f
                ring(c, x, y, r * pop, R * 0.12f + 0.8f, ac(ColorFx.color(ColorFx.h(s, 11) + t * 0.3f), f * 0.9f))
                dot(c, x - r * 0.38f, y - r * 0.38f, r * 0.18f, ac(Color.WHITE, f * 0.8f))
            }
            12 -> { // cometa: cola que se afina y cabeza brillante
                for (i in 1 until cnt) {
                    val a = idx(i - 1); val b = idx(i)
                    val f = 1f - (t - pb[b]) / life
                    if (f <= 0f) continue
                    val k = i / cnt.toFloat()
                    val col = ColorFx.color(0.08f + k * 0.18f + t * 0.1f)
                    seg(c, px[a], py[a], px[b], py[b], R * 1.6f * k * f, ac(col, f * 0.35f))
                    seg(c, px[a], py[a], px[b], py[b], R * 0.6f * k * f + 0.8f, ac(blend(col, Color.WHITE, 0.5f), f * 0.9f))
                }
                val hd = idx(cnt - 1)
                val f = 1f - (t - pb[hd]) / life
                if (f > 0f) {
                    glow(c, px[hd], py[hd], R * 2.4f, ac(ColorFx.color(0.1f + t * 0.1f), f * 0.7f))
                    dot(c, px[hd], py[hd], R * 0.45f * f, ac(Color.WHITE, f))
                }
            }
        }
    }

    private fun drawBurst(c: Canvas, i: Int, t: Float) {
        val type = bType[i]
        val e = ((t - bt[i]) / ColorFx.BURST_DUR[type]).coerceIn(0f, 1f)
        val x = bx[i]
        val y = by[i]
        val R = unit() * bk[i]
        val seed = bSeed[i]
        val fade = 1f - e
        when (type) {
            1 -> { // supernova: destello y tres anillos que se expanden uno tras otro
                if (e < 0.35f) glow(c, x, y, R * (1.5f + e * 9f), ac(blend(ColorFx.color(0.1f), Color.WHITE, 0.4f), (1f - e / 0.35f) * 0.85f))
                for (k in 0 until 3) {
                    val q = ((e - k * 0.12f) / (1f - k * 0.12f)).coerceIn(0f, 1f)
                    if (q <= 0f || q >= 1f) continue
                    val r = R * (0.6f + 6.5f * easeOut(q))
                    ring(c, x, y, r, R * 0.42f * (1f - q) + 0.8f, ac(if (k == 1) Color.WHITE else ColorFx.color(k * 0.33f + seed * 0.01f), (1f - q) * 0.95f))
                }
            }
            2 -> { // onda arcoíris: anillo de 28 colores que crece
                val r = R * (0.8f + 7f * easeOut(e))
                val n = 28
                line.strokeWidth = R * 0.55f * fade + 1f
                for (k in 0 until n) {
                    val a0 = k * 360f / n + e * 120f
                    line.color = ac(ColorFx.color(k / n.toFloat() + e * 0.5f), fade)
                    rect.set(x - r, y - r, x + r, y + r)
                    c.drawArc(rect, a0, 360f / n + 0.6f, false, line)
                }
            }
            3 -> { // chispas que salen disparadas y caen
                val n = 16
                for (k in 0 until n) {
                    val ang = (k / n.toFloat() + ColorFx.h(seed + k, 1) * 0.05f) * 2f * PI.toFloat()
                    val sp = R * (5f + 4f * ColorFx.h(seed + k, 2))
                    val d = sp * easeOut(e)
                    val gx = cos(ang) * d
                    val gy = sin(ang) * d + e * e * R * 4f
                    val tail = 0.16f
                    val d0 = sp * easeOut((e - tail).coerceAtLeast(0f))
                    val hx = cos(ang) * d0
                    val hy = sin(ang) * d0 + (e - tail).coerceAtLeast(0f).let { it * it } * R * 4f
                    val col = ColorFx.color(k / n.toFloat() + seed * 0.01f)
                    seg(c, x + hx, y + hy, x + gx, y + gy, R * 0.22f * fade + 0.8f, ac(col, fade))
                    if (k % 3 == 0) spark(c, x + gx, y + gy, R * 0.45f * fade + 0.6f, ac(Color.WHITE, fade))
                }
            }
            4 -> { // confeti que salta hacia arriba y cae girando
                val n = 22
                val tt = e * ColorFx.BURST_DUR[type]
                for (k in 0 until n) {
                    val ang = (-90f + (ColorFx.h(seed + k, 3) - 0.5f) * 130f) * PI.toFloat() / 180f
                    val v = R * (14f + 10f * ColorFx.h(seed + k, 4))
                    val gx = cos(ang) * v * tt
                    val gy = sin(ang) * v * tt + 0.5f * R * 42f * tt * tt
                    confetti(c, x + gx, y + gy, R * 0.8f, ColorFx.h(seed + k, 5) * 360f + tt * 720f, ac(ColorFx.color(ColorFx.h(seed + k, 6)), min(1f, fade * 2.5f)))
                }
            }
            5 -> { // burbujas que suben ondulando y revientan
                for (k in 0 until 7) {
                    val delay = ColorFx.h(seed + k, 7) * 0.25f
                    val q = ((e - delay) / (1f - delay)).coerceIn(0f, 1f)
                    if (q <= 0f) continue
                    val r = R * (0.5f + 0.9f * ColorFx.h(seed + k, 8)) * (0.5f + q)
                    val gx = (ColorFx.h(seed + k, 9) - 0.5f) * R * 5f + sin(q * 9f + k) * R * 0.6f
                    val gy = -q * R * 9f
                    val pop = if (q > 0.85f) 1f + (q - 0.85f) * 4f else 1f
                    val a = if (q > 0.85f) (1f - q) / 0.15f else 1f
                    ring(c, x + gx, y + gy, r * pop, R * 0.13f + 0.8f, ac(ColorFx.color(ColorFx.h(seed + k, 10) + q * 0.5f), a * 0.9f))
                    dot(c, x + gx - r * 0.38f, y + gy - r * 0.38f, r * 0.17f, ac(Color.WHITE, a * 0.85f))
                }
            }
            6 -> { // flor de luz: pétalos de color que giran hacia fuera
                if (e < 0.3f) glow(c, x, y, R * (1.2f + e * 6f), ac(Color.WHITE, (1f - e / 0.3f) * 0.6f))
                val n = 10
                for (k in 0 until n) {
                    val ang = (k / n.toFloat()) * 2f * PI.toFloat() + e * 2.4f
                    val d = R * 5.5f * easeOut(e)
                    val gx = x + cos(ang) * d
                    val gy = y + sin(ang) * d
                    glow(c, gx, gy, R * (1.4f + 0.8f * fade), ac(ColorFx.color(k / n.toFloat() + e * 0.3f), fade * 0.8f))
                    dot(c, gx, gy, R * 0.22f * fade + 0.6f, ac(Color.WHITE, fade))
                }
            }
        }
    }

    private fun drawEnter(c: Canvas, type: Int, e0: Float, t: Float) {
        val e = e0.coerceIn(0f, 1f)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // entra y sale suave (nunca de golpe)
        val env = sin(e * PI.toFloat()).coerceAtLeast(0f)
        when (type) {
            1 -> { // aurora: tres bandas onduladas que suben desde el dock
                val step = dp(18f)
                for (s in 0 until 3) {
                    val base = h * (1.05f - 0.75f * easeOut(e)) - s * dp(54f)
                    val col = ColorFx.color(0.3f + s * 0.22f + t * 0.05f)
                    var lx = 0f
                    var ly = 0f
                    var x = -step
                    while (x <= w + step) {
                        val y = base + sin(x / w * 5.2f + t * 2.4f + s * 1.3f) * dp(26f) + sin(x / w * 11f - t * 1.7f) * dp(8f)
                        if (x > -step) {
                            seg(c, lx, ly, x, y, dp(46f), ac(col, env * 0.12f))
                            seg(c, lx, ly, x, y, dp(14f), ac(col, env * 0.28f))
                            seg(c, lx, ly, x, y, dp(2.5f), ac(blend(col, Color.WHITE, 0.4f), env * 0.75f))
                        }
                        lx = x
                        ly = y
                        x += step
                    }
                }
            }
            2 -> { // lluvia de estrellas fugaces en diagonal
                val n = 22
                for (k in 0 until n) {
                    val d0 = ColorFx.h(k, 61) * 0.5f
                    val q = ((e - d0) / 0.5f).coerceIn(0f, 1f)
                    if (q <= 0f || q >= 1f) continue
                    val sx = ColorFx.h(k, 62) * w * 1.3f
                    val sy = -dp(40f) + ColorFx.h(k, 63) * h * 0.4f
                    val len = dp(70f) + ColorFx.h(k, 64) * dp(60f)
                    val dist = h * 0.7f * q
                    val x = sx - dist * 0.55f
                    val y = sy + dist
                    val a = sin(q * PI.toFloat())
                    val col = ColorFx.color(ColorFx.h(k, 65))
                    seg(c, x, y, x + len * 0.55f * 0.7f, y - len * 0.7f, dp(3.5f), ac(col, a * 0.35f))
                    seg(c, x, y, x + len * 0.55f * 0.45f, y - len * 0.45f, dp(1.4f), ac(Color.WHITE, a * 0.9f))
                    spark(c, x, y, dp(5f) * (0.7f + 0.3f * sin(t * 20f + k)), ac(Color.WHITE, a))
                }
            }
            3 -> { // ola arcoíris: una franja de colores sube desde abajo y se desvanece arriba
                val step = dp(14f)
                val base = h * (1.08f - 1.2f * e)
                var x = 0f
                while (x < w) {
                    val y0 = base + sin(x / w * 6.3f + t * 3f) * dp(18f)
                    val y1 = base + sin((x + step) / w * 6.3f + t * 3f) * dp(18f)
                    val col = ColorFx.color(x / w + t * 0.3f)
                    seg(c, x, y0, x + step, y1, dp(60f), ac(col, env * 0.16f))
                    seg(c, x, y0, x + step, y1, dp(18f), ac(col, env * 0.42f))
                    seg(c, x, y0 - dp(5f), x + step, y1 - dp(5f), dp(2f), ac(Color.WHITE, env * 0.5f))
                    x += step
                }
            }
            4 -> { // confeti que cae desde arriba por toda la pantalla
                val n = 46
                val tt = e * ColorFx.ENTER_DUR[type]
                for (k in 0 until n) {
                    val d0 = ColorFx.h(k, 71) * 0.35f
                    val q = (e - d0).coerceAtLeast(0f)
                    if (q <= 0f) continue
                    val x = ColorFx.h(k, 72) * w + sin(tt * 3f + k) * dp(18f)
                    val y = -dp(20f) + q * h * (0.9f + 0.5f * ColorFx.h(k, 73))
                    val a = if (e > 0.75f) (1f - e) / 0.25f else 1f
                    confetti(c, x, y, dp(9f), ColorFx.h(k, 74) * 360f + tt * 400f, ac(ColorFx.color(ColorFx.h(k, 75)), a))
                }
            }
        }
    }

    companion object {
        private var glowBmp: Bitmap? = null

        /** máscara de 64×64 con un degradado radial suave; se crea una vez y la comparten todas las capas */
        private fun makeGlow(): Bitmap? {
            return try {
                val n = 64
                val buf = ByteBuffer.allocate(n * n)
                val c = (n - 1) / 2f
                for (y in 0 until n) for (x in 0 until n) {
                    val d = hypot(x - c, y - c) / c
                    val v = (1f - d).coerceIn(0f, 1f)
                    buf.put((v * v * 255f).toInt().toByte())
                }
                buf.rewind()
                val b = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
                b.copyPixelsFromBuffer(buf)
                glowBmp = b
                b
            } catch (t: Throwable) {
                null
            }
        }
    }
}
