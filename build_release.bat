@echo off
setlocal EnableExtensions

pushd "%~dp0" >nul || exit /b 1

if not defined JAVA_HOME (
    if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
        set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
    )
)

if not defined JAVA_HOME (
    if exist "%ProgramFiles(x86)%\Android\Android Studio\jbr\bin\java.exe" (
        set "JAVA_HOME=%ProgramFiles(x86)%\Android\Android Studio\jbr"
    )
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JAVA_HOME is not set and Android Studio JBR was not found.
    echo [ERROR] Install Android Studio or set JAVA_HOME to a JDK 17+ directory.
    popd >nul
    exit /b 1
)

rem Use the default Android SDK location. Missing SDK components (platforms,
rem build-tools, NDK, CMake) are downloaded by Gradle into this same folder.
if not defined ANDROID_HOME (
    if exist "%LOCALAPPDATA%\Android\Sdk\platforms\" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)

if not defined ANDROID_HOME if defined ANDROID_SDK_ROOT (
    if exist "%ANDROID_SDK_ROOT%\platforms\" set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
)

if not defined ANDROID_HOME (
    echo [ERROR] Android SDK not found at %LOCALAPPDATA%\Android\Sdk.
    echo [ERROR] Install it via Android Studio or set ANDROID_HOME.
    popd >nul
    exit /b 1
)

if not defined ANDROID_SDK_ROOT set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

set "PATH=%JAVA_HOME%\bin;%PATH%"

call ".\gradlew.bat" :app:assembleUniversalRelease %*
set "BUILD_EXIT_CODE=%ERRORLEVEL%"

if "%BUILD_EXIT_CODE%"=="0" (
    echo APK: %CD%\app\build\outputs\apk\universal\release\app-universal-release.apk
)

popd >nul
exit /b %BUILD_EXIT_CODE%
