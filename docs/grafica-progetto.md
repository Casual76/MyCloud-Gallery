# Revisione grafica della Libreria (G1): diagnosi, principi, tre direzioni

> Tappa G1 del filone grafico (`docs/decisioni.md`, "Revisione grafica della Libreria", 13/10). Non è la riscrittura
> finale: sono **tre prototipi veri**, selezionabili in debug, fotografati sull'emulatore e misurati, perché l'utente
> scelga guardandoli.
>
> - Prototipi: menu della vista della Libreria → "Prototipo grafico: attuale / A / B / C" (solo debug, accanto a
>   "Sintetica: 100.000"), oppure da `adb` scrivendo `A`, `B`, `C` o `attuale` in
>   `/sdcard/Android/data/dev.pampa.mycloudgallery.debug/files/g1-design` (il file deve esistere già: lo crea l'app al
>   primo avvio). Valgono sulla Libreria vera e su quella sintetica. **Il default resta la Libreria attuale**, e in
>   release i prototipi non sono raggiungibili (le manopole di debug sono nulle).
> - Screenshot: `docs/screenshots/G1/` (locali: sei campagne con il dump di accessibilità accanto a ogni scatto e
>   `contrasti.json`, più `sintetica/` sulla Libreria sintetica), selezione di 18 in `docs/screenshots/selezione/G1/`,
>   pagina di confronto `docs/screenshots/selezione/G1/confronto.html`.
> - Strumenti, nel repository in `docs/screenshots/G1/strumenti/` (gli scatti restano locali, vedi il `.gitignore` della
>   cartella): `genera-media.py` (il contenuto peggiore, solo per l'emulatore), `campagna.py` (gli scatti),
>   `misura-contrasto.py` (il contrasto vero sui pixel), `gfxinfo.py`.
> - Fluid Engine 2.13.0. Nessuna modifica all'engine: quello che servirebbe è nel §7, come proposta.

## 0. In breve

Le quattro osservazioni dell'utente hanno **una causa comune**: la Libreria ha preso la grammatica di una pagina di
impostazioni (una barra di vetro a tutta larghezza, il testo del tema sopra) e l'ha messa sopra **foto**, che non
seguono il tema e possono essere bianche, nere o sature. In più ha impilato quattro strati flottanti (barra,
intestazione agganciata in vetro, segmenti, pillola, più "Oggi" e lo scrubber) che si toccano.

Misurato sui pixel dell'emulatore, con contenuto peggiore generato apposta (§1.5): nella Libreria attuale **nessuna
scritta sopra il vetro arriva a 4,5:1 nel caso peggiore**. La data agganciata, i segmenti e le etichette della pillola
scendono sotto 1,35:1 (il glifo non si distingue dal fondo), il titolo "Libreria" a 3,7:1 in chiaro e 1,9:1 in scuro, le
icone viola sulle lenti a 1,4:1. Nelle tre direzioni **tutto** il testo misurato è sopra 4,5:1 e ogni icona sopra 3:1,
in chiaro e in scuro (§5).

Le tre direzioni hanno in comune la struttura che risolve i problemi 1 e 2:

- **in alto una sola riga di chrome**: la data della sezione in vista *è* l'intestazione agganciata (niente seconda
  fascia di vetro), a destra "vai a oggi", "seleziona" e il menu della vista, che ora porta anche i **livelli**;
- **in basso solo la pillola**; in selezione la barra delle azioni prende **il suo posto**, alla stessa altezza, e la
  griglia non si sposta;
- **un fondale di vetro a schermo intero in meno** (lo scrubber è disegnato fuori dal fondale della pagina, la griglia
  non registra più il suo);
- nessun controllo tocca un altro e ogni bersaglio è di 48 dp (test dei bordi, §3).

E differiscono nel trattamento del vetro e del testo:

| | Idea | Contrasto peggiore misurato (chiaro / scuro) |
|---|---|---|
| **A, sopra le foto** | La chrome di un visore: sfumatura nera in alto e testo bianco, in chiaro e in scuro; capsula di vetro scuro; parete di foto squadrata | titolo 5,5 / 5,6 · comandi 12,1 / 12,1 · azioni 3,3 / 14,1 · pillola 12,2 / 4,7 |
| **B, barra solida** | Una barra opaca del colore della pagina, con "Libreria", il livello come etichetta e la data agganciata nella barra; la pillola posa su una fascia opaca. Il vetro resta solo alla pillola | titolo 16,5 / 17,2 · data 4,9 / 8,3 · comandi 15,9 / 12,7 · azioni 4,6 / 5,0 · pillola 12,3 / 11,2 |
| **C, vetro adattivo** | Due oggetti di vetro (la data, i comandi) su una sfumatura leggera del colore della pagina; pellicola e inchiostro **scelti dal contenuto che passa sotto**, con l'opacità minima che tiene 4,5:1 | titolo 5,6 / 5,9 · comandi 5,6 / 5,1 · azioni 10,5 / 14,3 · pillola 8,9 / 6,8 |

