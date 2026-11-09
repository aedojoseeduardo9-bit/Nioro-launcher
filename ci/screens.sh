#!/usr/bin/env bash
# Abre Nioro Launcher en un Android virtual (Pixel 5, 1080x2340), recorre las pantallas y guarda capturas.
set +x
P=com.nioro.launcher
mkdir -p shots
adb install -r app/build/outputs/apk/debug/app-debug.apk > shots/install.txt 2>&1
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER > acts.txt 2>&1
adb logcat -c

shot() { sleep "${2:-3}"; timeout 30 adb exec-out screencap -p > "shots/$1.png"; echo "captura $1"; }
prefs() {
  timeout 20 adb shell am force-stop $P
  python3 ci/mkprefs.py acts.txt "$1" prefs.xml $2
  timeout 30 adb push prefs.xml /data/local/tmp/nl.xml > /dev/null 2>&1
  timeout 30 adb shell "chmod 644 /data/local/tmp/nl.xml; run-as $P mkdir -p shared_prefs; run-as $P cp /data/local/tmp/nl.xml shared_prefs/nioro_launcher.xml"
  timeout 30 adb push prefs_s.xml /data/local/tmp/nls.xml > /dev/null 2>&1
  timeout 30 adb shell "chmod 644 /data/local/tmp/nls.xml; run-as $P cp /data/local/tmp/nls.xml shared_prefs/nioro_s.xml"
}
start() { timeout 60 adb shell am start -W -n $P/.MainActivity > /dev/null 2>&1; sleep 8; }
back() { timeout 20 adb shell input keyevent 4; sleep 2; }
swipe_up() { timeout 20 adb shell input swipe 540 1750 540 700 260; }
hold() { timeout 20 adb shell input swipe "$1" "$2" "$1" "$2" 1300; }

