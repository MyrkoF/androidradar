# Sonde — note de reprise de session

Écrite le 2026-09-06 pour reprendre le travail dans une nouvelle session Cowork avec le dépôt GitHub attaché.

## Où en est le projet

Sonde est une app Android (Kotlin / Jetpack Compose, package `org.equalium.sonde`, minSdk 26) de relevé RF passif Wi-Fi + BLE avec GPS, base SQLite au schéma WiGLE, classification par fabricant (table OUI Wireshark embarquée), exports CSV WiGLE 1.4 / JSON / débrief LLM. Version actuelle : **v0.1, jamais compilée ni exécutée**. ~1 340 lignes Kotlin, 16 fichiers.

Le code vit dans le dossier connecté du PC tx01 : `~/DATA/Common documents/Projects/AndroidRadar/sonde/`. Il n'a jamais été poussé sur GitHub. Le dépôt `MyrkoF/androidradar` (public) existe, vide.

Le cahier des charges v0.2 (rév. 4) est dans `sonde/docs/CAHIER_DES_CHARGES.md` et dans le projet Drone (`claude/Sonde_cahier_des_charges_v0.2.md`). Il est validé par Myrko ; c'est la boussole.

## Décisions prises (ne pas rouvrir)

- Usages, tous : site survey LoRa (Cauca), veille périmètre, inventaire avec diff, exploration.
- Priorité v0.2 : carte offline réelle avec objets Wi-Fi/BLE positionnés, cercle d'incertitude calculé, mode marche pour affiner. Pas d'azimut inventé.
- Carte : MapLibre Native Android + tuiles vectorielles OpenFreeMap, téléchargées sur le téléphone depuis l'app (avant, pendant ou après la mesure). Permission Internet acceptée, confinée au module `map/`. Aucune infrastructure serveur côté Myrko — ne rien proposer sur son VPS.
- Téléphones de test : vivo X Fold 5 en premier, Xiaomi Redmi 14 ensuite. Pas de Play Services requis.
- v0.3 : tête de sonde LoRa externe (LilyGO T-Beam, ESP32 + SX1262 + GPS) reliée en BLE, mode logger autonome sur site. Prévoir dès la v0.2 une interface `Sensor` commune (Wi-Fi interne, BLE interne, sonde externe → même `observe()`).
- Méthode : cahier des charges d'une page + itérations courtes, une fonction par itération, APK testé sur téléphone entre chaque. Ordre : fiabilité → carte + marqueurs → mode marche + estimation → sessions + diff → GeoJSON.
- Livraison APK : à chaque push sur `main`, le workflow publie une Release `latest` ; lien fixe `https://github.com/MyrkoF/androidradar/releases/download/latest/sonde-latest.apk`. Le workflow est déjà écrit (`.github/workflows/build.yml`).
- Myrko reproche à la v0.1 d'avoir été construite sans recueil du besoin : ne plus coder une fonction sans qu'elle soit dans le cahier des charges.

## Ce qui reste à faire, dans l'ordre

1. Pousser le code sur `MyrkoF/androidradar` (branche `main`). Depuis la nouvelle session : stager le dossier `sonde/` depuis le PC, le copier dans le clone du dépôt, commit, push.
2. Vérifier le premier build Actions. S'il casse, corriger la v0.1 telle quelle avant toute autre modification.
3. Itération fiabilité : classifieur sans recoupements (aujourd'hui `teltonika`, `calamp`, `milesight`, `espressif` sont dans deux catégories, et `huawei`/`netgear`/`mikrotik` dépendent de sous-regex) ; mettre à jour `assets/oui.tsv` depuis le fichier `manuf` de Wireshark ; ajouter la détection de l'optimisation batterie et un guide dans l'écran Session ; rendre le service de scan résilient (relance s'il est tué — OriginOS et HyperOS sont agressifs).
4. Premier test réel sur le vivo : Wi-Fi + BLE lancés, écran éteint 10 min, vérifier que le compteur monte et que le journal n'a pas de trou. Réglages vivo à faire avant : Options développeur → Limitation du scan Wi-Fi désactivée ; Batterie → consommation élevée en arrière-plan autorisée ; Infos de l'app → Batterie sans restriction ; Démarrage automatique activé ; app verrouillée dans les récents.
5. Ensuite seulement : itération carte (MapLibre + OpenFreeMap + téléchargement d'emprise).

## Structure du code (repères)

- `scan/WifiScanner.kt` (tick 8 s, détecte le throttling), `scan/BleScanner.kt`, `scan/GpsTracker.kt`, `scan/ScanService.kt` (premier plan, wakelock, notification).
- `data/ScanRepository.kt` : singleton, source de vérité, `observe()` point d'entrée unique des scanners, bip sur appareil prioritaire.
- `data/Classifier.kt`, `data/Oui.kt`, `data/Model.kt`, `data/Db.kt`.
- `export/Exporter.kt`, `ui/App.kt` (3 onglets : Liste, Radar, Session).

## Contraintes d'environnement apprises

- Le sandbox cloud n'atteint pas dl.google.com ni Maven Central : pas de compilation Android en local, tout passe par GitHub Actions.
- L'accès GitHub d'une session ne couvre que les dépôts attachés à sa création. Créer la tâche avec `MyrkoF/androidradar` sélectionné.
- Le navigateur intégré de l'app est connecté à GitHub (session Myrko) : utilisable pour l'interface web si besoin.
