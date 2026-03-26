#!/usr/bin/env bash
set -e

# ─────────────────────────────────────────────
# shadowsocks-android  –  WSL Build Script
# Requires: Ubuntu/Debian WSL
# ─────────────────────────────────────────────

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_SDK_ROOT="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
CMDLINE_TOOLS_ZIP="/tmp/cmdline-tools.zip"

# SDK components required
BUILD_TOOLS_VERSION="34.0.0"
PLATFORM_VERSION="android-36"
NDK_VERSION="27.0.12077973"

echo "==> Project dir : $PROJECT_DIR"
echo "==> Android SDK : $ANDROID_SDK_ROOT"

# ─── Helper: check if an apt package is installed ───
pkg_installed() { dpkg -s "$1" &>/dev/null; }

# ─── Helper: check if an SDK component is installed ─
sdk_installed() { [ -d "$ANDROID_SDK_ROOT/$1" ]; }

# ─── 1. System packages ───────────────────────
echo ""
echo "==> [1/6] Checking system packages..."

MISSING_PKGS=()
for pkg in curl wget unzip zip git python3 pkg-config libssl-dev; do
    pkg_installed "$pkg" || MISSING_PKGS+=("$pkg")
done

# Java: accept 17 or 21
if ! java -version 2>&1 | grep -qE "version \"(17|21)"; then
    pkg_installed openjdk-17-jdk || MISSING_PKGS+=("openjdk-17-jdk")
fi

# build-essential: check for gcc as a proxy
command -v gcc &>/dev/null || MISSING_PKGS+=("build-essential")

