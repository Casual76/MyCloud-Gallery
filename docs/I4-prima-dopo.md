# I4: prima e dopo (fase I, «l'app parla troppo»)

Il metro: **«se non sapessi leggere o sentire, potrei usare l'app?»**. Per ogni pagina toccata: che cosa c'era, che cosa c'e', le parole
visibili prima e dopo (contate sugli screenshot a 360 dp, stato tipico), e le scelte che l'utente potrebbe voler cambiare. Gli screenshot
sono in `docs/screenshots/beta-grafica/I4/` (dopo) e `docs/screenshots/beta-grafica/I4/prima/` (prima, copiati dalle fasi vecchie); quelli
che vivono nelle cartelle delle fasi di origine (`7-P10`, `2-P6`, `4-P14`, `RG-D`) sono stati riregistrati li'.

## Libreria
| Pagina | Prima | Dopo | Parole prima → dopo |
|---|---|---|---|
| Pop-up della nuvola, backup in pausa (seguito) | titolo «In pausa» e sotto la capsula «Backup in pausa» con la «i»: lo stato detto due volte | **una volta, nel titolo**: «In pausa» con la «i»; il perche' si apre al tocco sotto il titolo; resta il tasto «Apri Backup». Lo stesso per il backup vero non ancora acceso: «In pausa · Il backup vero non e' ancora acceso», le parole della pagina Backup di I3 (`pop-up-backup-in-pausa-*`, `pop-up-backup-non-ancora-acceso-*`) | 7 → 4 |
| Pop-up della nuvola, lavoro in corso | titolo, capsula «Backup in corso: 1.240 di 6.658» con la freccia, barra, «circa 1 h 20 min», «Spazio sul NAS», «1,2 TB usati di 3,6 TB», barra, «Ultima sincronizzazione», «Oggi alle 06:54», «Libera spazio», «Toglie dal telefono le foto gia' al sicuro sul NAS.» | titolo; **anello con «18%»** e accanto la nuvola che sale e «1.240 / 6.658», l'orologio e «circa 1 h 20 min»; **anello dello spazio con il disco** e «1,2 TB / di 3,6 TB»; il segno della cronologia e l'ora; «Libera spazio» con la scopa | ~40 → ~14 |
| Pop-up, un problema | capsula + frase di due-tre righe + tasto, per ogni problema | capsula (segno e due parole) con la **«i»**: la frase si apre al tocco; tasto con la sua icona (lo stetoscopio della Diagnostica, la nuvola del Backup, la chiave, la VPN) | ~45 → ~14 |
| Pop-up, in coda / lettura del NAS | capsule con le frasi | righe con l'anello (o l'orologio dell'attesa), il conto e la ragione dell'attesa in due parole | ~20 → ~12 |
| Libreria vuota | figura, «La Libreria e' vuota», due frasi | figura, «Ancora nessuna foto» | 20 → 3 |
| Prima lettura | figura, titolo, frase, barra e numero | figura, titolo, barra e numero | 16 → 7 |
| Filtro senza risultati | figura, titolo, frase, «Mostra tutto» | figura, titolo, «Mostra tutto» | 19 → 5 |
| Permesso negato | figura, titolo, due frasi, tasto | lucchetto, «Serve l'accesso alle foto», «Consenti accesso» | 30 → 7 |
| Strisce in cima (accesso parziale, foto in attesa) | piastrella, titolo, frase di due righe, tasto sotto | **una striscia**: piastrella, «Solo le foto scelte» / «12 foto in attesa», tasto sulla stessa riga (a testo grande il tasto torna sotto) | 35 → 9 |

