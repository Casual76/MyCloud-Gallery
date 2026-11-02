<#
.SYNOPSIS
  Prove di share-snapshot.ps1 su cartelle FINTE sotto build\prova-share\ (Fase 10 P10). Mai sulle share del NAS.

.DESCRIPTION
  Costruisce due radici finte (privata e comune), fa la fotografia "prima", applica i cambiamenti di uno scenario, fa la
  fotografia "dopo" e confronta. Prova: nessuna scrittura nelle radici (impronta dell'albero uguale prima e dopo), il passo "tutto
  dentro" (codice 0, con le sorgenti di un album spostate), i file aggiunti, cambiati e tolti fuori dalle cartelle attese (codice 3),
  un backup che cambia un file (codice 3), conteggi degli album diversi (codice 3), le uscite dentro una radice o gia' esistenti
  (codice 1). Codice di uscita 0 solo se tutte le prove passano. Lo lancia anche tools\check.ps1.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$tool = Join-Path $PSScriptRoot "share-snapshot.ps1"
$work = Join-Path $root "build\prova-share"
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Path $work | Out-Null
$failures = New-Object System.Collections.Generic.List[string]
$count = 0

function Put-File([string]$base, [string]$rel, [int]$size, [int]$age = 3600) {
  $path = Join-Path $base ($rel.Replace("/", "\"))
  $dir = Split-Path -Parent $path
  if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
  [System.IO.File]::WriteAllBytes($path, (New-Object byte[] $size))
  (Get-Item -LiteralPath $path).LastWriteTime = (Get-Date).AddSeconds(-$age)
}

function Invoke-Tool([string[]]$arguments) {
  $lines = @(& powershell -NoProfile -ExecutionPolicy Bypass -File $tool @arguments 2>&1 | ForEach-Object { $_.ToString() })
  return [pscustomobject]@{ Code = $LASTEXITCODE; Text = ($lines -join "`n") }
}

function Assert-That([string]$name, [bool]$condition, [string]$detail = "") {
  $script:count++
  if (-not $condition) { $script:failures.Add($name + $(if ($detail) { ": " + $detail } else { "" })); Write-Host ("  [KO] " + $name + $(if ($detail) { "`n" + $detail.Substring(0, [Math]::Min(700, $detail.Length)) } else { "" })) -ForegroundColor Red }
  else { Write-Host ("  [ok] " + $name) -ForegroundColor DarkGray }
}

# Nomi, dimensioni e contenuto (non le date delle cartelle: NTFS le aggiorna con ritardo nell'elenco del genitore).
function Get-TreeItems([string]$path) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  $items = Get-ChildItem -LiteralPath $path -Recurse -Force | ForEach-Object {
    if ($_.PSIsContainer) { "D|" + $_.FullName.Substring($path.Length) }
    else { "F|{0}|{1}|{2}" -f $_.FullName.Substring($path.Length), $_.Length, [System.BitConverter]::ToString($sha.ComputeHash([System.IO.File]::ReadAllBytes($_.FullName))) }
  }
  return (($items | Sort-Object) -join "`n")
}

function Get-TreeHash([string]$path) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  return [System.BitConverter]::ToString($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes((Get-TreeItems $path))))
}

# La base: due radici finte con i file di una share "come oggi".
$base = Join-Path $work "base"
Put-File $base "privata/Foto/IMG_001.jpg" 100
Put-File $base "privata/Foto/IMG_002.jpg" 200
Put-File $base "privata/Documenti/a.txt" 10
Put-File $base "privata/.mycloudgallery/devices/dev1/index/head/1" 50
Put-File $base "privata/Thumbs.db" 5
Put-File $base "comune/Condivisa/x.jpg" 300
Put-File $base "comune/.mycloudgallery/family/registry/1" 40
Put-File $base "comune/@eaDir/miniatura" 7

function New-Scenario([string]$name, [scriptblock]$mutate) {
  $dir = Join-Path $work $name
  Copy-Item -LiteralPath $base -Destination $dir -Recurse
  & $mutate (Join-Path $dir "privata") (Join-Path $dir "comune")
  return $dir
}

