# HK - controllo della provenienza R8 (11 novembre 2026)

Base 261a3553, ramo fix/hk-controllo-r8, worktree mcg-k4. Nessun Gradle, dispositivo, download, push o modifica ai sorgenti dell'app/engine. Gli input sono copie sotto build/hk degli output di mcg-integra-beta.

## Diagnosi verificata

APK SHA256 A7F34D779FEB451EC4358DD168296E47EA725F0718CB5760A21D92FAAA2626D9; mapping SHA256 3DAF4EA6FCA681B732B6A7A8562899676DA20F85CD050C4F4A6F11ED1BD6932C. R8 8.13.19, pg_map_id 54e6b08d12287f8d00e8da99c93442e8f1f5f712e82ad319c26494611e7b4038, presente anche come source_file del dex.

* HTTPS: classes2.dex, offset file 5856b4, PC 0440, `HttpURLConnection.disconnect:()V`. La tabella posizioni riporta `0x0440 line=1089`. Mapping righe 730599-730600: intervallo 1087:1092, frame interno `HttpsModelFetcher$Attempt$abort$1.invokeSuspend`, sorgente 81; il frame esterno e' il metodo della classe fusa. Non e' CatalogPhoneFolders a usare HTTPS.
* Privacy: classes2.dex dump riga 4929555, offset 7b0b9e, PC 000d, posizione 14. Mapping 1028028-1028029: intervallo 14:17, frame interno `RecentsPrivacy.setRecentsScreenshotEnabled`, sorgente 42; esterno `setHidden`, sorgente 25. Il callee residuo e' `VisibleRegion$$ExternalSyntheticApiModelOutline0.m(Activity,boolean)`; il suo corpo (classes3.dex dump riga 1875597) chiama soltanto `Activity.setRecentsScreenshotEnabled`.
* Informazioni: classes3.dex dump riga 1037497, offset 1f60d2, PC 0057, posizione 88. Mapping 1235889-1235890: intervallo 80:92, frame interno `AboutScreenKt.appVersionName`, sorgente 267; esterno `AboutRoute`, sorgente 47. Il callee e' l'overload `m(PackageManager,String,PackageInfoFlags):PackageInfo`; corpo a riga 1875537: `PackageManager.getPackageInfo`.

Il solo nome del contenitore MapLibre non prova l'uso di MapLibre. Esiste anche un altro overload dello stesso outline che cita LatLng: non viene esentato, e i suoi riferimenti restano attribuiti al chiamante e soggetti alla policy.

Non si puo' attribuire causalmente lo spostamento delle fusioni a Z1 oppure a P2/startup profile con questi soli output. Servirebbe una comparazione controllata delle build intermedie; non e' stata richiesta una nuova release e non ne ho eseguite.

## Giro 2: diagnosi dei due prologhi

Le due classi residue sono classi delle librerie, non dell'app. Le origini qui elencate comprendono TUTTI i frame, anche quelli esterni delle catene inline, e la classe residua. Nessuna origine nostra compare in questi due metodi.

| Metodo residuo | Istruzione | Classi d'origine possibili dal mapping |
|---|---|---|
| `com.google.ai.edge.litert.ModelSelector.selectModel` | classes2.dex, file 407f8a, PC 0x15: `ModelSelector$selectModel$1.<init>(ModelSelector,Continuation)` | `com.google.ai.edge.litert.ModelSelector`; `kotlin.collections.CollectionsKt___CollectionsKt`; `kotlin.jvm.internal.Intrinsics` |
| `androidx.glance.appwidget.AppWidgetSession.processEmittableTree` | classes2.dex, file 3086e2, PC 0x1f: `AppWidgetSession$processEmittableTree$1.<init>(AppWidgetSession,Continuation)` | `androidx.glance.appwidget.AppWidgetSession`; `kotlin.coroutines.jvm.internal.Boxing` |

LiteRT: mapping 496941-496978. Il primo intervallo e' 29:101; i frame Kotlin sono a 496944/496953/496965 (`firstOrNull`) e 496956/496968/496970 (`areEqual`). Dexdump 1795487-1795502 mostra controllo del tipo della continuazione, `label`, creazione della continuazione e `result`; PC 0x15 ha posizione sintetica 22, fuori dagli intervalli sorgente.

Glance: mapping 333778-333806. Primo intervallo 39:99; `Boxing.boxBoolean` compare a 333780 e 333805, intervalli 106:108 e 334:336, sempre seguito dal frame esterno `processEmittableTree`. Dexdump 1009850-1009870 mostra lo stesso prologo; PC 0x1f ha posizione sintetica 32. Non e' una chiamata proveniente dall'app a Glance.

