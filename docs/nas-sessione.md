# Sessione NAS (`core-nas`, Fase 2 P1)

> Implementa il §3 di `docs/fase2-progetto.md`. Contratti in `core-nas/.../contract/` (P0), contratto di fiducia in
> `core-trust/.../nas/NasFileSystem.kt`. Riferimenti: `docs/research/smb-vpn.md`, spike `nas-probe`.

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
(6) credenziali e **una** `authenticate` → (7) cifratura davvero attiva, né guest né anonima. Un fallimento tra 3 e 7
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
  (processo morto) aprono.
- Mai più di 8 richieste in volo; una sola connessione e un solo login per molti chiamanti.
- I lavori non chiudono mai share o sessione; le chiude solo il gestore (cambio di rete, inattività 5 min, sospetto).
- Mai sovrascrivere, mai cancellare file altrui; `read` non alloca oltre `maxBytes+1`.
- Log solo con `GalleryLog`, senza utente, password, IP, nomi o percorsi (`NasAddress`, `ShareMissing` e
  `Negotiation` hanno `toString` neutri).

Test JVM: `gradlew.bat :core-nas:testDebugUnitTest` (78 test; tabella §3.4 in `ErrorTableTest` con Robolectric per le
stringhe; tempo virtuale in `WatchdogTest`; simulatore `testing/InMemorySmbBackend`, riusabile dai moduli a valle).

## Suite d'integrazione (`@NasIntegration`, dal PC cablato)

```powershell
gradlew.bat :core-nas:testDebugUnitTest -PnasIntegration --tests "*.integration.*"
```

- `NasNegotiateProbeTest`: **solo NEGOTIATE**, nessuna credenziale. Scrive `core-nas/build/nas-it/negotiate.txt`.
- `NasIntegrationSuiteTest`: parte solo se esiste `%USERPROFILE%\.mycloudgallery\nas-it.properties`, scritto
  dall'utente (mai nel repository):
  ```properties
  user=test
  password=...
  share=test
  # facoltativi
  host=192.168.178.58
  guid=            # se manca si legge dalla NEGOTIATE
  share2=test-comune  # copia lato server tra share
  m9.consent=yes   # SOLO con consenso esplicito: un tentativo con password sbagliata (M9)
  ```
  Senza file i 5 test risultano *skipped* ("file delle credenziali assente"). Con il file: sessione cifrata e GUID
  dopo la riconnessione, `publishExclusive` reale (collisione, maiuscole, tetto, elenco troncato), `ShareProbe`,
  pulizia dei temporanei, copia tra share. La password si verifica **prima** con la sessione (interruttore condiviso
  da tutta la suite): un errore nel file costa un solo tentativo. Scrive solo sotto `.mycloudgallery/it/<giro>/` e
  `.mycloudgallery/tmp/` della share di prova e alla fine cancella tutto ciò che ha creato.

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
