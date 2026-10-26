# Famiglia e dispositivi: registro, approvazione, smarrimento, recupero (P3, Fase 2)

| | |
|---|---|
| **Stato** | Pacchetto P3 della Fase 2, 12/10/2026. Logica in `:core-family` (package `registry`, `lifecycle`, `trust`) e estensione del formato v1 in `:core-trust`. Nessuna UI (P7), nessun legame Hilt (P9). |
| **Fonti** | `docs/fase2-progetto.md` §1.2, §2.2 (lato admin), §2.3; `docs/formato-nas.md` (§4, §5.3, §5.9, §5.10); `docs/sicurezza.md` C7–C10, C15, C23, §6; `docs/review/1.4-core-trust.md`. |
| **Test** | `gradlew.bat :core-family:testDebugUnitTest :core-trust:testDebugUnitTest` (JVM, NAS in memoria ostile). |

---

## 1. Le parti

| Contratto (P0) | Implementazione P3 | Cosa fa |
|---|---|---|
| `TrustStateStore` | `trust/FileTrustStateStore` | Lo stato fidato in un file di `noBackupFilesDir` (`MCGT` v1, scrittura atomica). File illeggibile = nessuno stato: si lavora chiusi. |
| — | `trust/TrustEngine` | Il cuore: ultimo registro **accettato** in memoria (ricaricato con `RegistryVerifier.restore`), lettura dal NAS con `RegistrySync`, salvataggio prima dell'uso, stato di questo dispositivo (`TrustStatus`), fatti per la Diagnostica. **L'unico punto in cui un dato remoto fa azzerare.** |
| `TrustGuard` | `trust/DefaultTrustGuard` | `preflight` prima di ogni sessione di lavoro (registro riletto al massimo ogni 15 minuti), `writePermit` senza NAS. |
| `DiagnosticsContributor` | `trust/TrustDiagnostics` | Sezione "Fiducia" (codici `TrustDiagCodes`), solo numeri, flag ed enum. |
| `RegistryService` | `registry/NasRegistryService` | Elenco dei dispositivi, richieste in attesa, approvazione, cambio di stato, invito. |
| — | `registry/RegistryWriter` | L'unico punto che firma e pubblica il registro: apre la chiave di famiglia, `commit`, esiti, rami in attesa, file che bloccano, ripubblicazione (N3). |
| — | `registry/ApprovalSightings` | Quando l'admin ha **visto** ogni richiesta (finestra di 15 minuti, M2), in memoria o su file. |
| — | `registry/DeviceStatusBook` | Lettura e pubblicazione dello stato dei dispositivi (tipo 8). |
| `SelfWipe` | `lifecycle/DeviceSelfWipe` + `WipeJournal` | L'azzeramento nelle fasi (0)…(7), riprendibile. |
| `LostReporting` | `lifecycle/NasLostReporting` + `LostReportWatcher` | Segnalazione "perso" del proprietario e conversione dell'admin. |
| `AdminRecovery` | `lifecycle/KitAdminRecovery` | Recupero dell'admin con il kit, secondo admin. |
| `FamilyReset` | `lifecycle/NasFamilyReset` | Reset della famiglia: chiave, id e kit nuovi. |

Porte che `:app` (P9) collega, oltre a quelle di `core-trust` (`AndroidKeystoreKeyProvider`, `AndroidKeystoreFamilyKeyVault`) e
`core-nas` (`NasSessionManager`, `SessionNasClock`):

| Porta | Cosa deve fare `:app` |
|---|---|
| `LocalDeviceProfileSource` | Restituire account NAS, area comune, area privata, nome, modello, ruoli (passo 7) e versione dell'app, da cio' che l'onboarding (P4) ha salvato. Null = non configurato. |
| `CredentialWiper` | `CredentialVault.wipeAll()` (vero se la chiave e i dati sono spariti). |
| `WorkCanceller` | `WorkManager.cancelAllWork()` (e i lavori UIDT). |
| `LocalDataEraser` | Replica, miniature, cache, DataStore. **Non** deve cancellare il file del diario (`FileWipeJournal.FILE_NAME`): e' quello che fa riprendere l'azzeramento e mostrare "dispositivo azzerato". |
| orologio monotono | `SystemClock.elapsedRealtime` per la cadenza di `preflight`. |

