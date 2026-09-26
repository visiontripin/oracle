#!/usr/bin/env bash
# Rebuild BotControl-debug.apk inside a bare Linux sandbox.
# Installs JDK 17 + Android SDK + Gradle into /opt (ephemeral), adds swap, builds.
set -euo pipefail

TOOLS=/opt/tools
SDK=/opt/android-sdk
GRH=/opt/gradle-home

# 0) swap (sandbox has ~2 GB RAM, dexing needs more)
if ! grep -q swapfile /proc/swaps; then
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap -q /swapfile
  sudo /sbin/swapon /swapfile 2>/dev/null || sudo swapon /swapfile
fi

# 1) JDK 17 + Gradle 8.7
sudo mkdir -p "$TOOLS" "$SDK" "$GRH"
sudo chown -R "$(whoami)" /opt
if [ ! -x "$TOOLS/jdk17/bin/java" ]; then
  curl -sSL -o /tmp/jdk.tar.gz "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.12%2B7/OpenJDK17U-jdk_x64_linux_hotspot_17.0.12_7.tar.gz"
  tar xzf /tmp/jdk.tar.gz -C "$TOOLS" && mv "$TOOLS/jdk-17.0.12+7" "$TOOLS/jdk17" && rm /tmp/jdk.tar.gz
fi
if [ ! -x "$TOOLS/gradle/bin/gradle" ]; then
  curl -sSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-8.13-bin.zip"
  unzip -q /tmp/gradle.zip -d "$TOOLS" && mv "$TOOLS/gradle-8.13" "$TOOLS/gradle" && rm /tmp/gradle.zip
fi

# 2) Android cmdline-tools + packages
if [ ! -d "$SDK/platforms/android-34" ]; then
  curl -sSL -o /tmp/ct.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  unzip -q /tmp/ct.zip -d /tmp/ct && mkdir -p "$SDK/cmdline-tools/latest" && cp -a /tmp/ct/cmdline-tools/. "$SDK/cmdline-tools/latest/" && rm -rf /tmp/ct /tmp/ct.zip
  export JAVA_HOME="$TOOLS/jdk17"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-34" "platforms;android-35" "build-tools;34.0.0" > /dev/null
fi

mkdir -p "$GRH"
cat > "$GRH/gradle.properties" <<'EOF'
kotlin.compiler.execution.strategy=in-process
kotlin.daemon.jvmargs=-Xmx512m
org.gradle.workers.max=1
EOF

# 3) build
export JAVA_HOME="$TOOLS/jdk17" ANDROID_HOME="$SDK" GRADLE_USER_HOME="$GRH"
cd "$(dirname "$0")/../android"
"$TOOLS/gradle/bin/gradle" :app:assembleDebug --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx1200m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"

cp app/build/outputs/apk/debug/app-debug.apk ../BotControl-debug.apk
echo "DONE: $(dirname "$0")/../BotControl-debug.apk"
