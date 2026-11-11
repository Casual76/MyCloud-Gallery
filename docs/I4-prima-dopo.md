# I4: prima e dopo (fase I, «l'app parla troppo»)

Il metro: **«se non sapessi leggere o sentire, potrei usare l'app?»**. Per ogni pagina toccata: che cosa c'era, che cosa c'e', le parole
visibili prima e dopo (contate sugli screenshot a 360 dp, stato tipico), e le scelte che l'utente potrebbe voler cambiare. Gli screenshot
sono in `docs/screenshots/beta-grafica/I4/` (dopo) e `docs/screenshots/beta-grafica/I4/prima/` (prima, copiati dalle fasi vecchie); quelli
che vivono nelle cartelle delle fasi di origine (`7-P10`, `2-P6`, `4-P14`, `RG-D`) sono stati riregistrati li'.

## Libreria
| Pagina | Prima | Dopo | Parole prima → dopo |
|---|---|---|---|
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
| «Viaggi» (tutte le proposte) | una scheda di testo per proposta: copertina larga, nome, «12 foto · 3–6 mar», capsula «Viaggio», due tasti larghi uno sotto l'altro; sottotitolo della pagina; stati vuoti con due frasi | **griglia di schede** come «Tutti i ricordi» di I1: copertina a tutta scheda 3:4, nome e «Viaggio»/«Evento» sopra la sfumatura, la ✕ di «Non mi interessa» in alto, «Crea album» con il + sotto la scheda; numero e giorni a TalkBack; stati vuoti con il solo titolo | ~25 per proposta → ~5 |
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
| Cartelle del NAS | icona grigia uguale, «2,4 MB · 15 settembre 2026» | **piastrella colorata per tipo** (cartella del NAS blu, foto viola, video rosa, documento grigio), solo il peso (la data la dice TalkBack) | −3 parole per file |
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
l'orchestratore non gira quello di I3.

## Scelte di disegno che l'utente potrebbe voler cambiare
- Pop-up della nuvola: la percentuale dentro l'anello e il conto accanto (si potrebbe tenere solo uno dei due).
- Pop-up: la frase di un problema dietro la «i» della capsula (prima era sempre aperta).
- Album: nessun numero sotto le copertine (Samsung e Apple lo scrivono; il brief diceva di toglierlo).
- Cartelle del NAS: piastrelle colorate per tipo al posto delle anteprime vere (le anteprime chiedono un legame nuovo con le miniature).
- Onboarding: le tre promesse come piastrelle in fila; i percorsi senza la frase sotto.
- Operazioni: la garanzia «quello che e' gia' stato fatto resta fatto» una volta sola, sotto le schede.
- Libreria vuota: «Ancora nessuna foto» al posto di «La Libreria e' vuota».
- «Viaggi»: «Crea album» sotto la scheda e la ✕ sulla copertina (la scheda dei ricordi di I1 e' privata di `feature-memories`: qui la stessa forma, non lo stesso componente).
