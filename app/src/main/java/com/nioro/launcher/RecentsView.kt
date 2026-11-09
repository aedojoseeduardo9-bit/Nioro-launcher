package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Apps en segundo plano. 5 estilos:
 * 0 Carrusel (One UI), 1 Pila 3D (iOS), 2 Cover Flow, 3 Rejilla (Exposé de macOS), 4 Burbujas.
 * Deslizar una tarjeta hacia arriba la quita de la lista.
 */
class RecentsView(ctx: Context) : View0(ctx) {

    interface Listener {
        fun onLaunch(e: AppEntry, from: RectF)
        fun onClose()
        fun onOpenAll()
    }

    var listener: Listener? = null
    var progress = 0f
    var insetTop = 0f
    var insetBottom = 0f

    private var items = ArrayList<AppEntry>()
    private val ticker = Ticker { dt -> onFrame(dt) }
    private val pos = ticker.add(SpringValue(0f, 200f, 26f, 0.0005f))
    private val gridScroll = Scroll1D(ticker)
    private val dismissY = ticker.add(SpringValue(0f, 260f, 30f, 0.5f))
    private val closeAllSp = ticker.add(SpringValue(0f, 180f, 22f, 0.002f))
    private var closingAll = false
    private var dismissIdx = -1
    private val glass = Glass()
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = Paint(Paint.ANTI_ALIAS_FLAG)
    private val big = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val r2 = RectF()
    private val btn = RectF()
    private val hdr = RectF()
    private var style = 0

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startPos = 0f
    private var mode = 0 // 0 nada, 1 desplazar, 2 descartar
    private var hitIdx = -1
    private var wasMoving = false
    private var vt: VelocityTracker? = null
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    init {
        title.color = Color.WHITE
        title.textSize = dp(14f)
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        big.color = Color.WHITE
        big.textSize = dp(22f)
        big.textAlign = Paint.Align.CENTER
        hint.color = 0xCCFFFFFF.toInt()
        hint.textSize = dp(14f)
        hint.textAlign = Paint.Align.CENTER
    }

    // ---------------- Burbujas: cada una flota, se agarra con el dedo y estalla al mantenerla ----------------
    /** [slot] = su sitio fijo en la espiral: al quitar otra burbuja, las demás no cambian de sitio */
    private class Bub(val key: String, val slot: Int) {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var placed = false
        /** soltada por el usuario: se queda donde la dejó (ax, ay) en vez de volver a la espiral */
        var pinned = false
        var ax = 0f
        var ay = 0f
        /** lanzada con fuerza: vuela sin resorte y, al salir de la pantalla, se cierra */
        var flying = false
        /** gelatina: tiembla al soltarla o al chocar (estira a lo ancho y encoge a lo alto) */
        var wob = 0f
        var wobV = 0f
        /** velocidad suavizada mientras el dedo la arrastra (para estirarla en esa dirección) */
        var gvx = 0f
        var gvy = 0f
        var lastX = 0f
        var lastY = 0f
        val phase = (key.hashCode() and 0xFFFF) / 65535f * 6.2832f
        val speed = 0.75f + ((key.hashCode() ushr 8) and 0xFF) / 255f * 0.5f
    }

    /**
     * Estallido de una burbuja eliminada. Fases (t de 0 a 1 en ~0,6 s): se hincha un poco más,
     * revienta con un destello, un anillo se abre y gotas de distintos tamaños salen despedidas y caen.
     */
    private class Pop(val x: Float, val y: Float, val size: Float, val start: Float, val e: AppEntry) {
        var t = 0f
        val n = 11
        val ang = FloatArray(n)
        val spd = FloatArray(n)
        val sz = FloatArray(n)

        init {
            // gotas repartidas alrededor con un poco de desorden (siempre igual para la misma app)
            var h = e.key.hashCode()
            for (i in 0 until n) {
                h = h * 1103515245 + 12345
                val r1 = ((h ushr 8) and 0xFFFF) / 65535f
                h = h * 1103515245 + 12345
                val r2 = ((h ushr 8) and 0xFFFF) / 65535f
                ang[i] = i * (6.2832f / n) + (r1 - 0.5f) * 0.5f
                spd[i] = 0.55f + r2 * 0.7f
                sz[i] = 0.6f + ((r1 + r2) * 0.5f) * 0.9f
            }
        }
    }

    private val bubs = ArrayList<Bub>()
    private val pops = ArrayList<Pop>()
    private var floatT = 0f
    /** burbuja agarrada (-1 = ninguna) y dónde la tomó el dedo */
    private var grab = -1
    private var grabDX = 0f
    private var grabDY = 0f
    private val grabLift = ticker.add(SpringValue(1f, 420f, 26f, 0.001f))
    /** mantener pulsada: la burbuja se va hinchando y tiembla hasta estallar */
    private val holdSp = ticker.add(SpringValue(0f, 380f, 20f, 0.002f))
    private var holdIdx = -1
    private var holdVis = -1
    private var holdStart = 0L
    private val bubArt = DockArt()
    private val bubMat = GlassMat()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var longFired = false
    private val bubbleLong = Runnable {
        val i = hitIdx
        if (style == 4 && mode == 0 && i in items.indices) {
            longFired = true
            releasePress()
            removeBubble(i, true)
        }
    }