# 1. La fotografia "prima" non scrive niente nelle radici, rifiuta un'uscita dentro una radice o gia' esistente.
Write-Host "Fotografia e sola lettura"
$before = Get-TreeHash $base
$beforeItems = Get-TreeItems $base
$prima = Join-Path $work "prima.snap"
$r = Invoke-Tool @("-Modo", "Foto", "-Privata", (Join-Path $base "privata"), "-Comune", (Join-Path $base "comune"), "-Uscita", $prima)
Assert-That "foto prima: codice 0" ($r.Code -eq 0) $r.Text
Assert-That "foto prima: file scritto, sei file letti (Thumbs.db e @eaDir ignorati)" ((Test-Path -LiteralPath $prima) -and $r.Text -match "Radice privata: 4 file" -and $r.Text -match "Radice comune: 2 file") $r.Text
Assert-That "foto prima: le radici sono identiche" ((Get-TreeHash $base) -eq $before) ("before=[$before] now=[" + (Get-TreeHash $base) + "] " + (Compare-Object ($beforeItems -split "`n") ((Get-TreeItems $base) -split "`n") | Out-String))
$inside = Invoke-Tool @("-Modo", "Foto", "-Privata", (Join-Path $base "privata"), "-Comune", (Join-Path $base "comune"), "-Uscita", (Join-Path $base "privata\foto.snap"))
Assert-That "uscita dentro una radice: rifiutata (codice 1, niente file)" ($inside.Code -eq 1 -and -not (Test-Path -LiteralPath (Join-Path $base "privata\foto.snap")))
$again = Invoke-Tool @("-Modo", "Foto", "-Privata", (Join-Path $base "privata"), "-Comune", (Join-Path $base "comune"), "-Uscita", $prima)
Assert-That "uscita gia' esistente: rifiutata (codice 1)" ($again.Code -eq 1)
$missing = Invoke-Tool @("-Modo", "Foto", "-Privata", (Join-Path $base "manca"), "-Comune", (Join-Path $base "comune"), "-Uscita", (Join-Path $work "x.snap"))
Assert-That "radice che non esiste: rifiutata (codice 1)" ($missing.Code -eq 1)
Assert-That "foto prima: avvisa che contiene i nomi dei file privati e dice dove tenerla e quando cancellarla (I6)" ($r.Text -match "AVVISO \(riservato\)" -and $r.Text -match "NOMI" -and $r.Text -match "SOLO su questo PC" -and $r.Text -match "cancellalo" -and $r.Text -match "7 giorni") $r.Text

