package com.nioro.launcher

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

/**
 * Ajustes con estética One UI + Smart Launcher:
 *  - cabecera grande que ocupa la parte alta (zona de "alcance" de One UI) y se colapsa al desplazar
 *  - tarjeta principal con vista previa en vivo y botón para hacerlo predeterminado
 *  - categorías con iconos de color en tarjetas de cristal agrupadas
 *  - subpáginas que entran deslizando con la curva de animación de One UI
 */
class SettingsActivity : Activity() {

    companion object {
        val OPEN = arrayOf("Zoom", "Resorte", "Cristal", "Giro 3D", "Deslizar")
        val RETURN = arrayOf("Zoom", "Rebote", "Cascada", "Cristal", "Deslizar")
        val DOCK = arrayOf("macOS", "Resorte", "Ola", "Lupa", "Sutil")
        val BOUNCE = arrayOf("Salto", "Pulso", "Giro", "Ola", "Sacudir")
        val PAGE = arrayOf("Plana", "Cubo", "Cascada", "Zoom", "Carrusel")
        val RECENTS = arrayOf("Carrusel", "Pila 3D", "Cover Flow", "Rejilla", "Burbujas")
        val DRAWER = arrayOf("Launchpad", "Abanico", "Rejilla", "Lista", "Gravedad", "Playlist", "Panal", "Rueda")
        val DRAWER_DESC = arrayOf(
            "Páginas a pantalla completa, como el Launchpad del Mac",
            "Los iconos salen del dock en curva",
            "Panel de cristal flotante sobre el dock",
            "Lista alfabética con índice A‑Z",
            "Los iconos caen como esferas con física real",
            "Como una lista de Spotify: mantén una app para moverla y deslízala a la derecha para añadirla al inicio",
            "Un panal que mueves con el dedo, como el reloj de Apple: las del centro se ven grandes",
            "Una rueda que giras con el dedo, como un iPod: avanza con un clic en cada app"
        )
        // el número de cada diseño es su posición: 2 = sin reloj; los nuevos van al final
        val CLOCK = arrayOf(
            "Nioro", "Apilado", "Sin reloj", "Grande", "Analógico", "Fino", "Cápsula", "Neón", "Contorno", "Retro",
            "Minimal", "Anillo", "Palabras", "Lateral", "Burbujas", "Degradado", "Calendario", "Doble zona", "Puntos",
            "Segmentos", "Arco del día"
        )
        val THEMES = arrayOf("Liquid Glass", "One UI", "Minimal", "Neón", "Oscuro total", "Cristal claro", "Juguetón", "Rendimiento")
        val THEME_COLORS = intArrayOf(
            0xFF3E91FF.toInt(), 0xFF2F7BFF.toInt(), 0xFF64748B.toInt(), 0xFFD946EF.toInt(),
            0xFF1F2937.toInt(), 0xFF7DD3FC.toInt(), 0xFFFB923C.toInt(), 0xFF22C55E.toInt()
        )
        val SHAPES = arrayOf("Squircle", "Círculo", "One UI", "Gota", "Cristal")
        val SHAPE_DESC = arrayOf(
            "Curva continua como los iconos del iPhone",
            "Iconos redondos",
            "Cuadrado de esquinas suaves como Samsung",
            "Redondos con una esquina en punta",
            "El dibujo flota sobre cristal teñido con borde brillante"
        )
        val COLS = arrayOf("3", "4", "5", "6")
        val ROWS = arrayOf("Auto", "4", "5", "6", "7")

        const val PAGE_HOME = 0
        const val PAGE_DOCK = 1
        const val PAGE_APPS = 2
        const val PAGE_MOTION = 3
        const val PAGE_RECENTS = 4
        const val PAGE_LOOK = 5
        const val PAGE_BATTERY = 6
        const val PAGE_ABOUT = 7
        const val PAGE_GESTURES = 8
        private const val REQ_HOME = 7

        val CAT_TITLES = arrayOf(
            "Pantalla de inicio", "Dock", "Vista de apps", "Animaciones",
            "Apps en segundo plano", "Apariencia", "Batería y rendimiento", "Acerca de", "Gestos y vibración"
        )
        val CAT_SUBS = arrayOf(
            "Organización, cuadrícula, iconos y reloj",
            "30 diseños, magnificación y rebote",
            "5 formas de ver todas tus apps",
            "Abrir, volver y cambiar de página",
            "Estilo de recientes y acceso de uso",
            "Cristal líquido y desenfoque",
            "Ahorro, 120 Hz y consumo",
            "Versión y actualizaciones",
            "14 gestos, sus acciones y la vibración"
        )
        val CAT_GLYPHS = intArrayOf(
            Glyphs.HOME, Glyphs.DOCK, Glyphs.GRID, Glyphs.MOTION,
            Glyphs.RECENTS, Glyphs.PALETTE, Glyphs.BATTERY, Glyphs.INFO, Glyphs.MORE
        )
    }

