package com.nioro.launcher

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Estilos de organización de la pantalla de inicio. Todos usan las mismas celdas (columnas × filas),
 * así que cambiar de estilo no mueve ninguna app de su sitio: solo cambia dónde se dibuja cada celda.
 * Lo usan el inicio ([HomeView]) y la vista previa de Ajustes ([GridPreview]).
 */
object HomeLayout {
    const val GRID = 0
    const val HONEYCOMB = 1
    const val SMILE = 2
    const val WAVE = 3
    const val THUMB = 4
    const val SPLIT = 5
    const val COMPACT = 6
    const val STAIRS = 7

    val NAMES = arrayOf("Cuadrícula", "Panal", "Sonrisa", "Ola", "Al alcance del pulgar", "Dividida", "Compacta", "Escalera")
    val DESC = arrayOf(
        "Filas y columnas parejas, como siempre",
        "Una fila sí y otra no se desplaza media celda, como un panal de abejas",
        "Las filas se curvan en un arco suave",
        "Cada columna sube y baja un poco, como una ola",
        "Los iconos bajan hacia el dock para llegar con el pulgar; arriba queda aire para el reloj",
        "Iconos a los dos lados y el centro libre para lucir el fondo de pantalla",
        "Cuadrícula más estrecha y centrada, con más aire a los lados",
        "Cada fila se corre un poco a la derecha, como escalones"
    )

    /** medidas calculadas de una cuadrícula (se rellena una vez por cambio de tamaño, sin crear objetos al dibujar) */
    class Grid {
        var style = GRID
        var cols = 4
        var rows = 5
        /** distancia entre centros de columnas */
        var step = 0f
        /** centro x de la primera columna */
        var x0 = 0f
        /** hueco central (estilo Dividida) */
        var gap = 0f
        var leftCols = 0
        /** arriba de la primera fila y alto de cada fila */
        var top = 0f
        var cellH = 0f
        /** amplitud de las curvas (Sonrisa, Ola) */
        var amp = 0f
        /** ancho máximo de los nombres */
        val labelW: Float get() = max(dp(24f), step - dp(8f))
    }

    /**
     * Calcula la cuadrícula para el área [left]..[right] × [top]..[top + avail] con [rows] filas.
     * [minCellH] es el alto mínimo de una fila (icono + nombre) para que nada se encime.
     */
    fun setup(g: Grid, style: Int, cols: Int, rows: Int, left: Float, right: Float, top: Float, avail: Float, minCellH: Float) {
        g.style = style.coerceIn(0, NAMES.size - 1)
        g.cols = max(1, cols)
        g.rows = max(1, rows)
        val w = right - left
        var cellH = avail / g.rows
        var t = top
        g.gap = 0f
        g.leftCols = g.cols
        g.amp = 0f
        when (g.style) {
            HONEYCOMB -> {
                // media celda extra de ancho para que las filas desplazadas no se salgan
                g.step = w / (g.cols + 0.5f)
                g.x0 = left + g.step / 2f
            }
            STAIRS -> {
                g.step = w / (g.cols + 2f / 3f)
                g.x0 = left + g.step / 2f
            }
            SPLIT -> {
                g.gap = w * 0.16f
                g.leftCols = (g.cols + 1) / 2
                g.step = (w - g.gap) / g.cols
                g.x0 = left + g.step / 2f
            }
            COMPACT -> {
                val cw = w * 0.84f
                g.step = cw / g.cols
                g.x0 = left + (w - cw) / 2f + g.step / 2f
                val ch = max(minCellH, cellH * 0.88f)
                if (ch < cellH) {
                    t += g.rows * (cellH - ch) / 2f
                    cellH = ch
                }
            }
            THUMB -> {
                g.step = w / g.cols
                g.x0 = left + g.step / 2f
                val ch = max(minCellH, cellH * 0.8f)
                if (ch < cellH) {
                    t += g.rows * (cellH - ch)
                    cellH = ch
                }
            }
            else -> {
                g.step = w / g.cols
                g.x0 = left + g.step / 2f
            }
        }
        if (g.style == SMILE || g.style == WAVE) g.amp = minOf(cellH * 0.2f, dp(22f))
        g.top = t
        g.cellH = cellH
    }

    /** centro x de la celda */
    fun x(g: Grid, col: Int, row: Int): Float {
        var x = g.x0 + col * g.step
        when (g.style) {
            HONEYCOMB -> if (row % 2 == 1) x += g.step / 2f
            STAIRS -> x += (row % 3) * g.step / 3f
            SPLIT -> if (col >= g.leftCols) x += g.gap
        }
        return x
    }

    /** centro y de la fila (sin el ajuste del nombre) */
    fun y(g: Grid, col: Int, row: Int): Float {
        var y = g.top + row * g.cellH + g.cellH / 2f
        when (g.style) {
            SMILE -> {
                // centro abajo, bordes arriba: un arco suave alrededor de la fila
                val half = (g.cols - 1) / 2f
                val d = if (half > 0f) (col - half) / half else 0f
                y += g.amp * (0.5f - d * d)
            }
            WAVE -> {
                val ph = if (g.cols > 1) col / (g.cols - 1f) else 0f
                y += g.amp * 0.5f * sin(ph * 2f * PI.toFloat() + row * 0.8f)
            }
        }
        return y
    }
}
