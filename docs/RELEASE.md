# Livraison et signature

## Clé de release
L'APK est signé en CI avec une clé de release stable (PKCS12), fournie par les secrets du dépôt
`RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
Sans ces secrets (fork, PR externe) l'APK sort non signé. La clé n'est jamais dans le dépôt.

Empreinte SHA-256 du certificat (à comparer avec `apksigner verify --print-certs`) :

    B5:FD:45:2A:9A:6D:DE:62:62:06:E1:A3:6A:A5:6C:99:25:87:F4:B9:78:F1:32:22:20:2A:1C:D9:07:65:AB:78

Avant la v0.1.3 les APK étaient signés avec une clé *debug* régénérée à chaque build : il faut
désinstaller une version antérieure avant d'installer une version signée.

## Deux canaux
- `latest` : recréé à chaque push sur `main`. Lien fixe
  `https://github.com/MyrkoF/androidradar/releases/download/latest/radar-latest.apk`.
- `vX.Y.Z` : release versionnée créée quand on pousse un tag `vX.Y.Z` ; notes = `fastlane/metadata/android/fr-FR/changelogs/<versionCode>.txt`.
  C'est ce canal que suivent IzzyOnDroid et F-Droid.

## Publier une version
1. Bumper `versionCode` (+1) et `versionName` dans `app/build.gradle.kts`.
2. Écrire `fastlane/metadata/android/{fr-FR,en-US}/changelogs/<versionCode>.txt`.
3. `git tag vX.Y.Z && git push origin main vX.Y.Z`.
