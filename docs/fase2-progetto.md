# Fase 2 di MyCloud Gallery: progetto tecnico (NAS, onboarding, famiglia, dispositivi)

> Base per i brief dei sub-agent. Fonti: piano (§1–§3, FASE 2, §6, §7), `docs/decisioni.md`, `docs/inventario-nas.md`, `docs/android.md`, `docs/research/smb-vpn.md`, `docs/formato-nas.md`, `docs/sicurezza.md`, `docs/regole-attivita.md`, `docs/modello-dati.md`, `docs/review/1.4-core-trust.md`, codice di `core-trust`, `core-rules`, `core-security`, `app`, `spike/nas-probe`.
> Contesto: il NAS è **sempre acceso** (pianificazione disattivata dall'utente), quindi il WoL **non è verificabile**.

## 0. Sintesi

- **Moduli nuovi**: `core-network`, `core-nas`, `core-family`, `core-qr`, `feature-onboarding`, `feature-settings`, `feature-diagnostics`. Più aggiunte mirate a `core-common` (contratto della Diagnostica), `core-rules` (salute del background) e `core-trust` (estensione del formato, solo dopo la chiusura della revisione).
- **Cancello di rete tipizzato.** Una connessione SMB si apre solo da un `NasRoute.Allowed`, che solo `core-network` può creare. Il socket è sempre legato alla rete scelta, con una connect a tempo. La password parte solo se:
  1. il percorso è consentito (casa riconosciuta tramite ULA, oppure VPN di casa);
  2. la NEGOTIATE dà 3.1.1 con cifratura offerta e un GUID del NAS uguale a quello memorizzato;
  3. l'interruttore di sicurezza è chiuso.
  Dopo l'autenticazione si pretende la cifratura effettiva, e la sessione non deve essere né guest né anonima.
  **ULA e GUID non sono segreti** (revisione 2, F1): il cancello ferma gli errori e gli impostori casuali, non un attaccante
  mirato che li ha copiati (Wi-Fi gemello con un NAS finto). Contro quello difende la **password forte, obbligatoria**
  (`NasPasswordPolicy`, C5): limite dichiarato in `sicurezza.md` L19.
- **Dieci pacchetti di lavoro (P0–P9).** P0 (fondamenta e contratti) va da solo e per primo; poi P1–P8 in parallelo, su perimetri disgiunti; per ultimo P9, l'integrazione nell'app.
- **Test senza dispositivo**: fake più un simulatore SMB con semantica Samba, test JVM, Robolectric e Roborazzi. Non esiste un server SMB locale realistico su questo PC (§5.3). I test d'integrazione girano **dal PC contro il NAS vero** (smbj è Java puro) appena l'utente fornisce le credenziali. Poi si fa la verifica sul campo.

### Decisioni di progetto (valgono se l'utente non dice altro)
| # | Decisione | Perché |
|---|---|---|
| D1 | Si connette al NAS solo con un **socket legato** alla rete del percorso (Wi-Fi di casa oppure rete VPN), mai alla rete predefinita del processo | Se la VPN cade, il traffico non scivola sul Wi-Fi di una rete estranea. Vale anche con sottoreti sovrapposte (E-S10) |
| D2 | **Non basta una VPN qualsiasi**: deve essere la VPN di casa, riconosciuta da rotte, DNS e indirizzi della rete di casa e confermata dal GUID del NAS | Su S25 e tablet c'è anche una VPN di terzi. Nuovo edge case proposto: **E-S16** |
| D3 | Si memorizza il **GUID del NAS** dalla NEGOTIATE, prima dell'autenticazione. Se è diverso, la password non parte e si chiede una verifica. Il controllo diventa morbido (conferma dell'utente) se la misura M8 mostra che il GUID cambia al riavvio | È l'unica conferma possibile prima dell'autenticazione (E-R7) |
| D4 | I QR si leggono con **la fotocamera dentro l'app** (CameraX + ZXing core, Apache-2.0), senza ML Kit né Code Scanner dei Play Services (telemetria). Nuovo permesso `CAMERA` | Serve in due direzioni: l'invito della famiglia e la chiave del dispositivo per l'approvazione |
| D5 | Le funzioni sperimentali stanno in `core-common/featureflags`. **`wol = false`** non è attivabile in release | Il WoL non è verificabile con il NAS sempre acceso |
| D6 | I fake dei contratti stanno nel `src/main` di ogni modulo, nel package `testing`. `tools/check.ps1` fa fallire la release se nel dex compaiono `.testing.` | I moduli a valle li riusano senza cicli e senza `testFixtures` sperimentali |
| D7 | I moduli `core-*` restano senza Hilt. I `feature-*` usano `@HiltViewModel` (KSP) | Regola del README; `hiltNavigationCompose` è già in `:app` |
| D8 | Dipendenze, permessi e policy del manifest li aggiunge **solo P0** (poi passano a P9). Nessun altro pacchetto tocca i file di build condivisi | Niente conflitti di merge né rigenerazioni di `verification-metadata.xml` in parallelo |

## 1. Moduli

### 1.1 Grafo delle dipendenze (nessun ciclo)
```
core-common   core-model                                  (foglie)
core-trust      → model, common
core-security   → common
core-rules      → model, common   (+ androidx.core per la salute del background)
core-designsystem → engine-ui
core-network    → common, rules
core-nas        → common, model, trust, security (solo NasCredentials), network, rules  (+ smbj)
core-family     → common, model, trust, security, network, nas, rules
core-qr         → common, security (ScreenProtection), designsystem  (+ zxing-core, camerax)
feature-onboarding  → family, network, nas, qr, security, rules, designsystem
feature-settings    → family, network, nas, qr, security, rules, designsystem
feature-diagnostics → common, rules, network, nas, family, designsystem
app → tutti (Hilt, navigazione, WorkManager, notifiche)
```
I `feature-*` non si conoscono tra loro: la navigazione tra funzioni passa da callback fornite da `:app`.

### 1.2 Responsabilità e API (firme indicative; P0 scrive i contratti e i null-object)

**`:core-network` (P2): stato della rete, casa, VPN, WoL**
| Elemento | Firma | Note |
|---|---|---|
| Luogo | `enum class NetPlace { HOME, AWAY_VPN_WIFI, AWAY_VPN_MOBILE, ROAMING, VPN_OFF, UNTRUSTED, OFFLINE }` | stati del piano 2.1; "NAS spento" lo aggiunge `core-nas` |
| Istantanea | `data class NetworkSnapshot(place, underlying: UnderlyingNetwork?, home: HomeMatch, vpn: VpnObservation, localNetwork: LocalNetworkAccess, ipv6Global: Boolean, at: Long)` | `UnderlyingNetwork(transport, metered, roaming, validated, captivePortal)`; `VpnObservation { NONE, HOME_TUNNEL, FOREIGN_VPN, UNKNOWN }` |
| Ambiente | `interface NetworkEnvironment : HomeNetworkSignal { val snapshot: StateFlow<NetworkSnapshot>; fun toNetworkState(): NetworkState }` | implementa il segnale già previsto da `core-rules` |
| Impronta di casa | `data class HomeFingerprint(ulaPrefixes: Set<String>, gatewayV4: String?, dnsDomain: String?, capturedAt: Long)`; `interface HomeFingerprintStore`; `interface HomeCapture { suspend fun candidateFromCurrentWifi(): HomeCandidate? }` | ULA /48 = indizio **forte**; gateway e `fritz.box` = deboli, mai sufficienti da soli |
| Confronto | `sealed interface HomeMatch { Match; Weak(clues); NoMatch; Unknown }` | solo `Match` vale come casa |
| Cancello | `sealed interface NasRoute { Allowed(via: Via, binding: SocketBinding); Denied(reason: DenyReason) }`; `interface NasRouteGate { val routes: StateFlow<NasRoute>; fun current(): NasRoute; fun onboardingRoute(c: HomeCandidate): NasRoute }` | `Via { HOME_LAN, HOME_LAN_THROUGH_VPN, VPN }`; `DenyReason { OFFLINE, CAPTIVE_PORTAL, UNTRUSTED_NETWORK, VPN_OFF_AWAY, FOREIGN_VPN, LOCAL_NETWORK_DENIED, NOT_CONFIGURED }` |
| Socket legato | `interface SocketBinding { val networkId: String; fun connect(addr: InetAddress, port: Int, timeout: Duration): Socket }` | Android: `Network.socketFactory.createSocket()` non connesso, poi `connect(addr, timeout)`; JVM: `Socket()` |
| VPN | `interface VpnController { val capability: StateFlow<VpnCapability>; suspend fun setTunnel(up: Boolean, timeout: Duration = 10.seconds): VpnCommandResult; fun openWireGuardIntent(): Intent? }` | `VpnCapability { WIREGUARD_MISSING, PERMISSION_MISSING, NOT_CONFIGURED, READY }`; `VpnCommandResult { Confirmed; NotObserved; LostConnectivity; Skipped(reason) }` |
| Automazione VPN | `interface VpnAutomation { val status: StateFlow<VpnAutomationStatus>; fun attach(scope: CoroutineScope) }` | macchina a stati del §2.4 |
| Rete locale | `enum class LocalNetworkAccess { GRANTED, GRANTED_IMPLICIT, DENIED, UNKNOWN }`; `interface LocalNetworkPermission { fun status(): LocalNetworkAccess }` | pronto per il target 37 |
| Scoperta | `interface NasDiscovery { fun discover(timeout: Duration): Flow<DiscoveredNas>; suspend fun resolveOnWifi(name: String): InetAddress? }` | NSD `_smb._tcp` + nome DNS digitato; **solo onboarding** |
| Raggiungibilità | `interface ReachabilityProbe { suspend fun tcp445(route: NasRoute.Allowed, nas: NasAddress, timeout: Duration): ProbeResult }` | solo TCP connect, non sveglia i dischi (E-R15) |
| WoL | `interface WakeOnLan { val enabled: Boolean; suspend fun wake(t: WolTarget): WolOutcome }` | dietro `FeatureFlags.wol`, solo con `place == HOME` |

