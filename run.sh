#!/usr/bin/env bash
# Run Modulus for Companion from source (macOS / Linux).
# Requires: Java 17+, Maven 3.9+ on PATH.

set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$DIR/target/modulus-for-companion-0.0.5.jar"
LIB="$DIR/target/lib"

# Build if the JAR doesn't exist yet
if [ ! -f "$JAR" ]; then
  echo "Building..."
  mvn -q package -f "$DIR/pom.xml"
fi

# Pick the right JavaFX platform JARs
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) PLATFORM="mac-aarch64" ;;
  Darwin-*)     PLATFORM="mac" ;;
  *-aarch64)    PLATFORM="linux-aarch64" ;;
  *)            PLATFORM="linux" ;;
esac

MODS="$LIB/javafx-controls-17.0.12-${PLATFORM}.jar:$LIB/javafx-graphics-17.0.12-${PLATFORM}.jar:$LIB/javafx-base-17.0.12-${PLATFORM}.jar"

exec java \
  --module-path "$MODS" \
  --add-modules javafx.controls,javafx.graphics,javafx.base \
  -jar "$JAR" "$@"