## Album
| Pagina | Prima | Dopo | Parole prima → dopo |
|---|---|---|---|
| Scheda Album | nome e numero sotto ogni copertina | solo il nome (il numero lo dice TalkBack) | 24 → 12 (per 9 album) |
| Pagina di un album | «24 elementi · Dal piu' recente · Lo hai condiviso tu»; righe di stato con una frase ciascuna; piastrelle grigie | «24 elementi»; righe di stato con la **piastrella colorata della famiglia e il segno nell'angolo** (in corso, fatto, attenzione), la frase solo per gli avvisi | ~60 → ~25 |
| «Viaggi» (tutte le proposte) | una scheda di testo per proposta: copertina larga, nome, «12 foto · 3–6 mar», capsula «Viaggio», due tasti larghi uno sotto l'altro; sottotitolo della pagina; stati vuoti con due frasi | **griglia di schede** con la **stessa scheda** di «Tutti i ricordi» e delle Raccolte di I1 (`GalleryCoverCard`, seguito): copertina a tutta scheda 3:4, nome e «Viaggio»/«Evento» sopra la sfumatura, la ✕ di «Non mi interessa» in alto, «Crea album» con il + sotto la scheda; numero e giorni a TalkBack; stati vuoti con il solo titolo | ~25 per proposta → ~5 |
| Foglio «Condividi con la famiglia» | «24 foto e 2 video (186 MB) verranno spostati nell'area comune, nella cartella «...», e li vedranno tutti i familiari.», le scelte con una frase sotto, la nota «Lo puoi cambiare quando vuoi» | «24 foto e 2 video (186 MB) vanno nell'area comune: li vedranno tutti i familiari.», le scelte senza la frase (la cartella la dice la riga «Cartella») | ~80 → ~55 |

## Viewer
| Pagina | Prima | Dopo | Parole |
|---|---|---|---|
| Foglio «Eliminare questa foto?» (NAS) | ogni posto con «Nel cestino di Android / del NAS per 30 giorni.» e in fondo «Restano 30 giorni nel cestino» | i 30 giorni si dicono una volta, in fondo; sulle righe resta solo cio' che cambia (sola lettura, album protetto) | ~45 → ~30 |
| Menu «altro», barra in basso | gia' icona + una o due parole, Elimina in rosso | invariati | — |

