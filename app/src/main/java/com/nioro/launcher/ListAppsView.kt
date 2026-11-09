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
import kotlin.math.max
import kotlin.math.min

/** Vista 4 - Lista: filas de cristal con letras de sección e índice A-Z con burbuja. */
class ListAppsView(ctx: Context) : AppsView(ctx) {
    override val iconArea = S.IA_LIST


    private class Row(val header: Char, val entry: AppEntry?, val y: Float)

    private val scroll = Scroll1D(ticker)
    private val rows = ArrayList<Row>()
    private val panel = RectF()
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val idxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letters = ArrayList<Char>()
    private val rect = RectF()
    private val names = HashMap<String, String>()
    /** la burbuja del índice aparece y se va con un rebote */
    private val bubbleSp = ticker.add(SpringValue(0f, 420f, 22f, 0.002f))
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var nameW = 0f
    private var rowH = 0f
    private var headH = 0f
    private var iconSize = 0f
    private var contentH = 0f

    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var downRow = -1
    private var hiRow = -1
    private val hiSp = ticker.add(SpringValue(0f, 600f, 45f, 0.003f))
    private var longFired = false
    private var wasMoving = false
    private var atTop = false
    private var closing = false
    private var onIndex = false
    private var bubbleChar = ' '
    private var vt: VelocityTracker? = null
    private val longRun = Runnable {
        if (!moved && downRow in rows.indices && rows[downRow].entry != null) {
            longFired = true
            lift()
            Hx.fire(this, S.HE_LONG)
            val r = RectF()
            val ry = panel.top + dp(8f) + rows[downRow].y - scroll.value
            r.set(panel.left + dp(16f), ry, panel.left + dp(16f) + iconSize, ry + rowH)
            listener?.onMenu(rows[downRow].entry!!, r)
        }
    }

    init {
        namePaint.color = Color.WHITE
        namePaint.textSize = dp(17f)
        namePaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        letterPaint.color = 0xCCFFFFFF.toInt()
        letterPaint.textSize = dp(14f)
        letterPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        linePaint.color = 0x22FFFFFF
        linePaint.strokeWidth = dp(1f)
        hiPaint.color = 0x2EFFFFFF
        idxPaint.color = 0xDDFFFFFF.toInt()
        idxPaint.textSize = dp(10.5f)
        idxPaint.textAlign = Paint.Align.CENTER
        bubblePaint.color = Color.WHITE
        bubblePaint.textSize = dp(34f)
        bubblePaint.textAlign = Paint.Align.CENTER
        bubblePaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun layout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        rowH = dp(S.f(S.LIST_ROW))
        Tx.apply(namePaint, S.TX_LIST, dp(17f), 400, 0f, 0)
        Tx.apply(letterPaint, S.TX_LIST_LETTER, dp(14f), 700, 0f, 0)
        Tx.apply(bubblePaint, S.TX_BUBBLE, dp(34f), 500, 0f, 0)
        headH = dp(30f)
        iconSize = dp(S.f(S.LIST_ICON))
        panel.set(dp(14f), insetTop + (if (keyboardUp) dp(16f) else dp(76f)), w - dp(14f), h - bottomInset - dp(14f))
        // los nombres terminan antes del índice A-Z
        nameW = max(dp(40f), panel.right - dp(40f) - (panel.left + dp(22f) + iconSize + dp(16f)))
        names.clear()
        rows.clear()
        letters.clear()
        var y = 0f
        var cur = ' '
        for (e in shown) {
            val ch = e.label.trim().firstOrNull()?.uppercaseChar() ?: '#'
            val l = if (ch.isLetter()) ch else '#'
            if (l != cur) {
                cur = l
                rows.add(Row(l, null, y))
                letters.add(l)
                y += headH
            }
            rows.add(Row(l, e, y))
            y += rowH
        }
        contentH = y
        scroll.min = 0f
        scroll.max = max(0f, contentH - (panel.height() - dp(16f)))
        scroll.dim = panel.height()
    }

    override fun onImeChanged() {
        val sv = scroll.value
        layout()
        scroll.jump(minOf(sv, scroll.max))
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layout()
    }

    override fun onDataChanged() {
        layout()
        scroll.jump(0f)
    }