**Raccomandazione: A** come base, con due prestiti (§8). È la più vicina a Foto (che l'utente cita), quella con il
contrasto più stabile fra le due con il vetro (la chrome in alto non dipende dalla foto che passa), la più economica per
la GPU dopo B, e l'unica in cui la grafica "sa" di galleria e non di impostazioni. B è la scelta sicura se l'utente vuole il massimo della leggibilità e il minimo del vetro; C è la più
"Liquid Glass", ma il suo vetro cambia colore mentre si scorre ed è quella con più cose da tarare.

## 1. Diagnosi

### 1.1 I segmenti sopra la pillola sono di troppo

- **Dove**: `LibraryTab.kt` (`LibraryBottomChrome`, con `TodaySlot` sopra i segmenti) e `GalleryGridLevelBar` in
  `core-designsystem/.../grid/GalleryGridChrome.kt`; la pillola è in `app/.../main/MainScaffold.kt`.
- **Perché succede**: il §2.4 della Fase 3 voleva i segmenti come "alternativa al pinch" sempre a portata di pollice. Il
  risultato è una pila di tre oggetti flottanti in basso: "Oggi" (40 dp), i segmenti (56 dp), la pillola (60 dp), più
  gli spazi: circa 190 dp di chrome sopra le foto su 915, cioè un quinto dello schermo, quasi tutto vetro.
- **Il comando non è frequente**: si cambia livello di rado e il pinch lo fa meglio. Le gallerie di riferimento (§2) lo
  tengono in un menu o in un controllo compatto, non sopra la navigazione.

### 1.2 I pulsanti che si compenetrano

| Cosa | Dove | Perché |
|---|---|---|
| L'intestazione agganciata dentro la barra | `LibraryTab.kt`: `controlsBottom = barHeight - FluidScreenDefaults.GlassFadeTail` e `stickyTop = controlsBottom` | La barra finisce con una coda di vetro di 34 dp (`GlassFalloff.FadeDown`), e l'intestazione agganciata, che è un'altra fascia di vetro `GlassRole.Bar` (`MediaGrid.kt`, `StickyHeader`), parte **dentro** quella coda. Due vetri sovrapposti per 34 dp: si vede la riga doppia e, sotto la coda, il testo della lista "fantasma" (screenshot `selezione/3-P10/preferiti-selezione-chiaro-telefono.png`, "Roma" sotto la barra) |
| La maniglia dello scrubber sopra "Oggi" | `LibraryTab.kt`: `bottomPadding = pillInset + ChromeGap + bottomBarHeight + ChromeGap`, con `bottomBarHeight` misurato sui soli segmenti | "Oggi" sta sopra i segmenti ma fuori dalla misura: lo scrubber corre fino ai segmenti e passa sopra "Oggi" (già nei dubbi grafici di P11) |
| La barra delle azioni e la pillola | `MainScaffold.kt`: in selezione la pillola sfuma via (`AnimatedVisibility`) e `pillPadding` cambia; la barra delle azioni entra con un'altra dissolvenza in un altro punto (`LibraryBottomChrome`) | Per tutta la transizione ci sono due capsule di vetro una sopra l'altra; il margine in fondo cambia e **la griglia si sposta** entrando in selezione (dubbio grafico di P11). Nelle Raccolte (P10) la barra delle azioni sta sopra il posto della pillola |
| Le capsule di vetro della barra sopra la foto | `GalleryTopBar.kt`: tasti `glassControlSurface` da 44 dp dentro una barra di vetro | Vetro su vetro su foto: con la 2.13.0 il tasto campiona la barra, ma resta un terzo strato che si vede come un bottone incollato (P10, la "x" sopra una foto) |

### 1.3 Le scritte sotto il vetro si leggono a malapena

La causa è aritmetica. Il testo usa le coppie del tema (`onSurface`, `onSurfaceVariant`, `primary`), che sono garantite
**sulla superficie del tema**; sopra una foto la superficie è la pellicola del vetro composta con la foto, e la pellicola
è leggera apposta (il vetro deve trasmettere):

| Pezzo | Pellicola (engine 2.13.0) | Inchiostro | Caso peggiore, calcolato |
|---|---|---|---|
| Barra (`GalleryTopBar`) e data agganciata (`GalleryTimelineHeader(stuck = true)`) | `barTint()`: film chiaro al 44% (scuro: grigio `#4A4A4E` al 40%) | `onSurface`, luogo `onSurfaceVariant` | chiaro, sopra il nero: 3,4:1 il titolo, 1,9:1 il luogo; scuro, sopra il bianco: 1,6:1 |
| Segmenti (`GalleryGridLevelBar`) | `floatingTint()` al 52% (48%) | etichette `onSurface`, scelta in `primary` (lilla) | il lilla sulla lente chiara: sotto 1,5:1 già su fondo neutro |
| Pillola (engine) | `floatingTint()` | `onSurface` | scuro, sopra il bianco: circa 1,8:1 |
| "Oggi", icone della barra, azioni | `floatingTint()` / lente di controllo | `primary` | il viola su una lente che lascia passare una foto rossa o nera: 1,1–2,5:1 |

Tre aggravanti: il secondo rigo è **grigio** (`onSurfaceVariant`), cioè parte già più vicino al fondo; il viola
dell'accento è un colore medio, che sta male sia sul chiaro sia sullo scuro; la sfocatura di base è piccola (2,5 dp,
`regole.md`: "il vetro si tara con la lente, non con la sfocatura"), quindi sotto una barra il contenuto passa quasi
nitido, comprese le righe di testo di uno screenshot.

