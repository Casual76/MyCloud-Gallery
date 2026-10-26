# Motore delle regole delle attività (Fase 1.6, modulo `core-rules`)

Un solo posto decide se un'attività in background può girare adesso, spiega in italiano perché no,
misura i consumi e applica preset e tetti mensili. **Nessuna UI e nessun lavoro vero**: solo decisioni,
stato e misure. Fonti: piano §1–§3, 4.6, 6 (edge case), `android.md` (scoperte 1–6, temi 2 e 7),
`decisioni.md`, `inventario-nas.md` §4.

Verifica: `gradlew.bat :core-rules:testDebugUnitTest :core-rules:lintDebug`.

---

## 1. Struttura

```
                 +-----------------------+
 impostazioni -->|  RuleSettingsStore    |-- toRuleSet() --+
 (DataStore)     +-----------------------+                 |
                 +-----------------------+                 v
 contatori ----->|  TrafficLedger        |-- CycleUsage --> RuleEvaluator --> RuleDecision
 (DataStore)     +-----------------------+                 ^   (funzione pura)   Run / Wait /
                 +-----------------------+                 |                     Stop / NeedsConfirmation
 sistema ------->|  SystemStateProvider  |-- SystemState --+
                 +-----------------------+
                    ^   ^   ^   ^   ^
   DeviceStateSource   |   |   |   +-- AppContextSignal      (:app)
   (Android: batteria, |   |   +------ AndroidLimitsSignal   (chi esegue i lavori)
    PowerManager,      |   +---------- DeviceRolesSignal     (impostazioni)
    rete)              +-------------- HomeNetworkSignal / NasReachabilitySignal / NetworkQualitySignal  (Fase 2)
```

| Pezzo | File | Cosa fa |
|---|---|---|
| Attività | `BackgroundActivity.kt` | le 6 attività, leggera/pesante, ruolo richiesto, quota Android |
| Modello delle regole | `RuleModel.kt` | `TimeWindow`, `ActivityRule`, `RuleSet`, `MonthlyCaps` |
| Preset | `Presets.kt` | `PresetCatalog` (regole + descrizioni), `NightWindows` (fascia notte dal NAS) |
| Stato del sistema | `SystemState.kt` | `SystemState`, `NetworkClassifier` (che rete è) |
| Valutatore | `RuleEvaluator.kt` | puro: richiesta + regole + stato + consumi -> verdetto |
| Verdetti e motivi | `RuleDecision.kt` | `RuleDecision`, `WaitReason` (con il testo italiano), conferma "Fai ora" |
| Contatori | `traffic/` | `TrafficLedger`, `DataStoreTrafficLedger`, `BillingCycle`, `CycleUsage` |
| Impostazioni | `settings/` | `RuleSettings`, `DataStoreRuleSettingsStore` |
| Stimatore | `estimate/` | `ConsumptionEstimator`, `DeviceMeasures` (costanti Fase 0), `EstimateText` |
| Segnali | `Signals.kt` | contratti Fase 2 / `:app` + fake |
| Provider | `SystemStateProvider.kt` | unisce dispositivo e segnali; ricorda l'ultimo distacco e l'ultimo stato termico severo |
| Adattatori Android | `platform/` | `AndroidDeviceStateSource` (legge Android), `AndroidMappers` (traduce, senza Android) |
| Orchestratore | `ActivityRulesEngine.kt` | `evaluate`, `evaluateAll`, `observeAll` |

### Come lo usa chi esegue i lavori

1. Prima di partire: `engine.evaluate(WorkRequest.Background(attività))`. `Run` -> parte; `Wait` -> no, e
   `reason.message` dice perché.
2. A ogni lotto: `evaluate(request, running = true)`. `Stop` = "fermati": salva il punto di ripresa ed esci.
   Un breve distacco dell'alimentazione (< 15 s) non ferma un lavoro in corso.
3. Dopo ogni trasferimento: `ledger.record(sorgente, rete, byte)` con la rete **effettivamente usata**
   dal socket (E-R12), in tempo reale: se il processo muore, il traffico già passato conta.
4. `RuleDecision.Run.scope == LOCAL_BUFFER_ONLY`: elabora solo ciò che hai già in locale, poi fermati con
   lo stato "solo buffer" (E-A6). `Run.pace == REDUCED`: pause fra i lotti (telefono caldo).
   `Run.remainingBytes`: quanti byte restano nel tetto della rete in uso.
5. "Fai ora": `WorkRequest.UserInitiated(attività, byteStimati)`. Se torna `NeedsConfirmation` si mostra
   `message` (con la stima) e si rilancia con `confirmed = true`.

---

## 2. Le attività

| Attività | Peso | Ruolo | Quota Android | Note |
|---|---|---|---|---|
| Backup foto (`PHOTO_BACKUP`) | leggera | - | `dataSync` | |
| Backup video (`VIDEO_BACKUP`) | **pesante** | - | `dataSync` | regole proprie (piano §2) |
| Sync indice (`INDEX_SYNC`) | leggera | - | `dataSync` | in primo piano è sempre consentita |
| Preparazione (`PREPARATION`) | **pesante** | Preparatore | `mediaProcessing` | a CPU: ritmo ridotto col caldo |
| Calcolo AI (`AI_COMPUTE`) | **pesante** | Indicizzatore | `mediaProcessing` | a CPU; **senza NAS lavora sul buffer locale** |
| Anteprime HQ (`HQ_PREVIEWS`) | **pesante** | - | `dataSync` | si spegne se la qualità non è "Alta" (`disabledActivities`) |

