/*
 * DIAGNOSTIC DHT11 (temporaire)
 *
 * 1) Cherche sur quelle broche arrive le fil DATA du DHT11 :
 *    le module DHT11 a une résistance qui tire DATA vers le +,
 *    donc la broche où il est branché lit 1, les broches libres lisent 0.
 * 2) Essaie ensuite de lire le DHT11 sur la broche 18, toutes les 3 s.
 *
 * Remettre ensuite le main.cpp normal.
 */

#include <Arduino.h>
#include <DHT.h>

constexpr int PIN_DHT = 18;
DHT dht(PIN_DHT, DHT11);

// Broches libres testées (sans 14 = PIR, ni les broches réservées de la carte)
const int CANDIDATES[] = {2, 12, 13, 15, 18, 19, 25};

void findDataPin() {
    Serial.println();
    Serial.println("--- Recherche du fil DATA ---");
    for (int pin : CANDIDATES) {
        pinMode(pin, INPUT_PULLDOWN);   // une broche libre est tirée vers 0
    }
    delay(50);
    bool found = false;
    for (int pin : CANDIDATES) {
        int level = digitalRead(pin);
        Serial.printf("Broche %2d : %d%s\n", pin, level, level ? "   <-- quelque chose est branche ici" : "");
        if (level) found = true;
    }
    if (!found) {
        Serial.println("Aucune broche ne lit 1 : le fil DATA n'arrive sur aucune de ces broches,");
        Serial.println("ou il est branche sur la mauvaise broche du DHT11.");
    }
    pinMode(PIN_DHT, INPUT);  // rend la broche 18 au DHT11
}

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println();
    Serial.println("=== Diagnostic DHT11 ===");
    findDataPin();
    dht.begin();
    delay(2000);  // le DHT11 a besoin d'1 a 2 s apres la mise sous tension
}

void loop() {
    float t = dht.readTemperature();
    float h = dht.readHumidity();
    if (isnan(t) || isnan(h)) {
        Serial.println("Lecture broche 18 : ECHEC (le capteur ne repond pas)");
    } else {
        Serial.printf("Lecture broche 18 : OK  temperature %.1f C  humidite %.1f %%\n", t, h);
    }
    delay(3000);
}