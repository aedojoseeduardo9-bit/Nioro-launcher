package com.nioro.launcher

import android.content.Context
import android.content.SharedPreferences

/**
 * Motor de ajustes de Nioro Launcher.
 *
 * Cada ajuste se declara UNA vez aquí (tipo, rango, valor por defecto, categoría y grupo).
 * La pantalla de ajustes avanzados se genera sola a partir de esta lista, y el launcher lee
 * los valores desde un arreglo en memoria (muy rápido: se puede leer en cada fotograma).
 */
object S {
    const val BOOL = 0
    const val NUM = 1
    const val CHOICE = 2
    const val COLOR = 3

    class Def(
        val id: Int, val key: String, val title: String, val cat: Int, val group: String,
        val type: Int, val min: Float, val max: Float, val def: Float, val step: Float,
        val opts: Array<String>?, val unit: String, val desc: String?
    )

    val defs = ArrayList<Def>()
    private var v = FloatArray(0)
    private var loaded = false
    private var sp: SharedPreferences? = null
    /** sube con cada cambio: las vistas lo comparan para saber si deben recalcular */
    @Volatile var version = 0
        private set

    // ------------------------------------------------------------------ Categorías
    const val C_GLASS = 0
    const val C_TEXT = 1
    const val C_ICON = 2
    const val C_MOTION = 3
    const val C_STYLE = 4
    const val C_GESTURE = 5
    const val C_HOME = 6
    const val C_CLOCK = 7
    const val C_DOCK = 8
    const val C_DRAWER = 9
    const val C_RECENTS = 10
    const val C_EDIT = 11
    const val C_HAPTIC = 12
    const val C_COLOR = 13
    const val C_PERF = 14

    val CATS = arrayOf(
        "Cristal líquido", "Textos", "Iconos", "Física de animaciones", "Estilos de animación",
        "Gestos", "Pantalla de inicio", "Reloj y fecha", "Dock", "Vista de apps",
        "Apps en segundo plano", "Modo edición y menús", "Vibración", "Colores y fondo", "Rendimiento"
    )
    val CAT_SUB = arrayOf(
        "Tinte, borde, lente y sombra de cada superficie",
        "Tamaño, grosor, color y sombra de cada texto",
        "Escala, saturación, halo y contorno por zona",
        "Velocidad y rebote de cada movimiento",
        "Ajuste fino de los 5 estilos de cada animación",
        "Doble toque, dos dedos, pellizcar y más",
        "Márgenes, puntos de página y diseño",
        "Formatos, posición, saludo y batería",
        "Botones, apps abiertas, tamaño y posición",
        "Orden, búsqueda y las 5 vistas",
        "Tarjetas, Cerrar todo y límites",
        "Modo edición y menú contextual",
        "Qué vibra y con qué efecto",
        "Acento, oscurecer fondo y degradados",
        "Calidad, frecuencia y contador de FPS"
    )
    val CAT_GLYPH = intArrayOf(
        Glyphs.PALETTE, Glyphs.STYLE, Glyphs.GRID, Glyphs.MOTION, Glyphs.MOTION,
        Glyphs.MORE, Glyphs.HOME, Glyphs.CLOCK, Glyphs.DOCK, Glyphs.GRID,
        Glyphs.RECENTS, Glyphs.GEAR, Glyphs.BATTERY, Glyphs.WALLPAPER, Glyphs.BATTERY
    )
    val CAT_COLOR = intArrayOf(
        0xFF3E91FF.toInt(), 0xFFB45CFF.toInt(), 0xFF00B8A9.toInt(), 0xFFFF7A45.toInt(), 0xFFFF4D8D.toInt(),
        0xFF7C5CFF.toInt(), 0xFF3E91FF.toInt(), 0xFF2FC2C2.toInt(), 0xFF7C5CFF.toInt(), 0xFF00B8A9.toInt(),
        0xFFFF4D8D.toInt(), 0xFF8A94A6.toInt(), 0xFFFFB020.toInt(), 0xFFB45CFF.toInt(), 0xFF2FC26B.toInt()
    )

    // ------------------------------------------------------------------ Paleta de colores
    val PAL_NAMES = arrayOf(
        "Automático", "Blanco", "Azul noche", "Azul One UI", "Cian", "Turquesa", "Verde", "Lima",
        "Amarillo", "Naranja", "Rojo", "Rosa", "Magenta", "Morado", "Índigo", "Gris", "Negro"
    )
    val PAL = intArrayOf(
        0, 0xFFFFFFFF.toInt(), 0xFF0C142C.toInt(), 0xFF3E91FF.toInt(), 0xFF22D3EE.toInt(), 0xFF14B8A6.toInt(),
        0xFF22C55E.toInt(), 0xFFA3E635.toInt(), 0xFFFACC15.toInt(), 0xFFFB923C.toInt(), 0xFFEF4444.toInt(),
        0xFFF472B6.toInt(), 0xFFD946EF.toInt(), 0xFF8B5CF6.toInt(), 0xFF6366F1.toInt(), 0xFF94A3B8.toInt(),
        0xFF000000.toInt()
    )

    // ------------------------------------------------------------------ Declaración
    private fun add(key: String, title: String, cat: Int, group: String, type: Int, min: Float, max: Float,
                    def: Float, step: Float, opts: Array<String>?, unit: String, desc: String?): Int {
        val id = defs.size
        defs.add(Def(id, key, title, cat, group, type, min, max, def, step, opts, unit, desc))
        return id
    }

    fun bool(key: String, title: String, cat: Int, group: String, def: Boolean, desc: String? = null) =
        add(key, title, cat, group, BOOL, 0f, 1f, if (def) 1f else 0f, 1f, null, "", desc)

    fun num(key: String, title: String, cat: Int, group: String, min: Float, max: Float, def: Float,
            unit: String = "%", step: Float = 1f, desc: String? = null) =
        add(key, title, cat, group, NUM, min, max, def, step, null, unit, desc)

    fun choice(key: String, title: String, cat: Int, group: String, opts: Array<String>, def: Int, desc: String? = null) =
        add(key, title, cat, group, CHOICE, 0f, (opts.size - 1).toFloat(), def.toFloat(), 1f, opts, "", desc)

