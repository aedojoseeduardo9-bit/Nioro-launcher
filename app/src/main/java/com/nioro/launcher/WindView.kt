package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Modo Zen: el inicio es un cielo abierto y tus apps flotan en él como hojas llevadas por el viento.
 * No hay cuadrícula, ni orden, ni lista: todo se mueve por un campo de viento con remolinos.
 *
 *  - Deslizar el dedo es una ráfaga: empuja las apps que encuentra a su paso y deja estelas de aire.
 *  - Mantener el dedo en un hueco levanta un remolino: las apps cercanas giran a su alrededor y lo
 *    siguen si mueves el dedo; al soltar, salen despedidas.
 *  - Inclinar el móvil cambia hacia dónde sopla (solo el cambio: sostenerlo quieto no sopla).
 *  - Agitarlo levanta un torbellino que lo revuelve todo y despega las apps pegadas.
 *  - Un imán cerca del móvil (el sensor magnético lo nota) atrae las apps más cercanas a ese borde y
 *    se quedan pegadas allí, como imanes de nevera; se despegan con una ráfaga fuerte o agitando.
 *  - Cuando nadie lo toca, el viento amaina, las apps se posan, aparecen sus nombres y el dibujo se
 *    detiene (cero trabajo en reposo).
 *  - Desde el borde de abajo se sigue abriendo el menú de apps; desde el de arriba, las notificaciones.
 */
class WindView(ctx: Context) : AppsView(ctx), SensorEventListener {
    override val iconArea: Int = S.IA_HOME

    companion object {
        private const val MAX = 72
        private const val STREAKS = 22
        private const val TRAIL = 10
        private const val DUST = 34
        private const val NONE = -1
        private const val LEFT = 0
        private const val TOP = 1
        private const val RIGHT = 2
        private const val BOTTOM = 3
    }

    // ---------------------------------------------------------------- Las apps (hojas)
    private val list = ArrayList<AppEntry>()
    private var n = 0
    private val px = FloatArray(MAX)
    private val py = FloatArray(MAX)
    private val vx = FloatArray(MAX)
    private val vy = FloatArray(MAX)
    private val ang = FloatArray(MAX)
    private val av = FloatArray(MAX)
    /** profundidad: 1 delante, 0,62 al fondo (más pequeñas, más lentas, más transparentes) */
    private val zz = FloatArray(MAX)
    private val ph = FloatArray(MAX)
    /** borde al que está pegada (o NONE) y su sitio en ese borde */
    private val edge = IntArray(MAX) { NONE }
    private val slot = IntArray(MAX)
    private val stickT = FloatArray(MAX)
    private var placed = false
    private var size = 0f

    // ---------------------------------------------------------------- Viento
    private var tNow = 0f
    private var lastFrame = 0L
    private var lastWake = 0L
    /** fuerza del viento general (1 despierto, 0 en calma) */
    private var amp = 0f
    private var tiltX = 0f
    private var tiltY = 0f
    private var parX = 0f
    private var parY = 0f
    // ráfagas del dedo: posición, dirección y vida
    private val gx = FloatArray(24)
    private val gy = FloatArray(24)
    private val gdx = FloatArray(24)
    private val gdy = FloatArray(24)
    private val gt = FloatArray(24)
    private var gNext = 0
    // remolino bajo el dedo
    private var vortex = false
    private var vxC = 0f
    private var vyC = 0f
    private var vPow = 0f
    private var tornado = 0f

    // ---------------------------------------------------------------- Imán
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var sensing = false
    private var baseBx = 0f
    private var baseBy = 0f
    private var baseBz = 0f
    private var baseReady = 0
    private var magnet = false
    private var magEdge = NONE
    private var magPow = 0f
    private var gBaseX = 0f
    private var gBaseY = 9.8f
    private var shakeT = 0L
    private var shakes = 0

    // ---------------------------------------------------------------- Dibujo
    private val sx = Array(STREAKS) { FloatArray(TRAIL) }
    private val sy = Array(STREAKS) { FloatArray(TRAIL) }
    private val sLife = FloatArray(STREAKS)
    private val sAge = FloatArray(STREAKS)
    private val sHead = IntArray(STREAKS)
    private val sCount = IntArray(STREAKS)
    private var sAcc = 0f
    private val dx = FloatArray(DUST)
    private val dy = FloatArray(DUST)
    private val dz = FloatArray(DUST)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val names = HashMap<String, String>()
    private val field = Path()
    private val tmp = RectF()
    private var rnd = java.util.Random(7)