    private lateinit var stack: FrameLayout
    private val pages = ArrayList<View>()
    private var insetTop = 0
    private var insetBottom = 0
    private val ease = PathInterpolator(0.22f, 0.25f, 0f, 1f) // curva de One UI
    private var heroStatus: (() -> Unit)? = null
    private var triedHomeSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Compat.edgeToEdge(this)
        Compat.applyRefreshRate(this)
        stack = FrameLayout(this)
        stack.addView(BackgroundView(this), FrameLayout.LayoutParams(-1, -1))
        setContentView(stack)
        stack.setOnApplyWindowInsetsListener { _, ins ->
            val (t, b) = Compat.bars(ins)
            if (t != insetTop || b != insetBottom) {
                insetTop = t
                insetBottom = b
                for (p in pages) (p.tag as? (() -> Unit))?.invoke()
            }
            ins
        }
        push(buildMain(), false)
        // si Ajustes se abre sin el launcher en marcha, se cargan las apps para que las vistas previas
        // (inicio, dock, diseños) enseñen tus iconos de verdad
        if (AppRepo.apps.isEmpty()) AppRepo.load(this) { if (!isDestroyed) repaintTree(stack) }
        // al repintarse con un tema nuevo vuelve a la categoría donde estabas
        val start = savedInstanceState?.getInt("cat", -1)?.takeIf { it >= 0 } ?: intent.getIntExtra("page", -1)
        if (start in 0..8) stack.post { push(buildCategory(start), false) }
    }

    /** vuelve a dibujar todas las vistas (p. ej. cuando terminan de cargar los iconos) */
    private fun repaintTree(v: View) {
        v.invalidate()
        if (v is ViewGroup) for (i in 0 until v.childCount) repaintTree(v.getChildAt(i))
    }

    /** categoría abierta ahora (-1 = portada); se guarda para recreate() */
    private var openCat = -1

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("cat", if (pages.size >= 2) openCat else -1)
    }

    override fun onResume() {
        super.onResume()
        heroStatus?.invoke()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        // algunas capas (MIUI) cancelan el diálogo al instante: se abre el ajuste del sistema
        if (requestCode == REQ_HOME && !isDefaultHome() && resultCode != RESULT_OK && !triedHomeSettings) {
            triedHomeSettings = true
            tryStart(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        heroStatus?.invoke()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pages.size > 1) {
            pop()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.stay, R.anim.fade_zoom_out)
    }

    // ---------------------------------------------------------------- Navegación
    private var navBusy = false

    private fun push(v: View, animate: Boolean = true) {
        if (navBusy) return
        val prev = pages.lastOrNull()
        pages.add(v)
        stack.addView(v, FrameLayout.LayoutParams(-1, -1))
        if (animate && prev != null) {
            navBusy = true
            val w = stack.width.toFloat().coerceAtLeast(1f)
            v.translationX = w
            v.animate().translationX(0f).setDuration(420).setInterpolator(ease).withEndAction { navBusy = false }.start()
            // la página de atrás se aleja (se encoge) mientras la nueva entra: da profundidad
            prev.animate().translationX(-w * 0.28f).alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(420).setInterpolator(ease)
                .withEndAction { prev.visibility = View.INVISIBLE }.start()
        } else if (prev != null) {
            prev.translationX = -stack.width * 0.28f
            prev.alpha = 0f
            prev.scaleX = 0.94f
            prev.scaleY = 0.94f
            prev.visibility = View.INVISIBLE
        }
    }

    private fun pop() {
        if (pages.size < 2 || navBusy) return
        navBusy = true
        val top = pages.removeAt(pages.size - 1)
        if (pages.size == 1) openCat = -1
        val prev = pages.last()
        val w = stack.width.toFloat()
        // la página que sale ya no responde a toques
        (top as? FrameLayout)?.addView(View(this).apply { isClickable = true }, FrameLayout.LayoutParams(-1, -1))
        prev.visibility = View.VISIBLE
        top.animate().translationX(w).setDuration(380).setInterpolator(ease)
            .withEndAction {
                stack.removeView(top)
                navBusy = false
            }.start()
        prev.animate().translationX(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(ease).start()
        heroStatus?.invoke()
    }

    // ---------------------------------------------------------------- Página con cabecera One UI
    /**
     * Crea una página: la parte alta muestra un título grande; al desplazar, el título se
     * desvanece y aparece una barra compacta con el título pequeño (como en los Ajustes de Samsung).
     */
    private fun page(title: String, subtitle: String?, root: Boolean, body: (LinearLayout) -> Unit): View {
        val frame = FrameLayout(this)
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        scroll.addView(col)
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // Cabecera grande
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.gravity = Gravity.CENTER
        val big = text(title, 34f, 400)
        big.gravity = Gravity.CENTER
        header.addView(big)
        if (subtitle != null) {
            val sub = text(subtitle, 14f, 400, OneColors.SUB)
            sub.gravity = Gravity.CENTER
            sub.setPadding(0, dp(6f).toInt(), 0, 0)
            header.addView(sub)
        }
        col.addView(header, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.3f).toInt()))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        val ph = dp(16f).toInt()
        content.setPadding(ph, 0, ph, dp(28f).toInt())
        col.addView(content)
        body(content)
        // las tarjetas entran escalonadas, de arriba abajo (como en los Ajustes de iOS)
        for (k in 0 until content.childCount) {
            val ch = content.getChildAt(k)
            ch.alpha = 0f
            ch.translationY = dp(22f)
            ch.animate().alpha(1f).translationY(0f).setStartDelay(70L + k * 35L).setDuration(420)
                .setInterpolator(ease).start()
        }

        // Barra compacta
        val bar = FrameLayout(this)
        bar.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF2070B1A.toInt(), 0x00070B1A))
        val small = text(title, 19f, 500)
        small.alpha = 0f
        val sp = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL)
        sp.leftMargin = dp(if (root) 22f else 60f).toInt()
        bar.addView(small, sp)
        if (!root) {
            val back = View(this)
            back.background = GlyphDrawable(Glyphs.BACK, 26f)
            back.foreground = ripple(dp(22f))
            back.setOnClickListener { pop() }
            val bp = FrameLayout.LayoutParams(dp(44f).toInt(), dp(44f).toInt(), Gravity.CENTER_VERTICAL)
            bp.leftMargin = dp(10f).toInt()
            bar.addView(back, bp)
        }
        frame.addView(bar, FrameLayout.LayoutParams(-1, dp(64f).toInt()))

        val relayout = {
            (bar.layoutParams as FrameLayout.LayoutParams).height = insetTop + dp(60f).toInt()
            bar.setPadding(0, insetTop, 0, 0)
            bar.requestLayout()
            content.setPadding(ph, 0, ph, dp(28f).toInt() + insetBottom)
        }
        frame.tag = relayout
        relayout()

        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            val h = header.height.toFloat().coerceAtLeast(1f)
            val t = clamp01(y / (h * 0.55f))
            header.alpha = clamp01(1f - t * 1.15f)
            header.translationY = y * 0.35f
            big.scaleX = 1f - 0.08f * t
            big.scaleY = 1f - 0.08f * t
            small.alpha = smooth(0.6f, 1f, t)
        }
        return frame
    }

    // ---------------------------------------------------------------- Página principal
    private fun buildMain(): View = page("Nioro Launcher", "Cristal líquido · dock macOS · One UI", true) { c ->
        // Tarjeta principal (vista previa + predeterminado)
        val hero = card(c)
        val pv = HeroView(this)
        hero.addView(pv, LinearLayout.LayoutParams(-1, dp(210f).toInt()))
        val status = text("", 15f, 500)
        status.gravity = Gravity.CENTER
        status.setPadding(0, dp(14f).toInt(), 0, dp(4f).toInt())
        hero.addView(status)
        val btn = pill("Establecer como pantalla de inicio", true) { makeDefault() }
        hero.addView(btn, LinearLayout.LayoutParams(-1, dp(52f).toInt()).apply { topMargin = dp(10f).toInt() })
        heroStatus = {
            if (isDefaultHome()) {
                status.text = "✓  Nioro Launcher es tu pantalla de inicio"
                status.setTextColor(0xFF7CE0A3.toInt())
                btn.visibility = View.GONE
            } else {
                status.text = "Nioro Launcher aún no es tu pantalla de inicio"
                status.setTextColor(OneColors.SUB)
                btn.visibility = View.VISIBLE
            }
        }
        heroStatus?.invoke()

        // Buscador de todos los ajustes (arriba: es lo más rápido para encontrar algo)
        val sk = card(c, dp(4f).toInt())
        val sr = LinearLayout(this)
        sr.orientation = LinearLayout.HORIZONTAL
        sr.gravity = Gravity.CENTER_VERTICAL
        sr.setPadding(dp(18f).toInt(), dp(14f).toInt(), dp(18f).toInt(), dp(14f).toInt())
        sr.background = ripple(dp(22f))
        val sg = View(this)
        sg.background = GlyphDrawable(Glyphs.SEARCH, 22f, 0xCCFFFFFF.toInt())
        sr.addView(sg, LinearLayout.LayoutParams(dp(22f).toInt(), dp(22f).toInt()))
        sr.addView(text("Buscar entre " + S.count() + " ajustes", 16f, 400, OneColors.SUB).apply { setPadding(dp(14f).toInt(), 0, 0, 0) })
        sr.setOnClickListener { push(buildSearch()) }
        sk.addView(sr)

        // Atajos a lo más usado para personalizar
        sectionLabel(c, "Personaliza en un toque")
        quickCard(c)

        // Temas de color: tiñen todo el launcher
        colorThemesCard(c)

        // Categorías agrupadas como en los Ajustes de One UI, cada grupo con su título
        val groups = arrayOf(
            "Personalización" to intArrayOf(PAGE_HOME, PAGE_DOCK, PAGE_APPS, PAGE_LOOK),
            "Movimiento" to intArrayOf(PAGE_MOTION, PAGE_GESTURES),
            "Sistema" to intArrayOf(PAGE_RECENTS, PAGE_BATTERY),
            "Información" to intArrayOf(PAGE_ABOUT)
        )
        for ((label, g) in groups) {
            sectionLabel(c, label)
            val cc = card(c, dp(6f).toInt())
            for ((n, i) in g.withIndex()) {
                if (n > 0) divider(cc, dp(72f))
                categoryRow(cc, i) { push(buildCategory(i)) }
            }
        }

        // Actualizar desde GitHub, sin tienda
        val up = card(c, dp(6f).toInt())
        updateRow(up)
        val tip = text(
            "Mantén presionado el fondo del inicio para el modo edición · desliza hacia arriba para ver todas las apps",
            12.5f, 400, OneColors.SUB
        )
        tip.gravity = Gravity.CENTER
        tip.setPadding(dp(20f).toInt(), dp(20f).toInt(), dp(20f).toInt(), 0)
        c.addView(tip)
    }

    private fun buildCategory(i: Int): View = page(CAT_TITLES[i], CAT_SUBS[i], false) { c ->
        openCat = i
        if (i == PAGE_LOOK) {
            colorThemesCard(c)
            themesCard(c)
        }
        if (i == PAGE_GESTURES) {
            val t = text("Elige qué hace cada gesto en la pantalla de inicio y cómo vibra el launcher.", 13.5f, 400, OneColors.SUB)
            t.setPadding(dp(20f).toInt(), dp(4f).toInt(), dp(20f).toInt(), 0)
            c.addView(t)
        }
        basicSettings(c, i)
        detailSections(c, i)
    }

    private fun basicSettings(c: LinearLayout, i: Int) {
        when (i) {
            PAGE_HOME -> {
                // vista previa en vivo de la pantalla de inicio
                val pk = card(c, dp(12f).toInt())
                val gp = GridPreview(this)
                pk.addView(gp, LinearLayout.LayoutParams(-1, dp(300f).toInt()))

                sectionLabel(c, "Organización")
                val ko = card(c)
                choice(ko, "Cómo se colocan los iconos", HomeLayout.NAMES, S.i(S.H_LAYOUT), -1, HomeLayout.DESC) {
                    S.set(S.H_LAYOUT, it.toFloat())
                    gp.invalidate()
                }

                sectionLabel(c, "Iconos")
                val k0 = card(c)
                choice(k0, "Forma de los iconos", SHAPES, Prefs.iconShape, -1, SHAPE_DESC) {
                    if (Prefs.iconShape != it) {
                        Prefs.iconShape = it
                        gp.busy = true
                        gp.invalidate()
                        AppRepo.load(this) {
                            if (AppRepo.bakedShape == Prefs.iconShape) gp.busy = false
                            gp.invalidate()
                        }
                    }
                }

                sectionLabel(c, "Cuadrícula")
                val k = card(c)
                choice(k, "Columnas", COLS, (Prefs.columns - 3).coerceIn(0, 3), -1, null) {
                    Prefs.columns = it + 3
                    gp.invalidate()
                }
                divider(k, 0f)
                choice(k, "Filas", ROWS, if (Prefs.rows <= 0) 0 else (Prefs.rows - 3).coerceIn(1, 4), -1, null) {
                    Prefs.rows = if (it == 0) 0 else it + 3
                    gp.invalidate()
                }
                divider(k, 0f)
                slider(k, "Tamaño de iconos", 40, 72, Prefs.iconDp, " dp") {
                    Prefs.iconDp = it
                    gp.invalidate()
                }
                divider(k, 0f)
                slider(k, "Tamaño del texto", 9, 16, Prefs.labelSize, " sp") {
                    Prefs.labelSize = it
                    gp.invalidate()
                }
                divider(k, 0f)
                toggle(k, "Mostrar nombres", "Texto bajo cada icono", Prefs.labels) {
                    Prefs.labels = it
                    gp.invalidate()
                }

                sectionLabel(c, "Reloj")
                val kc = card(c)
                choice(kc, "Diseño del reloj", CLOCK, Prefs.clockStyle, -1, null) {
                    Prefs.clockStyle = it
                    gp.invalidate()
                }
                val tipc = text("Mantén pulsado el reloj en el inicio para cambiar colores, formato, posición y tamaño.", 13f, 400, OneColors.SUB)
                tipc.setPadding(dp(20f).toInt(), dp(6f).toInt(), dp(16f).toInt(), dp(4f).toInt())
                kc.addView(tipc)

                sectionLabel(c, "Fondo y apps")
                val k2 = card(c, dp(6f).toInt())
                button(k2, "Fondo de pantalla", null, Glyphs.WALLPAPER) {
                    tryStart(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Fondo de pantalla"))
                }
                divider(k2, dp(60f))
                var hiddenSub: TextView? = null
                hiddenSub = button(k2, "Mostrar apps ocultas", Prefs.hidden().size.toString() + " ocultas", Glyphs.GRID) {
                    Prefs.setHidden(emptyList())
                    hiddenSub?.text = "0 ocultas"
                    toast("Apps visibles de nuevo")
                }
                divider(k2, dp(60f))
                button(k2, "Restablecer inicio y dock", "Vuelve a la organización inicial", Glyphs.HOME) {
                    Prefs.resetLayout()
                    toast("Se reorganizará al volver al inicio")
                }
            }
            PAGE_DOCK -> {
                sectionLabel(c, "Diseños del dock")
                dockDesignsCard(c)
                sectionLabel(c, "Movimiento")
                val k = card(c)
                choice(k, "Magnificación", DOCK, Prefs.dockMode, PreviewView.DOCK, null) { Prefs.dockMode = it }
                divider(k, 0f)
                slider(k, "Intensidad de magnificación", 0, 100, Prefs.magnify, "%") { Prefs.magnify = it }
                divider(k, 0f)
                slider(k, "Tamaño del dock", 42, 64, Prefs.dockDp, " dp") { Prefs.dockDp = it }
                val k2 = card(c)
                choice(k2, "Rebote al tocar", BOUNCE, Prefs.bounceAnim, PreviewView.BOUNCE, null) { Prefs.bounceAnim = it }
            }
            PAGE_APPS -> {
                val k = card(c)
                choice(k, "Forma de ver todas las apps", DRAWER, Prefs.drawerStyle, -1, DRAWER_DESC) { Prefs.drawerStyle = it }
                divider(k, 0f)
                choice(k, "Columnas", COLS, (Prefs.drawerCols - 3).coerceIn(0, 3), -1, null) {
                    Prefs.drawerCols = it + 3
                }
                val k2 = card(c, dp(6f).toInt())
                toggle(k2, "Gravedad con inclinación", "Al inclinar el móvil, los iconos ruedan (solo en la vista Gravedad)", Prefs.tilt) { Prefs.tilt = it }
            }
            PAGE_MOTION -> {
                val k = card(c)
                choice(k, "Abrir apps", OPEN, Prefs.openAnim, PreviewView.OPEN, null) { Prefs.openAnim = it }
                val k2 = card(c)
                choice(k2, "Volver al inicio", RETURN, Prefs.returnAnim, PreviewView.RETURN, null) { Prefs.returnAnim = it }
                val k3 = card(c)
                choice(k3, "Cambio de página", PAGE, Prefs.pageAnim, PreviewView.PAGE, null) { Prefs.pageAnim = it }
                val k4 = card(c)
                slider(k4, "Velocidad de animaciones", 50, 200, Prefs.speedPct, "%") { Prefs.speedPct = it }
            }
            PAGE_RECENTS -> {
                val k = card(c)
                choice(k, "Estilo", RECENTS, Prefs.recentsAnim, -1, null) { Prefs.recentsAnim = it }
                val k2 = card(c, dp(6f).toInt())
                val has = Recents.hasUsageAccess(this)
                button(k2, "Acceso de uso", if (has) "Activado · ves todas tus apps recientes" else "Desactivado · tócalo para activarlo", Glyphs.RECENTS) {
                    tryStartAny(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")),
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                        Intent(Settings.ACTION_SECURITY_SETTINGS)
                    )
                }
            }
            PAGE_LOOK -> {
                val k = card(c)
                slider(k, "Intensidad del cristal", 20, 100, Prefs.glass, "%") { Prefs.glass = it }
                divider(k, 0f)
                toggle(k, "Desenfoque", "Esmerila el fondo al abrir paneles (el fondo de pantalla, desde Android 12)", Prefs.blur) { Prefs.blur = it }
            }
            PAGE_BATTERY -> {
                val k = card(c)
                toggle(k, "Modo ahorro", "Pantalla a 60 Hz, sin sombras ni desenfoque", Prefs.saver) {
                    Prefs.saver = it
                    Compat.applyRefreshRate(this)
                }
                val info = text(
                    "Fuera del modo ahorro, la pantalla sube a su frecuencia máxima (" + Screen.hz.toInt() +
                        " Hz ahora) solo mientras algo se mueve. En reposo el launcher no dibuja nada ni usa la CPU.",
                    13f, 400, OneColors.SUB
                )
                info.setPadding(dp(20f).toInt(), dp(14f).toInt(), dp(20f).toInt(), 0)
                c.addView(info)
            }
            PAGE_ABOUT -> {
                val k = card(c)
                val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (t: Throwable) { "?" }
                button(k, "Versión", ver, Glyphs.INFO) {}
                divider(k, dp(60f))
                updateRow(k)
                val err = CrashGuard.lastError()
                if (err != null) {
                    divider(k, dp(60f))
                    button(k, "Enviar el último error", "El launcher se cerró: toca para compartir el informe", Glyphs.INFO) {
                        try {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, err)
                            startActivity(Intent.createChooser(send, "Enviar informe de error"))
                        } catch (t: Throwable) {
                        }
                    }
                }
                val t = text("Hecho a medida: Kotlin nativo, sin HTML. Física de resortes exacta, cristal líquido dibujado a mano y cero animaciones en reposo.", 13f, 400, OneColors.SUB)
                t.setPadding(dp(20f).toInt(), dp(14f).toInt(), dp(20f).toInt(), 0)
                c.addView(t)
            }
        }
    }

    /** encabezado de sección estilo One UI (texto pequeño de color sobre la tarjeta) */
    private fun sectionLabel(c: LinearLayout, s0: String) {
        val t = text(s0, 14f, 600, OneColors.ACCENT)
        t.setPadding(dp(22f).toInt(), dp(24f).toInt(), dp(20f).toInt(), 0)
        c.addView(t)
    }

    private val ROW_COLORS = intArrayOf(
        0xFF3E91FF.toInt(), 0xFF7C5CFF.toInt(), 0xFF00B8A9.toInt(), 0xFFFF7A45.toInt(), 0xFFFF4D8D.toInt(),
        0xFFB45CFF.toInt(), 0xFF2FC26B.toInt(), 0xFFFFB020.toInt(), 0xFF22D3EE.toInt(), 0xFFEF4444.toInt()
    )

    /** todos los ajustes detallados de este menú, ordenados por secciones */
    private fun detailSections(c: LinearLayout, menu: Int) {
        val secs = Menus.forMenu(menu)
        if (secs.isEmpty()) return
        var lastHeader = ""
        var k: LinearLayout? = null
        var n = 0
        for (sec in secs) {
            if (sec.header != lastHeader) {
                lastHeader = sec.header
                sectionLabel(c, sec.header)
                k = card(c, dp(6f).toInt())
                n = 0
            }
            val kk = k ?: continue
            if (n > 0) divider(kk, dp(72f))
            val color = ROW_COLORS[(n + sec.kind * 3) % ROW_COLORS.size]
            advRow(kk, Menus.glyph(sec), color, sec.title, sec.ids.size.toString() + " ajustes") { push(buildSection(sec)) }
            n++
        }
        val rk = card(c, dp(6f).toInt())
        button(rk, "Restablecer este menú", "Devuelve sus ajustes detallados a los valores originales", Glyphs.BACK) {
            for (sec in secs) S.resetIds(sec.ids)
            toast("Restablecido")
        }
    }

    private fun buildSection(sec: Menus.Sec): View = page(sec.title, CAT_TITLES[sec.menu] + " · " + sec.header, false) { c ->
        val k = card(c)
        for ((n, id) in sec.ids.withIndex()) {
            if (n > 0) divider(k, 0f)
            item(k, S.defs[id], false)
        }
        val rk = card(c, dp(6f).toInt())
        button(rk, "Restablecer esta sección", null, Glyphs.BACK) {
            S.resetIds(sec.ids)
            toast("Restablecido")
            pop()
        }
    }

    /** atajos de la portada: lo que más se cambia al personalizar, a un toque */
    private fun quickCard(c: LinearLayout) {
        val k = card(c, dp(12f).toInt())
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val items = arrayOf(
            Triple("Organizar\ninicio", Glyphs.HOME, PAGE_HOME),
            Triple("Diseños\ndel dock", Glyphs.DOCK, PAGE_DOCK),
            Triple("Vista\nde apps", Glyphs.GRID, PAGE_APPS),
            Triple("Anima-\nciones", Glyphs.MOTION, PAGE_MOTION)
        )
        for ((label, glyph, pg) in items) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.setPadding(0, dp(6f).toInt(), 0, dp(6f).toInt())
            col.background = ripple(dp(18f))
            col.addView(CategoryIconView(this, glyph, OneColors.CATS[pg]), LinearLayout.LayoutParams(dp(46f).toInt(), dp(46f).toInt()))
            val t = text(label, 12.5f, 500)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(8f).toInt(), 0, 0)
            col.addView(t)
            col.setOnClickListener {
                Hx.fire(it, S.HE_SETTING)
                push(buildCategory(pg))
            }
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        k.addView(row)
    }

    /**
     * Galería de los 30 diseños del dock: cada miniatura dibuja el diseño de verdad (mismo pintor que
     * el inicio). Tocar uno lo aplica; debajo, la forma y los detalles para retocarlo.
     */
    private fun dockDesignsCard(c: LinearLayout) {
        val k = card(c, dp(12f).toInt())
        val intro = text("Toca un diseño para aplicarlo. Después puedes retocarlo abajo o en «Cristal · Dock».", 13.5f, 400, OneColors.SUB)
        intro.setPadding(dp(18f).toInt(), 0, dp(14f).toInt(), dp(10f).toInt())
        k.addView(intro)
        val thumbs = ArrayList<DockDesignThumb>()
        var paintForm: ((Int) -> Unit)? = null
        var reflectSw: OneSwitch? = null
        var doubleSw: OneSwitch? = null
        var row: LinearLayout? = null
        val perRow = 3
        for (i in DockDesigns.LIST.indices) {
            if (i % perRow == 0) {
                val r = LinearLayout(this)
                r.orientation = LinearLayout.HORIZONTAL
                r.setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
                k.addView(r, LinearLayout.LayoutParams(-1, -2))
                row = r
            }
            val t = DockDesignThumb(this, i)
            t.picked = S.i(S.K_DESIGN) == i
            t.setOnClickListener { v ->
                DockDesigns.apply(i)
                for (x in thumbs) {
                    x.picked = x.idx == i
                    x.invalidate()
                }
                paintForm?.invoke(S.i(S.K_SHAPE))
                reflectSw?.set(S.b(S.K_REFLECT))
                doubleSw?.set(S.b(S.K_DOUBLE))
                Hx.fire(v, S.HE_SETTING)
                v.animate().cancel()
                v.scaleX = 0.9f
                v.scaleY = 0.9f
                v.animate().scaleX(1f).scaleY(1f).setInterpolator(android.view.animation.OvershootInterpolator(3f)).setDuration(320).start()
                toast("Dock: " + DockDesigns.NAMES[i])
            }
            thumbs.add(t)
            row?.addView(t, LinearLayout.LayoutParams(0, dp(108f).toInt(), 1f).apply {
                leftMargin = dp(3f).toInt()
                rightMargin = dp(3f).toInt()
                bottomMargin = dp(6f).toInt()
            })
        }
        divider(k, 0f)
        paintForm = choice(k, "Forma", DockDesigns.FORMS, S.i(S.K_SHAPE), -1, null) { S.set(S.K_SHAPE, it.toFloat()) }
        divider(k, 0f)
        reflectSw = toggle(k, "Reflejo de los iconos", "Los iconos se reflejan en el cristal, como en el Mac", S.b(S.K_REFLECT)) {
            S.set(S.K_REFLECT, if (it) 1f else 0f)
        }
        divider(k, 0f)
        doubleSw = toggle(k, "Doble borde", "Un segundo filo brillante por dentro", S.b(S.K_DOUBLE)) {
            S.set(S.K_DOUBLE, if (it) 1f else 0f)
        }
    }

    /** tarjeta con las muestras de los temas de color */
    private fun colorThemesCard(c: LinearLayout) {
        val tk = card(c, dp(14f).toInt())
        tk.addView(text("Tema de color", 16.5f, 500).apply { setPadding(dp(20f).toInt(), 0, 0, dp(2f).toInt()) })
        tk.addView(text("Inicio, dock, vista de apps, recientes, menús y estos ajustes", 13.5f, 400, OneColors.SUB)
            .apply { setPadding(dp(20f).toInt(), 0, dp(16f).toInt(), dp(12f).toInt()) })
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.overScrollMode = View.OVER_SCROLL_NEVER
        val row = LinearLayout(this)
        row.setPadding(dp(12f).toInt(), dp(4f).toInt(), dp(12f).toInt(), dp(4f).toInt())
        for (i in ColorThemes.THEMES.indices) {
            val sw = ThemeSwatch(this, i)
            sw.picked = Prefs.colorTheme == i
            sw.setOnClickListener {
                if (Prefs.colorTheme == i) return@setOnClickListener
                ColorThemes.apply(i)
                Hx.fire(it, S.HE_SETTING)
                // la muestra se hunde y rebota; luego Ajustes se repinta con el tema nuevo
                it.animate().cancel()
                it.scaleX = 0.88f
                it.scaleY = 0.88f
                it.animate().scaleX(1f).scaleY(1f).setInterpolator(android.view.animation.OvershootInterpolator(3f)).setDuration(300)
                    .withEndAction { if (!isFinishing) recreate() }.start()
                toast("Tema " + ColorThemes.THEMES[i].name)
            }
            row.addView(sw, LinearLayout.LayoutParams(dp(92f).toInt(), dp(124f).toInt()).apply { rightMargin = dp(8f).toInt() })
        }
        hs.addView(row)
        tk.addView(hs)
    }

    private fun themesCard(c: LinearLayout) {
        val tk = card(c)
        tk.addView(text("Temas rápidos", 16.5f, 500).apply { setPadding(dp(20f).toInt(), 0, 0, dp(10f).toInt()) })
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.setPadding(dp(14f).toInt(), 0, dp(14f).toInt(), 0)
        for (i in THEMES.indices) {
            val chip = text(THEMES[i], 14.5f, 500)
            chip.setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(16f).toInt(), dp(10f).toInt())
            val g = GradientDrawable()
            g.cornerRadius = dp(20f)
            g.colors = intArrayOf(THEME_COLORS[i], blend(THEME_COLORS[i], 0xFF000000.toInt(), 0.35f))
            chip.background = g
            chip.foreground = ripple(dp(20f))
            chip.setOnClickListener {
                applyTheme(i)
                Hx.fire(it, S.HE_SETTING)
                toast("Tema \"" + THEMES[i] + "\" aplicado")
                // si cambió colores, Ajustes se repinta para verlos (y la muestra del tema de color se actualiza)
                if (i == 0 || i == 1 || i == 3 || i == 4 || i == 5) stack.postDelayed({ if (!isFinishing) recreate() }, 250)
            }
            row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8f).toInt() })
        }
        hs.addView(row)
        tk.addView(hs)
    }

    // ---------------------------------------------------------------- Buscador
    private fun norm(x: String): String =
        java.text.Normalizer.normalize(x.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun buildSearch(): View = page("Buscar", "Todos los ajustes del launcher", false) { c ->
        val sk = card(c, dp(8f).toInt())
        val search = android.widget.EditText(this)
        search.hint = "Escribe: sombra, dock, reloj, rebote…"
        search.setHintTextColor(0x99FFFFFF.toInt())
        search.setTextColor(Color.WHITE)
        search.textSize = 16f
        search.isSingleLine = true
        search.background = null
        search.setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(16f).toInt(), dp(10f).toInt())
        search.setCompoundDrawablesRelative(GlyphDrawable(Glyphs.SEARCH, 20f, 0xCCFFFFFF.toInt()), null, null, null)
        search.compoundDrawablePadding = dp(12f).toInt()
        sk.addView(search)
        val results = LinearLayout(this)
        results.orientation = LinearLayout.VERTICAL
        c.addView(results)
        search.postDelayed({
            search.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 450)
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var pending: Runnable? = null
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(x: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(x: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                pending?.let { h.removeCallbacks(it) }
                val q = norm(e?.toString()?.trim() ?: "")
                val r = Runnable {
                    results.removeAllViews()
                    if (q.length < 2) return@Runnable
                    val words = q.split(' ').filter { it.isNotEmpty() }
                    val found = ArrayList<Pair<Menus.Sec, Int>>()
                    for (sec in Menus.all()) for (id in sec.ids) {
                        val d = S.defs[id]
                        val hay = norm(d.title + " " + sec.title + " " + sec.header + " " + CAT_TITLES[sec.menu])
                        if (words.all { hay.contains(it) }) found.add(Pair(sec, id))
                    }
                    results.addView(text(found.size.toString() + " resultados", 13.5f, 500, OneColors.SUB).apply { setPadding(dp(20f).toInt(), dp(16f).toInt(), 0, 0) })
                    var last: Menus.Sec? = null
                    var k: LinearLayout? = null
                    var n = 0
                    for ((sec, id) in found.take(60)) {
                        if (sec !== last) {
                            last = sec
                            sectionLabel(results, CAT_TITLES[sec.menu] + "  ›  " + sec.title)
                            k = card(results)
                            n = 0
                        }
                        val kk = k ?: continue
                        if (n++ > 0) divider(kk, 0f)
                        item(kk, S.defs[id], false)
                    }
                    if (found.size > 60) results.addView(text("Mostrando 60. Escribe más para afinar.", 13f, 400, OneColors.SUB).apply { setPadding(dp(20f).toInt(), dp(10f).toInt(), 0, 0) })
                }
                pending = r
                h.postDelayed(r, 180)
            }
        })
    }

    private fun advRow(parent: LinearLayout, glyph: Int, color: Int, title: String, sub: String, run: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt())
        row.background = ripple(dp(22f))
        row.setOnClickListener { run() }
        row.addView(CategoryIconView(this, glyph, color), LinearLayout.LayoutParams(dp(42f).toInt(), dp(42f).toInt()))
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.setPadding(dp(16f).toInt(), 0, dp(8f).toInt(), 0)
        t.addView(text(title, 17f, 500))
        t.addView(text(sub, 13.5f, 400, OneColors.SUB).apply { setPadding(0, dp(2f).toInt(), 0, 0) })
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        val ch = View(this)
        ch.background = GlyphDrawable(Glyphs.CHEVRON, 18f, 0x80FFFFFF.toInt())
        row.addView(ch, LinearLayout.LayoutParams(dp(18f).toInt(), dp(18f).toInt()))
        parent.addView(row)
    }

    /** fila de un ajuste según su tipo */
    private fun item(parent: LinearLayout, d: S.Def, showPath: Boolean) {
        val title = if (showPath) d.title + "  ·  " + d.group else d.title
        when (d.type) {
            S.BOOL -> toggle(parent, title, d.desc, S.b(d.id)) { S.set(d.id, if (it) 1f else 0f) }
            S.NUM -> slider(parent, title, d.min.toInt(), d.max.toInt(), S.f(d.id).toInt(), if (d.unit == "%") " %" else d.unit) { S.set(d.id, it.toFloat()) }
            S.CHOICE -> choice(parent, title, d.opts ?: arrayOf(), S.i(d.id), -1, null) {
                S.set(d.id, it.toFloat())
            }
            S.COLOR -> colorRow(parent, title, S.i(d.id)) { S.set(d.id, it.toFloat()) }
        }
    }

    private fun colorRow(parent: LinearLayout, title: String, cur: Int, set: (Int) -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
        val name = text(title + ": " + S.PAL_NAMES[cur.coerceIn(0, S.PAL.size - 1)], 16.5f, 500)
        name.setPadding(dp(6f).toInt(), 0, 0, dp(10f).toInt())
        box.addView(name)
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        val dots = ArrayList<ColorDot>()
        for (i in S.PAL.indices) {
            val dv = ColorDot(this, S.PAL[i], i == 0)
            dv.picked = i == cur
            dv.setOnClickListener {
                for (x in dots) {
                    x.picked = false
                    x.invalidate()
                }
                dv.picked = true
                dv.invalidate()
                name.text = title + ": " + S.PAL_NAMES[i]
                Hx.fire(it, S.HE_SETTING)
                set(i)
            }
            dots.add(dv)
            row.addView(dv, LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt()).apply { rightMargin = dp(6f).toInt() })
        }
        hs.addView(row)
        box.addView(hs)
        parent.addView(box)
    }

    /** temas rápidos: cambian de golpe muchos ajustes a la vez */
    private fun applyTheme(i: Int) {
        val allSurf = S.SF_NAMES.indices
        fun glass(prop: Int, v: Float) { for (sf in allSurf) S.set(S.gp(sf, prop), v) }
        // los temas rápidos que cambian colores dejan el tema de color como "personalizado"
        if (i == 1 || i == 3 || i == 4 || i == 5) Prefs.colorTheme = -1
        when (i) {
            0 -> { // Liquid Glass (original): deshace todos los temas
                for (cat in intArrayOf(S.C_GLASS, S.C_ICON, S.C_COLOR, S.C_HOME, S.C_DOCK, S.C_CLOCK, S.C_MOTION, S.C_STYLE, S.C_PERF)) S.reset(cat)
                ColorThemes.apply(0)
                Prefs.iconShape = 0
                Prefs.clockStyle = 0
                Prefs.labels = true
                Prefs.saver = false
                Prefs.dockMode = 0
                Compat.applyRefreshRate(this)
            }
            1 -> { // One UI
                Prefs.iconShape = 2
                Prefs.clockStyle = 1
                S.set(S.D_STYLE, 4f)
                S.set(S.K_SHAPE, 0f)
                glass(S.GP_TINT, 2f)
            }
            2 -> { // Minimal
                Prefs.labels = false
                S.set(S.D_STYLE, 1f)
                S.set(S.K_SHAPE, 2f)
                S.set(S.DT_PILL, 0f)
                glass(S.GP_SHADOW, 0f)
                Prefs.iconShape = 1
            }
            3 -> { // Neón
                glass(S.GP_TINT, 12f)
                glass(S.GP_RIM_COLOR, 4f)
                glass(S.GP_RIM_W, 220f)
                glass(S.GP_GRAD, 1f)
                glass(S.GP_TINT2, 14f)
                for (a in intArrayOf(S.IA_HOME, S.IA_DOCK)) S.set(S.ip(a, S.IP_HALO), 1f)
            }
            4 -> { // Oscuro total
                glass(S.GP_TINT, 16f)
                glass(S.GP_TINT_OP, 180f)
                S.set(S.L_WALL_DIM, 45f)
                S.set(S.L_TOP_GRAD, 1f)
                S.set(S.L_BOT_GRAD, 1f)
            }
            5 -> { // Cristal claro
                glass(S.GP_TINT, 1f)
                glass(S.GP_TINT_OP, 45f)
                glass(S.GP_RIM_B, 160f)
                glass(S.GP_LENS, 170f)
            }
            6 -> { // Juguetón
                for (m in intArrayOf(S.MO_PAGE, S.MO_DOCK_MAG, S.MO_RELEASE, S.MO_DRAWER_OPEN, S.MO_MENU, S.MO_EDIT_IN)) S.set(S.mp(m, S.MP_BOUNCE), 180f)
                for (b in 0 until 5) S.set(S.BOUNCES_BASE + b, 170f)
                Prefs.dockMode = 1
            }
            7 -> { // Rendimiento
                Prefs.saver = true
                S.set(S.P_SHADOWS, 1f)
                S.set(S.P_BLUR_Q, 0f)
                S.set(S.P_REDUCE, 1f)
                Compat.applyRefreshRate(this)
            }
        }
    }

    // ---------------------------------------------------------------- Actualizar
    /** Fila "Actualizar": busca, descarga con barra de progreso e instala directo desde el repositorio. */
    private fun updateRow(parent: LinearLayout) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(10f).toInt())
        row.background = ripple(dp(22f))
        row.addView(CategoryIconView(this, Glyphs.CHECK, 0xFF2FC26B.toInt()), LinearLayout.LayoutParams(dp(42f).toInt(), dp(42f).toInt()))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(16f).toInt(), 0, dp(8f).toInt(), 0)
        texts.addView(text("Actualizar", 17f, 500))
        val local = Updater.localVersion(this)
        val sub = text("Versión 1.$local · toca para buscar actualizaciones", 13.5f, 400, OneColors.SUB)
        sub.setPadding(0, dp(2f).toInt(), 0, 0)
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row)
        val bar = ProgressLine(this)
        bar.visibility = View.GONE
        box.addView(bar, LinearLayout.LayoutParams(-1, dp(18f).toInt()))
        parent.addView(box)

        var remote = -1L
        fun install() {
            sub.text = "Descargando… 0 %"
            bar.visibility = View.VISIBLE
            bar.set(0f)
            Updater.downloadAndInstall(this, { pct ->
                sub.text = "Descargando… $pct %"
                bar.set(pct / 100f)
            }) { err ->
                if (isDestroyed) return@downloadAndInstall
                if (err != null) {
                    sub.text = err
                    bar.visibility = View.GONE
                } else {
                    sub.text = "Listo · confirma la instalación"
                    bar.set(1f)
                }
            }
        }
        row.setOnClickListener {
            if (Updater.busy) return@setOnClickListener
            if (remote > local) {
                install()
                return@setOnClickListener
            }
            sub.text = "Buscando actualizaciones…"
            Updater.check { v ->
                if (isDestroyed) return@check
                remote = v
                when {
                    v < 0 -> sub.text = "Sin conexión con GitHub · inténtalo de nuevo"
                    v > local -> {
                        sub.text = "Nueva versión 1.$v disponible · instalando…"
                        install()
                    }
                    else -> sub.text = "Ya tienes la última versión (1.$local)"
                }
            }
        }
    }

    // ---------------------------------------------------------------- Predeterminado
    private fun isDefaultHome(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
            }
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
        } catch (t: Throwable) {
            false
        }
    }

    private fun makeDefault() {
        triedHomeSettings = false
        try {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                @Suppress("DEPRECATION")
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME)
                return
            }
        } catch (t: Throwable) {
        }
        // cada marca guarda esta opción en un sitio: se prueba de la más directa a la más general
        tryStartAny(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
    }

    // ---------------------------------------------------------------- Componentes
    private fun text(s: String, size: Float, weight: Int, color: Int = OneColors.TEXT): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        t.typeface = Typeface.create(Typeface.DEFAULT, weight, false)
        return t
    }

    private fun ripple(radius: Float): RippleDrawable {
        val mask = GradientDrawable()
        mask.cornerRadius = radius
        mask.setColor(Color.WHITE)
        return RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), null, mask)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** abre la primera pantalla que exista en este móvil (Samsung, Xiaomi, Huawei, Oppo... no tienen las mismas) */
    private fun tryStartAny(vararg list: Intent) {
        for (i in list) {
            try {
                startActivity(i)
                return
            } catch (t: Throwable) {
            }
        }
        toast("No disponible en este móvil")
    }

    private fun tryStart(i: Intent) {
        try {
            startActivity(i)
        } catch (t: Throwable) {
            toast("No disponible en este móvil")
        }
    }

    private fun card(parent: LinearLayout, padV: Int = dp(18f).toInt()): LinearLayout {
        val k = LinearLayout(this)
        k.orientation = LinearLayout.VERTICAL
        k.background = GlassDrawable(28f, 1f, S.SF_SETTINGS)
        k.setPadding(dp(4f).toInt(), padV, dp(4f).toInt(), padV)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(14f).toInt()
        parent.addView(k, lp)
        return k
    }

    private fun divider(parent: LinearLayout, inset: Float) {
        val d = View(this)
        d.setBackgroundColor(0x1FFFFFFF)
        val lp = LinearLayout.LayoutParams(-1, dp(1f).toInt().coerceAtLeast(1))
        lp.leftMargin = (dp(18f) + inset).toInt()
        lp.rightMargin = dp(18f).toInt()
        lp.topMargin = dp(4f).toInt()
        lp.bottomMargin = dp(4f).toInt()
        parent.addView(d, lp)
    }

    private fun pill(label: String, accent: Boolean, run: () -> Unit): TextView {
        val b = text(label, 16f, 600)
        b.gravity = Gravity.CENTER
        val g = GradientDrawable()
        g.cornerRadius = dp(26f)
        if (accent) {
            g.colors = intArrayOf(blend(OneColors.ACCENT, 0xFFFFFFFF.toInt(), 0.15f), blend(OneColors.ACCENT, 0xFF000000.toInt(), 0.12f))
            g.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        } else {
            g.setColor(0x26FFFFFF)
        }
        b.background = g
        b.foreground = ripple(dp(26f))
        b.setOnClickListener { run() }
        return b
    }

    private fun categoryRow(parent: LinearLayout, i: Int, run: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt())
        row.background = ripple(dp(22f))
        row.setOnClickListener { run() }
        val icon = CategoryIconView(this, CAT_GLYPHS[i], OneColors.CATS[i])
        row.addView(icon, LinearLayout.LayoutParams(dp(42f).toInt(), dp(42f).toInt()))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(16f).toInt(), 0, dp(8f).toInt(), 0)
        texts.addView(text(CAT_TITLES[i], 17f, 500))
        val n = Menus.countFor(i)
        val sub = text(CAT_SUBS[i] + (if (n > 0) " · $n ajustes" else ""), 13.5f, 400, OneColors.SUB)
        sub.setPadding(0, dp(2f).toInt(), 0, 0)
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val chev = View(this)
        chev.background = GlyphDrawable(Glyphs.CHEVRON, 18f, 0x80FFFFFF.toInt())
        row.addView(chev, LinearLayout.LayoutParams(dp(18f).toInt(), dp(18f).toInt()))
        parent.addView(row)
    }

    /** fila con icono, título y subtítulo; devuelve el subtítulo para poder actualizarlo */
    private fun button(parent: LinearLayout, title: String, sub: String?, glyph: Int, run: () -> Unit): TextView? {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(12f).toInt(), dp(12f).toInt())
        row.background = ripple(dp(22f))
        row.setOnClickListener { run() }
        val g = View(this)
        g.background = GlyphDrawable(glyph, 24f, 0xE6FFFFFF.toInt())
        row.addView(g, LinearLayout.LayoutParams(dp(24f).toInt(), dp(24f).toInt()))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(20f).toInt(), 0, dp(8f).toInt(), 0)
        texts.addView(text(title, 16.5f, 500))
        var subView: TextView? = null
        if (sub != null) {
            val s = text(sub, 13.5f, 400, OneColors.SUB)
            s.setPadding(0, dp(2f).toInt(), 0, 0)
            texts.addView(s)
            subView = s
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row)
        return subView
    }

    private fun toggle(parent: LinearLayout, title: String, sub: String?, cur: Boolean, set: (Boolean) -> Unit): OneSwitch {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(20f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
        row.background = ripple(dp(22f))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(text(title, 16.5f, 500))
        if (sub != null) {
            val s = text(sub, 13.5f, 400, OneColors.SUB)
            s.setPadding(0, dp(2f).toInt(), dp(10f).toInt(), 0)
            texts.addView(s)
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = OneSwitch(this)
        sw.set(cur)
        sw.onChange = set
        row.addView(sw)
        row.setOnClickListener { sw.set(!sw.checked, true) }
        parent.addView(row)
        return sw
    }

    private fun slider(parent: LinearLayout, title: String, min: Int, max: Int, cur: Int, unit: String, set: (Int) -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(6f).toInt(), dp(10f).toInt(), dp(6f).toInt(), dp(6f).toInt())
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.setPadding(dp(14f).toInt(), 0, dp(14f).toInt(), 0)
        head.addView(text(title, 16.5f, 500), LinearLayout.LayoutParams(0, -2, 1f))
        val v = text("$cur$unit", 15f, 600, OneColors.ACCENT)
        head.addView(v)
        box.addView(head)
        val s = OneSlider(this, min, max, cur)
        s.onChange = { x ->
            v.text = "$x$unit"
            set(x)
        }
        box.addView(s, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f).toInt() })
        parent.addView(box)
    }

    /** Selector de 5 estilos (fichas) con vista previa animada y descripción opcional. */
    private fun choice(parent: LinearLayout, title: String, names: Array<String>, cur: Int, preview: Int, desc: Array<String>?, set: (Int) -> Unit): (Int) -> Unit {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
        val t = text(title, 16.5f, 500)
        t.setPadding(dp(6f).toInt(), 0, 0, dp(10f).toInt())
        box.addView(t)
        var pv: PreviewView? = null
        if (preview >= 0) {
            val v = PreviewView(this, preview)
            v.style = cur
            box.addView(v, LinearLayout.LayoutParams(-1, dp(150f).toInt()).apply { bottomMargin = dp(12f).toInt() })
            pv = v
        }
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.overScrollMode = View.OVER_SCROLL_NEVER
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val chips = ArrayList<TextView>()
        for (i in names.indices) {
            val c = text(names[i], 14.5f, 500)
            c.gravity = Gravity.CENTER
            c.setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(16f).toInt(), dp(10f).toInt())
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = dp(8f).toInt()
            chips.add(c)
            row.addView(c, lp)
        }
        var descView: TextView? = null
        if (desc != null) {
            val d = text(desc[cur.coerceIn(0, desc.size - 1)], 13.5f, 400, OneColors.SUB)
            d.setPadding(dp(6f).toInt(), dp(12f).toInt(), 0, dp(2f).toInt())
            descView = d
        }
        fun paint(sel: Int) {
            for (i in chips.indices) {
                val g = GradientDrawable()
                g.cornerRadius = dp(20f)
                if (i == sel) {
                    g.setColor(OneColors.ACCENT)
                    chips[i].setTextColor(Color.WHITE)
                } else {
                    g.setColor(0x1FFFFFFF)
                    g.setStroke(dp(1f).toInt(), 0x33FFFFFF)
                    chips[i].setTextColor(0xD9FFFFFF.toInt())
                }
                chips[i].background = g
            }
            if (desc != null) descView?.text = desc[sel.coerceIn(0, desc.size - 1)]
        }
        paint(cur)
        for (i in chips.indices) {
            chips[i].foreground = ripple(dp(20f))
            chips[i].setOnClickListener {
                set(i)
                paint(i)
                Hx.fire(it, S.HE_SETTING)
                // la ficha elegida se hunde y rebota
                it.animate().cancel()
                it.scaleX = 0.9f
                it.scaleY = 0.9f
                it.animate().scaleX(1f).scaleY(1f).setInterpolator(android.view.animation.OvershootInterpolator(3f)).setDuration(320).start()
                pv?.let { p -> p.style = i; p.play() }
            }
        }
        hs.addView(row)
        box.addView(hs)
        descView?.let { box.addView(it) }
        parent.addView(box)
        return { sel -> paint(sel) }
    }
}