    fun color(key: String, title: String, cat: Int, group: String, def: Int, desc: String? = null) =
        add(key, title, cat, group, COLOR, 0f, (PAL.size - 1).toFloat(), def.toFloat(), 1f, PAL_NAMES, "", desc)

    // ------------------------------------------------------------------ Lectura / escritura
    fun init(ctx: Context) {
        if (loaded) return
        val p = ctx.applicationContext.getSharedPreferences("nioro_s", Context.MODE_PRIVATE)
        sp = p
        val arr = FloatArray(defs.size)
        for (d in defs) arr[d.id] = try { p.getFloat(d.key, d.def) } catch (t: Throwable) { d.def }
        v = arr
        loaded = true
    }

    fun f(id: Int): Float = if (loaded) v[id] else defs[id].def
    fun i(id: Int): Int = f(id).toInt()
    fun b(id: Int): Boolean = f(id) >= 0.5f
    /** porcentaje como multiplicador (100 % = 1.0) */
    fun p(id: Int): Float = f(id) / 100f
    /** color de la paleta; 0 = automático (devuelve fallback) */
    fun col(id: Int, fallback: Int): Int {
        val k = i(id).coerceIn(0, PAL.size - 1)
        return if (k == 0) fallback else PAL[k]
    }

    fun set(id: Int, x: Float) {
        if (!loaded) return
        val d = defs[id]
        val c = x.coerceIn(d.min, d.max)
        v[id] = c
        sp?.edit()?.putFloat(d.key, c)?.apply()
        version++
    }

    fun reset(cat: Int, group: String? = null) {
        val e = sp?.edit() ?: return
        for (d in defs) {
            if (d.cat != cat || (group != null && d.group != group)) continue
            v[d.id] = d.def
            e.remove(d.key)
        }
        e.apply()
        version++
    }

    fun resetIds(ids: IntArray) {
        val e = sp?.edit() ?: return
        for (id in ids) {
            val d = defs[id]
            v[id] = d.def
            e.remove(d.key)
        }
        e.apply()
        version++
    }

    fun resetAll() {
        sp?.edit()?.clear()?.apply()
        for (d in defs) v[d.id] = d.def
        version++
    }

    fun count(): Int = defs.size

    // ==================================================================
    // 1) CRISTAL: 18 superficies × 19 propiedades
    // ==================================================================
    const val SF_DOCK = 0
    const val SF_DATE = 1
    const val SF_DOTS = 2
    const val SF_SEARCH = 3
    const val SF_MENU = 4
    const val SF_GRIDPANEL = 5
    const val SF_LISTPANEL = 6
    const val SF_RECENT_CARD = 7
    const val SF_RECENT_BTN = 8
    const val SF_EDIT_BTN = 9
    const val SF_EDIT_FRAME = 10
    const val SF_DROP = 11
    const val SF_FAN_LABEL = 12
    const val SF_BUBBLE = 13
    const val SF_LP_DOTS = 14
    const val SF_SETTINGS = 15
    const val SF_REMOVE = 16
    const val SF_DRAG = 17
    val SF_NAMES = arrayOf(
        "Dock", "Píldora de fecha", "Puntos de página", "Barra de búsqueda", "Menú contextual",
        "Panel Rejilla", "Panel Lista", "Tarjetas de recientes", "Botón Cerrar todo", "Botones del modo edición",
        "Marco del modo edición", "Hueco al arrastrar", "Nombres del Abanico", "Burbuja del índice A‑Z",
        "Puntos del Launchpad", "Tarjetas de Ajustes", "Píldora Quitar", "Icono arrastrado"
    )
    const val GP_VISIBLE = 0
    const val GP_TINT = 1
    const val GP_TINT_OP = 2
    const val GP_INTENSITY = 3
    const val GP_RADIUS = 4
    const val GP_RIM_W = 5
    const val GP_RIM_B = 6
    const val GP_RIM_COLOR = 7
    const val GP_LENS = 8
    const val GP_LENS_H = 9
    const val GP_CAUSTIC = 10
    const val GP_INNER = 11
    const val GP_SHADOW = 12
    const val GP_SHADOW_S = 13
    const val GP_SHADOW_Y = 14
    const val GP_ALPHA = 15
    const val GP_GRAD = 16
    const val GP_TINT2 = 17
    const val GP_SIZE = 18
    const val GP_N = 19

    val GLASS_BASE: Int = run {
        val base = defs.size
        for (s in SF_NAMES.indices) {
            val g = SF_NAMES[s]
            val k = "glass.$s."
            bool(k + "vis", "Visible", C_GLASS, g, true)
            color(k + "tint", "Color del cristal", C_GLASS, g, 0)
            num(k + "tintop", "Opacidad del tinte", C_GLASS, g, 0f, 250f, 100f)
            num(k + "int", "Intensidad del cristal", C_GLASS, g, 20f, 180f, 100f)
            num(k + "rad", "Redondeo de esquinas", C_GLASS, g, 0f, 200f, 100f)
            num(k + "rimw", "Grosor del borde", C_GLASS, g, 0f, 400f, 100f)
            num(k + "rimb", "Brillo del borde", C_GLASS, g, 0f, 200f, 100f)
            color(k + "rimc", "Color del borde", C_GLASS, g, 0)
            num(k + "lens", "Brillo de lente", C_GLASS, g, 0f, 250f, 100f)
            num(k + "lensh", "Altura de la lente", C_GLASS, g, 20f, 95f, 58f)
            num(k + "caus", "Reflejo inferior (cáustica)", C_GLASS, g, 0f, 300f, 100f)
            num(k + "inner", "Borde interior oscuro", C_GLASS, g, 0f, 300f, 100f)
            bool(k + "sh", "Sombra", C_GLASS, g, true)
            num(k + "shs", "Fuerza de la sombra", C_GLASS, g, 0f, 300f, 100f)
            num(k + "shy", "Distancia de la sombra", C_GLASS, g, 0f, 300f, 100f)
            num(k + "alpha", "Transparencia general", C_GLASS, g, 10f, 150f, 100f)
            bool(k + "grad", "Tinte en degradado", C_GLASS, g, false)
            color(k + "tint2", "Segundo color del degradado", C_GLASS, g, 13)
            num(k + "size", "Tamaño de la superficie", C_GLASS, g, 80f, 120f, 100f)
        }
        base
    }

