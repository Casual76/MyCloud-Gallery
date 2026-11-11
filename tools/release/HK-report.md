# HK - controllo della provenienza R8 (11 novembre 2026)

Base 261a3553, ramo fix/hk-controllo-r8, worktree mcg-k4. Nessun Gradle, dispositivo, download, push o modifica ai sorgenti dell'app/engine. Gli input sono copie sotto build/hk degli output di mcg-integra-beta.

## Diagnosi verificata

APK SHA256 A7F34D779FEB451EC4358DD168296E47EA725F0718CB5760A21D92FAAA2626D9; mapping SHA256 3DAF4EA6FCA681B732B6A7A8562899676DA20F85CD050C4F4A6F11ED1BD6932C. R8 8.13.19, pg_map_id 54e6b08d12287f8d00e8da99c93442e8f1f5f712e82ad319c26494611e7b4038, presente anche come source_file del dex.

* HTTPS: classes2.dex, offset file 5856b4, PC 0440, `HttpURLConnection.disconnect:()V`. La tabella posizioni riporta `0x0440 line=1089`. Mapping righe 730599-730600: intervallo 1087:1092, frame interno `HttpsModelFetcher$Attempt$abort$1.invokeSuspend`, sorgente 81; il frame esterno e' il metodo della classe fusa. Non e' CatalogPhoneFolders a usare HTTPS.
* Privacy: classes2.dex dump riga 4929555, offset 7b0b9e, PC 000d, posizione 14. Mapping 1028028-1028029: intervallo 14:17, frame interno `RecentsPrivacy.setRecentsScreenshotEnabled`, sorgente 42; esterno `setHidden`, sorgente 25. Il callee residuo e' `VisibleRegion$$ExternalSyntheticApiModelOutline0.m(Activity,boolean)`; il suo corpo (classes3.dex dump riga 1875597) chiama soltanto `Activity.setRecentsScreenshotEnabled`.
* Informazioni: classes3.dex dump riga 1037497, offset 1f60d2, PC 0057, posizione 88. Mapping 1235889-1235890: intervallo 80:92, frame interno `AboutScreenKt.appVersionName`, sorgente 267; esterno `AboutRoute`, sorgente 47. Il callee e' l'overload `m(PackageManager,String,PackageInfoFlags):PackageInfo`; corpo a riga 1875537: `PackageManager.getPackageInfo`.

Il solo nome del contenitore MapLibre non prova l'uso di MapLibre. Esiste anche un altro overload dello stesso outline che cita LatLng: non viene esentato, e i suoi riferimenti restano attribuiti al chiamante e soggetti alla policy.

Non si puo' attribuire causalmente lo spostamento delle fusioni a Z1 oppure a P2/startup profile con questi soli output. Servirebbe una comparazione controllata delle build intermedie; non e' stata richiesta una nuova release e non ne ho eseguite.

## Regole

| Regola | Accetta | Rifiuta / fa fallire |
|---|---|---|
| Provenienza istruzione | Posizione dex associata a intervallo mapping della firma residuale; primo frame inline (interno) | Mapping assente, firma sconosciuta, intervalli sovrapposti con origine non dimostrabile |
| Posizioni assenti | Una sola identita' originale dimostrata per quella firma | Metodo con piu' origini senza posizione utilizzabile |
| Outline API | Corpo presente e tutti i riferimenti di piattaforma android/java/javax/kotlin, senza client HTTPS; anche un no-op privo di riferimenti | Corpo assente, chiamata indiretta o outline annidato non dimostrato |
| Outline con librerie/rete | Nessuna esenzione: il corpo e' aggiunto ai riferimenti da controllare con l'origine del chiamante | Violazioni OkHttp/MapLibre/Glance/HTTPS restano problemi del chiamante |
| Ponte lambda | Solo una ExternalSyntheticLambda che invoca esattamente un `$r8$lambda$`: la firma e' attribuita al callee del ponte, il cui corpo e' controllato a sua volta | Nessuna autorizzazione derivata dal nome residuo della lambda; un callee vietato resta vietato |
| Recinzioni | Origine della singola invocazione/istanza/lettura del risultato | Rimossa l'autorizzazione basata sull'unione delle origini dell'intero metodo fuso |

Le firme sono ricavate dal mapping e dal metadato residualsignature; le catene inline con lo stesso intervallo vengono trattate come catene, non come origini alternative. La cache distingue il contenuto dei dex tramite SHA256. I processi dexdump sono chiusi anche quando la risoluzione lancia un errore.

## Ispezioni esposte

| Ispezione | Falso positivo precedente | Falso negativo precedente | Correzione |
|---|---|---|---|
| 7e HTTPS | Origine ammessa nascosta sotto classe vietata (caso reale) | Codice nostro nascosto sotto classe ammessa o libreria ignorata | Origine dell'istruzione prima del filtro codice nostro e dei prefissi ammessi |
| 7e ML Kit initialize | Bootstrap fuso altrove | Chiamante vietato sotto Bootstrap o sotto libreria ignorata | Stessa risalita |
| 7f LiteRT / FaceLiteRtSessions | Sessione fusa altrove | Codice esterno sotto core-ai/sessioni o libreria ignorata | Stessa risalita |
| 7h OkHttp, installazione client, MapLibre/getInstance | Outline API; codice ammesso fuso altrove | Codice vietato sotto net/MapLibre/initializer | Corpo outline e origine; controlli positivi mantenuti |
| 7i Glance | Widget/kit fuso altrove, outline | Chiamante vietato sotto widget/kit/Glance | Stessa logica |
| D12/R2/LA-5, D30, D34 | Montaggio/consumatore del cancello fuso altrove | Una chiamata estranea nel medesimo metodo fuso poteva costituire falsa prova del montaggio/consumatore | Origine per istruzione, senza unione di metodi |