/** Fondo de los ajustes: degradado profundo con dos "luces" de color, dibujado una sola vez. */
class BackgroundView(ctx: Context) : View(ctx) {
    private val base = Paint()
    private val blob1 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blob2 = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // 6 fondos: Noche azul, Morado, Negro puro, Océano, Atardecer, Bosque
        val sets = arrayOf(
            intArrayOf(0xFF0A1230.toInt(), 0xFF050816.toInt(), 0x663E91FF, 0x557C5CFF),
            intArrayOf(0xFF1E0B3A.toInt(), 0xFF0B0616.toInt(), 0x66B45CFF, 0x55FF4D8D),
            intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x223E91FF, 0x22FFFFFF),
            intArrayOf(0xFF032433.toInt(), 0xFF02101A.toInt(), 0x6622D3EE, 0x5514B8A6),
            intArrayOf(0xFF2A0F1A.toInt(), 0xFF120610.toInt(), 0x66FB923C, 0x55F472B6),
            intArrayOf(0xFF06231A.toInt(), 0xFF03110C.toInt(), 0x6622C55E, 0x5514B8A6)
        )
        val k = sets[S.i(S.L_SETTINGS_BG).coerceIn(0, sets.size - 1)]
        base.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), k[0], k[1], Shader.TileMode.CLAMP)
        blob1.shader = RadialGradient(w * 0.15f, h * 0.12f, w * 0.9f, k[2], k[2] and 0x00FFFFFF, Shader.TileMode.CLAMP)
        blob2.shader = RadialGradient(w * 0.95f, h * 0.75f, w * 0.85f, k[3], k[3] and 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), blob1)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), blob2)
    }
}

