package com.nioro.launcher

/**
 * Reparte los 1001 ajustes del motor (S) dentro de los menús de Ajustes,
 * cada uno en su sección: Más opciones, Iconos, Textos, Cristal, Física, Estilos, Gestos, Vibración.
 */
object Menus {
    const val K_OPT = 0
    const val K_ICON = 1
    const val K_TEXT = 2
    const val K_GLASS = 3
    const val K_PHYS = 4
    const val K_STYLE = 5
    const val K_GEST = 6
    const val K_HAPTIC = 7

    class Sec(val menu: Int, val header: String, val title: String, val kind: Int, val ids: IntArray, val order: Int)

    private var cache: List<Sec>? = null

    // menús (mismos índices que SettingsActivity.PAGE_*)
    private const val HOME = 0
    private const val DOCK = 1
    private const val APPS = 2
    private const val MOTION = 3
    private const val RECENTS = 4
    private const val LOOK = 5
    private const val BATTERY = 6
    private const val GESTURES = 8

    private fun glassMenu(g: String): Int = when (g) {
        "Dock" -> DOCK
        "Barra de búsqueda", "Panel Rejilla", "Panel Lista", "Nombres del Abanico", "Burbuja del índice A‑Z", "Puntos del Launchpad" -> APPS
        "Tarjetas de recientes", "Botón Cerrar todo" -> RECENTS
        "Menú contextual", "Tarjetas de Ajustes" -> LOOK
        else -> HOME
    }

    private fun textMenu(g: String): Int = when (g) {
        "Nombres en la vista de apps", "Nombres del Abanico", "Nombres de la Lista", "Letras de la Lista", "Barra de búsqueda", "Letra del índice" -> APPS
        "Títulos de recientes", "Botón Cerrar todo" -> RECENTS
        "Texto del menú" -> LOOK
        else -> HOME
    }

    private fun iconMenu(g: String): Int = when (g) {
        "Inicio" -> HOME
        "Dock" -> DOCK
        "Recientes" -> RECENTS
        else -> APPS
    }

    private fun motionMenu(g: String): Int = when {
        g == "Magnificación del dock" || g.startsWith("Rebote:") -> DOCK
        g == "Abrir vista de apps" || g == "Cerrar vista de apps" || g == "Páginas del Launchpad" || g == "Rebote de listas" -> APPS
        g == "Abrir recientes" || g == "Cerrar recientes" || g == "Carrusel de recientes" || g == "Descartar tarjeta" -> RECENTS
        g == "Menú contextual" || g == "Interruptores y deslizadores" -> LOOK
        else -> MOTION
    }

    private fun styleMenu(g: String): Int = when {
        g.startsWith("Magnificación:") || g.startsWith("Rebote:") -> DOCK
        g.startsWith("Recientes:") -> RECENTS
        g.startsWith("Vista:") -> APPS
        else -> MOTION
    }

    private fun optTitle(cat: Int, g: String): String = when {
        g == "General" -> when (cat) {
            S.C_DOCK -> "Opciones del dock"
            S.C_DRAWER -> "Opciones de la vista de apps"
            S.C_RECENTS -> "Opciones de recientes"
            else -> "Opciones generales"
        }
        else -> g
    }

