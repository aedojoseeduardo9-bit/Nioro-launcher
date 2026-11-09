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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Muchos resortes con clave (la posición de cada fila o icono) dentro de un solo paso del Ticker. */
class SpringMap : Stepper {
    val map = HashMap<String, SpringValue>()

    fun get(k: String, init: Float, stiff: Float = 340f, damp: Float = 30f): SpringValue =
        map.getOrPut(k) { SpringValue(init, stiff, damp, 0.3f) }

    override fun step(dt: Float): Boolean {
        var any = false
        for (s in map.values) if (s.step(dt)) any = true
        return any
    }
}

/**
 * Vista 6 - Playlist: tus apps como las canciones de una lista de Spotify.
 *  - cabecera con portada (tus 4 primeras apps), título y botón para cambiar entre "Mi orden" y A-Z;
 *    al desplazar, la portada se encoge y se desvanece y aparece una barra con el título;
 *  - mantener pulsada una app (o agarrarla por ≡) la levanta: se mueve con el dedo y las demás se
 *    apartan con un resorte para hacerle sitio; al soltarla se guarda tu orden;
 *  - deslizarla a la derecha descubre el círculo "Añadir al inicio" (como "Añadir a la cola").
 */
class PlaylistView(ctx: Context) : AppsView(ctx) {
    override val iconArea = S.IA_PLAYLIST

    private val scroll = Scroll1D(ticker)
    /** posición de cada fila dentro de la lista (px): al reordenar, las filas se deslizan a su sitio */
    private val rowY = ticker.add(SpringMap())
    private val list = ArrayList<AppEntry>()
    private val panel = RectF()
    private val rect = RectF()
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headerPaint = Paint()
    private var headerShader: LinearGradient? = null
    private val names = HashMap<String, String>()
    private val subs = HashMap<String, String>()
    private var rowH = 0f
    private var iconS = 0f
    private var headH = 0f
    private var nameW = 0f
    private var coverS = 0f
    private var subtitle = ""

    // ---- gestos
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var moved = false
    private var wasMoving = false
    private var atTop = false
    private var closing = false
    private var vt: VelocityTracker? = null
    /** 0 nada · 1 desplazar · 2 deslizar una fila · 3 arrastrar una fila */
    private var mode = 0
    private var downIdx = -1
    private var onGrip = false
    private var onButton = false
    private var longFired = false

    // ---- arrastrar para reordenar
    private var dragKey: String? = null
    private var dragY = 0f
    private var grabOff = 0f
    private var dragFrom = -1
    private var dragSlot = -1
    private val liftSp = ticker.add(SpringValue(0f, 420f, 26f, 0.002f))

    // ---- deslizar para "Añadir al inicio"
    private var swipeKey: String? = null
    private val swipeSp = ticker.add(SpringValue(0f, 420f, 30f, 0.3f))
    private var armed = false
    private val armSp = ticker.add(SpringValue(0f, 520f, 20f, 0.002f))
    private var doneKey: String? = null
    private val doneSp = ticker.add(SpringValue(0f, 60f, 15.5f, 0.002f))

    // ---- botón Mi orden / A-Z
    private val btnSp = ticker.add(SpringValue(1f, 600f, 30f, 0.002f))
    private val flipSp = ticker.add(SpringValue(0f, 300f, 20f, 0.002f))

    private val longRun = Runnable {
        if (!moved && downIdx in list.indices) {
            longFired = true
            startDrag(downIdx)
        }
    }

    init {
        namePaint.color = Color.WHITE
        subPaint.color = OneColors.SUB
        titlePaint.color = Color.WHITE
        titlePaint.typeface = Typeface.create(Typeface.DEFAULT, 800, false)
        barPaint.color = Color.WHITE
        barPaint.textAlign = Paint.Align.CENTER
        barPaint.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        gripPaint.color = Color.WHITE
        gripPaint.strokeCap = Paint.Cap.ROUND
    }

    // ---------------------------------------------------------------- Medidas y datos
    private val searching: Boolean get() = query.isNotEmpty()

