#!/usr/bin/env bash
# Build the macOS installer (.dmg) for Modulus for Companion.
#
# Requires:
#   - Java 17+ with jpackage (JDK 14+)  — Zulu+FX or Liberica Full recommended;
#     standard JDK (Temurin) also works using the JavaFX JARs built by mvn package.
#   - Maven 3.9+ on PATH
#
# Output: target/dist/Modulus for Companion.dmg
#
# Note: the app is ad-hoc signed (no Apple Developer account required).
# Users on macOS will need to right-click > Open the first time.

set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_VERSION=0.0.5
JPACKAGE_VERSION=1.0.0   # jpackage requires first number >= 1
FX_VERSION=17.0.12
JAR="$DIR/target/modulus-for-companion-$APP_VERSION.jar"
LIB="$DIR/target/lib"
APP="$DIR/target/app-stage"
DIST="$DIR/target/dist"
APP_NAME="Modulus for Companion"

# 1. Build
echo "[1/5] Building..."
mvn -q package -f "$DIR/pom.xml"

# 2. Prepare staging dir
echo "[2/5] Preparing staging directory..."
rm -rf "$APP" && mkdir -p "$APP"
cp "$JAR" "$APP/"
cp "$LIB"/jackson-*.jar "$APP/"
cp "$LIB"/snakeyaml-*.jar "$APP/"

# 3. Run jpackage to produce .app bundle
echo "[3/5] Running jpackage..."
rm -rf "$DIST" && mkdir -p "$DIST"

if [ -n "$JAVAFX_HOME" ] && [ -d "$JAVAFX_HOME/lib" ]; then
  MODS="$JAVAFX_HOME/lib"
  echo "Using JavaFX SDK: $JAVAFX_HOME"
else
  case "$(uname -m)" in
    arm64) FX_PLATFORM="mac-aarch64" ;;
    *)     FX_PLATFORM="mac" ;;
  esac
  MODS="$LIB/javafx-controls-$FX_VERSION-${FX_PLATFORM}.jar:$LIB/javafx-graphics-$FX_VERSION-${FX_PLATFORM}.jar:$LIB/javafx-base-$FX_VERSION-${FX_PLATFORM}.jar"
  echo "Using JavaFX platform: $FX_PLATFORM"
fi

jpackage \
  --type app-image \
  --input "$APP" \
  --main-jar "modulus-for-companion-$APP_VERSION.jar" \
  --main-class ModulusForCompanion \
  --name "$APP_NAME" \
  --app-version "$JPACKAGE_VERSION" \
  --vendor Modulus \
  --module-path "$JAVA_HOME/jmods:$MODS" \
  --add-modules javafx.controls,javafx.graphics,javafx.base \
  --icon "$DIR/src/main/resources/icons/app.icns" \
  --dest "$DIST"

# 4. Ad-hoc sign the .app so Gatekeeper treats it as self-consistent
echo "[4/5] Ad-hoc signing..."
codesign --sign - --force --deep "$DIST/$APP_NAME.app"

# 5. Create DMG with an Applications shortcut for drag-and-drop install
echo "[5/5] Creating DMG..."
DMG_STAGING="$DIR/target/dmg-staging"
rm -rf "$DMG_STAGING" && mkdir -p "$DMG_STAGING"
cp -r "$DIST/$APP_NAME.app" "$DMG_STAGING/"
ln -s /Applications "$DMG_STAGING/Applications"

hdiutil create \
  -volname "$APP_NAME" \
  -srcfolder "$DMG_STAGING" \
  -ov -format UDZO \
  "$DIST/$APP_NAME.dmg"

echo ""
echo "Done!  Distributable: $DIST/$APP_NAME.dmg"