Da collegare con P4 (`FamilySetupRepository`, `JoinFlow`, `OnboardingCoordinator`):
- dopo la fondazione (passo 12(e)) e dopo l'ingresso, P4 **salva il `TrustSnapshot`** (byte del registro accettato, impronta del firmatario, id della famiglia, impronta della chiave di famiglia, versione minima = quella del QR, oppure 0 senza QR). Da li' in poi lo aggiorna solo il motore;
- `SetupState.AwaitingApproval(code, …)` = `TrustStatus.Pending.code`; `Ready(admin)` = `TrustStatus.Approved`/`Stale`; `Blocked(WIPE_IN_PROGRESS)` = `DeviceSelfWipe.progress` non nullo e diverso da `DONE`; `Wiped(at)` = `DeviceSelfWipe.record.finishedAt`;
- quando l'utente riconfigura un dispositivo azzerato: `DeviceSelfWipe.forgetCompletedWipe()`.

---

## 2. Approvazione (lato admin, §2.2)

1. `NasRegistryService.refresh` rilegge il registro, gli stati, e (sull'admin) le richieste: ogni richiesta nuova e' annotata con l'ora
   del NAS in `ApprovalSightings` (e su file: un riavvio dell'app non riapre la finestra). L'elenco segnala i sosia (`LookAlike`).
2. `approve(id, prova)`: la richiesta si **rilegge per id** (`PendingRequests.readOne`: la trova anche se la cartella e' inondata),
   finestra di 15 minuti dalla prima vista (`WindowExpired`), prova = codice **digitato** o chiave scansionata (`ProofMismatch`; la
   prova non si costruisce mai dalla richiesta, N6), poi `FamilyAuthority.approve` → `RegistryWriter.commit`.
3. Solo un dispositivo **approvato** che apre la chiave di famiglia dalla cassaforte e' admin; con il telefono bloccato o senza blocco
   schermo sicuro la cassaforte non si apre e il risultato e' `NotAdmin` (M3).

| `CommitResult` | `AdminActionResult` | Cosa succede dopo |
|---|---|---|
| `Published` | `Done` | Lo stato nuovo diventa quello fidato. Se `unverifiedBeyond` (N2: elenco troncato o troppa spazzatura oltre il nome usato) `RegistryWriter.unverifiedBeyond` lo dice: la UI avvisa l'admin, la Diagnostica mostra `TRUST_PUBLISH_UNVERIFIED`. |
| `NewerExists` | `NewerExists` | Il registro e' gia' stato riletto: l'interfaccia riprova sulla base nuova (stessa prova, se la finestra e' aperta). |
| `Fork` | `Fork` | `RegistryWriter.pendingForks` (con `configDiffers`, `containsRotation`) per la schermata dedicata; `NasRegistryService.resolveForks(recognize)` unisce o rifiuta sulla **stessa** modifica. |
| `Blocked` | `Blocked` | `RegistryWriter.blockedPaths`: i file esatti da cancellare dal PC **dopo** aver cambiato la password NAS sospetta (L18). |
| `NeedsAppUpdate` | `NeedsAppUpdate` | Nessuna firma finche' l'app non si aggiorna (E-I3). |
| errore NAS | `Failed(NasError)` | Nessuna scrittura. |

`createInvite` porta l'ancora **attuale** (`toAnchor`: niente QR durante una rotazione a meta', N4), IP, nome, GUID e ULA dalla
configurazione firmata, mai una password, e apre la finestra d'invito di 24 h (`TrustSyncScheduler.setInviteWindow`).

**E-S14**: un dispositivo in attesa ha `TrustStatus.Pending(codice)`, `preflight` = `ReadOnly(AWAITING_APPROVAL)`, nessun
`WritePermit`, non pubblica il proprio stato; e se qualcosa scrivesse a suo nome, `DeviceTrust` lo rifiuta (`SIGNER_NOT_APPROVED`).

---

## 3. La guardia e l'autodistruzione (§2.3)

`DefaultTrustGuard.preflight`, in quest'ordine:

1. azzeramento iniziato → si finisce (`WipeStarted`); finito → `Stop(NOT_CONFIGURED)`;
2. nessuno stato, nessuna chiave → `Stop(NOT_CONFIGURED)`; stato locale che non si riverifica → `Stop(REGISTRY_UNTRUSTED)`;
3. se sono passati 15 minuti (orologio monotono) si rilegge il registro; il motore, se il registro **accettato** dice "perso",
   avvia l'azzeramento; poi la segnalazione nell'area privata (decisione 3(a), §4);
4. NAS che non risponde → `Stop(NAS_UNAVAILABLE)` (mai azzeramento ne' approvazione);
5. app vecchia → `Stop(NEEDS_APP_UPDATE)`; in attesa → `ReadOnly(AWAITING_APPROVAL)`; dismesso → `ReadOnly(RETIRED)`; revocato →
   `Stop(REVOKED)` (revocato non e' perso: nessun azzeramento); rami firmati dalla famiglia → `ReadOnly(REGISTRY_STALE)` finche' l'admin
   non sceglie; approvato → `Proceed(WritePermit)` e, se e' passato un giorno, pubblicazione dello stato.

**Cosa NON fa azzerare** (test `NoFalseWipeTest`): un registro firmato da un'altra chiave, un rollback (versione firmata dalla famiglia
ma piu' vecchia di quella accettata), un'equivocazione, una regressione (versione piu' nuova che "torna indietro"), un file troncato,
un NAS muto, una segnalazione falsa, di un altro account, di un dispositivo in attesa o revocato, o presente solo nell'area comune.
Il motivo e' strutturale: azzera solo `RegistryState.mustSelfWipe` sullo stato **accettato** (che `RegistrySync` non sostituisce mai con
qualcosa di non verificato) o `LostReports.find` valido nell'area privata dell'account.

**Ordine dell'azzeramento** (`DeviceSelfWipe`, test `SelfWipeTest` con fake che registrano la sequenza):

| Fase | Azione | Se fallisce |
|---|---|---|
| (0) `MARKED` | segno nel diario (`FileWipeJournal`, `noBackupFilesDir`) | si va avanti lo stesso |
| (1) `DEVICE_KEY_DESTROYED` | `KeyProvider.destroyDeviceKey` (poi si verifica che non ci sia piu') | ci si ferma: **nessun dato toccato** |
| (2) `FAMILY_KEY_DESTROYED` | `FamilyKeyVault.destroy` (no-op se non admin) | ci si ferma |
| (3) `CREDENTIALS_WIPED` | `CredentialWiper`; dalla Fase 8 (`docs/fase8-progetto.md` D14) anche le chiavi dei volti `mcg-faces-*` del Keystore (`FacesKeys.destroyAll`, legato da P13): distrutte prima dei dati, rendono illeggibili i resti del database dei volti | ci si ferma |
| (4) `SESSIONS_CLOSED` | `NasSessionManager.invalidate(WIPE)` | ci si ferma |
| (5) `LOCAL_DATA_ERASED` | stato fidato, richieste viste, poi `LocalDataEraser`; dalla Fase 8 anche i database dei volti `faces-<G>.db`, i modelli dei volti estratti e il file dell'esclusione `faces-local` (D14, D15) | ci si ferma |
| (6) `WORK_CANCELLED` | `WorkCanceller` | ci si ferma |
| (7) `DONE` | ora di fine nel diario → `SetupState.Wiped(at)` | — |

Una fase ferma lascia il diario all'ultima completata; `resumeIfInterrupted` (all'avvio) o la guardia riprendono da li'. Tutte le fasi
sono idempotenti.

---

## 4. Smarrimento (decisione 3(a), C15, E-S1/E-S2)

- **Admin**: `changeStatus(id, LOST)` → `commit`. Il dispositivo perso, appena rilegge il registro, si azzera.
- **Proprietario**: da un altro dispositivo **approvato dello stesso account** del NAS, `reportOwnDeviceLost(id)` firma un `LostReport`
  (tipo 7) e lo pubblica in `family/reports/<id>/<n>` **due volte**: nell'area comune (per l'admin, che con `convertReports` lo
  trasforma in `lost`, un gesto suo) e nell'**area privata dell'account**. Il dispositivo perso rispetta solo la copia nella propria area
  privata: e' la **prova di possesso dell'account** chiesta dalla revisione (B6) prima di dare questo potere. Un dispositivo che ha
  dichiarato l'account di un altro familiare non ha la sua password, quindi non scrive nella sua area privata e non puo' farne azzerare
  i telefoni; la copia nell'area comune la vede solo l'admin.
- Senza area privata nel profilo la segnalazione vale solo per l'admin (lo si registra nel log di debug).
- Poi la guida (P7), **in quest'ordine**: segnala → revoca il peer WireGuard → cambia la password NAS (la reinseriscono anche gli altri
  dispositivi dello stesso account) → Samsung Find.

---

## 5. Recupero dell'admin, secondo admin, reset (§2.3, E-S13)

**Recupero** (`KitAdminRecovery`), sul dispositivo nuovo con l'account e le aree dell'admin gia' configurati da P4:
1. `findKits`: i kit in `recovery/<famiglia>/<impronta>` dell'area privata, solo nomi validi e con l'intestazione coerente con il
   posto (`RecoveryKit.inspect`);
2. `recover(kit, codice, qr, destino, impronta)`: codice azzerato subito; `RecoveryKit.open` con l'ancora **indipendente dal NAS** (il QR
   "stato della famiglia", M7, oppure l'**impronta della famiglia scritta sul foglio** al passo 12 e digitata; revisione 2, F3: senza
   nessuna delle due il recupero non parte); registro letto con quell'ancora (versione minima del QR); stessa versione del QR ma altro contenuto → fermo; la
   chiave del kit deve firmare il registro (`acceptsRecoveredKey`); chiave nella cassaforte; questo dispositivo si approva da se' come
   admin (prova: la sua stessa chiave) e gli **altri admin approvati** diventano persi o revocati, nella stessa versione.
