#!/usr/bin/env python3
"""Prépare le projet Android généré par Capacitor : signature par secrets, version, couleur de fond.

Usage : python3 patch_android.py <dossier_android> <numero_de_build>
"""
import json
import os
import re
import shutil
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
g = re.sub(
    r"\bminSdkVersion\s+rootProject\.ext\.minSdkVersion",
    "minSdkVersion 23",
    g,
    count=1,
)

# BiometricPrompt bridge used by the native PIN/biometric lock.
if "androidx.biometric:biometric:" not in g:
    g, n = re.subn(
        r"(\ndependencies\s*\{)",
        r'\1\n    implementation "androidx.biometric:biometric:1.1.0"',
        g,
        count=1,
    )
    if n != 1:
        sys.exit("Bloc dependencies { introuvable dans app/build.gradle")

# Même clé de signature pour les mises à jour; les mots de passe viennent des secrets d'environnement.
if "momo.keystore" not in g:
    if not os.path.isfile(keystore):
        sys.exit("Keystore absent : configurez le secret MOMO_KEYSTORE_BASE64 dans GitHub Actions.")
    if not os.environ.get("MOMO_KEYSTORE_PASSWORD") or not os.environ.get("MOMO_KEY_PASSWORD"):
        sys.exit("Secrets de signature absents : configurez MOMO_KEYSTORE_PASSWORD et MOMO_KEY_PASSWORD.")
    block = (
        "\n    signingConfigs {\n"
        "        debug {\n"
        '            storeFile file("%s")\n'
        '            storePassword System.getenv("MOMO_KEYSTORE_PASSWORD")\n'
        '            keyAlias "momo"\n'
        '            keyPassword System.getenv("MOMO_KEY_PASSWORD")\n'
        "        }\n"
        "    }\n" % keystore.replace("\\", "/")
    )
    g, n = re.subn(r"(\nandroid\s*\{)", lambda m: m.group(1) + block, g, count=1)
    if n != 1:
        sys.exit("Bloc android { introuvable dans build.gradle")
open(gradle_path, "w", encoding="utf-8").write(g)

# Enregistrement des fichiers Excel (sauvegardes) dans Documents/CabineMomo
man_path = os.path.join(android, "app", "src", "main", "AndroidManifest.xml")
man = open(man_path, encoding="utf-8").read()
if "WRITE_EXTERNAL_STORAGE" not in man:
    man = man.replace(
        "</manifest>",
        '    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />\n'
        '    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />\n</manifest>',
    )
if "requestLegacyExternalStorage" not in man:
    man = man.replace("<application", '<application android:requestLegacyExternalStorage="true"', 1)
if "android.permission.USE_BIOMETRIC" not in man:
    man = man.replace(
        "</manifest>",
        '    <uses-permission android:name="android.permission.USE_BIOMETRIC" />\n'
        '    <uses-permission android:name="android.permission.USE_FINGERPRINT" android:maxSdkVersion="28" />\n</manifest>',
    )
open(man_path, "w", encoding="utf-8").write(man)

# Sauvegarde Excel quotidienne de 23h59 : code Android natif (dossier native/ du dépôt)
java_root = os.path.join(android, "app", "src", "main", "java")
pkg_dir = None
for dp, dn, fn in os.walk(java_root):
    if "MainActivity.java" in fn:
        pkg_dir = dp
        break
if pkg_dir is None:
    pkg_dir = os.path.join(java_root, "com", "cabine", "momo")
    os.makedirs(pkg_dir, exist_ok=True)
native_dir = os.path.join(root, "native")
for name in sorted(os.listdir(native_dir)):
    if name.endswith(".java"):
        shutil.copy(os.path.join(native_dir, name), os.path.join(pkg_dir, name))
man = open(man_path, encoding="utf-8").read()
for perm in ("RECEIVE_BOOT_COMPLETED", "SCHEDULE_EXACT_ALARM"):
    if perm not in man:
        man = man.replace("</manifest>", '    <uses-permission android:name="android.permission.%s" />\n</manifest>' % perm)
if "com.cabine.momo.BackupReceiver" not in man:
    man = man.replace(
        "</application>",
        '        <receiver android:name="com.cabine.momo.BackupReceiver" android:exported="true">\n'
        "            <intent-filter>\n"
        '                <action android:name="android.intent.action.BOOT_COMPLETED" />\n'
        '                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />\n'
        '                <action android:name="com.cabine.momo.BACKUP" />\n'
        "            </intent-filter>\n"
        "        </receiver>\n    </application>",
    )
open(man_path, "w", encoding="utf-8").write(man)

# Couleur de fond de la WebView (évite un flash blanc au démarrage)
cfg_path = os.path.join(os.path.dirname(android), "capacitor.config.json")
if os.path.exists(cfg_path):
    cfg = json.load(open(cfg_path, encoding="utf-8"))
    cfg["backgroundColor"] = "#04281B"
    json.dump(cfg, open(cfg_path, "w", encoding="utf-8"), indent=2, ensure_ascii=False)

print("Projet Android préparé (build %s)" % build)
