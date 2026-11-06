
#include <Arduino.h>
#include <WiFi.h>

void setup() {
    Serial.begin(115200);
    delay(1000);

    Serial.println("\n==============================");
    Serial.println(" ESP32 INITIALIZATION TEST");
    Serial.println("==============================");

    // ESP32 information
    Serial.print("Chip model: ");
    Serial.println(ESP.getChipModel());

    Serial.print("Chip revision: ");
    Serial.println(ESP.getChipRevision());

    Serial.print("CPU frequency: ");
    Serial.print(ESP.getCpuFreqMHz());
    Serial.println(" MHz");

    Serial.print("Flash size: ");
    Serial.print(ESP.getFlashChipSize() / 1024 / 1024);
    Serial.println(" MB");

    // PSRAM test
    if (psramFound()) {
        Serial.println("PSRAM: DETECTED");
        Serial.print("PSRAM size: ");
        Serial.print(ESP.getPsramSize() / 1024);
        Serial.println(" KB");
    } else {
        Serial.println("PSRAM: NOT DETECTED");
    }

    // WiFi test
    Serial.println("\nStarting WiFi scan...");

    WiFi.mode(WIFI_STA);
    WiFi.disconnect();
    delay(500);

    int networks = WiFi.scanNetworks();

    if (networks < 0) {
        Serial.println("WiFi scan failed.");
    } else {
        Serial.print("Networks found: ");
        Serial.println(networks);

        for (int i = 0; i < networks; i++) {
            Serial.print(i + 1);
            Serial.print(". ");
            Serial.print(WiFi.SSID(i));
            Serial.print(" | RSSI: ");
            Serial.println(WiFi.RSSI(i));
        }
    }

    Serial.println("\nESP32 TEST COMPLETED!");
}

void loop() {
    Serial.println("ESP32 is running...");
    delay(3000);
}
