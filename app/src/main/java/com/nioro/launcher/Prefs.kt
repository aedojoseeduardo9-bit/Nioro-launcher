package com.nioro.launcher

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlin.reflect.KProperty

class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        Prefs.init(this)
        S.init(this)
    }
}

class IntPref(private val key: String, private val def: Int) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = Prefs.rawInt(key, def)
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = Prefs.putInt(key, value)
}

class BoolPref(private val key: String, private val def: Boolean) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = Prefs.rawBool(key, def)
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = Prefs.putBool(key, value)
}

/**
 * Huecos de la pantalla de inicio. La lista del inicio guarda, por orden de celda, la clave de
 * cada app o [BLANK] para una celda vacía: así hay pantallas vacías y, al quitar un icono,
 * los demás no se mueven (como en One UI).
 */
object HomeSlots {
    const val BLANK = "."

    /** coloca la app justo después del último icono (rellena un hueco del final si lo hay) */
    fun add(list: MutableList<String>, key: String) {
        var last = -1
        for (i in list.indices) if (list[i] != BLANK) last = i
        val at = last + 1
        if (at < list.size) list[at] = key else list.add(key)
    }

    /** quita la app dejando su hueco */
    fun remove(list: MutableList<String>, key: String) {
        val i = list.indexOf(key)
        if (i >= 0) list[i] = BLANK
    }
}

/** Ajustes del launcher. Todo se guarda en SharedPreferences. */
object Prefs {
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) sp = ctx.applicationContext.getSharedPreferences("nioro_launcher", Context.MODE_PRIVATE)
    }

    private fun s(): SharedPreferences = sp!!
    fun rawInt(k: String, d: Int): Int = s().getInt(k, d)
    fun putInt(k: String, v: Int) { s().edit().putInt(k, v).apply() }
    fun rawBool(k: String, d: Boolean): Boolean = s().getBoolean(k, d)
    fun putBool(k: String, v: Boolean) { s().edit().putBoolean(k, v).apply() }

    // ---- 5 estilos por categoría (0..4) ----
    var openAnim by IntPref("openAnim", 0)
    var returnAnim by IntPref("returnAnim", 1)
    var dockMode by IntPref("dockMode", 0)
    var bounceAnim by IntPref("bounceAnim", 0)
    var pageAnim by IntPref("pageAnim", 0)
    var recentsAnim by IntPref("recentsAnim", 0)
    var drawerStyle by IntPref("drawerStyle", 0)
    var clockStyle by IntPref("clockStyle", 0)
    /** tema de color elegido (ColorThemes); -1 = personalizado */
    var colorTheme by IntPref("colorTheme", 0)
    var iconShape by IntPref("iconShape", 0)
    var rows by IntPref("rows", 0)          // 0 = automático
    var drawerCols by IntPref("drawerCols", 4)
    var labelSize by IntPref("labelSize", 12) // sp

    // ---- Apariencia / rendimiento ----
    var speedPct by IntPref("speedPct", 100)
    var glass by IntPref("glass", 60)
    var iconDp by IntPref("iconDp", 58)
    var dockDp by IntPref("dockDp", 52)
    var columns by IntPref("columns", 4)
    var magnify by IntPref("magnify", 70)
    var labels by BoolPref("labels", true)
    var saver by BoolPref("saver", false)
    var blur by BoolPref("blur", true)
    var tilt by BoolPref("tilt", false)
    var seeded by BoolPref("seeded", false)

    val speed: Float get() = speedPct / 100f

    private fun getList(k: String): MutableList<String> {
        val raw = s().getString(k, "") ?: ""
        val out = ArrayList<String>()
        for (p in raw.split('\n')) if (p.isNotEmpty()) out.add(p)
        return out
    }

    private fun putList(k: String, v: List<String>) {
        s().edit().putString(k, v.joinToString("\n")).apply()
    }

    fun homeKeys(): MutableList<String> = getList("homeKeys")
    fun setHomeKeys(v: List<String>) = putList("homeKeys", v)
    fun dockKeys(): MutableList<String> = getList("dockKeys")
    fun setDockKeys(v: List<String>) = putList("dockKeys", v)
    /** orden que el usuario eligió en la vista Playlist (arrastrando las apps) */
    fun playlistOrder(): MutableList<String> = getList("playlistOrder")
    fun setPlaylistOrder(v: List<String>) = putList("playlistOrder", v)
    /** Playlist: true = en el orden propio, false = A-Z */
    var playlistMine by BoolPref("playlistMine", true)
    fun hidden(): MutableList<String> = getList("hidden")
    fun setHidden(v: List<String>) = putList("hidden", v)
    fun knownKeys(): MutableList<String> = getList("knownKeys")
    fun setKnownKeys(v: List<String>) = putList("knownKeys", v)
    fun recentKeys(): MutableList<String> = getList("recentKeys")
    fun setRecentKeys(v: List<String>) = putList("recentKeys", v)
    fun dismissedKeys(): MutableList<String> = getList("dismissedKeys")
    fun setDismissedKeys(v: List<String>) = putList("dismissedKeys", v)

    fun resetLayout() {
        s().edit().remove("homeKeys").remove("dockKeys").putBoolean("seeded", false).apply()
    }
}
