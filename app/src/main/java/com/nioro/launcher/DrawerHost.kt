package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout

/**
 * Contenedor de la vista de aplicaciones: oscurece el fondo, muestra la barra de búsqueda
 * de cristal y aloja una de las 5 vistas. Su apertura/cierre es un resorte.
 */
class DrawerHost(ctx: Context) : FrameLayout(ctx) {

    interface Callback {
        fun onProgress(p: Float)
        fun onLaunch(e: AppEntry, from: RectF)
        fun onMenu(e: AppEntry, anchor: RectF)
        fun onClosed()
        fun dismissMenu()
        fun openSettings()
        /** añadir una app al inicio desde la vista de apps (gesto de la Playlist) */
        fun onAddHome(e: AppEntry) {}
    }

    var callback: Callback? = null
    private val sp = SpringValue(0f, 230f, 26f, 0.001f)
    private val ticker = Ticker { onFrame() }
    private var view: AppsView? = null
    private var viewStyle = -1
    private val search = EditText(ctx)
    private val scrim = Paint()
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val emptyRect = RectF()
    private val emptyGlass = Glass()
    /** "Sin resultados" aparece y se va con un fundido (y un pequeño crecimiento) */
    private val emptySp = SpringValue(0f, 320f, 26f, 0.003f)
    /** isla de búsqueda: 0 = barra arriba, 1 = isla flotando sobre el teclado */
    private val islandSp = SpringValue(0f, 320f, 22f, 0.002f)
    /** alto del teclado con el que se colocó la isla (se conserva mientras vuelve arriba) */
    private var islandIme = 0f
    private var emptyQuery = ""
    private var scrimShader: LinearGradient? = null
    private var insetTop = 0f
    private var insetBottom = 0f
    var isOpen = false
        private set
    private var anchorX = 0f
    private var moreDown = false

