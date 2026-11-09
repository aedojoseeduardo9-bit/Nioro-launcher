package com.nioro.launcher

import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Todas las matemáticas de las animaciones (5 estilos por categoría) en un solo lugar.
 * Son funciones puras: las usan tanto el launcher real como la vista previa de Ajustes.
 */
object Motion {
    class PT { var tx = 0f; var sc = 1f; var alpha = 1f; var rotY = 0f; var pivot = 0.5f }
    class IT { var dx = 0f; var dy = 0f; var sc = 1f; var alpha = 1f }
    class OF {
        var l = 0f; var t = 0f; var r = 0f; var b = 0f
        var corner = 0f; var bg = 1f; var iconA = 1f; var iconS = 1f; var rotY = 0f; var glow = 0f
    }

    // ---------------- Dock: magnificación ----------------
    // 0 macOS clásico, 1 Resorte, 2 Ola, 3 Lupa, 4 Sutil
    fun liftFactor(mode: Int): Float = when (mode) {
        0 -> 0.5f
        1 -> 0.65f
        2 -> 1.0f
        3 -> 0.8f
        else -> 0.15f
    }

    /** d = distancia al dedo en "casillas" de icono. Devuelve la escala (>= 1). */
    fun dockScale(mode: Int, d: Float, amt: Float): Float {
        val a = abs(d)
        return when (mode) {
            0 -> 1f + amt * exp(-(a * a) / (2f * 1.15f * 1.15f))
            1 -> 1f + amt * exp(-(a * a) / (2f * 1.5f * 1.5f))
            2 -> if (a > 2.85f) 1f else 1f + amt * max(0f, cos(a * 0.55f))
            3 -> if (a < 0.5f) 1f + amt * 1.25f else if (a < 1.5f) 1f + amt * 0.35f else 1f
            else -> 1f + amt * 0.5f * max(0f, 1f - a / 2.2f)
        }
    }

    // ---------------- Transición entre páginas ----------------
    // 0 Plana, 1 Cubo, 2 Cascada, 3 Zoom, 4 Carrusel
    fun pageT(mode: Int, o: Float, w: Float, colFrac: Float, out: PT) {
        pageRaw(mode, o, w, colFrac, out)
        val k = S.p(S.PAGE_BASE + mode * 2)
        val fade = S.p(S.PAGE_BASE + mode * 2 + 1)
        out.rotY *= k
        out.sc = 1f - (1f - out.sc) * k
        if (mode == 2) out.tx = o * w + (out.tx - o * w) * k
        out.alpha = clamp01(1f - (1f - out.alpha) * fade)
    }

    private fun pageRaw(mode: Int, o: Float, w: Float, colFrac: Float, out: PT) {
        val a = abs(o)
        out.tx = 0f
        out.sc = 1f
        out.alpha = 1f
        out.rotY = 0f
        out.pivot = 0.5f
        when (mode) {
            0 -> out.tx = o * w
            1 -> {
                out.tx = o * w
                out.rotY = (-o * 80f).coerceIn(-80f, 80f)
                out.pivot = if (o > 0f) 0f else 1f
                out.alpha = 1f - a * 0.25f
            }
            2 -> {
                val f = if (o > 0f) colFrac else 1f - colFrac
                out.tx = o * w * (1f + 0.6f * f)
                out.alpha = 1f - a * 0.6f
            }
            3 -> {
                out.tx = o * w * 0.4f
                out.sc = 1f - 0.22f * a
                out.alpha = clamp01(1f - a * 1.1f)
            }
            else -> {
                out.tx = o * w * 0.8f
                out.rotY = (-o * 40f).coerceIn(-45f, 45f)
                out.sc = 1f - 0.18f * a
                out.alpha = 1f - a * 0.55f
            }
        }
    }

    // ---------------- Volver al inicio ----------------
    // 0 Zoom, 1 Rebote, 2 Cascada, 3 Cristal, 4 Deslizar
    fun enterTune(style: Int, sp: SpringValue) {
        val m = S.MO_RET0 + style.coerceIn(0, 4)
        when (style) {
            1 -> Mo.tune(sp, m, 190f, 0.5f)
            2 -> Mo.tune(sp, m, 140f, 0.9f)
            4 -> Mo.tune(sp, m, 170f, 0.85f)
            else -> Mo.tune(sp, m, 190f, 0.95f)
        }
    }

