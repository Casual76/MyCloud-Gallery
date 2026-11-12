# I2: la ricerca che mostra invece di spiegare (fase I, 11/11/2026)

Il metro dell'utente: **«se non sapessi leggere o sentire, potrei usare l'app?»**. Il disegno della ricerca della fase H resta (niente pagina, la barra
che cresce dal cerchio in basso, la Libreria che sale con la tastiera, in alto al piu' tre suggerimenti, i risultati sopra la barra solo per pertinenza,
una X sola). Cambia come la ricerca si esprime. Screenshot: `docs/screenshots/beta-grafica/I2/` (dopo) e `docs/screenshots/beta-grafica/I2/prima/` (prima:
H4 e 6-P8). Parole contate sul telefono (360 dp), senza i numeri, le foto e il testo scritto dall'utente.

| Superficie | Prima | Dopo | Parole visibili |
|---|---|---|---|
| Barra a campo vuoto | tre voci di testo («mare ad agosto · Roma · Video»), sempre le stesse | due **esempi con la foto** del loro primo risultato (un luogo, un mese pieno, una persona col volto, una cosa che l'indice trova spesso, un tipo che c'e'), scelti fra quelli con tanti risultati e **diversi a ogni apertura**, piu' la ricerca recente piu' nuova; ogni voce e' una capsula con il suo tondo | 5 → 2-5, piu' 2-3 foto |
| Completamenti mentre si scrive | testo | la stessa forma (tondo + etichetta): un luogo con la sua foto, una persona col volto, un mese con una sua foto, una recente con il primo risultato | uguali, piu' le foto |
| Dopo una ricerca, copertura | schermata 1: tre schede («Nessun dispositivo analizza l'area comune» + «Attiva su questo dispositivo», lo stesso per l'area privata, «La ricerca per contenuto copre 5.029 media su 13.608» + «Perche'?»); in H4 una riga «Nessun dispositivo analizza l'area comune» | **una riga sola**: il segno della ricerca che impara, una barra che avanza (si allunga con la molla, un riflesso la percorre quando cresce) e «36%». Le aree senza chi le analizza **solo in Impostazioni** | ~40 (H4: 5) → 0 |
| Nessun risultato | «Niente per «zebre in montagna»» | il segno della ricerca vuota e «0»; in alto le recenti (o gli esempi) con la loro foto | 2 → 0 |
| Trova simili | «Simili a questa foto» | la **foto** da cui vengono e «Simili» | 4 → 1 |
| Ricerca non ancora pronta | «Sto preparando la ricerca per contenuto…», «Senza il modello cerco solo date, luoghi e testo» | la clessidra (giro 1: attesa = la clessidra e basta); la freccia di scaricamento e «Scarica il modello» | 6-9 → 0-3 |
| Impostazioni > Ricerca e modelli | una pagina di frasi: sottotitolo, ruolo con 24 parole di spiegazione, stato e stima in frasi, copertura in frase, «Aree senza chi le analizza», modelli come righe con capsule di testo e tasti, piede di 25 parole, spazio in frase | in testa **l'anello della galleria** con la percentuale e, sotto, il numero grande delle foto conosciute; i motivi come segni con un numero; **due piastrelle** (area comune, area privata) con il loro segno ✓/! e un solo interruttore «Questo dispositivo analizza»; che cosa fa adesso il telefono come **un segno** (in corso con il tempo che manca, fatto, in pausa con il perche', in attesa con la clessidra, problema) e **un tasto solo** con l'icona, le altre azioni dietro il tocco sulla riga; la misura come segno (luna e notti, caldo, misura in corso); i modelli come **anelli** (pronto, in arrivo con la percentuale, assente, problema) con il dettaglio e le azioni dietro un tocco; la lettura del testo con un tasto solo; le spiegazioni lunghe dietro la «i», che le apre nella pagina | ~180 → ~40 |

TalkBack dice ancora tutte le frasi di prima (descrizioni dei segni, degli anelli e delle righe).

## Scelte di disegno che l'utente potrebbe voler cambiare (una riga ciascuna)
- A campo vuoto **prima i due esempi, poi la ricerca recente**: a 360 dp, se le tre voci non entrano intere, restano le foto d'esempio (la recente si ritrova scrivendo e quando non si trova niente).
- La riga della copertura dice **solo «36%»** accanto alla barra (niente «Sto imparando le tue foto»): un numero si legge senza leggere; TalkBack dice «La ricerca sta imparando le tue foto: ne conosce 5.029 su 13.608».
- Il riflesso sulla barra passa **due volte** a ogni passo avanti e poi si ferma (non un'animazione continua).
- «Nessun risultato» e' il segno e **«0»**, non la parola cercata ripetuta (e' gia' nel campo).
- Le cose provate come esempi sono un **elenco fisso** di 16 parole (mare, spiaggia, montagna, neve, tramonto, cibo, pizza, torta, cane, gatto, fiori, bosco, lago, citta', concerto, bicicletta), tenute se l'indice ne trova almeno 12.
- Un esempio deve trovare **almeno 12 risultati** (3 in una libreria piccola).
- Le aree sono due piastrelle **senza interruttore ciascuna**: il ruolo e' uno solo e copre tutte e due le aree dell'account, quindi l'interruttore e' uno («Questo dispositivo analizza»), subito sotto.
- In «Ricerca e modelli» una **«i» sola** in testa alla pagina spiega copertura, ruolo e «niente esce da qui» (la frase si apre sotto, nella pagina); la lettura del testo spiega toccando la sua riga.
- I modelli sono tre anelli affiancati; **Scarica, Rimuovi, Riprova** stanno nel dettaglio dietro il tocco (prima erano tasti sempre visibili).
- «Solo sul telefono» non e' piu' in pagina (sta nella spiegazione).
- Finita l'analisi la pagina dice **solo «Fatto»**: la riga della misura («Niente da analizzare») sparisce, perche' direbbe la stessa cosa.
- Con il modello mancante la freccia di scaricamento sta **solo nell'anello**; accanto «Manca il modello» e la misura, senza un secondo segno.
- Giro 1 (CU-19): sotto l'analisi e sotto la lettura **un tasto solo** (quello che serve adesso); le altre scelte («Non su questo dispositivo», «Spegni», «Indicizza ora» quando c'e' un problema...) sono **dietro il tocco** sulla riga.
- Giro 1 (CU-19): l'analisi in corso dice **il tempo che manca** («≈ 95 min»), non un secondo «quanti su quanti» sotto quello della copertura; il numero sta nella frase per TalkBack e nel foglio.
- Giro 1 (CU-25): la ricerca per contenuto e' la **lente sull'immagine**, l'analisi il **chip**, l'analisi in corso il **tempo che scorre**; le scintille restano ai Ricordi.
- Giro 1: la testata ha l'anello **al centro** e i numeri sotto, a tutta larghezza (l'anello comune da 132 dp accanto ai numeri non ci stava a 360 dp e a 1,3x).