/**
 * Vista previa del launcher en la tarjeta principal (estilo Smart Launcher):
 * un "móvil" en miniatura con tus iconos reales y el dock haciendo la magnificación una vez.
 */
class HeroView(ctx: Context) : View(ctx) {
    private val ticker = Ticker { onFrame(it) }
    private var t = 0f
    private val glass = Glass()
    /** el dock de la vista previa usa el diseño elegido (Burbujas, Estante, Ola...) */
    private val art = DockArt()
    private val screenPath = android.graphics.Path()
    private val screen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clock = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val d = RectF()
    private val colors = intArrayOf(
        0xFF5AC8FA.toInt(), 0xFFFF9F0A.toInt(), 0xFF34C759.toInt(), 0xFFFF375F.toInt(),
        0xFFBF5AF2.toInt(), 0xFFFFD60A.toInt(), 0xFF64D2FF.toInt(), 0xFFFF6482.toInt()
    )

    init {
        clock.color = Color.WHITE
        clock.textAlign = Paint.Align.CENTER
        clock.typeface = Typeface.create(Typeface.DEFAULT, 200, false)
    }

    private val startRun = Runnable { ticker.kick() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        t = 0f
        postDelayed(startRun, 350)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(startRun)
        ticker.stop()
        super.onDetachedFromWindow()
    }

