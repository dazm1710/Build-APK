# CamOBS - Android Camera to OBS via SRT

App Android para transmitir la cámara del móvil a OBS Studio usando **SRT (Secure Reliable Transport)** con latencia ultra-baja (<100ms en LAN).

## 🚀 Características

- **SRT nativo** - Compatible con OBS Studio 27.1+ sin plugins
- **Latencia ultra-baja** - ~50-100ms en WiFi/LAN típica
- **Hardware encoding** - MediaCodec H.264/H.265 (AVC/HEVC)
- **CameraX** - API moderna de cámara con lifecycle awareness
- **Foreground Service** - Streaming continuo en background
- **Jetpack Compose + Material3** - UI moderna y reactiva
- **Hilt DI** - Inyección de dependencias
- **minSdk 21** - Compatible con Android 5.0+

## 📋 Requisitos previos

### PC (OBS Studio)
1. **OBS Studio 27.1 o superior** (SRT incluido nativamente)
2. Configurar **SRT Listener**:
   - Tools → SRT Listener
   - Puerto: `8888` (o el que prefieras)
   - Mode: `Listener`
   - Latency: `20ms` (para ultra-baja latencia)

### Android
- Android 5.0+ (API 21)
- Permisos: Cámara, Micrófono, Red, Notificaciones
- Misma red WiFi/LAN que la PC

## 🛠 Compilación

```bash
# Clonar y compilar
git clone <repo>
cd CamOBS
./gradlew assembleDebug

# APK en: app/build/outputs/apk/debug/app-debug.apk
```

## ⚙️ Configuración en la App

1. **URL SRT**: `srt://IP_DE_TU_PC:8888?mode=caller`
   - Ejemplo: `srt://192.168.1.50:8888?mode=caller`
2. **Resolución**: 720p recomendado (balance calidad/latencia)
3. **FPS**: 30 (o 60 si el dispositivo lo soporta)
4. **Bitrate**: 4000 Kbps para 720p, 8000+ para 1080p
4. **Modo latencia**: Ultra-baja (20ms buffer)

## 🔧 Configuración OBS óptima

```
SRT Listener Settings:
├── Port: 8888
├── Mode: Listener
├── Latency: 20 ms
├── Peer Latency: 20 ms
├── Too Late Packet Drop: ✓ Enabled
└── Encryption: None (para menor latencia)
```

## 📱 Uso

1. Inicia OBS con SRT Listener activo
2. Abre CamOBS en el móvil
3. Ingresa la IP de tu PC en la URL SRT
4. Pulsa **INICIAR STREAM**
5. En OBS: Add Source → Media Source → `srt://0.0.0.0:8888?mode=listener`

## 🏗 Arquitectura

```
┌─────────────────────────────────────────────────────────────┐
│                      Android App                            │
│  ┌──────────┐   ┌────────────┐   ┌──────────┐   ┌────────┐ │
│  │ CameraX  │──▶│VideoEncoder│──▶│ SRT Client│──▶│  UDP   │ │
│  │(Preview) │   │(MediaCodec)│   │(libsrt)  │   │(LAN)   │ │
│  └──────────┘   └────────────┘   └──────────┘   └────────┘ │
│        │              │                                  │   │
│        │              ▼                                  │   │
│        │         ┌────────────┐                          │   │
│        └────────▶│AudioEncoder│                          │   │
│                  │(MediaCodec)│                          │   │
│                  └────────────┘                          │   │
└─────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────┐
│                        PC/OBS                               │
│  ┌──────────┐   ┌────────────┐   ┌──────────────────────┐  │
│  │SRT Listener│◀───│  MPEG-TS   │◀───│  SRT Protocol      │  │
│  │ (OBS)    │   │  Demuxer   │   │  (ARQ, Congestion)   │  │
│  └──────────┘   └────────────┘   └──────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## 📦 Dependencias clave

| Biblioteca | Versión | Uso |
|------------|---------|-----|
| CameraX | 1.4.0 | Cámara |
| Media3 | 1.5.1 | MediaCodec helpers |
| libsrt-android | 1.5.3 | SRT nativo (JNI) |
| Compose BOM | 2024.09.00 | UI |
| Hilt | 2.50 | DI |
| Ktor | 3.0.0 | HTTP (config remota) |
| Coroutines | 1.8.1 | Async |

## 🐛 Troubleshooting

### "Connection failed"
- Verifica que OBS SRT Listener esté **activo** (botón Start)
- Confirma IP correcta: `ipconfig` (Windows) / `ifconfig` (Linux/Mac)
- Mismo red: ping desde móvil a PC
- Firewall Windows: permite puerto 8888 UDP

### Latencia alta
- OBS: Latency = 20ms, Peer Latency = 20ms
- App: Modo "Ultra-baja"
- WiFi: 5GHz, cerca del router
- Bitrate: no satures el enlace (máx 80% capacidad)

### Video negro / no aparece
- OBS: Media Source → `srt://0.0.0.0:8888?mode=listener`
- Formato: MPEG-TS, Codec: H.264
- Verifica logs: `adb logcat | grep -i camobs`

### Audio no funciona
- Permiso micrófono concedido
- `enableAudio: true` en config
- OBS: Audio Input Capture en la misma escena

## 📄 Licencia

MIT License - Úsalo libremente.

---

**¿Problemas?** Revisa `adb logcat -s CamOBS:V SrtClient:V VideoEncoder:V StreamingEngine:V`