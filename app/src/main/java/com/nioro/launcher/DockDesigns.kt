package com.nioro.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Diseños del dock: 13 formas y 30 diseños listos (forma + material de cristal + detalles).
 *
 * Aplicar un diseño solo escribe ajustes normales (forma del dock, los 19 ajustes de cristal del dock,
 * reflejo y doble borde): después se puede retocar cualquier cosa en Ajustes → Dock.
 */
object DockDesigns {
    // ---------------------------------------------------------------- Formas (S.K_SHAPE)
    const val F_CAPSULE = 0
    const val F_BAR = 1
    const val F_NONE = 2
    const val F_SOFT = 3
    const val F_SQUARE = 4
    const val F_SHELF = 5
    const val F_TAB = 6
    const val F_BUBBLES = 7
    const val F_TILES = 8
    const val F_LINE = 9
    const val F_ISLAND = 10
    const val F_WAVE = 11
    const val F_DOME = 12

    /** las 3 primeras son las de siempre y conservan su número */
    val FORMS = arrayOf(
        "Cápsula flotante", "Barra completa", "Sin fondo", "Rectángulo suave", "Esquinas rectas",
        "Estante 3D", "Pestaña", "Burbujas", "Baldosas", "Línea de luz", "Isla", "Ola", "Cúpula"
    )

    /** formas con un cristal por icono en vez de un fondo común */
    fun perIcon(form: Int): Boolean = form == F_BUBBLES || form == F_TILES

    // ---------------------------------------------------------------- Diseños
    /** un diseño: forma, detalles y los ajustes de cristal que cambia (en las unidades de S) */
    class Design(val name: String, val form: Int, val reflect: Boolean, val double: Boolean, val glass: Map<Int, Float>)

    private fun d(name: String, form: Int, vararg kv: Pair<Int, Float>, reflect: Boolean = false, double: Boolean = false) =
        Design(name, form, reflect, double, mapOf(*kv))

    // colores de la paleta S.PAL
    private const val WHITE = 1f
    private const val NIGHT = 2f
    private const val BLUE = 3f
    private const val CYAN = 4f
    private const val TEAL = 5f
    private const val GREEN = 6f
    private const val LIME = 7f
    private const val YELLOW = 8f
    private const val ORANGE = 9f
    private const val RED = 10f
    private const val PINK = 11f
    private const val MAGENTA = 12f
    private const val PURPLE = 13f
    private const val INDIGO = 14f
    private const val GRAY = 15f
    private const val BLACK = 16f

