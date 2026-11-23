# Sonicare Android Gateway

Android gateway for **Philips Sonicare HX7410/02** toothbrushes using Bluetooth Low Energy (BLE).

The application connects to the toothbrush directly from an Android phone, reads the Condor V4 BLE data, and publishes the telemetry to an MQTT broker. Home Assistant can then consume the data through MQTT Discovery.

## Why this project?

This is useful when the Home Assistant host or its Bluetooth adapter is outside the toothbrush's BLE range.

Instead of requiring the HA server to be physically close to the toothbrush:

**Sonicare HX7410 → BLE → Android phone → Wi-Fi/MQTT → Home Assistant**

The phone acts as a mobile BLE-to-MQTT gateway.

## Current release

**v0.1.34**

- Automatic BLE scanning and reconnect
- Condor V4 handshake and live subscriptions
- Battery, routine, brush-head and handle telemetry
- MQTT connection using HiveMQ MQTT Client
- Home Assistant MQTT Discovery
- Retained Home Assistant sensor configuration
- No manual Scan/Connect buttons required
- Automatic reconnect/search remains active

## Home Assistant

The application publishes Home Assistant MQTT Discovery configuration for the device:

**Philips Sonicare HX7410**

The MQTT Discovery device information identifies:

- Manufacturer: Philips
- Model: HX7410/02
- Device identifier: sonicare_hx7410

The current implementation creates MQTT sensors for data such as:

- Battery percentage
- Battery voltage
- Routine/session information
- Routine duration
- Routine length
- Routine intensity
- Brush-head usage
- Brush-head limit
- Brush-head usage percentage
- Handle state
- Physical modes
- Handle time

The normal telemetry topics remain under:

`sonicare/hx7410/`

including:

- `battery`
- `routine`
- `brushhead`
- `sonicare`
- `telemetry`

## MQTT configuration

The gateway supports:

- MQTT host
- MQTT port
- Username/password authentication
- Optional TLS
- Configurable base topic
- Home Assistant MQTT Discovery

Do not commit real MQTT credentials to the repository.

## Android application

The project is built with:

- Android SDK 36
- Kotlin
- Java 17
- Gradle 9.5.x
- HiveMQ MQTT Client 1.3.10

Minimum Android version: **Android 8.0 / API 26**

Tested with a **Google Pixel 9 Pro XL** and Philips Sonicare HX7410/02.

## Related Home Assistant integration

This project is intended to complement the community **Philips Sonicare BLE Home Assistant integration**:

https://github.com/mtheli/philips_sonicare_ble

The Android gateway does not replace the Home Assistant integration. It provides an alternative transport path when direct Bluetooth from the HA host is not practical.

## Architecture

```text
┌─────────────────────────┐
│ Philips Sonicare        │
│ HX7410/02               │
└────────────┬────────────┘
             │ BLE
             ▼
┌─────────────────────────┐
│ Android Gateway         │
│ SonicareBleDiagnostic   │
│                         │
│ BLE → Condor V4         │
│ BLE → MQTT              │
└────────────┬────────────┘
             │ Wi-Fi / MQTT
             ▼
┌─────────────────────────┐
│ MQTT Broker             │
└────────────┬────────────┘
             │ MQTT Discovery
             ▼
┌─────────────────────────┐
│ Home Assistant          │
│ Philips Sonicare HX7410│
└─────────────────────────┘
```

## Development

Clone the repository:

```bash
git clone https://github.com/Marthinek/sonicare-android-gateway.git
cd sonicare-android-gateway
```

Build the debug APK:

```./gradlew clean assembleDebug
```

## Release

Current stable release:

**v0.1.34**

Release tag:

https://github.com/Marthinek/sonicare-android-gateway/releases/tag/v0.1.34

## Disclaimer

This is an independent community project and is not affiliated with, endorsed by, or sponsored by Philips. Philips Sonicare is a trademark of Koninklijke Philips N.V.
