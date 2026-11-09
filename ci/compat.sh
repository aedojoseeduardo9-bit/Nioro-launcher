#!/usr/bin/env bash
# Prueba de compatibilidad: en un Android virtual (la versión y la pantalla las elige compat.yml)
# arranca el launcher, abre las 5 vistas de apps, prueba letra grande y otra densidad de pantalla
# y lo somete a una prueba de estrés. Cualquier cierre del launcher queda en el informe.
set +x
NAME=${1:-android}
P=com.nioro.launcher
mkdir -p compat
R=compat/$NAME.txt
# Android 13/14 dicen "arrancado" antes de tiempo: se espera a que el sistema responda de verdad
for i in $(seq 1 60); do adb shell pm path android > /dev/null 2>&1 && break; sleep 5; done
sleep 25
for i in 1 2 3 4; do
  adb wait-for-device
  timeout 120 adb install -r app/build/outputs/apk/debug/app-debug.apk > /dev/null 2>&1 && break
  sleep 20
done
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER > acts.txt 2>&1

prefs() {
  timeout 20 adb shell am force-stop $P
  python3 ci/mkprefs.py acts.txt "$1" prefs.xml > /dev/null
  timeout 30 adb push prefs.xml /data/local/tmp/nl.xml > /dev/null 2>&1
  timeout 30 adb shell "chmod 644 /data/local/tmp/nl.xml; run-as $P mkdir -p shared_prefs; run-as $P cp /data/local/tmp/nl.xml shared_prefs/nioro_launcher.xml"
}
start() { timeout 60 adb shell am start -W -n $P/.MainActivity > /dev/null 2>&1; sleep 6; }
back() { timeout 20 adb shell input keyevent 4; sleep 1; }
# tamaño real de la pantalla (cada perfil es distinto)
read W H < <(adb shell wm size | tail -n1 | sed 's/.*: //; s/x/ /' | tr -d '\r')
swipe_up() { timeout 20 adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 260; }

{
  echo "== $NAME · pantalla ${W}x${H} · Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
  adb logcat -c
  # las 5 vistas de apps, con búsqueda y teclado
  for st in 0 1 2 3 4; do
    prefs $st; start
    swipe_up; sleep 2
    timeout 20 adb shell input text a; sleep 1
    timeout 20 adb exec-out screencap -p > compat/${NAME}_vista$st.png
    back; back
  done
  # letra grande y otra densidad (como traen muchos móviles de fábrica)
  timeout 20 adb shell settings put system font_scale 1.3
  D=$(adb shell wm density | tail -n1 | sed 's/.*: //' | tr -dc 0-9)
  [ -n "$D" ] && timeout 20 adb shell wm density $(( D * 115 / 100 ))
  prefs 2; start; swipe_up; sleep 2
  timeout 20 adb exec-out screencap -p > compat/${NAME}_letra_grande.png
  back
  timeout 20 adb shell settings put system font_scale 1.0
  timeout 20 adb shell wm density reset
  # estrés: toques, deslizamientos y pellizcos al azar en el launcher y sus ajustes
  for st in 0 2 4; do
    prefs $st; start
    echo "-- estrés vista $st"
    timeout 200 adb shell monkey -p $P --throttle 30 --pct-syskeys 0 --pct-appswitch 0 --ignore-timeouts -s $((st+3)) -v 700 2>&1 | grep -E "CRASH|Events injected|aborted" | head -5
  done
  echo "== cierres del launcher"
  if adb logcat -d -s AndroidRuntime:E | grep -q "FATAL EXCEPTION"; then
    adb logcat -d -s AndroidRuntime:E | grep -A40 "FATAL EXCEPTION" | head -120
  else
    echo "ninguno"
  fi
  timeout 20 adb shell "run-as $P cat files/ultimo_error.txt" 2>/dev/null | head -60
} > "$R" 2>&1
cat "$R"