3. Codice sbagliato → `WrongCode`; kit di un'altra chiave → `RECOVERY_KIT_UNVERIFIED`; rami o cartella inondata → fermo (decide l'admin
   dalle Impostazioni).

**Secondo admin**: lo stesso percorso con `KEEP_APPROVED`; se il dispositivo e' gia' un familiare approvato cambia solo il segno di admin
(`FamilyAuthority.markAdmin`).

**Reset** (`NasFamilyReset`): doppia conferma → chiave nuova nella cassaforte (se poi qualcosa fallisce si rimette la vecchia) → kit nuovo
con codice nuovo nell'area privata, riletto e aperto → id nuovo e fondazione con `FamilyAuthority.refound` **nella stessa cartella**,
dopo le versioni della famiglia vecchia e con 32 nomi liberi dopo → lo stato fidato passa alla famiglia nuova. Il QR nuovo porta come
versione minima quella della fondazione. Il codice del kit nuovo resta in `recoveryCodeForDisplay()` finche' `confirmTypedCode` non
conferma la riscrittura. I dati vecchi restano (ignorati); i dispositivi non migrati restano nella famiglia vecchia e vedono la
fondazione nuova come un file firmato da un'altra chiave.

**Rientrare in una famiglia** (verifica funzionale del 19/10, ON-4 e ON-5): un dispositivo revocato, dismesso, senza stato della
famiglia, o rimasto in quella di prima dopo un reset, esce con `OnboardingCoordinator.startOver` ("Configura di nuovo" nella
pagina della radice; Impostazioni → Famiglia → "Rientra con un QR nuovo", dopo un foglio di conferma). Si tolgono, in
quest'ordine, la chiave del dispositivo (al rientro ne nasce una nuova, quindi un id nuovo: E-S2, E-O7), la chiave di famiglia se
c'era, password e account del NAS, lo stato fidato e l'avanzamento; poi il motore rilegge e, senza stato ne' chiave, dice "mai
configurato". Le foto, i nascosti, i preferiti e le miniature restano. Il dismesso si comporta come il revocato: niente sola
lettura, si riconfigura e l'admin riapprova. La pagina Famiglia avvisa quando sul NAS c'e' un registro piu' nuovo che non si
verifica (`Stale(NEWER_UNVERIFIED)`), senza decidere da sola che e' un reset.