### 1.4 Il vetro "poco pensato"

- **Il vetro è stato usato come colore di pagina.** Barra, intestazione, segmenti, "Oggi", pillola, scrubber, avvisi:
  sette superfici di vetro sopra una griglia di foto, quando il vetro dovrebbe stare solo dove *galleggia* qualcosa.
- **Nessuno ha deciso chi garantisce il contrasto.** L'engine garantisce le coppie del tema sulla sua superficie; la
  galleria ha messo quelle coppie sopra le foto. L'unico punto che ci aveva pensato è `galleryTintOnPhoto` (il viewer).
- **Due fondali a schermo intero annidati**: `LibraryTab` registra la pagina (`glassBackdropSource(backdrop)`) e
  `MediaGrid` registra di nuovo la lista (`listBackdrop`) per l'intestazione agganciata e lo scrubber. Sull'S25 il collo di
  bottiglia del pinch e dello scorrimento è proprio il RenderThread, con 47 passate GPU e la memoria dei layer sotto
  pressione (`docs/bench/misure-s25-2026-10-13.md` §3.4–3.5).

### 1.5 Contrasto misurato: la Libreria attuale

Sull'emulatore `Medium_Phone` (dove il vetro sfoca davvero), con 186 immagini di prova di
`strumenti/genera-media.py`: giornate tutte bianche, tutte nere, screenshot di testo nero su bianco e bianco su nero,
colori saturi, righe sottili, misto; con data e luogo nell'EXIF. Per ogni direzione e tema: la Libreria a riposo, dodici
passi di scorrimento (sotto la chrome passano a turno tutte le giornate), lo scrubber, la selezione, il menu della vista.