    private fun onFrame(dt: Float): Boolean {
        t += dt
        invalidate()
        return t < 3.4f
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        screen.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF2B4FC4.toInt(), 0xFF5B2FA8.toInt(), 0xFF101A44.toInt()), null, Shader.TileMode.CLAMP)
    }

    private fun drawApp(c: Canvas, i: Int, cx: Float, cy: Float, s: Float) {
        val apps = AppRepo.apps
        val b = if (apps.size > 8) apps[(i * 7 + 3) % apps.size].icon else null
        d.set(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f)
        if (b != null) {
            c.drawBitmap(b, null, d, icon)
        } else {
            icon.color = colors[i % colors.size]
            c.drawRoundRect(d, s * 0.24f, s * 0.24f, icon)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // "pantalla" en horizontal para que quepa en la tarjeta
        r.set(dp(8f), dp(4f), w - dp(8f), h - dp(4f))
        c.drawRoundRect(r, dp(24f), dp(24f), screen)
        clock.textSize = dp(40f)
        clock.alpha = 255
        c.drawText("12:30", w / 2f, r.top + dp(52f), clock)
        // fila de apps
        val s = dp(34f)
        val n = 6
        val gap = (r.width() - dp(32f)) / n
        for (i in 0 until n) drawApp(c, i, r.left + dp(16f) + gap * (i + 0.5f), r.top + dp(96f), s)
        // dock con magnificación
        val m = 6
        val unit = dp(38f)
        val base = dp(30f)
        val sweep = clamp01((t - 0.2f) / 3f)
        val finger = if (t > 0.2f && t < 3.2f) w * 0.15f + w * 0.7f * (0.5f - 0.5f * kotlin.math.cos(sweep * Math.PI.toFloat() * 2f)) else -9999f
        val x0 = (w - m * unit) / 2f
        val sc = FloatArray(m)
        var sum = 0f
        for (i in 0 until m) {
            val rest = x0 + unit * (i + 0.5f)
            sc[i] = if (finger > -999f) Motion.dockScale(Prefs.dockMode, (finger - rest) / unit, 0.6f) else 1f
            sum += unit * sc[i]
        }
        val bottom = r.bottom - dp(18f)
        val form = S.i(S.K_SHAPE)
        val mat = art.settingsMat()
        screenPath.reset()
        screenPath.addRoundRect(r, dp(24f), dp(24f), android.graphics.Path.Direction.CW)
        c.save()
        c.clipPath(screenPath) // la Pestaña llega al borde de la "pantalla"
        art.bodyRect(form, d, w, max(m * unit, sum) + dp(20f), bottom - base / 2f, base + dp(20f), r.bottom)
        if (mat != null) art.drawBody(c, form, d, base + dp(20f), 1f, mat, S.b(S.K_DOUBLE))
        var x = (w - sum) / 2f
        val lf = Motion.liftFactor(Prefs.dockMode)
        for (i in 0 until m) {
            val sw = unit * sc[i]
            val sz = base * sc[i]
            val cx = x + sw / 2f
            val cy = bottom - sz / 2f - (sc[i] - 1f) * base * lf
            if (mat != null && DockDesigns.perIcon(form)) art.drawTile(c, form, cx, cy, sz, 1f, mat)
            drawApp(c, i + 11, cx, cy, sz)
            x += sw
        }
        c.restore()
    }
}

