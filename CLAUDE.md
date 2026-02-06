# TremorWatch - Claude Instructions

## Project Overview
TremorWatch is a Wear OS + Android phone app for monitoring and tracking tremor severity for people with Parkinson's disease or essential tremor.

## Important Safety Rules

### App Uninstallation
**Never uninstall the apps without explicitly prompting the user first and suggesting they backup their data.** The phone app stores historical tremor data in a local Room database that will be lost if the app is uninstalled. Always:
1. Warn the user that uninstalling will delete their data
2. Suggest they export their data first (via the app's export feature)
3. Get explicit confirmation before proceeding with uninstall

## Project Structure
- `app/` - Wear OS watch app
- `phone/` - Android phone companion app
- `shared/` - Shared Kotlin code between watch and phone

## Build Commands
```bash
# Set Java home for Android Studio JDK
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"

# Build both apps
./gradlew assembleDebug

# Build watch app only
./gradlew :app:assembleDebug

# Build phone app only
./gradlew :phone:assembleDebug
```

## ADB Commands
```bash
# ADB is located at:
"C:\Users\david\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# Install watch app (replace with current device address)
adb -s <watch-address> install -r app/build/outputs/apk/debug/app-debug.apk

# Install phone app
adb -s <phone-serial> install -r phone/build/outputs/apk/debug/phone-debug.apk

# Grant background permissions on watch
adb -s <watch-address> shell dumpsys deviceidle whitelist +com.opensource.tremorwatch
adb -s <watch-address> shell cmd appops set com.opensource.tremorwatch RUN_IN_BACKGROUND allow
adb -s <watch-address> shell cmd appops set com.opensource.tremorwatch RUN_ANY_IN_BACKGROUND allow
```

## Key Features
- Accelerometer-based tremor detection on watch
- Subjective rating system (0-5 scale, 0 = no tremor)
- Data sync between watch and phone
- Historical data visualization on phone
- Data export functionality