"Pesante" significa: con `heavyOnlyAtHome` (proposta 1) gira solo sul Wi-Fi di casa.

**Calcolo AI (Fase 6, `docs/fase6-progetto.md` §2.2 e §2.7).** Nessuna attività nuova: il calcolo di vettori e testo, la lettura del
testo (OCR) in background e lo scaricamento dei modelli girano tutti dentro `AI_COMPUTE`.
* **Legge dal NAS e il traffico si registra.** Il calcolo scarica dall'indice della share le anteprime di analisi, i fotogrammi dei
  video e, per il testo fitto, le anteprime HQ (a lotti): quel traffico va nel `TrafficLedger` con `TrafficSource.AI_COMPUTE` e la
  rete **effettivamente usata**, subito, prima del punto di ripresa (`StagedRun`). Essendo pesante, scarica solo sul Wi-Fi di casa
  (proposta 1).
* **Lavora sul buffer senza rete.** Ciò che sta già nel buffer locale degli indicizzatori si elabora senza rete: se gli unici
  ostacoli sono di rete o di NAS (fuori casa, tetto raggiunto, NAS spento) il verdetto è `Run` con `LOCAL_BUFFER_ONLY` e il lavoro
  si ferma con lo stato "Fatto ciò che c'era in locale" (E-A6).
* **Il ruolo e la lettura del testo.** Serve il ruolo Indicizzatore (scelto da ogni utente per i propri dispositivi). L'OCR gira
  solo su un indicizzatore con la **lettura del testo** attiva: è una funzione dell'account, non un ruolo né un'attività (D41).
* **Scaricamento dei modelli.** In background segue le regole di `AI_COMPUTE` (pesante: fascia e alimentazione del preset, solo sul
  Wi-Fi di casa), con la rete validata e non a consumo per HTTPS e il cancello di rete del NAS per le copie sul NAS; mai dati
  mobili né roaming. "Scarica ora" e "Indicizza ora" sono `UserInitiated(AI_COMPUTE)` (scavalcano fascia e carica, non la
  temperatura; sui dati mobili chiedono conferma con la dimensione).
* **Senza regole**: la ricerca, il testo dal vivo, il ritaglio e la lettura del testo al volo (in primo piano, su un tocco, tutto
  locale); le teste `ai` si sincronizzano dentro `INDEX_SYNC` (leggera).

**Calcolo dei volti (Fase 8, `docs/fase8-progetto.md` D16, D17; regole di P4).** Nessuna attivita' nuova: il calcolo dei volti gira
**dentro `AI_COMPUTE`** (`FacesComputeWorker`, tappe da 8 minuti con `StagedRun`, dopo il calcolo AI della stessa notte), solo sugli
indicizzatori dell'account con Persone attiva su quel dispositivo (dopo l'avviso, mai su un dispositivo escluso). Gli ingressi sono le
anteprime di analisi del buffer, dell'indice o del telefono; se un'anteprima non c'e' da nessuna parte, un indicizzatore che e' anche
Preparatore la **rifa' dall'originale del NAS** come una preparazione (solo `analysis`): e' un'attivita' **pesante** (Wi-Fi di casa,
regole di `PREPARATION`) e il suo traffico si conta come quello della preparazione. "Analizza ora" e' `UserInitiated(AI_COMPUTE)`. Il
raggruppamento notturno del custode e la sincronizzazione dei dati dei volti (leggera, dentro `INDEX_SYNC`) non sono attivita' nuove.

---

## 3. Preset × attività

Fascia notte di default `23:00-07:00`. Reti: **W** = Wi-Fi di casa + qualunque Wi-Fi via VPN; **W+M** = W + dati
mobili. Il roaming non è in nessun preset. Batteria: soglia **20%** (a batteria, mai se collegato).

| Preset | Leggere (sync indice, backup foto) | Pesanti (video, preparazione, calcolo AI, anteprime HQ) | Reti | Pesanti solo a casa | Tetto dati mobili |
|---|---|---|---|---|---|
| **Notte** (default) | solo in fascia notte, collegato | solo in fascia notte, collegato | W | sì | 1 GB |
| **Bilanciato** | sempre, anche a batteria (>= soglia) | sempre, **collegato** | W | sì | 1 GB |
| **Wi-Fi sempre** | sempre, anche a batteria (>= soglia) | sempre, anche a batteria (>= soglia) | W | sì | 1 GB |
| **Massimo** | sempre, anche a batteria (>= soglia) | sempre, anche a batteria (>= soglia) | **W+M** | **no** | **10 GB** |
| **Personalizzato** | regole scelte per ogni attività (parte da Notte), i video separati | idem | a scelta | a scelta | a scelta |

Valido per tutti: nessun tetto Wi-Fi (scelta dell'utente); risparmio energetico = background fermo; il
roaming si abilita solo con l'interruttore `allowRoaming` (che aggiunge `ROAMING` alle reti di ogni
attività). Il tetto dati mobili di 1 GB dei preset senza dati mobili serve al contenuto che si carica
con l'app aperta (proposta 3), che nessun preset blocca.

**Eccezione per il backup delle foto nuove** (Fase 5, D34, approvata con Q7): "Proteggi subito le foto nuove" porta una foto
appena scattata sul NAS anche fuori dalla fascia di Notte e a batteria sopra la soglia, ma solo sul Wi-Fi di casa e solo per le
foto della fotocamera (§8, punto 14).

Descrizioni per l'utente (`PresetCatalog.info`, qui in sintesi):