    fun gp(surf: Int, prop: Int): Int = GLASS_BASE + surf * GP_N + prop

    // ==================================================================
    // 2) TEXTOS: 16 textos × 12 propiedades
    // ==================================================================
    const val TX_CLOCK = 0
    const val TX_DATE = 1
    const val TX_HOURS = 2
    const val TX_MINUTES = 3
    const val TX_LABEL_HOME = 4
    const val TX_LABEL_DRAWER = 5
    const val TX_FAN = 6
    const val TX_LIST = 7
    const val TX_LIST_LETTER = 8
    const val TX_RECENT = 9
    const val TX_MENU = 10
    const val TX_EDIT = 11
    const val TX_SEARCH = 12
    const val TX_CLOSEALL = 13
    const val TX_BUBBLE = 14
    const val TX_GREETING = 15
    val TX_NAMES = arrayOf(
        "Reloj", "Fecha", "Horas (reloj apilado)", "Minutos (reloj apilado)", "Nombres en el inicio",
        "Nombres en la vista de apps", "Nombres del Abanico", "Nombres de la Lista", "Letras de la Lista",
        "Títulos de recientes", "Texto del menú", "Textos del modo edición", "Barra de búsqueda",
        "Botón Cerrar todo", "Letra del índice", "Saludo"
    )
    const val TP_SIZE = 0
    const val TP_WEIGHT = 1
    const val TP_COLOR = 2
    const val TP_OPACITY = 3
    const val TP_SHADOW = 4
    const val TP_SHADOW_R = 5
    const val TP_SHADOW_A = 6
    const val TP_SPACING = 7
    const val TP_CAPS = 8
    const val TP_DY = 9
    const val TP_ITALIC = 10
    const val TP_SERIF = 11
    const val TP_N = 12
    val WEIGHTS = arrayOf("Predeterminado", "Fina", "Extra ligera", "Ligera", "Normal", "Media", "Semi negrita", "Negrita", "Extra negrita", "Negra")

    val TEXT_BASE: Int = run {
        val base = defs.size
        for (t in TX_NAMES.indices) {
            val g = TX_NAMES[t]
            val k = "text.$t."
            num(k + "size", "Tamaño", C_TEXT, g, 50f, 220f, 100f)
            choice(k + "w", "Grosor de letra", C_TEXT, g, WEIGHTS, 0)
            color(k + "c", "Color", C_TEXT, g, 0)
            num(k + "op", "Opacidad", C_TEXT, g, 10f, 100f, 100f)
            bool(k + "sh", "Sombra", C_TEXT, g, true)
            num(k + "shr", "Desenfoque de la sombra", C_TEXT, g, 0f, 300f, 100f)
            num(k + "sha", "Oscuridad de la sombra", C_TEXT, g, 0f, 250f, 100f)
            num(k + "sp", "Espaciado entre letras", C_TEXT, g, -10f, 40f, 0f, "")
            bool(k + "caps", "Todo en mayúsculas", C_TEXT, g, false)
            num(k + "dy", "Desplazamiento vertical", C_TEXT, g, -20f, 20f, 0f, " dp")
            bool(k + "it", "Cursiva", C_TEXT, g, false)
            bool(k + "serif", "Letra con serifa", C_TEXT, g, false)
        }
        base
    }

    fun tp(text: Int, prop: Int): Int = TEXT_BASE + text * TP_N + prop

    // ==================================================================
    // 3) ICONOS: 8 zonas × 16 propiedades
    // ==================================================================
    const val IA_HOME = 0
    const val IA_DOCK = 1
    const val IA_LAUNCHPAD = 2
    const val IA_FAN = 3
    const val IA_GRID = 4
    const val IA_LIST = 5
    const val IA_GRAVITY = 6
    const val IA_RECENTS = 7
    val IA_NAMES = arrayOf("Inicio", "Dock", "Launchpad", "Abanico", "Rejilla", "Lista", "Gravedad", "Recientes")
    const val IP_SCALE = 0
    const val IP_OPACITY = 1
    const val IP_SHADOW = 2
    const val IP_SHADOW_S = 3
    const val IP_PRESS = 4
    const val IP_PRESS_BOUNCE = 5
    const val IP_SAT = 6
    const val IP_BRIGHT = 7
    const val IP_MONO = 8
    const val IP_TINT = 9
    const val IP_TINT_S = 10
    const val IP_ROT = 11
    const val IP_HALO = 12
    const val IP_HALO_S = 13
    const val IP_OUTLINE = 14
    const val IP_OUTLINE_W = 15
    const val IP_N = 16

    val ICON_BASE: Int = run {
        val base = defs.size
        for (a in IA_NAMES.indices) {
            val g = IA_NAMES[a]
            val k = "icon.$a."
            num(k + "scale", "Escala de iconos", C_ICON, g, 50f, 150f, 100f)
            num(k + "op", "Opacidad", C_ICON, g, 10f, 100f, 100f)
            bool(k + "sh", "Sombra", C_ICON, g, true)
            num(k + "shs", "Fuerza de la sombra", C_ICON, g, 0f, 300f, 100f)
            num(k + "press", "Hundimiento al tocar", C_ICON, g, 0f, 40f, 14f)
            num(k + "pressb", "Rebote al soltar", C_ICON, g, 0f, 100f, 50f)
            num(k + "sat", "Saturación", C_ICON, g, 0f, 200f, 100f)
            num(k + "br", "Brillo", C_ICON, g, -50f, 50f, 0f)
            bool(k + "mono", "Blanco y negro", C_ICON, g, false)
            color(k + "tint", "Teñir con color", C_ICON, g, 0)
            num(k + "tints", "Fuerza del teñido", C_ICON, g, 0f, 100f, 50f)
            num(k + "rot", "Inclinación", C_ICON, g, -30f, 30f, 0f, "°")
            bool(k + "halo", "Halo de color detrás", C_ICON, g, false)
            num(k + "halos", "Intensidad del halo", C_ICON, g, 0f, 250f, 100f)
            bool(k + "out", "Contorno blanco", C_ICON, g, false)
            num(k + "outw", "Grosor del contorno", C_ICON, g, 50f, 400f, 100f)
        }
        base
    }

