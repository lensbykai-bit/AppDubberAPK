@echo off
setlocal
cd /d "%~dp0"
echo DubStudio KH Pro Mobile - Android debug APK builder
echo Requires: Android Studio + Android SDK 35 + Java 17 + Internet.
echo.
where gradle >nul 2>nul
if not errorlevel 1 (
    gradle --no-daemon :app:assembleDebug
    goto result
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0BUILD_WITH_GRADLE.ps1"
:result
if errorlevel 1 (
    echo BUILD FAILED. See the error above. You can also open this folder in Android Studio.
    pause
    exit /b 1
)
echo.
echo APK location: app\build\outputs\apk\debug\app-debug.apk
pause