    // ---------------------------------------------------------------- Toques
    private var vt: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downIdx = -1
    private var moved = false
    private var longFired = false
    private val longRun = Runnable {
        if (moved) return@Runnable
        val i = downIdx
        if (i in 0 until n) {
            longFired = true
            lift()
            listener?.onMenu(list[i], rectOf(i))
        } else {
            // en un hueco: se levanta un remolino
            vortex = true
            vxC = lastX
            vyC = lastY
            vPow = 0f
            Hx.fire(this, S.HE_LONG)
            wake()
        }
    }

    init {
        namePaint.color = Color.WHITE
        namePaint.textAlign = Paint.Align.CENTER
        namePaint.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        namePaint.setShadowLayer(dp(4f), 0f, dp(1f), 0xAA000000.toInt())
        shadowPaint.color = 0x33000000
        for (k in 0 until DUST) dz[k] = 0.3f + rnd.nextFloat() * 0.7f
    }

    // ================================================================ Datos
    override fun onDataChanged() {
        val all = shown
        list.clear()
        val seen = HashSet<String>()
        val stuck = Prefs.windStuck()
        // primero las pegadas (conservan su borde), luego las favoritas y, si se pide, todas
        for ((k, _) in stuck) {
            val e = AppRepo.get(k) ?: continue
            if (all.none { it.key == k }) continue
            if (seen.add(k)) list.add(e)
        }
        val everyone = S.i(S.ZW_APPS) == 1
        if (!everyone) {
            for (k in Prefs.dockKeys() + Prefs.homeKeys()) {
                if (k == HomeSlots.BLANK) continue
                val e = AppRepo.get(k) ?: continue
                if (seen.add(k)) list.add(e)
                if (list.size >= 28) break
            }
            if (list.size < 10) for (e in all) {
                if (seen.add(e.key)) list.add(e)
                if (list.size >= 12) break
            }
        } else {
            for (e in all) {
                if (seen.add(e.key)) list.add(e)
                if (list.size >= MAX) break
            }
        }
        val old = n
        n = min(list.size, MAX)
        for (i in 0 until n) {
            val st = stuck[list[i].key]
            edge[i] = st?.first ?: NONE
            slot[i] = st?.second ?: 0
        }
        if (old != n) placed = false
        names.clear()
        place()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        placed = false
        place()
    }

    override fun onImeChanged() {}

    private fun areaTop(): Float = insetTop
    private fun areaBottom(): Float = height - insetBottom

    /** primera colocación: al azar, sin que se monten unas sobre otras */
    private fun place() {
        if (width <= 0 || height <= 0 || n == 0) return
        size = dp(Prefs.iconDp.toFloat()) * 1.05f
        namePaint.textSize = dp(11.5f)
        if (placed) return
        placed = true
        rnd = java.util.Random(n * 31L + width)
        val l = dp(28f)
        val r = width - dp(28f)
        val t = areaTop() + dp(60f)
        val b = areaBottom() - dp(70f)
        for (i in 0 until n) {
            var bx = 0f
            var by = 0f
            var best = -1f
            repeat(18) {
                val x = l + rnd.nextFloat() * (r - l)
                val y = t + rnd.nextFloat() * max(1f, b - t)
                var d = Float.MAX_VALUE
                for (j in 0 until i) d = min(d, hypot(px[j] - x, py[j] - y))
                if (d > best) {
                    best = d
                    bx = x
                    by = y
                }
            }
            px[i] = bx
            py[i] = by
            vx[i] = 0f
            vy[i] = 0f
            ang[i] = (rnd.nextFloat() - 0.5f) * 0.5f
            av[i] = 0f
            zz[i] = 0.62f + rnd.nextFloat() * 0.38f
            ph[i] = rnd.nextFloat() * 6.28f
            stickT[i] = if (edge[i] != NONE) 1f else 0f
        }
        for (k in 0 until DUST) {
            dx[k] = rnd.nextFloat() * width
            dy[k] = areaTop() + rnd.nextFloat() * (areaBottom() - areaTop())
        }
    }

