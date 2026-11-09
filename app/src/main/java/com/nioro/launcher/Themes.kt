package com.nioro.launcher

/**
 * Temas de color: tiñen todo el launcher de una vez (cristal de dock, paneles, menús y tarjetas,
 * color de acento, fondo del cajón de apps y de recientes, fondo de Ajustes, puntos y segundo tono del reloj).
 * Los valores son índices de la paleta S.PAL (0 = automático).
 */
object ColorThemes {
    class Theme(
        val name: String,
        val desc: String,
        val accent: Int,
        val tint: Int,
        val tintOp: Float,
        /** segundo color del degradado del cristal (0 = sin degradado) */
        val tint2: Int,
        val rim: Int,
        val scrim: Int,
        /** fondo de Ajustes (S.L_SETTINGS_BG) */
        val bg: Int,
        val dots: Int,
        val minutes: Int
    )

    val THEMES = arrayOf(
        Theme("Original", "El cristal azul noche de siempre", 3, 0, 100f, 0, 0, 0, 0, 1, 0),
        Theme("Océano", "Azules profundos y cian", 4, 2, 110f, 5, 4, 3, 3, 4, 4),
        Theme("Atardecer", "Naranja y rosa sobre violeta", 9, 10, 70f, 9, 8, 13, 4, 9, 9),
        Theme("Bosque", "Verdes frescos", 6, 5, 85f, 6, 7, 6, 5, 6, 7),
        Theme("Amatista", "Morado y magenta", 13, 14, 100f, 12, 12, 13, 1, 12, 12),
        Theme("Grafito", "Negro y gris, sobrio", 15, 16, 150f, 0, 15, 16, 2, 1, 15)
    )

    /** colores para dibujar la muestra de cada tema en Ajustes: fondo, segundo fondo y acento */
    fun swatch(i: Int): IntArray {
        val t = THEMES[i]
        val a = if (t.tint == 0) 0xFF1B2C78.toInt() else S.PAL[t.tint]
        val b = if (t.tint2 == 0) blend(a, 0xFF000000.toInt(), 0.45f) else S.PAL[t.tint2]
        return intArrayOf(blend(a, 0xFF000000.toInt(), 0.25f), blend(b, 0xFF000000.toInt(), 0.2f), S.PAL[t.accent])
    }

    fun apply(i: Int) {
        val t = THEMES[i.coerceIn(0, THEMES.size - 1)]
        for (sf in S.SF_NAMES.indices) {
            S.set(S.gp(sf, S.GP_TINT), t.tint.toFloat())
            S.set(S.gp(sf, S.GP_TINT_OP), t.tintOp)
            S.set(S.gp(sf, S.GP_GRAD), if (t.tint2 != 0) 1f else 0f)
            S.set(S.gp(sf, S.GP_TINT2), if (t.tint2 != 0) t.tint2.toFloat() else 13f)
            S.set(S.gp(sf, S.GP_RIM_COLOR), t.rim.toFloat())
        }
        S.set(S.L_ACCENT, t.accent.toFloat())
        S.set(S.L_SCRIM, t.scrim.toFloat())
        S.set(S.L_SETTINGS_BG, t.bg.toFloat())
        S.set(S.K_DOT_COLOR, t.dots.toFloat())
        S.set(S.D_COLOR, t.dots.toFloat())
        S.set(S.tp(S.TX_MINUTES, S.TP_COLOR), t.minutes.toFloat())
        Prefs.colorTheme = i
    }
}
