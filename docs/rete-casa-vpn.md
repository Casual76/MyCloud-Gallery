# Rete, casa, VPN di casa e Wake-on-LAN (`core-network`, pacchetto P2)

> Cosa fa il modulo `:core-network`, perché lo fa così e cosa resta da provare con un telefono in mano.
> Fonti: `docs/fase2-progetto.md` (§0 D1, D2, D5; §1.2; §2.4; §2.5; §3.2; §3.5), `docs/android.md` (scoperta 13, tema 7,
> T-17/T-18/T-19), `docs/research/smb-vpn.md` (§3, §4, §7 M10/M11/M13/M14), `docs/decisioni.md`.
> Le reti degli esempi e dei test sono **inventate** (sottorete di fabbrica del FRITZ!Box `192.168.178.0/24`, ULA
> `fd12:3456:789a::/48`, globali `2001:db8::/32`): nessun indirizzo di una rete vera compare qui o nei test. È vera la
> *forma* misurata sul telefono (`spike/results/nasprobe*`).

## 1. In una pagina

```
Android                    logica pura (JVM)                              cosa esce
────────                   ─────────────────                              ─────────
4 NetworkCallback ──►  NetworkObservation (LinkFacts)  ─┐
HomeFingerprintStore ─────────────────────────────────  ├─► NetworkPolicy ─┬─► NetworkSnapshot  (luogo, indicatore)
LocalNetworkPermission ───────────────────────────────  ┘                  ├─► PathDecision ──► NasRouteGate ──► NasRoute
                                                                           ├─► NetworkState  (motore delle regole)
                                                                           └─► voci di Diagnostica
```

- **Una sola funzione decide**: `NetworkPolicy.evaluate(osservazione, impronta, permesso, istante)`. È pura: niente
  Android, niente tempo, niente I/O. Tutti i casi (casa, amico, hotspot, VPN di terzi...) si provano su reti sintetiche
  (`LinkFactsSamples`).
- **Lo strato Android è sottile** (`AndroidNetworkObserver`, `NetworkSocketBinding`, `AndroidWireGuardPlatform`,
  `AndroidLocalNetworkPermission`, `NsdNasDiscovery`, `AndroidMagicPacketSender`): traduce e basta.
- **Il cancello** (`DefaultNasRouteGate`) è l'unico a creare un `NasRoute.Allowed`. Con `Denied` il tipo non porta nessun
  socket: **non parte nemmeno la connessione TCP** (E-S10).
- Niente Hilt (D7): `:app` costruisce gli oggetti (§12). Nessun permesso nuovo: `INTERNET`, `ACCESS_NETWORK_STATE` e
  `CONTROL_TUNNELS` + `<queries>` li ha già messi il P0.

## 2. Le reti che si osservano

`AndroidNetworkObserver` registra **quattro** `NetworkCallback` (il brief ne chiedeva tre; il quarto è una scelta, §14):

| # | Richiesta | Perché |
|---|---|---|
| 1 | rete **predefinita** del sistema | con la VPN accesa è la VPN; dice anche quale rete è "la predefinita" |
| 2 | **Wi-Fi o Ethernet** con `NOT_VPN` | il Wi-Fi *sotto* la VPN resta visibile anche col tunnel su (T-17): da qui si riconosce casa |
| 3 | **cellulare** con `NOT_VPN` e `INTERNET` | con una VPN sul cellulare la predefinita è la VPN: serve la rete vera (roaming, a consumo, IPv6). `INTERNET` esclude la rete solo-IMS |
| 4 | **VPN** (`TRANSPORT_VPN`, senza `NOT_VPN`) | rotte (`AllowedIPs`), DNS, indirizzi e dominio del tunnel |

- Si usa `registerNetworkCallback`: **non tiene accesa nessuna rete e non fa traffico**. Servono solo
  `ACCESS_NETWORK_STATE` e `INTERNET`. **Nessun permesso di posizione, quindi nessun SSID.**
- Una rete entra nell'osservazione solo quando si conoscono sia le capacità sia le proprietà del collegamento (Android le
  consegna in due callback): una rete a metà darebbe un falso "senza indirizzi".
- `LinkFacts` tiene: trasporti (senza la VPN), `isVpn`, a consumo, roaming (solo cellulare), validata, captive portal,
  congestionata/sospesa (Android 11+), indirizzi con prefisso, rotte (solo unicast, dove Android lo dice), DNS, domini,
  server DHCP. **Vive solo in memoria e il suo `toString` non stampa un indirizzo** (§4.2).
- La forma tipica di un Wi-Fi di casa (misurata): dominio `fritz.box`; DNS = IPv4 del gateway + IPv6 del router (ULA e
  globale); indirizzi link-local, ULA e globali `/64`, ognuno in due varianti (stabile e temporanea); rotta predefinita
  IPv4 col gateway.

