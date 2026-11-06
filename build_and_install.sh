#!/usr/bin/env bash
# ==========================================
# CamOBS - Build & Install Script (Linux/macOS)
# ==========================================
# Compila el APK e instala en el dispositivo conectado por ADB
# Uso: ./build_and_install.sh [--release] [--no-install] [--clean]

set -euo pipefail

# Configuración
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLEW="$PROJECT_DIR/gradlew"
APP_ID="com.camobs.debug"
MAIN_ACTIVITY="com.camobs.MainActivity"

# Colores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
RESET='\033[0m'

# Parse args
BUILD_TYPE="Debug"
DO_INSTALL=1
DO_CLEAN=0

for arg in "$@"; do
    case $arg in
        --release) BUILD_TYPE="Release" ;;
        --no-install) DO_INSTALL=0 ;;
        --clean) DO_CLEAN=1 ;;
        -h|--help)
            echo "Uso: $0 [--release] [--no-install] [--clean]"
            echo "  --release      Compilar versión Release (signed)"
            echo "  --no-install   Solo compilar, no instalar"
            echo "  --clean        Limpiar antes de compilar"
            exit 0
            ;;
    esac
done

echo -e "${CYAN}==================================================${RESET}"
echo -e "${CYAN}  CamOBS - Build & Install (${BUILD_TYPE})${RESET}"
echo -e "${CYAN}==================================================${RESET}"
echo

# Función de logging
log_step() { echo -e "${BLUE}[$1/6] $2${RESET}"; }
log_ok()   { echo -e "${GREEN}  OK: $1${RESET}"; }
log_warn() { echo -e "${YELLOW}  $1${RESET}"; }
log_err()  { echo -e "${RED}  ERROR: $1${RESET}"; }

# 1. Verificar Java
log_step 1 "Verificando Java..."
if java -version 2>&1 | grep -q "version \"17\."; then
    log_ok "JDK 17 detectado"
else
    log_warn "Se recomienda JDK 17. Detectado:"
    java -version 2>&1 | head -1
fi

# 2. Verificar Android SDK
log_step 2 "Verificando Android SDK..."
if [[ -z "${ANDROID_HOME:-}" ]]; then
    log_err "ANDROID_HOME no está definido"
    echo -e "${YELLOW}  Exporta en ~/.bashrc o ~/.zshrc:${RESET}"
    echo '  export ANDROID_HOME=$HOME/Android/Sdk'
    echo '  export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin'
    exit 1
fi
log_ok "ANDROID_HOME=$ANDROID_HOME"

# 3. Verificar ADB
log_step 3 "Verificando ADB..."
if ! command -v adb &>/dev/null; then
    log_err "adb no encontrado en PATH"
    echo -e "${YELLOW}  Agrega \$ANDROID_HOME/platform-tools al PATH${RESET}"
    exit 1
fi
log_ok "ADB disponible: $(adb --version | head -1)"

# 4. Verificar dispositivo
log_step 4 "Verificando dispositivo conectado..."
DEVICE=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
if [[ -z "$DEVICE" ]]; then
    log_err "No hay dispositivos conectados"
    echo -e "${YELLOW}  1. Conecta el celular por USB${RESET}"
    echo -e "${YELLOW}  2. Activa 'Depuración USB' en Opciones de desarrollador${RESET}"
    echo -e "${YELLOW}  3. Autoriza la conexión en la pantalla del celular${RESET}"
    exit 1
fi
log_ok "Dispositivo $DEVICE conectado"

# 5. Generar gradlew si no existe
if [[ ! -f "$GRADLEW" ]]; then
    log_step 5 "Generando Gradle Wrapper..."
    cd "$PROJECT_DIR"
    if ! command -v gradle &>/dev/null; then
        log_err "Gradle no instalado. Instálalo: https://gradle.org/install/"
        exit 1
    fi
    gradle wrapper --gradle-version 8.5
    log_ok "Wrapper generado"
fi

# 6. Compilar
log_step 5 "Compilando APK $BUILD_TYPE..."
cd "$PROJECT_DIR"
chmod +x "$GRADLEW"

GRADLE_TASK="assemble${BUILD_TYPE}"
[[ $DO_CLEAN -eq 1 ]] && GRADLE_TASK="clean $GRADLE_TASK"

if ! "$GRADLEW" $GRADLE_TASK --no-daemon; then
    log_err "Falló la compilación"
    exit 1
fi

# Localizar APK
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/${BUILD_TYPE,,}/app-${BUILD_TYPE,,}.apk"
if [[ ! -f "$APK_PATH" && "$BUILD_TYPE" == "Release" ]]; then
    APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
fi
if [[ ! -f "$APK_PATH" ]]; then
    log_err "APK no encontrado en $APK_PATH"
    exit 1
fi
log_ok "APK generado: $APK_PATH"

# Instalar
if [[ $DO_INSTALL -eq 1 ]]; then
    log_step 6 "Instalando en dispositivo..."
    if ! adb install -r -g "$APK_PATH"; then
        log_err "Falló la instalación"
        echo -e "${YELLOW}  Intenta: adb uninstall $APP_ID && adb install -r -g \"$APK_PATH\"${RESET}"
        exit 1
    fi
    log_ok "App instalada"

    # Lanzar app
    echo -e "${BLUE}       Lanzando app...${RESET}"
    adb shell am start -n "$APP_ID/$MAIN_ACTIVITY"
    log_ok "App iniciada"
else
    log_warn "[6/6] Saltando instalación (--no-install)"
fi

echo
echo -e "${CYAN}==================================================${RESET}"
echo -e "${GREEN}  ¡COMPLETADO!${RESET}"
echo -e "${CYAN}==================================================${RESET}"
echo
echo -e "${YELLOW}APK: $APK_PATH${RESET}"
echo -e "${YELLOW}Ver logs: adb logcat -s CamOBS:V SrtClient:V VideoEncoder:V StreamingEngine:V${RESET}"