    val LIST: List<Design> = listOf(
        d("Nioro", F_CAPSULE),
        d("Estante Mac", F_SHELF, S.GP_TINT_OP to 80f, S.GP_LENS to 140f, reflect = true),
        d("Flotante iOS", F_SOFT, S.GP_TINT to WHITE, S.GP_TINT_OP to 35f, S.GP_RIM_B to 160f, S.GP_LENS to 150f),
        d("Barra One UI", F_BAR, S.GP_TINT to NIGHT, S.GP_TINT_OP to 150f, S.GP_RIM_W to 50f, S.GP_LENS to 60f),
        d("Píldora Pixel", F_CAPSULE, S.GP_TINT to GRAY, S.GP_TINT_OP to 200f, S.GP_RIM_W to 0f, S.GP_LENS to 30f, S.GP_CAUSTIC to 0f, S.GP_INNER to 0f),
        d("Transparente", F_NONE),
        d("Isla", F_ISLAND, S.GP_TINT to BLACK, S.GP_TINT_OP to 250f, S.GP_RIM_W to 40f, S.GP_RIM_B to 60f, S.GP_LENS to 20f, S.GP_CAUSTIC to 0f),
        d("Burbujas", F_BUBBLES, S.GP_LENS to 130f),
        d("Baldosas", F_TILES, S.GP_TINT to NIGHT, S.GP_TINT_OP to 120f),
        d("Línea de luz", F_LINE, S.GP_RIM_COLOR to CYAN),
        d("Pestaña", F_TAB, S.GP_TINT to NIGHT, S.GP_TINT_OP to 170f, S.GP_SHADOW to 0f),
        d("Neón cian", F_CAPSULE, S.GP_TINT to BLACK, S.GP_TINT_OP to 140f, S.GP_RIM_COLOR to CYAN, S.GP_RIM_W to 260f, S.GP_RIM_B to 200f, S.GP_LENS to 40f),
        d("Neón rosa", F_SOFT, S.GP_TINT to BLACK, S.GP_TINT_OP to 140f, S.GP_RIM_COLOR to MAGENTA, S.GP_RIM_W to 260f, S.GP_RIM_B to 200f, S.GP_LENS to 40f),
        d("Atardecer", F_CAPSULE, S.GP_GRAD to 1f, S.GP_TINT to ORANGE, S.GP_TINT2 to PINK, S.GP_TINT_OP to 110f),
        d("Océano", F_CAPSULE, S.GP_GRAD to 1f, S.GP_TINT to CYAN, S.GP_TINT2 to INDIGO, S.GP_TINT_OP to 110f),
        d("Aurora", F_SOFT, S.GP_GRAD to 1f, S.GP_TINT to GREEN, S.GP_TINT2 to PURPLE, S.GP_TINT_OP to 105f),
        d("Cristal claro", F_CAPSULE, S.GP_TINT to WHITE, S.GP_TINT_OP to 25f, S.GP_RIM_B to 180f, S.GP_LENS to 180f, S.GP_INNER to 40f),
        d("Hielo", F_SOFT, S.GP_TINT to WHITE, S.GP_TINT_OP to 120f, S.GP_RIM_B to 150f, S.GP_LENS to 120f, S.GP_CAUSTIC to 160f),
        d("Carbón", F_SQUARE, S.GP_TINT to BLACK, S.GP_TINT_OP to 240f, S.GP_RIM_W to 30f, S.GP_LENS to 20f, S.GP_CAUSTIC to 0f, S.GP_INNER to 0f),
        d("Grafito", F_BAR, S.GP_TINT to GRAY, S.GP_TINT_OP to 160f, S.GP_RIM_W to 40f, S.GP_LENS to 50f),
        d("Oro", F_CAPSULE, S.GP_TINT to YELLOW, S.GP_TINT_OP to 90f, S.GP_RIM_COLOR to YELLOW, S.GP_RIM_W to 200f, S.GP_RIM_B to 170f),
        d("Esmeralda", F_SOFT, S.GP_TINT to GREEN, S.GP_TINT_OP to 110f, S.GP_RIM_COLOR to LIME, S.GP_RIM_W to 150f),
        d("Rubí", F_CAPSULE, S.GP_TINT to RED, S.GP_TINT_OP to 110f, S.GP_RIM_COLOR to PINK, S.GP_RIM_W to 150f),
        d("Lavanda", F_CAPSULE, S.GP_TINT to PURPLE, S.GP_TINT_OP to 70f, S.GP_LENS to 140f),
        d("Ola", F_WAVE, S.GP_TINT to BLUE, S.GP_TINT_OP to 90f),
        d("Cúpula", F_DOME, S.GP_LENS to 130f),
        d("Doble borde", F_CAPSULE, S.GP_RIM_W to 160f, double = true),
        d("Sombra flotante", F_CAPSULE, S.GP_TINT_OP to 80f, S.GP_SHADOW_S to 260f, S.GP_SHADOW_Y to 240f),
        d("Reflejo", F_CAPSULE, S.GP_LENS to 130f, reflect = true),
        d("Retro", F_SQUARE, S.GP_TINT to BLUE, S.GP_TINT_OP to 230f, S.GP_RIM_COLOR to WHITE, S.GP_RIM_W to 280f, S.GP_RIM_B to 200f, S.GP_LENS to 0f, S.GP_CAUSTIC to 0f, S.GP_INNER to 0f, S.GP_SHADOW to 0f)
    )

    val NAMES: Array<String> = LIST.map { it.name }.toTypedArray()