    private fun easeOut(t: Float): Float {
        val k = 1f - clamp01(t)
        return 1f - k * k * k
    }

    /** curva de "globo": pasa un poco de su tamaño y vuelve (como iOS al aparecer) */
    private fun popCurve(q: Float): Float {
        val t = clamp01(q) - 1f
        return 1f + 2.7f * t * t * t + 1.7f * t * t
    }

    /** suelta la pulsación larga: la burbuja se deshincha con un resorte */
    private fun endHold() {
        if (holdIdx < 0) return
        holdIdx = -1
        Mo.tune(holdSp, S.MO_RELEASE, 380f, 0.5f)
        holdSp.to(0f)
        ticker.kick()
    }

    /** sitio de la burbuja i en la espiral (cx, cy) y su tamaño */
    private var homeX = 0f
    private var homeY = 0f
    private var homeS = 0f
    private fun bubbleHome(i: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = (insetTop + h - insetBottom) / 2f - dp(10f)
        // su sitio de siempre (no el puesto en la lista): si estalla otra, esta no se mueve
        val sl = bubs.getOrNull(i)?.slot ?: i
        val ang = sl * 2.39996f + pos.value * 0.6f
        val rad = dp(46f) + sqrtI(sl) * dp(58f)
        homeX = w / 2f + kotlin.math.cos(ang) * rad
        homeY = cy + sin(ang) * rad * 1.1f
        homeS = dp(84f) * (1f - min(0.45f, sl * 0.03f))
    }

