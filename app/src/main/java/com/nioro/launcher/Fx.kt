package com.nioro.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.View

/** Estilos de texto configurables (S.TX_*): tamaño, grosor, color, sombra, espaciado... */
object Tx {
    private val faces = HashMap<Int, Typeface>()
    private val WEIGHT_VAL = intArrayOf(0, 100, 200, 300, 400, 500, 600, 700, 800, 900)

    private fun face(weight: Int, italic: Boolean, serif: Boolean): Typeface {
        val key = weight * 4 + (if (italic) 1 else 0) + (if (serif) 2 else 0)
        return faces.getOrPut(key) {
            Typeface.create(if (serif) Typeface.SERIF else Typeface.DEFAULT, weight.coerceIn(100, 900), italic)
        }
    }

    /**
     * Aplica el estilo del texto [t] a la pintura.
     * baseSize en px, baseWeight (100..900), sombra base (radio px y alfa 0..255).
     */
    fun apply(p: Paint, t: Int, baseSize: Float, baseWeight: Int, shadowR: Float, shadowA: Int, baseSpacing: Float = 0f) {
        p.textSize = baseSize * S.p(S.tp(t, S.TP_SIZE))
        val wi = S.i(S.tp(t, S.TP_WEIGHT)).coerceIn(0, 9)
        val w = if (wi == 0) baseWeight else WEIGHT_VAL[wi]
        p.typeface = face(w, S.b(S.tp(t, S.TP_ITALIC)), S.b(S.tp(t, S.TP_SERIF)))
        p.color = S.col(S.tp(t, S.TP_COLOR), Color.WHITE)
        p.letterSpacing = baseSpacing + S.f(S.tp(t, S.TP_SPACING)) / 100f
        if (S.b(S.tp(t, S.TP_SHADOW)) && shadowR > 0f) {
            val a = (shadowA * S.p(S.tp(t, S.TP_SHADOW_A))).toInt().coerceIn(0, 255)
            p.setShadowLayer((shadowR * S.p(S.tp(t, S.TP_SHADOW_R))).coerceAtLeast(0.1f), 0f, shadowR * 0.25f, a shl 24)
        } else {
            p.clearShadowLayer()
        }
    }

    /** opacidad configurada del texto (multiplicador 0..1) */
    fun a(t: Int): Float = S.p(S.tp(t, S.TP_OPACITY))
    fun alpha(t: Int, base: Float): Int = (255f * base * a(t)).toInt().coerceIn(0, 255)
    fun dy(t: Int): Float = dp(S.f(S.tp(t, S.TP_DY)))
    fun str(t: Int, s: String): String = if (S.b(S.tp(t, S.TP_CAPS))) s.uppercase() else s
}

