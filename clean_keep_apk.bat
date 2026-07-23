@echo off
setlocal EnableExtensions

pushd "%~dp0" >nul || exit /b 1

for %%I in ("%CD%") do set "PROJECT_NAME=%%~nI"
set "APK_ROOT=app\build\outputs\apk"
set "TMP_ROOT=%TEMP%\rutts_keep_apk_%PROJECT_NAME%_%RANDOM%_%RANDOM%"
set "TMP_APK=%TMP_ROOT%\apk"
set "HAS_APK=0"
set "CLEAN_FAILED=0"

if exist "app\build\" call ".\gradlew.bat" --stop >nul 2>nul

if exist "%APK_ROOT%\" (
    mkdir "%TMP_APK%" >nul 2>nul
    robocopy "%APK_ROOT%" "%TMP_APK%" *.apk /S /R:1 /W:1 /NFL /NDL /NJH /NJS /NC /NS >nul
    if errorlevel 8 set "CLEAN_FAILED=1"
)

dir /b /s "%TMP_APK%\*.apk" >nul 2>nul && set "HAS_APK=1"

call :remove_dir ".gradle"
call :remove_dir ".idea"
call :remove_dir "app\.cxx"
call :remove_dir "app\build"
call :remove_file "local.properties"
del /f /q "*.iml" >nul 2>nul

if "%HAS_APK%"=="1" (
    mkdir "%APK_ROOT%" >nul 2>nul
    robocopy "%TMP_APK%" "%APK_ROOT%" *.apk /S /R:1 /W:1 /NFL /NDL /NJH /NJS /NC /NS >nul
    if errorlevel 8 set "CLEAN_FAILED=1"
)

rd /s /q "%TMP_ROOT%" >nul 2>nul

if exist ".gradle\" set "CLEAN_FAILED=1"
if exist ".idea\" set "CLEAN_FAILED=1"
if exist "app\.cxx\" set "CLEAN_FAILED=1"
if exist "local.properties" set "CLEAN_FAILED=1"

if "%CLEAN_FAILED%"=="0" (
    echo Cleanup finished. APK files were kept.
    popd >nul
    exit /b 0
)

echo Cleanup finished with warnings. Close Android Studio or Gradle and run again if needed.
popd >nul
exit /b 1

:remove_dir
if exist "%~1\" rd /s /q "%~1" >nul 2>nul
exit /b 0

:remove_file
if exist "%~1" del /f /q "%~1" >nul 2>nul
exit /b 0
