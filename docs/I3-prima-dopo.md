# I3: Backup e Impostazioni, prima e dopo (fase I, 11/11/2026)

Il metro dell'utente: **«se non sapessi leggere o sentire, potrei usare l'app?»**. Le parole contate sono quelle **visibili** (i testi dei nodi,
non le descrizioni per TalkBack, che restano), pagina intera a 360 dp in chiaro, dalle prove `I3BackupShots` e `I3SettingsShots`
(`build/i3-parole/`). Gli screenshot sono in `docs/screenshots/beta-grafica/I3/`, con `-prima-` e `-dopo-` nel nome.

## Il componente condiviso

`GalleryIconTile` (core-designsystem, `icon/`): una piastrella con il glifo bianco sul **colore pieno della sua famiglia** (foto, backup, persone,
ricordi, luoghi, ricerca, sicurezza, rete, sistema, pericolo), tre taglie (32, 44, 64 dp) e un **segno di stato** facoltativo nell'angolo (spunta,
frecce che girano, pausa, trattino, punto esclamativo, croce), che TalkBack dice a voce. Il vocabolario `GalleryIcons` fa si' che la stessa cosa
abbia lo stesso segno ovunque. Screenshot: `componente-icone-{chiaro,scuro}-telefono.png`.

## Pagina per pagina

| Pagina | Prima | Dopo | Parole prima → dopo |
|---|---|---|---|
| Backup «in prova» (la schermata 2) | scheda «Backup in prova · si scrive solo nelle share di prova», sei righe con l'icona grigia di cartella, la pillola «Fermo» e la stessa frase lunga ripetuta | anello (0%, 0 / 5.968) con il segno di pausa, «In pausa», una riga «Il backup vero non è ancora acceso» con la «i»; sei copertine con la foto più recente, il segno di pausa, una barra e il numero che manca; le righe del NAS con le piastrelle | 215 → 37 |
| Backup, primo backup in corso | scheda con titolo, frase e barra; righe con frasi | anello che avanza con le frecce, «Primo backup», una riga (foto, quante, quanto manca), «Ferma» con l'icona; copertine con la barra del loro avanzamento | 178 → 45 |
| Backup, tutto al sicuro | scheda «Tutto al sicuro» con frase | anello pieno con la spunta, «Al sicuro», ultimo giro, «Fai ora» | 72 → 24 |
| Backup, un problema | scheda del problema con la frase e i tasti; i problemi delle sorgenti come altre schede | anello con il segno del problema, il messaggio, la «i» accanto, i tasti con l'icona; gli altri problemi sono righe con la piastrella che dice la cosa (perché e rimedio a un tocco) | 144 → 39 |
| Backup, in pausa | scheda con la frase lunga | «In pausa» con la «i» (il perché), «Riprendi» con l'icona | 154 → 23 |
| Backup, da preparare | scheda con l'elenco puntato di frasi | i passi che mancano come piastrelle con il segno «attenzione» e un'etichetta | 96 → 35 |
| Impostazioni del backup (la schermata 3) | righe con l'icona grigia di cartella, note lunghe sotto ogni gruppo | copertine delle cartelle, numeri soli («2.105 · 4,2 GB»), il percorso delle copie come figura a pezzi, «Il backup parte con» notte · Wi-Fi di casa · batteria in piastrelle; le note dietro la «i» | 154 → 67 |
| Impostazioni, pagina principale | 8 gruppi; righe con il glifo grigio sulla piastrella del tono, frasi sotto diverse voci | 5 gruppi (Backup e NAS, Foto, Privacy, Famiglia, Avanzate) più «Da completare»; ogni riga con la piastrella della sua famiglia, lo stato a destra o un segno; il backup dice «Al sicuro» con la spunta | 125 → 81 |
| Quando lavora l'app | quattro preset con un paragrafo di sei righe ciascuno, sette tetti con la stessa frase | quattro schede con la piastrella e le condizioni in glifi (notte, corrente, Wi-Fi, dati mobili), il tetto come capsule; le frasi dietro la «i» | 228 → 59 |
| Sicurezza | righe con sottotitoli descrittivi, cinque righe per i tempi | capsule per i tempi (la consigliata con la stella), «Che cosa protegge» come due piastrelle con la spunta; il come dietro la «i» | 121 → 56 |
| Copia esterna del NAS | riquadro con il paragrafo sui due dischi, sei voci numerate con la frase | la figura dei due dischi che si copiano a vicenda e della copia staccata; sei voci con la piastrella che dice la cosa (le prime due con il segno di attenzione), il come a un tocco | 190 → 85 |
| Rete e VPN | frasi sotto i titoli dei gruppi, pillole, sottotitoli descrittivi | segni sulle piastrelle (casa salvata, VPN pronta), lo stato a destra, il come dietro la «i» | 120 → 88 |
| Widget | tre scelte con la frase sotto, quattro paragrafi («Cosa non fa mai», «Come si aggiunge») | le scelte con la piastrella e l'interruttore; le regole e il come dietro la «i» | 124 → 52 |
| Anteprime del NAS | tre livelli con una frase e la misura, frase sotto le copertine | sotto ogni livello la misura sola; che cosa fa il livello scelto dietro la «i» | 76 → 46 |
| Account, famiglia, dispositivi, condivisione, informazioni | glifo grigio sulla piastrella del tono | **solo** le piastrelle espressive per famiglia (stesso glifo, stesso colore ovunque) e i segni; i testi restano quelli di H5 | 80, 103, 164, 71, 312 (invariati) |

