#!/usr/bin/env sh
set -eu
GV=8.13
GD="${GRADLE_USER_HOME:-$HOME/.gradle}/quotevault-gradle-$GV"
GE="$GD/gradle-$GV/bin/gradle"
if [ ! -x "$GE" ]; then
  mkdir -p "$GD"
  ZIP="$GD/gradle.zip"
  echo "Downloading Gradle $GV (one-time setup)..."
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "https://services.gradle.org/distributions/gradle-$GV-bin.zip" -o "$ZIP"
  else
    wget -q "https://services.gradle.org/distributions/gradle-$GV-bin.zip" -O "$ZIP"
  fi
  unzip -q "$ZIP" -d "$GD"
  rm -f "$ZIP"
fi
exec "$GE" "$@"
