package com.nioro.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Todo lo que un diseño de reloj necesita saber de la hora actual (lo rellena HomeView cada minuto). */
class ClockInfo {
    var time = ""
    var prevTime = ""
    var hour = ""
    var prevHour = ""
    var min = ""
    var prevMin = ""
    var date = ""
    var h24 = 0
    var m = 0
    var prevH24 = -1
    var prevM = -1
    var dayNum = ""
    var weekday = ""
    var monthYear = ""
    var zone2 = ""
    var zone2Label = ""
    var zone2Diff = ""
    var words1 = ""
    var words2 = ""
    var prevWords1 = ""
    var prevWords2 = ""
    /** progreso (0 -> 1) de la animación del cambio de minuto */
    var q = 1f
}

/**
 * Diseños de reloj 8 a 20 (los 0-7 están en HomeView). Cada uno dibuja en coordenadas de pantalla
 * a partir de [top] (debajo de la barra de estado) y deja en clock/date el rectángulo que ocupa,
 * para poder tocarlo, moverlo y hacer su miniatura en el menú del reloj.
 */
class ClockFaces {
    companion object {
        val ZONES = arrayOf("UTC", "America/New_York", "Europe/Madrid", "America/Mexico_City",
            "America/Argentina/Buenos_Aires", "Asia/Tokyo", "Europe/London", "America/Santiago")
        val ZONE_NAMES = arrayOf("UTC", "Nueva York", "Madrid", "Ciudad de México", "Buenos Aires", "Tokio", "Londres", "Santiago")

        private val NUMS = arrayOf(
            "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
            "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve",
            "veinte", "veintiuno", "veintidós", "veintitrés", "veinticuatro", "veinticinco", "veintiséis",
            "veintisiete", "veintiocho", "veintinueve", "treinta"
        )

        /** "Son las diez" / "y cuarto" · "Es la una" / "menos veinte"... */
        fun words(h24: Int, m: Int): Pair<String, String> {
            var h = h24
            val phrase = when {
                m == 0 -> "en punto"
                m == 15 -> "y cuarto"
                m == 30 -> "y media"
                m < 30 -> "y " + NUMS[m]
                m == 45 -> { h++; "menos cuarto" }
                else -> { h++; "menos " + NUMS[60 - m] }
            }
            var h12 = h % 12
            if (h12 == 0) h12 = 12
            val line1 = if (h12 == 1) "Es la una" else "Son las " + NUMS[h12]
            return Pair(line1, phrase)
        }

        /** zona de reserva para cada diseño (dp por debajo de la barra de estado) */
        fun gridTopDp(style: Int): Float = when (style) {
            9 -> 236f; 10, 11 -> 268f; 12 -> 182f; 13 -> 272f; 14 -> 156f; 15 -> 166f
            16 -> 166f; 17 -> 160f; 18 -> 156f; 19 -> 176f; 20 -> 236f
            else -> 156f
        }

        /** altura (dp, desde la barra de estado) del centro aproximado de cada diseño */
        fun pivotDp(style: Int): Float = when (style) {
            9 -> 92f; 10, 11 -> 134f; 12 -> 84f; 13 -> 150f; 14 -> 66f; 15 -> 84f
            16 -> 72f; 17 -> 76f; 18 -> 70f; 19 -> 84f; 20 -> 124f
            else -> 84f
        }

        // fuente de puntos 5x7 (diseño "Puntos")
        private val DOT_FONT: Array<IntArray> = run {
            val rows = arrayOf(
                arrayOf(" ### ", "#   #", "#  ##", "# # #", "##  #", "#   #", " ### "),
                arrayOf("  #  ", " ##  ", "  #  ", "  #  ", "  #  ", "  #  ", " ### "),
                arrayOf(" ### ", "#   #", "    #", "   # ", "  #  ", " #   ", "#####"),
                arrayOf("#####", "   # ", "  #  ", "   # ", "    #", "#   #", " ### "),
                arrayOf("   # ", "  ## ", " # # ", "#  # ", "#####", "   # ", "   # "),
                arrayOf("#####", "#    ", "#### ", "    #", "    #", "#   #", " ### "),
                arrayOf("  ## ", " #   ", "#    ", "#### ", "#   #", "#   #", " ### "),
                arrayOf("#####", "    #", "   # ", "  #  ", " #   ", " #   ", " #   "),
                arrayOf(" ### ", "#   #", "#   #", " ### ", "#   #", "#   #", " ### "),
                arrayOf(" ### ", "#   #", "#   #", " ####", "    #", "   # ", " ##  ")
            )
            Array(10) { d -> IntArray(7) { r -> var bits = 0; for (col in 0 until 5) if (rows[d][r][col] == '#') bits = bits or (1 shl col); bits } }
        }

        // siete segmentos: a=1 b=2 c=4 d=8 e=16 f=32 g=64
        private val SEG = intArrayOf(63, 6, 91, 79, 102, 109, 125, 7, 127, 111)
    }

