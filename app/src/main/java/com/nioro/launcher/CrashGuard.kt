package com.nioro.launcher

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Red de seguridad contra cierres:
 *  - guarda el último error (con su traza) en un archivo para poder enviarlo desde Ajustes;
 *  - si el launcher se cierra varias veces seguidas, arranca en "modo seguro" (sin desenfoques ni
 *    efectos pesados) para que el sistema no lo dé por roto (MIUI muestra "Smart recovery").
 */
object CrashGuard {
    private const val LOG = "ultimo_error.txt"
    private const val TIMES = "cierres.txt"
    @Volatile var safeMode = false
        private set
    private var dir: File? = null

    fun install(ctx: Context) {
        dir = ctx.filesDir
        safeMode = recentCrashes() >= 2
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            try {
                save(ctx, th, ex)
            } catch (_: Throwable) {
            }
            if (prev != null) prev.uncaughtException(th, ex) else {
                Process.killProcess(Process.myPid())
                System.exit(10)
            }
        }
    }

    private fun save(ctx: Context, th: Thread, ex: Throwable) {
        val d = dir ?: return
        val sw = StringWriter()
        ex.printStackTrace(PrintWriter(sw))
        val ver = Updater.localVersion(ctx)
        val txt = buildString {
            append("Nioro Launcher 1.").append(ver).append('\n')
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE).append('\n')
            append("Hilo: ").append(th.name).append('\n')
            append("Hora: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append("\n\n")
            append(sw.toString().take(12000))
        }
        File(d, LOG).writeText(txt)
        val now = System.currentTimeMillis()
        val keep = times().filter { now - it < 10 * 60_000L } + now
        File(d, TIMES).writeText(keep.takeLast(8).joinToString("\n"))
    }

    private fun times(): List<Long> {
        val d = dir ?: return emptyList()
        return try {
            File(d, TIMES).readLines().mapNotNull { it.trim().toLongOrNull() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** cierres en los últimos 3 minutos */
    private fun recentCrashes(): Int {
        val now = System.currentTimeMillis()
        return times().count { now - it < 3 * 60_000L }
    }

    /** el launcher lleva un rato funcionando bien: se olvidan los cierres y se sale del modo seguro */
    fun markStable() {
        try {
            File(dir ?: return, TIMES).delete()
        } catch (_: Throwable) {
        }
        safeMode = false
    }

    fun lastError(): String? {
        val d = dir ?: return null
        return try {
            val f = File(d, LOG)
            if (f.exists()) f.readText() else null
        } catch (_: Throwable) {
            null
        }
    }

    fun clear() {
        try {
            File(dir ?: return, LOG).delete()
        } catch (_: Throwable) {
        }
    }
}
