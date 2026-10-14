# Sessione NAS (`core-nas`, Fase 2 P1; lettura dei media, Fase 4 P1)

> Implementa il §3 di `docs/fase2-progetto.md` e, dalla Fase 4, la riga P1 del §4.4 di `docs/fase4-progetto.md`
> (lettura dei media, sorvegliante a progresso, simulatore del §5.2, guardia dell'area di prova del §5.4, recinzione
> D33). Contratti in `core-nas/.../contract/` (P0), contratto di fiducia in `core-trust/.../nas/NasFileSystem.kt`.
> Riferimenti: `docs/research/smb-vpn.md`, spike `nas-probe`. La parte della Fase 4 e' descritta in fondo
> ("Fase 4, P1").

## Come funziona

```
NasRouteGate (P2) ──▶ SmbNasSessionManager ──▶ SmbTransport ──▶ SmbjTransport (smbj 0.15.0)
                         │   │   │                        └──▶ testing/InMemorySmbBackend (simulatore Samba)
                         │   │   └─ PersistentAuthCircuitBreaker (DataStore, per (GUID, utente))
                         │   └─ OperationMonitor (scadenze, watchdog, risveglio) + Semaphore(8)
                         └─ SmbNasFileSystem / SmbNasBrowser / SmbShareProbe / SmbNasHousekeeping / SmbServerSideCopy
```

| Pezzo | File | Cosa fa |
|---|---|---|
| `SmbTransport` | `smb/SmbTransport.kt` | Il confine con la libreria: connect (TCP legata + NEGOTIATE), autenticazione, share, file. Nessun tipo smbj fuori di qui. Errori come eccezioni proprie (`SmbStatusException(ntStatus)`, `SmbConnectionLostException`…) |
| `SmbjTransport` | `smb/SmbjTransport.kt` | Adattatore sottile: solo 3.1.1, firma obbligatoria, cifratura annunciata, niente DFS/multiprotocollo/directory lease, `SO_TIMEOUT` 0. Socket dal `SocketBinding` del percorso, IP letterale, connect a tempo. Azzera la copia della password di smbj dopo il login |
| `SmbNasSessionManager` | `session/SmbNasSessionManager.kt` | Il `NasSessionManager`: una connessione/sessione per account, connect a volo unico, ordine dei controlli, riconnessione, invalidazione, inattività, stato |
| `SmbErrors` | `session/SmbErrors.kt` | La tabella §3.4 in un solo posto: NTSTATUS/eccezione → `NasError` + che fare (riconnetti, sospetta, crediti, definitivo) |
| `NasErrorMessages` | `NasErrorMessages.kt`, `res/values/strings.xml` | Il messaggio in italiano di ogni `NasError` |
| `PersistentAuthCircuitBreaker` | `breaker/` | L'interruttore §3.3, persistente in `noBackupFilesDir/datastore/nas_auth_breaker.preferences_pb` |
| `SessionNasClock` | `session/Clocks.kt` | `NasClock` = ora del NAS letta alla NEGOTIATE + `elapsedRealtime` |
| `SmbConnectionTester` | `tools/` | Test per fasi con una connessione propria; stesso interruttore della sessione |
| `NasStatusMonitor` | `status/` | `NasReachabilitySignal` (sessione + sonda solo TCP 445) e `NetworkQualitySignal` (10 min dopo un blocco) |

**Ordine dei controlli a ogni connessione** (§3.1): (1) percorso `Allowed`, altrimenti nessun socket → (2) TCP sul
binding, 6 s (20 s dopo un timeout o in risveglio) → (3) NEGOTIATE: 3.1.1, cifratura offerta, GUID = memorizzato;
solo allora si ancora l'ora del NAS → (4) interruttore chiuso → (5) di nuovo il percorso, stessa rete del socket →
(6) credenziali e **una** `authenticate`, con la **firma della risposta finale di SESSION_SETUP verificata** (Fase 4
P1) → (7) cifratura davvero attiva, né guest né anonima; da qui ogni risposta in chiaro chiude la connessione. Un fallimento tra 3 e 7
chiude la connessione; l'esito resta "agganciato" sulla stessa rete e lo stesso account (nessun nuovo tentativo
automatico) finché non cambiano rete o account, o `invalidate(USER_REQUEST/CREDENTIALS_CHANGED/...)`.

