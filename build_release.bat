@echo off
setlocal EnableExtensions

pushd "%~dp0" >nul || exit /b 1
set "ROOT=%CD%"

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

if not defined ANDROID_HOME (
    if exist "C:\AndroidSdk\platforms\" set "ANDROID_HOME=C:\AndroidSdk"
)

if not defined ANDROID_HOME (
    if exist "%LOCALAPPDATA%\Android\Sdk\platforms\" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)

if not defined ANDROID_HOME if defined ANDROID_SDK_ROOT (
    if exist "%ANDROID_SDK_ROOT%\platforms\" set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
)

if not defined ANDROID_SDK_ROOT (
    if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
)

set "PATH=%JAVA_HOME%\bin;%PATH%"

set "BUILD_ROOT=%ROOT%"
if not exist "%SystemDrive%\rutts-android-legacy\gradlew.bat" (
    mklink /J "%SystemDrive%\rutts-android-legacy" "%ROOT%" >nul 2>nul
)
if exist "%SystemDrive%\rutts-android-legacy\gradlew.bat" (
    set "BUILD_ROOT=%SystemDrive%\rutts-android-legacy"
)

set "BUILD_ANDROID_HOME=%ANDROID_HOME%"
if not exist "%SystemDrive%\rutts-android-sdk\platforms\" (
    mklink /J "%SystemDrive%\rutts-android-sdk" "%ANDROID_HOME%" >nul 2>nul
)
if exist "%SystemDrive%\rutts-android-sdk\platforms\" (
    set "BUILD_ANDROID_HOME=%SystemDrive%\rutts-android-sdk"
)
set "ANDROID_HOME=%BUILD_ANDROID_HOME%"
set "ANDROID_SDK_ROOT=%BUILD_ANDROID_HOME%"
> "%ROOT%\local.properties" echo sdk.dir=%BUILD_ANDROID_HOME:\=/%
if exist "%ROOT%\app\.cxx" rmdir /s /q "%ROOT%\app\.cxx"

pushd "%BUILD_ROOT%" >nul || exit /b 1
call ".\gradlew.bat" :app:assembleUniversalRelease %*
set "BUILD_EXIT_CODE=%ERRORLEVEL%"

if "%BUILD_EXIT_CODE%"=="0" (
    echo APK: %CD%\app\build\outputs\apk\universal\release\app-universal-release.apk
)

popd >nul
popd >nul
exit /b %BUILD_EXIT_CODE%
