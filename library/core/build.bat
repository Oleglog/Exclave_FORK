@echo off
setlocal enabledelayedexpansion

REM Берем настройки из Android Studio
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
set ANDROID_SDK_ROOT=%ANDROID_HOME%
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr

REM КРИТИЧНО: добавляем Java в PATH ДО других путей
set PATH=!JAVA_HOME!\bin;!PATH!;!ANDROID_SDK_ROOT!\platform-tools

REM NDK
set ANDROID_NDK_HOME=%ANDROID_HOME%\NDK

echo JAVA_HOME: !JAVA_HOME!
echo ANDROID_NDK_HOME: !ANDROID_NDK_HOME!
echo ANDROID_HOME: !ANDROID_HOME!

if not exist "!ANDROID_NDK_HOME!\meta\platforms.json" (
    echo ERROR: NDK not found at !ANDROID_NDK_HOME!
    exit /b 1
)

set CGO_LDFLAGS=-Wl,-z,max-page-size=16384
gomobile bind -v -androidapi 21 -trimpath -ldflags="-s -buildid= -checklinkname=0" -tags="with_clash" -o libsagernetcore.aar "github.com/dyhkwong/libsagernetcore" "github.com/openlibrecommunity/olcrtc/mobile" "github.com/cacggghp/vk-turn-proxy/mobile"

if errorlevel 1 (
    exit /b 1
)

set "proj=..\..\app\libs"
if exist "!proj!" (
    copy /Y libsagernetcore.aar "!proj!\libsagernetcore.aar"
    if errorlevel 1 (
        exit /b 1
    )
    echo Copied libsagernetcore.aar to !proj!
)
