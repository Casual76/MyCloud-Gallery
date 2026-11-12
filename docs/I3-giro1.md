# I3, giro di correzione 1 della fase I: Backup e Impostazioni (12/11/2026)

Rilievi: `review-fase-I/giro1/TRIAGE.md` (voci di I3: CG-4 = CU-5, CU-6 = CS-3, CS-2, CG-3, CS-7, CU-10, CU-11, CU-16, CU-20 = CS-15,
CU-7 = CS-4, CU-14, CS-5, CS-11, CS-13). Il metro resta quello dell'utente: «se non sapessi leggere, potrei usare l'app?».

Gli screenshot nuovi hanno `-giro1-` nel nome, in `docs/screenshots/beta-grafica/I3/`; il «prima» di ognuno è il `-dopo-` di I3 accanto. Per Libera
spazio il prima è `docs/screenshots/5-P12/libera-spazio-riepilogo-*` al commit `29ca7fa4` (ora quei file mostrano il riepilogo nuovo). Le parole sono
quelle **visibili** (i testi dei nodi, non le descrizioni per TalkBack), contate dalle prove `I3SettingsShots` e `I3BackupShots`
(`build/i3-parole/`), in chiaro.

## Parole, prima → dopo

| Pagina (360 dp) | I3 | Giro 1 |
|---|---|---|
| Impostazioni, pagina principale (intera) | 81 | 65 |
| Rete e VPN (intera) | 88 | 53 |
| Account del NAS (intera) | 80 | 39 |
| Famiglia (intera) | 103 | 65 |
| Dispositivi (intera) | 164 | 69 |
| Condivisioni (intera) | 71 | 36 |
| Informazioni e licenze (intera) | 312 | 284 |
| Sicurezza (intera) | 56 | 39 |
| Copia esterna del NAS (intera) | 85 | 66 |
| Anteprime del NAS (intera) | 46 | 46 |
| Quando lavora l'app (intera) | 59 | 58 |
| Backup «in prova» (schermo) | 24 | 22 |
| Backup, primo backup in corso (intera) | 45 | 42 |
| Libera spazio, riepilogo (intera) | 186 (*) | 98 |
| Libera spazio con il backup in pausa (intera) | – | 82 |

(*) Il riepilogo di Libera spazio non aveva una prova che contasse le parole: 186 è contato dalle stringhe di `29ca7fa4` con gli stessi dati
(1.240 elementi, quattro motivi di esclusione, gli ultimi 30 giorni) e la stessa regola. Informazioni resta lunga di proposito: le attribuzioni
(GeoNames, OpenStreetMap, licenze) sono obblighi di licenza e restano in pagina; le frasi sotto i titoli dei gruppi sono dietro la «i».

## Che cosa è cambiato

- **Sottopagine delle Impostazioni (CG-4 = CU-5).** In cima `SettingsStatusHeader`: la piastrella grande con il segno di stato e il valore corto
  («In casa», «Collegato», «4 dispositivi», «3 attivi», «Attivo»); niente più frase che ripete lo stato (prima un `FluidHero` con titolo e descrizione).
  Le righe dicono lo stato a destra («Salvata», «Pronto», «Spenta», «2 minuti fa», «Da approvare») o con il segno sulla piastrella; le frasi stanno dietro
  la «i» (`SettingsInfo`, ora sulla «i» condivisa) o nella descrizione per TalkBack (`DescribedRow`). Rete: l'interruttore «VPN di casa» quando si può
  usare. Condivisioni: la figura «Sul telefono → Alle altre app» con il luogo tolto. Dispositivi: segni per perso, revocato, dismesso, sosia; le
  richieste con la clessidra. Pagina principale: la scheda della famiglia senza la frase («Approvi i dispositivi nuovi…»), il perché solo negli stati
  che chiedono qualcosa (dietro la «i»), «Rivedi le richieste» come tasto con l'icona; la voce Ricerca con il valore a destra.
- **Copia esterna (CS-7, CU-10).** Il «!» sulla riga della pagina principale c'è solo finché il passo «Il NAS non è un backup» dell'avvio non è deciso,
  con il valore «Senza copia»; la riga lo dice sempre a TalkBack e porta alla pagina. Nella figura nessun segno fisso (prima i dischi con la croce e la
  copia con la spunta, sempre); la copia è `GalleryIcons.ExternalCopy` ovunque; i due segni d'attenzione della lista solo finché il passo non è deciso.