    fun ip(area: Int, prop: Int): Int = ICON_BASE + area * IP_N + prop

    // ==================================================================
    // 4) FÍSICA: 31 movimientos × 3 propiedades
    // ==================================================================
    const val MO_PAGE = 0
    const val MO_DOCK_MAG = 1
    const val MO_B_JUMP = 2
    const val MO_B_PULSE = 3
    const val MO_B_SPIN = 4
    const val MO_B_SHAKE = 5
    const val MO_B_WAVE = 6
    const val MO_PRESS = 7
    const val MO_RELEASE = 8
    const val MO_DRAWER_OPEN = 9
    const val MO_DRAWER_CLOSE = 10
    const val MO_RECENTS_OPEN = 11
    const val MO_RECENTS_CLOSE = 12
    const val MO_MENU = 13
    const val MO_EDIT_IN = 14
    const val MO_EDIT_OUT = 15
    const val MO_OPEN0 = 16 // ...20
    const val MO_RET0 = 21 // ...25
    const val MO_LP_PAGE = 26
    const val MO_RECENTS_CAROUSEL = 27
    const val MO_DISMISS = 28
    const val MO_SCROLL = 29
    const val MO_CONTROLS = 30
    val MO_NAMES = arrayOf(
        "Cambio de página", "Magnificación del dock", "Rebote: Salto", "Rebote: Pulso", "Rebote: Giro",
        "Rebote: Sacudir", "Rebote: Ola", "Hundir icono", "Soltar icono", "Abrir vista de apps",
        "Cerrar vista de apps", "Abrir recientes", "Cerrar recientes", "Menú contextual", "Entrar al modo edición",
        "Salir del modo edición", "Abrir app: Zoom", "Abrir app: Resorte", "Abrir app: Cristal",
        "Abrir app: Giro 3D", "Abrir app: Deslizar", "Volver: Zoom", "Volver: Rebote", "Volver: Cascada",
        "Volver: Cristal", "Volver: Deslizar", "Páginas del Launchpad", "Carrusel de recientes",
        "Descartar tarjeta", "Rebote de listas", "Interruptores y deslizadores"
    )
    const val MP_SPEED = 0
    const val MP_BOUNCE = 1
    const val MP_ON = 2
    const val MP_N = 3

    val MOTION_BASE: Int = run {
        val base = defs.size
        for (m in MO_NAMES.indices) {
            val g = MO_NAMES[m]
            val k = "motion.$m."
            num(k + "speed", "Velocidad", C_MOTION, g, 25f, 300f, 100f)
            num(k + "bounce", "Rebote", C_MOTION, g, 0f, 200f, 100f, desc = "0 % = sin rebote · 200 % = muy elástico")
            bool(k + "on", "Animación activada", C_MOTION, g, true, "Si se desactiva, el cambio es instantáneo")
        }
        base
    }

    fun mp(m: Int, prop: Int): Int = MOTION_BASE + m * MP_N + prop

    // ==================================================================
    // 5) ESTILOS: parámetros propios de cada uno de los 5 estilos
    // ==================================================================
    private val OPEN_N = arrayOf("Zoom", "Resorte", "Cristal", "Giro 3D", "Deslizar")
    private val RET_N = arrayOf("Zoom", "Rebote", "Cascada", "Cristal", "Deslizar")
    private val PAGE_N = arrayOf("Plana", "Cubo", "Cascada", "Zoom", "Carrusel")
    private val DOCK_N = arrayOf("macOS", "Resorte", "Ola", "Lupa", "Sutil")
    private val BOUNCE_N = arrayOf("Salto", "Pulso", "Giro", "Ola", "Sacudir")
    private val REC_N = arrayOf("Carrusel", "Pila 3D", "Cover Flow", "Rejilla", "Burbujas")

    val OPEN_BASE: Int = run {
        val base = defs.size
        for (s in 0 until 5) {
            val g = "Abrir app: " + OPEN_N[s]
            num("so.$s.zoom", "Zoom del icono", C_STYLE, g, 0f, 300f, 100f)
            num("so.$s.corner", "Redondeo de la ventana", C_STYLE, g, 0f, 250f, 100f)
            num("so.$s.fire", "Momento en que se abre la app", C_STYLE, g, 20f, 95f, if (s == 4) 40f else 50f)
            num("so.$s.fade", "Velocidad de desvanecer el icono", C_STYLE, g, 25f, 300f, 100f)
            num("so.$s.extra", if (s == 3) "Ángulo del giro" else if (s == 2) "Destello del cristal" else "Intensidad del efecto", C_STYLE, g, 0f, 250f, 100f)
        }
        base
    }
    val RET_BASE: Int = run {
        val base = defs.size
        for (s in 0 until 5) {
            val g = "Volver al inicio: " + RET_N[s]
            num("sr.$s.dist", "Distancia del movimiento", C_STYLE, g, 0f, 300f, 100f)
            num("sr.$s.stagger", "Escalonado entre iconos", C_STYLE, g, 0f, 300f, 100f)
            num("sr.$s.scale", "Cantidad de escala", C_STYLE, g, 0f, 300f, 100f)
            num("sr.$s.fade", "Velocidad de aparición", C_STYLE, g, 25f, 300f, 100f)
        }
        base
    }
    val PAGE_BASE: Int = run {
        val base = defs.size
        for (s in 0 until 5) {
            val g = "Cambio de página: " + PAGE_N[s]
            num("sp.$s.int", "Intensidad del efecto", C_STYLE, g, 0f, 200f, 100f)
            num("sp.$s.fade", "Desvanecer al pasar", C_STYLE, g, 0f, 200f, 100f)
        }
        base
    }
    val DOCKS_BASE: Int = run {
        val base = defs.size
        for (s in 0 until 5) {
            val g = "Magnificación: " + DOCK_N[s]
            num("sd.$s.radius", "Alcance de la magnificación", C_STYLE, g, 30f, 300f, 100f)
            num("sd.$s.lift", "Elevación de los iconos", C_STYLE, g, 0f, 300f, 100f)
        }
        base
    }
    val BOUNCES_BASE: Int = run {
        val base = defs.size
        for (s in 0 until 5) num("sb.$s.force", "Fuerza", C_STYLE, "Rebote: " + BOUNCE_N[s], 0f, 300f, 100f)
        base
    }
    val RECS_BASE: Int = run {
        val base = defs.size
        for (s in 0 until 5) {
            val g = "Recientes: " + REC_N[s]
            num("sc.$s.size", "Tamaño de tarjetas", C_STYLE, g, 60f, 130f, 100f)
            num("sc.$s.gap", "Separación", C_STYLE, g, 50f, 200f, 100f)
            num("sc.$s.tilt", "Inclinación / profundidad", C_STYLE, g, 0f, 200f, 100f)
        }
        base
    }

