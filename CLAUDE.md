# TrackerControl + NetBird

Fork de TrackerControl (filtre de traqueurs Android, GPLv3) qui embarque le client NetBird (VPN maillé
WireGuard, client BSD-3) : un seul VPN Android, qui filtre les traqueurs ET fait partie du réseau NetBird
de Fabien (`*.vpn.hexonest.fr`), avec tous les pairs, le DNS et les politiques NetBird.

Contexte d'usage : Android n'autorise qu'un VPN à la fois ; Fabien veut garder TrackerControl et avoir
NetBird complet sur son téléphone (notamment pour joindre la PWA claudecode+ sur 100.102.79.78:7879).

## Langue
Répondre et documenter en français. Code et commentaires : suivre les conventions des dépôts en amont.

## Étude de faisabilité (2026-11-09) — ce qui est établi
Sources de référence clonées (peu profondes) dans `upstream/` :
- `upstream/tracker-control-android` (commit 4a3d193), `upstream/android-client` (client Android NetBird,
  a868fa0), `upstream/netbird` (e4fd231, moteur Go ; `go 1.26.0`).

### Point de branchement dans TrackerControl
- Couche C (héritée de NetGuard) : `app/src/main/jni/netguard/`. Les paquets autorisés et destinés au
  tunnel sont écrits dans un `socketpair(AF_UNIX, SOCK_DGRAM)` (`netguard.c` ~l.404,
  `write_wireguard_packet()` dans `netguard.h`, décision dans `ip.c` ~l.618 `wg_required`).
- Pont WireGuard actuel : `wgbridge-rs/` (Rust, gotatun de Mullvad), exposé par JNI
  `net/kollnig/missioncontrol/wgbridge/Wgbridge.java` : `startTunnel(uapiConfig, outboundRxFd, tunWriteFd,
  mtu, protector, logger, dnsRecorder)`. Il lit les paquets sortants sur `outboundRxFd` et écrit les
  paquets entrants directement dans le TUN (`tunWriteFd`). Ce pont a remplacé un ancien binding gomobile
  (Go) avec la même API : c'est la place du moteur NetBird.
- Tunnel partiel déjà géré : la politique (`wgbridge-rs/src/policy.rs`, `tc_policy_dest_tunnelled`,
  `set_allowed_ips`, chargée par `app/src/main/jni/netguard/policy.c` via dlopen de libwgbridge.so) ne met
  dans le tunnel que les destinations des AllowedIPs. Pour NetBird : AllowedIPs = plages et routes NetBird.
  ATTENTION : la politique vit dans libwgbridge.so ; la garder même si le transport devient NetBird.

### Contrat Android du moteur NetBird
- `upstream/netbird/client/android/client.go` : `NewClient(sdk, deviceName, uiVersion, tunAdapter,
  iFaceDiscover, networkChangeListener)`, `Run(...)` / `RunWithoutLogin(...)`.
- `TunAdapter` (`client/iface/device/adapter.go`) : `ConfigureInterface(address, addressV6, mtu, dns,
  searchDomains, routes) (fd int, err)`, `UpdateAddr`, `ProtectSocket(fd) bool`.
- POINT DUR : `client/iface/device/device_android.go` transforme le fd par
  `wgbackend.CreateUnmonitoredTUNFromFD` (wireguard-go forké, `tun/tun_linux.go`), qui fait
  `ioctl TUNGETIFF` → échoue sur un socketpair. Il faut un `tun.Device` Go maison au-dessus du socketpair
  de TrackerControl (lecture des paquets sortants, écriture des entrants dans le TUN) et un petit patch de
  `device_android.go` pour l'utiliser.
- DNS : NetBird configure son propre résolveur via ConfigureInterface (`dns`, `searchDomains`) ;
  TrackerControl a son filtrage DNS (`wgbridge-rs/tc-dns`, `jni/netguard/dns.c`). Il faudra relayer les
  domaines NetBird vers le résolveur NetBird.

### Environnement
- Présents : ~/Android/Sdk (NDK 29.0.13846066), OpenJDK 25, Rust + cibles Android.
- Manquants : Go 1.26 et gomobile (à installer dans ~/.local, sans sudo).
- Disque : surveiller l'espace (builds Android + Go + Rust) ; un disque plein a déjà bloqué la machine.

## Règles
- Ne jamais pousser ni publier quoi que ce soit (dépôts, stores) sans demande explicite de Fabien.
- Tester sur l'émulateur ou produire un APK ; l'installation sur le téléphone se fait par Fabien.
- Décision bloquante (choix d'architecture non tranché, licence, sécurité) : outil ask_user_important.
