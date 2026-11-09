package com.nioro.launcher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import java.util.concurrent.Executors

class AppEntry(val key: String, val pkg: String, val cls: String, val label: String) {
    var icon: Bitmap? = null
    var tint: Int = 0xFF3A5BA0.toInt()
    var installed: Long = 0L
}

/** Lista de apps instaladas + iconos "horneados" con forma squircle y brillo de cristal. */
object AppRepo {
    var apps: List<AppEntry> = emptyList()
        private set
    private var byKey: Map<String, AppEntry> = emptyMap()
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, Bitmap>()
    private val tintCache = HashMap<String, Int>()
    private val installCache = HashMap<String, Long>()
    var launchpadIcon: Bitmap? = null
        private set
    var recentsIcon: Bitmap? = null
        private set

    fun get(key: String): AppEntry? = byKey[key]

    /** Borra el icono en caché de un paquete (se actualizó o cambió su icono). */
    fun evict(pkg: String) {
        exec.execute {
            val it = cache.keys.iterator()
            while (it.hasNext()) {
                val k = it.next()
                if (k.startsWith("$pkg/")) {
                    it.remove()
                    tintCache.remove(k)
                }
            }
        }
    }

    /** aplica el orden elegido en Ajustes a una lista de apps */
    fun sorted(list: List<AppEntry>): List<AppEntry> {
        return when (S.i(S.DR_SORT)) {
            1 -> list.sortedByDescending { it.label.lowercase() }
            2 -> {
                val order = Recents.order()
                list.sortedWith(compareBy({ val i = order.indexOf(it.key); if (i < 0) Int.MAX_VALUE else i }, { it.label.lowercase() }))
            }
            3 -> list.sortedByDescending { it.installed }
            else -> list
        }
    }

    fun visible(): List<AppEntry> {
        val h = Prefs.hidden()
        return apps.filter { it.key !in h }
    }

    fun firstOfPackage(pkg: String): AppEntry? {
        for (a in apps) if (a.pkg == pkg) return a
        return null
    }