    // Vistas de apps
    val LP_CENTER = num("lp.center", "Entrada desde el centro", C_STYLE, "Vista: Launchpad", 0f, 200f, 100f)
    val LP_ZOOM = num("lp.zoom", "Zoom de entrada", C_STYLE, "Vista: Launchpad", 0f, 200f, 100f)
    val LP_ROWS = choice("lp.rows", "Filas", C_STYLE, "Vista: Launchpad", arrayOf("Auto", "3", "4", "5", "6", "7", "8"), 0)
    val LP_DOTS = bool("lp.dots", "Puntos de página", C_STYLE, "Vista: Launchpad", true)
    val FAN_CURVE = num("fan.curve", "Curvatura", C_STYLE, "Vista: Abanico", 0f, 300f, 100f)
    val FAN_GAP = num("fan.gap", "Separación", C_STYLE, "Vista: Abanico", 60f, 200f, 100f)
    val FAN_SIDE = choice("fan.side", "Lado de los nombres", C_STYLE, "Vista: Abanico", arrayOf("Automático", "Izquierda", "Derecha"), 0)
    val FAN_LABELS = bool("fan.labels", "Mostrar nombres", C_STYLE, "Vista: Abanico", true)
    val GRID_H = num("grid.h", "Altura máxima del panel", C_STYLE, "Vista: Rejilla", 40f, 90f, 66f)
    val GRID_TITLE = bool("grid.title", "Título del panel", C_STYLE, "Vista: Rejilla", true)
    val GRID_NUB = bool("grid.nub", "Pico hacia el dock", C_STYLE, "Vista: Rejilla", true)
    val LIST_ROW = num("list.row", "Alto de cada fila", C_STYLE, "Vista: Lista", 40f, 90f, 62f, " dp")
    val LIST_ICON = num("list.icon", "Tamaño de icono", C_STYLE, "Vista: Lista", 28f, 60f, 44f, " dp")
    val LIST_INDEX = bool("list.index", "Índice A‑Z", C_STYLE, "Vista: Lista", true)
    val LIST_LETTERS = bool("list.letters", "Letras de sección", C_STYLE, "Vista: Lista", true)
    val LIST_LINES = bool("list.lines", "Líneas separadoras", C_STYLE, "Vista: Lista", true)
    val GRAV_G = num("grav.g", "Fuerza de gravedad", C_STYLE, "Vista: Gravedad", 0f, 300f, 100f)
    val GRAV_BOUNCE = num("grav.bounce", "Rebote de las esferas", C_STYLE, "Vista: Gravedad", 0f, 100f, 38f)
    val GRAV_FRICTION = num("grav.friction", "Fricción", C_STYLE, "Vista: Gravedad", 0f, 100f, 30f)
    val GRAV_SIZE = num("grav.size", "Tamaño de las esferas", C_STYLE, "Vista: Gravedad", 50f, 150f, 100f)
    val GRAV_TILT = num("grav.tilt", "Sensibilidad a la inclinación", C_STYLE, "Vista: Gravedad", 0f, 300f, 100f)
    val GRAV_MAX = num("grav.max", "Máximo de esferas", C_STYLE, "Vista: Gravedad", 10f, 150f, 90f, "")
    val GRAV_THROW = num("grav.throw", "Fuerza al lanzar", C_STYLE, "Vista: Gravedad", 25f, 300f, 100f)
    val PAGE_LOOP = bool("page.loop", "Páginas en bucle", C_STYLE, "Cambio de página: general", false, "De la última página pasas a la primera")
    val PAGE_RUBBER = num("page.rubber", "Estiramiento en los bordes", C_STYLE, "Cambio de página: general", 0f, 200f, 100f)

    // ==================================================================
    // 6) GESTOS
    // ==================================================================
    val ACTIONS = arrayOf(
        "Nada", "Vista de apps", "Notificaciones", "Ajustes rápidos", "Apps recientes", "Modo edición",
        "Buscar apps", "Ajustes del launcher", "Fondo de pantalla", "Primera página", "Linterna",
        "Cámara", "Ajustes del sistema", "Modo ahorro sí/no", "Siguiente vista de apps", "Siguiente forma de iconos",
        "Menú del inicio"
    )
    const val A_NONE = 0
    const val A_DRAWER = 1
    const val A_NOTIF = 2
    const val A_QS = 3
    const val A_RECENTS = 4
    const val A_EDIT = 5
    const val A_SEARCH = 6
    const val A_SETTINGS = 7
    const val A_WALLPAPER = 8
    const val A_FIRST = 9
    const val A_TORCH = 10
    const val A_CAMERA = 11
    const val A_SYS = 12
    const val A_SAVER = 13
    const val A_NEXT_VIEW = 14
    const val A_NEXT_SHAPE = 15
    const val A_HOME_MENU = 16

    const val G_UP = 0
    const val G_DOWN = 1
    const val G_DTAP = 2
    const val G_LONG = 3
    const val G_TWO_UP = 4
    const val G_TWO_DOWN = 5
    const val G_PINCH_IN = 6
    const val G_PINCH_OUT = 7
    const val G_HOME = 8
    const val G_DOCK_UP = 9
    const val G_TRIPLE = 10
    const val G_LEFT_EDGE = 11
    const val G_RIGHT_EDGE = 12
    const val G_TWO_TAP = 13
    val G_NAMES = arrayOf(
        "Deslizar hacia arriba", "Deslizar hacia abajo", "Doble toque", "Mantener presionado el fondo",
        "Dos dedos hacia arriba", "Dos dedos hacia abajo", "Pellizcar", "Separar dos dedos",
        "Botón de inicio estando en el inicio", "Deslizar arriba en el dock", "Triple toque",
        "Deslizar a la izquierda en la primera página", "Deslizar a la derecha en la última página", "Toque con dos dedos"
    )
    private val G_DEF = intArrayOf(A_DRAWER, A_NOTIF, A_NONE, A_EDIT, A_RECENTS, A_QS, A_EDIT, A_DRAWER, A_FIRST, A_DRAWER, A_NONE, A_NONE, A_NONE, A_NONE)

