#!/usr/bin/env bash
# Build the macOS installer (.dmg) for Modulus for Companion.
#
# Requires:
#   - Java 17+ with jpackage (JDK 14+)  — Zulu+FX or Liberica Full recommended;
#     standard JDK (Temurin) also works using the JavaFX JARs built by mvn package.
#   - Maven 3.9+ on PATH
#
# Output: target/dist/Modulus for Companion.dmg

set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_VERSION=0.0.5
FX_VERSION=17.0.12
JAR="$DIR/target/modulus-for-companion-$APP_VERSION.jar"
LIB="$DIR/target/lib"
APP="$DIR/target/app-stage"
DIST="$DIR/target/dist"

# 1. Build
echo "[1/3] Building..."
mvn -q package -f "$DIR/pom.xml"

# 2. Prepare staging dir
echo "[2/3] Preparing staging directory..."
rm -rf "$APP" && mkdir -p "$APP"
cp "$JAR" "$APP/"
cp "$LIB"/jackson-*.jar "$APP/"
cp "$LIB"/snakeyaml-*.jar "$APP/"
ls "$APP"

# 3. Run jpackage
echo "[3/3] Running jpackage..."
rm -rf "$DIST"

# Prefer JavaFX-bundled JDK if available (JAVAFX_HOME), otherwise use Maven JARs
if [ -n "$JAVAFX_HOME" ] && [ -d "$JAVAFX_HOME/lib" ]; then
  MODS="$JAVAFX_HOME/lib"
  echo "Using JavaFX SDK: $JAVAFX_HOME"
else
  MODS="$LIB/javafx-controls-$FX_VERSION-mac.jar:$LIB/javafx-graphics-$FX_VERSION-mac.jar:$LIB/javafx-base-$FX_VERSION-mac.jar"
  echo "Using JavaFX from Maven local repo"
fi

jpackage \
  --input "$APP" \
  --main-jar "modulus-for-companion-$APP_VERSION.jar" \
  --main-class ModulusForCompanion \
  --name "Modulus for Companion" \
  --app-version "$APP_VERSION" \
  --vendor Modulus \
  --module-path "$JAVA_HOME/jmods:$MODS" \
  --add-modules javafx.controls,javafx.graphics,javafx.base \
  --icon "$DIR/src/main/resources/icons/app.icns" \
  --mac-package-name "Modulus for Companion" \
  --dest "$DIST"

echo ""
echo "Done!  Distributable: $DIST/"
