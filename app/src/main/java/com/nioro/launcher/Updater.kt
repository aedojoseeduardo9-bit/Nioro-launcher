package com.nioro.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Actualizador integrado: lee la versión publicada en la release "latest" del repositorio,
 * descarga el APK directamente de GitHub y lo instala con el instalador del sistema.
 * No pasa por Google Play ni por el navegador. Funciona con Wi‑Fi o datos móviles.
 */
object Updater {
    private const val BASE = "https://github.com/aedojoseeduardo9-bit/Nioro-Launcher/releases/download/latest/"
    private const val VERSION_URL = BASE + "version.txt"
    private const val APK_URL = BASE + "Nioro-Launcher.apk"
    const val ACTION_STATUS = "com.nioro.launcher.INSTALL_STATUS"

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var busy = false
        private set

    fun localVersion(ctx: Context): Long {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (t: Throwable) {
            0L
        }
    }

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        // GitHub redirige a su CDN: se siguen las redirecciones a mano (hasta 5)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "NioroLauncher")
            c.setRequestProperty("Cache-Control", "no-cache")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("Redirección sin destino")
                c.disconnect()
                u = URL(u, loc)
            } else {
                if (code != 200) {
                    c.disconnect()
                    throw IllegalStateException("HTTP $code")
                }
                return c
            }
        }
        throw IllegalStateException("Demasiadas redirecciones")
    }

    /** cb(versión remota) o cb(-1) si no se pudo comprobar. */
    fun check(cb: (Long) -> Unit) {
        exec.execute {
            val v = try {
                val c = open(VERSION_URL + "?t=" + System.currentTimeMillis())
                val txt = c.inputStream.bufferedReader().use { it.readText() }.trim()
                c.disconnect()
                txt.toLongOrNull() ?: -1L
            } catch (t: Throwable) {
                -1L
            }
            main.post { cb(v) }
        }
    }

    /** Descarga e instala. progress(0..100); done(null) si todo bien o done(mensaje de error). */
    fun downloadAndInstall(ctx: Context, progress: (Int) -> Unit, done: (String?) -> Unit) {
        if (busy) return
        val app = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            // hay que permitir "Instalar apps desconocidas" una sola vez
            // con el nombre de la app; si la marca no lo admite, la lista general; si no, la seguridad
            val tries = listOf(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)),
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            )
            for (i in tries) {
                try {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    app.startActivity(i)
                    break
                } catch (t: Throwable) {
                }
            }
            done("Permite \"Instalar apps desconocidas\" para Nioro Launcher y vuelve a tocar Actualizar")
            return
        }
        busy = true
        exec.execute {
            var err: String? = null
            try {
                val f = apkFile(app)
                val c = open(APK_URL + "?t=" + System.currentTimeMillis())
                val total = c.contentLengthLong
                var read = 0L
                var lastPct = -1
                c.inputStream.use { ins ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    main.post { progress(pct) }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                if (total > 0 && read != total) throw IllegalStateException("Descarga incompleta")
                try {
                    install(app, f)
                } catch (t: Throwable) {
                    // algunas capas (MIUI) no dejan usar el instalador interno: se abre el del sistema
                    if (!installWithSystem(app)) throw t
                }
            } catch (t: Throwable) {
                err = "No se pudo actualizar: " + (t.message ?: t.javaClass.simpleName)
            }
            busy = false
            main.post { done(err) }
        }
    }

    fun apkFile(ctx: Context): File {
        val dir = File(ctx.applicationContext.cacheDir, "update")
        dir.mkdirs()
        return File(dir, "Nioro-Launcher.apk")
    }

    /**
     * Plan B: abre el instalador normal del sistema con el APK ya descargado.
     * Lo necesitan los móviles Xiaomi/Redmi/POCO (MIUI), que bloquean el instalador interno
     * con "INSTALL_FAILED_INTERNAL_ERROR: Permission Denied".
     */
    fun installWithSystem(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (!apkFile(app).exists()) return false
        return try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(ApkProvider.uri(app), ApkProvider.MIME)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.startActivity(i)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { ins ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    ins.copyTo(out, 64 * 1024)
                    s.fsync(out)
                }
            }
            val i = Intent(ctx, InstallReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val p = PendingIntent.getBroadcast(ctx, id, i, flags)
            s.commit(p.intentSender)
        }
    }
}

/** Recibe el resultado del instalador del sistema y muestra la confirmación al usuario. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(confirm)
                    } catch (t: Throwable) {
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(ctx, "Nioro Launcher actualizado", Toast.LENGTH_SHORT).show()
            }
            // cancelada por el usuario, firma distinta o sin espacio: el instalador del sistema tampoco ayudaría
            PackageInstaller.STATUS_FAILURE_ABORTED, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Toast.makeText(ctx, "La instalación no se completó $msg", Toast.LENGTH_LONG).show()
            }
            else -> {
                // p. ej. MIUI: "INSTALL_FAILED_INTERNAL_ERROR: Permission Denied" -> instalador del sistema
                if (Updater.installWithSystem(ctx)) {
                    Toast.makeText(ctx, "Abriendo el instalador del sistema…", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                    Toast.makeText(ctx, "La instalación no se completó $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/** Entrega el APK descargado al instalador del sistema (solo lectura, con permiso temporal). */
class ApkProvider : ContentProvider() {
    companion object {
        const val MIME = "application/vnd.android.package-archive"
        fun uri(ctx: Context): Uri = Uri.parse("content://" + ctx.packageName + ".apk/Nioro-Launcher.apk")
    }

    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = MIME

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        if (uri.lastPathSegment != "Nioro-Launcher.apk") return null
        return ParcelFileDescriptor.open(Updater.apkFile(ctx), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        val f = Updater.apkFile(ctx)
        val c = android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
