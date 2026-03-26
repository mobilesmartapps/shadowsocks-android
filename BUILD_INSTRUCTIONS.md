# Build Instructions — shadowsocks-android

## Requirements
- Windows 11 with WSL2 (Ubuntu 20.04 / 22.04 recommended)
- ~5 GB free disk space (SDK + NDK + Rust)
- Internet connection (first run downloads dependencies)

## Quick Start

### 1. Open WSL terminal
```bash
wsl
```

### 2. Navigate to the project
The Windows path `F:\claude\myshadowsocks` is accessible in WSL at:
```bash
cd /mnt/f/claude/myshadowsocks
```

### 3. Run the build script (first time — installs everything)
```bash
chmod +x setup_and_build.sh
./setup_and_build.sh
```

This script will automatically:
- Install JDK 17
- Install Rust + Android targets (aarch64, armv7, x86_64, x86)
- Download Android SDK command-line tools
- Install Android SDK platform (android-36), build-tools, NDK
- Build the debug APK

### 4. Find your APK
After a successful build:
```
mobile/build/outputs/apk/debug/mobile-debug.apk
```

---

## Subsequent Builds (after setup)
Once the environment is set up, just run:
```bash
cd /mnt/f/claude/myshadowsocks
./gradlew :mobile:assembleDebug
```

## Build Variants
| Command | Output |
|---|---|
| `./gradlew :mobile:assembleDebug` | Debug APK (unsigned) |
| `./gradlew :mobile:assembleRelease` | Release APK (needs signing config) |
| `./gradlew :tv:assembleDebug` | TV variant debug APK |

## Signing a Release APK
To build a signed release APK, add to `local.properties`:
```
STORE_FILE=/path/to/keystore.jks
STORE_PASSWORD=yourpassword
KEY_ALIAS=youralias
KEY_PASSWORD=yourkeypassword
```

## Troubleshooting

**`ANDROID_HOME not set`**
```bash
source ~/.bashrc
```

**`Rust target not found`**
```bash
source ~/.cargo/env
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

**`SDK license not accepted`**
```bash
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
```

**Out of memory during build**
Edit `gradle.properties` and increase:
```
org.gradle.jvmargs=-Xmx4096m -XX:+UseParallelGC
```
