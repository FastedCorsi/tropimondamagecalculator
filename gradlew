#!/bin/sh

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_VERSION=9.5.0
WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v$WRAPPER_VERSION/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_SHA256="497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"

if [ -n "$JAVA_HOME" ]; then
  JAVA_EXE="$JAVA_HOME/bin/java"
else
  JAVA_EXE=java
fi

if [ ! -f "$CLASSPATH" ]; then
  echo "Downloading Gradle Wrapper $WRAPPER_VERSION..."
  mkdir -p "$APP_HOME/gradle/wrapper"

  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$WRAPPER_URL" -o "$CLASSPATH"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$WRAPPER_URL" -O "$CLASSPATH"
  else
    echo "curl or wget is required to bootstrap gradle-wrapper.jar." >&2
    exit 1
  fi

  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$CLASSPATH" | awk '{print $1}')
  else
    ACTUAL=$(shasum -a 256 "$CLASSPATH" | awk '{print $1}')
  fi

  if [ "$ACTUAL" != "$WRAPPER_SHA256" ]; then
    rm -f "$CLASSPATH"
    echo "Gradle Wrapper checksum mismatch." >&2
    echo "Expected: $WRAPPER_SHA256" >&2
    echo "Actual:   $ACTUAL" >&2
    exit 1
  fi
fi

exec "$JAVA_EXE" \
  -Dorg.gradle.appname=gradlew \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"
