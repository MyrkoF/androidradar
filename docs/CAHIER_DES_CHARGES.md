# Sonde — cahier des charges v0.2 (boussole, une page)

Statut : validé par Myrko (sections 1–9). Version : 2026-09-06 (rév. 4 : vivo en premier, tuiles sans infra).

## 1. Finalité

Outil Android tout-terrain, fonctionnant hors ligne (le réseau ne sert qu'à charger des fonds de carte), qui relève ce qui émet en Wi-Fi et BLE autour de l'utilisateur, le positionne sur une carte réelle, et permet de rejouer, comparer et exporter ces relevés.

Quatre usages, par ordre de priorité de conception :

1. Site survey avant déploiement LoRa (Cauca) : occupation RF, positions, couverture.
2. Veille périmètre / sécurité : détection et alerte sur appareils prioritaires (caméras, routeurs cellulaires, flottes, trackers BLE).
3. Inventaire d'un lieu : photographie RF à un instant T, diff entre deux visites.
4. Exploration / apprentissage.

## 2. Non-objectifs (ce que Sonde ne fera pas)

- Pas d'interception de trafic, pas de connexion aux appareils observés, pas de désauthentification.
- Pas d'identification de personnes. Les MAC aléatoires restent aléatoires.
- Pas de direction (azimut) déduite du RSSI. Un signal ne porte pas d'angle.
- Pas de dépendance à Google Play Services, pas de compte, pas de télémétrie.

## 3. Contrainte physique à accepter : la précision de position

Un téléphone seul mesure un RSSI, pas une distance. La position d'un émetteur se déduit de plusieurs observations prises depuis des points GPS différents. Ordre de grandeur honnête, en marchant autour de l'émetteur :

- Point d'accès Wi-Fi extérieur, 10+ observations, GPS à 5 m : erreur typique 10–25 m.
- BLE (portée courte, RSSI instable) : 5–15 m si on passe près, sinon « vu depuis ici » seulement.
- Une seule observation : position = celle du téléphone, avec un rayon d'incertitude, jamais un point.

Conséquence : chaque objet sur la carte porte un cercle d'incertitude visible, calculé, pas décoratif. « Assez précis » se gagne en marchant, pas en calculant. L'app doit guider ce mouvement (mode marche).

## 4. Fonctions v0.2 (périmètre fermé)

Carte
- Carte offline dans l'app : MapLibre Native Android (BSD-2), tuiles vectorielles OpenFreeMap. Emprise téléchargée depuis l'app quand il y a du réseau (voir §8), cache local.
- Position du téléphone, trace GPS de la session, objets Wi-Fi/BLE en marqueurs colorés par catégorie, cercle d'incertitude, tap = fiche détail.
- Position estimée d'un émetteur = centroïde pondéré par le signal de toutes ses observations géolocalisées ; recalculée en continu pendant la marche.

Mode marche
- Enregistre une observation par position (pas et temps), affiche en direct où le signal se renforce.
- Indicateur simple : « approche / éloigne » par émetteur sélectionné.

Sessions
- Une session = un lieu + une date. Liste, reprise, renommage, suppression.
- Diff entre deux sessions du même lieu : nouveaux, disparus, déplacés (au-delà de l'incertitude), changement de nom/sécurité.

Alertes
- Bip et surbrillance sur apparition d'un appareil prioritaire (déjà en v0.1). Ajout : liste blanche par session (ne plus alerter sur ce qui est connu du lieu).

Exports
- CSV WiGLE 1.4, JSON, débrief LLM (déjà). Ajout : GeoJSON (points + incertitude + trace) pour QGIS, et export/import de session complète pour le diff.

Fiabilité (préalable, non négociable)
- Premier build CI vert, test réel sur au moins un téléphone, correction du classifieur (règles sans recoupement, une source de vérité par fabricant), mise à jour de la table OUI.

## 5. Appareils cibles

Android 8+ (minSdk 26), plusieurs modèles possibles, aucun figé. Tout doit fonctionner sans Play Services (GPS natif, carte MapLibre). Bancs de test : vivo X Fold 5 (OriginOS) en premier, puis Xiaomi Redmi 14 (HyperOS). Les deux surcouches tuent agressivement les services en arrière-plan : l'app doit détecter l'optimisation batterie active et guider vers son désactivation (écran Session), et le service de scan doit se relancer seul s'il est tué.

## 6. Données

SQLite locale, schéma inspiré de WiGLE, étendu : table `session`, table `observation` (émetteur, horodatage, lat/lon/alt/précision, RSSI), position estimée et incertitude stockées par émetteur et par session. Jamais de position sans fix GPS de moins de 60 s.

## 7. Méthode

Une fonction par itération, dans cet ordre : fiabilité → carte + marqueurs → mode marche + estimation → sessions + diff → GeoJSON. Chaque itération se termine par un APK testé sur téléphone et un retour terrain avant la suivante. Ce document se met à jour à chaque décision.

## 8. Tuiles de carte (tranché)

Téléchargement de l'emprise dans l'app quand le téléphone a du réseau — avant, pendant ou après la mesure. Source : OpenFreeMap (tuiles vectorielles OSM, sans clé, sans quota), téléchargées directement sur le téléphone par l'app — aucune infrastructure côté Myrko. Moteur : MapLibre Native Android, dont le gestionnaire hors ligne télécharge une emprise (tuiles + style + polices) sur des niveaux de zoom choisis. Import MBTiles conservé en secours. Conséquences assumées :

- L'app obtient la permission Internet. Elle est confinée à un seul module (`map/`) ; scanners, base et exports ne touchent jamais au réseau, et l'écran Session affiche l'état réseau pour que ce soit visible.
- Le téléchargement est un choix explicite (bouton, emprise dessinée, niveaux de zoom, taille estimée), jamais automatique.
- Vectoriel = léger : une commune rurale entière tient en quelques dizaines de Mo jusqu'au zoom rue.

## 9. v0.3 — sonde LoRa externe

Le téléphone ne reçoit pas sous le GHz. Pour inventorier le 868/915 MHz, une tête de sonde externe : LilyGO T-Beam (ESP32 + SX1262 + GPS + batterie), reliée en BLE. Le téléphone reste la console (carte, base, exports) ; le device ne fait que mesurer et transmettre. Un device totalement autonome est écarté — il obligerait à refaire ce que le téléphone fournit.

- Mesures : balayage RSSI canal par canal (occupation et bruit de fond par fréquence), détection d'activité LoRa (CAD) et décodage des paquets aux paramètres courants (Meshtastic, LoRaWAN) — métadonnées seulement, comme pour le Wi-Fi.
- Protocole BLE simple, lignes texte horodatées ; le GPS du T-Beam peut remplacer celui du téléphone.
- Mode logger : posé seul sur un site, il enregistre 24 h+ et se vide en BLE au retour. C'est le seul cas « autonome » retenu.
- Le même T-Beam sert ensuite de nœud Meshtastic dans le village.
- Prérequis posé dès la v0.2 : une interface `Sensor` unique (Wi-Fi interne, BLE interne, sonde externe) qui alimente le même `observe()`.
