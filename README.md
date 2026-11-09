# TrackerControl NetBird Edition

> **Fork non officiel.** Ce projet n'a aucun lien avec les projets TrackerControl ni NetBird et
> n'est pas approuvé par eux. « TrackerControl » et « NetBird » désignent leurs projets d'origine ;
> ils sont cités uniquement pour indiquer la provenance du code.

Fork de [TrackerControl](https://github.com/TrackerControl/tracker-control-android) (GPLv3) qui
embarque le moteur du client [NetBird](https://github.com/netbirdio/netbird) (BSD-3) à la place de
son pont WireGuard : un seul VPN Android qui filtre les traqueurs et rejoint un réseau NetBird
(pairs, DNS et politiques NetBird).

Code source : https://github.com/Hexonest-SAS/trackercontrol-netbird-edition

## Licence et modifications

Cette œuvre est une version modifiée de TrackerControl et du client NetBird, distribuée sous la
licence **GNU GPLv3** pour l'ensemble (GPLv3 §5).

- **Modifications signalées** : toutes les modifications des sources en amont sont isolées dans des
  commits préfixés `trackercontrol:` ou `netbird:`. Les lister avec
  `git log -- trackercontrol/ netbird/` (ou `git log --oneline -- netbird/` par composant).
- **Date de début des modifications** : 2026-11-09.
- **TrackerControl** : GPLv3, notices et droits d'auteur d'origine conservés.
- **Client NetBird** : licence BSD-3-Clause, conservée avec sa notice (voir `netbird/LICENSE`) ;
  elle reste applicable à ce code, qui est redistribué dans l'ensemble sous GPLv3.
- Aucune garantie, dans la mesure permise par la loi (voir le texte de la GPLv3).

## Structure

| Dossier | Contenu |
|---|---|
| `trackercontrol/` | TrackerControl en amont (`git subtree`), plus nos patchs |
| `netbird/` | NetBird en amont (`git subtree`), plus nos patchs (moteur Go compilé par gomobile) |
| `scripts/` | outils du fork (`env.sh` : chemins Go, gomobile, SDK, NDK) |
| `upstream/` | clones de référence de l'étude, ignorés par git |

## Versions en amont

Source de vérité : `upstream-versions.env` (sourçable par bash), qui note les dernières versions
**publiées** (releases non pre-release) déjà incluses.

| Composant | Dépôt | Version suivie |
|---|---|---|
| TrackerControl | `https://github.com/TrackerControl/tracker-control-android.git` | release `2026110101` ; import actuel : commit `4a3d1939` de main (postérieur à cette release) |
| NetBird | `https://github.com/netbirdio/netbird.git` | `v0.82.0` (`3c00a9787`) |

Le commit « Squashed '<dossier>/' content from commit … » de chaque import note le commit en amont.

## Patchs

Chaque modification des sources en amont fait l'objet d'un commit séparé, préfixé
`trackercontrol:` ou `netbird:`, pour être relue et reportée lors d'une mise à jour.
Lister les patchs d'un composant depuis son dernier import :

```sh
git log --oneline -- netbird/
```

## Mettre à jour l'amont

```sh
scripts/update-upstream.sh all --dry-run   # versions actuelle / dernière publiée, sans rien modifier
scripts/update-upstream.sh [tc|nb|all]     # fusionne (git subtree pull --squash) les releases plus récentes
```

Le script lit `upstream-versions.env`, interroge la dernière release publiée de chaque projet,
fusionne l'étiquette si elle est plus récente (un commit de fusion, puis un commit séparé pour
`upstream-versions.env`). Codes de sortie : 0 mis à jour, 10 rien à faire, 20 conflit (état de
fusion laissé pour inspection), 1 erreur. Les conflits avec nos patchs se règlent comme une fusion
ordinaire.

Le workflow `.github/workflows/upstream.yml` le lance chaque jour (et à la demande) : sans nouveauté
il s'arrête ; en cas de conflit il ouvre un ticket ; sinon il compile (AAR NetBird, tests, APK
fdroidDebug, `cargo test --lib`) et ouvre une PR `upstream/<projet>-<étiquette>` vers main.

## Compiler

Outils (installés sans sudo, voir `scripts/env.sh`) : Go 1.26.10, gomobile et gobind épinglés sur
la version `golang.org/x/mobile` du `go.mod` de NetBird, Android SDK (plateforme 37.0), NDK
29.0.14206865 (épinglé par TrackerControl), Rust 1.95.0 et cargo-ndk 4.1.2
(`trackercontrol/scripts/setup_rust_android.sh`), OpenJDK 25.

```sh
. scripts/env.sh
cd trackercontrol
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleFdroidDebug
# APK : app/build/outputs/apk/fdroid/debug/
```

## Installer

Android n'autorise qu'un seul VPN actif à la fois : cette application remplace d'autres clients
VPN (dont l'application NetBird officielle). Installer l'APK produit (par exemple avec
`adb install -r <apk>`), ouvrir l'application, accepter la demande d'autorisation VPN, puis
ouvrir l'onglet VPN pour se connecter à son serveur de gestion NetBird (URL du serveur, clé
d'installation ou connexion). Les services du réseau NetBird sont ensuite joignables par leur
adresse d'overlay ou leur nom DNS NetBird.

### Mettre à jour sans créer de nouveau pair

- Les APK de développement sont signés avec la clé de debug de la machine de build
  (`~/.android/debug.keystore`). Android refuse une mise à jour signée par une autre clé :
  conserver ce fichier pour pouvoir mettre à jour sur place.
- Toujours mettre à jour sur place (`adb install -r`) : les données de l'app, donc la
  configuration NetBird (`files/netbird/netbird.cfg`) et sa clé WireGuard, sont conservées et
  l'appareil reste le même pair. Désinstaller ou effacer les données crée un nouveau pair
  (doublon à supprimer dans l'interface NetBird).
- Paquet de développement : `fr.hexonest.trackernetbird.fdroid.test` (« TrackerControl NetBird
  Edition »), installable à côté de TrackerControl.
- Vérifier le routage après une mise à jour : `adb shell ip route get <IP d'un pair> uid <uid
  d'une app>` doit passer par `tun*` avec l'adresse NetBird en source. Le shell adb et
  l'application elle-même sont exclus du VPN : leurs propres tests réseau ne prouvent rien.
- Se connecter par SSO avec le VPN de l'application activé : sinon Android peut geler
  l'application pendant que le navigateur est au premier plan, et le retour de connexion échoue.
