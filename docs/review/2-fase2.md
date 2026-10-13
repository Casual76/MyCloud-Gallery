# Revisione di sicurezza della Fase 2

| | |
|---|---|
| **Oggetto** | commit da `fc3c4f7` a `64b50eb` (`git log --oneline fc3c4f7..64b50eb`): `core-network`, `core-nas`, `core-family`, `core-qr`, `feature-onboarding`, `feature-settings`, `feature-diagnostics`, `:app`, aggiunte a `core-trust`, `core-common` e `core-security` |
| **Riferimenti** | `docs/fase2-progetto.md` (§0 D1–D3, §2.2, §2.3, §2.6, §3), `docs/nas-sessione.md`, `docs/rete-casa-vpn.md`, `docs/famiglia-dispositivi.md`, `docs/sicurezza.md` |
| **Tipo** | Revisione avversariale in sola lettura. Nessuna correzione al codice. |
| **Data** | 13/10/2026 |
| **Metodo** | Ho letto il codice delle superfici richieste, nell'ordine di priorità dato. Due aree autonome sono state analizzate in parallelo da due sotto-revisioni: `:app` (componenti, PendingIntent, notifiche, WorkManager, credenziali, log) ed esportazione della Diagnostica; i loro punti sono stati ricontrollati a campione nel codice. **1 test di prova temporaneo** (`core-family`, `ReviewPhase2ProbeTest`) è stato eseguito e poi cancellato. Alla fine `git status` è pulito. |

## Riepilogo

| Gravità | Numero | Voci |
|---|---|---|
| Critica | 0 | – |
| Alta | 0 | – |
| Media | 5 | F1 "casa" e NAS riconosciuti da dati non segreti, quindi NTLMv2 verso un impostore mirato · F2 VPN di casa spenta su una rete che imita casa · F3 recupero dell'admin ancorato al nome del file sul NAS (M7 riaperto) · F4 Diagnostica: chiavi e codici esportati come testo libero · F5 activity esportata pilotabile con gli extra di Navigation (codice di approvazione falso) |
| Bassa | 10 | F6…F15 |
| Informativa | 8 | I1…I8 |

---

## Risposte alle domande del perimetro

1. **NTLM verso un host non fidato.**
   - **Per caso, no.** Sono gestiti tutti i casi richiesti:
     - rete estranea con la stessa sottorete (riconosciuta solo dall'ULA);
     - VPN di terzi (negata);
     - DNS (solo IP letterale);
     - rete che cambia tra controllo e connect (socket legato alla `Network`, percorso ricontrollato prima del login, epoca);
     - GUID non verificato (senza GUID memorizzato, nessun login);
     - cifratura che ricade sulla firma (`shouldEncryptData()` controllato dopo il login);
     - guest e anonimo.
   - **Di proposito, sì**, per chi conosce l'ULA di casa e il GUID del NAS (F1). Non sono segreti: li conosce chiunque sia stato sulla LAN di casa o abbia fotografato un QR d'invito.
   - **L'interruttore** è persistente e "a richiesta". Resta un margine dichiarato di 2 tentativi non confermati, più il caso del rifiuto lento (F9).
2. **Autodistruzione.**
   - **Non parte da dati remoti non firmati o non accettati.** Gli ingressi sono solo due:
     - un registro **accettato** con `lost` (`TrustEngine.afterChange`, `DefaultTrustGuard`);
     - una `LostReport` **valida**, firmata da un dispositivo **approvato** dello stesso account e trovata nell'area privata.
   - **L'ordine chiave→dati** regge anche con il processo ucciso: il diario si scrive prima, e dopo ogni fase. **Eccezione:** se il diario non si può scrivere, l'errore è ignorato e un'interruzione non si riprende più. Confermato con un test (F7).
3. **Parser dei QR, ingresso senza QR, approvazione.**
   - **Parser** stretti e senza eccezioni verso fuori:
     - JSON canonico con tetti ridotti;
     - base32 stretto;
     - campi fissi;
     - punto P-256 verificato.
   - **Approvazione:** nessuna escalation trovata nella logica. Servono:
     - il codice da 80 bit **digitato** dall'admin, oppure la chiave scansionata;
     - la credenziale del telefono;
     - la conferma dell'account;
     - la finestra contata dalla prima vista locale;
     - la segnalazione dei sosia.
   - **Restano:** il TOFU dell'onboarding e dell'ingresso manuale (F6), il recupero senza QR di stato (F3) e il codice falso iniettabile nella schermata d'attesa (F5).
4. **`:app`.** Esportati solo `MainActivity` (launcher) e `SystemJobService`; un solo PendingIntent, esplicito e `FLAG_IMMUTABLE`; nessun worker avviabile da fuori. Restano F5, F10, F11, F12, F13, F14.
5. **Esportazione della Diagnostica.** Oggi non esce nessun dato personale. Però chiavi e codici sono testo libero validato solo nella forma (F4), e l'anteprima può differire dal file (F15).
6. **Dati letti dal NAS che cancellano o spostano.** Nessun percorso.
   - Sul NAS si cancellano solo i **propri** temporanei, `tmp/<id locale>-<32 esadecimali>` (`SmbNasHousekeeping`).
   - La ripubblicazione di una versione crea senza cancellare.
   - Le cancellazioni **locali** indotte da dati remoti sono due: l'autodistruzione (punto 2) e, in un ingresso fallito, il ritiro delle credenziali appena salvate (I7).

---

## Problemi di gravità media

### F1. "Casa", "VPN di casa" e "il NAS" si riconoscono da dati che non sono segreti: un attaccante mirato riceve un'autenticazione NTLMv2

- **Gravità:** media
- **Dove:**
  - `core-network/.../home/HomeMatcher.kt:21-29`: casa = un indirizzo del Wi-Fi nell'ULA memorizzato;
  - `.../policy/VpnClassifier.kt:53-96`: VPN di casa = l'ULA nelle rotte, nei DNS o negli indirizzi, oppure 2 indizi deboli (sottorete, DNS = gateway, `fritz.box`);
  - `core-nas/.../session/SmbNasSessionManager.kt:401-412`: unica conferma, il GUID della NEGOTIATE;
  - `core-family/.../invite/QrCodecs.kt:98-101`: il QR d'invito contiene `ip`, `gd` (GUID), `ula`, `usr`;
  - `feature-settings/.../network/NetworkViewModel.kt:191-249`: "riconosci di nuovo casa", su qualunque Wi-Fi con il solo GUID;
  - specifica: `docs/rete-casa-vpn.md:74-75` ("L'ULA riduce le occasioni, il GUID chiude il caso"), `docs/fase2-progetto.md` §0 punto 2.
- **Scenario:** un ospite o un dispositivo IoT che è stato sulla LAN di casa (attaccante già nel modello di minaccia) legge l'ULA dagli annunci del router e il GUID con una NEGOTIATE non autenticata al NAS. In alternativa, li legge entrambi da una foto del QR d'invito (E-S8: "inutile senza password", ma contiene proprio questi due dati). Poi:
  - **Wi-Fi gemello:** stesso SSID e stessa password del Wi-Fi di casa, che l'ospite conosce. Il telefono vi si collega da solo; anche un passaggio in roaming tra AP dello stesso SSID tiene la stessa `Network` e gli stessi indirizzi. In alternativa l'AP annuncia l'ULA di casa con un RA. Il NAS falso risponde all'IP del NAS con il GUID copiato. Risultato: `HOME_LAN`, NEGOTIATE accettata, **login NTLMv2 verso l'attaccante**.
  - **VPN di terzi:** una configurazione di tunnel con l'ULA di casa fra le rotte, o con sottorete + DNS + dominio di fabbrica del FRITZ!Box, diventa `HOME_TUNNEL` e porta allo stesso risultato.
  - **Persistenza:** se l'utente su quella rete accetta "riconosci di nuovo casa" (proposta dall'app quando la rete somiglia a casa, E-R16), l'ULA dell'attaccante **sostituisce** quello vero (`confirmCapture` salva senza unire).

  Con la risposta NTLMv2 si attacca la password offline (hashcat, modalità 5600). Una password scelta da un familiare spesso cade.