    private fun layout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        rowH = dp(64f)
        iconS = dp(46f) // (drawIcon ya aplica la escala de iconos de esta vista)
        Tx.apply(namePaint, S.TX_LIST, dp(16.5f), 500, 0f, 0)
        subPaint.textSize = dp(13f)
        titlePaint.textSize = dp(27f)
        barPaint.textSize = dp(16f)
        panel.set(dp(10f), insetTop + (if (keyboardUp) dp(16f) else dp(76f)), w - dp(10f), h - bottomInset - dp(10f))
        headH = if (searching) dp(8f) else dp(244f)
        coverS = dp(136f)
        nameW = max(dp(40f), panel.right - dp(64f) - (panel.left + dp(20f) + iconS + dp(14f)))
        names.clear()
        updateScrollRange()
        // degradado de la cabecera con el color de la "portada" (como Spotify)
        var r = 0
        var g = 0
        var b = 0
        val n = min(4, list.size)
        for (i in 0 until n) {
            val t = list[i].tint
            r += Color.red(t)
            g += Color.green(t)
            b += Color.blue(t)
        }
        val tint = if (n > 0) Color.rgb(r / n, g / n, b / n) else OneColors.ACCENT
        headerShader = LinearGradient(0f, 0f, 0f, dp(300f), (tint and 0x00FFFFFF) or (0x8C shl 24), tint and 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    private fun updateScrollRange() {
        scroll.min = 0f
        scroll.max = max(0f, headH + list.size * rowH + dp(24f) - panel.height())
        scroll.dim = panel.height()
    }

    /** arma la lista en su orden (el tuyo o A-Z) y coloca cada fila; con [animate] se deslizan */
    private fun rebuild(animate: Boolean) {
        list.clear()
        if (searching || !Prefs.playlistMine) {
            list.addAll(shown)
        } else {
            val byKey = LinkedHashMap<String, AppEntry>()
            for (e in shown) byKey[e.key] = e
            for (k in Prefs.playlistOrder()) byKey.remove(k)?.let { list.add(it) }
            // apps nuevas (o que nunca se movieron): al final, en orden alfabético
            list.addAll(byKey.values)
        }
        val home = HashSet(Prefs.homeKeys())
        val dock = HashSet(Prefs.dockKeys())
        subs.clear()
        for (e in list) subs[e.key] = when (e.key) {
            in dock -> "App · en el dock"
            in home -> "App · en el inicio"
            else -> "App"
        }
        subtitle = list.size.toString() + " apps · " + (if (Prefs.playlistMine) "Mi orden" else "A‑Z")
        layout()
        placeRows(animate)
    }

    private fun placeRows(animate: Boolean) {
        for ((i, e) in list.withIndex()) {
            if (e.key == dragKey) continue
            val s = rowY.get(e.key, i * rowH)
            if (animate) {
                Mo.tune(s, S.MO_EDIT_IN, 340f, 0.78f)
                s.to(i * rowH)
            } else {
                s.snap(i * rowH)
            }
        }
        if (animate) ticker.kick()
    }

    override fun onImeChanged() {
        val sv = scroll.value
        layout()
        scroll.jump(min(sv, scroll.max))
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layout()
    }

    override fun onDataChanged() {
        rebuild(false)
        scroll.jump(0f)
    }

    override fun onOpen() {
        dragKey = null
        swipeKey = null
        liftSp.snap(0f)
        swipeSp.snap(0f)
        rebuild(false)
        scroll.jump(0f)
    }

    override fun onFrame(dt: Float): Boolean {
        // arrastrando cerca del borde: la lista se desplaza sola, más rápido cuanto más al borde
        if (dragKey != null) {
            val edge = dp(80f)
            var v = 0f
            if (dragY < panel.top + edge) v = -(1f - (dragY - panel.top) / edge).coerceIn(0f, 1f)
            if (dragY > panel.bottom - edge) v = (1f - (panel.bottom - dragY) / edge).coerceIn(0f, 1f)
            if (v != 0f) {
                val nv = (scroll.value + v * dp(900f) * dt).coerceIn(scroll.min, scroll.max)
                if (nv != scroll.value) {
                    scroll.jump(nv)
                    updateSlot()
                }
            }
        }
        invalidate()
        return dragKey != null
    }

    // ---------------------------------------------------------------- Dibujo
    /** arriba de la primera fila, en pantalla */
    private fun baseY(): Float = panel.top + dp(6f) + headH - scroll.value

    private fun btnX(): Float = panel.right - dp(46f)
    private fun btnY(): Float = panel.top + dp(6f) + dp(16f) + coverS + dp(38f) - scroll.value

    private fun popCurve(q: Float): Float {
        val t = clamp01(q) - 1f
        return 1f + 2.7f * t * t * t + 1.7f * t * t
    }

    override fun onDraw(c: Canvas) {
        val p = progress
        val a = clamp01(p * 1.5f)
        c.save()
        c.translate(0f, (1f - min(p, 1f)) * dp(60f))
        glass.draw(c, panel, dp(30f), a, true, S.SF_LISTPANEL)
        c.save()
        c.clipRect(panel.left, panel.top + dp(4f), panel.right, panel.bottom - dp(4f))
        if (!searching) drawHeader(c, a, p)
        val base = baseY()
        var vis = 0
        for (e in list) {
            if (e.key == dragKey) continue
            val ry = rowY.get(e.key, 0f).value
            val y = base + ry
            if (y + rowH < panel.top || y > panel.bottom) continue
            // entrada: las filas suben y aparecen una tras otra
            val q = clamp01(p * 1.7f - min(vis, 10) * 0.06f)
            vis++
            drawRow(c, e, y + (1f - q) * dp(36f), a * clamp01(q * 1.4f), 0f)
        }
        // la fila que se arrastra va por encima, levantada
        val dk = dragKey
        if (dk != null) {
            val e = list.firstOrNull { it.key == dk }
            if (e != null) drawRow(c, e, dragY - rowH / 2f, a, liftSp.value)
        }
        c.restore()
        if (!searching) drawBar(c, a)
        c.restore()
    }

    private fun drawHeader(c: Canvas, a: Float, p: Float) {
        val top = panel.top + dp(6f) - scroll.value
        // degradado del color de la portada, que se va con la lista
        headerShader?.let {
            headerPaint.shader = it
            headerPaint.alpha = (255 * a).toInt()
            c.save()
            c.translate(0f, top - dp(10f))
            c.drawRect(panel.left, 0f, panel.right, dp(300f), headerPaint)
            c.restore()
        }
        // portada: se encoge y se desvanece al desplazar (y baja un poco más despacio: profundidad)
        val k = clamp01(scroll.value / (headH * 0.7f))
        val pq = clamp01(p * 1.5f)
        val cs = coverS * (1f - 0.28f * k) * (0.86f + 0.14f * popCurve(pq))
        val ccx = panel.centerX()
        val ccy = top + dp(16f) + coverS / 2f + scroll.value * 0.45f
        val ca = a * (1f - k)
        if (ca > 0.01f) {
            rect.set(ccx - cs / 2f, ccy - cs / 2f, ccx + cs / 2f, ccy + cs / 2f)
            glass.draw(c, rect, dp(18f) * cs / coverS, ca, true, S.SF_LISTPANEL)
            val n = min(4, list.size)
            if (n >= 4) {
                val q = cs / 2f - dp(10f)
                for (i in 0 until 4) {
                    val ix = ccx + (if (i % 2 == 0) -1 else 1) * (q / 2f + dp(3f))
                    val iy = ccy + (if (i < 2) -1 else 1) * (q / 2f + dp(3f))
                    drawIcon(c, list[i], ix, iy, q, ca)
                }
            } else if (n > 0) {
                drawIcon(c, list[0], ccx, ccy, cs * 0.6f, ca)
            }
        }
        // título, subtítulo y botón (se van con la lista)
        val ty = top + dp(16f) + coverS + dp(40f)
        titlePaint.alpha = (255 * a).toInt()
        c.drawText("Mis apps", panel.left + dp(20f), ty, titlePaint)
        subPaint.alpha = (200 * a).toInt()
        c.drawText(subtitle, panel.left + dp(20f), ty + dp(24f), subPaint)
        // botón redondo de acento: cambia entre Mi orden y A-Z (gira al cambiar)
        val bx = btnX()
        val by = btnY()
        val br = dp(25f) * btnSp.value
        fillPaint.color = OneColors.ACCENT
        fillPaint.alpha = (255 * a).toInt()
        c.drawCircle(bx, by, br, fillPaint)
        c.save()
        c.rotate(flipSp.value * 180f, bx, by)
        if (Prefs.playlistMine) {
            // tres rayas (orden propio)
            gripPaint.strokeWidth = dp(2.4f)
            gripPaint.alpha = (255 * a).toInt()
            for (i in -1..1) c.drawLine(bx - dp(9f), by + i * dp(6f), bx + dp(9f), by + i * dp(6f), gripPaint)
        } else {
            barPaint.alpha = (255 * a).toInt()
            barPaint.textSize = dp(14f)
            c.drawText("A‑Z", bx, by + dp(5f), barPaint)
            barPaint.textSize = dp(16f)
        }
        c.restore()
    }

    /** barra compacta con el título cuando la portada ya se fue (como Spotify) */
    private fun drawBar(c: Canvas, a: Float) {
        val k = clamp01((scroll.value - (headH - dp(110f))) / dp(50f))
        if (k <= 0.01f) return
        rect.set(panel.left + dp(6f), panel.top + dp(6f), panel.right - dp(6f), panel.top + dp(56f))
        glass.draw(c, rect, dp(24f), a * k, false, S.SF_LISTPANEL)
        barPaint.alpha = (255 * a * k).toInt()
        c.drawText("Mis apps", rect.centerX(), rect.centerY() + dp(6f) + (1f - k) * dp(8f), barPaint)
    }

    private fun drawRow(c: Canvas, e: AppEntry, y: Float, al: Float, lift: Float) {
        if (al <= 0.01f) return
        val swipe = if (e.key == swipeKey) swipeSp.value else 0f
        // detrás de la fila: el círculo "Añadir al inicio" que crece al deslizar
        if (swipe > dp(2f)) {
            val arm = armSp.value
            val cr = dp(17f) * (0.55f + 0.45f * clamp01(swipe / dp(80f))) * (1f + 0.15f * arm)
            val cx = panel.left + dp(36f)
            val cy = y + rowH / 2f
            fillPaint.color = blend(0x55FFFFFF, 0xFF1ED760.toInt(), clamp01(arm))
            fillPaint.alpha = (fillPaint.alpha * al * clamp01(swipe / dp(30f))).toInt().coerceIn(0, 255)
            c.drawCircle(cx, cy, cr, fillPaint)
            Glyphs.draw(c, Glyphs.PLUS, cx, cy, cr * 1.1f, if (arm > 0.5f) 0xFF0B1A10.toInt() else Color.WHITE, al)
        }
        c.save()
        c.translate(swipe, 0f)
        if (lift > 0.001f) {
            // levantada: se agranda un poco y lleva su propio cristal debajo
            val s = 1f + 0.035f * lift
            c.scale(s, s, panel.centerX(), y + rowH / 2f)
            rect.set(panel.left + dp(8f), y + dp(3f), panel.right - dp(8f), y + rowH - dp(3f))
            glass.draw(c, rect, dp(18f), al * lift, true, S.SF_DRAG)
        }
        val ix = panel.left + dp(20f) + iconS / 2f
        val cy = y + rowH / 2f
        drawIcon(c, e, ix, cy, iconS, al)
        val tx = ix + iconS / 2f + dp(14f)
        namePaint.alpha = Tx.alpha(S.TX_LIST, al)
        c.drawText(nameOf(e), tx, cy - dp(2f) + Tx.dy(S.TX_LIST), namePaint)
        subPaint.alpha = (190 * al).toInt()
        c.drawText(subs[e.key] ?: "App", tx, cy + dp(17f), subPaint)
        // ≡ para arrastrar (en la búsqueda no se reordena)
        if (!searching) {
            val gx = panel.right - dp(30f)
            gripPaint.strokeWidth = dp(2f)
            gripPaint.alpha = (150 * al * (1f - 0.4f * lift) + 105 * lift).toInt().coerceIn(0, 255)
            for (i in -1..1) c.drawLine(gx - dp(8f), cy + i * dp(5f), gx + dp(8f), cy + i * dp(5f), gripPaint)
        }
        // recién añadida al inicio: un check verde que aparece y se desvanece
        if (e.key == doneKey && doneSp.value > 0.01f) {
            val d = doneSp.value
            val bx = ix + iconS * 0.4f
            val by = cy - iconS * 0.4f
            fillPaint.color = 0xFF1ED760.toInt()
            fillPaint.alpha = (255 * al * clamp01(d * 1.6f)).toInt()
            c.drawCircle(bx, by, dp(10f) * popCurve(1f - d * 0.5f), fillPaint)
            Glyphs.draw(c, Glyphs.CHECK, bx, by, dp(13f), 0xFF0B1A10.toInt(), al * clamp01(d * 1.6f))
        }
        c.restore()
    }

    private fun nameOf(e: AppEntry): String = names.getOrPut(e.key) {
        TextUtils.ellipsize(Tx.str(S.TX_LIST, e.label), namePaint, nameW, TextUtils.TruncateAt.END).toString()
    }

    // ---------------------------------------------------------------- Reordenar
    private fun rowAt(x: Float, y: Float): Int {
        if (!panel.contains(x, y)) return -1
        val base = baseY()
        for ((i, e) in list.withIndex()) {
            val ry = rowY.get(e.key, i * rowH).value
            if (y >= base + ry && y < base + ry + rowH) return i
        }
        return -1
    }

    private fun startDrag(i: Int) {
        if (searching || i !in list.indices) return
        uiHandler.removeCallbacks(longRun)
        unpress()
        // si estaba en A-Z, el orden de ahora pasa a ser "Mi orden"
        if (!Prefs.playlistMine) {
            Prefs.playlistMine = true
            subtitle = list.size.toString() + " apps · Mi orden"
        }
        val e = list[i]
        dragKey = e.key
        dragFrom = i
        dragSlot = i
        val rowCy = baseY() + rowY.get(e.key, i * rowH).value + rowH / 2f
        grabOff = downY - rowCy
        dragY = rowCy
        mode = 3
        Mo.tune(liftSp, S.MO_PRESS, 420f, 0.6f)
        liftSp.to(1f)
        Hx.fire(this, S.HE_LONG)
        ticker.kick()
    }

    /** la fila arrastrada pasa por encima de otra: esta se aparta con un resorte */
    private fun updateSlot() {
        val k = dragKey ?: return
        val from = list.indexOfFirst { it.key == k }
        if (from < 0) return
        val slot = ((dragY - rowH / 2f - baseY()) / rowH).roundToInt().coerceIn(0, list.size - 1)
        if (slot == from) return
        val e = list.removeAt(from)
        list.add(slot, e)
        dragSlot = slot
        placeRows(true)
        Hx.fire(this, S.HE_DOCK)
    }

    private fun endDrag(save: Boolean) {
        val k = dragKey ?: return
        val i = list.indexOfFirst { it.key == k }
        val s = rowY.get(k, 0f)
        // la fila baja desde el dedo hasta su hueco y se asienta con un pequeño rebote
        s.snap(dragY - rowH / 2f - baseY())
        if (i >= 0) {
            Mo.tune(s, S.MO_RELEASE, 360f, 0.7f)
            s.to(i * rowH)
        }
        dragKey = null
        Mo.tune(liftSp, S.MO_RELEASE, 380f, 0.8f)
        liftSp.to(0f)
        if (save) {
            Prefs.setPlaylistOrder(list.map { it.key })
            Hx.fire(this, S.HE_ICON)
        }
        ticker.kick()
    }

    private fun toggleOrder() {
        Prefs.playlistMine = !Prefs.playlistMine
        Mo.tune(flipSp, S.MO_MENU, 300f, 0.6f)
        flipSp.snap(0f)
        flipSp.to(1f)
        // todas las filas se deslizan a su sitio nuevo (no saltan)
        rebuild(true)
        Hx.fire(this, S.HE_SETTING)
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
                wasMoving = scroll.isMoving
                scroll.stop()
                atTop = scroll.value <= scroll.min + 1f
                closing = false
                downX = x
                downY = y
                lastY = y
                moved = false
                longFired = false
                mode = 0
                downIdx = rowAt(x, y)
                onButton = !searching && hypot(x - btnX(), y - btnY()) < dp(34f)
                onGrip = !searching && downIdx >= 0 && x > panel.right - dp(58f)
                uiHandler.removeCallbacks(longRun)
                unpress()
                if (onButton) {
                    Mo.tune(btnSp, S.MO_PRESS, 600f, 1f)
                    btnSp.to(0.88f)
                    ticker.kick()
                } else if (downIdx >= 0) {
                    if (!searching) uiHandler.postDelayed(longRun, longMs)
                    press(list[downIdx])
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = x - downX
                val dy = y - downY
                if (mode == 3) {
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    dragY = y - grabOff
                    updateSlot()
                    invalidate()
                    return true
                }
                if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                    moved = true
                    uiHandler.removeCallbacks(longRun)
                    unpress()
                    if (onButton) {
                        btnSp.to(1f)
                        ticker.kick()
                    }
                    mode = when {
                        onGrip && abs(dy) >= abs(dx) -> {
                            startDrag(downIdx)
                            3
                        }
                        downIdx >= 0 && dx > 0f && abs(dx) > abs(dy) * 1.3f -> {
                            swipeKey = list[downIdx].key
                            armed = false
                            swipeSp.snap(0f)
                            armSp.snap(0f)
                            2
                        }
                        else -> 1
                    }
                }
                if (mode == 2) {
                    // sigue al dedo y, pasado el punto, se frena (como Spotify)
                    val lim = dp(96f)
                    val ox = if (dx <= 0f) 0f else if (dx < lim) dx else lim + (dx - lim) * 0.25f
                    swipeSp.snap(ox)
                    val now = ox > dp(80f)
                    if (now != armed) {
                        armed = now
                        Mo.tune(armSp, S.MO_PRESS, 520f, 0.5f)
                        armSp.to(if (now) 1f else 0f)
                        Hx.fire(this, S.HE_DOCK)
                        ticker.kick()
                    }
                    invalidate()
                } else if (mode == 1) {
                    // desde arriba del todo, tirar hacia abajo cierra la vista
                    if (atTop && !closing && S.b(S.DR_SWIPE_CLOSE) && dy > dp(120f) && abs(dy) > abs(dx) * 1.4f) {
                        closing = true
                        listener?.onClose()
                    }
                    scroll.drag(-(y - lastY))
                    invalidate()
                }
                lastY = y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longRun)
                unpress()
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                vt?.addMovement(ev)
                vt?.computeCurrentVelocity(1000)
                if (onButton) {
                    Mo.tune(btnSp, S.MO_RELEASE, 420f, 0.45f)
                    btnSp.to(1f)
                    ticker.kick()
                }
                when (mode) {
                    3 -> {
                        if (longFired && !moved && dragSlot == dragFrom) {
                            // mantener sin mover: menú de la app, como en el resto de vistas
                            val e = list.getOrNull(dragFrom)
                            endDrag(false)
                            if (e != null && up) {
                                lift()
                                val ry = baseY() + dragFrom * rowH
                                listener?.onMenu(e, RectF(panel.left + dp(20f), ry, panel.left + dp(20f) + iconS, ry + rowH))
                            }
                        } else {
                            endDrag(true)
                        }
                    }
                    2 -> {
                        val k = swipeKey
                        val e = list.firstOrNull { it.key == k }
                        if (armed && up && e != null) {
                            listener?.onAddHome(e)
                            subs[e.key] = "App · en el inicio"
                            doneKey = e.key
                            doneSp.snap(1f)
                            Mo.tune(doneSp, S.MO_MENU, 60f, 1f)
                            doneSp.to(0f)
                            Hx.fire(this, S.HE_LONG)
                        }
                        // vuelve a su sitio con un rebote
                        Mo.tune(swipeSp, S.MO_RELEASE, 420f, 0.55f)
                        swipeSp.to(0f)
                        armSp.to(0f)
                        armed = false
                        ticker.kick()
                    }
                    1 -> {
                        scroll.release(-(vt?.yVelocity ?: 0f))
                        ticker.kick()
                    }
                    else -> if (wasMoving) {
                        scroll.clampNow()
                        ticker.kick()
                    } else if (up && !longFired) {
                        if (onButton) {
                            toggleOrder()
                        } else if (downIdx in list.indices) {
                            val e = list[downIdx]
                            val ry = baseY() + rowY.get(e.key, 0f).value
                            val r = RectF(panel.left + dp(20f), ry + (rowH - iconS) / 2f, panel.left + dp(20f) + iconS, ry + (rowH + iconS) / 2f)
                            r.offset(0f, (1f - min(progress, 1f)) * dp(60f))
                            listener?.onLaunch(e, r)
                        } else if (!panel.contains(x, y)) {
                            listener?.onClose()
                        }
                    }
                }
                mode = 0
                onButton = false
                vt?.recycle()
                vt = null
                invalidate()
                return true
            }
        }
        return true
    }
}