    val GESTURE_BASE: Int = run {
        val base = defs.size
        for (g in G_NAMES.indices) {
            choice("gest.$g.a", "Acción", C_GESTURE, G_NAMES[g], ACTIONS, G_DEF[g])
            bool("gest.$g.v", "Vibrar al hacerlo", C_GESTURE, G_NAMES[g], false)
        }
        base
    }

    fun gestAction(g: Int): Int = i(GESTURE_BASE + g * 2)
    fun gestHaptic(g: Int): Boolean = b(GESTURE_BASE + g * 2 + 1)

    val G_SWIPE_DIST = num("gest.dist", "Distancia para deslizar", C_GESTURE, "Sensibilidad", 50f, 250f, 100f)
    val G_DTAP_MS = num("gest.dtap", "Tiempo del doble toque", C_GESTURE, "Sensibilidad", 150f, 500f, 300f, " ms")
    val G_LONG_MS = num("gest.long", "Tiempo de pulsación larga", C_GESTURE, "Sensibilidad", 250f, 1000f, 520f, " ms")
    val G_ICON_LONG_MS = num("gest.ilong", "Pulsación larga sobre iconos", C_GESTURE, "Sensibilidad", 200f, 900f, 380f, " ms")
    val G_PINCH = num("gest.pinch", "Sensibilidad del pellizco", C_GESTURE, "Sensibilidad", 50f, 250f, 100f)

    // ==================================================================
    // 7) PANTALLA DE INICIO
    // ==================================================================
    private const val HG = "Diseño"
    val H_TOP = num("home.top", "Margen superior de la cuadrícula", C_HOME, HG, -60f, 120f, 0f, " dp")
    val H_BOTTOM = num("home.bottom", "Margen inferior de la cuadrícula", C_HOME, HG, -40f, 120f, 0f, " dp")
    val H_SIDE = num("home.side", "Margen lateral", C_HOME, HG, 0f, 48f, 0f, " dp")
    val H_ICON_DY = num("home.icondy", "Desplazamiento vertical de iconos", C_HOME, HG, -30f, 30f, 0f, " dp")
    val H_LABEL_GAP = num("home.labelgap", "Distancia icono-nombre", C_HOME, HG, -6f, 24f, 4f, " dp")
    val H_LOCK = bool("home.lock", "Bloquear diseño", C_HOME, HG, false, "Impide mover o quitar iconos por accidente")
    val H_AUTO_ADD = bool("home.autoadd", "Añadir apps nuevas al inicio", C_HOME, HG, true)
    val H_REMOVE_DRAG = bool("home.removedrag", "Quitar arrastrando hacia arriba", C_HOME, HG, true)
    val H_SCALE_PANEL = num("home.panelscale", "Alejar el inicio al abrir paneles", C_HOME, HG, 0f, 25f, 8f)
    val H_DEFAULT_PAGE = num("home.defpage", "Página principal", C_HOME, HG, 1f, 6f, 1f, "")
    val H_LAYOUT = choice("home.layout", "Organización de los iconos", C_HOME, HG, HomeLayout.NAMES, 0, "Panal, Sonrisa, Ola, Dividida... (las apps no cambian de sitio)")
    private const val DG = "Puntos de página"
    val D_SHOW = bool("dots.show", "Mostrar indicador de páginas", C_HOME, DG, true)
    val D_STYLE = choice("dots.style", "Estilo", C_HOME, DG, arrayOf("Puntos", "Líneas", "Cápsula activa", "Números", "Píldora One UI"), 4)
    val D_SIZE = num("dots.size", "Tamaño", C_HOME, DG, 50f, 220f, 100f)
    val D_GAP = num("dots.gap", "Separación", C_HOME, DG, 50f, 250f, 100f)
    val D_DY = num("dots.dy", "Posición vertical", C_HOME, DG, -60f, 60f, 0f, " dp")
    val D_COLOR = color("dots.color", "Color del punto activo", C_HOME, DG, 1)
    val D_IDLE = num("dots.idle", "Opacidad de los inactivos", C_HOME, DG, 0f, 100f, 35f)
    val D_HOME = bool("dots.home", "Icono de casa en la página principal", C_HOME, DG, false)

    // ==================================================================
    // 8) RELOJ Y FECHA
    // ==================================================================
    private const val CG = "Reloj"
    val CK_FORMAT = choice("clock.fmt", "Formato de hora", C_CLOCK, CG, arrayOf("Del sistema", "12 horas", "24 horas"), 0)
    val CK_ALIGN = choice("clock.align", "Alineación", C_CLOCK, CG, arrayOf("Centro", "Izquierda", "Derecha"), 0)
    val CK_DY = num("clock.dy", "Posición vertical", C_CLOCK, CG, -60f, 420f, 0f, " dp")
    val CK_TAP = bool("clock.tap", "Tocar el reloj abre las alarmas", C_CLOCK, CG, true)
    val CK_AMPM = bool("clock.ampm", "Mostrar a. m./p. m.", C_CLOCK, CG, true)
    val CK_BLINK = bool("clock.sep", "Separador entre horas y minutos", C_CLOCK, CG, true)
    private const val FG = "Fecha"
    val DT_SHOW = bool("date.show", "Mostrar fecha", C_CLOCK, FG, true)
    val DT_FORMAT = choice("date.fmt", "Formato", C_CLOCK, FG, arrayOf("Lunes 2 de noviembre", "lun, 2 nov", "02/11", "2 de noviembre", "Lunes", "Lunes 02/11/2026"), 0)
    val DT_PILL = bool("date.pill", "Fecha dentro de píldora de cristal", C_CLOCK, FG, true)
    val DT_TAP = bool("date.tap", "Tocar la fecha abre el calendario", C_CLOCK, FG, true)
    val DT_BATTERY = bool("date.battery", "Mostrar batería junto a la fecha", C_CLOCK, FG, false)
    val DT_GREETING = bool("date.greet", "Saludo según la hora", C_CLOCK, FG, false, "Buenos días / Buenas tardes / Buenas noches")
    // se cambian también manteniendo pulsado el reloj (arrastrar y tirador de tamaño)
    val CK_DX = num("clock.dx", "Posición horizontal", C_CLOCK, CG, -180f, 180f, 0f, " dp")
    val CK_SCALE = num("clock.scale", "Tamaño del reloj", C_CLOCK, CG, 50f, 200f, 100f)
    val CK_ZERO = choice("clock.zero", "Cero a la izquierda en la hora", C_CLOCK, CG, arrayOf("Automático", "Siempre (09:05)", "Nunca (9:05)"), 0)
    val CK_ZONE2 = choice("clock.zone2", "Segunda ciudad (diseño Doble zona)", C_CLOCK, CG, ClockFaces.ZONE_NAMES, 0)
    val CK_SEPCH = choice("clock.sepch", "Separador", C_CLOCK, CG, arrayOf("Dos puntos  12:30", "Punto medio  12·30"), 0)