| Preset | Che cosa fa | Per chi |
|---|---|---|
| Notte | Tutto il lavoro in background parte solo di notte, con il telefono collegato alla corrente e in Wi-Fi. Consuma pochissimo e non te ne accorgi. | Chi lascia il telefono in carica di notte |
| Bilanciato | Sincronizzazione dell'indice e backup delle foto appena c'è il Wi-Fi, anche a batteria (sopra la soglia). I lavori pesanti aspettano la corrente. | Chi vuole il backup delle foto in giornata |
| Wi-Fi sempre | Tutto il lavoro parte ogni volta che c'è il Wi-Fi, anche a batteria (sopra la soglia). I lavori pesanti restano sul Wi-Fi di casa. | Chi è spesso in Wi-Fi |
| Massimo | Tutto, sempre: anche con i dati mobili entro il tetto mensile e anche a batteria (sopra la soglia). Il roaming resta escluso. | Chi ha dati illimitati |
| Personalizzato | Scegli tu, attività per attività, quando, con quale alimentazione e su quale rete. I video hanno regole separate. | Chi vuole il controllo |

### Fascia notte dall'orario del NAS (proposta 14)

`NightWindows.derive(NasSchedule(acceso, spento))` prende l'intersezione **più lunga** fra la notte ideale
(`23:00-07:00`) e le ore in cui il NAS è acceso. Se il NAS è sempre acceso o l'orario non si conosce vale la
notte ideale. Se l'intersezione è sotto i 30 minuti (oggi: NAS acceso `08:00-22:00`, nessuna sovrapposizione)
ripiega sulla notte ideale e segnala `overlapsNas = false`: la UI propone di allungare l'orario del NAS o di
permettere il giorno (E-R5). La fascia derivata si salva in `RuleSettings.nightWindow`.

---

## 4. Come decide il valutatore

### Richieste

| Richiesta | Regole applicate |
|---|---|
| `Background(attività)` | tutte (sotto). Eccezione: `INDEX_SYNC` con l'app in primo piano = come `VisibleContent` |
| `VisibleContent` (miniature/anteprime sullo schermo, solo app aperta) | profilo, roaming, rete, **tetti**, NAS, sicurezza termica CRITICAL. Niente fascia/carica/batteria/risparmio/reti ammesse/pesante/limiti Android |
| `UserInitiated` ("Fai ora") | scavalca fascia, alimentazione, soglia, risparmio energetico, reti ammesse, quote Android. Restano: ruolo, rete utilizzabile, NAS, sicurezza termica CRITICAL. Chiede **conferma** su dati mobili, roaming, tetto superato o che la stima supererebbe |

### Verdetti

| Verdetto | Significato |
|---|---|
| `Run(scope, pace, network, remainingBytes)` | può girare (o continuare) |
| `Wait(reason, alsoBlockedBy)` | non deve partire |
| `Stop(reason, alsoBlockedBy)` | come `Wait` ma per un lavoro **già in corso** (`running = true`): fermati |
| `NeedsConfirmation(kinds, estimate)` | solo "Fai ora": `MOBILE_DATA`, `ROAMING`, `OVER_CAP`; `message` ha la stima |

### Motivi, in ordine di priorità (il primo è `reason`, gli altri `alsoBlockedBy`)

| # | `WaitReason` | Testo italiano |
|---|---|---|
| 1 | `ProfileInactive` (E-P19) | Profilo non attivo: il lavoro riprende quando torni su questo profilo |
| 2 | `RoleDisabled` | Questo dispositivo non è un indicizzatore / un preparatore |
| 3 | `ActivityDisabled` | Attività disattivata nelle impostazioni |
| 4 | `Roaming` | Roaming: attività sospese |
| 5 | `Offline` (anche pagina di accesso) | Nessuna connessione: in attesa di una rete |
| 6 | `NetworkNotTrusted` | Rete non riconosciuta: serve il Wi-Fi di casa o la VPN |
| 7 | `PoorNetwork` (E-R4) | Rete lenta o instabile: attività sospese |
| 8 | `OutsideWindow` | In attesa della fascia oraria (23:00-07:00) |
| 9 | `NeedsPower` | In attesa: serve la carica |
| 10 | `BatteryLow` | Batteria al 19%: in pausa sotto il 20% |
| 11 | `PowerSaver` (E-P10) | Risparmio energetico attivo: attività in pausa |
| 12 | `Thermal` (E-A5) | Telefono troppo caldo: in pausa finché si raffredda |
| 13 | `NetworkNotAllowed` | In attesa del Wi-Fi: i dati mobili non sono consentiti per questa attività / In attesa del Wi-Fi di casa |
| 14 | `HeavyNeedsHomeWifi` | Attività pesante: parte solo sul Wi-Fi di casa |
| 15 | `CapReached` (E-B10) | Tetto dati mobili raggiunto (1,2/1 GB) / Tetto dati Wi-Fi raggiunto (51/50 GB) |
| 16 | `AndroidLimit` (E-P14) | Limite di Android raggiunto: riprende la notte dopo |
| 17 | `NasUnreachable` (E-R5) | NAS spento o non raggiungibile |
| - | `AppNotVisible` | Si carica solo con l'app aperta |
| - | `ConfirmationRequired` (Fai ora interrotto da un cambio di rete) | Serve la tua conferma per usare i dati mobili |

`OutsideWindow` porta con sé `nextStart` (quando riprende). La raggiungibilità del NAS non si valuta se la rete
non è utilizzabile (non avrebbe senso).

### Regole di dettaglio

