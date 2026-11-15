# Novità di MyCloud Gallery

Questo file racconta, in italiano semplice, cosa cambia a ogni versione. È anche il testo che l'app mostra in
Impostazioni → Informazioni e licenze → "Novità di questa versione" e quello che si legge nel Pampa Store.

Come si scrive (per chi prepara una versione):
- una sezione per versione, che comincia con `## ` e il numero della versione (per esempio `## 1.0.0`), la più nuova in alto;
- dentro, fino a quattro titoli, sempre in questo ordine: `### Novità`, `### Correzioni`, `### Sicurezza`, `### Da sapere`;
- sotto ogni titolo, frasi corte che cominciano con `- `. Niente sigle, niente parole da tecnici: le legge tutta la famiglia;
- la copia che sta dentro l'app è `feature-settings/src/main/res/raw/changelog.md` e deve essere uguale a questo file (un test lo controlla).

## 1.1.0

### Novità
- Il nastro del backup segue le foto e i video che vengono salvati sul NAS.
- La pagina dell'analisi mostra il lavoro effettivo e il motivo delle attese.

### Correzioni
- Si possono attivare anche gli ultimi album nell'elenco del backup. Le scelte rapide vengono conservate tutte.
- Il caricamento delle cartelle non mostra più per errore che non ne hai scelta nessuna.
- Il backup distingue le copie personali dalle foto presenti solo nell'area comune della famiglia.
- Dopo l'aggiornamento dal NAS riparte anche la preparazione delle foto da analizzare.
- Meno lavoro ripetuto in sottofondo e conteggi più rapidi con librerie grandi.
- Il nastro dell'analisi si ferma durante le attese e quando non ci sono foto in elaborazione.

### Sicurezza
- Le protezioni per la temperatura, la batteria e l'accesso alle foto restano attive anche quando un lavoro riparte.

### Da sapere
- Il caldo, la batteria e la rete possono rimandare backup e analisi: la pagina indica l'attesa in corso.

## 1.1.0-beta.02

### Novità
- Anche il backup mostra il nastro delle foto e dei video che sta salvando, con un movimento breve a ogni completamento.
- Puoi scegliere tutte le cartelle del telefono per il backup, comprese quelle della fotocamera e degli screenshot.

### Correzioni
- "Analizza ora" risponde subito e riavvia anche un lavoro rimandato. Le attese mostrano il motivo aggiornato.
- Le foto non più disponibili sul telefono non creano code fantasma. Il nastro si ferma quando non resta niente da analizzare.
- Un'analisi con il testo ancora da leggere resta da completare, senza rifare il lavoro già salvato.
- La scelta di molti album funziona anche con migliaia di cartelle.

### Sicurezza
- Le anteprime del backup e di "Libera spazio" spariscono quando una foto viene nascosta o l'accesso cambia.
- Il reset della famiglia protegge il passaggio alla nuova chiave e conserva il codice da riscrivere se la schermata viene ricreata.

### Da sapere
- È una versione di prova per il canale Beta.
- Il nastro segue il lavoro effettivo: durante un'attesa o a lavoro finito non mostra nuovi file in elaborazione.

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