    override fun onOpen() {
        layout()
        scroll.jump(0f)
    }

    override fun onFrame(dt: Float): Boolean {
        invalidate()
        return false
    }

    override fun onDraw(c: Canvas) {
        val p = progress
        val a = clamp01(p * 1.5f)
        c.save()
        c.translate(0f, (1f - min(p, 1f)) * dp(70f))
        glass.draw(c, panel, dp(30f), a, true, S.SF_LISTPANEL)
        c.save()
        c.clipRect(panel.left, panel.top + dp(6f), panel.right, panel.bottom - dp(6f))
        val baseY = panel.top + dp(8f) - scroll.value
        var shownIdx = 0
        for (i in 0 until rows.size) {
            val r = rows[i]
            val y = baseY + r.y
            val h = if (r.entry == null) headH else rowH
            if (y + h < panel.top || y > panel.bottom) continue
            // entrada escalonada desde la derecha
            // el escalonado se limita: en reposo (p = 1) todas las filas llegan a su sitio
            val q = clamp01(p * 1.7f - min(shownIdx, 10) * 0.07f)
            shownIdx++
            val ox = (1f - q) * dp(60f)
            val al = clamp01(q * 1.4f)
            if (r.entry == null) {
                letterPaint.alpha = Tx.alpha(S.TX_LIST_LETTER, al * 0.8f)
                if (!S.b(S.LIST_LETTERS)) letterPaint.alpha = 0
                c.drawText(r.header.toString(), panel.left + dp(22f) + ox, y + headH - dp(8f), letterPaint)
            } else {
                if (hiRow == i && hiSp.value > 0.01f) {
                    rect.set(panel.left + dp(8f), y + dp(2f), panel.right - dp(34f), y + rowH - dp(2f))
                    hiPaint.alpha = (46 * hiSp.value * al).toInt().coerceIn(0, 255)
                    c.drawRoundRect(rect, dp(16f), dp(16f), hiPaint)
                }
                val cx = panel.left + dp(22f) + iconSize / 2f + ox
                drawIcon(c, r.entry, cx, y + rowH / 2f, iconSize, al)
                namePaint.alpha = Tx.alpha(S.TX_LIST, al)
                c.drawText(nameOf(r.entry), cx + iconSize / 2f + dp(16f), y + rowH / 2f + dp(6f) + Tx.dy(S.TX_LIST), namePaint)
                linePaint.alpha = if (S.b(S.LIST_LINES)) (34 * al).toInt() else 0
                val lx = cx + iconSize / 2f + dp(16f)
                c.drawLine(lx, y + rowH - dp(0.5f), panel.right - dp(40f), y + rowH - dp(0.5f), linePaint)
            }
        }
        c.restore()
        // índice A-Z: la letra de la sección visible se resalta
        if (letters.size > 1 && S.b(S.LIST_INDEX)) {
            val top = panel.top + dp(18f)
            val ih = (panel.height() - dp(36f)) / letters.size
            val cur = currentLetter()
            for (i in 0 until letters.size) {
                val y = top + i * ih + ih * 0.5f
                if (letters[i] == cur) {
                    accentPaint.color = OneColors.ACCENT
                    accentPaint.alpha = (220 * a).toInt()
                    c.drawCircle(panel.right - dp(16f), y, min(ih * 0.5f, dp(9f)), accentPaint)
                }
                idxPaint.alpha = (230 * a).toInt()
                c.drawText(letters[i].toString(), panel.right - dp(16f), y + dp(4f), idxPaint)
            }
        }
        val bs = bubbleSp.value
        if (bs > 0.01f && bubbleChar != ' ') {
            val r = dp(42f) * bs
            rect.set(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r)
            glass.draw(c, rect, dp(26f) * bs, clamp01(bs), true, S.SF_BUBBLE)
            bubblePaint.alpha = (255 * clamp01(bs)).toInt()
            c.save()
            c.scale(bs, bs, rect.centerX(), rect.centerY())
            c.drawText(bubbleChar.toString(), rect.centerX(), rect.centerY() + dp(12f), bubblePaint)
            c.restore()
        }
        c.restore()
    }