## 3. Riconoscere casa senza posizione

`HomeMatcher.match(rete, impronta)` → `HomeMatch`:

| Risultato | Quando | Vale come casa? |
|---|---|---|
| `Match` | un indirizzo **ULA** della rete sta in un prefisso ULA /48 dell'impronta | **sì, solo questo** |
| `Weak(indizi)` | niente ULA in comune, ma coincidono gateway IPv4, sottorete IPv4 o dominio | no: "somiglia a casa" |
| `NoMatch` | non coincide niente | no |
| `Unknown` | non c'è impronta, o non c'è un Wi-Fi/Ethernet da confrontare (cellulare, VPN) | no |

**Perché solo l'ULA.** Il prefisso IPv6 locale è generato a caso da ogni FRITZ!Box; gateway, sottorete e `fritz.box` sono
uguali in mezza Italia. Il FRITZ!Box di un amico con la stessa sottorete ha tutti e tre gli indizi deboli e un ULA diverso:
è `Weak`, quindi **negato** (E-S10).

Cautele (fail-closed):
- l'impronta si legge con prudenza (`HomePrefixes`): conta solo un **ULA** lungo da /48 a /64. `fc00::/7`, `fd00::/8`, un
  prefisso globale o testo malformato si **scartano**, mai si allargano: una configurazione sbagliata non può far "essere a
  casa" ovunque;
- `HomeFingerprints.fromCandidate` rifiuta un candidato senza ULA (`canBeConfirmed`);
- l'ULA non è autenticato (un host sulla LAN può annunciarne uno con un RA falso): per questo la **conferma finale è il
  GUID del NAS**, letto dalla NEGOTIATE prima di qualunque autenticazione (core-nas). L'ULA riduce le occasioni, il GUID
  chiude il caso.

**Impronta** (`HomeFingerprintStore` → `DataStoreHomeFingerprintStore`): DataStore in `noBackupFilesDir` (né backup né
trasferimento tra dispositivi la copiano). Si salva e si rilegge **validata**: al massimo 4 ULA, gateway solo se è un IPv4
letterale, dominio normalizzato; senza nessun ULA valido non c'è impronta. Un file danneggiato riparte vuoto. Il valore in
memoria segue subito `save`/`clear`.

**Raccolta** (`ObservedHomeCapture`): legge il candidato dal Wi-Fi/Ethernet attuale (la predefinita se è una LAN, altrimenti
la validata); mai una rete dietro una pagina di accesso. Funziona anche con la VPN accesa (il Wi-Fi sotto si vede), ma il
percorso dell'onboarding no (§5).

### Casi

| Situazione | Esito |
|---|---|
| Wi-Fi di casa | `Match` → casa |
| FRITZ!Box di un amico (stessa sottorete, gateway, dominio; ULA diverso) | `Weak(3 indizi)` → **negato** (E-S10) |
| Casa dopo il reset del router (ULA nuovo) | `Weak` → negato, e la Diagnostica propone la **nuova raccolta** (E-R16) |
| Casa con l'IPv6 spento sul router (nessun ULA) | `Weak` → negato; l'onboarding non conferma "casa" senza ULA |
| Un bar, un hotspot | `NoMatch` |
| Tutto ciò che sta in un /48 ULA diverso per un solo bit | non coincide |

**E-R16 (ULA cambiato).** L'utente, a casa, ripete la raccolta (`ObservedHomeCapture` → conferma → `HomeFingerprints` →
`HomeFingerprintStore.save`); il GUID del NAS deve essere lo stesso (lo verifica core-nas); dopo l'autenticazione la
configurazione firmata deve portare il nuovo ULA, che aggiorna l'admin (P3/P4). Qui c'è solo la parte di rete.

## 4. La VPN di casa (D2, E-S16)

Non basta una VPN qualsiasi. `VpnClassifier` guarda cosa porta il tunnel:

| Indizio | Forte? | Cos'è |
|---|---|---|
| `ULA_ROUTE` | sì | una rotta del tunnel (`AllowedIPs`) copre il /48 ULA di casa (rotte di almeno /48) |
| `ULA_DNS` | sì | un DNS del tunnel sta nel /48 di casa |
| `ULA_ADDRESS` | sì | un indirizzo del tunnel sta nel /48 di casa |
| `SUBNET_ROUTE` | no | una rotta **non predefinita**, almeno /16, contiene il gateway IPv4 di casa |
| `GATEWAY_DNS` | no | il DNS del tunnel è il gateway IPv4 di casa |
| `DOMAIN` | no | il dominio del tunnel è quello di casa (`fritz.box`) |

