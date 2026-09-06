# Sonde — relevé RF passif (Wi-Fi + BLE) pour Android

Outil terrain, hors ligne, sans permission Internet. Observe les métadonnées publiquement diffusées
(balises Wi-Fi, annonces BLE), les horodate et les géolocalise, classe les fabricants, et exporte
en CSV WiGLE / JSON / débrief texte pour analyse par un LLM.

Aucune interception de trafic. Aucune connexion aux appareils observés. Aucune télémétrie.

## Compiler

**Via GitHub Actions (aucun outillage local)**
1. Créer un dépôt GitHub, y pousser ce dossier.
2. Onglet *Actions* → workflow *Build APK* → l'APK est dans les artefacts (`sonde-debug-apk`).

**Via Android Studio** : ouvrir le dossier, *Build → Build APK(s)*.

**En ligne de commande** (SDK Android installé, `ANDROID_HOME` défini) : `./gradlew assembleDebug`

## Installer

Sideload `app-debug.apk` (autoriser les sources inconnues). Android 8+ requis.

Au premier démarrage, accorder : **localisation précise** (exigée par Android pour tout scan
Wi-Fi/BLE, et utilisée pour géolocaliser les relevés), **appareils à proximité** (Android 12+),
**notifications** (Android 13+, pour le service de premier plan).

Recommandé : désactiver l'optimisation batterie pour l'app, et dans *Options développeur*,
désactiver **« Limitation du scan Wi-Fi »** (Android 9+ plafonne sinon à 4 scans / 2 min ;
l'app affiche `throttle` quand elle est bridée et lit alors le cache système).

## Utiliser

- **Wi-Fi** et **BLE** se lancent indépendamment (chips en haut). **Bip** = alerte sonore à la
  découverte d'un appareil prioritaire.
- **Liste** : tri prioritaires puis signal ; filtres ; toucher une ligne pour le détail.
- **Radar** : le rayon est le signal (RSSI), l'angle est arbitraire mais stable par adresse.
  Un RSSI ne contient pas de direction — l'affichage ne prétend pas le contraire.
- **Session** : exports (partage système → Drive, Signal, fichier…), journal, nouvelle session.

Le CSV suit le format WiGLE 1.4 : importable sur wigle.net et lisible par les outils du même écosystème.
La base SQLite locale (`sonde.db`, schéma inspiré de WiGLE) accumule toutes les sessions.

## Classification

`data/Classifier.kt` : une regex par catégorie sur le nom du fabricant (table OUI Wireshark
embarquée, `assets/oui.tsv`). À enrichir au fil des relevés — c'est volontairement lisible.

Catégories : routeur cellulaire, flotte/télématique, caméra, infra réseau, industriel/IoT pro,
grand public, MAC aléatoire, inconnu. Prioritaire = les cinq premières.

## Limites connues (v0.1)

- Adresses BLE aléatoires (la majorité des téléphones) : pas de fabricant via OUI ; on utilise
  l'identifiant d'entreprise Bluetooth SIG quand il est présent dans l'annonce (table partielle).
- Table OUI datée de 2022 : remplacer `assets/oui.tsv` (format `préfixe<TAB>court<TAB>long`)
  à partir du fichier `manuf` de Wireshark pour la mettre à jour.
- Pas de carte : les positions sont dans les exports.

## Licence et attributions

Code : MIT. Format CSV et schéma de base inspirés de WiGLE WiFi Wardriving (BSD-3-Clause).
Table OUI : fichier `manuf` du projet Wireshark (GPL-2.0, données factuelles).
