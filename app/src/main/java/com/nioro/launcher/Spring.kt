package com.nioro.launcher

import android.os.SystemClock
import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Cualquier cosa que avanza en el tiempo dentro de un Ticker. */
interface Stepper {
    /** Devuelve true mientras siga en movimiento. */
    fun step(dt: Float): Boolean
}

/**
 * Resorte amortiguado (masa = 1) resuelto de forma EXACTA (solución analítica),
 * no con pasos de integración. Así el movimiento es idéntico a 60, 90 o 120 Hz,
 * nunca "explota" con un fotograma lento y conserva la velocidad si se interrumpe un gesto.
 */
class SpringValue(
    v: Float = 0f,
    var stiffness: Float = 280f,
    var damping: Float = 24f,
    private val rest: Float = 0.001f
) : Stepper {
    var value = v
    var target = v
    var velocity = 0f
    var active = false

    fun snap(v: Float) {
        value = v
        target = v
        velocity = 0f
        active = false
    }

    fun to(t: Float) {
        target = t
        active = true
    }

    /** ratio < 1 rebota, = 1 crítico, > 1 sobreamortiguado. */
    fun tune(stiff: Float, ratio: Float) {
        stiffness = stiff
        damping = 2f * ratio * sqrt(stiff)
    }

    override fun step(dt: Float): Boolean {
        if (!active) return false
        val k = if (stiffness < 0.01f) 0.01f else stiffness
        val w0 = sqrt(k)
        val zeta = damping / (2f * w0)
        val x0 = value - target
        val v0 = velocity
        val t = dt
        val x: Float
        val v: Float
        if (zeta < 0.9999f) {
            val wd = w0 * sqrt(1f - zeta * zeta)
            val a = x0
            val b = (v0 + zeta * w0 * x0) / wd
            val e = exp(-zeta * w0 * t)
            val c = cos(wd * t)
            val s = sin(wd * t)
            x = e * (a * c + b * s)
            v = e * ((b * wd - zeta * w0 * a) * c - (a * wd + zeta * w0 * b) * s)
        } else if (zeta < 1.0001f) {
            val a = x0
            val b = v0 + w0 * x0
            val e = exp(-w0 * t)
            x = (a + b * t) * e
            v = (b - w0 * (a + b * t)) * e
        } else {
            val sq = sqrt(zeta * zeta - 1f)
            val r1 = -w0 * (zeta - sq)
            val r2 = -w0 * (zeta + sq)
            val c2 = (v0 - r1 * x0) / (r2 - r1)
            val c1 = x0 - c2
            val e1 = exp(r1 * t)
            val e2 = exp(r2 * t)
            x = c1 * e1 + c2 * e2
            v = c1 * r1 * e1 + c2 * r2 * e2
        }
        value = target + x
        velocity = v
        if (abs(x) < rest && abs(v) < rest * 20f) {
            value = target
            velocity = 0f
            active = false
        }
        return active
    }
}

/**
 * Bucle de animación bajo demanda (Choreographer = sincronizado con la pantalla, 60/90/120 Hz).
 * Solo pide fotogramas mientras algo se mueve. En reposo no consume nada de batería.
 */
class Ticker(private val frame: (Float) -> Boolean) : Choreographer.FrameCallback {
    val springs = ArrayList<Stepper>()
    private var running = false
    private var last = 0L
    /** último fotograma o petición (ms): detecta un bucle que se quedó parado */
    private var alive = 0L
    /**
     * Alguien pidió fotogramas (kick) mientras el bucle ya corría: el fotograma actual no debe ser el
     * último aunque sus resortes hayan terminado. Antes ese aviso se perdía y una animación que empezaba
     * justo al acabar otra se quedaba congelada a medias (p. ej. el dock con los iconos diminutos).
     */
    private var wantMore = false

    fun <T : Stepper> add(s: T): T {
        springs.add(s)
        return s
    }

    val isRunning: Boolean get() = running