**`:core-nas` (P1): sessione SMB e `NasFileSystem` reale**
| Elemento | Firma | Note |
|---|---|---|
| Indirizzo | `data class NasAddress(val ip: String, val name: String?, val port: Int = 445)` | connessione sempre all'**IP letterale**, niente DNS; il nome è informativo (E-R7) |
| Account | `data class NasAccount(val address: NasAddress, val user: String, val credentialSlot: String, val pinnedGuid: String?)` | uno per dispositivo o profilo |
| Credenziali | `fun interface NasCredentialSource { suspend fun load(slot: String): CredentialLoad }` | `:app` lo adatta a `CredentialVault` |
| Stato | `sealed interface NasConnState { Idle; Connecting; WakingUp(since); Ready(info: SessionInfo); Unavailable(error: NasError); AuthBlocked(reason) }` | `SessionInfo(dialect, encrypted, cipher, signingRequired, serverGuid, nasTimeOffsetMs, via)` |
| Sessione | `interface NasSessionManager { val state: StateFlow<NasConnState>; val lastDiskTouchAt: StateFlow<Long?>; suspend fun <T> withShare(share: String, purpose: Purpose, block: suspend (NasShareScope) -> T): NasResult<T>; fun fileSystem(share: String): NasFileSystem; fun browser(share: String): NasBrowser; suspend fun invalidate(reason: InvalidateReason) }` | `Purpose { INTERACTIVE, BACKGROUND, ONBOARDING }` |
| Esiti | `sealed interface NasResult<out T> { Ok(value); Err(error: NasError) }`; `sealed interface NasError` (§3.4) | con il `NasFileSystem` bloccante di `core-trust` l'errore arriva come `NasIoException(error)` |
| Interruttore | `interface AuthCircuitBreaker { val state: StateFlow<BreakerState>; suspend fun onNewPasswordSaved() }` | `BreakerState { Closed(gen); Open(reason, at, gen) }`, persistente |
| Test connessione | `interface ConnectionTester { suspend fun run(target: TestTarget, credentials: NasCredentials?, stages: Set<Stage>): ConnectionReport }` | fasi: percorso → TCP → NEGOTIATE → GUID → autenticazione → controllo di sicurezza → share → lettura |
| Share | `interface ShareProbe { suspend fun probe(share: String): ShareAccess }` | `ShareAccess { MISSING, DENIED, READ_ONLY, READ_WRITE }`; la scrittura si verifica aprendo la cartella con `FILE_ADD_FILE`, **senza creare file** |
| Sfoglia | `interface NasBrowser { suspend fun list(dir: SharePath?, limit: Int): NasResult<DirPage> }` | sola lettura, a pagine (`Directory.iterator`) |
| Pulizia | `interface NasHousekeeping { suspend fun purgeOwnStaleTemps(deviceId: String, olderThan: Duration): NasResult<Int> }` | solo `.mycloudgallery/tmp/<deviceId>-*` |
| Orologio | `class SessionNasClock : NasClock` | orologio monotono + scarto letto alla NEGOTIATE |
| Segnali | `class NasStatusMonitor : NasReachabilitySignal, NetworkQualitySignal` | contratti di `core-rules` |
| Fiducia reale | `internal class SmbNasFileSystem : NasFileSystem` | `list`/`read`/`publishExclusive` come da `formato-nas.md` §2.3 |