**Senza admin, senza kit e senza codice** (decisione dell'utente del 19/10, "via dal PC", ON-6): nessun familiare puo' azzerare la
famiglia dall'app, e l'app non riceve poteri nuovi. La guida "Ricominciare da zero" (nel recupero dell'onboarding quando non ci
sono kit o il codice non apre, nel recupero delle Impostazioni e in Impostazioni → Famiglia) spiega come farlo dal PC:
1. Esplora file, `\\<indirizzo del NAS>\<area comune>\.mycloudgallery` (l'app scrive il percorso vero, da `SavedNas`), con un
   account del NAS che scrive nell'area comune; la cartella e' nascosta (Visualizza → Mostra → Elementi nascosti);
2. si cancella **solo** `family` (registro, richieste, segnalazioni): `devices`, `trash`, `leases`, i kit nell'area privata e
   le foto restano;
3. su un telefono si rifa' "Crea la famiglia" (chiave, kit e codice nuovi), poi ogni altro dispositivo "Rientra con un QR nuovo".

Si perdono la famiglia e l'elenco dei dispositivi registrati; le foto no. Se la cartella c'e' ancora, "Crea la famiglia" si ferma al
passo 4 ("Qui c'e' gia' una famiglia"). Il vecchio telefono dell'admin, se ricompare, firma per la famiglia di prima: i dispositivi
della famiglia nuova non lo accettano (l'ancora e' un'altra).

---

## 6. Diagnostica (fiducia)

`TrustDiagnostics` (sezione `TRUST`), dai fatti gia' in memoria (nessun accesso al NAS):

| Codice | Quando | Esportazione |
|---|---|---|
| `TRUST_STATE` | sempre | `state` (enum `OwnTrustState`), `registryVersion`, `devices` |
| `TRUST_REJECTED_FILES`, `TRUST_NEWER_UNVERIFIED` | file del registro scartati | `rejected.<motivo>`, `total` |
| `TRUST_LISTING_TRUNCATED` | elenco oltre il tetto | — |
| `TRUST_NEEDS_APP_UPDATE` | registro di un'app piu' nuova (E-I3) | — |
| `TRUST_REGISTRY_FORK` | rami firmati dalla famiglia (equivocazione) | `forks` |
| `TRUST_VERSION_REPLACED` | versione fidata sostituita (N3) | — |
| `TRUST_SYNC_INCOMPLETE` | troppa spazzatura di fila | — |
| `TRUST_REGISTRY_BEHIND` | un altro dispositivo conosce un registro piu' nuovo | — |
| `TRUST_NO_MIN_VERSION` | configurato senza QR | — |
| `TRUST_NOT_REFRESHED` | ultima lettura fallita | — |
| `TRUST_LOCAL_STATE_UNTRUSTED` | stato locale non riverificabile | — |
| `TRUST_WIPE` | azzeramento in corso o finito | `phase` |
| `TRUST_LOST_REPORT_REJECTED` | segnalazioni non valide nell'area privata | `total` |
| `TRUST_STATUS_REJECTED` | stati di dispositivi non validi | `statusRejected` |
| `TRUST_PUBLISH_UNVERIFIED` | ultima pubblicazione non verificata oltre (N2) | — |
| `TRUST_PUBLISH_BLOCKED` | pubblicazione bloccata | `files` |

---

## 7. Decisioni applicate per default (`formato-nas.md` §13)

| # | Default applicato |
|---|---|
| 1 | Kit = file nell'area privata dell'admin + codice su carta (invariato). |
| 2 | **Non applicata**: la cassaforte chiede il telefono sbloccato con blocco schermo sicuro (`setUnlockedDeviceRequired` + `isDeviceSecure`), non la credenziale a ogni uso. Resta il residuo M3 (vedi `docs/review/1.4-core-trust.md`, terzo giro). |
| 3 | (a) anche il proprietario, **con la prova di possesso dell'account** (copia nell'area privata). |
| 4 | (a) i dati dei revocati sono scartati (invariato: `DeviceTrust`). |
| 5 | (a) prenotazioni di al massimo 1 h (invariato). |

## 8. Limiti e punti aperti

- **Recupero senza QR di stato** (chiuso dalla revisione 2, F3): il foglio del passo 12 (e quello del reset) porta anche
  l'**impronta della famiglia**; `AdminRecovery.recover` la riceve digitata (`TypedFingerprint`: spazi, trattini e maiuscole
  tollerati) e la usa come ancora. Il nome del file nell'area privata non vale piu' come ancora: senza QR e senza impronta il
  recupero non parte (`RECOVERY_KIT_UNVERIFIED`). L'id della famiglia viene ancora dal percorso: il kit aperto deve comunque avere
  l'impronta attesa, e un kit con un'altra chiave non si apre.
- **Area privata uguale all'area comune** (revisione 2, F8): l'invito la rifiuta, e un profilo che l'avesse lo stesso non
  accetta segnalazioni "perso" (nessun azzeramento automatico da segnalazione). Una share pubblica indicata come area privata non
  si riconosce dall'invito: limite in `sicurezza.md` L23.
- **Contratti stretti**: `FamilyReset` non restituisce il codice del kit nuovo (c'e' `NasFamilyReset.recoveryCodeForDisplay`);
  `RegistryService` non espone rami, file bloccanti e avvisi N2 (ci sono in `RegistryWriter`/`NasRegistryService`); `LostReporting` non
  elenca le segnalazioni in attesa per l'admin (`convertReports` le converte tutte).
- **Dismissione**: le teste congelate le sapra' l'indice (Fase 4.1); oggi `RETIRED` congela un elenco vuoto.
- **Recupero**: il "vecchio admin" sono tutti gli altri dispositivi admin approvati; la schermata li deve mostrare prima.
- **Stato dei dispositivi**: solo informativo; chi ha SMB puo' nasconderlo o farlo vedere vecchio (L11), mai falsificarlo.
- **Reset**: un dispositivo che entra senza QR dopo un reset parte dalla versione 1 e attraversa le versioni della famiglia vecchia come
  spazzatura (oltre 32 di fila si ferma): con un reset serve il QR, o la versione della fondazione digitata.

## 9. Da provare con due dispositivi (F2, F6, F7)

1. S25 admin, tablet "Entra con QR": la richiesta compare entro il controllo, il codice digitato coincide, approvazione, tablet `Ready`.
   Prima: sola lettura (E-S14). Finestra: lasciare passare 16 minuti → `WindowExpired`, il tablet rifa' la richiesta.
2. Profilo "Prova" segnato perso dall'S25 → al ricollegamento si azzera (chiave sparita dal Keystore, credenziali e dati cancellati, schermata
   "dispositivo azzerato"); riconfigurato prende un id nuovo. Ripetere uccidendo l'app durante l'azzeramento: riprende.
