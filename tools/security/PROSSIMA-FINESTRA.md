# Prossima finestra con un dispositivo: il copione della revisione 10.4 (Fase 10 P11, seconda parte)

Tutto è pronto e provato contro adb, emulatore e NAS finti (`tools\security\sicurezza-finto.ps1`, `FamilyAreaFakeNasTest`), ma niente è mai
girato su un dispositivo vero o sul NAS vero. I comandi si lanciano dalla cartella del progetto, uno alla volta, in quest'ordine.

## L'utente: una cosa sola, all'inizio (circa 5 minuti di gesti nei primi 20)

Al segnale sonoro, sull'S25: **scrivi la password di `test`** nel campo Password (il nome utente l'ha già scritto lo script) e tocca
"Accedi al NAS". Poi **appoggia il dito** (o scrivi il tuo PIN) ogni volta che compare il foglio di sblocco della chiave di famiglia: quattro
volte nei minuti successivi ("Genera chiave e codice", poi due approvazioni e una revoca dei dispositivi di prova). Dopo puoi andare. Gli agenti non scrivono mai
password e non toccano i fogli di sistema (il motivo dei quattro fogli è la decisione M3 del 31/10, L601).

## Prima del dispositivo (agente, circa 15 minuti)

1. `powershell -ExecutionPolicy Bypass -File tools\security\sicurezza-finto.ps1`: deve dire "Tutto come previsto".
2. La `versionCode` della debug sull'S25 (`adb -s RFCY61FZDHW shell dumpsys package dev.pampa.mycloudgallery.debug`, riga `versionCode=`), poi
   `run-all.ps1 -Livello jvm -Voce S7 -VersionCode <n>` e `run-all.ps1 -Livello jvm -Voce S3 -Breve`: scrivono `build\security\<data>\manifest\`
   e `ostili\`, che i livelli dei dispositivi prendono da soli (la cartella più recente, o `-Preparati`).
3. Per l'emulatore: `:app:assembleDebug` e una release candidata firmata con una **chiave di prova** generata sotto `build\` (mai `pampa.jks`,
   K1 o K2), da passare con `-ReleaseApk`.

## Il livello S25 (circa 3 ore; l'utente solo nei primi 20 minuti)

`powershell -ExecutionPolicy Bypass -File tools\security\run-all.ps1 -Livello s25 -Serial RFCY61FZDHW`
(oppure `tools\chaos\s25.ps1 -Mode security -Serial RFCY61FZDHW`; `-Piano` o `-DryRun` stampano l'ordine e non toccano niente).
Ogni passo sul NAS e' un comando Gradle dal PC (circa 2 minuti, dietro il semaforo): i tempi lo contano.

| Da | Passo | Che cosa | Chi |
|---|---|---|---|
| 0 min | preparazione | permessi di Android con `pm grant` (nessun dialogo), app debug presente | agente |
| 3 | famiglia | **reset della famiglia di prova** (sotto), poi subito i dispositivi di prova del PC "approvato" e "revocato" (approvati con il codice digitato dallo script, "revocato" revocato), poi le verifiche dal PC | **utente: password, poi il dito quattro volte** |
| 35 | S1c | segni `MCG-PRIV-...` nell'indice privato di `test`: Cerca con `test` li trova (controllo); con `test2` solo se c'e' un dispositivo a casa con `test2` (`-Test2Serial`) | agente |
| 43 | S2 | canaglia e revocato scrivono indice, dati personali e un album nell'area della famiglia: rifiuti in Diagnostica, niente in Libreria | agente |
| 55 | S3 | 16 segmenti ostili dell'"approvato" e le 13 immagini in `test-comune/mcg-ostile/`: nessun crollo, memoria sotto 1,5 GB | agente |
| 74 | S7 | le buste di prova in `test-comune/.mycloudgallery/release/manifest.mcgr`, una per volta: `WRITES`, `ALL`, nessun blocco, `seq` vecchio, ostili | agente |
| 119 | S7a | copie manomesse dei modelli sullo scaffale della famiglia (si vedono solo se l'app deve scaricare un modello) | agente |
| 129 | S9 | 2.000 file in `test-comune/mcg-it/<giro>/massa/`, un giro, il cambio di massa: congelamento, numero sbagliato rifiutato, "Ho controllato" | agente |
| 165 | S11, S10 | sonda del trascinamento (dichiarata); intent `OPEN_WIDGET` inventati | agente |
| 169 | pulizia | l'area della famiglia di prova torna com'era (solo il registro della guardia), fotografia finale | agente |

**Il reset della famiglia di prova** (decisione 1 del 02/11; anche da solo: `tools\security\famiglia-di-prova.ps1 -Passo tutto`): (1) fotografia di `test` e `test-comune` dal PC, in sola lettura; (2) `test-comune/.mycloudgallery/family` diventa
`family-vecchia-<giro>` (niente si cancella); (3) sull'app: Impostazioni, Famiglia, "Rientra con un QR nuovo", "Esci e rientra", "Crea la
famiglia", casa, NAS `192.168.178.58`, nome utente `test`, **password dell'utente**, aree `test` e `test-comune`, niente backup automatico,
"Genera chiave e codice" (**dito dell'utente**), il codice del kit nuovo in
`C:\VibeCoded Projects\MyCloud-Gallery-2-note-agenti\famiglia-di-prova\kit.txt` (fuori da git) e riscritto nell'app; (4) subito, i dispositivi
di prova (**dito dell'utente** tre volte); (5) fotografia "dopo" e
confronto: dentro `.mycloudgallery/` solo la famiglia nuova, fuori niente; la famiglia nuova si legge dal registro firmato dalla chiave del kit.
Se l'S25 risulta **admin della famiglia di prima** lo script si ferma: decide l'utente.

## Il livello emulatore (circa 45 minuti, senza utente)

`powershell -ExecutionPolicy Bypass -File tools\security\run-all.ps1 -Livello emulatore -ReleaseApk <release candidata di prova>`

Accende `Pampa_Tablet` su `emulator-5580` con `-tcpdump` solo se è spento (mai `Medium_Phone` né `emulator-5554`), installa la debug se manca e la
release candidata, poi: preflight (ULA nel Wi-Fi?), S8 (b) dalla cattura, S5 con `bmgr`, S4 (bloccata se l'emulatore non è casa, L600), S6 (semi
`MCG-PRIV-…`, logcat, Diagnostica, stack trace: oggi la release ne scrive per `ReadLimitExceededException`, la correzione è del pacchetto C), S7
sulla release (bloccata se non è casa), S13, S11, S12; toglie la release e spegne l'emulatore se l'ha acceso lui.

## Cosa si pulisce alla fine, e cosa resta

- **Si toglie da solo**: nell'area della famiglia di prova tutto ciò che il registro della guardia dice creato dal giro (cartelle dei
  dispositivi di prova, album di prova, richieste, immagini di `mcg-ostile/`, buste del manifest, con il manifest di prima rimesso), il giro di S9
  in `mcg-it/`, il file seme in `mcg-prova` dell'emulatore, la release candidata dall'emulatore, l'emulatore (se acceso dallo script), le chiavi
  dei dispositivi di prova dallo stato del PC.
- **Resta** (L601, L603): `test-comune/.mycloudgallery/family-vecchia-<giro>` e i kit vecchi in `test/.mycloudgallery/recovery/` (li toglie
  l'utente), i due dispositivi di prova nel registro (approvato senza chiave, revocato), il file del kit nuovo, l'S25 admin della famiglia nuova.

## Se qualcosa va storto

- Copione fermo su una schermata che non conosce: la struttura (testi e posizioni) è in `build\security\<data>\schermate\`; si corregge il passo
  in `tools\security\lib\copione-famiglia.ps1` (o `copioni-app.ps1`) e si riparte con `-Passo <id>`.
- `AUTH_FAILED`: tutto si ferma, nessun nuovo tentativo; la password la controlla l'utente.
- I resoconti, di soli numeri, sono in `build\security\<data>\s25.txt`, `emulatore.txt`, `famiglia.txt`.