**`:core-family` (P3, P4): fiducia operativa e flussi di famiglia (logica, nessuna UI)**
| Elemento | Firma | Proprietario |
|---|---|---|
| Stato | `sealed interface SetupState { Loading; NotConfigured; Onboarding(progress); AwaitingApproval(code, since); Ready(admin: Boolean); Wiped(at); Blocked(reason) }`; `interface FamilySetupRepository { val state: StateFlow<SetupState> }` | contratto P0, implementazione P4 |
| Onboarding | `enum class OnboardingPath { FOUND_FAMILY, JOIN_QR, JOIN_MANUAL, RECOVER_ADMIN, SECOND_ADMIN }`; `enum class OnboardingStep { WELCOME, NAS_ADDRESS, ACCOUNT, AREAS, FOLDERS, BACKUP_SOURCES, ROLES, PRESET, PERMISSIONS, BATTERY, VPN, FAMILY_KEY, SECURITY_NOTICE, SUMMARY }`; `interface OnboardingCoordinator` | P4 |
| Inviti/QR | `data class FamilyInvite(...)`, `object InviteCodec { fun encode(i): String; fun decode(t): Verdict<FamilyInvite> }`, `DeviceKeyQr`, `FamilyStateQr` | P4 |
| Ingresso | `interface JoinFlow { suspend fun start(i: FamilyInvite): JoinStep; suspend fun authenticate(pw: CharArray): JoinStep; suspend fun register(name: String): JoinStep }` | P4 |
| Registro | `sealed interface TrustStatus { NotConfigured; Pending(code); Approved(admin); Retired; Revoked; Lost; Stale(reason) }`; `interface RegistryService { val status; val devices: StateFlow<List<DeviceView>>; val pendingRequests: StateFlow<List<PendingRequestView>>; suspend fun refresh(p: Purpose); suspend fun approve(id, proof: ApprovalProof): AdminActionResult; suspend fun changeStatus(id, s: DeviceStatus): AdminActionResult; suspend fun createInvite(p: InviteParams): FamilyInvite }` | P3 |
| Guardia | `interface TrustGuard { suspend fun preflight(p: Purpose): PreflightResult; fun writePermit(): WritePermit? }` | P3 |
| Stato fidato | `interface TrustStateStore` (byte dell'ultimo registro accettato, impronta del firmatario, ancora, `HeadMark`) | P3 (contratto P0) |
| Ciclo di vita | `interface SelfWipe`, `fun interface LocalDataEraser` (implementato da `:app`), `interface AdminRecovery`, `interface FamilyReset`, `interface LostReporting` | P3 |
| Pianificazione | `interface TrustSyncScheduler { fun requestSoon(reason); fun setInviteWindow(untilNasMs: Long) }` | contratto P0, implementazione P9 |
| Cartelle | `interface FolderSurvey { suspend fun propose(share: String): FolderProposal }` (E-N14, E-N15) | P4 |

**`:core-qr` (P5)**:
- `@Composable fun QrCode(content: String, contentDescription: String, secure: Boolean, modifier: Modifier)`, con `FLAG_SECURE` tramite `ScreenProtection`;
- `@Composable fun QrScanner(accept: (String) -> ScanVerdict, onCancel: () -> Unit, modifier: Modifier)`, che gestisce il permesso della fotocamera e il ripiego manuale;
- `object QrMatrix { fun encode(text: String): BitMatrix; fun decode(luma: ByteArray, w: Int, h: Int): String? }`.

**`feature-*` (P6, P7, P8)**: `fun NavGraphBuilder.onboardingGraph(nav: NavController, onDone: () -> Unit)`; `fun NavGraphBuilder.settingsGraph(nav, openDiagnostics: () -> Unit, resumeStep: (OnboardingStep) -> Unit)`; `fun NavGraphBuilder.diagnosticsGraph(nav)`. Rotte `@Serializable` (Navigation dell'engine, plugin di serializzazione già dichiarato alla radice).

**Aggiunte a moduli esistenti**
| Modulo | Aggiunta | Chi |
|---|---|---|
| `core-common` | Package `diagnostics`: `DiagnosticsContributor { val section: DiagSection; suspend fun collect(): List<DiagItem> }`, `DiagItem(code, section, severity, titleRes, detail, action, export: Map<String, ExportValue>)`, `ExportValue { Num; Flag; Enum; Hashed; Dur }`. **Niente testo libero**: per costruzione del tipo, un dato personale non può finire nell'esportazione. `DiagnosticsLog`: anello di 500 eventi in `noBackupFilesDir`. Package `featureflags`: `FeatureFlags(wol = false)` | P0 |
| `core-rules` | Contratto `platform/health/BackgroundHealthReader` → `BackgroundHealth(ignoringBatteryOpt, standbyBucket, backgroundRestricted, unusedAppRestrictions, permissions, userUnlocked, bootCount, lastExitReasons, isSystemUser)` | contratto P0, implementazione P8 |
| `core-trust` | Estensione del formato v1 (§2.3): campi di `FamilyConfig`, tipo 7 `LostReport`, tipo 8 `DeviceStatus`, posizioni in `NasLayout`, tetti in `TrustLimits`, `formato-nas.md` | P3, **dopo** la chiusura della revisione di `core-trust` |

**Cosa resta in `:app` (P0 lo crea, P9 lo completa)**:
- `MainActivity : FragmentActivity` (serve per BiometricPrompt), unica activity, `singleTask`;
- `AppLockGate` alla radice sopra `AppNavHost`, più `LockedScreen`;
- `RootViewModel`, che sceglie la destinazione da `SetupState`: onboarding, attesa approvazione, principale, "dispositivo azzerato";
- `MainScaffold` con le schede segnaposto Libreria, Raccolte e Cerca, **l'indicatore di stato globale** (§3.5) e l'accesso alle Impostazioni;
- moduli Hilt: prima i null-object, poi le implementazioni vere;
- adattatore da `CredentialVault` a `NasCredentialSource`;
- WorkManager (`TrustSyncWorker`), canali di notifica, `AppContextSignal` (primo piano e profilo attivo), `LocalDataEraser`, osservatore di processo per `VpnAutomation`;
- l'ingresso al catalogo, solo in debug.

## 2. Flussi

### 2.1 Onboarding admin (piano 2.2, 14 passi)
Regole valide per tutti i passi:
- l'avanzamento si salva a ogni passo (`OnboardingProgress` in DataStore, in `noBackupFilesDir`);
- ogni scrittura sul NAS è **idempotente**: `ALREADY_EXISTS` con gli stessi byte vale come successo;
- l'onboarding riprende dopo un cambio di app, che è il caso normale ai passi 10 e 11 (nuovo edge case **E-O9**);
- SMB solo con l'app in primo piano: in background la rete viene interrotta;
- i passi saltati finiscono in Impostazioni → "Da completare".

| # | Passo | Cosa succede | Errori ed edge case |
|---|---|---|---|
| 1 | Benvenuto e privacy | Niente telemetria. Scelta del percorso: *Crea la famiglia*, *Entra con QR*, *Entra senza QR*, *Recupera l'admin*. Se `isSystemUser == false`, nota sui profili (2.8) | E-P19 |
| 2 | Indirizzo NAS | **Serve il Wi-Fi di casa.** `HomeCapture` chiede "È la rete di casa?" e, con il consenso, raccoglie ULA, gateway e dominio. Scoperta via NSD `_smb._tcp`, nome risolto sul Wi-Fi o IP digitato. Poi `onboardingRoute` → TCP 445 → NEGOTIATE (3.1.1 con cifratura offerta) → **GUID memorizzato**. Prima di dire "NAS spento" si controlla il permesso di rete locale | **E-O1**: messaggio chiaro e "Continua più tardi" (i passi 6–11 e 13 sono locali). VPN accesa: chiedere di spegnerla per questo passo. Nessun ULA: spiegarlo e non confermare "casa" |
| 3 | Account e password | `SecureInput`, `FLAG_SECURE`, protezione dalle sovrapposizioni. Rifiuta `admin`, senza distinzione di maiuscole. **Rifiuta le password deboli prima di mandarle** (revisione 2, F1: `NasPasswordPolicy`, 12 caratteri con tre tipi fra minuscole, maiuscole, cifre e simboli, oppure una frase di 16; il nome dell'account e le sequenze ovvie non contano) e spiega che cosa manca; "Proponimi una password forte" ne genera una di 16 caratteri da impostare sul NAS. La stessa soglia vale per il familiare che entra e per il cambio di password nelle Impostazioni. **Un solo tentativo**, poi il controllo di sicurezza: cifratura effettiva, niente guest. Le credenziali si salvano solo dopo il successo. Avviso morbido se l'account apre le share private di altri | **E-R8/E-S12**: `LOGON_FAILURE` apre l'interruttore; "Riprova" solo su azione dell'utente. Messaggi specifici per account bloccato, scaduto o disattivato. Senza cifratura → rifiuto |
| 4 | Aree | `ShareProbe` su `<utente>`, `Public`, `NAS` e sulle share digitate. Si scelgono area comune e area privata. Il backup va in `<privata>/Backup/<dispositivo>/` (creata in Fase 5). Se l'area comune contiene già `family/registry/…1`: "Qui c'è già una famiglia" → *Recupera l'admin* / *Entra come familiare* | **E-O2**, **E-O3** (l'app non crea share). Avviso se una share è pubblica |
| 5 | Cartelle | `FolderSurvey` limitato (2 livelli, 5000 voci, annullabile): propone di escludere le cartelle di musica e film e le copertine piccole | E-N14, E-N15; si può saltare |
| 6 | Sorgenti di backup | Fotocamera e screenshot; le altre cartelle si scelgono dopo, nelle Impostazioni | — |
| 7 | Ruoli | Indicizzatore e Preparatore, con un consiglio in base al modello del dispositivo | E-A4 |
| 8 | Preset e qualità | `RuleSettingsStore` e stime. La fascia notte deriva da `NasSchedule(always)`: 23–07 | — |
| 9 | Permessi | Notifiche, media (`READ_MEDIA_*`, `VISUAL_USER_SELECTED`, `ACCESS_MEDIA_LOCATION`), rete locale (ramo per target 37). Avviso se manca il blocco schermo | E-O4, E-S3 |
| 10 | Salute del background | Batteria "Illimitata", "Mai in sospensione" (link Samsung), "Pausa se inutilizzata", ciascuna con verifica | E-P2, E-P15; si può saltare |
| 11 | VPN WireGuard | Guida: peer sul FRITZ!Box (IPv6, CGNAT); il QR di WireGuard va trattato come una password; `AllowedIPs` = sottorete di casa + ULA. Facoltativo: controllo remoto + `CONTROL_TUNNELS`, nome del tunnel, prova di accensione e spegnimento osservata | E-S15, E-R3, E-R11, E-P20; si può saltare |
| 12 | Chiave di famiglia e kit | Ordine fisso: (a) genera la chiave → `FamilyKeyVault.store`; (b) genera il codice di recupero e lo mostra con `FLAG_SECURE` in 7 gruppi; (c) l'utente **lo riscrive per intero**; (d) `RecoveryKit.seal` → `publishExclusive` nell'area privata → **rilettura e `open` con il codice digitato**; (e) richiesta del fondatore → `FamilyAuthority.found(config)` → `publishFounding` → rilettura e verifica con l'ancora | `ALREADY_EXISTS` in (e): esiste già una famiglia, si torna al passo 4. Se si interrompe tra (d) ed (e), si riprende da (e) |
| 13 | Backup esterno e FRITZ!Box | Checklist di `sicurezza.md` §9, con "fatto/dopo"; l'avviso resta in Impostazioni | C13 |
| 14 | Riepilogo | Cosa è fatto e cosa manca → "Vai alla Libreria"; si misura il tempo (obiettivo < 5 minuti) | — |

La **configurazione di famiglia** firmata nasce al passo 12(e). Contiene: IP e nome del NAS, GUID, ULA, area comune, esclusioni, alimentazione "always", preset e qualità di default.

### 2.2 Familiare via QR, con approvazione (piano 2.3)
| Lato | Passi | Edge case |
|---|---|---|
| Admin | Impostazioni → Famiglia → *Configura un familiare*: utente NAS (ASCII, validato), nome del dispositivo o profilo, share privata, preset. Mostra il **QR d'invito** (`FLAG_SECURE`, mai condivisibile, scade dopo 24 h secondo l'ora del NAS) e apre una "finestra d'invito" di 24 h (`setInviteWindow`): in quella finestra i controlli sono frequenti, fuori una volta al giorno (E-R15) | E-S8 |
| Nuovo dispositivo | *Entra con QR* → scansione → `InviteCodec.decode` (`MCG1:` + base32 di JSON canonico, ≤ 600 caratteri, campi fissi) → riepilogo → **controllo casa** (l'ULA del Wi-Fi coincide con quello del QR, oppure VPN di casa) → TCP, NEGOTIATE, **GUID uguale al QR** → password (unico campo digitato, un solo tentativo) → `RegistrySync.refresh` con `TrustAnchor(famiglia, impronta, versione minima)` → si usa la configurazione **firmata** letta dal NAS → chiave del dispositivo nel Keystore → `RegistrationRequest` → mostra il **codice** e il QR della chiave (`MCGK1:`) → `AwaitingApproval`: sola lettura, il registro si rilegge ogni 10 s solo con l'app aperta | E-O5, E-O6, E-S14 |
| Admin | Notifica "Un nuovo dispositivo chiede di entrare", **senza il nome** (revisione 2, F10: il nome lo scrive chi crea la richiesta), al prossimo controllo (≤ 15 minuti nella finestra d'invito, o aprendo Dispositivi). Dettaglio della richiesta: nome, modello, **account NAS dichiarato da confermare** (B6). **Scansione del QR della chiave** nella stessa stanza (proposta per prima, revisione 2, F5) **oppure** codice letto di persona sullo schermo del dispositivo e digitato, poi la credenziale del telefono → `approve` → `commit`. La schermata d'attesa prende il codice dallo stato del dispositivo (mai da un argomento di navigazione), è sotto `FLAG_SECURE` e nasconde le sovrapposizioni; `MainActivity` ripulisce l'intent d'ingresso e registra per ogni stato solo le destinazioni raggiungibili | Finestra di 15 minuti scaduta: il nuovo dispositivo rifà la richiesta con una **chiave nuova**. Esiti di `commit`: `NewerExists` → risincronizza e riprova; `Fork`, `Blocked` e `NeedsAppUpdate` → messaggi dedicati |
| Senza QR | Indirizzo, utente, aree, id della famiglia e impronta **letti dalla schermata dell'admin** («Configura un familiare», sotto il QR), mai dal NAS (B9); prima di contattare il NAS l'utente conferma "è la rete di casa?". Versione minima 0, con avviso in Diagnostica. Facoltativo: QR "stato della famiglia" (`MCGS1:`) da un dispositivo approvato (M7), che porta anche id e impronta (verifica funzionale, ON-1) | E-O5 |

Reinstallazione o telefono nuovo (E-O7, E-O8): nessuna chiave sopravvive. `LookAlike` avvisa l'admin: "Sembra la reinstallazione di 'Telefono di X': dismetti il vecchio".

### 2.3 Registro, smarrimento, recupero dell'admin (piano 2.4)
**Estensione del formato v1** (P3, dopo la revisione; è lecita perché sul NAS non è ancora stato scritto nulla):

| Cosa | Contenuto |
|---|---|
| `FamilyConfig` | `nasHost` (IP letterale), `nasName`, `nasGuid`, `homeUla[] ≤4`, `nasPower` (`always` oppure `HH:MM-HH:MM`), `wolMac?`, `commonShare`, `commonExclusions[] ≤256` (validate con `SharePath`), `defaults{preset, quality}` |
| Tipo 7 `LostReport` (2 KiB) | `family`, `device` (chi segnala), `target`, `nasUser`, `reportedAt`; in `family/reports/<target>/<versione>` |
| Tipo 8 `DeviceStatus` (2 KiB) | `family`, `device`, `version`, `appVersion`, `roles[]`, `lastSeenAt` (ora del NAS), `registryVersion`; in `devices/<id>/status/<versione>`. Anti-rollback come per le teste; al massimo una pubblicazione al giorno, approfittando di una sessione già aperta |

| Flusso | Passi | Edge case |
|---|---|---|
| Elenco dispositivi | Registro accettato + `DeviceStatus`: nome (con profilo), modello, stato, ruoli, ultima attività, versione | L11 |
| Segnala perso | L'admin fa `changeStatus(LOST)` → `commit`. Il proprietario (decisione 3a) pubblica un `LostReport` firmato da un dispositivo dello stesso account, che l'admin converte. Poi la **guida, in quest'ordine**: segnala → revoca il peer WireGuard → cambia la password NAS (dovranno reinserirla anche gli altri dispositivi dello stesso account) → Samsung Find | E-S1, C15 |
| Autodistruzione | `TrustGuard.preflight()` prima di **ogni** sessione di lavoro (al massimo ogni 15 minuti), più `TrustSyncWorker` una volta al giorno. Se `mustSelfWipe(io)` (o se esiste un `LostReport` valido, con la decisione 3a) → `SelfWipe`: (0) segno "azzeramento in corso"; (1) `destroyDeviceKey`; (2) `FamilyKeyVault.destroy` se è admin; (3) `CredentialVault.wipeAll`; (4) `invalidate` delle sessioni; (5) `LocalDataEraser`; (6) WorkManager cancellato; (7) `SetupState.Wiped`. Riprende da dove si era fermato se il processo muore | E-S2. Un registro non firmato, un rollback o un'equivocazione **non** fanno azzerare (test obbligatorio) |
| Recupero dell'admin | Dispositivo nuovo → *Recupera l'admin* → indirizzo e account dell'admin → elenco di `recovery/` nell'area privata (solo nomi validi) → codice digitato → `RecoveryKit.open` con l'ancora **indipendente dal NAS**: il QR "stato della famiglia" (M7) oppure l'**impronta della famiglia scritta sul foglio** al passo 12 e digitata (revisione 2, F3; senza nessuna delle due il recupero non parte; senza QR la versione minima è 0, con avviso) → nuova chiave del dispositivo → si approva da solo con `admin = true` → il vecchio admin diventa `LOST` o `REVOKED` (scelta dell'utente) | E-S13 |
| Secondo admin | Come il recupero, ma il primo admin resta approvato | proposta 15 |
| Reset della famiglia | Doppia conferma → chiave nuova, id nuovo, kit nuovo, fondazione → guida per riconfigurare ogni dispositivo con il QR nuovo. I dati vecchi vengono ignorati, non cancellati | E-S13, `formato-nas.md` §4.10 |
| Rotazione | Fuori dal minimo della Fase 2. Il kit vecchio si cancella solo con un'azione locale confermata, attraverso un'interfaccia ristretta (review M4) | — |

### 2.4 VPN gestita dall'app (piano 2.5)
Ingressi: `NetworkSnapshot` (casa riconosciuta dal Wi-Fi sottostante, presenza e tipo di VPN), `VpnControlConfig(enabled, tunnelName)`, app in primo piano o no, `NasSessionManager` occupato o libero.

| Situazione | Azione | Verifica ed esito |
|---|---|---|
| Casa, VPN di casa accesa, controllo attivo, **tunnel limitato a casa** | Aspetta che la sessione sia libera (al massimo 2 minuti) → `SET_TUNNEL_DOWN`. Con un tunnel completo (`0.0.0.0/0`) **mai** (revisione 2, F2): "casa" si riconosce da un ULA che un Wi-Fi gemello può imitare, e il tunnel protegge tutto il traffico del telefono; il NAS si raggiunge attraverso il tunnel (`rete-casa-vpn.md` §8, L20) | Entro 10 s non deve più esserci `TRANSPORT_VPN` e il Wi-Fi deve risultare validato. Se si perde la connettività (probabile "VPN sempre attiva con blocco"): subito `UP`, controllo automatico disattivato, spiegazione. Se non succede nulla: "VPN non controllabile" + *Apri WireGuard* |
| Casa, VPN accesa, nessun controllo | NAS attraverso il tunnel (`HOME_LAN_THROUGH_VPN`, più lento), con avviso | Indicatore "VPN accesa in casa" |
| Fuori, VPN spenta, controllo attivo | `SET_TUNNEL_UP` (in primo piano; in background un solo tentativo) | Entro 10 s deve comparire una VPN **di casa**; altrimenti avviso + *Apri WireGuard* (E-R3) |
| Fuori, VPN spenta, nessun controllo | Avviso con pulsante; la Libreria locale resta usabile | E-R3 |
| VPN accesa, NAS muto, rete solo IPv4 | Diagnosi: "rete solo IPv4: il FRITZ!Box (CGNAT) si raggiunge solo in IPv6" | E-R11, L12; nuovo edge case **E-R17** |
| VPN di terzi | `FOREIGN_VPN`, nessun SMB | **E-S16** |
| VPN lenta o che si blocca | `NetworkQualitySignal.isPoor` → lavori automatici fermi, uso interattivo con indicatore | E-R4, E-R14 |

Regole:
- al massimo **un comando per ogni cambio di rete**, con attesa crescente e mai in ciclo;
- "VPN accesa" non si mostra solo perché l'intent è stato inviato;
- ogni profilo ha il suo WireGuard e il suo permesso (E-P20);
- nuovo edge case **E-R16** (impronta di casa cambiata dopo un reset del router): nuova raccolta confermata dall'utente con GUID uguale; dopo l'autenticazione la configurazione firmata deve contenere il nuovo ULA, che aggiorna l'admin.

### 2.5 Wake-on-LAN (piano 2.6), dietro `FeatureFlags.wol = false`
- Funziona solo con `place == HOME`, mai attraverso la VPN.
- Il MAC si inserisce a mano (da Android 10 non si legge l'ARP).
- Invio: UDP porta 9 in broadcast, con il socket legato al Wi-Fi, al massimo 3 invii in 30 s. Poi `tcp445` ogni 2 s, fino a un tetto di 120 s, con la scritta "Accensione del NAS…".
- Esiti: `AlreadyOn`, `Woke(dopo)`, `NoEffect` (E-R6, nessun nuovo tentativo), `NotAllowed`.
- Si prova solo con test JVM. La prova vera è rimandata a quando l'utente reimposterà un orario per il NAS.

### 2.6 Diagnostica (piano 2.7)
| Sezione | Contenuto | Fonte |
|---|---|---|
| Rete e NAS | luogo, rete sottostante, VPN, permesso rete locale, IPv6; stato della sessione, dialetto, **cifratura sì/no**, cifrario, firma; motivo del percorso; interruttore; GUID memorizzato e uguale | P1, P2 |
| Test di connessione | `ConnectionTester` per fasi, con i tempi; **non autentica se l'interruttore è aperto** | P1 |
| Attività | motivi da `ActivityRulesEngine.evaluateAll`; WorkManager (`getStopReason`) | core-rules, P9 |
| Ultimi esiti ed errori | `DiagnosticsLog` | tutti |
| Spazio | database, cache, `noBackupFilesDir` | P8 |
| Consumi del mese | `TrafficLedger` | core-rules |
| Avvisi Android | batteria "Illimitata", bucket, hibernation, permessi, restrizione in background, BFU/`BOOT_COUNT`, `ApplicationExitInfo`, motivi d'attesa dei job | P8 |
| Fiducia | versione del registro, il proprio stato, dispositivi, `RejectionTally`, `newerUnverified`/`listingTruncated`/`needsAppUpdate`, allarme di equivocazione, registro rimasto indietro | P3 |
| Profili | utente di sistema sì/no, ore dall'ultimo battito, avviso "il profilo può restare fermo" | P8 (E-P19) |

**Esportazione**:
- anteprima, poi `ACTION_CREATE_DOCUMENT` (nessun FileProvider);
- solo valori `ExportValue`: numeri, enum, oppure valori **hashati** (account, nomi, IP, ULA). Nessun testo libero;
- un test di catalogo verifica che ogni edge case di Fase 2 che produce un avviso abbia il suo `DiagCode`;
- **elenchi chiusi** (revisione 2, F4): esce solo un codice del `DiagCatalog` (gli altri come `UNKNOWN`, senza valori) e solo
  un valore con una chiave di `ExportKeys`; i test dei contributori veri (anche quelli di rete e fiducia collegati da `:app`)
  verificano che nessuna loro chiave manchi dall'elenco;
- **l'anteprima si ferma** all'apertura della pagina dell'esportazione (revisione 2, F15): un test che finisce o una
  rilettura nel frattempo non cambiano ciò che si salva.

### 2.7 Profili Android sul tablet (piano 2.8)
- Ogni profilo è un'installazione a sé: chiave nel proprio Keystore, credenziali, approvazione, VPN, DataStore. Niente di condiviso nel codice.
- Con `isSystemUser == false` l'app propone il nome "Tablet — profilo …" e avvisa che il lavoro in background può essere rinviato.
- `AppContextSignal.profileActive`: da verificare (T-20) se `UserManager.isUserForeground()` (API 31) si può leggere senza permessi; altrimenti si deduce da primo piano e battito.
- Al ritorno nel profilo, recupero con priorità (E-P19).

## 3. La sessione NAS

### 3.1 Modello
| Aspetto | Implementazione |
|---|---|
| Client | Un `SMBClient` per processo. `SmbConfig`: `withDialects(SMB_3_1_1)`, `withSigningRequired(true)`, `withEncryptData(true)`, `withDfsEnabled(false)`, `withMultiProtocolNegotiate(false)`, `withDirectoryLeasingEnabled(false)`, `withSoTimeout(0)`, buffer da 1 MiB (da tarare), `withSocketFactory(RoutedSocketFactory)` |
| Pool | **Una `Connection` e una `Session` per account**; `DiskShare` in cache per nome. I lavori **non chiudono mai** share o sessione: lo fa solo il gestore |
| Connessione unica | Un `Mutex` sul connect: chi arriva durante una connessione in corso aspetta il suo esito. Mai due `authenticate` in parallelo |
| Ordine dei controlli | (1) `NasRouteGate.current()` deve essere `Allowed`; (2) TCP connect a tempo (6 s, 20 s in "risveglio"); (3) NEGOTIATE: dialetto 3.1.1, `supportsEncryption()`, `serverGuid` uguale a quello memorizzato, scarto dell'orologio; (4) interruttore `Closed`; (5) **ricontrollo del percorso** (stessa rete del socket); (6) `authenticate` una sola volta; (7) `shouldEncryptData()`, `!isGuest`, `!isAnonymous`, firma richiesta. Se fallisce un controllo tra 3 e 7: `close` → `InsecureSession`/`IdentityMismatch`, **nessun nuovo tentativo** |
| Invalidazione | Un `NetworkCallback` cambia `NasRoute`. Se la rete del binding cambia o il percorso diventa `Denied` → `invalidate()` e socket chiusi subito. Chiusura anche dopo 5 minuti di inattività e su `ON_STOP` del processo senza lavori attivi |
| Riconnessione | Su `TransportException`, `SocketException`, `USER_SESSION_DELETED` o `NETWORK_SESSION_EXPIRED`: connessione scartata e ricostruita con la password **già verificata**. Le operazioni idempotenti si ripetono al massimo 2 volte, con attese di 1, 2 e 4 s |
| Concorrenza | `Semaphore(8)` sulle richieste in volo (i crediti di smbj danno errore dopo 5 s di attesa) |
| Annullamento | `runInterruptible(Dispatchers.IO)` + `withTimeout`: 15 s in interattivo, 60 s in background, 60 s per la prima operazione che tocca i dischi. Dopo un timeout la connessione è **sospetta** e si chiude |
| Blocchi silenziosi (E-R14) | Watchdog per ogni trasferimento: sotto 32 KiB/s per 20 s → "bloccato" → connessione chiusa, `isPoor = true` per 10 minuti, ripresa più tardi |
| Risveglio dischi (E-R1, E-R15) | Se la TCP è rapida ma il primo TREE_CONNECT o elenco supera i 2 s → `WakingUp` ("Risveglio del NAS…"). La sonda di salute è **solo TCP 445**, al più una NEGOTIATE; mai `list`, `stat` o `getShareInformation`. `lastDiskTouchAt` permette ai lavori leggeri di approfittare dei dischi già attivi |
| Orologio | `SessionNasClock` = `elapsedRealtime` + ora del NAS letta alla NEGOTIATE. Senza una sessione fresca, le operazioni firmate ne aprono prima una |
| `SmbNasFileSystem` | `read`: `EndOfFile` dalla CREATE, e se supera `maxBytes` → `TooLarge` senza allocare; poi lettura con tetto `maxBytes+1`. `list`: `Directory.iterator` con limite e `truncated`. `publishExclusive`: `mkdir` (se esiste già, va bene) → temporaneo `tmp/<deviceId>-<casuale>` con `FILE_CREATE` → scrittura → `flush` → verifica della lunghezza → `rename(path, false)`. In caso di collisione cancella il **proprio** temporaneo e restituisce `ALREADY_EXISTS` |
| R8 | `consumer-rules.pro` di `core-nas`: `-keep class net.engio.mbassy.** { *; }`, `-keepclassmembers class * { @net.engio.mbassy.listener.Handler *; }`, più i `-dontwarn` necessari. Esclusione di `META-INF/versions/9/OSGI-INF/MANIFEST.MF`; nessun provider SLF4J |

### 3.2 "SMB solo a casa o via VPN" su Android
1. `core-network` registra tre `NetworkCallback`: la rete predefinita; `TRANSPORT_WIFI` + `NOT_VPN`, cioè il Wi-Fi sottostante, visibile anche con la VPN accesa; `TRANSPORT_VPN`. Da `LinkProperties` legge indirizzi, rotte, DNS, domini e server DHCP. **Senza permesso di posizione**, quindi senza SSID.
2. **Casa** = un prefisso ULA /48 del Wi-Fi coincide con `HomeFingerprint.ulaPrefixes`. L'ULA è casuale per ogni FRITZ!Box: l'amico con la stessa sottorete non passa. **VPN di casa** = le rotte o i DNS della VPN coprono la sottorete o l'ULA di casa (da verificare con T-17/M11), più la conferma del GUID.
3. Binding:
   - casa senza VPN → la `Network` del Wi-Fi;
   - casa con VPN accesa e non controllabile → la `Network` della VPN (WireGuard non si può aggirare);
   - fuori casa → la `Network` della VPN.
   Sempre `network.socketFactory.createSocket()` seguito da `connect(InetSocketAddress(ipLetterale, 445), timeout)`. Mai `bindProcessToNetwork`, mai DNS.
4. Rete non riconosciuta → `Denied`: **non parte nemmeno la connessione TCP**.

### 3.3 Interruttore di sicurezza sull'autenticazione (E-R8, E-S12, C5)
- È persistente: DataStore in `noBackupFilesDir`, con chiave `(nasId, utente, generazione della credenziale)`.
- Si apre al **primo** codice d'autenticazione elencato nel §3.4. Gli errori di rete non lo aprono.
- Finché è aperto, **nessun** chiamante arriva a `authenticate`.
- Si chiude solo quando l'utente salva una nuova password (`onNewPasswordSaved`, che aumenta la generazione); a quel punto si fa un solo tentativo.
- Test obbligatorio: 10 coroutine e 2 lavori in parallelo con la password sbagliata devono produrre **esattamente 1** chiamata `authenticate`, anche dopo il riavvio del processo.
- Revisione 2, F9: la promessa vera è **al massimo 2**. Un tentativo interrotto durante il login (processo morto, oppure timeout o connessione caduta dopo l'invio della risposta NTLM, che può essere un rifiuto ritardato apposta) resta "non confermato"; due di fila aprono l'interruttore (`MAX_UNCONFIRMED`, `nas-sessione.md`, L24).

### 3.4 Errori → messaggi
| Stato SMB | `NasError` | Messaggio | Interruttore | Edge case |
|---|---|---|---|---|
| `0xC000006D` LOGON_FAILURE, `0xC000006A` | `AuthFailed` | "La password del NAS non è più valida (forse è stata cambiata). Inseriscila di nuovo." | apre | E-R8 |
| `0xC0000234` LOCKED_OUT | `AccountLocked` | "L'account del NAS è bloccato: va sbloccato dalla dashboard del NAS." | apre | E-S12 |
| `0xC0000072` DISABLED | `AccountDisabled` | "Questo account è disattivato sul NAS." | apre | — |
| `0xC0000071`, `0xC0000224` | `PasswordExpired` | "La password è scaduta: cambiala dal NAS." | apre | — |
| `0xC000006E/6F/70` | `AccountRestricted` | "Il NAS non permette l'accesso a questo account adesso." | apre | — |
| guest, anonimo, `SMB2GuestSigningRequiredException` | `InsecureSession(GUEST)` | "Il NAS ha offerto un accesso ospite: rifiutato." | no | C3 |
| nessuna cifratura o dialetto diverso da 3.1.1 | `InsecureSession(NO_ENCRYPTION/DIALECT)` | "Il NAS non ha concesso una connessione cifrata: nessun dato inviato." | no | C3 |
| GUID diverso | `IdentityMismatch` | "A questo indirizzo risponde un NAS diverso da quello configurato. Nessuna password inviata." | no | E-R7, E-S10 |
| `0xC0000022` | `PermissionDenied` | "Non hai il permesso per questa cartella." | no | E-R10, E-N9 |
| `0xC00000CC` | `ShareMissing` | "La cartella condivisa «…» non esiste più sul NAS." | no | E-R10 |
| `0xC000007F` con spazio libero globale > 0 | `QuotaExceeded` | "Hai esaurito lo spazio del tuo account (quota), il NAS ha ancora spazio." | no | E-N13 |
| `0xC000007F` con NAS pieno | `NasFull` | "Il NAS è pieno: backup in pausa, la consultazione continua." | no | E-R9 |
| `0xC0000043`, `0xC0000054` | `FileBusy` | "Il file è in uso dal PC: riprovo più tardi." | no | E-N13 |
| `0xC0000035` | `Exists` (non è un errore) | — | — | E-B3 |
| `0xC0000034`, `0xC000003A` | `NotFound` | — | — | — |
| `0xC0000203`, `0xC000035C` | `SessionExpired` | (riconnessione silenziosa) | no | — |
| TCP rifiutata | `ServiceDown` | "Il NAS è acceso ma la condivisione file non risponde." | no | — |
| timeout | `Timeout` / `WakingUp` | "Il NAS non risponde (forse si sta risvegliando)…" | no | E-R1 |
| percorso negato | `NotAllowedHere(reason)` | "Fuori casa: accendi la VPN" / "Rete non riconosciuta: nessuna connessione al NAS" / "VPN non di casa attiva" | no | E-S10, E-R3, E-S16 |
| rete locale negata | `LocalNetworkBlocked` | "Android non permette all'app di usare la rete locale." (controllato **prima** di "NAS spento") | no | android.md 7.3 |
| watchdog | `Stalled` | "Trasferimento bloccato: rete lenta, riprendo più tardi." | no | E-R14 |
| "Not enough credits" | interno | (limitatore, nuovo tentativo) | no | — |

Per distinguere quota esaurita e disco pieno si chiama `getShareInformation()` **solo** dopo un `DISK_FULL`, perché tocca i dischi.

### 3.5 Indicatore di stato globale
| Condizione | Testo |
|---|---|
| HOME e `Ready` | In casa · NAS collegato |
| AWAY_VPN_WIFI / AWAY_VPN_MOBILE | Fuori casa · via VPN (Wi-Fi) / (dati mobili) |
| ROAMING | Roaming · attività automatiche ferme |
| VPN_OFF | VPN spenta · Apri WireGuard |
| UNTRUSTED | Rete non riconosciuta · nessuna connessione al NAS |
| FOREIGN_VPN | VPN non di casa attiva |
| `WakingUp` | Risveglio del NAS… |
| percorso consentito ma la sonda fallisce | NAS non raggiungibile |
| `AuthBlocked` | Serve la password del NAS |
| OFFLINE | Offline · Libreria sul telefono |

## 4. Pacchetti di lavoro

### 4.1 Ordine
```
P0 (da solo) ──┬── P1 core-nas ───────────┐
               ├── P2 core-network ───────┤
               ├── P3 registro/ciclo vita ┤ (core-trust solo dopo la revisione)
               ├── P4 fondazione/ingresso ┤
               ├── P5 core-qr ────────────┤
               ├── P6 UI onboarding ──────┤
               ├── P7 UI impostazioni ────┤
               └── P8 Diagnostica ────────┴── P9 integrazione app ── check.ps1 + security-review
poi: test d'integrazione dal PC (P1, con credenziali) → verifica sul campo (§5.4)
```

### 4.2 Regole comuni dei brief
- **Mai** toccare file fuori dal proprio perimetro. Dipendenze, permessi o componenti nuovi si chiedono all'orchestratore, che li aggiunge in un P0-bis.
- Stringhe in italiano, nelle risorse del proprio modulo. Regole Fluid (nessun valore scritto a mano; vedi `NoHandWrittenStyleTest`).
- Log solo con `GalleryLog` e `Redacted`: mai utente, password, IP, nomi o percorsi.
- SMB e rete solo su IO; niente rete in coroutine legate a una schermata, salvo l'onboarding interattivo.
- Almeno un test per modulo. Screenshot in `docs/screenshots/2-<pacchetto>/`: chiaro e scuro, telefono (412 dp) e tablet (1280 dp), testo grande.
- Report finale: cosa è stato fatto, test, edge case coperti (per ID), punti aperti, cosa provare sul campo.

### 4.3 I pacchetti
| ID | Obiettivo | Perimetro esclusivo | Dipende da | Fatto quando | Modello | Telefono/NAS |
|---|---|---|---|---|---|---|
| **P0 Fondamenta e contratti** | Scheletro di tutti i moduli, dipendenze, policy, contratti, navigazione e gate | `settings.gradle`, `build.gradle` (`galleryModules`), `versions-app.gradle`, `gradle/verification-metadata.xml`, `gradle/verify-release-manifest.gradle`, `tools/check.ps1` (vieta `.testing.` nel dex di release), `docs/sicurezza.md` §4-bis/§8 (righe nuove), `app/**` (scaffold), creazione di `core-network`, `core-nas`, `core-family`, `core-qr`, `feature-*` (build, manifest, `contract/`, null-object, fake in `testing/`, 1 test), `core-common/diagnostics` e `featureflags`, contratto `core-rules/platform/health/BackgroundHealthReader.kt` + dipendenza `androidx.core` | — | `check.ps1` verde. Dipendenze con checksum rigenerati e diff letto: smbj 0.15.0, bcprov 1.85.2, mbassador, asn-one, slf4j-api, zxing-core, CameraX, work-runtime in `:app`. Permessi in policy e in §4-bis: `INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA`, `POST_NOTIFICATIONS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `READ_MEDIA_*`, `ACCESS_MEDIA_LOCATION`, `com.wireguard.android.permission.CONTROL_TUNNELS` + `<queries>`, quelli di WorkManager. `SystemJobService` ammesso (`BIND_JOB_SERVICE`), `DiagnosticsReceiver` rimosso. `MainActivity : FragmentActivity` + `AppLockGate`; `RootViewModel` con i fake porta a onboarding, attesa e principale; screenshot dello scaffold | opus | no |
| **P1 Sessione NAS** | §3 per intero | `core-nas/**` (consumer rules comprese), `docs/nas-sessione.md` | P0 (contratti di `core-network` tramite fake) | Test JVM verdi: tabella §3.4 completa; interruttore con 1 sola `authenticate` sotto concorrenza e dopo un riavvio; nessuna autenticazione con percorso negato, GUID diverso, cifratura non offerta o dialetto diverso; riconnessione dopo una caduta; invalidazione al cambio di rete; watchdog con tempo virtuale; `publishExclusive` (collisione, crash tra scrittura e rinomina → pulizia), `read` con dimensione dichiarata enorme, `list` troncato, nomi che differiscono solo per maiuscole (simulatore con semantica Samba). Suite `@NasIntegration` scritta (§5.2). Facoltativo: `ServerSideCopy` tra share (FSCTL, come nello spike) | opus | sì (integrazione) |
| **P2 Rete, casa, VPN, WoL** | §1.2 `core-network`, §2.4, §2.5, §3.2 | `core-network/**`, `docs/rete-casa-vpn.md` | P0 | Test JVM a tabella su `LinkFacts` sintetici: casa, casa + VPN, FRITZ!Box di un amico con la stessa sottorete e ULA diverso (E-S10), hotspot a consumo (E-R13), captive portal, due reti (E-R12), solo IPv4 + VPN (E-R11/E-R17), VPN di terzi (E-S16), ULA cambiato (E-R16). `VpnAutomation`: 10 entrate e uscite simulate seguono la regola, senza cicli, con l'euristica del blocco. WoL con tempo virtuale (E-R5, E-R6). Strato Android sottile, Robolectric dove possibile; contributo alla Diagnostica | sonnet | sì (T-17, T-18, T-19) |
| **P3 Registro e ciclo di vita** | §2.2 lato admin, §2.3 | `core-family/.../registry/**`, `.../lifecycle/**`, `.../trust/**` (`TrustStateStore`, `TrustGuard`); dopo la revisione `core-trust` (FamilyConfig, tipi 7 e 8, `NasLayout`, `TrustLimits`, test) e `docs/formato-nas.md`; `docs/famiglia-dispositivi.md` | P0; revisione di `core-trust` chiusa (per la parte sul formato) | Test con NAS in memoria ostile: approvazione con codice giusto e sbagliato, finestra scaduta, esiti di `commit`; E-S14; `LostReport` e decisione 3; **ordine dell'azzeramento** (chiave prima dei dati, con fake che registrano l'ordine) e ripresa a metà; registro falso, rollback o equivocazione **non** fanno azzerare; recupero dell'admin, secondo admin e reset completi; `DeviceStatus` con anti-rollback; contributo alla Diagnostica | opus | sì (due dispositivi) |
| **P4 Fondazione e ingresso** | §2.1 (logica dei 14 passi), §2.2 lato nuovo dispositivo, inviti e QR | `core-family/.../onboarding/**`, `.../invite/**`, `.../survey/**`, `.../setup/**` | P0 (contratti di P1, P2, P3 tramite fake) | `OnboardingCoordinator` persistente e riprendibile (E-O9); passo 12 completo (rilettura e apertura del kit **prima** della fondazione); `ALREADY_EXISTS` → "famiglia già esistente"; `InviteCodec`/`DeviceKeyQr`/`FamilyStateQr` con input ostili; E-O1…E-O8; `FolderSurvey` su elenchi sintetici (copertine, film) | opus | sì |
| **P5 QR** | Generazione e scansione | `core-qr/**` | P0 | Andata e ritorno su JVM: codifica → rendering → decodifica con rotazione, scala e rumore; QR da 600 caratteri leggibile; schermate del permesso (negato → ripiego manuale) con screenshot; `FLAG_SECURE` sul QR d'invito | sonnet | sì (fotocamera) |
| **P6 UI Onboarding** | Tutte le schermate di §2.1 e §2.2 (nuovo dispositivo) | `feature-onboarding/**` | P0 (fake di P4 e P5) | Test Compose (Robolectric) su ogni percorso; screenshot di **ogni** passo e stato d'errore (E-O1…E-O8, E-R8, E-S12, E-S15); `SecureInput`, `FLAG_SECURE` e protezione dalle sovrapposizioni; TalkBack, testo grande | sonnet | no |
| **P7 UI Impostazioni e Dispositivi** | Dispositivi, approvazione, Famiglia (invito, kit, secondo admin, reset, QR "stato"), Account NAS (password, cambio indirizzo E-R7), Rete e VPN, Sicurezza (blocco app), avviso sul backup, "Da completare" | `feature-settings/**` | P0 (fake di P3 e P4) | Screenshot di ogni schermata e stato; approvazione con codice o scansione; guida "segnala → revoca"; doppia conferma per "perso" e reset | sonnet | no |
| **P8 Diagnostica** | §2.6 | `feature-diagnostics/**`, `core-rules/.../platform/health/` (implementazione) | P0 | Catalogo dei `DiagCode` con un test che copre gli edge case di Fase 2; aggregatore; esportazione con anteprima, con un test che inserisce nomi, IP e utenti e verifica che **non compaiano**; `BackgroundHealthReader` (Robolectric); screenshot | sonnet | sì (letture reali) |
| **P9 Integrazione app** | Collegare le implementazioni vere | `app/**`, `gradle/verify-release-manifest.gradle` (passa a P9 dopo P0) | P1–P8 | Hilt con le implementazioni vere; `TrustSyncWorker` (una volta al giorno, più finestra d'invito, apertura dell'app e arrivo a casa); notifica all'admin con `PendingIntent` immutabile che si limita ad aprire l'app; `LocalDataEraser`; `AppContextSignal`; `VpnAutomation` collegato al ciclo del processo; indicatore globale; `check.ps1` verde; APK debug e release pronti | opus | sì |

> Nota dell'orchestratore sul consumo di crediti: P2 è passato a **sonnet** (logica a tabella ben specificata). Opus resta solo per P0, P1, P3, P4 e P9, che sono le fondamenta.

## 5. Test senza dispositivo e piano sul campo

### 5.1 Livelli
| Livello | Dove | Cosa |
|---|---|---|
| JVM puro | tutti i `core-*` | logica: cancello, impronta, macchine a stati, interruttore, errori, codec, tetti |
| Simulatore SMB | `core-nas/src/main/.../testing/InMemorySmbBackend` | semantica Samba: nomi senza distinzione di maiuscole, `FILE_CREATE` esclusivo, `rename` che non sovrascrive, `ACCESS_DENIED`, `DISK_FULL`/quota, GUID, scarto d'orologio, varianti senza cifratura o con guest, cadute, blocchi, latenza. `core-nas` astrae smbj dietro `SmbTransport`; l'adattatore `SmbjTransport` è sottile e si prova contro il NAS |
| Robolectric | adattatori Android, Compose UI | SDK 35 |
| Roborazzi | `core-qr`, `feature-*`, scaffold | ogni schermata e stato, chiaro e scuro, telefono e tablet, testo grande |
| Integrazione dal PC | `core-nas` (`@NasIntegration`, saltata senza il file delle credenziali) | smbj contro 192.168.178.58 dal PC cablato |
| Strumentati | `core-security`, `core-trust` (esistenti) | Keystore sull'S25 |

### 5.2 NAS vero senza telefono
- **Facoltativo, con l'ok dell'orchestratore**: un test JVM che fa **solo la NEGOTIATE** verso 192.168.178.58, senza autenticare (nessun rischio di blocco). Raccoglie dialetto, `supportsEncryption`, cifrario, `ServerGuid` letto 3 volte, `getTimeOffsetMillis`, tempi della `SocketFactory`.
- **Con l'utente**: credenziali in `%USERPROFILE%\.mycloudgallery\nas-it.properties`, **scritte dall'utente**, mai nel repository né nei log. Si usano solo le share di prova. La suite verifica `publishExclusive` reale (collisione, rinomina, maiuscole), la pulizia di `tmp/`, `ShareProbe`, `read` con tetto, il GUID dopo la riconnessione. M9 (**un solo** tentativo con password sbagliata, solo sull'account di prova) richiede il consenso esplicito.

### 5.3 Server SMB locale: nessuna opzione realistica
| Opzione | Esito |
|---|---|
| SMB server di Windows | modifica di sistema da admin, porta 445 occupata, semantica diversa → **esclusa** |
| Samba in Docker o WSL | non installati; installarli modifica il sistema → **esclusa** |
| impacket `smbserver` | niente SMB 3.1.1 con cifratura (la nostra configurazione obbligatoria) → **sconsigliata** |
| Server SMB Java | SMB3 solo nelle edizioni commerciali → **esclusa** |

Si procede così: simulatore in memoria per la logica, poi il NAS vero dal PC, poi telefono e tablet.

### 5.4 Verifica sul campo (passi per l'utente)
**A. Preparazione (utente, ~30 minuti)**
1. Dashboard del NAS:
   - crea l'utente `test2`;
   - crea la share `test-comune` con lettura e scrittura per `test` e `test2`;
   - crea la share privata `test2`;
   - verifica che `test` **non** abbia accesso a `NAS` e `Public`. Se le share sono pubbliche non si può impedire: va annotato.
2. Sul PC crea il file delle credenziali (§5.2) per `test`.
3. Attiva il debug USB su S25 e tablet. **Solo account di prova** (`test`, `test2`) con la build debug (revisione 2, F14): è `debuggable`, quindi con ADB `run-as` e il debugger si usano la chiave del Keystore e le credenziali, e le eccezioni finiscono intere nel logcat. Per l'uso vero con gli account della famiglia serve l'APK di **release** firmato (L25).
4. Sul tablet, aggiungi l'utente "Prova".
5. Facoltativo, per la VPN: sul FRITZ!Box crea un peer WireGuard per S25, tablet e profilo "Prova"; installa WireGuard, importa la configurazione e attiva "Allow remote control apps".

**B. Dal PC (agente, ~15 minuti)**: `gradlew :core-nas:testDebugUnitTest -PnasIntegration`; si annotano dialetto, cifratura, GUID ed errori.

**C. Dispositivi**
| # | Prova | Atteso |
|---|---|---|
| F1 | S25: debug, onboarding admin con `test` (area comune `test-comune`, privata `test`) | < 5 minuti; il kit si apre con il codice; `family/registry/…1` presente; Diagnostica "cifrata: sì" |
| F2 | Tablet: *Entra con QR* (QR dall'S25), account `test2` | Prima dell'approvazione: "in attesa, sola lettura" (E-S14); il codice coincide; l'S25 vede la richiesta; dopo: Ready |
| F3 | QR di un'altra famiglia | Rifiutato (E-O5) |
| F4 | S25 sull'hotspot del tablet | "Rete non riconosciuta", **nessuna** TCP verso la 445 (E-S10) |
| F5 | Password di `test2` cambiata dalla dashboard | Un solo tentativo, "Serve la password", poi ripartenza (E-R8) |
| F6 | Profilo "Prova" segnato come perso dall'S25 | Azzerato; riconfigurato prende un id nuovo (E-S2) |
| F7 | Tablet come *secondo admin*, poi revocato | Diventa admin, poi revocato |
| F8 | (WireGuard) controllo VPN casa/fuori, 10 volte | Spenta a casa, accesa fuori; su una rete solo IPv4 compare la diagnosi E-R11 |
| F9 | `adb shell am compat enable RESTRICT_LOCAL_NETWORK dev.pampa.mycloudgallery.debug` (poi `disable`) | "Rete locale non consentita", non "NAS spento" |
| F10 | Due profili sul tablet, 30 minuti su "Prova" | Isolamento; la Diagnostica spiega il ritardo (E-P19) |
| F11 | APK di **release** firmato sull'S25 → Test di connessione | Funziona con R8 (mbassador e smbj integri) |
| F12 | Esportazione della Diagnostica | Nessun dato personale |

## 6. Cose che richiedono l'utente
| Cosa | Perché | Nel frattempo |
|---|---|---|
| Password degli account di prova (file sul PC e digitazione sui telefoni) | Integrazione e campo | Simulatore e fake; prova della sola NEGOTIATE |
| Utente `test2`, share `test-comune` e `test2` | Simulare la famiglia senza dati veri | Scenari multi-account con i fake |
| Approvare permessi e dipendenze nuove (D4, `CAMERA`, `POST_NOTIFICATIONS`, `CONTROL_TUNNELS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, media, WorkManager) | Policy di sicurezza | P0 li aggiunge marcati come "proposta"; sono reversibili |
| Decisioni di `formato-nas.md` §13 | Cambiano P3 e P7 | Opzioni consigliate (a) |
| Nuovi edge case E-O9, E-S16, E-R16, E-R17 | Completare il §6 del piano | Già trattati |
| NAS sempre acceso e WoL spento | Coerenza dei documenti e della fascia notte | Fascia notte 23–07 |
| WireGuard sul FRITZ!Box e app installata | Prove VPN | Automazione verificata con i fake |
| Profilo secondario sul tablet | Prove del 2.8 | Logica su JVM |
| Telefono e tablet collegati, chiave di release | Installazione e R8 | Robolectric e Roborazzi |
| Consenso alla prova "password sbagliata" | M9 | Codici ricavati da MS-ERREF |
| Revisione degli screenshot | Gate grafico del piano | Generati in anticipo |

## 7. Rischi
| Rischio | Effetto | Mitigazione |
|---|---|---|
| smbj giovane; R8 toglie i gestori di mbassador | Chiusure non notificate in release | Regole consumer; F11; integrazione dal PC; limitatore e timeout propri |
| Account bloccato da più tentativi | Famiglia senza accesso | Interruttore persistente a richiesta unica; test "1 tentativo"; account admin vietato |
| Falsa "casa" (E-S10) o VPN di terzi | Risposta NTLM a un host estraneo | ULA forte, impronta della VPN, GUID prima dell'autenticazione, socket legato; nessuna TCP sulle reti negate |
| Falso "fuori casa" (ULA assente o cambiato) | App inutilizzabile a casa | Nuova raccolta guidata con GUID e configurazione firmata (E-R16); Diagnostica chiara |
| GUID instabile (M8) | Richieste di verifica a ogni riavvio | Controllo morbido, deciso dopo la misura |
| Intent di WireGuard rifiutati in background | VPN non gestita | Tentativo con verifica, in primo piano, niente cicli, *Apri WireGuard*; piano B (libreria `tunnel`) rimandato |
| Rete interrotta in background / onboarding interrotto | Passi a metà | Avanzamento salvato, scritture idempotenti, lavori solo in WorkManager |
| `core-trust` ancora in revisione | Conflitti | P3 tocca `core-trust` solo dopo la chiusura |
| Conflitti sui file di build | Merge rotti | Solo P0 (poi P9) li tocca |
| Manifest di release (WorkManager, permessi) | `assembleRelease` fallisce | P0 aggiorna la policy; `tools:node="remove"` per `DiagnosticsReceiver` |
| Azzeramento innescato da dati remoti | Perdita di dati locali | Solo con lo stato `lost` in un registro **firmato e accettato**; test con registri falsi |
| Notifica all'admin non istantanea | Attesa del familiare | Finestra d'invito; testo onesto |
| Profili del tablet fermi | Stime sbagliate | Testi onesti, recupero al ritorno, misura |
| Android 17 non coperto da Robolectric (SDK 35) | Sorprese | F9, F10; ramo target 37 pronto |
| Onboarding oltre 5 minuti | "Fatto quando" del 2.2 non raggiunto | Passi 5, 10 e 11 saltabili; tempo misurato in F1 |

### File cruciali
- `core-trust/.../nas/NasFileSystem.kt`: il contratto che `core-nas` implementa (`list`, `read`, `publishExclusive`, `NasClock`).
- `core-trust/.../registry/FamilyAuthority.kt`, `RegistrySync.kt`, `Registration.kt`: fondazione, approvazione, `commit` ed esiti.
- `core-rules/.../Signals.kt` e `SystemState.kt`: i segnali che la Fase 2 implementa.
- `spike/nas-probe/.../SmbTests.kt`, `SmbSupport.kt`, `NetUtil.kt`: uso di smbj verificato sul NAS (cifratura, copychunk tra share).
- `gradle/verify-release-manifest.gradle`, `versions-app.gradle`, `settings.gradle`, `app/src/main/AndroidManifest.xml`: la policy che P0 estende.