    /** valor de cada ajuste de cristal en un diseño (lo que no cambia queda como de fábrica) */
    private fun value(dz: Design, prop: Int): Float = dz.glass[prop] ?: S.defs[S.gp(S.SF_DOCK, prop)].def

    /** aplica el diseño [i]: forma, detalles y los 19 ajustes de cristal del dock */
    fun apply(i: Int) {
        val dz = LIST[i.coerceIn(0, LIST.size - 1)]
        for (prop in 0 until S.GP_N) S.set(S.gp(S.SF_DOCK, prop), value(dz, prop))
        S.set(S.K_SHAPE, dz.form.toFloat())
        S.set(S.K_REFLECT, if (dz.reflect) 1f else 0f)
        S.set(S.K_DOUBLE, if (dz.double) 1f else 0f)
        S.set(S.K_DESIGN, i.toFloat())
    }

    private fun pal(idx: Float, fallback: Int): Int {
        val k = idx.toInt().coerceIn(0, S.PAL.size - 1)
        return if (k == 0) fallback else S.PAL[k]
    }

    /** material del diseño [i] sin aplicarlo (para las miniaturas) */
    fun mat(i: Int, out: GlassMat): GlassMat {
        val dz = LIST[i.coerceIn(0, LIST.size - 1)]
        out.reset()
        out.size = value(dz, S.GP_SIZE) / 100f
        out.tint = pal(value(dz, S.GP_TINT), out.tint)
        out.tintOp = value(dz, S.GP_TINT_OP) / 100f
        out.intensity = value(dz, S.GP_INTENSITY) / 100f
        out.radius = value(dz, S.GP_RADIUS) / 100f
        out.rimW = value(dz, S.GP_RIM_W) / 100f
        out.rimB = value(dz, S.GP_RIM_B) / 100f
        out.rimColor = pal(value(dz, S.GP_RIM_COLOR), 0)
        out.lens = value(dz, S.GP_LENS) / 100f
        out.lensH = value(dz, S.GP_LENS_H) / 100f
        out.caustic = value(dz, S.GP_CAUSTIC) / 100f
        out.inner = value(dz, S.GP_INNER) / 100f
        out.shadow = value(dz, S.GP_SHADOW) >= 0.5f
        out.shadowS = value(dz, S.GP_SHADOW_S) / 100f
        out.shadowY = value(dz, S.GP_SHADOW_Y) / 100f
        out.alpha = value(dz, S.GP_ALPHA) / 100f
        out.grad = value(dz, S.GP_GRAD) >= 0.5f
        if (out.grad) out.tint2 = pal(value(dz, S.GP_TINT2), 0xFF8B5CF6.toInt())
        return out
    }
}

/**
 * Pintor del fondo del dock según su forma. Lo usan el inicio y las miniaturas de Ajustes, así
 * la miniatura es exactamente lo que se verá. No crea objetos al dibujar (rutas y pinturas reutilizadas).
 */
class DockArt {
    val glass = Glass()
    private val settingsMat = GlassMat()
    private val path = Path()
    private val tile = RectF()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPath = Path()

    /** material del dock según los ajustes, o null si el cristal del dock está oculto */
    fun settingsMat(): GlassMat? {
        settingsMat.reset()
        return if (settingsMat.fromSurface(S.SF_DOCK)) settingsMat else null
    }

