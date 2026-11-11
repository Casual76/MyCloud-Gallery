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
| Istruzione con posizione sorgente | Firma residuale e intervallo del mapping identificano la catena inline: dal frame interno si cerca il primo autore nostro (`dev.pampa` oppure engine); le librerie sono trasparenti. In assenza di frame nostri vale il frame interno (CR-3, giro 3). Intervalli incompatibili falliscono. |
| Istruzione senza posizione utilizzabile | Insieme di tutti i frame del metodo residuo con quella firma, piu' il contenitore residuo. Ogni origine deve soddisfare la policy; il FAIL nomina ciascuna origine vietata. Vale anche prima della prima voce della tabella delle righe. |
| Mapping senza origine | FAIL. La sola classe residua non autorizza il codice. |
| Filtri e controlli positivi | HTTPS/ML Kit, LiteRT e MapLibre mantengono il filtro originale su `dev.pampa` e engine. OkHttp e Glance mantengono il divieto globale. Le prove positive di presenza, montaggio e consumatori richiedono che TUTTI i candidati soddisfino lo stesso requisito. |
| Frame inline innocuo | Esclusione come autore della specifica invocazione soltanto dopo la prova descritta sotto; nessuna whitelist di classi Kotlin, Compose, DataStore o coroutine. Le origini `dev/` non ricevono questa esclusione. |
| Firma specializzata da R8 | Esenzione dal riferimento di firma solo con `residualsignature` esplicita, owner del callee esterno a Glance, e TUTTE le firme e origini originali del callee prive di Glance. Non esenta mai un'invocazione il cui owner e' Glance. |
| Outline API | Corpo leggibile, soli riferimenti di piattaforma senza client HTTPS. I corpi con librerie/rete restano attribuiti al chiamante. Corpo assente, chiamata indiretta o outline annidato non dimostrato falliscono. |
| Origine sintetica (CR-6) | Un autore `$$ExternalSyntheticLambdaN` o marcato `com.android.tools.r8.synthesized` e' indeterminato e fa fallire il passo, anche con intervallo 0:0 o nome ammesso. Nessuna autorizzazione dal gruppo fuso. |
| Ponte lambda e inoltro verificato | Resta il ponte `$r8$lambda$` realmente invocato. I corpi sintetici di solo inoltro si risolvono al metodo ordinario nella stessa classe originale soltanto con la prova su dichiarazione, ingresso, corpo e mapping descritta nel giro 3. |

La prova per un candidato inline richiede tutte queste condizioni:

1. Ogni sua occorrenza nel metodo e' un frame inline, mai il frame esterno; firma originale senza Glance e intervalli effettivamente leggibili nel dex.
2. Nessuna invocazione attribuita a quel frame interno nomina Glance, salvo un riferimento di firma introdotto da R8 e dimostrato separatamente. I frame esterni di un callback non diventano autori delle istruzioni del callback interno.
3. Gli eventuali corpi originali residui dello stesso metodo sono letti anche se spostati da R8: nessuna loro istruzione puo' nominare Glance senza la medesima prova di specializzazione. Un corpo presente ma non leggibile non concede l'esclusione.
4. L'invocazione in esame ha un owner esterno al runtime Kotlin e una firma completa (owner incluso) che nomina Glance: non coincide con le invocazioni dei frame gia' provati innocui.

Le copie inline possono contenere cast generici e spill nei campi della continuazione ospite. Sono operazioni non `invoke`, quindi non possono essere l'invocazione in esame; il perimetro storico dell'ispezione e' sulle invocazioni. Nei corpi originali rimasti, invece, anche un semplice cast a Glance fa fallire la prova. I test coprono entrambi i casi. La prova vale anche per gli altri frame inline emersi dalla scansione completa (Compose, DataStore, coroutine), sempre leggendo il codice, senza autorizzarne i pacchetti per nome.

Esempio ulteriore realmente verificato: `LayoutStateDefinition.getDataStore`, PC 0x1e, invoca `androidx.datastore.core.FileStorage.<init>(LayoutProtoSerializer,Function0)`. Mapping 307958-307960: le firme originali usano `Serializer`; il metadato `residualsignature` introduce `LayoutProtoSerializer`. Il riferimento a Glance e' prodotto dalla specializzazione. La firma residuale viene propagata a tutti gli intervalli del metodo, non soltanto al primo.

## Esiti sull'identico APK

Prima = script 261a3553 eseguito di nuovo sulle copie; giro 1 = log congelato del commit e06f972a; giro 2 = commit d548d057; giro 3 = correzioni CR-3 e CR-6. Tutte le corse sono terminate. I log del giro 1 provano i corpi dei cancelli; nel giro 2 il lanciatore importa anche le liste globali dei membri e delle eccezioni preesistenti, cosi' esegue integralmente i relativi precontrolli.