3. Segnalazione dal proprietario: telefono e tablet sullo stesso account, area privata scelta; il tablet si azzera senza l'admin.
4. Tablet come secondo admin (kit + codice + QR di stato dall'S25), poi revocato dall'S25; con il tablet ancora sulla base vecchia,
   l'S25 deve ricevere `Fork` o `NewerExists` e uscirne.
5. Recupero dell'admin su un telefono "nuovo" (profilo pulito) con il QR di stato del tablet: il vecchio S25 si azzera al ricollegamento.
6. Reset della famiglia: QR nuovo sul tablet, il kit nuovo si apre con il codice nuovo.
7. Telefono admin senza blocco schermo: niente approvazioni (`NotAdmin`), niente reset.
8. (Verifica funzionale, 19/10) Tablet "Entra senza QR": dati letti dalla pagina "Configura un familiare" dell'admin, "e' casa",
   password, richiesta, approvazione. Poi lasciare passare 16 minuti prima di approvare: "Rifai la richiesta" dalla pagina
   d'attesa, codice nuovo, approvazione.
9. (Verifica funzionale, 19/10) Tablet revocato dall'S25: pagina "revocato" con "Configura di nuovo", rientro con un QR nuovo.
   Reset della famiglia sull'S25: sul tablet, Impostazioni → Famiglia → "Rientra con un QR nuovo".