## NAS
| Pagina | Prima | Dopo | Parole |
|---|---|---|---|
| Operazioni in corso | per ogni scheda: capsula «In corso · 40%», «128 di 312 elementi · 1,6 GB di 4,1 GB», «Ferma», «Quello che e' gia' stato fatto resta fatto.» | piastrella del tipo con il **segno di stato** nell'angolo, titolo, «128 / 312 · 1,6 / 4,1 GB», **anello con la percentuale**, «Annulla il resto» con l'icona; la garanzia una volta sotto la sezione | ~110 → ~45 (tre schede) |
| Operazioni finite | capsula di stato + frase | piastrella con il segno dell'esito | −1 capsula per riga |
| Cartelle del NAS | icona grigia uguale, «2,4 MB · 15 settembre 2026» | **le anteprime vere** (seguito): una foto o un video che la Libreria conosce mostra la sua miniatura; una cartella mostra la sua copertina (una foto, o il mosaico di quattro) con un piccolo segno di cartella nell'angolo; il resto (PDF, file mai indicizzati, cartelle senza foto, nascosti) tiene la **piastrella colorata per tipo**; solo il peso (la data la dice TalkBack). Screenshot `nas-cartelle-anteprime-*` (prima: `nas-cartelle-cartella-*`), anche mentre le miniature arrivano (`-in-arrivo-`: la piastrella, la riga non salta) | −3 parole per file |
| Cestino del NAS (anche quello dentro la Libreria) | banner di tre righe, «Eliminato da questo dispositivo» su ogni riga, nota con «share» | banner di una riga, il proprio dispositivo non si scrive (TalkBack si'), nota senza parole da cantiere | ~120 → ~80 |

## Onboarding
| Pagina | Prima | Dopo | Parole |
|---|---|---|---|
| Benvenuto | tre righe con titolo e frase (Niente telemetria...), cinque percorsi con una frase ciascuno | **tre piastrelle colorate con due parole sotto**, in fila; i percorsi con la piastrella della loro famiglia (persone, sicurezza) e solo il titolo (la frase la dice TalkBack) | ~110 → ~35 |
| Permessi | per ogni permesso: icona grigia, nome, capsula, frase; nota in fondo | **piastrella di cio' che si ottiene** (campanella, foto, segnaposto, rete) con il segno «fatto» quando e' concesso; la capsula solo quando serve un tocco; via la nota | ~90 → ~40 |

## Integrazioni
| Pagina | Prima | Dopo | Parole |
|---|---|---|---|
| Ricezione «Salva sul NAS» | «Da salvare», «2 foto e 1 video · 25 MB», aree con «Area privata»/«Area comune» scritti sotto, «Non carico niente finche' non tocchi ...» | la piastrella delle foto e il conto; le aree con il **segno** (la persona, il gruppo); via la frase di garanzia | ~40 → ~25 |
| Scelta dell'album | sotto ogni album «Il tuo album» / «Album condiviso: ti chiedo conferma quando...» | la piastrella (album o gruppo); una frase sola in fondo | ~45 → ~25 |
| Selettore, avvisi | «i media che stanno solo sul NAS non si possono consegnare» | «le foto che sono solo sul NAS non si possono dare» | — |

## Parole da cantiere tolte
«share» (cestino del NAS, avvisi della Libreria e dei Doppioni), «media» come sostantivo (Integrazioni, `:app`, ordinamento degli album),
«area di prova» (`:app`), «Test di connessione» / «Negoziazione SMB» / «Connessione TCP» nei titoli della Diagnostica, la provetta
(`Science`) come segno del backup «in prova» (ora la clessidra). Il testo «Backup in prova / share di prova» resta al suo posto finche'
l'orchestratore non gira quello di I3. **Seguito**: girato. Piede della Libreria «In pausa · Il backup vero non e' ancora acceso», ricezione e
Diagnostica «Backup in pausa» con la frase di I3; resta «Indice condiviso in prova» (stato del NAS), per cui I3 non ha dato un testo.

## Scelte di disegno che l'utente potrebbe voler cambiare
- Pop-up della nuvola: la percentuale dentro l'anello e il conto accanto (si potrebbe tenere solo uno dei due).
- Pop-up: la frase di un problema dietro la «i» della capsula (prima era sempre aperta).
- Album: nessun numero sotto le copertine (Samsung e Apple lo scrivono; il brief diceva di toglierlo).
- Cartelle del NAS: **elenco con le anteprime a 44 dp, non una griglia**, anche per le cartelle piene di foto. La pagina e' per mettere in ordine
  (selezione, rinomina, sposta, la riga del nascosto, TalkBack riga per riga): la griglia la fa gia' il viewer, che si apre al tocco su una foto.
  Una griglia per le cartelle «solo foto» si puo' aggiungere dopo, se l'utente la vuole.
- Cartelle del NAS: la copertina di una cartella e' un mosaico di quattro quando ne ha almeno quattro, una foto sola altrimenti; il segno della
  cartella nell'angolo dice che e' una cartella e non una foto.
- Onboarding: le tre promesse come piastrelle in fila; i percorsi senza la frase sotto.
- Operazioni: la garanzia «quello che e' gia' stato fatto resta fatto» una volta sola, sotto le schede.
- Libreria vuota: «Ancora nessuna foto» al posto di «La Libreria e' vuota».
- «Viaggi»: «Crea album» sotto la scheda e la ✕ sulla copertina. La scheda e' ora la stessa dei ricordi (`GalleryCoverCard`): la sfumatura non ha
  piu' il suo angolo arrotondato in cima (prima si vedeva un gradino a meta' scheda).
- Pop-up della nuvola: con la pausa come unica cosa da dire, il titolo ha la «i»; con un problema piu' grave la pausa torna una capsula fra le voci.