    /**
     * Rectángulo del fondo para la forma [form]. [capW] = ancho de la cápsula, [cy]/[h] = centro y alto
     * del dock, [w] = ancho de la pantalla, [screenBottom] = borde inferior (para la Pestaña).
     */
    fun bodyRect(form: Int, out: RectF, w: Float, capW: Float, cy: Float, h: Float, screenBottom: Float) {
        when (form) {
            DockDesigns.F_BAR -> out.set(dp(8f), cy - h / 2f, w - dp(8f), cy + h / 2f)
            DockDesigns.F_TAB -> out.set(0f, cy - h / 2f, w, max(cy + h / 2f, screenBottom))
            DockDesigns.F_SHELF -> {
                val sw = min(w - dp(4f), capW + h * 0.5f)
                out.set((w - sw) / 2f, cy - h * 0.04f, (w + sw) / 2f, cy + h / 2f + dp(2f))
            }
            DockDesigns.F_ISLAND -> {
                // algo más ceñida que la cápsula, pero sin que los iconos de los extremos se salgan
                val iw = max(h * 2f, capW - dp(6f))
                out.set((w - iw) / 2f, cy - h * 0.43f, (w + iw) / 2f, cy + h * 0.43f)
            }
            else -> out.set((w - capW) / 2f, cy - h / 2f, (w + capW) / 2f, cy + h / 2f)
        }
    }

    /** redondeo de las formas rectangulares */
    private fun radius(form: Int, r: RectF, h: Float): Float = when (form) {
        DockDesigns.F_BAR -> h * 0.32f
        DockDesigns.F_SOFT -> h * 0.24f
        DockDesigns.F_SQUARE -> h * 0.08f
        DockDesigns.F_ISLAND -> r.height() / 2f
        else -> h * 0.44f
    }

    /** dibuja el fondo común del dock (las formas por icono y "Sin fondo" no dibujan nada aquí) */
    fun drawBody(c: Canvas, form: Int, r: RectF, h: Float, a: Float, m: GlassMat, double: Boolean) {
        when (form) {
            DockDesigns.F_NONE, DockDesigns.F_BUBBLES, DockDesigns.F_TILES -> return
            DockDesigns.F_LINE -> {
                drawLine(c, r, h, a, m)
                return
            }
            DockDesigns.F_SHELF -> {
                shelfPath(r, h)
                // (sin raya extra arriba: sobresalía por las esquinas redondeadas; el borde de cristal ya lo marca)
                glass.drawMat(c, r, h * 0.2f, a, true, m, path)
                return
            }
            DockDesigns.F_TAB -> {
                val rr = h * 0.36f
                path.reset()
                path.addRoundRect(r, floatArrayOf(rr, rr, rr, rr, 0f, 0f, 0f, 0f), Path.Direction.CW)
                glass.drawMat(c, r, rr, a, true, m, path)
                return
            }
            DockDesigns.F_WAVE -> {
                wavePath(r, h)
                glass.drawMat(c, r, h * 0.44f, a, true, m, path)
                return
            }
            DockDesigns.F_DOME -> {
                domePath(r, h)
                glass.drawMat(c, r, h * 0.44f, a, true, m, path)
                return
            }
        }
        val rad = radius(form, r, h)
        glass.drawMat(c, r, rad, a, true, m, null)
        if (double) {
            // segundo filo brillante por dentro
            val ins = dp(4.5f)
            Squircle.path(ringPath, r.left + ins, r.top + ins, r.right - ins, r.bottom - ins, max(0f, rad * m.radius - ins))
            ring.strokeWidth = dp(1.1f)
            ring.color = blend(Color.WHITE, if (m.rimColor != 0) m.rimColor else Color.WHITE, 0.6f)
            ring.alpha = (120 * a * m.alpha).toInt().coerceIn(0, 255)
            c.drawPath(ringPath, ring)
        }
    }

    // ---- cristal por icono guardado en la GPU: se dibuja una vez y luego solo se escala y se mueve
    private val tileNode = android.graphics.RenderNode("tile")
    private var tileKey = 0L
    private var tileSize = 0f
    private var tileW = 0