- **Scelte uniche (CU-16).** Widget e Anteprime del NAS: il tondo vuoto e la spunta piena (`ChoiceMark`) al posto degli interruttori.
- **Parole (CU-20 = CS-15).** «SMB 3.1.1» → «Il telefono e il NAS parlano la lingua di rete più recente» (dietro la «i»); «Come il preset · 1 GB» →
  «Come Bilanciato · 1 GB»; «preset» tolto dall'invito; i formati dietro la «i»; «13000 anteprime» → «13.000»; «media» come sostantivo tolto dal backup
  («N foto e video», «Foto e video spariti dall'area comune», «Questa foto non è tua»; §2.6 di `docs/fase5-progetto.md` aggiornato). «CPU» è di I2.
- **Pagina Backup.** CS-2: la pausa si dice una volta (anello ed etichetta); il segno sta solo sulla copertina diversa dalle altre (un problema, una
  cartella spenta: `coverMarkShown`). CS-5: l'anello è `GalleryProgressRing` (traccia del colore dell'arco al 28%, colore della famiglia del backup; mentre
  confronta l'arco gira senza misura), la «i» è `GalleryInfoButton`/`GalleryInfoGlyph`/`GalleryInfoDetail` anche nelle impostazioni del backup.
  CU-11: durante un giro la riga dice solo il tipo e il tempo («Foto · circa 40 min»), la frazione la dice l'anello. CU-7 = CS-4: l'attesa (carica,
  Wi-Fi, notte) ha la clessidra e solo quella, sull'anello e sulla riga «Backup» delle Impostazioni; la pausa resta le due barre. CU-14: «Lavori fermi:
  cambiamenti anomali» come il pop-up; «foto selezionate» come il pop-up. CS-13: col testo a 1,3x le copertine sono due per riga e il nome va su due righe.
  Nessuna piastrella grigia accanto alle colorate (Impostazioni del backup, Widget, Dispositivi, Diagnostica, Informazioni).
- **Copertine oltre la 24esima (CG-3).** Ogni copertina dice quando è sullo schermo (la sua area nella finestra, ritagliata dai genitori) e
  `BackupCovers` chiede le miniature di quelle e di sei sorgenti prima e dopo (`coverWindow`), rimandando la finestra solo quando cambia; senza niente
  sullo schermo, le prime sei. Prima: sempre le prime 24, anche fuori schermo, mai la 25esima.
- **Libera spazio (CU-6 = CS-3).** Il riepilogo è una testata: l'anello con i GB da liberare al centro (la parte piena: quanti sono pronti su tutti quelli
  che si potrebbero togliere), quanti sono, le anteprime di ciò che si toglie (quattro e «+N»; tre col testo grande) e la garanzia in una riga («Prima
  ricontrollo ogni copia.»). Le scelte con la piastrella e il segno di scelta; i tasti con l'icona; chi resta sul telefono come piastrella e numero
  (la clessidra per chi aspetta, le due barre per il backup in pausa); il come e il perché dietro la «i». L'avviso forte resta la scheda piena in cima (C13).
  Le recinzioni non cambiano: cambiano le parole («Backup in pausa», «il backup vero non è ancora acceso: per ora si libera solo la cartella già salvata»),
  mai più «cartella di prova». Le anteprime passano dalla Libreria visibile (`BackupCovers.request`) e si chiedono con `keep`, accanto alla finestra della
  griglia. I fogli di conferma restano `GalleryConfirmSheet` con i loro testi.
- **Prove (CS-11).** `BackupCoversTest`: una foto fuori dalla vista non ha richiesta; una foto nascosta dopo essere stata disegnata smette di essere la
  copertina al passaggio dopo (e la cartella torna alla piastrella); le anteprime di Libera spazio saltano le nascoste; la finestra segue lo schermo; la
  30esima cartella si chiede quando entra. `CatalogSourceNewestTest` (app, catalogo vero in memoria): dal più recente con il tetto per sorgente, solo le
  sorgenti chieste, mai una copia tolta dal telefono o del NAS, fotocamera e screenshot dal percorso.

## Prove sul telefono (le fa l'orchestratore)

1. La pagina Backup «in prova» con le cartelle vere: un solo segno di pausa (sull'anello), nessun segno sulle copertine (salvo una cartella spenta
   o con un problema). Difetto: la pausa su ogni copertina.
2. Impostazioni del backup con 40 cartelle o più e la cache fredda: scorrere fino alla 30esima: la copertina arriva entro un attimo senza aver
   visitato quella cartella nella Libreria. Difetto: resta il glifo.
3. Libera spazio dal pop-up della nuvola: in cima l'avviso rosso, poi i GB grandi nell'anello e quattro anteprime vere; nascondi una di quelle foto e
   riapri: non deve comparire. Col testo a 1,3x: tre anteprime e il «+N» intero.
4. Telefono in carica spenta con il backup in attesa: sulla pagina Backup e sulla riga «Backup» delle Impostazioni la clessidra, non le due barre.
5. TalkBack sulla riga «Copia esterna del NAS» con il passo dell'avvio deciso: dice «Il NAS non è un backup» e apre la pagina.
