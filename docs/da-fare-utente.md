# Cosa resta da fare a te (dopo la Fase 10)

Tutto il resto lo fanno gli agenti. Qui ci sono solo le cose che possono fare solo le tue mani, le tue chiavi o le tue decisioni, in ordine.

## Prima della beta (circa 30 minuti, al PC)
1. **La firma vera.** Aggiungi le quattro righe di `pampa.jks` a `local.properties` della cartella del progetto (non vanno mai in git):
   `pampa.storeFile=C:/percorso/pampa.jks`, `pampa.keyAlias=pampa`, `pampa.storePassword=...`, `pampa.keyPassword=...`.
   Senza, la release esce non firmata (la candidata provata il 02/11 era firmata con una chiave di prova).
2. **L'impronta della firma.** `powershell -ExecutionPolicy Bypass -File tools\release\release.ps1 -Impronta -Scrivi`, poi confrontala con
   la copia offline di `pampa.jks`. Senza, `release.ps1` rifiuta la release.
3. **Le chiavi del manifest K1 e K2** (il "kill switch"): `powershell -ExecutionPolicy Bypass -File tools\release\manifest-tool.ps1 keys`,
   conserva il foglio di K2 offline; poi dimmi "chiavi fatte" e un agente mette `PROVISIONAL = false` (un test controlla che non siano piu' i
   segnaposto).
4. **Il dispositivo della release candidata** e' rimasto nel registro della tua famiglia vera: toglilo da Impostazioni → Dispositivi.

## La prossima finestra con l'S25 (circa 3 ore di prove; tu solo nei primi 20 minuti)
Copione: `tools/security/PROSSIMA-FINESTRA.md`. La famiglia di prova si rifa' da capo sulle share di prova. A te:
- scrivere **una volta** la password di `test`;
- appoggiare il dito **circa quattro volte** sui fogli di sblocco della chiave di famiglia (la chiave chiede l'impronta a ogni uso, M3).
Nella stessa finestra: il riferimento dei benchmark sull'S25 (serve a `release.ps1`, deve avere al piu' 7 giorni).

## Il tablet, momento A (circa 25 minuti, una volta)
Profili "Prova 1" (`test`) e "Prova 2" (`test2`), le password, il tablet collegato al PC per la notte: pannelli, finestre e DeX, trascinamento,
benchmark del tablet (F165–F169, F188).

## Decisioni che aspettano te
- **"Elimina dal telefono" dalla selezione della Libreria** oggi manda subito al cestino di Android, senza il foglio "Prima mettilo al sicuro"
  che usa il viewer. Proposta: con il backup attivo, anche la selezione passa da quel foglio (cambia cio' che vedi).
- **Il passaggio alle share vere** (§2.10): **fatto il 12/11/2026**, con il tuo "si'". Restano il primo backup sull'S25 e la fotografia "dopo" dal
  PC; fino alla fotografia "dopo" meglio non usare "Salva sul NAS" e "Riporta nelle mie cartelle" (scrivono nelle cartelle che scegli tu).
- **La fase grafica**: ora che l'engine 3.0 e' dentro, il piano si fa con te (la tua lista in `docs/grafica-segnalazioni-utente.md`, piu'
  cio' che la 3.0 risolve gia').

## Dopo, quando vuoi
- Le prove del widget e dell'accessibilita' (secondarie, anche dopo la beta).
- Alla fine di tutto: togliere gli utenti `test` e `test2` dal NAS e cambiare la password dell'admin del NAS.
