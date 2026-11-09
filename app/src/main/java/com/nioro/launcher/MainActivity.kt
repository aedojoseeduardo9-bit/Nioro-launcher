package com.nioro.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var home: HomeView
    private lateinit var drawer: DrawerHost
    private lateinit var recents: RecentsHost
    private lateinit var overlay: OpenOverlay
    private lateinit var menu: MenuView
    private lateinit var backdrop: BackdropView
    private val bg = Executors.newSingleThreadExecutor()
    private var launchedApp = false
    private var lastBlur = -1f
    private var drawerP = 0f
    private var recentsP = 0f
    private var resumed = false
    private var wasStopped = false
    private val overlayGuard = Runnable { if (overlay.active) overlay.hide() }
    /** si al final no se abrió la vista de apps, la foto preparada se descarta (podría quedar vieja) */
    private val dropWarm = Runnable { if (drawerP == 0f && recentsP == 0f) backdrop.release() }
    private val calmDown = Runnable { Compat.applyRefreshRate(this, false) }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            i.data?.schemeSpecificPart?.let { AppRepo.evict(it) }
            reloadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Compat.edgeToEdge(this)
        Compat.applyRefreshRate(this)

        root = FrameLayout(this)
        home = HomeView(this)
        drawer = DrawerHost(this)
        recents = RecentsHost(this)
        overlay = OpenOverlay(this)
        menu = MenuView(this)
        backdrop = BackdropView(this)
        val mp = FrameLayout.LayoutParams.MATCH_PARENT
        root.addView(home, FrameLayout.LayoutParams(mp, mp))
        root.addView(backdrop, FrameLayout.LayoutParams(mp, mp))
        root.addView(drawer, FrameLayout.LayoutParams(mp, mp))
        root.addView(recents, FrameLayout.LayoutParams(mp, mp))
        root.addView(overlay, FrameLayout.LayoutParams(mp, mp))
        root.addView(menu, FrameLayout.LayoutParams(mp, mp))
        setContentView(root)
        Screen.onAnimating = { on ->
            if (on) {
                root.removeCallbacks(calmDown)
                Compat.applyRefreshRate(this, true)
            } else {
                // se espera un poco: así gestos seguidos no cambian de frecuencia una y otra vez
                root.removeCallbacks(calmDown)
                root.postDelayed(calmDown, S.f(S.P_IDLE).toLong())
            }
        }

        root.setOnApplyWindowInsetsListener { _, ins ->
            val (top, bottom) = Compat.bars(ins)
            home.setInsets(top.toFloat(), bottom.toFloat())
            drawer.setInsets(top.toFloat(), bottom.toFloat())
            recents.view.insetTop = top.toFloat()
            recents.view.insetBottom = bottom.toFloat()
            drawer.setIme(Compat.ime(ins).toFloat())
            ins
        }
        // Android 11+: la vista de apps sube a la vez que el teclado, fotograma a fotograma
        if (Build.VERSION.SDK_INT >= 30) {
            root.setWindowInsetsAnimationCallback(object : android.view.WindowInsetsAnimation.Callback(android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(insets: WindowInsets, running: MutableList<android.view.WindowInsetsAnimation>): WindowInsets {
                    drawer.setIme(Compat.ime(insets).toFloat())
                    return insets
                }
            })
        }

        home.host = homeHost
        drawer.callback = drawerCb
        recents.view.listener = recentsCb
        recents.onProgress = { p ->
            recentsP = p
            updateBackdrop()
        }

        val f = IntentFilter()
        f.addAction(Intent.ACTION_PACKAGE_ADDED)
        f.addAction(Intent.ACTION_PACKAGE_REMOVED)
        f.addAction(Intent.ACTION_PACKAGE_CHANGED)
        f.addAction(Intent.ACTION_PACKAGE_REPLACED)
        f.addDataScheme("package")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(pkgReceiver, f)

        reloadApps()
        if (CrashGuard.safeMode) {
            Toast.makeText(this, "Nioro se recuperó de un error: modo seguro (sin desenfoque) por un momento", Toast.LENGTH_LONG).show()
        }
        // tras un minuto funcionando bien se olvidan los cierres anteriores
        root.postDelayed({ CrashGuard.markStable() }, 60_000L)
        try {
            (getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager).registerTorchCallback(torchCb, null)
            torchReg = true
        } catch (t: Throwable) {
        }
    }

    private fun reloadApps() {
        AppRepo.load(this) {
            if (isDestroyed) return@load
            if (!Prefs.seeded && AppRepo.apps.isNotEmpty()) {
                val dock = AppRepo.defaultDockKeys(this)
                Prefs.setDockKeys(dock)
                Prefs.setHomeKeys(AppRepo.seedHome(this).filter { it !in dock })
                Prefs.seeded = true
            }
            // apps recién instaladas: se añaden al final del inicio (como One UI)
            if (AppRepo.apps.isNotEmpty()) {
                val known = Prefs.knownKeys()
                if (known.isEmpty()) {
                    Prefs.setKnownKeys(AppRepo.apps.map { it.key })
                } else {
                    val fresh = AppRepo.apps.filter { it.key !in known }
                    if (fresh.isNotEmpty()) {
                        if (S.b(S.H_AUTO_ADD)) {
                            val h = Prefs.homeKeys()
                            val d = Prefs.dockKeys()
                            for (e in fresh) if (e.key !in h && e.key !in d) HomeSlots.add(h, e.key)
                            Prefs.setHomeKeys(h)
                        }
                    }
                    Prefs.setKnownKeys(AppRepo.apps.map { it.key })
                }
            }
            // limpiar apps desinstaladas (solo si la lista cargó bien, para no borrar el inicio por error)
            if (AppRepo.apps.isNotEmpty()) {
                // una app desinstalada deja su hueco (los demás iconos no se mueven)
                Prefs.setHomeKeys(Prefs.homeKeys().map { if (it == HomeSlots.BLANK || AppRepo.get(it) != null) it else HomeSlots.BLANK })
                Prefs.setDockKeys(Prefs.dockKeys().filter { AppRepo.get(it) != null })
            }
            seenIcons = AppRepo.version
            home.reload()
            drawer.refreshData()
            refreshRunning()
        }
    }

    private var seenIcons = -1

    private fun refreshRunning() {
        val ctx = applicationContext
        bg.execute {
            val set = try { Recents.recentPackages(ctx) } catch (t: Throwable) { emptySet() }
            runOnUiThread { if (!isDestroyed) home.setRunning(set) }
        }
    }

    // ---------------------------------------------------------------- Ciclo de vida
    override fun onResume() {
        super.onResume()
        resumed = true
        overlay.hide()
        Compat.applyRefreshRate(this)
        home.resumeClock()
        drawer.sanitize()
        recents.sanitize()
        drawer.styleChanged()
        if (wasStopped) {
            // Al volver (de una app, de Ajustes o al desbloquear) se releen los ajustes.
            if (!Prefs.seeded || AppRepo.bakedShape != Prefs.iconShape || AppRepo.bakedRes != S.i(S.P_ICON_RES) || AppRepo.version != seenIcons) reloadApps() else {
                home.reload()
                drawer.refreshData()
            }
            home.refreshStyle()
        }
        if (launchedApp) {
            if (drawer.isOpen) drawer.closeNow()
            if (recents.isOpen) recents.closeNow()
        }
        if (launchedApp || wasStopped) home.playEnter()
        if (S.b(S.DT_BATTERY)) {
            try {
                val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val lv = bi?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val sc = bi?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                if (lv >= 0 && sc > 0) home.setBattery(lv * 100 / sc)
            } catch (t: Throwable) {
            }
        }
        launchedApp = false
        wasStopped = false
        refreshRunning()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        wasStopped = true
        home.pauseClock()
        menu.dismiss()
        overlay.hide()
        home.exitEdit(true)
        home.exitClockEdit(true)
        // nada de animaciones ni sensores mientras el launcher no se ve
        if (drawer.isOpen) drawer.closeNow()
        if (recents.isOpen) recents.closeNow()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Botón/gesto de inicio estando ya en el launcher.
        if (intent?.action == Intent.ACTION_MAIN && resumed) {
            when {
                menu.showing -> menu.dismiss()
                home.isClockEditing -> home.exitClockEdit()
                home.isEditing -> home.exitEdit()
                recents.isOpen -> recents.close()
                drawer.isOpen -> drawer.close()
                else -> runGesture(S.G_HOME)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        when {
            menu.showing -> menu.dismiss()
            home.isClockEditing -> home.exitClockEdit()
            home.isEditing -> home.exitEdit()
            recents.isOpen -> recents.close()
            drawer.isOpen -> drawer.close()
            else -> home.goToFirstPage()
        }
    }

    // ---------------------------------------------------------------- Fondo (desenfoque)
    private fun updateBackdrop() {
        val p = maxOf(drawerP, recentsP)
        home.setDrawerProgress(p)
        val blurOn = Prefs.blur && !Prefs.saver && !CrashGuard.safeMode
        if (p > 0.001f) {
            // vistas a pantalla completa: el inicio se desvanece casi del todo para que sus iconos
            // no se mezclen con los del cajón mientras sube con el dedo (rejilla y abanico lo dejan ver)
            val see = recentsP > drawerP || Prefs.drawerStyle == 1 || Prefs.drawerStyle == 2
            val keep = if (see) 0.35f else 0.85f
            // a pantalla completa y con el desenfoque del sistema (Android 12+) sobra la foto difuminada
            // del inicio: hacerla dibuja todo el inicio en la CPU en el primer fotograma (tirón al abrir)
            if (blurOn && !backdrop.tried && (see || !systemBlur())) backdrop.capture(home)
            backdrop.progress = p
            home.alpha = if (backdrop.hasSnapshot) 1f - p else 1f - keep * p
        } else {
            backdrop.release()
            home.alpha = 1f
        }
        wallpaperBlur(if (blurOn) p else 0f)
    }

    private fun systemBlur(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        try { windowManager.isCrossWindowBlurEnabled } catch (t: Throwable) { false }

    /** Android 12+: el sistema difumina el fondo de pantalla detrás del launcher (lo hace la GPU del compositor). */
    private fun wallpaperBlur(p: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            if (!windowManager.isCrossWindowBlurEnabled) return
            // pasos de 6 px: cambiar el desenfoque de la ventana cuesta, así hay menos cambios y va más fluido
            val r = (p * dp(34f)).toInt() / 6 * 6
            if (r != lastBlur.toInt()) {
                lastBlur = r.toFloat()
                window.setBackgroundBlurRadius(r)
            }
        } catch (t: Throwable) {
        }
    }

    // ---------------------------------------------------------------- Abrir apps
    private fun launch(e: AppEntry, from: RectF) {
        menu.dismiss()
        Hx.fire(root, S.HE_OPEN)
        val style = Prefs.openAnim
        val src = RectF(from)
        if (src.width() < 2f) src.set(root.width / 2f - 40f, root.height / 2f - 40f, root.width / 2f + 40f, root.height / 2f + 40f)
        overlay.start(e, src, style) { doStart(e) }
    }

    private fun doStart(e: AppEntry) {
        try {
            val opts = ActivityOptions.makeCustomAnimation(this, R.anim.zoom_in_soft, R.anim.stay)
            startActivity(AppRepo.intentFor(e), opts.toBundle())
            Recents.note(e.key)
            launchedApp = true
            // por si la app no llega a cubrir el launcher (p. ej. una ventana flotante)
            root.removeCallbacks(overlayGuard)
            root.postDelayed(overlayGuard, 2500)
        } catch (t: Throwable) {
            overlay.hide()
            Toast.makeText(this, "No se pudo abrir ${e.label}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- Menús
    private fun appMenu(e: AppEntry, anchor: RectF, where: Int) {
        val items = ArrayList<MenuView.Item>()
        val homeKeys = Prefs.homeKeys()
        val dockKeys = Prefs.dockKeys()
        if (e.key in homeKeys) {
            items.add(MenuView.Item("Quitar del inicio", Glyphs.MINUS) {
                val l = Prefs.homeKeys()
                HomeSlots.remove(l, e.key)
                Prefs.setHomeKeys(l)
                home.reload()
            })
        } else if (e.key !in dockKeys) {
            items.add(MenuView.Item("Añadir al inicio", Glyphs.PLUS) {
                val l = Prefs.homeKeys()
                HomeSlots.add(l, e.key)
                Prefs.setHomeKeys(l)
                home.reload()
                Toast.makeText(this, "Añadida al inicio", Toast.LENGTH_SHORT).show()
            })
        }
        if (e.key in dockKeys) {
            items.add(MenuView.Item("Quitar del dock", Glyphs.MINUS) {
                Prefs.setDockKeys(Prefs.dockKeys().filter { it != e.key })
                home.reload()
            })
        } else if (dockKeys.size < S.i(S.K_MAX)) {
            items.add(MenuView.Item("Añadir al dock", Glyphs.DOCK) {
                val l = Prefs.dockKeys()
                l.add(e.key)
                Prefs.setDockKeys(l)
                val hk = Prefs.homeKeys()
                HomeSlots.remove(hk, e.key)
                Prefs.setHomeKeys(hk)
                home.reload()
            })
        }
        if (S.b(S.M_INFO)) items.add(MenuView.Item("Información de la app", Glyphs.INFO) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg)))
            } catch (t: Throwable) {
            }
        })
        if (S.b(S.M_HIDE)) items.add(MenuView.Item("Ocultar", Glyphs.HIDE) {
            val h = Prefs.hidden()
            if (e.key !in h) h.add(e.key)
            Prefs.setHidden(h)
            val hk = Prefs.homeKeys()
            HomeSlots.remove(hk, e.key)
            Prefs.setHomeKeys(hk)
            Prefs.setDockKeys(Prefs.dockKeys().filter { it != e.key })
            home.reload()
            drawer.refreshData()
            Toast.makeText(this, "Oculta. Recupérala en Ajustes del launcher.", Toast.LENGTH_SHORT).show()
        })
        if (S.b(S.M_UNINSTALL)) items.add(MenuView.Item("Desinstalar", Glyphs.TRASH) {
            try {
                @Suppress("DEPRECATION")
                startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + e.pkg)))
            } catch (t: Throwable) {
            }
        })
        menu.show(e.label, anchor, items, e.icon)
    }

    // ---------------------------------------------------------------- Gestos
    private var torchOn = false
    private val torchCb = object : android.hardware.camera2.CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            torchOn = enabled
        }
    }
    private var torchReg = false

    /** ejecuta la acción configurada para el gesto [g] */
    private fun runGesture(g: Int) {
        val a = S.gestAction(g)
        if (a == S.A_NONE) return
        Hx.gesture(root, g)
        when (a) {
            S.A_DRAWER -> { menu.dismiss(); drawer.open(home.launchpadX()) }
            S.A_NOTIF -> homeHost.expandShade()
            S.A_QS -> try {
                @SuppressLint("WrongConstant")
                val sbm = getSystemService("statusbar")
                Class.forName("android.app.StatusBarManager").getMethod("expandSettingsPanel").invoke(sbm)
            } catch (t: Throwable) {
                homeHost.expandShade()
            }
            S.A_RECENTS -> homeHost.openRecents()
            S.A_EDIT -> home.enterEdit()
            S.A_SEARCH -> {
                drawer.open(home.launchpadX())
                drawer.showKeyboard()
            }
            S.A_SETTINGS -> drawerCb.openSettings()
            S.A_WALLPAPER -> try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Fondo de pantalla"))
            } catch (t: Throwable) {
            }
            S.A_FIRST -> home.goToFirstPage()
            S.A_TORCH -> try {
                val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                if (!torchReg) {
                    cm.registerTorchCallback(torchCb, null)
                    torchReg = true
                }
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (id != null) cm.setTorchMode(id, !torchOn)
            } catch (t: Throwable) {
                Toast.makeText(this, "No se pudo usar la linterna", Toast.LENGTH_SHORT).show()
            }
            S.A_CAMERA -> try {
                startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (t: Throwable) {
            }
            S.A_SYS -> try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (t: Throwable) {
            }
            S.A_SAVER -> {
                Prefs.saver = !Prefs.saver
                Compat.applyRefreshRate(this)
                home.invalidate()
                Toast.makeText(this, if (Prefs.saver) "Modo ahorro activado" else "Modo ahorro desactivado", Toast.LENGTH_SHORT).show()
            }
            S.A_NEXT_VIEW -> {
                Prefs.drawerStyle = (Prefs.drawerStyle + 1) % 5
                drawer.styleChanged()
                Toast.makeText(this, "Vista: " + SettingsActivity.DRAWER[Prefs.drawerStyle], Toast.LENGTH_SHORT).show()
            }
            S.A_NEXT_SHAPE -> {
                Prefs.iconShape = (Prefs.iconShape + 1) % 5
                Toast.makeText(this, "Iconos: " + SettingsActivity.SHAPES[Prefs.iconShape], Toast.LENGTH_SHORT).show()
                reloadApps()
            }
            S.A_HOME_MENU -> homeMenu(home.touchX, home.touchY)
        }
    }

    override fun onDestroy() {
        if (torchReg) try {
            (getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager).unregisterTorchCallback(torchCb)
        } catch (t: Throwable) {
        }
        try { unregisterReceiver(pkgReceiver) } catch (t: Throwable) {}
        super.onDestroy()
    }

    private fun homeMenu(x: Float, y: Float) {
        val items = ArrayList<MenuView.Item>()
        items.add(MenuView.Item("Ajustes del launcher", Glyphs.GEAR) {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(R.anim.slide_up_in, R.anim.stay)
        })
        items.add(MenuView.Item("Fondo de pantalla", Glyphs.WALLPAPER) {
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Fondo de pantalla"))
            } catch (t: Throwable) {
            }
        })
        items.add(MenuView.Item("Vista de apps: siguiente estilo", Glyphs.GRID) {
            Prefs.drawerStyle = (Prefs.drawerStyle + 1) % 5
            drawer.styleChanged()
            Toast.makeText(this, "Vista: " + SettingsActivity.DRAWER[Prefs.drawerStyle], Toast.LENGTH_SHORT).show()
        })
        val a = RectF(x - 10f, y - 10f, x + 10f, y + 10f)
        menu.show("Inicio", a, items)
    }

    // ---------------------------------------------------------------- Puentes
    private val homeHost = object : HomeView.Host {
        override fun launch(e: AppEntry, from: RectF, fromDock: Boolean) = this@MainActivity.launch(e, from)
        override fun openDrawer() {
            menu.dismiss()
            drawer.open(home.launchpadX())
        }
        override fun prewarmDrawer() {
            // Rejilla y Abanico dejan ver el inicio difuminado: la foto se hace ya, al apoyar el dedo,
            // y no en el primer fotograma de la apertura (ahí provocaba un tirón)
            val st = Prefs.drawerStyle
            if ((st == 1 || st == 2) && Prefs.blur && !Prefs.saver && !CrashGuard.safeMode && !backdrop.tried && drawerP == 0f && recentsP == 0f) {
                backdrop.capture(home)
                root.removeCallbacks(dropWarm)
                root.postDelayed(dropWarm, 900L)
            }
        }
        override fun drawerDrag(stage: Int, p: Float, velocity: Float) {
            when (stage) {
                0 -> {
                    menu.dismiss()
                    drawer.beginDrag(home.launchpadX())
                }
                1 -> drawer.dragTo(p)
                else -> drawer.endDrag(p >= 0.5f, velocity)
            }
        }
        override fun openRecents() {
            menu.dismiss()
            val ctx = applicationContext
            bg.execute {
                val list = try { Recents.list(ctx) } catch (t: Throwable) { emptyList() }
                runOnUiThread {
                    if (!isDestroyed) {
                        recents.open(list)
                        if (list.isEmpty() && !Recents.hasUsageAccess(ctx)) {
                            Toast.makeText(ctx, "Activa \"Acceso de uso\" en Ajustes del launcher para ver todas tus apps recientes", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        override fun showMenu(e: AppEntry, anchor: RectF, where: Int) = appMenu(e, anchor, where)
        override fun dismissMenu() = menu.dismiss()
        override fun expandShade() {
            try {
                @SuppressLint("WrongConstant")
                val sbm = getSystemService("statusbar")
                val c = Class.forName("android.app.StatusBarManager")
                c.getMethod("expandNotificationsPanel").invoke(sbm)
            } catch (t: Throwable) {
            }
        }
        override fun showHomeMenu(x: Float, y: Float) = homeMenu(x, y)
        override fun gesture(g: Int) = runGesture(g)
        override fun special(i: Int) {
            try {
                if (i == 0) {
                    startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    val u = Uri.parse("content://com.android.calendar/time/" + System.currentTimeMillis())
                    startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (t: Throwable) {
                Toast.makeText(this@MainActivity, "No hay una app para eso", Toast.LENGTH_SHORT).show()
            }
        }
        override fun editAction(i: Int) {
            when (i) {
                0 -> try {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Fondo de pantalla"))
                } catch (t: Throwable) {
                }
                else -> {
                    val it = Intent(this@MainActivity, SettingsActivity::class.java)
                    it.putExtra("page", when (i) { 1 -> SettingsActivity.PAGE_APPS; 2 -> SettingsActivity.PAGE_MOTION; else -> -1 })
                    startActivity(it)
                    @Suppress("DEPRECATION")
                    overridePendingTransition(R.anim.slide_up_in, R.anim.stay)
                }
            }
        }
    }

    private val drawerCb = object : DrawerHost.Callback {
        override fun onProgress(p: Float) {
            drawerP = p
            updateBackdrop()
        }
        override fun onLaunch(e: AppEntry, from: RectF) = launch(e, from)
        override fun onMenu(e: AppEntry, anchor: RectF) = appMenu(e, anchor, 2)
        override fun onClosed() {
            drawerP = 0f
            updateBackdrop()
        }
        override fun dismissMenu() = menu.dismiss()
        override fun openSettings() {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_up_in, R.anim.stay)
        }
    }

    private val recentsCb = object : RecentsView.Listener {
        override fun onLaunch(e: AppEntry, from: RectF) = launch(e, from)
        override fun onClose() = recents.close()
        override fun onOpenAll() {
            recents.close()
            drawer.open(home.launchpadX())
        }
    }
}
