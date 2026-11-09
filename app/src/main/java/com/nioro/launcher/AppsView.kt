package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewConfiguration
import java.util.Locale

/**
 * Base de las 5 vistas de aplicaciones. Cada una dibuja todo en un solo Canvas.
 * El "progress" (0 -> 1) lo controla DrawerHost con un resorte; las vistas solo
 * lo leen para sus efectos de entrada y salida.
 */
abstract class AppsView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onLaunch(e: AppEntry, from: RectF)
        fun onMenu(e: AppEntry, anchor: RectF)
        fun onClose()
        fun onTouchStart()
        /** cuánto se está tirando para cerrar (0..1): el fondo y la barra responden al dedo */
        fun onPull(k: Float) {}
    }

    var listener: Listener? = null
    var progress = 0f
    var insetTop = 0f
    var insetBottom = 0f
    /** alto del teclado (px, desde abajo): con el teclado abierto las vistas suben por encima */
    var imeInset = 0f
    /** borde de abajo útil: la barra del sistema o el teclado, lo que sea más alto */
    protected val bottomInset: Float get() = maxOf(insetBottom, imeInset)
    protected val keyboardUp: Boolean get() = imeInset > insetBottom + 1f

    /** el teclado aparece/desaparece (se llama en cada fotograma de su animación) */
    open fun onImeChanged() {}
    var anchorX = 0f

    protected var apps: List<AppEntry> = emptyList()
    protected var shown: List<AppEntry> = emptyList()
    protected var queryText = ""
    protected val ticker = Ticker { dt -> onFrame(dt) }
    protected val uiHandler = Handler(Looper.getMainLooper())
    protected val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    protected val glass = Glass()
    protected val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    protected val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    protected val dst = RectF()
    private val labelCache = HashMap<String, String>()
    protected val pressSp: SpringValue = ticker.add(SpringValue(1f, 700f, 53f, 0.0005f))
    /** al cambiar la búsqueda los resultados aparecen con un pequeño "pop" (0 -> 1) */
    private val filterSp: SpringValue = ticker.add(SpringValue(1f, 380f, 30f, 0.002f))
    /** hay texto buscado pero ninguna app coincide */
    val noResults: Boolean get() = queryText.isNotEmpty() && shown.isEmpty()
    val query: String get() = queryText
    /** zona de iconos de esta vista (S.IA_*) para sus ajustes propios */
    abstract val iconArea: Int
    protected val longMs: Long get() = S.f(S.G_ICON_LONG_MS).toLong()
    private var allApps: List<AppEntry> = emptyList()
    private var pressKey: String? = null

    /** El icono tocado se "hunde" con un resorte rápido y vuelve con un rebote suave. */
    protected fun press(e: AppEntry?) {
        if (e == null) return
        pressKey = e.key
        Mo.tune(pressSp, S.MO_PRESS, 700f, 1f)
        pressSp.to(1f - IconFx.pressDepth(iconArea))
        Hx.fire(this, S.HE_ICON)
        ticker.kick()
    }

    /** pulsación larga: el icono se levanta un poco antes de abrir su menú (como iOS) */
    protected fun lift() {
        Mo.tune(pressSp, S.MO_PRESS, 500f, 0.5f)
        pressSp.to(1.08f)
        ticker.kick()
    }

    protected fun unpress() {
        if (pressKey == null && pressSp.target >= 1f) return
        Mo.tune(pressSp, S.MO_RELEASE, 420f, IconFx.releaseRatio(iconArea))
        pressSp.to(1f)
        ticker.kick()
    }
    private var labelWidth = 0f

    init {
        textPaint.color = Color.WHITE
        textPaint.textSize = dp(11.5f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.setShadowLayer(dp(3f), 0f, dp(1f), 0x99000000.toInt())
    }

    open fun setData(list: List<AppEntry>, all: List<AppEntry> = list) {
        Tx.apply(textPaint, S.TX_LABEL_DRAWER, dp(Prefs.labelSize.toFloat()), 400, dp(3f), 0x99)
        apps = list
        allApps = all
        refilter()
    }

    fun setQuery(q: String) {
        val nq = q.trim().lowercase(Locale.getDefault())
        if (nq == queryText) return
        queryText = nq
        refilter()
        Mo.tune(filterSp, S.MO_DRAWER_OPEN, 380f, 0.7f)
        filterSp.snap(0.55f)
        filterSp.to(1f)
        ticker.kick()
    }

    private fun refilter() {
        shown = if (queryText.isEmpty()) apps else {
            val src = if (S.b(S.DR_HIDDEN_SEARCH)) allApps else apps
            val pkg = S.b(S.DR_PKG)
            src.filter { it.label.lowercase().contains(queryText) || (pkg && it.pkg.lowercase().contains(queryText)) }
        }
        labelCache.clear()
        onDataChanged()
        invalidate()
    }

    fun firstMatch(): AppEntry? = if (shown.isEmpty()) null else shown[0]

    abstract fun onFrame(dt: Float): Boolean
    open fun onDataChanged() {}
    open fun onOpen() {}
    open fun onClosed() {}

    override fun onDetachedFromWindow() {
        uiHandler.removeCallbacksAndMessages(null)
        ticker.stop()
        super.onDetachedFromWindow()
    }

    protected fun labelOf(e: AppEntry, maxW: Float): String {
        if (maxW != labelWidth) {
            labelCache.clear()
            labelWidth = maxW
        }
        var s = labelCache[e.key]
        if (s == null) {
            s = TextUtils.ellipsize(Tx.str(S.TX_LABEL_DRAWER, e.label), textPaint, maxW, TextUtils.TruncateAt.END).toString()
            labelCache[e.key] = s
        }
        return s
    }

    protected fun drawIcon(c: Canvas, e: AppEntry, cx: Float, cy: Float, size: Float, a: Float) {
        val b = e.icon ?: return
        val al = (255f * a).toInt().coerceIn(0, 255)
        if (al == 0) return
        if (pressKey != null && !pressSp.active && pressSp.target == 1f) pressKey = null
        val f = filterSp.value
        val pk = e.key == pressKey
        val sz = (if (pk) size * pressSp.value else size) * IconFx.scale(iconArea) * f
        // pulsado: se oscurece en proporción a lo que se hunde
        val dark = if (pk) clamp01((1f - pressSp.value) / 0.14f) else 0f
        IconFx.draw(c, b, e.tint, cx, cy, sz, a * clamp01(f * 1.6f - 0.6f), iconArea, 0f, dark)
    }

    protected fun drawLabel(c: Canvas, e: AppEntry, cx: Float, y: Float, maxW: Float, a: Float) {
        if (!Prefs.labels || !S.b(S.DR_LABELS)) return
        val al = Tx.alpha(S.TX_LABEL_DRAWER, a * clamp01(filterSp.value * 2f - 1f))
        if (al == 0) return
        textPaint.alpha = al
        c.drawText(labelOf(e, maxW), cx, y + Tx.dy(S.TX_LABEL_DRAWER), textPaint)
    }
}