Le righe complete di mapping e dexdump sono in `tools/release/fixtures/hk/giro2-provenance.txt`. Comprendono anche la lambda realmente fusa `CatalogPhoneFolders$folders$2`: campo `$r8$classId`, scrittura nel costruttore, lettura a PC 0x02 di invokeSuspend e `packed-switch` a PC 0x10. I campi condivisi e il codice prima del dispatch non identificano quale origine e' attiva. L'unione conservativa proposta e' quindi corretta: non si sceglie una classe dal nome del contenitore o dal ramo dello switch.

## Regole implementate

| Regola | Prova richiesta / comportamento |
|---|---|
| Istruzione con posizione sorgente | Firma residuale e intervallo del mapping identificano il primo frame inline, quello interno. Intervalli incompatibili falliscono. |
| Istruzione senza posizione utilizzabile | Insieme di tutti i frame del metodo residuo con quella firma, piu' il contenitore residuo. Ogni origine deve soddisfare la policy; il FAIL nomina ciascuna origine vietata. Vale anche prima della prima voce della tabella delle righe. |
| Mapping senza origine | FAIL. La sola classe residua non autorizza il codice. |
| Filtri e controlli positivi | HTTPS/ML Kit, LiteRT e MapLibre mantengono il filtro originale su `dev.pampa` e engine. OkHttp e Glance mantengono il divieto globale. Le prove positive di presenza, montaggio e consumatori richiedono che TUTTI i candidati soddisfino lo stesso requisito. |
| Frame inline innocuo | Esclusione come autore della specifica invocazione soltanto dopo la prova descritta sotto; nessuna whitelist di classi Kotlin, Compose, DataStore o coroutine. Le origini `dev/` non ricevono questa esclusione. |
| Firma specializzata da R8 | Esenzione dal riferimento di firma solo con `residualsignature` esplicita, owner del callee esterno a Glance, e TUTTE le firme e origini originali del callee prive di Glance. Non esenta mai un'invocazione il cui owner e' Glance. |
| Outline API | Corpo leggibile, soli riferimenti di piattaforma senza client HTTPS. I corpi con librerie/rete restano attribuiti al chiamante. Corpo assente, chiamata indiretta o outline annidato non dimostrato falliscono. |
| Ponte lambda sintetica | Solo il ponte `$r8$lambda$` realmente invocato; il callee e' controllato a sua volta. Nessuna autorizzazione dal contenitore fuso. |

La prova per un candidato inline richiede tutte queste condizioni:

1. Ogni sua occorrenza nel metodo e' un frame inline, mai il frame esterno; firma originale senza Glance e intervalli effettivamente leggibili nel dex.
2. Nessuna invocazione attribuita a quel frame interno nomina Glance, salvo un riferimento di firma introdotto da R8 e dimostrato separatamente. I frame esterni di un callback non diventano autori delle istruzioni del callback interno.
3. Gli eventuali corpi originali residui dello stesso metodo sono letti anche se spostati da R8: nessuna loro istruzione puo' nominare Glance senza la medesima prova di specializzazione. Un corpo presente ma non leggibile non concede l'esclusione.
4. L'invocazione in esame ha un owner esterno al runtime Kotlin e una firma completa (owner incluso) che nomina Glance: non coincide con le invocazioni dei frame gia' provati innocui.

Le copie inline possono contenere cast generici e spill nei campi della continuazione ospite. Sono operazioni non `invoke`, quindi non possono essere l'invocazione in esame; il perimetro storico dell'ispezione e' sulle invocazioni. Nei corpi originali rimasti, invece, anche un semplice cast a Glance fa fallire la prova. I test coprono entrambi i casi. La prova vale anche per gli altri frame inline emersi dalla scansione completa (Compose, DataStore, coroutine), sempre leggendo il codice, senza autorizzarne i pacchetti per nome.

Esempio ulteriore realmente verificato: `LayoutStateDefinition.getDataStore`, PC 0x1e, invoca `androidx.datastore.core.FileStorage.<init>(LayoutProtoSerializer,Function0)`. Mapping 307958-307960: le firme originali usano `Serializer`; il metadato `residualsignature` introduce `LayoutProtoSerializer`. Il riferimento a Glance e' prodotto dalla specializzazione. La firma residuale viene propagata a tutti gli intervalli del metodo, non soltanto al primo.

## Esiti sull'identico APK

Prima = script 261a3553 eseguito di nuovo sulle copie; giro 1 = log congelato del commit e06f972a; giro 2 = codice aggiornato. Tutte le corse sono terminate. I log del giro 1 provano i corpi dei cancelli; nel giro 2 il lanciatore importa anche le liste globali dei membri e delle eccezioni preesistenti, cosi' esegue integralmente i relativi precontrolli.

