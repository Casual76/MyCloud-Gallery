<#
.SYNOPSIS
  Il comando unico di verifica di MyCloud Gallery: se finisce tutto OK, il repo e' in ordine.

.DESCRIPTION
  Esegue, in ordine, e alla fine stampa un riepilogo OK/FAIL (codice di uscita 0 solo se e' tutto OK):

    1. engine-doctor            l'engine e' quello che engine.properties dichiara (versione, tag, moduli)
    2. engine pulito            nessuna modifica locale dentro engine/ (sarebbe una variante silenziosa)
    3. repo senza segreti       nessun .jks/.keystore/local.properties tracciato o da tracciare
    3b. nessuna chiave privata  (Fase 10, P0) nel CONTENUTO dei file del repository nessuna chiave PEM ne' l'inizio di un PKCS#8 EC P-256 o
                                RSA (base64 o esadecimale), salvo chiavi di prova nelle cartelle dei test (D31, D32, C442)
    4. verifica checksum        gradle/verification-metadata.xml c'e' ed e' attiva; il wrapper e' fissato
    5. gradle                   assembleDebug assembleRelease galleryTest galleryLint
                                (galleryTest/galleryLint = testDebugUnitTest/lintDebug dei soli moduli della
                                galleria, engine escluso; in piu' assembleRelease esegue verifyReleaseManifest)
    6. test eseguiti            ogni modulo ha eseguito almeno un test e nessuno e' fallito (un modulo con
                                ZERO test eseguiti risulterebbe "passato" per Gradle: qui no). `:benchmark` e'
                                escluso: e' un modulo `com.android.test` (Macrobenchmark) senza unit test, che
                                gira solo da un dispositivo e non entra mai nell'APK (Fase 3, D21)
    7. APK di release           ispezione dell'APK vero con aapt2: non debuggable, backup escluso, nessun
                                componente esportato oltre quelli ammessi da gradle\verify-release-manifest.gradle
                                (launcher e JobService di WorkManager, quest'ultimo solo con BIND_JOB_SERVICE),
                                nessun codice solo-debug dentro, (Fase 3) nessun `ProfileInstallReceiver` (il
                                receiver del Macrobenchmark torna solo nella variante benchmarkRelease) e (Fase 4B, D29)
                                ogni `*JobService` protetto da BIND_JOB_SERVICE: quello delle operazioni lunghe sui
                                file del NAS (UIDT) non e' nemmeno esportato; e (Fase 5B, P0-B) i quattro componenti di
                                :feature-integrations ci sono con la giusta esposizione (selettore, ricezione e provider dei
                                documenti esportati, il FileProvider NO) e nessun componente delle prove guidate `FieldProbe*`
    7b. nessun resto di debug  nel dex dell'APK di release non compaiono la Libreria sintetica, il marcatore della famiglia
                                "pronta", `DebugSyntheticSize`, il sink di logcat e il catalogo dei componenti (I4 della
                                revisione di sicurezza), ne' (Fase 4A, D33) la recinzione delle prove `ShareFence` e il NAS
                                sintetico del menu di debug, ne' (revisione 4B, F20) il gancio che accorcia i 30 giorni del
                                cestino del NAS, ne' (Fase 5A, D13) la recinzione del telefono del backup in debug
                                (`DebugBackupFence`, le cartelle `mcg-prova` e la radice `mcg-campo`), ne' (Fase 7, P12) lo strumento di valutazione
                                degli album (`debugtools/eval/albums`, `viaggi.mcgalb`, `MCGALB01`), ne' (Fase 8, P11) quello delle persone
                                (`debugtools/eval/faces`, `persone.mcgfac`, `MCGFAC01`), ne' (Fase 5B, P0-B) le prove
                                guidate T-09, T-14, T-15 e T-16 (`FieldProbe*`, `debugtools/probes`, `field-probe`): lettura diretta del dex,
                                FALLISCE se non lo legge; e (R11 della revisione 4A) gli esiti di R8 della release: `ShareFence`
                                tolta per intero in usage.txt e assente da mapping.txt (una classe fusa in un'altra non lascia il
                                suo nome nel dex)
    7d. D12 nella release       (Fase 5A) la protezione "backup solo sulle share di prova" C'E' nell'APK di release: il campo
                                `FeatureFlags.backupTestSharesOnly` e la decisione `backupMayWriteTo` nel dex e tenuti da R8 in
                                mapping.txt (consumer-rules.pro di core-common); il valore acceso e non spegnibile lo prova
                                FeatureFlagsTest. E (P7) il CANCELLO: nel bytecode della release (dexdump) `AppBackupGate.stateOf`
                                chiama `FeatureFlags.backupMayWriteTo`, risponde `BackupGateState.TEST_ONLY` e la classe del cancello
                                e' istanziata da `BackupGraph` (un cancello tolto, fuso o scollegato fa FALLIRE il passo). E (R2 della
                                revisione 5A) il cancello chiama anche `FeatureFlags.backupMayWriteAs` e risponde `TEST_ONLY`: in
                                release un account di prova non scrive in nessuna share (D13, la recinzione del telefono, c'e' solo in
                                debug); anche `backupBlocksTestAccounts` e `backupMayWriteAs` devono stare nel dex e in mapping.txt.
                                E (LA-5 della verifica funzionale della 5B) il SECONDO cancello, quello dei file in arrivo ("Salva sul
                                NAS", `IncomingGate.stateOf`, la share di destinazione): stessa prova, cioe' chiama `backupMayWriteTo` e
                                `backupMayWriteAs`, risponde `TEST_ONLY` dopo ognuna ed e' istanziato da una classe del backup
    7d-bis. D30 nella release   (Fase 7, P0) la recinzione "album condivisi solo sulle share di prova" C'E' nell'APK di release:
                                `FeatureFlags.sharedAlbumsTestSharesOnly` e la decisione `sharedAlbumsMayUse` nel dex e in mapping.txt
                                (consumer-rules.pro di core-common). E (R20 della revisione di sicurezza della Fase 7) i CANCELLI che le
                                usano, nel bytecode della release (dexdump): `AppAlbumWriteGate.blockFor` chiama `sharedAlbumsMayUse` e
                                risponde `AlbumWriteBlock.TEST_SHARES_ONLY`, `FlagsAlbumReadGate.mayRead` chiama la stessa decisione, li
                                istanzia il montaggio degli album di :app e li chiamano pubblicatore, piano della condivisione e replica
    7d-ter. D34 nella release   (Fase 8, P0) la recinzione "dati dei volti sul NAS solo sulle share di prova" (risposta Q5 (a))
                                C'E' nell'APK di release: `FeatureFlags.facesSyncTestSharesOnly` e la decisione `facesSyncMayUse`
                                nel dex e in mapping.txt (consumer-rules.pro di core-common); il cancello dei volti lo aggiunge P13
    7d-quater. recinzioni       (Fase 10, P10; docs/fase10-progetto.md par. 2.10, D35, Q6 (b), C446) le quattro recinzioni (F4-D31, F5-D12,
                                F7-D30, F8-D34) si spengono in UN passo solo e il controllo ha due stati: con tutte accese e' verde come
                                prima; con le quattro dichiarate spente in tools\release\recinzioni.json (con UN consenso: data e marcatore
                                di una riga di docs\decisioni.md con i quattro id) accetta solo le decisioni che valgono "spenta"; uno
                                stato misto solo con un ritorno indietro parziale dichiarato. Tre passi: lo stato dichiarato uguale a
                                quello di FeatureFlags.kt (statico), uguale a quello del BYTECODE della release (getter, decisione e
                                toString letti col dexdump), e le prove su copie temporanee sotto build\ (mai un commit che spegne una
                                recinzione: FeatureFlags non si cambia) e sulle cartelle finte di share-snapshot.ps1; la "Simulazione del
                                passo" (solo build debug) non deve avere resti nel dex di release (passo 7b)
    7e. motori on-device       (Fase 6, P0; docs/fase6-progetto.md D3, D33, D38, D41) sull'APK di release vero: librerie
                                native solo arm64-v8a (P11: x86_64 solo nella debug, per gli emulatori), non compresse,
                                allineate a 16 KB nel file e nei segmenti LOAD;
                                nel manifest niente `MlKitInitProvider`, i componenti di ML Kit e DataTransport esattamente
                                l'elenco chiuso di `releaseManifestPolicy` (non esportati), le `uses-native-library` solo le due
                                ammesse e facoltative; nel bytecode (dexdump) `MlKit.initialize` chiamato da codice nostro solo
                                dal cancello `core-ai/.../ocr/MlKitBootstrap` e i client HTTPS (`HttpsURLConnection`,
                                `URL.openConnection`/`openStream`/`getContent`, ogni `URLConnection`, `Network.openConnection`,
                                `HttpEngine`, `DownloadManager`, `WebView`, i socket TLS; I3 della revisione di Fase 6: anche dal
                                codice dell'engine, `Ldev/antigravity/fluidengine/`) solo da `core-models/.../download/https/` e
                                (P11) almeno una da li' (`ModelFetchers` montato da `:app`); I2: la variante "Play services" di ML Kit non
                                entra nella release (nessun meta-data `com.google.mlkit.*DEPENDENCIES`, i tre registri di ML Kit noti, i due
                                descrittori `dynamite` del testo, il modello OCR integrato nell'APK, soli artefatti noti nei metadati di
                                verifica). FALLISCE se non legge l'APK
    7f. LiteRT solo in core-ai  (Fase 8, P0; docs/fase8-progetto.md D2, §6.4) nel bytecode della release (dexdump) le chiamate di
                                codice nostro a LiteRT (`org.tensorflow.lite`, `com.google.ai.edge.litert`) partono solo da
                                `core/ai/` e, fra le classi dei volti, solo da `core/ai/faces/FaceLiteRtSessions`; controllo
                                positivo: almeno una chiamata da `core/ai/` (il ritaglio). FALLISCE se non legge l'APK
    7h. OkHttp e MapLibre       (Fase 9, P0; docs/fase9-progetto.md D21, D23, §5.5) nel bytecode della release (dexdump): ogni chiamata a
                                OkHttp (`okhttp3`) parte da OkHttp stesso, da Okio, da MapLibre (`org.maplibre`, `com.mapbox`) o da
                                `feature/map/net/` (`MapTileHttp`, l'unico codice di rete della mappa); ogni chiamata di codice nostro (e
                                dell'engine) a MapLibre parte da `feature/map/`. Il passo 7e ammette i client HTTPS anche da `feature/map/net/`
                                (la seconda eccezione dichiarata, D23). P1: `HttpRequestUtil.setOkHttpClient` solo da
                                `feature/map/net/MapTileHttp`, `MapLibre.getInstance` solo da `feature/map/map/MapLibreInit`; quando
                                `:app` dipende da `:feature-map` (P13) il controllo positivo: almeno una chiamata a OkHttp da
                                `feature/map/net/`, il client installato in MapLibre, MapLibre inizializzato, `libmaplibre.so` nell'APK
                                (anche nel passo 7e, con i 16 KB). Le classi di OkHttp, MapLibre e `net/` non si fondono con altre
                                (feature-map/consumer-rules.pro): senza, R8 le mescola e il passo non prova niente. FALLISCE se non legge l'APK
    7i. Glance e KT             (Fase 10, P0; docs/fase10-progetto.md D10, D31) nel bytecode della release (dexdump) ogni chiamata a Glance
                                (`androidx.glance`) parte da Glance, dal kit dell'engine o da `feature/widget/` (P3; oggi passa a vuoto, con il
                                controllo positivo quando :app dipende da :feature-widget con Glance); la chiave di prova del manifest (KT, le
                                stringhe di `core-release/.../testing/ReleaseTestKeys.kt`, P1) e `ManifestTool` non sono nella release (anche 7b)
    7g. modelli dei volti       (Fase 8, P1; docs/fase8-progetto.md D39, §6.4) nell'APK di release `assets/faces/` ha YuNet e SFace con la
                                dimensione e lo SHA-256 di `ModelCatalog.FACE_YUNET` e `FACE_SFACE` (letti dal sorgente), le due licenze
                                (MIT, Apache-2.0) e il NOTICE che nomina le impronte, nessun altro modello nella cartella e nessun file
                                ONNX in tutto l'APK. Controllo positivo: il ritaglio e' al suo posto. FALLISCE se non legge l'APK
    7c. niente servizi        l'APK di release non ha nessun `META-INF/services/dev.pampa.*` (ne' un file di servizio che
                                nomini una classe `dev.pampa.*`): le varianti di misura legano le loro classi con Hilt, mai
                                con `ServiceLoader` (R6)
    8. niente fake in release   nessuna classe di un package `testing` nel dex dell'APK di release (D6 di
                                docs/fase2-progetto.md: i fake stanno in src/main per riusarli a valle, ma
                                non devono arrivare all'APK). Lettura diretta dei classes*.dex: non dipende
                                da apkanalyzer e, se non riesce a leggere il dex, FALLISCE (non salta)
    +  permessi documentati     ogni permesso ammesso da gradle\verify-release-manifest.gradle (statico) e ogni
                                permesso dell'APK di release deve comparire in docs\sicurezza.md 4-bis
    +  profilo nell'APK         (Fase 3, P9) quando il Baseline Profile esiste nei sorgenti
                                (app\src\main\generated\baselineProfiles), l'APK di release contiene
                                assets/dexopt/baseline.prof e AGP ha fuso le regole del profilo
    +  benchmark compilano      (Fase 3, P9) gli scenari di :benchmark e il dataset di app/src/benchmarkRelease
                                compilano: senza dispositivo e' tutto cio' che si puo' verificare
    +  etichette private        (Fase 6, P9/P11) nessun file di etichette o query del set privato nel repository (nomi,
                                cartelle, intestazione MCGEVAL1) e le cinque regole nel .gitignore; nel dex di release
                                nessun resto dello strumento di valutazione (`debugtools/eval`, `EvalEntry`)
    +  scenari della ricerca    (Fase 6, P9/P11) gli scenari `ricerca*` in benchmark\soglie.json e `SearchBenchmark`
                                nel modulo; i banchi delle interruzioni di P10 girano dentro galleryTest (core-models,
                                core-ai: 2.000 + 600 + 2.000 giri)
    +  scenari album e volti    (debito del 27/10: Fase 7 par. 5.4 e Fase 8 par. 5.4) gli scenari `album`, `album-scroll`,
                                `album-ricalcolo`, `album-raccolte`, `volti` e `volti-scroll` in benchmark\soglie.json con le
                                soglie dei progetti (300 ms, 2 s, 1%, +15%) e le prove che li misurano (`AlbumBenchmark`,
                                `FacesBenchmark`) nel modulo
    +  scenari ricordi e mappa  (Fase 9, P12: par. 5.4 e punto 12 del par. 0) gli scenari `ricordi-*` e `mappa-*` in benchmark\soglie.json
                                con le soglie del progetto (300 ms, 2 s, 800/300 ms, 8 ms, 1%, +15%; il tempo di disegno di MapLibre
                                `null` da calibrare) e le prove che li misurano (`MemoriesBenchmark`, `MapBenchmark`); le soglie JVM del
                                par. 5.4 nei test di `galleryTest`; il collaudo non accende mai la mappa online
    +  -Bench                   (Fase 3, P9) le misure su dispositivo, SKIP senza dispositivo (vedi -Bench)

.PARAMETER SkipGradle
  Salta i passi 5-7 (e' il controllo statico, veloce: engine, repo, checksum, permessi ammessi vs docs).

.PARAMETER NoDaemon
  Esegue Gradle con --no-daemon (nessun processo che resta residente a fine verifica).

.PARAMETER Bench
  Fase 3, P9 (docs/fase3-progetto.md par. 2.6, D21; docs/bench/README.md): esegue le misure di prestazioni (Macrobenchmark:
  scorrimento, pinch, avvio a freddo, apertura del viewer, zoom a 200 MP) sul dispositivo collegato, sulla variante
  benchmarkRelease, e le confronta con benchmark\soglie.json e con il riferimento docs\bench\riferimento-<dispositivo>.json
  (tools\bench\compare.ps1). SENZA dispositivo il passo e' SKIP. Annota la frequenza dello schermo letta in sola lettura
  (getprop, dumpsys display, settings get): a 120 Hz serve "Fluidita' movimento: Adattiva". Su un emulatore e' uno smoke:
  gli scenari girano e basta, nessuna soglia si applica. Un dispositivo per volta.

.PARAMETER BenchSerial
  Il numero di serie del dispositivo (`adb devices`) quando ce n'e' piu' di uno.

.PARAMETER BenchStrict
  Con -Bench, una prova attesa senza risultato (saltata, non girata) o un tetto da calibrare e' un errore: e' la modalita'
  della chiusura della Fase 3. Senza, restano un avviso ("OK con riserve").

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\check.ps1
#>
[CmdletBinding()]
param(
  [switch]$SkipGradle,
  [switch]$NoDaemon,
  [switch]$Bench,
  [string]$BenchSerial,
  [switch]$BenchStrict
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $root

$script:results = New-Object System.Collections.Generic.List[object]
$script:failed = $false

# --- Utilita' ---------------------------------------------------------------------------------------------

function Read-TextFile($path) {
  return [System.IO.File]::ReadAllText($path, (New-Object System.Text.UTF8Encoding($false)))
}

# Esegue un comando nativo, mostra l'output in rientro (o solo la coda se e' lunghissimo) e restituisce
# codice e righe. 2>&1 e' sicuro qui: non si guarda $? ma $LASTEXITCODE, e ogni ErrorRecord diventa testo.
function Invoke-Native {
  param([string]$File, [string[]]$Arguments, [string]$LogPath)
  $previous = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    $lines = @(& $File @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previous
  }
  if ($LogPath) {
    $dir = Split-Path -Parent $LogPath
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    [System.IO.File]::WriteAllLines($LogPath, $lines)
  }
  return [pscustomobject]@{ Code = $code; Lines = $lines }
}

function Show-Lines($lines, [int]$tail = 0) {
  $shown = $lines
  if ($tail -gt 0 -and $lines.Count -gt $tail) {
    Write-Host ("        ... ({0} righe omesse)" -f ($lines.Count - $tail)) -ForegroundColor DarkGray
    $shown = $lines | Select-Object -Last $tail
  }
  foreach ($line in $shown) { Write-Host ("        " + $line) -ForegroundColor DarkGray }
}

# Un passo ritorna $null (tutto bene) oppure una stringa che spiega cosa non va. Un'eccezione e' un FAIL.
function Step {
  param([string]$Name, [scriptblock]$Body, [switch]$Skip, [string]$SkipReason)
  if ($Skip) {
    Write-Host ("[ -- ] {0}  ({1})" -f $Name, $SkipReason) -ForegroundColor DarkGray
    $script:results.Add([pscustomobject]@{ Name = $Name; Status = "SKIP"; Detail = $SkipReason; Seconds = 0 })
    return
  }
  Write-Host ("[ .. ] {0}" -f $Name) -ForegroundColor Cyan
  $watch = [System.Diagnostics.Stopwatch]::StartNew()
  $problem = $null
  try {
    $problem = & $Body
  } catch {
    $problem = "eccezione: " + $_.Exception.Message
  }
  $watch.Stop()
  $seconds = [math]::Round($watch.Elapsed.TotalSeconds, 1)
  if ($problem) {
    Write-Host ("[FAIL] {0}  -> {1}  ({2}s)" -f $Name, $problem, $seconds) -ForegroundColor Red
    $script:failed = $true
    $script:results.Add([pscustomobject]@{ Name = $Name; Status = "FAIL"; Detail = [string]$problem; Seconds = $seconds })
  } else {
    Write-Host ("[ OK ] {0}  ({1}s)" -f $Name, $seconds) -ForegroundColor Green
    $script:results.Add([pscustomobject]@{ Name = $Name; Status = "OK"; Detail = ""; Seconds = $seconds })
  }
}

# Gradle ha bisogno di un JDK 17: se JAVA_HOME non c'e' e java non e' nel PATH, prova dove Alessio lo tiene.
function Ensure-Java {
  if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) { return }
  if (Get-Command java -ErrorAction SilentlyContinue) { return }
  $candidate = Get-ChildItem "C:\Program Files\Microsoft" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
  if ($candidate) { $env:JAVA_HOME = $candidate.FullName }
}

function Get-Aapt2 {
  $sdk = $null
  $localProperties = Join-Path $root "local.properties"
  if (Test-Path -LiteralPath $localProperties) {
    $match = Select-String -LiteralPath $localProperties -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
    if ($match) {
      # Formato .properties: "C\:\\Users\\..." -> "C:\Users\..."
      $sdk = $match.Matches[0].Groups[1].Value.Trim().Replace("\:", ":").Replace("\\", "\")
    }
  }
  if (-not $sdk -and $env:ANDROID_HOME) { $sdk = $env:ANDROID_HOME }
  if (-not $sdk -and $env:ANDROID_SDK_ROOT) { $sdk = $env:ANDROID_SDK_ROOT }
  if (-not $sdk) { return $null }
  $tools = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "aapt2.exe") } |
    Sort-Object { try { [version]($_.Name -replace '[^0-9.].*$', '') } catch { [version]"0.0" } } -Descending |
    Select-Object -First 1
  if (-not $tools) { return $null }
  return (Join-Path $tools.FullName "aapt2.exe")
}

# Legge il manifest DENTRO l'apk (non quello dei sorgenti, non quello fuso: quello che si installa).
function Get-ApkManifestFacts([string]$aapt2, [string]$apk) {
  $dump = Invoke-Native -File $aapt2 -Arguments @("dump", "xmltree", "--file", "AndroidManifest.xml", $apk)
  if ($dump.Code -ne 0) { throw "aapt2 dump xmltree fallito: " + ($dump.Lines -join " | ") }
  $facts = [ordered]@{ Debuggable = $false; AllowBackup = "(assente)"; Components = @() }
  $element = ""
  $current = $null
  foreach ($line in $dump.Lines) {
    if ($line -match '^\s*E: ([\w\-]+) ') {
      $element = $Matches[1]
      if (@("activity", "activity-alias", "service", "receiver", "provider") -contains $element) {
        $current = @{ Kind = $element; Name = ""; Exported = $false; Permission = "" }
        $facts.Components += , $current
      } else {
        $current = $null
      }
      continue
    }
    if ($line -match '^\s*A: .*android:([A-Za-z]+)\(0x[0-9a-fA-F]+\)=(.*)$') {
      $attr = $Matches[1]
      $value = $Matches[2].Trim()
      $isTrue = ($value -match '^true' -or $value -match '0xffffffff')
      if ($element -eq "application") {
        if ($attr -eq "debuggable") { $facts.Debuggable = $isTrue }
        if ($attr -eq "allowBackup") { $facts.AllowBackup = if ($isTrue) { "true" } else { "false" } }
      }
      if ($current) {
        if ($attr -eq "name" -and $value -match '^"([^"]*)"') { $current.Name = $Matches[1] }
        if ($attr -eq "exported") { $current.Exported = $isTrue }
        if ($attr -eq "permission" -and $value -match '^"([^"]*)"') { $current.Permission = $Matches[1] }
      }
    }
  }
  return $facts
}

# I componenti esportati ammessi da gradle\verify-release-manifest.gradle (stessa fonte di verifyReleaseManifest):
# nome -> permesso che il componente deve dichiarare ("" = nessun obbligo).
function Get-AllowedExported {
  $gradle = Read-TextFile (Join-Path $root "gradle\verify-release-manifest.gradle")
  $list = [regex]::Match($gradle, '(?s)allowedExported\s*:\s*\[(.*?)\n\s*\],')
  if (-not $list.Success) { throw "non trovo allowedExported in gradle\verify-release-manifest.gradle" }
  $allowed = @{}
  foreach ($line in ($list.Groups[1].Value -split '\r?\n')) {
    $code = ($line -replace '//.*$', '').Trim()
    foreach ($m in [regex]::Matches($code, "'([^']+)'")) { $allowed[$m.Groups[1].Value] = "" }
  }
  $map = [regex]::Match($gradle, '(?s)exportedRequiresPermission\s*:\s*\[(.*?)\n\s*\],')
  if ($map.Success) {
    foreach ($line in ($map.Groups[1].Value -split '\r?\n')) {
      $code = ($line -replace '//.*$', '').Trim()
      $pair = [regex]::Match($code, "'([^']+)'\s*:\s*'([^']+)'")
      if ($pair.Success) { $allowed[$pair.Groups[1].Value] = $pair.Groups[2].Value }
    }
  }
  if ($allowed.Count -eq 0) { throw "allowedExported e' vuoto in gradle\verify-release-manifest.gradle" }
  return $allowed
}

# Le classi definite nei classes*.dex di un APK che corrispondono a [pattern] (un'espressione regolare sui
# descrittori, es. Ldev/pampa/mycloudgallery/core/nas/testing/FakeNasSessionManager;). Legge i byte del dex: i
# descrittori delle classi sono stringhe ASCII nella tabella delle stringhe, quindi una lettura Latin-1 li conserva.
# Controllo positivo: deve trovare la MainActivity, altrimenti la lettura non e' affidabile e si lancia.
function Get-DexClassesMatching([string]$apk, [string]$pattern) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
  try {
    $dexes = @($zip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' })
    if ($dexes.Count -eq 0) { throw "nessun classes*.dex nell'APK" }
    $latin1 = [System.Text.Encoding]::GetEncoding(28591)
    $found = @{}
    $sawMain = $false
    foreach ($entry in $dexes) {
      $stream = $entry.Open()
      try {
        $buffer = New-Object System.IO.MemoryStream
        $stream.CopyTo($buffer)
      } finally {
        $stream.Dispose()
      }
      $text = $latin1.GetString($buffer.ToArray())
      if ($text.Contains("Ldev/pampa/mycloudgallery/MainActivity;")) { $sawMain = $true }
      foreach ($m in [regex]::Matches($text, $pattern)) { $found[$m.Value] = $true }
    }
    if (-not $sawMain) { throw "lettura del dex non affidabile: la MainActivity non compare in nessun classes*.dex" }
    return @($found.Keys | Sort-Object)
  } finally {
    $zip.Dispose()
  }
}

# Il bytecode del cancello D12 (Fase 5A, P7): `dexdump -d` dei classes*.dex dell'APK (build-tools, accanto ad aapt2), letto in streaming da un
# piccolo lettore C# (l'uscita e' di decine di MB). Cerca: la classe del cancello e il suo metodo `stateOf`, se lo `stateOf` chiama la
# decisione `FeatureFlags.backupMayWriteTo` e risponde `BackupGateState.TEST_ONLY`, chi istanzia la classe del cancello e chi altro chiama
# la decisione. Senza dexdump o senza vedere la MainActivity la lettura non e' affidabile e il passo FALLISCE (non salta).
$script:dexGateReaderLoaded = $false
function Initialize-DexGateReader {
  if ($script:dexGateReaderLoaded) { return }
  Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;

public class DexGateFacts {
  public bool SawMainActivity;
  public bool GateClassFound;
  public bool StateOfFound;
  public bool StateOfCallsDecision;
  public bool StateOfReturnsTestOnly;
  public bool StateOfCallsAccountDecision;
  public bool StateOfReturnsTestOnlyForAccount;
  public bool IncomingClassFound;
  public bool IncomingStateOfFound;
  public bool IncomingStateOfCallsDecision;
  public bool IncomingStateOfReturnsTestOnly;
  public bool IncomingStateOfCallsAccountDecision;
  public bool IncomingStateOfReturnsTestOnlyForAccount;
  public List<string> Instantiators = new List<string>();
  public List<string> IncomingInstantiators = new List<string>();
  public List<string> DecisionCallers = new List<string>();
  public List<string> AccountDecisionCallers = new List<string>();
  public int Lines;
}

public static class DexGateReader {
  const string GateClass = "Ldev/pampa/mycloudgallery/backup/AppBackupGate;";
  const string IncomingClass = "Ldev/pampa/mycloudgallery/backup/IncomingGate;";
  const string MainClass = "Ldev/pampa/mycloudgallery/MainActivity;";
  const string Decision = "Ldev/pampa/mycloudgallery/core/common/featureflags/FeatureFlags;.backupMayWriteTo:(Ljava/lang/String;)Z";
  const string AccountDecision = "Ldev/pampa/mycloudgallery/core/common/featureflags/FeatureFlags;.backupMayWriteAs:(Ljava/lang/String;)Z";
  const string TestOnly = "Ldev/pampa/mycloudgallery/core/backup/contract/BackupGateState;.TEST_ONLY:";

  static readonly Regex ClassLine = new Regex(@"^\s*Class descriptor\s*:\s*'([^']+)'", RegexOptions.Compiled);
  static readonly Regex NameLine = new Regex(@"^\s*name\s*:\s*'([^']*)'", RegexOptions.Compiled);
  static readonly Regex InsnLine = new Regex(@"\|[0-9a-fA-F]{4}:\s+(\S+)\s*(.*)$", RegexOptions.Compiled);

  public static void Scan(string dexdump, string dexPath, DexGateFacts facts) {
    var info = new ProcessStartInfo(dexdump, "-d \"" + dexPath + "\"") {
      UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
      StandardOutputEncoding = Encoding.GetEncoding(28591),
    };
    using (var process = Process.Start(info)) {
      string cls = "", method = "";
      bool sawDecisionInStateOf = false, sawAccountDecisionInStateOf = false;
      var errors = process.StandardError.ReadToEndAsync();
      string line;
      while ((line = process.StandardOutput.ReadLine()) != null) {
        facts.Lines++;
        var m = ClassLine.Match(line);
        if (m.Success) {
          cls = m.Groups[1].Value; method = ""; sawDecisionInStateOf = false; sawAccountDecisionInStateOf = false;
          if (cls == MainClass) facts.SawMainActivity = true;
          if (cls == GateClass) facts.GateClassFound = true;
          if (cls == IncomingClass) facts.IncomingClassFound = true;
          continue;
        }
        m = NameLine.Match(line);
        if (m.Success) {
          method = m.Groups[1].Value; sawDecisionInStateOf = false; sawAccountDecisionInStateOf = false;
          if (cls == GateClass && method == "stateOf") facts.StateOfFound = true;
          if (cls == IncomingClass && method == "stateOf") facts.IncomingStateOfFound = true;
          continue;
        }
        var insn = InsnLine.Match(line);
        if (!insn.Success) continue;
        string op = insn.Groups[1].Value, rest = insn.Groups[2].Value;
        string here = cls + "." + method;
        if (op.StartsWith("invoke") && rest.Contains(Decision)) {
          facts.DecisionCallers.Add(here);
          if (cls == GateClass && method == "stateOf") { facts.StateOfCallsDecision = true; sawDecisionInStateOf = true; }
          if (cls == IncomingClass && method == "stateOf") { facts.IncomingStateOfCallsDecision = true; sawDecisionInStateOf = true; }
        } else if (op.StartsWith("invoke") && rest.Contains(AccountDecision)) {
          facts.AccountDecisionCallers.Add(here);
          if (cls == GateClass && method == "stateOf") { facts.StateOfCallsAccountDecision = true; sawAccountDecisionInStateOf = true; }
          if (cls == IncomingClass && method == "stateOf") { facts.IncomingStateOfCallsAccountDecision = true; sawAccountDecisionInStateOf = true; }
        } else if (op == "new-instance" && rest.Contains(GateClass)) {
          facts.Instantiators.Add(here);
        } else if (op == "new-instance" && rest.Contains(IncomingClass)) {
          facts.IncomingInstantiators.Add(here);
        } else if (op.StartsWith("sget") && rest.Contains(TestOnly) && cls == GateClass && method == "stateOf") {
          // D12: la risposta TEST_ONLY dopo la decisione per share; R2: la risposta TEST_ONLY dopo la decisione per account.
          if (sawDecisionInStateOf) facts.StateOfReturnsTestOnly = true;
          if (sawAccountDecisionInStateOf) facts.StateOfReturnsTestOnlyForAccount = true;
        } else if (op.StartsWith("sget") && rest.Contains(TestOnly) && cls == IncomingClass && method == "stateOf") {
          // LA-5: lo stesso per il cancello dei file in arrivo.
          if (sawDecisionInStateOf) facts.IncomingStateOfReturnsTestOnly = true;
          if (sawAccountDecisionInStateOf) facts.IncomingStateOfReturnsTestOnlyForAccount = true;
        }
      }
      process.WaitForExit();
      if (process.ExitCode != 0) throw new InvalidOperationException("dexdump ha fallito: " + errors.Result);
    }
  }
}
'@
  $script:dexGateReaderLoaded = $true
}

# Il dexdump accanto ad aapt2 (stessa cartella build-tools).
function Get-DexDump {
  $aapt2 = Get-Aapt2
  if (-not $aapt2) { return $null }
  $dexdump = Join-Path (Split-Path -Parent $aapt2) "dexdump.exe"
  if (Test-Path -LiteralPath $dexdump) { return $dexdump }
  return $null
}

# Estrae i classes*.dex dell'APK in una cartella temporanea e li legge uno a uno.
function Get-D12GateFacts([string]$dexdump, [string]$apk) {
  Initialize-DexGateReader
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("mcg-dex-" + [guid]::NewGuid().ToString("N"))
  New-Item -ItemType Directory -Path $temp | Out-Null
  $facts = New-Object DexGateFacts
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
  try {
    $dexes = @($zip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' })
    if ($dexes.Count -eq 0) { throw "nessun classes*.dex nell'APK" }
    foreach ($entry in $dexes) {
      $target = Join-Path $temp $entry.FullName
      [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
      [DexGateReader]::Scan($dexdump, $target, $facts)
    }
  } finally {
    $zip.Dispose()
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
  }
  return $facts
}

# Le classi di un package `testing` (i fake dei contratti, D6 di Fase 2 e D23 di Fase 3).
function Get-TestingClassesInApk([string]$apk) {
  return Get-DexClassesMatching $apk 'L(?:[A-Za-z0-9_$]+/)+testing/[A-Za-z0-9_$/]*;'
}

# La misura (Fase 3, D21): classi `Bench*` (l'inizializzatore del dataset sintetico della variante benchmarkRelease),
# un package `benchmark`, androidx.benchmark e UiAutomator. Nessuna deve stare nella release.
# Eccezione nominata (Fase 6, P0): due tipi dell'API di `litertlm-android` 0.18.1 che hanno "Benchmark" nel nome ma non sono codice
# di misura (le statistiche di un motore e il file Kotlin che le espone). Restano per forza: la parte nativa della libreria nomina
# `BenchmarkInfo` per JNI e la regola keep di core-ai tiene la libreria intera. Elenco chiuso: ogni altra classe fa fallire il passo.
$script:allowedLibraryBenchmarkTypes = @("Lcom/google/ai/edge/litertlm/BenchmarkInfo;", "Lcom/google/ai/edge/litertlm/BenchmarkKt;")
function Get-BenchmarkClassesInApk([string]$apk) {
  $found = Get-DexClassesMatching $apk 'L(?:[A-Za-z0-9_$]+/)*(?:Bench[A-Za-z0-9_$]*;|benchmark/[A-Za-z0-9_$/]*;)|Landroidx/test/uiautomator/[A-Za-z0-9_$/]*;'
  return @($found | Where-Object { $script:allowedLibraryBenchmarkTypes -notcontains $_ })
}

# I permessi documentati in docs/sicurezza.md 4-bis: i nomi in maiuscolo tra apici inversi (es. `VIBRATE`),
# esclusa la riga "Non richiesti" (i permessi che l'app promette di NON avere non contano come documentati).
function Get-DocumentedPermissions {
  $text = Read-TextFile (Join-Path $root "docs\sicurezza.md")
  $start = $text.IndexOf("### 4-bis")
  if ($start -lt 0) { throw "docs\sicurezza.md non ha la sezione '### 4-bis'" }
  $rest = $text.Substring($start + 9)
  $end = [regex]::Match($rest, '(?m)^(---|## )')
  $section = if ($end.Success) { $rest.Substring(0, $end.Index) } else { $rest }
  $names = @{}
  foreach ($line in ($section -split '\r?\n')) {
    if ($line -match 'Non richiesti') { continue }
    foreach ($m in [regex]::Matches($line, '`([A-Z][A-Z0-9_]+)`')) { $names[$m.Groups[1].Value] = $true }
  }
  if ($names.Count -eq 0) { throw "la sezione 4-bis di docs\sicurezza.md non elenca nessun permesso" }
  return $names
}

# Un permesso (nome completo) e' documentato se il suo nome corto (dopo l'ultimo punto) e' nella sezione 4-bis.
# L'unico permesso ammesso senza riga e' quello `signature` che androidx.core aggiunge ai receiver dinamici.
function Get-UndocumentedPermissions($permissions, $documented) {
  $missing = @()
  foreach ($permission in $permissions) {
    if ($permission -match '\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$') { continue }
    $short = $permission.Substring($permission.LastIndexOf('.') + 1)
    if (-not $documented.ContainsKey($short)) { $missing += $permission }
  }
  return $missing
}

Write-Host ""
Write-Host "MyCloud Gallery - verifica completa" -ForegroundColor Cyan
Write-Host "  cartella: $root"
Write-Host ""

# --- 1. engine-doctor -------------------------------------------------------------------------------------
Step "engine-doctor (engine agganciato alla versione dichiarata)" {
  $doctor = Join-Path $root "engine\tools\engine-doctor.ps1"
  if (-not (Test-Path -LiteralPath $doctor)) { return "manca engine\tools\engine-doctor.ps1: submodule non inizializzato? (git submodule update --init)" }
  $run = Invoke-Native -File "powershell" -Arguments @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $doctor, "-AppRoot", ".")
  Show-Lines $run.Lines
  if ($run.Code -ne 0) { return "engine-doctor ha segnalato problemi (codice $($run.Code))" }
}

# --- 2. engine pulito -------------------------------------------------------------------------------------
Step "engine senza modifiche locali" {
  $status = Invoke-Native -File "git" -Arguments @("-C", "engine", "status", "--porcelain")
  if ($status.Code -ne 0) { return "git non risponde dentro engine/" }
  $dirty = @($status.Lines | Where-Object { $_.Trim().Length -gt 0 })
  if ($dirty.Count -gt 0) {
    Show-Lines $dirty 10
    return "engine/ ha $($dirty.Count) modifiche non committate: o vanno nel repo dell'engine o si perdono"
  }
}

# --- 3. repo senza segreti ---------------------------------------------------------------------------------
Step "repo senza segreti (chiavi di firma, local.properties)" {
  $files = Invoke-Native -File "git" -Arguments @("ls-files", "--cached", "--others", "--exclude-standard")
  if ($files.Code -ne 0) { return "git ls-files non risponde" }
  $bad = @($files.Lines | Where-Object { $_ -match '(\.jks|\.keystore|\.p12|\.pfx|\.pem)$' -or $_ -match '(^|/)local\.properties$' -or $_ -match '(^|/)keystore\.properties$' })
  if ($bad.Count -gt 0) {
    Show-Lines $bad 10
    return "file sensibili tracciati o non ignorati: " + ($bad -join ", ")
  }
}

# Fase 10 (P0; docs/fase10-progetto.md par. 4.3 "Chiavi", D31, D32, C442): nessuna chiave privata nel CONTENUTO dei file del repository (il
# passo 3 guarda solo i nomi). Una chiave PEM ("-----BEGIN ... PRIVATE KEY-----") o l'inizio di un PKCS#8 EC P-256 o RSA (in base64 o in
# esadecimale) fa fallire il passo, salvo nelle cartelle dei test (`src/test/`, `src/testDebug/`), dove possono stare solo chiavi di prova
# generate apposta (KT, keystore di prova). Le chiavi vere (pampa.jks, K1, K2) stanno solo sul PC dell'admin e nella copia offline.
# I marcatori sono scritti a pezzi: questo file non contiene mai un marcatore intero, cosi' non si segnala da solo.
$privateKeyMarkers = @(
  ('-----BEGIN [A-Z0-9 ]*' + "PRIVATE KEY" + '-----'),
  ('MIGHAgEAMBMGByqGSM49' + 'AgEGCCqGSM49AwEHBG0wawIBAQQg'),
  ('MEECAQAwEwYHKoZIzj0C' + 'AQYIKoZIzj0DAQcEJzAlAgEBBCA'),
  ('308187020100301306072a8648ce3d0201' + '06082a8648ce3d030107046d306b0201010420'),
  ('3041020100301306072a8648ce3d0201' + '06082a8648ce3d030107042730250201010420'),
  ('BgkqhkiG9w0BAQEF' + 'AASC')
)
Step "nessuna chiave privata nel contenuto dei file del repository (PEM, PKCS#8 EC e RSA; chiavi di prova solo nei test)" {
  $files = Invoke-Native -File "git" -Arguments @("ls-files", "--cached", "--others", "--exclude-standard")
  if ($files.Code -ne 0) { return "git ls-files non risponde" }
  $pattern = [regex]::new(($privateKeyMarkers -join "|"), [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
  $binary = '\.(png|jpg|jpeg|webp|gif|heic|jar|aar|so|bin|tflite|onnx|litertlm|task|zip|gz|ttf|otf|pbf|mbtiles|db|keystore)$'
  $latin1 = [System.Text.Encoding]::GetEncoding(28591)
  $problems = @()
  $read = 0
  foreach ($path in @($files.Lines | Where-Object { $_.Trim().Length -gt 0 })) {
    if ($path -match $binary) { continue }
    $full = Join-Path $root $path
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { continue }
    if ((Get-Item -LiteralPath $full).Length -gt 4MB) { continue }
    $read++
    $text = $latin1.GetString([System.IO.File]::ReadAllBytes($full))
    if (-not $pattern.IsMatch($text)) { continue }
    if ($path -match '(^|/)src/(test|testDebug)/') { continue }
    $problems += $path
  }
  Write-Host ("        file letti: {0}" -f $read) -ForegroundColor DarkGray
  if ($problems.Count -gt 0) { return "chiavi private (o il loro inizio) fuori dai test: " + ($problems -join ", ") }
}

# Fase 6 (P9, P11; docs/fase6-progetto.md par. 5.3): le etichette e le query del set privato della valutazione stanno solo sul telefono e
# nella cartella dell'utente, MAI nel repository. Come `NoPrivateEvalDataInGitTest` di core-search: nomi, cartelle, intestazione del
# formato dei file tracciati e regole del .gitignore.
Step "nessuna etichetta ne' query privata della valutazione nel repository (P9)" {
  $files = Invoke-Native -File "git" -Arguments @("ls-files", "--cached", "--others", "--exclude-standard")
  if ($files.Code -ne 0) { return "git ls-files non risponde" }
  $problems = @()
  foreach ($path in @($files.Lines | Where-Object { $_.Trim().Length -gt 0 })) {
    $segments = $path -split '/'
    $name = $segments[-1].ToLowerInvariant()
    if ($name.EndsWith(".mcgeval") -or $name.StartsWith("etichette") -or $name.StartsWith("query-private")) { $problems += "file di etichette: $path"; continue }
    $folders = @($segments | Select-Object -First ($segments.Count - 1))
    if (@($folders | Where-Object { $_ -ieq "eval-privato" -or $_ -ieq ".mycloudgallery" }).Count -gt 0) { $problems += "file in una cartella privata: $path"; continue }
    $full = Join-Path $root $path
    if (Test-Path -LiteralPath $full -PathType Leaf) {
      $stream = [System.IO.File]::OpenRead($full)
      try {
        $buffer = New-Object byte[] 8
        $read = $stream.Read($buffer, 0, 8)
      } finally { $stream.Dispose() }
      if ($read -eq 8 -and [System.Text.Encoding]::ASCII.GetString($buffer) -eq "MCGEVAL1") { $problems += "file nel formato delle etichette: $path" }
    }
  }
  $ignore = Read-TextFile (Join-Path $root ".gitignore")
  foreach ($rule in @("*.mcgeval", "etichette*", "query-private*", "eval-privato/", ".mycloudgallery/")) {
    if (-not ($ignore -split '\r?\n' | Where-Object { $_.Trim() -eq $rule })) { $problems += ".gitignore senza la regola $rule" }
  }
  Write-Host ("        file guardati: {0}" -f @($files.Lines).Count) -ForegroundColor DarkGray
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
}

# --- 4. verifica dei checksum -----------------------------------------------------------------------------
Step "verifica checksum delle dipendenze attiva" {
  $metadata = Join-Path $root "gradle\verification-metadata.xml"
  if (-not (Test-Path -LiteralPath $metadata)) { return "manca gradle\verification-metadata.xml (vedi README: come rigenerarlo)" }
  $xml = Read-TextFile $metadata
  if ($xml -notmatch '<verify-metadata>\s*true\s*</verify-metadata>') { return "verification-metadata.xml non ha <verify-metadata>true</verify-metadata>" }
  $components = ([regex]::Matches($xml, '<component ')).Count
  if ($components -lt 50) { return "verification-metadata.xml ha solo $components componenti: e' vuoto o troncato" }
  $properties = Read-TextFile (Join-Path $root "gradle.properties")
  if ($properties -match '(?im)^\s*org\.gradle\.dependency\.verification\s*=\s*(off|lenient)') { return "gradle.properties allenta la verifica dei checksum" }
  $wrapper = Read-TextFile (Join-Path $root "gradle\wrapper\gradle-wrapper.properties")
  if ($wrapper -notmatch '(?m)^distributionSha256Sum=[0-9a-f]{64}') { return "il wrapper non fissa distributionSha256Sum" }
}

# --- permessi: allowlist del manifest vs docs/sicurezza.md 4-bis (statico) ----------------------------------
Step "permessi ammessi dal controllo del manifest elencati in docs/sicurezza.md 4-bis" {
  $documented = Get-DocumentedPermissions
  $gradle = Read-TextFile (Join-Path $root "gradle\verify-release-manifest.gradle")
  $block = [regex]::Match($gradle, '(?s)allowedPermissions\s*:\s*\[(.*?)\n\s*\],')
  if (-not $block.Success) { return "non trovo allowedPermissions in gradle\verify-release-manifest.gradle" }
  $entries = @()
  foreach ($line in ($block.Groups[1].Value -split '\r?\n')) {
    $code = ($line -replace '//.*$', '').Trim()
    foreach ($m in [regex]::Matches($code, "'([^']+)'")) { $entries += $m.Groups[1].Value }
  }
  $missing = Get-UndocumentedPermissions $entries $documented
  if ($missing.Count -gt 0) {
    return "permessi ammessi in verify-release-manifest.gradle ma senza riga in docs\sicurezza.md 4-bis: " + ($missing -join ", ")
  }
}

# --- 5-7. gradle e APK ------------------------------------------------------------------------------------
$gradleOk = $false
Step "gradle: assembleDebug assembleRelease galleryTest galleryLint" -Skip:$SkipGradle -SkipReason "-SkipGradle" {
  Ensure-Java
  $arguments = @("--console=plain", "-q", "assembleDebug", "assembleRelease", "galleryTest", "galleryLint")
  if ($NoDaemon) { $arguments = @("--no-daemon") + $arguments }
  Write-Host "        (puo' volerci qualche minuto; il log completo e' in build\check-gradle.log)" -ForegroundColor DarkGray
  $run = Invoke-Native -File (Join-Path $root "gradlew.bat") -Arguments $arguments -LogPath (Join-Path $root "build\check-gradle.log")
  if ($run.Code -ne 0) {
    Show-Lines $run.Lines 60
    return "gradle e' fallito (codice $($run.Code))"
  }
  $script:gradleOk = $true
}

# I moduli senza unit test per costruzione: `:benchmark` e' un `com.android.test` (Macrobenchmark, D21) che non ha un
# task testDebugUnitTest e gira solo da un dispositivo. Tutti gli altri devono avere eseguito almeno un test.
$modulesWithoutUnitTests = @("benchmark")
$modules = @()
foreach ($line in (Get-Content -LiteralPath (Join-Path $root "settings.gradle"))) {
  if ($line -match "^\s*include\s+':([A-Za-z0-9\-]+)'" -and $modulesWithoutUnitTests -notcontains $Matches[1]) { $modules += $Matches[1] }
}

Step "test eseguiti in ogni modulo (nessuno a zero, nessuno fallito)" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $problems = @()
  $summary = @()
  foreach ($module in $modules) {
    $dir = Join-Path $root "$module\build\test-results\testDebugUnitTest"
    $tests = 0; $bad = 0
    if (Test-Path -LiteralPath $dir) {
      foreach ($file in Get-ChildItem $dir -Filter "*.xml") {
        $head = (Get-Content -LiteralPath $file.FullName -TotalCount 3) -join " "
        if ($head -match 'tests="(\d+)"') { $tests += [int]$Matches[1] }
        if ($head -match 'failures="(\d+)"') { $bad += [int]$Matches[1] }
        if ($head -match 'errors="(\d+)"') { $bad += [int]$Matches[1] }
      }
    }
    $summary += ("{0}={1}" -f $module, $tests)
    if ($tests -eq 0) { $problems += "${module}: ZERO test eseguiti" }
    if ($bad -gt 0) { $problems += "${module}: ${bad} falliti" }
  }
  Write-Host ("        test per modulo: " + ($summary -join "  ")) -ForegroundColor DarkGray
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
}

Step "APK di release: non debuggable, backup escluso, nessun componente esposto" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $aapt2 = Get-Aapt2
  if (-not $aapt2) { return "aapt2 non trovato: controlla sdk.dir in local.properties e le build-tools dell'SDK" }
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $signed = ($apk.Name -notmatch "unsigned")
  Write-Host ("        apk: {0} ({1})" -f $apk.Name, $(if ($signed) { "firmato" } else { "non firmato: mancano le chiavi pampa.* in local.properties, e' previsto" })) -ForegroundColor DarkGray

  $problems = @()
  $badging = Invoke-Native -File $aapt2 -Arguments @("dump", "badging", $apk.FullName)
  if ($badging.Code -ne 0) { return "aapt2 dump badging fallito" }
  if ($badging.Lines -match '^application-debuggable') { $problems += "l'APK di release e' DEBUGGABLE (application-debuggable)" }

  $facts = Get-ApkManifestFacts $aapt2 $apk.FullName
  if ($facts.Debuggable) { $problems += "android:debuggable=true nel manifest dell'APK" }
  if ($facts.AllowBackup -ne "false") { $problems += "android:allowBackup nell'APK e' '$($facts.AllowBackup)', deve essere false" }
  $exported = @($facts.Components | Where-Object { $_.Exported })
  $allowedExported = Get-AllowedExported
  foreach ($component in $exported) {
    if (-not $allowedExported.ContainsKey($component.Name)) {
      $problems += ("componente esportato non ammesso: <{0}> {1}" -f $component.Kind, $component.Name)
    } elseif ($allowedExported[$component.Name] -and $component.Permission -ne $allowedExported[$component.Name]) {
      $problems += ("<{0}> {1} e' esportato senza il permesso {2}" -f $component.Kind, $component.Name, $allowedExported[$component.Name])
    }
  }
  # Fase 4B (D29): ogni JobService (quello di WorkManager e quello delle operazioni avviate dall'utente) lo lega solo il sistema, quindi
  # deve richiedere BIND_JOB_SERVICE; quello dell'app non e' esportato (lo prende gia' il controllo qui sopra: non e' fra gli ammessi).
  foreach ($component in @($facts.Components | Where-Object { $_.Kind -eq "service" -and $_.Name -match 'JobService$' })) {
    if ($component.Permission -ne "android.permission.BIND_JOB_SERVICE") {
      $problems += ("<service> {0} e' un JobService senza il permesso BIND_JOB_SERVICE" -f $component.Name)
    }
  }
  # Fase 3: il ProfileInstallReceiver (Macrobenchmark) non deve esserci neanche non esportato.
  foreach ($component in @($facts.Components | Where-Object { $_.Name -eq "androidx.profileinstaller.ProfileInstallReceiver" })) {
    $problems += "il ProfileInstallReceiver e' nella release (<{0}>): torna solo nella variante benchmarkRelease" -f $component.Kind
  }
  if (-not ($exported | Where-Object { $_.Name -eq "dev.pampa.mycloudgallery.MainActivity" })) {
    $problems += "la launcher activity non risulta esportata: il lettore del manifest non funziona o l'app non si avvia"
  }
  # Fase 5B (P0-B): i componenti delle prove guidate (`FieldProbe*`, solo nel manifest di debug) non devono essere nel manifest di release,
  # nemmeno non esportati.
  foreach ($component in @($facts.Components | Where-Object { $_.Name -match 'debugtools\.probes\.|FieldProbe' })) {
    $problems += ("un componente delle prove guidate e' nella release: <{0}> {1}" -f $component.Kind, $component.Name)
  }
  # Fase 5B (P0-B): i quattro componenti di `:feature-integrations` ci sono, con la forma approvata: le due activity e il provider dei
  # documenti esportati (quest'ultimo con MANAGE_DOCUMENTS, gia' pretesa sopra), il FileProvider NON esportato. Se uno manca il lettore del
  # manifest non funziona o il modulo non e' stato legato all'app.
  $integrations = @(
    @{ Name = "dev.pampa.mycloudgallery.feature.integrations.picker.PickerActivity"; Exported = $true },
    @{ Name = "dev.pampa.mycloudgallery.feature.integrations.receive.ShareReceiverActivity"; Exported = $true },
    @{ Name = "dev.pampa.mycloudgallery.feature.integrations.provider.MediaDocumentsProvider"; Exported = $true },
    @{ Name = "dev.pampa.mycloudgallery.feature.integrations.share.IntegrationFileProvider"; Exported = $false }
  )
  foreach ($expected in $integrations) {
    $found = @($facts.Components | Where-Object { $_.Name -eq $expected.Name })
    if ($found.Count -eq 0) {
      $problems += ("manca nella release il componente approvato dalla 5B: {0}" -f $expected.Name)
    } elseif ([bool]$found[0].Exported -ne $expected.Exported) {
      $problems += ("{0} ha esportato={1}, doveva essere {2}" -f $expected.Name, [bool]$found[0].Exported, $expected.Exported)
    }
  }
  Write-Host ("        componenti: {0}, esportati: {1}" -f @($facts.Components).Count, (($exported | ForEach-Object { $_.Name }) -join ", ")) -ForegroundColor DarkGray

  if ($problems.Count -gt 0) { return ($problems -join "; ") }
}

Step "nessuna classe di un package testing nel dex di release (fake solo per i test)" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $testing = @(Get-TestingClassesInApk $apk.FullName)
  if ($testing.Count -gt 0) {
    Show-Lines $testing 20
    return "$($testing.Count) classi di un package testing nel dex di release: i fake (D6) non devono arrivare all'APK"
  }
}

# I resti degli strumenti di debug (I4): nomi di classi e stringhe che nella release non devono esserci. Il passo legge il dex
# direttamente (come i passi dei fake e della misura): se non lo legge FALLISCE, non salta (prima usava apkanalyzer e, senza,
# saltava in silenzio).
$debugRemnants = @(
  # Il sink di logcat e il catalogo dei componenti (Fase 1/2).
  "LogcatSink", "ComponentCatalogPlaceholder", "core/designsystem/catalog", "DeveloperEntryPoint", "CatalogRoute",
  # La famiglia "pronta" per lo smoke sull'emulatore: il marcatore scritto da adb e la famiglia finta (DeveloperTools di debug).
  "debug-family-ready", "DeveloperTools`$ReadyFamily",
  # La Libreria sintetica (M1): il kit, i nomi del suo database e della sua istantanea, la manopola che sceglie la dimensione.
  "SyntheticCatalogKit", "gallery-synthetic", "replica-synthetic", "libreria-sintetica", "m1-synthetic-count", "DebugSyntheticSize",
  "LibraryDebugHooks",
  # Fase 4A (P11, D33): la recinzione delle prove sul campo (`ShareFence`) e' legata solo nella build debug
  # (DeveloperTools di debug); in release non e' referenziata e R8 la toglie. Anche il NAS sintetico del menu di debug.
  "core/nas/fence/ShareFence", "SyntheticNasKit", "nas-synthetic",
  # Fase 4B (revisione 4B, prova F20): il gancio che accorcia i 30 giorni del cestino del NAS (DeveloperTools di debug) e il nome del
  # suo marcatore scritto da adb. In release DeveloperTools torna null e il gancio non deve esistere.
  "DebugTrashRetention", "debug-trash-retention",
  # Fase 5A (P0, D13): la recinzione del telefono del backup con gli account di prova (DeveloperTools di debug, app/src/debug) e le
  # cartelle che recinta: in release DeveloperTools.backupFence() risponde null e niente di questo deve esistere.
  "DebugBackupFence", "mcg-prova", "mcg-campo",
  # Fase 5B (P0-B): le prove guidate T-09, T-14, T-15 e T-16 (schermata, selettore e ricezione di prova, provider di file e di documenti di
  # prova, resoconto di soli numeri): classi `FieldProbe*` in app/src/debug, componenti nel manifest di debug, cartella `field-probe`.
  # In release DeveloperTools torna senza la rotta e niente di questo deve esistere (ne' nel dex ne' nel manifest).
  "FieldProbe", "debugtools/probes", "field-probe", "fieldprobe",
  # Fase 8 (correzioni E, DR-2): il ricevitore degli scenari delle persone (banco F103 e ricetta forzata F109) e il suo lavoro, il file di stato e la ricetta forzata.
  # Gia' coperti dal prefisso `FieldProbe`, ma nominati: in release non deve esistere niente di tutto questo (il ricevitore vive solo in app/src/debug).
  "FieldProbeFacesScenario", "FieldProbeFacesStatus", "FieldProbeFacesRecipe", "FieldProbeFacesEntryPoint",
  # Fase 6 (P9): lo strumento di valutazione della ricerca (app/src/debug/.../debugtools/eval): classi, porta e nome del file di etichette.
  "debugtools/eval", "EvalEnvironmentHolder", "EvalSession", "EvalEntry", "labels.mcgeval", "MCGEVAL1",
  # Fase 7 (P9, D22, L65): il widget di prova dei Nascosti (app/src/debug/.../debugtools/widget): ricevitore, contenuto e disegno. Solo debug.
  "debugtools/widget", "FieldProbeHiddenWidget", "FieldProbeWidget",
  # Fase 7 (P12): lo strumento di valutazione degli album smart e dei viaggi (app/src/debug/.../debugtools/eval/albums): classi, porta, nomi del file di etichette
  # (`viaggi.mcgalb`, intestazione MCGALB01) e del file dei viaggi sintetici (`viaggi-sintetici`, intestazione MCGSINT1). Solo debug.
  "debugtools/eval/albums", "AlbumsEvalEnvironmentHolder", "AlbumsEvalSession", "AlbumsEvalEntry", "AppAlbumsEvalEnvironment", "viaggi.mcgalb", "MCGALB01", "MCGSINT1", "viaggi-sintetici",
  # Fase 8 (P11): lo strumento di valutazione delle persone (app/src/debug/.../debugtools/eval/faces): classi, porta e nome del file di etichette
  # (`persone.mcgfac`, intestazione MCGFAC01). Le etichette sono posti di volti veri: in release non deve esistere niente di tutto questo.
  "debugtools/eval/faces", "FacesEvalEnvironmentHolder", "FacesEvalSession", "FacesEvalEntry", "FacesEvalStore", "persone.mcgfac", "MCGFAC01",
  # Fase 9 (P0 per P11, §5.3): lo strumento di valutazione dei ricordi (app/src/debug/.../debugtools/eval/memories): classi, porta e nome del file
  # dei giudizi (`ricordi.mcgmem`, intestazione MCGMEM01). Giudizi su ricordi veri: in release non deve esistere niente di tutto questo. P11
  # usa questi nomi (o aggiorna qui la riga).
  "debugtools/eval/memories", "MemoriesEvalEnvironmentHolder", "MemoriesEvalSession", "MemoriesEvalEntry", "MemoriesEvalStore", "ricordi.mcgmem", "MCGMEM01",
  # Fase 10 (P0 per P1 e P11; docs/fase10-progetto.md par. 2.8 "Le chiavi nell'app", par. 2.11 S14): la chiave di PROVA del manifest (KT, fidata
  # solo in debug e nei test: P1 la scrive in `ReleaseTestKeys`) e lo strumento dell'admin che genera le chiavi e firma i manifest (`ManifestTool`,
  # solo sul PC, in tools/release/). Nella release non deve esistere niente di tutto questo; le stringhe di KT le cerca il passo 7i.
  "ReleaseTestKeys", "ManifestTool",
  # Fase 10 (P10; docs/fase10-progetto.md par. 2.10): la "Simulazione del passo" (sola lettura, solo build debug dell'admin, Impostazioni -> Sviluppo): il giro
  # e la schermata in feature-diagnostics (`diagnostics/release/Step*`), le fonti e il montaggio in `:app/.../release/` (`LiveStepSources`, `StepForecasts`,
  # `StepNasSpace`), l'ingresso in app/src/debug. Nella release non deve esistere niente di tutto questo (la raggiunge solo `DeveloperTools` di debug).
  "StepSimulation", "LiveStepSources", "StepForecasts", "StepNasSpace", "diagnostics/release/Step", "mycloudgallery/release/Step"
)
Step "nessun resto degli strumenti di debug nel dex di release (Libreria e NAS sintetici, famiglia pronta, sink di logcat, ShareFence D33, cestino corto F20, recinzione del telefono D13, prove guidate 5B, valutazione della ricerca P9, widget di prova dei Nascosti, valutazione degli album P12, valutazione delle persone P11, valutazione dei ricordi F9-P11, chiave di prova del manifest e ManifestTool F10, simulazione del passo P10)" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $pattern = ($debugRemnants | ForEach-Object { [regex]::Escape($_) }) -join "|"
  $found = @(Get-DexClassesMatching $apk.FullName $pattern)
  if ($found.Count -gt 0) {
    Show-Lines $found 20
    return "$($found.Count) resti di strumenti di debug nel dex di release: " + ($found -join ", ")
  }
  Write-Host ("        cercati {0} nomi, nessuno nel dex" -f $debugRemnants.Count) -ForegroundColor DarkGray
}

# R11 (revisione della Fase 4A): il passo sopra cerca nomi di classe nel dex, ma R8 sposta metodi fra classi anche senza
# offuscare (in questa release `FinalResponseCheck.signed` sta dentro un'altra classe e il dex non contiene la stringa
# `FinalResponseCheck`): se la recinzione D33 diventasse raggiungibile e fosse fusa, il passo sopra passerebbe lo stesso. Qui si
# guardano gli ESITI di R8 della stessa build: ogni classe della recinzione deve risultare TOLTA per intero (una riga sola col
# suo nome in `usage.txt`) e non comparire da nessuna parte in `mapping.txt` (ne' tenuta ne' fusa). Senza i due file il passo
# FALLISCE (non salta).
$fenceClasses = @("dev.pampa.mycloudgallery.core.nas.fence.ShareFence")
Step "R8 ha tolto la recinzione D33 dalla release (usage.txt e mapping.txt)" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $dir = Join-Path $root "app\build\outputs\mapping\release"
  $usage = Join-Path $dir "usage.txt"
  $mapping = Join-Path $dir "mapping.txt"
  if (-not (Test-Path $usage) -or -not (Test-Path $mapping)) { return "mancano usage.txt o mapping.txt in app\build\outputs\mapping\release" }
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  # Gli esiti di R8 vengono prima del pacchetto, mai dopo (un pacchetto rifatto senza rifare R8 puo' essere piu' nuovo).
  if ((Get-Item $mapping).LastWriteTime -gt $apk.LastWriteTime.AddMinutes(1)) {
    return "mapping.txt e' piu' nuovo dell'APK di release: rifare la build di release"
  }
  # Controllo positivo: se la lettura non trova nemmeno l'activity di avvio, i file non sono quelli giusti.
  if (-not (Select-String -Path $mapping -SimpleMatch -Pattern "dev.pampa.mycloudgallery.MainActivity -> " -Quiet)) {
    return "mapping.txt non leggibile: manca la riga di MainActivity"
  }
  $problems = @()
  foreach ($cls in $fenceClasses) {
    if (-not (Select-String -Path $usage -Pattern ("^" + [regex]::Escape($cls) + "$") -Quiet)) {
      $problems += "$cls non risulta tolta per intero da R8 (usage.txt)"
    }
    if (Select-String -Path $mapping -SimpleMatch -Pattern $cls -Quiet) {
      $problems += "$cls compare in mapping.txt: R8 l'ha tenuta o fusa in un'altra classe"
    }
  }
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
  Write-Host ("        {0} classi della recinzione tolte da R8, nessuna in mapping.txt" -f $fenceClasses.Count) -ForegroundColor DarkGray
}