| Ispezione | Prima | Giro 1 | Giro 2 | Giro 3 |
|---|---|---|---|---|
| HTTPS / ML Kit, solo sezione bytecode 7e | FAIL: lambda fusa | OK | OK, 45 nostre | OK, 45 nostre |
| LiteRT / FaceLiteRtSessions | OK, 60 nostre | FAIL: origine indeterminata | OK, 60 nostre | OK, 60 nostre |
| OkHttp / MapLibre / installazione / inizializzazione | FAIL: due outline | OK | OK, 3065 OkHttp, 151 net/, 162 nostre MapLibre, installazione 1, init 1 | OK, 3065 OkHttp, 151 net/, 163 nostre MapLibre, installazione 1, init 1 |
| Glance / KT | OK | FAIL: origine indeterminata | OK, 9016 invocazioni, 84 widget, KT assenti | OK, 9016 invocazioni, 84 widget, KT assenti |
| D12 / R2 / LA-5 | OK | OK | OK | OK |
| D30 | OK | OK | OK | OK |
| D34 | OK | OK | OK, sei consumatori | OK, sei consumatori |
| Classi testing / resti debug / D33 / valori recinzioni / classi di misura | OK, rieseguiti nel giro 2 | Non rieseguiti nel giro 1 | OK, cinque passi | OK, cinque passi |

Le differenze nei conteggi riflettono l'origine delle istruzioni e l'esclusione dei riferimenti di firma dimostrati. I byte dell'APK non sono cambiati. Nessun difetto dell'app dimostrato da queste ispezioni; nessun FAIL per origine sconosciuta sull'APK verificato.

Evidenze congelate in `tools/release/fixtures/hk/`: `before.log`, `after.log` e `synthetic.log` sono quelle del giro 1; `giro2-before.log`, `giro2-after.log`, `giro2-extra-before.log`, `giro2-extra.log`, `giro2-synthetic.log`, `giro2-provenance.txt`, `giro2-repo-guards.log` sono quelle aggiornate. Il log dopo contiene le prove dei frame esclusi, delle firme specializzate e gli insiemi che restano multipli ma interamente ammessi.

## Prove e riproduzione

- `powershell -NoProfile -File tools/release/r8-provenance-prove.ps1`: **120 prove sintetiche verdi dopo il completamento della review** (116 al giro 3, 64 al giro 2). Mapping e dexdump scritti a mano sotto build/prova-r8. Inclusi: tutte le origini ammesse; origine vietata nel prologo sotto nome ammesso; frame esterno inline vietato; contenitore residuo vietato; mapping senza origine; prima voce della tabella successiva all'istruzione; runtime innocuo; classe `kotlin.*` con invocazione Glance; corpo originale Kotlin con cast Glance; specializzazione innocua/reale/senza metadato; propagazione residualsignature; prova positiva universale.
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


## Giro 3 - CR-3

Corretto il falso negativo del frame di libreria inline dentro codice nostro vietato. `PolicyOrigin` scorre la catena dall'interno e sceglie il primo frame nostro; in assenza di frame nostri conserva il frame interno. Le policy esistenti applicano a quell'autore i prefissi ammessi, anche per OkHttp e Glance globali. Senza posizione resta l'unione conservativa di tutti i frame e del contenitore: nessuna origine nostra viene eliminata dalla prova del runtime.

Nuove prove con dati scritti a mano, eseguite sui cicli reali di check.ps1 estratti via AST: per HTTPS, OkHttp, Glance, LiteRT e MapLibre cadono libreria in codice nostro vietato, due librerie in codice nostro vietato, libreria in engine vietato e prologo con origine nostra vietata; passa l'aiutante nostro ammesso inline in un chiamante nostro ammesso. La prima prova HTTPS cade sul codice d548d057, poi tutte le 89 prove passano. Questo e' l'esito intermedio del commit fdfd5351; la verifica finale e' riportata nel giro 3 CR-6.


## Giro 3 - CR-6 e verifica finale

CR-6 corregge l'autorizzazione dei gruppi sintetici privi di un autore sorgente: il nome di una classe ammessa non basta. Il parser conserva i metadati synthesized di classi originali e singoli frame. Un metadato della classe sorgente sintetica non contamina altre classi reali confluite nello stesso contenitore. Il controllo si applica sia alla provenienza con posizione sia a tutti i candidati dei prologhi, prima di qualsiasi prova del runtime. Il ponte preesistente resta separato: il codice del suo callee viene a sua volta ispezionato.

La regressione del riferimento `client::newCall` in `MapTileHttp$$ExternalSyntheticLambda0` e' riprodotta sul codice precedente a CR-6. Sono provati separatamente il riconoscimento per nome, per metadato di classe e per metadato di metodo, con e senza posizione. Il riferimento diretto cade, il ponte ammesso passa e quello con autore nostro vietato resta vietato.