* **"In carica" = alimentazione collegata** (`EXTRA_PLUGGED != 0`), non lo stato di carica. Con la Protezione
  batteria Samsung (carica ferma all'80-90% con il cavo, `status = NOT_CHARGING`) il telefono conta come in
  carica (E-P16); `PowerState.chargingPaused` serve solo alla UI.
* **Soglia batteria** (20%): si applica solo a batteria; `level < soglia` ferma, `level == soglia` no.
* **Fascia**: inizio incluso, fine esclusa; a cavallo della mezzanotte `23:00-07:00` contiene `00:00`.
  Per un lavoro in corso la fascia che si chiude dà `Stop`.
* **Termica**: `SEVERE` o headroom >= 0,95 sospende il background; `LIGHT`/`MODERATE` o headroom >= 0,85
  = ritmo ridotto per le attività a CPU; dopo uno stato severo si riparte solo dopo 5 minuti (isteresi,
  `timeSinceThermalSevere`); `CRITICAL` o peggio ferma tutto, anche "Fai ora" e contenuto visibile.
* **Falsi distacchi**: un lavoro in corso tollera un'alimentazione scollegata da meno di 15 s (visto
  sull'S25: ~5 s, `android.md` 2.2 bis); per partire serve l'alimentazione stabile.
* **Rete scadente** (E-R4, `NetworkState.poor`): ferma solo il lavoro automatico. Contenuto visibile, sync
  con l'app aperta e "Fai ora" continuano (la UI mostra gli indicatori di lentezza). Il segnale lo misura la
  Fase 2 con il NAS (`NetworkQualitySignal`); **non** lo si ricava da `NOT_CONGESTED`/`NOT_SUSPENDED` perché non
  è verificato che tutte le reti (cellulare) li espongano, e un falso positivo fermerebbe il backup per sempre.
* **Calcolo AI senza rete utile** (offline, rete non riconosciuta, rete scadente, rete non ammessa, "pesante"
  fuori casa, tetto raggiunto, NAS spento): se questi sono gli **unici** ostacoli, `Run` con `LOCAL_BUFFER_ONLY`. Il
  roaming no: resta `Wait` (nessuna attività automatica in roaming, proposta 2).
* **Limiti di Android** (`AndroidLimits`): `dataSync` per backup, sync indice, anteprime HQ; `mediaProcessing`
  per preparazione e calcolo AI; la quota dei job ferma tutto.

### Classificazione della rete (`NetworkClassifier`)

| Trasporto | VPN | Casa | A consumo | Roaming | Validata | Classe |
|---|---|---|---|---|---|---|
| nessuno | - | - | - | - | - | `OFFLINE` |
| qualsiasi | - | - | - | - | **no** (pagina di accesso, E-R13) | `OFFLINE` |
| cellulare | - | - | - | **sì** | sì | `ROAMING` |
| cellulare | sì | - | - | no | sì | `MOBILE` |
| cellulare | **no** | - | - | no | sì | `UNTRUSTED` (VPN spenta) |
| Wi-Fi / Ethernet | - | **sì** | - | - | sì | `HOME_WIFI` (anche se marcata a consumo: il traffico è locale) |
| Wi-Fi / Ethernet | no | no | - | - | sì | `UNTRUSTED` (SMB mai su reti sconosciute) |
| Wi-Fi / Ethernet | sì | no | **sì** | - | sì | `MOBILE` (hotspot / Wi-Fi a consumo, E-R13) |
| Wi-Fi / Ethernet | sì | no | no | - | sì | `EXTERNAL_WIFI` |

Con due reti contemporanee conta quella **effettivamente usata** (la predefinita di Android, E-R12): se
Android passa ai dati mobili, le regole vedono `MOBILE` e il traffico pesante non parte.

---

## 5. Contatori di traffico e tetti

* `TrafficLedger.record(sorgente, rete, byte)`; sorgenti = le 6 attività + `VISIBLE_CONTENT`; reti = Wi-Fi di
  casa, Wi-Fi esterno, dati mobili, roaming. Un Wi-Fi a consumo si registra come dati mobili.
* **Tetto dati mobili** = mobile + roaming; **tetto Wi-Fi** = casa + esterno. Valgono per tutte le attività
  insieme. `null` = nessun tetto. "Raggiunto" = consumo >= tetto.
* **Azzeramento mensile**: il ciclo corrente si ricava dalla data (fuso orario del dispositivo, E-P5); un ciclo
  nuovo non ha ancora chiavi, quindi parte da zero a mezzanotte del primo giorno. Nessun lavoro di pulizia da
  schedulare. Giorno di inizio configurabile (1-28) per i piani dati con rinnovo a metà mese.
* **Storico**: i cicli passati restano in DataStore per 12 cicli (`keepCycles`); `history()` li elenca.
* **Backup (Fase 5)**: il backup registra caricamento **e** rilettura di verifica (§6, "Backup").
* Persistenza: `DataStore<Preferences>`, chiavi `traffic.<AAAA-MM>.<SORGENTE>.<RETE>` (somma atomica con
  `edit`, saturante). Le impostazioni usano chiavi `rule.*` (si può condividere lo stesso DataStore).
  `:app` crea il `DataStore` (serve la dipendenza `datastore-preferences` anche lì).

---

## 6. Stimatore dei consumi

`ConsumptionEstimator.estimate(rules, backlog, measures, profile)`. Input: l'arretrato per attività
(`Workload(elementi, byte)`) e il flusso mensile di media nuovi; le misure del dispositivo; l'uso tipico di una
giornata. Output (`ConsumptionEstimate`): batteria per notte (anche la quota che pesa davvero sulla batteria),
batteria al giorno a regime, dati mobili e Wi-Fi al mese, dati mobili nel primo mese, giorni per finire.
`EstimateText.describe` ne fa una frase.

### Costanti (`DeviceMeasures.PHASE_0`, tutte sostituibili)

| Costante | Valore | Fonte |
|---|---|---|
| Scrittura SMB (backup) | 9,0 MB/s | `inventario-nas.md` §4, S25, cifrata (tablet 7,3) |
| Lettura SMB (sync indice, preparazione, HQ) | 16,0 MB/s | idem, cifrata, 4 flussi (S25 16,3, tablet 15,8) |
| Calcolo AI | 2,8 foto/s | `decisioni.md`, GPU, con decodifica (700 foto) |
| Batteria Calcolo AI | 4% ogni 1000 foto | `decisioni.md`, GPU |
| Anteprima di analisi | 150 KB | `decisioni.md` (E-I11) |
| Batteria trasferimento | **0,5% per GB** | **ipotesi, non misurata** (Wi-Fi + cifratura SMB + hash): da misurare |
| Fattore radio cellulare | **x2** | **ipotesi**: la radio mobile costa ~2x il Wi-Fi a parità di byte |
| Rilettura di verifica del backup (`verifyReadBytesPerSecond`) | **16e6** B/s (16 MB/s) | Fase 5, D4: stessa misura della lettura SMB cifrata a 4 flussi (`inventario-nas.md` §4); **ipotesi** per la rilettura del temporaneo appena scritto, da misurare a M2 |
| Costo per file del backup (`perFileOverheadSeconds`) | **0,12** s | Fase 5, `docs/fase5-progetto.md` §2.9: circa 12 viaggi SMB (creazione del temporaneo, scritture, `flush`, chiusura, apertura e lettura di verifica, rinomina, `stat`) a circa 10 ms l'uno in casa; **ipotesi**, da misurare in F22 |
| Caricamenti paralleli (`uploadStreams`) | **1** | Fase 5: i 9 MB/s della scrittura sono la misura con un flusso; due caricamenti paralleli **non sono misurati** (la CPU del NAS fa da tetto: in lettura 4 flussi rendono 2,2 volte uno); si misurano con 1 e 2 flussi in F22 |
| Calcolo AI dalle anteprime (`aiItemsPerSecond`, **ricalibrata**) | **3,6** foto/s di partenza (**2,8** col caldo, che è anche il valore prudente di oggi) | Fase 6, `docs/fase6-progetto.md` §2.8: 440M generico su GPU, 4,4 foto/s di solo embedding (Fase 0); dalle anteprime da 1024 px la decodifica pesa meno che dagli originali (il 2,8 della Fase 0 era con la decodifica degli originali), col caldo −26%. **Ipotesi, da misurare in P4 e P5 (F52)**, anche sul Tab S9, che non ha misure |
| Batteria Calcolo AI (`aiBatteryPercentPerThousandItems`) | 4% ogni 1000 foto (invariata) | Fase 0, GPU; da confermare in F52 |
| Lettura del testo (`ocrSecondsPerItem`, **nuova**) | **0,1–0,25** s a immagine (si parte dal limite alto, 0,25 s, per una stima prudente); seconda passata sull'HQ su circa il 15% delle immagini | Fase 6 §2.8: ML Kit su CPU, in parallelo all'embedding, solo con la lettura attiva; **ipotesi, mai misurata**, da misurare in P4 e P5 (F52) |
| Fotogrammi per video (`framesPerVideo`, **nuova**) | circa **4** a video (al massimo 9: copertina e fino a 8, uno ogni 30 s); un vettore per fotogramma | Fase 6 §2.8: porta l'archivio di famiglia (circa 22.000 foto e 2.600 video) a circa 32.000 vettori; da verificare con il backlog vero (P5) |
| Anteprime scaricate dal NAS per il calcolo (`analysisPreviewBytes` e simili) | analisi **150 KB**, fotogrammi **450 KB** a video, HQ **400 KB** | Fase 4 §2.10 e Fase 6 §2.8; il commento di `analysisPreviewBytes` dice "~768 px", il codice produce 1024 px (lo corregge P1) |
| Scaricamento dei modelli (`modelBytes`, **nuova**) | 270M **164.626.432** B (157 MiB), 440M **387.710.976** B (370 MiB); dal NAS a 16 MB/s, da Internet di casa il 270M in circa 30 s | Fase 6 §2.1: catalogo fissato nell'app. Spazio libero richiesto = dimensione mancante + 300 MB (E-A3). **Ipotesi per la velocità da Internet**, da misurare in P3 (F46) |

`UsageProfile` (ipotesi, nessuna misura in Fase 0): 8 h/giorno collegato, 14 h sul Wi-Fi di casa, 1 h su altro
Wi-Fi, 8 h sui dati mobili, efficienza 0,8 (risvegli del NAS, ripartenze, riavvii notturni E-P13).

### Formule

* **Tempo di lavoro** `T` dell'arretrato: backup = byte / scrittura; sync indice, preparazione, HQ = byte /
  lettura; calcolo AI = elementi / foto al secondo.
* **Ore utili al giorno** `u` = `min(ore della fascia, ore collegato se serve la carica, ore delle reti
  ammesse) x efficienza`. Reti ammesse = ore su ciascuna rete della regola (le pesanti, con "solo a casa", solo
  Wi-Fi di casa). Il calcolo AI non è limitato dalla rete. Attività spenta: `u = 0`, esclusa dai totali.
* **Giorni per finire**: le attività di rete si dividono la stessa banda (il collo di bottiglia è il NAS), quindi
  `giorni_rete = (somma delle T di rete) / u` (il maggiore fra le attività); il calcolo AI è in parallelo,
  `giorni_AI = T_AI / u_AI`. Totale = il maggiore dei due. `null` = non finisce mai (regole che non lasciano
  nessuna ora utile). Stima prudente.
* **Energia dell'arretrato** `E`: trasferimento = `GB x 0,5% x (1 + quota_mobile x (fattore - 1))`; calcolo AI =
  `migliaia di foto x 4%`. `quota_mobile` = ore mobili / ore di tutte le reti ammesse (0 se i dati mobili non
  sono ammessi).
* **Batteria per notte** = somma di `E x min(1, u / T_rete_totale)` per le attività di rete (e `min(1, u / T_AI)`
  per il calcolo AI). **Dalla batteria** = solo le attività con alimentazione `BATTERY_ALLOWED` (con Notte: 0).
* **Batteria a regime al giorno** = `E` dei soli media nuovi del mese / 30 (obiettivo del piano §4: < 2% con Notte).
* **Dati al mese**: byte dei media nuovi ripartiti per ore su ogni rete ammessa; la quota mobile è limitata dal
  tetto mobile, il Wi-Fi dal tetto Wi-Fi. "Primo mese" aggiunge l'arretrato.

Esempio (test): 20.000 foto da indicizzare = 1,98 ore di lavoro (la Fase 0 stimava "~2 ore") e 80% di energia,
tutta dalla presa con Notte, smaltita in una notte.

### Backup (Fase 5, `docs/fase5-progetto.md` §2.9)

Il backup non è un semplice trasferimento: ogni file si **scrive** sul NAS e poi si **rilegge per intero** prima del nome
definitivo (D4). Per questo:

* **Tempo** del backup di `B` byte in `N` file: `T = B / W + B / R + N · o`, con `W` = scrittura SMB (9,0 MB/s con un
  flusso, `uploadStreams = 1`), `R` = `verifyReadBytesPerSecond` (16 MB/s) e `o` = `perFileOverheadSeconds` (0,12 s).
  Con due caricamenti paralleli il totale può scendere se il NAS li regge: lo dice la misura di F22.
* **Il traffico del backup conta anche la rilettura**: caricamento **e** rilettura si registrano nel `TrafficLedger` con la
  rete usata, subito, prima del punto di ripresa (`StagedRun`). Il traffico di un backup è quindi circa il **doppio** dei
  byte caricati: per il primo backup dell'S25 (~48 GB di media, dimensioni ipotizzate) circa 97 GB sul Wi-Fi di casa. Con il
  preset Massimo il tetto di 10 GB di dati mobili vale circa 5 GB di media.
* **Arretrato**: `BacklogFacts.toProtect` (media da caricare, c'è già) più `toVerify` (copie da rileggere, nuovo).
* **Esempio** (ipotesi, dimensioni vere solo dopo M2): primo backup dell'S25 con 6.658 media = circa 2,6 ore (caso basso
  1,5, alto 5,3), una notte con il preset Notte; a regime (200 foto e 10 video al mese) circa 6 minuti di lavoro e 3,4 GB
  di traffico al mese.

Le tre costanti nuove sono **ipotesi** come le altre: si ricalibrano con le misure di M2 e del primo backup vero (F22, F32).

### Calcolo AI e lettura del testo (Fase 6, `docs/fase6-progetto.md` §2.8)

Un "elemento" del calcolo è un **vettore**: una foto, o un fotogramma di un video (`framesPerVideo`). `BacklogFacts.toAnalyze` lo
alimenta il catalogo (celle senza il vettore della ricetta corrente). La lettura del testo non cambia il numero di elementi: si
somma al tempo di ogni immagine solo dove è attiva.

* **Tempo** del calcolo di `N` elementi: `T = N / aiItemsPerSecond`. Con la lettura del testo attiva, che lavora in parallelo
  all'embedding sulla stessa anteprima (CPU contro GPU), la proposta per P5 è `T = max(N / aiItemsPerSecond, N · ocrSecondsPerItem)`:
  conta la fase più lenta.
* **Traffico**: le anteprime lette dal NAS si registrano come `AI_COMPUTE` (§2, §5); lo scaricamento dei modelli, una volta per
  famiglia, anche (527 MB da Internet, poi dal NAS).
* **Prima indicizzazione** dell'archivio di famiglia (ipotesi, un indicizzatore, S25): circa 32.000 vettori a 2,8 al secondo ≈ 3,2
  ore (con due indicizzatori circa 1,6 ore ciascuno); circa 5,7 GB di anteprime (foto, video e HQ per il testo fitto) ≈ 6 minuti a
  16 MB/s, in parallelo al calcolo; circa 4% di batteria ogni 1000 foto ≈ 130%, quindi una notte dalla presa con il preset Notte.
* **A regime** (200 foto e 10 video al mese): circa 250 vettori al mese, circa 2 minuti di calcolo e l'1% di batteria al mese,
  dentro l'obiettivo del 2% al giorno.
* **Dispositivo lento** (E-A4 senza NPU): `DeviceProbe` misura 20 immagini sintetiche e la stima ("Analizzare 18.000 media richiede
  circa 2 notti") nasce da `aiItemsPerSecond` misurato qui, non dal valore di partenza.

Le costanti nuove o ricalibrate di questo paragrafo (`aiItemsPerSecond` dalle anteprime, `ocrSecondsPerItem`, `framesPerVideo`,
`modelBytes`) sono **ipotesi** da sostituire con le misure di P4 e P5 (F52, F46).

### Album, viaggi e condivisione (Fase 7, `docs/fase7-progetto.md` §1.4, §2.2, §2.3, §2.5, D16, D25, D28)

Costanti nuove (ipotesi di partenza, da misurare in F91):
* `smartAlbumRecomputeBudget`: il ricalcolo di un album smart a lotti di 2.000 candidati, obiettivo < 2 s per album su 100 mila elementi
  (`SmartLimits.RECOMPUTE_BATCH`); gira dentro `LibraryMaintenanceWorker` dopo le scansioni con novita', solo per gli album non congelati
  che coprono i giorni toccati.
* `tripDetectionNightly`: la rilevazione di casa, viaggi ed eventi una volta per notte (e dopo un primo backup o una prima scansione),
  obiettivo < 5 s sulla Libreria intera.
* `similarDuplicatesWindow`: i doppioni "simili" si cercano a gruppi di 10 minuti nello stesso giorno, solo media con vettore o firma.

### Persone (Fase 8, `docs/fase8-progetto.md` D10, D16, D17, §2.12)

Costanti nuove (ipotesi di partenza [stima], da misurare: P2 scrive `facesPerSecond`, F104 conferma):
* `facesPerSecond`: foto analizzate al secondo dal rilevatore e dall'embedding dei volti sulla CPU (YuNet a 1024 px, SFace per volto):
  partenza 20-30 sull'S25 (~30-50 ms a foto), 12-20 sul Tab S9. Tempo del calcolo dei volti di `N` foto: `T = N / facesPerSecond`, piu'
  la seconda passata HQ.
* `facesHqShare`: la parte di foto che vuole la seconda passata sull'HQ (volto sotto 40 px o almeno 5 volti), ~0,2, a ~100-200 ms l'una.
* `faceFramesPerVideo`: fotogrammi di un video analizzati per i volti (copertina e fino a 3 del blob `frames`), 3 (`FacesComputeLimits.FRAMES_PER_VIDEO`).
* `facesGroupingNightly`: il raggruppamento notturno del custode (assegnazione, nascite, manutenzione), obiettivo < 1 minuto per ~20.000
  volti; il raggruppamento incrementale gira dopo ogni unita' (o ogni 50 volti non assegnati, `GroupingParams.BIRTHS_EVERY`).

**Prima analisi** dell'archivio dell'utente (~15.000 foto e ~2.600 video, un indicizzatore, S25): ~25-35 minuti di calcolo, di notte in
carica; PSS +80-150 MB col calcolo in corso. Le anteprime che mancano e vanno rifatte dall'originale costano come una preparazione.

**Attivita' pesanti** (Wi-Fi di casa, come il backup, F5-D9): gli **spostamenti in condivisione** (`ShareWorker`: copia lato server e
verifica completa nell'area comune, anche come lavoro avviato dall'utente, UIDT come le operazioni della 4B) e il **guardiano dopo una
condivisione** (D28: copia privata dei media propri rimasti con la sola copia comune). Il traffico degli spostamenti si conta come quello
delle operazioni del NAS; i caricamenti dal telefono verso la cartella dell'album come `INCOMING`.


---

## 7. Adattatori Android (`platform/`)

| Dato | Fonte Android | Note |
|---|---|---|
| Alimentazione collegata | `ACTION_BATTERY_CHANGED`, `EXTRA_PLUGGED` | **non** `isCharging()` né `EXTRA_STATUS` |
| Carica sospesa (solo UI) | `EXTRA_PLUGGED != 0` e `EXTRA_STATUS == NOT_CHARGING` | Protezione batteria |
| Livello | `EXTRA_LEVEL` / `EXTRA_SCALE` | dati mancanti -> 100 (non blocca per soglia) |
| Risparmio energetico | `PowerManager.isPowerSaveMode` | |
| Stato termico | `PowerManager.currentThermalStatus` | |
| Headroom | `PowerManager.getThermalHeadroom(10)` (API 30+) | al massimo una chiamata ogni 10 s; NaN -> null |
| Trasporto, VPN, a consumo, roaming, validata | `ConnectivityManager.activeNetwork` + `NetworkCapabilities` | `TRANSPORT_*`, `NOT_METERED` / `TEMPORARILY_NOT_METERED`, `NOT_ROAMING`, `VALIDATED`; una VPN senza trasporto proprio -> si cerca la rete sottostante |

**Permesso**: leggere la rete richiede `ACCESS_NETWORK_STATE`. Il modulo **non** lo dichiara (un permesso nuovo
è una decisione di sicurezza: riga in `docs/sicurezza.md` §4-bis **e** in `releaseManifestPolicy`, altrimenti
`assembleRelease` fallisce): deve dichiararlo `:app`. Senza, la rete risulta `OFFLINE` e il background non parte
(fallisce in modo sicuro).

I segnali "rete di casa", "NAS raggiungibile" e "rete scadente" sono interfacce (`HomeNetworkSignal`,
`NasReachabilitySignal`, `NetworkQualitySignal`) che implementa la Fase 2; qui c'è il contratto e un fake
(finché non esiste la misura, `NoNetworkQualityInfo` dice "rete buona"). Idem `DeviceRolesSignal`,
`AndroidLimitsSignal` e `AppContextSignal` (app in primo piano e profilo attivo, da `:app`).

---

## 8. Decisioni prese qui, da confermare

1. **Il Calcolo AI è "pesante"** (solo Wi-Fi di casa per scaricare le anteprime), ma senza rete utile o senza NAS
   lavora sul buffer locale.
2. **Tetti di default**: dati mobili 1 GB (10 GB con Massimo); nessun tetto Wi-Fi.
3. **In primo piano la sync dell'indice è consentita anche sui dati mobili**, entro il tetto (così dice il brief; la
   proposta 3 diceva "sui dati mobili solo ciò che è visibile").
