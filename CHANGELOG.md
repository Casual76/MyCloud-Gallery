# Novità di MyCloud Gallery

Questo file racconta, in italiano semplice, cosa cambia a ogni versione. È anche il testo che l'app mostra in
Impostazioni → Informazioni e licenze → "Novità di questa versione" e quello che si legge nel Pampa Store.

Come si scrive (per chi prepara una versione):
- una sezione per versione, che comincia con `## ` e il numero della versione (per esempio `## 1.0.0`), la più nuova in alto;
- dentro, fino a quattro titoli, sempre in questo ordine: `### Novità`, `### Correzioni`, `### Sicurezza`, `### Da sapere`;
- sotto ogni titolo, frasi corte che cominciano con `- `. Niente sigle, niente parole da tecnici: le legge tutta la famiglia;
- la copia che sta dentro l'app è `feature-settings/src/main/res/raw/changelog.md` e deve essere uguale a questo file (un test lo controlla).

## 1.1.0-beta.01

### Novità
- L'analisi delle foto ora si vede: una pagina mostra a che punto è, con un nastro delle foto che l'app sta guardando in quel momento.
- Dal visore puoi chiedere "Analizza ora" per una foto: passa davanti alle altre.
- Toccando la nuvola in Libreria vedi anche a che punto è l'analisi e quando è stata salvata l'ultima volta sul NAS.
- Batteria: scegli sotto quale livello l'app smette di fare il backup, l'analisi delle foto, o tutti e due. Lo trovi nelle impostazioni del backup e in quelle dell'analisi. Se non tocchi niente resta al 20%, come prima.

### Correzioni
- Le foto cancellate dal telefono non restano più in attesa di essere salvate: il backup arriva al 100%.
- Quando un lavoro aspetta per la batteria, l'app dice il livello vero, per esempio "Batteria sotto il 60%".

### Sicurezza
- Una foto che cancelli o nascondi esce anche dal nastro dell'analisi.

### Da sapere
- È una versione di prova: arriva solo a chi ha scelto "Beta" in Impostazioni → Aggiornamenti.
- "Fai ora" e "Analizza ora" partono anche sotto il livello di batteria scelto: il livello ferma solo il lavoro che l'app fa da sola.

## 1.0.0

### Novità
- Le foto e i video del telefono e quelli del NAS di casa stanno insieme in un posto solo.
- Il backup sul NAS: le foto nuove si mettono al sicuro quando il telefono è carico e in rete di casa. "Libera spazio" parte solo se lo chiedi, dopo aver controllato che sul NAS ci sia una copia intatta.
- La ricerca: scrivi cosa cerchi e l'app trova le foto. Tutto sul telefono, senza internet.
- Gli album: i tuoi, quelli che si riempiono da soli (viaggi, luoghi, persone) e quelli da condividere con la famiglia.
- Le persone: l'app può riconoscere i volti e raggrupparli, ma solo se lo accendi tu, per te.
- I ricordi ("Un anno fa", i viaggi, il meglio del mese) e la mappa con le foto, con la notifica discreta dei ricordi.
- Il widget per la schermata Home: il ricordo di oggi, oppure foto a caso.
- Sul tablet: due finestre insieme, i pannelli affiancati, la tastiera e il mouse. Puoi trascinare una foto in un'altra app e un file dentro l'app.
- Per chi usa TalkBack o testi grandi: ogni schermata si legge a voce e ogni foto ha la sua descrizione.
- Questa pagina: "Novità di questa versione".
- Gli aggiornamenti dentro l'app, da Impostazioni → Aggiornamenti: "Stabile" (di serie) oppure "Beta", che arriva prima. Quando c'è una versione nuova l'app lo dice una volta, e si aggiorna con un tocco.

### Correzioni
- È la prima versione per la famiglia: non c'è niente da correggere rispetto a prima.

### Sicurezza
- Gli aggiornamenti arrivano da internet, dalla pagina delle versioni dell'app. Prima di installarne uno l'app controlla che il file sia proprio quello pubblicato e che abbia la stessa firma dell'app che hai già; Android fa lo stesso controllo sulla firma.
- Chi amministra può fermare una versione che dà problemi: l'app smette di scrivere sul NAS finché non la aggiorni, ma le tue foto restano dove sono.
- Le foto nascoste non compaiono mai nel widget, e il widget non mostra mai nomi di persone. Il titolo di un ricordo può dire dove è stato.
- Con il blocco dell'app acceso, il widget mostra solo le foto che sono anche nel telefono.

### Da sapere
- Prima la versione di prova: per due settimane solo sui dispositivi di chi amministra. Poi arriva a tutta la famiglia insieme.
- Ogni telefono o tablet nuovo deve essere approvato da chi amministra, prima di vedere le foto del NAS.
- Il NAS non sostituisce una copia fuori casa: per le foto più care serve anche un disco esterno.