    private val glass = Glass()
    private fun tf(w: Int): Typeface = Typeface.create(Typeface.DEFAULT, w, false)
    private val tfThin = tf(100)
    private val tfLight = tf(300)
    private val tfReg = tf(400)
    private val tfMed = tf(500)
    private val tfBold = tf(700)
    private val tfBlack = tf(900)

    private val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val p2 = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r1 = RectF()
    private val r2 = RectF()
    private val m = Matrix()
    private val dash = DashPathEffect(floatArrayOf(dp(2f), dp(7f)), 0f)
    private var grad: LinearGradient? = null
    private var gradA = 0
    private var gradB = 0

    var base = Color.WHITE
    var tone = Color.WHITE
    var accent = 0xFF3E91FF.toInt()
    var dateCol = Color.WHITE
    var k = 1f

    /** colores y tamaño desde los ajustes (se llama cuando cambian) */
    fun refresh() {
        base = S.col(S.tp(S.TX_CLOCK, S.TP_COLOR), Color.WHITE)
        val minAuto = S.i(S.tp(S.TX_MINUTES, S.TP_COLOR)) == 0
        tone = if (minAuto) blend(base, OneColors.ACCENT, 0.55f) else S.col(S.tp(S.TX_MINUTES, S.TP_COLOR), Color.WHITE)
        accent = if (minAuto) OneColors.ACCENT else tone
        dateCol = S.col(S.tp(S.TX_DATE, S.TP_COLOR), 0xF2FFFFFF.toInt())
        k = S.p(S.tp(S.TX_CLOCK, S.TP_SIZE))
        if (grad == null || gradA != base || gradB != accent) {
            grad = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(base, accent, blend(accent, 0xFFF472B6.toInt(), 0.6f)), null, Shader.TileMode.CLAMP)
            gradA = base
            gradB = accent
        }
    }

    private fun text(paint: TextPaint, face: Typeface, size: Float, color: Int, al: Paint.Align, shadow: Boolean = true) {
        paint.typeface = face
        paint.textSize = size
        paint.color = color
        paint.textAlign = al
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.letterSpacing = 0f
        if (shadow) paint.setShadowLayer(dp(6f), 0f, dp(1.5f), 0x55000000) else paint.clearShadowLayer()
    }

    private fun leftOf(x: Float, w: Float, al: Paint.Align): Float = when (al) {
        Paint.Align.LEFT -> x
        Paint.Align.RIGHT -> x - w
        else -> x - w / 2f
    }

    private fun pop(q: Float): Float {
        val t = clamp01(q) - 1f
        return 1f + 2.7f * t * t * t + 1.7f * t * t
    }

    /** cambio de minuto: el texto viejo sube y se va, el nuevo entra desde abajo */
    private fun roll(c: Canvas, paint: Paint, now: String, old: String, x: Float, y: Float, alpha: Int, q: Float) {
        if (q >= 0.999f || old.isEmpty() || old == now) {
            paint.alpha = alpha
            c.drawText(now, x, y, paint)
            return
        }
        val d = paint.textSize * 0.28f
        paint.alpha = (alpha * clamp01(1f - q * 1.4f)).toInt()
        c.drawText(old, x, y - d * clamp01(q), paint)
        paint.alpha = (alpha * clamp01(q * 1.3f)).toInt()
        c.drawText(now, x, y + d * (1f - q), paint)
        paint.alpha = alpha
    }

    private fun dateLine(c: Canvas, s: String, x: Float, y: Float, al: Paint.Align, a: Float, date: RectF, size: Float = dp(15f), face: Typeface = tfMed) {
        if (s.isEmpty()) return
        text(small, face, size, dateCol, al)
        small.alpha = (255 * a).toInt()
        c.drawText(s, x, y, small)
        val w = small.measureText(s)
        val l = leftOf(x, w, al)
        date.set(l, y - size * 1.1f, l + w, y + dp(6f))
    }

    private fun anchorX(al: Paint.Align, width: Float, half: Float): Float = when (al) {
        Paint.Align.LEFT -> dp(28f) + half
        Paint.Align.RIGHT -> width - dp(28f) - half
        else -> width / 2f
    }

    /**
     * Dibuja el diseño [style] (8..20). [x] = ancla horizontal según la alineación, [top] = arriba
     * del reloj, [width] = ancho de pantalla, [showDate] = mostrar la fecha, [live] = animaciones
     * vivas (solo mientras se edita, para no gastar batería en reposo).
     */
    fun draw(c: Canvas, style: Int, i: ClockInfo, a: Float, x: Float, top: Float, al: Paint.Align, width: Float,
             showDate: Boolean, live: Boolean, clock: RectF, date: RectF) {
        clock.setEmpty()
        date.setEmpty()
        when (style) {
            8 -> outline(c, i, a, x, top, al, showDate, clock, date)
            9 -> flip(c, i, a, x, top, al, showDate, clock, date)
            10 -> minimal(c, i, a, top, al, width, showDate, clock, date)
            11 -> ring(c, i, a, top, al, width, showDate, clock, date)
            12 -> wordsFace(c, i, a, x, top, al, width, showDate, clock, date)
            13 -> side(c, i, a, top, al, width, showDate, clock, date)
            14 -> bubbles(c, i, a, x, top, al, showDate, live, clock, date)
            15 -> gradient(c, i, a, x, top, al, showDate, clock, date)
            16 -> calendar(c, i, a, x, top, al, clock)
            17 -> dual(c, i, a, x, top, al, width, showDate, clock, date)
            18 -> dots(c, i, a, x, top, al, showDate, clock, date)
            19 -> segments(c, i, a, x, top, al, showDate, clock, date)
            else -> dayArc(c, i, a, top, al, width, showDate, clock, date)
        }
    }

    // 8 · Contorno: cifras huecas con un relleno muy tenue
    private fun outline(c: Canvas, i: ClockInfo, a: Float, x: Float, top: Float, al: Paint.Align, sd: Boolean, clock: RectF, date: RectF) {
        val y = top + dp(112f)
        text(p2, tfBold, dp(104f) * k, base, al, false)
        roll(c, p2, i.time, i.prevTime, x, y, (34 * a).toInt(), i.q)
        text(p, tfBold, dp(104f) * k, base, al, false)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(2.2f)
        roll(c, p, i.time, i.prevTime, x, y, (255 * a).toInt(), i.q)
        val w = p.measureText(i.time)
        val l = leftOf(x, w, al)
        clock.set(l, y - p.textSize * 0.76f, l + w, y + dp(6f))
        if (sd) dateLine(c, i.date, x, y + dp(38f), al, a, date)
    }

    // 9 · Retro: dos tarjetas que giran como un reloj de cartel al cambiar
    private fun flip(c: Canvas, i: ClockInfo, a: Float, x: Float, top: Float, al: Paint.Align, sd: Boolean, clock: RectF, date: RectF) {
        val tw = dp(118f) * k
        val th = dp(132f) * k
        val gap = dp(12f)
        val l = leftOf(x, tw * 2 + gap, al)
        val t = top + dp(22f)
        for (n in 0 until 2) {
            val now = if (n == 0) i.hour else i.min
            val old = if (n == 0) i.prevHour else i.prevMin
            r1.set(l + n * (tw + gap), t, l + n * (tw + gap) + tw, t + th)
            fill.color = Color.BLACK
            fill.alpha = (80 * a).toInt()
            c.drawRoundRect(r1, dp(22f), dp(22f), fill)
            glass.draw(c, r1, dp(22f), a, true, S.SF_DATE)
            var s = now
            var sy = 1f
            if (i.q < 0.999f && old.isNotEmpty() && old != now) {
                // gira sobre su eje horizontal: la mitad con el número viejo, la otra con el nuevo
                sy = max(0.03f, abs(cos(i.q * PI.toFloat())))
                if (i.q < 0.5f) s = old
            }
            c.save()
            c.scale(1f, sy, r1.centerX(), r1.centerY())
            text(p, tfBold, th * 0.6f, if (n == 0) base else tone, Paint.Align.CENTER)
            p.alpha = (255 * a).toInt()
            c.drawText(s, r1.centerX(), r1.centerY() + p.textSize * 0.36f, p)
            c.restore()
            // la línea del medio de la tarjeta
            fill.color = Color.BLACK
            fill.alpha = (110 * a).toInt()
            c.drawRect(r1.left, r1.centerY() - dp(1f), r1.right, r1.centerY() + dp(1f), fill)
        }
        clock.set(l, t, l + tw * 2 + gap, t + th)
        if (sd) dateLine(c, i.date, clock.centerX(), t + th + dp(34f), Paint.Align.CENTER, a, date)
    }

    /** ángulos (grados) de las agujas; pasan del minuto anterior al nuevo con la animación */
    private val hands = FloatArray(2)

    private fun handAngles(i: ClockInfo) {
        var minA = i.m * 6f
        var hourA = ((i.h24 % 12) + i.m / 60f) * 30f
        if (i.q < 0.999f && i.prevM >= 0) {
            var pm = i.prevM * 6f
            if (minA < pm) pm -= 360f
            var ph = ((max(0, i.prevH24) % 12) + i.prevM / 60f) * 30f
            if (hourA < ph) ph -= 360f
            minA = lerp(pm, minA, i.q)
            hourA = lerp(ph, hourA, i.q)
        }
        hands[0] = hourA
        hands[1] = minA
    }

    private fun hand(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float, w: Float, col: Int, a: Float) {
        val ang = Math.toRadians(deg.toDouble())
        stroke.pathEffect = null
        stroke.strokeWidth = w
        stroke.color = col
        stroke.alpha = (255 * a).toInt()
        c.drawLine(cx, cy, cx + sin(ang).toFloat() * len, cy - cos(ang).toFloat() * len, stroke)
    }

    // 10 · Minimal analógico: sin esfera, solo puntos, números 12-3-6-9 y agujas finas
    private fun minimal(c: Canvas, i: ClockInfo, a: Float, top: Float, al: Paint.Align, width: Float, sd: Boolean, clock: RectF, date: RectF) {
        val r = dp(92f) * k
        val cx = anchorX(al, width, r)
        val cy = top + dp(40f) + r
        for (n in 0 until 60) {
            val big = n % 5 == 0
            if (big && n % 15 == 0) continue
            val ang = n * 6.0 * PI / 180.0
            val rr = r - dp(6f)
            fill.color = base
            fill.alpha = ((if (big) 210 else 70) * a).toInt()
            c.drawCircle(cx + sin(ang).toFloat() * rr, cy - cos(ang).toFloat() * rr, if (big) dp(2.4f) else dp(1.1f), fill)
        }
        text(p, tfMed, dp(17f) * k, base, Paint.Align.CENTER)
        p.alpha = (230 * a).toInt()
        val nr = r - dp(8f)
        c.drawText("12", cx, cy - nr + dp(12f), p)
        c.drawText("6", cx, cy + nr + dp(2f), p)
        c.drawText("3", cx + nr - dp(4f), cy + dp(6f), p)
        c.drawText("9", cx - nr + dp(4f), cy + dp(6f), p)
        handAngles(i)
        hand(c, cx, cy, hands[0], r * 0.5f, dp(5f), base, a)
        hand(c, cx, cy, hands[1], r * 0.8f, dp(2.6f), tone, a)
        fill.color = accent
        fill.alpha = (255 * a).toInt()
        c.drawCircle(cx, cy, dp(5f), fill)
        clock.set(cx - r, cy - r, cx + r, cy + r)
        if (sd) dateLine(c, i.date, cx, cy + r + dp(30f), Paint.Align.CENTER, a, date)
    }

    // 11 · Anillo: un aro que se llena con los minutos y la hora en el centro
    private fun ring(c: Canvas, i: ClockInfo, a: Float, top: Float, al: Paint.Align, width: Float, sd: Boolean, clock: RectF, date: RectF) {
        val r = dp(86f) * k
        val cx = anchorX(al, width, r)
        val cy = top + dp(40f) + r
        r1.set(cx - r, cy - r, cx + r, cy + r)
        stroke.pathEffect = null
        stroke.strokeWidth = dp(9f)
        stroke.color = base
        stroke.alpha = (45 * a).toInt()
        c.drawCircle(cx, cy, r, stroke)
        var pm = (if (i.prevM < 0) i.m else i.prevM).toFloat()
        var tm = i.m.toFloat()
        if (tm < pm) tm += 60f
        val mm = if (i.q < 0.999f) lerp(pm, tm, i.q) else i.m.toFloat()
        val sweep = (mm % 60f) / 60f * 360f
        stroke.color = accent
        stroke.alpha = (255 * a).toInt()
        if (sweep > 0.5f) c.drawArc(r1, -90f, sweep, false, stroke)
        val ea = Math.toRadians((sweep - 90f).toDouble())
        fill.color = Color.WHITE
        fill.alpha = (255 * a).toInt()
        c.drawCircle(cx + cos(ea).toFloat() * r, cy + sin(ea).toFloat() * r, dp(3.2f), fill)
        text(p, tfMed, dp(42f) * k, base, Paint.Align.CENTER)
        roll(c, p, i.time, i.prevTime, cx, cy + p.textSize * 0.34f, (255 * a).toInt(), i.q)
        clock.set(r1)
        if (sd) dateLine(c, i.date, cx, cy + r + dp(32f), Paint.Align.CENTER, a, date)
    }

    /** reduce el texto si no cabe en [maxW] */
    private fun fit(paint: TextPaint, s: String, maxW: Float) {
        val w = paint.measureText(s)
        if (w > maxW && w > 0f) paint.textSize = paint.textSize * maxW / w
    }

    // 12 · Palabras: "Son las diez / y cuarto"
    private fun wordsFace(c: Canvas, i: ClockInfo, a: Float, x: Float, top: Float, al: Paint.Align, width: Float, sd: Boolean, clock: RectF, date: RectF) {
        val maxW = width - dp(56f)
        text(p, tfLight, dp(32f) * k, base, al)
        fit(p, i.words1, maxW)
        text(p2, tfBold, dp(40f) * k, tone, al)
        fit(p2, i.words2, maxW)
        val y1 = top + dp(58f)
        val y2 = y1 + p2.textSize * 1.12f
        roll(c, p, i.words1, i.prevWords1, x, y1, (255 * a).toInt(), i.q)
        roll(c, p2, i.words2, i.prevWords2, x, y2, (255 * a).toInt(), i.q)
        val w = max(p.measureText(i.words1), p2.measureText(i.words2))
        val l = leftOf(x, w, al)
        clock.set(l, y1 - p.textSize, l + w, y2 + dp(8f))
        if (sd) dateLine(c, i.date, x, y2 + dp(36f), al, a, date)
    }

    // 13 · Lateral: la hora girada en vertical junto al borde, con la fecha al lado
    private fun side(c: Canvas, i: ClockInfo, a: Float, top: Float, al: Paint.Align, width: Float, sd: Boolean, clock: RectF, date: RectF) {
        val right = al == Paint.Align.RIGHT
        text(p, tfThin, dp(96f) * k, base, Paint.Align.CENTER)
        val half = p.textSize * 0.36f
        val rx = if (right) width - dp(30f) - half else dp(30f) + half
        val ry = top + dp(150f)
        c.save()
        c.rotate(if (right) 90f else -90f, rx, ry)
        roll(c, p, i.time, i.prevTime, rx, ry + half, (255 * a).toInt(), i.q)
        c.restore()
        val tw = p.measureText(i.time)
        clock.set(rx - half - dp(6f), ry - tw / 2f, rx + half + dp(6f), ry + tw / 2f)
        if (sd) {
            val dx = if (right) rx - half - dp(18f) else rx + half + dp(18f)
            val dal = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
            text(p2, tfBold, dp(24f) * k, base, dal)
            p2.alpha = (255 * a).toInt()
            c.drawText(i.weekday, dx, ry - dp(4f), p2)
            dateLine(c, i.dayNum + " " + i.monthYear, dx, ry + dp(22f), dal, a, date, dp(15f), tfReg)
            val w = max(p2.measureText(i.weekday), date.width())
            val l = if (right) dx - w else dx
            date.set(l, ry - dp(30f), l + w, ry + dp(28f))
        }
    }

    // 14 · Burbujas: cada cifra dentro de su propia burbuja de cristal
    private fun bubbles(c: Canvas, i: ClockInfo, a: Float, x: Float, top: Float, al: Paint.Align, sd: Boolean, live: Boolean, clock: RectF, date: RectF) {
        val s = i.hour + i.min
        val n = s.length
        val hl = i.hour.length
        val d = dp(64f) * k
        val gap = dp(8f)
        val colon = dp(16f)
        val total = n * d + (n - 1) * gap + colon
        val l = leftOf(x, total, al)
        val cy = top + dp(30f) + d / 2f
        val old = i.prevHour + i.prevMin
        val t = android.os.SystemClock.uptimeMillis() / 1000f
        var cx = l + d / 2f
        for (j in 0 until n) {
            if (j == hl) {
                // los dos puntos entre horas y minutos
                fill.color = base
                fill.alpha = (200 * a).toInt()
                val mx = cx - d / 2f - gap / 2f + colon / 2f
                c.drawCircle(mx, cy - dp(9f), dp(3.2f), fill)
                c.drawCircle(mx, cy + dp(9f), dp(3.2f), fill)
                cx += colon
            }
            val bob = if (live) sin(t * 2.2f + j * 0.9f) * dp(2.5f) else 0f
            val changed = i.q < 0.999f && old.length == n && old[j] != s[j]
            val sc = if (changed) pop(i.q) else 1f
            r1.set(cx - d / 2f * sc, cy - d / 2f * sc + bob, cx + d / 2f * sc, cy + d / 2f * sc + bob)
            glass.draw(c, r1, d / 2f * sc, a, true, S.SF_DATE)
            text(p, tfMed, d * 0.56f * sc, if (j < hl) base else tone, Paint.Align.CENTER)
            p.alpha = (255 * a).toInt()
            c.drawText(s[j].toString(), cx, cy + bob + p.textSize * 0.36f, p)
            cx += d + gap
        }
        clock.set(l, cy - d / 2f, l + total, cy + d / 2f)
        if (sd) dateLine(c, i.date, x, cy + d / 2f + dp(32f), al, a, date)
    }

    // 15 · Degradado: cifras gruesas pintadas con un degradado de color
    private fun gradient(c: Canvas, i: ClockInfo, a: Float, x: Float, top: Float, al: Paint.Align, sd: Boolean, clock: RectF, date: RectF) {
        val y = top + dp(112f)
        text(p, tfBlack, dp(100f) * k, base, al)
        p.letterSpacing = -0.03f
        val w = p.measureText(i.time)
        val l = leftOf(x, w, al)
        val g = grad
        if (g != null) {
            m.setScale(max(1f, w), 1f)
            m.postTranslate(l, 0f)
            g.setLocalMatrix(m)
            p.shader = g
        }
        roll(c, p, i.time, i.prevTime, x, y, (255 * a).toInt(), i.q)
        p.shader = null
        clock.set(l, y - p.textSize * 0.76f, l + w, y + dp(6f))
        if (sd) dateLine(c, i.date, x, y + dp(38f), al, a, date)
    }

    // 16 · Calendario: el día del mes en grande, el día de la semana y la hora al lado
    private fun calendar(c: Canvas, i: ClockInfo, a: Float, x: Float, top: Float, al: Paint.Align, clock: RectF) {
        text(p, tfBold, dp(92f) * k, accent, Paint.Align.LEFT)
        val dw = p.measureText(i.dayNum)
        text(p2, tfMed, dp(22f) * k, base, Paint.Align.LEFT)
        text(small, tfReg, dp(16f) * k, dateCol, Paint.Align.LEFT)
        val rw = max(max(p2.measureText(i.weekday), small.measureText(i.monthYear)), dp(80f) * k)
        val total = dw + dp(14f) + rw
        val l = leftOf(x, total, al)
        val base0 = top + dp(104f)
        p.alpha = (255 * a).toInt()
        c.drawText(i.dayNum, l, base0, p)
        val rx = l + dw + dp(14f)
        p2.alpha = (255 * a).toInt()
        c.drawText(i.weekday, rx, top + dp(52f), p2)
        small.alpha = (210 * a).toInt()
        c.drawText(i.monthYear, rx, top + dp(76f), small)
        text(p2, tfLight, dp(26f) * k, base, Paint.Align.LEFT)
        roll(c, p2, i.time, i.prevTime, rx, top + dp(104f), (255 * a).toInt(), i.q)
        clock.set(l, top + dp(24f), l + total, base0 + dp(8f))
    }

    // 17 · Doble zona: la hora de aquí y la de otra ciudad, lado a lado
    private fun dual(c: Canvas, i: ClockInfo, a: Float, x: Float, top: Float, al: Paint.Align, width: Float, sd: Boolean, clock: RectF, date: RectF) {
        val col = min(dp(150f) * k, (width - dp(72f)) / 2f)
        val total = col * 2 + dp(24f)
        val l = leftOf(x, total, al)
        val lx = l + col / 2f
        val rx = l + col + dp(24f) + col / 2f
        text(small, tfMed, dp(13f), dateCol, Paint.Align.CENTER)
        small.alpha = (190 * a).toInt()
        c.drawText("Aquí", lx, top + dp(32f), small)
        c.drawText(i.zone2Label, rx, top + dp(32f), small)
        text(p, tfLight, dp(52f) * k, base, Paint.Align.CENTER)
        fit(p, i.time, col)
        roll(c, p, i.time, i.prevTime, lx, top + dp(92f), (255 * a).toInt(), i.q)
        text(p2, tfLight, dp(52f) * k, tone, Paint.Align.CENTER)
        fit(p2, i.zone2, col)
        p2.alpha = (255 * a).toInt()
        c.drawText(i.zone2, rx, top + dp(92f), p2)
        small.alpha = (160 * a).toInt()
        c.drawText(i.zone2Diff, rx, top + dp(116f), small)
        fill.color = accent
        fill.alpha = (200 * a).toInt()
        val mx = l + col + dp(12f)
        c.drawRoundRect(mx - dp(1.2f), top + dp(22f), mx + dp(1.2f), top + dp(118f), dp(1.2f), dp(1.2f), fill)
        clock.set(l, top + dp(16f), l + total, top + dp(122f))
        if (sd) dateLine(c, i.date, l + total / 2f, top + dp(150f), Paint.Align.CENTER, a, date)
    }

    // 18 · Puntos: cifras de LED en una rejilla de puntos (los apagados se ven tenues)
    private fun dots(c: Canvas, i: ClockInfo, a: Float, x: Float, top: Float, al: Paint.Align, sd: Boolean, clock: RectF, date: RectF) {
        val s = i.hour + ":" + i.min
        val step = dp(9f) * k
        val rad = dp(3.3f) * k
        var cols = 0
        for (ch in s) cols += if (ch == ':') 2 else 6
        cols -= 1
        val w = cols * step
        val l = leftOf(x, w, al)
        val t = top + dp(40f)
        var cx = l
        for (ch in s) {
            if (ch == ':') {
                fill.color = accent
                fill.alpha = (255 * a).toInt()
                c.drawCircle(cx + rad, t + 2 * step + rad, rad, fill)
                c.drawCircle(cx + rad, t + 4 * step + rad, rad, fill)
                cx += 2 * step
                continue
            }
            val dgt = ch - '0'
            if (dgt !in 0..9) continue
            val rows = DOT_FONT[dgt]
            for (rr in 0 until 7) for (cc in 0 until 5) {
                val on = rows[rr] and (1 shl cc) != 0
                fill.color = base
                fill.alpha = ((if (on) 255 else 26) * a).toInt()
                c.drawCircle(cx + cc * step + rad, t + rr * step + rad, rad, fill)
            }
            cx += 6 * step
        }
        clock.set(l, t, l + w, t + 7 * step)
        if (sd) dateLine(c, i.date, x, t + 7 * step + dp(34f), al, a, date)
    }

    // 19 · Segmentos: cifras de siete segmentos como un reloj digital (los apagados en sombra)
    private fun segments(c: Canvas, i: ClockInfo, a: Float, x: Float, top: Float, al: Paint.Align, sd: Boolean, clock: RectF, date: RectF) {
        val s = i.hour + ":" + i.min
        val cw = dp(46f) * k
        val chh = dp(84f) * k
        val th = dp(8f) * k
        val gap = dp(10f) * k
        var w = 0f
        for (ch in s) w += if (ch == ':') dp(18f) * k else cw + gap
        w -= gap
        val l = leftOf(x, w, al)
        val t = top + dp(36f)
        var cx = l
        for (ch in s) {
            if (ch == ':') {
                fill.color = accent
                fill.alpha = (255 * a).toInt()
                c.drawCircle(cx + dp(9f) * k - gap / 2f, t + chh * 0.32f, th * 0.6f, fill)
                c.drawCircle(cx + dp(9f) * k - gap / 2f, t + chh * 0.68f, th * 0.6f, fill)
                cx += dp(18f) * k
                continue
            }
            val dgt = ch - '0'
            if (dgt !in 0..9) continue
            val bits = SEG[dgt]
            for (sg in 0 until 7) {
                val on = bits and (1 shl sg) != 0
                segRect(sg, cx, t, cw, chh, th)
                fill.color = base
                fill.alpha = ((if (on) 255 else 22) * a).toInt()
                c.drawRoundRect(r2, th / 2f, th / 2f, fill)
            }
            cx += cw + gap
        }
        clock.set(l, t, l + w, t + chh)
        if (sd) dateLine(c, i.date, x, t + chh + dp(34f), al, a, date)
    }

    private fun segRect(sg: Int, x: Float, y: Float, w: Float, h: Float, t: Float) {
        val o = t * 0.6f
        when (sg) {
            0 -> r2.set(x + o, y, x + w - o, y + t)
            1 -> r2.set(x + w - t, y + o, x + w, y + h / 2f - t * 0.4f)
            2 -> r2.set(x + w - t, y + h / 2f + t * 0.4f, x + w, y + h - o)
            3 -> r2.set(x + o, y + h - t, x + w - o, y + h)
            4 -> r2.set(x, y + h / 2f + t * 0.4f, x + t, y + h - o)
            5 -> r2.set(x, y + o, x + t, y + h / 2f - t * 0.4f)
            else -> r2.set(x + o, y + h / 2f - t / 2f, x + w - o, y + h / 2f + t / 2f)
        }
    }

    // 20 · Arco del día: el sol recorre un arco de 6:00 a 20:00 y la hora va debajo
    private fun dayArc(c: Canvas, i: ClockInfo, a: Float, top: Float, al: Paint.Align, width: Float, sd: Boolean, clock: RectF, date: RectF) {
        val r = dp(118f) * k
        val cx = anchorX(al, width, r)
        val cy = top + dp(30f) + r
        r1.set(cx - r, cy - r, cx + r, cy + r)
        stroke.pathEffect = dash
        stroke.strokeWidth = dp(2.5f)
        stroke.color = base
        stroke.alpha = (110 * a).toInt()
        c.drawArc(r1, 180f, 180f, false, stroke)
        stroke.pathEffect = null
        stroke.strokeWidth = dp(1.4f)
        stroke.alpha = (70 * a).toInt()
        c.drawLine(cx - r - dp(14f), cy, cx + r + dp(14f), cy, stroke)
        val minutes = i.h24 * 60 + i.m
        val t = (minutes - 360) / 840f
        if (t in 0f..1f) {
            val ang = PI * (1.0 - t)
            val sx = cx + cos(ang).toFloat() * r
            val sy = cy - sin(ang).toFloat() * r
            fill.color = accent
            fill.alpha = (60 * a).toInt()
            c.drawCircle(sx, sy, dp(22f), fill)
            fill.alpha = (255 * a).toInt()
            c.drawCircle(sx, sy, dp(9f), fill)
            fill.color = Color.WHITE
            fill.alpha = (255 * a).toInt()
            c.drawCircle(sx, sy, dp(4f), fill)
        } else {
            // de noche: una luna en lo alto del arco
            fill.color = base
            fill.alpha = (200 * a).toInt()
            c.drawCircle(cx, cy - r, dp(9f), fill)
            fill.color = Color.BLACK
            fill.alpha = (150 * a).toInt()
            c.drawCircle(cx + dp(4f), cy - r - dp(3f), dp(7.5f), fill)
        }
        text(small, tfMed, dp(12f), dateCol, Paint.Align.CENTER)
        small.alpha = (150 * a).toInt()
        c.drawText("6:00", cx - r, cy + dp(20f), small)
        c.drawText("20:00", cx + r, cy + dp(20f), small)
        text(p, tfLight, dp(46f) * k, base, Paint.Align.CENTER)
        roll(c, p, i.time, i.prevTime, cx, cy - dp(16f), (255 * a).toInt(), i.q)
        clock.set(cx - r, cy - r, cx + r, cy + dp(24f))
        if (sd) dateLine(c, i.date, cx, cy + dp(52f), Paint.Align.CENTER, a, date)
    }
}