    fun kick() {
        val now = SystemClock.uptimeMillis()
        if (!running) {
            running = true
            last = 0L
            alive = now
            Screen.animStart()
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            wantMore = true
        }
        if (running && now - alive > 500L) {
            // "corriendo" pero sin fotogramas desde hace rato (p. ej. MIUI congeló la app): se reanuda
            alive = now
            last = 0L
            Choreographer.getInstance().removeFrameCallback(this)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stop() {
        if (running) Screen.animEnd()
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        alive = SystemClock.uptimeMillis()
        // Primer fotograma: se usa el periodo real de la pantalla (8,3 ms a 120 Hz).
        val dt = if (last == 0L) Screen.frameSec else ((frameTimeNanos - last) / 1_000_000_000f).coerceIn(0.002f, 0.05f)
        last = frameTimeNanos
        var any = false
        wantMore = false
        val sdt = dt * Prefs.speed
        for (i in 0 until springs.size) {
            if (springs[i].step(sdt)) any = true
        }
        val more = frame(dt)
        if (any || more || wantMore) {
            wantMore = false
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            running = false
            Screen.animEnd()
        }
    }
}

/** Info de la pantalla que usan las animaciones. */
object Screen {
    @Volatile var hz = 60f
    val frameSec: Float get() = 1f / hz

    /** Se avisa cuando empieza la primera animación (true) o termina la última (false). */
    var onAnimating: ((Boolean) -> Unit)? = null
    private var count = 0

    fun animStart() {
        count++
        if (count == 1) onAnimating?.invoke(true)
    }

    fun animEnd() {
        if (count > 0) count--
        if (count == 0) onAnimating?.invoke(false)
    }
}

/** Estiramiento elástico estilo iOS cuando se arrastra más allá del borde. */
fun rubber(over: Float, dim: Float): Float {
    if (dim <= 0f) return over * 0.3f
    val s = if (over < 0f) -1f else 1f
    val o = abs(over)
    return s * (1f - 1f / (o * 0.55f / dim + 1f)) * dim
}

/**
 * Desplazamiento 1D con inercia real (desaceleración exponencial, como iOS),
 * rebote elástico en los bordes y regreso con resorte.
 */
class Scroll1D(ticker: Ticker) : Stepper {
    val sp = SpringValue(0f, 170f, 26f, 0.3f)
    var min = 0f
    var max = 0f
    /** tamaño visible, para el estiramiento elástico */
    var dim = 900f
    private var flingV = 0f
    private var flinging = false
    private var raw = 0f

    init {
        ticker.add(this)
    }

    val value: Float get() = sp.value

    fun jump(v: Float) {
        stop()
        sp.snap(v)
        raw = v
    }

    /** Detiene cualquier movimiento (al poner el dedo). */
    fun stop() {
        flinging = false
        flingV = 0f
        val v = sp.value
        sp.snap(v)
        raw = when {
            v < min -> min - unrubber(min - v)
            v > max -> max + unrubber(v - max)
            else -> v
        }
    }

    /** ¿Se estaba moviendo (inercia, rebote o estirado fuera del borde)? */
    val isMoving: Boolean get() = flinging || sp.active || sp.value < min - 0.5f || sp.value > max + 0.5f

    /** Inversa de rubber(): cuánto "arrastre real" corresponde a un estiramiento visible f. */
    private fun unrubber(f: Float): Float {
        val d = if (dim <= 0f) 900f else dim
        val q = (f / d).coerceAtMost(0.95f)
        return q / (1f - q) * d / 0.55f
    }

    fun drag(delta: Float) {
        flinging = false
        raw += delta
        val v = when {
            raw < min -> min + rubber(raw - min, dim)
            raw > max -> max + rubber(raw - max, dim)
            else -> raw
        }
        sp.snap(v)
    }

    fun release(vel: Float) {
        val v = sp.value
        if (v < min || v > max) {
            Mo.tune(sp, S.MO_SCROLL, 170f, 1f)
            sp.to(if (v < min) min else max)
            sp.velocity = vel * 0.5f
            flinging = false
        } else {
            flingV = vel
            flinging = abs(vel) > 40f
            sp.snap(v)
        }
        raw = sp.value
    }

    fun clampNow() {
        flinging = false
        if (sp.value < min) sp.to(min)
        if (sp.value > max) sp.to(max)
    }

    override fun step(dt: Float): Boolean {
        if (flinging) {
            val decay = exp(-2.6f * dt)
            val nv = sp.value + flingV * (1f - decay) / 2.6f
            flingV *= decay
            if (nv < min || nv > max) {
                // choca con el borde: rebota con resorte conservando la velocidad
                sp.value = nv
                Mo.tune(sp, S.MO_SCROLL, 170f, 0.9f)
                sp.to(if (nv < min) min else max)
                sp.velocity = flingV * 0.6f
                flinging = false
            } else {
                sp.value = nv
                sp.target = nv
                if (abs(flingV) < 20f) flinging = false
            }
            raw = sp.value
            return flinging || sp.active
        }
        val r = sp.step(dt)
        raw = sp.value
        return r
    }
}
