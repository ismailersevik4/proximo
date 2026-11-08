# Proximo

Messagerie Android **sans Internet** : les téléphones se parlent directement en Bluetooth basse consommation et en Wi-Fi direct (Wi-Fi Aware), et chaque téléphone relaie les messages des autres (réseau maillé).

Conçu et développé par **Ismail Ersevik**.

## Ce que fait l'appli (version 1.3.5, APK 13)

- **Radar** : les téléphones Proximo à portée, avec distance approximative, ceux déjà en contact et les inconnus (« ? »).
- **Contacts** : ajout depuis le radar, par QR code ou par code `PRX-XXXX-XXXX` (l'ajout se fait dès que les deux téléphones sont à portée).
- **Messages privés chiffrés** de bout en bout (X25519 + AES-GCM, signés Ed25519), statuts « En attente → Relayé par… → Reçu ».
- **Photos** (compressées), **talkie-walkie** (vocal, lu automatiquement à l'arrivée), **position**, **SOS** prioritaire à tout le monde.
- **Groupes** (clé de groupe partagée) et canal **« Tout le monde »** (public, à portée).
- **Appels vocaux** (bêta) chiffrés de bout en bout, en Wi-Fi direct, en direct ou via un voisin relais (2 sauts).
- **Vidéos courtes** (15 s, 3,5 Mo max), uniquement par Wi-Fi direct, un relais au maximum.
- **Messages en attente** : ce qui ne peut pas partir est gardé et part dès qu'un voisin apparaît.
- **Mises à jour** depuis l'appli : par Internet (Releases GitHub) ou **de téléphone à téléphone** par Wi-Fi direct, avec secours Bluetooth (lent, reprise automatique).
- **Suppression** de messages et effacement de discussions (sur le téléphone seulement) ; les messages en transit sont gardés par les relais 14 jours (petits messages), 3 jours (photos), 24 h (vidéos), plafond 300 Mo ; 30 jours chez l'expéditeur. Date complète et mention « reçu le… » pour les messages arrivés en retard, fiche détail au toucher.
- **Photos de profil** échangées entre téléphones, **notification vivante** (téléphones à portée, messages en attente).
- Modes **Normal / Priorité portée / Priorité batterie**.

Android 8 et plus. Interface en français.

## Installer

Télécharger `Proximo.apk` dans [Releases](https://github.com/ismailersevik4/proximo/releases/tag/android), l'ouvrir sur le téléphone et accepter l'installation. Ensuite, l'appli propose elle-même les nouvelles versions.

## Compilation

GitHub Actions compile et signe l'APK à chaque modification (`.github/workflows/apk.yml`). La clé de signature est dans `signing/proximo.jks.enc`, chiffrée ; seul le secret `ANDROID_KEYSTORE_PASS` permet de l'ouvrir.

Pour une nouvelle version : augmenter `appVersionCode` (+1) et `appVersionName` dans `app/build.gradle.kts`.

## Organisation du code

- `core/` : identité et clés, chiffrement, base de données, réglages.
- `mesh/` : trames, Bluetooth (`BleTransport`), Wi-Fi Aware (`AwareTransport`), relais et file d'attente (`MeshCore`), service de premier plan.
- `msg/` : messagerie (contacts, groupes, messages, notifications).
- `call/` : appels vocaux directs.
- `update/` : mises à jour (Internet et voisin à voisin).
- `ui/` : écrans Jetpack Compose.