- **Perché funziona:** l'ULA è annunciato in chiaro a ogni client e un RA non è autenticato (la specifica lo ammette). Il GUID si legge senza credenziali da chiunque raggiunga la porta 445. Nessuno dei due è un segreto, quindi insieme fanno **riconoscimento, non autenticazione**. Sulla LAN di casa vera la stessa cattura era già possibile (ARP spoofing, limite dichiarato in `sicurezza.md` §6); la Fase 2 la estende **fuori casa**, dove il cancello dovrebbe proteggere, e la specifica afferma il contrario.
- **Correzione proposta:**
  - **specifica:** dire che ULA e GUID sono pubblici e che il cancello evita gli errori ma non ferma un attaccante mirato; collegare il limite a C5 (password lunghe, generate) e renderlo visibile all'utente;
  - **QR d'invito:** non mettere `gd` e `ula` nel QR, ma ricavarli dalla configurazione firmata dopo il login sulla rete confermata (oppure accettare il compromesso, ma scriverlo);
  - **"riconosci di nuovo casa":** aggiungere gli ULA invece di sostituirli, e chiedere una prova più forte di un GUID (per esempio un login riuscito con firma verificata, o l'impronta del registro firmato letta subito dopo);
  - se si vuole di più: BSSID (con permesso di posizione), oppure un login SMB **solo dopo** aver verificato la firma del server sulla risposta finale di SESSION_SETUP (non impedisce la cattura, ma impedisce di usare la sessione falsa; vedi domanda aperta 1).
- **Confermato:** no (analisi del protocollo e del codice).

### F2. L'automazione spegne WireGuard su qualunque rete che "sembra casa"

- **Gravità:** media
- **Dove:** `core-network/.../vpn/DefaultVpnAutomation.kt:239-247` (`atHome && vpn == HOME_TUNNEL → Need.DOWN`), con `HomeMatcher.match` come sopra.
- **Scenario:** lo stesso Wi-Fi gemello di F1, con internet (rete validata). Il telefono è fuori casa con la VPN di casa accesa. L'app vede `HomeMatch.Match` e manda `SET_TUNNEL_DOWN`: **tutto il traffico del telefono**, di tutte le app, esce sulla rete dell'attaccante senza tunnel. Subito dopo parte anche il login di F1.
- **Perché funziona:** la decisione "sono a casa" si basa solo sull'ULA, cioè su un dato che l'attaccante controlla con un RA o con il roaming. Il GUID non entra nella decisione di spegnere la VPN.
- **Correzione proposta:**
  - prima di spegnere la VPN, ottenere una conferma che l'attaccante non possa fabbricare: almeno ULA + gateway + dominio + BSSID memorizzato (permesso di posizione facoltativo), oppure una prova attraverso il tunnel (la sessione SMB via VPN risponde con lo stesso GUID **e** con una sessione firmata valida) e poi lo spegnimento;
  - in alternativa, rendere lo spegnimento automatico una scelta esplicita, con un avviso che spiega il rischio.
- **Confermato:** no (analisi; è il comportamento voluto dal §2.4, applicato a un dato falsificabile).

### F3. Recupero dell'admin senza QR di stato: l'impronta attesa viene dal nome del file sul NAS (M7 riaperto)

- **Gravità:** media
- **Dove:** `core-family/.../lifecycle/KitAdminRecovery.kt:134-135`: `expectedFingerprint = familyState?.familyKeyFingerprint ?: pathFingerprint`, dove `pathFingerprint` è il nome del file in `recovery/<famiglia>/<impronta>` dell'area privata. È dichiarato come limite in `docs/famiglia-dispositivi.md` §8. Contraddice `docs/formato-nas.md` §4.8 ("l'impronta attesa dal foglio, o dal QR di stato") e la chiusura di M7 nella revisione 1.4.
- **Scenario:**
  1. Qualcuno ha visto il foglio del codice e scrive nell'area privata dell'admin (malware sul PC dell'admin).
  2. Mette un kit con la **sua** chiave in `recovery/<famiglia>/<sua impronta>` e pubblica nell'area comune un registro firmato da quella chiave. Può cancellare le versioni vere a nomi bassi, e la ripubblicazione rimette solo l'ultima.
  3. Il telefono nuovo dell'admin, senza QR di stato (la specifica ammette "versione minima 0 e avviso"), si fida della famiglia dell'attaccante.
- **Correzione proposta:** quella già proposta dal documento (P0-bis). `AdminRecovery.recover` riceve id e impronta **digitati dal foglio**, che li riporta; senza quei valori, nessun recupero.
- **Confermato:** no (evidente dal codice e dichiarato; il fix è noto).

### F4. Diagnostica: chiavi dell'esportazione e `DiagCode` sono stringhe libere, controllate solo nella forma

- **Gravità:** media (latente: oggi nessun produttore la sfrutta)
- **Dove:**
  - `core-common/.../diagnostics/Diagnostics.kt:14-19`: `DiagCode` ha un costruttore pubblico e la sola regex `[A-Z][A-Z0-9_]{2,63}`;
  - `:113`, `:120`: `EXPORT_KEY = [a-z][A-Za-z0-9_.]{0,47}`;
  - `feature-diagnostics/.../export/DiagnosticsExporter.kt:74`: il codice è scritto così com'è; un codice fuori catalogo passa lo stesso.
- **Scenario:**
  - un contributore futuro scrive `"share.${nome.lowercase()}" to Num(1)` oppure `DiagCode(utente.uppercase())`;
  - `mario.rossi` / `MARIO_ROSSI` superano tutti i controlli e tutti i test;
  - anche un `Num` può portare un IPv4 (`3232281146`).

  `ExportPrivacyTest` prova i dati personali solo nei canali `DiagText`/`Hashed`.
- **Correzione proposta:**
  - esportare solo i codici del `DiagCatalog` (gli altri diventano `UNKNOWN`);
  - chiavi come tipo chiuso (`ExportKey` con costanti, o derivate solo da enum);
  - un test che confronta le chiavi prodotte dai contributori veri con una lista ammessa.
- **Confermato:** no (verificato nel codice: costruttore pubblico e regex).

### F5. `MainActivity` esportata: un'app qualunque apre schermate interne con argomenti scelti, compreso un codice di approvazione falso

- **Gravità:** media
- **Dove:**
  - `app/src/main/AndroidManifest.xml:48-56`: esportata, `singleTask`, senza `taskAffinity`;
  - `MainActivity`: l'intent di avvio non viene ripulito e manca `onNewIntent`;
  - `navigation/AppNavHost.kt:58, 103-104`: `AwaitingApprovalRoute(val code: String)` mostra il codice passato come argomento della rotta. Tutte le destinazioni sono registrate in ogni stato.
- **Scenario:**
  1. Navigation legge dall'intent dell'activity gli extra `android-support-nav:controller:deepLinkIds` / `deepLinkExtras` anche senza `navDeepLink` dichiarati. Gli id sono hash delle rotte, ricavabili dall'APK, che non è offuscato.
  2. Un'app senza permessi sul telefono che sta entrando apre la schermata d'attesa **dell'app vera** con il codice di una richiesta dell'attaccante, già pubblicata nell'area comune da un appoggio SMB che può anche cancellare la richiesta vera.
  3. Il familiare legge quel codice all'admin, che lo digita, e l'approvazione va **al dispositivo dell'attaccante**.
  4. Con lo stesso mezzo si aprono Impostazioni, Recupero admin (`replace=true`), Reset o Approvazione da stati in cui la macchina a stati non li prevede. I controlli veri restano nei moduli core.
- **Perché funziona:** il codice da confrontare è un dato della rotta, non viene ricalcolato dalla chiave del dispositivo nella schermata. L'activity esportata accetta extra di navigazione da chiunque.
- **Correzione proposta:**
  - in `onCreate`, prima di `setContent`, sostituire l'intent con uno pulito (`ACTION_MAIN`), e fare lo stesso in `onNewIntent`;
  - la schermata d'attesa calcola il codice dalla chiave del Keystore (`ApprovalCodes.of`) e non lo riceve come argomento;
  - registrare per ogni stato solo le destinazioni raggiungibili.
- **Confermato:** no. Da verificare con `adb shell am start -n …/.MainActivity --eia android-support-nav:controller:deepLinkIds <id>`; il comportamento di Navigation è stato letto nel bytecode della versione in uso dalla sotto-revisione.

---

## Problemi di gravità bassa

### F6. TOFU all'onboarding e all'ingresso manuale: indirizzo da NSD o DNS, GUID memorizzato al primo contatto
- **Dove:** `core-family/.../onboarding/DefaultOnboardingCoordinator.kt:277-282` (prima NEGOTIATE con `expectedGuid = null`, poi il GUID si memorizza); `core-network/.../discovery/NsdNasDiscovery.kt:70, 123` (mDNS/NSD e DNS del Wi-Fi); `DefaultJoinFlow.kt:152-168` (ingresso manuale: GUID imparato, confrontato con quello firmato solo **dopo** il login, `:284-285`).
- **Scenario:** un dispositivo IoT sulla LAN annuncia via mDNS un "MyCloudEX2Ultra" con il proprio IP, oppure risponde al DNS. L'admin lo sceglie, e GUID e password vanno all'impostore. Il login poi fallisce o si interrompe, ma la risposta NTLMv2 è già stata presa.
- **Correzione proposta:** mostrare IP e nome insieme e avvisare se due servizi hanno lo stesso nome; consigliare di digitare l'IP letto dal FRITZ!Box. Nell'ingresso manuale, dopo il login, il GUID firmato diverso deve dire chiaramente "cambia la password: l'hai data a un altro dispositivo".
- **Confermato:** no.

### F7. Autodistruzione: se il diario non si può scrivere, un'interruzione non si riprende più (chiave distrutta, credenziali rimaste)
- **Dove:** `core-family/.../lifecycle/DeviceSelfWipe.kt:115-125` (`persist` ignora l'errore di scrittura: "si va avanti lo stesso").
- **Scenario:** disco pieno o file non scrivibile. La corsa distrugge la chiave del dispositivo e poi si ferma (processo ucciso o fase fallita). Al riavvio il diario è vuoto: `resumeIfInterrupted` non fa nulla e `TrustEngine.myDeviceId()` è null (senza chiave non si rileva più "perso"). Le **credenziali del NAS e i dati locali restano**.
- **Correzione proposta:** se il segno (0) non si scrive, non distruggere nulla e riprovare. Oppure tenere un secondo indicatore (per esempio un flag nel DataStore dello stato), oppure rendere "chiave del dispositivo assente ma registro e credenziali presenti" una condizione che all'avvio completa l'azzeramento.
- **Confermato:** **sì**. Con un diario che fallisce in scrittura e le credenziali che falliscono alla prima corsa, la prima corsa si ferma a `FAMILY_KEY_DESTROYED` con la chiave distrutta; dopo il "riavvio" `progress == null` e le credenziali non vengono mai cancellate.

### F8. L'area privata di un familiare può essere la stessa share dell'area comune: la "prova di possesso" delle segnalazioni "perso" vale zero
- **Dove:** l'onboarding dell'admin rifiuta `common == private` (`DefaultOnboardingCoordinator.kt:398-400`); l'invito no (`InviteSupport.kt:86-87`, `NasRegistryService.createInvite`). `NasLostReporting.kt:45` cerca le segnalazioni nella `privateShare` del profilo, e `:95` usa `distinctBy`: le due share possono coincidere.
- **Scenario:** l'admin invita un familiare indicando "Public" come area privata (scrivibile da tutti). La regola "l'azzeramento automatico vale solo nell'area privata, dove scrive solo il titolare" (B6, decisione 3a) non distingue più chi ha la password. Resta comunque necessaria la firma di un dispositivo approvato dello stesso account.
- **Correzione proposta:** rifiutare nell'invito (e nel profilo) un'area privata uguale all'area comune o a una share pubblica; senza un'area privata vera, niente azzeramento automatico da segnalazione.
- **Confermato:** no.

### F9. Interruttore: fino a 2 tentativi "non confermati" e un rifiuto lento che non conta mai
- **Dove:** `core-nas/.../breaker/PersistentAuthCircuitBreaker.kt:137` (`MAX_UNCONFIRMED = 2`, dichiarato in `docs/nas-sessione.md:122`), `:106` e `:112` (un `Failed`, compreso il timeout di 20 s, toglie il segno); `SmbNasSessionManager.kt:501-518`.
- **Scenario:**
  - due morti del processo durante il login, con la password sbagliata, fanno due tentativi falliti sul NAS. La specifica (§3.3) promette "esattamente 1, anche dopo il riavvio";
  - un attaccante in LAN che ritarda la risposta di SESSION_SETUP oltre 20 s fa vedere ogni rifiuto come `Timeout`, e i lavori in background riprovano la password vecchia a ogni giro, con rischio di blocco dell'account sul NAS (E-R8).
- **Correzione proposta:** allineare la specifica ("al massimo 2 dopo un crash"); dopo un timeout in fase di autenticazione, tenere il segno "non confermato" invece di toglierlo.
- **Confermato:** no.

### F10. "Nuovo dispositivo: <nome>" sulla schermata di blocco, con un nome scritto da chiunque abbia accesso all'area comune
- **Dove:** `app/.../notify/AppNotifications.kt:63-85` (`VISIBILITY_PRIVATE` + `setPublicVersion`).
- **Scenario:** con l'impostazione di sistema "mostra tutti i contenuti" Android usa la versione privata, quindi il commento a `:29` promette troppo. Il nome viene da una richiesta che chiunque abbia accesso SMB in scrittura può creare: è ripulito da `TextRules`, ma resta un testo utile al phishing ("…tocca e conferma").
- **Correzione proposta:** titolo sempre generico, nome solo dentro l'app; oppure `VISIBILITY_SECRET` sul canale "Approvazioni".
- **Confermato:** no (dalla sotto-revisione `:app`, riletto).

### F11. `OneShotForeignShares` richiude l'interruttore prima dei controlli, senza limitare i tentativi
- **Dove:** `app/.../graph/ForeignShares.kt:69-70`: se l'interruttore dell'account dell'admin è aperto, lo richiude prima del percorso e del GUID. Non esiste l'equivalente di `RejectedCredentials`.
- **Scenario:** nel recupero e nel secondo admin ogni tocco su "Riprova" fa un tentativo con la password dell'admin, anche ripetendo la stessa, quindi tentativi illimitati sull'account dell'admin. Se un chiamante futuro passasse l'utente del dispositivo, ne richiuderebbe l'interruttore senza una password nuova salvata: oggi l'unico chiamante usa `base.pinnedGuid` e un altro utente.
- **Correzione proposta:** ricavare indirizzo e GUID dentro la classe; rifiutare l'utente del dispositivo; richiudere l'interruttore solo dopo percorso e GUID; ricordare la coppia rifiutata.
- **Confermato:** no.

### F12. Password tenute come `String` nello stato della UI
- **Dove:** `AdminRecoveryViewModel.kt:52`, `PasswordViewModel.kt:44`, `JoinPages.kt:347`; anche `OnboardingSupport.kt:250` (`String(password)` per l'impronta).
- **Rischio:** copie non azzerabili nell'heap, leggibili da un dump di memoria. Il `toString` è oscurato e la password non finisce in `SavedStateHandle`.
- **Correzione proposta:** `CharArray` fino all'invio, oppure dichiararlo nel limite L4.
- **Confermato:** no.

### F13. `singleTask` esportata senza `android:taskAffinity=""` (StrandHogg, soprattutto su Android 10)
- **Dove:** `AndroidManifest.xml:48-51`. **Correzione:** `android:taskAffinity=""`. **Confermato:** no.

### F14. La build debug con i dati veri della famiglia
- **Dove:** `app/build.gradle:68` (`debuggable`), `debug/.../AppLogging.kt:29-30` (stack trace complete su logcat).
- **Rischio:** con ADB, `run-as` e JDWP si usa la chiave del Keystore e si decifrano le credenziali; le eccezioni possono contenere IP e percorsi. La verifica sul campo (§5.4) usa proprio la build debug.
- **Correzione proposta:** scrivere nella guida della verifica sul campo "solo account di prova"; build `release` firmata per l'uso reale.
- **Confermato:** no.

### F15. Diagnostica: il registro eventi riletto accetta codici e chiavi non a catalogo; l'anteprima può differire dal file
- **Dove:** `feature-diagnostics/.../DiagnosticsLog.kt:196-212`: `DiagCode(parts[1])` riletto dal file, poi esportato da `RecentEventsContributor.kt:48`. `DiagnosticsViewModel.kt:180-183` scrive lo stato **corrente** al ritorno dal selettore, mentre `publish()` (`:195-209`) rigenera l'esportazione se intanto finisce il test di connessione.
- **Rischio:** il primo caso richiede la scrittura nella sandbox; il secondo viola la promessa "anteprima, poi salvataggio" (§2.6), anche se oggi senza dati personali.
- **Correzione proposta:** filtrare gli eventi riletti con il catalogo; congelare il documento al tocco su "Esporta".
- **Confermato:** no (dalla sotto-revisione della Diagnostica).

---

## Informative

- **I1. Password in memoria per i rifiuti.** `RejectedCredentials` (`OnboardingSupport.kt:232-255`) tiene SHA-256 senza sale di utente+password: con un dump di memoria si verifica in fretta un dizionario. Solo in RAM e per poco tempo.
- **I2. Finestra d'approvazione e tetto del file.** `FileApprovalSightings` (`ApprovalSightings.kt:86, 91`) tiene 256 voci e `retain` toglie le richieste non elencate: inondando `requests/` si può far "rivedere per la prima volta" una richiesta vecchia. Con il codice a 80 bit la finestra è una difesa in più, non quella che regge.
- **I3. Recupero dell'admin.** Il "vecchio admin" sono **tutti** gli altri dispositivi admin approvati, che diventano persi o revocati (dichiarato in `famiglia-dispositivi.md` §8): un secondo admin legittimo si azzera. La schermata li deve elencare prima.
- **I4. Revoca, "perso" e unione dei rami.** `changeStatus` (revoca, perso) non chiede la credenziale del telefono, mentre l'approvazione sì; l'unione dei rami (`resolveForks`) non è ancora collegata alla UI. Quando lo sarà, deve chiedere la credenziale come l'approvazione, perché unire un ramo **approva** i suoi dispositivi.
- **I5. Diagnostica.** L'HMAC dei valori di rete ha una chiave per processo: due esportazioni dello stesso processo si possono collegare. Alcuni numeri al millisecondo (età, uptime, `bootCount`, scarto d'orologio, byte esatti) sono quasi-identificativi: conviene arrotondarli.
- **I6. Ingresso fallito.** Se la lettura del registro fallisce (per esempio spazzatura nella cartella), `DefaultJoinFlow.abandon` ritira credenziali e account appena salvati (`:415-426`): è solo negazione del servizio (bisogna ridigitare la password).
- **I7. `:app`.** `KEY_REASON` viene scritto e mai letto; la sezione cloud-backup delle regole di estrazione non elenca i domini `device_*` (con `allowBackup=false` non conta); in release resta `Log.e` delle librerie.
- **I8. Notifica delle richieste.** Le richieste nuove si notificano una volta sola, e gli id notificati sono filtrati con una regex (`AppNotifications.kt:164`): uno spam di richieste costa all'attaccante, non all'admin.

---

## Cose fatte bene

- **Cancello di rete.**
  - Tipizzato: un `NasRoute.Allowed` lo crea **solo** `DefaultNasRouteGate` (verificato: nessun altro costruttore in produzione).
  - Socket sempre legato alla `Network` e connesso a un IP letterale costruito dai byte (`NasAddress`, `IpLiteral` senza DNS, senza zeri iniziali ambigui).
  - Mai `bindProcessToNetwork`.
  - Percorso ricontrollato (rete ed epoca) subito prima del login.
  - Sessione chiusa al cambio di rete.
- **Ordine dei controlli** come da progetto: GUID prima della password; ora del NAS ancorata solo dopo il GUID; solo 3.1.1 con cifrario negoziato; cifratura **effettiva** verificata dopo il login; guest e anonimo rifiutati; un `SMBClient` per connessione (niente pool che riusi una rete vecchia); copia della password dentro smbj azzerata.
- **Interruttore** persistente in `noBackupFilesDir`, con un mutex per chiave condiviso da sessione e test, riletto dal file a ogni tentativo. Si riapre solo per un'azione dell'utente con una password nuova; la stessa coppia rifiutata non torna al NAS.
- **Autodistruzione:** ingressi solo da dati firmati e accettati; ordine chiave→dati; fasi idempotenti; ripresa all'avvio da `ProcessWatcher` e da `TrustSyncRun`; diario escluso dall'eraser.
- **Parser dei QR** stretti; il QR della chiave verifica la coincidenza fra id e chiave e che il punto stia sulla curva; un QR di WireGuard si riconosce e si rifiuta.
- **Approvazione:** codice da 80 bit **digitato**, oppure chiave scansionata e confrontata con la richiesta; credenziale del telefono; conferma dell'account; finestra contata dalla prima vista locale (non da `requestedAt`); sosia segnalati; richiesta riletta per id al momento dell'approvazione.
- **Ingresso con QR:** GUID del QR prima della password; scadenza giudicata con l'ora del NAS; configurazione **firmata** che vince sul QR (GUID e indirizzo firmati ricontrollati); impronta di casa provvisoria tolta se l'ingresso fallisce.
- **Nessuna cancellazione da dati remoti** sul NAS: pulizia ristretta ai propri temporanei, con id locale e formato stretto.
- **`:app`:** due soli componenti esportati; un solo PendingIntent esplicito e immutabile; nessun FileProvider; backup e trasferimento esclusi; release non debuggable con controllo del manifest; nessun log dell'app in release; credenziali con AES-GCM legato alla voce e chiave hardware obbligatoria.
- **Diagnostica:** `ExportValue` sigillato senza un caso stringa; grammatica che rifiuta l'esportazione invece di far uscire testo; HMAC con chiave casuale; nessun FileProvider; il testo a schermo non entra mai nel file.

## Domande aperte

1. **smbj e la sessione finale.** smbj 0.15 verifica la firma della risposta finale di SESSION_SETUP (obbligatoria in 3.1.1) e rifiuta le risposte non cifrate quando la sessione richiede la cifratura? Se no, un impostore di F1, oltre alla risposta NTLMv2, potrebbe far "completare" la sessione e restituire contenuti a sua scelta: per i file firmati nessun danno, per i media (Fase 4) sì. Serve un test con un server finto che risponde `STATUS_SUCCESS` senza firma.
2. **Navigation.** Il comportamento descritto in F5 (`deepLinkIds` letti dall'intent di avvio senza deep link dichiarati) va provato con `adb` sulla versione in uso.
3. **Quanto è pubblico il GUID.** Il WD lo espone anche fuori da SMB (UPnP, interfaccia web)? E cambia al riavvio (misura M8)? Se cambia, la conferma "morbida" prevista da D3 abbassa ancora la barriera di F1.
4. **Verifica sul campo.** La guida §5.4 usa la build debug con account di prova? Va scritto esplicitamente (F14).
5. **Area privata.** Si accetta di vietare `privateShare == commonShare` e le share pubbliche come area privata (F8)?

---

## Esito delle correzioni

| | |
|---|---|
| **Data** | 13/10/2026 |
| **Perimetro** | i 5 problemi medi e i 10 bassi. Nessuna dipendenza, permesso o componente nuovo; nessun dispositivo, nessuna credenziale del NAS. |
| **Metodo** | Ogni correzione ha un test di regressione. Ogni test è stato **verificato reintroducendo il difetto**: il test fallisce, poi il codice è stato rimesso com'era. `tools/check.ps1` verde alla fine. |
| **Limiti dichiarati** | `docs/sicurezza.md` §6, da L19 a L25, più L4 estesa; una riga in §8. Specifica corretta in `fase2-progetto.md`, `rete-casa-vpn.md`, `nas-sessione.md` e `famiglia-dispositivi.md`. |

### Medi

**F1. "Casa" e NAS riconosciuti da dati non segreti**
- **Esito:** corretto per la parte che si può chiudere (la password); il resto è limite dichiarato (**L19**).
- **Cosa cambia:**
  - `core-security`: nuova `NasPasswordPolicy`. Accetta almeno 12 caratteri con tre tipi fra minuscole, maiuscole, cifre e simboli, oppure una frase di almeno 16 caratteri. Il nome dell'account e le sequenze ovvie (`123456`, `qwerty`, `password`, `fritzbox`…) non contano: senza di loro devono restare 12 caratteri. Rifiuta anche un carattere ripetuto 4 volte e meno di 6 caratteri diversi.
  - La soglia si applica **prima** di mandare la password, in tre punti: onboarding dell'admin, ingresso del familiare, cambio di password nelle Impostazioni.
  - Generatore: 16 caratteri a caso senza simboli, circa 79 bit.
  - Testi che spiegano la soglia e il motivo. L'avviso "password corta" (`SHORT_PASSWORD`) è sostituito dal rifiuto (`PASSWORD_TOO_WEAK`).
  - Specifica corretta: "il GUID chiude il caso" → ULA e GUID sono pubblici.
- **Perché il resto è un limite:** l'ULA è annunciato dal router e il GUID si legge senza credenziali. Nessun segnale prima del login, senza il permesso di posizione, è stato ancora misurato. Il certificato TLS del FRITZ!Box è il candidato da provare sul campo.
- **Test:**
  - `NasPasswordPolicyTest` (3);
  - `OnboardingCoordinatorTest.unaPasswordDeboleNonArrivaAlNas`;
  - `DefaultJoinFlowTest.unaPasswordDeboleDelFamiliareNonArrivaAlNas`;
  - `PasswordStrengthUiTest` (3);
  - `PasswordViewModelTest.unaPasswordDeboleNonSiProvaELAppNeProponeUnaForte`.

**F2. VPN spenta su una rete che imita casa**
- **Esito:** corretto per il tunnel completo; il residuo è limite dichiarato (**L20**).
- **Cosa cambia:**
  - `VpnClassifier.carriesInternet` riconosce un tunnel che porta internet: rotta predefinita, metà di internet, rotte pubbliche ampie di "escludi gli IP privati".
  - Il valore finisce in `NetworkSnapshot.vpnCarriesInternet`. `DefaultVpnAutomation` **non spegne mai** un tunnel così: il NAS si raggiunge attraverso il tunnel.
  - Guida e testi aggiornati.
- **Perché resta un residuo:** con il tunnel limitato a casa lo spegnimento automatico resta. Su un Wi-Fi gemello il danno si riduce al traffico verso il NAS, che regge con una password forte (L19).
- **Test:**
  - `VpnAutomationTest.inCasa_unaVpnCheportaTuttoIlTrafficoNonSiSpegneDaSola`;
  - `VpnClassifierTest.unaVpnCheportaInternetSiRiconosceDalleRotte`.

**F3. Recupero dell'admin ancorato al nome del file**
- **Esito:** corretto.
- **Cosa cambia:**
  - Il foglio del passo 12, e quello del reset, porta anche l'**impronta della famiglia** (`RecoveryCodeDisplay.familyFingerprint`).
  - `AdminRecovery.recover(…, typedFingerprint)`: senza QR di stato l'ancora è l'impronta digitata (`TypedFingerprint` tollera spazi, trattini e maiuscole). Senza QR e senza impronta il recupero non parte.
  - Il campo c'è nelle due schermate del recupero (onboarding e Impostazioni); il tasto resta spento finché l'impronta non è completa.
- **Test:**
  - `LifecycleTest.recuperoDellAdmin_senzaQrServeLImprontaDelFoglio_F3`;
  - `LifecycleTest.recuperoDellAdmin_unKitPiantatoConUnAltraChiaveNonPortaANulla`, aggiornato: senza QR ora si ferma prima di aprire il kit;
  - `FamilyKeyStepTest` e `LifecycleTest`: impronta sul foglio, anche nel reset;
  - `AdminRecoveryViewModelTest.senzaLoStatoDellaFamigliaServeLImprontaDelFoglio`;
  - `OnboardingFlowTest` (recupero);
  - `OnboardingSecurityTest.ilFoglioPortaAncheLImprontaDellaFamiglia`.

**F4. Diagnostica: chiavi e codici come testo libero**
- **Esito:** corretto.
- **Cosa cambia:**
  - L'esportatore fa uscire solo i codici del `DiagCatalog`; gli altri escono come `UNKNOWN`, senza valori.
  - Fa uscire solo i valori con una chiave di `ExportKeys`: un elenco chiuso, più tre famiglie a indice o a costante (`ula0-3`, `rejected.*`, `bytes.*`).
- **Test:**
  - `DiagnosticsExporterTest.unCodiceFuoriCatalogoEsceComeUnknownSenzaValori`;
  - `DiagnosticsExporterTest.unaChiaveFuoriElencoNonSiEsporta`;
  - `assertExportKeysAllowed` nei test dei contributori veri (salute, attività, consumi, spazio, test di connessione);
  - `DiagnosticsExportKeysTest` in `:app`: contributori di rete e fiducia sul NAS simulato, ogni valore arriva nel file.
  - Il test di copertura ha trovato una famiglia di chiavi mancante, `bytes.*`, aggiunta all'elenco.

**F5. Activity esportata e codice di approvazione falso**
- **Esito:** corretto dentro l'app; il residuo è limite dichiarato (**L21**).
- **Cosa cambia:**
  - `MainActivity` sostituisce l'intent con uno pulito (`ACTION_MAIN`) all'inizio di `onCreate`, prima di `setContent`, e in `onNewIntent`.
  - `AwaitingApprovalRoute` non ha argomenti: il codice viene dallo stato del dispositivo.
  - Per ogni stato si registrano solo le destinazioni raggiungibili.
  - La schermata d'attesa ha `SensitiveScreen` (`FLAG_SECURE` e sovrapposizioni nascoste) anche senza QR.
  - Nell'approvazione la scansione del QR della chiave è proposta per prima. I testi dicono all'admin di leggere il codice di persona e di non fidarsi di un codice arrivato per messaggio.
- **Perché resta un residuo:** un'app ostile sul telefono che entra può imitare la schermata d'attesa fuori dall'app.
- **Test:**
  - `EntryHardeningTest`: intent pulito; codice dallo stato con `FLAG_SECURE`; destinazioni per stato.
  - `OnboardingSecurityTest.ancheSenzaIlQrLAttesaEProtetta`;
  - `ApprovalFlowUiTest`: si parte dalla scansione.
- **Nota:** che `MainActivity` chiami davvero la pulizia si legge nel codice. Un'activity Hilt non si avvia in Robolectric senza l'applicazione vera. La domanda aperta 2 (prova con `adb`) resta per il campo.

### Bassi

**F6. TOFU all'onboarding e all'ingresso manuale**
- **Esito:** limite dichiarato (**L22**), con una correzione di messaggio.
- **Cosa cambia:** dopo il login, un GUID firmato diverso dà `PASSWORD_SENT_TO_OTHER_NAS`: "la password è arrivata a un altro NAS, cambiala". Prima l'errore era `IDENTITY_MISMATCH`, che diceva "nessuna password inviata".
- **Perché resta un limite:** al primo contatto non esiste un'ancora.
- **Test:** `DefaultJoinFlowTest.ilGuidFirmatoDallAdminEUnaConfermaIndipendenteDalQr` e `senzaQrIlGuidSiConfermaConQuelloFirmatoOra`.

**F7. Diario dell'azzeramento non scrivibile**
- **Esito:** corretto.
- **Cosa cambia:** all'avvio, "chiave del dispositivo assente ma stato fidato presente" vale come azzeramento interrotto e si completa.
- **Test:** `WipeJournalFailureTest` (2). Il secondo verifica che un dispositivo mai configurato, o sano, non si azzeri.

**F8. Area privata uguale all'area comune**
- **Esito:** corretto; limite dichiarato (**L23**) per una share pubblica indicata come privata.
- **Cosa cambia:**
  - l'invito, in `createInvite` e nella validazione, rifiuta un'area privata uguale all'area comune;
  - un profilo così non accetta segnalazioni "perso".
- **Test:**
  - `NoFalseWipeTest.unaSegnalazioneNellAreaComuneUsataComePrivataNonFaAzzerare`;
  - `ApprovalTest.lInvitoPortaLAncoraAttualeEApreLaFinestra`, esteso.

**F9. Interruttore: tentativi non confermati e rifiuto lento**
- **Esito:** corretto; la specifica è allineata ("al massimo 2", **L24**).
- **Cosa cambia:** un timeout o una connessione caduta dopo la traccia di `beforeAuthenticate` restano "non confermati". Due di fila aprono l'interruttore.
- **Test:**
  - `AuthBreakerTest.unTimeoutDuranteLAutenticazioneNonSiCancellaEDueDiFilaAprono`;
  - `AuthBreakerTest.gliErroriDiReteNonApronoLInterruttore`, aggiornato.

**F10. Nome del dispositivo nella notifica**
- **Esito:** corretto.
- **Cosa cambia:** il titolo è sempre generico; il nome si vede solo dentro l'app.
- **Test:** `AppIntegrationTest`: nessun testo della notifica, privata o pubblica, contiene il nome della richiesta.

**F11. `OneShotForeignShares` richiude l'interruttore prima dei controlli**
- **Esito:** corretto.
- **Cosa cambia:**
  - rifiuta l'account del dispositivo;
  - richiude l'interruttore solo dopo una prova senza password (percorso, NEGOTIATE, GUID);
  - ricorda le coppie rifiutate con un HMAC a chiave casuale, solo in memoria.
- **Test:** `ForeignSharesTest`, sul NAS simulato con un impostore che usa un altro GUID.

**F12. Password come `String` nello stato della UI**
- **Esito:** limite dichiarato (**L4** estesa).
- **Motivo:** i campi di Compose lavorano su `String`. Il resto dell'app riceve già un `CharArray` azzerato dopo l'uso, e la password non va né nei log né nello stato salvato. Passare a un campo su `CharArray` è un lavoro sul design system, fuori da questo giro.
- **Test:** nessuno nuovo.

**F13. `taskAffinity`**
- **Esito:** corretto: `android:taskAffinity=""`.
- **Test:** `EntryHardeningTest.laMainActivityNonHaLAffinitaDelPacchetto`.

**F14. Build debug con i dati veri**
- **Esito:** limite dichiarato (**L25**).
- **Cosa cambia:** la guida §5.4 dice "solo account di prova; per gli account della famiglia l'APK di release firmato".
- **Test:** non applicabile (documentazione).

**F15. Eventi riletti e anteprima diversa dal file**
- **Esito:** corretto.
- **Cosa cambia:**
  - i codici e le chiavi degli eventi riletti passano dagli elenchi chiusi di F4;
  - l'anteprima si ferma all'apertura della pagina dell'esportazione (`freezeExport`), e si salva quella.
- **Test:**
  - `DiagnosticsViewModelTest.lAnteprimaSiFermaAllaperturaESiSalvaQuellaVista`;
  - i test di F4.

### Informative

- **I1–I8:** nessuna correzione richiesta, nessuna modifica.
- **I1** vale ancora per `RejectedCredentials` dell'onboarding. La memoria nuova di F11 usa invece un HMAC con chiave casuale.
- **Domande aperte:** la 4 trova risposta nella guida §5.4; la 5 è decisa per `privateShare == commonShare` (F8). La 2 conta meno (intent ripulito, destinazioni per stato), ma la prova con `adb` resta per il campo. Restano aperte la 1 e la 3.
