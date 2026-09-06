# Protocole de la tête de sonde externe (v0.3, cahier §9, issue #11)

Liaison **BLE, service UART Nordic** (NUS) : service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`,
caractéristique TX (sonde → téléphone, notifications) `6E400003-…`, RX (téléphone → sonde, écriture) `6E400002-…`.
La sonde s'annonce avec un nom commençant par `Radar-` (ex. `Radar-TBeam-01`). Le téléphone se connecte seul
dès qu'un relevé tourne et qu'une sonde est visible.

Chaque message = **une ligne texte** terminée par `\n`, champs séparés par `;`. Champs vides autorisés.
Le premier champ est le type. Horodatage : celui de réception côté téléphone (la sonde n'a pas besoin d'horloge).

| Type | Champs | Sens |
|---|---|---|
| `W` | `mac;ssid;rssi;freqMHz;caps` | point d'accès Wi-Fi vu par la sonde (comme le téléphone, mais déporté) |
| `S` | `mac;rssi;ssids_recherchés(virgules)` | **station** Wi-Fi (téléphone, PC, caméra qui *cherche* un réseau) — invisible au téléphone seul |
| `B` | `mac;nom;rssi;mfgHex` | annonce BLE vue par la sonde |
| `L` | `freqHz;rssi;snr;type;meta` | paquet LoRa : `type` = `meshtastic` / `lorawan` / `raw`, `meta` = en-tête (nodeId, devAddr, SF/BW), jamais la charge utile |
| `R` | `freqHz;rssi` | échantillon de balayage RSSI (occupation, bruit de fond) — SX1262 ou CC1101 |
| `C` | `freqHz;rssi;modulation;meta` | signal sub-GHz non LoRa (CC1101 : OOK/FSK — télécommande, capteur météo…) — réception seule |
| `G` | `lat;lon;alt;acc;sats` | position GPS de la sonde (peut remplacer celle du téléphone) |
| `P` | `pression_hPa;temp_C` | capteurs d'environnement, si présents |
| `I` | `nom;firmware;batterie_%;uptime_s` | identité / état, envoyée à la connexion puis toutes les 60 s |
| `E` | `message` | erreur ou journal de la sonde |

Commandes téléphone → sonde (RX), une ligne : `START`, `STOP`, `SWEEP 863000000 870000000 25000` (balayage
début fin pas Hz), `LORA 868100000 7 125` (fréq, SF, BW kHz), `LOG ON` / `LOG OFF` (mode logger autonome),
`DUMP` (renvoie le journal du logger), `PING`.

Identifiants côté téléphone : `W`/`B` → adresse MAC (comme les capteurs internes, fusion naturelle) ;
`S` → `STA-<mac>` (type `STATION`) ; `L` → `LORA-<nodeId ou devAddr>` sinon `LORA-<freq>-<hash meta>` (type `LORA`) ;
`C` → `SUBGHZ-<freq>-<modulation>` (type `LORA`, catégorie « sub-GHz ») ; `R` → alimente l'occupation spectrale
(pas un objet).

Exemple :
```
I;Radar-TBeam-01;0.3.0;87;120
G;20.661553;-87.046571;12.0;2.5;9
W;F4:1E:57:9E:36:16;AP-Iot;-52;2417;[WPA2-PSK-CCMP][ESS]
S;A4:83:E7:12:34:56;-71;INFINITUM56C4,Hyperion
L;868100000;-97;7.5;meshtastic;!a1b2c3d4 SF7 BW125
R;868300000;-112
C;433920000;-64;OOK;pulse=350us repeat=3
```
