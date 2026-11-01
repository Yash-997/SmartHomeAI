// Template. Copy this file to "secrets.h" (same folder) and fill in your values.
//   secrets.h is git-ignored and must never be committed.
//
// There is deliberately NO MQTT username/password here: the device's MQTT credential is issued by the backend when
// you pair the device (see README) and is stored in the chip's flash, not in source code.
#pragma once

// Your 2.4 GHz Wi-Fi network (the ESP32 does not support 5 GHz).
#define WIFI_SSID "your-wifi-name"
#define WIFI_PASSWORD "your-wifi-password"

// How the ESP32 reaches your PC. Use the PC's LAN IP address (run `ipconfig`), NOT localhost.
// API_BASE_URL is the backend REST API (the backend's SERVER_PORT, 8080 by default). Plain http:// only.
#define API_BASE_URL "http://192.168.1.50:8080"
// The Mosquitto broker (MQTT_PORT in .env, 1883 by default).
#define MQTT_HOST "192.168.1.50"
#define MQTT_BROKER_PORT 1883