    fun intentFor(e: AppEntry): Intent {
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_LAUNCHER)
        i.component = ComponentName(e.pkg, e.cls)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return i
    }

    private fun px(): Int = dp(when (S.i(S.P_ICON_RES)) { 0 -> 56f; 2 -> 96f; else -> 72f }).toInt()

    fun load(ctx: Context, done: () -> Unit) {
        val app = ctx.applicationContext
        exec.execute {
            try {
                // si cambió la forma de los iconos, se vuelven a hornear todos
                val shape = Prefs.iconShape
                val key = shape * 10 + S.i(S.P_ICON_RES)
                val shapeChanged = key != workerShape
                if (shapeChanged) {
                    cache.clear()
                    tintCache.clear()
                    workerShape = key
                }
                val pm = app.packageManager
                val q = Intent(Intent.ACTION_MAIN)
                q.addCategory(Intent.CATEGORY_LAUNCHER)
                val list = pm.queryIntentActivities(q, 0)
                val out = ArrayList<AppEntry>()
                for (ri in list) {
                    // si se eligió otra forma mientras horneaba, se abandona: la carga siguiente la hará
                    if (Prefs.iconShape != shape || S.i(S.P_ICON_RES) != key % 10) throw InterruptedException("forma cambiada")
                    val ai = ri.activityInfo ?: continue
                    val key = ai.packageName + "/" + ai.name
                    val label = try { ri.loadLabel(pm).toString() } catch (t: Throwable) { ai.packageName }
                    val e = AppEntry(key, ai.packageName, ai.name, label)
                    e.installed = installCache.getOrPut(ai.packageName) {
                        try { pm.getPackageInfo(ai.packageName, 0).firstInstallTime } catch (t: Throwable) { 0L }
                    }
                    val cached = cache[key]
                    if (cached != null) {
                        e.icon = cached
                        e.tint = tintCache[key] ?: e.tint
                    } else {
                        val d = try { ri.loadIcon(pm) } catch (t: Throwable) { null }
                        if (d != null) {
                            val full = renderContent(d, px())
                            e.tint = average(full)
                            val b = if (shape == 4) bakeGlass(d, full, e.tint) else bake(full, shape)
                            e.icon = b
                            cache[key] = b
                            tintCache[key] = e.tint
                        }
                    }
                    if (e.icon == null) {
                        // sin icono la app quedaba invisible pero se podía tocar
                        val b = fallbackIcon(label, px(), shape)
                        e.icon = b
                        cache[key] = b
                    }
                    out.add(e)
                }
                out.sortBy { it.label.lowercase() }
                if (launchpadIcon == null || shapeChanged) buildSpecials(shape)
                val map = HashMap<String, AppEntry>()
                for (e in out) map[e.key] = e
                main.post {
                    apps = out
                    byKey = map
                    bakedShape = shape
                    bakedRes = key % 10
                    version++
                    done()
                }
            } catch (t: Throwable) {
                workerShape = -1
                main.post { done() }
            }
        }
    }

    @Volatile var bakedShape = -1
        private set
    private var workerShape = -1
    @Volatile var bakedRes = -1
        private set
    /** sube cada vez que cambian los iconos (las vistas lo comparan para refrescarse) */
    var version = 0
        private set

    // ---------------- Iconos ----------------
    /** 0 Squircle (Apple) · 1 Círculo · 2 One UI (cuadrado redondeado) · 3 Gota · 4 Cristal (squircle). */
    fun shapePath(shape: Int, size: Float): Path {
        return when (shape) {
            1 -> Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
            2 -> Path().apply { addRoundRect(RectF(0f, 0f, size, size), size * 0.3f, size * 0.3f, Path.Direction.CW) }
            3 -> {
                val r = size * 0.5f
                val k = size * 0.14f
                Path().apply {
                    addRoundRect(RectF(0f, 0f, size, size), floatArrayOf(r, r, r, r, k, k, r, r), Path.Direction.CW)
                }
            }
            else -> squirclePath(size)
        }
    }

    fun squirclePath(size: Float): Path {
        val p = Path()
        val r = size / 2.0
        val n = 5.0
        val steps = 128
        for (i in 0..steps) {
            val t = i * 2.0 * Math.PI / steps
            val ct = Math.cos(t)
            val st = Math.sin(t)
            val x = r + r * Math.signum(ct) * Math.pow(Math.abs(ct), 2.0 / n)
            val y = r + r * Math.signum(st) * Math.pow(Math.abs(st), 2.0 / n)
            if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
        }
        p.close()
        return p
    }

    private fun renderContent(d: Drawable, px: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (d is AdaptiveIconDrawable) {
            val ext = (px * 0.25f).toInt()
            val bg = d.background
            val fg = d.foreground
            if (bg != null) {
                bg.setBounds(-ext, -ext, px + ext, px + ext)
                bg.draw(c)
            }
            if (fg != null) {
                fg.setBounds(-ext, -ext, px + ext, px + ext)
                fg.draw(c)
            }
        } else {
            c.drawColor(0xFFF1F3F8.toInt())
            val inset = (px * 0.14f).toInt()
            d.setBounds(inset, inset, px - inset, px - inset)
            d.draw(c)
        }
        return b
    }

    /**
     * Estilo 5 "Cristal": el dibujo del icono flota sobre una baldosa de cristal teñida con su color,
     * con borde grueso brillante (como los iconos transparentes de iOS 26 / Liquid Glass).
     */
    private fun bakeGlass(d: Drawable, full: Bitmap, tint: Int): Bitmap {
        val size = full.width
        val s = size.toFloat()
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val path = squirclePath(s)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.color = blend(tint, 0xFF0B1430.toInt(), 0.55f)
        bg.alpha = 205
        c.drawPath(path, bg)
        val lens = Paint(Paint.ANTI_ALIAS_FLAG)
        lens.shader = LinearGradient(0f, 0f, 0f, s, intArrayOf(0x59FFFFFF, 0x0DFFFFFF, 0x26FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, lens)
        // primer plano del icono
        val fg = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cf = Canvas(fg)
        if (d is AdaptiveIconDrawable && d.foreground != null) {
            val ext = (size * 0.25f).toInt()
            d.foreground.setBounds(-ext, -ext, size + ext, size + ext)
            d.foreground.draw(cf)
        } else {
            val inset = (size * 0.2f).toInt()
            d.setBounds(inset, inset, size - inset, size - inset)
            d.draw(cf)
        }
        val fp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // cuánto del cuadrado tapa el dibujo de delante:
        //  - casi nada: la app guarda su dibujo en la capa de fondo (p. ej. Brother) → antes salía vacío;
        //  - casi todo: el dibujo ocupa todo el cuadrado (AliExpress, Canon...) → antes asomaba por las esquinas.
        // En esos dos casos se usa el icono completo (fondo + dibujo) dentro del cristal.
        val cover = coverage(fg)
        val src = if (cover < 0.04f || cover > 0.78f) full else fg
        // siempre recortado a la forma del cristal (un poco hacia dentro, para no pisar el borde)
        val clip = squirclePath(s * 0.92f)
        clip.offset(s * 0.04f, s * 0.04f)
        c.save()
        c.clipPath(clip)
        if (src === full) {
            // icono completo: llena el cristal y se le deja un velo del tinte para que siga pareciendo cristal
            c.drawBitmap(full, null, RectF(0f, 0f, s, s), fp)
            val veil = Paint(Paint.ANTI_ALIAS_FLAG)
            veil.color = blend(tint, 0xFF0B1430.toInt(), 0.55f)
            veil.alpha = 46
            c.drawPath(path, veil)
        } else {
            val sc = 0.94f
            val off = s * (1f - sc) / 2f
            c.drawBitmap(fg, null, RectF(off, off, s - off, s - off), fp)
        }
        c.restore()
        // brillo de cristal también por encima del dibujo
        c.drawPath(path, lens)
        fg.recycle()
        full.recycle()
        val rim = Paint(Paint.ANTI_ALIAS_FLAG)
        rim.style = Paint.Style.STROKE
        rim.strokeWidth = s * 0.04f
        rim.shader = LinearGradient(0f, 0f, s, s, intArrayOf(0xF2FFFFFF.toInt(), 0x33FFFFFF, 0x1AFFFFFF, 0x99FFFFFF.toInt()), floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
        // borde dibujado sobre una forma un poco más pequeña: queda dentro del icono y con bordes suaves
        val sw = rim.strokeWidth
        val inner = squirclePath(s - sw)
        inner.offset(sw / 2f, sw / 2f)
        c.drawPath(inner, rim)
        return out
    }

    /** Aplica la máscara con la forma elegida y hornea el borde y el brillo superior "liquid glass". */
    private fun bake(content: Bitmap, shape: Int = Prefs.iconShape): Bitmap {
        val size = content.width
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val path = shapePath(if (shape == 4) 0 else shape, size.toFloat())
        val mask = Paint(Paint.ANTI_ALIAS_FLAG)
        mask.color = Color.WHITE
        c.drawPath(path, mask)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(content, 0f, 0f, p)
        content.recycle()

        val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
        gloss.shader = LinearGradient(
            0f, 0f, 0f, size * 0.62f,
            intArrayOf(0x4DFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        c.drawPath(path, gloss)

        val rim = Paint(Paint.ANTI_ALIAS_FLAG)
        rim.style = Paint.Style.STROKE
        rim.strokeWidth = size * 0.022f
        rim.shader = LinearGradient(
            0f, 0f, 0f, size.toFloat(),
            intArrayOf(0xB3FFFFFF.toInt(), 0x14FFFFFF, 0x66FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        val sw = rim.strokeWidth
        val inner = shapePath(if (shape == 4) 0 else shape, size - sw)
        inner.offset(sw / 2f, sw / 2f)
        c.drawPath(inner, rim)
        return out
    }

    /** Icono de reserva (inicial sobre color) para apps cuyo icono no se pudo cargar. */
    private fun fallbackIcon(label: String, size: Int, shape: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFF4A5FA8.toInt(), 0xFF1B2440.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)
        val t = Paint(Paint.ANTI_ALIAS_FLAG)
        t.color = Color.WHITE
        t.textSize = size * 0.46f
        t.textAlign = Paint.Align.CENTER
        val ch = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        c.drawText(ch, size / 2f, size / 2f - (t.descent() + t.ascent()) / 2f, t)
        return bake(b, shape)
    }

    /** fracción (0..1) del cuadrado que tapa el dibujo (píxeles bastante opacos), muestreada en 24×24 */
    private fun coverage(b: Bitmap): Float {
        return try {
            val n = 24
            val s = Bitmap.createScaledBitmap(b, n, n, true)
            val px = IntArray(n * n)
            s.getPixels(px, 0, n, 0, 0, n, n)
            if (s !== b) s.recycle()
            var on = 0
            for (p in px) if ((p ushr 24) > 60) on++
            on / (n * n).toFloat()
        } catch (t: Throwable) {
            0.5f
        }
    }

    private fun average(b: Bitmap): Int {
        return try {
            val s = Bitmap.createScaledBitmap(b, 1, 1, true)
            val col = s.getPixel(0, 0)
            if (s !== b) s.recycle()
            col or 0xFF000000.toInt()
        } catch (t: Throwable) {
            0xFF3A5BA0.toInt()
        }
    }

    private fun buildSpecials(shape: Int) {
        val size = px()
        // Launchpad: degradado + rejilla 3x3 de cuadritos de colores.
        val a = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val ca = Canvas(a)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            0xFF6A4CFF.toInt(), 0xFF22B5FF.toInt(), Shader.TileMode.CLAMP
        )
        ca.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)
        val cols = intArrayOf(
            0xFFFFD166.toInt(), 0xFFFF7A90.toInt(), 0xFFB9F27C.toInt(),
            0xFFFFFFFF.toInt(), 0xFF7CE0FF.toInt(), 0xFFFFA24C.toInt(),
            0xFFC7A6FF.toInt(), 0xFFFFFFFF.toInt(), 0xFF8CFFC4.toInt()
        )
        val cell = size / 5.2f
        val gap = cell * 0.38f
        val start = (size - (cell * 3 + gap * 2)) / 2f
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        for (r in 0 until 3) for (cc in 0 until 3) {
            dot.color = cols[r * 3 + cc]
            val l = start + cc * (cell + gap)
            val t = start + r * (cell + gap)
            ca.drawRoundRect(RectF(l, t, l + cell, t + cell), cell * 0.3f, cell * 0.3f, dot)
        }
        launchpadIcon = bake(a, shape)

        // Recientes: tres tarjetas apiladas.
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cb = Canvas(b)
        val bg2 = Paint(Paint.ANTI_ALIAS_FLAG)
        bg2.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            0xFF1B2440.toInt(), 0xFF4A5FA8.toInt(), Shader.TileMode.CLAMP
        )
        cb.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg2)
        val card = Paint(Paint.ANTI_ALIAS_FLAG)
        val w = size * 0.5f
        val h = size * 0.34f
        for (i in 0 until 3) {
            card.color = Color.argb(70 + i * 80, 255, 255, 255)
            val l = (size - w) / 2f + (i - 1) * size * 0.04f
            val t = size * 0.2f + i * size * 0.16f
            cb.drawRoundRect(RectF(l, t, l + w, t + h), size * 0.07f, size * 0.07f, card)
        }
        recentsIcon = bake(b, shape)
    }

    // ---------------- Apps por defecto ----------------
    fun defaultDockKeys(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val intents = ArrayList<Intent>()
        intents.add(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
        intents.add(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
        intents.add(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        intents.add(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")))
        intents.add(Intent(Settings.ACTION_SETTINGS))
        val keys = ArrayList<String>()
        for (i in intents) {
            try {
                val ri = pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)
                for (r in ri) {
                    val pkg = r.activityInfo?.packageName ?: continue
                    if (pkg == "android") continue
                    val e = firstOfPackage(pkg) ?: continue
                    if (e.key !in keys) {
                        keys.add(e.key)
                        break
                    }
                }
            } catch (t: Throwable) {
            }
        }
        return keys
    }

    fun seedHome(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val preferred = listOf(
            "com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.sec.android.gallery3d",
            "com.google.android.apps.photos", "com.google.android.apps.maps", "com.android.vending",
            "com.google.android.youtube", "com.whatsapp", "com.android.chrome", "com.sec.android.app.sbrowser",
            "com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.samsung.android.calendar",
            "com.google.android.calendar", "com.spotify.music", "com.instagram.android",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.google.android.gm",
            "com.samsung.android.app.notes", "com.sec.android.app.myfiles", "com.netflix.mediaclient",
            "org.telegram.messenger", "com.anthropic.claude", "com.openai.chatgpt", "com.nioro.life"
        )
        val out = ArrayList<String>()
        for (p in preferred) {
            val e = firstOfPackage(p) ?: continue
            if (e.key !in out) out.add(e.key)
        }
        for (e in apps) {
            if (out.size >= 20) break
            if (e.key in out) continue
            try {
                val flags = pm.getApplicationInfo(e.pkg, 0).flags
                if (flags and ApplicationInfo.FLAG_SYSTEM == 0) out.add(e.key)
            } catch (t: Throwable) {
            }
        }
        return out
    }
}

/** Apps recientes: con "Acceso de uso" usa el historial real; si no, las que abrió este launcher. */
object Recents {
    private val session = ArrayList<String>()
    private var loaded = false

    @Synchronized
    private fun snapshot(): List<String> {
        ensure()
        return ArrayList(session)
    }

    @Synchronized
    private fun ensure() {
        if (!loaded) {
            loaded = true
            session.addAll(Prefs.recentKeys())
            // también se recuerdan las apps quitadas (si no, reaparecían al reiniciarse el launcher)
            val old = System.currentTimeMillis() - 48 * 3600_000L
            for (line in Prefs.dismissedKeys()) {
                val pkg = line.substringBefore('|')
                val t = line.substringAfter('|').toLongOrNull() ?: continue
                if (t > old) dismissed[pkg] = t
            }
        }
    }

    private fun saveDismissed() {
        Prefs.setDismissedKeys(dismissed.entries.map { it.key + "|" + it.value })
    }

    @Synchronized
    fun note(key: String) {
        ensure()
        session.remove(key)
        session.add(0, key)
        while (session.size > 24) session.removeAt(session.size - 1)
        Prefs.setRecentKeys(session)
    }

    /** paquete -> momento en que se quitó de Recientes (para que no reaparezca hasta volver a usarse). */
    private val dismissed = HashMap<String, Long>()

    @Synchronized
    fun forget(key: String) {
        ensure()
        session.remove(key)
        Prefs.setRecentKeys(session)
        val pkg = key.substringBefore('/')
        dismissed[pkg] = System.currentTimeMillis()
        saveDismissed()
    }

    @Synchronized
    fun forgetAll(keys: List<String>) {
        ensure()
        val now = System.currentTimeMillis()
        for (k in keys) dismissed[k.substringBefore('/')] = now
        session.clear()
        Prefs.setRecentKeys(session)
        saveDismissed()
    }

    @Synchronized
    private fun isDismissed(pkg: String, lastUse: Long): Boolean {
        val d = dismissed[pkg] ?: return false
        return lastUse <= d
    }

    /** claves de las apps en orden de uso (para ordenar la vista de apps) */
    fun order(): List<String> = snapshot()

    fun hasUsageAccess(ctx: Context): Boolean {
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
                AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            false
        }
    }

    /** paquete -> última vez en primer plano (ms) durante las últimas 12 h. */
    private fun usagePackages(ctx: Context): LinkedHashMap<String, Long> {
        val res = LinkedHashMap<String, Long>()
        if (!hasUsageAccess(ctx)) return res
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val ev = usm.queryEvents(now - S.f(S.RC_HOURS).toLong() * 3600_000L, now)
            val e = UsageEvents.Event()
            val last = HashMap<String, Long>()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last[e.packageName] = e.timeStamp
            }
            val sorted = last.entries.sortedByDescending { it.value }
            for (en in sorted) res[en.key] = en.value
        } catch (t: Throwable) {
        }
        return res
    }

    fun list(ctx: Context): List<AppEntry> {
        val sess = snapshot()
        val out = ArrayList<AppEntry>()
        val seen = HashSet<String>()
        val hidden = Prefs.hidden()
        for ((pkg, last) in usagePackages(ctx)) {
            if (isDismissed(pkg, last)) continue
            if (pkg == ctx.packageName) continue
            val e = AppRepo.firstOfPackage(pkg) ?: continue
            if (e.key in hidden) continue
            if (seen.add(e.pkg)) out.add(e)
        }
        for (k in sess) {
            val e = AppRepo.get(k) ?: continue
            if (seen.add(e.pkg)) out.add(e)
        }
        val max = S.i(S.RC_MAX)
        return if (out.size > max) ArrayList(out.subList(0, max)) else out
    }

    /** Apps usadas en los últimos 30 min: se marcan con un puntito bajo el icono del dock. */
    fun recentPackages(ctx: Context): Set<String> {
        val sess = snapshot()
        val out = HashSet<String>()
        val now = System.currentTimeMillis()
        val win = S.f(S.K_RECENT_MIN).toLong() * 60_000L
        for ((pkg, t) in usagePackages(ctx)) if (now - t < win && !isDismissed(pkg, t)) out.add(pkg)
        if (out.isEmpty()) {
            for (i in 0 until minOf(4, sess.size)) {
                val e = AppRepo.get(sess[i]) ?: continue
                out.add(e.pkg)
            }
        }
        return out
    }
}