    /** huella del material: si cambia, la burbuja guardada se vuelve a dibujar */
    private fun matKey(form: Int, m: GlassMat): Long {
        var h = form.toLong()
        h = h * 31 + m.tint
        h = h * 31 + (m.tintOp * 100).toInt()
        h = h * 31 + (m.intensity * 100).toInt()
        h = h * 31 + (m.radius * 100).toInt()
        h = h * 31 + (m.rimW * 100).toInt()
        h = h * 31 + (m.rimB * 100).toInt()
        h = h * 31 + m.rimColor
        h = h * 31 + (m.lens * 100).toInt()
        h = h * 31 + (m.lensH * 100).toInt()
        h = h * 31 + (m.caustic * 100).toInt()
        h = h * 31 + (m.inner * 100).toInt()
        h = h * 31 + (if (m.shadow) 1 else 0)
        h = h * 31 + (m.shadowS * 100).toInt()
        h = h * 31 + (m.shadowY * 100).toInt()
        h = h * 31 + (m.alpha * 100).toInt()
        h = h * 31 + (if (m.grad) m.tint2 else 0)
        h = h * 31 + Prefs.glass
        h = h * 31 + S.i(S.P_SHADOWS) + (if (Prefs.saver) 7 else 0)
        return h
    }

    /** dibuja el cristal de un icono tal cual (centrado en cx, cy) */
    private fun tileRaw(c: Canvas, form: Int, cx: Float, cy: Float, size: Float, a: Float, m: GlassMat) {
        val half = size * 0.6f
        tile.set(cx - half, cy - half, cx + half, cy + half)
        val rad = if (form == DockDesigns.F_BUBBLES) half else half * 0.46f
        glass.drawMat(c, tile, rad, a, true, m, null)
    }

    /**
     * Cristal propio bajo un icono (formas Burbujas y Baldosas). Antes cada burbuja se redibujaba entera
     * en cada fotograma de la magnificación (~8 formas × 7 iconos): tirones. Ahora se graba una vez al
     * tamaño máximo de la magnificación y la GPU solo la escala y la coloca (las burbujas son iguales).
     */
    fun drawTile(c: Canvas, form: Int, cx: Float, cy: Float, size: Float, a: Float, m: GlassMat) {
        if (!c.isHardwareAccelerated || size < 1f) {
            tileRaw(c, form, cx, cy, size, a, m)
            return
        }
        val key = matKey(form, m)
        if (key != tileKey || size > tileSize * 1.01f || !tileNode.hasDisplayList()) {
            // se graba algo más grande que lo pedido: así el icono puede crecer sin volver a grabar
            val ref = max(size, tileSize) * (if (key != tileKey) max(1f, S.p(S.K_MAX_SCALE)) else 1.15f)
            tileKey = key
            tileSize = ref
            val pad = dp(16f) // la sombra sobresale por abajo
            val half = ref * 0.6f
            tileW = (2f * (half + pad)).toInt() + 2
            tileNode.setPosition(0, 0, tileW, tileW)
            tileNode.setUseCompositingLayer(true, null)
            val rc = tileNode.beginRecording()
            tileRaw(rc, form, tileW / 2f, tileW / 2f, ref, 1f, m)
            tileNode.endRecording()
        }
        val k = size / tileSize
        tileNode.setAlpha(clamp01(a))
        c.save()
        c.translate(cx, cy)
        c.scale(k, k)
        c.translate(-tileW / 2f, -tileW / 2f)
        c.drawRenderNode(tileNode)
        c.restore()
    }

    /** Línea de luz: una raya brillante bajo los iconos con un halo suave alrededor */
    private fun drawLine(c: Canvas, r: RectF, h: Float, a: Float, m: GlassMat) {
        val col = if (m.rimColor != 0) m.rimColor else OneColors.ACCENT
        val y = r.bottom - h * 0.12f
        val l = r.left + h * 0.3f
        val rr = r.right - h * 0.3f
        val al = a * m.alpha
        line.shader = null
        // halo: dos franjas anchas y muy tenues (sin desenfoque: barato)
        line.color = (col and 0x00FFFFFF) or ((40 * al).toInt().coerceIn(0, 255) shl 24)
        c.drawRoundRect(l - dp(6f), y - dp(5f), rr + dp(6f), y + dp(5f), dp(5f), dp(5f), line)
        line.color = (col and 0x00FFFFFF) or ((80 * al).toInt().coerceIn(0, 255) shl 24)
        c.drawRoundRect(l - dp(2f), y - dp(2.5f), rr + dp(2f), y + dp(2.5f), dp(2.5f), dp(2.5f), line)
        line.color = (col and 0x00FFFFFF) or ((235 * al).toInt().coerceIn(0, 255) shl 24)
        c.drawRoundRect(l, y - dp(1f), rr, y + dp(1f), dp(1f), dp(1f), line)
    }

