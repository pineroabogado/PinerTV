@echo off
setlocal enabledelayedexpansion

echo.
echo ======================================================
echo    DESPLIEGUE PINERTV EN FIRE TV
echo ======================================================
echo.

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "ANDROID_PROJECT=%ROOT%\android_project"

set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "FIRE_TV_IP=192.168.18.52"
set "PACKAGE=com.piner.pinertv"
set "ACTIVITY=.ui.splash.SplashActivity"

if exist "%ROOT%\ip.txt" (
    for /f "usebackq tokens=2 delims=:" %%a in (`findstr /i "Fire TV" "%ROOT%\ip.txt"`) do (
        set "FIRE_TV_IP=%%a"
    )
    set "FIRE_TV_IP=!FIRE_TV_IP: =!"
)

echo Proyecto:  %ANDROID_PROJECT%
echo Fire TV:   %FIRE_TV_IP%
echo.

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: JDK no encontrado en %JAVA_HOME%
    goto :error
)
if not exist "%ADB%" (
    echo ERROR: adb no encontrado
    goto :error
)
if not exist "%ANDROID_PROJECT%\gradlew.bat" (
    echo ERROR: gradlew.bat no encontrado
    goto :error
)

if not exist "%ANDROID_PROJECT%\local.properties" (
    powershell -NoProfile -Command ^
        "$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk';" ^
        "$escaped = ($sdk -replace '\\','\\') -replace ':','\\:';" ^
        "Set-Content -Path '%ANDROID_PROJECT%\local.properties' -Value \"sdk.dir=$escaped\" -Encoding ASCII"
)

echo [1/4] Conectando Fire TV...
"%ADB%" disconnect %FIRE_TV_IP%:5555 >nul 2>&1
"%ADB%" connect %FIRE_TV_IP%
if errorlevel 1 goto :error

"%ADB%" devices | findstr /i "%FIRE_TV_IP%.*device" >nul
if errorlevel 1 (
    echo ERROR: Fire TV no conectado/autorizado
    goto :error
)

echo [2/4] Compilando e instalando...
cd /d "%ANDROID_PROJECT%"
call gradlew.bat installDebug
if errorlevel 1 (
    echo Reintento: uninstall + install...
    "%ADB%" uninstall %PACKAGE% >nul 2>&1
    call gradlew.bat installDebug
    if errorlevel 1 goto :error
)

echo [3/4] Lanzando PinerTV...
"%ADB%" shell am start -n %PACKAGE%/%ACTIVITY%
if errorlevel 1 goto :error

echo [4/4] OK
echo.
goto :eof

:error
echo.
echo FALLO en el despliegue.
exit /b 1