/** Dibujo de iconos con los 16 ajustes de cada zona (S.IA_*). */
object IconFx {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val dst = RectF()
    private val filters = arrayOfNulls<ColorMatrixColorFilter>(8)
    private val filterVer = IntArray(8) { -1 }
    private val filterNone = BooleanArray(8)
    private val paths = HashMap<Int, Path>()
    private var pathShape = -1
    /** forma del icono a tamaño 100 para el oscurecido al tocar (se escala con el lienzo) */
    private var unitPath: Path? = null
    private var unitShape = -2
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    private fun filterFor(area: Int): ColorMatrixColorFilter? {
        if (filterVer[area] == S.version) return if (filterNone[area]) null else filters[area]
        filterVer[area] = S.version
        val sat = S.p(S.ip(area, S.IP_SAT))
        val br = S.f(S.ip(area, S.IP_BRIGHT)) * 2.55f
        val mono = S.b(S.ip(area, S.IP_MONO))
        val tintC = S.col(S.ip(area, S.IP_TINT), 0)
        val tintS = S.p(S.ip(area, S.IP_TINT_S))
        if (!mono && sat == 1f && br == 0f && tintC == 0) {
            filterNone[area] = true
            return null
        }
        filterNone[area] = false
        val cm = ColorMatrix()
        cm.setSaturation(if (mono) 0f else sat)
        if (br != 0f) {
            val b = ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, br, 0f, 1f, 0f, 0f, br, 0f, 0f, 1f, 0f, br, 0f, 0f, 0f, 1f, 0f))
            cm.postConcat(b)
        }
        if (tintC != 0) {
            val k = 1f - tintS
            val t = ColorMatrix(floatArrayOf(
                k, 0f, 0f, 0f, Color.red(tintC) * tintS,
                0f, k, 0f, 0f, Color.green(tintC) * tintS,
                0f, 0f, k, 0f, Color.blue(tintC) * tintS,
                0f, 0f, 0f, 1f, 0f
            ))
            cm.postConcat(t)
        }
        val f = ColorMatrixColorFilter(cm)
        filters[area] = f
        return f
    }

    fun scale(area: Int): Float = S.p(S.ip(area, S.IP_SCALE))
    fun pressDepth(area: Int): Float = S.p(S.ip(area, S.IP_PRESS))
    /** rebote al soltar -> razón de amortiguación del resorte */
    fun releaseRatio(area: Int): Float = lerp(1f, 0f, S.p(S.ip(area, S.IP_PRESS_BOUNCE))).coerceAtLeast(0.12f)

    private fun outlinePath(size: Float): Path {
        val shape = maxOf(0, AppRepo.bakedShape)
        if (shape != pathShape) {
            paths.clear()
            pathShape = shape
        }
        val key = size.toInt()
        return paths.getOrPut(key) { AppRepo.shapePath(if (shape == 4) 0 else shape, key.toFloat()) }
    }

    /**
     * Dibuja un icono centrado en (cx, cy). El tamaño ya debe incluir la escala de la zona
     * (multiplicar por [scale]) y el hundimiento al tocar.
     */
    /** [dark] 0..1: oscurece el icono mientras se pulsa (como iOS) */
    fun draw(c: Canvas, b: Bitmap, tint: Int, cx: Float, cy: Float, size: Float, a: Float, area: Int, rot: Float = 0f, dark: Float = 0f) {
        val al = a * S.p(S.ip(area, S.IP_OPACITY))
        if (al <= 0.004f || size < 1f) return
        val r = rot + S.f(S.ip(area, S.IP_ROT))
        if (r != 0f) {
            c.save()
            c.rotate(r, cx, cy)
        }
        if (S.b(S.ip(area, S.IP_HALO))) {
            val hs = S.p(S.ip(area, S.IP_HALO_S))
            val rad = size * 0.9f
            halo.shader = RadialGradient(cx, cy, rad, (tint and 0x00FFFFFF) or ((150 * al * hs).toInt().coerceIn(0, 255) shl 24), tint and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, rad, halo)
        }
        if (S.b(S.ip(area, S.IP_SHADOW))) IconShadow.draw(c, cx, cy, size, al * S.p(S.ip(area, S.IP_SHADOW_S)))
        paint.alpha = (255f * al).toInt().coerceIn(0, 255)
        paint.colorFilter = filterFor(area)
        dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        c.drawBitmap(b, null, dst, paint)
        if (dark > 0.01f) {
            val shape = maxOf(0, AppRepo.bakedShape)
            var up = unitPath
            if (up == null || unitShape != shape) {
                up = AppRepo.shapePath(if (shape == 4) 0 else shape, 100f)
                unitPath = up
                unitShape = shape
            }
            darkPaint.alpha = (72f * dark * al).toInt().coerceIn(0, 255)
            c.save()
            c.translate(cx - size / 2f, cy - size / 2f)
            c.scale(size / 100f, size / 100f)
            c.drawPath(up, darkPaint)
            c.restore()
        }
        if (S.b(S.ip(area, S.IP_OUTLINE))) {
            val w = dp(1.5f) * S.p(S.ip(area, S.IP_OUTLINE_W))
            outline.strokeWidth = w
            outline.alpha = (230 * al).toInt().coerceIn(0, 255)
            val p = outlinePath(size)
            c.save()
            c.translate(cx - size / 2f, cy - size / 2f)
            c.drawPath(p, outline)
            c.restore()
        }
        if (r != 0f) c.restore()
    }
}

/** Vibración configurable por evento (S.HE_*). */
object Hx {
    private val FX = intArrayOf(
        HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.KEYBOARD_TAP,
        HapticFeedbackConstants.CONTEXT_CLICK, HapticFeedbackConstants.LONG_PRESS,
        HapticFeedbackConstants.VIRTUAL_KEY
    )

    fun fire(v: View, event: Int) {
        if (!S.b(S.HAPTIC_ON)) return
        if (!S.b(S.HAPTIC_BASE + event * 2)) return
        val fx = S.i(S.HAPTIC_BASE + event * 2 + 1).coerceIn(0, FX.size - 1)
        v.performHapticFeedback(FX[fx])
    }

    /** vibración de un gesto (si el gesto la tiene activada) */
    fun gesture(v: View, g: Int) {
        if (S.b(S.HAPTIC_ON) && S.gestHaptic(g)) v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }
}