Regola, in ordine: senza VPN → `NONE`; senza impronta (o senza ULA valido) → `UNKNOWN`; **un indizio forte** →
`HOME_TUNNEL`; il tunnel porta un ULA ma **di un'altra casa** → `FOREIGN_VPN`; **almeno due indizi deboli** diversi →
`HOME_TUNNEL`; altro → `FOREIGN_VPN`.

- La rotta predefinita **non è mai un indizio** (ogni tunnel completo ce l'ha); neanche le due metà di Internet
  (`0.0.0.0/1`, `128.0.0.0/1`) né un `/8`.
- La configurazione di fabbrica del FRITZ!Box (rotta predefinita + DNS del router + `fritz.box`) ha due indizi deboli:
  vale come casa. La configurazione della guida del passo 11 (`AllowedIPs` = sottorete di casa + ULA) ne ha di forti.
- Il tunnel verso il FRITZ!Box **di un amico** (stessa sottorete e dominio, ULA diverso) è `FOREIGN_VPN`.
- **Residuo noto**: due soli indizi deboli non distinguono la propria casa da un FRITZ!Box altrui *senza ULA nel tunnel*.
  Nessuna password parte lo stesso: il GUID del NAS lo ferma (D3). Per questo la guida del passo 11 chiede di includere
  l'ULA nelle `AllowedIPs`.
- `FOREIGN_VPN` e `UNKNOWN` si trattano uguale: **negato** (`DenyReason.FOREIGN_VPN`), anche a casa — il tunnel cattura il
  traffico dell'app, quindi il NAS non si contatta (E-S16).
- Con più VPN si giudica quella predefinita, altrimenti una di casa.

## 5. Il cancello e il socket legato (D1)

### Luogo e percorso

`NetworkPolicy` ricava il luogo (`NetPlace`, i testi dell'indicatore del §3.5) e il percorso insieme. Si applicano **in
ordine**:

| # | Condizione | Luogo | Percorso |
|---|---|---|---|
| 1 | nessuna rete | `OFFLINE` | `Denied(OFFLINE)` |
| 2 | rete sottostante dietro una pagina di accesso | `OFFLINE` | `Denied(CAPTIVE_PORTAL)` |
| 3 | nessuna impronta di casa | `UNTRUSTED` | `Denied(NOT_CONFIGURED)` |
| 4 | VPN estranea o sconosciuta | `UNTRUSTED` | `Denied(FOREIGN_VPN)` |
| 5 | VPN di casa, sottostante non validata | `OFFLINE` | `Denied(OFFLINE)` |
| 6 | VPN di casa, **casa** (`Match`) | `HOME` | `Allowed(HOME_LAN_THROUGH_VPN)`, legato alla **VPN** |
| 7 | VPN di casa, fuori casa | `ROAMING` / `AWAY_VPN_WIFI` / `AWAY_VPN_MOBILE` | `Allowed(VPN)`, legato alla **VPN** |
| 8 | niente VPN, **casa**, rete locale negata | `HOME` | `Denied(LOCAL_NETWORK_DENIED)` |
| 9 | niente VPN, **casa** | `HOME` | `Allowed(HOME_LAN)`, legato al **Wi-Fi** |
| 10 | niente VPN, non validata | `OFFLINE` | `Denied(OFFLINE)` |
| 11 | niente VPN, `Weak` (somiglia a casa) | `UNTRUSTED` | `Denied(UNTRUSTED_NETWORK)` (E-S10, E-R16) |
| 12 | niente VPN, altro | `VPN_OFF` | `Denied(VPN_OFF_AWAY)` (E-R3) |

- **Casa con la VPN accesa → binding VPN**, non Wi-Fi: WireGuard non chiama `allowBypass()`, quindi un socket legato al
  Wi-Fi sarebbe comunque catturato dal tunnel (docs/android.md, scoperta 13). Dire "VPN" è onesto; se l'automazione la
  spegne, la rete del legame cambia e core-nas invalida la sessione.
- **Casa vale anche senza internet** (riga 9 prima della 10): il NAS è in LAN. Un router senza WAN non deve togliere il NAS.
  Non vale per una pagina di accesso (riga 2).
- Roaming e "Wi-Fi a consumo" contano solo con la VPN accesa: **ROAMING** = cellulare in roaming; **AWAY_VPN_MOBILE** =
  cellulare o Wi-Fi a consumo (hotspot di un altro telefono, E-R13). Il Wi-Fi di casa marcato "a consumo" resta `HOME`.
- **Rete locale negata**: nega il solo percorso LAN diretto. Se un indirizzo privato *dentro il tunnel* conti come "locale"
  non è documentato (T-19): la VPN non si nega in anticipo; se poi la TCP va in timeout, core-nas controlla il permesso
  *prima* di dire "NAS spento" (E-R5).

### Il socket

`NetworkSocketBinding`: `network.socketFactory.createSocket()` **senza argomenti** (nasce non connesso, già legato) e poi
`connect(InetSocketAddress(ipLetterale, 445), tempoMassimo)`. Se la connessione fallisce il socket si chiude.
**Mai** `bindProcessToNetwork` (legherebbe tutto il processo: se la VPN cade il traffico scivolerebbe sul Wi-Fi di una rete
estranea), **mai** `createSocket(host, port)` (risolve il nome e si connette senza tempo), **mai** DNS (E-R7).
`ArchitectureGuardTest` lo scrive nel codice: una modifica futura che lo faccia rompe un test.

### Il valore di `routes`

`DefaultNasRouteGate.routes` cambia **solo quando cambia la sostanza** (via, rete del legame, motivo): l'istanza di
`Allowed` resta la stessa finché il percorso non cambia, così core-nas confronta `binding.networkId` e invalida al primo
cambio. `current()` non si fida dell'ultimo valore: rivaluta con gli ultimi dati.

### Onboarding (`onboardingRoute`)

Prima dell'impronta il percorso è il Wi-Fi da cui è stato letto il candidato, **senza VPN**. È `Denied` se: il Wi-Fi non è
più quello (`UNTRUSTED_NETWORK`); pagina di accesso; **c'è una VPN accesa** (`FOREIGN_VPN`: senza impronta non si sa di chi
sia, e il tunnel cattura anche un socket legato al Wi-Fi — la UI chiede di spegnerla per quel passo); il candidato è
cambiato dopo la lettura (ULA, gateway, dominio); rete locale negata. Un candidato senza ULA può usare il percorso: confermare
"casa" è dell'interfaccia, e senza impronta salvata l'app non riconoscerà la LAN.

## 6. Cosa vedono le regole delle attività

`LiveNetworkEnvironment` implementa `HomeNetworkSignal` (`isAtHome` = luogo `HOME`) e produce lo `NetworkState`
(`toNetworkState()`): "casa" ha **una sola definizione**. Differenze volute rispetto a leggere la rete predefinita di Android:

- una **VPN estranea non è "via VPN"** (E-S16): le regole vedono una rete non riconosciuta;
- con **due reti** (E-R12) conta la rete su cui poggia il socket verso il NAS (docs/android.md 7.3), non quella di sistema:
  Wi-Fi di casa senza internet + cellulare predefinito → `HOME_WIFI`, nessun dato mobile in gioco;
- a **casa senza internet** la rete conta come validata (il NAS è in LAN); altrove una rete non validata è `OFFLINE` (E-R13);
- hotspot a consumo con la VPN → `MOBILE`; Wi-Fi di casa marcato a consumo → `HOME_WIFI` (E-R13).

`EnvironmentDeviceStateSource(AndroidDeviceStateSource, ambiente)` mette insieme alimentazione (da Android) e rete (da qui):
è il `DeviceStateSource` da dare a `ComposedSystemStateProvider`. Senza, il provider userebbe la rete predefinita di Android
e solo `isHome` da qui.

**Qualità (E-R4, E-R14).** `LiveNetworkEnvironment.quality` dice "scadente" solo per ciò che Android sa dire: rete
sottostante **congestionata o sospesa** (`NOT_CONGESTED` / `NOT_SUSPENDED`, Android 11+). Le stime di banda non si usano
(non sono affidabili). La parte misurata (latenza, throughput, blocchi) è di core-nas; `CombinedNetworkQuality` le unisce
("scadente se almeno una lo dice").

## 7. Controllo di WireGuard

### Intent

`AndroidWireGuardPlatform`: broadcast **esplicito** (`setPackage("com.wireguard.android")`) con azione
`com.wireguard.android.action.SET_TUNNEL_UP` / `SET_TUNNEL_DOWN` e l'extra `tunnel` = nome del tunnel. Il ricevitore di
WireGuard è protetto da `CONTROL_TUNNELS` (livello "dangerous", lo concede l'utente a runtime; il sistema lo controlla alla
consegna). Con "Allow remote control apps" spento in WireGuard l'intent è **ignorato senza avviso**: non c'è risposta al
mittente. Il nome del tunnel si valida (`[A-Za-z0-9_=+.-]{1,15}`) prima di entrare in un intent; uno strano è un errore.

### Capacità (`VpnController.capability`)

app mancante → `WIREGUARD_MISSING`; permesso mancante → `PERMISSION_MISSING`; nome del tunnel mancante → `NOT_CONFIGURED`;
altrimenti `READY`. Si rilegge con `refresh()` (al ritorno in primo piano, dopo la richiesta del permesso o l'installazione
di WireGuard) e a ogni comando. Ogni profilo Android ha il suo WireGuard, il suo permesso e il suo `DataStore` (E-P20).
Il controllore **non** applica `VpnControlConfig.enabled`: è dell'automazione. La prova guidata del passo 11 comanda il
tunnel anche prima di abilitare il controllo.

### Verifica osservata (`setTunnel`, 10 s di default)

"VPN accesa" non si dice perché l'intent è partito.

| Comando | Confermato quando | Altrimenti |
|---|---|---|
| `UP` | compare una VPN **di casa** (`HOME_TUNNEL`) entro il tempo | `NotObserved` |
| `DOWN` | non c'è più nessuna VPN **e** la rete sottostante risulta validata, entro il tempo | `NotObserved` se il tunnel non scende; `LostConnectivity` se scende ma la rete no |

- `LostConnectivity` (probabile "VPN sempre attiva con blocco", che da fuori non si vede): il controllore **rimanda subito
  `UP`** prima di restituirlo. Il telefono non resta senza rete per colpa dell'app.
- Già nello stato chiesto → `Skipped(ALREADY_IN_STATE)`, nessun intent. Una VPN di terzi non si tocca: `DOWN` su una VPN
  estranea è "già nello stato" e non si finge di averla spenta.
- Un comando alla volta (un `Mutex`): due richieste insieme mandano un solo intent.

## 8. L'automazione "spenta a casa, accesa fuori"

`DefaultVpnAutomation` (§2.4), in tempo virtuale nei test. Il **bisogno** si ricava dalla fotografia:

| Bisogno | Quando |
|---|---|
| **giù** | casa (`Match`) + VPN di casa accesa + rete validata |
| **su** | fuori casa con certezza (cellulare, oppure Wi-Fi/Ethernet `NoMatch`/`Weak`) + nessuna VPN + rete validata |
| nessuno | VPN estranea; offline; pagina di accesso; casa "sconosciuta" (nessuna impronta, o Wi-Fi sotto il tunnel non visibile) |

Regole (mai in ciclo):
1. **un comando per ogni cambio di rete**: il tentativo è identificato da (verso, rete sottostante); sulla stessa rete non si
   ritenta, neanche se non ha avuto effetto. Se l'utente riaccende la VPN a mano in casa sulla stessa rete, l'app non la
   rispegne;
2. dopo comandi **senza effetto** l'attesa prima del successivo cresce: 30 s, 60 s, 120 s... fino a 10 min; al **terzo di
   fila** l'automazione si sospende (`NOT_CONTROLLABLE`, persistente) e propone *Apri WireGuard*;
3. dopo un insuccesso, in **background non si ritenta** (Android rifiuta spesso gli avvii dal background, E-R3): si riprova
   quando l'app torna in primo piano. Il primo tentativo in background è consentito;
4. su un Wi-Fi che non è (ancora) casa si aspetta **8 secondi** prima di accendere la VPN: all'arrivo a casa arriva prima
   l'IPv4 e poi, con l'annuncio del router, l'ULA; senza l'attesa la VPN si accenderebbe a casa a ogni rientro. Sul
   cellulare non serve;
5. per spegnere la VPN in casa si aspetta che la sessione NAS sia libera (`NasSessionActivity.isBusy`), **al massimo 2
   minuti**; l'attesa non ricomincia se il passo riparte;
6. se spegnendo la VPN la rete sparisce (`LostConnectivity`): il controllore ha già rimandato `UP` e l'automazione si
   sospende **per sempre** (`LOCKDOWN_SUSPECTED`, persistente) con la spiegazione in Diagnostica.

**Sospensione.** È in `VpnControlConfigStore` (nuovo contratto, §12) e sopravvive al riavvio del processo: altrimenti
l'automazione spegnerebbe di nuovo la VPN e toglierebbe di nuovo la rete. Si toglie solo con un'azione dell'utente:
`save(config)` (per esempio dopo una prova di accensione e spegnimento riuscita) o `clearSuspension()`.

**Stati** (`VpnAutomationStatus`): `Off` (controllo spento, capacità mancante); `Idle`; `WaitingForIdleSession(since)`;
`Commanding(up, at)`; `Done(up, esito)` — resta fino al comando successivo, perché la conferma stessa cambia la rete;
`Suspended(motivo)`. Un comando saltato (`Skipped`) non è un insuccesso e non allunga l'attesa. Un controllore che lancia
un'eccezione conta come comando senza effetto: non fa cadere lo scope dell'app.

Esempio (VPN accesa, `NotObserved` sempre): arrivo a casa a t=0 → `DOWN` a t=0, nessun effetto a t=10 → il Wi-Fi si
riconnette a t=20 (rete nuova) → `DOWN` a t=40 → riconnessione a t=60 → `DOWN` a t=110, terzo insuccesso → `Suspended`.

## 9. Permesso di rete locale (ramo target 37)

`AndroidLocalNetworkPermission` → `LocalNetworkAccess`:

| Android | Target | Permesso `ACCESS_LOCAL_NETWORK` | Stato |
|---|---|---|---|
| < 17 | qualunque | non esiste | `GRANTED_IMPLICIT` |
| 17 | 36 (oggi) | concesso (implicito) | `GRANTED_IMPLICIT` |
| 17 | 36 | revocato | `DENIED` |
| 17 | 37 | concesso | `GRANTED` |
| 17 | 37 | no | `DENIED` |

Il nome è scritto come testo (`LocalNetworkPermissions.PERMISSION`): la costante esiste solo dall'SDK 37. Quando `:app`
passerà a target 37 basterà dichiarare il permesso nel manifest (e nella policy) e chiederlo a runtime; dopo la risposta
`LiveNetworkEnvironment.onPermissionsChanged()`. **Limite (T-19)**: con la restrizione opt-in
(`am compat enable RESTRICT_LOCAL_NETWORK`) su un'app a target 36 lo stato del permesso potrebbe non cambiare, e l'SDK 37 non
espone un motivo di blocco leggibile da Java: il segnale sarebbe "TCP in timeout con rete valida". Provare con F9.

## 10. Scoperta, sonda, Wake-on-LAN

**Scoperta (`NsdNasDiscovery`)**, solo onboarding. NSD `_smb._tcp` sul Wi-Fi attuale (da Android 13 legata alla `Network`;
prima NSD gira sul Wi-Fi comunque) e un nome digitato risolto con il DNS **di quel Wi-Fi** (`Network.getAllByName`), mai
con quello della rete predefinita. Senza Wi-Fi valido il flusso termina vuoto. Le risoluzioni NSD vanno una alla volta. I
risultati passano da `LanAddressFilter`: **solo indirizzi della sottorete locale** — un dispositivo malevolo che annuncia
un NAS con un indirizzo di Internet, o un nome che punta fuori, non porta l'app a connettersi lì. Niente IPv6 link-local
(servirebbe la zona). I nomi annunciati servono solo a mostrare e sono ripuliti.

**Sonda (`TcpReachabilityProbe`)**: una connessione TCP alla 445, subito chiusa, dal legame della rotta consentita. Nessuna
richiesta SMB: non sveglia i dischi (E-R15). Esiti: `Open` / `Refused` (RST: NAS acceso, SMB no) / `TimedOut` / `NoRoute`.

**Wake-on-LAN (`DefaultWakeOnLan`)**, dietro `FeatureFlags.wol` (spento in release e non accendibile, D5):
- solo con percorso `HOME_LAN`; **mai** attraverso la VPN (nemmeno quella di casa): altrimenti `NotAllowed`;
- MAC a mano (`aa:bb:cc:dd:ee:ff`, anche con `-`); non valido, broadcast o tutto zero → `NotAllowed`;
- prima la 445: se risponde (anche `Refused`) → `AlreadyOn`, **nessun pacchetto**;
- poi al massimo **3 pacchetti** (a 0, 10 e 20 s) UDP, porta 9, 102 byte, al broadcast della sottorete IPv4, con il socket
  legato al Wi-Fi; la 445 ogni 2 s fino a **120 s** dal primo pacchetto; se si apre → `Woke(dopo)`;
- altrimenti `NoEffect` e **nessun nuovo tentativo per 5 minuti** (E-R6), per quel MAC;
- il percorso si controlla a ogni giro: se il Wi-Fi cambia a metà si smette (`NotAllowed`); un solo risveglio alla volta.

La prova vera è rimandata a quando il NAS avrà di nuovo un orario di accensione (docs/decisioni.md).

## 11. Diagnostica

`NetworkDiagnosticsContributor` (sezione `NETWORK_AND_NAS`): luogo e rete sottostante, motivo del percorso con il rimedio,
permesso di rete locale, **una sola sonda TCP** (se il percorso è consentito e c'è un NAS configurato), diagnosi IPv4
(E-R11/E-R17), controllo di WireGuard e sospensioni, qualità, impronta di casa.

- Codici in `NetworkDiagCodes` (17), ognuno con gli edge case che copre; `NetworkDiagCodes.ALL` serve al catalogo di P8.
  Azioni: quelle di `DiagAction` più `NetworkDiagActions.RECAPTURE_HOME` (E-R16; un'azione che P8 non conosce si mostra senza
  tasto).
- **Esportazione senza dati personali, per costruzione**: solo `Num`, `Flag`, `Dur`, `Enum` (costanti del codice) e `Hashed`
  (HMAC con chiave locale). L'ULA di casa, il gateway e il dominio entrano come impronta (`ula0`, `gateway`, `domain`), con
  in più l'impronta dell'ULA della rete di adesso (`currentUla0`): si vede "è la stessa rete di casa" senza dire quale.
  Nessun argomento nei testi a schermo. `NetworkDiagnosticsContributorTest` inserisce reti con indirizzi e nomi riconoscibili
  e verifica che non compaiano in nessuna voce.

## 12. Cablaggio per `:app` (P9)

```kotlin
val observer  = AndroidNetworkObserver(context)                         // NetworkObservationSource + NetworkLookup
val homeStore = DataStoreHomeFingerprintStore.create(context, appScope)
val vpnStore  = DataStoreVpnControlConfigStore.create(context, appScope) // VpnControlConfigStore
val env       = LiveNetworkEnvironment(observer, homeStore, AndroidLocalNetworkPermission(context), appScope)
val gate      = DefaultNasRouteGate(env, AndroidBindingFactory(observer), appScope)
val capture   = ObservedHomeCapture(env)
val probe     = TcpReachabilityProbe()
val wireGuard = WireGuardVpnController(AndroidWireGuardPlatform(context), vpnStore, env, appScope)
val automation = DefaultVpnAutomation(env, wireGuard, vpnStore, appContextSignal, sessionActivity)   // poi .attach(processScope)
val discovery = NsdNasDiscovery(context, env, observer)
val wol       = DefaultWakeOnLan(featureFlags, gate, probe, AndroidMagicPacketSender(observer, env))
val diag      = NetworkDiagnosticsContributor(env, gate, wireGuard, automation, probe, hasher) { account?.address }
// regole delle attività:
ComposedSystemStateProvider(device = EnvironmentDeviceStateSource(AndroidDeviceStateSource(context), env), home = env,
  quality = CombinedNetworkQuality(listOf(env.quality, nasStatusMonitor), appScope), ...)
```

- `LiveNetworkEnvironment` comincia a osservare nel costruttore: crearlo una volta, come singleton.
- Al ritorno in primo piano: `env.onPermissionsChanged()` e `wireGuard.refresh()`.
- `sessionActivity` adatta `NasSessionManager` a `NasSessionActivity` (`isBusy` = sessione al lavoro).
- **Contratti aggiunti da P2** (non erano nel P0): `VpnControlConfigStore` e `NasSessionActivity`
  (`contract/VpnSupport.kt`), `WireGuardTunnelNames`. Chi salva il nome del tunnel (onboarding passo 11, Impostazioni → Rete
  e VPN) deve usare `VpnControlConfigStore.save`, non un fake locale.
- `NasRouteGate.routes` e `NetworkEnvironment.snapshot` sono `StateFlow`: nessuna chiamata di rete, si leggono sul thread
  che si vuole.

## 13. Edge case coperti, e dove

| ID | Cosa | Dove si prova |
|---|---|---|
| E-S10 | amico: stessa sottorete, ULA diverso → `Denied`, nessun socket | `HomeMatcherTest`, `NetworkPolicyTest` (tabella, `E_S10_*`), `NasRouteGateTest.E_S10_*`, diagnostica |
| E-S16 | VPN di terzi / tunnel verso casa altrui → `Denied(FOREIGN_VPN)`, per le regole non è "via VPN" | `VpnClassifierTest`, `NetworkPolicyTest.E_S16_*`, `NasRouteGateTest.E_S16_*` |
| E-R3 | fuori casa a VPN spenta; intent senza effetto; un tentativo in background | `NetworkPolicyTest`, `VpnAutomationTest` (backoff, background), `WireGuardVpnControllerTest` |
| E-R4, E-R14 | rete scadente (parte di Android) | `LiveNetworkEnvironmentTest` (qualità, `CombinedNetworkQuality`) |
| E-R5 | rete locale negata prima di "NAS spento" | `LocalNetworkPermissionTest`, `NetworkPolicyTest`, `NasRouteGateTest` |
| E-R6 | WoL senza effetto: 3 invii in 30 s, nessun nuovo tentativo | `WakeOnLanTest.E_R6_*` |
| E-R7 | niente DNS: IP letterale, niente `getByName` | `ArchitectureGuardTest`, `IpModelTest` |
| E-R11, E-R17 | solo IPv4 con la VPN | `NetworkPolicyTest.E_R11_E_R17_*`, `NetworkDiagnosticsContributorTest` |
| E-R12 | due reti: conta quella del socket | `NetworkPolicyTest.E_R12_*`, `LiveNetworkEnvironmentTest` (`EnvironmentDeviceStateSource`) |
| E-R13 | hotspot a consumo, pagina di accesso | `NetworkPolicyTest.E_R13_*`, tabella |
| E-R15 | la sonda è solo TCP 445 | `TcpReachabilityProbeTest` (nessun byte), diagnostica (una sonda) |
| E-R16 | ULA cambiato → nuova raccolta | `NetworkPolicyTest.E_R16_*`, `HomeMatcherTest`, diagnostica |
| E-P20 | un profilo, il suo DataStore | per costruzione (`noBackupFilesDir` di ogni utente); capacità in Diagnostica |
| §2.4 | dieci entrate/uscite, nessun ciclo, euristica del blocco, sospensione persistente | `VpnAutomationTest` |

## 14. Scelte fatte dove il brief lasciava spazio

- **Quattro callback, non tre**: il cellulare sottostante. Con una VPN sul cellulare la rete predefinita è la VPN, e dalle
  sue capacità non è garantito (M11) che si legga roaming e a consumo della rete vera. `registerNetworkCallback` non tiene
  accesa nessuna rete. La richiesta Wi-Fi comprende anche l'Ethernet (il contratto parla di "Wi-Fi o Ethernet di casa").
- **`UNTRUSTED` o `VPN_OFF`?** `UNTRUSTED` quando una rete *somiglia* a casa e non lo è (E-S10, E-R16) o c'è una VPN estranea;
  `VPN_OFF` quando si è altrove senza VPN. Così l'indicatore dice "rete non riconosciuta" a casa col router reimpostato, non
  "VPN spenta".
- **Attesa di 8 s** prima di accendere la VPN su un Wi-Fi non riconosciuto (§8, regola 4): da misurare (T-17: quanto
  dopo l'IPv4 arriva l'ULA).
- **Soglie del backoff** (30 s, 10 min, 3 insuccessi) e **pausa dopo `NoEffect`** (5 minuti): ragionevoli, non misurate.
- **Rete locale negata** nega il solo percorso LAN diretto (§5).
- **`Refused` alla sonda del WoL**: all'inizio vale "acceso" (`AlreadyOn`); durante l'attesa vale "la scheda risponde, SMB
  no": si continua ad aspettare la 445 aperta.

## 15. Da verificare sul campo

| Prova | Cosa guardare | Cosa fa decidere |
|---|---|---|
| **T-17** `LinkProperties` senza posizione | (a) il Wi-Fi sotto la VPN resta visibile via la richiesta `NOT_VPN`; (b) l'ULA resta uguale dopo il riavvio del FRITZ!Box e dopo un aggiornamento del firmware; (c) cosa porta il tunnel di WireGuard: rotte, DNS, indirizzi, trasporti ereditati, `NOT_METERED`/`NOT_ROAMING` — con la configurazione della guida e con quella di fabbrica; (d) hotspot di un altro telefono: è "a consumo"?; (e) cellulare + VPN: roaming e a consumo della rete vera; (f) quanti secondi passano fra l'IPv4 e l'ULA all'arrivo a casa | formula dell'impronta; soglie della VPN di casa; attesa di 8 s; se (a) fallisce, a casa non si riconosce e l'automazione non spegne la VPN (il percorso resta valido via tunnel) |
| **T-18** VPN non bypassabile | (a) con la VPN accesa in casa, un socket legato al Wi-Fi passa dal tunnel (latenza, contatori WireGuard)?; (b) `SET_TUNNEL_DOWN`/`UP` 10 volte (F8) da primo piano, da background, a schermo spento, dopo il riavvio; (c) con "VPN sempre attiva + blocco": come si presenta `LostConnectivity` — il Wi-Fi risulta davvero non validato? (la NetworkMonitor di sistema potrebbe non essere bloccata e la perdita non vedersi) | gestione della VPN; testo della guida; se (c) non si vede, serve una sonda TCP verso il NAS dopo il `DOWN` |
| **T-19** permesso di rete locale | F9: `am compat enable RESTRICT_LOCAL_NETWORK` sull'S25 (target 36): lo stato letto cambia? Un indirizzo privato dentro il tunnel conta come locale? UDP broadcast del WoL | se serve un segnale diverso dal permesso; se la VPN va negata in anticipo; passaggio a target 37 |
| **Intent WireGuard su Android 16 (tablet) e 17 (S25)** (M10) | tasso di successo e latenza fino alla comparsa di `TRANSPORT_VPN`; con WireGuard installato dopo la nostra app: il permesso si può chiedere senza reinstallare? | se serve il piano B (libreria `tunnel` incorporata) |
| **F4** hotspot del tablet | "Rete non riconosciuta", **nessuna** TCP verso la 445 | conferma di E-S10 sul campo |
| **NSD** | il NAS WD annuncia `_smb._tcp`? Se no, restano nome digitato e IP | passo 2 dell'onboarding |
| **R8** | le classi di `core-network` non usano riflessione: nessuna regola `consumer-rules.pro` necessaria; verificare con F11 | — |
