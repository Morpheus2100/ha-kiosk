# HA Kiosk

Eigen kiosk-app voor een Android-tablet als Home Assistant-paneel.

## Wat hij doet
- Toont je HA-dashboard schermvullend (geen status- of navigatiebalk)
- Scherm blijft aan
- Start vanzelf na een herstart van de tablet en na een update
- Herlaadt vanzelf als HA even weg is (bv. tijdens een HA-update)
- Terugknop sluit de app niet

## Instellingen
Tik **5x snel in de linkerbovenhoek** van het scherm.

## Installeren
Elke push naar `main` bouwt een nieuwe APK. Op de tablet:

`https://github.com/<jouw-naam>/<repo>/releases/latest/download/HAKiosk.apk`

Een nieuwe versie installeer je gewoon over de oude heen; je login en instellingen blijven bewaard.

## Na de eerste installatie
Geef de toestemming **Weergeven boven andere apps**, anders kan Android de app niet vanzelf starten na een herstart.