## Le conferme

Restano tutte `GalleryConfirmSheet` (con `OverlayShield` dove c'era), stessi tasti, stesso ordine. Si accorcia solo la frase, tenendo l'avvertimento che
conta (non si torna indietro, il kit di recupero, la cartella sul NAS che non cambia): **294 → 187 parole** in dieci conferme.

| Conferma | Parole prima → dopo |
|---|---|
| Nuovo indirizzo del NAS (telefono / admin) | 27 → 18, 28 → 18 |
| Rinomina, dismetti, revoca un dispositivo | 33 → 18, 22 → 17, 26 → 18 |
| Reimposta la famiglia | 21 → 20 |
| Esci dalla famiglia (familiare / admin) | 22 → 18, 64 → 29 |
| È la rete di casa? | 25 → 15 |
| Scarta un file ricevuto | 26 → 16 |

Libera spazio, Primo backup, Versioni e Correzione erano già fatti a righe con titolo e una frase (e i testi di Libera spazio sono legati alle
recinzioni): non li ho toccati.

## Le scelte di disegno che l'utente potrebbe voler cambiare

- Le famiglie hanno **colori pieni fissi** (il backup sempre blu, la sicurezza grafite) anche con un altro accento o con Material You, come le icone di sistema.
- Il glifo è **bianco** su ogni colore; i colori chiari (arancio, verde) si scuriscono quanto basta perché il bianco regga 3:1.
- La copertina di una cartella è **una foto sola** (la più recente), non un mosaico: a 100 dp un mosaico non si legge.
- Le copertine della pagina Backup sono **tre per riga** sul telefono (cinque sul tablet); le cartelle spente sono sbiadite.
- L'anello conta le **sorgenti attive** (non il giro in corso): durante un giro la riga sotto dice il giro, l'anello il totale.
- Il percento non arriva mai a 100% finché manca una foto (99%).
- Lo stato «in prova» si chiama **«In pausa»**, con la riga «Il backup vero non è ancora acceso» e il perché dietro la «i».
- Nelle Impostazioni i gruppi sono cinque e **Diagnostica** e **Informazioni** stanno in «Avanzate».
- «Animazione delle foto in movimento» sta nel gruppo «Foto», senza la frase sotto (TalkBack la legge).
- Il widget nella pagina principale dice solo **ogni quanto** cambia; che cosa mostra lo dice la sua pagina.
- I preset di «Quando lavora l'app» sono **schede 2×2**; il tetto dei dati mobili è una fila di **capsule**.