/** Barra de progreso fina estilo One UI. */
class ProgressLine(ctx: Context) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ticker = Ticker { invalidate(); false }
    /** el relleno avanza con un resorte en vez de saltar de porcentaje en porcentaje */
    private val sp = ticker.add(SpringValue(0f, 160f, 26f, 0.0005f))
    private val p: Float get() = clamp01(sp.value)
    private val r = RectF()

    fun set(v: Float) {
        val t = clamp01(v)
        if (t <= 0f) sp.snap(0f) else sp.to(t)
        ticker.kick()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val h = dp(5f)
        r.set(dp(72f), height / 2f - h / 2f, width - dp(20f), height / 2f + h / 2f)
        track.color = OneColors.TRACK
        c.drawRoundRect(r, h / 2f, h / 2f, track)
        fill.color = OneColors.ACCENT
        c.drawRoundRect(r.left, r.top, r.left + r.width() * p, r.bottom, h / 2f, h / 2f, fill)
    }
}

/**
 * Vista previa de la pantalla de inicio que cambia al instante con la cuadrícula,
 * el tamaño y la forma de los iconos (usa tus iconos reales).
 */
class GridPreview(ctx: Context) : View(ctx) {
    var busy = false
    private val glass = Glass()
    private val art = DockArt()
    private val hg = HomeLayout.Grid()
    private val phone = android.graphics.Path()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clock = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val d = RectF()
    private val colors = intArrayOf(
        0xFF5AC8FA.toInt(), 0xFFFF9F0A.toInt(), 0xFF34C759.toInt(), 0xFFFF375F.toInt(),
        0xFFBF5AF2.toInt(), 0xFFFFD60A.toInt(), 0xFF64D2FF.toInt(), 0xFFFF6482.toInt()
    )