    // ================================================================ Sensores
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startSensors() else stopSensors()
    }

    override fun onDetachedFromWindow() {
        stopSensors()
        super.onDetachedFromWindow()
    }

    private fun startSensors() {
        if (sensing) return
        sensing = true
        baseReady = 0
        try {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            if (S.b(S.ZW_MAGNET)) sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        } catch (t: Throwable) {
        }
    }

    private fun stopSensors() {
        if (!sensing) return
        sensing = false
        try {
            sm.unregisterListener(this)
        } catch (t: Throwable) {
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(ev: SensorEvent) {
        if (ev.sensor.type == Sensor.TYPE_ACCELEROMETER) accel(ev.values) else magnetic(ev.values)
    }

    private fun accel(v: FloatArray) {
        // gravedad en coordenadas de la pantalla (x a la derecha, y hacia abajo)
        val gxs = -v[0]
        val gys = v[1]
        val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        // agitar: dos sacudidas fuertes seguidas
        if (S.b(S.ZW_SHAKE) && abs(mag - 9.81f) > 7.5f) {
            val now = SystemClock.uptimeMillis()
            if (now - shakeT < 650L) shakes++ else shakes = 1
            shakeT = now
            if (shakes >= 2 && tornado < 0.2f) {
                shakes = 0
                startTornado()
            }
        }
        // solo cuenta el cambio de inclinación: la base se adapta en unos 3 segundos
        gBaseX += (gxs - gBaseX) * 0.02f
        gBaseY += (gys - gBaseY) * 0.02f
        val tx = (gxs - gBaseX)
        val ty = (gys - gBaseY)
        parX += (gxs / 9.81f - parX) * 0.1f
        parY += ((gys - 6.5f) / 9.81f - parY) * 0.1f
        if (!S.b(S.ZW_TILT)) return
        tiltX = tx
        tiltY = ty
        if (hypot(tx, ty) > 1.4f) wake()
    }

    private fun magnetic(v: FloatArray) {
        if (baseReady < 12) {
            // los primeros valores fijan el campo de la Tierra (unos 25-65 µT)
            baseBx += (v[0] - baseBx) * 0.3f
            baseBy += (v[1] - baseBy) * 0.3f
            baseBz += (v[2] - baseBz) * 0.3f
            baseReady++
            return
        }
        val ax = v[0] - baseBx
        val ay = v[1] - baseBy
        val az = v[2] - baseBz
        val anomaly = sqrt(ax * ax + ay * ay + az * az)
        if (!magnet && anomaly > 140f) {
            // ¡un imán! el borde es hacia donde apunta la alteración del campo
            magnet = true
            val sxs = ax
            val sys = -ay
            magEdge = if (abs(sxs) > abs(sys)) (if (sxs > 0f) RIGHT else LEFT) else (if (sys > 0f) BOTTOM else TOP)
            attract(magEdge)
        } else if (magnet && anomaly < 70f) {
            magnet = false
        }
        magPow = clamp01((anomaly - 40f) / 300f)
        if (!magnet) {
            // la base sigue despacio al campo normal (girar el móvil lo cambia un poco)
            baseBx += (v[0] - baseBx) * 0.01f
            baseBy += (v[1] - baseBy) * 0.01f
            baseBz += (v[2] - baseBz) * 0.01f
        }
        if (magnet) wake()
    }

    /** las apps libres más cercanas a ese borde vuelan hacia él y se quedan pegadas */
    private fun attract(e: Int) {
        var taken = 0
        for (i in 0 until n) if (edge[i] == e) taken++
        val cap = edgeCapacity(e)
        val want = min(5, cap - taken)
        if (want <= 0) return
        for (k in 0 until want) {
            var best = -1
            var bd = Float.MAX_VALUE
            for (i in 0 until n) {
                if (edge[i] != NONE) continue
                val d = when (e) {
                    LEFT -> px[i]
                    RIGHT -> width - px[i]
                    TOP -> py[i] - areaTop()
                    else -> areaBottom() - py[i]
                }
                if (d < bd) {
                    bd = d
                    best = i
                }
            }
            if (best < 0) break
            edge[best] = e
            slot[best] = freeSlot(e)
            stickT[best] = 0f
        }
        saveStuck()
        Hx.fire(this, S.HE_DOCK)
        wake()
    }

    private fun edgeCapacity(e: Int): Int {
        val len = if (e == LEFT || e == RIGHT) areaBottom() - areaTop() - dp(140f) else width - dp(60f)
        return max(1, (len / (size * 1.25f)).toInt())
    }

    /** el primer sitio libre de un borde, desde el centro hacia fuera (0, 1, -1, 2, -2...) */
    private fun freeSlot(e: Int): Int {
        var k = 0
        while (k < 40) {
            val s = if (k % 2 == 0) k / 2 else -(k + 1) / 2
            var used = false
            for (i in 0 until n) if (edge[i] == e && slot[i] == s) used = true
            if (!used) return s
            k++
        }
        return 0
    }

    private fun slotPos(e: Int, s: Int, out: FloatArray) {
        val m = size * 0.62f + dp(6f)
        val step = size * 1.25f
        when (e) {
            LEFT -> { out[0] = m; out[1] = (areaTop() + areaBottom()) / 2f + s * step }
            RIGHT -> { out[0] = width - m; out[1] = (areaTop() + areaBottom()) / 2f + s * step }
            TOP -> { out[0] = width / 2f + s * step; out[1] = areaTop() + m }
            else -> { out[0] = width / 2f + s * step; out[1] = areaBottom() - m }
        }
    }

    private val slotTmp = FloatArray(2)

    private fun saveStuck() {
        val m = LinkedHashMap<String, Pair<Int, Int>>()
        for (i in 0 until n) if (edge[i] != NONE) m[list[i].key] = Pair(edge[i], slot[i])
        Prefs.setWindStuck(m)
    }

    private fun release(i: Int, kx: Float, ky: Float) {
        edge[i] = NONE
        vx[i] += kx
        vy[i] += ky
        av[i] += (rnd.nextFloat() - 0.5f) * 8f
    }

    private fun startTornado() {
        tornado = 1f
        var any = false
        for (i in 0 until n) if (edge[i] != NONE) {
            release(i, (width / 2f - px[i]) * 2f, (height / 2f - py[i]) * 2f)
            any = true
        }
        if (any) saveStuck()
        Hx.fire(this, S.HE_PAGE)
        wake()
    }

    // ================================================================ Física
    private fun wake() {
        lastWake = SystemClock.uptimeMillis()
        ticker.kick()
    }

    /** campo de viento con remolinos: el rotacional de una función suave (no tiene "fuentes") */
    private fun curlX(x: Float, y: Float, t: Float): Float {
        val a = 0.0042f
        val b = 0.0037f
        return (cos(y * b - t * 0.21f) * b * sin(x * a + t * 0.33f) + 0.55f * 0.0061f * cos((x + y) * 0.0061f + t * 0.47f))
    }

    private fun curlY(x: Float, y: Float, t: Float): Float {
        val a = 0.0042f
        val b = 0.0037f
        return -(a * cos(x * a + t * 0.33f) * cos(y * b - t * 0.21f) + 0.55f * 0.0061f * cos((x + y) * 0.0061f + t * 0.47f))
    }

    /** viento en un punto (px/s): brisa con remolinos + inclinación + ráfagas + remolino del dedo */
    private val air = FloatArray(2)

    private fun airAt(x: Float, y: Float) {
        val k = dp(1f)
        val swirl = (40f + 120f * tornado) * k * amp
        var ax = curlX(x, y, tNow) * 240f * swirl + 22f * k * amp
        var ay = curlY(x, y, tNow) * 240f * swirl
        ax += tiltX * 55f * k
        ay += tiltY * 55f * k
        for (g in 0 until gx.size) {
            val life = gt[g]
            if (life <= 0f) continue
            val d2 = (x - gx[g]) * (x - gx[g]) + (y - gy[g]) * (y - gy[g])
            val r = dp(130f)
            val f = exp(-d2 / (r * r)) * life
            ax += gdx[g] * f
            ay += gdy[g] * f
        }
        if (vPow > 0.01f) {
            // remolino: gira alrededor del dedo y atrae un poco hacia dentro
            val ddx = x - vxC
            val ddy = y - vyC
            val d = max(dp(30f), hypot(ddx, ddy))
            val r = dp(210f)
            val f = vPow * exp(-(d * d) / (r * r) * 0.7f)
            val spin = 900f * k * f
            ax += (-ddy / d) * spin - (ddx / d) * spin * 0.35f
            ay += (ddx / d) * spin - (ddy / d) * spin * 0.35f
        }
        if (tornado > 0.01f) {
            val ddx = x - width / 2f
            val ddy = y - (areaTop() + areaBottom()) / 2f
            val d = max(dp(40f), hypot(ddx, ddy))
            val spin = 700f * k * tornado
            ax += (-ddy / d) * spin
            ay += (ddx / d) * spin
        }
        air[0] = ax
        air[1] = ay
    }

    override fun onFrame(dt0: Float): Boolean {
        val dt = dt0.coerceIn(0.001f, 0.05f)
        val now = SystemClock.uptimeMillis()
        tNow += dt
        val calm = (S.f(S.ZW_CALM) * 1000f).toLong()
        val awake = now - lastWake < calm || vortex || magnet
        amp += ((if (awake) 1f else 0f) - amp) * (1f - exp(-dt * (if (awake) 3f else 0.9f)))
        tornado = max(0f, tornado - dt * 0.45f)
        vPow = if (vortex) min(1f, vPow + dt * 1.6f) else max(0f, vPow - dt * 3f)
        for (g in 0 until gt.size) if (gt[g] > 0f) gt[g] = max(0f, gt[g] - dt * 1.7f)
        tiltX *= exp(-dt * 1.2f)
        tiltY *= exp(-dt * 1.2f)
        val top = areaTop()
        val bottom = areaBottom()
        var energy = 0f
        for (i in 0 until n) {
            if (edge[i] != NONE) {
                // pegada a un borde: va a su sitio con un resorte (y un pequeño temblor si el imán sigue)
                slotPos(edge[i], slot[i], slotTmp)
                val k = 60f
                val d = 2f * sqrt(k) * 0.55f
                vx[i] += ((slotTmp[0] - px[i]) * k - vx[i] * d) * dt
                vy[i] += ((slotTmp[1] - py[i]) * k - vy[i] * d) * dt
                av[i] += (-ang[i] * 80f - av[i] * 10f) * dt
                if (stickT[i] < 1f) {
                    stickT[i] = min(1f, stickT[i] + dt * 2.2f)
                    if (stickT[i] >= 1f) Hx.fire(this, S.HE_DOCK)
                }
            } else {
                airAt(px[i], py[i])
                // la hoja se deja llevar: cuanto más al fondo, más despacio
                val drag = 1.6f * (0.6f + 0.4f * zz[i])
                vx[i] += (air[0] * zz[i] - vx[i]) * drag * dt
                vy[i] += (air[1] * zz[i] - vy[i]) * drag * dt
                // revoloteo: gira con el viento cruzado
                val want = (vx[i] / dp(400f)).coerceIn(-0.9f, 0.9f) + sin(tNow * 1.7f + ph[i]) * 0.12f * amp
                av[i] += ((want - ang[i]) * 9f - av[i] * 4f) * dt
                // paredes blandas arriba y abajo; a los lados el viento las saca y vuelven por el otro lado
                val m = size * 0.6f
                if (py[i] < top + m) vy[i] += (top + m - py[i]) * 30f * dt
                if (py[i] > bottom - m - dp(24f)) vy[i] -= (py[i] - (bottom - m - dp(24f))) * 30f * dt
                // con viento fuerte salen por un lado y entran por el otro; al amainar, vuelven dentro
                if (amp > 0.6f) {
                    if (px[i] < -m) px[i] = width + m * 0.8f
                    if (px[i] > width + m) px[i] = -m * 0.8f
                } else {
                    val wall = 30f * (1f - amp)
                    if (px[i] < m) vx[i] += (m - px[i]) * wall * dt
                    if (px[i] > width - m) vx[i] -= (px[i] - (width - m)) * wall * dt
                }
            }
            ang[i] += av[i] * dt
        }
        // que no se monten unas sobre otras
        val minD = size * 1.02f
        for (i in 0 until n) for (j in i + 1 until n) {
            val ddx = px[j] - px[i]
            val ddy = py[j] - py[i]
            val d2 = ddx * ddx + ddy * ddy
            if (d2 >= minD * minD || d2 < 0.01f) continue
            val d = sqrt(d2)
            val push = (minD - d) * 6f
            val ux = ddx / d
            val uy = ddy / d
            val fi = if (edge[i] != NONE) 0.15f else 1f
            val fj = if (edge[j] != NONE) 0.15f else 1f
            vx[i] -= ux * push * fi
            vy[i] -= uy * push * fi
            vx[j] += ux * push * fj
            vy[j] += uy * push * fj
        }
        for (i in 0 until n) {
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            energy += abs(vx[i]) + abs(vy[i]) + abs(av[i]) * 40f
        }
        stepStreaks(dt)
        stepDust(dt)
        invalidate()
        // en calma y quietas (y todas dentro de la pantalla): se para el dibujo
        var outside = false
        for (i in 0 until n) if (edge[i] == NONE && (px[i] < size * 0.5f || px[i] > width - size * 0.5f)) outside = true
        val still = energy / max(1, n) < dp(3f) && !outside
        return amp > 0.01f || !still || vPow > 0f || tornado > 0f || anyGust() || magnet
    }

    private fun anyGust(): Boolean {
        for (g in gt) if (g > 0f) return true
        return false
    }

    private fun stepStreaks(dt: Float) {
        if (!S.b(S.ZW_STREAKS)) return
        // nacen más estelas cuanto más sopla
        sAcc += dt * (2f + 14f * amp + 30f * tornado + 18f * vPow)
        while (sAcc > 1f) {
            sAcc -= 1f
            var k = -1
            for (q in 0 until STREAKS) if (sLife[q] <= 0f) { k = q; break }
            if (k < 0) break
            sLife[k] = 1.1f + rnd.nextFloat() * 0.9f
            sAge[k] = 0f
            sHead[k] = 0
            sCount[k] = 1
            sx[k][0] = rnd.nextFloat() * width
            sy[k][0] = areaTop() + rnd.nextFloat() * (areaBottom() - areaTop())
        }
        for (k in 0 until STREAKS) {
            if (sLife[k] <= 0f) continue
            sAge[k] += dt
            if (sAge[k] >= sLife[k]) {
                sLife[k] = 0f
                continue
            }
            val h = sHead[k]
            airAt(sx[k][h], sy[k][h])
            val nh = (h + 1) % TRAIL
            sx[k][nh] = sx[k][h] + air[0] * dt * 1.6f
            sy[k][nh] = sy[k][h] + air[1] * dt * 1.6f
            sHead[k] = nh
            if (sCount[k] < TRAIL) sCount[k]++
        }
    }

    private fun stepDust(dt: Float) {
        for (k in 0 until DUST) {
            airAt(dx[k], dy[k])
            dx[k] += air[0] * dz[k] * dt * 1.2f
            dy[k] += (air[1] * dz[k] + dp(4f)) * dt * 1.2f
            if (dx[k] < -10f) dx[k] = width + 10f
            if (dx[k] > width + 10f) dx[k] = -10f
            if (dy[k] < areaTop()) dy[k] = areaBottom()
            if (dy[k] > areaBottom()) dy[k] = areaTop()
        }
    }

    // ================================================================ Dibujo
    override fun onDraw(c: Canvas) {
        val enter = clamp01(progress * 1.4f)
        val calmK = 1f - amp
        // polvo en el aire (más visible cuando sopla)
        val dustA = (90f * (0.25f + 0.75f * amp) * enter).toInt()
        if (dustA > 2) for (k in 0 until DUST) {
            dotPaint.color = Color.WHITE
            dotPaint.alpha = (dustA * dz[k]).toInt()
            c.drawCircle(dx[k] + parX * dp(14f) * dz[k], dy[k] + parY * dp(10f) * dz[k], dp(1.2f) + dz[k] * dp(1.3f), dotPaint)
        }
        // estelas de aire
        if (S.b(S.ZW_STREAKS)) for (k in 0 until STREAKS) {
            if (sLife[k] <= 0f || sCount[k] < 2) continue
            val life = sAge[k] / sLife[k]
            val fade = sin(PI.toFloat() * life)
            var prev = (sHead[k] - sCount[k] + 1 + TRAIL) % TRAIL
            for (q in 1 until sCount[k]) {
                val cur = (prev + 1) % TRAIL
                val w = q / sCount[k].toFloat()
                linePaint.color = Color.WHITE
                linePaint.alpha = (110f * fade * w * enter).toInt()
                linePaint.strokeWidth = dp(0.8f) + dp(1.4f) * w
                c.drawLine(sx[k][prev], sy[k][prev], sx[k][cur], sy[k][cur], linePaint)
                prev = cur
            }
        }
        // líneas del campo magnético mientras hay un imán cerca
        if (magPow > 0.05f && magEdge != NONE) drawField(c)
        // remolino bajo el dedo: anillos que giran
        if (vPow > 0.02f) {
            linePaint.color = Color.WHITE
            for (r in 1..3) {
                linePaint.alpha = (70f * vPow / r).toInt()
                linePaint.strokeWidth = dp(1.5f)
                val rad = dp(40f) * r + sin(tNow * 3f + r) * dp(6f)
                tmp.set(vxC - rad, vyC - rad, vxC + rad, vyC + rad)
                c.drawArc(tmp, (tNow * 160f * r) % 360f, 220f, false, linePaint)
            }
        }
        // las apps: las del fondo primero
        for (pass in 0..1) for (i in 0 until n) {
            val front = zz[i] > 0.81f || edge[i] != NONE
            if ((pass == 0) == front) continue
            val e = list[i]
            val z = if (edge[i] != NONE) 1f else zz[i]
            val s = size * (0.72f + 0.28f * z)
            val x = px[i] + if (edge[i] == NONE) parX * dp(22f) * (1f - z) else 0f
            val y = py[i] + if (edge[i] == NONE) parY * dp(16f) * (1f - z) else 0f
            val a = enter * (0.55f + 0.45f * z)
            // sombra suave en el "suelo", más lejos cuanto más alta va la hoja
            shadowPaint.alpha = (60f * a).toInt()
            c.drawOval(x - s * 0.36f, y + s * 0.52f, x + s * 0.36f, y + s * 0.64f, shadowPaint)
            c.save()
            c.rotate(Math.toDegrees(ang[i].toDouble()).toFloat(), x, y)
            drawIcon(c, e, x, y, s, a)
            c.restore()
            // en calma aparecen los nombres
            if (S.b(S.ZW_LABELS) && calmK > 0.2f && edge[i] == NONE) {
                val n0 = names.getOrPut(e.key) { TextUtils.ellipsize(e.label, namePaint, size * 1.6f, TextUtils.TruncateAt.END).toString() }
                namePaint.alpha = (255f * a * clamp01((calmK - 0.2f) * 1.6f)).toInt()
                c.drawText(n0, x, y + s * 0.5f + dp(15f), namePaint)
            }
        }
    }

    private fun drawField(c: Canvas) {
        linePaint.color = 0xFFB9D8FF.toInt()
        linePaint.strokeWidth = dp(1.3f)
        val top = areaTop()
        val bottom = areaBottom()
        for (k in -3..3) {
            field.reset()
            val wob = sin(tNow * 4f + k) * dp(8f)
            when (magEdge) {
                LEFT, RIGHT -> {
                    val ex = if (magEdge == LEFT) 0f else width.toFloat()
                    val dir = if (magEdge == LEFT) 1f else -1f
                    val cy = (top + bottom) / 2f
                    field.moveTo(ex, cy - dp(30f) * k - dp(12f))
                    field.quadTo(ex + dir * (dp(90f) + abs(k) * dp(34f)) + wob, cy + k * dp(10f), ex, cy + dp(30f) * k + dp(12f))
                }
                else -> {
                    val ey = if (magEdge == TOP) top else bottom
                    val dir = if (magEdge == TOP) 1f else -1f
                    val cx = width / 2f
                    field.moveTo(cx - dp(30f) * k - dp(12f), ey)
                    field.quadTo(cx + k * dp(10f), ey + dir * (dp(90f) + abs(k) * dp(34f)) + wob, cx + dp(30f) * k + dp(12f), ey)
                }
            }
            linePaint.alpha = (150f * magPow * (1f - abs(k) / 4.5f)).toInt()
            c.drawPath(field, linePaint)
        }
    }

    private fun rectOf(i: Int): RectF {
        val s = size
        return RectF(px[i] - s / 2f, py[i] - s / 2f, px[i] + s / 2f, py[i] + s / 2f)
    }

    private fun hit(x: Float, y: Float): Int {
        // de delante hacia atrás
        var best = -1
        var bz = -1f
        for (i in 0 until n) {
            val r = size * 0.62f
            if (abs(x - px[i]) < r && abs(y - py[i]) < r) {
                val z = if (edge[i] != NONE) 2f else zz[i]
                if (z > bz) {
                    bz = z
                    best = i
                }
            }
        }
        return best
    }

    override fun onOpen() {
        // al volver al inicio, una brisa entra por un lado y lo despierta todo
        val side = if (rnd.nextBoolean()) 1f else -1f
        for (g in 0 until 4) {
            val k = gNext
            gNext = (gNext + 1) % gx.size
            gx[k] = if (side > 0) 0f else width.toFloat()
            gy[k] = areaTop() + (areaBottom() - areaTop()) * (g + 0.5f) / 4f
            gdx[k] = side * dp(260f)
            gdy[k] = 0f
            gt[k] = 0.8f
        }
        wake()
    }

    // ================================================================ Toques
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // los bordes de arriba y de abajo siguen siendo del inicio (notificaciones y menú de apps)
                if (y < areaTop() + dp(36f) || y > areaBottom() - dp(48f)) return false
                listener?.onTouchStart()
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
                downX = x
                downY = y
                lastX = x
                lastY = y
                moved = false
                longFired = false
                vortex = false
                downIdx = hit(x, y)
                if (downIdx >= 0) press(list[downIdx])
                uiHandler.removeCallbacks(longRun)
                uiHandler.postDelayed(longRun, longMs)
                wake()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (!moved && (abs(x - downX) > slop || abs(y - downY) > slop)) {
                    moved = true
                    if (!vortex) uiHandler.removeCallbacks(longRun)
                    unpress()
                }
                if (vortex) {
                    // el remolino sigue al dedo
                    vxC = x
                    vyC = y
                } else if (moved) {
                    // ráfaga: el aire sigue al dedo y empuja lo que encuentra
                    vt?.computeCurrentVelocity(1000)
                    val fvx = (vt?.xVelocity ?: 0f).coerceIn(-dp(3000f), dp(3000f))
                    val fvy = (vt?.yVelocity ?: 0f).coerceIn(-dp(3000f), dp(3000f))
                    if (hypot(x - lastX, y - lastY) > dp(14f)) {
                        val k = gNext
                        gNext = (gNext + 1) % gx.size
                        gx[k] = x
                        gy[k] = y
                        gdx[k] = fvx * 0.55f
                        gdy[k] = fvy * 0.55f
                        gt[k] = 1f
                        // una ráfaga fuerte despega las apps pegadas por las que pasa
                        val sp = hypot(fvx, fvy)
                        if (sp > dp(1400f)) {
                            var any = false
                            for (i in 0 until n) if (edge[i] != NONE && hypot(px[i] - x, py[i] - y) < size) {
                                release(i, fvx * 0.6f, fvy * 0.6f)
                                any = true
                            }
                            if (any) {
                                saveStuck()
                                Hx.fire(this, S.HE_ICON)
                            }
                        }
                        lastX = x
                        lastY = y
                    }
                }
                wake()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longRun)
                vt?.recycle()
                vt = null
                unpress()
                val i = downIdx
                downIdx = -1
                if (vortex) {
                    // el remolino se deshace y suelta las apps hacia fuera
                    vortex = false
                    for (j in 0 until n) {
                        if (edge[j] != NONE) continue
                        val ddx = px[j] - vxC
                        val ddy = py[j] - vyC
                        val d = max(dp(20f), hypot(ddx, ddy))
                        if (d < dp(260f)) {
                            vx[j] += ddx / d * dp(380f) * vPow
                            vy[j] += ddy / d * dp(380f) * vPow
                        }
                    }
                } else if (ev.actionMasked == MotionEvent.ACTION_UP && !moved && !longFired && i in 0 until n) {
                    listener?.onLaunch(list[i], rectOf(i))
                } else if (ev.actionMasked == MotionEvent.ACTION_UP && !moved && !longFired && i < 0) {
                    // un toque en el aire: un soplido corto desde ahí
                    val k = gNext
                    gNext = (gNext + 1) % gx.size
                    gx[k] = x
                    gy[k] = y
                    val a = rnd.nextFloat() * 2f * PI.toFloat()
                    gdx[k] = cos(a) * dp(320f)
                    gdy[k] = sin(a) * dp(320f)
                    gt[k] = 0.7f
                }
                wake()
                return true
            }
        }
        return true
    }
}
