# Nioro Launcher — guía para Claude

Launcher para Android nativo (Kotlin, sin Compose ni HTML): todo se dibuja a mano en `Canvas`.
El dueño habla **español**: responde siempre en español, claro y sin tecnicismos innecesarios.

## Compilar y probar
- En el contenedor **no se puede compilar**: `dl.google.com` (Google Maven / SDK) está bloqueado.
- Push a cualquier rama que no sea `main` → workflow **"Verificar rama"** (`check.yml`): `assembleDebug`,
  sin publicar nada. Revisa el resultado con las herramientas `mcp__github__actions_*`
  (y `get_job_logs` si falla). Espera en segundo plano, no con `sleep` en primer plano.
- No hay pruebas unitarias ni capturas automáticas todavía: revisa a mano la lógica que cambies.
- Si el usuario manda capturas de sus teléfonos (Samsung S21, Redmi Note 9 Pro con MIUI y Honor X9a con MagicOS) en el chat, úsalas para comprobar.
- Cierres: `CrashGuard.kt` guarda el último error (Ajustes → Acerca de → "Enviar el último error") y activa un modo seguro tras 2 cierres seguidos. Pide ese texto al usuario si hay problemas de estabilidad.
- Ideas y repositorios de referencia para diseñar: `docs/referencias-diseno.md`.

## Publicar una actualización
El usuario quiere que **las actualizaciones le lleguen solas**. El flujo:
1. Trabajar en la rama asignada → push → esperar "Verificar rama" en verde.
2. Crear PR a `main` y fusionarlo (`mcp__github__create_pull_request` + `merge_pull_request`). **El usuario quiere
   que siempre se cree el PR y se fusione** en cuanto "Verificar rama" esté en verde, sin preguntar.
3. `build.yml` compila y publica el release `latest` con `Nioro-Launcher.apk` y `version.txt`
   (= número de ejecución de Actions = `versionCode`). `Updater.kt` compara ese número y lo instala.
4. Si la rama ya se fusionó antes, reiníciala desde `origin/main` antes de seguir.
5. La firma usa `app/nioro-launcher.keystore` (incluida en el repo). **No la cambies**: con otra clave
   las actualizaciones ya no se instalarían encima de la versión del usuario.

## Mapa del código (`app/src/main/java/com/nioro/launcher/`)
- `MainActivity.kt` — capas (inicio, fondo, vista de apps, recientes, animación de abrir, menú) y acciones de gestos.
- `Reg.kt` — objeto `S`: **los 1001 ajustes** declarados una vez (id = orden de declaración; no reordenes
  ni insertes en medio, añade al final de su bloque o cambiarán los valores guardados). `Mo` aplica la física.
- `Menus.kt` — reparte los ajustes de `S` en los menús de `SettingsActivity`.
- `Prefs.kt` — ajustes básicos (estilos 0..4, tamaños, listas de inicio/dock/ocultas/recientes).
- `Spring.kt` — `SpringValue` (resorte exacto), `Ticker` (bucle solo mientras algo se mueve), `Scroll1D`.
- `Motion.kt` — matemáticas de los 5 estilos (abrir, volver, página, dock). Las usa también `PreviewView`.
- `Glass.kt` — pintor de cristal, desenfoque, sombra de iconos. `Fx.kt` — textos (`Tx`), iconos (`IconFx`), vibración (`Hx`).
- `HomeView.kt` — inicio: páginas, dock con magnificación, reloj, modo edición, arrastrar, gestos.
- `ClockFaces.kt` — diseños de reloj 8-20 (los 0-7 están en `HomeView`). Mantener pulsado el reloj abre su
  menú dock (`drawSheet`): pestañas Diseños (miniaturas en vivo), Colores, Formato y Posición.
- `AppsView.kt` + `LaunchpadView`, `FanView`, `GridPopupView`, `ListAppsView`, `GravityView` — las 5 vistas de apps; `DrawerHost.kt` las aloja.
- `RecentsView.kt`, `Overlays.kt` (menú, animación de abrir, fondo difuminado), `AppRepo.kt` (apps, iconos, recientes).
- `SettingsActivity.kt`, `Ui.kt`, `PreviewView.kt` — pantalla de Ajustes. `Updater.kt` — actualizador. `Compat.kt` — Android 10–14.

## Reglas de la casa
- **Siempre** al terminar un cambio: revisión de errores y entrega al usuario la **lista ordenada de más a
  menos importante**, arreglarlos en ese orden, y luego subir (PR + fusionar).
- Temas de color: `Themes.kt` (`ColorThemes`); tiñen cristal, acento, paneles, fondo de Ajustes, puntos y reloj.
- Toda vibración pasa por `Hx.fire(view, S.HE_*)` (o `Hx.gesture`): nunca `performHapticFeedback` directo,
  así respeta los ajustes de Vibración.
- Un panel cerrado o casi invisible no debe recibir toques (ver `dispatchTouchEvent` en `DrawerHost.kt`):
  si no, aparecen "apps fantasma" al tocar el inicio.
- Cero trabajo en reposo: anima con `Ticker`/`SpringValue` y deja que se detenga solo.
- Nada de asignaciones en `onDraw` si se puede evitar (reutiliza `RectF`, `Paint`, shaders).