## Giro di correzione 1 (12/11)
| Pagina | Prima | Dopo |
|---|---|---|
| Pop-up della nuvola, recinzioni accese (lo stato di oggi) | «In pausa (i)» e sotto «Indice condiviso in prova» con la frase sulle «share di prova» | «In pausa» con le due barre e la «i», una volta; sotto il tasto, lo spazio e l'ultima sincronizzazione (`pop-up-recinzioni-accese-*`) |
| Cartelle del NAS, mosaico | bordo blu e croce bianca della piastrella sotto le foto | fondo della superficie e filo grigio solo fra le celle |
| Operazioni del NAS | frasi di due-tre righe sulle schede in attesa, vuoto con paragrafo e titolo ripetuto | «12 / 40» con la «i»: la frase al tocco sulla testata; vuoto con figura e titolo |
| Pagina di un album | «Condivisione in corso: 7 di 18», lucchetto sui permessi | anello con il percento e «Condivisione · 7 / 18», segno del livello (gruppo, gruppo con il +, occhio) |
| Eliminati di recente | paragrafo di 37-55 parole in cima; righe del NAS con icone grigie, «Da Viaggi / Oslo 2026 · 4,8 MB», «Restano 29 giorni», capsule «Scade presto», «Senza record» | «Restano 30 giorni» con la «i»; piastrella del tipo con il segno, nome su una riga, peso, giorni come numero |
| Onboarding, Permessi | «Da concedere» su ogni riga, scheda della modalita' ridotta con quattro righe | il segno sulla piastrella e la freccia; la scheda con il titolo e la «i» |
| Schede di copertina (Raccolte, Ricordi, Viaggi, Persone) | nome nella parte chiara della sfumatura (2-2,6:1 su foto chiara) | scrim pieno dietro il nome (5,7:1 su una foto bianca) |

## Giro di correzione 2 (12/11)
| Pagina | Prima | Dopo |
|---|---|---|
| Strisce in cima alla Libreria (360 dp, 1,3x) | «12 foto…», «1 …» accanto a «Consenti posizione» | il titolo intero; il tasto sotto quando non c'e' posto |
| Stati vuoti (Viaggi, Tutti i ricordi, Album vuoto) | figura spostata a sinistra | figura al centro |
| Scheda Album vuota, pagine di un album, Suggerite, Diagnostica vuota | titolo e due frasi | figura, titolo, «i»; l'attesa di uno smart con l'anello che gira |
| Pop-up e Operazioni del NAS | attesa con l'orologio o con le due barre; anelli nell'accento | attesa con la clessidra; anelli nel colore del backup |
| Operazioni in coda | «3 elementi · 190 MB, in coda» | «190 MB» (il conto nel titolo, lo stato nel segno) |
| Onboarding, Batteria e VPN | piastrelle pallide, «Da fare» su ogni riga, percorsi di 3-4 righe | piastrelle della famiglia, un segno per riga, percorsi dietro la «i» |
| Cestino del NAS dalle Raccolte | si apriva sul cestino del telefono | si apre sulla sezione «Dal NAS» |

## Giro di rifinitura I6 (12/11)
| Pagina | Prima | Dopo |
|---|---|---|
| Pop-up del backup | arco viola, orologio | arco nel colore del backup, clessidra; pausa con le due barre |
| Strisce in cima alla Libreria (1x) | il tasto sempre sotto | il tasto accanto al titolo quando ci sta |
| Batteria | la «i» su una riga sua; due «i» nella scheda | la «i» a destra; la luna nella scheda |
| VPN, controllo automatico | paragrafo di sei righe sotto l'interruttore | interruttore e «i» |
| Indirizzo, account, aree, cartelle, chiave | piastrelle grigie o pallide | piastrelle della famiglia |
| Scegli e Ricevi, stati con messaggio | figura e titolo composti a mano | la figura di stato comune |