### Artefatti effettivi dell'APK e prova di inoltro

Applicando il divieto ai metodi sintetici sono emersi corpi di ponte realmente generati da R8. Non sono difetti dell'app. Esempi osservati:

- `androidx.glance.session.TimeSource$Companion.$r8$lambda$_XVgIMcP0eHGcKJ-uDdxtDVJU9E`: mapping 348537-348541; corpo classes2.dex dump 1172973: unico invoke-static a `Monotonic$lambda$0`, originale della stessa classe, riga 48 di TimerScope.kt.
- `okhttp3.internal.Util.$r8$lambda$6bJzZULcdNB_k9pZZ3Jli9KIJPU`: classes.dex dump 2410429; inoltra a `asFactory$lambda$8`. Il metodo ordinario originale del jar e' mappato a 1415177 con linea 0: manca la riga debug, ma l'identita' della classe originale e del metodo ordinario e' definita e non e' un gruppo R8 sintetico.
- I ponti di `EngineWidgetComponentsKt`, anche nella variante invoke-static/range, inoltrano a funzioni della stessa classe. I frame di GapComposer in `EngineWidgetMessage$lambda$0` (mapping 658760-658766) sono trasparenti verso il primo frame nostro, coerentemente con CR-3.
- `org.maplibre.android.attribution.AttributionMeasure.-$$Nest$mgetLogoContainerWidth`: mapping 1428746; corpo classes.dex dump 2446593: unico invoke-direct al metodo ordinario `getLogoContainerWidth`, mapping 1428770, riga 182 di AttributionMeasure.java. E' un accessore privato di desugaring.
- `AttributionMeasure$FullLogoLongTextCommand.<init>(int)`: mapping 1428800-1428802; costruttore sintetico che inoltra al costruttore ordinario senza parametro, riga 51 di AttributionMeasure.java.

Un ulteriore accessore, `NativeHttpRequest.-$$Nest$mnativeOnResponse`, prepara lo stato 200 e valori null, poi inoltra al metodo PRIVATE NATIVE `nativeOnResponse` della stessa classe. Quel metodo nativo mantenuto non ha una riga di metodo nel mapping: si verifica la dichiarazione nel dex e l'identita' originale invariata della classe, non il nome dell'accessore.

Non c'e' una whitelist di questi nomi. Un inoltro si accetta solo se:

1. Ha una dichiarazione sintetica nel mapping e un ingresso statico o direct effettivamente visto nel dex. Anche l'accesso nel dex deve avere SYNTHETIC: fuori dai costruttori sono richiesti STATIC e BRIDGE, oppure il ponte R8 gia' previsto. Un accessore generico, senza tale ponte R8, deve raggiungere un membro PRIVATE: non puo' prestare il proprio nome a un riferimento a un'API pubblica. Un metodo instance funzionale non riceve questa prova. Il chiamante dell'ingresso continua a essere controllato dalla propria policy. L'ingresso puo' sopravvivere anche se la lambda e' gia' stata inlinata.
2. La classe ospite originale e' reale: non un gruppo ExternalSyntheticLambda, non una classe marcata synthesized. TUTTI i frame della dichiarazione del ponte coincidono col metodo originale nella stessa classe: un ponte spostato da una classe vietata non eredita il nome ammesso.
3. Il corpo contiene esattamente un invoke static/direct/virtual (anche range) a un metodo della stessa classe e soltanto movimenti, costanti primitive, nop o ritorno. Nessun campo condiviso, classId, dispatch, costruzione o seconda chiamata. Una chiamata diretta alla libreria di un'altra classe non riceve questa prova.
4. La firma del destinatario trova nel mapping un frame esterno ordinario nella stessa classe originale. L'inoltro recupera la dichiarazione ordinaria, cioe' i frame esterni del destinatario. Non attribuisce l'invocazione agli autori di altre istruzioni nel suo corpo: un getter GeoE6 o la matematica di GeoGrid non sono l'invocazione del ponte. Ogni istruzione sorvegliata nel corpo ordinario continua invece a seguire CR-3. Una prova sintetica esegue entrambe le policy: l'inoltro e' ammesso e la vera chiamata API nel corpo, scritta da un autore vietato, cade.
5. Per un metodo JNI assente dal mapping serve invece una dichiarazione PRIVATE NATIVE, non SYNTHETIC, realmente letta nel dex; la classe ospite deve avere una sola identita' originale e mantenerla invariata. La prova non vale per una dichiarazione senza NATIVE o per un alias di classe ambiguo.

Questa prova recupera un autore definito dal codice e dal mapping; non autorizza il nome di un gruppo sintetico. I casi non dimostrabili restano FAIL CR-6.

### Prove del giro 3