    init {
        clock.color = Color.WHITE
        clock.textAlign = Paint.Align.CENTER
        clock.typeface = Typeface.create(Typeface.DEFAULT, 200, false)
        label.color = 0x8CFFFFFF.toInt()
        hint.color = Color.WHITE
        hint.textAlign = Paint.Align.CENTER
        hint.textSize = dp(13f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF2B4FC4.toInt(), 0xFF5B2FA8.toInt(), 0xFF101A44.toInt()), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val scr = resources.displayMetrics
        // "móvil" con la proporción real de tu pantalla
        val ph = height - dp(8f)
        val pw = min(width - dp(16f), ph * scr.widthPixels / scr.heightPixels.toFloat())
        r.set(width / 2f - pw / 2f, dp(4f), width / 2f + pw / 2f, dp(4f) + ph)
        c.drawRoundRect(r, dp(22f), dp(22f), bg)
        val k = pw / scr.widthPixels.toFloat() // escala pantalla real -> vista previa
        val cols = Prefs.columns.coerceIn(3, 6)
        // reloj
        val top = when (Prefs.clockStyle) {
            1 -> {
                clock.textAlign = Paint.Align.LEFT
                clock.textSize = dp(96f) * k
                c.drawText("12", r.left + dp(26f) * k, r.top + dp(130f) * k, clock)
                c.drawText("30", r.left + dp(26f) * k, r.top + dp(214f) * k, clock)
                r.top + dp(262f) * k
            }
            2 -> r.top + dp(60f) * k
            else -> {
                clock.textAlign = Paint.Align.CENTER
                clock.textSize = dp(76f) * k
                c.drawText("12:30", r.centerX(), r.top + dp(118f) * k, clock)
                r.top + dp(176f) * k
            }
        }
        val dockH = (dp(Prefs.dockDp.toFloat()) + dp(22f)) * k
        val dockBottom = r.bottom - dp(40f) * k
        val dotsY = dockBottom - dockH - dp(22f) * k
        val avail = dotsY - dp(16f) * k - top
        val labelH = if (Prefs.labels) dp(Prefs.labelSize + 8f) * k else 0f
        var iconS = dp(Prefs.iconDp.toFloat()) * k
        val minCell = iconS + labelH + dp(24f) * k
        val rows = if (Prefs.rows > 0) Prefs.rows.coerceIn(3, 8) else (avail / minCell).toInt().coerceIn(2, 7)
        val cw = pw / cols
        val ch = avail / rows
        iconS = min(iconS, min(cw - dp(12f) * k, ch - labelH - dp(8f) * k)).coerceAtLeast(dp(28f) * k)
        // mismo estilo de organización que el inicio (Panal, Sonrisa, Dividida...)
        HomeLayout.setup(hg, S.i(S.H_LAYOUT), cols, rows, r.left, r.right, top, avail, iconS + labelH + dp(12f) * k)
        iconS = min(iconS, min(hg.step - dp(12f) * k, hg.cellH - labelH - dp(8f) * k)).coerceAtLeast(dp(20f) * k)
        val apps = AppRepo.apps
        val keys = Prefs.homeKeys()
        var n = 0
        for (row in 0 until rows) for (col in 0 until cols) {
            if (n < keys.size && keys[n] == HomeSlots.BLANK) {
                n++
                continue
            }
            val cx = HomeLayout.x(hg, col, row)
            val cy = HomeLayout.y(hg, col, row) - labelH / 2f
            val e = if (n < keys.size) AppRepo.get(keys[n]) else if (apps.isNotEmpty()) apps[(n * 5) % apps.size] else null
            drawIcon(c, e, n, cx, cy, iconS)
            if (Prefs.labels) {
                label.alpha = 140
                val lw = min(hg.step * 0.7f, iconS * 1.1f)
                val lh = max(dp(2f), dp(Prefs.labelSize.toFloat()) * k * 0.45f)
                d.set(cx - lw / 2f, cy + iconS / 2f + dp(6f) * k, cx + lw / 2f, cy + iconS / 2f + dp(6f) * k + lh)
                c.drawRoundRect(d, lh / 2f, lh / 2f, label)
            }
            n++
        }
        // dock con su forma y diseño (el mismo pintor que el inicio)
        val ds = dp(Prefs.dockDp.toFloat()) * k
        val unit = ds * 1.18f
        val n5 = 5
        val dw = unit * n5 + dp(20f) * k
        val form = S.i(S.K_SHAPE)
        val m = art.settingsMat()
        val dcy = dockBottom - dockH / 2f
        phone.reset()
        phone.addRoundRect(r, dp(22f), dp(22f), android.graphics.Path.Direction.CW)
        c.save()
        c.clipPath(phone) // la Pestaña llega al borde: que no se salga de las esquinas del "móvil"
        c.translate(r.left, 0f)
        art.bodyRect(form, d, pw, dw, dcy, dockH, r.bottom)
        if (m != null) art.drawBody(c, form, d, dockH, 1f, m, S.b(S.K_DOUBLE))
        val dk = Prefs.dockKeys()
        val x0 = (pw - unit * n5) / 2f
        for (i in 0 until n5) {
            val e = if (i < dk.size) AppRepo.get(dk[i]) else null
            val cx = x0 + unit * (i + 0.5f)
            if (m != null && DockDesigns.perIcon(form)) art.drawTile(c, form, cx, dcy, ds, 1f, m)
            drawIcon(c, e, i + 3, cx, dcy, ds)
        }
        c.restore()
        if (busy) {
            d.set(r.centerX() - dp(90f), r.centerY() - dp(20f), r.centerX() + dp(90f), r.centerY() + dp(20f))
            glass.draw(c, d, dp(20f), 1f)
            c.drawText("Aplicando forma…", d.centerX(), d.centerY() + dp(5f), hint)
        }
    }