4. **Roaming**: nessuna attività automatica nemmeno con l'interruttore per il contenuto visibile; "Fai ora" in
   roaming chiede conferma.
5. **Rete non riconosciuta** (né casa né VPN): il NAS non si contatta, nemmeno con "Fai ora".
6. **Wi-Fi a consumo** = dati mobili solo se non è la rete di casa.
7. **Risparmio energetico** ferma il background anche in carica (c'è l'interruttore `pauseOnPowerSaver`, da
   usare se T-22 mostra che il risparmio Samsung resta acceso in carica).
8. **Termica**: `SEVERE` sospende il background ma "Fai ora" può scavalcarlo; `CRITICAL` ferma tutto. Ripartenza
   dopo 5 minuti.
9. **Soglia batteria** solo a batteria (collegato non si applica).
10. **Fascia notte ideale 23:00-07:00**; con il NAS 08-22 di oggi il NAS risulta spento nella fascia.
11. **Stime**: 0,5%/GB di batteria, fattore radio x2 e il profilo d'uso sono ipotesi, non misure.
12. **Ciclo dati** con giorno di inizio configurabile (1-28).
13. `:app` dichiara `ACCESS_NETWORK_STATE` (vedi §7).
14. **"Proteggi subito le foto nuove"** (Fase 5, D34): **approvata dall'utente il 15/10 (Q7 (a))**, anche con il preset Notte.
    Con qualunque preset, una foto appena scattata si carica appena c'è il Wi-Fi di casa e la batteria è sopra la soglia,
    anche fuori dalla fascia notte. Solo foto della fotocamera (pochi MB), **mai** video, **mai** dati mobili. Si realizza
    come `RuleSettings.protectNewPhotos` e richiesta `QUICK_NEW_PHOTOS` di `PHOTO_BACKUP`; il motivo è che Samsung "Salva"
    sovrascrive il file, e una foto modificata prima del backup notturno non arriverebbe mai originale sul NAS (E-B15, L38
    di `docs/sicurezza.md`). Costo: pochi secondi per foto. Il test `PresetMatrixTest` si estende con questa regola.