if [ ${#MISSING_PKGS[@]} -eq 0 ]; then
    echo "     All system packages already installed. Skipping."
else
    echo "     Installing missing packages: ${MISSING_PKGS[*]}"
    sudo apt-get update -qq
    sudo apt-get install -y -qq "${MISSING_PKGS[@]}"
fi

# Always detect Java home from Linux paths (ignore any Windows JAVA_HOME set in env)
JAVA_HOME=""
for jdir in \
    /usr/lib/jvm/java-21-openjdk-amd64 \
    /usr/lib/jvm/java-17-openjdk-amd64 \
    /usr/lib/jvm/java-21-openjdk-arm64 \
    /usr/lib/jvm/java-17-openjdk-arm64; do
    [ -d "$jdir" ] && JAVA_HOME="$jdir" && break
done

if [ -z "$JAVA_HOME" ]; then
    # Fallback: ask update-alternatives
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
echo "     Java: $(java -version 2>&1 | head -1)"
echo "     JAVA_HOME: $JAVA_HOME"

# ─── 2. Rust toolchain ────────────────────────
echo ""
echo "==> [2/6] Checking Rust toolchain..."

if ! command -v rustup &>/dev/null; then
    echo "     rustup not found — installing..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
else
    echo "     rustup already installed: $(rustup --version 2>&1)"
fi
source "$HOME/.cargo/env"

# Update only if not already on stable
if ! rustup show active-toolchain 2>/dev/null | grep -q "stable"; then
    rustup update stable
fi

# Android Rust targets
echo "     Checking Android Rust targets..."
INSTALLED_TARGETS=$(rustup target list --installed)
for target in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android; do
    if echo "$INSTALLED_TARGETS" | grep -q "$target"; then
        echo "     [skip] $target already installed"
    else
        echo "     [add]  $target"
        rustup target add "$target"
    fi
done

# ─── 3. Android SDK ───────────────────────────
echo ""
echo "==> [3/6] Checking Android SDK..."
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"

if [ -f "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "     Command-line tools already present. Skipping download."
else
    echo "     Downloading Android command-line tools..."
    wget -q --show-progress -O "$CMDLINE_TOOLS_ZIP" "$CMDLINE_TOOLS_URL"
    unzip -q "$CMDLINE_TOOLS_ZIP" -d /tmp/cmdline-tools-extract
    mv /tmp/cmdline-tools-extract/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    rm -f "$CMDLINE_TOOLS_ZIP"
fi

export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

# Accept licenses (idempotent)
yes | sdkmanager --licenses > /dev/null 2>&1 || true

# Install SDK components only if missing
echo "     Checking SDK components..."
for component in "platforms;$PLATFORM_VERSION" "build-tools;$BUILD_TOOLS_VERSION" "ndk;$NDK_VERSION" "platform-tools"; do
    # Convert component path separator for directory check
    comp_dir="${component/;/\/}"
    if sdk_installed "$comp_dir"; then
        echo "     [skip] $component already installed"
    else
        echo "     [install] $component"
        sdkmanager --install "$component"
    fi
done

# ─── 4. Environment variables ─────────────────
echo ""
echo "==> [4/6] Configuring environment..."
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"

# Persist to ~/.bashrc if not already there
if ! grep -q "ANDROID_HOME" "$HOME/.bashrc"; then
    cat >> "$HOME/.bashrc" <<EOF

# Android SDK
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_NDK_HOME"
export PATH="\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH"
EOF
    echo "     Saved ANDROID_HOME to ~/.bashrc"
else
    echo "     ANDROID_HOME already in ~/.bashrc. Skipping."
fi

if ! grep -q "cargo/env" "$HOME/.bashrc"; then
    echo 'source "$HOME/.cargo/env"' >> "$HOME/.bashrc"
    echo "     Saved cargo env to ~/.bashrc"
else
    echo "     cargo/env already in ~/.bashrc. Skipping."
fi

# ─── 5. Git submodules ────────────────────────
echo ""
echo "==> [5/6] Checking git submodules..."
cd "$PROJECT_DIR"
MISSING_SUBS=0
while IFS= read -r subpath; do
    subpath=$(echo "$subpath" | xargs)
    [ -z "$subpath" ] && continue
    if [ -z "$(ls -A "$PROJECT_DIR/$subpath" 2>/dev/null)" ]; then
        MISSING_SUBS=1
        break
    fi
done < <(grep 'path' "$PROJECT_DIR/.gitmodules" | awk -F '= ' '{print $2}')

if [ "$MISSING_SUBS" -eq 1 ]; then
    echo "     Initializing submodules (badvpn, libancillary, libevent, redsocks, shadowsocks-rust)..."
    git submodule update --init --recursive
else
    echo "     Submodules already initialized. Skipping."
fi

# ─── 7. local.properties ──────────────────────
echo ""
echo "==> [6/7] Writing local.properties..."
cat > "$PROJECT_DIR/local.properties" <<EOF
sdk.dir=$ANDROID_HOME
ndk.dir=$ANDROID_NDK_HOME
EOF
echo "     sdk.dir=$ANDROID_HOME"
echo "     ndk.dir=$ANDROID_NDK_HOME"

# ─── 6. Build APK ─────────────────────────────
echo ""
echo "==> [7/7] Building debug APK..."
cd "$PROJECT_DIR"

# Fix Windows line endings (CRLF -> LF) so WSL can execute gradlew
sed -i 's/\r//' gradlew
chmod +x gradlew

ANDROID_HOME="$ANDROID_HOME" \
ANDROID_NDK_HOME="$ANDROID_NDK_HOME" \
JAVA_HOME="$JAVA_HOME" \
./gradlew :plugin:assembleDebug :core:assembleDebug :shadowsocks-sdk:assembleDebug :mobile:assembleDebug --stacktrace

echo ""
echo "============================================"
echo "  Build complete!"
echo ""
echo "  APK:"
find "$PROJECT_DIR/mobile/build/outputs/apk" -name "*.apk" 2>/dev/null || echo "  mobile/build/outputs/apk/"
echo ""
echo "  SDK AARs:"
find "$PROJECT_DIR/plugin/build/outputs/aar" -name "*.aar" 2>/dev/null
find "$PROJECT_DIR/core/build/outputs/aar" -name "*.aar" 2>/dev/null
find "$PROJECT_DIR/shadowsocks-sdk/build/outputs/aar" -name "*.aar" 2>/dev/null
echo "============================================"
