package com.nioro.launcher

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs

/** Utilidades para que funcione igual en Android 10 a 14 (Samsung, Xiaomi, Pixel...). */
object Compat {

    /**
     * Frecuencia de pantalla inteligente:
     *  - mientras hay una animación se pide el modo más rápido (120 Hz en el S21) para que sea fluida;
     *  - en reposo se deja al sistema elegir (One UI baja a 10-60 Hz y ahorra batería);
     *  - en modo ahorro se pide 60 Hz siempre.
     */
    fun applyRefreshRate(act: Activity, animating: Boolean = false) {
        try {
            @Suppress("DEPRECATION")
            val disp = if (Build.VERSION.SDK_INT >= 30) act.display else act.windowManager.defaultDisplay
            disp ?: return
            val cur = disp.mode
            val same = disp.supportedModes.filter {
                it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
            }
            val wantId = when {
                same.size < 2 -> 0
                Prefs.saver -> (same.minByOrNull { abs(it.refreshRate - 60f) } ?: cur).modeId
                animating -> {
                    val cap = when (S.i(S.P_FPS_CAP)) { 1 -> 91f; 2 -> 61f; else -> 1000f }
                    (same.filter { it.refreshRate <= cap }.maxByOrNull { it.refreshRate } ?: same.minByOrNull { it.refreshRate } ?: cur).modeId
                }
                else -> 0
            }
            val lp = act.window.attributes
            if (lp.preferredDisplayModeId != wantId) {
                lp.preferredDisplayModeId = wantId
                act.window.attributes = lp
            }
            Screen.hz = disp.refreshRate.coerceIn(30f, 165f)
        } catch (t: Throwable) {
        }
    }

    /** Dibujar detrás de las barras del sistema (pantalla completa) en cualquier versión. */
    fun edgeToEdge(act: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            act.window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            act.window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    /** alto del teclado en pantalla (0 si está cerrado o en Android 10) */
    fun ime(ins: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.ime()).bottom else 0

    /** (arriba, abajo) de las barras del sistema. */
    fun bars(ins: WindowInsets): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Pair(b.top, b.bottom)
        } else {
            @Suppress("DEPRECATION")
            Pair(ins.systemWindowInsetTop, ins.systemWindowInsetBottom)
        }
    }
}
