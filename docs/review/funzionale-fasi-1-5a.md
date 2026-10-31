# Verifica funzionale: Fasi 1–5A (commit `0d6b4c4`, 19/10/2026)

**Stato (31/10).** Le 59 voci sono corrette nel codice (19/10, «Correzioni funzionali: onboarding e famiglia», «rete e VPN», «Libreria e NAS», «backup 5A» in `docs/decisioni.md`, ognuna con il suo "Stato"), tranne **GA-7**, non confermata e da guardare sul telefono (F15, P14). Riconciliazione del 31/10 (Fase 10 P11): `docs/review/10-chiusura.md`.

**La domanda.** Ogni funzione promessa all'utente funziona davvero nell'app installata, da capo a fondo? Questa non è una
revisione di sicurezza: quelle stanno negli altri file di `docs/review/`.

**Come.** Ho letto il codice committato, senza eseguire niente: niente Gradle, telefono, emulatori o NAS. Per ogni flusso
dell'utente ho seguito questa catena: tasto → ViewModel → porta → oggetto legato in `:app` (Hilt, `AppGraph`,
`GalleryGraph`, debug e release) → codice che tocca NAS o telefono → risultato che torna alla schermata. In più ho confrontato
ogni fake usato dai test con l'implementazione vera.

**Il modello dei difetti.** Sono i due difetti trovati sul telefono il 19/10 e corretti in `4521eff`:
- un tasto che non fa niente, con l'errore che finisce in uno stato che la schermata non mostra;
- un passo che parte prima che i suoi dati esistano, con un fake che salta il passo che il piano vero ha.

**Perimetro.** Fasi 1–5A, onboarding e famiglia compresi. Sono escluse le parti della 5B non ancora collegate.

**Stato di ogni voce:**
- **confermato**: il percorso completo è nel codice;
- **plausibile**: serve una prova sul telefono o un test, descritto nella voce.

**Gravità:**
- **Blocca**: la funzione non si può usare;
- **Sbagliata**: funziona male o promette ciò che non fa;
- **Minore**.

I percorsi sono relativi alla radice del repository. I numeri di riga sono quelli del commit `0d6b4c4`.

---

## 1. In breve

| | Blocca | Sbagliata | Minore | Totale | di cui confermati | di cui plausibili |
|---|---|---|---|---|---|---|
| Onboarding e famiglia (ON) | 7 | 4 | 3 | 14 | 13 | 1 |
| Rete, VPN, Diagnostica, Impostazioni (RE) | 3 | 4 | 4 | 11 | 7 | 4 |
| Galleria del telefono (GA) | 0 | 2 | 6 | 8 | 5 | 3 |
| NAS nella Libreria e organizzazione (NA) | 0 | 6 | 6 | 12 | 10 | 2 |
| Backup 5A (BK) | 1 | 5 | 8 | 14 | 12 | 2 |
| **Totale** | **11** | **21** | **27** | **59** | **47** | **12** |

Le cose più gravi:
- **L'ingresso di un familiare si rompe in due modi.**
  - Se l'admin approva più di 15 minuti dopo aver visto la richiesta, il telefono nuovo resta in attesa per sempre (ON-2).
  - "Entra senza QR" non può riuscire mai (ON-1).
- **Un dispositivo revocato, dismesso o rimasto nella famiglia vecchia dopo un reset non si può riconfigurare dall'app**
  (ON-4, ON-5).
- **La VPN gestita dall'app non si accende mai.** L'app non chiede il permesso di WireGuard (RE-1). Una sospensione
  dell'automazione non si toglie (RE-2). "Configura" in Impostazioni apre il riepilogo dell'onboarding (ON-7).
- **Backup e operazioni sul NAS hanno tasti e stati che mentono:**
  - "Inizia ora" che finisce senza fare niente (BK-6);
  - un problema risolto che resta sulla pagina (BK-3);
  - una pausa che si toglie su "Annulla" (BK-4);
  - un'operazione sul NAS che aspetta ore senza dirlo (NA-1).

---

## 2. I difetti

### 2.1 Onboarding e famiglia

#### ON-1 · "Entra senza QR" non può riuscire
- **Gravità:** Blocca. **Stato:** confermato.
- **Flusso:** Benvenuto → "Entra senza QR" (o, dalla scansione, "Inserisci a mano") → dati → Continua.
- **Percorso:** quattro guasti indipendenti; basta uno per fermare il flusso.
  1. **Invito senza aree.** `OnboardingViewModel.submitManualJoin` (`feature-onboarding/.../OnboardingViewModel.kt:680-701`)
     costruisce l'invito con `commonShare = ""` e `privateShare = ""`, e la pagina (`join/JoinPages.kt:107-232`) non chiede
     le aree. `DefaultJoinFlow.start` → `InviteRules.check` → `ShareNames.check("")` (`core-family/.../invite/InviteSupport.kt:86-87`)
     → `SharePath.checkComponent` "componente vuoto" → `INVITE_INVALID` (`DefaultJoinFlow.kt:115-118`).
  2. **Testo non normalizzato.** Il VM toglie solo gli spazi da impronta e id della famiglia. Il vero `ManualInvite.create`
     li normalizza (`Base32.normalizeHumanInput`), mentre `InviteRules` esige la forma canonica (`InviteSupport.kt:62-63`).
     Un trattino, una maiuscola, una O o una I fanno `INVITE_INVALID`.
  3. **Percorso sbagliato dalla scansione.** Da "Inserisci a mano" il percorso resta `JOIN_QR` (`OnboardingViewModel.kt:642-645`),
     mentre `start` per un invito manuale vuole `JOIN_MANUAL` (`DefaultJoinFlow.kt:122-128`): risponde `STEP_NOT_AVAILABLE`.
  4. **Casa mai confermata.** Senza QR `start` pretende `snapshot.homeConfirmed` (`DefaultJoinFlow.kt:135-138`), che scrive solo
     `ConfirmHome` del passo 2. Con `JOIN_MANUAL` il VM salta subito alla pagina manuale (`OnboardingViewModel.kt:170-175, 249-265`),
     e la domanda "È la rete di casa?" esiste solo in `NasAddressScreen` (`OnboardingHost.kt:343-365`): si arriva a `NOT_AT_HOME`.
- **Scenario:** il familiare scrive IP, utente, id e impronta letti dallo schermo dell'admin e tocca Continua. Compare
  "Questo QR non è un invito valido". Dovrebbe invece chiedere la password.
- **Perché i test passano:**
  - `OnboardingViewModelTest` (righe 592-621) usa `FakeJoinFlow`, che accetta qualunque invito;
  - `DefaultJoinFlowTest` (riga 480 e seguenti) costruisce l'invito con `ManualInvite.create(..., "NAS", "mamma", ...)` e chiama
    `ConfirmHome` a mano: due cose che la UI non fa.
- **Correzione:**
  - aggiungere al modulo area comune e area privata (o leggerle dal QR "stato della famiglia");
  - costruire l'invito con `ManualInvite.create`;
  - fare in modo che "Inserisci a mano" cambi il percorso in `JOIN_MANUAL`;
  - prima di `start`, mostrare la conferma "È la rete di casa?" (`ConfirmHome`).
