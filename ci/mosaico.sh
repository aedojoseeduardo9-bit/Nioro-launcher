#!/usr/bin/env bash
# Capturas del modo Mosaico (estilo Windows Phone): inicio, edición, lista de apps, letras, búsqueda y menú.
# Se carga desde screens.sh (usa sus funciones prefs, start, shot, back y hold). Pantalla 1080x2340.
M="home.screen=2"
mrec() {
  n=$1; shift
  L=${REC_S:-3}
  timeout 25 adb shell "screenrecord --time-limit $L --size 540x1170 --bit-rate 6000000 /sdcard/$n.mp4" &
  RP=$!; sleep 1; "$@"; wait $RP; sleep 1
  timeout 30 adb pull /sdcard/$n.mp4 shots/$n.mp4 > /dev/null 2>&1
  ffmpeg -loglevel error -y -i shots/$n.mp4 -vf "fps=${FPS:-10},scale=180:-1,tile=10x3" -frames:v 1 "shots/$n.png" && echo "video $n"
}
echo "== Mosaico"
SVALS="$M" prefs 0; start; shot m01_inicio 4
timeout 20 adb shell input swipe 540 1800 540 600 300; shot m02_inicio_abajo 2
timeout 20 adb shell input swipe 540 600 540 1800 300; sleep 1
hold 280 420; shot m03_edicion 2; back; sleep 1
timeout 20 adb shell input swipe 980 1300 120 1300 250; shot m04_lista 3
timeout 20 adb shell input tap 124 352; shot m05_letras 2
timeout 20 adb shell input tap 914 685; shot m06_tras_letra 2
timeout 20 adb shell input swipe 540 700 540 2000 200; sleep 1
timeout 20 adb shell input swipe 540 700 540 2000 200; sleep 1
timeout 20 adb shell input tap 108 190; shot m07_busqueda 2
timeout 20 adb shell input text "c"; shot m08_busqueda_c 2
back; back; sleep 1
hold 500 520; shot m09_menu 2; back; sleep 1
back; shot m10_vuelta_inicio 2
SVALS="$M,mz.bg=1" prefs 0; start; shot m11_claro 3
SVALS="$M,mz.transp=1" prefs 0; start; shot m12_transparente 3
SVALS="$M,mz.cols=0,mz.accent=20" prefs 0; start; shot m13_4col_color_app 3
SVALS="$M" prefs 0; start
g_abrir() { timeout 20 adb shell input tap 280 420; }
REC_S=3 FPS=10 mrec mv1_torniquete g_abrir; back; sleep 2
g_lista() { timeout 20 adb shell input swipe 980 1300 120 1300 400; }
REC_S=3 mrec mv2_a_la_lista g_lista
echo "-- cierres de Nioro (Mosaico)"
adb logcat -d -s AndroidRuntime:E | grep -A30 "FATAL EXCEPTION" | head -60
