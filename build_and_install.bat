@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

:: ==========================================
:: CamOBS - Build & Install Script (Windows)
:: ==========================================
:: Compila el APK e instala en el dispositivo conectado por ADB
:: Uso: build_and_install.bat [--release] [--no-install] [--clean]

set PROJECT_DIR=%~dp0
set GRADLEW=%PROJECT_DIR%gradlew.bat
set APP_ID=com.camobs.debug
set MAIN_ACTIVITY=com.camobs.MainActivity

:: Colores
set RED=\033[91m
set GREEN=\033[92m
set YELLOW=\033[93m
set BLUE=\033[94m
set CYAN=\033[96m
set RESET=\033[0m

:: Parse args
set BUILD_TYPE=Debug
set DO_INSTALL=1
set DO_CLEAN=0

for %%a in (%*) do (
    if "%%a"=="--release" set BUILD_TYPE=Release
    if "%%a"=="--no-install" set DO_INSTALL=0
    if "%%a"=="--clean" set DO_CLEAN=1
)

echo %CYAN%==================================================%RESET%
echo %CYAN%  CamOBS - Build & Install (%BUILD_TYPE%)%RESET%
echo %CYAN%==================================================%RESET%
echo.

:: Verificar Java
echo %BLUE%[1/6] Verificando Java...%RESET%
java -version 2>&1 | findstr /C:"17." >nul
if errorlevel 1 (
    echo %YELLOW%  Advertencia: Se recomienda JDK 17. Detectado:%RESET%
    java -version 2>&1 | head -1
) else (
    echo %GREEN%  OK: JDK 17 detectado%RESET%
)

:: Verificar Android SDK
echo %BLUE%[2/6] Verificando Android SDK...%RESET%
if not defined ANDROID_HOME (
    echo %RED%  ERROR: ANDROID_HOME no esta definido%RESET%
    echo %YELLOW%  Configuralo en Variables de Entorno:%RESET%
    echo %YELLOW%    ANDROID_HOME=C:\Users\%USERNAME%\AppData\Local\Android\Sdk%RESET%
    exit /b 1
)
echo %GREEN%  OK: ANDROID_HOME=%ANDROID_HOME%%RESET%

:: Verificar ADB
echo %BLUE%[3/6] Verificando ADB...%RESET%
adb version >nul 2>&1
if errorlevel 1 (
    echo %RED%  ERROR: adb no encontrado en PATH%RESET%
    echo %YELLOW%  Agrega %ANDROID_HOME%\platform-tools al PATH%RESET%
    exit /b 1
)
echo %GREEN%  OK: ADB disponible%RESET%

:: Verificar dispositivo conectado
echo %BLUE%[4/6] Verificando dispositivo...%RESET%
for /f "skip=1 tokens=1,2" %%i in ('adb devices') do (
    if "%%j"=="device" set DEVICE=%%i
)
if not defined DEVICE (
    echo %RED%  ERROR: No hay dispositivos conectados%RESET%
    echo %YELLOW%  1. Conecta el celular por USB%RESET%
    echo %YELLOW%  2. Activa 'Depuracion USB' en Opciones de desarrollador%RESET%
    echo %YELLOW%  3. Autoriza la conexion en la pantalla del celular%RESET%
    exit /b 1
)
echo %GREEN%  OK: Dispositivo %DEVICE% conectado%RESET%

:: Generar gradlew si no existe
if not exist "%GRADLEW%" (
    echo %BLUE%[5/6] Generando Gradle Wrapper...%RESET%
    cd /d "%PROJECT_DIR%"
    gradle wrapper --gradle-version 8.5 2>&1 | findstr /V "Download"
    if errorlevel 1 (
        echo %RED%  ERROR: No se pudo generar wrapper. Instala Gradle primero.%RESET%
        exit /b 1
    )
)

:: Compilar
echo %BLUE%[5/6] Compilando APK %BUILD_TYPE%...%RESET%
cd /d "%PROJECT_DIR%"

set GRADLE_TASK=assemble%BUILD_TYPE%
if %DO_CLEAN%==1 set GRADLE_TASK=clean %GRADLE_TASK%

call %GRADLEW% %GRADLE_TASK% --no-daemon
if errorlevel 1 (
    echo %RED%  ERROR: Fallo la compilacion%RESET%
    exit /b 1
)

:: Localizar APK generado
set APK_PATH=%PROJECT_DIR%app\build\outputs\apk\%BUILD_TYPE%\app-%BUILD_TYPE%.apk
if not exist "%APK_PATH%" (
    :: Buscar en debug si es release
    if "%BUILD_TYPE%"=="Release" (
        set APK_PATH=%PROJECT_DIR%app\build\outputs\apk\debug\app-debug.apk
    )
)
if not exist "%APK_PATH%" (
    echo %RED%  ERROR: APK no encontrado en %APK_PATH%%RESET%
    exit /b 1
)

echo %GREEN%  OK: APK generado en %APK_PATH%%RESET%

:: Instalar
if %DO_INSTALL%==1 (
    echo %BLUE%[6/6] Instalando en dispositivo...%RESET%
    adb install -r -g "%APK_PATH%"
    if errorlevel 1 (
        echo %RED%  ERROR: Fallo la instalacion%RESET%
        echo %YELLOW%  Intenta: adb uninstall %APP_ID% && adb install -r -g "%APK_PATH%"%RESET%
        exit /b 1
    )
    echo %GREEN%  OK: App instalada%RESET%

    :: Lanzar app
    echo %BLUE%       Lanzando app...%RESET%
    adb shell am start -n %APP_ID%/%MAIN_ACTIVITY%
    echo %GREEN%  OK: App iniciada%RESET%
) else (
    echo %YELLOW%[6/6] Saltando instalacion (--no-install)%RESET%
)

echo.
echo %CYAN%==================================================%RESET%
echo %GREEN%  ¡COMPLETADO!%RESET%
echo %CYAN%==================================================%RESET%
echo.
echo %YELLOW%APK: %APK_PATH%%RESET%
echo %YELLOW%Para ver logs: adb logcat -s CamOBS:V SrtClient:V VideoEncoder:V StreamingEngine:V%RESET%

endlocal