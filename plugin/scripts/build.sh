#!/usr/bin/env bash
# Build the Skiplum superuser plugin and deploy it to Solibri's lib/.
# Usage (Git Bash):  bash /c/workspace/toolkit/solibri/plugin/scripts/build.sh [--no-deploy]
# Solibri must be restarted to load a new jar (python cli.py shutdown && python cli.py launch).
set -euo pipefail

JDK="${JDK:-$(ls -d "/c/Program Files/Eclipse Adoptium/"jdk-21* 2>/dev/null | head -1)}"
LIB="C:/Program Files/Solibri/SOLIBRI/lib"
HERE="$(cd "$(dirname "$0")/../java" && pwd)"
OUT="$HERE/build"
JAR_NAME="skiplum-superuser.jar"

[ -x "$JDK/bin/javac" ] || { echo "[ERROR] JDK 21 not found (winget install EclipseAdoptium.Temurin.21.JDK)"; exit 1; }

rm -rf "$OUT" && mkdir -p "$OUT"
# solibri.jar on the classpath gives access to internal classes (classificationplugin, RecentResource, ...)
"$JDK/bin/javac" --release 21 --add-modules jdk.httpserver \
  -cp "$LIB/smc-api.jar;$LIB/smc-geometry.jar;$LIB/solibri.jar;$LIB/*" \
  -d "$OUT" "$HERE"/src/main/java/no/skiplum/solibri/superuser/*.java
cp -r "$HERE/src/main/resources/META-INF" "$OUT/"
"$JDK/bin/jar" cfm "$HERE/$JAR_NAME" "$HERE/manifest.mf" -C "$OUT" .
echo "[OK] built $HERE/$JAR_NAME"

[ "${1:-}" = "--no-deploy" ] && exit 0
# Exactly one copy of the plugin in lib/: two jars with the same SPI classes collide.
rm -f "$LIB"/skiplum-solibri-tools*.jar
cp "$HERE/$JAR_NAME" "$LIB/"
echo "[OK] deployed to $LIB/$JAR_NAME. Restart Solibri to load."