# Fase 5A (P0, D12): "backup solo sulle share di prova" e' una protezione che deve esserci NELLA RELEASE (come F4-D31), accesa e non
# spegnibile (FeatureFlagsTest). Qui si controlla che R8 non l'abbia tolta: i due membri di `FeatureFlags` (il campo e la decisione per
# share, tenuti da core-common/consumer-rules.pro anche prima che P7 leghi il cancello del backup) devono stare nel dex dell'APK di
# release e in mapping.txt. Senza i file il passo FALLISCE (non salta).
$d12Members = @("getBackupTestSharesOnly", "backupMayWriteTo", "getBackupBlocksTestAccounts", "backupMayWriteAs")
Step "D12 e R2 nella release: FeatureFlags.backupTestSharesOnly, backupMayWriteTo, backupBlocksTestAccounts e backupMayWriteAs nel dex e in mapping.txt, e il cancello che li usa" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $mapping = Join-Path $root "app\build\outputs\mapping\release\mapping.txt"
  if (-not (Test-Path $mapping)) { return "manca mapping.txt in app\build\outputs\mapping\release" }
  $problems = @()
  $found = @(Get-DexClassesMatching $apk.FullName (($d12Members | ForEach-Object { [regex]::Escape($_) }) -join "|"))
  foreach ($member in $d12Members) {
    if ($found -notcontains $member) { $problems += "$member non e' nel dex di release: la protezione D12 e' stata tolta" }
  }
  $flags = "dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags"
  if (-not (Select-String -Path $mapping -SimpleMatch -Pattern "$flags -> " -Quiet)) { $problems += "FeatureFlags non e' in mapping.txt" }
  foreach ($member in @("boolean backupMayWriteTo(java.lang.String)", "boolean getBackupTestSharesOnly()", "boolean backupMayWriteAs(java.lang.String)", "boolean getBackupBlocksTestAccounts()")) {
    if (-not (Select-String -Path $mapping -SimpleMatch -Pattern $member -Quiet)) { $problems += "$member non e' in mapping.txt" }
  }
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
  Write-Host "        D12 e R2 presenti nella release (dex e mapping.txt)" -ForegroundColor DarkGray

  # Fase 5A (P7): il campo e la decisione non bastano, serve il CANCELLO che la usa. Il bytecode della release (dexdump): lo `stateOf` del
  # cancello chiama `FeatureFlags.backupMayWriteTo` e risponde `BackupGateState.TEST_ONLY`, e il cancello e' istanziato (collegato al grafo
  # del backup). R8 puo' fondere o inlineare: le regole di app\proguard-rules.pro tengono il cancello e vietano di inlineare la decisione.
  $dexdump = Get-DexDump
  if (-not $dexdump) { return "dexdump.exe non trovato accanto ad aapt2 (build-tools): non posso provare il cancello D12 nel dex di release" }
  $gate = Get-D12GateFacts $dexdump $apk.FullName
  if ($gate.Lines -lt 1000 -or -not $gate.SawMainActivity) { return "lettura del bytecode non affidabile ($($gate.Lines) righe, MainActivity vista: $($gate.SawMainActivity))" }
  if (-not $gate.GateClassFound) { $problems += "AppBackupGate non e' nel dex di release: il cancello del backup e' stato tolto o fuso" }
  elseif (-not $gate.StateOfFound) { $problems += "AppBackupGate.stateOf non e' nel dex di release" }
  else {
    if (-not $gate.StateOfCallsDecision) { $problems += "AppBackupGate.stateOf non chiama FeatureFlags.backupMayWriteTo: la decisione D12 non governa il cancello" }
    elseif (-not $gate.StateOfReturnsTestOnly) { $problems += "AppBackupGate.stateOf non risponde BackupGateState.TEST_ONLY dopo la decisione D12" }
    # R2 (revisione 5A): in release un account di prova non scrive in nessuna share. Il controllo sta nel cancello della release, non solo in
    # debug: stateOf chiama la decisione per account e, se non passa, risponde TEST_ONLY.
    if (-not $gate.StateOfCallsAccountDecision) { $problems += "AppBackupGate.stateOf non chiama FeatureFlags.backupMayWriteAs: in release un account di prova scriverebbe senza la recinzione D13 (R2)" }
    elseif (-not $gate.StateOfReturnsTestOnlyForAccount) { $problems += "AppBackupGate.stateOf non risponde BackupGateState.TEST_ONLY dopo la decisione per account (R2)" }
  }
  if ($gate.Instantiators.Count -eq 0) { $problems += "nessun codice istanzia AppBackupGate: il cancello non e' collegato al backup" }
  elseif (-not ($gate.Instantiators | Where-Object { $_ -like "Ldev/pampa/mycloudgallery/backup/BackupGraph;.*" })) {
    $problems += ("AppBackupGate e' istanziato solo da " + (($gate.Instantiators | Select-Object -Unique) -join ", ") + ", non da BackupGraph")
  }
  # LA-5 (verifica funzionale della 5B): il secondo cancello, quello dei file in arrivo ("Salva sul NAS" scrive nella share di
  # destinazione, anche quella comune). Stessa prova di quello del backup: se R8 lo togliesse, lo fondesse o ne perdesse una decisione, i
  # file ricevuti andrebbero in una share vera anche in release. Lo tiene app\proguard-rules.pro; lo istanzia una classe del backup.
  if (-not $gate.IncomingClassFound) { $problems += "IncomingGate non e' nel dex di release: il cancello dei file in arrivo e' stato tolto o fuso (D12 e R2 non valgono per Salva sul NAS)" }
  elseif (-not $gate.IncomingStateOfFound) { $problems += "IncomingGate.stateOf non e' nel dex di release" }
  else {
    if (-not $gate.IncomingStateOfCallsDecision) { $problems += "IncomingGate.stateOf non chiama FeatureFlags.backupMayWriteTo: D12 non governa i file in arrivo" }
    elseif (-not $gate.IncomingStateOfReturnsTestOnly) { $problems += "IncomingGate.stateOf non risponde BackupGateState.TEST_ONLY dopo la decisione D12" }
    if (-not $gate.IncomingStateOfCallsAccountDecision) { $problems += "IncomingGate.stateOf non chiama FeatureFlags.backupMayWriteAs: in release un account di prova riceverebbe file senza la recinzione D13 (R2)" }
    elseif (-not $gate.IncomingStateOfReturnsTestOnlyForAccount) { $problems += "IncomingGate.stateOf non risponde BackupGateState.TEST_ONLY dopo la decisione per account (R2)" }
  }
  if ($gate.IncomingInstantiators.Count -eq 0) { $problems += "nessun codice istanzia IncomingGate: il cancello dei file in arrivo non e' collegato" }
  elseif (-not ($gate.IncomingInstantiators | Where-Object { $_ -like "Ldev/pampa/mycloudgallery/backup/*" })) {
    $problems += ("IncomingGate e' istanziato solo da " + (($gate.IncomingInstantiators | Select-Object -Unique) -join ", ") + ", non da una classe del backup")
  }
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
  Write-Host ("        cancello D12 e R2 provato nel bytecode: stateOf -> backupMayWriteTo -> TEST_ONLY e stateOf -> backupMayWriteAs -> TEST_ONLY, istanziato da {0}, decisioni chiamate da {1} e {2} metodi ({3} righe di dexdump)" -f
    (($gate.Instantiators | Select-Object -Unique) -join ", "), @($gate.DecisionCallers | Select-Object -Unique).Count, @($gate.AccountDecisionCallers | Select-Object -Unique).Count, $gate.Lines) -ForegroundColor DarkGray
  Write-Host ("        cancello dei file in arrivo (LA-5) provato nel bytecode: IncomingGate.stateOf -> backupMayWriteTo/backupMayWriteAs -> TEST_ONLY, istanziato da {0}" -f
    (($gate.IncomingInstantiators | Select-Object -Unique) -join ", ")) -ForegroundColor DarkGray
}