    private fun drawIcon(c: Canvas, e: AppEntry?, i: Int, cx: Float, cy: Float, s: Float) {
        val b = e?.icon
        d.set(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f)
        if (b != null) {
            c.drawBitmap(b, null, d, icon)
        } else {
            icon.color = colors[i % colors.size]
            val path = AppRepo.shapePath(Prefs.iconShape, s)
            c.save()
            c.translate(d.left, d.top)
            c.drawPath(path, icon)
            c.restore()
        }
    }
}

/**
 * Muestra de un tema de color: una pantalla en miniatura con su degradado, un dock de cristal
 * con puntos del color de acento y el nombre debajo.
 */
class ThemeSwatch(ctx: Context, private val idx: Int) : View(ctx) {
    var picked = false
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val glass = Glass()
    private val r = RectF()
    private val d = RectF()
    private val cols = ColorThemes.swatch(idx)

    init {
        foreground = null
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, w.toFloat(), h * 0.8f, cols[0], cols[1], Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cardH = height - dp(22f)
        r.set(dp(4f), dp(4f), w - dp(4f), cardH)
        c.drawRoundRect(r, dp(18f), dp(18f), bg)
        // reloj de muestra
        label.textSize = dp(17f)
        label.alpha = 235
        c.drawText("12:30", r.centerX(), r.top + dp(30f), label)
        // dock de cristal con los puntos de acento
        d.set(r.left + dp(10f), r.bottom - dp(30f), r.right - dp(10f), r.bottom - dp(10f))
        glass.draw(c, d, dp(10f), 1f, false)
        p.color = cols[2]
        for (k in 0 until 3) c.drawCircle(d.left + d.width() * (0.25f + k * 0.25f), d.centerY(), dp(4f), p)
        // anillo del elegido
        if (picked) {
            ring.color = cols[2]
            ring.strokeWidth = dp(2.5f)
            c.drawRoundRect(r.left - dp(2f), r.top - dp(2f), r.right + dp(2f), r.bottom + dp(2f), dp(20f), dp(20f), ring)
        }
        label.textSize = dp(12.5f)
        label.alpha = if (picked) 255 else 200
        c.drawText(ColorThemes.THEMES[idx].name, w / 2f, height - dp(5f), label)
    }
}

/** Círculo de color para elegir de la paleta (el primero es "Automático"). */
class ColorDot(ctx: Context, private val color: Int, private val auto: Boolean) : View(ctx) {
    var picked = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width.coerceAtMost(height) / 2f - dp(5f)
        if (auto) {
            p.shader = android.graphics.SweepGradient(cx, cy, intArrayOf(0xFF3E91FF.toInt(), 0xFFD946EF.toInt(), 0xFFFACC15.toInt(), 0xFF22C55E.toInt(), 0xFF3E91FF.toInt()), null)
        } else {
            p.shader = null
            p.color = color
        }
        c.drawCircle(cx, cy, r, p)
        ring.strokeWidth = dp(1f)
        ring.color = 0x55FFFFFF
        c.drawCircle(cx, cy, r, ring)
        if (picked) {
            ring.strokeWidth = dp(2.5f)
            ring.color = Color.WHITE
            c.drawCircle(cx, cy, r + dp(3f), ring)
        }
        if (auto) Glyphs.draw(c, Glyphs.CHECK, cx, cy, r * 1.1f, 0xCCFFFFFF.toInt(), if (picked) 1f else 0f)
    }
}

/**
 * Miniatura de un diseño del dock: un trocito de pantalla con el dock dibujado con ese diseño
 * (forma, cristal, reflejo...) y tus iconos del dock. Se dibuja una vez; no anima en reposo.
 */
class DockDesignThumb(ctx: Context, val idx: Int) : View(ctx) {
    var picked = false
    private val art = DockArt()
    private val mat = GlassMat()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val r = RectF()
    private val d = RectF()
    private val dst = RectF()
    private val clip = android.graphics.Path()
    private val colors = intArrayOf(0xFF5AC8FA.toInt(), 0xFFFF9F0A.toInt(), 0xFF34C759.toInt(), 0xFFFF375F.toInt())

    init {
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF2B4FC4.toInt(), 0xFF5B2FA8.toInt(), 0xFF1A1440.toInt()), null, Shader.TileMode.CLAMP)
    }

    private fun bitmapFor(k: Int, keys: List<String>): android.graphics.Bitmap? {
        if (k < keys.size) AppRepo.get(keys[k])?.icon?.let { return it }
        val apps = AppRepo.apps
        return if (apps.isNotEmpty()) apps[(k * 7 + 2) % apps.size].icon else null
    }

    override fun onDraw(c: Canvas) {
        val dz = DockDesigns.LIST[idx]
        val labelH = dp(22f)
        r.set(dp(3f), dp(3f), width - dp(3f), height - labelH)
        clip.reset()
        clip.addRoundRect(r, dp(14f), dp(14f), android.graphics.Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        c.drawRect(r, bg)
        DockDesigns.mat(idx, mat)
        val h = r.height() * 0.42f
        val cy = r.bottom - h / 2f - dp(7f)
        val n = 4
        val size = min(h * 0.62f, (r.width() - dp(24f)) / (n * 1.22f))
        val unit = size * 1.22f
        val capW = unit * n + dp(8f)
        c.translate(r.left, 0f)
        art.bodyRect(dz.form, d, r.width(), capW, cy, h, r.bottom)
        art.drawBody(c, dz.form, d, h, 1f, mat, dz.double)
        val keys = Prefs.dockKeys()
        val x0 = (r.width() - unit * n) / 2f
        for (k in 0 until n) {
            val cx = x0 + unit * (k + 0.5f)
            if (DockDesigns.perIcon(dz.form)) art.drawTile(c, dz.form, cx, cy, size, 1f, mat)
            val b = bitmapFor(k, keys)
            dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
            if (b != null) {
                if (dz.reflect && dz.form != DockDesigns.F_NONE) {
                    c.save()
                    c.clipRect(d.left, dst.bottom, d.right, min(d.bottom, cy + h / 2f))
                    c.scale(1f, -1f, cx, dst.bottom)
                    icon.alpha = 56
                    c.drawBitmap(b, null, dst, icon)
                    c.restore()
                }
                icon.alpha = 255
                c.drawBitmap(b, null, dst, icon)
            } else {
                icon.color = colors[k % colors.size]
                c.drawRoundRect(dst, size * 0.26f, size * 0.26f, icon)
            }
        }
        c.restore()
        if (picked) {
            ring.color = OneColors.ACCENT
            ring.strokeWidth = dp(2.5f)
            c.drawRoundRect(r.left - dp(1.5f), r.top - dp(1.5f), r.right + dp(1.5f), r.bottom + dp(1.5f), dp(15f), dp(15f), ring)
        }
        label.textSize = dp(12f)
        label.alpha = if (picked) 255 else 205
        val name = TextUtils.ellipsize(dz.name, label, width - dp(6f), TextUtils.TruncateAt.END).toString()
        c.drawText(name, width / 2f, height - dp(6f), label)
    }
}
