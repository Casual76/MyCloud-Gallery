<#
.SYNOPSIS
  Interruzioni casuali sul telefono: la prova delle 500 interruzioni lato telefono. Tre modi:
    move    (default) durante una mossa di massa sul NAS (Fase 4B, docs/fase4-progetto.md par. 5.5, P15);
    backup  durante il primo backup di `mcg-prova` (Fase 5A, docs/fase5-progetto.md par. 5.3 e 5.4, P6) e, dalla 5B (P13, F43), durante le
            VERSIONI: foto di `mcg-prova` modificate dopo il primo backup (`-Step edit`, come Samsung "Salva") e salvate di nuovo.
    ai      durante lo scaricamento dei modelli e l'indicizzazione AI (Fase 6, docs/fase6-progetto.md par. 5.5, P10): almeno 100
            interruzioni (`kill -9` e Wi-Fi spento per 3-20 s), poi il verificatore dei dati sul telefono (`-Step verify`).
  Come si usa e come si legge il risultato: docs/prova-500.md.

.DESCRIPTION
  --- Modo move ---
  L'utente avvia nell'app una mossa di massa (di norma tra due share di prova, sotto mcg-it/<giro>/), poi lancia questo script.
  A intervalli casuali lo script fa UNA di due cose:
    - uccide il processo dell'app (la morte del processo: l'app deve ripartire dal suo diario locale);
    - spegne il Wi-Fi per qualche secondo e lo riaccende (la rete che cade a meta' di un passo).
  Dopo ogni morte rilancia l'app (se non si chiede altrimenti), cosi' la ripresa parte come all'avvio vero. A fine prova aspetta che la
  ripresa finisca; con -Verify lancia il verificatore degli invarianti sull'area di prova (tools\chaos\area.ps1 -Mode verify).

  --- Modo backup (-Mode backup, con -Step) ---
  Il telefono e' quello vero, con la build DEBUG e l'account di prova: il backup legge SOLO DCIM/mcg-prova/ e Pictures/mcg-prova/ (D13) e scrive in
  <DestShare>/<DestDir>/Backup/ (default test/mcg-campo/Backup/). Quattro passi, in quest'ordine:
    seed       semina `mcg-prova` sul telefono con adb: i file di tools\fixtures (formati misti: JPEG con GPS, HEIC, PNG, WebP, motion photo, screenshot,
               MP4), file jpeg unici fino a -Photos, e -BigVideos video da -BigVideoMb MB fatti SUL telefono (copia di un MP4 piu' byte casuali: niente
               da trasferire); chiede la scansione del MediaStore; scrive il MANIFESTO con `adb shell sha256sum` (impronta, byte, etichetta).
    interrupt  l'utente ha gia' avviato il primo backup nell'app ("Inizia ora"); lo script uccide il processo o spegne il Wi-Fi a tempi casuali (come
               nel modo move), rilancia l'app, aspetta che la ripresa finisca e, con -Verify, passa a `verify`.
    edit       (5B) a primo backup finito: modifica -Edits foto di `mcg-prova` aggiungendo in coda 24 byte casuali (un JPEG li ignora: Samsung "Salva"
               sul telefono) e scrive nel manifesto una riga `edit=<impronta vecchia>|<impronta nuova>|<etichetta>` per ognuna. Poi l'utente rifa il
               backup nell'app ("Fai ora") e si lancia `interrupt`: l'originale deve finire in `Versioni/`, la modifica al solito percorso (invarianti
               12 e 13). Sul telefono si possono provare anche "Ripristina originale" (lo scambio) e "Libera spazio" durante le interruzioni.
    verify     controlla che il telefono non sia cambiato (le impronte tornano; le modifiche del manifesto contano, e con -AllowFreed anche i file che
               "Libera spazio" ha tolto) e lancia il verificatore dal PC sull'area di prova, in sola lettura:
               tools\chaos\area.ps1 -Mode backup -Stage verify -Manifest <il manifesto>.
    clean      toglie `mcg-prova` dal telefono (solo quelle due cartelle, con -Yes).
  Lo script non scrive sul NAS e non legge altro dal telefono che `mcg-prova`. Il verificatore dal PC sta in core-backup (BackupAreaVerifier).

  --- Modo ai (-Mode ai, Fase 6, P10; -Step interrupt | verify) ---
  Prima, a mano: l'S25 con la build DEBUG e l'account di prova ha gia' fatto il primo backup di `mcg-prova` (modo backup) e nell'app il
  ruolo Indicizzatore e' acceso (Ricerca e modelli); i modelli si scaricano da soli (con -KillMode force-stop si prova anche la ripresa dal
  rilancio). Poi:
    interrupt  l'utente ha gia' fatto partire lo scaricamento o l'indicizzazione ("Indicizza ora"); lo script uccide il processo (`kill -9`
               dalla build debug) o spegne il Wi-Fi per 3-20 s a tempi casuali (come nel modo move), rilancia l'app, aspetta che la ripresa
               finisca e, con -Verify, passa a `verify`. Servono almeno 100 interruzioni (par. 5.5): meno si fanno, ma lo script lo dice.
    verify     il verificatore dei dati sul telefono, in SOLA LETTURA e solo nella cartella dell'app di debug (`run-as`, mai altre app):
                 - models/: ogni file ha il nome, la dimensione e lo SHA-256 del catalogo (I2: nessun parziale promosso; I1: niente di
                   non verificato da caricare); models/partial/ vuota a ripresa finita (I9);
                 - ai-buffer/: nessun temporaneo orfano (`*.tmp`) e al massimo 500 MB (I9);
                 - search/: i record del file dei vettori hanno il CRC giusto (I5): un'interruzione puo' rovinare al piu' i record di UNA
                   scrittura (fino a 9, un video); di piu' e' un errore. Il file si copia in build\chaos\ (nessun dato esce dal PC).
               Il verificatore dei dati sul NAS (teste `ai` sotto devices/<io>/ai/, invarianti 5 e 6) non e' di questo script: sta nel modulo
               che vede il NAS (vedi docs/prova-500.md, sezione della Fase 6). Con -NotSettled la prova non e' ancora ferma: i parziali non
               contano come errore.
    eval-push  (DB-4, F54) porta il set OCR sintetico (-EvalSource: la cartella MCG-ocr di tools\fixtures\generate-ocr.ps1, con manifest.tsv e
               immagini\) dentro la cartella dell'app di debug, dove lo strumento "Valutazione della ricerca" lo cerca (no_backup/eval/ocr/): `adb push` in
               /data/local/tmp (la shell la scrive), poi `run-as <pacchetto> cp` (l'app di debug e' l'unica che puo' scrivere nella sua cartella), poi si
               toglie la copia temporanea. Da shell la cartella dell'app non si scrive senza `run-as`.
    eval-pull  (DB-4, F54) porta sul PC cio' che la valutazione ha scritto: le etichette (labels.mcgeval), i resoconti (report-NN.txt) e la lettura di
               prova (ocr/letto.tsv, ocr/ocr-resoconto.txt), con `exec-out run-as <pacchetto> cat`, in -EvalDir (default build\chaos\eval-<data>).
               Le etichette sono dati privati: mai nel repository (la cartella di default e' sotto build\, ignorata da git).
  Guardie del modo ai (le stesse degli altri modi, un po' piu' strette): un solo telefono, RFCY61FZDHW (con un adb finto, MCG_ADB, vale il
  numero che si vuole); solo l'app di debug dev.pampa.mycloudgallery.debug; il Wi-Fi si spegne solo con `svc wifi disable` di questo script,
  per al massimo 120 s, e si riaccende SEMPRE (anche se lo script e' interrotto); sul telefono si leggono solo file della cartella dell'app.
  Con -DryRun si vede il calendario senza telefono; con -SelfTest si provano, senza telefono, il controllo del file dei vettori (record
  buoni, rovinati nel mezzo, troncati in coda, nel formato e con il nome che scrive l'app), la lettura del catalogo dei modelli e le guardie;
  tools\chaos\s25-ai-finto.ps1 prova i passi contro l'adb finto, compresa la morte del processo (`pidof`, `kill -9`, rilancio).

  Il calendario delle interruzioni (quando e che cosa) dipende solo dal seme: lo stesso -Seed rifa' la stessa prova. Con -DryRun lo script
  stampa il calendario (o il piano della semina) e i comandi adb che userebbe e NON ESEGUE NIENTE (nemmeno adb): e' il modo di controllarlo
  senza il telefono. Con -SelfTest prova solo la conversione dell'uscita di `sha256sum` nel manifesto, su un testo finto.
  Con la variabile MCG_ADB che punta a un adb FINTO (uno script che imita il telefono in una cartella) si prova l'intera semina e la
  verifica senza telefono: lo fa tools\chaos\s25-finto.ps1, che lancia tutti i passi contro tools\chaos\fake-adb\ e controlla gli esiti.

  Sicurezza: tocca un solo dispositivo (-Serial, o ANDROID_SERIAL), mai "tutti"; rifiuta di partire senza un numero di serie vero
  (ANDROID_SERIAL=none vale "nessun dispositivo": solo -DryRun e -SelfTest); il Wi-Fi viene SEMPRE riacceso, anche se lo script e' interrotto.
  Funziona sulla build debug (run-as) con gli account di prova (par. 5.7); il modo backup rifiuta un pacchetto che non sia di debug.

.PARAMETER Mode                move | backup | ai (default move).
.PARAMETER Step                Per backup: seed | edit | interrupt | verify | clean (default interrupt). Per ai: interrupt | verify.
.PARAMETER Serial              Numero di serie del telefono (`adb devices`). Default: ANDROID_SERIAL.
.PARAMETER Package             Il pacchetto dell'app (default: la build debug, dev.pampa.mycloudgallery.debug).
.PARAMETER Interruptions       Quante interruzioni (default 100, come chiede il par. 5.5 / 5.4 per il telefono).
.PARAMETER Seed                Il seme del calendario (default: casuale; lo script lo stampa, cosi' la prova si rifa'). Per la semina: il seme dei file unici.
.PARAMETER MinGapSeconds       Attesa minima fra due interruzioni (default 6).
.PARAMETER MaxGapSeconds       Attesa massima fra due interruzioni (default 40).
.PARAMETER WifiPercent         Su cento interruzioni, quante sono col Wi-Fi (le altre uccidono il processo; default 40).
.PARAMETER WifiOffMinSeconds   Il Wi-Fi resta spento almeno questo (default 3).
.PARAMETER WifiOffMaxSeconds   ...e al massimo questo (default 20).
.PARAMETER KillMode            Come si uccide il processo: run-as (kill -9 dalla build debug; poi `am crash` se non riesce), crash
                               (am crash), am-kill (am kill: uccide solo un processo non in primo piano), force-stop (am force-stop:
                               toglie anche i lavori in coda, quindi la ripresa parte solo dal rilancio).
.PARAMETER RelaunchDelaySeconds  Dopo una morte, quanto aspettare prima di rilanciare l'app (default 4).
.PARAMETER NoRelaunch          Non rilancia l'app dopo una morte (la ripresa e' lasciata al sistema).
.PARAMETER SettleSeconds       A fine prova: quanto aspettare che la ripresa finisca (default 180; per il backup con i video grandi alzarlo).
.PARAMETER Manifest            Il manifesto: per move, quello scritto da `area.ps1 -Mode seed`; per backup, quello scritto da `-Step seed` (o da leggere).
.PARAMETER Verify              A fine prova lancia il verificatore sull'area (serve -Manifest in move; in backup il manifesto di `seed`).
.PARAMETER Log                 Dove scrivere il racconto (default: build\chaos\s25-<data>.log).
.PARAMETER Edits               Per backup/edit: quante foto modificare (default 20).
.PARAMETER AllowFreed          Per backup/verify: i file che "Libera spazio" ha tolto dal telefono non sono un cambiamento (le altre impronte devono tornare).
.PARAMETER Photos              Per backup/seed: quante foto in tutto (default 300).
.PARAMETER BigVideos           Per backup/seed: quanti video grandi (default 2).
.PARAMETER BigVideoMb          Per backup/seed: quanto e' grande ciascuno, in MB (default 2560).
.PARAMETER FixturesDir         Per backup/seed: la cartella dei file di tools\fixtures gia' generati (default: li genera con generate.ps1 -SkipBig).
.PARAMETER DestShare           Per backup: la share di destinazione (default test).
.PARAMETER DestDir             Per backup: la cartella sotto cui sta Backup/ (D13, default mcg-campo).
.PARAMETER Device              Per backup: l'id del dispositivo del backup (Diagnostica), per riconoscere i suoi temporanei. Senza, qualunque `-bk-`.
.PARAMETER Run                 Per backup: il nome del giro (default: nuovo, aaaammgg-hhmmss-<8 esadecimali>).
.PARAMETER SkipNas             Per backup/verify: controlla solo le impronte del telefono, senza lanciare il verificatore sul NAS.
.PARAMETER Yes                 Per backup/clean: serve per togliere `mcg-prova` dal telefono.
.PARAMETER DryRun              Stampa il calendario/il piano e i comandi, non esegue niente.
.PARAMETER CatalogFile         Per ai/verify: il file Kotlin da cui leggere il catalogo dei modelli (default: ModelCatalog.kt di core-models). Serve solo
                               a provare lo script con l'adb finto, che non puo' tenere file da centinaia di MB.
.PARAMETER NotSettled          Per ai/verify: la prova non e' ancora ferma, un parziale in models/partial/ non e' un errore.
.PARAMETER EvalSource          Per ai/eval-push: la cartella del set OCR (manifest.tsv e immagini\), uscita di tools\fixtures\generate-ocr.ps1.
.PARAMETER EvalDir             Per ai/eval-pull: dove mettere cio' che la valutazione ha scritto (default build\chaos\eval-<data>).
.PARAMETER SelfTest            Prova la conversione dell'uscita di sha256sum nel manifesto, su un testo finto (nessun adb); con -Mode ai prova anche il
                               verificatore dei dati (file dei vettori, catalogo dei modelli) e le guardie.

.EXAMPLE
  # Controllo a secco, senza telefono:
  powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -DryRun -Interruptions 10 -Seed 42

.EXAMPLE
  # La prova vera sul telefono (dopo aver avviato la mossa di massa nell'app):
  powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Serial R5CX1234567 -Interruptions 100 -Manifest build\chaos\manifest-20261016-101500.txt -Verify

.EXAMPLE
  # Il backup: semina, (l'utente avvia il primo backup nell'app), interrompi e verifica.
  powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step seed -DryRun
  powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step seed -Serial R5CX1234567 -Run 20261016-101500-1a2b3c4d
  powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step interrupt -Serial R5CX1234567 -Interruptions 100 -Manifest build\chaos\backup-manifest-20261016-101500-1a2b3c4d.txt -Verify
#>
[CmdletBinding()]
param(
  [ValidateSet("move", "backup", "ai")][string]$Mode = "move",
  [ValidateSet("seed", "edit", "interrupt", "verify", "clean", "eval-push", "eval-pull")][string]$Step = "interrupt",
  [string]$Serial = $env:ANDROID_SERIAL,
  [string]$Package = "dev.pampa.mycloudgallery.debug",
  [int]$Interruptions = 100,
  [int]$Seed = 0,
  [int]$MinGapSeconds = 6,
  [int]$MaxGapSeconds = 40,
  [int]$WifiPercent = 40,
  [int]$WifiOffMinSeconds = 3,
  [int]$WifiOffMaxSeconds = 20,
  [ValidateSet("run-as", "crash", "am-kill", "force-stop")][string]$KillMode = "run-as",
  [int]$RelaunchDelaySeconds = 4,
  [switch]$NoRelaunch,
  [int]$SettleSeconds = 180,
  [string]$Manifest,
  [switch]$Verify,
  [string]$Log,
  [int]$Photos = 300,
  [int]$Edits = 20,
  [switch]$AllowFreed,
  [int]$BigVideos = 2,
  [int]$BigVideoMb = 2560,
  [string]$FixturesDir,
  [string]$DestShare = "test",
  [string]$DestDir = "mcg-campo",
  [string]$Device,
  [string]$Run,
  [switch]$Yes,
  [switch]$SkipNas,
  [switch]$NotSettled,
  [string]$CatalogFile,
  [string]$EvalSource,
  [string]$EvalDir,
  [switch]$DryRun,
  [switch]$SelfTest
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$isBackup = ($Mode -eq "backup")
$isAi = ($Mode -eq "ai")

function Fail([string]$message, [int]$code = 1) {
  Write-Host $message -ForegroundColor Red
  exit $code
}

# --- Le cartelle del telefono che il backup puo' leggere (D13): scritte qui, mai da un parametro -------------------------------------
$PhoneFolders = @("/sdcard/DCIM/mcg-prova", "/sdcard/Pictures/mcg-prova")

# --- O-1 delle prove sul campo: i fixture finti (non sono media, E-M12). L'app li ignora e li segnala, il backup NON li carica: nel manifesto
# stanno come `notmedia=` e sul NAS devono mancare (la verifica dell'area da' errore se ci sono, mai se mancano). Nomi scritti qui, minuscoli.
$NotMediaFixtures = @("casuali.png", "testo.jpg", "video-falso.mp4")

# --- L'uscita di `sha256sum` e di `stat` sul telefono diventa il manifesto (par. 5.4: il manifesto da `adb shell sha256sum`) -------------
# hashLines: "<64 esadecimali>  <percorso>"; sizeLines: "<byte> <percorso>". L'etichetta e' solo il nome del file (la cartella e' `mcg-prova`).
function ConvertTo-PhoneFileList([string[]]$hashLines, [string[]]$sizeLines) {
  $sizes = @{}
  foreach ($line in $sizeLines) {
    if ($line -match '^\s*(\d+)\s+(.+?)\s*$') { $sizes[$Matches[2]] = [long]$Matches[1] }
  }
  $files = @()
  foreach ($line in $hashLines) {
    if ($line -match '^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$') {
      $path = $Matches[2]
      $sha = $Matches[1].ToLowerInvariant()
      if (-not $sizes.ContainsKey($path)) { throw "Manca la dimensione di un file del telefono." }
      # Un file a zero byte non e' un media da salvare (E-M12): il backup non lo carica, quindi non entra nel manifesto.
      if ($sizes[$path] -le 0) { continue }
      $label = ($path -split '/')[-1]
      # O-1: un fixture finto resta nell'elenco (il telefono lo ha) ma e' segnato: nel manifesto e' `notmedia=`, non `phone=`.
      $notMedia = $NotMediaFixtures -contains $label.ToLowerInvariant()
      $files += [pscustomobject]@{ Sha = $sha; Size = $sizes[$path]; Label = $label; Path = $path; NotMedia = $notMedia }
    }
  }
  return $files
}

function New-BackupManifestText([string]$runId, [string]$deviceId, [string]$destShare, [string]$destDir, $files) {
  $lines = @("# mcg-backup-manifest 1", "run=$runId", "device=$deviceId", "dest=$destShare|$destDir", "app=$destShare|")
  foreach ($file in $files) {
    $key = if ($file.NotMedia) { "notmedia" } else { "phone" }
    $lines += ("{0}={1}|{2}|{3}" -f $key, $file.Sha, $file.Size, $file.Label)
  }
  return ($lines -join "`n") + "`n"
}

# =================================================================================================================================
# Modo ai (Fase 6, P10): le parti che non toccano il telefono (guardie, catalogo dei modelli, controllo del file dei vettori)
# =================================================================================================================================

# Le guardie del modo ai: un solo telefono, solo la nostra app di debug, il Wi-Fi al massimo 120 s. Valori scritti qui, mai da un parametro.
$AiSerial = "RFCY61FZDHW"
$AiPackage = "dev.pampa.mycloudgallery.debug"
$AiWifiMaxSeconds = 120
$AiRecommendedInterruptions = 100

# Le cartelle dell'app di debug (relative alla sua cartella, `run-as`): `noBackupFilesDir/...`. Se P11 le sposta, si cambiano solo qui.
$AiModelsDir = "no_backup/models"
$AiBufferDir = "no_backup/ai-buffer"
$AiSearchDir = "no_backup/search"
$AiBufferMaxBytes = 500MB          # AiBuffer.MAX_BYTES (core-ai, contract/Compute.kt)
$AiVectorDimension = 768           # spazio 1, embeddinggemma2-float-v1 (core-trust, SpaceCode)
$AiMaxBadRecordsPerKill = 9        # una scrittura e' un media: al piu' 9 vettori (video, D11); piu' di cosi' per morte non si spiega
$AiSpaceCodeFile = Join-Path $root "core-model\src\main\kotlin\dev\pampa\mycloudgallery\core\model\AiCodes.kt"
$AiEvalDir = "no_backup/eval"      # EvalStore: noBackupFilesDir/eval
$AiEvalTemp = "/data/local/tmp/mcg-eval"
# Cio' che la valutazione scrive e che si porta sul PC: solo questi nomi (percorso relativo a no_backup/eval/).
$AiEvalPullNames = '^(labels\.mcgeval|report-[0-9]{2,}\.txt|ocr/letto\.tsv|ocr/ocr-resoconto\.txt)$'

function Get-AiGuardErrors([string]$serial, [string]$package, [int]$wifiMax, [bool]$fakeAdb, [bool]$dryRun) {
  $errors = @()
  # Un adb finto (MCG_ADB) o il controllo a secco senza telefono non toccano un telefono vero: il numero di serie non conta.
  if (-not $fakeAdb) {
    if ($serial -and $serial -ne $AiSerial) { $errors += "Il modo ai tocca un solo telefono, $AiSerial (-Serial)." }
    if (-not $serial -and -not $dryRun) { $errors += "Il modo ai vuole -Serial $AiSerial (o ANDROID_SERIAL)." }
  }
  if ($serial -and $serial -notmatch '^[A-Za-z0-9]{3,40}$') { $errors += "Numero di serie non valido." }
  if ($package -ne $AiPackage) { $errors += "Il modo ai vale solo per l'app di debug $AiPackage (-Package)." }
  if ($wifiMax -gt $AiWifiMaxSeconds) { $errors += "Il Wi-Fi resta spento al massimo $AiWifiMaxSeconds secondi (-WifiOffMaxSeconds)." }
  return $errors
}

# DB-2: il file dei vettori si chiama `vectors-<codice dello spazio>-<generazione>.bin` (LocalAiIndex.vectorFileFor); il codice dello spazio corrente e'
# quello di `SpaceCode.EMBEDDINGGEMMA2_FLOAT_V1` in AiCodes.kt (core-model). Letto dal sorgente come il catalogo dei modelli: se cambia, lo script lo segue.
function Get-AiSpaceCode([string]$source) {
  $m = [regex]::Match($source, 'EMBEDDINGGEMMA2_FLOAT_V1\(\s*1\s*,\s*"([a-z0-9_]+)"')
  if (-not $m.Success) { throw "Il codice dello spazio dei vettori non si legge (AiCodes.kt)." }
  return $m.Groups[1].Value
}

function Get-AiVectorNamePattern([string]$spaceCode) { return '^vectors-' + [regex]::Escape($spaceCode) + '-[0-9]+\.bin$' }

# Il catalogo dei modelli letto dal sorgente (ModelCatalog.kt, in questa cartella): nome del file, dimensione e SHA-256 di ogni modello.
function ConvertTo-ModelCatalog([string]$source) {
  $names = @([regex]::Matches($source, 'fileName\s*=\s*"([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
  $sizes = @([regex]::Matches($source, 'sizeBytes\s*=\s*([0-9_]+)L') | ForEach-Object { [long]($_.Groups[1].Value -replace '_', '') })
  $shas = @([regex]::Matches($source, 'sha256\s*=\s*Sha256\("([0-9a-f]{64})"\)') | ForEach-Object { $_.Groups[1].Value })
  if ($names.Count -eq 0 -or $names.Count -ne $sizes.Count -or $names.Count -ne $shas.Count) { throw "Il catalogo dei modelli non si legge (nomi $($names.Count), dimensioni $($sizes.Count), impronte $($shas.Count))." }
  $catalog = @()
  for ($i = 0; $i -lt $names.Count; $i++) { $catalog += [pscustomobject]@{ Name = $names[$i]; Size = $sizes[$i]; Sha = $shas[$i] } }
  return $catalog
}

# Il controllo del file dei vettori (core-search, VectorFile.kt): record `MCGV` (4) versione (1) ricetta (1) fotogramma (1) bit (1) chiave (32)
# istante (4) `dim` float16 CRC32 (4, little-endian) dei byte precedenti. In C# perche' in PowerShell 16 MB di CRC durerebbero minuti.
# DB-2: il file e' little-endian (`ByteBuffer.order(LITTLE_ENDIAN)`), quindi `putInt(0x4D434756)` scrive i byte `56 47 43 4D`, non `4D 43 47 56`.
function Initialize-VectorChecker {
  if ('McgVectorCheck' -as [type]) { return }
  Add-Type -TypeDefinition @'
using System;
public static class McgVectorCheck {
  static readonly uint[] Table = MakeTable();
  static uint[] MakeTable() {
    uint[] t = new uint[256];
    for (uint i = 0; i < 256; i++) { uint c = i; for (int k = 0; k < 8; k++) c = ((c & 1) != 0) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1); t[i] = c; }
    return t;
  }
  public static uint Crc32(byte[] b, int off, int len) {
    uint c = 0xFFFFFFFFu;
    for (int i = off; i < off + len; i++) c = Table[(c ^ b[i]) & 0xFF] ^ (c >> 8);
    return c ^ 0xFFFFFFFFu;
  }
  public static byte[] MagicBytes() { return new byte[] { 0x56, 0x47, 0x43, 0x4D }; }
  static bool Magic(byte[] b, int p) { return p + 4 <= b.Length && b[p] == 0x56 && b[p + 1] == 0x47 && b[p + 2] == 0x43 && b[p + 3] == 0x4D; }
  static bool Good(byte[] b, int p, int size) {
    return p + size <= b.Length && Magic(b, p) && Crc32(b, p, size - 4) == BitConverter.ToUInt32(b, p + size - 4);
  }
  // Restituisce { buoni, rovinati, coda troncata (byte), byte scartati }. Un record rovinato nel mezzo (CRC) e' contato e si riparte dal
  // primo record buono dopo; una coda piu' corta di un record e' una scrittura interrotta (se comincia con MCGV) o spazzatura.
  public static int[] Check(byte[] data, int dimension) {
    int size = 44 + 2 * dimension + 4;
    int pos = 0, valid = 0, bad = 0, torn = 0, garbage = 0;
    while (pos < data.Length) {
      if (data.Length - pos < size) {
        if (Magic(data, pos)) torn = data.Length - pos; else garbage += data.Length - pos;
        break;
      }
      if (Good(data, pos, size)) { valid++; pos += size; continue; }
      bool header = Magic(data, pos);
      int next = pos + 1;
      while (next + size <= data.Length && !Good(data, next, size)) next++;
      if (next + size > data.Length) { int rest = data.Length - pos; if (header && rest < size * 2) torn = rest; else garbage += rest; break; }
      // Una zona rovinata lunga n record conta n (e non uno): un record a meta' seguito da altri conta uno.
      if (header) bad += Math.Max(1, (next - pos + size / 2) / size);
      garbage += next - pos;
      pos = next;
    }
    return new int[] { valid, bad, torn, garbage };
  }
}
'@
}

function Test-VectorFile([byte[]]$bytes, [int]$dimension) {
  Initialize-VectorChecker
  $r = [McgVectorCheck]::Check($bytes, $dimension)
  return [pscustomobject]@{ Valid = $r[0]; Bad = $r[1]; TornTail = $r[2]; GarbageBytes = $r[3] }
}

# Un record sintetico per l'autotest e per l'adb finto: la stessa forma che scrive `VectorFile.append` (DB-2), byte per byte.
# `MCGV` come `putInt(0x4D434756)` in little-endian (56 47 43 4D), versione 1, ricetta 1, fotogramma 0, nessun istante (bit 0), chiave di 32 byte dal seme,
# istante -1 (FF FF FF FF), `dim` float16 (small-endian: un valore vicino a 1,0), CRC32 little-endian dei byte precedenti.
function New-VectorRecord([int]$dimension, [int]$seed) {
  Initialize-VectorChecker
  $size = 44 + 2 * $dimension + 4
  $record = New-Object byte[] $size
  [byte[]]$magic = 0x56, 0x47, 0x43, 0x4D
  [Array]::Copy($magic, 0, $record, 0, 4)
  $record[4] = 1; $record[5] = 1; $record[6] = 0; $record[7] = 0
  $random = New-Object System.Random($seed)
  $key = New-Object byte[] 32
  $random.NextBytes($key)
  [Array]::Copy($key, 0, $record, 8, 32)
  $record[40] = 0xFF; $record[41] = 0xFF; $record[42] = 0xFF; $record[43] = 0xFF
  for ($i = 0; $i -lt $dimension; $i++) {
    $half = 0x3000 + $random.Next(0, 0x400)
    $record[44 + 2 * $i] = [byte]($half -band 0xFF)
    $record[45 + 2 * $i] = [byte](($half -shr 8) -band 0xFF)
  }
  $crc = [McgVectorCheck]::Crc32($record, 0, $size - 4)
  [Array]::Copy([BitConverter]::GetBytes([uint32]$crc), 0, $record, $size - 4, 4)
  return , $record
}

# DB-2: i problemi di UN file dei vettori. Un'interruzione rovina al piu' i record di una scrittura (se ne occupa il totale, in Invoke-VerifyAi), ma un
# file NON vuoto senza nemmeno un record buono non e' una scrittura interrotta: e' un file che l'app non sa leggere (nome giusto, formato sbagliato).
# Fa eccezione la sola coda di una scrittura interrotta (il processo e' morto durante il primo `append`: il file e' meno di un record, con l'intestazione).
function Get-VectorFileProblems($check, [long]$size) {
  $problems = @()
  if ($size -gt 0 -and $check.Valid -eq 0 -and $check.TornTail -ne $size) {
    $problems += "I5: un file dei vettori di $size byte senza nessun record riconoscibile (nome giusto, formato sbagliato?)"
  }
  return $problems
}

function Join-Bytes([object[]]$parts) {
  $stream = New-Object System.IO.MemoryStream
  foreach ($part in $parts) { $stream.Write([byte[]]$part, 0, ([byte[]]$part).Length) }
  return , $stream.ToArray()
}

function Invoke-AiSelfTest {
  $failed = 0
  function Check-Ai([string]$what, [bool]$ok) {
    if ($ok) { Write-Host ("  OK    {0}" -f $what) -ForegroundColor Green } else { Write-Host ("  FAIL  {0}" -f $what) -ForegroundColor Red; $script:aiSelfTestFailed++ }
  }
  $script:aiSelfTestFailed = 0
  $dim = 8
  $r = @(1..6 | ForEach-Object { , (New-VectorRecord $dim $_) })
  $size = $r[0].Length
  # 1. Cinque record buoni.
  $x = Test-VectorFile (Join-Bytes $r[0..4]) $dim
  Check-Ai "cinque record buoni: nessun rovinato, nessuna coda" (($x.Valid -eq 5) -and ($x.Bad -eq 0) -and ($x.TornTail -eq 0) -and ($x.GarbageBytes -eq 0))
  # 2. Un record con un byte cambiato nel mezzo (l'intestazione c'e', il CRC no): contato, gli altri si leggono.
  $broken = [byte[]]$r[2].Clone(); $broken[30] = $broken[30] -bxor 1
  $x = Test-VectorFile (Join-Bytes @($r[0], $r[1], $broken, $r[3], $r[4])) $dim
  Check-Ai "un record rovinato nel mezzo: contato, i quattro buoni si leggono" (($x.Valid -eq 4) -and ($x.Bad -eq 1) -and ($x.TornTail -eq 0))
  # 3. Una scrittura interrotta in coda: mezzo record.
  $half = [byte[]]$r[5][0..($size / 2 - 1)]
  $x = Test-VectorFile (Join-Bytes @($r[0], $r[1], $r[2], $half)) $dim
  Check-Ai "una scrittura interrotta in coda: coda troncata, nessun rovinato" (($x.Valid -eq 3) -and ($x.Bad -eq 0) -and ($x.TornTail -eq $half.Length))
  # 4. Mezzo record e poi altri record (il processo e' morto e uno nuovo ha continuato a scrivere in fondo).
  $x = Test-VectorFile (Join-Bytes @($r[0], $half, $r[1], $r[2])) $dim
  Check-Ai "mezzo record seguito da altri: i buoni si leggono, il mezzo e' contato" (($x.Valid -eq 3) -and ($x.Bad -ge 1))
  # 5. Un file vuoto e uno di spazzatura.
  $x = Test-VectorFile (New-Object byte[] 0) $dim
  Check-Ai "file vuoto: niente" (($x.Valid -eq 0) -and ($x.Bad -eq 0) -and ($x.TornTail -eq 0) -and ($x.GarbageBytes -eq 0))
  $x = Test-VectorFile ([byte[]](1..200 | ForEach-Object { 7 })) $dim
  Check-Ai "spazzatura senza intestazioni: nessun record" (($x.Valid -eq 0) -and ($x.Bad -eq 0))
  # 5-bis. DB-2: il nome e l'ordine dei byte sono quelli che scrive l'app, non quelli che lo script si aspettava.
  $spaceCode = Get-AiSpaceCode (Get-Content -LiteralPath $AiSpaceCodeFile -Raw)
  $pattern = Get-AiVectorNamePattern $spaceCode
  Check-Ai "il codice dello spazio letto da AiCodes.kt e' embeddinggemma2_float_v1" ($spaceCode -eq "embeddinggemma2_float_v1")
  Check-Ai "il file dei vettori vero (vectors-<spazio>-<generazione>.bin) e' riconosciuto" ("vectors-$spaceCode-7.bin" -match $pattern)
  Check-Ai "il vecchio nome che lo script si aspettava (vectors-1-1.bin) non e' un file dell'app" (-not ("vectors-1-1.bin" -match $pattern))
  $vectorSource = Get-Content -LiteralPath (Join-Path $root "core-search\src\main\kotlin\dev\pampa\mycloudgallery\core\search\index\VectorFile.kt") -Raw
  $magicMatch = [regex]::Match($vectorSource, 'const val MAGIC: Int = 0x([0-9A-Fa-f]{8})')
  Check-Ai "VectorFile.kt ha il segno MAGIC letto dal sorgente" $magicMatch.Success
  if ($magicMatch.Success) {
    $big = [Convert]::ToUInt32($magicMatch.Groups[1].Value, 16)
    $little = [BitConverter]::GetBytes([uint32]$big)   # little-endian, come ByteBuffer.order(LITTLE_ENDIAN)
    $ours = [McgVectorCheck]::MagicBytes()
    Check-Ai "il segno cercato dallo script e' MAGIC scritto little-endian (56 47 43 4D)" (($little -join ",") -eq ($ours -join ","))
    Check-Ai "un record vero comincia con quei byte" (($r[0][0..3] -join ",") -eq ($little -join ","))
  }
  Check-Ai "VectorFile.kt ha l'intestazione di 44 byte che lo script usa" ($vectorSource -match 'const val HEADER: Int = 44')
  $reversed = [byte[]]$r[0].Clone(); $reversed[0] = 0x4D; $reversed[1] = 0x43; $reversed[2] = 0x47; $reversed[3] = 0x56
  $x = Test-VectorFile $reversed $dim
  Check-Ai "un record con il segno nell'ordine sbagliato non e' riconosciuto" ($x.Valid -eq 0)
  $x = Test-VectorFile (Join-Bytes $r[0..2]) $dim
  Check-Ai "tre record veri: nessun problema" ((Get-VectorFileProblems $x (3 * $size)).Count -eq 0)
  $garbage = [byte[]](1..4000 | ForEach-Object { 7 })
  $x = Test-VectorFile $garbage $dim
  Check-Ai "un file non vuoto senza nessun record riconoscibile e' un problema" ((Get-VectorFileProblems $x $garbage.Length).Count -eq 1)
  $x = Test-VectorFile $half $dim
  Check-Ai "la sola coda di una scrittura interrotta (il primo append) non e' un problema" ((Get-VectorFileProblems $x $half.Length).Count -eq 0)
  $x = Test-VectorFile (New-Object byte[] 0) $dim
  Check-Ai "un file vuoto non e' un problema" ((Get-VectorFileProblems $x 0).Count -eq 0)
  # 6. Il catalogo dei modelli dal sorgente di questa cartella.
  $catalogFile = Join-Path $root "core-models\src\main\kotlin\dev\pampa\mycloudgallery\core\models\contract\ModelCatalog.kt"
  $catalog = @(ConvertTo-ModelCatalog (Get-Content -LiteralPath $catalogFile -Raw))
  Check-Ai "il catalogo ha almeno due modelli con nome, dimensione e impronta" (($catalog.Count -ge 2) -and (@($catalog | Where-Object { ($_.Size -le 0) -or ($_.Sha -notmatch '^[0-9a-f]{64}$') -or (-not $_.Name) }).Count -eq 0))
  Check-Ai "il catalogo letto ha il 270M da 164.626.432 byte" (@($catalog | Where-Object { ($_.Size -eq 164626432) -and ($_.Sha -eq "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb") }).Count -eq 1)
  # 7. Le guardie.
  Check-Ai "guardie: il telefono giusto, l'app di debug, Wi-Fi 20 s passano" ((Get-AiGuardErrors $AiSerial $AiPackage 20 $false $false).Count -eq 0)
  Check-Ai "guardie: un altro telefono e' rifiutato" ((Get-AiGuardErrors "R5CX1234567" $AiPackage 20 $false $false).Count -ge 1)
  Check-Ai "guardie: un altro telefono con l'adb finto passa" ((Get-AiGuardErrors "FAKE123" $AiPackage 20 $true $false).Count -eq 0)
  Check-Ai "guardie: l'app di release e' rifiutata" ((Get-AiGuardErrors $AiSerial "dev.pampa.mycloudgallery" 20 $false $false).Count -ge 1)
  Check-Ai "guardie: un'altra app di debug e' rifiutata" ((Get-AiGuardErrors $AiSerial "com.example.altra.debug" 20 $false $false).Count -ge 1)
  Check-Ai "guardie: Wi-Fi spento oltre 120 s e' rifiutato" ((Get-AiGuardErrors $AiSerial $AiPackage 121 $false $false).Count -ge 1)
  Check-Ai "guardie: senza telefono servono -DryRun o l'adb finto" ((Get-AiGuardErrors "" $AiPackage 20 $false $false).Count -ge 1)
  Check-Ai "guardie: a secco senza telefono passa" ((Get-AiGuardErrors "" $AiPackage 20 $false $true).Count -eq 0)
  return $script:aiSelfTestFailed
}

if ($SelfTest -and $isAi) {
  Write-Host "SelfTest del modo ai (nessun telefono, nessun adb):"
  $failedChecks = Invoke-AiSelfTest
  if ($failedChecks -gt 0) { Fail "SelfTest ai: $failedChecks controlli non passano." 3 }
  Write-Host "SelfTest ai: il verificatore dei dati e le guardie funzionano." -ForegroundColor Green
  exit 0
}

if ($SelfTest) {
  $hash = @(
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  /sdcard/DCIM/mcg-prova/vuoto.jpg",
    "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae  /sdcard/DCIM/mcg-prova/IMG_0001.jpg",
    "fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9 */sdcard/Pictures/mcg-prova/VID_big_1.mp4",
    "b5bb9d8014a0f9b1d61e21e796d78dccdf1352f23cd32812f4850b878ae4944c  /sdcard/DCIM/mcg-prova/testo.jpg",
    "una riga che non e' un hash"
  )
  $size = @("0 /sdcard/DCIM/mcg-prova/vuoto.jpg", "1234 /sdcard/DCIM/mcg-prova/IMG_0001.jpg", "2684354560 /sdcard/Pictures/mcg-prova/VID_big_1.mp4", "22 /sdcard/DCIM/mcg-prova/testo.jpg")
  $files = @(ConvertTo-PhoneFileList $hash $size)
  $text = New-BackupManifestText "20261016-101500-1a2b3c4d" "" "test" "mcg-campo" $files
  $expected = "# mcg-backup-manifest 1`nrun=20261016-101500-1a2b3c4d`ndevice=`ndest=test|mcg-campo`napp=test|`n" +
    "phone=2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae|1234|IMG_0001.jpg`n" +
    "phone=fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9|2684354560|VID_big_1.mp4`n" +
    "notmedia=b5bb9d8014a0f9b1d61e21e796d78dccdf1352f23cd32812f4850b878ae4944c|22|testo.jpg`n"
  Write-Host $text
  if ($text -ne $expected) { Fail "SelfTest: il manifesto non e' quello atteso." 3 }
  Write-Host "SelfTest: la conversione dell'uscita di sha256sum nel manifesto e' giusta (un file a zero byte e una riga estranea scartati, un fixture finto come notmedia)." -ForegroundColor Green
  exit 0
}

# --- I parametri -----------------------------------------------------------------------------------------------------------
if ($Interruptions -lt 1 -or $Interruptions -gt 1000) { Fail "-Interruptions deve stare fra 1 e 1000." }
if ($MinGapSeconds -lt 1 -or $MaxGapSeconds -lt $MinGapSeconds -or $MaxGapSeconds -gt 600) { Fail "Le attese vanno da 1 a 600 secondi, il massimo non sotto il minimo." }
if ($WifiPercent -lt 0 -or $WifiPercent -gt 100) { Fail "-WifiPercent deve stare fra 0 e 100." }
if ($WifiOffMinSeconds -lt 1 -or $WifiOffMaxSeconds -lt $WifiOffMinSeconds -or $WifiOffMaxSeconds -gt 120) { Fail "Il Wi-Fi spento dura da 1 a 120 secondi, il massimo non sotto il minimo." }
if ($Package -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$') { Fail "Nome del pacchetto non valido." }
if ($Verify -and -not $Manifest -and -not $isAi) { Fail "-Verify vuole -Manifest (il file scritto da area.ps1 -Mode seed, o da questo script con -Mode backup -Step seed)." }
if ($Step -in @("eval-push", "eval-pull") -and -not $isAi) { Fail "I passi eval-push ed eval-pull sono solo del modo ai (-Mode ai)." 2 }
if ($isAi) {
  # Il modo ai: solo interrupt e verify (la semina e' quella del modo backup), e le guardie sul telefono, l'app e il Wi-Fi.
  if ($Step -notin @("interrupt", "verify", "eval-push", "eval-pull")) { Fail "Il modo ai ha solo i passi interrupt, verify, eval-push ed eval-pull (la semina di mcg-prova e il primo backup sono del modo backup)." }
  $aiErrors = @(Get-AiGuardErrors $Serial $Package $WifiOffMaxSeconds ([bool]$env:MCG_ADB) ([bool]$DryRun))
  if ($aiErrors.Count -gt 0) { Fail ($aiErrors -join " ") 2 }
}
if ($isBackup) {
  if ($Package -notmatch '\.debug$') { Fail "Il modo backup vale solo per la build debug con gli account di prova (D13): il pacchetto deve finire con .debug." }
  if ($Photos -lt 1 -or $Photos -gt 5000) { Fail "-Photos deve stare fra 1 e 5000." }
  if ($BigVideos -lt 0 -or $BigVideos -gt 4) { Fail "-BigVideos deve stare fra 0 e 4." }
  if ($Edits -lt 1 -or $Edits -gt 500) { Fail "-Edits deve stare fra 1 e 500." }
  if ($BigVideoMb -lt 1 -or $BigVideoMb -gt 4096) { Fail "-BigVideoMb deve stare fra 1 e 4096." }
  if ($DestShare -notmatch '^(test|test2|test-comune)$') { Fail "-DestShare deve essere una share di prova (test, test2, test-comune)." }
  if ($DestDir -notmatch '^[A-Za-z0-9._-]+$' -or $DestDir -match '^\.+$') { Fail "-DestDir deve essere un solo nome di cartella semplice (default mcg-campo)." }
  if ($Run -and $Run -notmatch '^[0-9]{8}-[0-9]{6}-[0-9a-f]{8}$') { Fail "-Run deve avere la forma aaaammgg-hhmmss-<8 esadecimali>." }
  if ($Device -and $Device -notmatch '^[A-Za-z0-9]{26}$') { Fail "-Device e' l'id del dispositivo: 26 caratteri (Diagnostica)." }
}
if ($Seed -eq 0) { $Seed = Get-Random -Minimum 1 -Maximum 2147483647 }
$runId = if ($Run) { $Run } else { "{0}-{1:x8}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), (Get-Random -Minimum 0 -Maximum ([int]::MaxValue)) }
if ($isBackup -and -not $Manifest) { $Manifest = Join-Path $root "build\chaos\backup-manifest-$runId.txt" }

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $Log) { $Log = Join-Path $root "build\chaos\s25-$stamp.log" }
$logDir = Split-Path -Parent $Log
$logLines = New-Object System.Collections.Generic.List[string]
function Write-Log([string]$text) {
  $line = "{0}  {1}" -f (Get-Date -Format "HH:mm:ss"), $text
  Write-Host $line
  $logLines.Add($line)
}

# --- Il calendario: dipende solo dal seme ------------------------------------------------------------------------------------
$random = New-Object System.Random($Seed)
$plan = @()
for ($i = 1; $i -le $Interruptions; $i++) {
  $gap = $random.Next($MinGapSeconds, $MaxGapSeconds + 1)
  if ($random.Next(100) -lt $WifiPercent) {
    $plan += [pscustomobject]@{ N = $i; Gap = $gap; Action = "wifi"; Duration = $random.Next($WifiOffMinSeconds, $WifiOffMaxSeconds + 1) }
  } else {
    $plan += [pscustomobject]@{ N = $i; Gap = $gap; Action = "kill"; Duration = 0 }
  }
}
$wifiCount = @($plan | Where-Object { $_.Action -eq "wifi" }).Count
$killCount = $plan.Count - $wifiCount
$totalSeconds = ($plan | Measure-Object -Property Gap -Sum).Sum + ($plan | Measure-Object -Property Duration -Sum).Sum +
  $killCount * $(if ($NoRelaunch) { 0 } else { $RelaunchDelaySeconds }) + $SettleSeconds

# --- I comandi che si userebbero (stampati a secco, eseguiti davvero altrimenti) --------------------------------------------------
$serialText = if ($Serial) { $Serial } else { "<numero di serie>" }
$commandPlan = [ordered]@{
  "stato del telefono"         = "adb -s $serialText get-state"
  "app installata"             = "adb -s $serialText shell pm path $Package"
  "processo dell'app"          = "adb -s $serialText shell pidof $Package"
  "uccidere (run-as)"          = "adb -s $serialText shell run-as $Package kill -9 <pid>      (se non riesce: am crash)"
  "uccidere (crash)"           = "adb -s $serialText shell am crash $Package"
  "uccidere (am-kill)"         = "adb -s $serialText shell am kill $Package"
  "uccidere (force-stop)"      = "adb -s $serialText shell am force-stop $Package"
  "Wi-Fi spento"               = "adb -s $serialText shell svc wifi disable"
  "Wi-Fi acceso"               = "adb -s $serialText shell svc wifi enable"
  "rilancio dell'app"          = "adb -s $serialText shell monkey -p $Package -c android.intent.category.LAUNCHER 1"
}

function Get-SdkDir {
  $localProperties = Join-Path $root "local.properties"
  if (Test-Path -LiteralPath $localProperties) {
    $match = Select-String -LiteralPath $localProperties -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
    if ($match) { return $match.Matches[0].Groups[1].Value.Trim().Replace("\:", ":").Replace("\\", "\") }
  }
  if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
  return $env:ANDROID_SDK_ROOT
}

# Uno solo dei comandi adb: sempre con -s, mai su "tutti i dispositivi". Si trova e si usa solo quando serve davvero (mai a secco).
$script:adb = $null
function Initialize-Adb {
  if (-not $Serial -or $Serial -eq "none") {
    Fail "Serve il numero di serie di un telefono vero (-Serial o ANDROID_SERIAL): 'none' vuol dire nessun dispositivo, e questo script non sceglie da solo." 2
  }
  # MCG_ADB: un adb finto per provare lo script senza telefono (modalita' finta): se c'e', e' l'unico adb che si usa.
  if ($env:MCG_ADB) {
    if (-not (Test-Path -LiteralPath $env:MCG_ADB)) { Fail "MCG_ADB non punta a un file." }
    $script:adb = $env:MCG_ADB
  }
  $sdk = if ($script:adb) { $null } else { Get-SdkDir }
  if ($sdk -and (Test-Path -LiteralPath (Join-Path $sdk "platform-tools\adb.exe"))) { $script:adb = Join-Path $sdk "platform-tools\adb.exe" }
  if (-not $script:adb) { $command = Get-Command adb -ErrorAction SilentlyContinue; if ($command) { $script:adb = $command.Source } }
  if (-not $script:adb) { Fail "adb non trovato (sdk.dir in local.properties, o adb nel PATH)." }
  $state = Invoke-Adb @("get-state")
  if ($state.Text -ne "device") { Fail "Il telefono $Serial non e' pronto (adb dice: '$($state.Text)')." }
}

function Invoke-Adb([string[]]$arguments) {
  $previous = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    $lines = @(& $script:adb -s $Serial @arguments 2>&1 | ForEach-Object { $_.ToString() })
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previous
  }
  return [pscustomobject]@{ Code = $code; Text = (($lines -join "`n").Trim()); Lines = $lines }
}

function Assert-Debug-App {
  $installed = Invoke-Adb @("shell", "pm", "path", $Package)
  if ($installed.Text -notmatch "package:") { Fail "L'app $Package non e' installata sul telefono." }
}

function Start-App {
  [void](Invoke-Adb @("shell", "monkey", "-p", $Package, "-c", "android.intent.category.LAUNCHER", "1"))
}

function Stop-AppProcess {
  $pidText = (Invoke-Adb @("shell", "pidof", $Package)).Text
  if (-not $pidText) { return "il processo non era in esecuzione" }
  $pidNumber = ($pidText -split "\s+")[0]
  if ($pidNumber -notmatch '^\d+$') { return "pid non valido" }
  switch ($KillMode) {
    "run-as" {
      $run = Invoke-Adb @("shell", "run-as", $Package, "kill", "-9", $pidNumber)
      if ($run.Code -eq 0 -and $run.Text -notmatch "(?i)not debuggable|unknown package|Operation not permitted") { return "kill -9 del pid $pidNumber" }
      [void](Invoke-Adb @("shell", "am", "crash", $Package))
      return "run-as non riuscito, am crash"
    }
    "crash" { [void](Invoke-Adb @("shell", "am", "crash", $Package)); return "am crash" }
    "am-kill" { [void](Invoke-Adb @("shell", "am", "kill", $Package)); return "am kill" }
    "force-stop" { [void](Invoke-Adb @("shell", "am", "force-stop", $Package)); return "am force-stop" }
  }
}

function Save-Log {
  if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
  [System.IO.File]::WriteAllLines($Log, $logLines)
  Write-Host "Racconto: $Log"
}

# =================================================================================================================================
# Modo backup: seed, verify, clean (interrupt usa il calendario e il ciclo qui sotto, uguali al modo move)
# =================================================================================================================================

# I comandi del telefono per `mcg-prova`: scritti qui, con le due cartelle fisse.
$listCommand = "find " + ($PhoneFolders -join " ") + " -type f"
$hashCommand = "$listCommand -exec sha256sum {} +"
$sizeCommand = "$listCommand -exec stat -c '%s %n' {} +"
$scanCommand = "content call --uri content://media/ --method scan_volume --arg external_primary"

function Get-PhoneFiles {
  $hash = Invoke-Adb @("shell", $hashCommand)
  if ($hash.Code -ne 0) { Fail "sha256sum sul telefono non e' riuscito (adb dice: '$($hash.Text)')." }
  $size = Invoke-Adb @("shell", $sizeCommand)
  if ($size.Code -ne 0) { Fail "stat sul telefono non e' riuscito (adb dice: '$($size.Text)')." }
  return @(ConvertTo-PhoneFileList $hash.Lines $size.Lines)
}

function Write-SeedPlan {
  Write-Host "=== PIANO DELLA SEMINA (a secco): nessun comando adb viene eseguito ===" -ForegroundColor Yellow
  Write-Host ("Telefono: {0}   Giro: {1}   Seme: {2}   Destinazione: {3}/{4}/Backup/" -f $serialText, $runId, $Seed, $DestShare, $DestDir)
  Write-Host ("File: i fixture di tools\fixtures ({0}), fino a {1} foto in tutto (jpeg unici dal seme), {2} video da {3} MB fatti sul telefono" -f $(if ($FixturesDir) { $FixturesDir } else { "generati con generate.ps1 -SkipBig" }), $Photos, $BigVideos, $BigVideoMb)
  Write-Host "Cartelle del telefono (le uniche toccate): $($PhoneFolders -join ', ')"
  Write-Host ""
  Write-Host "Comandi adb che userebbe:"
  Write-Host ("  {0,-26} adb -s {1} get-state" -f "stato del telefono", $serialText)
  Write-Host ("  {0,-26} adb -s {1} shell mkdir -p {2}" -f "cartelle", $serialText, ($PhoneFolders -join " "))
  Write-Host ("  {0,-26} adb -s {1} push <staging>\DCIM\mcg-prova /sdcard/DCIM/   e   push <staging>\Pictures\mcg-prova /sdcard/Pictures/" -f "file piccoli", $serialText)
  for ($i = 1; $i -le $BigVideos; $i++) {
    Write-Host ("  {0,-26} adb -s {1} shell cp <un mp4 di mcg-prova> {2}/VID_mcg_grande_{3}.mp4" -f "video grande $i (copia)", $serialText, $PhoneFolders[0], $i)
    Write-Host ("  {0,-26} adb -s {1} shell sh -c 'dd if=/dev/urandom bs=1048576 count={2} >> {3}/VID_mcg_grande_{4}.mp4'" -f "video grande $i (byte)", $serialText, $BigVideoMb, $PhoneFolders[0], $i)
  }
  Write-Host ("  {0,-26} adb -s {1} shell {2}" -f "scansione del MediaStore", $serialText, $scanCommand)
  Write-Host ("  {0,-26} adb -s {1} shell {2}" -f "impronte (sha256sum)", $serialText, $hashCommand)
  Write-Host ("  {0,-26} adb -s {1} shell {2}" -f "dimensioni (stat)", $serialText, $sizeCommand)
  Write-Host ""
  Write-Host ("Il manifesto va in {0}: poi l'utente avvia il primo backup nell'app e si lancia -Step interrupt." -f $Manifest) -ForegroundColor Green
}

# Aspetta che il MediaStore abbia indicizzato i file di mcg-prova (se no l'app non li vede e il verificatore direbbe "persi"): conta le righe
# con `mcg-prova` nel percorso. Un esito diverso non ferma la semina, la avvisa.
function Wait-MediaStore([int]$expected) {
  $deadline = (Get-Date).AddSeconds(180)
  $seen = 0
  while ((Get-Date) -lt $deadline) {
    $rows = Invoke-Adb @("shell", "content query --uri content://media/external/file --projection _data")
    $seen = @($rows.Lines | Where-Object { $_ -match 'mcg-prova' }).Count
    if ($seen -ge $expected) { return $seen }
    Start-Sleep -Seconds 5
  }
  Write-Log "ATTENZIONE: il MediaStore ha indicizzato ${seen} file su ${expected}; l'app potrebbe non vederli tutti (ripetere la scansione)."
  return $seen
}

function Write-EditPlan {
  Write-Host "=== PIANO DELLA MODIFICA (a secco): nessun comando adb viene eseguito ===" -ForegroundColor Yellow
  Write-Host ("Telefono: {0}   Manifesto: {1}   Seme: {2}   Foto da modificare: {3}" -f $serialText, $Manifest, $Seed, $Edits)
  Write-Host "Cartelle del telefono (le uniche toccate): $($PhoneFolders -join ', ')"
  Write-Host ""
  Write-Host "Comandi adb che userebbe:"
  Write-Host ("  {0,-26} adb -s {1} shell {2}" -f "impronte (sha256sum)", $serialText, $hashCommand)
  Write-Host ("  {0,-26} adb -s {1} shell {2}" -f "dimensioni (stat)", $serialText, $sizeCommand)
  Write-Host ("  {0,-26} adb -s {1} shell sh -c 'dd if=/dev/urandom bs=24 count=1 >> <una foto di mcg-prova>'   (per ciascuna delle {2})" -f "una modifica (24 byte in coda)", $serialText, $Edits)
  Write-Host ("  {0,-26} adb -s {1} shell {2}" -f "scansione del MediaStore", $serialText, $scanCommand)
  Write-Host ""
  Write-Host "Poi l'utente rifa il backup nell'app (Fai ora) e si lancia -Step interrupt: l'originale in Versioni/, la modifica al solito percorso." -ForegroundColor Green
}

# Modifica -Edits foto di `mcg-prova` come farebbe Samsung "Salva" (byte in coda: un JPEG li ignora) e scrive le righe `edit=` nel manifesto.
function Invoke-Edit {
  if (-not (Test-Path -LiteralPath $Manifest)) { Fail "Manifesto non trovato: $Manifest (serve quello della semina)." }
  Initialize-Adb
  Assert-Debug-App
  $before = @(Get-PhoneFiles)
  # O-1: mai un fixture finto (testo.jpg non e' una foto: il backup non lo carica, quindi non avrebbe versioni).
  $candidates = @($before | Where-Object { $_.Label -match '\.jpe?g$' -and $_.Size -gt 0 -and -not $_.NotMedia })
  if ($candidates.Count -eq 0) { Fail "Nessuna foto JPEG in mcg-prova da modificare." }
  $random = New-Object System.Random($Seed)
  $chosen = @($candidates | Sort-Object { $random.Next() } | Select-Object -First $Edits)
  foreach ($file in $chosen) {
    # Il percorso viene dal telefono (find nelle due cartelle di prova): dev'essere sotto una di quelle, altrimenti non si tocca.
    $inside = $false
    foreach ($folder in $PhoneFolders) { if ($file.Path.StartsWith($folder + "/")) { $inside = $true } }
    if (-not $inside) { Fail "Un file fuori da mcg-prova: non si tocca." }
    $dd = Invoke-Adb @("shell", "sh", "-c", "'dd if=/dev/urandom bs=24 count=1 >> $($file.Path)'")
    if ($dd.Code -ne 0) { Fail "dd sul telefono non e' riuscito." }
  }
  Write-Log "Chiedo la scansione del MediaStore..."
  [void](Invoke-Adb @("shell", $scanCommand))
  $after = @(Get-PhoneFiles)
  $text = ""
  $count = 0
  foreach ($file in $chosen) {
    $now = @($after | Where-Object { $_.Path -eq $file.Path } | Select-Object -First 1)
    if ($now.Count -eq 0 -or $now[0].Sha -eq $file.Sha) { Fail "Una foto non e' cambiata dopo la modifica." }
    $text += ("edit={0}|{1}|{2}`n" -f $file.Sha, $now[0].Sha, $file.Label)
    $count++
  }
  [System.IO.File]::AppendAllText($Manifest, $text)
  Write-Log ("Modificate {0} foto di mcg-prova. Manifesto: {1}" -f $count, $Manifest)
  Write-Host "Adesso, nell'app, rifai il backup (Fai ora) e lancia: s25.ps1 -Mode backup -Step interrupt -Manifest `"$Manifest`" -Verify -AllowFreed" -ForegroundColor Green
}

function Invoke-Seed {
  Initialize-Adb
  Assert-Debug-App
  $work = Join-Path $root "build\chaos\seed-$runId"
  New-Item -ItemType Directory -Force -Path $work | Out-Null
  # 1. I fixture: formati misti.
  if ($FixturesDir) {
    $fixtureRoot = (Resolve-Path -LiteralPath $FixturesDir).Path
  } else {
    $fixtureRoot = Join-Path $work "fixtures"
    Write-Log "Genero i fixture di prova (generate.ps1 -SkipBig)..."
    & (Join-Path $root "tools\fixtures\generate.ps1") -SkipBig -Out $fixtureRoot
    if ($LASTEXITCODE -ne 0) { Fail "generate.ps1 non e' riuscito." }
  }
  $allowed = @(".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic", ".heif", ".dng", ".mp4", ".mov")
  $fixtures = @(Get-ChildItem -LiteralPath $fixtureRoot -Recurse -File | Where-Object { $allowed -contains $_.Extension.ToLowerInvariant() -and $_.Length -gt 0 })
  if ($fixtures.Count -eq 0) { Fail "Nessun fixture in $fixtureRoot." }
  # I due contenitori locali hanno il nome della cartella del telefono: `adb push <cartella> /sdcard/DCIM/` crea /sdcard/DCIM/mcg-prova.
  $dcim = Join-Path $work "staging\DCIM\mcg-prova"
  $pictures = Join-Path $work "staging\Pictures\mcg-prova"
  New-Item -ItemType Directory -Force -Path $dcim, $pictures | Out-Null
  $used = @{}
  $stagedMp4 = $null
  foreach ($file in $fixtures) {
    $name = $file.Name
    if ($used.ContainsKey($name.ToLowerInvariant())) { $name = "{0}_{1}{2}" -f $file.BaseName, $used.Count, $file.Extension }
    $used[$name.ToLowerInvariant()] = $true
    Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $dcim $name)
    if (-not $stagedMp4 -and $file.Extension -ieq ".mp4") { $stagedMp4 = $name }
  }
  # 2. Foto uniche fino a -Photos: un jpeg dei fixture con in coda byte casuali dal seme (un jpeg ignora cio' che segue la fine dell'immagine);
  #    meta' in DCIM e meta' in Pictures (le due sorgenti di D13).
  $base = $fixtures | Where-Object { $_.Extension -match '^\.jpe?g$' } | Sort-Object Length | Select-Object -First 1
  $random = New-Object System.Random($Seed)
  if ($base) {
    $baseBytes = [System.IO.File]::ReadAllBytes($base.FullName)
    $missing = $Photos - @(Get-ChildItem -LiteralPath $dcim -File | Where-Object { $_.Extension -match '^\.(jpe?g|png|webp|heic|heif|gif|dng)$' }).Count
    for ($i = 1; $i -le $missing; $i++) {
      $tail = New-Object byte[] 24
      $random.NextBytes($tail)
      $target = if ($i % 2 -eq 0) { $pictures } else { $dcim }
      [System.IO.File]::WriteAllBytes((Join-Path $target ("IMG_mcg_{0:D4}.jpg" -f $i)), [byte[]]($baseBytes + $tail))
    }
  }
  # 3. Sul telefono: le due cartelle, i file piccoli, i video grandi, la scansione.
  Write-Log "Preparo le cartelle del telefono: $($PhoneFolders -join ', ')"
  [void](Invoke-Adb (@("shell", "mkdir", "-p") + $PhoneFolders))
  foreach ($pair in @(@($dcim, "/sdcard/DCIM/"), @($pictures, "/sdcard/Pictures/"))) {
    if (@(Get-ChildItem -LiteralPath $pair[0] -File).Count -gt 0) {
      Write-Log "Spingo $($pair[0]) su $($pair[1])..."
      $result = Invoke-Adb @("push", $pair[0], $pair[1])
      if ($result.Code -ne 0) { Fail "adb push non e' riuscito (adb dice: '$($result.Text)')." }
    }
  }
  for ($i = 1; $i -le $BigVideos; $i++) {
    if (-not $stagedMp4) { Fail "Serve un mp4 fra i fixture per fare i video grandi." }
    $target = "$($PhoneFolders[0])/VID_mcg_grande_$i.mp4"
    Write-Log "Faccio il video grande $i sul telefono ($BigVideoMb MB)..."
    [void](Invoke-Adb @("shell", "cp", "$($PhoneFolders[0])/$stagedMp4", $target))
    $dd = Invoke-Adb @("shell", "sh", "-c", "'dd if=/dev/urandom bs=1048576 count=$BigVideoMb >> $target'")
    if ($dd.Code -ne 0) { Fail "dd sul telefono non e' riuscito (spazio?)." }
  }
  Write-Log "Chiedo la scansione del MediaStore..."
  [void](Invoke-Adb @("shell", $scanCommand))
  # 4. Il manifesto: le impronte vere, dal telefono.
  $files = Get-PhoneFiles
  if ($files.Count -eq 0) { Fail "Nessun file di mcg-prova sul telefono dopo la semina." }
  [void](Wait-MediaStore $files.Count)
  $text = New-BackupManifestText $runId $Device $DestShare $DestDir $files
  $manifestDir = Split-Path -Parent $Manifest
  if ($manifestDir -and -not (Test-Path -LiteralPath $manifestDir)) { New-Item -ItemType Directory -Path $manifestDir | Out-Null }
  [System.IO.File]::WriteAllText($Manifest, $text)
  Write-Log ("Semina finita: {0} file in mcg-prova (somma {1:N0} byte). Manifesto: {2}" -f $files.Count, (($files | Measure-Object -Property Size -Sum).Sum), $Manifest)
  Write-Host "Adesso, nell'app (build debug, account di prova), avvia il primo backup (Inizia ora) e lancia: s25.ps1 -Mode backup -Step interrupt -Manifest `"$Manifest`" -Verify" -ForegroundColor Green
}

function Invoke-VerifyBackup {
  if (-not (Test-Path -LiteralPath $Manifest)) { Fail "Manifesto non trovato: $Manifest" }
  # Il telefono non deve essere cambiato: nella 5A il backup non scrive niente sul telefono.
  Initialize-Adb
  # Per etichetta: le impronte che un file puo' avere (quella della semina; dalla 5B anche quella di una modifica: `edit=`).
  $allowed = @{}
  $labelsSeeded = @{}
  foreach ($line in (Get-Content -LiteralPath $Manifest)) {
    if ($line -match '^phone=([0-9a-f]{64})\|(\d+)\|(.+)$') { $allowed["$($Matches[1])|$($Matches[3])"] = $true; $labelsSeeded[$Matches[3]] = $true }
    # O-1: i fixture finti sono sul telefono come gli altri (il backup non li carica, ma il telefono li tiene).
    if ($line -match '^notmedia=([0-9a-f]{64})\|(\d+)\|(.+)$') { $allowed["$($Matches[1])|$($Matches[3])"] = $true; $labelsSeeded[$Matches[3]] = $true }
    if ($line -match '^edit=([0-9a-f]{64})\|([0-9a-f]{64})\|(.+)$') { $allowed["$($Matches[1])|$($Matches[3])"] = $true; $allowed["$($Matches[2])|$($Matches[3])"] = $true }
  }
  $now = @{}
  foreach ($file in (Get-PhoneFiles)) { $now["$($file.Sha)|$($file.Label)"] = $true }
  # Un file con un'impronta che il manifesto non conosce e' un cambiamento; uno che manca lo e' solo se "Libera spazio" non c'entra (-AllowFreed).
  $unknown = @($now.Keys | Where-Object { -not $allowed.ContainsKey($_) }).Count
  $nowLabels = @{}
  foreach ($key in $now.Keys) { $nowLabels[($key -split '\|', 2)[1]] = $true }
  $missing = @($labelsSeeded.Keys | Where-Object { -not $nowLabels.ContainsKey($_) }).Count
  $changed = $unknown + $(if ($AllowFreed) { 0 } else { $missing })
  if ($changed -gt 0) { Fail "Il telefono e' cambiato durante la prova ($changed file diversi dal manifesto): senza modifiche del manifesto il backup non tocca il telefono." 4 }
  Write-Log ("Il telefono non e' cambiato rispetto al manifesto: le impronte di mcg-prova tornano{0}." -f $(if ($missing -gt 0) { " ($missing file tolti da Libera spazio)" } else { "" }))
  if ($SkipNas) {
    Write-Log "Verifica sul NAS saltata (-SkipNas): solo le impronte del telefono."
    return 0
  }
  $verify = Join-Path $PSScriptRoot "area.ps1"
  Write-Log "Verifica degli invarianti sull'area di prova (in sola lettura)..."
  & $verify -Mode backup -Stage verify -Manifest $Manifest
  return $LASTEXITCODE
}

function Invoke-Clean {
  if (-not $Yes) { Fail "Togliere mcg-prova dal telefono vuole -Yes." }
  Initialize-Adb
  Assert-Debug-App
  foreach ($folder in $PhoneFolders) {
    # Solo le due cartelle di prova, per nome intero: nessun parametro entra nel comando.
    [void](Invoke-Adb @("shell", "rm", "-rf", $folder))
    Write-Log "Tolta la cartella $folder dal telefono."
  }
  [void](Invoke-Adb @("shell", $scanCommand))
}

# =================================================================================================================================
# Modo ai: il verificatore dei dati sul telefono (sola lettura, solo la cartella dell'app di debug, via `run-as`)
# =================================================================================================================================

# I file di una cartella dell'app (percorso relativo alla cartella dell'app) con la dimensione: solo `find` e `stat`. Una cartella che non
# c'e' e' vuota. Sul telefono non si scrive, non si cancella e non si legge altro: i nomi vengono dall'elenco e non entrano in nessun comando.
function Get-AppFiles([string]$dir) {
  $result = Invoke-Adb @("shell", "run-as $Package find $dir -type f -exec stat -c '%s %n' {} +")
  $files = @()
  foreach ($line in $result.Lines) {
    if ($line -match '^\s*(\d+)\s+(.+?)\s*$') { $files += [pscustomobject]@{ Path = $Matches[2]; Size = [long]$Matches[1]; Name = ($Matches[2] -split '/')[-1] } }
  }
  return $files
}

function Get-AppHashes([string]$dir) {
  $result = Invoke-Adb @("shell", "run-as $Package find $dir -type f -exec sha256sum {} +")
  $map = @{}
  foreach ($line in $result.Lines) { if ($line -match '^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$') { $map[$Matches[2]] = $Matches[1].ToLowerInvariant() } }
  return $map
}

# Copia un file dell'app sul PC (build\chaos\): `exec-out` porta i byte senza conversioni e li si scrive direttamente. Solo un nome che passa
# la forma di un nome di file dell'indice (lettere, cifre, punto, trattino) si copia.
function Save-AppFile([string]$relative, [string]$destination) {
  if (($relative -split '/')[-1] -notmatch '^[A-Za-z0-9._-]+$') { throw "Nome di file dell'app non ammesso." }
  $info = New-Object System.Diagnostics.ProcessStartInfo
  $info.FileName = $script:adb
  $info.Arguments = "-s $Serial exec-out run-as $Package cat $relative"
  $info.RedirectStandardOutput = $true
  $info.UseShellExecute = $false
  $process = [System.Diagnostics.Process]::Start($info)
  $out = [System.IO.File]::Create($destination)
  try { $process.StandardOutput.BaseStream.CopyTo($out) } finally { $out.Close() }
  $process.WaitForExit()
}

function Invoke-VerifyAi {
  Initialize-Adb
  Assert-Debug-App
  $problems = New-Object System.Collections.Generic.List[string]
  $catalogFile = if ($CatalogFile) { $CatalogFile } else { Join-Path $root "core-models\src\main\kotlin\dev\pampa\mycloudgallery\core\models\contract\ModelCatalog.kt" }
  $catalog = @(ConvertTo-ModelCatalog (Get-Content -LiteralPath $catalogFile -Raw))
  $bySha = @{}
  foreach ($model in $catalog) { $bySha[$model.Sha] = $model }

  # I2 e I1: in models/ solo file con nome, dimensione e SHA-256 del catalogo (un parziale promosso o un file alterato si vedono qui).
  $inModels = @(Get-AppFiles $AiModelsDir)
  $ready = @($inModels | Where-Object { $_.Path -match ('^' + [regex]::Escape($AiModelsDir) + '/[^/]+$') })
  $hashes = if ($ready.Count -gt 0) { Get-AppHashes $AiModelsDir } else { @{} }
  $good = 0
  foreach ($file in $ready) {
    $model = $bySha[$file.Name]
    if (-not $model) { $problems.Add("I2: in models/ un file che non e' del catalogo ($($file.Size) byte)"); continue }
    if ($file.Size -ne $model.Size) { $problems.Add("I2: un modello in models/ lungo $($file.Size) byte invece di $($model.Size)"); continue }
    if ($hashes[$file.Path] -ne $file.Name) { $problems.Add("I2: un modello in models/ con l'impronta diversa dal catalogo"); continue }
    $good++
  }
  Write-Log ("models/: {0} file del catalogo verificati (dimensione e SHA-256), {1} problemi" -f $good, @($problems | Where-Object { $_ -like "I2*" }).Count)

  # I9: a ripresa finita niente parziali di modelli, niente temporanei nel buffer, buffer sotto il tetto.
  $partials = @($inModels | Where-Object { $_.Path -like "$AiModelsDir/partial/*" -and $_.Name -match '\.part$' })
  if ($partials.Count -gt 0 -and -not $NotSettled) { $problems.Add("I9: $($partials.Count) parziali di modelli rimasti in models/partial/ (alzare -SettleSeconds, o -NotSettled se la ripresa non e' finita)") }
  $buffer = @(Get-AppFiles $AiBufferDir)
  $orphans = @($buffer | Where-Object { $_.Name -like "*.tmp" }).Count
  $bufferBytes = [long]($buffer | Measure-Object -Property Size -Sum).Sum
  if ($orphans -gt 0) { $problems.Add("I9: $orphans temporanei orfani in ai-buffer/") }
  if ($bufferBytes -gt $AiBufferMaxBytes) { $problems.Add("I9: il buffer pesa $([math]::Round($bufferBytes / 1MB)) MB, oltre il tetto di $([math]::Round($AiBufferMaxBytes / 1MB)) MB") }
  Write-Log ("ai-buffer/: {0} file, {1} MB, {2} temporanei orfani" -f $buffer.Count, [math]::Round($bufferBytes / 1MB, 1), $orphans)

  # I5: il file dei vettori. Un'interruzione rovina al piu' i record di UNA scrittura (fino a 9); il processo nuovo li toglie e li rifa.
  # Le morti di questa prova (calendario dello stesso lancio); da solo, `-Step verify` non sa quante ce ne sono state: vale -Interruptions (un tetto, non un
  # numero a caso dal calendario di un seme casuale: prima la tolleranza cambiava da un lancio all'altro).
  $kills = if ($Step -eq "interrupt" -and $killCount -gt 0) { $killCount } else { $Interruptions }
  $allowedBad = [math]::Max(1, $kills) * $AiMaxBadRecordsPerKill
  $stamp2 = Get-Date -Format "yyyyMMdd-HHmmss"
  $pulled = Join-Path $root "build\chaos\ai-$stamp2"
  New-Item -ItemType Directory -Force -Path $pulled | Out-Null
  # DB-2: il nome e l'ordine dei byte sono quelli che scrive l'app (vedi Get-AiSpaceCode e McgVectorCheck); i file stanno in search/index/.
  $namePattern = Get-AiVectorNamePattern (Get-AiSpaceCode (Get-Content -LiteralPath $AiSpaceCodeFile -Raw))
  $vectorFiles = @(Get-AppFiles $AiSearchDir | Where-Object { $_.Name -match $namePattern })
  $valid = 0
  $bad = 0
  foreach ($file in $vectorFiles) {
    $local = Join-Path $pulled $file.Name
    Save-AppFile $file.Path $local
    $bytes = [System.IO.File]::ReadAllBytes($local)
    $check = Test-VectorFile $bytes $AiVectorDimension
    foreach ($problem in (Get-VectorFileProblems $check $bytes.Length)) { $problems.Add($problem) }
    $valid += $check.Valid
    $bad += $check.Bad
    Write-Log ("search/: un file di vettori, {0} record buoni, {1} rovinati, coda troncata {2} byte, {3} byte scartati" -f $check.Valid, $check.Bad, $check.TornTail, $check.GarbageBytes)
  }
  if ($vectorFiles.Count -eq 0) { Write-Log "search/: nessun file di vettori (l'indicizzazione non ha ancora salvato niente?)." }
  if ($vectorFiles.Count -eq 0 -and @(Get-AppFiles $AiSearchDir | Where-Object { $_.Name -like 'vectors-*' }).Count -gt 0) {
    $problems.Add("I5: in search/ ci sono file `vectors-*` ma nessuno ha il nome che scrive l'app (vectors-<spazio>-<generazione>.bin)")
  }
  if ($bad -gt $allowedBad) { $problems.Add("I5: $bad record dei vettori rovinati, piu' di quelli che $kills interruzioni possono spiegare (al piu' $allowedBad)") }
  Remove-Item -LiteralPath $pulled -Recurse -Force -ErrorAction SilentlyContinue

  foreach ($problem in $problems) { Write-Log ("ERRORE  " + $problem) }
  Write-Log "Non verificato qui (nel modulo che vede il NAS): teste ai sotto devices/<io>/ai/, M3, prenotazioni (invarianti 5, 6, 7 sul NAS)."
  if ($problems.Count -gt 0) {
    Write-Host ("`n{0} problemi: vedi il racconto." -f $problems.Count) -ForegroundColor Red
    return 4
  }
  Write-Log ("Verifica dei dati sul telefono: tutto a posto ({0} record dei vettori buoni, {1} rovinati su {2} ammessi)." -f $valid, $bad, $allowedBad)
  return 0
}

function Write-EvalPushPlan {
  Write-Host "=== A SECCO: non lancio niente ===" -ForegroundColor Yellow
  Write-Host ("Telefono: {0}   App: {1}   Set OCR: {2}" -f $serialText, $Package, $(if ($EvalSource) { $EvalSource } else { "<-EvalSource>" }))
  Write-Host ("adb -s {0} shell rm -rf {1}     (la copia temporanea, se c'era)" -f $serialText, $AiEvalTemp)
  Write-Host ("adb -s {0} push <set OCR> {1}     (la shell scrive in /data/local/tmp)" -f $serialText, $AiEvalTemp)
  Write-Host ("adb -s {0} shell chmod -R a+rX {1}     (leggibile dall'app)" -f $serialText, $AiEvalTemp)
  Write-Host ("adb -s {0} shell run-as {1} mkdir -p {2}/ocr" -f $serialText, $Package, $AiEvalDir)
  Write-Host ("adb -s {0} shell run-as {1} cp -r {2}/. {3}/ocr/     (l'app di debug scrive nella sua cartella)" -f $serialText, $Package, $AiEvalTemp, $AiEvalDir)
  Write-Host ("adb -s {0} shell rm -rf {1}     (la copia temporanea si toglie)" -f $serialText, $AiEvalTemp)
}

# DB-4 (F54): il set OCR sintetico dentro la cartella dell'app di debug, dove lo strumento "Valutazione della ricerca" lo cerca (EvalStore.ocrDir).
function Invoke-EvalPush {
  if (-not $EvalSource -or -not (Test-Path -LiteralPath (Join-Path $EvalSource "manifest.tsv"))) {
    Fail "-EvalSource deve essere la cartella del set OCR (manifest.tsv e immagini\), uscita di tools\fixtures\generate-ocr.ps1." 2
  }
  Initialize-Adb
  Assert-Debug-App
  [void](Invoke-Adb @("shell", "rm", "-rf", $AiEvalTemp))
  $push = Invoke-Adb @("push", $EvalSource, $AiEvalTemp)
  if ($push.Code -ne 0) { Fail "adb push non e' riuscito (adb dice: '$($push.Text)')." 4 }
  [void](Invoke-Adb @("shell", "chmod", "-R", "a+rX", $AiEvalTemp))
  $mk = Invoke-Adb @("shell", "run-as $Package mkdir -p $AiEvalDir/ocr")
  if ($mk.Code -ne 0) { Fail "run-as mkdir non e' riuscito (e' la build di debug? adb dice: '$($mk.Text)')." 4 }
  $cp = Invoke-Adb @("shell", "run-as $Package cp -r $AiEvalTemp/. $AiEvalDir/ocr/")
  if ($cp.Code -ne 0) { Fail "run-as cp non e' riuscito (adb dice: '$($cp.Text)')." 4 }
  [void](Invoke-Adb @("shell", "rm", "-rf", $AiEvalTemp))
  $files = @(Get-AppFiles "$AiEvalDir/ocr")
  $images = @($files | Where-Object { $_.Path -match '/immagini/' }).Count
  Write-Log ("Set OCR sul telefono: {0} file ({1} immagini) in {2}/ocr/. Ora: Impostazioni > Sviluppo > Valutazione della ricerca > Lettura di prova." -f $files.Count, $images, $AiEvalDir)
  if (-not ($files | Where-Object { $_.Name -eq "manifest.tsv" })) { Fail "Il set OCR copiato non ha manifest.tsv." 4 }
  return 0
}

function Write-EvalPullPlan {
  Write-Host "=== A SECCO: non lancio niente ===" -ForegroundColor Yellow
  Write-Host ("Telefono: {0}   App: {1}   Destinazione: {2}" -f $serialText, $Package, $(if ($EvalDir) { $EvalDir } else { "build\chaos\eval-<data>" }))
  Write-Host ("adb -s {0} shell run-as {1} find {2} -type f -exec stat -c '%s %n' {{}} +     (l'elenco)" -f $serialText, $Package, $AiEvalDir)
  Write-Host ("adb -s {0} exec-out run-as {1} cat {2}/<labels.mcgeval | report-NN.txt | ocr/letto.tsv | ocr/ocr-resoconto.txt>     (uno per file)" -f $serialText, $Package, $AiEvalDir)
}

# DB-4 (F54): le etichette e i resoconti della valutazione sul PC. Solo i nomi dell'elenco $AiEvalPullNames; le etichette sono dati privati (mai nel repository).
function Invoke-EvalPull {
  Initialize-Adb
  Assert-Debug-App
  $dest = if ($EvalDir) { $EvalDir } else { Join-Path $root "build\chaos\eval-$stamp" }
  $prefix = "$AiEvalDir/"
  $found = @(Get-AppFiles $AiEvalDir | Where-Object { $_.Path.StartsWith($prefix) -and ($_.Path.Substring($prefix.Length) -match $AiEvalPullNames) })
  if ($found.Count -eq 0) { Write-Log "Niente da portare: la valutazione non ha scritto etichette ne' resoconti (lo strumento e' stato usato?)."; return 0 }
  New-Item -ItemType Directory -Force -Path $dest | Out-Null
  foreach ($file in $found) {
    $relative = $file.Path.Substring($prefix.Length)
    $local = Join-Path $dest ($relative.Replace('/', '\'))
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $local) | Out-Null
    Save-AppFile $file.Path $local
    Write-Log ("portato {0} ({1} byte)" -f $relative, (Get-Item -LiteralPath $local).Length)
  }
  Write-Log ("{0} file in {1}. Le etichette sono dati privati: non vanno nel repository." -f $found.Count, $dest)
  return 0
}

if ($isAi -and $Step -eq "eval-push") {
  if ($DryRun) { Write-EvalPushPlan; exit 0 }
  try { $code = Invoke-EvalPush } finally { Save-Log }
  exit $code
}
if ($isAi -and $Step -eq "eval-pull") {
  if ($DryRun) { Write-EvalPullPlan; exit 0 }
  try { $code = Invoke-EvalPull } finally { Save-Log }
  exit $code
}

if ($isAi -and $Step -eq "verify") {
  if ($DryRun) {
    Write-Host "=== A SECCO: non lancio niente ===" -ForegroundColor Yellow
    Write-Host ("Telefono: {0}   App: {1}   (sola lettura, solo la cartella dell'app, via run-as)" -f $serialText, $Package)
    Write-Host ("adb -s {0} shell run-as {1} find {2} -type f -exec stat -c '%s %n' {{}} +     (modelli: nome e dimensione)" -f $serialText, $Package, $AiModelsDir)
    Write-Host ("adb -s {0} shell run-as {1} find {2} -type f -exec sha256sum {{}} +     (modelli: impronta contro ModelCatalog.kt)" -f $serialText, $Package, $AiModelsDir)
    Write-Host ("adb -s {0} shell run-as {1} find {2} -type f -exec stat -c '%s %n' {{}} +     (buffer: temporanei e peso)" -f $serialText, $Package, $AiBufferDir)
    Write-Host ("adb -s {0} shell run-as {1} find {2} -type f -exec stat -c '%s %n' {{}} +     (file dei vettori)" -f $serialText, $Package, $AiSearchDir)
    Write-Host ("adb -s {0} exec-out run-as {1} cat {2}/vectors-<spazio>-<generazione>.bin     (copiato in build\chaos\, CRC dei record)" -f $serialText, $Package, $AiSearchDir)
    exit 0
  }
  try { $code = Invoke-VerifyAi } finally { Save-Log }
  exit $code
}

if ($isBackup -and $Step -eq "seed") {
  if ($DryRun) { Write-SeedPlan; exit 0 }
  try { Invoke-Seed } finally { Save-Log }
  exit 0
}
if ($isBackup -and $Step -eq "edit") {
  if ($DryRun) { Write-EditPlan; exit 0 }
  try { Invoke-Edit } finally { Save-Log }
  exit 0
}
if ($isBackup -and $Step -eq "verify") {
  if ($DryRun) {
    Write-Host "=== A SECCO: non lancio niente ===" -ForegroundColor Yellow
    Write-Host ("adb -s {0} shell {1}     (le impronte di mcg-prova contro il manifesto)" -f $serialText, $hashCommand)
    Write-Host ("tools\chaos\area.ps1 -Mode backup -Stage verify -Manifest {0}     (sola lettura dell'area di prova)" -f $Manifest)
    exit 0
  }
  try { $code = Invoke-VerifyBackup } finally { Save-Log }
  exit $code
}
if ($isBackup -and $Step -eq "clean") {
  if ($DryRun) {
    Write-Host "=== A SECCO: non lancio niente ===" -ForegroundColor Yellow
    foreach ($folder in $PhoneFolders) { Write-Host ("adb -s {0} shell rm -rf {1}     (serve -Yes)" -f $serialText, $folder) }
    Write-Host ("adb -s {0} shell {1}" -f $serialText, $scanCommand)
    exit 0
  }
  try { Invoke-Clean } finally { Save-Log }
  exit 0
}

# =================================================================================================================================
# Le interruzioni: uguali nei due modi (move, e backup con -Step interrupt)
# =================================================================================================================================

if ($DryRun) {
  Write-Host "=== PROVA A SECCO: nessun comando adb viene eseguito ===" -ForegroundColor Yellow
  Write-Host ("Modo: {0}{1}   Telefono: {2}   Pacchetto: {3}   Seme: {4}   Uccisione: {5}{6}" -f $Mode, $(if ($isBackup) { " (interrupt)" } else { "" }), $serialText, $Package, $Seed, $KillMode, $(if ($NoRelaunch) { " (senza rilancio)" } else { "" }))
  Write-Host ("{0} interruzioni: {1} morti del processo, {2} cadute del Wi-Fi; durata prevista circa {3} minuti" -f $plan.Count, $killCount, $wifiCount, [math]::Ceiling($totalSeconds / 60.0))
  Write-Host ""
  Write-Host ("{0,4}  {1,8}  {2}" -f "n.", "attesa", "azione")
  foreach ($event in $plan) {
    $what = if ($event.Action -eq "wifi") { "Wi-Fi spento per $($event.Duration) s, poi acceso" } else { "morte del processo ($KillMode)" + $(if ($NoRelaunch) { "" } else { ", rilancio dopo $RelaunchDelaySeconds s" }) }
    Write-Host ("{0,4}  {1,6} s  {2}" -f $event.N, $event.Gap, $what)
  }
  Write-Host ""
  Write-Host "Comandi adb che userebbe:"
  foreach ($name in $commandPlan.Keys) { Write-Host ("  {0,-24} {1}" -f $name, $commandPlan[$name]) }
  if ($isAi -and $Interruptions -lt $AiRecommendedInterruptions) {
    Write-Host ("ATTENZIONE: il par. 5.5 chiede almeno {0} interruzioni sul telefono; questa prova ne fa {1}." -f $AiRecommendedInterruptions, $Interruptions) -ForegroundColor Yellow
  }
  if ($isAi) {
    Write-Host "Prima: backup di mcg-prova gia' fatto, ruolo Indicizzatore acceso, scaricamento/indicizzazione partiti nell'app. Le interruzioni cadono durante l'uno e l'altra." -ForegroundColor Cyan
  }
  if ($Verify) {
    if ($isAi) {
      Write-Host "A fine prova: s25.ps1 -Mode ai -Step verify   (sola lettura, solo la cartella dell'app: models/, ai-buffer/, search/)"
    } elseif ($isBackup) {
      Write-Host ("A fine prova: s25.ps1 -Mode backup -Step verify -Manifest {0}   (impronte del telefono, poi area.ps1 -Mode backup -Stage verify)" -f $Manifest)
    } else {
      Write-Host ("A fine prova: powershell -File tools\chaos\area.ps1 -Mode verify -Manifest {0}" -f $Manifest)
    }
  }
  Write-Host ""
  Write-Host "Il calendario e' ripetibile: stesso -Seed, stessa prova." -ForegroundColor Green
  exit 0
}

# --- La prova vera ---------------------------------------------------------------------------------------------------------------
Initialize-Adb
Assert-Debug-App
if ($isAi -and $Interruptions -lt $AiRecommendedInterruptions) {
  Write-Log ("ATTENZIONE: il par. 5.5 chiede almeno {0} interruzioni sul telefono; questa prova ne fa {1}." -f $AiRecommendedInterruptions, $Interruptions)
}
if ($isBackup -and (Test-Path -LiteralPath $Manifest)) {
  $count = @(Get-Content -LiteralPath $Manifest | Where-Object { $_ -like "phone=*" }).Count
  Write-Log "Manifesto: $count file del telefono. Il primo backup deve essere gia' partito nell'app."
}

Write-Log ("INIZIO prova: telefono {0}, app {1}, modo {2}, seme {3}, {4} interruzioni ({5} morti, {6} Wi-Fi), durata prevista circa {7} minuti" -f $Serial, $Package, $Mode, $Seed, $plan.Count, $killCount, $wifiCount, [math]::Ceiling($totalSeconds / 60.0))
$exitCode = 0
try {
  foreach ($event in $plan) {
    Start-Sleep -Seconds $event.Gap
    if ($event.Action -eq "kill") {
      $how = Stop-AppProcess
      Write-Log ("#{0}/{1}  morte del processo: {2}" -f $event.N, $plan.Count, $how)
      if (-not $NoRelaunch) {
        Start-Sleep -Seconds $RelaunchDelaySeconds
        Start-App
        Write-Log ("#{0}/{1}  app rilanciata" -f $event.N, $plan.Count)
      }
    } else {
      [void](Invoke-Adb @("shell", "svc", "wifi", "disable"))
      Write-Log ("#{0}/{1}  Wi-Fi spento per {2} s" -f $event.N, $plan.Count, $event.Duration)
      Start-Sleep -Seconds $event.Duration
      [void](Invoke-Adb @("shell", "svc", "wifi", "enable"))
      Write-Log ("#{0}/{1}  Wi-Fi acceso" -f $event.N, $plan.Count)
    }
  }
  Write-Log "Fine delle interruzioni: l'app riparte e si aspetta che la ripresa finisca ($SettleSeconds s)."
  Start-App
  Start-Sleep -Seconds $SettleSeconds
} catch {
  Write-Log ("INTERROTTA: " + $_.Exception.Message)
  $exitCode = 1
} finally {
  # Qualunque cosa sia successa, il telefono non resta senza Wi-Fi.
  [void](Invoke-Adb @("shell", "svc", "wifi", "enable"))
  Write-Log "Wi-Fi riacceso (garanzia di uscita)."
  Save-Log
}

if ($exitCode -eq 0 -and $Verify) {
  if ($isAi) {
    Write-Log "Verifica dei dati sul telefono (sola lettura)..."
    try { $exitCode = Invoke-VerifyAi } finally { Save-Log }
  } elseif ($isBackup) {
    Write-Log "Verifica del backup: impronte del telefono e invarianti sull'area di prova..."
    $exitCode = Invoke-VerifyBackup
  } else {
    $verify = Join-Path $PSScriptRoot "area.ps1"
    Write-Log "Verifica degli invarianti sull'area di prova..."
    & $verify -Mode verify -Manifest $Manifest
    $exitCode = $LASTEXITCODE
  }
}
exit $exitCode
