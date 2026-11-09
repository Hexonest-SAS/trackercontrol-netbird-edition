# TrackerControl + NetBird

Fork personnel de [TrackerControl](https://github.com/TrackerControl/tracker-control-android)
(GPLv3) qui embarque le moteur du client [NetBird](https://github.com/netbirdio/netbird) (BSD-3)
à la place de son pont WireGuard : un seul VPN Android qui filtre les traqueurs et rejoint le
réseau NetBird. Usage personnel, rien n'est publié (voir `CLAUDE.md`).

Licence de l'ensemble : GPLv3 (celle de TrackerControl ; le code NetBird garde sa licence BSD-3).

## Structure

| Dossier | Contenu |
|---|---|
| `trackercontrol/` | TrackerControl en amont (`git subtree`), plus nos patchs |
| `netbird/` | NetBird en amont (`git subtree`), plus nos patchs (moteur Go compilé par gomobile) |
| `scripts/` | outils du fork (`env.sh` : chemins Go, gomobile, SDK, NDK) |
| `upstream/` | clones de référence de l'étude, ignorés par git |

## Versions en amont

| Composant | Dépôt | Version importée |
|---|---|---|
| TrackerControl | `https://github.com/TrackerControl/tracker-control-android.git` | `4a3d1939` (main, 2026-11-09) |
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
git subtree pull --prefix=netbird https://github.com/netbirdio/netbird.git vX.Y.Z --squash
git subtree pull --prefix=trackercontrol https://github.com/TrackerControl/tracker-control-android.git <commit> --squash
```

`git subtree pull` fusionne la nouvelle version avec nos patchs ; les conflits se règlent comme
une fusion ordinaire. Mettre à jour le tableau des versions ci-dessus.

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

## Installer sur le téléphone

Signature : clé de debug de la machine de build (`~/.android/debug.keystore`, alias
`androiddebugkey`, SHA-256 `dd2dcbbf…ef1fb1`). Décision D5 : on reste sur cette clé tant que
l'app ne quitte pas le téléphone de Fabien. **Sauvegarder ce fichier** : sans lui, plus aucune
mise à jour sur place n'est possible (Android refuse une signature différente).

```sh
# APK : build/apk/TrackerControl-NetBird-<commit>-debug.apk (copie de
# trackercontrol/app/build/outputs/apk/fdroid/debug/TrackerControl-fdroidDebug-latest.apk)
adb -s <téléphone> install -r build/apk/TrackerControl-NetBird-<commit>-debug.apk
```

- Toujours `install -r` (mise à jour sur place) : les données de l'app sont conservées, donc la
  configuration NetBird (`files/netbird/netbird.cfg`) et sa clé WireGuard : le téléphone reste le
  même pair NetBird. Ne jamais désinstaller ni `pm clear` : une nouvelle installation crée un
  nouveau pair (doublon à supprimer dans l'interface NetBird).
- Paquet : `fr.hexonest.trackernetbird.fdroid.test` (« TrackerControl NetBird »), installable à
  côté de TrackerControl ; un seul VPN actif à la fois sous Android.
- Vérification après mise à jour : `adb shell ip route get 100.102.79.78 uid <uid d'une app>`
  doit passer par `tun*` avec l'adresse NetBird en source (le shell adb et l'app elle-même sont
  exclus du VPN : leurs propres tests réseau ne prouvent rien).
- Se connecter par SSO avec le VPN de l'app activé : sinon Android peut geler l'app pendant que
  le navigateur est au premier plan et le retour de connexion échoue.
