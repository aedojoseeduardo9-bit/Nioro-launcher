package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import org.jbox2d.collision.shapes.CircleShape
import org.jbox2d.collision.shapes.PolygonShape
import org.jbox2d.common.Vec2
import org.jbox2d.dynamics.Body
import org.jbox2d.dynamics.BodyDef
import org.jbox2d.dynamics.BodyType
import org.jbox2d.dynamics.FixtureDef
import org.jbox2d.dynamics.World
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Vista 5 - Gravedad: los iconos caen como esferas de cristal con física real (Box2D).
 * Se pueden lanzar con el dedo y, si se activa "Inclinar", rebotan según cómo inclines el móvil.
 * Cuando todo se duerme el bucle se detiene y no gasta batería.
 */
class GravityView(ctx: Context) : AppsView(ctx), SensorEventListener {
    override val iconArea = S.IA_GRAVITY


    private class Ball(val e: AppEntry, val body: Body, val r: Float)

    private var world: World? = null
    private val balls = ArrayList<Ball>()
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var sensing = false
    private var acc = 0f
    private val ppm get() = dp(40f)
    private val grav = Vec2(0f, 18f)

    private var dragBall: Ball? = null
    private var dragX = 0f
    private var dragY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longFired = false
    private var downBall: Ball? = null
    private val longRun = Runnable {
        val b = downBall
        if (!moved && b != null) {
            longFired = true
            lift()
            dragBall = null
            Hx.fire(this, S.HE_LONG)
            val r = RectF()
            ballRect(b, r)
            listener?.onMenu(b.e, r)
        }
    }

