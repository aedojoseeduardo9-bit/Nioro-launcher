#!/usr/bin/env python3
"""Crea los ajustes del launcher para las capturas: apps en el inicio y el dock y el estilo de vista de apps.
Uso: mkprefs.py acts.txt ESTILO salida.xml"""
import sys
import os
from xml.sax.saxutils import escape

acts, style, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
# 4.º argumento "paginas": inicio con dos pantallas (6 apps, huecos y otras 6) para probar el paso de página
pages = len(sys.argv) > 4 and sys.argv[4] == "paginas"
keys = []
for line in open(acts, encoding="utf-8", errors="ignore"):
    line = line.strip()
    if "/" not in line or " " in line:
        continue
    pkg, cls = line.split("/", 1)
    if cls.startswith("."):
        cls = pkg + cls
    k = pkg + "/" + cls
    if pkg != "com.nioro.launcher" and k not in keys:
        keys.append(k)
dock = keys[:int(os.environ.get("DOCKN", "4"))]
home = keys[4:16]
if pages:
    home = keys[4:10] + ["."] * 18 + keys[10:16]
nl = "&#10;"
xml = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>",
       '    <boolean name="seeded" value="true" />',
       '    <int name="drawerStyle" value="%d" />' % style,
       '    <int name="iconShape" value="%d" />' % int(os.environ.get('FORMA', '0')),
       '    <int name="recentsAnim" value="%d" />' % int(os.environ.get('RECIENTES', '0')),
       '    <string name="recentKeys">%s</string>' % nl.join(escape(k) for k in keys[:9]),
       '    <string name="dockKeys">%s</string>' % nl.join(escape(k) for k in dock),
       '    <string name="homeKeys">%s</string>' % nl.join(escape(k) for k in home),
       '    <string name="knownKeys">%s</string>' % nl.join(escape(k) for k in keys),
       "</map>", ""]
open(out, "w", encoding="utf-8").write("\n".join(xml))
# ajustes del motor S (archivo nioro_s.xml): SVALS="home.layout=1,dock.shape=5" (opcional)
sv = os.environ.get("SVALS", "").strip()
sx = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
for kv in [x for x in sv.split(",") if "=" in x]:
    k, v = kv.split("=", 1)
    sx.append('    <float name="%s" value="%s" />' % (escape(k.strip()), float(v)))
sx += ["</map>", ""]
open(out.replace(".xml", "_s.xml"), "w", encoding="utf-8").write("\n".join(sx))
print("apps:", len(keys), "inicio:", len(home), "dock:", len(dock))