120 prove sintetiche verdi dopo il completamento della review: le 64 precedenti, 25 prove CR-3 sui cicli reali delle cinque policy e 31 prove CR-6 (27 nel commit del giro 3, quattro aggiunte dopo la review). Incluse catene libreria-libreria-nostro vietato, codice engine vietato, aiutanti nostri ammessi, tutti i candidati nei prologhi, tre modi di riconoscere sintetici, falso riferimento a metodo sotto nome ammesso, ponti ammessi/vietati/spostati, ingresso inlinato nel chiamante, range, accessori senza nomi speciali, costruttori con o senza classId, esclusione dei metodi instance funzionali, destinazioni JNI private verificate/non native e coerenza CR-3/CR-6 dentro il destinatario di un ponte.

Le nuove prove invocano i cicli effettivi di check.ps1 estratti via AST, con liste di policy lette dallo script; non si limitano al confronto di una stringa di origine. `giro3-cr3-red.log` prova il fallimento prima della correzione; `giro3-cr3-green.log` le 89 prove del primo commit. `giro3-cr6-red.log` prova il caso scoperto per CR-6; `giro3-synthetic.log` contiene la suite finale.

**CR-3: corretto**, commit fdfd5351. **CR-6: corretto**, secondo commit dedicato del giro 3. Tutte le sette ispezioni di chiamanti/cancelli passano sull'APK vero (uscita 0): D12/R2/LA-5 16 s, D30 13,8 s, D34 13,4 s, HTTPS/ML Kit 12 s, LiteRT 11,4 s, OkHttp/MapLibre 13,2 s, Glance/KT 13,4 s. Passano anche i cinque passi aggiuntivi sul bytecode. Nessun difetto dell'app dimostrato e nessun FAIL indeterminato residuo sull'APK attuale.

Le 120 prove sintetiche sono verdi. Le regressioni del giro 3 sono state viste rosse prima della correzione; restano archiviate come prove, non come difetti dell'app. Gli artefatti intermedi incontrati durante la verifica (accessori, costruttori e inoltri JNI) sono stati risolti con le prove sopra, senza whitelist di classi.

I log aggiornati sono `giro3-after.log`, `giro3-extra.log`, `giro3-synthetic.log`, `giro3-cr6-red.log`, `giro3-forwarders.txt` e `giro3-repo-guards.log` sotto tools/release/fixtures/hk. Le impronte di APK e mapping sono state ricontrollate dopo il riavvio: coincidono con gli output originali in sola lettura e con le copie in build/hk. Non e' stata ricompilata la release.

Restano conservativi gli inoltri che non soddisfano interamente la prova strutturale; non si autorizzano funzioni sintetiche generiche, alias nativi ambigui o dichiarazioni senza PRIVATE NATIVE. Questa verifica riguarda il chiamante e gli artefatti esaminati: non estende la risalita dei nomi residui dei callee oltre le regole gia' presenti, rischio teorico distinto segnalato dal CR.

Il riavvio esterno dell'ambiente ha interrotto una corsa: non e' contata come esito. Nessun comando Gradle o dispositivo, nessuna modifica all'app/engine o alle regole R8. Nessun push e nessun processo altrui terminato. Le sole terminazioni nel codice riguardano i Process dexdump avviati dall'ispezione. Rimangono i limiti gia' dichiarati del controllo e il check completo da eseguire all'integrazione.

### Completamento dopo la review CR, giro 2

Aggiunte quattro prove negative dell'inoltro JNI: destinazione NATIVE pubblica, protetta, senza modificatore di accesso e PRIVATE NATIVE SYNTHETIC. Le prime tre usano invoke-virtual; la quarta invoke-direct. Ogni prova richiede il rifiuto specifico CR-6 per origine sintetica senza autore sorgente: un errore diverso non la fa passare. Restano verdi le prove positive PRIVATE NATIVE e i rifiuti senza NATIVE e con identita' di classe ambigua. Il controllo non cambia: questi casi erano gia' rifiutati dal codice.

Suite sintetica completa: **120/120 OK**, uscita 0. Rilanciate le sette ispezioni sull'APK vero, con impronte di APK e mapping riconfermate uguali agli originali in sola lettura: **7/7 OK**, uscita 0. D12/R2/LA-5 25,1 s; D30 19 s; D34 19,8 s; HTTPS/ML Kit 18 s; LiteRT 18,6 s; OkHttp/MapLibre 21,7 s; Glance/KT 25,5 s. Le prove del codice restano 45 HTTPS/ML Kit, 60 LiteRT, 3065 OkHttp (151 da net/), 163 nostre MapLibre e 9016 Glance (84 da widget).

Evidenze aggiunte sotto tools/release/fixtures/hk: `jni-non-privato-synthetic.log` e `jni-non-privato-apk.log`. Nessun Gradle, dispositivo, push o modifica al controllo, ai sorgenti dell'app o a engine/.