**Operazioni.** `runOp` = sessione (connettendo se serve) → share dalla cache → permesso del `Semaphore(8)` →
biglietto del sorvegliante → chiamata bloccante su `Dispatchers.IO` (`runInterruptible`). Il sorvegliante gira a passi
di 1 s solo mentre ci sono operazioni: oltre la scadenza (15 s interattivo, 60 s background, 60 s la prima che tocca
i dischi) o sotto 32 KiB/s per 20 s **chiude la connessione** (l'unico modo di sbloccare smbj su un socket muto):
`Timeout`/`Stalled`, nessun nuovo tentativo. La prima operazione sui dischi oltre 2 s porta lo stato a `WakingUp`.
Cadute e sessioni scadute: connessione scartata, ricostruita con la password già verificata, operazione idempotente
ripetuta al massimo 2 volte (attese 1 e 2 s). "Not enough credits": stessa connessione, nuovo tentativo.

**`SmbNasFileSystem`.** `read`: la dimensione dichiarata decide prima di allocare (`TooLarge` senza leggere un byte);
poi lettura con tetto `maxBytes+1` comunque (un server che mente non fa allocare di più). `list`: iteratore a pagine,
`truncated`, cartella assente = vuota. `publishExclusive`: temporaneo `.mycloudgallery/tmp/<deviceId>-<32 hex>` con
`FILE_CREATE` → scrittura → flush → verifica della lunghezza → `rename(…, false)`; collisione (anche di maiuscole) →
il proprio temporaneo si cancella e il risultato è `ALREADY_EXISTS`. Caduta prima della rinomina: si cancella il
temporaneo e si ripete; caduta **durante** la rinomina: si rilegge il file finale (stessi byte = `CREATED`, altri =
`ALREADY_EXISTS`). Un processo morto lascia il temporaneo: lo toglie `SmbNasHousekeeping` (solo nomi
`<proprioId>-<32 hex>`, età misurata con l'ora del NAS).

## Invarianti (ognuno ha un test)

- Nessun socket se il percorso è negato; nessuna password se il GUID è diverso, la cifratura non è offerta, il
  dialetto non è 3.1.1, l'interruttore è aperto o il percorso è cambiato tra NEGOTIATE e login (`SessionTest`).
- 10 coroutine + 2 lavori (su un secondo gestore) con la password sbagliata → **esattamente 1** `authenticate`, anche
  dopo il "riavvio" (nuovo DataStore sullo stesso file); si richiude solo con `onNewPasswordSaved` (`AuthBreakerTest`).
  Gli errori di rete non aprono; un fallimento della generazione precedente non riapre; 2 tentativi rimasti a metà
  (processo morto) aprono. Quindi la promessa vera è **al massimo 2** tentativi con la stessa password dopo crash ripetuti
  durante il login (`MAX_UNCONFIRMED`), uno solo altrimenti.
- Revisione 2, F9: un **timeout o una connessione caduta durante l'autenticazione** (`Timeout`/`Stalled` dopo la traccia)
  resta "non confermato": può essere un rifiuto ritardato apposta da chi sta in mezzo. Due di fila aprono l'interruttore;
  un login riuscito toglie il segno. Un timeout prima del login (TCP, NEGOTIATE) non conta (`AuthBreakerTest`).
- Mai più di 8 richieste in volo; una sola connessione e un solo login per molti chiamanti.
- I lavori non chiudono mai share o sessione; le chiude solo il gestore (cambio di rete, inattività 5 min, sospetto).
- Mai sovrascrivere, mai cancellare file altrui; `read` non alloca oltre `maxBytes+1`.
- Log solo con `GalleryLog`, senza utente, password, IP, nomi o percorsi (`NasAddress`, `ShareMissing` e
  `Negotiation` hanno `toString` neutri).

Test JVM: `gradlew.bat :core-nas:testDebugUnitTest` (78 test nella Fase 2, 141 con la Fase 4 P1; tabella §3.4 in `ErrorTableTest` con Robolectric per le
stringhe; tempo virtuale in `WatchdogTest`; simulatore `testing/InMemorySmbBackend`, riusabile dai moduli a valle).

## Suite d'integrazione (`@NasIntegration`, dal PC cablato)

```powershell
gradlew.bat :core-nas:testDebugUnitTest -PnasIntegration --tests "*.integration.*"
```

- `NasNegotiateProbeTest`: **solo NEGOTIATE**, nessuna credenziale. Scrive `core-nas/build/nas-it/negotiate.txt`.
- `NasIntegrationSuiteTest`: parte solo se esiste `%USERPROFILE%\.mycloudgallery\nas-it.properties`, scritto
  dall'utente (mai nel repository, mai letto ne' stampato dai log). Chiavi in `integration/NasIntegration.kt`
  (`ItCredentials`):
  ```properties
  user=test
  password=...
  share=test
  # facoltative (senza, i test che le usano risultano saltati)
  host=192.168.178.58
  guid=               # se manca si legge dalla NEGOTIATE
  share2=test-comune  # copia lato server tra share
  m9.consent=yes      # SOLO con consenso esplicito: un tentativo con password sbagliata (M9)
  # Fase 4 (docs/fase4-progetto.md §5.4)
  user2=test2         # secondo account: l'area privata del primo non si vede
  password2=...
  privateShare2=test2
  chaos.rounds=400    # P15
  chaos.bigFileMb=200 # P15
  ```
  **Dietro la guardia dell'area di prova** (Fase 4, D30, sotto): la configurazione si valida e la guardia fa il suo
  autotest **prima di qualunque socket**; ogni richiesta passa dal trasporto recintato; tutto avviene sotto
  `mcg-it/<giro>/` delle share di prova (anche i temporanei e l'indice di prova); alla fine il giro si cancella, e
  con lui `mcg-it/` se l'ha creata il giro. La password si verifica **prima** con la sessione (interruttore condiviso
  da tutta la suite): un errore nel file costa un solo tentativo.

## NEGOTIATE reale (12/10/2026, PC cablato → 192.168.178.58:445, nessuna autenticazione)

| Misura | Valore |
|---|---|
| Dialetto | **SMB 3.1.1** |
| Cifratura offerta (`supportsEncryption`) | **sì**, cifrario **AES_128_GCM** |
| Firma richiesta dal server | no (la impone il client) |
| `ServerGuid` | `6c63796d…`, **uguale nelle 3 letture** (e in 3 letture di un secondo giro, 15 min dopo) |
| Scarto ora NAS − PC (`getTimeOffsetMillis`, segno invertito) | **+53,2 … +53,7 s**; il PC era a −0,65 s da `time.windows.com`, quindi è l'orologio del **NAS** a essere avanti di ~53 s (e in 15 min lo scarto è cresciuto di ~0,4 s) |
| TCP connect | 0,7–2,2 ms |
| TCP + NEGOTIATE | 15 ms (primo giro 80–180 ms: avvio della JVM e di BouncyCastle) |
| `ConnectionTester` fino al GUID | tutte le fasi PASSED |

## Deviazioni dal progetto

- **Un `SMBClient` per connessione** (non per processo): il pool di smbj riusa le connessioni per `host:porta` e
  potrebbe restituirne una legata alla rete di prima (D1).
- `EndOfFile` letto con una `QUERY_INFO` sull'handle (+1 giro): smbj non espone quello della CREATE.
- Cartelle create solo quando servono (`OBJECT_PATH_NOT_FOUND` → `mkdir` livello per livello, "esiste già" = ok):
  meno giri sulla VPN, stessa semantica.
- Scadenze e watchdog con un sorvegliante a passi di 1 s che chiude la connessione, non con `withTimeout`: l'API di
  `NasFileSystem` è bloccante. `withTimeout` resta su connect e login.
- La firma "richiesta" non si controlla a parte: con la cifratura attiva (obbligatoria) smbj mette
  `isSigningRequired=false` se è il server a imporla, e l'AEAD autentica già ogni messaggio.
- `CredentialLoad.Unavailable` (telefono appena acceso) → `NasError.Unexpected(null)`: il contratto non ha un errore
  "credenziali momentaneamente illeggibili".
- `Purpose.ONBOARDING` usa `NasRouteGate.onboardingRoute(candidato)` se il gestore riceve `onboardingCandidate`.
- `AuthBlocked` rende `isReachable=false` (nessun lavoro può partire).
- In più rispetto al progetto: interruttore che si apre dopo 2 tentativi "non confermati"; `EPERM` sulla connect →
  `LocalNetworkBlocked`; `SmbServerSideCopy` (copia via temporaneo + rinomina senza sovrascrivere).

## Cosa resta

- **Con le credenziali** (§5.4 B): eseguire la suite; M9 solo con `m9.consent=yes`; misurare se il GUID resta uguale
  dopo un riavvio del NAS (M8, decide il controllo "morbido" di D3).
- **Orologio del NAS avanti di ~53 s**: controllare l'NTP nella dashboard. L'app usa comunque l'ora del NAS come
  riferimento unico (formato-nas.md §6.1); `SessionNasClock.lastJumpMs` segnala salti > 5 min tra due NEGOTIATE.
- **P9**: `DataStoreBreakerStore.create(context, scope)`, `AuthBreakerRegistry`, `SmbNasSessionManager(... clock =
  ElapsedRealtimeClock, deviceId, onboardingCandidate)`, `NasStatusMonitor` con la `ReachabilityProbe` di P2,
  adattatore `CredentialVault` → `NasCredentialSource`, `invalidate(PROCESS_STOPPED)` su `ON_STOP` con
  `activeOperations == 0`, l'interruttore dell'account per le Impostazioni (`registry.forAccount(account)`).
- **P2**: il cancello deve negare `LOCAL_NETWORK_DENIED` (a permesso negato la TCP va solo in timeout).
- **Sul campo** (F11): APK di release con R8 (regole consumer verificate da `ToolsTest`); confermare che
  `Connection.close(true)` sblocca subito una richiesta appesa (watchdog) anche sul telefono.

---

## Fase 4, P1: lettura dei media, sorvegliante a progresso, recinzioni (14/10/2026)

| Pezzo | File | Cosa fa |
|---|---|---|
| `SmbNasMediaReader` | `media/SmbNasMediaReader.kt` | `NasMediaReader` sopra la sessione (`NasSessionManager.mediaReader()`): stessi controlli, limitatore, nuovi tentativi, recinzione |
| Elenco a pagine | idem (`DirCursor`) | Una pagina = un'operazione corta; **lo stesso handle e lo stesso iteratore** per tutte le pagine; mai l'elenco intero in memoria. Guasto prima della prima pagina: si riparte da capo; dopo: errore (rifarlo darebbe doppioni). Tetto 200.000 (`cappedAt`) |
| File aperti | idem (`SmbRandomFile`) | Timbro (dimensione, data, `fileId`) letto **sullo stesso handle** (`FileAllInformation`, una `QUERY_INFO`); ogni lettura e' un'operazione corta; dopo una riconnessione il file si riapre e, se il timbro e' cambiato, la lettura fallisce (`Unexpected`): mai due versioni mescolate |
| `readAll` | idem | 4 flussi a blocchi da 1 MiB, riordinati prima del `sink`, SHA-256 in ordine, al massimo 8 blocchi in memoria; il ritmo lo sorveglia un biglietto del trasferimento |
| Sorvegliante a progresso (D3) | `session/OperationMonitor.kt`, `OpContext` | Operazioni a flusso (`startTransfer`): la scadenza si sposta di 15/60 s a ogni blocco; resta la soglia di 32 KiB/s per 20 s; i 60 s della prima operazione sui dischi valgono solo fino al primo blocco. Vale per letture, pubblicazioni e copia lato server |
| Firma della risposta finale | `smb/SmbjTransport.kt` (`FinalResponseCheck`) | AES-CMAC con la chiave di firma della sessione sui byte della risposta finale di `SESSION_SETUP` (catturati da `ResponseGuard`), prima di qualunque richiesta |
| Niente risposte in chiaro | idem (`ResponseGuard`, `GuardedTransportFactory`) | Il ricevitore di smbj avvolto: dopo il login su una sessione cifrata ogni pacchetto non cifrato chiude la connessione (`SmbInsecureSessionException` → `InsecureSession`, agganciato sulla rete) |
| Recinzione D33 | `session/ShareGuard.kt`, `fence/ShareFence.kt` | Con un account di prova, share fuori da `test`, `test2`, `test-comune` → `PermissionDenied` prima del socket e prima del `TREE_CONNECT`, per ogni via (anche la sorgente della copia). La lega :app solo in debug (P11) |
| Copia lato server | `fs/SmbServerSideCopy.kt` | Implementa `ServerCopy`; operazione a flusso; recinzione su entrambe le share |
| Simulatore §5.2 | `testing/InMemorySmbBackend.kt`, `testing/FaultPlan.kt` | `fileId`, date delle cartelle, `lock`, contenuti virtuali, latenze e standby a tempo virtuale, `FaultPlan` (prima/dopo, sessione scaduta, blocco, codici), ordine casuale, elenchi troncati, copia rifiutata, collegamenti e cicli, `SimNasTime` (scarto, deriva, salti) |
| Guardia dell'area di prova | `src/test/.../integration/TestAreaGuard.kt`, `TestAreaSelfTest.kt` | Configurazione validata (share = file ∩ `TestShares`, famiglia rifiutata in ogni forma), percorsi solo sotto `mcg-it/<giro>/` a livello delle interfacce e del trasporto, autotest con contatori a zero; nessun test d'integrazione usa smbj direttamente |

**Domanda aperta 1 della revisione 2** (smbj e la sessione finale), provata con un impostore SMB su `127.0.0.1`
(`SessionSetupSignatureTest`): smbj 0.15.0 **non** verifica la firma della risposta finale di `SESSION_SETUP` e
**accetta** una risposta in chiaro e non firmata su una sessione cifrata (con il flag `ENCRYPT_DATA` smbj smette di
pretendere la firma; con la sessione 0 nell'intestazione non verifica nulla). Ora le due cose le fa `SmbjTransport`;
il test di caratterizzazione segnalera' un eventuale cambio di comportamento di smbj. La verifica in positivo (firma
giusta, calcolata a parte con NTLMv2, KDF SP800-108 e AES-CMAC scritti nel test) passa, e sul NAS vero la sessione si
apre.

**Invarianti nuovi** (ognuno ha un test): elenco di 50.000 voci con al piu' una pagina in memoria; `fileId` uguale
dopo una rinomina (nostra o dal PC), nuovo dopo una copia; letture a intervalli giuste ai bordi e mai oltre il
richiesto; `readAll` a 4 flussi con lo SHA-256 giusto e i blocchi in ordine; un trasferimento lungo che procede non
scade, uno fermo si' (15 s interattivo, 20 s di soglia in background); con un guasto a ogni chiamata (prima e dopo
l'applicazione, sessione scaduta) nessun file a meta' visibile, nessun contenuto sbagliato, nessun doppione negli
elenchi, nessun temporaneo altrui toccato; nessuna richiesta verso una share fuori recinzione.

**Sul NAS vero** (14/10/2026, PC cablato, account `test`, share `test`, area `mcg-it/<giro>/`; due giri):

| Misura | Valore |
|---|---|
| Sessione | SMB 3.1.1, AES-128-GCM, firma finale di `SESSION_SETUP` verificata; GUID stabile alla riconnessione |
| Ora del NAS | **+45,8 … +46,0 s** sul PC (il 12/10 erano +53 s) |
| Creazione di 1.500 file vuoti | 11–31 s (CREATE + CLOSE, a lotti di 100) |
| Elenco di 1.500 voci a pagine da 1.000 | **170–178 ms** (8.400–8.800 voci/s), un solo handle; `fileId` presenti, unici, uguali a quelli di `stat`, **stabili alla rinomina** |
| Scrittura di 64 MiB (un flusso) | 8,0–10,3 MB/s |
| Lettura a un flusso (blocchi da 1 MiB) | 11,4–11,8 MB/s |
| `readAll` a 4 flussi, 64 MiB | **16,9–18,1 MB/s** (3,7–4,0 s), SHA-256 giusto |
| `readAll` interattivo con scadenza per blocco di 1 s | 3,8 s in tutto: nessuna interruzione (D3) |
| Copia lato server nella share, 64 MiB | 1,2–1,8 s (**36–57 MB/s**), SHA-256 giusto, `fileId` nuovo |
| Copia tra share | non provata (`share2` assente nel file) |
| Recinzione | `Public` e `NAS` rifiutate senza richieste; autotest della guardia: 93 casi, zero chiamate |
| Pulizia | giro cancellato e verificato; `mcg-it/` tolta (creata dal giro) |

**Deviazioni**
- `NasSessionManager.mediaReader()` ha un default (`NoNasMediaReader`): le sessioni finte gia' scritte dagli altri
  moduli restano valide; `SmbNasSessionManager` e `FakeNasSessionManager` (campo `media`) lo ridefiniscono.
- Nessun `NasError` o `InsecureKind` nuovo: risposta finale non firmata = `InsecureSession(SIGNING_NOT_REQUIRED)`,
  risposta in chiaro = `InsecureSession(NO_ENCRYPTION)`; file cambiato durante la lettura = `Unexpected(null)`;
  share fuori recinzione = `PermissionDenied`.
- Con 4 flussi ogni blocco aspetta i suoi tre compagni: in interattivo `readAll` vuole almeno ~270 KiB/s in tutto
  (4 MiB in 15 s); sotto, conviene un flusso solo (punto aperto per P8).
- La soglia di 32 KiB/s si vede a blocchi interi: con blocchi da 1 MiB il minimo osservabile e' ~51 KiB/s per 20 s.
- `SmbShare` ha due primitive nuove: `stat` e `removeEmptyDirectory` (quest'ultima solo per `ops/` della 4B e per la
  pulizia dell'area di prova).

**Cosa resta**
- **P11**: legare `shareGuard = ShareFence` in `SmbNasSessionManager` solo da `DeveloperTools` di debug e
  verificare con `check.ps1` che `fence/ShareFence` non sia nel dex di release.
- **P12** (4B): `NasFileOps` passa da `runOp` (recinzione e sorvegliante gia' attivi); aggiungere i suoi percorsi
  all'autotest della guardia (`TestAreaSelfTest`).
- **Con le chiavi nuove**: copia tra share (`share2`), privacy fra account (`user2`, `password2`, `privateShare2`).
- **Sul campo**: ripetere le misure dall'S25 sul Wi-Fi e sulla VPN (con 4 flussi in interattivo).