    /** un paso de la física de las burbujas: resorte hacia su sitio + flotación + empujarse entre ellas */
    private fun stepBubbles(dt0: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val dt = min(dt0, 0.033f) / 2f
        val fa = dp(9f)
        // la agarrada: velocidad del dedo suavizada (para estirarla hacia donde va)
        bubs.getOrNull(grab)?.let { g ->
            val inv = 1f / max(0.004f, dt0)
            g.gvx = lerp(g.gvx, (g.x - g.lastX) * inv, 0.35f)
            g.gvy = lerp(g.gvy, (g.y - g.lastY) * inv, 0.35f)
            g.lastX = g.x
            g.lastY = g.y
        }
        repeat(2) {
            // gelatina: un resorte poco amortiguado que vuelve a la forma redonda temblando
            for (b in bubs) {
                b.wobV += (-b.wob * 320f - b.wobV * 7f) * dt
                b.wob += b.wobV * dt
            }
            for (i in bubs.indices) {
                val b = bubs[i]
                bubbleHome(i)
                if (!b.placed) {
                    b.x = homeX
                    b.y = homeY
                    b.placed = true
                }
                if (i == grab) continue
                if (b.flying) {
                    // lanzada: casi sin freno hasta salir de la pantalla
                    b.vx *= 1f - 0.4f * dt
                    b.vy *= 1f - 0.4f * dt
                } else {
                    // flotar: cada burbuja se mece a su ritmo alrededor de su sitio
                    val tx = (if (b.pinned) b.ax else homeX) + sin(floatT * 1.05f * b.speed + b.phase) * fa
                    val ty = (if (b.pinned) b.ay else homeY) + kotlin.math.cos(floatT * 0.85f * b.speed + b.phase * 1.3f) * fa * 1.3f
                    b.vx += ((tx - b.x) * 26f - b.vx * 7.5f) * dt
                    b.vy += ((ty - b.y) * 26f - b.vy * 7.5f) * dt
                }
            }
            // se apartan cuando se tocan (la agarrada empuja a las demás)
            for (i in bubs.indices) for (j in i + 1 until bubs.size) {
                val a = bubs[i]
                val b = bubs[j]
                if (a.flying || b.flying) continue
                bubbleHome(i)
                val si = homeS
                bubbleHome(j)
                val sj = homeS
                val dx = b.x - a.x
                val dy = b.y - a.y
                val d = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.5f)
                val minD = (si + sj) / 2f + dp(8f)
                if (d < minD) {
                    val push = (minD - d) * 90f * dt
                    val nx = dx / d
                    val ny = dy / d
                    val ka = if (i == grab) 0f else if (j == grab) 2f else 1f
                    val kb = if (j == grab) 0f else if (i == grab) 2f else 1f
                    a.vx -= nx * push * ka
                    a.vy -= ny * push * ka
                    b.vx += nx * push * kb
                    b.vy += ny * push * kb
                    // al chocar se aplastan un poco, como gelatina
                    val jig = min(0.9f, push * 0.012f)
                    a.wobV += jig * ka
                    b.wobV += jig * kb
                }
            }
            for (i in bubs.indices) {
                val b = bubs[i]
                if (i == grab) continue
                b.x += b.vx * dt
                b.y += b.vy * dt
                if (!b.flying) {
                    // no se salen de la pantalla: rebotan suave en los bordes
                    bubbleHome(i)
                    val r0 = homeS / 2f
                    val l = r0 + dp(4f)
                    val rgt = w - r0 - dp(4f)
                    val top = insetTop + r0 + dp(8f)
                    val bot = h - insetBottom - dp(96f) - r0
                    if (b.x < l) { b.x = l; b.vx = abs(b.vx) * 0.4f }
                    if (b.x > rgt) { b.x = rgt; b.vx = -abs(b.vx) * 0.4f }
                    if (b.y < top) { b.y = top; b.vy = abs(b.vy) * 0.4f }
                    if (bot > top && b.y > bot) { b.y = bot; b.vy = -abs(b.vy) * 0.4f }
                }
            }
        }
        // las lanzadas que ya salieron de la pantalla se cierran
        for (i in bubs.indices.reversed()) {
            val b = bubs[i]
            if (b.flying && (b.x < -dp(120f) || b.x > w + dp(120f) || b.y < -dp(120f) || b.y > h + dp(120f))) removeBubble(i, false)
        }
        for (p in pops) p.t += dt0 / 0.62f
        pops.removeAll { it.t >= 1f }
    }

    private fun onFrame(dt: Float): Boolean {
        invalidate()
        if (style != 4 || !isShown || progress <= 0.01f) return false
        if (items.isEmpty() && pops.isEmpty()) return false
        floatT += dt
        if (holdIdx >= 0) holdSp.snap(clamp01((android.os.SystemClock.uptimeMillis() - holdStart) / max(1f, S.f(S.G_LONG_MS))))
        stepBubbles(dt)
        // mientras las burbujas se ven, flotan (al cerrar recientes se detiene solo)
        return true
    }

    /** quita la burbuja i: con [pop] estalla en su sitio; sin él (lanzada fuera) solo desaparece */
    private fun removeBubble(i: Int, pop: Boolean) {
        if (i !in items.indices) return
        val b = bubs[i]
        val e = items[i]
        if (pop) {
            bubbleHome(i)
            val start = 1f + 0.16f * easeOut(if (holdVis == i) holdSp.value else 0f)
            pops.add(Pop(b.x, b.y, homeS, start, e))
            Hx.fire(this, S.HE_LONG)
            // onda del estallido: las burbujas cercanas se apartan un poco y tiemblan
            for ((k, o) in bubs.withIndex()) {
                if (k == i || o.flying) continue
                val dx = o.x - b.x
                val dy = o.y - b.y
                val d = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val f = clamp01(1f - d / dp(240f))
                if (f <= 0f) continue
                o.vx += dx / d * 1000f * f
                o.vy += dy / d * 1000f * f
                o.wobV += 2.2f * f
            }
        } else {
            Hx.fire(this, S.HE_CLOSEALL)
        }
        items.removeAt(i)
        bubs.removeAt(i)
        if (holdVis == i) {
            holdVis = -1
            holdSp.snap(0f)
        } else if (holdVis > i) holdVis--
        if (holdIdx == i) holdIdx = -1 else if (holdIdx > i) holdIdx--
        if (grab == i) grab = -1 else if (grab > i) grab--
        if (hitIdx == i) hitIdx = -1 else if (hitIdx > i) hitIdx--
        if (pressIdx == i) pressIdx = -1 else if (pressIdx > i) pressIdx--
        Recents.forget(e.key)
        // (el giro de la espiral no se toca: así ninguna otra burbuja se mueve de su sitio)
        ticker.kick()
        invalidate()
        // la última: se deja ver el estallido entero antes de cerrar recientes
        if (items.isEmpty()) postDelayed({ listener?.onClose() }, if (pop) 640L else 300L)
    }

    fun setItems(list: List<AppEntry>) {
        items = ArrayList(list)
        style = Prefs.recentsAnim
        bubs.clear()
        for ((k, e) in items.withIndex()) bubs.add(Bub(e.key, k))
        pops.clear()
        grab = -1
        floatT = 0f
        grabLift.snap(1f)
        Tx.apply(title, S.TX_RECENT, dp(14f), 500, 0f, 0)
        Tx.apply(hint, S.TX_CLOSEALL, dp(14f), 500, 0f, 0)
        Mo.tune(pos, S.MO_RECENTS_CAROUSEL, 200f, 0.92f)
        Mo.tune(dismissY, S.MO_DISMISS, 260f, 0.93f)
        closingAll = false
        closeAllSp.snap(0f)
        pos.snap(0f)
        gridScroll.jump(0f)
        dismissIdx = -1
        layoutGrid()
        invalidate()
    }

    // ---------------- Geometría ----------------
    private fun cardW(): Float = S.p(S.RECS_BASE + style * 3) * when (style) {
        0 -> width * 0.66f
        1 -> width * 0.78f
        2 -> width * 0.56f
        else -> width * 0.42f
    }
    private val gapK: Float get() = S.p(S.RECS_BASE + style * 3 + 1)
    private val tiltK: Float get() = S.p(S.RECS_BASE + style * 3 + 2)

    private fun cardH(): Float = cardW() * (if (style == 4) 1f else 1.72f)
    private fun spacing(): Float = when (style) {
        0 -> (cardW() + dp(18f)) * gapK
        1 -> cardW() * 0.55f * gapK
        2 -> cardW() * 0.6f * gapK
        else -> (cardW() + dp(30f)) * gapK
    }

    private fun gridCols(): Int = 2
    private fun gridCellH(): Float = cardW() * 1.5f + dp(14f)

    private fun layoutGrid() {
        if (style != 3) return
        val rows = (items.size + 1) / 2
        val top = insetTop + dp(80f)
        val vis = height - insetBottom - dp(110f) - top
        gridScroll.min = 0f
        gridScroll.max = max(0f, rows * gridCellH() - vis)
        gridScroll.dim = vis
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutGrid()
    }

    /** Rectángulo de la tarjeta i en el estado actual. Devuelve escala/alpha/rotación vía campos. */
    private var outA = 1f
    private var outRot = 0f
    private fun cardRect(i: Int, out: RectF): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        val cw = cardW()
        val ch = cardH()
        val cy = (insetTop + h - insetBottom) / 2f - dp(10f)
        // tras quitar una tarjeta, las de después empiezan donde estaban y se deslizan al hueco
        val o = i - pos.value + (if (removedAt in 0..i) gapShift.value else 0f)
        val p = progress
        outA = 1f
        outRot = 0f
        var sc = 1f
        var cx = w / 2f
        var y = cy
        when (style) {
            0 -> {
                cx = w / 2f + o * spacing()
                sc = 1f - min(1f, abs(o)) * 0.08f * tiltK
            }
            1 -> {
                // pila: las tarjetas anteriores se apilan a la izquierda
                val sp = spacing()
                cx = if (o >= 0) w / 2f + o * w * 0.9f else w / 2f + o * sp * 0.35f
                sc = if (o >= 0) 1f else 1f - min(4f, -o) * 0.05f * tiltK
                outA = if (o < -4f) 0f else 1f
                outRot = 0f
            }
            2 -> {
                cx = w / 2f + o * spacing()
                outRot = (-o * 55f * tiltK).coerceIn(-80f, 80f)
                sc = 1f - min(1f, abs(o)) * 0.18f
            }
            3 -> {
                val col = i % gridCols()
                val row = i / gridCols()
                val gw = w / gridCols()
                cx = col * gw + gw / 2f
                y = insetTop + dp(80f) + row * gridCellH() + cw * 0.75f - gridScroll.value
                val ch2 = cw * 1.5f
                val q = clamp01(p * 1.6f - (i % 6) * 0.06f)
                sc = lerp(0.7f, 1f, q)
                outA = clamp01(q * 1.5f)
                out.set(cx - cw / 2f * sc, y - ch2 / 2f * sc, cx + cw / 2f * sc, y + ch2 / 2f * sc)
                if (i == dismissIdx) applyDismiss(out)
                if (closingAll) {
                    out.offset(0f, -closeAllSp.value * h * 0.7f)
                    outA *= 1f - clamp01(closeAllSp.value * 1.3f)
                }
                return out.bottom > 0 && out.top < h
            }
            else -> {
                // burbujas: flotan alrededor de su sitio en la espiral (o donde las dejaste)
                bubbleHome(i)
                val b = bubs.getOrNull(i)
                cx = if (b != null && b.placed) b.x else homeX
                y = if (b != null && b.placed) b.y else homeY
                // respiran un poco y la agarrada se levanta
                val breathe = 1f + 0.018f * sin(floatT * 1.3f + (b?.phase ?: 0f))
                var s = homeS * breathe * (if (i == grab) grabLift.value else 1f)
                if (i == holdVis) {
                    // mantenida: se va hinchando y tiembla cada vez más, avisando de que va a estallar
                    val k = holdSp.value
                    s *= 1f + 0.16f * easeOut(k)
                    val jit = dp(1.6f) * k * k
                    cx += sin(floatT * 57f) * jit
                    y += kotlin.math.cos(floatT * 49f) * jit
                }
                // entrada: suben desde abajo una tras otra y se inflan con un pequeño rebote
                val q = clamp01(p * 1.5f - i * 0.05f)
                y += (1f - q) * (1f - q) * dp(70f)
                val ss = s * (0.25f + 0.75f * popCurve(q))
                outA = clamp01(q * 2f)
                out.set(cx - ss / 2f, y - ss / 2f, cx + ss / 2f, y + ss / 2f)
                if (i == dismissIdx) applyDismiss(out)
                if (closingAll) {
                    out.offset(0f, -closeAllSp.value * h * 0.7f)
                    outA *= 1f - clamp01(closeAllSp.value * 1.3f)
                }
                return true
            }
        }
        // entrada: crece desde el centro con un pequeño retraso por distancia
        val q = clamp01(p * 1.4f - abs(o) * 0.08f)
        val enter = lerp(0.82f, 1f, q)
        sc *= enter
        outA *= clamp01(q * 1.6f)
        out.set(cx - cw / 2f * sc, y - ch / 2f * sc, cx + cw / 2f * sc, y + ch / 2f * sc)
        if (i == dismissIdx) applyDismiss(out)
        if (closingAll) {
            out.offset(0f, -closeAllSp.value * h * (0.6f + abs(o) * 0.1f))
            outA *= 1f - clamp01(closeAllSp.value * 1.3f)
        }
        return out.right > -w * 0.2f && out.left < w * 1.2f
    }

    private fun sqrtI(i: Int): Float = kotlin.math.sqrt(i.toFloat())

    /** la tarjeta que se arrastra hacia arriba se encoge y se desvanece mientras sube */
    private fun applyDismiss(out: RectF) {
        val dy = dismissY.value
        out.offset(0f, dy)
        val k = clamp01(-dy / (height * 0.45f))
        if (k > 0f) {
            val ix = out.width() * 0.1f * k
            val iy = out.height() * 0.1f * k
            out.inset(ix, iy)
            outA *= 1f - 0.7f * k
        }
    }

    /** tarjeta pulsada: se hunde un poco antes de abrirse */
    private val pressK = ticker.add(SpringValue(1f, 600f, 40f, 0.0005f))
    private var pressIdx = -1

    override fun onDraw(c: Canvas) {
        val a = clamp01(progress * 1.6f)
        if (items.isEmpty() && style == 4 && pops.isNotEmpty()) {
            // la última burbuja está estallando: se ve el estallido y luego se cierra
            if (!ticker.isRunning) ticker.kick()
            drawPops(c, a)
            return
        }
        if (items.isEmpty()) {
            // estado vacío: icono de recientes en cristal sobre el texto
            r.set(width / 2f - dp(38f), height / 2f - dp(124f), width / 2f + dp(38f), height / 2f - dp(48f))
            glass.draw(c, r, dp(38f), a * 0.85f, true, S.SF_RECENT_CARD)
            Glyphs.draw(c, Glyphs.RECENTS, r.centerX(), r.centerY(), dp(36f), Color.WHITE, a)
            big.alpha = (255 * a).toInt()
            c.drawText("No hay apps recientes", width / 2f, height / 2f, big)
            hint.alpha = (200 * a).toInt()
            c.drawText("Toca para volver", width / 2f, height / 2f + dp(30f), hint)
            return
        }
        // orden de dibujo: en estilos 3D, la tarjeta central encima
        val order = ArrayList<Int>(items.size)
        for (i in items.indices) order.add(i)
        if (style == 2 || style == 0) order.sortByDescending { abs(it - pos.value) }
        if (style == 4) {
            // burbujas: flotan mientras se ven (el bucle se para solo al cerrar)
            if (!ticker.isRunning && progress > 0.01f) ticker.kick()
            // la burbuja agarrada va por encima de las demás
            if (grab in order.indices) {
                order.remove(grab)
                order.add(grab)
            }
            bubMat.reset()
            if (!bubMat.fromSurface(S.SF_RECENT_CARD)) bubMat.visible = false
        }
        for (i in order) {
            if (!cardRect(i, r)) continue
            val al = outA * a
            if (al <= 0.01f) continue
            val e = items[i]
            c.save()
            if (outRot != 0f) Rot3D.apply(c, outRot, r.centerX(), r.centerY())
            if (i == pressIdx && pressK.value != 1f) c.scale(pressK.value, pressK.value, r.centerX(), r.centerY())
            if (style == 4) {
                val b = bubs.getOrNull(i)
                val bx = r.centerX()
                val by = r.centerY()
                if (b != null) {
                    // se estira hacia donde se mueve (más al arrastrarla o lanzarla)
                    val mvx = if (i == grab) b.gvx else b.vx
                    val mvy = if (i == grab) b.gvy else b.vy
                    val st = clamp01(kotlin.math.hypot(mvx, mvy) / 3800f) * 0.16f
                    if (st > 0.004f) {
                        val ang = Math.toDegrees(kotlin.math.atan2(mvy, mvx).toDouble()).toFloat()
                        c.rotate(ang, bx, by)
                        c.scale(1f + st, 1f - st * 0.7f, bx, by)
                        c.rotate(-ang, bx, by)
                    }
                    val wb = b.wob.coerceIn(-0.14f, 0.14f)
                    if (abs(wb) > 0.002f) c.scale(1f + wb, 1f - wb, bx, by)
                }
                drawBubble(c, r, al)
                // el icono se mece un poco dentro de la burbuja
                val tilt = if (b != null) sin(floatT * 0.6f * b.speed + b.phase) * 3f else 0f
                c.save()
                c.rotate(tilt, bx, by)
                drawIcon(c, e, bx, by, r.width() * 0.62f, al)
                c.restore()
            } else {
                drawCard(c, e, r, al)
            }
            c.restore()
        }
        if (style == 4) drawPops(c, a)
        // botón "Ver todo" abajo
        if (!S.b(S.RC_CLOSEALL)) {
            btn.setEmpty()
            return
        }
        btn.set(width / 2f - dp(80f), height - insetBottom - dp(78f), width / 2f + dp(80f), height - insetBottom - dp(34f))
        glass.draw(c, btn, dp(22f), a, true, S.SF_RECENT_BTN)
        hint.alpha = Tx.alpha(S.TX_CLOSEALL, a)
        c.drawText(Tx.str(S.TX_CLOSEALL, "Cerrar todo"), btn.centerX(), btn.centerY() + dp(5f) + Tx.dy(S.TX_CLOSEALL), hint)
    }

    /**
     * Cristal de una burbuja. Se graba una vez en la GPU y luego solo se escala y se mueve: así pueden
     * flotar todas a la vez sin redibujar sus formas en cada fotograma.
     */
    private fun drawBubble(c: Canvas, rr: RectF, al: Float) {
        if (!bubMat.visible) return
        val size = rr.width() / 1.2f // drawTile usa un cristal 1,2 veces el tamaño del icono
        if (al >= 0.99f) {
            bubArt.drawTile(c, DockDesigns.F_BUBBLES, rr.centerX(), rr.centerY(), size, 1f, bubMat)
        } else {
            // varias burbujas comparten la misma imagen guardada: la transparencia de cada una va aparte
            val pad = dp(18f)
            c.saveLayerAlpha(rr.left - pad, rr.top - pad, rr.right + pad, rr.bottom + pad * 1.5f, (255 * clamp01(al)).toInt())
            bubArt.drawTile(c, DockDesigns.F_BUBBLES, rr.centerX(), rr.centerY(), size, 1f, bubMat)
            c.restore()
        }
    }

    /**
     * Estallido, por fases (t de 0 a 1):
     *  0,00–0,22  la burbuja se hincha un poco más y la piel tiembla;
     *  0,22       revienta: destello suave y la burbuja desaparece;
     *  0,22–1,00  un anillo se abre y se afina, las gotas salen despedidas, frenan y caen un poco,
     *             y el icono se encoge girando mientras se desvanece.
     */
    private fun drawPops(c: Canvas, a: Float) {
        val burst = 0.22f
        for (p in pops) {
            val t = clamp01(p.t)
            val rad = p.size / 2f
            if (t < burst) {
                // 1) se hincha (continúa desde lo que se hinchó al mantenerla) y la piel tiembla
                val q = t / burst
                val sc = p.start * (1f + 0.12f * easeOut(q))
                val wob = sin(q * 18f) * 0.03f * q
                val sz = p.size * sc
                r2.set(p.x - sz / 2f, p.y - sz / 2f, p.x + sz / 2f, p.y + sz / 2f)
                c.save()
                c.scale(1f + wob, 1f - wob, p.x, p.y)
                drawBubble(c, r2, a)
                drawIcon(c, p.e, p.x, p.y, sz * 0.62f, a)
                c.restore()
                continue
            }
            val u = (t - burst) / (1f - burst) // 0 → 1 tras reventar
            val br = rad * p.start * 1.12f      // radio en el momento de reventar
            // 2) destello suave justo al reventar
            if (u < 0.35f) {
                val f = 1f - u / 0.35f
                dropPaint.color = Color.WHITE
                dropPaint.alpha = (120 * a * f * f).toInt().coerceIn(0, 255)
                c.drawCircle(p.x, p.y, br * (1f + u * 0.5f), dropPaint)
            }
            // 3) anillo que se abre rápido y luego frena, cada vez más fino
            val ring = easeOut(u)
            ringPaint.color = Color.WHITE
            ringPaint.strokeWidth = dp(3.2f) * (1f - ring) + dp(0.4f)
            ringPaint.alpha = (210 * a * (1f - u) * (1f - u)).toInt().coerceIn(0, 255)
            c.drawCircle(p.x, p.y, br * (1f + ring * 0.85f), ringPaint)
            // 4) gotas: salen despedidas, frenan, caen un poco y se encogen
            dropPaint.color = Color.WHITE
            for (d in 0 until p.n) {
                val go = easeOut(u) * p.spd[d]
                val dist = br * (0.95f + go * 1.1f)
                val fall = u * u * dp(46f) * p.spd[d]
                val dx = kotlin.math.cos(p.ang[d]) * dist
                val dy = sin(p.ang[d]) * dist + fall
                val ds = dp(3.4f) * p.sz[d] * (1f - u * 0.75f)
                dropPaint.alpha = (230 * a * (1f - u)).toInt().coerceIn(0, 255)
                c.drawCircle(p.x + dx, p.y + dy, ds, dropPaint)
                // brillo pequeño en cada gota (parecen de agua)
                if (ds > dp(1.6f)) {
                    dropPaint.alpha = (255 * a * (1f - u)).toInt().coerceIn(0, 255)
                    c.drawCircle(p.x + dx - ds * 0.3f, p.y + dy - ds * 0.3f, ds * 0.35f, dropPaint)
                }
            }
            // 5) el icono se encoge girando y se desvanece
            if (u < 0.6f) {
                val k = u / 0.6f
                c.save()
                c.rotate(k * 40f, p.x, p.y)
                drawIcon(c, p.e, p.x, p.y, p.size * 0.62f * p.start * (1f - easeOut(k) * 0.85f), a * (1f - k))
                c.restore()
            }
        }
    }

    private fun drawCard(c: Canvas, e: AppEntry, rr: RectF, al: Float) {
        val k = rr.width() / max(1f, cardW())
        val rad = dp(28f) * k
        // cuerpo: color del icono + cristal encima (se ve como una ventana esmerilada de la app)
        card.color = e.tint
        card.alpha = (190 * al).toInt()
        c.drawRoundRect(rr, rad, rad, card)
        glass.draw(c, rr, rad, al, true, S.SF_RECENT_CARD)
        // "contenido" sugerido: barras de cristal suaves
        hdr.set(rr.left + dp(16f) * k, rr.top + dp(18f) * k, rr.right - dp(16f) * k, rr.top + dp(30f) * k)
        card.color = 0x33FFFFFF
        card.alpha = (70 * al).toInt()
        c.drawRoundRect(hdr, dp(6f), dp(6f), card)
        hdr.set(rr.left + dp(16f) * k, rr.bottom - dp(46f) * k, rr.right - dp(60f) * k, rr.bottom - dp(34f) * k)
        c.drawRoundRect(hdr, dp(6f), dp(6f), card)
        val isz = min(rr.width() * 0.4f, dp(80f))
        drawIcon(c, e, rr.centerX(), rr.centerY(), isz, al)
        // cabecera estilo iOS: icono pequeño + nombre encima de la tarjeta
        if (!S.b(S.RC_HEADER)) return
        val hs = dp(24f) * min(1f, k + 0.2f)
        val hy = rr.top - dp(10f) - hs / 2f
        drawIcon(c, e, rr.left + dp(4f) + hs / 2f, hy, hs, al)
        title.alpha = Tx.alpha(S.TX_RECENT, al)
        title.textAlign = Paint.Align.LEFT
        c.drawText(Tx.str(S.TX_RECENT, e.label), rr.left + dp(12f) + hs, hy + dp(5f) + Tx.dy(S.TX_RECENT), title)
    }

    private fun drawIcon(c: Canvas, e: AppEntry, cx: Float, cy: Float, size: Float, a: Float) {
        val b = e.icon ?: return
        IconFx.draw(c, b, e.tint, cx, cy, size * IconFx.scale(S.IA_RECENTS), a, S.IA_RECENTS)
    }


    private fun hit(x: Float, y: Float): Int {
        if (style == 4) {
            // burbujas: la que tenga el centro más cerca del dedo (con un margen para tocarlas fácil)
            var bi = -1
            var bdist = Float.MAX_VALUE
            for (i in items.indices) {
                if (!cardRect(i, r) || outA < 0.2f) continue
                val d = kotlin.math.hypot(x - r.centerX(), y - r.centerY())
                if (d < r.width() / 2f + dp(8f) && d < bdist) {
                    bdist = d
                    bi = i
                }
            }
            return bi
        }
        // de arriba (dibujado último) hacia abajo
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in items.indices) {
            if (!cardRect(i, r)) continue
            if (outA < 0.2f) continue
            if (r.contains(x, y)) {
                val d = abs(i - pos.value)
                if (d < bd) {
                    bd = d
                    best = i
                }
            }
        }
        return best
    }

    private fun maxPos(): Float = max(0, items.size - 1).toFloat()

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (closingAll) return true
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
                downX = x
                downY = y
                lastX = x
                lastY = y
                mode = 0
                startPos = pos.value
                pos.snap(pos.value)
                wasMoving = (style == 3 && gridScroll.isMoving) || pos.active
                gridScroll.stop()
                hitIdx = hit(x, y)
                longFired = false
                removeCallbacks(bubbleLong)
                if (hitIdx >= 0) {
                    pressIdx = hitIdx
                    Mo.tune(pressK, S.MO_PRESS, 600f, 1f)
                    pressK.to(0.96f)
                    ticker.kick()
                    // burbujas: mantener pulsada la hace estallar (la quita de recientes); mientras, se hincha
                    if (style == 4) {
                        postDelayed(bubbleLong, S.f(S.G_LONG_MS).toLong())
                        holdIdx = hitIdx
                        holdVis = hitIdx
                        holdStart = android.os.SystemClock.uptimeMillis()
                        holdSp.snap(0f)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = x - downX
                val dy = y - downY
                if (longFired) return true
                if (style == 4 && mode == 0 && hitIdx in bubs.indices && (abs(dx) > slop || abs(dy) > slop)) {
                    // agarrar la burbuja: se levanta y sigue al dedo
                    removeCallbacks(bubbleLong)
                    endHold()
                    releasePress()
                    mode = 5
                    grab = hitIdx
                    val b = bubs[grab]
                    if (!b.placed) {
                        bubbleHome(grab)
                        b.x = homeX
                        b.y = homeY
                        b.placed = true
                    }
                    b.flying = false
                    b.lastX = b.x
                    b.lastY = b.y
                    b.gvx = 0f
                    b.gvy = 0f
                    // al levantarla se aplasta un instante, como al pellizcar una gota
                    b.wobV -= 1.4f
                    grabDX = downX - b.x
                    grabDY = downY - b.y
                    Mo.tune(grabLift, S.MO_PRESS, 420f, 0.55f)
                    grabLift.to(1.12f)
                    Hx.fire(this, S.HE_ICON)
                    ticker.kick()
                }
                if (mode == 5) {
                    val b = bubs.getOrNull(grab)
                    if (b != null) {
                        b.x = x - grabDX
                        b.y = y - grabDY
                        b.vx = 0f
                        b.vy = 0f
                    }
                    invalidate()
                    lastX = x
                    lastY = y
                    return true
                }
                if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                    removeCallbacks(bubbleLong)
                    endHold()
                    releasePress()
                    mode = if (S.b(S.RC_SWIPE) && hitIdx >= 0 && dy < 0 && abs(dy) > abs(dx) * 1.2f && style != 4) 2 else 1
                    if (mode == 2) {
                        dismissIdx = hitIdx
                        dismissY.snap(0f)
                    }
                }
                if (mode == 1) {
                    if (style == 3) {
                        gridScroll.drag(-(y - lastY))
                    } else {
                        val dist = if (style == 4) dp(120f) else spacing()
                        val d = if (style == 4) (dx + dy) else dx
                        var t = startPos - d / dist
                        val m = maxPos()
                        if (t < 0f) t = rubber(t, 1f)
                        if (t > m) t = m + rubber(t - m, 1f)
                        pos.snap(t)
                    }
                    invalidate()
                } else if (mode == 2) {
                    dismissY.snap(min(0f, dy))
                    invalidate()
                }
                lastX = x
                lastY = y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(ev)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val vy = vt?.yVelocity ?: 0f
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                removeCallbacks(bubbleLong)
                if (!longFired) endHold()
                releasePress()
                if (longFired) {
                    // la burbuja ya estalló con la pulsación larga
                    longFired = false
                    mode = 0
                    vt?.recycle()
                    vt = null
                    return true
                }
                when (mode) {
                    5 -> {
                        // soltar la burbuja: se queda donde la dejas; lanzada fuerte sale volando y se cierra
                        val b = bubs.getOrNull(grab)
                        if (b != null) {
                            val speed = kotlin.math.hypot(vx, vy)
                            if (up && speed > 2600f) {
                                b.flying = true
                                b.vx = vx
                                b.vy = vy
                            } else {
                                b.pinned = true
                                b.ax = b.x
                                b.ay = b.y
                                // conserva un poco de la inercia del dedo y se asienta temblando como gelatina
                                b.vx = vx * 0.35f
                                b.vy = vy * 0.35f
                                b.wobV += 2.4f
                            }
                            b.gvx = 0f
                            b.gvy = 0f
                        }
                        grab = -1
                        Mo.tune(grabLift, S.MO_RELEASE, 380f, 0.45f)
                        grabLift.to(1f)
                        ticker.kick()
                    }
                    1 -> {
                        if (style == 3) {
                            gridScroll.release(-vy)
                        } else {
                            val dist = if (style == 4) dp(120f) else spacing()
                            val v = -vx / dist
                            val target = (pos.value + v * 0.3f).roundToInt().coerceIn(0, max(0, items.size - 1))
                            pos.to(target.toFloat())
                            pos.velocity = v
                        }
                        ticker.kick()
                    }
                    2 -> {
                        val idx = dismissIdx
                        if (idx >= 0 && (dismissY.value < -dp(140f) || vy < -1800f)) {
                            removeAt(idx)
                        } else {
                            dismissY.to(0f)
                            ticker.kick()
                        }
                    }
                    else -> if (wasMoving) {
                        gridScroll.clampNow()
                        if (pos.value != pos.value.roundToInt().toFloat()) pos.to(pos.value.roundToInt().toFloat().coerceIn(0f, maxPos()))
                        ticker.kick()
                    } else if (up) {
                        if (btn.contains(x, y)) {
                            // "Cerrar todo" de One UI: las tarjetas salen volando hacia arriba
                            Recents.forgetAll(items.map { it.key })
                            Hx.fire(this, S.HE_CLOSEALL)
                            closingAll = true
                            closeAllSp.snap(0f)
                            closeAllSp.to(1f)
                            ticker.kick()
                            postDelayed({ listener?.onClose() }, 260)
                        } else if (hitIdx >= 0 && hitIdx < items.size) {
                            cardRect(hitIdx, r)
                            listener?.onLaunch(items[hitIdx], RectF(r))
                        } else {
                            listener?.onClose()
                        }
                    }
                }
                mode = 0
                vt?.recycle()
                vt = null
                return true
            }
        }
        return true
    }

    private fun releasePress() {
        if (pressK.target >= 1f) return
        Mo.tune(pressK, S.MO_RELEASE, 420f, 0.5f)
        pressK.to(1f)
        ticker.kick()
    }

    private val gapShift = ticker.add(SpringValue(0f, 300f, 28f, 0.002f))
    private var removedAt = -1

    private fun removeAt(idx: Int) {
        removedAt = idx
        Mo.tune(gapShift, S.MO_RECENTS_CAROUSEL, 300f, 0.78f)
        gapShift.snap(1f)
        gapShift.to(0f)
        val e = items.removeAt(idx)
        Recents.forget(e.key)
        dismissIdx = -1
        dismissY.snap(0f)
        if (pos.value > maxPos()) pos.to(maxPos())
        layoutGrid()
        Hx.fire(this, S.HE_CLOSEALL)
        ticker.kick()
        invalidate()
        if (items.isEmpty()) postDelayed({ listener?.onClose() }, 250)
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }
}

/** Alias corto para android.view.View (evita choques de nombres). */
typealias View0 = android.view.View