D33 (assenza classi in mapping/usage), valori delle recinzioni, manifest, risorse, ABI e profili non autorizzano per classe chiamante e non richiedono questa risalita.

## Prove ripetibili

`powershell -NoProfile -File tools/release/r8-provenance-prove.ps1`: 43 prove sintetiche verdi, con mapping e righe dexdump scritti a mano sotto build/prova-r8. Coprono entrambi i versi della fusione per tutti i prefissi, inline, overload, assenza/ambiguita', outline innocui/pericolosi/assenti/annidati e ponti ammessi/vietati.

`tools/release/r8-apk-prove.ps1` carica le funzioni e soltanto i passi indicati via AST: non esegue check.ps1 intero, Gradle o dispositivi. Richiede copie APK/mapping/classes*.dex e relativi dump sotto build/hk; per `-Before` legge check.ps1 da 261a3553. `-All` include D12/D30/D34, LiteRT e Glance. La parte 7e eseguita e' esplicitamente soltanto il bytecode, non manifest/ABI/modelli.

Log conservati in `tools/release/fixtures/hk/{before,after,synthetic}.log`.

| Sezione eseguita sull'APK | Prima | Dopo |
|---|---|---|
| 7e solo bytecode ML Kit/HTTPS | FAIL: CatalogPhoneFolders$folders$2.invokeSuspend, HttpURLConnection | OK, 45 chiamate nostre (19,1 s) |
| 7h OkHttp/MapLibre | FAIL: RecentsPrivacy.setHidden e AboutRoute, prefisso MapLibre | OK: 3065 chiamate OkHttp, 151 da net/, 162 nostre a MapLibre, client installato 1, init 1, libreria nativa presente (20,4 s) |
| D12/R2/LA-5 | Non rieseguito prima | OK (54,4 s) |
| D30 | Non rieseguito prima | OK (54,3 s) |
| D34 | Non rieseguito prima | OK, sei consumatori originali di core/faces/sync (46,1 s) |
| LiteRT | Non rieseguito prima | FAIL chiuso: ModelSelector.selectModel PC 0x15 / posizione 22, origine indeterminata (8 s) |
| Glance | Non rieseguito prima | FAIL chiuso: AppWidgetSession.processEmittableTree PC 0x1f / posizione 32, origine indeterminata (7,6 s) |

**Il pacchetto non da' il via libera al rilascio.** L'estensione a tutte le ispezioni e' implementata, ma la verifica complessiva resta parziale: gli ultimi due FAIL sono bloccanti, non trasformati in esenzioni. Le istruzioni chiamano rispettivamente i costruttori di `ModelSelector$selectModel$1` e `AppWidgetSession$processEmittableTree$1`; si trovano nel prologo delle coroutine, prima degli intervalli sorgente mappati (Glance inizia a posizione 39). Il mapping contiene piu' frame originali nel metodo, quindi la risalita per quella precisa istruzione non e' dimostrata. Il nome del contenitore o del costruttore non e' stato usato come autorizzazione alternativa.

Stato delle voci del brief: diagnosi **fatta**; correzione delle due ispezioni originali **fatta e provata**; estensione a tutti i chiamanti **fatta in parte** (codice applicato, due origini reali restano non dimostrate e correttamente bloccanti); prove sintetiche e prima/dopo **fatti**; alternativa tramite regole R8 **non applicata**, in assenza della misura richiesta.

Durante lo sviluppo le prove hanno segnalato e fatto correggere la selezione per firma, gli overload non puramente Android e i ponti sintetici. Un giro esteso precedente si e' interrotto prima del report completo; non e' contato come successo. La corsa finale e' terminata con codice 1 per i due FAIL espliciti sopra. Nessun comando rimasto in esecuzione.

## Limiti e rilascio

Nessuna regola R8 applicata o proposta come stabile senza misura. DEX immutati: classes.dex 6241908, classes2.dex 11387628, classes3.dex 4513032 byte, totale 22142568. Nessuna modifica al comportamento o alle prestazioni dell'app. Il controllo richiede piu' memoria/tempo di analisi sul PC; l'indice viene condiviso tra le ispezioni dello stesso APK.

I formati non dimostrati falliscono chiuso; outline annidati non vengono autorizzati. Le analisi di chiamanti non sostituiscono una dimostrazione di controllo del flusso del cancello: mantengono la forza della verifica esistente, correggendone l'identita'. Il check completo rimane compito dell'integrazione. Nessun test sul telefono o screenshot necessario per questa modifica agli strumenti; nessuna scelta visiva o decisione di prodotto aperta. Nessun batch Gradle: nessuna riga di esito da inoltrare al Coordinatore.