    // ==================================================================
    // 9) DOCK
    // ==================================================================
    private const val KG = "General"
    private const val KS = "Diseño del dock"
    val K_SHOW = bool("dock.show", "Mostrar dock", C_DOCK, KG, true)
    val K_LAUNCHPAD = bool("dock.lp", "Botón Launchpad", C_DOCK, KG, true)
    val K_RECENTS = bool("dock.rec", "Botón Recientes", C_DOCK, KG, true)
    val K_SEP = bool("dock.sep", "Separador", C_DOCK, KG, true)
    val K_LP_RIGHT = bool("dock.lpright", "Launchpad a la derecha", C_DOCK, KG, false)
    val K_MAX = num("dock.max", "Máximo de apps", C_DOCK, KG, 3f, 7f, 5f, "")
    val K_BOTTOM = num("dock.bottom", "Distancia al borde inferior", C_DOCK, KG, 0f, 60f, 12f, " dp")
    val K_EXTRA_W = num("dock.extraw", "Ancho extra de la cápsula", C_DOCK, KG, 0f, 80f, 20f, " dp")
    val K_EXTRA_H = num("dock.extrah", "Alto extra de la cápsula", C_DOCK, KG, -10f, 40f, 22f, " dp")
    // las 3 primeras formas son las de siempre (mismos números): los valores guardados siguen valiendo
    val K_SHAPE = choice("dock.shape", "Forma del dock", C_DOCK, KS, DockDesigns.FORMS, 0)
    val K_MAX_SCALE = num("dock.maxscale", "Escala máxima al magnificar", C_DOCK, KG, 100f, 250f, 190f)
    // solo recuerda qué miniatura marcar en la galería (aplicar un diseño cambia los ajustes de verdad)
    val K_DESIGN = choice("dock.design", "Diseño elegido", C_DOCK, "_interno", DockDesigns.NAMES, 0)
    val K_REFLECT = bool("dock.reflect", "Reflejo de los iconos", C_DOCK, KS, false, "Los iconos se reflejan en el cristal, como en el Mac")
    val K_DOUBLE = bool("dock.double", "Doble borde", C_DOCK, KS, false, "Un segundo filo brillante por dentro del dock")
    private const val KD = "Apps abiertas"
    val K_DOTS = bool("dock.dots", "Puntos de apps abiertas", C_DOCK, KD, true)
    val K_DOT_COLOR = color("dock.dotc", "Color de los puntos", C_DOCK, KD, 1)
    val K_DOT_SIZE = num("dock.dots2", "Tamaño de los puntos", C_DOCK, KD, 50f, 250f, 100f)
    val K_RECENT_MIN = num("dock.recmin", "Considerar abierta durante", C_DOCK, KD, 5f, 240f, 30f, " min")

    // ==================================================================
    // 10) VISTA DE APPS (general)
    // ==================================================================
    private const val AG = "General"
    val DR_SORT = choice("drawer.sort", "Orden de las apps", C_DRAWER, AG, arrayOf("A‑Z", "Z‑A", "Recientes primero", "Instaladas recientemente"), 0)
    val DR_SEARCH = bool("drawer.search", "Barra de búsqueda", C_DRAWER, AG, true)
    val DR_KEYBOARD = bool("drawer.kb", "Abrir teclado automáticamente", C_DRAWER, AG, false)
    val DR_PKG = bool("drawer.pkg", "Buscar también por nombre de paquete", C_DRAWER, AG, false)
    val DR_HIDDEN_SEARCH = bool("drawer.hsearch", "Mostrar apps ocultas al buscar", C_DRAWER, AG, false)
    val DR_DIM = num("drawer.dim", "Oscurecer el fondo", C_DRAWER, AG, 0f, 150f, 100f)
    val DR_LABELS = bool("drawer.labels", "Nombres de apps", C_DRAWER, AG, true)
    val DR_SWIPE_CLOSE = bool("drawer.swclose", "Cerrar deslizando hacia abajo", C_DRAWER, AG, true)

    // ==================================================================
    // 11) RECIENTES
    // ==================================================================
    private const val RG = "General"
    val RC_CLOSEALL = bool("rec.closeall", "Botón Cerrar todo", C_RECENTS, RG, true)
    val RC_DIM = num("rec.dim", "Oscurecer el fondo", C_RECENTS, RG, 0f, 150f, 100f)
    val RC_HEADER = bool("rec.header", "Icono y nombre sobre la tarjeta", C_RECENTS, RG, true)
    val RC_MAX = num("rec.max", "Máximo de apps", C_RECENTS, RG, 3f, 40f, 20f, "")
    val RC_SWIPE = bool("rec.swipe", "Deslizar hacia arriba para quitar", C_RECENTS, RG, true)
    val RC_HOURS = num("rec.hours", "Historial a mostrar", C_RECENTS, RG, 1f, 48f, 12f, " h")