- **Test da aggiungere:** `AppIntegrationTest`, flusso manuale col VM vero su `AppGraph` (o col solo `DefaultJoinFlow`, ma con
  l'invito costruito come lo costruisce il VM). Asserire che si arrivi a `NeedsPassword`.
- **Stato: corretta** (19/10). La pagina «Entra senza QR» chiede area comune e area privata e «È la rete di casa?»
  (`ConfirmHome`), costruisce l'invito con `ManualInvite.create` (id e impronta in forma canonica; versione minima dello
  «stato della famiglia», se scansionato, che riempie anche id e impronta) e «Inserisci a mano» passa a `JOIN_MANUAL`. La
  pagina «Configura un familiare» dell'admin mostra i dati da leggere a voce. Test: `VerificaFunzionaleFamigliaTest.on1_…`
  (ViewModel vero su `AppGraph` fino alla richiesta e all'approvazione), `VerificaFunzionaleViewModelTest.on1_…`,
  `FakeJoinFlowParityTest`, `VerificaFunzionaleOnboardingTest.on1_…`, `VerificaFunzionaleImpostazioniTest.on1_…`.

#### ON-2 · Richiesta d'ingresso scaduta: il telefono nuovo resta in attesa per sempre
- **Gravità:** Blocca. **Stato:** confermato.
- **Flusso:** il familiare chiede di entrare, l'admin approva tardi.
- **Percorso:**
  - la finestra di 15 minuti parte quando l'app dell'admin vede la richiesta. Di solito la vede il lavoro in background che
    manda la notifica (`TrustSyncRun.kt:91-101` → `NasRegistryService.refreshPending`, `registry/NasRegistryService.kt:181-184`);
  - dopo, `approve` risponde `WindowExpired` (`NasRegistryService.kt:91-93`), e la pagina dell'admin dice: "Il dispositivo
    nuovo deve rifare la richiesta: ripartirà dall'ingresso con il QR" (`feature-settings/src/main/res/values/strings.xml:97`);
  - sul telefono nuovo, finito l'onboarding, la radice registra solo `AwaitingApprovalRoute` (`app/.../navigation/AppNavHost.kt:193-195`)
    e la pagina d'attesa non ha nessun tasto (`AppNavHost.kt:224-234`; `AwaitingApprovalScreen` senza `onContinue`);
  - il rinnovo esiste (`DefaultJoinFlow.start` con `renewing`, righe 123-128, e il KDoc di `ApprovalWatcher` lo descrive), ma
    lo chiamano solo le pagine "entra" (`OnboardingViewModel.kt:143-153, 704-711`), che dopo l'invio della richiesta non si
    raggiungono più.
- **Scenario:** la notifica "Nuovo dispositivo" arriva alle 22:10, l'admin la apre alle 22:40 e la richiesta risulta "Scaduta".
  Il tablet mostra "In attesa dell'admin" all'infinito. L'unica uscita è cancellare i dati dell'app da Android.
  - È anche la prova sul campo n. 1 di `docs/famiglia-dispositivi.md` §9: "lasciare passare 16 minuti → il tablet rifà la richiesta".
- **Correzione:**
  - nella pagina d'attesa, un tasto "Rifai la richiesta" che riapre la scansione (`JoinFlow.start` con il rinnovo);
  - oppure far partire la finestra solo quando l'admin apre la pagina di approvazione, non quando la vede il lavoro in background.
- **Stato: corretta** (19/10). Nella pagina d'attesa «Rifai la richiesta» apre la sola rotta `OnboardingRenewRoute` (non
  l'onboarding intero: F5 resta chiusa) con la scansione e il rinnovo di `JoinFlow` (chiave nuova); finita o lasciata, si
  torna all'attesa. La finestra di 15 minuti dalla prima vista non cambia (M2). Test: `VerificaFunzionaleFamigliaTest.on2_…`
  (16 minuti, `WindowExpired`, rinnovo, approvazione), `VerificaFunzionaleViewModelTest.on2_…`, `EntryHardeningTest`,
  `VerificaFunzionaleRegistroTest.on2_…` (stessa finestra nel registro vero e nel finto).

#### ON-3 · "Recupera l'admin" e "secondo admin" non riprendono dopo la morte del processo
- **Gravità:** Blocca, in quello scenario. **Stato:** percorso confermato; quanto spesso capita va visto sul telefono.
- **Flusso:** recupero dell'admin o secondo admin nell'onboarding, con un'interruzione fra le Aree e la fine del recupero:
  "Continua più tardi", telefonata, app chiusa da Android.
- **Percorso:**
  - lo stato del recupero vive solo nel ViewModel: `RecoveryUi`, più `chosenAddress` e `chosenUser`
    (`OnboardingViewModel.kt:128-129`), scritti solo da `checkNas` e `submitAccount` (righe 327, 346);
  - il recupero parte solo nel ritorno di `submitAreas` (righe 392-407), mentre il piano `RECOVER` non ha un passo di recupero
    (`core-family/.../onboarding/OnboardingSupport.kt:159-170`). Dopo le Aree il coordinatore è già a "Ruoli";
  - al riavvio `init` ricostruisce solo le pagine "entra" (righe 143-153);
  - il riepilogo propone "Vai alla Libreria" (nessun passo essenziale in sospeso), ma `finish` risponde `STEP_NOT_AVAILABLE`
    perché `essentialsDone` vuole `Approved(admin = true)` (`DefaultOnboardingCoordinator.kt:596-611`);
  - tornando alle Aree e confermando di nuovo, `searchKits` trova `chosenAddress == null` e dice "Non trovo nessun kit"
    (righe 802-810).
- **Scenario:** l'admin sul telefono nuovo arriva alla scelta del kit, va a prendere il foglio e torna dopo 10 minuti. Si
  ritrova su "Ruoli del dispositivo", finisce i passi e resta fermo su "Questo passo non è disponibile adesso". Per uscirne
  deve indovinare che serve tornare indietro fino al passo 2 e rifare indirizzo, password e aree.
- **Correzione:**
  - salvare nello snapshot che il recupero è in corso (o aggiungere un passo `RECOVERY` al piano);
  - prendere indirizzo e utente da `snapshot.nas` e `snapshot.nasUser`, non dal ViewModel;
  - al riavvio riaprire le pagine del recupero se il percorso è `RECOVER_ADMIN` o `SECOND_ADMIN`, le Aree sono fatte e lo
    stato non è admin.
- **Test:** `OnboardingViewModelTest` con un coordinatore che parte con lo stato dopo le Aree (percorso `RECOVER_ADMIN`) e un
  VM nuovo. Atteso: destinazione `Recovery(Kits)` e ricerca dei kit con l'account salvato.
- **Stato: corretta** (19/10). Il coordinatore dice se il recupero è a metà (`recoveryPending`: aree fatte, admin non
  recuperato, onboarding non finito) e dà indirizzo, utente e aree salvati (`savedNas`). Il ViewModel riapre i kit
  all'avvio e da «Vai alla Libreria», e li cerca con l'account salvato, non con quello in memoria. Test:
  `VerificaFunzionaleOnboardingTest.on3_…`, `VerificaFunzionaleViewModelTest.on3_…`.

#### ON-4 · Dispositivo revocato o dismesso: fermo per sempre sul vecchio riepilogo dell'onboarding
- **Gravità:** Blocca. **Stato:** confermato.
- **Flusso:** l'admin revoca o dismette un dispositivo. Cosa vede quel dispositivo.
- **Percorso:**
  - `SetupResolver.fromTrust` porta `Revoked` e `Retired` a `NotConfigured` (`core-family/.../setup/DefaultFamilySetupRepository.kt:106-109`);
  - la radice manda `NotConfigured` all'onboarding (`root/RootViewModel.kt:129-131`, `AppNavHost.kt:204, 353-354`);
  - l'onboarding salvato però è finito, e il VM mostra `progress.current = SUMMARY`. "Indietro" non fa niente
    (`DefaultOnboardingCoordinator.back`, riga 203: niente se `finished`), "Vai alla Libreria" emette `Finished` verso
    `onDone = {}` e lo stato non cambia;
  - l'avanzamento dell'onboarding si cancella solo dopo un azzeramento (`AppGraph.kt:363-370`, `graph/LocalData.kt:97`);
  - la guardia stessa dice che un dismesso dovrebbe restare in sola lettura (`DefaultTrustGuard.kt:88`), non tornare al
    benvenuto.
- **Scenario:** l'S25 revoca il tablet secondo admin (prova n. 4 di `famiglia-dispositivi.md` §9). Il tablet mostra "Tutto
  pronto" con un tasto che non fa niente, senza dire che è stato revocato e senza modo di ricominciare.
- **Correzione:**
  - una schermata "Questo dispositivo è stato revocato o dismesso" con "Configura di nuovo", che toglie avanzamento, account e
    chiave come `forgetCompletedWipe`;
  - per il dismesso, decidere fra sola lettura e riconfigurazione, e mostrarlo.
- **Stato: corretta** (19/10). Con l'onboarding finito e il dispositivo revocato, dismesso o senza stato della famiglia,
  la pagina della radice dice «Questo dispositivo è stato revocato» (o dismesso) con «Configura di nuovo»
  (`OnboardingCoordinator.startOver`: chiave del dispositivo, chiave di famiglia, password, account, stato fidato e
  avanzamento; non le foto né i dati locali). Decisione: il dismesso si comporta come il revocato (si riconfigura, niente
  sola lettura). Test: `VerificaFunzionaleFamigliaTest.on4_…` (revoca vera, poi rientro con una chiave nuova),
  `VerificaFunzionaleOnboardingTest.on4_…`, `VerificaFunzionaleViewModelTest.on4_…`.

#### ON-5 · Dopo il reset della famiglia gli altri dispositivi non possono rientrare
- **Gravità:** Blocca. **Stato:** confermato.
- **Flusso:** Impostazioni → Famiglia → Reimposta → "Riconfigura ogni dispositivo" (`strings_family.xml:199, 228-229`).
- **Percorso:**
  - sui dispositivi vecchi le versioni nuove firmate da un'altra chiave sono "più nuove non verificabili": risultano
    `Stale(NEWER_UNVERIFIED)` e, con l'onboarding finito, `Ready` (`DefaultFamilySetupRepository.kt:110-116`). Restano nella
    famiglia vecchia senza saperlo;
  - nessuna schermata di un dispositivo configurato permette di scansionare un invito: `JoinFlow` si usa solo
    nell'onboarding, e l'avanzamento non si cancella (vedi ON-4).
- **Scenario:** l'admin reimposta e mostra il QR nuovo alla moglie. Sul suo telefono non c'è dove scansionarlo: deve
  cancellare i dati dell'app.
- **Correzione:**
  - "Rientra con un QR nuovo" in Impostazioni → Famiglia (stesso meccanismo di ON-4);
  - un avviso quando il registro mostra una fondazione nuova firmata da un'altra chiave.
- **Stato: corretta** (19/10). Impostazioni → Famiglia → «Rientra con un QR nuovo» (lo stesso `startOver`, dopo un foglio
  di conferma; su un admin un avviso in più), visibile anche con il registro «vecchio»; la pagina avvisa quando sul NAS c'è
  un registro più nuovo non verificabile. Dopo un reset la pagina Reimposta dice di usarla. Test:
  `VerificaFunzionaleFamigliaTest.on5_…` (reset vero, poi rientro nella famiglia nuova), `VerificaFunzionaleImpostazioniTest.on5_…`.

#### ON-6 · Admin perso senza kit: nessuna via d'uscita
- **Gravità:** Blocca. **Stato:** confermato nel codice. Potrebbe essere una scelta di sicurezza non scritta: va decisa.
- **Flusso:** E-S13 dice: "recupero con il kit o con il secondo admin; altrimenti reset della famiglia guidato".
- **Percorso:**
  - il reset si vede e si avvia solo da admin (`feature-settings/.../family/FamilyScreen.kt:199-208`,
    `FamilyResetScreen.kt:102-106, 129`), ma senza admin nessuno è admin;
  - `NasFamilyReset.reset` invece non ha bisogno della chiave vecchia (`lifecycle/NasFamilyReset.kt:36-49, 99`);
  - nell'onboarding "Qui c'è già una famiglia" offre solo "Recupera l'admin" ed "Entra come familiare".
- **Correzione:** permettere il reset a un dispositivo approvato (con doppia conferma e un avviso a tutti), oppure scrivere
  in `decisioni.md` che senza kit né secondo admin non si può e aggiornare la guida.
- **Decisione dell'utente (19/10): «via dal PC».** Nessun familiare può azzerare la famiglia dall'app: resta un potere
  dell'admin, e l'app non ne riceve di nuovi.
- **Stato: corretta** (19/10). La guida «Ricominciare da zero» spiega come cancellare dal PC solo la cartella
  `\\<indirizzo del NAS>\<area comune>\.mycloudgallery\family` (con il percorso vero, come lo scrive Esplora file), poi
  rifare «Crea la famiglia» e far rientrare gli altri dispositivi; avvisa che si perdono la famiglia e i dispositivi
  registrati, non le foto. Sta dove l'utente la cerca: nel recupero dell'onboarding (nessun kit, codice che non apre), nel
  recupero delle Impostazioni («Ho perso l'admin») e in Impostazioni → Famiglia. Test: `VerificaFunzionaleViewModelTest.on6_…`,
  `VerificaFunzionaleImpostazioniTest.on6_…`.

#### ON-7 · "Configura il controllo" (Rete) e "Rivedi la checklist" (Avviso sul backup) aprono il riepilogo dell'onboarding
- **Gravità:** Blocca; dopo l'onboarding il controllo della VPN non si configura più. **Stato:** confermato.
  L'ha trovato anche la revisione della rete.
- **Percorso:**
  - `SettingsNavigation.kt:201` e `:208` → `resumeStep(VPN)` e `resumeStep(SECURITY_NOTICE)` → `OnboardingRoute` →
    `viewModel.resume` (`OnboardingScreen.kt:22-24`; il VM ignora l'esito, `OnboardingViewModel.kt:159-161`);
  - `coordinator.resume` accetta solo passi saltati e non completati (`DefaultOnboardingCoordinator.kt:208-214`). VPN completato
    non lo è; `SECURITY_NOTICE` non è saltabile e per chi è entrato o ha recuperato non è nemmeno nel piano;
  - la pagina mostra allora `progress.current = SUMMARY`, e Indietro (sistema e freccia) chiama `back()`, che non fa niente
    a onboarding finito (`OnboardingHost.kt:150-162`, `DefaultOnboardingCoordinator.kt:203`);
  - il tasto "Configura" compare ogni volta che WireGuard non è `READY` (`network/NetworkScreen.kt:284-293`), "Rivedi la
    checklist" sempre (`security/BackupNoticeScreen.kt:84`).
- **Scenario:** l'utente tocca "Configura il controllo" e si trova su "Riepilogo · Tutto pronto". Indietro non funziona;
  "Vai alla Libreria" lo riporta indietro. Per un admin recuperato con il registro non riletto (`Stale`), "Vai alla Libreria"
  fallisce, e si esce solo chiudendo l'app.
- **Correzione:**
  - far riaprire a `resume` anche un passo completato, quando arriva dalle Impostazioni;
  - nell'host, se `resume` fallisce, uscire (`onDone`);
  - togliere "Rivedi la checklist" dove il passo 13 non c'è.
- **Test:** coordinatore vero con il passo VPN completato: `resume(VPN)` deve dare `Advanced` (oggi dà `Problem`).
- **Stato: corretta** (19/10). A onboarding finito `resume` riapre anche un passo locale già fatto (6-11 e 13, se nel piano;
  mai quelli del NAS). Se non si riapre la pagina esce, e «Indietro» esce sempre dal passo riaperto. «Rivedi la checklist»
  c'è solo se il passo 13 è nel piano. Test: `VerificaFunzionaleOnboardingTest.on7_…` (coordinatore vero: `resume(VPN)`
  dà `Advanced`), `VerificaFunzionaleViewModelTest.on7_…`, `VerificaFunzionaleImpostazioniTest.on7_…`, `FakeCoordinatorParityTest`.

#### ON-8 · "Da completare → Cartelle da escludere" non ha nessun effetto
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Percorso:**
  - le esclusioni dell'area comune entrano nella configurazione firmata al passo 12 (`OnboardingSupport.kt:111-129`), e la
    scansione usa quelle firmate (`AppGraph.kt:313`, `nas/NasGraph.kt:601-606`);
  - riprendere il passo 5 dopo la fondazione scrive solo lo snapshot locale (`DefaultOnboardingCoordinator.kt:450-463`), e
    nessuno firma di nuovo la configurazione (`FamilyAuthority.updateConfig` non ha chiamanti);
  - nel percorso normale il passo 5 saltato si può riprendere solo dopo il 12 (dal riepilogo o dalle Impostazioni).
- **Scenario:** l'utente salta "Cartelle", poi da Impostazioni → Da completare esclude "Musica". La voce sparisce, ma la
  musica viene comunque scansionata.
- **Correzione:** sull'admin, dopo il passo 5 ripreso, `updateConfig` e `commit`. Sugli altri, la voce non deve comparire.
- **Stato: corretta** (19/10). Dopo la fondazione il passo 5 firma le esclusioni (`RegistryService.updateCommonExclusions`
  → `FamilyAuthority.updateConfig` → `commit`, con la chiave di famiglia aperta come ogni azione dell'admin); se la firma non
  riesce il passo resta da completare. Gli altri percorsi non hanno il passo 5. Test: `VerificaFunzionaleOnboardingTest.on8_…`,
  `VerificaFunzionaleRegistroTest.on8_…`, `VerificaFunzionaleFamigliaTest.on8_…` (fino alle esclusioni che usa la scansione).

#### ON-9 · Un errore nella ricerca dei kit diventa "Non trovo nessun kit"
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Percorso:** `searchKits` trasforma qualunque `NasResult.Err` in `noKits = true` senza problema
  (`OnboardingViewModel.kt:816`). La scheda dice "Nella cartella «recovery» di quest'area non c'è nessun kit"
  (`strings_join_recovery.xml:107-108`).
- **Scenario:** NAS lento, permesso negato o VPN caduta. L'admin legge "nessun kit" e pensa di doverlo dare per perso, quindi
  di dover reimpostare la famiglia.
- **Correzione:** mostrare il problema (`problemOf(error)`) con "Riprova", e distinguere "nessun kit" da "non riesco a leggere".
- **Stato: corretta** (19/10). Un errore del NAS nella ricerca dei kit è il suo problema (`NasError.asOnboardingProblem`) con
  «Riprova»; «nessun kit» solo con l'area letta e vuota. Le Impostazioni lo facevano già. Test:
  `VerificaFunzionaleViewModelTest.on9_…`.

#### ON-10 · La pagina d'attesa promette la Libreria comune, che non c'è
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Percorso:**
  - la nota dice "Puoi già guardare la Libreria comune" (`strings_join_recovery.xml:93`), e il piano 2.3 ed E-S14 parlano di
    "sola lettura";
  - ma `AwaitingApproval` registra solo la pagina d'attesa (`AppNavHost.kt:193-195`), e il catalogo esiste solo con `Ready`
    (`GalleryGraph.core`).
- **Correzione:** togliere la frase, oppure aprire la Libreria in sola lettura durante l'attesa (decisione di prodotto).
- **Stato: corretta** (19/10). Tolta la frase (decisione: niente Libreria in sola lettura durante l'attesa); anche la
  nota della pagina della password non dice più «potrai guardare». È un testo: nessun test di comportamento.

#### ON-11 · Dopo un azzeramento, "Configura di nuovo" nello stesso processo lascia la galleria e il NAS spenti
- **Gravità:** Sbagliata. **Stato:** plausibile. L'ha trovato anche la revisione del NAS.
- **Percorso:**
  - l'azzeramento ferma la galleria per sempre (`GalleryGraph.shutdown`, `gallery/GalleryGraph.kt:677-685`, `retired`; e
    `NasGraph.stop`);
  - `RootViewModel.reconfigure` → `AppGraph.forgetCompletedWipe` (righe 363-370) non riavvia il processo, e
    `GalleryShutdownTest` lo fissa come voluto.
- **Scenario:** il telefono segnato perso viene ritrovato con l'app aperta. Si azzera, l'utente tocca "Configura di nuovo",
  rientra ed è approvato. La Libreria resta vuota e preferito/nascondi danno errore finché Android non chiude l'app.
- **Correzione:** dopo "Configura di nuovo" riavviare il processo, oppure ricreare `GalleryGraph`.
- **Test:** variante di `GalleryShutdownTest`: shutdown → `NotConfigured` → `Ready`. Attesi `coreIfReady() != null` e `nas.start() == true`.
- **Stato: corretta** (19/10). «Configura di nuovo» dopo un azzeramento riavvia il processo (`ProcessRestart`, in
  produzione `AndroidProcessRestart`): galleria e NAS ripartono in un processo nuovo. `GalleryShutdownTest` resta com'è
  (la galleria fermata non riparte nello stesso processo). Test: `VerificaFunzionaleFamigliaTest.on11_…`.

#### ON-12 · Minori dell'onboarding (confermati)
- **ON-12a.** Se `register` trova il dispositivo già approvato, il VM mostra "Approvato" e `finishJoin` emette `Finished`
  senza finire l'onboarding (`OnboardingViewModel.kt:759-764`, `onDone = {}`). L'utente si ritrova su "Ruoli". Caso raro
  (rientro con la stessa chiave).
  **Stato: corretta** (19/10): «già approvato» porta ai passi sul telefono (tasto «Continua»), non fuori. Test:
  `VerificaFunzionaleViewModelTest.on12a_…`, `OnboardingFlowTest.lAdminApprovaEIlDispositivoEntra`.
- **ON-12b.** L'impronta digitata nel recupero (`TypedFingerprint.normalize`, `contract/Lifecycle.kt`) non trasforma O in 0
  né I/L in 1, a differenza di `Base32.normalizeHumanInput` usato altrove. Un "o" letto dal foglio dà "kit non verificato".
  **Stato: corretta** (19/10): `TypedFingerprint.normalize` legge gli alias come `Base32.normalizeHumanInput`, solo per il
  confronto; gli alias stanno fuori dall'alfabeto dell'impronta, quindi un'impronta vera resta se stessa e due impronte
  diverse restano diverse. Test: `VerificaFunzionaleOnboardingTest.on12b_…`.
- **ON-12c.** Un admin appena recuperato può tornare alle Aree, cambiarle e rifare il recupero: `SETTLED_STEPS` vale solo
  per famiglia fondata o richiesta inviata (`DefaultOnboardingCoordinator.kt:134-135`). Cambiando l'area comune,
  `syncLocked` leggerebbe il registro dalla share sbagliata.
  **Stato: corretta** (19/10): con l'admin recuperato percorso e passi 2-4 sono fissati come dopo la fondazione, e «Indietro»
  non ci torna più (per ogni percorso fissato: prima si arrivava alle aree e da lì non c'era un «avanti»). Test:
  `VerificaFunzionaleOnboardingTest.on12c_…`, `OnboardingCoordinatorTest.fondataLaFamigliaIndirizzoAccountEAreeNonSiRifanno`,
  `FakeCoordinatorParityTest`.

### 2.2 Rete, VPN, Diagnostica, Impostazioni

#### RE-1 · Il permesso `CONTROL_TUNNELS` di WireGuard non viene mai chiesto
- **Gravità:** Blocca la VPN gestita dall'app (2.5). **Stato:** confermato: la richiesta non esiste. Va provato sul telefono
  se Android lo offra altrove.
- **Percorso:**
  - è un permesso "pericoloso" (`core-network/src/main/AndroidManifest.xml:13-18`), e lo stesso
    `WireGuardVpnController.kt:35` dice che "lo concede l'utente a runtime";
  - nessun `requestPermissions` per quella stringa in tutto il repository, e `PermissionKind` non lo prevede;
  - senza, la capacità resta `PERMISSION_MISSING` (`WireGuardVpnController.kt:72-74, 131-135`). Il passo VPN e la Rete dicono
    solo di accendere l'opzione dentro WireGuard (`VpnScreen.kt:191-199`).
- **Correzione:** `RequestPermission` per `com.wireguard.android.permission.CONTROL_TUNNELS` nel passo VPN e in Rete, poi
  `wireGuard.refresh()`. Dopo un rifiuto definitivo, aprire Info app.
- **Prova:** sull'S25, `dumpsys package` deve dare `granted=true` dopo il tocco.
- **Stato: corretta** (19/10). Il permesso si chiede con il dialogo di sistema da un tasto «Consenti il controllo»: nel passo VPN
  dell'onboarding (quando c'è WireGuard e manca il permesso) e in Impostazioni → Rete e VPN. Dopo il dialogo `VpnController.refresh()`
  rilegge la capacità (che non cambiava da sola); un rifiuto resta visibile con «Riprova a consentire» e, se Android non mostra più la
  richiesta (`shouldShowRequestPermissionRationale` falso dopo il rifiuto), con «Apri le impostazioni dell'app»; il passo si rilegge
  anche a ogni ritorno in primo piano (permesso dato dalla scheda dell'app). La Diagnostica porta a Rete e VPN
  (`NetworkDiagActions.OPEN_VPN_CONTROL`). Test: `WireGuardPermissionTest` (nome nel manifest, esito del dialogo, parità
  `FakeVpnController`/vero), `WireGuardVpnControllerTest.ilPermessoConcessoDalDialogoSiVedeSoloRileggendo`,
  `NetworkViewModelTest.ilPermessoDiWireGuard_…`, `OnboardingViewModelTest.ilPassoDellaVpnRileggeIlPermessoAdOgniIngresso` e
  `ilRifiutoDelPermessoRestaNelPassoConIlModoDiRiprovare`, `NetworkScreenTest` (i tre tasti), `NetworkDiagnosticsContributorTest`.

#### RE-2 · La sospensione dell'automazione VPN non si toglie più
- **Gravità:** Blocca. **Stato:** confermato.
- **Percorso:**
  - `DefaultVpnAutomation.kt:146-149, 236-239` la salva e smette di agire;
  - la toglie solo `VpnControlConfigStore.save`, che avviene solo se la scelta cambia (`graph/Adapters.kt:177`) o con la prova
    del passo VPN, che dopo l'onboarding non si raggiunge (ON-7);
  - `clearSuspension()` non ha chiamanti, e `NetworkViewModel` non ha un tasto per riattivare.
- **Scenario:** tre cambi di rete in cui la VPN non viene riconosciuta in 10 secondi. La Rete dice "Fermo" per sempre; la
  Diagnostica dice "riattiva il controllo", ma non c'è dove farlo.
- **Correzione:** "Riattiva il controllo" in Rete (`clearSuspension()` più refresh), e lo stesso dopo una prova manuale riuscita.
- **Stato: corretta** (19/10). Due difetti: la sospensione non si toglieva, e anche togliendola (`save`, `clearSuspension`) l'automazione
  teneva i conti di prima (ultimo tentativo sulla stessa rete, tre insuccessi, attesa crescente): non partiva nessun comando finché la
  rete non cambiava, e un insuccesso la risospendeva subito. Ora `DefaultVpnAutomation` ricomincia da zero quando vede sparire la
  sospensione; «Riattiva il controllo» in Rete la toglie (`VpnControlConfigStore.clearSuspension`); una prova a mano riuscita la toglie da sola
  (per `NOT_CONTROLLABLE` qualunque prova riuscita, per `LOCKDOWN_SUSPECTED` solo lo spegnimento riuscito con la rete che resta). Test (visti rossi
  prima della correzione): `VpnAutomationTest.riattivareDopoUnaSospensione_…` e `salvareDiNuovoLaScelta_…`; `NetworkViewModelTest.riattivareIlControllo_…`,
  `unaProvaARiuscita_…`, `dopoIlSospettoDiBloccoSoloSpegnere…`, `unaProvaFallitaNonRiattivaNiente`; `NetworkScreenTest`.

#### RE-3 · I lavori a processo freddo non aspettano la rete
- **Gravità:** Blocca il lavoro notturno quando succede. **Stato:** plausibile.
- **Percorso:**
  - `TrustSyncRun.awaitNetwork` (`work/TrustSyncRun.kt:106-114`) esiste perché le reti arrivano dai callback un attimo dopo
    l'avvio del processo;
  - `BackupStages.round`, `NasWork.doWork` e `StagedRun` non lo fanno. La prima valutazione può vedere "offline" o "NAS
    irraggiungibile" (`NasStatusMonitor.kt:69-72`), e per scelta un'attesa di rete non programma niente (`BackupWork.delayFor`).
- **Scenario:** WorkManager sveglia l'app alle 2 di notte, il giro finisce subito "riuscito" senza far niente e si riprova
  dopo 6 ore.
- **Test:** `FakeNetworkObservationSource` che pubblica la rete dopo 300 ms, poi `BackupStages.photos()` subito: oggi `Waiting(Offline)`.
- **Correzione:** portare `awaitNetwork()` in un punto comune, prima della prima valutazione delle regole.
- **Stato: corretta** (19/10), **confermata** da un test sul grafo vero: con la rete che compare 300 ms dopo l'avvio, il giro del backup (`BackupStages`)
  finiva `Waiting(Offline)`. Ora `AppGraph.stageRules()` è l'unico punto: il motore delle regole preceduto da `NetworkSettler.settle()`
  (aspetta fino a 5 s che la rete si veda, poi una sonda TCP 445 se non si sa ancora che il NAS risponda), una volta per tappa, prima della prima
  valutazione. Lo usano `NasStages`, `BackupStages` (anche le foto appena scattate, D34) e, con la sonda sempre, `TrustSyncRun`. Test:
  `VerificaFunzionaleReteTest.re3_unProcessoFreddoAspettaLaReteInvecePerdereIlGiro` (backup vero) e `re3_leRegoleDiUnaTappa…`.

#### RE-4 · Diagnostica "Ultimi esiti ed errori": sempre vuota
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Percorso:** `RingDiagnosticsLog` è legato (`di/AppModule.kt:62`), ma nessun codice di produzione chiama `record`. Gli
  errori dei lavori finiscono solo in `GalleryLog`, che in release è muto (`app/src/release/.../AppLogging.kt`; per esempio
  `NasWork.kt:308-310`, `AppGraph.kt:352`).
- **Correzione:** scrivere nell'anello, con i codici del catalogo, gli esiti dei lavori, `AppGraph.start`, `TrustSyncRun`, la
  VPN e l'interruttore.
- **Stato: corretta** (19/10). `DiagnosticsRecorder` (in `feature-diagnostics`) scrive nell'anello solo codici e numeri (`ExportValue`: nessun testo
  libero): le tappe dei lavori (`EVT_JOB_RESULT`: lavoro, esito, motivo dell'attesa, lotti, byte, durata), gli errori inattesi (`EVT_JOB_FAILED`),
  gli stop di Android (`ACT_JOB_STOPPED`, che prima non si salvavano), la sessione NAS (gli stessi codici `NAS_*` della Diagnostica, e il ritorno),
  i problemi del backup (`BACKUP_*`), la sospensione della VPN e il fallimento dell'avvio. Un giro a vuoto non scrive niente e lo stesso esito
  non si riscrive prima di sei ore (dieci minuti per gli errori del NAS). Le righe dei lavori hanno per lo schermo una frase («Backup delle foto:
  in attesa · Nessuna connessione», solo nel dettaglio, mai esportato). Test: `DiagnosticsRecorderTest`, `VerificaFunzionaleReteTest.re3_…`/`re4_…`/`re7_…`.

#### RE-5 · Cambio indirizzo del NAS e nuova impronta di casa: valgono solo per questo telefono
- **Gravità:** Sbagliata. **Stato:** confermato. Il guasto per i familiari è plausibile.
- **Percorso:** `AppNasAccountSettings.updateAddress` (`Adapters.kt:202-205`) e `NetworkViewModel.confirmCapture` (riga 235)
  scrivono solo in locale. `createInvite` prende IP e ULA dalla configurazione firmata (`NasRegistryService.kt:149, 152`), che
  nessuno aggiorna (`FamilyAuthority.updateConfig` non ha chiamanti).
- **Scenario (E-R7, E-R16):** il NAS cambia IP. L'admin lo corregge sul suo telefono, ma il QR nuovo porta ancora l'IP vecchio
  e il familiare non entra.
- **Correzione:** sull'admin, dopo la conferma, `updateConfig` e `commit`.
- **Stato: corretta** (19/10). Nuove azioni firmate `RegistryService.updateNasAddress` e `updateHomeUla`, con lo stesso controllo
  dell'admin di `updateCommonExclusions` (`RegistryWriter.adminContext`: dispositivo approvato, chiave di famiglia nella cassaforte a
  telefono sbloccato; un telefono che non la tiene risponde `NotAdmin`): `FamilyAuthority.updateConfig` + `commit`. La pagina dell'indirizzo e
  la nuova raccolta dell'impronta le chiamano **dopo** il salvataggio locale (la firma si scrive sul NAS al nuovo indirizzo) e dicono fin dove
  vale il cambio: per la famiglia (admin, firma riuscita), solo per questo telefono (non admin: gli altri telefoni e i nuovi inviti li aggiorna
  l'admin), oppure salvato qui ma non firmato, con «Riprova». L'identità del NAS (GUID) resta quella firmata; un indirizzo che il formato non
  accetta e un'impronta senza nessun /48 ULA valido non firmano niente. Test: `VerificaFunzionaleRegistroTest.re5_…` (registro vero: Done,
  NotAdmin, la stessa configurazione non firma una versione nuova, l'invito porta i dati nuovi), `AddressViewModelTest`, `NetworkViewModelTest`,
  `AddressScopeScreenTest`, `NetworkScreenTest`, `VerificaFunzionaleReteTest.re5_…` (grafo vero).

#### RE-6 · Dopo una sonda fallita l'indicatore resta "NAS non raggiungibile"
- **Gravità:** Sbagliata. **Stato:** plausibile.
- **Percorso:** l'ultimo esito della sonda si azzera solo al cambio di percorso (`Adapters.kt:103-113`), e `GlobalStatus`
  lo usa a sessione inattiva (`status/GlobalStatus.kt:76-80`). Le regole leggono `isReachable` (`RuleEvaluator.kt:176`), che
  torna vero solo con una sonda o una sessione `Ready`.
- **Effetto:** l'indicatore torna "non raggiungibile" alla chiusura della sessione, anche dopo che il NAS è tornato, e i lavori
  dicono "NAS spento" senza provarci.
- **Test:** `RootViewModelTest` con la `ObservedReachabilityProbe` vera: `TimedOut`, poi `Ready`, poi `Idle`; deve dare
  "a casa, inattivo".
- **Stato: corretta** (19/10), **confermata** dal test (prima: «NAS non raggiungibile» dopo `Ready` e `Idle`). `ObservedReachabilityProbe`
  dimentica l'esito anche quando la sessione risponde (pronta o in risveglio); all'ingresso in primo piano, se non si sa che il NAS risponda, una
  sonda TCP sola (`NetworkSettler`), così l'indicatore e i lavori («NAS spento» senza provarci) non restano fermi a una sonda vecchia. Test:
  `RootViewModelTest.dopoUnaSondaFallitaIlNasTornatoNonRestaIrraggiungibile`, e i lavori via `NetworkSettler` (RE-3).

#### RE-7 · I limiti di Android non arrivano mai alle regole
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:** `AppGraph.kt:289` lega `NoAndroidLimits` (`Adapters.kt:181-184`), quindi `WaitReason.AndroidLimit` è codice
  morto. `ACT_JOB_STOPPED` non ha contributore e `stopReason` non si salva.
- **Effetto:** con la quota dei job esaurita la Diagnostica non dice perché i lavori si fermano.
- **Stato: corretta** (19/10). `AndroidLimitsTracker` (:app) sostituisce `NoAndroidLimits`: quando WorkManager ferma un lavoro per quota,
  bucket sceso o sfondo limitato (`STOP_REASON_QUOTA`, `APP_STANDBY`, `BACKGROUND_RESTRICTION`) la quota risulta esaurita per un'ora (o finché
  l'app non torna in primo piano) e le regole dicono «limite di Android raggiunto» invece di far partire lavori che Android fermerebbe. Il motivo
  di ogni stop si salva nell'anello (`ACT_JOB_STOPPED`, `StopReports`). I budget dei servizi in primo piano restano spenti (l'app non ne usa
  di quei tipi); gli stop dei lavori dell'utente (UIDT, non soggetti alla quota) non sono ancora registrati; il motivo dello stop c'è da Android 12
  (API 31): su Android 10 e 11 non si registra niente. Test:
  `VerificaFunzionaleReteTest.re7_…` (grafo vero: Run → Wait(AndroidLimit) → Run).

#### RE-8 · Preset, tetti dati e roaming non si cambiano dopo l'onboarding
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Percorso:** l'unico scrittore di preset e qualità è il passo 8 (`DefaultOnboardingCoordinator.kt:482-493`), e `PRESET` non
  è saltabile. Non c'è nessuna pagina in Impostazioni; la riga "Quando fa il backup" non ha azione.
- **Effetto:** "Notte" resta per sempre. Le "settimane dei preset" richieste per chiudere la 4B (`fase4-progetto.md:54`) non si
  possono fare dall'app.
- **Stato: corretta** (19/10). Nuova pagina Impostazioni → «Quando lavora l'app» (`feature-settings/.../rules`): preset (i quattro
  dell'onboarding), tetto dei dati mobili (come il preset, 500 MB, 1, 2, 5, 10 GB, nessun tetto) e roaming. `RulesChoices` scrive nelle stesse
  `RuleSettings` dell'onboarding: il preset ha **lo stesso effetto** del passo 8 (preset e fascia notte derivata dall'orario del NAS; le
  anteprime HQ le decide la pagina delle anteprime); il motore le rilegge da solo. Il tetto Wi-Fi non cambia mai. La riga «Quando fa il backup»
  della pagina Backup (modulo `feature-backup`, fuori dal perimetro) non ha ancora l'azione: può aprire questa pagina. Test: `RulesSettingsTest`,
  `VerificaFunzionaleReteTest.re8_…` (onboarding vero e DataStore vero).

#### RE-9 · Chiave del Keystore invalidata: l'app dice "NAS non raggiungibile" invece di chiedere la password
- **Gravità:** Minore. **Stato:** plausibile.
- **Percorso:** `SmbNasSessionManager.kt:577` (`CredentialsMissing`) porta a `Unavailable`, mostrato come irraggiungibile
  (`GlobalStatus.kt:73-74`). Nessuna notifica.
- **Stato: corretta** (19/10), **confermata** dal test (prima: 7 connessioni TCP per 1 sola richiesta). Nessuna password da provare → nessuna autenticazione
  verso il NAS (l'account non rischia di bloccarsi) e nessun nuovo socket: `SmbNasSessionManager` ricorda l'account senza password e rifiuta ogni
  connessione con `CredentialsMissing` finché la password non torna leggibile (rilegge il Keystore in locale, quindi vale per ogni via con cui la password
  viene salvata); un guasto passeggero del Keystore non conta. L'indicatore diventa «Serve la password del NAS» (tocco → password) e la notifica
  «Serve la password» parte una volta sola. Test: `SessionTest.chiaveInvalidata_…`, `GlobalStatusTest.laChiaveDelKeystoreInvalidata…`,
  `VerificaFunzionaleReteTest.re9_…` (grafo vero: stato, notifica, nessun socket, password nuova → un tentativo, avviso tolto).

#### RE-10 · La Diagnostica "Rete e NAS" non ha dati di sessione, e "Ripeti il riconoscimento" non ha tasto
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:** `RECAPTURE_HOME` finisce nel ramo `else -> 0` di `DiagnosticsActions.kt:74`, e `AppNavHost.onDiagnosticsAction`
  (righe 247-253) non lo gestisce.
- **Stato: corretta** (19/10). «Ripeti il riconoscimento» ha l'etichetta (e quindi il tasto) e porta a Impostazioni → Rete e VPN, dove c'è la
  raccolta; la stessa via per «Consenti il controllo» e «Riattiva il controllo» (`OPEN_VPN_CONTROL`). `diagnosticsRouteOf` è pura. Il titolo parla
  anche dei «dati di sessione» della sezione Rete e NAS: il corpo della voce non ne descrive nessuno mancante (la sessione NAS è nelle voci `NAS_*`),
  quindi qui è corretto solo il tasto. Test: `NetworkActionsTest`, `DiagnosticsRoutesTest`, `NetworkDiagnosticsContributorTest`.

#### RE-11 · Con un router senza internet i lavori non girano
- **Gravità:** Minore. **Stato:** plausibile.
- **Percorso:** il vincolo `NetworkType.CONNECTED` (`NasWork.kt:98`, `TrustSyncScheduling.kt:134`, `BackupWork`) richiede una
  rete con internet validata, mentre `rete-casa-vpn.md` §5 dice che casa vale anche senza.
- **Stato: corretta** (19/10), **confermata**: in WorkManager 2.11.2 `NetworkType.CONNECTED` arriva a JobScheduler come `NETWORK_TYPE_ANY`, che
  Android traduce in una richiesta con `INTERNET` e `VALIDATED` (`LanConstraintsTest.ilVincoloDiPrima_chiedeAndroidUnaReteConInternetValidato`):
  il Wi-Fi di casa senza WAN non la soddisfa mai. Tutti i vincoli di rete dei lavori (periodici, immediati, a catena, lavori dell'utente UIDT)
  passano ora da `LanConstraints`: una rete qualunque (Wi-Fi, Ethernet o cellulare), senza internet e senza validazione. Se la rete serve
  davvero, e se è casa, lo decide il motore delle regole dentro il lavoro. Test (visti rossi rimettendo `CONNECTED`: nessuna richiesta di rete nei
  lavori): `LanConstraintsTest` (la richiesta, il backup con le sue condizioni di carica e batteria, tutti i lavori periodici veri, la verifica e il
  controllo del registro immediati, i `JobInfo` dell'utente); `BackupWorkTest` e `TrustSyncSchedulingTest` non pretendono più `CONNECTED`.

### 2.3 Galleria del telefono

#### GA-1 · Dal viewer dei Nascosti, "Mostra nella Libreria", "Preferito" ed "Elimina" non cambiano niente a schermo
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Percorso:**
  - `CatalogHidden.read` restituisce una vista costruita una volta sola (`gallery/CatalogPorts.kt:88-95, 122-129`);
  - `ViewerController.toggleHidden` scrive il catalogo, ma la vista non cambia e il messaggio resta in `pendingMessage`
    (`feature-viewer/.../ViewerController.kt:329-354`).
- **Scenario:** la foto torna davvero in Libreria, ma il viewer resta sulla foto, non dà nessun avviso e la griglia dei
  Nascosti la mostra ancora.
- **Perché i test passano:** il fake delle azioni non tocca nessuna vista (`LibraryFakes.kt:84-95`).
- **Correzione:** una vista viva per i Nascosti finché sono sbloccati, oppure ricaricare dopo ogni azione del viewer.
- **Stato: corretta** (19/10). `CatalogHidden.read` dà una vista viva (`stateIn` con `WhileSubscribed`): finché la griglia dei
  Nascosti o il viewer aperto da lì la guardano, si rilegge a ogni cambiamento del catalogo, quindi «Mostra nella Libreria»,
  «Preferito» ed «Elimina» si vedono (l'elemento esce dalla vista e il viewer passa al vicino con il suo avviso). Quando la pagina
  esce e `HiddenController` dimentica l'oggetto, la rilettura si ferma; rientrando riparte da quello che c'è. Test:
  `VerificaFunzionaleGalleriaTest.ga1_…` (grafo vero), `HiddenLiveViewTest` (il finto, stessi passi). Fake allineato:
  `FakeCollectionsWorld.hiddenLibrary` non dà più una fotografia.

#### GA-2 · In selezione nella Libreria, Indietro esce dall'app
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:** in `feature-library/.../library/LibraryScreen.kt` non c'è un `BackHandler` per la selezione; Raccolte ed
  Eliminati di recente ce l'hanno.
- **Stato: corretta** (19/10). `BackHandler(enabled = grid.selecting) { grid.stopSelecting() }` in `LibraryScreen` (la selezione si
  legge già nella composizione della Libreria: nessuna ricomposizione in più). Test: `LibraryTabTest.ga2_…` (Indietro chiude la
  selezione e non l'app; fuori dalla selezione il tasto resta del sistema).

#### GA-3 · Dopo `onTrimMemory` le miniature visibili spariscono e non tornano finché non si scorre
- **Gravità:** Sbagliata. **Stato:** confermato nel codice; quanto spesso capita va visto sul telefono.
- **Percorso:** `GalleryGraph.onTrimMemory` → `PipelineThumbnailLoader.trim`; da `BACKGROUND` svuota tutto
  (`ThumbnailCache.kt:136-140, 221-228`). `repump` c'è solo sotto il livello 20, e `onForeground` non rimanda la finestra.
- **Test:** `window([A,B])`, poi `trim(BACKGROUND)`, poi primo piano: `image(A)` deve tornare.
- **Stato: corretta** (19/10). `PipelineThumbnailLoader.resume()` riapplica la finestra che il pianificatore ricorda (parte solo ciò
  che manca dalla cache: nessun costo se non è stato scartato niente) e `GalleryGraph.onForeground` la chiama; il contratto
  `ThumbnailLoader` ha `resume()` (di serie non fa niente). Test: `PipelineThumbnailLoaderTest.ga3_…` (rosso prima). Fake
  allineato: `FakeThumbnailLoader.trim` da `UI_HIDDEN` in su porta a null lo stato delle celle (prima lasciava la bitmap alla cella:
  nessun test poteva vedere le miniature sparire) e `resume()` le riporta (`ImagingContractTest.ga3_…`). Resta fuori perimetro il
  selettore 5B (`PickerScreen` fa `window(vuota, vuota)` allo smontarsi): stesso meccanismo, da guardare quando si chiude la 5B.

#### GA-4 · Il primo "Consenti" può non fare niente
- **Gravità:** Minore. **Stato:** plausibile.
- **Percorso:** `LibraryCommandHostEffect.kt:43` tiene `asked` in `remember`, e lo perde con una rotazione. Con il permesso già
  negato per sempre, il primo tocco non apre le Impostazioni.
- **Stato: corretta** (19/10), confermata dal codice (la `MainActivity` non gestisce le rotazioni: si ricrea). Il ricordo di aver già
  chiesto sta in un file (`PermissionAsks`, SharedPreferences con i soli nomi dei permessi, aperto su un thread di lavoro): sopravvive
  a rotazione, morte del processo e riavvio. Un permesso poi concesso si dimentica, così se l'utente lo toglie il dialogo di
  sistema torna ad aprirsi. Test: `PermissionAsksTest` (processo nuovo → «negato per sempre» → Impostazioni al primo tocco). Il
  passo del backup (`BackupHostEffect`, BK-3) ha lo stesso schema e non è stato toccato.

#### GA-5 · Media con `datetaken = 0` saltati nella ripresa della scansione
- **Gravità:** Minore. **Stato:** plausibile.
- **Percorso:** `AndroidMediaStoreSource.kt:359` (0 diventa null nella cache) e `:102-116` (`DATE_TAKEN IS NULL` non prende gli 0).
- **Test:** `FakeMediaProvider` con tre righe `taken = 0` e `limit = 1`: devono arrivare tutte.
- **Stato: corretta** (19/10), confermata dal test (rosso prima). La riga normalizza `datetaken` 0 a null, ma per la ripresa 0 e NULL
  sono diversi (nell'ordine decrescente 0 viene prima dei NULL): la sorgente ricordava il null e riprendeva con
  `IS NULL AND _id < ?`, saltando gli altri zeri e i NULL a `_id` più alto. Ora una riga senza data non si ricorda e la ripresa la
  rilegge dal MediaStore con il valore vero. Test: `AndroidMediaStoreSourceTest.ga5_…` (con la sorgente di prima e con una
  nuova a ogni pagina); parità del finto: `…ga5_ilMediaStoreFinto…` (stesse righe, una alla volta: arrivano tutte).

#### GA-6 · In primo piano la fase METADATI dura 30 secondi e poi si ferma fino al prossimo evento
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:** `DefaultPhoneLibrarySync.kt:138-164`, `PhoneSyncConfig.kt:40`.
- **Effetto:** nella prima lettura, luogo, foto in movimento e raffiche restano incompleti finché non si riapre l'app.
- **Stato: corretta** (19/10). In primo piano la fase META lavora a fette (`runMetaSlices`): se il budget di 30 s finisce prima del
  lavoro (`BudgetExhausted`) parte la fetta dopo, dopo una pausa di 5 s (`PhoneSyncConfig.foregroundMetaPause`); si ferma a `Done`, a una
  pausa (permessi) o a un fallimento, e un giro nuovo ferma tutto prima. Test: `RunnerAndForegroundTest.ga6_…` (rosso prima).

#### GA-7 · Eliminati di recente: le miniature potrebbero non caricarsi
- **Gravità:** da provare (F15). **Stato:** plausibile.
- **Percorso:** `PhoneThumbnailSource.kt:105-114` carica la miniatura senza "includi cestinati".
- **Stato: non confermata** (19/10), e non dimostrabile fuori dal telefono. Se `loadThumbnail` di un elemento nel cestino riesca lo
  decide il MediaProvider, che Robolectric non ha; nell'SDK (android-37.0, letto) non c'è un equivalente pubblico di
  `setIncludePending` per il cestino, quindi il codice non ha nessun interruttore da girare, e nessuna correzione al buio. Se sul
  telefono le miniature mancassero, la cella resta sul segnaposto (E-M10) e «Ripristina» funziona lo stesso. Resta da guardare
  nella prova F15: Eliminati di recente dopo aver eliminato qualche foto.

#### GA-8 · "Condividi" ed "Elimina" su una selezione di soli media del NAS risultano riusciti senza fare niente
- **Gravità:** Minore. **Stato:** confermato. L'hanno trovato sia la revisione della galleria sia quella del NAS.
- **Percorso:** `gallery/LibraryStates.kt:74-81, 88-94` restituisce `true` se non resta nessun riferimento del telefono. Il
  foglio conta però tutta la selezione (`LibraryParts.kt:459-483`).
- **Correzione:** escludere i media solo-NAS dal conteggio e dirlo, oppure spegnere le azioni.
- **Stato: corretta** (19/10). Né Condividi né Elimina dicono più «fatto» senza averlo fatto, e nessuna delle due tocca il NAS (il
  cestino del NAS passa dal viewer, con le sue conferme). `LibraryCommands.onPhone(ids)` dice quali media hanno una copia sul
  telefono (`AppLibraryCommands`: `refOf(id) is MediaRef.Phone`). Condividi: tutta la selezione sul NAS → avviso «Questi elementi sono
  solo sul NAS» con come fare (aprirli e «Scarica sul telefono»); selezione mista → parte il selettore con quelli del telefono e un
  avviso dice quanti sono rimasti fuori. Elimina: tutta sul NAS → nessun foglio, avviso; mista → il foglio conta e tocca solo quelli
  del telefono e dice che gli altri restano dove sono. In difesa, `share` e `deleteFromPhone` rispondono falso (non vero) se non c'è
  niente sul telefono. Test: `LibraryActionNoticeTest.ga8_…` (4), `AppLibraryCommandsTest.ga8_…` (4).

### 2.4 Il NAS nella Libreria e l'organizzazione del NAS

#### NA-1 · Un'operazione nuova aspetta, invisibile, la fine di quella in corso
- **Gravità:** Sbagliata (alta). **Stato:** confermato.
- **Percorso:**
  - `NasOpsEngine.start` tiene `lock.mutex` per tutta l'esecuzione (`core-nasops/.../engine/NasOpsEngine.kt:249-270`,
    `runOp(..., Long.MAX_VALUE)`) e crea la voce del diario solo lì dentro;
  - `AppNasOperations.start` (`nas/NasOrganizer.kt:646-672`) aspetta quella voce dentro il suo mutex `starting`.
- **Scenario:** è in corso uno spostamento di 2.000 file fra le aree e l'utente crea una cartella o rinomina una foto. Niente
  compare in "Operazioni in corso", la richiesta parte ore dopo e una terza resta appesa.
- **Correzione:** creare la voce del diario fuori dal lucchetto dell'esecuzione, eseguire con `resumeAll`, mostrare "in coda".
- **Stato: corretta** (19/10). `NasOpsEngine.start` registra l'operazione nel diario sotto un lucchetto breve (`registration`, mai tenuto
  durante l'esecuzione) e prende il lucchetto dell'esecuzione dopo, solo per eseguire, in ordine di arrivo: l'operazione nuova si vede
  subito come «In coda» (`NasOperationState.Planned`, già mostrato da «Operazioni in corso»). Chi arriva dopo aver trovato l'operazione
  fermata dall'utente (R9) o chiusa non la esegue; «Ferma» su una in coda la annulla senza eseguirla; la stessa richiesta toccata due
  volte resta `AlreadyStarted`; se muore il processo in coda, la riprende `resumeAll`. Test: `VerificaFunzionaleNasOpsTest.na1_…`
  (su **entrambi** gli esecutori del kit, con il NAS bloccato a metà del primo). Fake allineato: `InMemoryNasOperations` registra e poi
  si mette in fila, come il vero.

#### NA-2 · Le operazioni brevi in pausa o in attesa non ripartono da sole
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Percorso:** il lavoro di ripresa si chiede solo per le operazioni lunghe (`NasOrganizer.kt:670`) e all'avvio del processo
  (`:392`).
- **Effetto:** un file aperto dal PC (E-N13), il NAS caduto, lo scongelamento: la voce resta "In attesa" finché Android non
  riavvia l'app, mentre il testo promette "riprende da sola" (`strings_nas_operations.xml:96-97`).
- **Correzione:** `resumeLater(...)` dopo ogni `run` lasciato aperto, e `resumeLater(0)` su scongelamento e "Riattiva".
- **Stato: corretta** (19/10). `AppNasOperations.start` chiede la ripresa dal diario quando l'esecuzione finisce lasciando l'operazione
  aperta (`NasOrganizer.resumeIfLeftOpen`: alla scadenza della prima attesa se sono solo file aperti dal PC, altrimenti subito, con la
  rete e il ritardo progressivo del lavoro), lunga o breve che sia. `resumeSoon()` la chiede quando una share si scongela (a mano o da
  sola: `watchFreezes`), con «Riattiva le scritture» (`NasGraph.setWritesStopped`) e con l'ora del NAS accettata. Test:
  `NasOrganizeIntegrationTest.na2_…` (rinomina di un file aperto dal PC: `WaitingLocked` e `resumeLater` chiesto, nessun UIDT).

#### NA-3 · Cartelle NAS mostra per sempre il primo elenco letto
- **Gravità:** Sbagliata. **Stato:** confermato. La gara della rilettura dopo un'operazione è plausibile.
- **Percorso:** `NasFolderService.observe` legge dal NAS solo la prima volta (`core-nasops/.../folders/NasFolderService.kt:49-53`)
  e vive quanto il processo. Non c'è nessun gesto per aggiornare (`NasFoldersScreen.kt:352-361, 451-462`).
- **Effetto:** i file copiati dal PC o caricati dal backup non compaiono. Dopo uno spostamento l'origine li mostra ancora, e
  toccarli dà "non c'è più".
- **Stato: corretta** (19/10), compresa la gara. `NasFolderService.observe` mostra subito l'ultimo elenco noto e **a ogni
  osservazione** rilegge dal NAS; ogni lettura prende un numero alla partenza e pubblica solo se nessuna partita dopo di lei ha già
  pubblicato (una lettura lenta, partita prima, non sovrascrive quella nuova). In Cartelle NAS c'è il gesto «Aggiorna» (spento mentre
  legge). Test: `VerificaFunzionaleNasOpsTest.na3_…` (4: riaprire rilegge, vecchio-poi-nuovo, la gara), `VerificaFunzionaleNasTest.na3_…`.

#### NA-4 · "Ho controllato, continua" non sopravvive alla morte del processo
- **Gravità:** Sbagliata (alta). **Stato:** confermato.
- **Percorso:** la franchigia sta solo in memoria (`core-nasmedia/.../freeze/MassChangeGuard.kt:88, 113`), mentre `release`
  azzera il congelamento nel cursore salvato.
- **Scenario:** il giro successivo, di solito in un processo nuovo, rivede le stesse 500 assenze e ricongela con lo stesso numero.
- **Correzione:** salvare la franchigia nel cursore e chiedere `scanNow` dopo il rilascio.
- **Test:** `ScanFreezeTest` con un `DefaultNasScanner` nuovo sullo stesso store.
- **Stato: corretta** (19/10), **senza indebolire il congelamento** (F4-D10, E-B19). La franchigia (i conteggi che l'utente ha visto e
  confermato) si salva in `FreezeAcceptances` (in produzione `FileFreezeAcceptances`, un file sotto `replica/` che l'azzeramento toglie)
  **prima** di togliere il congelamento dal cursore, e si rilegge in `StoreFreezeState.load`. Vale per quel congelamento soltanto:
  la usa e la toglie il primo giro completo dopo il gesto **qualunque sia l'esito** (prima restava anche dopo un nuovo congelamento) e
  scade dopo 7 giorni di ora del NAS; il gesto resta quello di prima (il numero riscritto uguale a quello mostrato). Il gesto chiede
  anche la verifica (`NasGraph.release` → `requestVerify`), così il giro che la usa parte subito. Test:
  `ScanFreezeTest.na4_…` (4: processo nuovo, solo quel congelamento, ricongela e la conferma è consumata, scadenza),
  `FileFreezeAcceptances` in `VerificaFunzionaleNasAppTest.na4_…`. Il banco `ScanHarness` ora tiene la franchigia fuori dallo scanner
  (sopravvive a `restartScanner`).

#### NA-5 · Il piede della Libreria resta su "Lavori fermi" dopo lo scongelamento
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:** `NasGraph.kt:444` (ripiego su `NasScanStatus.Frozen`) e `GalleryHostViewModel.kt:86-95` (nessun `scanNow`).
- **Stato: corretta** (19/10). Il piede legge il congelamento da quello che lo scanner dice adesso (`freezeState.frozen`), non
  dall'ultimo stato del giro (che resta `Frozen` fino al giro dopo), e il gesto chiede subito la verifica (vedi NA-4). Test:
  `NasOrganizeIntegrationTest.na5_…` (grafo vero: congela, il piede dice «Lavori fermi», il gesto, il piede non lo dice più, la
  verifica successiva non ricongela e segna le assenze).

#### NA-6 · La prima scansione del NAS non si vede nel piede
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:** i lavori usano sempre `ScanMode.Verify` (`work/NasWork.kt:162, 328`), mentre solo `Full` diventa
  `ScanProgress.First` (`NasGraph.kt:441`).
- **Stato: corretta** (19/10). `NasScanStatus.Scanning` ha `first`, vero finché la share non ha mai finito un giro completo (dal
  cursore: `lastFullScanAt == null`), qualunque sia il modo con cui parte; il piede mostra l'avanzamento della prima lettura per `Full`
  **o** `first`. Dal secondo giro no. Test: `ScanFirstAndVerifyTest.na6_…`.

#### NA-7 · Il riepilogo giornaliero può tacere un congelamento in un processo freddo
- **Gravità:** Sbagliata. **Stato:** plausibile.
- **Percorso:** `NasDailySummary.kt:117-121` legge `nas.frozen.value` subito dopo `start()`, ma la mappa si riempie in modo
  asincrono (`NasGraph.kt:588, 613-620`).
- **Test:** `NasGraph` con un congelamento salvato e `StandardTestDispatcher`: dopo `start()` atteso `frozen.size == 1`.
- **Stato: corretta** (19/10), confermata dal test con un ambito sospeso (rosso prima). `NasGraph.start` pubblica i congelamenti
  salvati **subito dopo `freezeState.load()` e prima di rendere visibile il motore**: il riepilogo giornaliero li legge dopo `start()`
  e i cancelli delle scritture (indice, operazioni dell'utente) non restano aperti su una share congelata nel tempo che il guardiano
  impiega a partire in un processo appena nato (era anche una piccola finestra di sicurezza, non solo un avviso mancato).
  Test: `NasOrganizeIntegrationTest.na7_…`.

#### NA-8 · Il battito della scansione batte anche quando non si è letto niente
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:** `NasWork.kt:166-170, 272-276`.
- **Effetto:** con una share negata "fermo da N giorni" non compare mai.
- **Stato: corretta** (19/10). Il battito della scansione si scrive solo se almeno un'area è stata elencata davvero
  (`NasStages.readSomething`: almeno una cartella letta). Test: `VerificaFunzionaleNasAppTest.na8_…`. Toccato `work/NasWork.kt`
  (solo `NasStages.scan` e una funzione nel `companion`).

#### NA-9 · Toccare la capsula del NAS esegue l'azione della rete
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:** `main/MainScaffold.kt:142` (`onNasStatusClick = onStatusClick`).
- **Effetto:** "Lavori fermi" apre WireGuard o la password, invece della Diagnostica.
- **Stato: corretta** (19/10). `MainScaffold` ha `onNasStatusClick` (di serie quello della rete, per i chiamanti che non lo danno);
  `AppNavHost` lo lega alla Diagnostica. Test: `NasStatusCapsuleTest.na9_…` (un tocco su «Lavori fermi» chiama il NAS e non la rete).

#### NA-10 · Le esclusioni dell'area privata non si possono impostare
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:** `setPrivateAreaExclusions` non ha chiamanti in produzione (`NasGraph.kt:602-608`; E-N15).
- **Stato: corretta** (19/10). In Cartelle NAS, nell'area privata, scelta una o più cartelle compare «Escludi dalla Libreria» (o
  «Includi nella Libreria» se sono già escluse); la riga dice «Esclusa dalla Libreria». `NasAreas.setPrivateExclusions` →
  `NasOrganizer.setPrivateAreaExclusions` (impostazione personale sincronizzata fra i dispositivi dell'account) e chiede subito la verifica:
  la Libreria le toglie alla scansione che segue, **senza toccare niente sul NAS**. Non c'è nell'area comune (le sue esclusioni sono
  firmate dalla famiglia: ON-8). Test: `VerificaFunzionaleNasTest.na10_…` (8), `NasOrganizeIntegrationTest.na10_…` (grafo vero).
  Fake allineato: `FakeNasAreas` salva e conta le verifiche come il vero.

#### NA-11 · Manca il gesto "L'ora del NAS è giusta, continua" (residuo noto, `decisioni.md:444-446`)
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:** `NasGraph.acceptClockJump` non ha chiamanti.
- **Effetto:** dopo un salto dell'ora si aspettano sempre 24 ore.
- **Stato: corretta** (19/10). «Operazioni in corso» mostra la scheda «L'ora del NAS è cambiata di colpo» con «L'ora del NAS è giusta,
  continua»: il tocco apre un foglio («L'ora del NAS è giusta?») e solo «Sì, l'ora è giusta» fa il gesto (`NasGraph.acceptClockJump`,
  che libera anche le operazioni rimaste ferme). Sta qui e non in Diagnostica perché Diagnostica è dell'altro agente; la voce di
  Diagnostica (`INDEX_CLOCK_JUMP`) resta solo testo. Test: `VerificaFunzionaleNasTest.na11_…` (4), `NasOrganizeIntegrationTest.na11_…`.

#### NA-12 · Il servizio delle operazioni in un processo freddo finisce senza fare niente, e le tappe si allontanano
- **Gravità:** Sbagliata. **Stato:** plausibile.
- **Percorso:** `work/NasOpsJobService.kt:75` (`resumeOperations` null prima di `attach`, quindi `Done`) e il backoff
  esponenziale di `BUDGET` (`NasOpsJobs.kt:81`).
- **Effetto:** dopo la morte del processo una mossa lunga avanza a fette sempre più distanti, e "Ferma" sparisce dalla notifica.
- **Test:** servizio su Robolectric con la galleria non ancora partita: non deve finire `Done`.
- **Stato: corretta** (19/10). Il servizio chiama `NasGraph.resumeOperations`, che avvia il motore del NAS se il processo è appena nato,
  aspetta un poco che la galleria lo agganci da sola e, se non c'è ma nel diario ci sono operazioni aperte, risponde «il NAS non risponde»
  (si riprogramma) invece di null (= «fatto»); null solo senza NAS o ad azzeramento fatto (`resumeCold`). Il backoff: una tappa che finisce
  il tempo è ora `NasOpsJobEnd.Continue` (lavoro nuovo subito) invece di `Reschedule` (ritardo esponenziale di JobScheduler);
  `Reschedule` resta per NAS non raggiungibile e scritture ferme. Test: `VerificaFunzionaleNasAppTest.na12_…` (3),
  `NasOpsJobsTest.na12_…`, `NasOrganizeIntegrationTest.na12_…`. Toccati `work/NasOpsJobService.kt` (`NasOpsJobEnd`, `NasOpsJobRun`, la
  chiamata del servizio).

### 2.5 Backup 5A

#### BK-1 · "Proteggi subito le foto nuove" (D34) non protegge subito se il diario ha un arretrato
- **Gravità:** Sbagliata; durante il primo backup di fatto blocca la funzione. **Stato:** confermato.
- **Percorso:** `SafeUploadEngine.drive` prende tutti gli elementi aperti del tipo, senza guardare il trigger
  (`core-backup/.../upload/SafeUploadEngine.kt:280-282`), in ordine `item_id` (`BackupDaos.kt:80`).
- **Scenario:** a primo backup a metà, il giro QUICK carica per 8 minuti l'arretrato vecchio, di giorno e a batteria, contro il
  preset. La foto nuova aspetta la notte.
- **Perché i test passano:** il piano finto (`ReferenceBackupPlanner`) ignora il trigger.
- **Correzione:** con `QUICK_NEW_PHOTOS`, guidare solo gli elementi creati da quel giro.
- **Test:** `BackupIntegrationTest` con 20 elementi vecchi aperti e una foto nuova, budget per un file: deve finire al sicuro
  la foto nuova.
- **Stato: corretta** (19/10). Un giro `QUICK_NEW_PHOTOS` guida solo gli elementi che ha creato (`SafeUploadEngine.drive(only = …)`): l'arretrato
  già nel diario lo riprendono i giri normali, con le regole del preset. Una corsa rapida lasciata a metà non rientra nella corsa dopo (il piano la
  vede già pianificata): la riprende il lavoro delle foto. `ReferenceBackupPlanner` ora legge il trigger come il piano vero (solo foto della
  fotocamera, mai video, al massimo 24, ultimi 3 giorni dall'ora del NAS) e `InMemoryBackupEngine` guida solo ciò che ha creato. Test:
  `VerificaFunzionaleBackupTest.bk1_…` (3: motore vero con budget per un file solo, esecutore di riferimento, senza arretrato),
  `DefaultBackupPlannerTest.bk1_…` (parità fra il piano vero e quello di riferimento).

#### BK-2 · D34 non riparte al rientro a casa se la foto è stata scattata fuori
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Percorso:** `work/BackupWork.kt:118-122` (nessun ritardo per le attese di rete), `:269`, e `backup/BackupGraph.kt:425`
  (`next()` restituisce `Quick` prima di foto e video).
- **Correzione:** rimettere in coda QUICK con il vincolo `UNMETERED`; in `afterPhoneRead` chiedere anche il lavoro delle foto.
- **Stato: corretta** (19/10). `afterPhoneRead` chiede **tutti** i giri che servono (`BackupGraph.nextAll()`: foto nuove, foto, video; prima
  uno solo). Il lavoro rapido che aspetta il Wi-Fi di casa (`Offline`, `NetworkNotAllowed`, `NetworkNotTrusted`, `Roaming`) si rimette in coda su
  una rete non a consumo (`LanConstraints.unmetered`: Wi-Fi o Ethernet, senza INTERNET né VALIDATED come RE-11), con un ritardo di 15 minuti per non
  girare a ciclo su un Wi-Fi che non è casa; con i dati mobili il vincolo non è soddisfatto e non gira niente. Il NAS irraggiungibile a casa e gli
  altri lavori non programmano niente (niente polling). Test: `BackupSchedulingTest.bk2_…` e `gliAltriLavori…`,
  `VerificaFunzionaleBackupAppTest.bk2_laFotoNuovaNonToglieIlLavoroDelleFoto`.

#### BK-3 · Il problema dell'ultimo giro resta sulla pagina dopo che l'utente l'ha risolto
- **Gravità:** Sbagliata; i tasti non fanno niente. **Stato:** confermato.
- **Percorso:**
  - `BackupGraph.kt:652`: `memory.lastProblem()` vince sul resto;
  - si toglie solo con un giro finito o con "Riprova" (`BackupStages.kt:143-158`);
  - la scheda non offre "Riprova" (`BackupStatusCard.kt:229-248`);
  - con il permesso già dato "Consenti" non fa niente (`main/BackupHostEffect.kt:85-90`).
- **Scenario:** l'utente dà il permesso alla posizione e la pagina resta su "in attesa" fino alla notte. Stesso schema per
  la password e per il NAS pieno.
- **Correzione:** ignorare `MEDIA_*` se l'accesso adesso c'è, azzerare il problema al ritorno da un'azione, "Riprova" su ogni
  problema di giro.
- **Stato: corretta** (19/10). Il problema ricordato si confronta col mondo di adesso (`BackupGraph.rememberedProblem`): i permessi dei media
  (`MEDIA_PERMISSION`, `MEDIA_LOCATION`, `PARTIAL_ACCESS`) e la sola lettura dell'area (rimessa alla prova, BK-7) si tolgono da soli quando non valgono
  più; l'osservatore dell'accesso ai media fa ricalcolare lo stato (BK-13). Gli altri (NAS pieno, quota, password) li toglie un giro riuscito, "Riprova"
  (ora offerto anche da `NAS_FULL`, `QUOTA`, `AUTH`, `READ_ONLY_AREA`) o il **ritorno da un'azione**: "Inserisci la password", "Spazio NAS" e "Consenti"
  ricordano di averti mandato fuori, e al ritorno sulla pagina (`BackupActions.onPageResumed`) il problema si toglie e il giro si rifà; senza un'uscita
  aprire la pagina non cancella niente. Test: `VerificaFunzionaleBackupAppTest.bk3_…` (4), `BackupPageScreenTest.bk3_…`,
  `BackupViewModelsTest.ilRitornoAllaPaginaArrivaAlleAzioni`. `BackupHostEffect` usa `PermissionAsks` (stesso difetto di GA-4): `BackupPermissionStepTest`.

#### BK-4 · La pausa si toglie anche quando l'utente annulla
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Percorso:** `backup/BackupUi.kt:419-427`: `resumeFromPause()` avviene prima della decisione e del foglio dei dati mobili, e
  `dismissPrompt` (riga 416) non la rimette.
- **Scenario:** "Ferma", poi "Riprendi" sui dati mobili, poi "Annulla". La pagina dice ancora "In pausa", ma il backup riparte
  al primo Wi-Fi.
- **Correzione:** togliere la pausa solo in `confirm()` o quando il lavoro viene programmato.
- **Stato: corretta** (19/10). `AppBackupActions.startUser` decide prima (`decide` → conferma dei dati mobili) e toglie la pausa solo quando il lavoro
  viene programmato; "Annulla" la lascia com'era. "Stanotte" la toglie senza lavoro dell'utente. Test:
  `VerificaFunzionaleBackupAppTest.bk4_annullareLaConfermaDeiDatiMobiliLasciaIlBackupInPausa` e `bk4_riprendiESenzaConferma…`.

#### BK-5 · L'avanzamento del primo backup riparte da zero ogni minuto e il tempo stimato non c'è mai
- **Gravità:** Sbagliata. **Stato:** confermato.
- **Percorso:**
  - `UploadStages.kt:46-55`: un giro nuovo per ogni lotto da 60 s;
  - `SafeUploadEngine.kt:1192-1200`: contatori del lotto, `etaMillis = null`;
  - `BackupGraph.kt:651`.
- **Effetto:** "1.240 di 6.658 · circa 1 h 20 min" diventa "37 di 980", e la barra della notifica torna indietro.
- **Stato: corretta** (19/10). L'avanzamento è cumulativo e sta fuori dal motore (`RunProgress`): fatti e totale vengono dai media ancora in coda del
  catalogo (`totale = fatti + in coda`, i fatti non scendono mai, le foto nuove allungano il totale); il tempo che manca viene dal ritmo osservato
  mentre il backup lavorava (nulla prima di due media, il tempo fermo non conta); si ricorda su file (`backup-progress`, tolto dall'azzeramento) e finisce
  quando non c'è più niente in coda. Test: `RunProgressTest` (8), `VerificaFunzionaleBackupAppTest.bk5_…` (stati veri di un primo backup).

#### BK-6 · "Inizia ora", "Fai ora" e "Riprendi" finiscono senza fare niente se un lavoro in background tiene il turno
- **Gravità:** Blocca, in quel momento. **Stato:** percorso confermato; quanto spesso capita va misurato.
- **Percorso:** `BackupGraph.exclusive` aspetta al massimo 90 s (`backup/BackupGraph.kt:328-340`), poi `round()` restituisce
  null (`BackupStages.kt:118-122`) e `BackupJobService.kt:69` lo tratta come `DONE`. Guardiano e verifiche tengono il turno
  fino a 10 minuti, QUICK fino a 8.
- **Scenario:** l'utente tocca "Inizia ora" mentre girano le verifiche. "Sto riprendendo…" resta 90 s e poi sparisce.
- **Correzione:** un esito "occupato" distinto da "niente da fare", che faccia `RESCHEDULE`, oppure dare la precedenza al
  lavoro dell'utente.
- **Test:** turno occupato con `exclusive { awaitCancellation() }`, poi `BackupJobRun(..., userInitiated = true)`: atteso
  `RESCHEDULE` (oggi `DONE`).
- **Stato: corretta** (19/10). **Scelta: parte dopo, e gli altri cedono il passo.** Il lavoro dell'utente aspetta il suo turno fino a 15 minuti (non 90
  secondi) e intanto lo dice agli altri (`BackupGraph.userIsWaiting`): le tappe di sfondo, il guardiano e le verifiche finiscono il lotto in corso (al
  massimo 60 secondi) e non ne cominciano altri; se il turno è ancora occupato la tappa dice **occupato** (`BackupStageOutcome.busy`) e il lavoro si
  fa riprogrammare (`RESCHEDULE`; un lavoro normale `Result.retry()`), mai `DONE`. Test: `VerificaFunzionaleBackupAppTest.bk6_…` (2: occupato → `RESCHEDULE`;
  aspetta, poi lavora), `VerificaFunzionaleBackupTest.bk6_…` (la tappa cede il passo / non cede se nessuno aspetta), `BackupWorkTest.bk6_…`.

#### BK-7 · L'area segnata "in sola lettura" non si riprova mai nei giri
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:** `BackupStages.kt:112-117` controlla il cancello prima di `refreshArea()`.
- **Effetto:** dopo che l'admin ha corretto i permessi, il backup resta fermo fino alle verifiche giornaliere.
- **Stato: corretta** (19/10). `BackupStages.round` rimette alla prova la share prima di guardare il cancello (se è aperto o `READ_ONLY_AREA`).
  Test: `VerificaFunzionaleBackupAppTest.bk7_unAreaInSolaLetturaSiRimetteAllaProvaEIlGiroCarica`.

#### BK-8 · Il riepilogo del primo backup non si ricalcola più
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:** `BackupGraph.kt:712` (chiave della cache).
- **Effetti:**
  - il numero di elementi da mettere al sicuro resta vecchio;
  - "Fai ora" sui dati mobili chiede conferma con la stima dell'intero primo backup;
  - in un processo nuovo il calcolo precede la programmazione del lavoro dell'utente.
- **Stato: corretta** (19/10). La chiave del riepilogo comprende i media al sicuro e in coda (`SummaryCache`): si ricalcola quando il backup ha fatto
  strada. La stima per "Fai ora" e per il lavoro (`BackupGraph.uploadEstimateBytes`) aspetta al massimo 3 secondi e poi va avanti con l'ultima nota o
  senza (le regole la usano solo per il tetto e per dirlo nella conferma): la programmazione del lavoro non aspetta più il calcolo di un processo nuovo.
  Test: `VerificaFunzionaleBackupAppTest.bk8_…`, `SummaryCacheTest`.

#### BK-9 · "Apri Impostazioni, Aree" porta alla radice delle Impostazioni, dove le Aree non ci sono
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:** `main/BackupHostEffect.kt:65`; nessuna pagina Aree in `SettingsNavigation.kt`.
- **Stato: corretta** (19/10). La pagina "Impostazioni, Aree" non c'è e non può esserci: le aree si fissano alla fondazione della famiglia (ON-12c) e l'app
  non crea share (E-O3). Il tasto è tolto: `NO_PRIVATE_AREA` propone ora "Chiedi all'admin del NAS" (una frase, nessun tasto) e la scheda "non è ancora
  pronto" lo dice sotto l'elenco. `BackupAction.AREAS_SETTINGS` resta nell'enum (codice stabile) ma nessun problema lo propone più. Test:
  `BackupPageScreenTest.bk9_…`, `BackupPiecesTest.bk9_…`.

#### BK-10 · Un video interrotto riprende solo con il lavoro periodico delle 6 ore
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:** `BackupGraph.kt:429` (ogni elemento aperto diventa `Photos`, e il giro delle foto salta i video).
- **Stato: corretta** (19/10). Un elemento aperto riprende dal lavoro del **suo** tipo (`BackupGraph.hasOpenItems(kind)`, una query sul diario unito al
  catalogo): `hasWork` e `nextAll` non mandano più un video al lavoro delle foto. Test: `VerificaFunzionaleBackupAppTest.bk10_unVideoInterrottoRiprendeDalLavoroDeiVideo`.

#### BK-11 · "Separa" e "Mostra come principale" non chiedono la sincronizzazione dei dati personali
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:** `GalleryGraph.kt:394-409` chiama `personalChanged()` solo per preferiti e nascosti; `SimilarCopiesGraph.kt:141-143`.
- **Stato: corretta** (19/10). `SimilarCopiesChoices` avvisa (`onSaved`) dopo ogni scelta scritta; `SimilarCopiesGraph.onChoiceSaved`, impostato da
  `BackupGraph`, chiede la sincronizzazione accorpata dei dati personali (`NasOrganizer.personalChanged`) come per preferiti e nascosti. Test:
  `SimilarCopiesChoicesTest` (core-personal), `VerificaFunzionaleBackupAppTest.bk11_…`, `SimilarCopiesIntegrationTest` («Separa» chiede la sincronizzazione).

#### BK-12 · Codici di stato che non arrivano mai alla pagina
- **Gravità:** Minore. **Stato:** confermato.
- **Percorso:**
  - `STALE` e `PHONE_STORAGE_LOW` esistono solo in Diagnostica (`BackupDiagnostics.kt:83-86`);
  - `CAP_REACHED`, `WAITING_UNLOCK` e `PROFILE_INACTIVE` non li produce nessuno.
- **Stato: corretta** (19/10). Arrivano alla pagina `STALE` (backup fermo da 3 giorni con media in coda, stessa regola del riepilogo giornaliero),
  `CAP_REACHED` (le regole dicono tetto dati) e `PHONE_STORAGE_LOW` (con media in coda), in quest'ordine di gravità (`BackupGraph.waitingStatus`). `WAITING_UNLOCK`
  e `PROFILE_INACTIVE` **non possono comparire**: la pagina esiste solo con il telefono sbloccato e il profilo attivo, un'app che non gira non può dirlo
  (restano nel catalogo dei codici). Test: `BackupPiecesTest.bk12_…`, `VerificaFunzionaleBackupAppTest.bk12_…`.

#### BK-13 · Lo stato a volte non si ricalcola (cambio dei permessi, "Riattiva le scritture", cache "Sto cercando…")
- **Gravità:** Minore. **Stato:** plausibile.
- **Percorso:** `BackupGraph.kt:592-604`.
- **Test:** cache su "confronto in corso" e un solo evento entro 60 s: lo stato non diventa mai "assestato".
- **Stato: confermata e corretta** (19/10). Lo stato si ricalcola anche per i permessi dei media (osservatore dell'accesso), per le regole (preset,
  soglie), per un riepilogo provvisorio ("sto cercando…": un solo ricontrollo fra un minuto, `SummaryCache.recheckInMs`) e a ogni ritorno sulla pagina Backup
  (`onPageResumed`): «Riattiva le scritture» non ha un evento che il backup ascolti (`EmergencyStop` è un flag in `nas/`), quindi si vede al ritorno sulla
  pagina; la capsula del piede della Libreria lo vede al primo evento successivo. Test: `VerificaFunzionaleBackupAppTest.bk13_…` (2), `SummaryCacheTest`.

#### BK-14 · Solo debug (D13): se l'account arriva dopo le impostazioni, nessuna sorgente valida per tutto il processo
- **Gravità:** Minore, ma tocca le prove sul campo F21 e seguenti. **Stato:** plausibile.
- **Percorso:** `core-backup/.../settings/DefaultBackupSettings.kt:84-98`.
- **Test:** `BackupFenceIntegrationTest` con l'account in ritardo: `settings.sources` deve contenere `DCIM/mcg-prova/`.
- **Stato: confermata e corretta** (19/10). `DefaultBackupSettings.refresh()` ricalcola le sorgenti ammesse con l'account di adesso; la chiamano
  `BackupGraph.start` e il ricalcolo dello stato (nessun effetto senza recinzione). La regola di D13 sta in `BackupSourceFence`, condivisa con
  `FakeBackupSettings`. Test: `DefaultBackupSettingsTest.bk14_…` (3, anche la parità del finto col vero), `BackupFenceIntegrationTest.bk14_…` (debug).

---

## 3. I fake che divergono dal vero

| Fake | Test coinvolti | Differenza dal vero |
|---|---|---|
| `FakeOnboardingCoordinator` (`core-family/.../testing/FamilyFakes.kt`) | `OnboardingViewModelTest`, `OnboardingFlowTest`, `OnboardingSkeletonTest`, test delle Impostazioni | Accetta qualunque input in qualunque passo (il vero: solo il passo corrente). `skip` non rimanda i passi del NAS (E-O1). `resume` guarda `skipped` e non `pending`, e non ha la logica `resuming`. `back` funziona anche a onboarding finito. `Finish` va sempre avanti (il vero vuole l'essenziale fatto, admin per il recupero). `ChoosePath` non azzera lo snapshot. `recoveryCode` ha sempre i gruppi. Nasconde ON-3 e ON-7 |
| `FakeJoinFlow` | `OnboardingViewModelTest` (ingresso con e senza QR) | Non controlla l'invito, il percorso, la casa né la scadenza. Nasconde ON-1 |
| `DefaultJoinFlowTest` (invito costruito nel test) | ingresso manuale | Usa `ManualInvite.create` con le aree e chiama `ConfirmHome`, due cose che la UI non fa (ON-1) |
| `FakeAdminRecovery` | test del recupero nell'onboarding, `AdminRecoveryViewModelTest` | `findKits` ignora account, area, password ed errori; `recover` non ha bisogno del profilo (area comune). Nasconde ON-9 e la forma del difetto del 19/10 |
| `FakeRegistryService` | `ApprovalViewModelTest`, `DevicesViewModelTest`, `InviteViewModelTest`, `SettingsViewModel` | `refresh` non fa niente, `approve` ignora la finestra, lo stato non cambia mai (nessun passaggio a revocato, admin o stale). Nasconde ON-2 e ON-4 |
| `FakeFamilyReset` | `FamilyResetViewModelTest` | Nessun requisito di admin né di NAS (ON-6) |
| `FakeNasReachabilitySignal(initial=true)` | regole, `StagedRun`, contributori Attività | Il vero parte falso e si azzera a ogni cambio di percorso (RE-3, RE-6) |
| `FakeWireGuardPlatform(permission=true)`, `FakeVpnController(READY)` | `WireGuardVpnControllerTest`, `VpnAutomationTest`, VM Rete e passo VPN | Il permesso è sempre concesso e la capacità parte `READY`; il vero parte `PERMISSION_MISSING` (RE-1) |
| `InMemoryVpnControlConfigStore` | VM Rete | Si può partire senza sospensione; nessun test prova come toglierla (RE-2) |
| `FakeConnectionTester`, `FakeAuthCircuitBreaker` | VM Password, Indirizzo, Rete; onboarding | Ignorano fasi, credenziali e interruttore; il vero salta l'autenticazione con l'interruttore aperto |
| `FakeNasSessionManager` | radice, Impostazioni, Diagnostica, lavori | Lo stato non segue le operazioni (mai `Ready`, `WakingUp` o `Unavailable`) |
| `FakeNasAccountSettings` | VM Password, Indirizzo | `updateAddress` registra e basta; nessun effetto sulla configurazione firmata (RE-5) |
| `FakeReachabilityProbe(Open)` in `TestPlatform` | test d'integrazione dell'app | Non fallisce mai (RE-6) |
| `FakeAndroidLimitsSignal` | `PresetMatrixTest` | Limiti veri; in produzione c'è `NoAndroidLimits` (RE-7) |
| `FakeBackgroundHealthReader` (UNKNOWN) | onboarding, Diagnostica | `ignoringBatteryOpt = null` fa contare il passo Batteria come verificato |
| `FakeLocalMediaActions`, `FakeViewerItems` | `ViewerActionsTest`, `ViewerLogicTest`, `HiddenControllerTest` | Le azioni non toccano nessuna vista (GA-1) |
| `FakeThumbnailLoader` | `LibraryTabTest`, viewer, screenshot | L'immagine c'è subito e `trim` non lascia celle vuote (GA-3) |
| `FakePhoneMediaIntents` | `ViewerActionsTest`, Eliminati di recente, `GalleryRig` | `trashRequest` restituisce sempre `null` (nel vero vuol dire "fallito") e non applica il tetto di 2.000 |
| `FakeMediaStore`, `FakeRecentlyDeletedSource`, `FakePhoneAccessReader` | `GalleryIntegrationTest`, sync | Ordine e date diversi (GA-5); cestino separato e fisso; un solo lettore dei permessi invece di due |
| `InMemoryNasOperations`, `InMemoryNasFolders` | `NasFoldersViewModelTest`, `NasOtherViewModelsTest`, `NasActionsControllerTest` | Eseguono fino in fondo senza lucchetto condiviso e rileggono a ogni `observe`; il vero mette in coda (NA-1) e tiene la cache (NA-3) |
| `InMemoryNasTrash`, `InMemoryNasSpace` | `NasOtherViewModelsTest` | Errori mappati diversamente; mai `Denied`; il cestino non viene dal diario |
| `NasOpsJobRun({ null }) → Done`, `RecordingJobs` | `NasOpsJobsTest`, `NasOrganizeIntegrationTest` | "null = niente NAS" anche quando il NAS non è ancora agganciato (NA-12); `resumeLater` dopo una pausa mai verificato (NA-2) |
| Scansioni con `ScanMode.Full`; stessa istanza dello scanner | `NasLibraryIntegrationTest`, `ScanFreezeTest` | L'app usa sempre `Verify` (NA-6); la franchigia sopravvive solo perché l'istanza è la stessa (NA-4) |
| `FakeBackupOverview` | `BackupViewModelsTest`, `BackupPageScreenTest`, `FirstBackupSheetTest`, screenshot | Lo stato lo sceglie il test; il vero tiene problema vecchio, `Stopped(USER)`, avanzamento per lotto e riepilogo in cache (BK-3, BK-4, BK-5, BK-8) |
| `ReferenceBackupPlanner` | `BackupTestKit`, `BackupChaosWorld` (500 interruzioni), `PlannerWithEngineTest` | Ignora il trigger, `alreadyPlanned` e `ONLY_COMMON_COPY` (BK-1) |
| `FakeBackupSettings`, `FakeBackupGate`, `FakePhoneFolders`, `FakeDeviceFolderProposals` | test di core-backup e dei VM | Sorgenti subito, cancello sempre aperto, nessuna recinzione D13, nessuna lettura del NAS (BK-14) |
| `BackupRig` | `BackupIntegrationTest`, `SimilarCopiesIntegrationTest` | Grafo vero, ma `round()` chiamato direttamente: salta azioni, lavoro dell'utente, `BackupJobRun` e WorkManager; diario vuoto quando gira QUICK (BK-1, BK-6) |

**Allineati il 19/10 (correzioni RE).** `FakeVpnController` cambia capacità solo rileggendo (`refresh`, `capabilityOnRefresh`), come
`WireGuardVpnController` che non vede il permesso concesso finché nessuno rilegge (RE-1); il confronto è `WireGuardPermissionTest`. `FakeRegistryService`
registra `updateNasAddress` e `updateHomeUla` (RE-5); il registro vero lo prova `VerificaFunzionaleRegistroTest.re5_…`. Per RE-3 e RE-6 i test nuovi
non usano `FakeNasReachabilitySignal(true)` né `FakeReachabilityProbe(Open)`: girano sul grafo vero (`VerificaFunzionaleReteTest`) o con
`ObservedReachabilityProbe` vera (`RootViewModelTest`). `FakeAndroidLimitsSignal` resta dov'è (inietta limiti voluti in `PresetMatrixTest`); il
tracker vero è provato sul grafo (RE-7). Il comportamento di `FakeWireGuardPlatform(permission = true)` come default resta (molti test lo
usano): i test del permesso lo mettono a `false` come sul telefono.

**Allineati il 19/10 (correzioni ON).** `FakeOnboardingCoordinator` usa le regole vere dei passi (`OnboardingRules`,
le stesse di `DefaultOnboardingCoordinator`: un input solo al suo passo, rinvio E-O1, riprendi, indietro, cambio di percorso,
essenziale per finire); `FakeJoinFlow` fa i controlli di `start` del vero (forma dell'invito, percorso, casa, scadenza,
rinnovo); `FakeAdminRecovery` ricorda account e area e vuole il profilo; `FakeRegistryService` cambia stato solo rileggendo e
applica la finestra di `ApprovalWindow`. I confronti: `FakeCoordinatorParityTest`, `FakeJoinFlowParityTest`,
`VerificaFunzionaleRegistroTest`. I flussi 1, 2, 3 e 10 qui sotto hanno ora un test sul grafo vero
(`VerificaFunzionaleFamigliaTest`), tranne il recupero dell'admin con un'interruzione (coordinatore vero e ViewModel).

**Allineati il 19/10 (correzioni GA e NA).** `FakeThumbnailLoader` perde le miniature da `UI_HIDDEN` in su (le celle tornano sul segnaposto) e le
ritrova con `resume()` (`ImagingContractTest.ga3_…`, e `PipelineThumbnailLoaderTest.ga3_…` per il vero); `FakeCollectionsWorld.hiddenLibrary`
dà una vista viva che segue le azioni (`HiddenLiveViewTest`, e `VerificaFunzionaleGalleriaTest.ga1_…` per il vero); `InMemoryNasOperations`
registra l'operazione e poi si mette in fila (`VerificaFunzionaleNasOpsTest.na1_…` gira sui **due** esecutori); `FakeNasAreas` salva le
esclusioni, conta le verifiche e ha il salto dell'ora (`VerificaFunzionaleNasTest`); `ScanHarness` tiene la franchigia di «Ho controllato,
continua» fuori dallo scanner, come il file di produzione (`ScanFreezeTest.na4_…`); `FakeMediaStore` e il MediaStore vero danno le stesse
righe senza data, una alla volta (`AndroidMediaStoreSourceTest.ga5_…`; l'ordine fra le righe senza data può differire). `RecordingJobs`
ora verifica che dopo un'operazione breve lasciata aperta la ripresa si chieda (`NasOrganizeIntegrationTest.na2_…`), e `NasOpsJobRun` non è più
provato solo con `{ null } → Done` (`NasOpsJobsTest.na12_…`, `VerificaFunzionaleNasAppTest.na12_…`).

**Allineati il 19/10 (correzioni BK).** `ReferenceBackupPlanner` legge il trigger come il piano vero (solo foto della fotocamera, mai video, al massimo 24,
ultimi 3 giorni; confronto: `DefaultBackupPlannerTest.bk1_…`) e `InMemoryBackupEngine` guida solo gli elementi creati da una corsa rapida
(`VerificaFunzionaleBackupTest.bk1_ancheIlMotoreDiRiferimento…`); restano diversi `alreadyPlanned` e `ONLY_COMMON_COPY`, che i due motori applicano da sé
(`store.openFor`, il guardiano). `FakeBackupSettings` applica D13 con la stessa regola delle impostazioni vere (`BackupSourceFence`, `refresh()` quando l'account
arriva): `DefaultBackupSettingsTest.bk14_ilFintoEIlVeroDannoLeStesseSorgentiInOgniMomento`. `BackupRig` ha il lavoro dell'utente come lo fa il servizio
(`userJob`: `BackupJobRun` dentro `runIfActive`), le azioni vere della pagina con il verdetto delle regole scelto dal test (`actions`), l'orologio monotono
e gli ingressi del NAS sostituibili (account che arriva tardi). `FakeBackupOverview` resta una lavagna (lo stato lo sceglie il test, per costruzione): le regole
che le mancavano (problema vecchio, pausa, avanzamento per lotto, riepilogo in cache) ora sono provate sul grafo vero (`VerificaFunzionaleBackupAppTest`,
`RunProgressTest`, `SummaryCacheTest`). `FakeBackupGate`, `FakePhoneFolders` e `FakeDeviceFolderProposals` restano lavagne: il cancello vero ha i suoi test
(`AppBackupGateTest`) e nessuna voce BK dipendeva da loro. I flussi 5, 6 e 13 della sezione 4 hanno ora un test sul grafo vero
(`VerificaFunzionaleBackupAppTest`, con il WorkManager di prova per le catene dei lavori); restano da provare sul telefono le catene vere di WorkManager e JobScheduler.

---

## 4. Flussi provati solo con fake: da mettere per primi nelle prove sul campo

In ordine di rischio. Tra parentesi i difetti che una prova farebbe emergere.

1. **Ingresso di un familiare dal QR fino all'approvazione**, compreso "approvo dopo 16 minuti" (ON-2). La UI e il rinnovo non
   sono mai passati dal grafo vero.
2. **Ingresso senza QR** (ON-1): mai provato con l'invito costruito dalla UI.
3. **Revoca, dismissione e reset della famiglia, visti dal dispositivo colpito** (ON-4, ON-5, ON-6). Nessun test d'integrazione.
4. **Controllo della VPN con WireGuard vero:** permesso, prova, sospensione, riattivazione (RE-1, RE-2, ON-7).
5. **Tasti della pagina Backup → lavoro avviato dall'utente → `BackupJobService`**, notifica, "Ferma" (BK-3, BK-4, BK-6).
6. **D34 e le catene di WorkManager del backup:** foto scattata fuori casa, primo backup a metà (BK-1, BK-2, BK-10).
7. **Operazioni sul NAS dalla UI** attraverso `AppNasOperations`: coda, rilettura delle cartelle (NA-1, NA-2, NA-3).
8. **Lavori a processo freddo:** notte, `NasOpsJobService` dopo la morte del processo, riepilogo giornaliero (RE-3, NA-7, NA-12).
9. **"Ho controllato, continua"** con il giro successivo in un processo nuovo (NA-4, NA-5).
10. **Recupero dell'admin e secondo admin** (onboarding e Impostazioni) con un'interruzione a metà (ON-3, ON-9). Il recupero
    sul telefono nuovo è già riuscito una volta il 19/10, ma senza interruzioni.
11. **Riapertura dei passi da "Da completare" e dalle Impostazioni** con il coordinatore vero (ON-7, ON-8).
12. **Eliminazione e ripristino con i dialoghi di sistema**, Eliminati di recente (GA-7, F15).
13. **Permessi dei media e "Consenti"**, dopo una rotazione e con il permesso negato per sempre (GA-4, BK-3).
14. **Cambio password, cambio indirizzo, nuova impronta di casa** (RE-5, RE-9).
15. **Ritorno del NAS dopo una sonda fallita** (RE-6).
16. **Ritorno dal secondo piano dopo qualche minuto:** miniature (GA-3).
17. **La release (R8)** su onboarding, Impostazioni e Diagnostica: `benchmarkRelease` salta l'onboarding, quindi queste rotte
    non sono mai state aperte in una build ridotta su un telefono.

---

## 5. Flussi verificati da capo a fondo

Una riga ciascuno: il percorso è completo nel codice e i dati arrivano alla schermata.

**Onboarding e famiglia**
- **Crea la famiglia (14 passi):** coordinatore vero, kit verificato prima della fondazione, stato fidato salvato,
  `Ready(admin)`. C'è anche un test d'integrazione sul NAS simulato.
- **"Continua più tardi" e ripresa dal riepilogo** (E-O1), nella creazione e nel recupero: passi del NAS rimandati, ripresi in
  ordine, chiave e fondazione al passo 12 (integrazione `conIlNasSpentoSiContinuaPiuTardi…`).
- **"Qui c'è già una famiglia" → "Recupera l'admin" o "Entra come familiare"** (dopo `4521eff`).
- **Recupero dell'admin sul telefono nuovo, senza interruzioni:** aree, poi kit nell'area privata, codice e impronta (o QR di
  stato), poi registro letto con l'ancora, autoapprovazione e `Approved(admin)`, poi fine.
- **Ingresso con QR fino a "in attesa":** casa e GUID, password (un solo tentativo), registro firmato, nome, richiesta, codice
  e QR della chiave, poi passi locali e attesa (integrazione sul simulatore SMB).
- **Approvazione da parte dell'admin**, entro la finestra: Dispositivi (si rilegge all'apertura), richiesta, account
  confermato, codice o chiave scansionata, credenziale, commit. Il telefono in attesa, che rilegge ogni 10 s, passa a `Ready`.
- **Notifica "Nuovo dispositivo":** finestra d'invito di 15 minuti (WorkManager), regole, `notifyNewDevices` una volta per
  richiesta; il tocco apre l'app.
- **Segnala perso** (admin o proprietario), poi azzeramento al ricollegamento, chiavi prima dei dati, schermata "azzerato",
  "Configura di nuovo" (integrazione; vedi ON-11 per il processo).
- **Secondo admin dalle Impostazioni**, con la password dell'admin per una sessione usa e getta (`ForeignSharesTest`).
- **QR "stato della famiglia":** dallo stato fidato salvato, poi letto da ingresso e recupero.
- **Controllo quotidiano del registro**, all'apertura e all'arrivo a casa.

**Rete, sicurezza, Diagnostica**
- **Blocco app:** Sicurezza, `AppLockSettingsStore`, `AppLockController`, `AppLockGate` e schermata bloccata.
- **Esportazione della Diagnostica:** aggregatore, anteprima congelata, `CreateDocument`, esito mostrato.
- **Test di connessione** dall'account del dispositivo, senza autenticare con l'interruttore aperto.
- **Indicatore globale** (rete, sessione, sonda, interruttore) e destinazioni del tocco, tutte registrate.
- **Percorso di rete:** osservatore, regole di casa e VPN, cancello, sessione con il socket legato alla rete.
- **Notifica "Serve la password":** interruttore aperto con l'app in background, `PendingIntent` immutabile, tolta alla chiusura.
- **Porte opzionali di Impostazioni e Diagnostica tutte legate in produzione**; `DeveloperTools` di release vuoti.

**Galleria del telefono**
- **Prima lettura e incrementale:** MediaStore, catalogo, repository, Libreria; in tempo reale in primo piano e con il lavoro
  a trigger di contenuto in background.
- **Permesso concesso dopo l'avvio** e **accesso "solo foto selezionate"** con il banner.
- **Preferiti e Nascondi** dalla Libreria, dalle Raccolte e dal viewer della Libreria.
- **Eliminazione dal telefono** con il dialogo di sistema, **Eliminati di recente** con il ripristino, **Nascosti** dietro lo
  sblocco ("Mostra" dalla selezione).
- **Raccolte** (Preferiti, Video, Screenshot, WhatsApp) e pannello del tablet; **viewer** (pager vivo, E-V6, E-V8, video,
  foto in movimento, info, copia, condividi); **luoghi** offline.

**NAS nella Libreria e organizzazione**
- **Media del NAS in Libreria:** `startWhenReady`, `NasGraph.start`, scansione, catalogo, timeline. Il NAS parte anche quando
  la famiglia diventa pronta a processo avviato.
- **Miniature e viewer di un media solo NAS** (replica, EXIF provvisoria, originale); **qualità delle anteprime e copertine**.
- **Congelamento nello stesso processo**, "Ferma le scritture" e "Riattiva".
- **Cartelle NAS, Eliminati di recente del NAS** (ripristino, "elimina subito"), **Spazio NAS**, "Ferma" da Operazioni in corso
  e dalla notifica, ripresa dal diario all'avvio.
- **Preferiti e nascosti fra i propri dispositivi** (sulle share di prova; sulle share vere D31 li tiene in locale per scelta).
- **Azzeramento che ferma prima NAS e backup**, poi cancella i file. Nessun ViewModel resta su un null-object: le porte NAS e
  backup di Hilt sono facciate su oggetti costruiti nel costruttore di `GalleryGraph`.

**Backup 5A**
- **Voce Impostazioni e capsula del piede → pagina Backup**; **sorgenti e cartelle aggiuntive**; **cartella del dispositivo**.
- **"Inizia ora" e "Fai ora"** quando il turno è libero: lavoro avviato dall'utente, giro, copia verificata, "al sicuro".
- **"Stanotte"**, **"Ferma"** (pagina e notifica), **"Riprova"**, **azioni di navigazione dei problemi** (Aree: tolta, BK-9).
- **Riga del viewer**, **copie simili D36** ("Separa", "Mostra come principale"), **verifiche e guardiano**, **lavori periodici**.
- **D12 in release:** cancello `TEST_ONLY`; pagina, piede e Impostazioni dicono "Backup in prova".

---

## 6. Note

**Scartati dopo la verifica:**
- i null-object in `BackupModule` e `GalleryModule`: le porte leggono oggetti nati nel costruttore e seguono flussi vivi;
- la gara fra il salvataggio dell'account e la sessione nell'ingresso: fra le due ci sono abbastanza sospensioni;
- il formato del GUID del NAS: è canonico, da `SmbjTransport`;
- `HiddenAccessGate.lock()` mai chiamato: il blocco app toglie comunque il contenuto.

**Scelte e non difetti:**
- D31 sulle share vere (indice "in prova", dati personali solo locali);
- D12 e R2 per il backup in release;
- il Wake-on-LAN legato ma senza interfaccia (D5);
- le notifiche che aprono solo l'app.

**Fuori perimetro:**
- il selettore per le altre app (5B, P10) svuota la finestra delle miniature condivisa con la Libreria, con lo stesso
  meccanismo di GA-3: da guardare quando si chiude la 5B.

**Non letti in dettaglio**, quindi non dati per verificati:
- il livello SMB (`SmbjTransport`, `OperationMonitor`);
- gli algoritmi di layout, lo scrubber e il pinch;
- la decodifica dei formati.