| Ispezione | Prima | Giro 1 | Giro 2 |
|---|---|---|---|
| HTTPS / ML Kit, solo sezione bytecode 7e | FAIL: lambda fusa | OK | OK, 45 chiamate nostre |
| LiteRT / FaceLiteRtSessions | OK, 60 chiamate nostre | FAIL: origine indeterminata | OK, 60 chiamate nostre |
| OkHttp / MapLibre / installazione / inizializzazione | FAIL: due outline | OK | OK, 3065 OkHttp, 151 da net/, 162 nostre a MapLibre, installazione 1, init 1 |
| Glance / KT | OK | FAIL: origine indeterminata | OK, 9016 invocazioni, 84 sicuramente dal widget; due stringhe KT assenti |
| D12 / R2 / LA-5 | OK | OK | OK |
| D30 | OK | OK | OK |
| D34 | OK | OK | OK, sei consumatori originali di core/faces/sync |
| Classi testing / resti debug / D33 / valori recinzioni / classi di misura | OK, rieseguiti nel giro 2 | Non rieseguiti nel giro 1 | OK, cinque passi aggiuntivi |

Le differenze nei conteggi riflettono l'origine delle istruzioni e l'esclusione dei riferimenti di firma dimostrati. I byte dell'APK non sono cambiati. Nessun difetto dell'app dimostrato da queste ispezioni; nessun FAIL per origine sconosciuta sull'APK verificato.

Evidenze congelate in `tools/release/fixtures/hk/`: `before.log`, `after.log` e `synthetic.log` sono quelle del giro 1; `giro2-before.log`, `giro2-after.log`, `giro2-extra-before.log`, `giro2-extra.log`, `giro2-synthetic.log`, `giro2-provenance.txt`, `giro2-repo-guards.log` sono quelle aggiornate. Il log dopo contiene le prove dei frame esclusi, delle firme specializzate e gli insiemi che restano multipli ma interamente ammessi.

## Prove e riproduzione

- `powershell -NoProfile -File tools/release/r8-provenance-prove.ps1`: **64 prove sintetiche verdi**. Mapping e dexdump scritti a mano sotto build/prova-r8. Inclusi: tutte le origini ammesse; origine vietata nel prologo sotto nome ammesso; frame esterno inline vietato; contenitore residuo vietato; mapping senza origine; prima voce della tabella successiva all'istruzione; runtime innocuo; classe `kotlin.*` con invocazione Glance; corpo originale Kotlin con cast Glance; specializzazione innocua/reale/senza metadato; propagazione residualsignature; prova positiva universale.
- `powershell -NoProfile -File tools/release/r8-apk-prove.ps1 -All`: sette ispezioni di chiamanti/cancelli, tutte OK. Con `-Before -All` esegue le versioni originali, con i due FAIL attesi.
- `... -ExtraBytecode` e `... -Before -ExtraBytecode`: cinque ulteriori passi su dex/mapping/usage, tutti OK.
- `... -RepoGuards`: passi reali "repo senza segreti", "nessuna chiave privata" e "nessuna etichetta/query privata", tutti OK. Scandiscono anche i file di prova e il report aggiornati. Nessuna esclusione aggiunta per farli passare.
- Parsing PowerShell e `git diff --check`: OK.

Il lanciatore estrae via AST soltanto funzioni, assegnazioni pure necessarie e passi indicati. Non esegue `check.ps1` intero, Gradle o dispositivi. Richiede copie APK/mapping/usage/classes*.dex e dump sotto build/hk. `-BuildTools` permette di scegliere la cartella degli strumenti senza leggere local.properties.

## Stato, limiti e processi

Tutti i punti del giro 2 sono **fatti e verificati**. Il report e' stato spostato da tools/release a docs; docs/decisioni.md e' aggiornato. Nessuna policy globale Glance allentata, nessuna nuova eccezione nominale per Kotlin o altre librerie.

APK e dex immutati: classes.dex 6241908, classes2.dex 11387628, classes3.dex 4513032 byte; totale 22142568. Nessuna regola R8 cambiata senza misura. Nessun sorgente dell'app o engine modificato. Nessun rischio aggiunto alle prestazioni dell'app; il controllo sul PC conserva un indice di mapping/posizioni/corpi necessario alle prove.

Restano fail-closed mapping assenti, firme non risolte, intervalli incompatibili, corpi necessari non leggibili e ogni candidato vietato che non supera una prova specifica. Il check completo con Gradle resta all'integrazione: questo report qualifica le sezioni eseguite sull'APK esistente. Non distingue causalmente Z1 da P2/startup profile. Nessuno screenshot o test sul telefono necessario, nessuna scelta visiva o decisione di prodotto aperta.

Nessun Gradle: nessun batch/semaforo e nessuna riga da inoltrare al Coordinatore. Nessun adb, dispositivo, download, push o lettura di local.properties. Non ho usato Stop-Process o taskkill, neppure fra 14:40 e 14:50. Il codice chiude, in caso di errore, soltanto gli oggetti Process dexdump che ha avviato nel proprio albero. Nessuna chiusura per nome e nessun processo di altri agenti terminato.