    /** estante 3D: un trapecio (más estrecho arriba) con las esquinas redondeadas */
    private fun shelfPath(r: RectF, h: Float) {
        val l = r.left
        val t = r.top
        val rt = r.right
        val b = r.bottom
        val ins = min(h * 0.3f, r.width() * 0.2f)
        val u = 0.22f
        path.reset()
        path.moveTo(l + ins + h * 0.2f, t)
        path.lineTo(rt - ins - h * 0.2f, t)
        path.quadTo(rt - ins, t, rt - ins + ins * u, t + (b - t) * u)
        path.lineTo(rt - ins * u, b - (b - t) * u)
        path.quadTo(rt, b, rt - h * 0.2f, b)
        path.lineTo(l + h * 0.2f, b)
        path.quadTo(l, b, l + ins * u, b - (b - t) * u)
        path.lineTo(l + ins - ins * u, t + (b - t) * u)
        path.quadTo(l + ins, t, l + ins + h * 0.2f, t)
        path.close()
    }

    /** cápsula con el borde de arriba ondulado */
    private fun wavePath(r: RectF, h: Float) {
        val l = r.left
        val t = r.top
        val rt = r.right
        val b = r.bottom
        val rad = min(h * 0.44f, r.height() / 2f)
        val span = r.width() - rad * 2f
        // ondas de tamaño fijo contadas desde el centro: al ensancharse el dock (magnificación) aparecen
        // poco a poco por los lados en vez de cambiar de número de golpe; se apagan hacia los extremos
        val lambda = h * 1.1f
        val cx = r.centerX()
        val amp = h * 0.07f
        path.reset()
        path.moveTo(l + rad, b)
        path.arcTo(l, b - rad * 2f, l + rad * 2f, b, 90f, 90f, false)
        path.lineTo(l, t + rad)
        path.arcTo(l, t, l + rad * 2f, t + rad * 2f, 180f, 90f, false)
        val steps = 48
        for (i in 1..steps) {
            val f = i / steps.toFloat()
            val x = l + rad + span * f
            val env = sin(f * PI.toFloat())
            path.lineTo(x, t - amp * env * kotlin.math.cos((x - cx) / lambda * 2f * PI.toFloat()))
        }
        path.arcTo(rt - rad * 2f, t, rt, t + rad * 2f, 270f, 90f, false)
        path.lineTo(rt, b - rad)
        path.arcTo(rt - rad * 2f, b - rad * 2f, rt, b, 0f, 90f, false)
        path.close()
    }

    /** cápsula con una cúpula suave en el centro del borde de arriba */
    private fun domePath(r: RectF, h: Float) {
        val l = r.left
        val t = r.top
        val rt = r.right
        val b = r.bottom
        val rad = min(h * 0.44f, r.height() / 2f)
        val span = r.width() - rad * 2f
        val cx = r.centerX()
        val peak = t - h * 0.22f
        path.reset()
        path.moveTo(l + rad, b)
        path.arcTo(l, b - rad * 2f, l + rad * 2f, b, 90f, 90f, false)
        path.lineTo(l, t + rad)
        path.arcTo(l, t, l + rad * 2f, t + rad * 2f, 180f, 90f, false)
        path.cubicTo(l + rad + span * 0.28f, t, cx - span * 0.2f, peak, cx, peak)
        path.cubicTo(cx + span * 0.2f, peak, rt - rad - span * 0.28f, t, rt - rad, t)
        path.arcTo(rt - rad * 2f, t, rt, t + rad * 2f, 270f, 90f, false)
        path.lineTo(rt, b - rad)
        path.arcTo(rt - rad * 2f, b - rad * 2f, rt, b, 0f, 90f, false)
        path.close()
    }
}
