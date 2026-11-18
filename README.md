# Sonicare BLE Diagnostic

First-stage Android diagnostic app for testing Philips Sonicare HX7410/02 over BLE.

## Goal

This app deliberately does NOT implement MQTT or Home Assistant yet. It scans for BLE advertisements and can later be extended with GATT connection, bonding and Condor protocol handling.

## Build

Open this directory in Android Studio. Let Gradle sync, then run the `app` configuration on the Pixel 9 Pro XL.

Requirements:
- Android 8.0+ (API 26)
- Android Studio with JDK 17
- Pixel with Bluetooth enabled

## Test

1. Close the official Sonicare app.
2. Remove the toothbrush from its charger.
3. Wake the toothbrush.
4. Start `SCAN 15 s`.
5. Save/copy the log.

The important result is the advertisement containing the Philips/Sonicare identity and service/manufacturer data. The next version will add automatic detection and GATT connection.

Note: the current upstream `philips_sonicare_ble` project documents bonding and rotating private addresses for the HX74/Series 7100 family, so the final gateway must not rely on a fixed BLE MAC address.