# 1b. I6: l'uscita solo su un disco fisso di questo PC, e si guarda il percorso VERO, non il testo.
Write-Host "Uscita: solo su un disco locale, percorso vero"
$rootsArgs = @("-Privata", (Join-Path $base "privata"), "-Comune", (Join-Path $base "comune"))
$watch = [System.Diagnostics.Stopwatch]::StartNew()
$unc = Invoke-Tool (@("-Modo", "Foto") + $rootsArgs + @("-Uscita", "\\127.0.0.1\mcg-prova\x.snap"))
$uncSlash = Invoke-Tool (@("-Modo", "Foto") + $rootsArgs + @("-Uscita", "//127.0.0.1/mcg-prova/x.snap"))
$watch.Stop()
Assert-That "uscita su un percorso UNC (anche con le barre dritte): rifiutata (codice 1), subito e senza toccare la rete" ($unc.Code -eq 1 -and $unc.Text -match "UNC" -and $uncSlash.Code -eq 1 -and $uncSlash.Text -match "UNC" -and $watch.Elapsed.TotalSeconds -lt 20) ($unc.Text + "`n" + $uncSlash.Text + " (" + $watch.Elapsed.TotalSeconds + " s)")
$noDir = Invoke-Tool (@("-Modo", "Foto") + $rootsArgs + @("-Uscita", (Join-Path $work "manca\x.snap")))
Assert-That "uscita in una cartella che non esiste: rifiutata (codice 1)" ($noDir.Code -eq 1 -and $noDir.Text -match "non esiste") $noDir.Text
$junction = Join-Path $work "giunzione"
New-Item -ItemType Junction -Path $junction -Target (Join-Path $base "privata") | Out-Null
try {
  $viaJunction = Invoke-Tool (@("-Modo", "Foto") + $rootsArgs + @("-Uscita", (Join-Path $junction "foto.snap")))
} finally {
  [System.IO.Directory]::Delete($junction)   # solo il collegamento: Remove-Item -Recurse lo seguirebbe
}
Assert-That "uscita dentro una radice attraverso una giunzione (il testo non lo dice): rifiutata (codice 1), niente file nella radice" ($viaJunction.Code -eq 1 -and $viaJunction.Text -match "dentro una radice" -and -not (Test-Path -LiteralPath (Join-Path $base "privata\foto.snap"))) $viaJunction.Text
Assert-That "la giunzione e' stata tolta e la radice e' ancora intera" (-not (Test-Path -LiteralPath $junction) -and (Test-Path -LiteralPath (Join-Path $base "privata\Foto\IMG_001.jpg")))
$letter = @("Z", "Y", "X", "W", "V") | Where-Object { -not (Test-Path -LiteralPath ($_ + ":\")) } | Select-Object -First 1
$substOk = $false
if ($letter) {
  & subst ($letter + ":") (Join-Path $base "privata") | Out-Null
  $substOk = ($LASTEXITCODE -eq 0)
}
if ($substOk) {
  try {
    $viaSubst = Invoke-Tool (@("-Modo", "Foto") + $rootsArgs + @("-Uscita", ($letter + ":\foto.snap")))
  } finally {
    & subst ($letter + ":") /d | Out-Null
  }
  Assert-That "uscita su un'unita' assegnata con subst alla radice: rifiutata (codice 1), niente file nella radice" ($viaSubst.Code -eq 1 -and $viaSubst.Text -match "dentro una radice" -and -not (Test-Path -LiteralPath (Join-Path $base "privata\foto.snap"))) $viaSubst.Text
} else {
  Write-Host "  [--] subst non disponibile o nessuna lettera libera: prova dell'unita' assegnata saltata" -ForegroundColor DarkGray
}
$prima0 = Join-Path $work "prima0.snap"
$ok0 = Invoke-Tool (@("-Modo", "Foto") + $rootsArgs + @("-Uscita", $prima0))
Assert-That "uscita su un disco locale fuori dalle radici: accettata (codice 0)" ($ok0.Code -eq 0 -and (Test-Path -LiteralPath $prima0)) $ok0.Text
$repUnc = Invoke-Tool @("-Modo", "Confronta", "-Prima", $prima0, "-Dopo", $prima0, "-Report", "\\127.0.0.1\mcg-prova\r.json")
$repIn = Invoke-Tool @("-Modo", "Confronta", "-Prima", $prima0, "-Dopo", $prima0, "-Report", (Join-Path $base "privata\r.json"))
$repOk = Invoke-Tool @("-Modo", "Confronta", "-Prima", $prima0, "-Dopo", $prima0, "-Report", (Join-Path $work "r0.json"))
Assert-That "il resoconto ha le stesse regole: UNC e dentro una radice rifiutati, su disco locale accettato con l'avviso" ($repUnc.Code -eq 1 -and $repUnc.Text -match "UNC" -and $repIn.Code -eq 1 -and $repIn.Text -match "dentro una radice" -and -not (Test-Path -LiteralPath (Join-Path $base "privata\r.json")) -and $repOk.Code -eq 0 -and $repOk.Text -match "AVVISO \(riservato\)") ($repUnc.Text + "`n" + $repIn.Text + "`n" + $repOk.Text)

function Invoke-Scenario([string]$name, [scriptblock]$mutate) {
  $dir = New-Scenario $name $mutate
  $dopo = Join-Path $work ($name + ".snap")
  $f = Invoke-Tool @("-Modo", "Foto", "-Privata", (Join-Path $dir "privata"), "-Comune", (Join-Path $dir "comune"), "-Uscita", $dopo)
  Assert-That "$name : foto dopo scritta" ($f.Code -eq 0) $f.Text
  $hash = Get-TreeHash $dir
  $report = Join-Path $work ($name + ".json")
  $c = Invoke-Tool @("-Modo", "Confronta", "-Prima", $prima, "-Dopo", $dopo, "-Report", $report)
  Assert-That "$name : il confronto non scrive nelle radici" ((Get-TreeHash $dir) -eq $hash)
  return $c
}

# 2. Il passo "tutto dentro": backup, indice, volti, album e le sorgenti spostate (una tolta da Foto/, una copia nell'album, una nel cestino).
Write-Host "Tutto dentro le cartelle attese"
$ok = Invoke-Scenario "tutto-dentro" {
  param($p, $c)
  Put-File $p "Backup/Pixel/2026/10/IMG_9.jpg" 111
  Put-File $p ".mycloudgallery/devices/dev1/index/seg/aa" 70
  Put-File $p ".mycloudgallery/faces/g1/dev1/head/1" 30
  Put-File $p ".mycloudgallery/trash/dev1/files/t1" 100
  Put-File $c ".mycloudgallery/albums/al/dev1/head/1" 20
  Put-File $c "Album condivisi/Viaggio/s1.jpg" 100
  Remove-Item -LiteralPath (Join-Path $p "Foto\IMG_001.jpg")
}
Assert-That "tutto dentro: codice 0" ($ok.Code -eq 0) $ok.Text
Assert-That "tutto dentro: una sorgente spostata e nessun problema" ($ok.Text -match "spostati \(sorgenti degli album\): 1" -and $ok.Text -match "Tutto dentro") $ok.Text
Assert-That "tutto dentro: le quattro aree hanno i loro numeri" ($ok.Text -match "backup\s+aggiunti 1" -and $ok.Text -match "volti\s+aggiunti 1" -and $ok.Text -match "album\s+aggiunti 3" -and $ok.Text -match "indice\s+aggiunti 1") $ok.Text

# 3. File aggiunti, cambiati e tolti fuori dalle cartelle attese (E-SV2): codice 3 e i percorsi nel resoconto.
Write-Host "Fuori dalle cartelle attese"
$bad = Invoke-Scenario "fuori" {
  param($p, $c)
  Put-File $p "Documenti/nuovo.txt" 33
  Put-File $p "Foto/IMG_002.jpg" 250
  Remove-Item -LiteralPath (Join-Path $c "Condivisa\x.jpg")
}
Assert-That "fuori: codice 3" ($bad.Code -eq 3) $bad.Text
Assert-That "fuori: aggiunto, cambiato e tolto nominati" ($bad.Text -match "Documenti/nuovo\.txt" -and $bad.Text -match "Foto/IMG_002\.jpg" -and $bad.Text -match "Condivisa/x\.jpg" -and $bad.Text -match "DA FERMARE") $bad.Text
$jsonPath = Join-Path $work "fuori.json"
$json = if (Test-Path -LiteralPath $jsonPath) { Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json } else { $null }
Assert-That "fuori: il resoconto JSON ha i tre elenchi" ($json -and @($json.fuori.aggiunti).Count -eq 1 -and @($json.fuori.cambiati).Count -eq 1 -and @($json.fuori.tolti).Count -eq 1)

# 4. Il backup puo' solo aggiungere: un file cambiato dentro Backup/ e' un'anomalia.
Write-Host "Il backup aggiunge soltanto"
$bkDir = Join-Path $work "backup-sovrascrive"
Copy-Item -LiteralPath $base -Destination $bkDir -Recurse
Put-File (Join-Path $bkDir "privata") "Backup/Pixel/2026/10/IMG_1.jpg" 100
$bkPrima = Join-Path $work "backup-prima.snap"
$bkDopo = Join-Path $work "backup-dopo.snap"
$f = Invoke-Tool @("-Modo", "Foto", "-Privata", (Join-Path $bkDir "privata"), "-Comune", (Join-Path $bkDir "comune"), "-Uscita", $bkPrima)
Put-File (Join-Path $bkDir "privata") "Backup/Pixel/2026/10/IMG_1.jpg" 160
$f = Invoke-Tool @("-Modo", "Foto", "-Privata", (Join-Path $bkDir "privata"), "-Comune", (Join-Path $bkDir "comune"), "-Uscita", $bkDopo)
$c = Invoke-Tool @("-Modo", "Confronta", "-Prima", $bkPrima, "-Dopo", $bkDopo)
Assert-That "un file del backup cambiato: codice 3 (si puo' solo aggiungere)" ($c.Code -eq 3 -and $c.Text -match "aggiunge soltanto|solo aggiungere") $c.Text

# 5. Album: le sorgenti tolte devono essere tante quante le copie aggiunte (stessa dimensione).
Write-Host "Album: conteggi uguali"
$al = Invoke-Scenario "album-conti" {
  param($p, $c)
  Put-File $c "Album condivisi/Viaggio/s1.jpg" 100
  Put-File $c "Album condivisi/Viaggio/s2.jpg" 120
  Remove-Item -LiteralPath (Join-Path $p "Foto\IMG_001.jpg")
}
Assert-That "album con due copie e una sola sorgente tolta: codice 3, conteggi diversi" ($al.Code -eq 3 -and $al.Text -match "conteggi diversi") $al.Text

# 6. Fotografie con aree diverse non si confrontano.
Write-Host "Fotografie e aree"
$other = Join-Path $work "aree-diverse.json"
$cfg = Get-Content -LiteralPath (Join-Path $PSScriptRoot "recinzioni.json") -Raw | ConvertFrom-Json
$cfg.aree[0].cartelleAttese.privata = @(".mycloudgallery/altro")
[System.IO.File]::WriteAllText($other, ($cfg | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
$diverse = Join-Path $work "diverse.snap"
$f = Invoke-Tool @("-Modo", "Foto", "-Privata", (Join-Path $base "privata"), "-Comune", (Join-Path $base "comune"), "-Uscita", $diverse, "-Aree", $other)
$c = Invoke-Tool @("-Modo", "Confronta", "-Prima", $prima, "-Dopo", $diverse)
Assert-That "aree diverse: confronto rifiutato (codice 1)" ($c.Code -eq 1 -and $c.Text -match "aree diverse") $c.Text

Assert-That "le radici di base non sono mai cambiate" ((Get-TreeHash $base) -eq $before)

if ($failures.Count -gt 0) {
  Write-Host ("share-snapshot: {0} prove, {1} fallite" -f $count, $failures.Count) -ForegroundColor Red
  foreach ($f in $failures) { Write-Host ("  - " + $f) -ForegroundColor Red }
  exit 1
}
Write-Host ("share-snapshot: {0} prove, tutte passate (cartelle finte sotto build\prova-share)" -f $count) -ForegroundColor Green
exit 0
