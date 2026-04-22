#!/usr/bin/env bash
# Build the macOS installer (.dmg) for Modulus for Companion.
#
# Requires:
#   - Java 17+ with jpackage (JDK 14+)  — Zulu+FX or Liberica Full recommended;
#     standard JDK (Temurin) also works using the JavaFX JARs built by mvn package.
#   - Maven 3.9+ on PATH
#
# Output: target/dist/Modulus-for-Companion-MacOS.dmg
#
# The app is intentionally left unsigned. On first launch macOS will block it;
# users can right-click > Open to bypass this.

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

# 4. Strip all signatures jpackage applied.
# A fully unsigned app can be cleared by the user with a one-time xattr command.
# A partially or ad-hoc signed app gets hard-blocked with no recourse.
echo "[4/5] Stripping signatures..."
find "$DIST/$APP_NAME.app" -type f \( -name "*.dylib" -o -name "*.so" \) | while read -r f; do
  codesign --remove-signature "$f" 2>/dev/null || true
done
codesign --remove-signature "$DIST/$APP_NAME.app/Contents/MacOS/$APP_NAME" 2>/dev/null || true
codesign --remove-signature "$DIST/$APP_NAME.app" 2>/dev/null || true

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
  "$DIST/Modulus-for-Companion-MacOS.dmg"

echo ""
echo "Done!  Distributable: $DIST/Modulus-for-Companion-MacOS.dmg"