Il metodo (`misura-contrasto.py`): il dump di uiautomator dice dove sta ogni testo; nei pixel i glifi sono quelli più
scuri (o chiari) del loro fondo locale; il fondo è l'anello di pixel fra 3 e 14 px dai glifi; il **caso peggiore** è il
5% dell'anello più vicino all'inchiostro, il minimo su tutti gli scatti. Per le icone si guarda il 64% centrale del
bersaglio (all'orlo la lente rifrange il contenuto di fuori, che non è inchiostro). "< 1,35" vuol dire che il glifo non
si distingue dal fondo nemmeno di 1,35:1.

| Pezzo (Libreria attuale) | Chiaro, peggiore | Scuro, peggiore | Soglia |
|---|---|---|---|
| Titolo "Libreria" nella barra | 3,67 | 1,89 | 4,5 |
| Data e luogo agganciati | < 1,35 ("Oslo" sopra il nero) | < 1,35 ("Firenze" sopra il bianco) | 4,5 |
| Segmenti Anni / Mesi / Giorni / Tutte | < 1,35 | < 1,35 | 4,5 |
| Etichette della pillola | < 1,35 ("Libreria" sopra il nero) | < 1,35 | 4,5 |
| "Oggi" | 3,80 | 2,30 | 4,5 |
| Icone della barra (seleziona, vista, chiudi) | 1,39 | < 1,35 | 3 |
| Barra delle azioni | 1,12 | < 1,35 | 3 |
| Menu della vista (pop-up dell'engine) | 5,22 | 3,15 | 4,5 |

Il menu e la pillola sono componenti dell'engine: il loro limite in scuro sopra le foto chiare è nel §7.

## 2. Riferimenti

A memoria, senza scaricare niente: dove non sono sicuro lo dico.

**Foto di iOS (17, 18, 26).**
- La griglia è una parete di foto **quadrate con fessure di un punto**, senza angoli; le proporzioni originali sono
  un'opzione di vista.
- In cima alla griglia c'è la **data della zona in vista** (e il luogo), in bianco e grassetto su una sfumatura scura
  sopra le foto, non una barra; a destra "Seleziona" e un menu in capsule sfocate. È lo stesso trattamento in chiaro e
  in scuro: la chrome sopra le foto è quella di un visore.
- Il **pinch** cambia la scala ancorato al dito. Il comando esplicito Anni/Mesi/Tutte in iOS 17–18 era un controllo
  in basso; l'utente ricorda che Foto lo ha tolto: su questo dettaglio non ho certezza, e non cambia la proposta (il
  comando esplicito sale in alto, il pinch resta il gesto).
- In selezione il titolo diventa "N selezionati", in basso una barra con condividi, cuore, elimina al posto della
  navigazione. Ordinamento e filtri stanno in un menu.
- iOS 26: la navigazione torna a schede in una capsula di vetro che **si riduce scorrendo** e torna risalendo.

**Samsung Gallery (One UI 6–9).** Navigazione in basso (Immagini, Album, Storie, Menu); il titolo grande che si
richiude scorrendo; ricerca e ⋮ in alto, dove stanno selezione, filtri e impostazioni; pinch per la densità; una
maniglia di scorrimento rapido con la data; in selezione la barra in basso diventa quella delle azioni e in alto c'è
"N selezionati" con "Tutti". Le intestazioni di data sono testo sulla pagina, non fasce di vetro.

**Liquid Glass (Apple, iOS 26) e la leggibilità.**
- **Due strati**: i controlli sono uno strato funzionale *sopra* il contenuto; il vetro non va nel contenuto e non si
  impila su altro vetro.
- **Tinta adattiva**: gli elementi piccoli (barre, pulsanti) passano da un aspetto chiaro a uno scuro secondo la
  luminosità di ciò che hanno sotto; quelli grandi si adattano meno. La variante "trasparente" chiede uno strato di
  attenuazione sotto, quando il contenuto è chiaro.
- **Scroll edge effect**: dove il contenuto passa sotto le barre, una sfumatura (morbida di serie, netta per le barre con
  testo fitto) lo attenua, e i controlli flottanti si leggono sopra la sfumatura, non sopra la foto nuda.
- **Testo in vibrancy**: le etichette prendono colore dal contenuto ma con il contrasto spinto; l'ombra del vetro si fa
  più forte sopra il testo.
- **Tipografia**: pesi più forti e titoli allineati a sinistra nelle viste nuove; dimensioni piccole evitate sopra il
  vetro.

Quello che prendiamo: la data in alto al posto della barra (Foto), il comando di vista in un menu (Foto, Galleria),
la pillola che si riduce scorrendo (iOS 26), la sfumatura di bordo (Liquid Glass), la tinta adattiva come direzione C,
e la regola che il testo sopra il vetro deve avere un fondo garantito, non sperato.

## 3. Principi misurabili

| # | Principio | Come si verifica |
|---|---|---|
| P1 | Il testo sopra vetro o sfumatura regge **4,5:1**, le icone e il testo grande **3:1**, sul **caso peggiore** del contenuto sotto (bianco, nero, grigi, colori saturi, testo) | Per costruzione: `GalleryLegibilityTest` (A e C, foto peggiori una per una e tutte insieme). Sui pixel: `misura-contrasto.py` sulle campagne (§1.5, §5) |
| P2 | Nessun controllo si sovrappone a un altro: titolo, comandi, scrubber, barra delle azioni e pillola hanno bordi disgiunti, a riposo e in selezione, anche con il testo a 1,3x | `LibraryPrototypeLayoutTest` (test dei bordi, Robolectric) |
| P3 | Ogni bersaglio della chrome è di almeno **48 dp** | `LibraryPrototypeLayoutTest` |
| P4 | **Nessun fondale di vetro a schermo intero in più** rispetto a oggi | Nel codice: i prototipi hanno un solo `glassBackdropSource` (la pagina); oggi sono due. È uno in meno |
| P5 | Sopra la pillola **niente**: in basso c'è la pillola, o in selezione la barra delle azioni al suo posto | Il test dei bordi controlla che la barra finisca dove finiva la pillola e sia alta uguale |
| P6 | Entrare e uscire dalla selezione **non sposta la griglia** | `LibraryPrototypeLayoutTest` (prima riga visibile e scostamento uguali) |
| P7 | L'inchiostro non è mai traslucido: la gerarchia la fanno taglia e peso, non l'alfa | Revisione del codice: `GalleryChromeTitle` non ha alfa sul testo |
| P8 | Il testo non sta mai su vetro più grande di 64 dp di lato corto (la regola della taglia lo sfocherebbe fino alla media) | Le capsule G1 sono alte 48 dp, l'etichetta di C 52 dp; nessuna fascia di vetro a tutta larghezza |

## 4. Le tre direzioni

La struttura comune è nel §0. Codice: `feature-library/.../library/LibraryPrototype.kt` (la Libreria),
`LibraryDesign.kt` (la scelta), `grid/MediaGridChrome.kt` (sezione sotto la linea, scrubber esterno, contenuto sotto
un rettangolo), `core-designsystem/.../design/` (capsule, titolo, sfumature, barra delle azioni, l'aritmetica della
leggibilità). La Libreria attuale non è cambiata (`LibraryScreen`); i parametri nuovi di `MediaGrid`,
`GalleryScrubber`, `rememberGalleryGridCellStyle` hanno il default di prima.

### A, sopra le foto

- **In alto**: nessuna barra. Una sfumatura nera dalla barra di stato in giù (64% in cima, **almeno 58% sotto il
  testo**, poi a zero in 64 dp), disegnata dentro il fondale come un rettangolo con un gradiente. Sopra, a sinistra, la
  data della sezione in vista in **bianco grassetto** (17 sp) e il luogo (15 sp medio), con un'ombra leggera; a destra
  una capsula di **vetro scuro** (`darkFloatingTint`) con "vai a oggi" (solo lontano dai recenti), "seleziona" e il
  menu della vista (livelli, filtro, stile, Impostazioni). Le icone della barra di stato diventano chiare finché A è
  sullo schermo. In cima alla Libreria, dove non c'è ancora una sezione, il titolo è "Libreria" e il riepilogo.
- **Le intestazioni**: testo sulla pagina come oggi; salendo sotto la riga di chrome cedono il posto alla data in alto
  (stessa logica dell'intestazione agganciata, senza la fascia).
- **La griglia**: parete squadrata, fessura di 1 dp, angoli a zero (`cellCornerScale = 0`).
- **In basso**: solo la pillola, che si ripiega scorrendo verso i più vecchi e torna verso i recenti; dietro, una
  sfumatura del colore della pagina che le garantisce un fondo: al bordo della pillola l'opacità è **calcolata**
  (`GalleryLegibility.minimalUnderlayAlpha`: il minimo per cui le etichette dell'engine reggono 4,5:1 più un margine
  sopra il bianco e il nero), mai sotto 0,55, e arriva a 0,9 in fondo. Con 0,40 l'etichetta della scheda scelta, sul
  pomello tinto d'accento, si fermava a 3,7:1 (misurato).
- **Selezione**: in alto "N selezionati" in bianco e "Fine"; la barra delle azioni in vetro scuro nel posto della
  pillola, il rosso distruttivo schiarito quanto basta per 3:1.
- **Scrubber**: maniglia e bolla in vetro scuro con inchiostro bianco, fra la riga in alto e la pillola.
- **Scorrimento**: la chrome in alto resta ferma e cambia solo la data; la pillola si riduce.
- **Pro**: è la grammatica di Foto, coerente con il viewer; il testo bianco su nero al 58% regge su qualunque foto
  (5,3:1 sul bianco pieno, per costruzione); poca superficie di vetro e piccola.
- **Contro**: nel tema chiaro la cima dello schermo è scura (voluto, ma è una scelta); sopra una zona di pagina (fra due
  sezioni) la sfumatura si vede come un grigio; la parete squadrata cambia la Libreria più di quanto l'utente abbia
  chiesto (è un parametro: si può tenere l'angolo di oggi).
- **Costo GPU**: un fondale a schermo intero (uno in meno di oggi); vetro su una capsula di circa 150 × 48 dp, la pillola
  e lo scrubber quando c'è; due rettangoli sfumati. Niente fasce di vetro a tutta larghezza (oggi due).

### B, barra solida

- **In alto**: una barra **opaca** del colore della pagina con un filo sotto. Riga 1: "Libreria" (22 sp grassetto) a
  sinistra, a destra il livello come etichetta con la freccia ("Giorni ▾", apre i quattro livelli), "vai a oggi",
  "seleziona" e il menu della vista. Riga 2: la data agganciata e il luogo, nella barra. La griglia comincia sotto.
- **Le intestazioni**: testo sulla pagina; passano sotto la barra opaca e la riga 2 le sostituisce.
- **In basso**: la pillola posa su una **fascia opaca** del colore della pagina; la griglia finisce sopra.
- **Selezione**: "✕" e "N selezionati" nella barra; la barra delle azioni è una capsula opaca con ombra nel posto della
  pillola.
- **Scrubber**: capsula opaca con ombra.
- **Scorrimento**: niente si muove; cambia la riga 2.
- **Pro**: il contrasto è quello del tema e basta (16–17:1 il titolo, 4,9:1 il luogo in grigio); è la più economica;
  il livello è un comando visibile e diretto, come chiesto ("un comando in alto").
- **Contro**: niente vetro sopra la griglia vuol dire meno "Fluid"; la pillola sopra una fascia piatta perde la
  rifrazione (vetro sopra il grigio, `regole.md`); le due bande opache nascondono le foto che in A e C restano visibili
  sotto le sfumature (circa 90 dp in alto e 130 in basso, sul telefono).
- **Costo GPU**: il più basso. Un fondale (lo campiona solo la pillola), nessun vetro in alto, barra e fascia sono
  rettangoli pieni.

### C, vetro adattivo

- **In alto**: una sfumatura del **colore della pagina**, densa solo dietro la barra di stato (85% in cima, 55% al
  suo bordo, 15% dove finiscono i controlli, poi a zero): lo "scroll edge effect". Sopra, due oggetti di vetro: a sinistra
  un'etichetta con data e luogo (angoli `FluidRadius.Card`, 52 dp), a destra la capsula dei comandi. Con una sfumatura
  più forte (prima prova: 82–50%) la polarità seguiva sempre il tema e il vetro "adattivo" non adattava niente.
- **La tinta adattiva**: per ogni oggetto si leggono le celle che gli passano sotto (`contentUnder`: dai **ThumbHash**
  dell'indice, il colore medio e gli estremi di luminanza, senza leggere un pixel dalla GPU; le intestazioni e il
  riepilogo contano come pagina con testo), si compongono con la sfumatura alla quota del bordo basso, e
  `GalleryLegibility.adapt` sceglie la polarità (inchiostro quasi nero su pellicola bianca, o bianco su nera) e
  **l'opacità minima** della pellicola (fra 0,30 e 0,86) che porta il testo a 4,5:1 sul caso peggiore. Si ricalcola
  solo quando sotto entra o esce una riga; si cambia polarità solo se l'altra costa almeno 0,08 di pellicola in meno
  (isteresi); inchiostro e pellicola cambiano con la molla standard.
- **In basso**: la pillola (che si ripiega) sulla stessa sfumatura calcolata di A; la barra delle azioni è adattiva come
  la chrome in alto.
- **Scrubber**: la scelta adattiva per un contenuto qualunque (nero e bianco), nella polarità del tema.
- **Pro**: è il Liquid Glass come Apple lo descrive; il vetro resta trasparente dove può (foto uniformi) e si fa denso
  solo dove serve; il contrasto è garantito dal calcolo, non dalla taratura a occhio.
- **Contro**: le capsule cambiano opacità e polarità mentre si scorre (nel tema scuro, sopra le giornate chiare,
  sono passate a inchiostro scuro su pellicola chiara in 18 misure su 54; nel tema chiaro mai, perché sotto le capsule
  passa quasi sempre un'intestazione o il bordo della pagina e il calcolo resta prudente), e sopra i colori saturi il
  vetro si colora: è vetro, ma si nota; il ThumbHash è una stima a bassa risoluzione
  (le righe sottili e il testo degli screenshot li vede come un grigio medio: regge perché la sfocatura del vetro li media
  e perché il calcolo guarda gli estremi della luminanza, non la media); è la direzione con più parametri da tarare sul
  telefono e quella con il contrasto più vicino alla soglia (5,1:1 nel caso peggiore). Le fessure fra le celle non
  entrano nel calcolo: contate come pagina tiravano sempre la polarità verso il tema.
- **Costo GPU**: come A più un'etichetta di vetro (circa 200 × 52 dp); CPU: una ventina di toni letti dall'intestazione
  dei ThumbHash per ogni riga che entra o esce sotto una capsula, trascurabile; il cambio di tinta ricompone due nodi
  piccoli per la durata della molla.

### Cosa resta uguale in tutte

Il pinch e il suo ancoraggio, il viewer, le celle e i badge, il menu della vista (che in A e C ha i livelli in cima), gli
avvisi (sotto la riga di chrome), le Raccolte. In B i livelli stanno nell'etichetta, non nel menu.

## 5. Confronto misurato

Contrasto peggiore sui pixel, campagne del 14/10 (chiaro / scuro; soglia 4,5 per il testo, 3 per le icone):

| | Attuale | A | B | C |
|---|---|---|---|---|
| Titolo o data in alto | 3,67 / 1,89 | **5,51 / 5,60** | **16,51 / 17,22** | **5,57 / 5,94** |
| Data agganciata | < 1,35 / < 1,35 | (è il titolo) | **4,89 / 8,34** | (è il titolo) |
| Comandi in alto (icone e "Fine") | 1,39 / < 1,35 | **12,08 / 12,08** | **15,89 / 12,68** | **5,57 / 5,06** |
| Segmenti | < 1,35 / < 1,35 | (nel menu) | (l'etichetta del livello è fra i comandi) | (nel menu) |
| Barra delle azioni (icone) | 1,12 / < 1,35 | **3,34 / 14,08** | **4,57 / 5,02** | **10,53 / 14,28** |
| Pillola (etichette, aperta sopra le foto) | < 1,35 / < 1,35 | **12,22 / 4,65** | **12,34 / 11,23** | **8,89 / 6,76** |
| Menu della vista (pop-up) | 5,22 / 3,15 | **5,21 / 8,52** | **5,21 / 5,28** | **5,50 / 5,42** |

Il riassunto è in `docs/screenshots/G1/contrasti-finali.json`, il dettaglio di ogni scatto in
`docs/screenshots/G1/campagna-*/contrasti.json` (sei campagne: la prima completa, le altre dopo ogni correzione;
per ogni pezzo vale la campagna con il codice finale: B la 3, A la 4, C la 6). Il 3,34 di A in chiaro è il rosso di
"Elimina" schiarito sulla pellicola scura: per le icone la soglia è 3.

**Costo di disegno** (dal codice, durante uno scorrimento a "Giorni", senza selezione):

| | Fondali a schermo intero | Superfici di vetro che si ricatturano | Fasce di vetro a tutta larghezza |
|---|---|---|---|
| Attuale | 2 (pagina e lista, annidati) | barra, 2 tasti, intestazione agganciata, segmenti, pillola (pannello, riga nascosta, pomello), "Oggi" e scrubber quando ci sono: 8–10 | 2 |
| A | 1 | capsula, pillola (2–3), scrubber quando c'è: 3–5 | 0 |
| B | 1 | pillola (2–3) | 0 |
| C | 1 | etichetta, capsula, pillola (2–3), scrubber quando c'è: 4–6 | 0 |

`gfxinfo` sull'emulatore (`strumenti/gfxinfo.py`) non distingue le direzioni: la GPU del PC e il traduttore grafico
dell'emulatore coprono le differenze (percentili quasi uguali, e i percentili GPU fuori scala). La misura vera è
sull'S25 con Macrobenchmark, quando si sceglie (§9).

## 6. Le domande grafiche aperte dei pacchetti

| Domanda (P7/P8/P10/P11) | Risposta delle direzioni |
|---|---|
| Il pinch ancora la riga? | Sì, invariato (`GridAnchors`): l'elemento sotto il dito resta sotto il dito. Togliendo i segmenti il pinch diventa il gesto principale, come in Foto; il menu (o l'etichetta di B) resta per chi non può pizzicare e per TalkBack |
| I segmenti restano sempre visibili? | No: spariscono dal basso in tutte e tre. In A e C i livelli sono le prime quattro voci del menu della vista; in B un'etichetta "Giorni ▾" sempre visibile in alto |
| La barra delle azioni sopra la pillola? | No: **al posto** della pillola, stessa altezza (60 dp) e stesso fondo; la griglia non si sposta (P5, P6). Da portare anche nelle Raccolte quando si sceglie |
| Angoli e badge sotto i 64 dp | Proposta: sotto i 64 dp di lato (il livello Anni, 56 dp) angoli a zero e nessun badge tranne la durata dei video ridotta a un glifo. Oggi l'angolo scala già (`CornerReferenceCell`, minimo 45%) e i badge spariscono sotto 64 dp; in A gli angoli sono a zero a ogni livello |
| La maniglia dello scrubber sopra "Oggi" | Risolto dalla struttura: "vai a oggi" sale nella capsula in alto e lo scrubber corre fra la chrome in alto e la pillola |
| Il piede sotto i segmenti cambiando livello | Non c'è più niente sopra la pillola: il piede ha sempre lo stesso spazio |

## 7. Proposte per l'engine (non fatte: è un altro repository)

1. **`legibleInk` pubblico e generalizzato in una `FluidLegibility`.** Oggi è `internal` in `FluidFloatingTabBar.kt` e
   risolve solo l'inchiostro del pomello. Serve all'engine e alle app la stessa aritmetica che G1 ha scritto in
   `core-designsystem/.../design/GalleryLegibility.kt`: contrasto WCAG, `minimalFilmAlpha(ink, film, contenuto, soglia)`
   e la scelta di polarità con isteresi, con i test. Da lì `GlassDefaults.legibleTint(ink, worstUnder)`.
2. **La pellicola dei modali e della pillola calcolata sul caso peggiore.** Misurato in scuro sopra una foto bianca: il
   menu della vista (pop-up, `modalTint` grigio al 50% più lo scrim al 20%) tiene il testo a 3,15:1, le etichette della
   pillola scendono sotto 1,35:1. Proposta: nel tema scuro `modalTint` e `floatingTint` con l'opacità minima per 4,5:1
   sopra il bianco (circa 0,7 di film scuro), o un velo nel pannello. G1 lo fa da fuori nei prototipi:
   `GalleryChromeMenuAnchor` stende sotto le voci il velo minimo calcolato (menu da 3,15 a 5,2–8,5:1) e A e C stendono
   sotto la pillola la sfumatura calcolata. Il calcolo da solo non bastava, e il motivo va all'engine: la pellicola che
   si vede non è solo la tinta (lente, riflesso e vibrancy la cambiano), quindi un velo calcolato a 4,5 si fermava a
   4,4; G1 usa una soglia di 5,5 (`GalleryChromeDefaults.MeasuredTarget`).
3. **L'etichetta della scheda scelta sul pomello.** A riposo l'etichetta della scheda scelta sta sul pomello tinto
   d'accento: sopra una foto qualunque, con il velo che bastava alle altre etichette, misurata a 3,7:1 (chiaro) e 3,8:1
   (scuro). `legibleInk` oggi vale solo per il pomello premuto; va esteso al pomello a riposo.
4. **Una `FluidScrollEdge` (top/bottom, morbida o netta)**: la sfumatura di bordo come componente, disegnata dentro il
   fondale (così il vetro sopra la vede), con il colore della pagina o il nero "foto" e le tappe di opacità. In G1 è
   `GalleryEdgeScrim`.
5. **`FluidFloatingTabBar` con un fondo garantito**: un parametro `edge` che stende la sfumatura sotto la pillola, o un
   `tint` per la famiglia "sopra le foto" (`floatingTintOnPhoto`) con l'inchiostro che lo segue (oggi le etichette
   prendono `LocalContentColor`, quindi una pillola scura nel tema chiaro avrebbe il testo scuro).
6. **La tinta come lambda**: `glassSurface(tint = { … })` letto nella fase di disegno, così una tinta animata (il vetro
   adattivo) non ricompone il nodo a ogni fotogramma della molla.
7. **Una sonda di luminanza del fondale** (`GlassBackdropState.luminanceUnder(rect)`), facoltativa: dalla cattura già
   fatta, a bassissima risoluzione e non a ogni fotogramma. Per le app che, a differenza della galleria, non hanno un
   ThumbHash per ogni cosa che scorre.
8. **Uno stile di testo "sopra le foto"** in `FluidTextStyles` (bianco pieno, peso medio o grassetto, ombra corta), invece
   di costruirlo nelle app.

## 8. Raccomandazione

**A**, con due prestiti:

- dalla **B**, l'etichetta del livello: in A i livelli stanno nel menu, e l'utente ha chiesto "un comando di
  ordinamento o vista in alto". Un'etichetta "Giorni ▾" chiara sulla capsula scura lo rende visibile senza aggiungere una
  riga (va provato a occhio: la capsula si allarga di circa 90 dp e il titolo ne perde altrettanti);
- dalla **C**, il calcolo: la sfumatura e la capsula di A sono tarate con l'aritmetica di `GalleryLegibility` (0,58 sotto
  il testo è il minimo per 5,3:1 sul bianco), e le stesse funzioni vanno nell'engine (§7.1).

Perché A e non le altre:

- **è la grammatica di una galleria**, quella di Foto e del nostro viewer: le foto sono la pagina, la chrome è un velo
  sopra. B è una pagina di impostazioni ben fatta, C un esercizio di vetro;
- **il contrasto è garantito per costruzione e stabile**: il bianco su nero al 58% non dipende dalla foto (5,5–5,6:1
  misurato nel caso peggiore, in chiaro e in scuro), e la chrome non cambia colore mentre si scorre (in C sì);
- **è economica**: un fondale in meno di oggi, nessuna fascia di vetro, poca superficie di vetro e piccola; sulla carta
  vale meno passate GPU per fotogramma di C, ed è il RenderThread il collo di bottiglia misurato sull'S25;
- **toglie più cose**: nessuna barra, nessuna intestazione in vetro, niente sopra la pillola, che si riduce.

Se l'utente trova la cima scura troppo forte nel tema chiaro, la variante è C con la polarità bloccata sul tema (meno
cambi di colore, stesso calcolo); se vuole zero vetro sopra la griglia, B.

## 9. Come provare e cosa resta

- Debug: Libreria → menu della vista → "Prototipo grafico: …". Si passa da una all'altra senza riavviare; la scelta si
  ricorda. Con la Libreria sintetica (100.000) si prova lo scorrimento lungo.
- Test: `LibraryPrototypeLayoutTest` (bordi, bersagli, posto della pillola, griglia ferma, release invariata, il vetro
  adattivo sopra il vuoto), `GalleryLegibilityTest` (il contrasto per costruzione, il velo sotto la pillola),
  `ThumbHashTonesTest` (i toni del vetro adattivo).
- Provate anche sulla Libreria sintetica (2.000 elementi, `docs/screenshots/G1/sintetica/`). Lì è uscito un crash del
  vetro adattivo: un rettangolo sopra il vuoto di una riga incompleta dava un intervallo di contenuto vuoto. Corretto
  (sotto il vuoto c'è la pagina) e coperto da `cSottoUnaFessuraCeLaPagina`.
- **Da fare dopo la scelta**: misurare sull'S25 con gli scenari di P9 (scorrimento e pinch) la direzione scelta contro
  l'attuale; portare la barra delle azioni al posto della pillola anche nelle Raccolte e nei Nascosti; togliere il
  codice delle direzioni scartate e il secondo fondale anche dalla Libreria di sempre (diventa la nuova); decidere gli
  angoli sotto i 64 dp; tarare a occhio sul telefono le opacità (sono numeri di partenza, come i massimi della regola
  della taglia dell'engine).
- **Limiti**: la tinta adattiva di C stima il contenuto dai ThumbHash, non dai pixel; Robolectric non rende il vetro
  (i test controllano geometria e aritmetica, la leggibilità vera è sui pixel dell'emulatore); l'emulatore non misura il
  costo GPU (§5).

## 10. Domande per l'utente

1. **Quale direzione?** A (sopra le foto, consigliata), B (barra solida), C (vetro adattivo), oppure A con l'etichetta
   del livello presa da B.
2. **La cima scura nel tema chiaro (A)**: va bene come in Foto, oppure la vuoi del colore della pagina (diventa una
   via di mezzo fra A e C)?
3. **La griglia**: parete squadrata con fessura di 1 dp (A), oppure gli angoli continui di oggi?
4. **Il livello**: nel menu "⋯" (A, C), oppure sempre visibile in alto come "Giorni ▾" (B)?
5. **La pillola che si riduce scorrendo** (A, C): sì, oppure sempre aperta (B, e oggi)?
6. **Angoli e badge sotto i 64 dp**: angoli a zero e solo il glifo del video, oppure come oggi (angolo ridotto, nessun
   badge)?