    // ==================================================================
    // 12) MODO EDICIÓN Y MENÚS
    // ==================================================================
    private const val EG = "Modo edición"
    val E_SCALE = num("edit.scale", "Tamaño de las páginas", C_EDIT, EG, 55f, 95f, 76f)
    val E_BTN_WALL = bool("edit.bwall", "Botón Fondo", C_EDIT, EG, true)
    val E_BTN_VIEW = bool("edit.bview", "Botón Vista de apps", C_EDIT, EG, true)
    val E_BTN_MOTION = bool("edit.bmot", "Botón Animaciones", C_EDIT, EG, true)
    val E_BTN_SETTINGS = bool("edit.bset", "Botón Ajustes", C_EDIT, EG, true)
    val E_BTN_SIZE = num("edit.bsize", "Tamaño de los botones", C_EDIT, EG, 60f, 150f, 100f)
    private const val MG = "Menú contextual"
    val M_WIDTH = num("menu.w", "Ancho del menú", C_EDIT, MG, 180f, 320f, 240f, " dp")
    val M_DIM = num("menu.dim", "Oscurecer al abrir el menú", C_EDIT, MG, 0f, 250f, 100f)
    val M_UNINSTALL = bool("menu.uninst", "Opción Desinstalar", C_EDIT, MG, true)
    val M_HIDE = bool("menu.hide", "Opción Ocultar", C_EDIT, MG, true)
    val M_INFO = bool("menu.info", "Opción Información de la app", C_EDIT, MG, true)
    val M_ROW = num("menu.row", "Alto de las opciones", C_EDIT, MG, 40f, 64f, 50f, " dp")

    // ==================================================================
    // 13) VIBRACIÓN
    // ==================================================================
    val HAPTIC_EFFECTS = arrayOf("Tic suave", "Toque de tecla", "Clic", "Pulsación larga", "Confirmar")
    private val HE_NAMES = arrayOf(
        "Tocar un icono", "Recorrer el dock", "Pulsación larga", "Cambiar de página", "Abrir app",
        "Cerrar todo", "Cambiar un ajuste", "Abrir paneles"
    )
    val HAPTIC_ON = bool("hap.on", "Vibración del launcher", C_HAPTIC, "General", true)
    val HAPTIC_BASE: Int = run {
        val base = defs.size
        for (h in HE_NAMES.indices) {
            bool("hap.$h.on", "Activar", C_HAPTIC, HE_NAMES[h], h != 0 && h != 3 && h != 4)
            choice("hap.$h.fx", "Efecto", C_HAPTIC, HE_NAMES[h], HAPTIC_EFFECTS, intArrayOf(0, 0, 3, 0, 1, 1, 1, 0)[h])
        }
        base
    }
    const val HE_ICON = 0
    const val HE_DOCK = 1
    const val HE_LONG = 2
    const val HE_PAGE = 3
    const val HE_OPEN = 4
    const val HE_CLOSEALL = 5
    const val HE_SETTING = 6
    const val HE_PANEL = 7

    // ==================================================================
    // 14) COLORES Y FONDO
    // ==================================================================
    private const val LG = "Colores"
    val L_ACCENT = color("col.accent", "Color de acento", C_COLOR, LG, 3)
    val L_WALL_DIM = num("col.walldim", "Oscurecer el fondo de pantalla", C_COLOR, LG, 0f, 80f, 0f)
    val L_TOP_GRAD = bool("col.topgrad", "Degradado superior (barra de estado)", C_COLOR, LG, false)
    val L_TOP_GRAD_S = num("col.topgrads", "Fuerza del degradado superior", C_COLOR, LG, 0f, 100f, 40f)
    val L_BOT_GRAD = bool("col.botgrad", "Degradado inferior (detrás del dock)", C_COLOR, LG, false)
    val L_BOT_GRAD_S = num("col.botgrads", "Fuerza del degradado inferior", C_COLOR, LG, 0f, 100f, 45f)
    val L_SCRIM = color("col.scrim", "Color de fondo de los paneles", C_COLOR, LG, 0)
    val L_SETTINGS_BG = choice("col.setbg", "Fondo de los ajustes", C_COLOR, LG, arrayOf("Noche azul", "Morado", "Negro puro", "Océano", "Atardecer", "Bosque"), 0)

    // ==================================================================
    // 15) RENDIMIENTO
    // ==================================================================
    private const val PG = "Rendimiento"
    val P_SHADOWS = choice("perf.shadows", "Calidad de sombras", C_PERF, PG, arrayOf("Sin sombras", "Baja", "Alta"), 2)
    val P_ICON_RES = choice("perf.iconres", "Resolución de iconos", C_PERF, PG, arrayOf("Baja (ahorra memoria)", "Normal", "Alta"), 1)
    val P_BLUR_Q = choice("perf.blurq", "Calidad del desenfoque", C_PERF, PG, arrayOf("Baja", "Normal", "Alta"), 1)
    val P_FPS_CAP = choice("perf.fps", "Frecuencia al animar", C_PERF, PG, arrayOf("Máxima", "90 Hz", "60 Hz"), 0)
    val P_REDUCE = bool("perf.reduce", "Reducir movimiento", C_PERF, PG, false, "Animaciones más cortas y sin rebotes")
    val P_FPS = bool("perf.showfps", "Mostrar contador de FPS", C_PERF, PG, false, "Para comprobar la fluidez")
    val P_IDLE = num("perf.idle", "Espera antes de bajar la frecuencia", C_PERF, PG, 200f, 3000f, 1200f, " ms")
}

/** Aplica la física configurada (velocidad, rebote, activada) a un resorte. */
object Mo {
    fun tune(sp: SpringValue, motion: Int, stiff: Float, ratio: Float) {
        if (!S.b(S.mp(motion, S.MP_ON))) {
            sp.tune(6000f, 1f)
            return
        }
        var k = stiff * S.p(S.mp(motion, S.MP_SPEED))
        var r = ratio
        val bounce = S.p(S.mp(motion, S.MP_BOUNCE))
        if (r < 1f) {
            // más rebote = menos amortiguación; 0 % = crítico
            r = if (bounce <= 0f) 1f else (1f - (1f - r) * bounce).coerceIn(0.08f, 1.2f)
        } else if (bounce > 1f) {
            r = (r - (bounce - 1f) * 0.5f).coerceAtLeast(0.35f)
        }
        if (S.b(S.P_REDUCE)) {
            k *= 1.8f
            r = maxOf(r, 1f)
        }
        sp.tune(k.coerceIn(5f, 8000f), r)
    }
}