{
  # 1) inicio con iconos y dock
  prefs 0; start; shot 01_inicio
  # 2) modo edición: mantener pulsado un hueco del fondo
  hold 540 1750; shot 02_edicion; back; sleep 2
  # 3) menú del reloj: mantener pulsado el reloj
  hold 540 300; shot 03_reloj_menu 4; back; sleep 2
  # 4) las 5 vistas de apps (deslizar hacia arriba)
  swipe_up; shot 04_launchpad; back
  prefs 2; start; swipe_up; shot 05_rejilla
  timeout 20 adb shell input swipe 540 1500 540 1100 400; shot 05b_rejilla_scroll 1; back
  prefs 3; start; swipe_up; shot 06_lista; back
  prefs 1; start; swipe_up; shot 07_abanico; back
  prefs 4; start; swipe_up; shot 08_gravedad 5; back
  # teclado abierto: los resultados deben quedar por encima del teclado
  adb logcat -c
  prefs 2; start; swipe_up; shot 10a_antes 2; timeout 20 adb shell input tap 540 255; shot 10b_tras_tocar 1; timeout 20 adb shell input text c; shot 10_rejilla_teclado 3
  echo "== diagnóstico teclado"; adb shell dumpsys input_method | grep -E "mInputShown|mShowRequested|mCurFocusedWindow" | head -5
  adb logcat -d -s NioroDbg:W AndroidRuntime:E | tail -n 60
  back; back
  prefs 0; start; swipe_up; sleep 2; timeout 20 adb shell input tap 540 255; sleep 2; shot 11_launchpad_teclado 3; back; back
  # 6) vídeos de las animaciones: cada uno se convierte en una tira de fotogramas (10 por segundo)
  rec() {
    n=$1; shift
    L=${REC_S:-3}
    timeout 25 adb shell "screenrecord --time-limit $L --size 540x1170 --bit-rate 6000000 /sdcard/$n.mp4" &
    RP=$!; sleep 1; "$@"; wait $RP; sleep 1
    timeout 30 adb pull /sdcard/$n.mp4 shots/$n.mp4 > /dev/null 2>&1
    ffmpeg -loglevel error -y -i shots/$n.mp4 -vf "fps=${FPS:-10},scale=180:-1,tile=10x3" -frames:v 1 "shots/$n.png" && echo "video $n"
  }
  g_paginas() { timeout 20 adb shell input swipe 900 1300 150 1300 260; sleep 1.1; timeout 20 adb shell input swipe 150 1300 900 1300 260; }
  g_abrir_lento() { timeout 20 adb shell input swipe 540 1750 540 950 900; }
  g_cerrar_tirando() { timeout 20 adb shell input swipe 540 700 540 1650 420; }
  g_tocar_fuera() { timeout 20 adb shell input tap 540 2050; }
  g_lista_fling() { timeout 20 adb shell input swipe 540 1700 540 700 140; }
  g_gota() { timeout 20 adb shell input tap 540 255; }
  prefs 0 paginas; start; rec v1_paginas_inicio g_paginas
  rec v2_abrir_cajon_dedo g_abrir_lento
  rec v3_cerrar_tirando g_cerrar_tirando
  prefs 2; start; swipe_up; sleep 2; rec v4_rejilla_cerrar g_tocar_fuera
  prefs 3; start; swipe_up; sleep 2; rec v5_lista_fling g_lista_fling; back
  prefs 2; start; swipe_up; sleep 2; REC_S=7 FPS=5 rec v6_gota_busqueda g_gota; back; back

  # 7) cajón a mitad de gesto: el dedo se queda quieto a media subida
  prefs 0; start
  # (un deslizamiento lento de 3 s en segundo plano; la captura se hace con el dedo aún abajo)
  timeout 20 adb shell input swipe 540 1700 540 1000 3000 &
  SW=$!; sleep 1.8; timeout 20 adb exec-out screencap -p > shots/12_cajon_a_mitad.png; echo "captura 12_cajon_a_mitad"
  wait $SW; sleep 2; back
  # 10) estilos de organización del inicio y diseños del dock (ajustes del motor S)
  SVALS="home.layout=1" prefs 0; start; shot 20_org_panal 2
  SVALS="home.layout=2" prefs 0; start; shot 21_org_sonrisa 2
  SVALS="home.layout=5" prefs 0; start; shot 22_org_dividida 2
  SVALS="home.layout=4,dock.shape=5,dock.reflect=1" prefs 0; start; shot 23_pulgar_estante 2
  DOCKN=0 SVALS="dock.shape=5,dock.reflect=1" prefs 0; start; shot 23b_estante_sin_apps 2
  DOCKN=1 SVALS="dock.shape=5,dock.reflect=1" prefs 0; start; shot 23c_estante_una_app 2
  SVALS="dock.shape=7" prefs 0; start; shot 24_dock_burbujas 2
  SVALS="dock.shape=11,glass.0.tint=3,glass.0.tintop=90" prefs 0; start; shot 25_dock_ola 2
  SVALS="dock.shape=6,glass.0.tint=2,glass.0.tintop=170" prefs 0; start; shot 26_dock_pestana 2
  SVALS="dock.shape=9,glass.0.rimc=4" prefs 0; start; shot 27_dock_linea 2
  SVALS="dock.shape=12" prefs 0; start; shot 28_dock_cupula 2
  # vídeos: pasar el dedo por el dock (magnificación) con Burbujas, Ola y Estante
  g_scrub() { timeout 20 adb shell input swipe 160 2075 920 2075 1800; }
  SVALS="dock.shape=7" prefs 0; start; rec v9_dock_burbujas g_scrub
  SVALS="dock.shape=11,glass.0.tint=3,glass.0.tintop=90" prefs 0; start; rec v10_dock_ola g_scrub
  SVALS="dock.shape=5,dock.reflect=1" prefs 0; start; rec v11_dock_estante g_scrub
  # recientes en Burbujas: flotan, se arrastran con el dedo y estallan al mantenerlas
  g_burbujas() { timeout 20 adb shell input tap 930 2070; sleep 2.2; timeout 20 adb shell input swipe 666 1170 300 620 900; }
  g_estallar() { timeout 20 adb shell input tap 930 2070; sleep 2; timeout 20 adb shell input swipe 330 1382 330 1382 1100; }
  RECIENTES=4 prefs 0; start; REC_S=6 FPS=5 rec v12_burbujas_arrastrar g_burbujas; back
  RECIENTES=4 prefs 0; start; REC_S=8 FPS=4 rec v13_burbujas_estallar g_estallar; back
  # vistas de apps nuevas: Playlist (5), Panal (6) y Rueda (7)
  prefs 5; start; swipe_up; shot 40_playlist 3
  g_pl_mover() { timeout 10 adb shell input motionevent DOWN 540 1047; sleep 1; for yy in 1110 1180 1260 1340 1420 1500; do timeout 10 adb shell input motionevent MOVE 540 $yy; done; timeout 10 adb shell input motionevent UP 540 1500; }
  REC_S=6 FPS=5 rec v14_playlist_mover g_pl_mover; back
  prefs 5; start; swipe_up; sleep 2
  g_pl_deslizar() { timeout 20 adb shell input swipe 200 1223 800 1223 900; }
  REC_S=4 rec v15_playlist_deslizar g_pl_deslizar; back
  prefs 6; start; swipe_up; shot 41_panal 3
  g_panal() { timeout 20 adb shell input swipe 700 1500 300 900 220; }
  REC_S=4 rec v16_panal g_panal; back
  prefs 7; start; swipe_up; shot 42_rueda 3
  g_rueda() { timeout 20 adb shell input swipe 950 1720 150 1720 260; }
  REC_S=4 rec v17_rueda g_rueda; back
  # Ajustes: portada y galería de diseños del dock
  prefs 0; timeout 60 adb shell am start -W -n $P/.SettingsActivity > /dev/null 2>&1; sleep 5; shot 30_ajustes 1
  timeout 20 adb shell input swipe 540 1800 540 600 400; sleep 2; shot 31_ajustes_abajo 1
  timeout 60 adb shell am start -S -W -n $P/.SettingsActivity --ei page 1 > /dev/null 2>&1; sleep 6; shot 32_ajustes_dock 1
  timeout 20 adb shell input swipe 540 1800 540 500 500; sleep 2; shot 33_galeria_dock 1
  timeout 20 adb shell input swipe 540 1800 540 500 500; sleep 2; shot 34_galeria_dock2 1
  timeout 60 adb shell am start -S -W -n $P/.SettingsActivity --ei page 0 > /dev/null 2>&1; sleep 6
  timeout 20 adb shell input swipe 540 1700 540 900 400; sleep 2; shot 35_ajustes_inicio 1
  back; back
  # 5) menú contextual de un icono del inicio (mantener pulsado el primero)
  prefs 0; start; hold 135 700; shot 09_menu_icono; back
  # 8) botón Launchpad del dock: vídeo de la apertura y fluidez medida por el sistema
  prefs 0; start
  g_boton_lp() { timeout 20 adb shell input tap 136 2070; }
  rec v7_boton_launchpad g_boton_lp; back
  prefs 2; start; rec v8_rejilla_abrir g_boton_lp; back
  FORMA=4 prefs 2; start; swipe_up; shot 13_iconos_cristal 3; back
  timeout 20 adb shell dumpsys gfxinfo $P reset > /dev/null
  for k in 1 2 3 4 5; do timeout 20 adb shell input tap 136 2070; sleep 1.5; back; sleep 1; done
  echo "== fluidez al abrir con el botón (5 veces)"
  timeout 20 adb shell dumpsys gfxinfo $P | grep -E "Total frames|Janky|percentile|Slow|Missed|High input|Frame deadline" | head -20
  # 9) prueba de estrés: miles de toques al azar en el launcher y sus ajustes, para cazar cierres y bloqueos
  timeout 30 adb root > /dev/null 2>&1; sleep 3
  for st in 2; do
    prefs $st; start
    timeout 20 adb shell "rm -f /data/anr/*" > /dev/null 2>&1
    adb logcat -c
    echo "== estrés (vista $st)"
    timeout 420 adb shell monkey -p $P --throttle 40 --pct-syskeys 0 --pct-appswitch 0 --ignore-timeouts --ignore-crashes -s $((st+7)) -v 3000 2>&1 | grep -E "CRASH|ANR in|Events injected|Monkey finished|aborted" | head -10
    echo "-- cierres de Nioro"
    adb logcat -d -s AndroidRuntime:E | grep -A40 "FATAL EXCEPTION" | head -80
    echo "-- bloqueos de Nioro (hilo principal)"
    for f in $(timeout 20 adb shell "ls /data/anr/" 2>/dev/null | tr -d '\r'); do
      timeout 20 adb shell "cat /data/anr/$f" 2>/dev/null | grep -q "com.nioro.launcher" || continue
      echo "[$f]"
      timeout 20 adb shell "cat /data/anr/$f" | awk '/^"main"/{p=1} p{print; n++} n>45{exit}'
    done
    timeout 20 adb shell "run-as $P cat files/ultimo_error.txt" 2>/dev/null | head -40
  done
  echo "== errores graves"
  adb logcat -d -b crash | tail -n 80
} > shots/report.txt 2>&1
cat shots/report.txt