    private val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = android.graphics.Color.WHITE
    }
    private val shine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }

    override fun onDataChanged() {
        if (width > 0 && progress > 0.01f) build()
    }

    override fun onOpen() {
        build()
        startSensor()
    }

    override fun onClosed() {
        stopSensor()
        destroy()
    }

    private val imeRebuild = Runnable { if (progress > 0.01f) build() }

    /** el suelo sube sobre el teclado (se reconstruye cuando el teclado termina de moverse) */
    override fun onImeChanged() {
        uiHandler.removeCallbacks(imeRebuild)
        uiHandler.postDelayed(imeRebuild, 160)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (progress > 0.01f) build()
    }

    private fun destroy() {
        ticker.stop()
        world = null
        balls.clear()
        dragBall = null
        downBall = null
    }

    private fun build() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        destroy()
        grav.set(0f, 18f * S.p(S.GRAV_G))
        val wd = World(Vec2(grav.x, grav.y))
        wd.setAllowSleep(true)
        world = wd
        val wm = w / ppm
        val floor = (h - bottomInset - (if (keyboardUp) dp(16f) else dp(104f))) / ppm
        val topWall = (insetTop + dp(60f)) / ppm

        fun wall(cx: Float, cy: Float, hx: Float, hy: Float) {
            val bd = BodyDef()
            bd.type = BodyType.STATIC
            bd.position.set(cx, cy)
            val b = wd.createBody(bd)
            val sh = PolygonShape()
            sh.setAsBox(hx, hy)
            val fd = FixtureDef()
            fd.shape = sh
            fd.friction = 0.35f
            fd.restitution = 0.2f
            b.createFixture(fd)
        }
        wall(wm / 2f, floor + 1f, wm, 1f)               // suelo
        wall(-1f, floor / 2f - 40f, 1f, floor / 2f + 60f) // pared izquierda
        wall(wm + 1f, floor / 2f - 40f, 1f, floor / 2f + 60f) // pared derecha

        val r = dp(Prefs.iconDp.toFloat() * 0.5f - 1f) * S.p(S.GRAV_SIZE) / ppm
        val perRow = (wm / (r * 2.2f)).toInt().coerceAtLeast(2)
        // solo las esferas que caben en pantalla (el resto se encuentra con la búsqueda)
        val fitRows = ((floor - topWall) / (r * 2f * 0.95f)).toInt().coerceAtLeast(1)
        val cap = min(S.i(S.GRAV_MAX), perRow * fitRows)
        val list = if (shown.size > cap) shown.subList(0, cap) else shown
        for ((i, e) in list.withIndex()) {
            val col = i % perRow
            val row = i / perRow
            val bd = BodyDef()
            bd.type = BodyType.DYNAMIC
            val jitter = ((i * 7919) % 100) / 100f - 0.5f
            bd.position.set(
                (col + 0.5f) * (wm / perRow) + jitter * r * 0.5f,
                topWall - 2f - row * r * 2.3f - (i % 3) * r * 0.5f
            )
            bd.angle = jitter
            bd.angularDamping = 0.35f
            bd.linearDamping = 0.05f
            val b = wd.createBody(bd)
            val sh = CircleShape()
            sh.radius = r
            val fd = FixtureDef()
            fd.shape = sh
            fd.density = 1f
            fd.friction = S.p(S.GRAV_FRICTION)
            fd.restitution = S.p(S.GRAV_BOUNCE)
            b.createFixture(fd)
            balls.add(Ball(e, b, r))
        }
        ticker.kick()
    }

    private fun startSensor() {
        if (!Prefs.tilt || sensing) return
        val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensing = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopSensor() {
        if (sensing) {
            sm.unregisterListener(this)
            sensing = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(ev: SensorEvent) {
        val sens = 1.8f * S.p(S.GRAV_TILT)
        val g = S.p(S.GRAV_G)
        val gx = -ev.values[0] * sens * g
        val gy = (ev.values[1] * sens * g).coerceIn(4f * g, 28f * g)
        if (abs(gx - grav.x) + abs(gy - grav.y) > 0.6f) {
            grav.set(gx, gy)
            world?.gravity = Vec2(gx, gy)
            wakeAll()
            ticker.kick()
        }
    }

    private fun wakeAll() {
        for (b in balls) b.body.isAwake = true
    }

    override fun onFrame(dt: Float): Boolean {
        val wd = world ?: return false
        acc += dt
        var steps = 0
        while (acc >= 1f / 60f && steps < 3) {
            val d = dragBall
            if (d != null) {
                val p = d.body.position
                val tx = dragX / ppm
                val ty = dragY / ppm
                d.body.isAwake = true
                val th = 16f * S.p(S.GRAV_THROW)
                d.body.setLinearVelocity(Vec2((tx - p.x) * th, (ty - p.y) * th))
                d.body.angularVelocity = d.body.angularVelocity * 0.9f
            }
            wd.step(1f / 60f, 6, 2)
            acc -= 1f / 60f
            steps++
        }
        if (steps == 3) acc = 0f
        invalidate()
        if (dragBall != null) return true
        for (b in balls) if (b.body.isAwake) return true
        return false
    }

    private fun ballRect(b: Ball, out: RectF) {
        val p = b.body.position
        val cx = p.x * ppm
        val cy = p.y * ppm
        val r = b.r * ppm
        out.set(cx - r, cy - r, cx + r, cy + r)
    }

    override fun onDraw(c: Canvas) {
        val a = clamp01(progress * 1.6f)
        for (bi in balls.indices) {
            val b = balls[bi]
            val p = b.body.position
            val cx = p.x * ppm
            val cy = p.y * ppm
            if (cy < -b.r * ppm * 2f) continue
            val q = clamp01(progress * 1.8f - bi * 0.012f)
            val tq = q - 1f
            val size = b.r * ppm * 2f * (1f + 2.7f * tq * tq * tq + 1.7f * tq * tq)
            c.save()
            c.rotate(Math.toDegrees(b.body.angle.toDouble()).toFloat(), cx, cy)
            drawIcon(c, b.e, cx, cy, size, a)
            c.restore()
            // borde y reflejo fijos (no giran con la esfera): parecen bolas de cristal
            val r = size / 2f
            rim.strokeWidth = dp(1.2f)
            rim.alpha = (70 * a).toInt()
            c.drawCircle(cx, cy, r * 0.97f, rim)
            shine.alpha = (55 * a).toInt()
            c.drawCircle(cx - r * 0.36f, cy - r * 0.42f, r * 0.16f, shine)
        }
    }

    private fun hit(x: Float, y: Float): Ball? {
        var best: Ball? = null
        var bd = Float.MAX_VALUE
        for (b in balls) {
            val p = b.body.position
            val d = hypot(x - p.x * ppm, y - p.y * ppm)
            if (d < b.r * ppm * 1.1f && d < bd) {
                bd = d
                best = b
            }
        }
        return best
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                listener?.onTouchStart()
                downX = x
                downY = y
                dragX = x
                dragY = y
                moved = false
                longFired = false
                downBall = hit(x, y)
                uiHandler.removeCallbacks(longRun)
                unpress()
                if (downBall != null) uiHandler.postDelayed(longRun, longMs)
                press(downBall?.e)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (longFired) return true
                dragX = x
                dragY = y
                if (!moved && (abs(x - downX) > slop || abs(y - downY) > slop)) {
                    moved = true
                    uiHandler.removeCallbacks(longRun)
                unpress()
                    dragBall = downBall
                    if (dragBall != null) ticker.kick()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longRun)
                unpress()
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                val d = dragBall
                dragBall = null
                if (d != null) {
                    // se suelta con la velocidad actual y la física continúa
                    ticker.kick()
                } else if (!moved && !longFired && up) {
                    val b = downBall
                    if (b != null) {
                        val r = RectF()
                        ballRect(b, r)
                        listener?.onLaunch(b.e, r)
                    } else {
                        listener?.onClose()
                    }
                } else if (moved && downBall == null && up && y - downY > dp(110f) && S.b(S.DR_SWIPE_CLOSE)) {
                    listener?.onClose()
                }
                downBall = null
                return true
            }
        }
        return true
    }
}
