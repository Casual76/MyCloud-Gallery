# I1: le Raccolte come una galleria, prima e dopo (fase I, 11/11/2026)

L'utente ha messo a confronto le nostre Raccolte con la Galleria di Samsung (One UI 8) e ha scelto **«come Samsung»**: caroselli orizzontali di
schede grandi con la foto e il nome sopra, i volti a tutta scheda per le Persone, una mappa in anteprima per i Luoghi. Il metro della fase: *«se non
sapessi leggere, potrei usare l'app?»*. Gli screenshot sono in `docs/screenshots/beta-grafica/I1/` (`*-prima-*` registrati sul codice di partenza
`f7d91f7f`, `*-dopo-*` su questo ramo; telefono 360 x 780 dp e tablet 1280 x 800 dp, chiaro e scuro, testo a 1,3x dove indicato).

Le parole visibili sono contate sugli stessi dati di prova (le sezioni come le consegna `:app`), per tutta la pagina.

| Pagina | Prima | Dopo | Parole visibili |
|---|---|---|---|
| **Raccolte** (`raccolte-*`) | Righe di un gruppo per ogni sezione: piastrella piccola a sinistra, titolo e una frase sotto ("212 foto", "Viaggio · 640 elementi", "Sfoglia, crea, rinomina, sposta ed elimina"). Lo stato in cima su due righe. | Per ogni sezione un titolo grande (con la freccia se porta a «tutti») e un **carosello di schede grandi**: le persone alte con il volto a tutta scheda e il nome sopra la sfumatura (senza nome: solo il volto), i ricordi e i viaggi larghi con il titolo e il mese o «Viaggio», i luoghi con la foto e il nome; in fondo la scheda «tutti». I Luoghi con la **mappa in anteprima** (le foto appuntate dove sono; senza carta il mosaico delle foto). Utility, NAS e Nascosti in **piastrelle** con l'icona espressiva (I3). Gli stati: un invito colorato con l'icona, o una capsula sotto il carosello. Lo stato in cima: una capsula. | 185 -> 85 |
| **Tutte le persone** (`persone-elenco-*`) | Avatar rotondi grandi, due per riga, nome e "5 foto" sotto; sopra le proposte un'intestazione con una frase di due righe; "Altri volti" e "Persone nascoste" con una frase ciascuna. | Schede alte (3:4) con il volto a tutta scheda e il nome sopra la sfumatura, **tre per riga** come la Galleria di Samsung; senza nome solo il volto; niente conti (li dice TalkBack); le righe "Altri volti (3)" e "Persone nascoste (1)" senza frase; la proposta "E' la stessa persona?" e' la riga stessa (niente intestazione doppia). | 57 -> 18 |
| **Altri volti** (`persone-altri-volti-*`) | Sottotitolo di una frase e "5 foto" sotto ogni volto. | Solo i volti, tre per riga. | 17 -> 2 |
| **Pagina di una persona** (`persone-pagina-*`) | "Forse e' anche…" con una frase sotto e righe "Marco · 5 foto · Forse e' la stessa persona". | "Forse e' anche…" e le righe con il volto e il nome. | 27 -> 11 |
| **Tutti i ricordi** (`tutti-i-ricordi-*`) | Righe per mese: copertina piccola, titolo, "9 foto · 3 novembre 2025", capsula "Nuovo". | Una **griglia di schede** per mese (due per riga sul telefono): la copertina a tutta scheda, il titolo sopra la sfumatura, il mese; "nuovo" e' un punto dell'accento. | 37 -> 20 |

## Scelte di disegno che l'utente potrebbe voler cambiare (una riga ciascuna)
- Le misure delle schede: persone 116 x 156 dp, ricordi e viaggi 264 x 176 dp, luoghi 136 dp (sul telefono da 360 dp: 2 persone e mezza, 1 ricordo e un pezzo, 2 luoghi e mezzo).
- Il titolo di ogni sezione e' toccabile con la freccia quando porta a «tutti»; nei **Luoghi** apre la Mappa (non esiste una pagina «Tutti i luoghi»).
- Il carosello finisce con una scheda «Tutte le persone» / «Tutti i ricordi» / «Tutti i viaggi» (freccia nel colore della famiglia), oltre al titolo.
- "Altri volti (3)" e "312 senza posizione" sono capsule piccole sotto il carosello, non schede.
- La mappa e' una carta ferma 2:1 (al piu' 220 dp) con al piu' 5 foto appuntate, la capsula «Mappa» in basso a sinistra e il credito della carta in basso a destra.
- Lo zoom della mappa va da 1 a 8 (un luogo solo: zoom 8, una regione); il margine attorno ai luoghi e' il 18% del lato corto.
- Utility, NAS e Nascosti: piastrelle a due colonne con l'icona del componente condiviso (Eliminati di recente in rosso "pericolo", NAS in blu "backup", Utility in grigio "sistema", Nascosti in nero "sicurezza").
- L'invito di una sezione vuota (Persone spenta, nessun ricordo) ha la forma unica degli stati vuoti (giro 1, CU-23): la figura e il titolo, dentro una scheda che si tocca. Prima era una scheda piena nel colore della famiglia.
- Giro 1: il cestino del NAS si chiama «Cestino del NAS» (cartella con il cestino); i tasti della pagina di una persona sono «Nome»/«Rinomina» e «Controlla» con l'icona; il pannello vuoto del tablet e' solo la figura.
- Giro 1: negli stati vuoti la frase resta solo dove spiega una regola, dietro la «i»; altrove ci sono solo la figura e il titolo.
- "Nuovo" sui ricordi e' un punto dell'accento in alto a destra, senza parola.
- In «Tutte le persone» una persona senza nome non ha piu' l'invito «Aggiungi un nome» sulla scheda: si aggiunge dalla sua pagina.
- Lo stato in cima alle Raccolte e' solo la capsula («In casa»): «NAS collegato» lo dice TalkBack e la pagina che si apre toccandola.

## Giro di correzione 1 (12/11/2026)
Screenshot nuovi nella stessa cartella: `raccolte-copertine-chiare-*` e `persone-elenco-copertine-chiare-*` (copertine e volti quasi bianchi, chiaro e
scuro: il nome si legge con la sfumatura corretta di I4), `raccolte-raccolta-aperta-dopo-*-tablet` (CS-8), gli stati vuoti nella forma comune
(`libreria-*`, `persone-elenco-vuoto/calcolo-*`, `persone-altri-volti-vuoto-*`, `persone-nascoste-vuoto-*`, `persone-pagina-nascosta-*`,
`persone-persona-foto-nascoste-*`, `persone-controlla-*`, `tutti-i-ricordi-vuoto-*`, `pagina-sparita-*`, `presentazione-sparita-*`). Le scelte sono
in `docs/decisioni.md`, «I1, giro di correzione 1».