    init {
        setWillNotDraw(false)
        ticker.add(sp)
        ticker.add(emptySp)
        ticker.add(islandSp)
        visibility = GONE
        search.hint = "Buscar"
        search.setHintTextColor(0xB3FFFFFF.toInt())
        search.setTextColor(Color.WHITE)
        search.textSize = 16f
        search.isSingleLine = true
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        search.imeOptions = EditorInfo.IME_ACTION_GO
        search.background = GlassDrawable(22f)
        // Barra de búsqueda One UI: lupa a la izquierda y menú ⋮ a la derecha
        search.setPadding(dp(18f).toInt(), 0, dp(14f).toInt(), 0)
        search.gravity = Gravity.CENTER_VERTICAL
        search.setCompoundDrawablesRelative(GlyphDrawable(Glyphs.SEARCH, 20f, 0xCCFFFFFF.toInt()), null, GlyphDrawable(Glyphs.MORE, 22f), null)
        search.compoundDrawablePadding = dp(12f).toInt()
        @Suppress("ClickableViewAccessibility")
        search.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    moreDown = ev.x > v.width - dp(48f)
                    moreDown // si es en ⋮ el EditText no recibe nada (ni foco ni pulsación larga)
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (moreDown) {
                        moreDown = false
                        if (ev.x > v.width - dp(56f)) {
                            hideKeyboard()
                            callback?.openSettings()
                        }
                        true
                    } else false
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    val was = moreDown
                    moreDown = false
                    was
                }
                else -> moreDown
            }
        }
        // al tocar la barra crece un poco con rebote
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                view?.setQuery(s?.toString() ?: "")
                val v = view
                val want = if (v != null && v.noResults) 1f else 0f
                if (v != null && v.noResults) emptyQuery = v.query
                if (emptySp.target != want) {
                    Mo.tune(emptySp, S.MO_MENU, 320f, 0.62f)
                    emptySp.to(want)
                    ticker.kick()
                }
                invalidate() // para mostrar u ocultar "Sin resultados"
            }
        })
        search.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                val e = view?.firstMatch()
                if (e != null) {
                    val r = RectF(width / 2f - dp(30f), height / 2f - dp(30f), width / 2f + dp(30f), height / 2f + dp(30f))
                    callback?.onLaunch(e, r)
                }
                true
            } else false
        }
    }

    private var imeInset = 0f

    /** alto del teclado: la vista de apps sube por encima para que no tape los resultados */
    fun setIme(px: Float) {
        if (kotlin.math.abs(px - imeInset) < 0.5f) return
        imeInset = px
        val up = px > insetBottom + dp(60f)
        if (up) islandIme = px
        val t = if (up) 1f else 0f
        if (islandSp.target != t) {
            // gota de agua: sin rebote y despacio (amortiguación crítica); la forma la da placeIsland()
            islandSp.tune(60f * S.p(S.mp(S.MO_MENU, S.MP_SPEED)), 1f)
            islandSp.to(t)
            ticker.kick()
        }
        placeIsland()
        val v = view ?: return
        // con la isla abajo, las apps se quedan por encima de la isla (no solo del teclado)
        v.imeInset = if (up) px + ISLAND_H + dp(20f) else px
        v.onImeChanged()
    }

    private val ISLAND_H: Float get() = dp(48f)

    /** coloca la barra: arriba, o como isla justo encima del teclado según su altura */
    private fun placeIsland() {
        val a = clamp01(sp.value)
        val q = islandSp.value
        val restTop = insetTop + dp(16f)
        val islandTop = height - islandIme - dp(12f) - ISLAND_H
        // Gota de agua en tres tiempos (q va de 0 a 1, y al revés al cerrar el teclado):
        //  1) la barra se recoge en una gota redonda,  2) la gota viaja hasta encima del teclado
        //     alargándose un poco mientras se mueve,  3) al llegar se extiende hasta su tamaño.
        val t = clamp01(q)
        val gather = smooth(t / 0.28f)                 // 0 → 1: recogerse
        val travel = smooth((t - 0.2f) / 0.58f)        // 0 → 1: viajar
        val spread = smooth((t - 0.72f) / 0.28f)       // 0 → 1: extenderse al llegar
        val bw = search.width.toFloat().coerceAtLeast(1f)
        val dropW = (ISLAND_H * 1.05f / bw).coerceAtMost(1f)
        // ancho: entero → gota → (en el sitio) un poco más estrecho que arriba, como isla
        val wf = lerp(lerp(1f, dropW, gather), 0.92f, spread)
        val moving = 4f * travel * (1f - travel)       // 0 en los extremos, 1 a mitad del viaje
        val enter = lerp(0.9f, 1f, a)
        search.translationY = (1f - a) * -dp(30f) + (islandTop - restTop) * travel
        search.scaleX = enter * wf
        search.scaleY = enter * (1f + 0.22f * moving) * lerp(1f, 0.94f, gather * (1f - spread))
        search.translationZ = dp(6f) * t
        // el texto y los iconos de la barra se esconden mientras es gota y vuelven al extenderse
        val content = clamp01(1f - gather * (1f - spread) * 1.6f)
        setSearchContentAlpha(content)
    }

    private fun smooth(x: Float): Float {
        val v = clamp01(x)
        return v * v * (3f - 2f * v)
    }

    private var contentA = -1
    private fun setSearchContentAlpha(f: Float) {
        val al = (255 * clamp01(f)).toInt()
        if (al == contentA) return
        contentA = al
        search.setHintTextColor((0xB3 * al / 255) shl 24 or 0xFFFFFF)
        val tc = S.col(S.tp(S.TX_SEARCH, S.TP_COLOR), Color.WHITE)
        search.setTextColor((((tc ushr 24) * al) / 255) shl 24 or (tc and 0xFFFFFF))
        for (d in search.compoundDrawablesRelative) d?.alpha = al
    }

    fun setInsets(top: Float, bottom: Float) {
        insetTop = top
        insetBottom = bottom
        view?.insetTop = top
        view?.insetBottom = bottom
        placeSearch()
    }

    private fun placeSearch() {
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, dp(48f).toInt())
        lp.topMargin = (insetTop + dp(16f)).toInt()
        lp.leftMargin = dp(20f).toInt()
        lp.rightMargin = dp(20f).toInt()
        search.layoutParams = lp
    }

    private fun ensureView() {
        val st = Prefs.drawerStyle
        if (view != null && st == viewStyle) return
        view?.let { removeView(it) }
        val v: AppsView = when (st) {
            1 -> FanView(context)
            2 -> GridPopupView(context)
            3 -> ListAppsView(context)
            4 -> GravityView(context)
            5 -> PlaylistView(context)
            6 -> HoneyView(context)
            7 -> WheelView(context)
            else -> LaunchpadView(context)
        }
        viewStyle = st
        v.insetTop = insetTop
        v.insetBottom = insetBottom
        v.imeInset = imeInset
        v.listener = object : AppsView.Listener {
            override fun onLaunch(e: AppEntry, from: RectF) { callback?.onLaunch(e, from) }
            override fun onMenu(e: AppEntry, anchor: RectF) { callback?.onMenu(e, anchor) }
            override fun onClose() { close() }
            override fun onTouchStart() {
                callback?.dismissMenu()
                hideKeyboard()
            }
            override fun onPull(k: Float) {
                if (kotlin.math.abs(k - pullK) < 0.002f) return
                pullK = k
                applyProgress()
            }
            override fun onAddHome(e: AppEntry) { callback?.onAddHome(e) }
        }
        addView(v, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (search.parent == null) {
            placeSearch()
            addView(search)
        }
        view = v
        v.setData(AppRepo.sorted(AppRepo.visible()), AppRepo.sorted(AppRepo.apps))
    }

    fun refreshData() {
        view?.setData(AppRepo.sorted(AppRepo.visible()), AppRepo.sorted(AppRepo.apps))
    }

    /** abre la vista con el teclado listo para buscar */
    fun showKeyboard() {
        search.requestFocus()
        search.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun rebuildScrim() {
        val h = height.toFloat().coerceAtLeast(1f)
        val pick = S.col(S.L_SCRIM, 0)
        // "Automático" = degradado original azul noche
        val cols = if (pick == 0) intArrayOf(0xB3060A18.toInt(), 0x8C0A1230.toInt(), 0xCC050814.toInt()) else {
            // el color se oscurece mucho: tiñe el fondo sin quitar contraste a iconos y nombres
            val c = blend(pick, 0xFF000000.toInt(), 0.74f) and 0x00FFFFFF
            val c2 = blend(pick, 0xFF000000.toInt(), 0.86f) and 0x00FFFFFF
            intArrayOf(c or 0xB3000000.toInt(), c or 0x8C000000.toInt(), c2 or 0xCC000000.toInt())
        }
        scrimShader = LinearGradient(0f, 0f, 0f, h, cols, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    /** Llamar cuando cambian los ajustes (estilo de vista, tamaños...). */
    fun styleChanged() {
        if (!isOpen && Prefs.drawerStyle != viewStyle) {
            view?.let { removeView(it) }
            view = null
            viewStyle = -1
        }
    }

    fun open(anchor: Float) {
        prepareOpen(anchor)
        if (S.b(S.DR_KEYBOARD) && S.b(S.DR_SEARCH)) showKeyboard()
        sp.to(1f)
        ticker.kick()
    }

    /** empieza a abrirse siguiendo al dedo (deslizar hacia arriba en el inicio) */
    fun beginDrag(anchor: Float) {
        prepareOpen(anchor)
        sp.snap(sp.value)
        ticker.kick()
    }

    fun dragTo(p: Float) {
        if (!isOpen) return
        sp.snap(p)
        ticker.kick()
    }

    /** el dedo se soltó: termina de abrirse (o vuelve a cerrarse) con la velocidad que llevaba */
    fun endDrag(open: Boolean, velocity: Float) {
        if (open) {
            Mo.tune(sp, S.MO_DRAWER_OPEN, 230f, 0.82f)
            sp.velocity = velocity
            sp.to(1f)
            ticker.kick()
            if (S.b(S.DR_KEYBOARD) && S.b(S.DR_SEARCH)) showKeyboard()
        } else {
            close()
            sp.velocity = velocity
        }
    }

    private fun prepareOpen(anchor: Float) {
        anchorX = anchor
        ensureView()
        val v = view ?: return
        v.anchorX = anchor
        search.setText("")
        if (!isOpen) {
            isOpen = true
            // si aún se estaba cerrando, la animación da la vuelta desde donde va (sin saltar a 0)
            if (visibility != VISIBLE) {
                visibility = VISIBLE
                v.progress = 0f
                sp.snap(0f)
                v.post { if (isOpen && view === v) v.onOpen() }
            }
            if (Prefs.drawerStyle == 4) Mo.tune(sp, S.MO_DRAWER_OPEN, 140f, 1f) else Mo.tune(sp, S.MO_DRAWER_OPEN, 230f, 0.82f)
            search.visibility = if (S.b(S.DR_SEARCH)) View.VISIBLE else View.GONE
            search.textSize = 16f * S.p(S.tp(S.TX_SEARCH, S.TP_SIZE))
            search.setTextColor(S.col(S.tp(S.TX_SEARCH, S.TP_COLOR), Color.WHITE))
            contentA = -1
            search.background = GlassDrawable(22f, 1f, S.SF_SEARCH)
            rebuildScrim()
            Hx.fire(this, S.HE_PANEL)
        }
    }

    /**
     * Seguridad contra "apps fantasma": cerrada o aún invisible, nunca recibe toques
     * (si no, tocar el inicio abriría apps que no se ven).
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isOpen) {
            // cerrada pero aún visible y quieta: se termina de cerrar
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && !sp.active) finishClose()
            return false
        }
        if (sp.value < 0.25f) {
            // se está abriendo; si el resorte se quedó parado, se reanuda
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && !sp.active) {
                sp.to(1f)
                ticker.kick()
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Si quedó visible estando cerrada (p. ej. tras pausar el launcher), se oculta del todo. */
    fun sanitize() {
        if (!isOpen && visibility == VISIBLE && !sp.active) finishClose()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        hideKeyboard()
        search.clearFocus()
        Mo.tune(sp, S.MO_DRAWER_CLOSE, 260f, 1f)
        sp.to(0f)
        ticker.kick()
    }

    fun closeNow() {
        isOpen = false
        hideKeyboard()
        sp.snap(0f)
        finishClose()
    }

    private fun finishClose() {
        pullK = 0f
        view?.onClosed()
        view?.progress = 0f
        visibility = GONE
        callback?.onProgress(0f)
        callback?.onClosed()
        styleChanged()
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
    }

    /** tirar para cerrar: el fondo se aclara, la barra se va y asoma el inicio mientras el dedo baja */
    private var pullK = 0f

    private fun applyProgress() {
        val a = clamp01(sp.value)
        search.alpha = a * (1f - pullK)
        // la barra entra desde arriba creciendo, y con el teclado se vuelve isla
        placeIsland()
        callback?.onProgress(a * (1f - pullK * 0.85f))
        invalidate()
    }

    private fun onFrame(): Boolean {
        val p = sp.value
        val v = view
        if (v != null) {
            v.progress = p
            v.invalidate()
        }
        applyProgress()
        if (!sp.active && !isOpen && p <= 0.001f) {
            finishClose()
            return false
        }
        return false
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        rebuildScrim()
    }

    override fun onDraw(c: Canvas) {
        val a = clamp01(sp.value)
        scrim.shader = scrimShader
        scrim.alpha = (255 * a * (1f - pullK * 0.8f) * S.p(S.DR_DIM) * (if (Prefs.drawerStyle == 1 || Prefs.drawerStyle == 2) 0.75f else 1f)).toInt().coerceIn(0, 255)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
    }

    /** "Sin resultados": se dibuja encima de la vista cuando la búsqueda no encuentra nada */
    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (view == null) return
        val e = emptySp.value
        if (e < 0.01f) return
        val a = clamp01(sp.value) * clamp01(e)
        val cx = width / 2f
        val freeBottom = if (islandSp.target > 0.5f) height - islandIme - ISLAND_H - dp(20f) else height * 0.84f
        val cy = (insetTop + dp(80f) + freeBottom) / 2f - dp(30f)
        val r = dp(34f) * e
        emptyRect.set(cx - r, cy - r, cx + r, cy + r)
        emptyGlass.draw(c, emptyRect, r, a * 0.8f, true, S.SF_SEARCH)
        Glyphs.draw(c, Glyphs.SEARCH, cx, cy, dp(34f) * e, Color.WHITE, a)
        emptyPaint.textSize = dp(18f)
        emptyPaint.alpha = (255 * a).toInt()
        c.drawText("Sin resultados", cx, cy + dp(66f), emptyPaint)
        emptyPaint.textSize = dp(14f)
        emptyPaint.alpha = (170 * a).toInt()
        c.drawText("Ninguna app coincide con «" + emptyQuery + "»", cx, cy + dp(92f), emptyPaint)
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }
}

/** Contenedor de Recientes con su propio resorte de entrada/salida. */
class RecentsHost(ctx: Context) : FrameLayout(ctx) {
    val view = RecentsView(ctx)
    private val sp = SpringValue(0f, 240f, 27f, 0.001f)
    private val ticker = Ticker { onFrame() }
    private val scrim = Paint()
    var isOpen = false
        private set
    var onProgress: ((Float) -> Unit)? = null

    init {
        setWillNotDraw(false)
        ticker.add(sp)
        visibility = GONE
        scrim.color = 0xFF050814.toInt()
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun open(items: List<AppEntry>) {
        view.setItems(items)
        isOpen = true
        visibility = VISIBLE
        if (Prefs.recentsAnim == 0) Mo.tune(sp, S.MO_RECENTS_OPEN, 240f, 0.85f) else Mo.tune(sp, S.MO_RECENTS_OPEN, 200f, 0.75f)
        Hx.fire(this, S.HE_PANEL)
        sp.to(1f)
        ticker.kick()
    }

    /**
     * Seguridad contra "apps fantasma": cerrada o aún invisible, nunca recibe toques
     * (si no, tocar el inicio abriría apps que no se ven).
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isOpen) {
            // cerrada pero aún visible y quieta: se termina de cerrar
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && !sp.active) closeNow()
            return false
        }
        if (sp.value < 0.25f) {
            // se está abriendo; si el resorte se quedó parado, se reanuda
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && !sp.active) {
                sp.to(1f)
                ticker.kick()
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Si quedó visible estando cerrada (p. ej. tras pausar el launcher), se oculta del todo. */
    fun sanitize() {
        if (!isOpen && visibility == VISIBLE && !sp.active) closeNow()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        Mo.tune(sp, S.MO_RECENTS_CLOSE, 280f, 1f)
        sp.to(0f)
        ticker.kick()
    }

    fun closeNow() {
        isOpen = false
        sp.snap(0f)
        visibility = GONE
        onProgress?.invoke(0f)
    }

    private fun onFrame(): Boolean {
        view.progress = sp.value
        view.invalidate()
        onProgress?.invoke(clamp01(sp.value))
        invalidate()
        if (!isOpen && !sp.active) {
            visibility = GONE
            onProgress?.invoke(0f)
        }
        return false
    }

    override fun onDraw(c: Canvas) {
        // el fondo de recientes sigue el color de los paneles (temas de color)
        val pick = S.col(S.L_SCRIM, 0)
        scrim.color = if (pick == 0) 0xFF050814.toInt() else blend(pick, 0xFF000000.toInt(), 0.82f)
        scrim.alpha = (170 * clamp01(sp.value) * S.p(S.RC_DIM)).toInt().coerceIn(0, 255)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }
}

/** Utilidad para View.visibility legible. */
fun View.shown(): Boolean = visibility == View.VISIBLE