---

## 9. Edge case coperti dai test

| Edge case | Dove |
|---|---|
| E-R4 VPN su rete scadente | `reteScadente_*` (valutatore e matrice dei preset), `SystemStateProviderTest.laQualitaDellaRete_*` |
| E-R5 NAS spento nella fascia | `nasSpento_*`, `PresetMatrixTest.nasSpentoNellaNotte_*`, `NightWindows.derive` (`TimeWindowAndNightTest`) |
| E-R12 due reti contemporanee | `dueReti_siSegueQuellaEffettivamenteUsata_E_R12` |
| E-R13 Wi-Fi a consumo / pagina di accesso | `wifiAConsumo_*`, `wifiConPaginaDiAccesso_*`, `NetworkClassifierTest` |
| E-P5 cambio di fuso | `ClocksTest` |
| E-P10 risparmio energetico | `risparmioEnergetico_*`, `PresetMatrixTest.risparmioEnergetico_*` |
| E-P13 riavvio notturno | solo nelle stime: l'efficienza 0,8 di `UsageProfile` lo include; prima del primo sblocco l'app non gira (nessun componente direct-boot) |
| E-P14 limiti Android raggiunti | `limiteDiAndroidRaggiunto_*`, `quotaDeiJobEsaurita_*` |
| E-P16 carica ferma all'80% | `notte_caricaFermaAll80ConCavoCollegato_*`, `AndroidMappersTest`, `AndroidDeviceStateSourceTest` |
| E-P19 profilo non attivo | `profiloNonAttivo_*` (valutatore, matrice, motore) |
| E-B10 tetto raggiunto, "Fai ora" con conferma | `tettoDatiMobiliRaggiunto_*`, `faiOra_*` |
| E-A5 surriscaldamento (pausa e ripresa) | `termica*`, `SystemStateProviderTest.isteresiTermica_*` |
| E-A6 notte senza NAS | `calcoloAiSenzaNas_*`, `nasSpento_ilCalcoloAiLavoraSulBuffer_*` |
| Mezzanotte e fascia a cavallo | `TimeWindowAndNightTest`, `notte_confiniDellaFasciaALaMezzanotte` |
| Cambio mese (azzeramento) | `TrafficLedgerTest.cambioMese_*`, `ActivityRulesEngineTest.tettoDatiMobili_*` |
| Batteria che scende sotto soglia durante un lavoro | `laBatteriaScendeSottoSogliaDuranteUnLavoro_*` |
| Rete che cambia a metà | `laReteCambiaAMeta_*` |
| Tutti i preset | `PresetMatrixTest` (13 scenari x 5 preset), `PresetCatalogTest` |

## 10. Cosa non c'è (di proposito)

Nessuna UI, nessun worker, nessuna implementazione dei segnali della Fase 2, nessuna Hilt (il cablaggio è di
`:app`), nessuna notifica ("serve la carica da N giorni" è la proposta 12 e la fa la Diagnostica 2.7), nessuna
pianificazione degli orari (le soglie di tempo si rivalutano con il timer di `observeAll`).