# Il bytecode dei cancelli D30 (R20 della revisione di sicurezza della Fase 7): come per D12, `dexdump -d` dei classes*.dex dell'APK letto in
# streaming da un piccolo lettore C#, separato da quello di D12. Cerca: il cancello delle scritture degli album (`AppAlbumWriteGate.blockFor`)
# che chiama la decisione `FeatureFlags.sharedAlbumsMayUse` e poi risponde `AlbumWriteBlock.TEST_SHARES_ONLY`; il cancello della lettura
# (`FlagsAlbumReadGate.mayRead`) che chiama la stessa decisione; chi istanzia i due cancelli; chi chiama i due cancelli dal motore degli album
# (pubblicatore, piano della condivisione, replica: per interfaccia, o sulla classe se R8 la rende diretta). Senza dexdump o senza vedere la
# MainActivity la lettura non e' affidabile e il passo FALLISCE (non salta).
$script:dexAlbumGateReaderLoaded = $false
function Initialize-DexAlbumGateReader {
  if ($script:dexAlbumGateReaderLoaded) { return }
  Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;

public class DexAlbumGateFacts {
  public bool SawMainActivity;
  public bool WriteGateFound;
  public bool BlockForFound;
  public bool BlockForCallsDecision;
  public bool BlockForReturnsTestSharesOnly;
  public bool ReadGateFound;
  public bool MayReadFound;
  public bool MayReadCallsDecision;
  public List<string> WriteGateInstantiators = new List<string>();
  public List<string> ReadGateInstantiators = new List<string>();
  public List<string> DecisionCallers = new List<string>();
  public List<string> WriteGateUsers = new List<string>();
  public List<string> ReadGateUsers = new List<string>();
  public int Lines;
}

public static class DexAlbumGateReader {
  const string WriteGate = "Ldev/pampa/mycloudgallery/albums/AppAlbumWriteGate;";
  const string ReadGate = "Ldev/pampa/mycloudgallery/albums/FlagsAlbumReadGate;";
  const string MainClass = "Ldev/pampa/mycloudgallery/MainActivity;";
  const string Decision = "Ldev/pampa/mycloudgallery/core/common/featureflags/FeatureFlags;.sharedAlbumsMayUse:(Ljava/lang/String;)Z";
  const string TestSharesOnly = "Ldev/pampa/mycloudgallery/core/albums/contract/AlbumWriteBlock;.TEST_SHARES_ONLY:";
  const string WritePort = "Ldev/pampa/mycloudgallery/core/albums/contract/AlbumWriteGate;.blockFor:";
  const string WriteDirect = "Ldev/pampa/mycloudgallery/albums/AppAlbumWriteGate;.blockFor:";
  const string ReadPort = "Ldev/pampa/mycloudgallery/core/albums/shared/AlbumReadGate;.mayRead:";
  const string ReadDirect = "Ldev/pampa/mycloudgallery/albums/FlagsAlbumReadGate;.mayRead:";

  static readonly Regex ClassLine = new Regex(@"^\s*Class descriptor\s*:\s*'([^']+)'", RegexOptions.Compiled);
  static readonly Regex NameLine = new Regex(@"^\s*name\s*:\s*'([^']*)'", RegexOptions.Compiled);
  static readonly Regex InsnLine = new Regex(@"\|[0-9a-fA-F]{4}:\s+(\S+)\s*(.*)$", RegexOptions.Compiled);

  public static void Scan(string dexdump, string dexPath, DexAlbumGateFacts facts) {
    var info = new ProcessStartInfo(dexdump, "-d \"" + dexPath + "\"") {
      UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
      StandardOutputEncoding = Encoding.GetEncoding(28591),
    };
    using (var process = Process.Start(info)) {
      string cls = "", method = "";
      bool sawDecisionInBlockFor = false;
      var errors = process.StandardError.ReadToEndAsync();
      string line;
      while ((line = process.StandardOutput.ReadLine()) != null) {
        facts.Lines++;
        var m = ClassLine.Match(line);
        if (m.Success) {
          cls = m.Groups[1].Value; method = ""; sawDecisionInBlockFor = false;
          if (cls == MainClass) facts.SawMainActivity = true;
          if (cls == WriteGate) facts.WriteGateFound = true;
          if (cls == ReadGate) facts.ReadGateFound = true;
          continue;
        }
        m = NameLine.Match(line);
        if (m.Success) {
          method = m.Groups[1].Value; sawDecisionInBlockFor = false;
          if (cls == WriteGate && method == "blockFor") facts.BlockForFound = true;
          if (cls == ReadGate && method == "mayRead") facts.MayReadFound = true;
          continue;
        }
        var insn = InsnLine.Match(line);
        if (!insn.Success) continue;
        string op = insn.Groups[1].Value, rest = insn.Groups[2].Value;
        string here = cls + "." + method;
        if (op.StartsWith("invoke") && rest.Contains(Decision)) {
          facts.DecisionCallers.Add(here);
          if (cls == WriteGate && method == "blockFor") { facts.BlockForCallsDecision = true; sawDecisionInBlockFor = true; }
          if (cls == ReadGate && method == "mayRead") facts.MayReadCallsDecision = true;
        } else if (op.StartsWith("invoke") && (rest.Contains(WritePort) || rest.Contains(WriteDirect))) {
          facts.WriteGateUsers.Add(here);
        } else if (op.StartsWith("invoke") && (rest.Contains(ReadPort) || rest.Contains(ReadDirect))) {
          facts.ReadGateUsers.Add(here);
        } else if (op == "new-instance" && rest.Contains(WriteGate)) {
          facts.WriteGateInstantiators.Add(here);
        } else if (op == "new-instance" && rest.Contains(ReadGate)) {
          facts.ReadGateInstantiators.Add(here);
        } else if (op.StartsWith("sget") && rest.Contains(TestSharesOnly) && cls == WriteGate && method == "blockFor") {
          // D30: la risposta TEST_SHARES_ONLY dopo la decisione per share.
          if (sawDecisionInBlockFor) facts.BlockForReturnsTestSharesOnly = true;
        }
      }
      process.WaitForExit();
      if (process.ExitCode != 0) throw new InvalidOperationException("dexdump ha fallito: " + errors.Result);
    }
  }

  // I metodi del dex ("Lclasse;.metodo") che contengono codice delle classi originali [patterns], secondo mapping.txt. Anche con
  // -dontobfuscate R8 fonde classi di pacchetti diversi (il pubblicatore degli album finisce in NasPersonalSync, la replica in una classe di
  // CameraX) e inlinea metodi: il nome della classe nel dex non dice da dove viene il codice, mapping.txt si'. Ogni riga di un metodo e'
  // "[a:b:]tipo [classeOriginale.]nome(argomenti)[:x[:y]] -> nomeNelDex" sotto "classeOriginale -> classeNelDex:"; senza classe davanti al
  // nome il codice e' della classe del blocco. Un pattern che finisce con '*' vale come prefisso; senza, la classe e le sue classi interne.
  public static Dictionary<string, HashSet<string>> ResidualMethods(string mapping, string[] patterns) {
    var result = new Dictionary<string, HashSet<string>>();
    foreach (var p in patterns) result[p] = new HashSet<string>();
    string owner = null, residual = null;
    foreach (var raw in File.ReadLines(mapping)) {
      if (raw.Length == 0 || raw[0] == '#') continue;
      if (raw[0] != ' ') {
        int arrow = raw.IndexOf(" -> ");
        if (arrow < 0 || !raw.EndsWith(":")) { owner = null; residual = null; continue; }
        owner = raw.Substring(0, arrow);
        residual = "L" + raw.Substring(arrow + 4, raw.Length - arrow - 5).Replace('.', '/') + ";";
        continue;
      }
      if (residual == null) continue;
      string line = raw.Trim();
      if (line.StartsWith("#")) continue;
      int arrowM = line.LastIndexOf(" -> ");
      if (arrowM < 0) continue;
      string left = line.Substring(0, arrowM), name = line.Substring(arrowM + 4);
      int paren = left.IndexOf('(');
      if (paren < 0) continue;
      int space = left.LastIndexOf(' ', paren);
      string qualified = left.Substring(space + 1, paren - space - 1);
      int dot = qualified.LastIndexOf('.');
      string origin = dot < 0 ? owner : qualified.Substring(0, dot);
      foreach (var p in patterns) {
        bool hit = p.EndsWith("*") ? origin.StartsWith(p.Substring(0, p.Length - 1)) : (origin == p || origin.StartsWith(p + "$"));
        if (hit) result[p].Add(residual + "." + name);
      }
    }
    return result;
  }
}
'@
  $script:dexAlbumGateReaderLoaded = $true
}

