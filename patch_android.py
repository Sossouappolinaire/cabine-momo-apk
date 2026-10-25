#!/usr/bin/env python3
"""Prépare le projet Android généré par Capacitor : signature fixe, numéro de version, couleur de fond.

Usage : python3 patch_android.py <dossier_android> <numero_de_build>
"""
import json
import os
import re
import sys

android = os.path.abspath(sys.argv[1])
build = sys.argv[2] if len(sys.argv) > 2 else "1"
root = os.path.dirname(os.path.abspath(__file__))
keystore = os.path.join(root, "momo.keystore")

gradle_path = os.path.join(android, "app", "build.gradle")
g = open(gradle_path, encoding="utf-8").read()

# Numéro de version : chaque build est plus récent que le précédent (mise à jour par-dessus l'ancienne)
g = re.sub(r"versionCode\s+\d+", "versionCode " + build, g, count=1)
g = re.sub(r'versionName\s+"[^"]*"', 'versionName "1.0.' + build + '"', g, count=1)

# Signature fixe : l'APK se met à jour sans désinstaller (les données locales sont conservées)
if "momo.keystore" not in g:
    block = (
        "\n    signingConfigs {\n"
        "        debug {\n"
        '            storeFile file("%s")\n'
        '            storePassword "momo-cabine"\n'
        '            keyAlias "momo"\n'
        '            keyPassword "momo-cabine"\n'
        "        }\n"
        "    }\n" % keystore.replace("\\", "/")
    )
    g, n = re.subn(r"(\nandroid\s*\{)", lambda m: m.group(1) + block, g, count=1)
    if n != 1:
        sys.exit("Bloc android { introuvable dans build.gradle")
open(gradle_path, "w", encoding="utf-8").write(g)

# Couleur de fond de la WebView (évite un flash blanc au démarrage)
cfg_path = os.path.join(os.path.dirname(android), "capacitor.config.json")
if os.path.exists(cfg_path):
    cfg = json.load(open(cfg_path, encoding="utf-8"))
    cfg["backgroundColor"] = "#04281B"
    json.dump(cfg, open(cfg_path, "w", encoding="utf-8"), indent=2, ensure_ascii=False)

print("Projet Android préparé (build %s)" % build)
