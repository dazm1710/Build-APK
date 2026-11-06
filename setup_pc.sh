#!/usr/bin/env bash
# ==========================================
# CamOBS - PC Setup Script (Linux/macOS)
# ==========================================
# Configura firewall, verifica OBS y muestra IP para la app
# Linux: ejecutar con sudo
# macOS: ejecutar normal (pfctl requiere sudo)

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
RESET='\033[0m'

echo -e "${CYAN}==================================================${RESET}"
echo -e "${CYAN}  CamOBS - Configuración PC (OBS Side)${RESET}"
echo -e "${CYAN}==================================================${RESET}"
echo

# 1. Detectar IPs locales
echo -e "${BLUE}[1/4] Detectando IPs de red local...${RESET}"
LOCAL_IPS=$(ip route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}' || \
           ifconfig 2>/dev/null | grep -E "inet (192\.168|10\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)" | awk '{print $2}' | head -3)

if [[ -z "$LOCAL_IPS" ]]; then
    LOCAL_IPS=$(hostname -I | tr ' ' '\n' | grep -E "^(192\.168|10\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)" | head -3)
fi

echo -e "${GREEN}    IPs detectadas:${RESET}"
echo "$LOCAL_IPS" | while read ip; do
    echo "      $ip"
done
PRIMARY_IP=$(echo "$LOCAL_IPS" | head -1)
echo

# 2. Configurar Firewall
echo -e "${BLUE}[2/4] Configurando firewall (puerto 8888 UDP)...${RESET}"

if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    # Linux - ufw o firewalld o iptables
    if command -v ufw &>/dev/null; then
        if sudo ufw status | grep -q "8888/udp"; then
            echo "    Regla UFW ya existe"
        else
            sudo ufw allow 8888/udp comment "CamOBS SRT Listener"
            echo -e "${GREEN}    [OK] Regla UFW creada${RESET}"
        fi
    elif command -v firewall-cmd &>/dev/null; then
        sudo firewall-cmd --permanent --add-port=8888/udp --zone=public
        sudo firewall-cmd --reload
        echo -e "${GREEN}    [OK] Regla firewalld creada${RESET}"
    elif command -v iptables &>/dev/null; then
        sudo iptables -A INPUT -p udp --dport 8888 -j ACCEPT
        echo -e "${GREEN}    [OK] Regla iptables creada (temporal)${RESET}"
        echo -e "${YELLOW}    Nota: Para persistir, usa iptables-save o instala ufw${RESET}"
    else
        echo -e "${YELLOW}    No se detectó firewall conocido (ufw/firewalld/iptables)${RESET}"
    fi
elif [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS - pfctl
    PF_ANCHOR="/etc/pf.anchors/camobs"
    echo "anchor \"camobs\"" | sudo pfctl -a "camobs" -f - 2>/dev/null || true
    echo "pass in proto udp from any to any port 8888" | sudo tee "$PF_ANCHOR" >/dev/null
    sudo pfctl -a "camobs" -f "$PF_ANCHOR" 2>/dev/null
    sudo pfctl -e 2>/dev/null
    echo -e "${GREEN}    [OK] Regla pfctl creada${RESET}"
fi
echo

# 3. Verificar OBS
echo -e "${BLUE}[3/4] Verificando OBS Studio...${RESET}"
if command -v obs &>/dev/null || command -v obs64 &>/dev/null || flatpak info com.obsproject.Studio &>/dev/null; then
    if command -v obs &>/dev/null; then
        OBS_VERSION=$(obs --version 2>&1 | head -1)
    elif command -v obs64 &>/dev/null; then
        OBS_VERSION=$(obs64 --version 2>&1 | head -1)
    else
        OBS_VERSION="Flatpak: $(flatpak info com.obsproject.Studio | grep Version | awk '{print $2}')"
    fi
    echo -e "${GREEN}    [OK] OBS detectado: $OBS_VERSION${RESET}"
    echo
    echo -e "${YELLOW}    IMPORTANTE: Configura SRT Listener en OBS:${RESET}"
    echo "      Tools > SRT Listener"
    echo "        Port: 8888"
    echo "        Mode: Listener"
    echo "        Latency: 20 ms"
    echo "        Peer Latency: 20 ms"
    echo "        Too Late Packet Drop: ON"
    echo "        Encryption: None"
    echo "      Click \"Start\""
    echo
    echo -e "${YELLOW}    Luego agrega fuente en OBS:${RESET}"
    echo "      Sources > + > Media Source"
    echo "        Input: srt://0.0.0.0:8888?mode=listener"
else
    echo -e "${YELLOW}    [WARN] OBS no encontrado${RESET}"
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        echo "      Instalar: flatpak install flathub com.obsproject.Studio"
        echo "      O: sudo apt install obs-studio (Ubuntu/Debian)"
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        echo "      Instalar: brew install --cask obs"
    fi
fi
echo

# 4. Resumen
echo -e "${BLUE}[4/4] Resumen para configurar la app en el móvil:${RESET}"
echo
echo -e "${CYAN}    URL SRT en la app:${RESET}"
echo -e "${GREEN}      srt://${PRIMARY_IP}:8888?mode=caller${RESET}"
echo
if [[ $(echo "$LOCAL_IPS" | wc -l) -gt 1 ]]; then
    echo -e "${YELLOW}    IPs alternativas si falla:${RESET}"
    echo "$LOCAL_IPS" | tail -n +2 | while read ip; do
        echo "      srt://${ip}:8888?mode=caller"
    done
    echo
fi
echo -e "${YELLOW}    Verificar conexión desde el móvil:${RESET}"
echo "      ping ${PRIMARY_IP}"
echo
echo -e "${YELLOW}    Ver logs en PC mientras stream (si tienes adb):${RESET}"
echo "      adb logcat -s CamOBS:V SrtClient:V VideoEncoder:V StreamingEngine:V"
echo

echo -e "${CYAN}==================================================${RESET}"
echo -e "${GREEN}  Configuración completada${RESET}"
echo -e "${CYAN}==================================================${RESET}"