# Estrae i classes*.dex dell'APK in una cartella temporanea e li legge uno a uno con il lettore dei cancelli D30.
function Get-D30GateFacts([string]$dexdump, [string]$apk) {
  Initialize-DexAlbumGateReader
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("mcg-dex30-" + [guid]::NewGuid().ToString("N"))
  New-Item -ItemType Directory -Path $temp | Out-Null
  $facts = New-Object DexAlbumGateFacts
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
  try {
    $dexes = @($zip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' })
    if ($dexes.Count -eq 0) { throw "nessun classes*.dex nell'APK" }
    foreach ($entry in $dexes) {
      $target = Join-Path $temp $entry.FullName
      [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
      [DexAlbumGateReader]::Scan($dexdump, $target, $facts)
    }
  } finally {
    $zip.Dispose()
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
  }
  return $facts
}

# Fase 7 (P0, D30): "album condivisi solo sulle share di prova" e' una protezione che deve esserci NELLA RELEASE (come D12), accesa e non
# spegnibile (FeatureFlagsTest). Qui si controlla che R8 non l'abbia tolta: il campo e la decisione per share di `FeatureFlags`, tenuti da
# core-common/consumer-rules.pro, devono stare nel dex dell'APK di release e in mapping.txt. R20 della revisione di sicurezza della Fase 7: e,
# come per D12, il CANCELLO che li usa, nel bytecode della release (Get-D30GateFacts). Senza i file il passo FALLISCE (non salta).
$d30Members = @("getSharedAlbumsTestSharesOnly", "sharedAlbumsMayUse")
Step "D30 nella release: FeatureFlags.sharedAlbumsTestSharesOnly e sharedAlbumsMayUse nel dex e in mapping.txt, e i cancelli degli album che li usano" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $mapping = Join-Path $root "app\build\outputs\mapping\release\mapping.txt"
  if (-not (Test-Path $mapping)) { return "manca mapping.txt in app\build\outputs\mapping\release" }
  $problems = @()
  $found = @(Get-DexClassesMatching $apk.FullName (($d30Members | ForEach-Object { [regex]::Escape($_) }) -join "|"))
  foreach ($member in $d30Members) {
    if ($found -notcontains $member) { $problems += "$member non e' nel dex di release: la protezione D30 e' stata tolta" }
  }
  foreach ($member in @("boolean sharedAlbumsMayUse(java.lang.String)", "boolean getSharedAlbumsTestSharesOnly()")) {
    if (-not (Select-String -Path $mapping -SimpleMatch -Pattern $member -Quiet)) { $problems += "$member non e' in mapping.txt" }
  }
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
  Write-Host "        D30 presente nella release (dex e mapping.txt)" -ForegroundColor DarkGray

  # R20 (revisione di sicurezza della Fase 7): il campo e la decisione non bastano (la regola di consumer-rules.pro li tiene anche se nessuno
  # li chiama), serve il CANCELLO che li usa. Nel bytecode della release: `AppAlbumWriteGate.blockFor` chiama `FeatureFlags.sharedAlbumsMayUse`
  # e poi risponde `AlbumWriteBlock.TEST_SHARES_ONLY`; `FlagsAlbumReadGate.mayRead` chiama la stessa decisione; i due cancelli li istanzia il
  # montaggio degli album di :app (`albums/` o `di/`); il pubblicatore, il piano della condivisione e la replica li chiamano. Le regole di
  # app\proguard-rules.pro tengono i due metodi (niente fusioni ne' inline). Le altre classi R8 le fonde e le inlinea anche fra pacchetti
  # diversi: chi istanzia e chi chiama si riconosce dal nome nel dex o, se fuso altrove, da mapping.txt (DexAlbumGateReader.ResidualMethods).
  $dexdump = Get-DexDump
  if (-not $dexdump) { return "dexdump.exe non trovato accanto ad aapt2 (build-tools): non posso provare i cancelli D30 nel dex di release" }
  $gate = Get-D30GateFacts $dexdump $apk.FullName
  if ($gate.Lines -lt 1000 -or -not $gate.SawMainActivity) { return "lettura del bytecode non affidabile ($($gate.Lines) righe, MainActivity vista: $($gate.SawMainActivity))" }
  if (-not $gate.WriteGateFound) { $problems += "AppAlbumWriteGate non e' nel dex di release: il cancello delle scritture degli album e' stato tolto o fuso" }
  elseif (-not $gate.BlockForFound) { $problems += "AppAlbumWriteGate.blockFor non e' nel dex di release" }
  elseif (-not $gate.BlockForCallsDecision) { $problems += "AppAlbumWriteGate.blockFor non chiama FeatureFlags.sharedAlbumsMayUse: la recinzione D30 non governa le scritture degli album" }
  elseif (-not $gate.BlockForReturnsTestSharesOnly) { $problems += "AppAlbumWriteGate.blockFor non risponde AlbumWriteBlock.TEST_SHARES_ONLY dopo la decisione D30" }
  if (-not $gate.ReadGateFound) { $problems += "FlagsAlbumReadGate non e' nel dex di release: il cancello della lettura degli album e' stato tolto o fuso" }
  elseif (-not $gate.MayReadFound) { $problems += "FlagsAlbumReadGate.mayRead non e' nel dex di release" }
  elseif (-not $gate.MayReadCallsDecision) { $problems += "FlagsAlbumReadGate.mayRead non chiama FeatureFlags.sharedAlbumsMayUse: la recinzione D30 non governa la lettura degli album" }
  $origins = [DexAlbumGateReader]::ResidualMethods($mapping, [string[]]@(
      "dev.pampa.mycloudgallery.albums.*", "dev.pampa.mycloudgallery.di.*",
      "dev.pampa.mycloudgallery.core.albums.shared.NasAlbumPublisher", "dev.pampa.mycloudgallery.core.albums.share.Share*",
      "dev.pampa.mycloudgallery.core.albums.shared.NasSharedAlbumSync"))
  $wiring = { param($list) @($list | Where-Object {
        $_ -like "Ldev/pampa/mycloudgallery/albums/*" -or $_ -like "Ldev/pampa/mycloudgallery/di/*" -or
        $origins["dev.pampa.mycloudgallery.albums.*"].Contains($_) -or $origins["dev.pampa.mycloudgallery.di.*"].Contains($_) }) }
  if ($gate.WriteGateInstantiators.Count -eq 0) { $problems += "nessun codice istanzia AppAlbumWriteGate: il cancello delle scritture degli album non e' collegato" }
  elseif ((& $wiring $gate.WriteGateInstantiators).Count -eq 0) {
    $problems += ("AppAlbumWriteGate e' istanziato solo da " + (($gate.WriteGateInstantiators | Select-Object -Unique) -join ", ") + ", non dal montaggio degli album")
  }
  if ($gate.ReadGateInstantiators.Count -eq 0) { $problems += "nessun codice istanzia FlagsAlbumReadGate: il cancello della lettura degli album non e' collegato" }
  elseif ((& $wiring $gate.ReadGateInstantiators).Count -eq 0) {
    $problems += ("FlagsAlbumReadGate e' istanziato solo da " + (($gate.ReadGateInstantiators | Select-Object -Unique) -join ", ") + ", non dal montaggio degli album")
  }
  foreach ($user in @(
      @("Ldev/pampa/mycloudgallery/core/albums/shared/NasAlbumPublisher;", "dev.pampa.mycloudgallery.core.albums.shared.NasAlbumPublisher", $gate.WriteGateUsers, "il pubblicatore non chiede il cancello delle scritture degli album"),
      @("Ldev/pampa/mycloudgallery/core/albums/share/Share", "dev.pampa.mycloudgallery.core.albums.share.Share*", $gate.WriteGateUsers, "il piano della condivisione non chiede il cancello delle scritture degli album"),
      @("Ldev/pampa/mycloudgallery/core/albums/shared/NasSharedAlbumSync;", "dev.pampa.mycloudgallery.core.albums.shared.NasSharedAlbumSync", $gate.ReadGateUsers, "la replica non chiede il cancello della lettura degli album"))) {
    # Il prefisso di una classe (che finisce con ';') vuole quella classe; quello di share/ (`.../share/Share`) accetta il piano o l'orchestratore,
    # se R8 vi inlinea il piano. Se R8 ha fuso la classe in un'altra, vale un metodo del dex che secondo mapping.txt contiene il suo codice.
    $prefix = if ($user[0].EndsWith(";")) { $user[0] + "." } else { $user[0] }
    $merged = $origins[$user[1]]
    if (-not (@($user[2]) | Where-Object { $_.StartsWith($prefix) -or $merged.Contains($_) })) { $problems += $user[3] }
  }
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
  Write-Host ("        cancelli D30 provati nel bytecode: AppAlbumWriteGate.blockFor -> sharedAlbumsMayUse -> TEST_SHARES_ONLY e FlagsAlbumReadGate.mayRead -> sharedAlbumsMayUse, istanziati da {0}; decisione chiamata da {1} metodi ({2} righe di dexdump)" -f
    ((@($gate.WriteGateInstantiators) + @($gate.ReadGateInstantiators) | Select-Object -Unique) -join ", "), @($gate.DecisionCallers | Select-Object -Unique).Count, $gate.Lines) -ForegroundColor DarkGray
}

# Il bytecode del cancello D34 (Fase 8, P13): come per D30, `dexdump -d` dei classes*.dex dell'APK letto in streaming da un piccolo lettore C#.
# Cerca: il cancello delle scritture dei volti (`AppFacesWriteGate.blockFor`) che chiama la decisione `FeatureFlags.facesSyncMayUse` e poi risponde
# `FacesWriteBlock.TEST_SHARES_ONLY`; chi lo istanzia (il montaggio delle persone di :app); chi lo chiama (replica, pubblicatore, prenotazioni e
# cancellazione sul NAS, per interfaccia o sulla classe se R8 la rende diretta). Senza dexdump o senza vedere la MainActivity la lettura non e'
# affidabile e il passo FALLISCE (non salta).
$script:dexFacesGateReaderLoaded = $false
function Initialize-DexFacesGateReader {
  if ($script:dexFacesGateReaderLoaded) { return }
  Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;

public class DexFacesGateFacts {
  public bool SawMainActivity;
  public bool WriteGateFound;
  public bool BlockForFound;
  public bool BlockForCallsDecision;
  public bool BlockForReturnsTestSharesOnly;
  public List<string> Instantiators = new List<string>();
  public List<string> DecisionCallers = new List<string>();
  public List<string> Users = new List<string>();
  public int Lines;
}

public static class DexFacesGateReader {
  const string WriteGate = "Ldev/pampa/mycloudgallery/faces/AppFacesWriteGate;";
  const string MainClass = "Ldev/pampa/mycloudgallery/MainActivity;";
  const string Decision = "Ldev/pampa/mycloudgallery/core/common/featureflags/FeatureFlags;.facesSyncMayUse:(Ljava/lang/String;)Z";
  const string TestSharesOnly = "Ldev/pampa/mycloudgallery/core/faces/contract/FacesWriteBlock;.TEST_SHARES_ONLY:";
  const string Port = "Ldev/pampa/mycloudgallery/core/faces/contract/FacesWriteGate;.blockFor:";
  const string Direct = "Ldev/pampa/mycloudgallery/faces/AppFacesWriteGate;.blockFor:";

  static readonly Regex ClassLine = new Regex(@"^\s*Class descriptor\s*:\s*'([^']+)'", RegexOptions.Compiled);
  static readonly Regex NameLine = new Regex(@"^\s*name\s*:\s*'([^']*)'", RegexOptions.Compiled);
  static readonly Regex InsnLine = new Regex(@"\|[0-9a-fA-F]{4}:\s+(\S+)\s*(.*)$", RegexOptions.Compiled);

  public static void Scan(string dexdump, string dexPath, DexFacesGateFacts facts) {
    var info = new ProcessStartInfo(dexdump, "-d \"" + dexPath + "\"") {
      UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
      StandardOutputEncoding = Encoding.GetEncoding(28591),
    };
    using (var process = Process.Start(info)) {
      string cls = "", method = "";
      bool sawDecisionInBlockFor = false;
      var errors = process.StandardError.ReadToEndAsync();
      string line;
      while ((line = process.StandardOutput.ReadLine()) != null) {
        facts.Lines++;
        var m = ClassLine.Match(line);
        if (m.Success) {
          cls = m.Groups[1].Value; method = ""; sawDecisionInBlockFor = false;
          if (cls == MainClass) facts.SawMainActivity = true;
          if (cls == WriteGate) facts.WriteGateFound = true;
          continue;
        }
        m = NameLine.Match(line);
        if (m.Success) {
          method = m.Groups[1].Value; sawDecisionInBlockFor = false;
          if (cls == WriteGate && method == "blockFor") facts.BlockForFound = true;
          continue;
        }
        var insn = InsnLine.Match(line);
        if (!insn.Success) continue;
        string op = insn.Groups[1].Value, rest = insn.Groups[2].Value;
        string here = cls + "." + method;
        if (op.StartsWith("invoke") && rest.Contains(Decision)) {
          facts.DecisionCallers.Add(here);
          if (cls == WriteGate && method == "blockFor") { facts.BlockForCallsDecision = true; sawDecisionInBlockFor = true; }
        } else if (op.StartsWith("invoke") && (rest.Contains(Port) || rest.Contains(Direct))) {
          facts.Users.Add(here);
        } else if (op == "new-instance" && rest.Contains(WriteGate)) {
          facts.Instantiators.Add(here);
        } else if (op.StartsWith("sget") && rest.Contains(TestSharesOnly) && cls == WriteGate && method == "blockFor") {
          // D34: la risposta TEST_SHARES_ONLY dopo la decisione per share.
          if (sawDecisionInBlockFor) facts.BlockForReturnsTestSharesOnly = true;
        }
      }
      process.WaitForExit();
      if (process.ExitCode != 0) throw new InvalidOperationException("dexdump ha fallito: " + errors.Result);
    }
  }
}
'@
  $script:dexFacesGateReaderLoaded = $true
}

# Estrae i classes*.dex dell'APK in una cartella temporanea e li legge uno a uno con il lettore del cancello D34.
function Get-D34GateFacts([string]$dexdump, [string]$apk) {
  Initialize-DexFacesGateReader
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("mcg-dex34-" + [guid]::NewGuid().ToString("N"))
  New-Item -ItemType Directory -Path $temp | Out-Null
  $facts = New-Object DexFacesGateFacts
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
  try {
    $dexes = @($zip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' })
    if ($dexes.Count -eq 0) { throw "nessun classes*.dex nell'APK" }
    foreach ($entry in $dexes) {
      $target = Join-Path $temp $entry.FullName
      [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
      [DexFacesGateReader]::Scan($dexdump, $target, $facts)
    }
  } finally {
    $zip.Dispose()
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
  }
  return $facts
}

# Fase 8 (P0, D34; risposta Q5 (a) del 27/10): "dati dei volti sul NAS solo sulle share di prova" e' una protezione che deve esserci NELLA
# RELEASE (come D12 e D30), accesa e non spegnibile (FeatureFlagsTest) fino alla fine della Fase 8. Qui si controlla che R8 non l'abbia
# tolta: il campo e la decisione per share di `FeatureFlags`, tenuti da core-common/consumer-rules.pro anche prima che P13 leghi il
# cancello dei volti (`FacesWriteGate`), devono stare nel dex dell'APK di release e in mapping.txt. E (P13) il CANCELLO che li usa, nel bytecode (`AppFacesWriteGate`,
# vedi sotto). Senza i file il passo FALLISCE (non salta).
$d34Members = @("getFacesSyncTestSharesOnly", "facesSyncMayUse")
Step "D34 nella release: FeatureFlags.facesSyncTestSharesOnly e facesSyncMayUse nel dex e in mapping.txt" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $mapping = Join-Path $root "app\build\outputs\mapping\release\mapping.txt"
  if (-not (Test-Path $mapping)) { return "manca mapping.txt in app\build\outputs\mapping\release" }
  $problems = @()
  $found = @(Get-DexClassesMatching $apk.FullName (($d34Members | ForEach-Object { [regex]::Escape($_) }) -join "|"))
  foreach ($member in $d34Members) {
    if ($found -notcontains $member) { $problems += "$member non e' nel dex di release: la protezione D34 e' stata tolta" }
  }
  foreach ($member in @("boolean facesSyncMayUse(java.lang.String)", "boolean getFacesSyncTestSharesOnly()")) {
    if (-not (Select-String -Path $mapping -SimpleMatch -Pattern $member -Quiet)) { $problems += "$member non e' in mapping.txt" }
  }
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
  Write-Host "        D34 presente nella release (dex e mapping.txt)" -ForegroundColor DarkGray

  # P13: il campo e la decisione non bastano (la regola di consumer-rules.pro li tiene anche se nessuno li chiama), serve il CANCELLO che li usa.
  # Nel bytecode della release: `AppFacesWriteGate.blockFor` chiama `FeatureFlags.facesSyncMayUse` e poi risponde `FacesWriteBlock.TEST_SHARES_ONLY`;
  # lo istanzia il montaggio delle persone di :app (`faces/` o `di/`); lo chiamano la replica, il pubblicatore e le prenotazioni (`core/faces/sync/`)
  # o la cancellazione sul NAS (la funzione `asEraseGate` di :app). La regola di app\proguard-rules.pro tiene il metodo (niente fusioni ne' inline).
  $dexdump = Get-DexDump
  if (-not $dexdump) { return "dexdump.exe non trovato accanto ad aapt2 (build-tools): non posso provare il cancello D34 nel dex di release" }
  $gate = Get-D34GateFacts $dexdump $apk.FullName
  if ($gate.Lines -lt 1000 -or -not $gate.SawMainActivity) { return "lettura del bytecode non affidabile ($($gate.Lines) righe, MainActivity vista: $($gate.SawMainActivity))" }
  if (-not $gate.WriteGateFound) { $problems += "AppFacesWriteGate non e' nel dex di release: il cancello delle scritture dei volti e' stato tolto o fuso" }
  elseif (-not $gate.BlockForFound) { $problems += "AppFacesWriteGate.blockFor non e' nel dex di release" }
  elseif (-not $gate.BlockForCallsDecision) { $problems += "AppFacesWriteGate.blockFor non chiama FeatureFlags.facesSyncMayUse: la recinzione D34 non governa le scritture dei volti" }
  elseif (-not $gate.BlockForReturnsTestSharesOnly) { $problems += "AppFacesWriteGate.blockFor non risponde FacesWriteBlock.TEST_SHARES_ONLY dopo la decisione D34" }
  Initialize-DexAlbumGateReader
  $origins = [DexAlbumGateReader]::ResidualMethods($mapping, [string[]]@(
      "dev.pampa.mycloudgallery.faces.*", "dev.pampa.mycloudgallery.di.*", "dev.pampa.mycloudgallery.core.faces.sync.*"))
  $wiring = @($gate.Instantiators | Where-Object {
      $_ -like "Ldev/pampa/mycloudgallery/faces/*" -or $_ -like "Ldev/pampa/mycloudgallery/di/*" -or
      $origins["dev.pampa.mycloudgallery.faces.*"].Contains($_) -or $origins["dev.pampa.mycloudgallery.di.*"].Contains($_) })
  if ($gate.Instantiators.Count -eq 0) { $problems += "nessun codice istanzia AppFacesWriteGate: il cancello delle scritture dei volti non e' collegato" }
  elseif ($wiring.Count -eq 0) {
    $problems += ("AppFacesWriteGate e' istanziato solo da " + (($gate.Instantiators | Select-Object -Unique) -join ", ") + ", non dal montaggio delle persone")
  }
  $callers = @($gate.Users | Where-Object { $_ -like "Ldev/pampa/mycloudgallery/core/faces/sync/*" -or $origins["dev.pampa.mycloudgallery.core.faces.sync.*"].Contains($_) })
  if ($callers.Count -eq 0) { $problems += "replica, pubblicatore e prenotazioni dei volti non chiedono il cancello delle scritture (nessuna chiamata da core/faces/sync/)" }
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
  Write-Host ("        cancello D34 provato nel bytecode: AppFacesWriteGate.blockFor -> facesSyncMayUse -> TEST_SHARES_ONLY, istanziato da {0}; chiamato da {1} metodi di core/faces/sync; decisione chiamata da {2} metodi ({3} righe di dexdump)" -f
    (($gate.Instantiators | Select-Object -Unique) -join ", "), @($callers | Select-Object -Unique).Count, @($gate.DecisionCallers | Select-Object -Unique).Count, $gate.Lines) -ForegroundColor DarkGray
}

# --- Fase 10 (P10): le quattro recinzioni in UN passo (docs/fase10-progetto.md par. 2.10, D35, Q6 (b), C446) ------------------------------
# Lo stato atteso di ogni recinzione (F4-D31, F5-D12, F7-D30, F8-D34) e il consenso unico del passo stanno in tools\release\recinzioni.json;
# le funzioni in tools\release\recinzioni.ps1. Oggi sono tutte accese e il consenso e' vuoto: i passi sono verdi come prima. Il giorno del passo
# l'orchestratore cambia FeatureFlags e recinzioni.json insieme, con il si' dell'utente, e il controllo accetta solo "spenta" dichiarata con la riga
# del consenso in docs\decisioni.md. Lo stato misto passa solo con un ritorno indietro parziale dichiarato. Il bytecode (R8 ripiega le costanti di
# FeatureFlags nei getter, nelle decisioni e nel toString) e' letto dal dex della release: se non si legge, il passo FALLISCE.
. (Join-Path $root "tools\release\recinzioni.ps1")
$recinzioniJson = Join-Path $root "tools\release\recinzioni.json"
$recinzioniFlags = Join-Path $root "core-common\src\main\kotlin\dev\pampa\mycloudgallery\core\common\featureflags\FeatureFlags.kt"
$recinzioniDecisioni = Join-Path $root "docs\decisioni.md"

Step "recinzioni (P10, D35): lo stato dichiarato in recinzioni.json e' quello di FeatureFlags.kt; consenso unico e ritorno parziale in regola" {
  $problems = @(Invoke-RecinzioniCheck $recinzioniJson $recinzioniFlags $recinzioniDecisioni $null (Get-Date))
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
  $config = Read-RecinzioniJson $recinzioniJson
  Write-Host ("        " + (($config.recinzioni | ForEach-Object { "$($_.id) $($_.stato)" }) -join ", ") + "; consenso: " + $(if ($config.consenso.data) { $config.consenso.data } else { "nessuno" })) -ForegroundColor DarkGray
}

Step "recinzioni nel bytecode della release (P10, C446): lo stato di ognuna letto dal dex (getter, decisione, toString) e' quello dichiarato" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $dexdump = Get-DexDump
  if (-not $dexdump) { return "dexdump.exe non trovato accanto ad aapt2 (build-tools): non posso leggere lo stato delle recinzioni nel dex di release" }
  $classLines = Get-FlagsClassLines $dexdump $apk.FullName
  if (-not $classLines -or $classLines.Count -lt 20) { return "la classe FeatureFlags non e' nel dex di release (lettura del bytecode non affidabile)" }
  $dexStates = Get-FenceDexStates (Get-FlagsDexFacts $classLines)
  $problems = @(Invoke-RecinzioniCheck $recinzioniJson $recinzioniFlags $recinzioniDecisioni $dexStates (Get-Date))
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
  foreach ($id in $script:FenceIds) { Write-Host ("        {0} {1} nel bytecode ({2} prove: getter, decisione, toString)" -f $id, $dexStates[$id].Stato, $dexStates[$id].Prove) -ForegroundColor DarkGray }
}

Step "recinzioni e fotografia delle share (P10): prove su copie temporanee e su cartelle finte sotto build\ (mai sulle share del NAS)" {
  foreach ($proof in @("recinzioni-prove.ps1", "share-snapshot-prove.ps1")) {
    $r = Invoke-Native "powershell.exe" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $root ("tools\release\" + $proof)))
    if ($r.Code -ne 0) { Show-Lines $r.Lines 40; return "$proof fallito (codice $($r.Code))" }
    Write-Host ("        " + ($r.Lines | Select-Object -Last 1)) -ForegroundColor DarkGray
  }
}

# --- Fase 6 (P0): i motori on-device nell'APK di release (docs/fase6-progetto.md D3, D33, D38, D41, L47, L49) -------------------------
# Tre letture dell'APK vero: (1) le librerie native (ABI, compressione, allineamento a 16 KB nel file e nei segmenti LOAD dell'ELF:
# Android 15+ su pagine da 16 KB); (2) il manifest (aapt2): niente MlKitInitProvider, i componenti delle librerie di Google
# esattamente l'elenco chiuso di releaseManifestPolicy e non esportati, solo le uses-native-library ammesse e facoltative; (3) il
# bytecode (dexdump): chi chiama MlKit.initialize e chi usa i client HTTPS, fra le classi nostre. Ogni lettura che non riesce FALLISCE.

# Un elenco di stringhe fra apici di `name : [ ... ]` in gradle\verify-release-manifest.gradle (stessa fonte di verifyReleaseManifest).
function Get-PolicyList([string]$name) {
  $gradle = Read-TextFile (Join-Path $root "gradle\verify-release-manifest.gradle")
  $list = [regex]::Match($gradle, "(?s)$name\s*:\s*\[(.*?)\n\s*\],")
  if (-not $list.Success) { throw "non trovo $name in gradle\verify-release-manifest.gradle" }
  $values = @()
  foreach ($line in ($list.Groups[1].Value -split '\r?\n')) {
    $code = ($line -replace '//.*$', '').Trim()
    foreach ($m in [regex]::Matches($code, "'([^']+)'")) { $values += $m.Groups[1].Value }
  }
  if ($values.Count -eq 0) { throw "$name e' vuoto in gradle\verify-release-manifest.gradle" }
  return $values
}

# Fase 9 (P1): vero se `:app` dipende da `:feature-map` (lo collega P13). Da li' in poi MapLibre e il client delle tessere sono nell'APK e i
# passi 7e e 7h pretendono di vederli (controllo positivo). Si legge il codice di app\build.gradle senza i commenti `//`.
function Test-AppUsesFeatureMap {
  $gradle = Read-TextFile (Join-Path $root "app\build.gradle")
  foreach ($line in ($gradle -split '\r?\n')) {
    $code = ($line -replace '//.*$', '')
    # Fase 9 (orchestratore, 30/10): le configurazioni dei soli test (testImplementation, androidTestImplementation, ...) non finiscono nell'APK;
    # la forma con path: conta come quella corta, cosi' una dipendenza di produzione scritta in quel modo non sfugge al controllo.
    if ($code -match '^\s*(test|androidTest)\w*\s*[\(\s]') { continue }
    if ($code -match "project\(\s*(path\s*:\s*)?['""]:feature-map['""]") { return $true }
  }
  return $false
}

$script:apkInspectLoaded = $false
function Initialize-ApkInspect {
  if ($script:apkInspectLoaded) { return }
  Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;

public class NativeLibFact {
  public string Name;
  public int Method;
  public long DataOffset;
  public long Size;
  public List<long> LoadAligns = new List<long>();
  public string Problem;
}

public static class ApkInspect {
  // Le voci lib/<abi>/*.so dell'APK dalla directory centrale dello zip: metodo di compressione, posizione dei dati nel file e
  // allineamento dei segmenti LOAD dell'ELF (solo per le voci non compresse, che il sistema mappa cosi' come sono).
  public static List<NativeLibFact> NativeLibs(string apk) {
    var result = new List<NativeLibFact>();
    byte[] data = File.ReadAllBytes(apk);
    int eocd = -1;
    for (int i = data.Length - 22; i >= Math.Max(0, data.Length - 65557); i--) {
      if (BitConverter.ToUInt32(data, i) == 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new InvalidOperationException("fine della directory centrale non trovata");
    int count = BitConverter.ToUInt16(data, eocd + 10);
    long cd = BitConverter.ToUInt32(data, eocd + 16);
    long p = cd;
    for (int e = 0; e < count; e++) {
      if (BitConverter.ToUInt32(data, (int)p) != 0x02014b50) throw new InvalidOperationException("directory centrale non valida");
      int method = BitConverter.ToUInt16(data, (int)p + 10);
      long csize = BitConverter.ToUInt32(data, (int)p + 20);
      long usize = BitConverter.ToUInt32(data, (int)p + 24);
      int nameLen = BitConverter.ToUInt16(data, (int)p + 28);
      int extraLen = BitConverter.ToUInt16(data, (int)p + 30);
      int commentLen = BitConverter.ToUInt16(data, (int)p + 32);
      long local = BitConverter.ToUInt32(data, (int)p + 42);
      string name = Encoding.UTF8.GetString(data, (int)p + 46, nameLen);
      p += 46 + nameLen + extraLen + commentLen;
      if (!name.StartsWith("lib/") || !name.EndsWith(".so")) continue;
      var fact = new NativeLibFact { Name = name, Method = method, Size = usize };
      int localName = BitConverter.ToUInt16(data, (int)local + 26);
      int localExtra = BitConverter.ToUInt16(data, (int)local + 28);
      fact.DataOffset = local + 30 + localName + localExtra;
      if (method == 0) {
        long b = fact.DataOffset;
        if (data[b] != 0x7f || data[b + 1] != (byte)'E' || data[b + 2] != (byte)'L' || data[b + 3] != (byte)'F' || data[b + 4] != 2) {
          fact.Problem = "non e' un ELF a 64 bit";
        } else {
          long phoff = BitConverter.ToInt64(data, (int)(b + 0x20));
          int phentsize = BitConverter.ToUInt16(data, (int)(b + 0x36));
          int phnum = BitConverter.ToUInt16(data, (int)(b + 0x38));
          for (int h = 0; h < phnum; h++) {
            long ph = b + phoff + (long)h * phentsize;
            if (BitConverter.ToUInt32(data, (int)ph) == 1) fact.LoadAligns.Add(BitConverter.ToInt64(data, (int)(ph + 0x30)));
          }
        }
      }
      result.Add(fact);
    }
    return result;
  }
}

public class DexCallFacts {
  public bool SawMainActivity;
  public int Lines;
  // "bersaglio|classe.metodo" del chiamante, per ogni invocazione di un bersaglio.
  public List<string> Calls = new List<string>();
  // I2 (revisione di Fase 6): i descrittori delle classi che cominciano con uno dei [ClassPrefixes] (es. i descrittori `dynamite` dei moduli
  // consegnati da Google Play services): chi li guarda sa quali moduli di Play services sono nell'APK.
  public string[] ClassPrefixes = new string[0];
  public List<string> ClassHits = new List<string>();
}

public static class DexCallScanner {
  static readonly Regex ClassLine = new Regex(@"^\s*Class descriptor\s*:\s*'([^']+)'", RegexOptions.Compiled);
  static readonly Regex NameLine = new Regex(@"^\s*name\s*:\s*'([^']*)'", RegexOptions.Compiled);
  static readonly Regex InsnLine = new Regex(@"\|[0-9a-fA-F]{4}:\s+(\S+)\s*(.*)$", RegexOptions.Compiled);

  public static void Scan(string dexdump, string dexPath, string[] targets, DexCallFacts facts) {
    var info = new ProcessStartInfo(dexdump, "-d \"" + dexPath + "\"") {
      UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
      StandardOutputEncoding = Encoding.GetEncoding(28591),
    };
    using (var process = Process.Start(info)) {
      string cls = "", method = "";
      var errors = process.StandardError.ReadToEndAsync();
      string line;
      while ((line = process.StandardOutput.ReadLine()) != null) {
        facts.Lines++;
        var m = ClassLine.Match(line);
        if (m.Success) {
          cls = m.Groups[1].Value; method = "";
          if (cls == "Ldev/pampa/mycloudgallery/MainActivity;") facts.SawMainActivity = true;
          foreach (var prefix in facts.ClassPrefixes) { if (cls.StartsWith(prefix)) facts.ClassHits.Add(cls); }
          continue;
        }
        m = NameLine.Match(line);
        if (m.Success) { method = m.Groups[1].Value; continue; }
        var insn = InsnLine.Match(line);
        if (!insn.Success || !insn.Groups[1].Value.StartsWith("invoke")) continue;
        string rest = insn.Groups[2].Value;
        foreach (var target in targets) {
          if (rest.Contains(target)) facts.Calls.Add(target + "|" + cls + "." + method);
        }
      }
      process.WaitForExit();
      if (process.ExitCode != 0) throw new InvalidOperationException("dexdump ha fallito: " + errors.Result);
    }
  }
}
'@
  $script:apkInspectLoaded = $true
}

# Le uses-native-library e i componenti (nome, esportato) del manifest DENTRO l'APK.
function Get-ApkPhase6ManifestFacts([string]$aapt2, [string]$apk) {
  $dump = Invoke-Native -File $aapt2 -Arguments @("dump", "xmltree", "--file", "AndroidManifest.xml", $apk)
  if ($dump.Code -ne 0) { throw "aapt2 dump xmltree fallito: " + ($dump.Lines -join " | ") }
  $facts = [ordered]@{ NativeLibs = @(); Components = @(); MetaData = @() }
  $current = $null
  foreach ($line in $dump.Lines) {
    if ($line -match '^\s*E: ([\w\-]+) ') {
      $element = $Matches[1]
      if ($element -eq "uses-native-library") {
        $current = @{ Kind = $element; Name = ""; Required = $true }
        $facts.NativeLibs += , $current
      } elseif (@("activity", "activity-alias", "service", "receiver", "provider") -contains $element) {
        $current = @{ Kind = $element; Name = ""; Exported = $false }
        $facts.Components += , $current
      } elseif ($element -eq "meta-data") {
        # I2: il nome dei meta-data (i registri di ML Kit, l'eventuale `com.google.mlkit.vision.DEPENDENCIES` dei Play services).
        $current = @{ Kind = $element; Name = "" }
        $facts.MetaData += , $current
      } else {
        $current = $null
      }
      continue
    }
    if ($current -and $line -match '^\s*A: .*android:([A-Za-z]+)\(0x[0-9a-fA-F]+\)=(.*)$') {
      $attr = $Matches[1]
      $value = $Matches[2].Trim()
      if ($attr -eq "name" -and $value -match '^"([^"]*)"') { $current.Name = $Matches[1] }
      if ($attr -eq "required") { $current.Required = ($value -match '^true' -or $value -match '0xffffffff') }
      if ($attr -eq "exported") { $current.Exported = ($value -match '^true' -or $value -match '0xffffffff') }
    }
  }
  return $facts
}

Step "motori on-device nella release: librerie native (ABI, 16 KB), manifest (ML Kit, elenco chiuso, uses-native-library), bytecode (MlKit.initialize, HTTPS)" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $aapt2 = Get-Aapt2
  if (-not $aapt2) { return "aapt2 non trovato: controlla sdk.dir in local.properties e le build-tools dell'SDK" }
  Initialize-ApkInspect
  $problems = @()

  # (1) Le librerie native.
  $libs = @([ApkInspect]::NativeLibs($apk.FullName))
  if ($libs.Count -eq 0) { return "nessuna libreria nativa nell'APK di release: la lettura non e' affidabile (LiteRT-LM, LiteRT e ML Kit ne portano)" }
  $expectedLibs = @("liblitertlm_jni.so", "libLiteRt.so", "liblitert_jni.so", "libmlkit_google_ocr_pipeline.so")
  # Fase 9 (P1, D21): con `:feature-map` nell'app (P13) c'e' anche MapLibre, e i 16 KB qui sotto valgono anche per lui.
  if (Test-AppUsesFeatureMap) { $expectedLibs += "libmaplibre.so" }
  foreach ($expected in $expectedLibs) {
    # P11 (modifica a D3): la release porta solo arm64-v8a; x86_64 resta nella debug per gli emulatori.
    if (-not ($libs | Where-Object { $_.Name -eq "lib/arm64-v8a/$expected" })) { $problems += "manca lib/arm64-v8a/$expected" }
  }
  foreach ($lib in $libs) {
    $abi = ($lib.Name -split '/')[1]
    if ($abi -ne "arm64-v8a") { $problems += "$($lib.Name): ABI non ammessa nella release (solo arm64-v8a, D3 modificata da P11)" ; continue }
    if ($lib.Method -ne 0) { $problems += "$($lib.Name) e' compressa nell'APK (va mappata cosi' com'e')"; continue }
    if ($lib.DataOffset % 16384 -ne 0) { $problems += "$($lib.Name) non e' allineata a 16 KB nel file (scostamento $($lib.DataOffset))" }
    if ($lib.Problem) { $problems += "$($lib.Name): $($lib.Problem)"; continue }
    if (@($lib.LoadAligns).Count -eq 0) { $problems += "$($lib.Name): nessun segmento LOAD letto" }
    foreach ($align in $lib.LoadAligns) {
      if ($align -lt 16384) { $problems += "$($lib.Name): segmento LOAD allineato a $align byte (servono 16 KB)" }
    }
  }
  Write-Host ("        librerie native: {0}, tutte non compresse e allineate a 16 KB" -f $libs.Count) -ForegroundColor DarkGray

  # (2) Il manifest dell'APK.
  $manifest = Get-ApkPhase6ManifestFacts $aapt2 $apk.FullName
  $allowedNative = @(Get-PolicyList "allowedNativeLibraries")
  $nativeNames = @($manifest.NativeLibs | ForEach-Object { $_.Name })
  foreach ($native in $manifest.NativeLibs) {
    if ($allowedNative -notcontains $native.Name) { $problems += "uses-native-library non ammessa nell'APK: $($native.Name)" }
    elseif ($native.Required) { $problems += "uses-native-library $($native.Name) non facoltativa" }
  }
  if (@($nativeNames | Select-Object -Unique).Count -ne $nativeNames.Count) { $problems += "uses-native-library ripetute: $($nativeNames -join ', ')" }
  if (@($manifest.Components | Where-Object { $_.Name -eq "com.google.mlkit.common.internal.MlKitInitProvider" }).Count -gt 0) {
    $problems += "MlKitInitProvider e' nel manifest dell'APK: ML Kit partirebbe da solo all'avvio (D41)"
  }
  $prefixes = @(Get-PolicyList "thirdPartyComponentPrefixes")
  $closed = @(Get-PolicyList "allowedThirdPartyComponents")
  $thirdParty = @($manifest.Components | Where-Object { $name = $_.Name; @($prefixes | Where-Object { $name.StartsWith($_) }).Count -gt 0 })
  foreach ($component in $thirdParty) {
    if ($closed -notcontains $component.Name) { $problems += "componente di una libreria di Google fuori dall'elenco chiuso: <$($component.Kind)> $($component.Name)" }
    if ($component.Exported) { $problems += "componente di una libreria di Google esportato: $($component.Name)" }
  }
  foreach ($name in $closed) {
    if (-not ($thirdParty | Where-Object { $_.Name -eq $name })) { $problems += "il componente dell'elenco chiuso $name non e' piu' nell'APK: aggiornare releaseManifestPolicy e docs/sicurezza.md 4-bis" }
  }
  Write-Host ("        manifest: uses-native-library {0}; componenti delle librerie di Google {1} (elenco chiuso)" -f ($nativeNames -join ", "), $thirdParty.Count) -ForegroundColor DarkGray

  # (2b) I2 (revisione di Fase 6): la variante "Play services" di ML Kit non entra nella release. `com.google.android.gms:play-services-mlkit-text-
  # recognition` (e `-common`) sono dipendenze obbligatorie della versione integrata (`com.google.mlkit:text-recognition`), quindi i loro file
  # sono nell'APK (due descrittori `dynamite` e poco altro); quello che farebbe lavorare ML Kit tramite Google Play services non ci deve essere:
  #  - nessun meta-data `com.google.mlkit.*.DEPENDENCIES` (chiede a Play services di scaricare il modello OCR all'installazione);
  #  - i registri dei componenti di ML Kit sono i tre noti (testo, visione comune, comune): un quarto e' un altro modulo ML Kit;
  #  - i descrittori `dynamite` (i moduli che Play services consegna) sono solo i due del testo;
  #  - il modello OCR integrato e' nell'APK (`assets/mlkit-google-ocr-models/`, `libmlkit_google_ocr_pipeline.so` sopra): e' lui che legge;
  #  - `gradle/verification-metadata.xml` elenca per ML Kit e Play services di ML Kit solo gli artefatti noti.
  $allowedMlKitRegistrars = @(
    "com.google.mlkit.vision.text.internal.TextRegistrar",
    "com.google.mlkit.vision.common.internal.VisionCommonRegistrar",
    "com.google.mlkit.common.internal.CommonComponentRegistrar"
  )
  if (@($manifest.MetaData).Count -lt 1) { $problems += "nessun meta-data letto dal manifest dell'APK: la lettura non e' affidabile (I2)" }
  foreach ($meta in $manifest.MetaData) {
    if ($meta.Name -like "com.google.mlkit.*DEPENDENCIES") {
      $problems += "meta-data $($meta.Name) nel manifest dell'APK: Play services scaricherebbe i modelli di ML Kit al posto del modello integrato (I2)"
    }
    $registryPrefix = "com.google.firebase.components:com.google.mlkit."
    if ($meta.Name.StartsWith($registryPrefix)) {
      $registrar = $meta.Name.Substring("com.google.firebase.components:".Length)
      if ($allowedMlKitRegistrars -notcontains $registrar) { $problems += "registro di un componente di ML Kit fuori dall'elenco: $registrar (I2: un altro modulo di ML Kit)" }
    }
  }
  $mlKitRegistrarsSeen = @($manifest.MetaData | Where-Object { $_.Name.StartsWith("com.google.firebase.components:com.google.mlkit.") })
  if ($mlKitRegistrarsSeen.Count -ne $allowedMlKitRegistrars.Count) {
    $problems += "registri di ML Kit nel manifest: $($mlKitRegistrarsSeen.Count), attesi $($allowedMlKitRegistrars.Count) (I2; aggiornare l'elenco e docs/sicurezza.md 4-bis)"
  }
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $apkZip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
  try {
    $ocrModels = @($apkZip.Entries | Where-Object { $_.FullName -like "assets/mlkit-google-ocr-models/*" -and -not $_.FullName.EndsWith("/") })
  } finally {
    $apkZip.Dispose()
  }
  if ($ocrModels.Count -lt 1) { $problems += "il modello OCR integrato di ML Kit (assets/mlkit-google-ocr-models/) non e' nell'APK: ML Kit lo chiederebbe a Play services (I2)" }
  $verificationFile = Join-Path $root "gradle\verification-metadata.xml"
  [xml]$verification = Get-Content -LiteralPath $verificationFile -Raw
  function Get-VerifiedNames([xml]$doc, [string]$group, [string]$prefix) {
    return @($doc.SelectNodes("//*[local-name()='component']") | Where-Object { $_.GetAttribute("group") -eq $group -and $_.GetAttribute("name").StartsWith($prefix) } |
      ForEach-Object { $_.GetAttribute("name") } | Sort-Object -Unique)
  }
  $knownPlay = @("play-services-mlkit-text-recognition", "play-services-mlkit-text-recognition-common")
  $knownMlKit = @("common", "text-recognition", "text-recognition-bundled-common", "vision-common", "vision-interfaces")
  $playNames = @(Get-VerifiedNames $verification "com.google.android.gms" "play-services-mlkit")
  $mlKitNames = @(Get-VerifiedNames $verification "com.google.mlkit" "")
  foreach ($name in $playNames) { if ($knownPlay -notcontains $name) { $problems += "verification-metadata: artefatto Play services di ML Kit nuovo: $name (I2: la variante Play services non entra nella release)" } }
  foreach ($name in $mlKitNames) { if ($knownMlKit -notcontains $name) { $problems += "verification-metadata: artefatto ML Kit nuovo: com.google.mlkit:$name (I2)" } }
  Write-Host ("        ML Kit (I2): {0} registri noti, {1} file del modello OCR integrato, nessun meta-data DEPENDENCIES; metadati: {2} artefatti Play services, {3} ML Kit" -f $mlKitRegistrarsSeen.Count, $ocrModels.Count, $playNames.Count, $mlKitNames.Count) -ForegroundColor DarkGray

  # (3) Il bytecode: MlKit.initialize e i client HTTPS chiamati da classi nostre.
  $dexdump = Get-DexDump
  if (-not $dexdump) { return "dexdump.exe non trovato accanto ad aapt2 (build-tools)" }
  $initialize = "Lcom/google/mlkit/common/MlKit;.initialize:"
  # I3 (revisione di Fase 6): prima i bersagli erano tre e i chiamanti solo `Ldev/pampa/`. Ora anche `URL.openStream`/`getContent`, ogni
  # `URLConnection`, `Network.openConnection` (la rete scelta dal sistema), `HttpEngine` (android.net.http), `DownloadManager`, `WebView`,
  # i socket TLS e gli stessi client in codice dell'engine (engine-foundation, engine-ui: `Ldev/antigravity/fluidengine/`).
  $https = @(
    "Ljavax/net/ssl/HttpsURLConnection;.", "Ljava/net/URL;.openConnection:", "Ljava/net/HttpURLConnection;.",
    "Ljava/net/URL;.openStream:", "Ljava/net/URL;.getContent:", "Ljava/net/URLConnection;.", "Landroid/net/Network;.openConnection:",
    "Landroid/net/http/HttpEngine", "Landroid/net/http/UrlRequest", "Landroid/app/DownloadManager;.", "Landroid/webkit/WebView;.",
    "Ljavax/net/ssl/SSLSocket", "Ljavax/net/ssl/SSLEngine;.", "Ljava/net/http/HttpClient", "Lokhttp3/OkHttpClient;."
  )
  $ownPrefixes = @("Ldev/pampa/", "Ldev/antigravity/fluidengine/")
  $facts = New-Object DexCallFacts
  $facts.ClassPrefixes = [string[]]@("Lcom/google/android/gms/dynamite/descriptors/")
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("mcg-dex6-" + [guid]::NewGuid().ToString("N"))
  New-Item -ItemType Directory -Path $temp | Out-Null
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
  try {
    foreach ($entry in @($zip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' })) {
      $target = Join-Path $temp $entry.FullName
      [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
      [DexCallScanner]::Scan($dexdump, $target, [string[]](@($initialize) + $https), $facts)
    }
  } finally {
    $zip.Dispose()
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
  }
  if ($facts.Lines -lt 1000 -or -not $facts.SawMainActivity) { return "lettura del bytecode non affidabile ($($facts.Lines) righe, MainActivity vista: $($facts.SawMainActivity))" }
  foreach ($call in $facts.Calls) {
    $parts = $call -split '\|', 2
    $caller = $parts[1]
    if (@($ownPrefixes | Where-Object { $caller.StartsWith($_) }).Count -eq 0) { continue }
    if ($parts[0] -eq $initialize) {
      if (-not ($caller.StartsWith("Ldev/pampa/mycloudgallery/core/ai/ocr/MlKitBootstrap;") -or $caller.StartsWith("Ldev/pampa/mycloudgallery/core/ai/ocr/MlKitBootstrap$"))) {
        $problems += "MlKit.initialize chiamato fuori dal cancello: $caller"
      }
    } elseif (-not ($caller.StartsWith("Ldev/pampa/mycloudgallery/core/models/download/https/") -or $caller.StartsWith("Ldev/pampa/mycloudgallery/feature/map/net/"))) {
      # Fase 9 (D23): la seconda eccezione dichiarata, il client delle tessere della mappa (`MapTileHttp`); le regole sue sono nel passo 7h.
      $problems += "client HTTPS fuori da core-models/.../download/https/ e feature/map/net/: $caller ($($parts[0]))"
    }
  }
  # P11: da quando `:app` monta `ModelFetchers` il client HTTPS dei modelli e' raggiunto e R8 lo tiene: il controllo deve vederlo (controllo
  # positivo: un passo che non vede niente non prova niente).
  $httpsOk = @($facts.Calls | Where-Object { ($_ -split '\|', 2)[1].StartsWith("Ldev/pampa/mycloudgallery/core/models/download/https/") })
  if ($httpsOk.Count -eq 0) { $problems += "nessuna chiamata HTTPS da core-models/.../download/https/ nel dex: ModelFetchers non e' montato da :app (o la lettura non e' affidabile)" }
  # I2: i moduli che Play services consegnerebbe (descrittori `dynamite`): solo i due del testo (insieme alle loro librerie obbligatorie).
  $knownDescriptors = @(
    "Lcom/google/android/gms/dynamite/descriptors/com/google/mlkit/dynamite/text/common/ModuleDescriptor;",
    "Lcom/google/android/gms/dynamite/descriptors/com/google/mlkit/dynamite/text/latin/ModuleDescriptor;"
  )
  foreach ($descriptor in @($facts.ClassHits | Sort-Object -Unique)) {
    if ($knownDescriptors -notcontains $descriptor) { $problems += "modulo di Play services fuori dall'elenco nel dex: $descriptor (I2)" }
  }
  $ourCalls = @($facts.Calls | Where-Object { $caller = ($_ -split '\|', 2)[1]; @($ownPrefixes | Where-Object { $caller.StartsWith($_) }).Count -gt 0 })
  Write-Host ("        bytecode: {0} chiamate nostre a MlKit.initialize o ai client HTTPS, tutte al loro posto ({1} righe di dexdump)" -f $ourCalls.Count, $facts.Lines) -ForegroundColor DarkGray
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
}

# Fase 8 (P0; docs/fase8-progetto.md D2, §1.1, §6.4): LiteRT resta in core-ai anche nel bytecode della release. I sorgenti li controllano
# Phase6ArchitectureTest e Phase8ArchitectureTest; qui si guarda cio' che si installa: ogni chiamata di codice nostro (`Ldev/pampa/`, e
# dell'engine) a una classe di LiteRT (`org.tensorflow.lite`, `com.google.ai.edge.litert`, non LiteRT-LM) parte da `core/ai/`, e fra le classi
# dei volti (`core/ai/faces/`) solo da `FaceLiteRtSessions`. Controllo positivo: almeno una chiamata da `core/ai/` (le sessioni del ritaglio,
# montate da :app): un passo che non vede niente non prova niente. Senza dexdump o senza vedere la MainActivity il passo FALLISCE.
Step "LiteRT solo in core-ai nel bytecode della release (volti solo da core/ai/faces/FaceLiteRtSessions)" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  Initialize-ApkInspect
  $dexdump = Get-DexDump
  if (-not $dexdump) { return "dexdump.exe non trovato accanto ad aapt2 (build-tools)" }
  $targets = [string[]]@("Lorg/tensorflow/lite/", "Lcom/google/ai/edge/litert/")
  $ownPrefixes = @("Ldev/pampa/", "Ldev/antigravity/fluidengine/")
  $aiPrefix = "Ldev/pampa/mycloudgallery/core/ai/"
  $facesPrefix = "Ldev/pampa/mycloudgallery/core/ai/faces/"
  $facesSessions = "Ldev/pampa/mycloudgallery/core/ai/faces/FaceLiteRtSessions"
  $facts = New-Object DexCallFacts
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("mcg-dex8-" + [guid]::NewGuid().ToString("N"))
  New-Item -ItemType Directory -Path $temp | Out-Null
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
  try {
    foreach ($entry in @($zip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' })) {
      $target = Join-Path $temp $entry.FullName
      [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
      [DexCallScanner]::Scan($dexdump, $target, $targets, $facts)
    }
  } finally {
    $zip.Dispose()
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
  }
  if ($facts.Lines -lt 1000 -or -not $facts.SawMainActivity) { return "lettura del bytecode non affidabile ($($facts.Lines) righe, MainActivity vista: $($facts.SawMainActivity))" }
  $problems = @()
  $ours = @()
  foreach ($call in $facts.Calls) {
    $parts = $call -split '\|', 2
    $caller = $parts[1]
    if (@($ownPrefixes | Where-Object { $caller.StartsWith($_) }).Count -eq 0) { continue }
    $ours += $call
    if (-not $caller.StartsWith($aiPrefix)) {
      $problems += "LiteRT chiamato fuori da core-ai: $caller ($($parts[0]))"
    } elseif ($caller.StartsWith($facesPrefix) -and -not ($caller.StartsWith("$facesSessions;") -or $caller.StartsWith("$facesSessions`$"))) {
      $problems += "LiteRT chiamato da una classe dei volti diversa da FaceLiteRtSessions: $caller"
    }
  }
  if (@($ours | Where-Object { ($_ -split '\|', 2)[1].StartsWith($aiPrefix) }).Count -eq 0) {
    $problems += "nessuna chiamata a LiteRT da core-ai nel dex: le sessioni del ritaglio non sono montate (o la lettura non e' affidabile)"
  }
  Write-Host ("        bytecode: {0} chiamate nostre a LiteRT, tutte da core-ai ({1} righe di dexdump)" -f $ours.Count, $facts.Lines) -ForegroundColor DarkGray
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
}

# Fase 9 (P0; docs/fase9-progetto.md D21, D23, §5.5): la rete della mappa resta al suo posto anche nel bytecode della release. I sorgenti li
# controllano Phase6ArchitectureTest (regola 5, due pacchetti) e Phase9ArchitectureTest; qui si guarda cio' che si installa:
#  - ogni chiamata a OkHttp (`Lokhttp3/`, anche solo come tipo nella firma) parte da OkHttp, da Okio, da MapLibre o da `feature/map/net/`
#    (`MapTileHttp`): nessun altro codice, nostro, dell'engine o di un'altra libreria, parla con OkHttp;
#  - ogni chiamata di codice nostro (e dell'engine) a MapLibre (`Lorg/maplibre/`, `Lcom/mapbox/`) parte da `feature/map/`.
# P1 (docs/decisioni.md "Fase 9 P1"): in piu' il client si installa in MapLibre (`HttpRequestUtil.setOkHttpClient`) solo da
# `feature/map/net/MapTileHttp`, e MapLibre si inizializza (`MapLibre.getInstance`) da codice nostro solo da `feature/map/map/MapLibreInit`.
# Le dipendenze sono in `:feature-map` da P1, ma nell'APK entrano quando `:app` dipende da `:feature-map` (P13): da quel momento il passo
# pretende di vederle (controllo positivo: almeno una chiamata a OkHttp da `net/`, il client installato, MapLibre inizializzato,
# `libmaplibre.so` nell'APK); prima conta zero e lo dice. feature-map/consumer-rules.pro tiene OkHttp, MapLibre e `net/` fuori dalle fusioni di
# classi di R8 (provato il 30/10: senza, metodi dell'app e di Compose finiscono in classi di OkHttp e il passo segnalerebbe mezza app).
# Senza dexdump o senza vedere la MainActivity il passo FALLISCE (non salta).
Step "OkHttp e MapLibre al loro posto nel bytecode della release (OkHttp solo da feature/map/net/ e da MapLibre, MapLibre solo da feature/map/)" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  Initialize-ApkInspect
  $dexdump = Get-DexDump
  if (-not $dexdump) { return "dexdump.exe non trovato accanto ad aapt2 (build-tools)" }
  $okhttp = "Lokhttp3/"
  $mapLibre = @("Lorg/maplibre/", "Lcom/mapbox/")
  $okhttpCallers = @("Lokhttp3/", "Lokio/", "Lorg/maplibre/", "Lcom/mapbox/", "Ldev/pampa/mycloudgallery/feature/map/net/")
  $ownPrefixes = @("Ldev/pampa/", "Ldev/antigravity/fluidengine/")
  $mapPrefix = "Ldev/pampa/mycloudgallery/feature/map/"
  $netPrefix = "Ldev/pampa/mycloudgallery/feature/map/net/"
  $setClient = "Lorg/maplibre/android/module/http/HttpRequestUtil;.setOkHttpClient:"
  $getInstance = "Lorg/maplibre/android/MapLibre;.getInstance:"
  $installer = "Ldev/pampa/mycloudgallery/feature/map/net/MapTileHttp"
  $initializer = "Ldev/pampa/mycloudgallery/feature/map/map/MapLibreInit"
  $mapInApp = Test-AppUsesFeatureMap
  $facts = New-Object DexCallFacts
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("mcg-dex9-" + [guid]::NewGuid().ToString("N"))
  New-Item -ItemType Directory -Path $temp | Out-Null
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
  try {
    foreach ($entry in @($zip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' })) {
      $target = Join-Path $temp $entry.FullName
      [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
      [DexCallScanner]::Scan($dexdump, $target, [string[]](@($okhttp, $setClient, $getInstance) + $mapLibre), $facts)
    }
    $hasMapLibreLib = @($zip.Entries | Where-Object { $_.FullName -eq "lib/arm64-v8a/libmaplibre.so" }).Count -gt 0
  } finally {
    $zip.Dispose()
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
  }
  if ($facts.Lines -lt 1000 -or -not $facts.SawMainActivity) { return "lettura del bytecode non affidabile ($($facts.Lines) righe, MainActivity vista: $($facts.SawMainActivity))" }
  $problems = @()
  $okCount = 0
  $mapCount = 0
  $okFromNet = 0
  $installs = 0
  $inits = 0
  foreach ($call in $facts.Calls) {
    $parts = $call -split '\|', 2
    $caller = $parts[1]
    $ours = @($ownPrefixes | Where-Object { $caller.StartsWith($_) }).Count -gt 0
    if ($parts[0] -eq $setClient) {
      # Il client delle tessere in MapLibre: solo il nostro, solo da MapTileHttp (nessun altro codice cambia il client di MapLibre).
      if ($caller.StartsWith("$installer;") -or $caller.StartsWith("$installer`$")) { $installs++ }
      elseif (-not $caller.StartsWith("Lorg/maplibre/")) { $problems += "client di MapLibre installato fuori da feature/map/net/MapTileHttp: $caller" }
      continue
    }
    if ($parts[0] -eq $getInstance) {
      if ($caller.StartsWith("$initializer;") -or $caller.StartsWith("$initializer`$")) { $inits++ }
      elseif ($ours) { $problems += "MapLibre inizializzato fuori da feature/map/map/MapLibreInit: $caller" }
      continue
    }
    if ($parts[0] -eq $okhttp) {
      if ($caller.StartsWith($netPrefix)) { $okFromNet++ }
      $okCount++
      if (@($okhttpCallers | Where-Object { $caller.StartsWith($_) }).Count -eq 0) { $problems += "OkHttp chiamato fuori da feature/map/net/ e da MapLibre: $caller" }
    } elseif (@($ownPrefixes | Where-Object { $caller.StartsWith($_) }).Count -gt 0) {
      $mapCount++
      if (-not $caller.StartsWith($mapPrefix)) { $problems += "MapLibre chiamato da codice nostro fuori da feature/map/: $caller ($($parts[0]))" }
    }
  }
  if ($mapInApp) {
    # P13 ha collegato la mappa: il client delle tessere, MapLibre e la sua libreria nativa DEVONO esserci (un passo che non vede niente non prova niente).
    if ($okFromNet -eq 0) { $problems += "nessuna chiamata a OkHttp da feature/map/net/ nel dex: il client delle tessere non e' nell'APK (o la lettura non e' affidabile)" }
    if ($installs -eq 0) { $problems += "nessuna chiamata a HttpRequestUtil.setOkHttpClient da MapTileHttp: MapLibre userebbe il suo client" }
    if ($inits -eq 0) { $problems += "nessuna chiamata a MapLibre.getInstance da MapLibreInit nel dex" }
    if (-not $hasMapLibreLib) { $problems += "lib/arm64-v8a/libmaplibre.so non e' nell'APK di release" }
  }
  $state = if ($mapInApp) { "mappa nell'app" } else { "mappa non ancora nell'app (P13)" }
  Write-Host ("        bytecode ({0}): {1} chiamate a OkHttp ({2} da net/), {3} chiamate nostre a MapLibre, client installato {4}, MapLibre inizializzato {5}; libmaplibre.so: {6} ({7} righe di dexdump)" -f $state, $okCount, $okFromNet, $mapCount, $installs, $inits, $hasMapLibreLib, $facts.Lines) -ForegroundColor DarkGray
  if ($problems.Count -gt 0) { return ($problems | Select-Object -First 20) -join "; " }
}

# Fase 10 (P0; docs/fase10-progetto.md par. 1.1, D10, D31, par. 2.8 "Le chiavi nell'app"): nel bytecode della release (dexdump) ogni chiamata a
# Glance (`androidx.glance`) parte da Glance stesso, dal kit dell'engine (`fluidengine/widget`) o da `feature/widget/` (il widget, P3); in piu' la
# chiave di PROVA del manifest (KT) non c'e': il suo nome di classe e' fra i resti di debug vietati (passo 7b) e, quando P1 la scrive in
# `core-release/.../testing/ReleaseTestKeys.kt`, le sue stringhe lunghe (la chiave pubblica in base64 o in esadecimale) non devono comparire nel
# dex di release. Oggi Glance non c'e' (arriva con P3) e KT nemmeno (P1): il passo passa a vuoto e lo dice; quando :app dipende da
# :feature-widget e feature-widget porta Glance, il controllo positivo pretende almeno una chiamata da `feature/widget/`. FALLISCE se non legge l'APK.
Step "Glance al suo posto nel bytecode della release (solo da feature/widget/) e nessuna chiave di prova del manifest (KT)" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  Initialize-ApkInspect
  $dexdump = Get-DexDump
  if (-not $dexdump) { return "dexdump.exe non trovato accanto ad aapt2 (build-tools)" }
  $glance = "Landroidx/glance/"
  $glanceCallers = @("Landroidx/glance/", "Ldev/antigravity/fluidengine/widget/", "Ldev/pampa/mycloudgallery/feature/widget/")
  $widgetPrefix = "Ldev/pampa/mycloudgallery/feature/widget/"
  $widgetInApp = $false
  foreach ($line in ((Read-TextFile (Join-Path $root "app\build.gradle")) -split '\r?\n')) {
    $codeLine = ($line -replace '//.*$', '')
    if ($codeLine -match '^\s*(test|androidTest)\w*\s*[\(\s]') { continue }
    if ($codeLine -match "project\(\s*(path\s*:\s*)?['""]:feature-widget['""]") { $widgetInApp = $true }
  }
  $widgetGradle = Join-Path $root "feature-widget\build.gradle"
  $glanceInWidget = (Test-Path -LiteralPath $widgetGradle) -and ((Read-TextFile $widgetGradle) -match 'glance')
  $facts = New-Object DexCallFacts
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("mcg-dex10-" + [guid]::NewGuid().ToString("N"))
  New-Item -ItemType Directory -Path $temp | Out-Null
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
  try {
    foreach ($entry in @($zip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' })) {
      $target = Join-Path $temp $entry.FullName
      [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
      [DexCallScanner]::Scan($dexdump, $target, [string[]]@($glance), $facts)
    }
  } finally {
    $zip.Dispose()
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
  }
  if ($facts.Lines -lt 1000 -or -not $facts.SawMainActivity) { return "lettura del bytecode non affidabile ($($facts.Lines) righe, MainActivity vista: $($facts.SawMainActivity))" }
  $problems = @()
  $glanceCount = 0
  $fromWidget = 0
  foreach ($call in $facts.Calls) {
    $caller = ($call -split '\|', 2)[1]
    $glanceCount++
    if ($caller.StartsWith($widgetPrefix)) { $fromWidget++ }
    if (@($glanceCallers | Where-Object { $caller.StartsWith($_) }).Count -eq 0) { $problems += "Glance chiamato fuori da feature/widget/ e dal kit dell'engine: $caller" }
  }
  if ($widgetInApp -and $glanceInWidget -and $fromWidget -eq 0) { $problems += "nessuna chiamata a Glance da feature/widget/ nel dex: il widget non e' nell'APK (o la lettura non e' affidabile)" }
  $ktFile = Join-Path $root "core-release\src\main\kotlin\dev\pampa\mycloudgallery\core\release\testing\ReleaseTestKeys.kt"
  $ktState = "KT non ancora scritta (P1)"
  if (Test-Path -LiteralPath $ktFile) {
    $literals = @([regex]::Matches((Read-TextFile $ktFile), '"([A-Za-z0-9+/=]{64,})"') | ForEach-Object { $_.Groups[1].Value })
    if ($literals.Count -eq 0) {
      $problems += "ReleaseTestKeys.kt senza la chiave pubblica di KT come stringa (base64 o esadecimale): il passo non la puo' cercare"
    } else {
      $found = @(Get-DexClassesMatching $apk.FullName (($literals | ForEach-Object { [regex]::Escape($_) }) -join "|"))
      if ($found.Count -gt 0) { $problems += "la chiave di prova KT e' nel dex di release" }
      $ktState = "KT cercata ($($literals.Count) stringhe), assente"
    }
  }
  $state = if ($widgetInApp) { "widget nell'app" } else { "widget non ancora nell'app (P3, P13)" }
  Write-Host ("        bytecode ({0}): {1} chiamate a Glance ({2} da feature/widget/); {3} ({4} righe di dexdump)" -f $state, $glanceCount, $fromWidget, $ktState, $facts.Lines) -ForegroundColor DarkGray
  if ($problems.Count -gt 0) { return ($problems | Select-Object -First 20) -join "; " }
}

# Fase 8 (P1; docs/fase8-progetto.md D39, §6.4): i due modelli dei volti viaggiano nell'APK di release (risposta Q1 (a)). Qui si guarda cio' che
# si installa: `assets/faces/` ha YuNet e SFace con la dimensione e lo SHA-256 del catalogo (`ModelCatalog.FACE_YUNET` e `FACE_SFACE`, letti dal
# sorgente: sono quelli del FILE del repository, fissati da P1), le due licenze (MIT, Apache-2.0) e l'avviso accanto, l'avviso nomina le due
# impronte, nessun altro modello nella cartella e nessun file ONNX in tutto l'APK. Controllo positivo: il ritaglio e' al suo posto (se non si
# legge nemmeno quello, l'elenco dell'APK non e' affidabile). Senza APK il passo FALLISCE.
Step "Modelli dei volti nell'APK di release: YuNet e SFace con le impronte del catalogo, licenze e NOTICE accanto, nessun ONNX (D39)" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $catalog = Read-TextFile (Join-Path $root "core-models\src\main\kotlin\dev\pampa\mycloudgallery\core\models\contract\ModelCatalog.kt")
  $specs = @()
  foreach ($name in @("FACE_YUNET", "FACE_SFACE")) {
    $block = [regex]::Match($catalog, '(?s)val ' + $name + ': ModelSpec = ModelSpec\((.*?)\n  \)')
    if (-not $block.Success) { return "ModelCatalog.$name non trovato in ModelCatalog.kt" }
    $size = [regex]::Match($block.Groups[1].Value, 'sizeBytes = ([0-9_]+)L')
    $sha = [regex]::Match($block.Groups[1].Value, 'Sha256\("([0-9a-f]{64})"\)')
    $asset = [regex]::Match($block.Groups[1].Value, 'bundledAsset = "([^"]+)"')
    if (-not ($size.Success -and $sha.Success -and $asset.Success)) { return "ModelCatalog.${name}: dimensione, SHA-256 o asset non leggibili" }
    $specs += [pscustomobject]@{ Name = $name; Size = [int64]($size.Groups[1].Value -replace '_', ''); Sha = $sha.Groups[1].Value; Asset = $asset.Groups[1].Value }
  }
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
  try {
    $problems = @()
    $names = @($zip.Entries | ForEach-Object { $_.FullName })
    if ($names -notcontains "assets/cutout/magic_touch.tflite") { return "lettura dell'APK non affidabile: manca assets/cutout/magic_touch.tflite" }
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    foreach ($spec in $specs) {
      $entry = $zip.GetEntry("assets/" + $spec.Asset)
      if (-not $entry) { $problems += "$($spec.Name): assets/$($spec.Asset) non e' nell'APK"; continue }
      if ($entry.Length -ne $spec.Size) { $problems += "$($spec.Name): $($entry.Length) byte nell'APK, $($spec.Size) nel catalogo"; continue }
      $stream = $entry.Open()
      try { $hex = ([System.BitConverter]::ToString($sha256.ComputeHash($stream)) -replace '-', '').ToLowerInvariant() } finally { $stream.Dispose() }
      if ($hex -ne $spec.Sha) { $problems += "$($spec.Name): SHA-256 $hex nell'APK, $($spec.Sha) nel catalogo" }
    }
    function Read-ZipText($entry) {
      $reader = New-Object System.IO.StreamReader($entry.Open(), (New-Object System.Text.UTF8Encoding($false)))
      try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    }
    $texts = @{ "assets/faces/LICENSE-YuNet.txt" = "MIT License"; "assets/faces/LICENSE-SFace.txt" = "Apache License"; "assets/faces/NOTICE.txt" = "OpenCV Zoo" }
    foreach ($path in $texts.Keys) {
      $entry = $zip.GetEntry($path)
      if (-not $entry) { $problems += "$path non e' nell'APK (licenza o avviso dei modelli dei volti)"; continue }
      $text = Read-ZipText $entry
      if (-not $text.Contains($texts[$path])) { $problems += "$path non contiene `"$($texts[$path])`"" }
      if ($path -like "*NOTICE.txt") {
        foreach ($spec in $specs) { if (-not $text.Contains($spec.Sha)) { $problems += "NOTICE.txt non nomina l'impronta di $($spec.Name)" } }
      }
    }
    $expected = @($specs | ForEach-Object { "assets/" + $_.Asset })
    $models = @($names | Where-Object { $_ -like "assets/faces/*" -and $_ -match '\.(tflite|onnx|task|litertlm|bin|pt|pb|h5)$' })
    foreach ($extra in @($models | Where-Object { $expected -notcontains $_ })) { $problems += "modello in piu' in assets/faces/: $extra" }
    foreach ($onnx in @($names | Where-Object { $_ -match '\.onnx$' })) { $problems += "file ONNX nell'APK: $onnx (si consegnano solo i .tflite)" }
    if ($problems.Count -gt 0) { return ($problems -join "; ") }
    Write-Host ("        assets/faces/: {0} modelli verificati ({1}), licenze e NOTICE presenti" -f $specs.Count, (($specs | ForEach-Object { "{0} {1} B" -f $_.Name, $_.Size }) -join ", ")) -ForegroundColor DarkGray
  } finally {
    $zip.Dispose()
  }
}

# R6: nessun file di servizio nostro nell'APK di release. `ServiceLoader` e' un canale dichiarativo e invisibile: un file in
# META-INF/services basta a sostituire una classe (la famiglia "pronta", la sincronizzazione ferma) senza toccare il codice.
# Le varianti di misura (benchmarkRelease, nonMinifiedRelease) legano le loro classi con un modulo Hilt, quindi la release
# non deve averne nemmeno uno.
Step "APK di release: nessun file di servizio dev.pampa.* in META-INF/services" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
  try {
    $names = @($zip.Entries | ForEach-Object { $_.FullName })
    # Controllo positivo: se l'elenco non e' quello di un APK la lettura non e' affidabile.
    if (-not ($names -contains "AndroidManifest.xml") -or -not ($names | Where-Object { $_ -match '^classes\d*\.dex$' })) {
      return "lettura dell'APK non affidabile: mancano AndroidManifest.xml o classes.dex"
    }
    $services = @($zip.Entries | Where-Object { $_.FullName -like "META-INF/services/*" -and -not $_.FullName.EndsWith("/") })
    $problems = @()
    foreach ($entry in $services) {
      if ($entry.Name -match '^dev\.pampa\.') { $problems += "file di servizio nostro: " + $entry.FullName; continue }
      # Anche un servizio con il nome di un'interfaccia di Java o di una libreria non deve registrare una classe nostra.
      $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
      try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
      if ($text -match 'dev\.pampa\.') { $problems += ($entry.FullName + " registra una classe dev.pampa.*") }
    }
    Write-Host ("        servizi nell'APK: {0}" -f $(if ($services.Count -gt 0) { ($services | ForEach-Object { $_.Name }) -join ", " } else { "nessuno" })) -ForegroundColor DarkGray
    if ($problems.Count -gt 0) { return ($problems -join "; ") }
  } finally {
    $zip.Dispose()
  }
}

Step "nessuna classe di misura (Bench*, benchmark, UiAutomator) nel dex di release" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $benchClasses = @(Get-BenchmarkClassesInApk $apk.FullName)
  if ($benchClasses.Count -gt 0) {
    Show-Lines $benchClasses 20
    return "$($benchClasses.Count) classi di misura nel dex di release: la variante benchmarkRelease non deve mai arrivare all'APK (D21)"
  }
}

Step "permessi dell'APK di release tutti elencati in docs/sicurezza.md 4-bis" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $aapt2 = Get-Aapt2
  if (-not $aapt2) { return "aapt2 non trovato: controlla sdk.dir in local.properties e le build-tools dell'SDK" }
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $dump = Invoke-Native -File $aapt2 -Arguments @("dump", "permissions", $apk.FullName)
  if ($dump.Code -ne 0) { return "aapt2 dump permissions fallito" }
  $permissions = @()
  foreach ($line in $dump.Lines) {
    if ($line -match "^uses-permission[\w\-]*: name='([^']+)'") { $permissions += $Matches[1] }
  }
  Write-Host ("        permessi nell'APK: " + (($permissions | Sort-Object) -join ", ")) -ForegroundColor DarkGray
  $missing = Get-UndocumentedPermissions $permissions (Get-DocumentedPermissions)
  if ($missing.Count -gt 0) {
    return "permessi nell'APK senza riga in docs\sicurezza.md 4-bis: " + ($missing -join ", ")
  }
}

# --- Fase 3, P9: Baseline Profile e prestazioni misurate (docs/fase3-progetto.md par. 2.6, D21) --------------------------

# Il Baseline Profile nei sorgenti, se esiste (lo scrive :app:generateBaselineProfile o tools\bench\generate-profile.ps1).
function Get-BaselineProfileSource {
  foreach ($relative in @("app\src\main\generated\baselineProfiles\baseline-prof.txt", "app\src\release\generated\baselineProfiles\baseline-prof.txt", "app\src\main\baseline-prof.txt")) {
    $path = Join-Path $root $relative
    if (Test-Path -LiteralPath $path) { return $path }
  }
  return $null
}

# Le regole di un profilo testuale: tutte le righe che non sono vuote ne' commenti.
function Get-ProfileRules([string]$path) {
  return @(Get-Content -LiteralPath $path | Where-Object { $_.Trim().Length -gt 0 -and -not $_.TrimStart().StartsWith("#") })
}

function Get-SdkDir {
  $sdk = $null
  $localProperties = Join-Path $root "local.properties"
  if (Test-Path -LiteralPath $localProperties) {
    $match = Select-String -LiteralPath $localProperties -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
    if ($match) { $sdk = $match.Matches[0].Groups[1].Value.Trim().Replace("\:", ":").Replace("\\", "\") }
  }
  if (-not $sdk -and $env:ANDROID_HOME) { $sdk = $env:ANDROID_HOME }
  if (-not $sdk -and $env:ANDROID_SDK_ROOT) { $sdk = $env:ANDROID_SDK_ROOT }
  return $sdk
}

function Get-Adb {
  $sdk = Get-SdkDir
  if ($sdk) {
    $path = Join-Path $sdk "platform-tools\adb.exe"
    if (Test-Path -LiteralPath $path) { return $path }
  }
  $command = Get-Command adb -ErrorAction SilentlyContinue
  if ($command) { return $command.Source }
  return $null
}

# I numeri di serie dei dispositivi collegati e pronti (`adb devices`), nell'ordine di adb.
function Get-AdbSerials([string]$adb) {
  $run = Invoke-Native -File $adb -Arguments @("devices")
  $serials = @()
  foreach ($line in $run.Lines) {
    if ($line -match '^(\S+)\s+device$') { $serials += $Matches[1] }
  }
  return $serials
}

# I fatti del dispositivo, letti in SOLA LETTURA (getprop, dumpsys, settings get): niente si cambia mai sul telefono.
# La frequenza dello schermo conta perche' a 120 Hz serve "Fluidita' movimento: Adattiva" (par. 2.6): si leggono i modi
# del display (il massimo disponibile e l'attivo) e le tre impostazioni che la decidono; il giudizio e' di chi legge.
function Get-DeviceFacts([string]$adb, [string]$serial) {
  $read = {
    param([string]$command)
    $run = Invoke-Native -File $adb -Arguments @("-s", $serial, "shell", $command)
    return (($run.Lines -join "`n").Trim())
  }
  $facts = [ordered]@{ Serial = $serial; Model = ""; Release = ""; Sdk = ""; Emulator = $false; ActiveHz = $null; MaxHz = $null; Settings = "" }
  $facts.Model = & $read "getprop ro.product.model"
  $facts.Release = & $read "getprop ro.build.version.release"
  $facts.Sdk = & $read "getprop ro.build.version.sdk"
  $facts.Emulator = ($serial -like "emulator-*") -or ((& $read "getprop ro.kernel.qemu") -eq "1") -or ((& $read "getprop ro.boot.qemu") -eq "1")
  $display = & $read "dumpsys display"
  $modes = @{}
  # I modi del display: "{id=1, [parentModeId=-1, sfModeId=0, flags=,] width=1080, height=2400, fps=60.0, ...}" (i campi
  # in mezzo cambiano da una versione di Android all'altra).
  foreach ($m in [regex]::Matches($display, '\bid=(\d+),[^{}]*?\bwidth=\d+,\s*height=\d+,\s*fps=([0-9.]+)')) {
    $modes[$m.Groups[1].Value] = [double]::Parse($m.Groups[2].Value, [System.Globalization.CultureInfo]::InvariantCulture)
  }
  if ($modes.Count -gt 0) { $facts.MaxHz = ($modes.Values | Measure-Object -Maximum).Maximum }
  $render = [regex]::Match($display, 'mActiveRenderFrameRate=([0-9.]+)')
  $active = [regex]::Match($display, 'mActiveModeId=(\d+)')
  if ($render.Success) {
    $facts.ActiveHz = [double]::Parse($render.Groups[1].Value, [System.Globalization.CultureInfo]::InvariantCulture)
  } elseif ($active.Success -and $modes.ContainsKey($active.Groups[1].Value)) {
    $facts.ActiveHz = $modes[$active.Groups[1].Value]
  }
  $peak = & $read "settings get system peak_refresh_rate"
  $min = & $read "settings get system min_refresh_rate"
  $mode = & $read "settings get secure refresh_rate_mode"
  $facts.Settings = "peak_refresh_rate=$peak min_refresh_rate=$min refresh_rate_mode=$mode"
  return $facts
}

Step "Baseline Profile: se esiste nei sorgenti, l'APK di release lo contiene e AGP l'ha unito" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $source = Get-BaselineProfileSource
  if (-not $source) {
    Write-Host "        nessun profilo nei sorgenti (app\src\main\generated\baselineProfiles): da generare con :app:generateBaselineProfile o tools\bench\generate-profile.ps1" -ForegroundColor Yellow
    return
  }
  $rules = @(Get-ProfileRules $source)
  if ($rules.Count -lt 50) { return "il profilo nei sorgenti ha solo $($rules.Count) regole: vuoto o troncato ($source)" }
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
  try {
    $prof = $zip.Entries | Where-Object { $_.FullName -eq "assets/dexopt/baseline.prof" } | Select-Object -First 1
    $profm = $zip.Entries | Where-Object { $_.FullName -eq "assets/dexopt/baseline.profm" } | Select-Object -First 1
    $profSize = if ($prof) { $prof.Length } else { 0 }
    $profmSize = if ($profm) { $profm.Length } else { 0 }
  } finally {
    $zip.Dispose()
  }
  if (-not $prof) { return "l'APK di release non contiene assets/dexopt/baseline.prof pur esistendo il profilo nei sorgenti" }
  # Le librerie (Compose, Room...) portano profili propri e producono un baseline.prof anche senza il nostro: l'unione
  # si verifica sul testo che AGP ha fuso, che deve contenere (quasi) tutte le nostre regole.
  $merged = Join-Path $root "app\build\intermediates\merged_art_profile\release\mergeReleaseArtProfile\baseline-prof.txt"
  $note = ""
  if (Test-Path -LiteralPath $merged) {
    $set = New-Object System.Collections.Generic.HashSet[string]
    foreach ($line in (Get-Content -LiteralPath $merged)) { [void]$set.Add($line.Trim()) }
    $absent = @($rules | Where-Object { -not $set.Contains($_.Trim()) })
    if ($absent.Count -gt [math]::Ceiling($rules.Count * 0.05)) {
      return "AGP non ha unito il profilo: $($absent.Count) di $($rules.Count) regole non compaiono nel profilo fuso della release"
    }
    $note = ", unione verificata"
  } else {
    $note = ", file fuso non trovato (unione non verificata)"
  }
  Write-Host ("        profilo: {0} regole; APK: baseline.prof {1} byte, baseline.profm {2} byte{3}" -f $rules.Count, $profSize, $profmSize, $note) -ForegroundColor DarkGray
}

Step "benchmark: gli scenari (:benchmark) e il dataset (app/src/benchmarkRelease) compilano" -Skip:$SkipGradle -SkipReason "-SkipGradle" {
  Ensure-Java
  $arguments = @("--console=plain", "-q", ":app:compileBenchmarkReleaseKotlin", ":benchmark:compileBenchmarkReleaseKotlin")
  if ($NoDaemon) { $arguments = @("--no-daemon") + $arguments }
  $run = Invoke-Native -File (Join-Path $root "gradlew.bat") -Arguments $arguments -LogPath (Join-Path $root "build\check-bench-compile.log")
  if ($run.Code -ne 0) {
    Show-Lines $run.Lines 40
    return "la variante benchmarkRelease non compila (codice $($run.Code)); log in build\check-bench-compile.log"
  }
}

# Fase 6 (P9, P11; docs/fase6-progetto.md par. 5.4): la guardia -Bench della ricerca. Senza dispositivo si controlla almeno che gli scenari
# della ricerca ci siano in benchmark\soglie.json (letti da compare.ps1) e che la prova che li misura (`SearchBenchmark`) sia nel modulo.
Step "benchmark: scenari della ricerca (ricerca, ricerca-freddo-contenuto, ricerca-freddo-filtri, ricerca-memoria) in soglie.json" {
  $soglie = Join-Path $root "benchmark\soglie.json"
  if (-not (Test-Path -LiteralPath $soglie)) { return "manca benchmark\soglie.json" }
  $json = (Read-TextFile $soglie) | ConvertFrom-Json
  $ids = @($json.scenari | ForEach-Object { $_.id })
  if ($ids.Count -eq 0) { $ids = @(([regex]::Matches((Read-TextFile $soglie), '"id"\s*:\s*"([^"]+)"')) | ForEach-Object { $_.Groups[1].Value }) }
  $problems = @()
  foreach ($id in @("ricerca", "ricerca-freddo-contenuto", "ricerca-freddo-filtri", "ricerca-memoria")) {
    if ($ids -notcontains $id) { $problems += "scenario $id assente" }
  }
  $bench = @(Get-ChildItem (Join-Path $root "benchmark\src") -Recurse -Filter "SearchBenchmark.kt" -ErrorAction SilentlyContinue)
  if ($bench.Count -eq 0) { $problems += "manca benchmark/.../SearchBenchmark.kt" }
  Write-Host ("        scenari in soglie.json: {0}" -f $ids.Count) -ForegroundColor DarkGray
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
}

# Debito del 27/10 (docs/fase7-progetto.md par. 5.4, docs/fase8-progetto.md par. 5.4): gli scenari `album` e `volti`, promessi e mancanti. Come per la ricerca, senza
# dispositivo si controlla che ci siano in soglie.json con le soglie dei progetti (apertura 300 ms, ricalcolo 2 s, meno dell'1% di fotogrammi in ritardo, regressione
# +15%) e che le prove che li misurano esistano nel modulo (`AlbumBenchmark`, `FacesBenchmark`). Le misure vere vengono da -Bench sul telefono.
Step "benchmark: scenari degli album e dei volti (album, album-scroll, album-ricalcolo, album-raccolte, volti, volti-scroll) in soglie.json e nel modulo" {
  $soglie = Join-Path $root "benchmark\soglie.json"
  if (-not (Test-Path -LiteralPath $soglie)) { return "manca benchmark\soglie.json" }
  $json = (Read-TextFile $soglie) | ConvertFrom-Json
  $byId = @{}
  foreach ($s in @($json.scenari)) { $byId[[string]$s.id] = $s }
  $sources = @(Get-ChildItem (Join-Path $root "benchmark\src") -Recurse -Filter "*.kt" -ErrorAction SilentlyContinue)
  $wanted = @(
    @{ Id = "album";           Test = "albumOpen";         Class = "AlbumBenchmark"; Metric = "albumOpenMs";      Max = 300.0 },
    @{ Id = "album-scroll";    Test = "albumScroll";       Class = "AlbumBenchmark"; Metric = "jankPercent";      Max = 1.0 },
    @{ Id = "album-ricalcolo"; Test = "albumRecompute";    Class = "AlbumBenchmark"; Metric = "albumRecomputeMs"; Max = 2000.0 },
    @{ Id = "album-raccolte";  Test = "collectionsOpen";   Class = "AlbumBenchmark"; Metric = $null;              Max = $null },
    @{ Id = "volti";           Test = "facesEngines";      Class = "FacesBenchmark"; Metric = $null;              Max = $null },
    @{ Id = "volti-scroll";    Test = "facesLoadedScroll"; Class = "FacesBenchmark"; Metric = "jankPercent";      Max = 1.0 }
  )
  $problems = @()
  foreach ($w in $wanted) {
    $scenario = $byId[$w.Id]
    if (-not $scenario) { $problems += "scenario $($w.Id) assente"; continue }
    if (@($scenario.proveAttese) -notcontains $w.Test) { $problems += "scenario $($w.Id): la prova attesa $($w.Test) manca" }
    if (@($scenario.classi) -notcontains $w.Class) { $problems += "scenario $($w.Id): la classe $($w.Class) manca" }
    $file = $sources | Where-Object { $_.Name -eq "$($w.Class).kt" } | Select-Object -First 1
    if (-not $file) {
      $problems += "manca benchmark/.../$($w.Class).kt"
    } elseif ((Read-TextFile $file.FullName) -notmatch ("fun\s+" + $w.Test + "\s*\(")) {
      $problems += "$($w.Class).kt non ha la prova $($w.Test)"
    }
    $absolute = @($scenario.assolute)
    if ($w.Metric) {
      $rule = @($absolute | Where-Object { $_.metrica -eq $w.Metric }) | Select-Object -First 1
      if (-not $rule -or [double]$rule.massimo -ne [double]$w.Max) { $problems += "scenario $($w.Id): la soglia assoluta di $($w.Metric) non e' $($w.Max)" }
    } elseif ($absolute.Count -gt 0) {
      $problems += "scenario $($w.Id): il progetto non fissa una soglia assoluta"
    }
    $rise = @(@($scenario.regressione) | Where-Object { [math]::Abs([double]$_.aumentoMassimo - 0.15) -lt 1e-9 })
    if ($rise.Count -eq 0) { $problems += "scenario $($w.Id): manca la regressione +15%" }
  }
  # L'auto-test di compare.ps1 prova le soglie nuove su misure costruite a mano (300 ms contro 301, 2 s, +15%, prova non valida): non serve un dispositivo.
  $selfTest = Invoke-Native -File "powershell" -Arguments @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $root "tools\bench\compare.ps1"), "-SelfTest")
  if ($selfTest.Code -ne 0) {
    Show-Lines $selfTest.Lines 20
    $problems += "compare.ps1 -SelfTest fallisce: le soglie nuove non si confrontano come dovrebbero"
  }
  Write-Host ("        scenari verificati: {0}" -f $wanted.Count) -ForegroundColor DarkGray
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
}

# Fase 9 (P12, docs/fase9-progetto.md par. 5.4 e §0 punto 12: "scenari promessi e mancanti"): gli scenari `ricordi-*` e `mappa-*` devono ESISTERE, non solo stare nei documenti.
# Senza dispositivo si controlla che ci siano in soglie.json con le soglie del progetto (300 ms, 2 s, 300 ms, 800/300 ms, 8 ms, 150 ms, 100 ms, 1% di jank, 16,7 ms; il tempo di
# disegno di MapLibre `null` da calibrare) e le regressioni (+15%, +10% dove la guardia e' quella della Libreria), che le prove che li misurano esistano nel modulo
# (`MemoriesBenchmark`, `MapBenchmark`), che le soglie JVM del par. 5.4 stiano nei test che girano a ogni check (generazione su 100 mila < 1 s, scelta da 200 candidati < 50 ms,
# indice della mappa < 800 ms e `clusters` < 4 ms p95, query dei punti < 400 ms senza TEMP B-TREE) e che il collaudo non accenda mai la mappa online. Le misure vere
# vengono da -Bench sul telefono; lo smoke sull'emulatore dice se girano.
Step "benchmark: scenari dei ricordi e della mappa (ricordi-*, mappa-*) in soglie.json e nel modulo; soglie JVM del par. 5.4 nei test" {
  $soglie = Join-Path $root "benchmark\soglie.json"
  if (-not (Test-Path -LiteralPath $soglie)) { return "manca benchmark\soglie.json" }
  $json = (Read-TextFile $soglie) | ConvertFrom-Json
  $byId = @{}
  foreach ($s in @($json.scenari)) { $byId[[string]$s.id] = $s }
  $sources = @(Get-ChildItem (Join-Path $root "benchmark\src") -Recurse -Filter "*.kt" -ErrorAction SilentlyContinue)
  $wanted = @(
    @{ Id = "ricordi-apertura";       Test = "memoryOpen";           Class = "MemoriesBenchmark"; Metric = "memoryOpenMs";        Max = 300.0;  Rise = 0.15 },
    @{ Id = "ricordi-presentazione";  Test = "memoryPlayer";         Class = "MemoriesBenchmark"; Metric = "jankPercent";         Max = 1.0;    Rise = 0.15 },
    @{ Id = "ricordi-presentazione";  Test = "memoryPlayer";         Class = "MemoriesBenchmark"; Metric = "maxFrameCpuMs";       Max = 16.7;   Rise = 0.15 },
    @{ Id = "ricordi-generazione";    Test = "memoriesGenerate";     Class = "MemoriesBenchmark"; Metric = "memoriesGenerateMs";  Max = 2000.0; Rise = 0.15 },
    @{ Id = "ricordi-generazione";    Test = "memoriesGenerate";     Class = "MemoriesBenchmark"; Metric = "memoriesLightMs";     Max = 300.0;  Rise = 0.15 },
    @{ Id = "ricordi-raccolte";       Test = "collectionsScroll";    Class = "MemoriesBenchmark"; Metric = "jankPercent";         Max = 1.0;    Rise = 0.10 },
    @{ Id = "ricordi-scroll-carico";  Test = "memoriesLoadedScroll"; Class = "MemoriesBenchmark"; Metric = "jankPercent";         Max = 1.0;    Rise = 0.15 },
    @{ Id = "mappa-apertura-freddo";  Test = "mapOpenCold";          Class = "MapBenchmark";      Metric = "mapOpenMs";           Max = 800.0;  Rise = 0.15 },
    @{ Id = "mappa-apertura-caldo";   Test = "mapOpenWarm";          Class = "MapBenchmark";      Metric = "mapOpenMs";           Max = 300.0;  Rise = 0.15 },
    @{ Id = "mappa-gesti";            Test = "mapGestures";          Class = "MapBenchmark";      Metric = "jankPercent";         Max = 1.0;    Rise = 0.10 },
    @{ Id = "mappa-disegno";          Test = "mapDraw";              Class = "MapBenchmark";      Metric = "mapDrawP95Ms";        Max = $null;  Rise = 0.15 },
    @{ Id = "mappa-bolle";            Test = "mapClusters";          Class = "MapBenchmark";      Metric = "mapClustersP95Ms";    Max = 8.0;    Rise = 0.15 },
    @{ Id = "mappa-zona";             Test = "mapZoneOpen";          Class = "MapBenchmark";      Metric = "zoneOpenMs";          Max = 150.0;  Rise = 0.15 },
    @{ Id = "mappa-viewer";           Test = "mapZoneViewer";        Class = "MapBenchmark";      Metric = "viewerOpenMs";        Max = 100.0;  Rise = 0.15 },
    @{ Id = "mappa-memoria";          Test = "mapMemory";            Class = "MapBenchmark";      Metric = $null;                 Max = $null;  Rise = 0.15 },
    @{ Id = "mappa-scroll-carico";    Test = "mapLoadedScroll";      Class = "MapBenchmark";      Metric = "jankPercent";         Max = 1.0;    Rise = 0.15 }
  )
  $problems = @()
  foreach ($w in $wanted) {
    $scenario = $byId[$w.Id]
    if (-not $scenario) { $problems += "scenario $($w.Id) assente"; continue }
    if (@($scenario.proveAttese) -notcontains $w.Test) { $problems += "scenario $($w.Id): la prova attesa $($w.Test) manca" }
    if (@($scenario.classi) -notcontains $w.Class) { $problems += "scenario $($w.Id): la classe $($w.Class) manca" }
    $file = $sources | Where-Object { $_.Name -eq "$($w.Class).kt" } | Select-Object -First 1
    if (-not $file) {
      $problems += "manca benchmark/.../$($w.Class).kt"
    } elseif ((Read-TextFile $file.FullName) -notmatch ("fun\s+" + $w.Test + "\s*\(")) {
      $problems += "$($w.Class).kt non ha la prova $($w.Test)"
    }
    if ($w.Metric) {
      $rule = @(@($scenario.assolute) | Where-Object { $_.metrica -eq $w.Metric }) | Select-Object -First 1
      if (-not $rule) {
        $problems += "scenario $($w.Id): manca la soglia assoluta di $($w.Metric)"
      } elseif ($null -eq $w.Max) {
        # Il tetto da calibrare resta `null` con la sua calibrazione: scriverci un numero a mano nasconderebbe che manca il primo riferimento.
        if ($null -ne $rule.massimo -or $null -eq $rule.calibrazione) { $problems += "scenario $($w.Id): $($w.Metric) deve restare null con la calibrazione (da calibrare sul primo riferimento)" }
      } elseif ([double]$rule.massimo -ne [double]$w.Max) {
        $problems += "scenario $($w.Id): la soglia assoluta di $($w.Metric) non e' $($w.Max)"
      }
    } elseif (@($scenario.assolute).Count -gt 0) {
      $problems += "scenario $($w.Id): il progetto non fissa una soglia assoluta"
    }
    $rise = @(@($scenario.regressione) | Where-Object { [math]::Abs([double]$_.aumentoMassimo - [double]$w.Rise) -lt 1e-9 })
    if ($rise.Count -eq 0) { $problems += "scenario $($w.Id): manca la regressione +$([int]([double]$w.Rise * 100))%" }
    if (-not $scenario.validita) { $problems += "scenario $($w.Id): manca la validita' (un falso verde senza dataset o senza il lavoro in corso)" }
  }
  # Le soglie JVM del par. 5.4 stanno nei test (a ogni `galleryTest`): se qualcuno le allenta o le toglie, il controllo lo dice.
  $jvm = @(
    @{ File = "core-memories\src\test\kotlin\dev\pampa\mycloudgallery\core\memories\plan\MemoryEnginePerformanceTest.kt"; Pattern = 'isLessThan\(1_000\.0\)'; What = "generazione su 100 mila < 1 s" },
    @{ File = "core-memories\src\test\kotlin\dev\pampa\mycloudgallery\core\memories\pick\PickPerformanceTest.kt"; Pattern = 'LIMIT_MS\s*=\s*50\.0'; What = "scelta di un ricordo da 200 candidati < 50 ms" },
    @{ File = "core-map\src\test\kotlin\dev\pampa\mycloudgallery\core\map\points\GeoPointIndexPerformanceTest.kt"; Pattern = 'isLessThan\(800\)'; What = "indice della mappa: costruzione < 800 ms" },
    @{ File = "core-map\src\test\kotlin\dev\pampa\mycloudgallery\core\map\points\GeoPointIndexPerformanceTest.kt"; Pattern = 'isLessThan\(4\.0\)'; What = "indice della mappa: clusters p95 < 4 ms" },
    @{ File = "core-database\src\test\kotlin\dev\pampa\mycloudgallery\core\database\MapPointsQueriesTest.kt"; Pattern = 'LIMIT_MS\s*=\s*400L'; What = "query dei punti su 100 mila righe < 400 ms" },
    @{ File = "core-database\src\test\kotlin\dev\pampa\mycloudgallery\core\database\MapPointsQueriesTest.kt"; Pattern = 'doesNotContain\("TEMP B-TREE"\)'; What = "query dei punti senza TEMP B-TREE" }
  )
  foreach ($j in $jvm) {
    $path = Join-Path $root $j.File
    if (-not (Test-Path -LiteralPath $path)) { $problems += "soglia JVM '$($j.What)': manca $($j.File)"; continue }
    if ((Read-TextFile $path) -notmatch $j.Pattern) { $problems += "soglia JVM '$($j.What)': non e' piu' nel test ($($j.File))" }
  }
  # Il collaudo non accende la mappa online (nessun traffico verso OpenFreeMap): la variante la spegne e le prove non la nominano.
  $hooks = Get-ChildItem (Join-Path $root "app\src\benchmarkRelease") -Recurse -Filter "BenchMeasurementHooks.kt" -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $hooks -or (Read-TextFile $hooks.FullName) -notmatch 'mapOnlineAllowed\s*:\s*Boolean\s*=\s*false') { $problems += "BenchMeasurementHooks non spegne la mappa dettagliata (mapOnlineAllowed = false)" }
  foreach ($file in $sources) {
    # Solo il codice: i commenti spiegano proprio che la mappa online non si accende e la nominano.
    $code = [regex]::Replace([regex]::Replace((Read-TextFile $file.FullName), '(?s)/\*.*?\*/', ''), '(?m)//.*$', '')
    if ($code -match '(?i)MapOnlineChoice|MapOnline\.On|\.enable\(|openfreemap|https?://|mapOnline\s*=\s*true') { $problems += "benchmark/.../$($file.Name) nomina la mappa online nel codice: il collaudo non la accende mai" }
  }
  Write-Host ("        scenari verificati: {0}; soglie JVM: {1}" -f $wanted.Count, $jvm.Count) -ForegroundColor DarkGray
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
}

# Avvio istantaneo (28/10, docs/decisioni.md "Avvio e pagine istantanei"): il primo disegno utile e l'apertura delle pagine principali. Senza
# dispositivo si controlla che le soglie ci siano (primo disegno utile stretto, caricamento completo largo a 1500 ms, pagine) e che le prove e la
# metrica esistano nel modulo. Le misure vere vengono da -Bench sul telefono.
Step "benchmark: scenari dell'avvio istantaneo (avvio, avvio-abituale, pagine) in soglie.json e nel modulo" {
  $soglie = Join-Path $root "benchmark\soglie.json"
  if (-not (Test-Path -LiteralPath $soglie)) { return "manca benchmark\soglie.json" }
  $json = (Read-TextFile $soglie) | ConvertFrom-Json
  $byId = @{}
  foreach ($s in @($json.scenari)) { $byId[[string]$s.id] = $s }
  $sources = @(Get-ChildItem (Join-Path $root "benchmark\src") -Recurse -Filter "*.kt" -ErrorAction SilentlyContinue)
  $wanted = @(
    @{ Id = "avvio";           Test = "coldStart";       Class = "StartupBenchmark"; Useful = $true },
    @{ Id = "avvio-abituale";  Test = "coldStartUsual";  Class = "StartupBenchmark"; Useful = $true },
    @{ Id = "pagina-raccolte"; Test = "collectionsPage"; Class = "PagesBenchmark";   Metric = "raccoltePageMs" },
    @{ Id = "pagina-cerca";    Test = "searchPage";      Class = "PagesBenchmark";   Metric = "cercaPageMs" },
    @{ Id = "pagina-album";    Test = "albumPage";       Class = "PagesBenchmark";   Metric = "albumPageMs" },
    @{ Id = "pagina-persone";  Test = "peoplePage";      Class = "PagesBenchmark";   Metric = "personePageMs" }
  )
  $problems = @()
  foreach ($w in $wanted) {
    $scenario = $byId[$w.Id]
    if (-not $scenario) { $problems += "scenario $($w.Id) assente"; continue }
    if (@($scenario.proveAttese) -notcontains $w.Test) { $problems += "scenario $($w.Id): la prova attesa $($w.Test) manca" }
    $file = $sources | Where-Object { $_.Name -eq "$($w.Class).kt" } | Select-Object -First 1
    if (-not $file) {
      $problems += "manca benchmark/.../$($w.Class).kt"
    } elseif ((Read-TextFile $file.FullName) -notmatch ("fun\s+" + $w.Test + "\s*\(")) {
      $problems += "$($w.Class).kt non ha la prova $($w.Test)"
    }
    $absolute = @($scenario.assolute)
    if ($w.Useful) {
      if (-not (@($absolute | Where-Object { $_.metrica -eq "libraryUsefulMs" -and $_.statistica -eq "p90" }))) { $problems += "scenario $($w.Id): manca la soglia del primo disegno utile" }
      $full = @($absolute | Where-Object { $_.metrica -eq "timeToFullDisplayMs" }) | Select-Object -First 1
      if (-not $full -or [double]$full.massimoEsclusivo -ne 1500.0) { $problems += "scenario $($w.Id): il caricamento completo non e' p90 < 1500 ms" }
    } elseif (-not (@($absolute | Where-Object { $_.metrica -eq $w.Metric -and $_.statistica -eq "p90" }))) {
      $problems += "scenario $($w.Id): manca la soglia di $($w.Metric)"
    }
  }
  $metrics = $sources | Where-Object { $_.Name -eq "AvvioMetrics.kt" } | Select-Object -First 1
  if (-not $metrics -or (Read-TextFile $metrics.FullName) -notmatch "class LibraryUsefulMetric") { $problems += "manca LibraryUsefulMetric (benchmark/.../AvvioMetrics.kt)" }
  Write-Host ("        scenari verificati: {0}" -f $wanted.Count) -ForegroundColor DarkGray
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
}

# -Bench: le misure del par. 2.6 su un dispositivo collegato. Senza -Bench o senza dispositivo il passo e' SKIP (non fallisce).
$benchAdb = $null
$benchTarget = $null
$benchSkip = -not $Bench
$benchReason = "senza -Bench"
if ($Bench) {
  $benchAdb = Get-Adb
  if (-not $benchAdb) {
    $benchSkip = $true
    $benchReason = "adb non trovato (sdk.dir in local.properties)"
  } else {
    $serials = @(Get-AdbSerials $benchAdb)
    if ($BenchSerial) { $serials = @($serials | Where-Object { $_ -eq $BenchSerial }) }
    if ($serials.Count -eq 0) {
      $benchSkip = $true
      $benchReason = "nessun dispositivo collegato"
    } elseif ($serials.Count -eq 1) {
      $benchTarget = $serials[0]
    } else {
      $benchTarget = ""   # piu' dispositivi: il passo fallisce chiedendo -BenchSerial (un dispositivo per volta)
    }
  }
}
Step "benchmark su dispositivo (-Bench): scenari del par. 2.6 contro benchmark\soglie.json e riferimento" -Skip:$benchSkip -SkipReason $benchReason {
  if ($benchTarget -eq "") { return "piu' dispositivi collegati: indica quale con -BenchSerial <numero di serie> (si misura un dispositivo per volta)" }
  $facts = Get-DeviceFacts $benchAdb $benchTarget
  $emulatorNote = if ($facts.Emulator) { " - EMULATORE: smoke, i numeri non valgono" } else { "" }
  Write-Host ("        dispositivo: {0} ({1}), Android {2} (SDK {3}){4}" -f $facts.Model, $benchTarget, $facts.Release, $facts.Sdk, $emulatorNote) -ForegroundColor DarkGray
  $hz = if ($null -ne $facts.MaxHz) { $facts.MaxHz } else { 0 }
  $activeText = if ($null -ne $facts.ActiveHz) { $facts.ActiveHz } else { "?" }
  $maxText = if ($null -ne $facts.MaxHz) { $facts.MaxHz } else { "?" }
  Write-Host ("        schermo (sola lettura): attivo {0} Hz, massimo {1} Hz; {2}" -f $activeText, $maxText, $facts.Settings) -ForegroundColor DarkGray
  if (-not $facts.Emulator) {
    Write-Host "        a 120 Hz serve 'Fluidita' movimento: Adattiva' (Impostazioni > Display), batteria carica, schermo acceso e sbloccato" -ForegroundColor DarkGray
  }
  Ensure-Java
  $gradleArguments = @(
    "--console=plain", ":benchmark:connectedBenchmarkReleaseAndroidTest",
    "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark"
  )
  if ($facts.Emulator) {
    # Fase 9 (P12): la benchmarkRelease porta solo arm64-v8a; su un emulatore x86_64 si tiene anche x86_64 (solo in questa variante, solo per lo smoke).
    $gradleArguments += "-PbenchKeepX86=true"
    $gradleArguments += "-Pandroid.testInstrumentationRunnerArguments.benchSmoke=true"
    $gradleArguments += "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,LOW-BATTERY,NOT-PROFILEABLE"
  }
  if ($NoDaemon) { $gradleArguments = @("--no-daemon") + $gradleArguments }
  Write-Host "        (puo' volerci diversi minuti; il log completo e' in build\check-bench.log)" -ForegroundColor DarkGray
  # Un dispositivo per volta: Gradle gira su tutti quelli collegati, a meno di ANDROID_SERIAL.
  $previousSerial = $env:ANDROID_SERIAL
  $env:ANDROID_SERIAL = $benchTarget
  try {
    $run = Invoke-Native -File (Join-Path $root "gradlew.bat") -Arguments $gradleArguments -LogPath (Join-Path $root "build\check-bench.log")
  } finally {
    $env:ANDROID_SERIAL = $previousSerial
    # Sul dispositivo non resta niente della misura: AGP disinstalla da solo la variante `.bench` e l'APK di test alla fine
    # delle prove, ma non se Gradle si interrompe a meta'. Solo questi due id, mai la debug ne' la release.
    foreach ($leftover in @("dev.pampa.mycloudgallery.bench", "dev.pampa.mycloudgallery.benchmark")) {
      $listed = Invoke-Native -File $benchAdb -Arguments @("-s", $benchTarget, "shell", "pm", "list", "packages", $leftover)
      if (@($listed.Lines | Where-Object { $_.Trim() -eq "package:$leftover" }).Count -gt 0) {
        [void](Invoke-Native -File $benchAdb -Arguments @("-s", $benchTarget, "uninstall", $leftover))
        Write-Host "        disinstallato $leftover (era rimasto sul dispositivo)" -ForegroundColor DarkGray
      }
    }
  }
  if ($run.Code -ne 0) {
    Show-Lines $run.Lines 60
    $hint = "vedi build\check-bench.log"
    if (@($run.Lines | Where-Object { $_ -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' }).Count -gt 0) {
      $hint = "la release firmata e' installata con un'altra firma: disinstallarla prima (docs/bench/README.md)"
    }
    if (@($run.Lines | Where-Object { $_ -match 'INSTALL_FAILED_NO_MATCHING_ABIS' }).Count -gt 0) {
      $hint = "la benchmarkRelease porta solo arm64-v8a (Fase 6, P11, D3): sugli emulatori x86_64 si tiene anche x86_64 con -PbenchKeepX86=true (lo fa -Bench da solo sugli emulatori, Fase 9, P12); su un telefono vero e' un altro problema"
    }
    return "il benchmark e' fallito (codice $($run.Code)): $hint"
  }
  $compare = Join-Path $root "tools\bench\compare.ps1"
  $compareArguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $compare, "-RefreshHz", ([string]$hz).Replace(",", "."))
  if ($facts.Emulator) { $compareArguments += "-Smoke" }
  if ($BenchStrict) { $compareArguments += "-RequireAll" }
  $result = Invoke-Native -File "powershell" -Arguments $compareArguments -LogPath (Join-Path $root "build\check-bench-compare.log")
  Show-Lines $result.Lines
  if ($result.Code -ne 0) { return "i risultati non rispettano le soglie (codice $($result.Code)); dettaglio sopra e in build\check-bench-compare.log" }
}

# --- Riepilogo ---------------------------------------------------------------------------------------------
Write-Host ""
Write-Host "Riepilogo" -ForegroundColor Cyan
foreach ($result in $script:results) {
  $color = switch ($result.Status) { "OK" { "Green" } "FAIL" { "Red" } default { "DarkGray" } }
  Write-Host ("  {0,-4}  {1}" -f $result.Status, $result.Name) -ForegroundColor $color
  if ($result.Status -eq "FAIL") { Write-Host ("        {0}" -f $result.Detail) -ForegroundColor Red }
}
Write-Host ""
if ($script:failed) {
  Write-Host "FAIL: c'e' almeno un controllo che non passa." -ForegroundColor Red
  exit 1
}
Write-Host "OK: tutto a posto." -ForegroundColor Green
exit 0
