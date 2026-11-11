# I4: censimento delle schermate «che parlano troppo» (fase I, 11/11/2026)

Il metro dell'utente: **«se non sapessi leggere o sentire, potrei usare l'app?»**. Qui le schermate di I4 (tutto cio' che non e'
Raccolte, ricerca, Backup e Impostazioni), **in ordine di quanto l'utente le vede**. Le parole visibili sono contate sugli screenshot
Roborazzi gia' nel repository (telefono 360 dp, stato tipico) e sui sorgenti; «senza leggere» dice che cosa si capisce solo guardando.
L'ultima colonna e' l'esito di I4 (fatta / in parte / non toccata e perche'), aggiornata a fine lavoro.

| # | Schermata (modulo) | Parole visibili | Che cosa si capisce senza leggere | Che cosa cambio | Esito I4 |
|---|---|---|---|---|---|
| 1 | Libreria, griglia e capsule in alto (`feature-library`) | ~6 («Oggi · Londra», «1.817 foto, 183 video») | tutto: le foto, la nuvola con il suo stato, la selezione | niente: e' gia' giusta | non toccata (gia' a misura) |
| 2 | Libreria, barra della selezione | 1 («3 selezionati») + 5 icone | tutto | niente | non toccata (gia' a misura) |
| 3 | Libreria, pop-up della nuvola (stato del backup e del NAS) | 30-55 («Sincronizzazione in corso», «Backup in corso: 1.240 di 6.658», «circa 1 h 20 min», «Spazio sul NAS», «1,2 TB usati di 3,6 TB», «Ultima sincronizzazione», «Oggi alle 06:54», «Libera spazio», «Toglie dal telefono le foto gia' al sicuro sul NAS.»; con un problema: capsula + frase di due righe + tasto, per ogni problema) | poco: tre titoli in grassetto e tre frasi, due barre uguali per cose diverse | **anelli che avanzano con il numero dentro** (backup, lettura del NAS, spazio), segni (orologio per l'ultima volta, tasto con l'icona), problemi = segno + due parole + tasto con l'icona, la frase dietro il tocco | vedi report |
| 4 | Viewer, menu «altro» e barra in basso (`feature-viewer`) | 6 voci di 1-3 parole, ognuna con l'icona; Elimina in rosso | tutto | niente (gia' come Samsung/Apple) | non toccata (gia' a misura) |
| 5 | Scheda Album (`feature-albums`) | nome + conteggio per album; il conteggio sta due righe sotto il nome quando il nome e' corto | le copertine, il segno «condiviso» | via il buco fra nome e numero; il numero piu' discreto | vedi report |
| 6 | Pagina di un album | sottotitolo «24 elementi · Dal piu' recente · Lo hai condiviso tu», righe di stato con una frase ciascuna («Si sposta in background: puoi usare l'app.», «Stanno andando nell'area comune. Per ora li vedi solo tu.»), nota «I permessi li fa rispettare l'app: dal PC...» (~60) | le foto | sottotitolo = numero; stato = anello + numero; le frasi dietro il tocco | vedi report |
| 7 | Libreria vuota (e stati vuoti di Preferiti, Nascosti, cestino) | titolo + due frasi (~20) | la figura | figura + titolo corto; l'azione | vedi report |
| 8 | Viewer, fogli delle azioni (elimina ovunque / solo telefono / solo NAS, sposta, rinomina) | 25-60 | i tasti colorati | conferme distruttive: restano frasi (rischio per i dati), accorciate dove ripetono | vedi report |
| 9 | Foglio «Condividi con la famiglia» (album) | ~80 («24 foto e 2 video (186 MB) verranno spostati nell'area comune, nella cartella...», «Restano nascosti e al loro posto.», «I permessi li fa rispettare l'app...») | i tondi delle scelte | una riga di riepilogo con i numeri, scelte con l'icona e una parola, nota tolta (detta una volta nella pagina) | vedi report |
| 10 | Ricezione «Salva sul NAS» (`feature-integrations`) | ~40 («Da un'altra app», «Da salvare», «Dove li salvo», «Non carico niente finche' non tocchi...») | poco | le miniature di cio' che arriva, aree con l'icona, via la frase di garanzia (il tasto dice gia' che cosa fa) | vedi report |
| 11 | Cartelle del NAS (`feature-nas`) | nome + «412 file» o «2,4 MB · 15 settembre 2026» per riga | icona di cartella uguale per tutte | **anteprime** delle foto (le miniature che l'app ha gia') al posto dell'icona del file; il numero da solo | vedi report |
| 12 | Operazioni del NAS in corso | ~110 per tre operazioni («In corso · 40%», «128 di 312 elementi · 1,6 GB di 4,1 GB», «Quello che e' gia' stato fatto resta fatto.» ripetuta, frasi di pausa lunghe) | la barra | **anello con la percentuale** e segno di stato (in corso / in pausa / in attesa / fatto / fallito), numero corto, «Ferma» con l'icona, la frase lunga dietro il tocco, la garanzia detta una volta | vedi report |
| 13 | Cestino del NAS | ~120 a schermo (banner di tre righe, due righe di dettaglio per voce, capsule «Scade presto», «Senza record») | le caselle | una riga per voce (giorni che restano come numero con il segno), il banner = segno + due parole con il dettaglio dietro il tocco | vedi report |
| 14 | Onboarding: Benvenuto | ~60 (sottotitolo, tre righe con titolo e frase) | la figura | tre segni con due parole ciascuno, una riga sola | vedi report |
| 15 | Onboarding: Permessi | ~90 (per permesso: nome, capsula, frase; scheda «modalita' ridotta») | le icone grigie | icone espressive di cio' che si ottiene, una parola, lo stato come segno | vedi report |
| 16 | Onboarding: indirizzo, account, aree, cartelle, sorgenti, ruoli, preset, batteria, VPN, chiave di famiglia, recupero (~80 stati) | 40-120 per pagina | poco | il resto dell'onboarding si vede una volta: fuori da questo giro salvo le parole da cantiere | non toccata (vista una volta; vedi report) |
| 17 | Widget (`feature-widget`) | 0-3 (foto + data) | tutto | niente | non toccata (gia' a misura) |
| 18 | Diagnostica (cio' che l'utente vede) | molte: e' per chi indaga | — | solo le parole da cantiere nei titoli («Test di connessione», «Negoziazione SMB») | vedi report |
| 19 | Avvisi e fogli di `:app` (aggiornamento, novita', errori, conferme del backup dal viewer) | 20-50 | — | parole da cantiere («media», «area di prova») | vedi report |

## Parole da cantiere trovate nelle risorse dei miei moduli
- «Backup in prova» / «Si scrive solo nelle share di prova.» (`feature-library` piede e nuvola) e «Indice condiviso in prova» / «...solo
  sulle share di prova» (stato del NAS): **il testo lo gira l'orchestratore quando I3 consegna**; fino ad allora resta il posto, con
  il segno senza la provetta (`Science`).
- «Esclusa per l'account di prova» (onboarding, aree): solo nella build di debug (D33) — da verificare che non arrivi in release.
- «share» in frasi visibili: cestino del NAS (`nas_trash_footnote`), avvisi della Libreria (`trash_notice_rejected`,
  `duplicates_notice_cleanup_rejected`).
- «media» come sostantivo: Integrazioni (5 frasi), `:app` (1), Album (1: «Data dei media»), Diagnostica (15, ma e' per chi indaga).
- Sigle: «SMB», «SMB 3.1.1» negli errori dell'onboarding (servono a chi configura il NAS: restano, sono istruzioni per la dashboard),
  «Negoziazione SMB» e «codice SMB» in Diagnostica (pagina per chi indaga: restano).
- «area di prova» in `:app` (`fiveb_edit_not_saveable`).