    fun enterItem(style: Int, p: Float, idx: Int, n: Int, out: IT) {
        val st = style.coerceIn(0, 4)
        val b = S.RET_BASE + st * 4
        val dist = S.p(b)
        val stagger = S.p(b + 1)
        val scl = S.p(b + 2)
        val fade = S.p(b + 3)
        val frac = if (n <= 1) 0f else idx.toFloat() / (n - 1)
        val delay = (when (style) {
            2 -> frac * 0.5f
            4 -> frac * 0.3f
            1 -> frac * 0.25f
            else -> frac * 0.15f
        } * stagger).coerceIn(0f, 0.85f)
        var l = (p - delay) / (1f - delay)
        if (l < 0f) l = 0f
        val inv = 1f - l
        out.dx = 0f
        out.dy = 0f
        out.sc = 1f
        out.alpha = 1f
        when (style) {
            0 -> { out.sc = 1f + 0.2f * inv * scl; out.alpha = clamp01(l * 1.6f * fade) }
            1 -> { out.sc = 1f - 0.45f * inv * scl; out.alpha = clamp01(l * 3f * fade) }
            2 -> { out.dy = inv * dp(140f) * dist; out.sc = 1f - 0.15f * inv * scl; out.alpha = clamp01(l * 2f * fade) }
            3 -> { out.sc = 1f + 0.08f * inv * scl; out.alpha = clamp01(l * 1.4f * fade) }
            else -> { out.dx = -inv * dp(180f) * dist; out.alpha = clamp01(l * 2f * fade) }
        }
    }

    // ---------------- Abrir app ----------------
    // 0 Zoom, 1 Resorte, 2 Cristal, 3 Giro 3D, 4 Deslizar
    fun openTune(style: Int, sp: SpringValue) {
        val m = S.MO_OPEN0 + style.coerceIn(0, 4)
        when (style) {
            1 -> Mo.tune(sp, m, 190f, 0.55f)
            2 -> Mo.tune(sp, m, 150f, 0.85f)
            3 -> Mo.tune(sp, m, 210f, 0.9f)
            4 -> Mo.tune(sp, m, 230f, 1.0f)
            else -> Mo.tune(sp, m, 300f, 1.0f)
        }
    }

    /** momento (0..1) en que se abre la app de verdad durante la animación */
    fun openFireAt(style: Int): Float = S.p(S.OPEN_BASE + style.coerceIn(0, 4) * 5 + 2)

    fun openFrame(style: Int, p: Float, from: RectF, to: RectF, out: OF) {
        openRaw(style, p, from, to, out)
        val b = S.OPEN_BASE + style.coerceIn(0, 4) * 5
        val zoom = S.p(b)
        val corner = S.p(b + 1)
        val fade = S.p(b + 3)
        val extra = S.p(b + 4)
        out.iconS = 1f + (out.iconS - 1f) * zoom * (if (style == 0 || style == 1) extra else 1f)
        out.corner *= corner
        out.iconA = clamp01(1f - (1f - out.iconA) * fade)
        if (style == 3) out.rotY *= extra
        if (style == 2) out.glow *= extra
    }

    private fun openRaw(style: Int, p: Float, from: RectF, to: RectF, out: OF) {
        val cFrom = from.width() * 0.225f
        val cTo = dp(38f)
        out.rotY = 0f
        out.glow = 0f
        out.iconS = 1f
        out.iconA = 1f
        out.bg = 1f
        when (style) {
            4 -> {
                out.l = to.left
                out.r = to.right
                out.t = lerp(to.top + to.height() * 0.92f, to.top, p)
                out.b = lerp(to.bottom + to.height() * 0.92f, to.bottom, p)
                out.corner = cTo * clamp01(1f - p)
                out.iconA = 0f
            }
            2 -> {
                val pa = clamp01(p * 1.35f)
                val pb = clamp01(p) * clamp01(p)
                out.l = lerp(from.left, to.left, pa)
                out.r = lerp(from.right, to.right, pa)
                out.t = lerp(from.top, to.top, pb)
                out.b = lerp(from.bottom, to.bottom, pb)
                out.corner = lerp(cFrom, cTo, clamp01(p)) * (1f - smooth(0.8f, 1f, p))
                out.glow = sin(clamp01(p) * PI.toFloat())
                out.bg = clamp01(p * 2.2f)
                out.iconA = 1f - clamp01(p * 1.6f)
                out.iconS = 1f + p * 0.6f
            }
            3 -> {
                out.l = lerp(from.left, to.left, p)
                out.t = lerp(from.top, to.top, p)
                out.r = lerp(from.right, to.right, p)
                out.b = lerp(from.bottom, to.bottom, p)
                out.rotY = (1f - clamp01(p)) * 78f
                out.corner = lerp(cFrom, cTo, clamp01(p)) * (1f - smooth(0.85f, 1f, p))
                out.bg = clamp01(p * 1.8f)
                out.iconA = 1f - clamp01(p * 2f)
                out.iconS = 1f + p * 1.2f
            }
            else -> {
                out.l = lerp(from.left, to.left, p)
                out.t = lerp(from.top, to.top, p)
                out.r = lerp(from.right, to.right, p)
                out.b = lerp(from.bottom, to.bottom, p)
                out.corner = lerp(cFrom, cTo, clamp01(p)) * (1f - smooth(0.85f, 1f, p))
                out.bg = clamp01(p * 3f)
                out.iconA = 1f - clamp01(p * 2.2f)
                out.iconS = 1f + p * 1.8f
            }
        }
    }
}