    /** nombre con el estilo de texto aplicado y recortado con "…" si no cabe */
    private fun nameOf(e: AppEntry): String = names.getOrPut(e.key) {
        android.text.TextUtils.ellipsize(Tx.str(S.TX_LIST, e.label), android.text.TextPaint(namePaint), nameW, android.text.TextUtils.TruncateAt.END).toString()
    }

    private fun rowAt(x: Float, y: Float): Int {
        if (!panel.contains(x, y)) return -1
        val ly = y - (panel.top + dp(8f)) + scroll.value
        for (i in 0 until rows.size) {
            val r = rows[i]
            val h = if (r.entry == null) headH else rowH
            if (ly >= r.y && ly < r.y + h) return if (r.entry != null) i else -1
        }
        return -1
    }

    /** letra de la sección que está arriba de la lista ahora mismo */
    private fun currentLetter(): Char {
        var cur = ' '
        for (r in rows) {
            if (r.entry != null) continue
            if (r.y <= scroll.value + dp(4f)) cur = r.header else break
        }
        return cur
    }

    private fun jumpToIndex(y: Float) {
        if (letters.isEmpty()) return
        val top = panel.top + dp(18f)
        val ih = (panel.height() - dp(36f)) / letters.size
        val i = ((y - top) / ih).toInt().coerceIn(0, letters.size - 1)
        bubbleChar = letters[i]
        for (r in rows) {
            if (r.entry == null && r.header == bubbleChar) {
                scroll.jump(min(r.y, scroll.max))
                break
            }
        }
        invalidate()
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
                wasMoving = scroll.isMoving
                scroll.stop()
                atTop = scroll.value <= scroll.min + 1f
                closing = false
                downX = x
                downY = y
                lastY = y
                moved = false
                longFired = false
                onIndex = S.b(S.LIST_INDEX) && letters.size > 1 && panel.contains(x, y) && x > panel.right - dp(34f)
                if (onIndex) {
                    Mo.tune(bubbleSp, S.MO_MENU, 420f, 0.55f)
                    bubbleSp.to(1f)
                    ticker.kick()
                    jumpToIndex(y)
                    return true
                }
                downRow = rowAt(x, y)
                uiHandler.removeCallbacks(longRun)
                unpress()
                if (downRow >= 0) {
                    uiHandler.postDelayed(longRun, longMs)
                    hiRow = downRow
                    hiSp.to(1f)
                    ticker.kick()
                }
                if (downRow >= 0) press(rows[downRow].entry)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (onIndex) {
                    jumpToIndex(y)
                    return true
                }
                if (longFired) return true
                if (!moved && (abs(x - downX) > slop || abs(y - downY) > slop)) {
                    moved = true
                    hiSp.to(0f)
                    uiHandler.removeCallbacks(longRun)
                unpress()
                }
                if (moved) {
                    // desde arriba del todo, tirar hacia abajo cierra la vista
                    if (atTop && !closing && S.b(S.DR_SWIPE_CLOSE) && y - downY > dp(120f) && abs(y - downY) > abs(x - downX) * 1.4f) {
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
                if (onIndex) {
                    onIndex = false
                    Mo.tune(bubbleSp, S.MO_MENU, 420f, 1f)
                    bubbleSp.to(0f)
                    ticker.kick()
                    invalidate()
                } else if (moved) {
                    vt?.computeCurrentVelocity(1000)
                    scroll.release(-(vt?.yVelocity ?: 0f))
                    ticker.kick()
                } else if (wasMoving) {
                    // el toque solo frenó la inercia o el rebote: no abre nada y vuelve al borde
                    scroll.clampNow()
                    ticker.kick()
                } else if (!longFired && up) {
                    val row = downRow
                    if (row >= 0 && row < rows.size && rows[row].entry != null) {
                        val ry = panel.top + dp(8f) + rows[row].y - scroll.value
                        val r = RectF(panel.left + dp(16f), ry, panel.left + dp(16f) + iconSize, ry + rowH)
                        r.offset(0f, (1f - min(progress, 1f)) * dp(70f))
                        listener?.onLaunch(rows[row].entry!!, r)
                    } else if (!panel.contains(x, y)) {
                        listener?.onClose()
                    }
                }
                downRow = -1
                hiSp.to(0f)
                ticker.kick()
                invalidate()
                vt?.recycle()
                vt = null
                return true
            }
        }
        return true
    }
}
