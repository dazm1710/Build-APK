@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

:: ==========================================
:: CamOBS - PC Setup Script (Windows)
:: ==========================================
:: Configura firewall, verifica OBS y muestra IP para la app
:: Ejecutar como ADMINISTRADOR

echo ==========================================
echo  CamOBS - Configuracion PC (OBS Side)
echo ==========================================
echo.

:: Verificar admin
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [ERROR] Este script debe ejecutarse como ADMINISTRADOR
    echo.
    echo Click derecho en este archivo > "Ejecutar como administrador"
    pause
    exit /b 1
)

echo [OK] Ejecutando como Administrador
echo.

:: 1. Mostrar IPs locales
echo [1/4] Detectando IPs de red local...
for /f "tokens=2 delims=[]" %%a in ('ping -n 1 -4 "%computername%" ^| findstr \[') do set LOCAL_IP=%%a
echo     IP principal: %LOCAL_IP%
echo.
echo     Todas las IPs:
ipconfig | findstr /R "IPv4.*[0-9]" | findstr /V "127.0.0.1" | findstr /V "169.254"
echo.

:: 2. Configurar Firewall
echo [2/4] Configurando Firewall de Windows (puerto 8888 UDP)...
netsh advfirewall firewall show rule name="CamOBS SRT Listener" >nul 2>&1
if %errorLevel% equ 0 (
    echo     Regla ya existe, actualizando...
    netsh advfirewall firewall delete rule name="CamOBS SRT Listener" >nul
)
netsh advfirewall firewall add rule ^
    name="CamOBS SRT Listener" ^
    dir=in ^
    action=allow ^
    protocol=UDP ^
    localport=8888 ^
    profile=private ^
    description="Permitir SRT Listener de OBS para CamOBS" >nul
if %errorLevel% equ 0 (
    echo     [OK] Regla de firewall creada
) else (
    echo     [WARN] No se pudo crear regla (requiere admin)
)
echo.

:: 3. Verificar OBS
echo [3/4] Verificando OBS Studio...
where obs64 >nul 2>&1 || where obs32 >nul 2>&1
if %errorLevel% equ 0 (
    for /f "tokens=3" %%i in ('obs64 --version 2^>^&1 ^| findstr /R "[0-9]\.[0-9]\.[0-9]"') do set OBS_VERSION=%%i
    echo     [OK] OBS detectado: %OBS_VERSION%
    echo.
    echo     IMPORTANTE: Configura SRT Listener en OBS:
    echo       Tools > SRT Listener
    echo         Port: 8888
    echo         Mode: Listener
    echo         Latency: 20 ms
    echo         Peer Latency: 20 ms
    echo         Too Late Packet Drop: ON
    echo         Encryption: None
    echo       Click "Start"
    echo.
    echo     Luego agrega fuente en OBS:
    echo       Sources > + > Media Source
    echo         Input: srt://0.0.0.0:8888?mode=listener
) else (
    echo     [WARN] OBS no encontrado en PATH
    echo     Descarga: https://obsproject.com/download
)
echo.

:: 4. Instrucciones finales
echo [4/4] Resumen para configurar la app en el movil:
echo.
echo     URL SRT en la app:
echo       srt://%LOCAL_IP%:8888?mode=caller
echo.
echo     Si esa IP no funciona, prueba las otras mostradas arriba.
echo.
echo     Verificar conexion desde el movil (Termux o app Ping):
echo       ping %LOCAL_IP%
echo.
echo     Ver logs en PC mientras stream:
echo       adb logcat -s CamOBS:V SrtClient:V VideoEncoder:V StreamingEngine:V
echo.

echo ==========================================
echo  Configuracion completada
echo ==========================================
pause
endlocal