    private fun build(): List<Sec> {
        // agrupa los ajustes por (categoría, grupo) conservando el orden de declaración
        val groups = LinkedHashMap<String, ArrayList<Int>>()
        val catOf = HashMap<String, Int>()
        for (d in S.defs) {
            // grupos que empiezan por "_" son internos (p. ej. qué diseño del dock está elegido)
            if (d.group.startsWith("_")) continue
            val k = d.cat.toString() + "|" + d.group
            groups.getOrPut(k) { ArrayList() }.add(d.id)
            catOf[k] = d.cat
        }
        val out = ArrayList<Sec>()
        var order = 0
        for ((k, ids) in groups) {
            val cat = catOf[k] ?: continue
            val g = k.substringAfter('|')
            val arr = ids.toIntArray()
            fun split(x: String): Pair<String, String> =
                if (x.contains(":")) Pair(x.substringBefore(':').trim(), x.substringAfter(':').trim()) else Pair("", x)
            val sec = when (cat) {
                S.C_GLASS -> Sec(glassMenu(g), "Cristal", g, K_GLASS, arr, order)
                S.C_TEXT -> Sec(textMenu(g), "Textos", g, K_TEXT, arr, order)
                S.C_ICON -> Sec(iconMenu(g), "Iconos", "Iconos: " + g, K_ICON, arr, order)
                S.C_MOTION -> {
                    val (pre, rest) = split(g)
                    Sec(motionMenu(g), if (pre.isEmpty()) "Física" else "Física · $pre", rest, K_PHYS, arr, order)
                }
                S.C_STYLE -> {
                    val (pre, rest) = split(g)
                    val header = when {
                        pre.isEmpty() -> "Estilos"
                        pre == "Vista" -> "Las 5 vistas"
                        else -> "Estilos · $pre"
                    }
                    Sec(styleMenu(g), header, rest.replaceFirstChar { it.uppercase() }, K_STYLE, arr, order)
                }
                S.C_GESTURE -> Sec(GESTURES, "Gestos", g, K_GEST, arr, order)
                S.C_HAPTIC -> Sec(GESTURES, "Vibración", if (g == "General") "Vibración general" else g, K_HAPTIC, arr, order)
                S.C_HOME -> Sec(HOME, "Más opciones", g, K_OPT, arr, order)
                S.C_CLOCK -> Sec(HOME, "Reloj y fecha", g, K_OPT, arr, order)
                S.C_EDIT -> if (g == "Menú contextual") Sec(LOOK, "Más opciones", g, K_OPT, arr, order) else Sec(HOME, "Más opciones", g, K_OPT, arr, order)
                S.C_DOCK -> Sec(DOCK, "Más opciones", optTitle(cat, g), K_OPT, arr, order)
                S.C_DRAWER -> Sec(APPS, "Más opciones", optTitle(cat, g), K_OPT, arr, order)
                S.C_RECENTS -> Sec(RECENTS, "Más opciones", optTitle(cat, g), K_OPT, arr, order)
                S.C_COLOR -> Sec(LOOK, "Más opciones", "Colores y fondo", K_OPT, arr, order)
                S.C_PERF -> Sec(BATTERY, "Más opciones", "Rendimiento", K_OPT, arr, order)
                else -> Sec(LOOK, "Más opciones", g, K_OPT, arr, order)
            }
            out.add(sec)
            order++
        }
        // orden de secciones dentro de cada menú
        val headerRank = { h: String ->
            when {
                h == "Más opciones" -> 0
                h == "Reloj y fecha" -> 1
                h == "Gestos" -> 2
                h == "Vibración" -> 3
                h == "Las 5 vistas" -> 4
                h == "Iconos" -> 5
                h == "Textos" -> 6
                h == "Cristal" -> 7
                h.startsWith("Estilos") -> 8
                h.startsWith("Física") -> 9
                else -> 10
            }
        }
        return out.sortedWith(compareBy({ it.menu }, { headerRank(it.header) }, { it.header }, { it.order }))
    }

    fun all(): List<Sec> {
        var c = cache
        if (c == null) {
            c = build()
            cache = c
        }
        return c
    }

    fun forMenu(m: Int): List<Sec> = all().filter { it.menu == m }

    fun countFor(m: Int): Int = forMenu(m).sumOf { it.ids.size }

    fun glyph(sec: Sec): Int = when (sec.kind) {
        K_ICON -> Glyphs.GRID
        K_TEXT -> Glyphs.STYLE
        K_GLASS -> when (sec.title) {
            "Dock" -> Glyphs.DOCK
            "Píldora de fecha" -> Glyphs.CLOCK
            "Barra de búsqueda" -> Glyphs.SEARCH
            "Tarjetas de recientes", "Botón Cerrar todo" -> Glyphs.RECENTS
            "Menú contextual", "Puntos de página", "Puntos del Launchpad" -> Glyphs.MORE
            "Botones del modo edición", "Tarjetas de Ajustes" -> Glyphs.GEAR
            else -> Glyphs.PALETTE
        }
        K_PHYS -> Glyphs.MOTION
        K_STYLE -> Glyphs.MOTION
        K_GEST -> Glyphs.MORE
        K_HAPTIC -> Glyphs.BATTERY
        else -> when (sec.title) {
            "Reloj", "Fecha" -> Glyphs.CLOCK
            "Puntos de página" -> Glyphs.MORE
            "Modo edición" -> Glyphs.HOME
            "Menú contextual" -> Glyphs.MORE
            "Colores y fondo" -> Glyphs.WALLPAPER
            "Rendimiento" -> Glyphs.BATTERY
            else -> Glyphs.GEAR
        }
    }
}
