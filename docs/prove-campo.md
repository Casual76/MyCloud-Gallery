# Prove sul campo di MyCloud Gallery (Fasi 4A, 4B, 5A, 5B)

| | |
|---|---|
| **Stato** | Copione del 20/10/2026, scritto sul commit `f705a6f`. Nessuna prova è ancora stata eseguita: ogni riga della tabella finale (§7) è "da fare". |
| **Chi esegue** | L'orchestratore (un'altra istanza di Claude) sull'S25 `RFCY61FZDHW`, collegato al PC con `adb`. Dove serve un gesto fisico o un'altra app, lo fa l'utente, guidato da istruzioni brevi. |
| **Cosa si prova** | F1–F20 (Fasi 4A e 4B, `docs/fase4-progetto.md` §5.7: il brief dice §5.6, ma lì c'è il benchmark), F21–F45 (Fasi 5A e 5B, `docs/fase5-progetto.md` §5.6), T-09, T-11, T-14, T-15, T-16 (`docs/android.md`), i punti "Da provare sul campo" delle sei revisioni di `docs/review/` e di `docs/decisioni.md`, i "Flussi provati solo con fake" delle due verifiche funzionali, le prove con due dispositivi di `docs/famiglia-dispositivi.md` §9. |
| **Tre blocchi** | **A** le fa l'orchestratore da solo (nostra app, `adb`, script). **B** servono l'utente (altre app, gesti fisici, dialoghi di sistema, il PC con Esplora risorse). **C** servono un altro dispositivo, l'APK di release, il tempo, o una decisione. |
| **Primo tentativo** | Il 20/10 tra le 17:30 e le 17:55 una sessione ha provato il blocco A senza questo copione (esiti in `docs/prove-campo-esiti.md`) e si è **fermata quasi subito**: l'app di debug sul telefono era configurata con l'account vero `alessio`, non con `test` (§2.3 e §8, punto 1). Ha lasciato `mcg-prova` seminata (308 file) e 5 foto nel Cestino di Samsung Gallery. Ha già fatto T-09 (d, e, scrittura), F39 da `adb` e le impronte del telefono: i loro esiti sono usati qui sotto. |
| **Durata** | Blocco A circa **725 minuti** (12 ore, di cui 5 ore e mezza di prove lunghe sul PC, da fare di notte). Blocco B circa **227 minuti dell'utente** (3 ore e 45 in 18 sessioni), più 118 minuti dell'orchestratore da solo mentre l'utente non c'è. La preparazione (35 minuti) non è contata. Vedi §3 per l'ordine e per quanto le prove si sovrappongono. |

## 0. Come leggere questo file

**Sigle dei difetti** (colonna "difetti da cercare" della tabella finale e righe "Può far emergere"):

| Sigla | Dove sta |
|---|---|
| `4a-R3`, `4b-R8`, `5a-R1`, `5b-R3` | revisioni di sicurezza (`docs/review/4a-fase4a.md`, `4b-fase4b.md`, `5a-fase5a.md`, `5b-fase5b.md`), voce R.. o I.. |
| `ON-`, `RE-`, `GA-`, `NA-`, `BK-` | `docs/review/funzionale-fasi-1-5a.md` (onboarding, rete, galleria, NAS, backup 5A) |
| `VE-`, `MC-`, `LS-`, `IN-`, `LA-` | `docs/review/funzionale-5b.md` (versioni, modifica/condividi, libera spazio, integrazioni, lavori) |
| `FF-n` | flusso n della sezione 4 di `funzionale-fasi-1-5a.md` ("Flussi provati solo con fake") |
| `FB-n` | flusso n della sezione 4 di `funzionale-5b.md` |
| `E-..` | edge case del piano (`docs/fase4-progetto.md`, `docs/fase5-progetto.md`) |

Quasi tutti i difetti elencati sono già "corretti" nel codice: la prova sul campo serve a vedere che la correzione regga sul telefono vero.

**Come è fatta ogni prova**: id, scopo in una riga, cosa serve prima, passi, cosa si guarda e come lo si verifica dal PC, esito atteso.

**Parole**:
- "la nostra app" = `dev.pampa.mycloudgallery.debug`, la build di debug con l'account di prova `test`;
- "giro" = il nome di questa sessione di prove (`$giro`), forma `aaaammgg-hhmmss-<8 esadecimali>`;
- "orchestratore" = chi esegue questo copione; "utente" = Alessio.

## 1. Regole fisse (valgono per ogni passo)

1. **Telefono.** Si usa solo la nostra app (`dev.pampa.mycloudgallery.debug`; l'APK di release solo se serve, e oggi non è installabile: vedi §6, C-9). Mai toccare altre app. Samsung Gallery, Gmail, WhatsApp, Telegram, Chrome, Google Foto, OneDrive, "I miei file" e i **dialoghi di sistema** (cestino, scrittura, permessi) li usa **l'utente**. `am start` di un **nostro** componente da `adb` è permesso.
2. **Impostazioni del telefono.** Non si modificano. Niente `settings put`, `pm grant`, `pm revoke`, `svc` e simili. **Unica eccezione**: spegnere e riaccendere il Wi-Fi con `tools/chaos/s25.ps1`.
3. **Password.** Mai digitate. L'accesso all'app lo fa l'utente. L'app di debug dovrebbe essere già configurata con l'account di prova `test`: il 20/10 non lo era (§2.3), quindi si ricontrolla prima di tutto (§2.5).
4. **`ANDROID_SERIAL`** sempre `RFCY61FZDHW`: ogni chiamata di shell parte da zero, quindi ogni blocco di comandi comincia caricando `build\campo\h.ps1` (§2.1), che lo imposta. Gli script del repo si lanciano con `powershell -ExecutionPolicy Bypass -File ...` (processo figlio: `area.ps1` mette `ANDROID_SERIAL=none` per le sue prove da PC e non deve contagiare la shell).
5. **Dove si scrive.**
   - Il backup scrive solo in `test/mcg-campo/` (D13).
   - Le prove dal PC scrivono solo sotto `mcg-it/<giro>/` delle share di prova (`test`, `test2`, `test-comune`), dietro la guardia. Quello che nel piano è "dal PC" dentro `mcg-campo/` (rinominare, cancellare, mettere un file) **lo fa l'utente** in Esplora risorse, non l'orchestratore.
   - Se un login fallisce con `AUTH_FAILED` (Gradle, o l'app che dice "Serve la password del NAS"): **ci si ferma subito**, niente nuovi tentativi, si avvisa l'utente.
   - Se l'account dell'app **non è `test` (o `test2`)**: la recinzione del telefono (D13) è spenta e il backup, "Libera spazio", "Elimina solo dal telefono", le correzioni e le versioni agirebbero sulle foto vere. In quel caso non si fa **nessuna** prova che scriva o cancelli, e si avvisa l'utente (è successo il 20/10).
6. **Dati di prova.** Si mettono con `s25.ps1 -Mode backup -Step seed` e alla fine si tolgono con `-Step clean -Yes`. Nient'altro si crea a mano in `mcg-prova` (le prove che lo vorrebbero stanno in §6, C-18).
7. **Schermo.** Le schermate di configurazione hanno `FLAG_SECURE`: gli screenshot sono neri. Per leggere lo schermo si usa `uiautomator dump` (funzione `Ui-Texts`). La Libreria e il viewer si possono fotografare.
8. **Git.** Nessun `commit`, nessun `git add`. Non si legge nulla in `%USERPROFILE%\.mycloudgallery\` (si controlla solo con `Test-Path` che il file delle credenziali esista).

**Regole in più di questo copione** (prudenza, non vengono dal piano):
- mai `adb uninstall`, `pm clear`, `adb shell reboot`: cancellerebbero la configurazione (chiave del dispositivo, famiglia, account) e servirebbero le password; il riavvio, se una prova lo chiede, lo fa l'utente (C-19);
- `input tap`, `input keyevent` e `uiautomator dump` solo se in primo piano c'è la nostra app (`Assert-Ours`); se compare un dialogo di sistema o un'altra app, ci si ferma e si chiede all'utente;
- l'orchestratore non cancella niente dal NAS: lo fanno l'app (con i suoi cestini) o l'utente;
- Gradle una sola volta alla volta, e solo quando chi sta compilando ha finito; le prove sul NAS vero (`area.ps1`, la suite di `core-nas`) presuppongono di essere le sole a girare sulle share di prova.

## 2. Preparazione

### 2.1 Ambiente e funzioni di appoggio

Una volta sola, all'inizio:

```powershell
$repo = "C:\VibeCoded Projects\MyCloud-Gallery-2"
New-Item -ItemType Directory -Force "$repo\build\campo" | Out-Null
$giro = "{0}-{1:x8}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), (Get-Random -Minimum 0 -Maximum ([int]::MaxValue))
Set-Content -LiteralPath "$repo\build\campo\giro.txt" -Value $giro
New-Item -ItemType Directory -Force "$repo\build\campo\$giro" | Out-Null
```

Poi si scrive `build\campo\h.ps1` (la cartella `build\` è ignorata da git) con il contenuto qui sotto e **a ogni chiamata di shell** si comincia con `. "C:\VibeCoded Projects\MyCloud-Gallery-2\build\campo\h.ps1"`. Sono funzioni scritte per questo copione (non stanno negli script del repo): al primo uso provarle e, se una non va, correggerla senza uscire dalle regole.

```powershell
# build\campo\h.ps1
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:ANDROID_SERIAL = "RFCY61FZDHW"
$repo = "C:\VibeCoded Projects\MyCloud-Gallery-2"
Set-Location -LiteralPath $repo
$pkg = "dev.pampa.mycloudgallery.debug"
$giro = (Get-Content -LiteralPath "$repo\build\campo\giro.txt").Trim()
$out = "$repo\build\campo\$giro"
$manifest = "$repo\build\chaos\backup-manifest-$giro.txt"
$sdk = (Select-String -LiteralPath "$repo\local.properties" -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1).Matches[0].Groups[1].Value.Trim().Replace('\:', ':').Replace('\\', '\')
$adb = Join-Path $sdk "platform-tools\adb.exe"

function A { & $adb @args }                                  # adb sul solo telefono di ANDROID_SERIAL

# --- Primo piano e schermo (si legge e si tocca solo la nostra app) ---
function Focus { ((A shell dumpsys window) | Select-String 'mCurrentFocus') -join ' ' }
function Assert-Ours { $f = Focus; if ($f -notmatch [regex]::Escape($pkg)) { throw "In primo piano non c'e' la nostra app: $f" } }
function Get-Ui {
  $raw = (A exec-out uiautomator dump /dev/tty) -join "`n"
  $m = [regex]::Match($raw, '<\?xml.*?</hierarchy>', 'Singleline')
  if (-not $m.Success) { throw "uiautomator non ha dato niente (schermo spento o bloccato?)" }
  [xml]$m.Value
}
function Ui-Texts { Assert-Ours; foreach ($n in (Get-Ui).SelectNodes('//node')) { $t = $n.GetAttribute('text'); if (-not $t) { $t = $n.GetAttribute('content-desc') }; if ($t) { $t } } }
function Tap-Text([string]$text, [switch]$Like) {
  Assert-Ours
  $node = (Get-Ui).SelectNodes('//node') | Where-Object {
    $t = $_.GetAttribute('text'); $d = $_.GetAttribute('content-desc')
    if ($Like) { ($t -like "*$text*") -or ($d -like "*$text*") } else { ($t -eq $text) -or ($d -eq $text) }
  } | Select-Object -First 1
  if (-not $node) { throw "Non trovo '$text' sullo schermo" }
  if ($node.GetAttribute('bounds') -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    A shell input tap ([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) ([int](([int]$Matches[2] + [int]$Matches[4]) / 2))
  } else { throw "bounds illeggibili per '$text'" }
}
function Key([string]$k) { Assert-Ours; A shell input keyevent $k }       # KEYCODE_BACK, KEYCODE_HOME, ...
function Start-Ours { A shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null }
function Wait-Text([string]$text, [int]$timeoutSec = 600, [switch]$Like) {
  $end = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $end) {
    $hit = Ui-Texts | Where-Object { if ($Like) { $_ -like "*$text*" } else { $_ -eq $text } }
    if ($hit) { return $true }
    Start-Sleep -Seconds 15
  }
  return $false
}
function Shot([string]$name) {                                            # screenshot (nero sulle schermate FLAG_SECURE)
  $psi = New-Object System.Diagnostics.ProcessStartInfo($adb, "-s $($env:ANDROID_SERIAL) exec-out screencap -p")
  $psi.RedirectStandardOutput = $true; $psi.UseShellExecute = $false
  $p = [System.Diagnostics.Process]::Start($psi)
  $fs = [System.IO.File]::Create("$out\$name.png"); $p.StandardOutput.BaseStream.CopyTo($fs); $fs.Close(); $p.WaitForExit()
}

# --- Log, rete, registro degli esiti ---
function Log-Clear { A logcat -c }
function Log-Save([string]$name) { (A logcat -d -v time) | Select-String 'MyCloud/' | ForEach-Object { $_.Line } | Set-Content -LiteralPath "$out\$name.log" -Encoding UTF8 }
function Net { $l = ((A shell cat /proc/net/dev) | Select-String 'wlan0:').Line.Trim() -split '[:\s]+'; @{ rx = [int64]$l[1]; tx = [int64]$l[9] } }
function Net-Phase([int]$sec = 5) {                                       # carico (tx alto), verifica (rx alto) o fermo
  $a = Net; Start-Sleep -Seconds $sec; $b = Net
  $tx = [math]::Round(($b.tx - $a.tx) / $sec / 1MB, 1); $rx = [math]::Round(($b.rx - $a.rx) / $sec / 1MB, 1)
  [pscustomobject]@{ tx_MBs = $tx; rx_MBs = $rx; fase = $(if ($tx -gt 5) { "carico" } elseif ($rx -gt 5) { "verifica" } else { "fermo o file piccoli" }) }
}
function Esito([string]$id, [string]$esito, [string]$nota) {              # esito: OK | KO | NOTA | SALTATA
  ("{0} | {1} | {2} | {3}" -f $id, $esito, (Get-Date -Format s), $nota) | Add-Content -LiteralPath "$out\esiti.txt" -Encoding UTF8
}

# --- Il manifesto di mcg-prova (stesso formato di s25.ps1 e di BackupAreaManifest) ---
function Phone-Path([string]$label) { (A shell find /sdcard/DCIM/mcg-prova /sdcard/Pictures/mcg-prova -name $label) | Select-Object -First 1 }
function Sha-Phone([string]$p) { if ($p -notmatch '^/sdcard/(DCIM|Pictures)/mcg-prova/[^/]+$') { throw "fuori da mcg-prova: $p" }; ((A shell sha256sum $p) -split '\s+')[0] }
function Add-Phone([string]$p) {                                          # un file NUOVO comparso in mcg-prova (es. "Salva copia")
  $size = ((A shell stat -c %s $p) | Select-Object -First 1).Trim()
  Add-Content -LiteralPath $manifest -Value ("phone={0}|{1}|{2}" -f (Sha-Phone $p), $size, $p.Split('/')[-1]) -Encoding ASCII
}
function Add-Edit([string]$p, [string]$oldSha) { Add-Content -LiteralPath $manifest -Value ("edit={0}|{1}|{2}" -f $oldSha, (Sha-Phone $p), $p.Split('/')[-1]) -Encoding ASCII }
function Add-Pre([string]$sha, [string]$sharePath) { Add-Content -LiteralPath $manifest -Value ("pre={0}|test|{1}" -f $sha, $sharePath) -Encoding ASCII }
function Drop-Phone([string]$label) {                                     # un file cancellato davvero dal telefono: via dal manifesto
  (Get-Content -LiteralPath $manifest) | Where-Object { $_ -notmatch ('^phone=[0-9a-f]{64}\|\d+\|' + [regex]::Escape($label) + '$') } | Set-Content -LiteralPath $manifest -Encoding ASCII
}
function Old-Sha([string]$label) { ((Get-Content -LiteralPath $manifest) | Where-Object { $_ -match ('^phone=([0-9a-f]{64})\|\d+\|' + [regex]::Escape($label) + '$') } | ForEach-Object { $Matches[1] }) | Select-Object -First 1 }
```

Ricette che tornano spesso (tutte con `s25.ps1`, l'unico modo ammesso per toccare il Wi-Fi):

```powershell
# TAGLIO-WIFI <s>: un solo spegnimento del Wi-Fi di <s> secondi (da 1 a 120), poi acceso. Alla fine rilancia l'app.
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step interrupt -Serial RFCY61FZDHW -Interruptions 1 -WifiPercent 100 -MinGapSeconds 1 -MaxGapSeconds 1 -WifiOffMinSeconds <s> -WifiOffMaxSeconds <s> -SettleSeconds 30

# TAGLIO-MORTE <modo>: una sola morte del processo (modo: run-as = kill -9, am-kill, force-stop, crash). Poi rilancia l'app.
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step interrupt -Serial RFCY61FZDHW -Interruptions 1 -WifiPercent 0 -MinGapSeconds 1 -MaxGapSeconds 1 -KillMode <modo> -SettleSeconds 30

# VERIFICA-NAS: solo il NAS, in sola lettura (il manifesto deve descrivere il telefono di adesso)
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage verify -Manifest $manifest [-NotSettled]

# VERIFICA-COMPLETA: impronte del telefono + NAS (dura circa 15 minuti con 9 GB: rilegge tutto senza cache)
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step verify -Serial RFCY61FZDHW -Manifest $manifest [-AllowFreed]
```

```powershell
# ESPORTA: i numeri della Diagnostica NON si leggono dallo schermo. Le voci mostrano solo titolo e codice, e l'anteprima di
# "Esporta la diagnostica" e' un testo unico che uiautomator non rende (O-4 del 20/10). Si salva il file: Diagnostica -> Esporta -> Salva.
# Il dialogo di sistema lo conferma l'UTENTE (30 secondi, cartella Download). Poi, solo quel file:
A shell "ls -t /sdcard/Download | head -3"                                         # il file si chiama mycloudgallery-diagnostica.json
A pull /sdcard/Download/mycloudgallery-diagnostica.json "$out\diag-<quando>.json"

# NET-LOG: il traffico del Wi-Fi ogni 2 secondi in $out\net.csv (tempi e byte del primo backup, dei video, delle riprese)
$netjob = Start-Job -ArgumentList $adb, $env:ANDROID_SERIAL, "$out\net.csv" -ScriptBlock {
  param($adb, $serial, $file)
  while ($true) {
    try { $l = (& $adb -s $serial shell cat /proc/net/dev | Select-String 'wlan0:'); if ($l) { $f = $l.Line.Trim() -split '[:\s]+'; "{0},{1},{2}" -f (Get-Date -Format o), $f[1], $f[9] | Add-Content -LiteralPath $file } } catch { }
    Start-Sleep -Seconds 2
  }
}
# a fine prova: Stop-Job $netjob; Remove-Job $netjob
function Net-Between([datetime]$from, [datetime]$to) {                          # byte tra due ore; il contatore riparte da zero quando il Wi-Fi si spegne
  $r = @(Import-Csv "$out\net.csv" -Header t,rx,tx | ForEach-Object { [pscustomobject]@{ t = [datetime]$_.t; rx = [int64]$_.rx; tx = [int64]$_.tx } } | Where-Object { $_.t -ge $from -and $_.t -le $to })
  $rx = 0L; $tx = 0L
  for ($i = 1; $i -lt $r.Count; $i++) { $a = $r[$i].rx - $r[$i-1].rx; if ($a -lt 0) { $a = $r[$i].rx }; $rx += $a; $b = $r[$i].tx - $r[$i-1].tx; if ($b -lt 0) { $b = $r[$i].tx }; $tx += $b }
  [pscustomobject]@{ tx_MiB = [math]::Round($tx / 1MB, 1); rx_MiB = [math]::Round($rx / 1MB, 1); secondi = [int]($r[-1].t - $r[0].t).TotalSeconds }
}
```

`-NotSettled` si usa a lavoro ancora in corso (controlla solo gli invarianti che valgono sempre). Esito buono: `TUTTI GLI INVARIANTI RISPETTATI`. Il rapporto non contiene nomi di file.

**Verifica del backup con i giri vecchi alle spalle (D-14).** Il verificatore (`-Mode backup -Stage verify`) legge sempre anche la `Versioni/` **dentro l'area recintata** (`mcg-campo/Versioni/`; mai quella alla radice della share). Gli originali che i giri precedenti hanno già archiviato lì (le 15-16 fixture che F43-tel aveva modificato) non sono una perdita: il rapporto li conta come **previsti** («originali già in Versioni/ contati come previsti: N»). Senza righe `edit=` nel manifesto la `Versioni/` serve solo a questo; con le righe `edit=` valgono gli invarianti 12 e 13 come prima.

**Fai ora i lavori periodici (solo build debug).** Per le prove che prima chiedevano di modificare il database di WorkManager (F20, F12, le scansioni) c'è un'azione che accoda **subito** gli stessi lavori del giro periodico: pulizia del cestino (`trash`), anteprime HQ (`hq`), preparazione (`prep`), sincronizzazione dell'indice (`sync`), scansione (`scan`, poi indice e dati personali), backup (`backup`: foto, video e verifica «al sicuro»):
```powershell
A shell am broadcast -a dev.pampa.mycloudgallery.debug.PERIODIC_NOW -p $pkg --es jobs all          # oppure: trash,hq,prep,sync,scan,backup
```
Risponde con i lavori accodati (o con `result=2` se un nome non esiste). Si può anche fare dall'app: Impostazioni → Sviluppo → Prove guidate → «Lavori periodici · Fai ora». Non scavalca niente: i lavori sono quelli veri e passano dalle regole delle attività (preset, rete, carica, fascia), dal cancello del backup e dalle recinzioni delle share di prova (D33) e del telefono (D13); se le regole dicono di aspettare, aspettano. Il ricevitore è protetto dal permesso `android.permission.DUMP` (lo ha la shell di `adb`, non un'altra app) e nella release non esiste (`tools/check.ps1`, resti di debug: `FieldProbe`, `debugtools/probes`).

### 2.2 APK da installare

| Cosa | Dove | Nota |
|---|---|---|
| **Debug** | `app\build\outputs\apk\debug\app-debug.apk` (id `dev.pampa.mycloudgallery.debug`, versione `0.1.0-debug`) | È la sola build da usare in A e B. |
| Release | `app\build\outputs\apk\release\app-release-unsigned.apk` | **Non firmata** (in `local.properties` mancano le chiavi `pampa.*`): non si installa. Vedi C-9. |

Si compila **fuori da qui** (lo fa chi sta compilando). L'orchestratore controlla che l'APK sia quello giusto:

```powershell
. "C:\VibeCoded Projects\MyCloud-Gallery-2\build\campo\h.ps1"
git rev-parse --short HEAD                                  # f705a6f
git status --short                                          # modifiche non committate: annotarle nel registro
git log -1 --format=%cd --date=iso                          # ora del commit
Get-Item app\build\outputs\apk\debug\app-debug.apk | Select-Object Length, LastWriteTime
Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
# l'APK deve essere piu' recente di ogni sorgente di produzione modificato:
$apk = Get-Item app\build\outputs\apk\debug\app-debug.apk
Get-ChildItem -Recurse -File app\src\main, core-*\src\main, feature-*\src\main | Where-Object { $_.LastWriteTime -gt $apk.LastWriteTime } | Select-Object -First 5 FullName
```

Se la lista finale non è vuota, l'APK è più vecchio del codice: **non si installa**, si chiede a chi compila di rifarlo. (Il 20/10 l'APK presente era del 16:22, prima del commit delle 16:28, e l'albero di lavoro aveva modifiche non committate in `BackupGraph`, `RunProgress`, `BackupStages`.)

Installazione, che **conserva i dati** dell'app (account, chiave, famiglia):

```powershell
A install -r app\build\outputs\apk\debug\app-debug.apk
A shell dumpsys package $pkg | Select-String "versionName|lastUpdateTime"
```

Mai `-g` (concederebbe permessi), mai `uninstall`.

### 2.3 Stato in cui deve essere l'app

Si controlla, non si imposta (le correzioni le fa l'utente, 2.5).

**Condizione che blocca tutto: l'account.** L'app deve essere entrata con `test` (o `test2`). Il 20/10 non lo era: Impostazioni → Account del NAS diceva `alessio`, area privata `alessio`, area comune `Public`, percorso `RECOVER_ADMIN`. Con un account vero D13 è **spenta** ("Cosa salvare" elenca le cartelle vere: "Fotocamera 957 in coda", "Screenshot 1.105 in coda") e "Libera spazio", "Elimina solo dal telefono", correzioni e versioni agirebbero sulle foto vere. D12 e D31 hanno tenuto (niente scritto sulle share vere), ma non basta per le prove sul telefono. Se è ancora così: nessuna prova che scriva o cancelli, si avvisa l'utente, §8 punto 1.

- configurata con l'account `test`: area privata `test`, area comune `test-comune`; D13 (recinzione del telefono) e D33 (recinzione delle share) attive, perché account di prova su build debug;
- Wi-Fi di casa; la capsula di stato dice **In casa · NAS collegato**;
- permessi: foto e video (tutto), **posizione dei media** (`ACCESS_MEDIA_LOCATION`), notifiche, batteria "senza limiti" (altrimenti Android ferma i lavori);
- pagina Backup (Impostazioni → Backup) senza "Il backup non è ancora pronto" e con **"Inizia ora"** disponibile: la cartella del dispositivo sul NAS è già scelta (`Sul NAS`). Il 20/10 la pagina diceva "Cartella del dispositivo: si sceglie prima del primo backup" e non aveva "Inizia ora": la sceglie l'utente (2.8);
- blocco dell'app **spento** (si accende solo in B, T-14/F39), "Rimuovi la posizione" come l'ha lasciato l'utente (annotarlo);
- preset e qualità delle anteprime come sono (Impostazioni → Quando lavora l'app, Anteprime): **annotarli**; "Inizia ora" parte comunque fuori dalla notte e senza carica.

### 2.4 Semina del telefono

**Stato lasciato dal primo tentativo (20/10).** `mcg-prova` è già seminata con il comando di default (308 file, 5,37 GB, giro `20261020-173044-435aed91`) e 5 foto di prova sono nel Cestino di Samsung Gallery (`20240312_101533.jpg`, `20240312_110000_001.jpg`, `_002`, `_003`, `20240312_112000_001.jpg`). Si riparte da pulito, così il manifesto descrive esattamente il telefono:
1. l'utente ripristina quelle 5 foto dal Cestino della Galleria, oppure le lascia nel cestino (sono di prova: la pulizia finale le butta);
2. `s25.ps1 -Mode backup -Step clean -Serial RFCY61FZDHW -DryRun`, poi con `-Yes` al posto di `-DryRun` (toglie solo `DCIM/mcg-prova` e `Pictures/mcg-prova`).

**Tre file finti dei fixture non vanno sul telefono.** `casuali.png`, `testo.jpg` e `video-falso.mp4` sono scritti apposta per non leggersi: l'app li scarta e non entrano mai nel catalogo (305 su 308 il 20/10). Se restassero nel manifesto, ogni verifica del NAS direbbe "3 file del telefono non sono sul NAS" (invariante 4) e F30 sarebbe rossa senza un difetto vero (O-1 del 20/10). Si generano i fixture, si tolgono i tre e si danno alla semina con `-FixturesDir`:

```powershell
. "C:\VibeCoded Projects\MyCloud-Gallery-2\build\campo\h.ps1"
powershell -ExecutionPolicy Bypass -File tools\fixtures\generate.ps1 -SkipBig -Out "$repo\build\campo\fixtures"
Get-ChildItem "$repo\build\campo\fixtures" -Recurse -File | Where-Object { $_.Name -in @("casuali.png", "testo.jpg", "video-falso.mp4") } | Remove-Item
```

**Parametri.** Serve JDK nel `PATH` (`javac -version`), almeno **15 GB liberi** sul telefono (`A shell df -h /sdcard`) e il telefono sbloccato. Il seme fisso 42 rende la semina ripetibile. `-Photos 520` perché T-09 (f) vuole almeno 500 righe (la semina di default ne fa circa 300; `docs/android.md` e la pagina delle prove guidate dicono `-Mode seed`, che non esiste: il comando giusto è questo). Tre video da 3 GB: F23 (Wi-Fi spento a metà di un video da 3 GB), F40 (un video da 3 GB che l'app deve rifiutare) e un primo backup abbastanza lungo (circa 9 GB, 35–60 minuti) da contenere le 100 interruzioni di F30.

```powershell
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step seed -DryRun -Serial RFCY61FZDHW -Run $giro -Seed 42 -Photos 520 -BigVideos 3 -BigVideoMb 3072 -FixturesDir "$repo\build\campo\fixtures"
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step seed -Serial RFCY61FZDHW -Run $giro -Seed 42 -Photos 520 -BigVideos 3 -BigVideoMb 3072 -FixturesDir "$repo\build\campo\fixtures" | Tee-Object "$out\seed.log"
```

Tempo: 6–10 minuti (`adb push`, tre `dd` da 3 GB sul telefono, scansione del MediaStore, SHA-256 di tutto). Scrive `build\chaos\backup-manifest-<giro>.txt` (= `$manifest`) e `build\chaos\seed-<giro>\` sul PC (i file originali, utili in F25).

**Com'è fatta la semina** (dal 20/10): le foto "uniche" sono JPEG minuscoli (circa 800 byte, copie del JPEG più piccolo dei fixture con 24 byte casuali in coda): le miniature del telefono possono restare sul segnaposto e i tre video hanno durata 0:00. Non è un difetto: il peso lo fanno i tre video, il numero lo fanno le foto. Due file `Screenshot_*.png` dei fixture sono classificati dall'app come screenshot e non risultano nella sorgente `mcg-prova` di "Cosa salvare": con D13 la sorgente Screenshot non passa mai, quindi possono non essere salvati da nessuna sorgente (O-2). Si lasciano, e al primo backup si guarda se arrivano: se no, `LOSS` di 2 file è una **nota**, non un guasto.

Controlli dopo la semina:

```powershell
(Get-Content $manifest | Where-Object { $_ -like 'phone=*' }).Count                 # circa 523 (520 immagini piu' 3 video, meno i tre finti)
A shell "ls /sdcard/DCIM/mcg-prova | wc -l"; A shell "ls /sdcard/Pictures/mcg-prova | wc -l"
A shell df -h /sdcard
```

I nomi dei file unici sono `IMG_mcg_0001.jpg`, `IMG_mcg_0002.jpg`, ...: i dispari in `DCIM/mcg-prova`, i pari in `Pictures/mcg-prova`. I video sono `VID_mcg_grande_1.mp4` ... `_3.mp4`. Gli altri sono i fixture (con la tabella dei valori attesi in `build\campo\fixtures\MCG-prova\MANIFEST.tsv`).

### 2.5 Controlli iniziali (prima di ogni altra cosa)

```powershell
. "C:\VibeCoded Projects\MyCloud-Gallery-2\build\campo\h.ps1"
A devices                                                       # RFCY61FZDHW deve essere "device"; altri dispositivi collegati non si toccano
A shell getprop ro.product.model; A shell getprop ro.build.version.release
A shell dumpsys battery | Select-String "level|AC powered|USB powered"
A shell df -h /sdcard
A shell pm path $pkg
A shell dumpsys package $pkg | Select-String "READ_MEDIA|ACCESS_MEDIA_LOCATION|POST_NOTIFICATIONS|versionName"   # granted=true
A shell dumpsys deviceidle whitelist | Select-String mycloudgallery                                          # in lista = batteria senza limiti
Focus                                                           # niente "Keyguard"/"NotificationShade": il telefono deve essere sbloccato
Test-Path "$env:USERPROFILE\.mycloudgallery\nas-it.properties"  # True; il contenuto non si apre
java -version; javac -version
```

Poi sullo schermo (nostra app, `Start-Ours`, `Ui-Texts`):
1. la capsula dice **In casa · NAS collegato**. Se dice **Serve la password del NAS** o l'indirizzo è sbagliato: **stop** (regola 5), si avvisa l'utente;
2. Impostazioni → Account del NAS: l'utente è `test` (e, in `Ui-Texts`, l'area comune è `test-comune`). Se è `alessio` o qualunque altro: **stop** (2.3, §8 punto 1). Il 20/10 lo si è confermato anche con `run-as` sui file di configurazione dell'app (`nas-account`: `nas.user`; `onboarding`: `usr`, `ps`, `cs`, percorso): si leggono solo quelle chiavi, mai altro;
3. Libreria: il piede in fondo dà il numero di foto e video. Annotarlo;
4. Cartelle NAS → area privata → `mcg-campo`, e area comune → `mcg-campo`: ci devono essere i **campioni** (F1: un file per ogni formato, una cartella musicale con copertina, una cartella da **1.000 foto**, un video da più di 2 GB). Se mancano → sessione di preparazione 2.8.

Se un permesso manca (`granted=false`) o la batteria è limitata, lo dice l'orchestratore e **lo sistema l'utente** dalla schermata di Android (2.8, punto 1).

### 2.6 Registro degli esiti e regole d'arresto

Ogni prova finisce con `Esito "<id>" "OK|KO|NOTA|SALTATA" "<due righe>"`, `Log-Save "<id>"` e, se serve, `Shot "<id>-1"`. Tutto in `build\campo\<giro>\`. Un difetto trovato si descrive in due righe (cosa, come si rifà, sigla della revisione se c'è): **non si corregge** niente qui.

**Ci si ferma e si avvisa l'utente** se:
- `AUTH_FAILED`, "Serve la password del NAS", o l'app chiede un accesso;
- l'account dell'app non è `test` o `test2` (2.3);
- in primo piano c'è un dialogo di sistema o un'altra app che non si aspettava (si aspetta l'utente);
- una verifica del NAS dice `HALF_FILE` (file a metà visibile), `LOSS` (perdita) o `REPLACED` (sostituzione): si tiene lo stato (`core-backup\build\chaos\backup-state-*.txt`), non si lancia nessuna prova che scrive, si avvisa;
- il Wi-Fi resta spento dopo uno script (di solito `s25.ps1` lo riaccende sempre; se no lo riaccende l'utente);
- lo spazio libero sul telefono scende sotto 3 GB;
- compare una share che non sia `test`, `test2` o `test-comune`.

### 2.7 Pulizia finale

1. Telefono: `powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step clean -Serial RFCY61FZDHW -DryRun`, poi lo stesso con `-Yes` al posto di `-DryRun` (toglie solo `DCIM/mcg-prova` e `Pictures/mcg-prova`). Le foto di prova che sono nel Cestino di Samsung Gallery (T-09, F26, F37) le svuota l'utente da lì: `clean` non le vede, perché su One UI il cestino di sistema le sposta fuori dalla cartella (D-3 del 20/10).
2. Marcatore del cestino (F20, solo se messo): `A shell rm /sdcard/Android/data/$pkg/files/debug-trash-retention-minutes.txt`.
3. NAS, area di prova del PC: `.\gradlew.bat :core-nas:testDebugUnitTest --tests "*NasIntegrationSuiteTest*" -PnasIntegration --console=plain --rerun` pulisce `mcg-it/` (e alla fine `mcg-it` deve essere vuota per `test` e `test2`).
4. NAS, `test/mcg-campo/Backup/`, `test/mcg-campo/Versioni/`, `test-comune/mcg-campo/` e il cestino: **li svuota l'utente** da Esplora risorse quando ha finito di guardare. L'orchestratore non cancella dal NAS.
5. Telefono, `Pictures/MyCloud Gallery/` (copie scaricate da F11, F41): le toglie l'utente dall'app (Libreria → seleziona → Elimina, con il dialogo di sistema).
6. Impostazioni dell'app cambiate durante le prove (preset, qualità, "Rimuovi la posizione", blocco dell'app): rimesse com'erano (annotate in 2.3). Prove guidate: "Azzera i numeri" e "Dimentica le condivisioni ricevute".
7. Se l'utente ha cambiato permessi di Android per le prove (B, 5a-R5): li rimette com'erano.
8. `build\campo\<giro>\` si tiene; i file grossi di `build\chaos\seed-<giro>\` si possono cancellare.

### 2.8 Se manca l'account di prova, i permessi o i campioni sul NAS (utente, 10–15 minuti, solo se serve)

0. **Account di prova.** Se l'app è entrata con `alessio` (come il 20/10) va rifatta la configurazione con `test` (area privata `test`, area comune `test-comune`): lo fa **solo l'utente**, perché serve la password. **Attenzione**: in quella configurazione questo S25 è il **dispositivo admin della famiglia** con l'account vero (percorso `RECOVER_ADMIN`, chiave di famiglia nella cassaforte del telefono). "Configura di nuovo" toglie dal telefono la chiave del dispositivo e quella di famiglia: prima di farlo l'utente deve avere il **kit di recupero con il suo codice** o un **secondo admin**. L'alternativa è provare su un altro profilo del telefono o sul Tab S9 con `test2`. Decide l'utente. Una volta rifatta, si ricomincia da 2.5.
1. **Permessi.** Impostazioni di Android → App → MyCloud Gallery (debug) → Autorizzazioni: Foto e video "Consenti tutto", "Posizione dei contenuti multimediali" consentita, Notifiche consentite; Batteria "Senza limiti". (Lo fa l'utente: sono impostazioni del telefono.)
2. **Cartella del dispositivo** (se la pagina Backup dice "Scegli la cartella del dispositivo sul NAS"): la sceglie l'utente dalla pagina.
3. **Campioni sul NAS** (se mancano). L'orchestratore prepara sul PC `build\campo\campioni\` con `powershell -ExecutionPolicy Bypass -File tools\fixtures\generate.ps1 -SkipBig -Out build\campo\campioni` (un file per formato) e, per la cartella da 1.000 foto, `1..1000 | ForEach-Object { ... }` copiando un JPEG piccolo con 24 byte casuali in coda (stessa tecnica della semina). L'utente copia in Esplora risorse:
   - `build\campo\campioni\MCG-prova\*` in `\\<indirizzo del NAS>\test\mcg-campo\campioni\` e in `\\<indirizzo del NAS>\test-comune\mcg-campo\campioni\`;
   - la cartella da 1.000 foto in `\\<indirizzo del NAS>\test\mcg-campo\mille\`, e una copia in `...\test\mcg-campo\F9\`;
   - un video da più di 2 GB che si apra (se ne ha uno suo, lo mette in `...\test-comune\mcg-campo\campioni\`).
   L'indirizzo del NAS è in Impostazioni → Account del NAS. **Solo** le share `test` e `test-comune`, **solo** dentro `mcg-campo\`. Chiedere all'utente di dire quanti file ha messo: serve per F1.

## 3. Ordine della giornata

Il blocco A è scritto in questo ordine perché è quello che fa risparmiare tempo: prima le prove sulla Libreria del NAS (il telefono non è occupato), poi il primo backup (che dura ore con le interruzioni), poi le sessioni dell'utente mentre l'orchestratore fa le attese e i controlli, e di notte le prove lunghe sul PC.

| Quando | Cosa | Chi |
|---|---|---|
| 0 | Preparazione 2.1–2.5 (**prima l'account**: se non è `test`, finisce qui), semina 2.4 | orchestratore, 35 minuti |
| 1 | A.1 PC-0 (credenziali), A.2 F1, A.3 F3 (parte in sottofondo), A.4 F6a, A.5 FF-15, A.6 GV, A.7 F10, A.8 F11 | orchestratore, circa 100 minuti |
| 2 | **Sessione B-S1** (T-09) | utente, 15 minuti |
| 3 | **Sessione B-S2** (F25, poi F26 dentro il primo backup) | utente, 11 minuti |
| 4 | A.9 il primo backup: F21, F22, F23, F24, F30-tel (si sovrappongono: circa 90 minuti di orologio, il totale di A.9 nella tabella è la somma delle parti) | orchestratore |
| 5 | A.10 F43-tel (modifiche e versioni con interruzioni) | orchestratore, 80 minuti |
| 6 | Sessioni B-S3 … B-S18 (§5), nell'ordine; tra una e l'altra A.11 FF-8 e A.12 F39-adb. B-S5 lancia F16-tel (55 minuti da solo) | utente + orchestratore |
| 7 | A.13 la suite di integrazione di `core-nas` (pulisce `mcg-it`), poi di notte A.14 F16-PC, F30-PC, F43-PC | orchestratore, circa 6 ore sul PC |
| 8 | A.15 F13 (benchmark, con Gradle libero), Blocco C (solo elenco), pulizia 2.7 | orchestratore |

**Esportazioni della Diagnostica** (numeri che lo schermo non mostra): **E1** a fine A.9 (30 secondi dell'utente), **E2** a fine B-S14, e quelle dette nelle sessioni. Il registro del traffico (`NET-LOG`, 2.1) parte all'inizio di A.9 e si ferma a fine A.10.

Il blocco C non si fa in questa giornata.

## 4. Blocco A: "le faccio da solo"

Servono solo la nostra app, `adb` e gli script del repo. Ogni prova comincia con `. "C:\VibeCoded Projects\MyCloud-Gallery-2\build\campo\h.ps1"` e finisce con `Esito`, `Log-Save`. L'utente deve solo **tenere il telefono sbloccato e con lo schermo acceso** (se si blocca, `Assert-Ours` si ferma e l'orchestratore lo chiede).

### A.1 PC-0: le credenziali e la guardia dal PC (6 minuti)

**Scopo.** Vedere che il file delle credenziali c'è, che il login di prova funziona e che la guardia tiene, prima di ogni altra prova sul NAS. Se qui c'è `AUTH_FAILED`, **tutto il resto si ferma**.

**Passi.**
```powershell
Test-Path "$env:USERPROFILE\.mycloudgallery\nas-it.properties"       # True; il file non si apre
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage run -Interruptions 20 -DryRun
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage run -Interruptions 20 -TimeoutMinutes 30 | Tee-Object "$out\pc0.log"
```
Gradle non deve essere in uso da altri.

**Si guarda.** L'uscita di Gradle (test passato, non "saltato"), i rapporti in `core-backup\build\chaos\` (`backup-area-report-*.txt`).

**Atteso.** Test verde, zero violazioni, nessun errore di autenticazione. "Saltato" = file delle credenziali assente (lo dice il motivo). `AUTH_FAILED` = stop e si avvisa l'utente.

### A.2 F1: prima scansione dell'area di prova sull'S25 (15 minuti)

**Scopo.** I media del NAS arrivano in Libreria con date giuste e badge "solo NAS"; copertine e temporanei no; tempo annotato (M1).

**Prima.** Campioni sul NAS (2.5). Chiedere all'utente quanti file media ha messo.

**Passi.**
1. `Log-Clear; Start-Ours; Ui-Texts`. In Impostazioni → Diagnostica → NAS guardare se una prima scansione completa è già stata fatta (titoli e codici della sezione NAS, e le ore nel log). Se sì, **non si azzera niente**: si annotano le ore dai log e si fanno solo i passi 3-6 (F1 non si ripete senza azzerare l'app, §8).
2. **Difetto già noto (D-2 del 20/10).** All'avvio a freddo la prima scansione può partire prima che la sessione SMB sia pronta: `NasScanWorker: tappa non riuscita` 180 ms prima di `NasSession: sessione pronta`, e non si ripete finché l'app non va in background e torna, o scatta il giro periodico (6 ore). Se in 2 minuti la Libreria non riceve media del NAS: `Key KEYCODE_HOME`, aspettare 5 secondi, `Start-Ours`, e annotare "D-2 ancora presente". Il tempo della scansione si legge dal log: `A logcat -d -v time | Select-String "MyCloud/NasScanWorker|MyCloud/NasSession"`. (Con l'account `alessio` la prima lettura di 9.306 file era durata circa 12 minuti, a giri da 1.000 file ogni 75-80 secondi; qui i file sono molti meno.)
3. Annotare il piede della Libreria (N foto, M video). Il numero atteso è quanti media ci sono nei campioni (più le foto della Fase 0 già in `test`), meno copertine e temporanei.
4. A campione, 5 media solo-NAS: apri nel viewer → Informazioni: la data è quella del nome o dei metadati (tabella `MANIFEST.tsv` dei fixture), c'è il badge "solo NAS", la miniatura appare subito. `Shot "F1-viewer-n"`.
5. Cartelle NAS → la cartella musicale c'è, ma nessuna sua copertina compare in Libreria (E-N14), e nessun file `*-bk-*` o temporaneo.
6. GA-5 e GA-6: dopo la semina (2.4) il telefono ha le foto di `mcg-prova` (i file dei fixture sono sintetici e piccoli: le miniature possono restare sul segnaposto). Entro 3 minuti, **senza riaprire l'app**, la foto con GPS dei fixture mostra il luogo in Informazioni.
7. `Log-Save "F1"` e cercare righe di errore: `Select-String " E/MyCloud" "$out\F1.log"`.

**Atteso.** Conti giusti, date giuste, badge giusti, copertina esclusa, tempo di prima scansione annotato (4–12 minuti per la famiglia intera: qui poche decine di file), nessun `E/MyCloud` inatteso oltre a D-2.

**Può far emergere.** NA-6 (la prima scansione non si vede nel piede), GA-5, GA-6, 4a-R10.

### A.3 F3: verifica senza novità ripetuta, e i dischi (10 minuti attivi, il resto in sottofondo)

**Scopo.** Una verifica senza novità sta sotto la soglia (≤ 60 s in casa, §2.10 di `fase4-progetto.md`) e non sveglia i dischi del NAS senza motivo.

**Passi.**
1. Dopo F1, con l'app in primo piano e il Wi-Fi acceso.
2. Tre volte, a distanza di 4 minuti: `Log-Clear`; `Key KEYCODE_HOME`; `A shell dumpsys jobscheduler $pkg | Set-Content "$out\jobs.txt"`; lanciare uno per volta i lavori elencati (`A shell cmd jobscheduler run -f $pkg <id>`) e vedere nel log (`A logcat -d -v time | Select-String 'MyCloud/'`) quale è la verifica del NAS; `Log-Save "F3-n"`; riaprire l'app. (In alternativa, senza cercare gli id: `A shell am broadcast -a dev.pampa.mycloudgallery.debug.PERIODIC_NOW -p $pkg --es jobs scan`.)
3. Dal log di ogni giro: ora di inizio e di fine di `NasScanWorker` (la durata), e quante volte compare `NasSession: sessione pronta` (ogni apertura di sessione tocca i dischi).
4. Il "tocco dei dischi" (`lastDiskTouchAt`) è un numero della Diagnostica: non sta sullo schermo, lo si legge dall'esportazione E1 (fine di A.9) e lo si confronta con le ore delle verifiche.
5. Lasciare il resto della giornata: a ogni ritorno nell'app, le righe `NasSession` del log dicono se si sono aperte sessioni senza motivo.

**Atteso.** Ogni verifica senza novità ≤ 60 s; nessuna sessione del NAS aperta tra due verifiche; nessuna scrittura sul NAS.

**Può far emergere.** NA-8 (battito della scansione a vuoto), RE-6, 4a-I4.

### A.4 F6a: la preparazione dell'area di prova (10 minuti attivi)

**Scopo.** Il Preparatore dell'S25 prepara tutto; i formati che non si decodificano sono segnalati; l'outbox non cresce senza tetto.

**Passi.**
1. Prima: `A shell run-as $pkg du -sk no_backup | Out-File "$out\f6-spazio-prima.txt"`.
2. Impostazioni → Anteprime: annotare la qualità. Diagnostica → "Preparazione": annotare i **titoli** delle voci (per esempio "Tutto preparato" o "Preparazione in corso"): i numeri non sono sullo schermo, stanno nell'esportazione E1.
3. Lasciare l'app in primo piano e in carica; ogni 5 minuti rileggere i titoli finché dice "tutto preparato" (al massimo 40 minuti; il resto continua in sottofondo mentre si fanno altre prove).
4. Dopo: `du -sk no_backup` di nuovo e confronto.

**Atteso.** "Tutto preparato"; i file corrotti dei fixture segnalati come non decodificabili (non come errore dell'app); lo spazio di `no_backup` cresce poco (sotto D31 le anteprime delle share vere non si pubblicano; qui le share sono di prova).

**Può far emergere.** 4a-R1, 4a-R2, 4a-R5. (La parte del tablet, che vede le miniature senza prepararle, è C-2.)

### A.5 FF-15: il NAS torna dopo una sonda fallita (6 minuti)

**Scopo.** Dopo un Wi-Fi caduto, l'indicatore torna "In casa · NAS collegato" e i lavori non dicono "NAS spento" (RE-6).

**Passi.**
1. App in primo piano. `Ui-Texts` → capsula "In casa · NAS collegato".
2. `TAGLIO-WIFI 60` (§2.1). Durante: la capsula dice "Offline · Libreria sul telefono" o "NAS non raggiungibile".
3. Dopo il Wi-Fi riacceso: ogni 10 secondi `Ui-Texts` fino a "In casa · NAS collegato". Annotare i secondi.
4. `Log-Save "FF-15"` e verificare che i lavori in sottofondo non scrivano "NAS spento" dopo il ritorno.

**Atteso.** La capsula torna a posto da sola entro circa un minuto, senza toccare niente.

**Può far emergere.** RE-6, RE-3.

### A.6 GV: giro veloce sulla Libreria e sulle Impostazioni (20 minuti)

**Scopo.** Una serie di controlli corti su correzioni già fatte (FF-11, FF-16 e le voci qui sotto).

| # | Controllo | Come | Atteso |
|---|---|---|---|
| 1 | GA-2 | Libreria: pressione lunga su una cella (`A shell input swipe X Y X Y 800`), poi `Key KEYCODE_BACK` | si esce dalla selezione, non dall'app |
| 2 | GA-3 | Libreria con miniature visibili; `Key KEYCODE_HOME`; `A shell am send-trim-memory $pkg BACKGROUND`; `Start-Ours`; `Shot` a 1 e 3 secondi | le miniature tornano da sole, senza scorrere (FF-16) |
| 3 | GA-8 | Selezionare solo media "solo NAS" → Condividi, poi → Elimina | avviso "Questi elementi sono solo sul NAS", nessun foglio di sistema |
| 4 | NA-3 | Cartelle NAS → una cartella → gesto "Aggiorna" | l'elenco si rilegge, nessun errore |
| 5 | ON-7 | Impostazioni → Rete e VPN → "Configura il controllo" (se c'è) | apre il passo VPN, non il riepilogo; Indietro esce |
| 6 | ON-6 | Impostazioni → Famiglia | la guida "Ricominciare da zero" c'è e dice di cancellare dal PC solo `family`; nessun tasto distruttivo |
| 7 | RE-4 | Impostazioni → Diagnostica → "Ultimi esiti ed errori" dopo le prove di oggi | non è vuota |
| 8 | RE-8 | Impostazioni → Quando lavora l'app: cambiare preset e rimetterlo | si salva, la riga lo mostra; **rimesso com'era** |
| 9 | BK-9 | Impostazioni → Backup | nessun tasto "Apri Impostazioni, Aree" |
| 10 | FF-11 | Impostazioni → "Da completare" (se ci sono passi saltati) | "Cartelle da escludere" ecc. si riaprono e si chiudono |

**Può far emergere.** GA-2, GA-3, GA-8, NA-3, ON-6, ON-7, ON-8, RE-4, RE-8, BK-9.

### A.7 F10: viewer di un solo-NAS, video, Wi-Fi spento a metà (15 minuti)

**Scopo.** Anteprima subito poi nitida; il video parte; col Wi-Fi spento l'app dice "non raggiungibile" senza bloccarsi. (La parte con l'hotspot è C-11.)

**Passi.**
1. Aprire un media solo-NAS (badge "solo NAS"): `Shot` a 0,3 s, 1 s, 3 s.
2. Aprire un video 4K del NAS (se i campioni lo hanno; il video da più di 2 GB va bene): tocca Riproduci; `Shot` a +2 s e +8 s (il fotogramma cambia); `Net-Phase` mostra `rx` alto.
3. Mentre il video va: `TAGLIO-WIFI 40`. Atteso: messaggio "Il NAS non risponde" (E-L4), l'app non si chiude.
4. A Wi-Fi tornato: il video riprende o si può riprovare.

**Atteso.** Anteprima immediata, poi immagine nitida; video che parte; messaggio chiaro; nessuna chiusura.

**Può far emergere.** 4b-R10, GA-3 (miniature dopo il ritorno).

### A.8 F11: "Scarica sul telefono" di 10 media, uno interrotto (12 minuti)

**Scopo.** Le copie arrivano in `Pictures/MyCloud Gallery`, con due posizioni (telefono e NAS), senza doppioni e senza file a metà.

**Passi.**
1. Scegliere 10 media solo-NAS (non video grandi). Libreria → selezione → "Scarica sul telefono" (o, dal viewer, uno per volta se la selezione non lo offre).
2. Dopo circa 3 media: `TAGLIO-MORTE run-as`. Riaprire l'app e lasciar finire.
3. Controlli sul telefono (sola lettura):
```powershell
A shell find "/sdcard/Pictures/MyCloud Gallery" -type f
A shell content query --uri content://media/external/images/media --projection _display_name:is_pending --where "relative_path LIKE 'Pictures/MyCloud Gallery/%'"
```

**Si guarda.** Nessuna riga con `is_pending=1`; nessun nome con "(1)" duplicato; nessun file più piccolo dell'originale (le dimensioni stanno in Informazioni); nel viewer i 10 media risultano con le due posizioni.

**Atteso.** 10 file interi, nessun doppione, nessun temporaneo; quello interrotto o è intero o non c'è, e si può rifare.

**Può far emergere.** 4a-R8 (copia senza impronta né timbro).

### A.9 Il primo backup di `mcg-prova`: F21 (con T-11), F22, F23, F24, F30-tel

Una sola grande sessione di circa 90 minuti di orologio, con le cinque prove una dentro l'altra, **in quest'ordine**. Le foto sono minuscole e finiscono in un paio di minuti; poi il tempo lo fanno i tre video da 3 GB (caricamento a 9-16 MB/s, poi rilettura per la verifica).

| Fase | Che cosa | Prova |
|---|---|---|
| 1 | avvio, riepilogo, prime foto al sicuro, notifica | F21, F22 |
| 2 | primo video: Wi-Fi spento **una volta sola**, poi si lascia finire il caricamento | F23 |
| 3 | rilettura di quel video: morte del processo | F24 (verifica) |
| 4 | secondo video: "Ferma" e "Riprendi", `am kill`, "Ferma" seguito da una morte | F22, F24 (caricamento) |
| 5 | il resto del backup con 100 interruzioni | F30-tel |
| 6 | fine: totali, verifica completa, esportazione E1 | F22, F30-tel |

**Prima** devono essere fatte B-S1 (T-09) e B-S2 (F25 e l'avvio di F26), e il manifesto deve essere a posto:

```powershell
# dopo B-S1: le foto svuotate per sempre dal Cestino di Samsung Gallery non ci sono piu': via dal manifesto
Drop-Phone "<nome>"                                   # una volta per ogni foto svuotata
# F25: le righe pre= per i file messi dall'utente (B-S2) sono gia' nel manifesto
Select-String -Path $manifest -Pattern '^pre='
# nessun file ".trashed*" rimasto (su One UI di solito non ce ne sono: il cestino li sposta fuori dalla cartella)
A shell "find /sdcard/DCIM/mcg-prova /sdcard/Pictures/mcg-prova -name '.trashed*'"
```
La foto lasciata nel Cestino di Samsung Gallery per F26 è fuori dalla cartella e il manifesto la nomina ancora: va bene, torna con il ripristino (B-S2); per il confronto col telefono si usa `-AllowFreed`.

Registro del traffico, ora di partenza e batteria:
```powershell
Log-Clear
# avviare NET-LOG come in §2.1 ($netjob = Start-Job ...)
$t0 = Get-Date; $n0 = Net; A shell dumpsys battery | Select-String "level"
```

#### F21 con T-11: le prime foto al sicuro, byte per byte (fase 1)

**Scopo.** Il primo file messo al sicuro ha sul NAS lo stesso SHA-256 che dà `adb shell sha256sum` sul telefono (le impronte del manifesto vengono da lì), con lo scudo in Libreria e "Al sicuro sul NAS" nel viewer. (T-11: la copia non perde il GPS; si prova la sola condizione "tutti i permessi dati": togliere i permessi per le varianti (a), (b), (d) è una modifica delle impostazioni, C-18.)

**Passi.**
1. Impostazioni → Backup. `Ui-Texts`: "Pronto per il primo backup", "N foto e video da mettere al sicuro, X in tutto". N deve essere il numero delle righe `phone=` meno le foto non disponibili (quella cestinata di F26). Annotare N e X.
2. `Tap-Text "Vedi il riepilogo"` → foglio "Primo backup". Annotare "Da caricare", "Tempo", "Traffico", "Batteria (circa X%)", "già sul NAS" (deve contare la copia identica di F25), "con una copia simile" (0). Il foglio è protetto: si salva `Ui-Texts` in `$out\f22-riepilogo.txt`.
3. `Tap-Text "Inizia ora"`. Annotare l'ora.
4. Dopo 2-3 minuti: aprire una foto già fatta nel viewer → "Al sicuro sul NAS"; in Libreria lo scudo; `Shot "F21-scudo"`.
5. Dal PC, mentre il backup lavora: `VERIFICA-NAS -NotSettled` (le foto sono già sul NAS, il primo video non è ancora grosso).

**Atteso.** `TUTTI GLI INVARIANTI RISPETTATI` anche a metà; nessun file a metà visibile; i file arrivati hanno l'SHA-256 del manifesto (invarianti 1, 5); in `test/mcg-campo/Backup/<dispositivo>/AAAA/MM/`.

**Può far emergere.** BK-14 (sorgenti di D13 se l'account arriva tardi), 5a-R6 (lettura del telefono), 5a-R1.

#### F22: riepilogo, notifica, Ferma e Riprendi, stima, Libreria fluida (fasi 1, 4 e 6)

**Scopo.** Il primo backup si vede e si governa: riepilogo, notifica viva con l'app chiusa, "Ferma" e "Riprendi", avanzamento che non torna indietro, tempo/traffico entro ±30% della stima, Libreria fluida.

**Passi.**
1. **Notifica** (fase 1): `A shell dumpsys notification | Select-String "pkg=$pkg"` (solo le righe della nostra app; non salvare il resto). Deve esserci una notifica con barra. `Key KEYCODE_HOME`; dopo 2 minuti di nuovo: c'è ancora (lavoro dell'utente, UIDT), e il traffico continua (`Net-Phase`).
2. **Avanzamento** (BK-5), ogni 60 secondi: `Ui-Texts` → "N di M" nella pagina Backup; N non scende mai, M può solo salire; dopo i primi due media compare un tempo stimato. Il file `backup-progress` dell'app tiene gli stessi numeri (`A shell run-as $pkg find . -name 'backup-progress*'`).
3. **Ferma e Riprendi** (fase 4, durante il secondo video): `Tap-Text "Ferma"`. Atteso: "In pausa", il testo "non riparte da solo finché non tocchi «Riprendi»", la notifica sparisce o dice fermo, `Net-Phase` = fermo; dopo 60 secondi ancora fermo (5a-R4). `Tap-Text "Riprendi"`: riparte dal punto in cui era (N non torna a zero).
4. **Fluidità** (la Libreria con un backup in corso resta nella guardia): `A shell wm size` (per le coordinate), `A shell dumpsys gfxinfo $pkg reset`, 10 scorrimenti `A shell input swipe 540 1700 540 600 300` nella Libreria, `A shell dumpsys gfxinfo $pkg | Select-String "Janky frames"`. Confronto con `docs/bench/misure-s25-2026-10-13.md`.
5. **Totali** (fase 6, a backup finito: pagina "Tutto al sicuro", "N media al sicuro sul NAS"): `$t1 = Get-Date`; `Net-Between $t0 $t1` (byte inviati e ricevuti), tempo totale, costo per file (tempo / N), batteria (`dumpsys battery`). Confronto con la stima del passo 2 di F21 (tempo, traffico = inviati + ricevuti per la rilettura, batteria). La batteria con il cavo USB lento scende poco (il 20/10: dal 63% al 59% in 25 minuti a schermo acceso): la misura è grossolana, si annota (§8, punto 6).
6. Costo per file e scrittura a 1 e 2 flussi, se la Diagnostica li dà: dall'esportazione E1.

**Atteso.** Tempo e traffico entro ±30% della stima; tutti gli elementi `DONE`/`LINKED_EXISTING`/`GONE`; il file identico messo dall'utente (F25) collegato **senza traffico**; fotogrammi lenti dentro la guardia. Se "Inizia ora" finisce senza far niente dopo 90 secondi: BK-6.

**Può far emergere.** BK-3, BK-4, BK-5, BK-6, BK-8, BK-12, BK-13, 5a-R4, 5a-R7.

#### F23: Wi-Fi spento a metà di un video da 3 GB (fase 2)

**Scopo.** La ripresa riparte dall'ultimo punto: al massimo 64 MiB ricaricati (E-B1).

**Come si misura.** In Diagnostica non c'è un contatore dei "byte ricaricati" sullo schermo (20/10). Si usa il registro del traffico: i byte inviati per quel video, meno la sua dimensione.

**Passi.**
1. Aspettare che `Net-Phase` dia `carico` con `tx_MBs` ≥ 5: è il primo video grande. Annotare l'ora `$vStart`. **Non toccare altro** (niente "Ferma", niente morti) fino alla fine della sua rilettura.
2. Quando `Net-Between $vStart (Get-Date)` dice `tx_MiB` ≥ 1024: `TAGLIO-WIFI 30`.
3. Al ritorno del Wi-Fi l'app riprende da sola entro 2-3 minuti. Aspettare `Net-Phase` = `verifica` (rx alto, tx basso): annotare l'ora `$vEnd`.
4. `$r = Net-Between $vStart $vEnd`; ricaricati = `$r.tx_MiB - 3072` (il video è di 3.072 MiB; le intestazioni SMB aggiungono meno dell'1%).

**Atteso.** Ricaricati ≤ 64 MiB (con una tolleranza di circa 30 MiB per le intestazioni); nessun file a metà visibile (`VERIFICA-NAS -NotSettled` pulita). Il numero esatto "ricaricati" dell'ultimo giro sta nell'esportazione E1 (`bytesUploaded`), come riscontro.

**Può far emergere.** BK-6, BK-10.

#### F24: `am kill` durante il caricamento e durante la verifica (fasi 3 e 4)

**Scopo.** Dal PC nessun file a metà (solo `.mycloudgallery/tmp`); la ripresa c'è; "Ferma" seguito da una morte non fa ripartire il backup da solo.

**Passi.**
1. **Durante la verifica** (fase 3): appena `Net-Phase` = `verifica` per il primo video: `TAGLIO-MORTE run-as`. `VERIFICA-NAS -NotSettled`. Riaprire: la rilettura si rifà da capo (la verifica non si riprende) e il backup va avanti.
2. **Durante il caricamento** (fase 4, secondo video): `Net-Phase` = `carico`; `Key KEYCODE_HOME`; `TAGLIO-MORTE am-kill`. Se `A shell pidof $pkg` risponde ancora (l'app ha un lavoro in corso e `am kill` non la uccide), ripetere con `TAGLIO-MORTE run-as`. `VERIFICA-NAS -NotSettled`. Poi riaprire e aspettare la ripresa (entro 2-3 minuti): il video riprende dal **lavoro dei video**, non dopo 6 ore (BK-10).
3. **Ferma e subito la morte** (R9 della 4B, R4 della 5A): `Tap-Text "Ferma"`; entro 3 secondi `TAGLIO-MORTE force-stop`. Alla riapertura la pagina dice "In pausa" e per 2 minuti non riparte da sola; poi `Tap-Text "Riprendi"`.

**Atteso.** Nessun file a metà tra quelli visibili; ripresa; lo stato "In pausa" sopravvive alla morte.

**Può far emergere.** 5a-R3, 5a-R4, 4b-R9, BK-10.

#### F30-tel: 100 interruzioni durante il primo backup (fase 5)

**Scopo.** Il banco delle interruzioni sul telefono vero (invarianti 1-11 del §5.4 di `fase5-progetto.md`).

**Passi.** Appena finite le fasi 2-4, con il backup ancora in corso (terzo video e il resto):
```powershell
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step interrupt -Serial RFCY61FZDHW -Interruptions 100 -Seed 7 -Manifest $manifest -Verify -AllowFreed -SettleSeconds 600 | Tee-Object "$out\f30-tel.log"
```
Dura circa 50 minuti di interruzioni, 10 di attesa e circa 15 di verifica completa. Il calendario dipende solo dal seme: `-Seed 7` rifà la stessa prova. `-AllowFreed` serve per la foto che F26 ha lasciato nel cestino fino al ripristino (se è già stata ripristinata non cambia niente).

**Atteso.** `TUTTI GLI INVARIANTI RISPETTATI`; il telefono non è cambiato (le impronte tornano); nessun temporaneo `-bk-` rimasto; nessun elemento `FAILED` per un guasto passeggero. Annotare **a quale interruzione** la pagina ha detto "Tutto al sicuro" (log di `s25.ps1` in `build\chaos\s25-*.log` + orario): se è prima della 60ª, le interruzioni dopo non hanno più provato il caricamento; la prova si completa con F43-tel (A.10).
**Eccezione nota (O-2 del 20/10).** Se la verifica dice "2 file del telefono non sono sul NAS" e i due sono i `Screenshot_*.png` dei fixture (nessuna sorgente li salva con D13), è una **nota** da riportare, non un guasto del backup.

**Se rosso.** `HALF_FILE`, `DUPLICATE`, `LOSS` (oltre alla nota sopra), `REPLACED`: tenere `core-backup\build\chaos\backup-state-*.txt` e il log; si può rifare un pezzo con lo stesso seme e `-Interruptions` ridotte. Non proseguire con prove che scrivono.

**Può far emergere.** BK-1, BK-6, BK-10, 5a-R5.

#### Fine di A.9 (fase 6)

`Stop-Job $netjob; Remove-Job $netjob` (oppure lasciarlo per A.10). Chiedere all'utente l'**esportazione E1** della Diagnostica (30 secondi: ricetta ESPORTA, §2.1) e leggere nel file: stati degli elementi, costo per file, byte ricaricati, `lastDiskTouchAt` (F3), preparazione (F6a), `bytesUploaded`. `Esito` e `Log-Save` per F21, F22, F23, F24, F30-tel.

### A.10 F43-tel: modifiche come Samsung "Salva", versioni, interruzioni (80 minuti)

**Scopo.** Le versioni (D17) reggono alle interruzioni: l'originale in `Versioni/`, la modifica al solito percorso (invarianti 12 e 13). È anche la simulazione automatica di F34 (la parte con Samsung Gallery vera è B-S6).

**Prima.** A.9 finito e verificato; nessun `.trashed-*` in `mcg-prova`.

**Passi.**
```powershell
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step edit -Serial RFCY61FZDHW -DryRun -Edits 20 -Seed 42 -Manifest $manifest
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step edit -Serial RFCY61FZDHW -Edits 20 -Seed 42 -Manifest $manifest | Tee-Object "$out\f43-edit.log"
```
(aggiunge 20 righe `edit=` al manifesto; 24 byte casuali in coda a 20 JPEG, scansione del MediaStore.)
1. Nell'app: Impostazioni → Backup → `Tap-Text "Fai ora"`. La pagina mostra circa 20 media in coda; `Log-Save` per vedere partire i lavori delle versioni (tag `BackupVersioni`).
2. Interruzioni:
```powershell
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step interrupt -Serial RFCY61FZDHW -Interruptions 100 -Seed 11 -Manifest $manifest -Verify -AllowFreed -SettleSeconds 300 | Tee-Object "$out\f43-tel.log"
```
3. Dopo: Diagnostica → Backup: nessun lavoro fermo, nessuna voce "Versioni in attesa del computer" (`SOURCE_CHANGED`); dal viewer di una foto modificata: foglio Versioni con "originale" e "attuale".

**Attenzione al falso rosso sull'invariante 13.** Il verificatore (`BackupAreaVerifier`) cerca `Versioni/` alla **radice della share** (`test\Versioni`), mentre con la recinzione D13 le versioni stanno in `test\mcg-campo\Versioni\Backup\...` (`VersionPaths`, `docs/decisioni.md` «Fase 5B: P8»). Se le sole violazioni sono 20 del tipo "l'originale di una modifica del telefono non c'e' piu' sul NAS", non è un difetto del backup: l'utente guarda in Esplora risorse `\\<NAS>\test\mcg-campo\Versioni\Backup\<dispositivo>\<AAAA>\<MM>\` e conta 20 file "(originale)". Se i conti tornano, esito OK con nota (§8, punto 4).

**Atteso.** Invarianti 1-14 veri (salvo il falso rosso), il telefono invariato tranne le 20 modifiche, nessun lavoro fermo.

**Può far emergere.** 5b-R3, 5b-R9, 5b-R10, 5b-R16, VE-1, VE-9, VE-10, LA-3, LA-4.

### A.11 FF-8: i lavori a processo freddo (12 minuti)

**Scopo.** Un lavoro partito da processo morto aspetta la rete e fa il suo lavoro, invece di chiudersi "riuscito" senza fare niente (RE-3), e lascia traccia in Diagnostica (RE-4). E rivedere il difetto D-2 del 20/10 (la prima scansione all'avvio a freddo che non si ripete).

**Passi.**
1. `A shell am force-stop $pkg` (nessun processo: `A shell pidof $pkg` vuoto). Wi-Fi acceso. `Log-Clear`.
2. **D-2.** `Start-Ours`; guardare nel log l'ordine di `MyCloud/NasScanWorker` e `MyCloud/NasSession: sessione pronta`. Se `NasScanWorker: tappa non riuscita` viene **prima** di "sessione pronta" e poi per 5 minuti, con l'app in primo piano, non compare nessun altro `NasScanWorker`: D-2 è ancora presente (annotarlo con le ore). Poi `Key KEYCODE_HOME`, 5 secondi, `Start-Ours`: la scansione riparte (come il 20/10).
3. `A shell dumpsys jobscheduler $pkg | Set-Content "$out\jobs-freddo.txt"`: annotare gli id dei lavori di WorkManager (`SystemJobService`).
4. `A shell am force-stop $pkg` di nuovo; uno per volta: `A shell cmd jobscheduler run -f $pkg <id>`; ogni volta `Log-Save "FF-8-<id>"` e guardare in `MyCloud/` che il lavoro abbia fatto qualcosa (o dica un motivo vero di attesa), non "Offline" con il Wi-Fi acceso. (Un lavoro per volta anche senza gli id: `A shell am broadcast -a dev.pampa.mycloudgallery.debug.PERIODIC_NOW -p $pkg --es jobs sync`, poi `prep`, `scan`, `trash`; il ricevitore fa nascere il processo.)
5. `Start-Ours`; Diagnostica → "Ultimi esiti ed errori": il lavoro di prima c'è, con il suo titolo.
6. La parte NA-12 (il servizio delle operazioni dopo la morte del processo) è dentro B-S5 (F16-tel); NA-7 (riepilogo con un congelamento) dentro B-S3 (F9).

**Atteso.** Con il Wi-Fi acceso nessun lavoro finisce in attesa di rete; gli esiti sono in Diagnostica. D-2: se c'è ancora, è un difetto da riportare (il rimedio proposto il 20/10: aspettare la sessione o riprovare con `Result.retry()`).

**Può far emergere.** RE-3, RE-4, NA-7, NA-12, D-2 (di `docs/prove-campo-esiti.md`).

### A.12 F39-adb: le porte esportate dalla riga di comando (12 minuti)

**Scopo.** Le porte che l'app apre agli altri si comportano come previsto quando le si chiama a mano.

**Cosa si sa già (20/10).** Dalla shell `content query` sul provider dei documenti **non** dà `SecurityException`: la shell ha `MANAGE_DOCUMENTS` (`dumpsys package com.android.shell`) e risponde con la radice (`root_id`, `document_id` opachi, nessun percorso). L'attesa "SecurityException" della revisione 5B vale per un'app che quel permesso non ce l'ha, e quella non c'è (C-26). Il `FileProvider`, non esportato, la dà.

**Passi** (tutti verso componenti **nostri**, con `-n`).
```powershell
# 1. il provider dei documenti, dalla shell: risponde (la shell ha MANAGE_DOCUMENTS). Si guarda che non ci siano percorsi ne' nomi veri
A shell content query --uri "content://$pkg.integrations.documents/root"
# 1b. il FileProvider non esportato: deve dare SecurityException
A shell content query --uri "content://$pkg.integrations.files/share/x"
# 2. il selettore, lanciato esplicitamente (blocco dell'app spento: si apre; l'utente non deve toccare niente)
A shell am start -n "$pkg/dev.pampa.mycloudgallery.feature.integrations.picker.PickerActivity" -a android.intent.action.GET_CONTENT -t "image/*"
Ui-Texts ; Key KEYCODE_BACK
# 3. il PICK classico con il tipo del MediaStore (IN-13)
A shell am start -n "$pkg/dev.pampa.mycloudgallery.feature.integrations.picker.PickerActivity" -a android.intent.action.PICK -t "vnd.android.cursor.dir/image"
Ui-Texts ; Key KEYCODE_BACK
# 4. la ricezione con un file:// : deve rifiutare con il motivo e non copiare niente
A shell am start -n "$pkg/dev.pampa.mycloudgallery.feature.integrations.receive.ShareReceiverActivity" -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM "file:///sdcard/DCIM/mcg-prova/IMG_mcg_0001.jpg"
Ui-Texts ; Key KEYCODE_BACK
A shell run-as $pkg ls -la no_backup/incoming                  # vuota o assente
# 5. una condivisione con un indirizzo del NOSTRO provider dei file: rifiutata
A shell am start -n "$pkg/dev.pampa.mycloudgallery.feature.integrations.receive.ShareReceiverActivity" -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM "content://$pkg.integrations.files/share/x.jpg"
Ui-Texts ; Key KEYCODE_BACK
```

**Atteso.** (1) risposta con id opachi e nessun percorso; (1b) `SecurityException`; (2) e (3) il selettore mostra la Libreria e Indietro lo chiude (`RESULT_CANCELED`); (4) e (5) rifiuto con una frase, nessun file in `incoming/`.

**Può far emergere.** 5b-R8, IN-3, IN-13, LA-5. (Con il blocco dell'app acceso e la tolleranza di 2 minuti: B-S13.)

### A.13 La suite d'integrazione di `core-nas` sul NAS vero, e la pulizia di `mcg-it` (20 minuti)

**Scopo.** Le primitive con `FILE_OPEN_REPARSE_POINT` non cambiano niente per i file normali (rinomina, cestino, "Elimina subito"); la guardia tiene; alla fine `mcg-it` è vuota per `test` e `test2`.

**Quando.** Dopo F16-tel e la sua verifica (B-S5): la suite pulisce `mcg-it/` e cancellerebbe l'area di F16.

```powershell
.\gradlew.bat :core-nas:testDebugUnitTest --tests "*NasIntegrationSuiteTest*" -PnasIntegration --console=plain --rerun | Tee-Object "$out\suite-core-nas.log"
```

**Atteso.** Verde (o "saltato" col motivo se manca `test2`); nessun `AUTH_FAILED`; `mcg-it` vuota.

**Può far emergere.** 4b-R3, 4b-R4.

### A.14 Le tre prove lunghe dal PC: F16-PC, F30-PC, F43-PC (di notte, 6 ore)

**Scopo.** Il runner delle interruzioni contro l'area di prova del NAS vero, sotto `mcg-it/<giro>/`, con la guardia. Una alla volta, mai insieme alle prove sul telefono.

```powershell
# F16-PC (4B, operazioni sui file): circa 142 minuti per 400 interruzioni
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode run -Interruptions 400 -TimeoutMinutes 240 | Tee-Object "$out\f16-pc.log"
# F30-PC (5A, backup): 400 interruzioni, con il video grande di backup.bigFileMb
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage run -Interruptions 400 -TimeoutMinutes 600 | Tee-Object "$out\f30-pc.log"
# F43-PC (5B, versioni, scambi, guardiano, Libera spazio): circa 48 minuti
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage run5b -Interruptions 400 -TimeoutMinutes 120 | Tee-Object "$out\f43-pc.log"
```
Prima di ognuna, il `-DryRun` dello stesso comando.

**Atteso.** Zero giri rossi; rapporti in `core-nasops\build\chaos\` e `core-backup\build\chaos\` (`*-area-report-*.txt`, `*-progress-*.txt`, `*-log-*.txt`). Un giro rosso si rifà con lo stesso seme (`-Seed`, `-FirstRound`, `-Interruptions 1`) e si verifica da solo con `-Stage state`.

**Può far emergere.** 4b-R8 (congelamento), 5a-R1, 5a-R3, 5b-R5, 5b-R14, 5b-R16.

### A.15 F13 (parte S25): benchmark con la libreria mista (40 minuti)

**Scopo.** Le prestazioni restano dentro la guardia del §5.6 di `fase4-progetto.md` e delle soglie di `benchmark/soglie.json`. (La parte del Tab S9 è C-15.)

**Prima.** Gradle libero. Telefono carico, **sbloccato, schermo acceso, a 120 Hz con "Fluidità movimento: Adattiva"** (lo ha impostato l'utente: lo script lo legge soltanto). Nessun altro uso del telefono per 40 minuti.

```powershell
powershell -ExecutionPolicy Bypass -File tools\check.ps1 -SkipGradle -Bench -BenchSerial RFCY61FZDHW | Tee-Object "$out\f13-bench.log"
```
Lo script installa e poi toglie solo `dev.pampa.mycloudgallery.bench` e `dev.pampa.mycloudgallery.benchmark`, mai la debug.

**Atteso.** TTFD p90 ≤ 750 ms, apertura del viewer p90 ≤ 25 ms, jank di scorrimento e pinch ≤ riferimento del 13/10 + 10% e ≤ 1% dove già lo era (`docs/bench/riferimento-s25-fase3.json`). Il confronto lo stampa `tools\bench\compare.ps1`.

## 5. Blocco B: "serve l'utente"

Quando serve un'altra app, il PC o un gesto fisico. Le sessioni durano 10-15 minuti. Regole comuni:

- L'**orchestratore comanda la nostra app** (toccando con `Tap-Text` e leggendo con `Ui-Texts`) e dice all'utente, una cosa alla volta, cosa fare nelle altre app. L'utente non deve imparare i menu della nostra app.
- I **dialoghi di sistema** li conferma l'utente. L'orchestratore non li tocca mai.
- Quello che nel piano è "dal PC" dentro `mcg-campo/` lo fa l'utente in Esplora risorse. L'indirizzo del NAS è `\\<indirizzo del NAS>` (Impostazioni → Account del NAS). Solo le share `test`, `test2`, `test-comune`.
- I **numeri** della Diagnostica non si leggono dallo schermo (titoli e codici sì): dove servono, l'utente salva l'**esportazione** (ricetta ESPORTA, §2.1: un dialogo di sistema, 30 secondi) e l'orchestratore la legge con `adb pull`. I contatori delle prove guidate (`t09.*`, `t14.*`, `t15.*`, `t16.*`) si leggono con `A shell run-as $pkg cat no_backup/field-probe/counters.properties`.
- Le istruzioni qui sotto sono quelle da **dire all'utente**, in italiano semplice. Tra virgolette quando vanno lette quasi come sono.
- Dopo ogni sessione: `Esito`, `Log-Save`, e se l'utente ha cambiato qualcosa in `mcg-prova` (modificato, cancellato, aggiunto) si aggiorna il manifesto (`Add-Edit`, `Add-Phone`, `Drop-Phone`, §2.1). Per trovare il file toccato: `A shell "find /sdcard/DCIM/mcg-prova /sdcard/Pictures/mcg-prova -type f -mmin -5"`.
- Funzioni di controllo da usare dove si dice (da aggiungere a `h.ps1` quando servono):

```powershell
Add-Type -AssemblyName System.Drawing
function Exif-Info([string]$file) {                       # JPEG/PNG: data di scatto, orientamento, quanti tag GPS
  $img = [System.Drawing.Image]::FromFile($file)
  try {
    $ids = $img.PropertyIdList
    $date = if ($ids -contains 36867) { ([System.Text.Encoding]::ASCII.GetString($img.GetPropertyItem(36867).Value)).Trim([char]0) } else { "-" }
    $orient = if ($ids -contains 274) { [BitConverter]::ToUInt16($img.GetPropertyItem(274).Value, 0) } else { "-" }
    [pscustomobject]@{ data = $date; orientamento = $orient; tag_gps = @($ids | Where-Object { $_ -ge 0 -and $_ -le 30 }).Count }
  } finally { $img.Dispose() }
}
function Check-NoGps([string]$file) {                     # controllo INDICATIVO; la prova vera e' exiftool (non e' installato sul PC, §8)
  $s = [System.Text.Encoding]::GetEncoding(28591).GetString([System.IO.File]::ReadAllBytes($file))
  $found = @()
  foreach ($p in @("GPSLatitude", "GPSLongitude", "exif:GPS", "GPSInfo", ([string][char]0xA9 + "xyz"), "gps ", "camm", "gpmd", "nmea")) { if ($s.Contains($p)) { $found += $p } }
  if ($file -match '\.jpe?g$') { $x = Exif-Info $file; if ($x.tag_gps -gt 0) { $found += "tag GPS dell'EXIF: $($x.tag_gps)" } }
  if ($found.Count -eq 0) { "nessuna traccia trovata" } else { "TROVATO: " + ($found -join ", ") }
}
```
Con `exiftool` (se l'utente lo installa o ne autorizza il download): `exiftool -a -G1 -ee -gps:all -xyz -location* -xmp:all -iptc:all <file>` deve essere vuoto di posizione.

### B-S1 · T-09: il cestino di Samsung Gallery e di Android; F26 (inizio) e GA-7 (15 minuti)

**Scopo.** Misurare come si comportano le righe del MediaStore quando si cestina, si ripristina e si svuota in Samsung Gallery e con i dialoghi dell'app (finestra di tolleranza di E-P11, necessità di `MANAGE_MEDIA`); lasciare una foto nel cestino per F26.

**Cosa si sa già (20/10).** Il primo tentativo ha fatto (d), (e) e la scrittura. Su questo S25 (One UI) il cestino di sistema **sposta i file nel cestino di Samsung Gallery** (`Android/.Trash/com.sec.android.gallery3d`): escono da `mcg-prova`, non resta nessun `.trashed-…` al loro posto e la prova non li ritrova (`t09.trash_is_trashed` = 0, `t09.trash_visible_default` = 0, la scadenza non si legge). "4 · Ripristina quelle foto" risponde "Niente da fare: nessuna foto adatta in mcg-prova" (D-3 del 20/10). Il dialogo e la scrittura invece vanno (`trash_result` = -1, `write_dialog` = -1, `write_code` = 0). Qui si rifanno (a)-(c) e (f), si guarda se D-3 c'è ancora, e **i ripristini li fa l'utente dal Cestino della Galleria**.

**Prima (orchestratore).** Semina fatta (`env.rows` ≥ 500). Impostazioni → Sviluppo → "Prove guidate T-09, T-14, T-15, T-16" (solo nella build debug). `Tap-Text "Controlla l'ambiente"`; annotare `env.sdk` e `env.rows`. Poi "1 · Fotografia di riferimento". (La schermata è lunga: per arrivare ai pulsanti si scorre con `A shell input swipe`.)

**Istruzioni per l'utente** (l'orchestratore tocca i pulsanti della nostra app; l'utente fa solo ciò che segue).
1. "Apri la Galleria di Samsung, album **mcg-prova**. Cestina **2 foto** (tienile premute, poi Cestino). Dimmi quando hai fatto." → *orchestratore: "2 · Confronta con il riferimento".*
2. "Nel Cestino della Galleria **ripristina una** delle due foto." → *Confronta di nuovo.*
3. "Ruota il telefono in orizzontale e di nuovo in verticale." → *la schermata delle prove si ricrea: il riferimento e le righe devono esserci ancora (LA-7).*
4. "Nel Cestino della Galleria **svuota il cestino** (elimina la foto rimasta) e conferma." → *Confronta. L'orchestratore trova quale file non c'è più (`find` contro il manifesto) e lo toglie dal manifesto con `Drop-Phone`.*
5. (d, e) *"3 · Cestina 5 foto con l'app".* "Compare un dialogo di Android che chiede di spostare 5 foto nel cestino: **conferma**. Poi guarda nel Cestino della Galleria: **vedi quelle 5 foto**? Dimmi sì o no." → *l'orchestratore tocca "Le mostra" o "Non le mostra". Poi "4 · Ripristina quelle foto": si aspetta "Niente da fare" (D-3), si annota.* "Nel Cestino della Galleria **ripristina quelle 5 foto**." → *`s25.ps1 -Mode backup -Step verify -SkipNas -AllowFreed`: devono tornare tutte le impronte.*
6. (f) *"5 · Cestina fino a 500 foto con l'app".* "Il dialogo parla di centinaia di foto (ci sono anche i tre video grandi): **conferma**." *Annotare `many_requested`, `many_result`.* "Nel Cestino della Galleria: **Seleziona tutto → Ripristina** (anche i video: può volerci un minuto)." → *di nuovo `s25.ps1 ... -Step verify -SkipNas -AllowFreed`.*
7. *"6 · Permesso di scrittura e riscrittura".* "Conferma il dialogo."
8. (GA-7) *L'orchestratore, in Libreria, seleziona 3 foto del telefono → Elimina.* "Conferma il dialogo." *Raccolte → Eliminati di recente: le miniature ci sono? (con D-3 potrebbero non esserci: si annota) → Ripristina, se l'app lo permette.* "Se la foto non torna, ripristinala dal Cestino della Galleria."
9. (F26) "Scegli **un'altra foto** dell'album mcg-prova e **cestinala** nella Galleria. **Lasciala nel cestino**: la riprenderemo dopo."

**Poi l'orchestratore.** Il resoconto è di soli numeri e si legge in due modi:
```powershell
# a) il file che la schermata salva (in fondo, dopo "Aggiorna": "Salva su file")
A pull /sdcard/Android/data/$pkg/files/field-probe-report.txt "$out\t09-report.txt"
# b) i contatori grezzi, come il 20/10
A shell run-as $pkg cat no_backup/field-probe/counters.properties | Set-Content "$out\t09-counters.txt"
```
Annotare il nome della foto lasciata nel cestino (quella che manca nel confronto con il manifesto).

**Atteso** (chiavi del resoconto, `docs/android.md`): `t09.trash_requested` = 5 e `t09.trash_result` = -1; `t09.trash_is_trashed`: il progetto si aspetta 5, ma su questo telefono il 20/10 era 0 (D-3): **annotare cosa si vede oggi**; `t09.trash_expires_days_min`/`_max` = 30 se si leggono (il 20/10 no); `t09.trash_in_samsung` = la risposta dell'utente; `t09.many_requested` = il minore tra 500 e `env.rows` e `t09.many_result` = -1 (quindi `MANAGE_MEDIA` **non serve**); `t09.write_dialog` = -1, `t09.write_code` = 0, dimensioni uguali prima e dopo (un ritardo di pochi secondi del MediaStore è normale). Le righe `t09.diff_*` non hanno un valore "giusto": decidono la finestra di tolleranza (righe sparite, nuove, tornate con un `_ID` diverso). Il resoconto va riportato in `docs/android.md` (a cura di chi riceve gli esiti). **Dopo la sessione** tutte le impronte del manifesto devono tornare (tranne la foto svuotata e quella lasciata nel cestino).

**Può far emergere.** VE-2 (dialogo con l'indirizzo generico), VE-5, GA-7, LA-6, LA-7, LA-8, 5b-I2, D-3 (di `docs/prove-campo-esiti.md`: su Samsung l'app non ritrova le foto che ha mandato nel cestino; vale anche per "Libera spazio").

### B-S2 · F25 (file messi dal PC prima del backup) e F26 (foto cancellata e ripristinata) (11 minuti)

**Scopo.** F25: con il primo backup, un file **diverso** con lo stesso nome nella destinazione fa arrivare il nostro come "nome (2)" e l'altro resta com'è; una copia **identica** si collega senza traffico. F26: la foto cancellata in Samsung Gallery prima del backup è "Persa prima del backup" con il suggerimento, e dopo il ripristino finisce al sicuro una sola volta.

**Prima (orchestratore).** Leggere nella pagina Backup il nome della cartella del dispositivo (`Sul NAS`). Preparare i due file (da `build\chaos\seed-<giro>\staging\...`, i byte della semina):
```powershell
$dev = "<nome della cartella del dispositivo>"
$src = "$repo\build\chaos\seed-$giro\staging\DCIM\mcg-prova"
# la data per le cartelle AAAA/MM e' DATE_TAKEN del MediaStore (millisecondi); se e' 0 o vuota, la data di modifica del file (date_modified, secondi)
A shell content query --uri content://media/external/images/media --projection _display_name:datetaken:date_modified --where "_display_name='IMG_mcg_0001.jpg'"
# [DateTimeOffset]::FromUnixTimeMilliseconds(<datetaken>).ToLocalTime()   oppure   FromUnixTimeSeconds(<date_modified>)
New-Item -ItemType Directory -Force "$out\f25\diverso", "$out\f25\identica" | Out-Null
$b = [System.IO.File]::ReadAllBytes("$src\IMG_mcg_0001.jpg"); $t = New-Object byte[] 24; (New-Object System.Random(99)).NextBytes($t)
[System.IO.File]::WriteAllBytes("$out\f25\diverso\IMG_mcg_0001.jpg", [byte[]]($b + $t))      # stesso nome, byte diversi
Copy-Item "$src\IMG_mcg_0003.jpg" "$out\f25\identica\IMG_mcg_0003.jpg"                        # copia esatta di una foto del telefono
(Get-FileHash "$out\f25\diverso\IMG_mcg_0001.jpg" -Algorithm SHA256).Hash.ToLower()
(Get-FileHash "$out\f25\identica\IMG_mcg_0003.jpg" -Algorithm SHA256).Hash.ToLower()
```
(Le foto "uniche" della semina sono JPEG minuscoli e quasi certamente senza data di scatto: di solito la cartella `AAAA/MM` è quella di oggi. Se il mese è appena cambiato, si guarda dove il backup ha messo il primo file.)

**Istruzioni per l'utente.**
1. "Apri Esplora risorse e vai in `\\<indirizzo del NAS>\test\mcg-campo\Backup\<nome del dispositivo>\<AAAA>\<MM>\`. Se manca una cartella, creala con quel nome."
2. "Copia lì dentro i due file che ti indico: quello nella cartella `diverso` e quello nella cartella `identica`. Non toccare altro. Dimmi quando hai finito."

*L'orchestratore aggiunge al manifesto le due righe `pre=` (file che c'erano prima del backup):*
```powershell
Add-Pre "<sha del file diverso>"  "mcg-campo/Backup/$dev/<AAAA>/<MM>/IMG_mcg_0001.jpg"
Add-Pre "<sha del file identico>" "mcg-campo/Backup/$dev/<AAAA>/<MM>/IMG_mcg_0003.jpg"
```
Se alla prima verifica il file "diverso" risulta "byte che non sono di nessun file del telefono", il percorso scritto nella riga `pre=` non è quello che il verificatore si aspetta: correggerlo (stesso modo con cui compare nel rapporto) e rilanciare.

**Poi parte il primo backup (A.9, "Inizia ora").** L'utente resta vicino al telefono per i primi minuti:
3. (F26) *Quando la pagina Backup mostra il problema "Persa prima del backup" con il suggerimento (l'orchestratore lo legge con `Ui-Texts`)*: "Apri il **Cestino della Galleria** e **ripristina la foto** che ci avevi lasciato."

**Poi l'orchestratore.** Il problema sparisce da solo; la foto finisce al sicuro **una volta sola** (nessun secondo elemento, nessuna copia "(2)"); `VERIFICA-NAS -NotSettled`. A fine A.9: la foto del file "diverso" è su NAS come `IMG_mcg_0001 (2).jpg` (o simile) e quella messa dall'utente ha i suoi byte di prima; la copia identica non è stata ricaricata (nel riepilogo "già sul NAS" la contava; nessun doppione).

**Atteso.** "(2)" per il diverso; l'identico collegato senza traffico; "Persa prima del backup" con il suggerimento e poi tutto al sicuro.

**Può far emergere.** 5a-R1, BK-7, BK-3, BK-12.

### B-S3 · F4, F5, F9: le modifiche dal PC (13 minuti)

**Scopo.** F4: rinominare una cartella da 1.000 foto non rifà il rilevamento né la preparazione e conserva i preferiti. F5: una foto modificata dal PC diventa una nuova versione, una cancellata esce dalla Libreria. F9: 500 file rinominati `.locked` fanno scattare il congelamento (senza segnare assenze), e il gesto lo toglie.

**Prima (orchestratore).** F1 finita. Cartelle `mille`, `campioni`, `F9` sul NAS (2.8). Segnare come preferito una foto della cartella `mille` (viewer → cuore). Annotare il numero di foto in Libreria. I contatori di rilevamento e di preparazione non sono sullo schermo: l'utente salva l'**esportazione** della Diagnostica (ricetta ESPORTA, §2.1) prima di cominciare (P1) e dopo F5 (P2); si confrontano i due file. Scegliere due foto di `campioni` (una da modificare, una da cancellare) e dirne i nomi.

**Istruzioni per l'utente.**
1. (F4) "In Esplora risorse apri `\\<indirizzo del NAS>\test\mcg-campo\`. **Rinomina** la cartella `mille` in `mille-rinominata`. Poi dimmi quando hai finito."
2. (F5) "Nella cartella `campioni` apri la foto **<nome 1>** con Paint, **ruotala** e salva con Salva. Poi **cancella** la foto **<nome 2>**. Dimmi quando hai finito."
3. (F9) "Apri PowerShell e incolla questo comando: `Get-ChildItem '\\<indirizzo del NAS>\test\mcg-campo\F9' -File | Select-Object -First 500 | Rename-Item -NewName { $_.Name + '.locked' }`. È solo nella cartella F9. Dimmi quando ha finito."

**Dopo ogni modifica dell'utente (l'orchestratore).** L'app **non** guarda da sola il NAS «dopo 3 minuti»: la verifica parte a ogni apertura dell'app se l'ultima ha più di 15 minuti, nella fascia notte e quando i dischi sono svegli; in background al massimo ogni 6 ore (E-R15, per non svegliare i dischi del NAS: `fase4-progetto.md` §2.3; l'esito del 21/10 è in `docs/prove-campo-esiti.md`, O-B4). Quindi: **riapri l'app** (se l'ultima verifica ha più di 15 minuti; un avvio a freddo parte comunque: `A shell am force-stop $pkg ; Start-Ours`) **oppure aspetta la fascia notte**. Nella build debug puoi anche chiedere la verifica subito: `A shell am broadcast -a dev.pampa.mycloudgallery.debug.PERIODIC_NOW -p $pkg --es jobs scan` (vedi «Fai ora i lavori periodici» in §2.1): le regole delle attività valgono lo stesso.

**Poi l'orchestratore** (dopo ogni passo, `Ui-Texts`, `Shot`, Diagnostica):
- F4: i contatori di rilevamento e preparazione (P1 contro P2) non salgono per le 1.000 foto; il preferito c'è ancora; il numero di foto in Libreria uguale; nessun "assente".
- F5: la foto modificata ha una miniatura/data nuova (nuova versione); l'altra è uscita dalla Libreria.
- F9: la capsula dice "Lavori fermi" e Libreria/Diagnostica avvisano del congelamento; **nessuna** foto segnata assente; il riepilogo giornaliero (non una notifica subito, Q6 (b)). Toccare la capsula: apre la **Diagnostica** (NA-9). Fare il gesto "Ho controllato, continua" (Operazioni in corso, scheda del congelamento); poi `A shell am force-stop $pkg`, riaprire: il giro dopo, in un processo nuovo, **non** ricongela con lo stesso numero (NA-4) e il piede non resta su "Lavori fermi" (NA-5). Per l'ultima parte di 4a-R9 ("conferma e poi cifratura di massa"): "Rinomina in `.locked` anche altri 300 file della stessa cartella": il congelamento deve **scattare di nuovo** (la conferma valeva solo per il numero visto).

**Atteso.** Come sopra.

**Può far emergere.** 4a-R6, 4a-R8, NA-3, NA-4, NA-5, NA-7, NA-9, 4a-R9.

### B-S4 · F15: operazioni sui file dall'app (15 minuti)

**Scopo.** Crea, rinomina (anche solo maiuscole), sposta nella share e tra share, elimina, ripristina, file aperto in un editor sul PC, nome in conflitto, cartella intera; foglio dell'eliminazione; nessun congelamento per le operazioni dell'app.

**Prima (orchestratore).** Seminare l'area di prova dal PC (guardia, solo `mcg-it/<giro>/`):
```powershell
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode seed -Files 300 -Run $giro -Manifest build\chaos\manifest-$giro.txt -DryRun
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode seed -Files 300 -Run $giro -Manifest build\chaos\manifest-$giro.txt | Tee-Object "$out\f15-seed.log"
```
(300 originali in `test/mcg-it/<giro>/r0/sorgente/`.) `AUTH_FAILED` = stop.

**Istruzioni per l'utente** (tutto nell'app, Cartelle NAS; l'orchestratore dice il nome esatto del giro).
1. "Apri **Cartelle NAS → area privata → mcg-it → <giro> → r0 → sorgente**."
2. "Crea una cartella `prova-a`. Rinominala `Prova-A` (solo le maiuscole). Poi `prova-b`."
3. "Seleziona **5 file** e **spostali dentro `prova-b`**."
4. "Seleziona **20 file** e **spostali nell'area comune**: scegli `test-comune`, crea la cartella `mcg-it` e dentro `<giro>` e `dest`, poi 'Sposta qui'. Aspetta la fine."
5. "Seleziona **2 file** ed **eliminali** (cestino). Poi **Eliminati di recente** e **ripristina** uno." *(GA-7: le miniature ci sono?)*
6. "Rinomina un file con il **nome di un altro** file della cartella." *(nome in conflitto: l'app non deve sovrascrivere)*
7. "Sposta tutta la cartella `prova-b` dentro `dest`." *(cartella intera)*
8. "Seleziona un file → Elimina → **Elimina subito**. Sul primo pannello tocca **due volte in fretta**." *(non deve confermare da solo: 4b-R6)*
9. "**Sul PC** apri con Paint il file `\\<indirizzo del NAS>\test\mcg-it\<giro>\r0\sorgente\<nome>`. **Sul telefono** rinominalo: deve dire che aspetta (file aperto). **Chiudi Paint**: entro qualche minuto riparte da solo." *(E-N13, NA-2)*
10. (R2 della 4B) "Seleziona la cartella `mille-rinominata` (è in `test/mcg-campo`) → Elimina: leggi il foglio e **annulla**." *(deve dire quanti file e quanto pesa, non '0 B')*
11. (R8 della 4B) "Sposta **60** foto di `mille-rinominata` in `test-comune/mcg-campo`, poi tutta la cartella da **1.000**. Guarda se compare 'Lavori fermi'." *(le operazioni dell'app non devono congelare la share)*

**Poi l'orchestratore.**
```powershell
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode verify -Manifest build\chaos\manifest-$giro.txt -NotSettled | Tee-Object "$out\f15-verify.log"
```
e, nella nostra app, Operazioni in corso / Diagnostica → NAS (codici `NAS_OPS_*`): nessuna operazione bloccata, nessun errore; le operazioni nuove mentre una lunga è in corso risultano "In coda" (NA-1).

**Atteso.** Come §2.7 di `fase4-progetto.md`: invarianti 1-3 veri (nessun file a metà visibile, nessun doppione, nessuna perdita), nessuna sovrascrittura, cestino con i file tolti, nessun congelamento per le operazioni dell'app.

**Può far emergere.** NA-1, NA-2, NA-3, 4b-R2, 4b-R6, 4b-R7, 4b-R8, 4b-R10, 4b-R11, GA-7.

### B-S5 · F20 (cestino in minuti), F12 (qualità) e F16-tel (mossa di massa con le interruzioni) (14 minuti, poi 55 da solo)

**F20 · Scopo.** La pulizia automatica del cestino, con la ritenzione accorciata a 5 minuti (solo build debug), toglie solo le voci del proprio diario. (Il record falso messo dal PC è C-17.)

**Prima.**
```powershell
A shell "echo 5 > /sdcard/Android/data/$pkg/files/debug-trash-retention-minutes.txt"
A shell am force-stop $pkg ; Start-Ours                # il marcatore si legge all'avvio della parte NAS
```
**Utente.** "In Cartelle NAS, dentro `mcg-it/<giro>/r0/sorgente`, **elimina 5 file** (non Elimina subito: Elimina nel cestino)." *(3 minuti)*
**Poi.** Passati 6 minuti (mentre l'utente fa F12) l'orchestratore **fa partire la pulizia ora**, senza toccare il database di WorkManager: il lavoro è giornaliero e WorkManager rifiuta di anticiparlo («being executed before schedule»); il 22/10 si era dovuto modificare `last_enqueue_time` a mano.
```powershell
A shell am broadcast -a dev.pampa.mycloudgallery.debug.PERIODIC_NOW -p $pkg --es jobs trash     # risponde con i lavori accodati
```
In "Eliminati di recente" del NAS le 5 voci sono scadute e sparite; i file non sono più nel cestino. Rimuovere il marcatore e riavviare:
```powershell
A shell rm /sdcard/Android/data/$pkg/files/debug-trash-retention-minutes.txt
A shell am force-stop $pkg ; Start-Ours
```
**Atteso.** Sparite solo le 5 voci del diario; nient'altro (anche altre voci più vecchie di 5 minuti dello stesso diario, quelle di B-S4, sono di prova). **Può far emergere.** 4b-R1, 4b-R3, 4b-R4, 4b-R5.

**F12 · Scopo.** Cambiando la qualità delle anteprime, lo spazio sul NAS converge alla stima, ±15%. **Passi (6 minuti dell'utente).** L'orchestratore annota la stima in Impostazioni → Anteprime e cambia Standard → Alta (poi Risparmio, poi rimette com'era), aspettando ogni volta che la pagina smetta di dire «sta adattando lo spazio». Dal 22/10 il cambio di qualità accoda **subito** la sincronizzazione dell'indice (e, salendo ad «Alta», lo scaricamento dell'HQ), con le regole delle attività; per anticiparla o rifarla: `A shell am broadcast -a dev.pampa.mycloudgallery.debug.PERIODIC_NOW -p $pkg --es jobs sync,hq`. Con «Alta» la Diagnostica **non** deve più dire `ACT_DISABLED` per `HQ_PREVIEWS`. L'utente, dopo ogni cambio: "In Esplora risorse (con 'Mostra elementi nascosti') guarda le **Proprietà** della cartella `\\<indirizzo del NAS>\test\.mycloudgallery` e dimmi la dimensione." **Atteso.** La dimensione segue la stima ±15%; se l'area di prova è piccola, i costi fissi dominano: si annota e basta. **Può far emergere.** 4a-R2, RE-8.

**F16-tel · Scopo.** Almeno 100 interruzioni (processo ucciso o Wi-Fi spento) durante una mossa di massa tra due share, poi gli invarianti (§5.5 di `fase4-progetto.md`).

**Prima.** Nuovo giro e nuovo seme dei file:
```powershell
$giro16 = "{0}-{1:x8}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), (Get-Random -Minimum 0 -Maximum ([int]::MaxValue))
powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode seed -Files 300 -Run $giro16 -Manifest build\chaos\manifest-$giro16.txt | Tee-Object "$out\f16-seed.log"
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Interruptions 100 -Seed 5 -DryRun       # il calendario
```
**Istruzioni per l'utente (5 minuti).**
1. "In Cartelle NAS apri `mcg-it → <giro16> → r0 → sorgente`, **seleziona tutto**."
2. "**Sposta** nell'area comune: `test-comune`, cartella `mcg-it/<giro16>/dest` (creala), 'Sposta qui'."
3. "Appena parte (vedi la notifica 'Operazioni sul NAS') dimmi **'partita'**. Poi **non toccare più il telefono** per un'ora: lascialo acceso, sbloccato e in carica."

**Poi l'orchestratore** (R9 della 4B, poi il ciclo):
- subito: `Tap-Text "Annulla il resto"` nelle Operazioni in corso (il pulsante si chiamava «Ferma») e entro 3 secondi `TAGLIO-MORTE force-stop`; riaprire: l'operazione è **annullata** («Annullata dopo N di M: il resto non è stato fatto»), quello che era già fatto resta fatto e **non riparte da sola**. Per le operazioni sul NAS **non c'è «Riprendi»**: per il progetto (R9 della 4B) fermare un'operazione la annulla; «Ferma» e «Riprendi» sono del **backup** (R4 della 5A). Per finire il lavoro si rifà l'operazione sul resto. *(O3, 4b-R9; decisione del 22/10 in `decisioni.md`)*
- poi il ciclo vero:
```powershell
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Serial RFCY61FZDHW -Interruptions 100 -Seed 5 -Manifest build\chaos\manifest-$giro16.txt -Verify | Tee-Object "$out\f16-tel.log"
```
Dura circa 55 minuti (interruzioni, attesa di 3 minuti, verifica).
- durante il ciclo, dopo una morte: la notifica con "Ferma" torna; la mossa avanza a fette vicine (non sempre più lontane): NA-12.

**Atteso.** `TUTTI GLI INVARIANTI RISPETTATI` (mossa finita o ferma in modo ripresa); nessuna perdita; in Diagnostica → NAS → Operazioni l'operazione risulta conclusa.

**Può far emergere.** NA-12, 4b-R9, 4b-R8.

### B-S6 · F34 e T-16 (a): "Salva", "Salva copia", "Ripristina originale" in Samsung Gallery (13 minuti)

**Scopo.** Una foto già al sicuro, modificata e salvata in Samsung Gallery, va in `Versioni/` (originale) e al solito percorso (modifica); "Ripristina originale" è uno scambio senza traffico; "Salva copia" è un media nuovo. T-16 (a): come si muove il MediaStore.

**Prima (orchestratore).** A.9 e A.10 finiti. Il registro del traffico (`NET-LOG`, 2.1) dà i byte caricati: annotare l'ora di partenza di ogni passo. Aprire in Prove guidate la sezione "T-16 · «Modifica con…»". Tocca "1 · Apri una foto in Samsung Gallery" (quale foto apre lo decide la prova: l'orchestratore ricava il file toccato con il `find -mmin` qui sotto).

**Istruzioni per l'utente.**
1. "Si è aperta una foto in Galleria. Tocca la **matita**, **ruotala**, tocca **Salva**. Torna a MyCloud Gallery."
2. *(L'orchestratore: "2 · Confronta dopo la modifica"; poi di nuovo "1 ·".)* "Di nuovo la foto: matita, ritaglia, tocca **Salva copia**. Torna indietro."
3. *("Confronta".)* "Ancora la foto originale: matita, nel menu in alto **Ripristina originale** (o 'Annulla modifiche'). Torna indietro."
4. (R3 della 5B) *L'orchestratore apre un'altra foto del telefono dal viewer → "Modifica con…" → Samsung Gallery.* "Matita, ruota, **Salva**. Subito dopo, **senza tornare**, matita, ruota ancora, **Salva**. Torna indietro." *Poi, prima di "Fai ora", dall'app: viewer → menu → Versioni → "Ripristina originale".* "Compare un dialogo di Android? **Conferma**." *(Dopo la correzione di 5b-R3 il foglio deve dire "prima il backup la mette al sicuro" e non toccare niente; dopo il backup il ripristino riesce.)*

**Poi l'orchestratore** dopo ogni passo:
```powershell
A shell "find /sdcard/DCIM/mcg-prova /sdcard/Pictures/mcg-prova -type f -mmin -5"      # il file toccato
Add-Edit "<percorso>" (Old-Sha "<nome>")                                                  # dopo "Salva"
Add-Phone "<percorso della copia>"                                                       # dopo "Salva copia", se la copia sta in mcg-prova
```
Poi `Tap-Text "Fai ora"`, aspettare "Tutto al sicuro", e **una sola** verifica finale: `VERIFICA-COMPLETA` (circa 15 minuti; vedi il falso rosso sull'invariante 13 in A.10).

**Si guarda.** (a) Dopo "Salva": nel foglio Versioni del viewer ci sono "originale" e "attuale". (b) Dopo "Salva copia": un **media nuovo** nella Libreria (non una versione) — oppure, per la correzione F41, una versione dello stesso media se `DATE_TAKEN` è identico: annotare quale delle due. (c) Dopo "Ripristina originale": scambio senza traffico (`Net-Between` sul minuto dello scambio: pochi KiB, non la dimensione della foto), la riga del telefono e il NAS d'accordo. (d) Dal PC niente in `Versioni/` che manchi.

**Atteso.** "Versione al solito percorso e originale in `Versioni/`; scambio; media nuovo" (F34). Annotare i tempi: la rinomina in `Versioni/` sul WD (durata, `fileId` dopo la rinomina, ora di modifica conservata).

**Può far emergere.** VE-1, VE-9, VE-10, 5b-R3, MC-3.

### B-S7 · F34-bis (modifica dal PC e "Ripristina originale" con l'app chiusa) e F27 (rinomina del dispositivo) (12 minuti)

**F34-bis · Scopo.** Cosa dice la pagina quando il PC cambia il file durante una versione (VE-9) e se gli scambi e le versioni ferme ripartono da soli appena si riapre l'app, senza aspettare il giro delle 6 ore (VE-10).

**Prima.** `powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step edit -Serial RFCY61FZDHW -Edits 1 -Seed 202 -Manifest $manifest` (una nuova modifica, come "Salva"); l'orchestratore legge il nome della foto dal log.
**Istruzioni per l'utente.**
1. (VE-9) *L'orchestratore tocca "Fai ora".* "**Subito** vai sul PC e apri con Paint la foto `\\<indirizzo del NAS>\test\mcg-campo\Backup\...\<nome>` (la stessa) e salvala." *(La finestra è breve: se la versione è già finita, si annota "non riprodotta".)* Se compare "Versioni in attesa del computer" in Backup e nel piede: bene, e deve sparire quando il PC ha finito.
2. (VE-10) *L'orchestratore: `A shell am force-stop $pkg`.* "In Galleria apri una foto di mcg-prova **già al sicuro**, matita, **Salva**, poi di nuovo matita e **Ripristina originale**. Poi **apri MyCloud Gallery**." *L'orchestratore guarda: lo scambio parte entro 2-3 minuti dall'apertura (non dopo 6 ore).*

**Atteso.** Il PC che cambia il file non fa perdere niente (il temporaneo resta, dopo la scansione la versione va in `Versioni/`); scambi e versioni ferme ripartono alla riapertura.
**Può far emergere.** VE-9, VE-10, 5b-R16.

**F27 · Scopo.** Rinominare il dispositivo (E-B6) non cambia la cartella sul NAS. **Passi (4 minuti).** L'orchestratore: Impostazioni → Dispositivi → «Telefono di test» (questo dispositivo, sull'admin) → **«Rinomina questo dispositivo»** ("S25 prova"; dal 22/10 la voce c'è: il nome sta nel registro firmato e lo firma l'admin; da Backup non si rinomina, la cartella lì dice «Rinominare il dispositivo non cambia la cartella»), poi `s25.ps1 -Step edit -Edits 1 -Seed 203` e `Tap-Text "Fai ora"`; aspetta "Tutto al sicuro". L'utente: "In Esplora risorse apri `\\<indirizzo del NAS>\test\mcg-campo\Backup\` e dimmi **quante cartelle di dispositivo** ci sono." **Atteso.** Una sola, la stessa di prima. (Il secondo dispositivo con lo stesso account è C-10.) **Può far emergere.** BK-9, 5a-R7.

### B-S8 · F29: 20 foto proprie spostate nell'area comune; poi `test2` le cancella (10 minuti)

**Scopo.** Cosa succede alle foto di cui il backup ha la copia privata quando la si sposta nell'area comune e un altro familiare cancella la copia comune.

**Attenzione.** Il progetto (`fase5-progetto.md` §5.6) descrive ancora l'atteso con la risposta Q4 (a) ("copie private in `Backup/Area comune/`"). Il 15/10 l'utente ha scelto **Q4 (b)**: nessuna copia privata subito dopo lo spostamento; resta la protezione dei 30 giorni del cestino. Qui si osserva e si annota (§8, punto 10).

**Istruzioni per l'utente.**
1. "In Cartelle NAS apri **area privata → mcg-campo → Backup → <dispositivo> → <AAAA> → <MM>**. Seleziona **20 foto** (mai quelle che l'orchestratore ti ha detto di non usare) e **spostale** in `test-comune`, cartella `mcg-campo/F29`."
2. "Sul PC: apri `\\<indirizzo del NAS>\test-comune\mcg-campo\F29` con l'account **test2** (la password la digiti tu) e **cancella** quelle 20 foto."
3. "Torna sul telefono e apri la Libreria."

**Poi l'orchestratore.** Libreria e piede: le 20 foto sono ancora sul telefono; che cosa dicono (al sicuro / in coda)? Pagina Backup: il backup le ricarica al giro dopo (`Tap-Text "Fai ora"`)? Dopo, il cestino dell'area comune. Annotare tutto; le foto spostate sono "perse" per il verificatore di A.9 (non lanciarlo più dopo questa sessione).

**Atteso (Q4 b).** Niente copia privata creata subito; finché la copia del telefono c'è, il backup la rimette al sicuro al giro dopo; nessun errore; nessun media "al sicuro" senza una copia vera.
**Può far emergere.** 5a-R7, BK-11.

### B-S9 · F37: "Libera spazio" con il dialogo vero (15 minuti)

**Scopo.** Il guardiano controlla ogni media; due casi sbagliati (copia cambiata e copia cancellata sul NAS) restano esclusi; il resto va nel cestino di Android, le celle diventano "solo NAS" e si aprono; "Libera subito" richiede due gesti; l'app uccisa col dialogo aperto non perde niente.

**Prima (orchestratore).** Primo backup finito. Scegliere **50 media** di `mcg-prova` che non servono ad altre prove (un blocco di 50 celle della Libreria contenente due foto vicine, **X** e **Y**; leggere i loro nomi nel viewer → Informazioni). Preparare `X` cambiato:
```powershell
$b = [System.IO.File]::ReadAllBytes("$repo\build\chaos\seed-$giro\staging\<cartella>\X"); $b[100] = [byte](($b[100] + 1) % 256)    # un byte cambiato
New-Item -ItemType Directory -Force "$out\f37" | Out-Null; [System.IO.File]::WriteAllBytes("$out\f37\X", $b)
```

**Istruzioni per l'utente.**
1. (PC) "In `\\<indirizzo del NAS>\test\mcg-campo\Backup\<dispositivo>\<AAAA>\<MM>\` **sostituisci** il file **X** con quello che ti do (stesso nome) e **cancella** il file **Y**."
2. *L'orchestratore seleziona i 50 media in Libreria → Libera spazio → parte la verifica.* "**Esci dalla pagina** e **spegni lo schermo** premendo il tasto laterale per 30 secondi; poi riaccendi e sblocca." *(LS-2: il lavoro continua, arriva la notifica "Pronto", il tocco riapre la pagina.)*
3. *Riepilogo: X e Y escluse con il motivo; 48 pronti. L'orchestratore tocca "Libera spazio".* "Compare il dialogo di Android per spostare le foto nel cestino: **conferma**."
4. "In Galleria le foto sono sparite? Dimmi." *L'orchestratore: celle "solo NAS" in Libreria; una si apre dal NAS.*
5. (Libera subito) *L'orchestratore sceglie 5 media nuovi → Libera spazio → "Libera subito…" → due gesti (il pulsante cambia posto).* "Conferma il dialogo di Android."
6. (App uccisa col dialogo aperto, 5b-I2) *L'orchestratore sceglie 3 media → conferma → appena compare il dialogo di sistema: `TAGLIO-MORTE force-stop`.* "Il dialogo è ancora lì? **Conferma**." *Alla riapertura il diario e il catalogo dicono 'liberato'.*

**D-3 anche qui.** Su One UI il cestino di sistema porta i file nel cestino di Samsung Gallery e li toglie dalla cartella. L'app, per dire "liberato", conta sulle righe che spariscono o sono nel cestino di sistema: bisogna guardare che dopo il dialogo dica "liberate N" e **non** "in dubbio" o "nel dialogo" (LS-12), e che dopo la morte del processo il diario si risolva da solo. Se resta "in dubbio" per file che sul telefono non ci sono più, è il difetto D-3 che arriva a "Libera spazio": annotarlo con l'ora e il testo.

**Poi l'orchestratore.** Dopo il passo 3: `VERIFICA-NAS` (solo il NAS) e, per il telefono, `s25.ps1 -Mode backup -Step verify -SkipNas -AllowFreed`: sul telefono le foto liberate non sono più in `mcg-prova` (il 20/10 lo script le ha trattate come "tolte da Libera spazio": 303 su 308 e nessun file cambiato). Se invece comparissero file `.trashed-*` nella cartella, lo script li leggerebbe come sconosciuti (§8, punto 5): in quel caso si usa solo `VERIFICA-NAS`. Misurare: durata della verifica sul momento per GB (attesi circa 16 MB/s).

**Atteso.** Le due escluse (X e Y); le altre nel cestino di Android; "Libera subito" in due gesti; una foto liberata si apre; niente "perso" dopo la morte del processo.
**Può far emergere.** LS-2, LS-5, LS-6, LS-7, LS-8, LS-11, LS-12, LS-13, 5b-I2, D-3.

### B-S10 · F38: "Elimina solo dal telefono" (10 minuti)

**Scopo.** Un media non al sicuro, o con la sola copia comune, non si elimina dal telefono senza l'avviso e senza passare dal guardiano.

**Prima (orchestratore).** Un media **non al sicuro**: una copia scaricata da F11 in `Pictures/MyCloud Gallery` (fuori dalle sorgenti del backup), oppure una foto con una modifica nuova non ancora salvata (`s25.ps1 -Step edit -Edits 1 -Seed 301` e nessun "Fai ora"). Un media con la **sola copia comune**: una delle 20 di B-S8, se ancora sul telefono.

**Istruzioni per l'utente.**
1. *L'orchestratore apre il media non al sicuro nel viewer → Elimina → "Solo dal telefono".* "Leggi l'avviso: dice che la foto non è sul NAS? Dimmi cosa dice."
2. *Tocca 'Metti al sicuro'; il foglio aspetta e poi dice 'Continua'.* "Quando lo dice, avanti; compare il dialogo di Android: **conferma**." *(Il viewer si chiude, 'Libera spazio' si apre sopra: LS-3.)*
3. *Il media con la sola copia comune: stesso percorso.* "Conferma il dialogo se compare." Poi: "Nel **Cestino della Galleria** c'è la foto? Dimmelo."

**Atteso.** Avviso "Prima mettilo al sicuro"; il guardiano gira **prima** del cestino; niente liberato senza una copia verificata. (Con l'APK di release il messaggio è C-9.)
**Può far emergere.** LS-1, LS-3, LS-4, LS-14.

### B-S11 · F35: un video modificato e salvato sopra (10 minuti)

**Scopo.** Un video tagliato in Samsung Gallery arriva in `Versioni/`; nasce l'attività "Da decidere"; "Sì" porta l'originale nel cestino del NAS, "No" lo tiene e non si ripropone.

**Prima (orchestratore).** Due video piccoli di `mcg-prova` (dai fixture, `MANIFEST.tsv`; non i tre grandi), già al sicuro. Dire all'utente come si riconoscono (durata) o aprirli dal viewer con "Modifica con…".

**Istruzioni per l'utente.**
1. "In Galleria apri il video **<descrizione>**. Matita → **Taglia** (togli qualche secondo) → **Salva** (sovrascrive)."
2. "Fai lo stesso con il secondo video."

**Poi l'orchestratore.** `Add-Edit` per i due. `Tap-Text "Fai ora"`. Backup → scheda "Da decidere": due voci. Sul primo: "Elimina l'originale" → foglio "Elimina…" della 4B → conferma (`Tap-Text`) → l'originale va nel cestino del NAS. Sul secondo: "Tienilo" → resta in `Versioni/` e la domanda non torna. Col NAS spento (VE-6): prima di confermare `TAGLIO-WIFI 60`: il foglio dice perché non parte. Annotare con quali regole sale il video (preset e rete) e se una foto scattata subito dopo aspetta (VE-1).

**Atteso.** Originale in `Versioni/`; "Da decidere" una sola volta per originale; "Sì" → cestino del NAS; "No" → resta.
**Può far emergere.** VE-1, VE-6, 5b-R3.

### B-S12 · F36: correggere data, luogo e rotazione (12 minuti)

**Scopo.** La correzione scrive il file (JPEG, PNG, WebP) dopo il dialogo di sistema, mette l'originale in `Versioni/`; un HEIC resta intatto e la correzione vale solo nell'app; un solo-NAS si "mette al sicuro" prima.

**Prima (orchestratore).** Un JPEG di `mcg-prova` al sicuro (con EXIF), un HEIC dei fixture, un solo-NAS di `campioni`. Annotare `Exif-Info` del JPEG (`A pull` del file in `$out\f36\prima.jpg`).

**Istruzioni per l'utente.**
1. *L'orchestratore: viewer del JPEG → menu → "Correggi data e luogo": cambia la data di un giorno, sceglie il luogo "Roma", ruota 90°.* "Compare il dialogo di Android: **conferma**." *(ripetere con 'Ruota' su un PNG o WebP dei fixture se ci sono)*
2. *HEIC: stesso menu; 'Ruota' non deve esserci.* "Conferma il dialogo."
3. *Solo-NAS: stesso menu; 'Metti al sicuro adesso'.* "Se compare un dialogo, conferma."
4. "Apri la foto corretta in Galleria: è girata e ha la data nuova?"

**Poi l'orchestratore.** `A pull` del JPEG corretto in `$out\f36\dopo.jpg`; `Exif-Info "$out\f36\prima.jpg"` e `... dopo.jpg`: data diversa, orientamento diverso, `tag_gps` > 0 se il luogo è nel file. `Add-Edit` e `VERIFICA-COMPLETA` (falso rosso sull'invariante 13, vedi A.10). HEIC: file intatto (`Sha-Phone` uguale a prima), correzione visibile nell'app.

**Atteso.** "La versione corretta al solito percorso, l'originale in `Versioni/`; HEIC secondo Q6 (solo nell'app, dichiarato); 'Ruota' di un formato non scrivibile non dice 'fatto'."
**Può far emergere.** VE-2, VE-3, VE-4, VE-5, VE-8, 5b-R12.

### B-S13 · T-14 e F39: scegliere foto per le altre app (15 minuti)

**Scopo.** La radice "MyCloud Gallery" compare nei selettori di documenti; i media consegnati sono identici all'originale; la lettura non riempie la memoria; il blocco dell'app e i nascosti sono rispettati. Misure per tarare tetti e scadenze.

**Prima (orchestratore).** Aprire "T-14 · Selettori e provider dei documenti" nelle Prove guidate; tenere la forma "A richiesta". Le chiamate da `adb` sono già in A.12 (dalla shell il provider risponde, perché la shell ha `MANAGE_DOCUMENTS`: la prova contro un'app che non lo ha è C-26). Sul PC: `build\campo\campioni\` ha i file veri con cui confrontare gli SHA (sono quelli copiati sul NAS).

**Istruzioni per l'utente.**
1. (Gmail) "Scrivi un messaggio a te stesso. Graffetta → **Allega file** → nel menu a sinistra scegli **MyCloud Gallery** → scegli **3 foto del telefono e 3 foto solo-NAS** → **Scegli**. Invia il messaggio a te stesso." *(T-14: la radice compare; il tempo di lettura lo misura l'orchestratore da `MyCloud/`)*
2. (WhatsApp) "In una chat: graffetta → **Documento** → **Sfoglia altri documenti** → **MyCloud Gallery** → scegli il **video più grande solo-NAS**. **Appena parte l'invio, tocca Indietro/Annulla** a metà." *(non deve restare nessun lavoro: l'orchestratore guarda `A shell top -b -n1 | Select-String mycloud` e, dopo, i contatori nell'esportazione E2)* (Telegram: graffetta → **File** → **MyCloud Gallery**.)
3. "In **OneDrive** (carica), in **I miei file** e in **Google Drive** (carica): compare **MyCloud Gallery** tra le fonti? Nel selettore foto di sistema (quello che si apre da Messaggi) **non** deve comparire."
4. (E-X1) *L'orchestratore dice 'ora':* "Scegli un video solo-NAS da Gmail." *Al tocco di 'Scegli' l'orchestratore fa `TAGLIO-WIFI 30`.* "Gmail ha dato un errore **subito** (non dopo molto)? Dimmi."
5. (Blocco app) *L'orchestratore spiega, l'utente lo fa:* "Apri **MyCloud Gallery → Impostazioni → Sicurezza**, accendi il **Blocco dell'app** con **Subito** (ti chiede la tua credenziale)." Poi "Da Gmail: Allega file → MyCloud Gallery: ti chiede lo sblocco? Sblocca: arriva la lista? (non deve chiedere lo sblocco in ciclo)".
6. (Tolleranza 2 minuti) *Appena l'utente sblocca:* l'orchestratore, entro 2 minuti, `A shell am start -n "$pkg/dev.pampa.mycloudgallery.feature.integrations.picker.PickerActivity" -a android.intent.action.GET_CONTENT -t "image/*"`: si apre **senza** sblocco; dopo 2 minuti e mezzo lo richiede (`Ui-Texts` mostra lo sblocco). L'utente poi **spegne** il Blocco dell'app.
7. (Ingressi a freddo) *`A shell am force-stop $pkg`.* "Da Gmail e da WhatsApp, **5 volte ciascuno**, apri Allega file → MyCloud Gallery e torna indietro: **mai** 'Prima configura MyCloud Gallery'."
8. (Facoltativo) "Mentre il selettore è aperto in Gmail, passa a MyCloud Gallery, **nascondi una foto**, torna a Gmail e tocca quella foto → Scegli: **non deve uscire**."

**Poi l'orchestratore.** Se l'utente scarica dal PC gli allegati della mail a sé stesso in `build\campo\<giro>\f39\`, `Get-FileHash` contro `build\campo\campioni\` (solo-NAS) e contro il manifesto (telefono): **uguali**. I contatori `INTEGRATIONS_COUNTERS` stanno nell'esportazione (E2, a fine B-S14: l'utente la salva una volta per le due sessioni). Tempi di lettura di Gmail e WhatsApp dal log. Resoconto T-14 (`t14.*`): `A shell run-as $pkg cat no_backup/field-probe/counters.properties`.

**Atteso.** Radice visibile in Gmail, WhatsApp, Telegram, OneDrive, I miei file, Drive e non nel selettore di sistema; allegati identici; annullo pulito; errore subito a NAS spento; blocco rispettato; ingressi a freddo sempre aperti; nascosto mai consegnato.
**Può far emergere.** IN-2, IN-3, IN-4, IN-9, IN-10, 5b-R8.

### B-S14 · T-15 e F40: ricevere condivisioni e "Salva sul NAS" (15 minuti)

**Scopo.** Le app passano i file con `content://` (non `file://`); la copia parte subito; "Salva sul NAS" funziona a freddo, col Wi-Fi spento (in coda), con 100 immagini e con un video grande; i rifiuti dicono il motivo; la Libreria mostra il ricevuto con data e copertina.

**Prima (orchestratore).** Prove guidate → "T-15 · Condivisioni in arrivo": prima con "Legge subito". Una copia della cartella `incoming`: `A shell run-as $pkg ls -la no_backup/incoming` (vuota).

**Istruzioni per l'utente.** (Usa foto e video **tuoi di poca importanza**: finiranno nella share di prova `test`.)
1. (T-15) "Da **Galleria**: seleziona 3 foto → Condividi → **MCG prova (T-15)**." Poi "da **I miei file**: condividi un file immagine", "da **Chrome**: condividi un'immagine di una pagina", "da **WhatsApp**: condividi una foto ricevuta", "da **Quick Share**: stesso". *(Ogni volta l'orchestratore annota `t15.caller` e `t15.scheme_*`.)* Poi "Tocca 'Chiude prima'" (orchestratore) e ripeti due condivisioni. *L'orchestratore tocca "Riprova ad aprire le condivisioni ricevute" subito e dopo `force-stop`.*
2. (F40, a freddo) *`A shell am force-stop $pkg`.* "Da **WhatsApp** condividi una foto → **Salva sul NAS**. Aspetta **5 secondi** prima di toccare Salva." *(IN-1/IN-3: la copia non deve sparire, nessun 'Prima configura')*
3. "Da **Galleria** seleziona **100 immagini** → Condividi → Salva sul NAS." *(tetto, lotto, notifica 'N di M file ricevuti' e 'Ferma' che ferma solo loro)*
4. "Da **Galleria**, un **video lungo** (circa 1 GB, se ne hai uno tuo) → Salva sul NAS." *(lavoro dell'utente con notifica; copertina e data nel ricevuto)* "Poi condividi il **video grande di mcg-prova (3 GB)**: dev'essere **rifiutato con il motivo**."
5. (NAS in coda) *L'orchestratore: `TAGLIO-WIFI 90`* appena l'utente tocca Salva su una foto di WhatsApp e un video di Chrome: "La schermata dice **'In coda'**?" *Backup → 'Ricevuti in coda' con 'N in attesa'; Wi-Fi tornato: salgono e spariscono; la Libreria li mostra solo adesso.*
6. (Notte di giorno) *Con il preset 'Notte' (Impostazioni → Quando lavora l'app) e Wi-Fi di casa:* "Salva sul NAS una foto: sale **subito**" (IN-5). *Poi rimettere il preset.*
7. "Premi **Indietro** a metà di una copia: non deve restare niente."

**Poi l'orchestratore.** `A shell run-as $pkg ls -la no_backup/incoming` **vuota** alla fine; il resoconto T-15 (`t15.*`) con `A shell run-as $pkg cat no_backup/field-probe/counters.properties`; l'**esportazione E2** (l'utente la salva a fine sessione: ricevuti e problemi); `Ui-Texts` della scheda "Ricevuti in coda"; data del ricevuto (mai 1970), informazioni e copertina del video in Libreria; nessun log con nomi.

**Atteso.** Come sopra. Tutto solo in `mcg-campo/` di `test`.
**Può far emergere.** IN-1, IN-3, IN-5, IN-6, IN-7, IN-8, IN-11, IN-12, 5b-R5, 5b-R14, 5b-R15.

### B-S15 · T-16 (b) e F41: "Modifica con…" in Samsung Gallery e Google Foto (15 minuti)

**Scopo.** L'esito di "Modifica con…" si legge dal MediaStore; "Salva copia" torna alla stessa sessione; per un solo-NAS la versione sale sul NAS subito; "Scarica e modifica in Galleria" funziona.

**Istruzioni per l'utente.**
1. *Foto del telefono: viewer → menu → "Modifica con…" → Samsung Gallery.* "Matita, modifica, **Salva**." Poi (di nuovo "Modifica con…") "**Salva copia**". Poi "**Ripristina originale**". *(la copia salvata con 'Salva copia' deve avere `DATE_TAKEN` identico: lo guarda l'orchestratore con `content query`.)*
2. *Un solo-NAS di `test/mcg-campo/campioni`: viewer → "Modifica con…" → **Google Foto**.* "Modifica e salva." Poi "Salva copia".
3. *Un solo-NAS: "Scarica e modifica in Galleria".* "Modifica e **Salva**."
4. *Un solo-NAS dell'area **comune** (`test-comune/mcg-campo/campioni`): "Modifica con…".* "Dice che non si può / chiede prima di aprire?"
5. (Prove guidate T-16 b) *"3 · Modifica una copia in cache" → Google Foto, poi OneDrive.* "Modifica e salva."
6. *Il preset 'Notte' di giorno e `force-stop`; fuori casa è C-11.*

**Poi l'orchestratore.** Dopo ogni passo `Log-Save`; Backup: la versione è sul NAS entro pochi minuti (non dopo un giro qualunque: MC-3); i byte caricati salgono; `A shell content query --uri content://media/external/images/media --projection _display_name:datetaken:date_added --where "relative_path LIKE 'Pictures/MyCloud Gallery/%'"`. Resoconto T-16 b (`t16.edit_*`): salvato in `$out\t16b-report.txt`.

**Atteso.** Versione = quella scritta dall'editor; "Salva copia" ricollegata alla sessione; l'area comune rifiutata prima di aprire; nessuna perdita.
**Può far emergere.** MC-3, MC-4, MC-5, MC-6, 5b-R11, LA-2.

### B-S16 · F42 (e MC-1, MC-2): condividere "senza posizione" (12 minuti)

**Scopo.** Il file ricevuto dall'altra app non contiene la posizione (JPEG, HEIC, MP4, WebP/PNG, motion photo) e l'altra app lo riceve intero anche se si sceglie la chat dopo 3 secondi o si torna subito nell'app.

**Prima (orchestratore).** "Rimuovi la posizione" acceso (Impostazioni → Condivisioni). Dai fixture (`MANIFEST.tsv`): JPEG con GPS, HEIC, MP4, motion photo, uno **screenshot di Maps** con posizione nell'XMP, un PNG/WebP. Uno solo-NAS di `campioni`. Cartella di arrivo sul PC: `build\campo\<giro>\f42\`.

**Istruzioni per l'utente.**
1. *Viewer del JPEG con GPS → Condividi.* "Aspetta **4 secondi** senza toccare niente, poi scegli **Gmail**, a te stesso, **Invia**." Lo stesso per HEIC, MP4, motion photo, screenshot di Maps e per un solo-NAS.
2. *Viewer → Condividi.* "Scegli **WhatsApp** (la chat con te) o **Telegram** (Messaggi salvati), aspetta **più di 3 secondi** prima di toccare la chat, invia, e **torna subito** in MyCloud Gallery." *(MC-1: il file deve arrivare intero)* Una volta con un video grande.
3. *Libreria: seleziona 3 foto con GPS → Condividi → Gmail.* "Invia a te stesso." *(MC-2, 5b-R4: la selezione rispetta l'impostazione)*
4. "Sul PC scarica gli allegati e i file ricevuti in WhatsApp/Telegram nella cartella `<giro>\f42`." *(Gmail dal browser, WhatsApp Web o Telegram Desktop.)*

**Poi l'orchestratore** (dopo un minuto dall'ultimo invio):
```powershell
A shell run-as $pkg ls -la cache/share                         # vuota: le copie sono state tolte
Get-ChildItem "$out\f42" | ForEach-Object { "{0}: {1}" -f $_.Name, (Check-NoGps $_.FullName) }
# con exiftool, se c'e':  exiftool -a -G1 -ee -gps:all -xyz -location* -xmp:all -iptc:all <file>
```
Il nome del file è quello dell'utente; orientamento e data restano.

**Atteso.** Nessuna posizione in nessun file; file arrivato intero in WhatsApp e Telegram; `cache/share/` vuota dopo il ritorno.
**Può far emergere.** MC-1, MC-2, 5b-R1, 5b-R2, 5b-R4, 5b-R12, 5b-R13. (File particolari: drone, Lightroom, MOV di iPhone: C-21.)

### B-S17 · Permessi e "foto selezionate" (10 minuti)

**Scopo.** Con l'accesso solo ad alcune foto, il backup salva quelle e non si ferma per le altre (5a-R5); il problema dei permessi si toglie dopo averli dati (BK-3); il primo "Consenti" non resta muto dopo una rotazione (GA-4).

**Attenzione.** Cambiare i permessi è una modifica delle impostazioni di Android: la fa **l'utente**, e la rimette com'era. L'orchestratore non tocca niente.

**Prima (orchestratore).** `s25.ps1 -Mode backup -Step edit -Edits 15 -Seed 77 -Manifest $manifest`; niente "Fai ora" ancora.

**Istruzioni per l'utente.**
1. "Impostazioni di Android → App → MyCloud Gallery (debug) → Autorizzazioni → Foto e video → **Consenti solo quelle selezionate** → scegli **10 foto** (quelle che vuoi)."
2. *L'orchestratore: "Fai ora"; la pagina dice 'Hai dato accesso solo ad alcune foto: si salvano solo quelle'; le foto scelte salgono, le altre aspettano senza fermare il resto.*
3. "Ruota il telefono mentre compare la richiesta di permesso (se compare); poi torna in MyCloud Gallery e tocca **Consenti**." *(GA-4: il primo tocco fa qualcosa)*
4. "Rimetti **Consenti tutto**." *Il problema sparisce da solo (BK-3) e il resto sale.*

**Poi l'orchestratore.** A fine: `VERIFICA-COMPLETA` (chiude tutte le righe `edit=`).

**Atteso.** Le foto scelte arrivano, le altre aspettano; nessun elemento `FAILED`; nessun problema fermo sulla pagina.
**Può far emergere.** 5a-R5, GA-4, BK-3.

### B-S18 · FF-4: la VPN con WireGuard vero (10 minuti, solo se WireGuard è installato)

**Scopo.** Il permesso di controllare i tunnel di WireGuard si chiede con il dialogo di sistema e si vede (RE-1); "Configura il controllo" apre il passo giusto (ON-7).

**Prima.** `A shell pm path com.wireguard.android` (si guarda soltanto se c'è): se non c'è, la prova passa al blocco C (C-24).

**Istruzioni per l'utente.**
1. *L'orchestratore: Impostazioni → Rete e VPN → "Consenti il controllo".* "Compare un dialogo di Android: **consenti**."
2. "Se hai già un tunnel di casa in WireGuard: **non accenderlo**." *(accendere o spegnere la VPN cambia la rete: solo se l'utente lo vuole, a Wi-Fi di casa)*

**Poi l'orchestratore.** `A shell dumpsys package $pkg | Select-String "CONTROL_TUNNELS"` → `granted=true`. Il tasto "Consenti il controllo" sparisce; "Configura il controllo" apre il passo VPN.

**Atteso.** `granted=true` dopo il tocco. (Con WireGuard installato dopo l'app il dialogo potrebbe non comparire: M10, annotare.)
**Può far emergere.** RE-1, RE-2, ON-7.

## 6. Blocco C: "serve un altro dispositivo, l'APK di release o tempo"

Solo elencate, con quello che serve. Nessuna si fa nella giornata di A e B.

| # | Prova | Cosa serve | Perché non ora |
|---|---|---|---|
| C-1 | **F2**: scansione in sola lettura dell'archivio vero di `alessio` con D31 acceso (tempo e traffico entro ±30% del §2.10 di `fase4-progetto.md`; dal PC nessuna `.mycloudgallery` nuova) | APK di **release firmato** + account vero (la password la digita l'utente) + il consenso Q4 (a), già dato il 14/10 | Release non installabile (C-9); l'account vero non è di prova. Il controllo "nessuna `.mycloudgallery` nuova" lo fa l'utente dal PC. |
| C-2 | **F6** parte tablet / M2: il Tab S9 (`test2`, area comune) vede le miniature senza prepararle | Tab S9 con il profilo "Prova" e `test2` | Altro dispositivo. |
| C-3 | **F7**: S25 e Tab S9 (due account) per 3 ore, con file che arrivano dal PC: lavoro doppio ≤ 1%, nessun segmento rifiutato, viste identiche (riassunto dell'indice per share uguale in Diagnostica di debug) | Tab S9, 3 ore, file che l'utente copia dal PC ogni tanto | Altro dispositivo e tempo. |
| C-4 | **F8** (e i due casi in più di `4a-fase4a.md`: blob con chiave inventata, record "tutto preparato" senza blob): segmenti e teste ostili scritti nell'area di prova, rifiutati senza crash e contati in Diagnostica | `tools/hostile-index` — **non esiste** nel repository — e il permesso di scrivere nella cartella `.mycloudgallery` vera di `test` (fuori da `mcg-it/<giro>/`) | **Bloccata**: strumento mancante e scrittura fuori dal recinto (§8, punto 3). |
| C-5 | **F14** e **F45**: Twonky e Plex con `test-comune` aggiunta (poi una share con `Backup/` e `Versioni/`); Foto di Windows sulla share: né l'indice né il cestino né `Versioni/` devono comparire; se compaiono, M11 / L43 | Dashboard del NAS (aggiungere e togliere la share dalle librerie), la TV e l'utente che guarda | Dashboard del NAS e TV. |
| C-6 | **F14-bis** e `4a-R11`: all'onboarding con `test` si sceglie `Public` come area; l'app rifiuta con un messaggio, anche dal test di connessione; nessun file in `Public` (controllo dal PC) | Un profilo pulito (tablet "Prova" o un reset) e le password | L'S25 è già configurato: rifare l'onboarding vuole le password. |
| C-7 | **F17**: preferito e nascosto sull'S25 compaiono sul tablet con lo stesso account, **non** su quello di `test2` | Tab S9 con `test`, e un altro con `test2` | Altro dispositivo. |
| C-8 | **F18** e **F33**: una settimana con Notte e una con Bilanciato, backup acceso; meno del 2% di batteria al giorno con Notte; stime entro ±30% | Due settimane di uso normale; batteria da `dumpsys batterystats` o dall'utente | Tempo. |
| C-9 | **F19** e **F44**: APK di **release**: scansione, preparazione, viewer, streaming, operazioni, UIDT e "Ferma", backup, versioni, "Libera spazio", integrazioni con R8; messaggi di release ("Backup in prova", "Questa foto non è sul NAS"); `FF-17`: onboarding, Impostazioni e Diagnostica in una build ridotta | APK di release **firmato** (oggi è `app-release-unsigned.apk`: mancano le chiavi `pampa.*` in `local.properties`) installato accanto alla debug, e l'utente che fa l'accesso | Non installabile (§8, punto 2). |
| C-10 | **F27** parte 2: un secondo dispositivo con lo stesso account di prova → cartelle separate per i due dispositivi | Tab S9 con `test` | Altro dispositivo. |
| C-11 | **Fuori casa**: **F28** (hotspot del tablet, dati mobili, preset Bilanciato: niente backup in background, "Fai ora" chiede conferma con la stima, il tetto ferma), **F10** parte con i dati mobili (avviso con la dimensione), `MC-6` ("Modifica con…" di una foto del telefono fuori casa a processo freddo), `IN-5` ("Salva sul NAS" fuori casa parte al rientro) | Tab S9 con l'hotspot; l'utente collega l'S25 all'hotspot (è un cambio di rete: lo fa lui) | Altro dispositivo. |
| C-12 | **F31**: quota piccola sull'account `test` dalla dashboard → "Spazio del tuo account esaurito", nessun file a metà, e riprende quando si toglie la quota (E-R9, E-N13) | Dashboard del NAS | Il NAS di famiglia si amministra solo da lì. |
| C-13 | **F31-bis**: copie di foto di `mcg-prova` identiche, ricompresse, con EXIF riscritto e convertite da HEIC (preparate dal PC) in `mcg-campo/` **prima** del backup, più una raffica; riepilogo con i tre numeri giusti; "Separa" sul telefono e sul tablet, anche con id diversi sul tablet (`LA-2`) | Tab S9 con `test`; ImageMagick o simili per preparare le copie; un nuovo primo backup (o un nuovo seme) | Altro dispositivo e una preparazione che oggi non c'è. |
| C-14 | **F32** (chiusura 5B, Q3 (b)): il primo backup **vero** dell'S25 su `alessio`, D12 spento; tempo, traffico e batteria entro ±30% del riepilogo. Con lui `FF-6` (D34: foto scattata fuori casa, primo backup a metà) e `BK-1`, `BK-2` | APK di release firmato, account vero, consenso esplicito dell'utente (Q3), telefono in carica una notte | Consenso e release (C-9). D34 non si prova in debug: la recinzione D13 tiene fuori le foto della fotocamera (§8, punto 17). |
| C-15 | **F13** parte Tab S9: `check.ps1 -Bench` sul tablet (e `zoom200Mp`) | Tab S9 collegato; il permesso di usare il suo numero di serie (la regola dice solo `RFCY61FZDHW`) | Altro dispositivo. |
| C-16 | **Famiglia con due dispositivi** (`docs/famiglia-dispositivi.md` §9, punti 1-9) e i flussi `FF-1` (ingresso dal QR fino all'approvazione, anche dopo 16 minuti: `ON-2`), `FF-2` (ingresso senza QR: `ON-1`), `FF-3` (revoca, dismissione, reset visti dal dispositivo colpito: `ON-4`, `ON-5`, `ON-6`, `5b-R7`), `FF-10` (recupero dell'admin con un'interruzione: `ON-3`), `ON-11`, `5b-R6` (dati del vecchio account dopo "Configura di nuovo"), `5b-R18`, `5b-R19` | Tab S9, le password, il QR; lo stato "perso" e il reset della famiglia sono distruttivi (chiave del dispositivo, chiavi, famiglia): l'utente decide | Altro dispositivo e password. Il punto 7 (telefono admin senza blocco schermo) è C-20. |
| C-17 | **Il NAS come amministratore**: `4a-D32` (NTP del NAS sistemato: salto d'ora con l'app chiusa e aperta), `4a-I4` (ChangeTime su Samba dopo una scrittura dal PC con la data ripristinata), `4b-R3` via SSH (collegamento simbolico a una cartella di `mcg-it/…`, voce `..`, poi cestino e "Elimina subito" su una copia), `5b-I10` (`fileId` di un file prima e dopo una rinomina e di una copia fatta da Esplora risorse: mai 0), `5b-R5` (area comune in sola lettura per l'account di prova), `F20` parte 2 (un record falso messo dal PC nel cestino non fa eliminare niente), `4b-R1` | SSH o dashboard del NAS, scrittura dal PC fuori da `mcg-it/<giro>/` | Fuori dal recinto delle prove dal PC e dai poteri dell'orchestratore. |
| C-18 | **File messi a mano in `mcg-prova`** (la regola dice che i dati di prova li mette solo la semina): `5a-R1` (due file con lo stesso nome da 255 byte, uno in `DCIM`, uno in `Pictures`, dello stesso mese: il secondo deve arrivare "(2)" senza fermare gli altri), `5a-R6` (un file allungato con `dd` **senza** far rileggere il MediaStore: non deve risultare al sicuro con la copia corta), `4a-I3` (nome `.nomedia` e nome con " (2)" oltre 255 byte sul MediaStore di Android 14/15), `F35` simulato con `dd` su un video, e le varianti (a), (b), (d) di `T-11` (permesso ritirato, accesso parziale: sono impostazioni) | Un OK esplicito dell'utente, oppure un passo nuovo di `s25.ps1` (`-Step extra`) che crei questi file solo dentro `mcg-prova` | Contraddicono la regola sui dati di prova (§8, punto 12). |
| C-19 | **Il tempo e il riavvio**: un URI consegnato (`F39`) che dopo **6 ore** non si apre; un riavvio del telefono tra la verifica e il dialogo di "Libera spazio" (giro scaduto, `LS-12`); "una notte di preparazione" con D31 forzato per lo spazio dell'outbox (`4a-R2`) | 6 ore, il riavvio (lo fa l'utente: `adb reboot` non è nei permessi) | Tempo e riavvio. |
| C-20 | **Telefono senza blocco schermo**: il messaggio del selettore dice cosa fare (`T-14`, `IN-10`); nessuna approvazione né reset dall'admin senza blocco (famiglia §9.7) | Togliere il blocco schermo è una modifica delle impostazioni | Regola sulle impostazioni. |
| C-21 | **F42 esteso** (`5b-R1`, `5b-R2`): foto e video di un drone o di una dashcam, una foto passata da Lightroom o Photoshop (XMP esteso, APP13), un PNG con XMP in `tEXt`, una motion photo di Google, un video di iPhone (MOV), un video Samsung normale per escludere il falso positivo | I file veri, `exiftool` | Servono file che l'utente deve procurarsi. |
| C-22 | **NAS davvero spento** (E-X1: "NAS spento dopo la scelta" nel selettore; `F40` "Salva col NAS spento") | Il NAS di famiglia non si spegne mai per una prova. In A e B si usa il **Wi-Fi spento** (al massimo 120 secondi per volta con `s25.ps1`) come equivalente: l'app vede "offline", non "NAS irraggiungibile a casa" | Rischio per la famiglia. |
| C-23 | **Sovrapposizioni** (`4b-R7`, `5b-R8`): un'app che disegna sopra (filtro luce blu) mentre si apre un foglio della 4B o il selettore: i tocchi vanno scartati | Un'app di terze parti attiva (è un'altra app e una modifica delle impostazioni di Android) | Regola sulle altre app. |
| C-24 | **VPN con WireGuard vero**, parte lunga (`FF-4`): accendere e spegnere il tunnel dall'app, sospensione dopo tre cambi di rete (`RE-2`), "Riattiva il controllo"; B-S18 prova solo il permesso | WireGuard installato e un tunnel di casa; un luogo fuori casa | La VPN cambia la rete del telefono. |
| C-25 | **Cambio password, indirizzo del NAS e nuova impronta di casa** (`FF-14`: `RE-5`, `RE-9`); chiave del Keystore invalidata | Il NAS cambia davvero indirizzo o password | Cambia la configurazione di tutta la famiglia. |
| C-26 | **F39 e T-14 con un'app senza `MANAGE_DOCUMENTS`**: il provider dei documenti, chiamato da un'app qualunque e non dal sistema, deve dare `SecurityException`. Da `adb shell` non si prova (la shell ha il permesso) | Un'app di prova con un altro pacchetto che interroghi `content://<pacchetto>.integrations.documents/root` | Installare un'altra app non è ammesso dalle regole di questo copione. |

## 7. Tabella finale

Colonne: **id** (come nel testo), **blocco**, **durata stimata** in minuti (per il blocco B: i minuti dell'utente, e tra parentesi quelli dell'orchestratore da solo), **stato**, **difetti da cercare** (le sigle del §0 che la prova può far emergere).

### Blocco A (21 prove)

| id | blocco | durata | stato | difetti da cercare |
|---|---|---|---|---|
| PC-0 (A.1) | A | 6 | da fare | — (credenziali e guardia) |
| F1 (A.2) | A | 15 | da fare | NA-6, GA-5, GA-6, 4a-R10 |
| F3 (A.3) | A | 10 | da fare | NA-8, RE-6, 4a-I4 |
| F6a (A.4) | A | 10 | da fare | 4a-R1, 4a-R2, 4a-R5 |
| FF-15 (A.5) | A | 6 | da fare | RE-6, RE-3 |
| GV (A.6) | A | 20 | da fare | GA-2, GA-3, GA-8, NA-3, ON-6, ON-7, ON-8, RE-4, RE-8, BK-9 |
| F10 (A.7) | A | 15 | da fare | 4b-R10, GA-3 |
| F11 (A.8) | A | 12 | da fare | 4a-R8 |
| F21 e T-11 (A.9) | A | 15 | da fare | BK-14, 5a-R1, 5a-R6 |
| F22 (A.9) | A | 25 | da fare | BK-3, BK-4, BK-5, BK-6, BK-8, BK-12, BK-13, 5a-R4, 5a-R7 |
| F23 (A.9) | A | 10 | da fare | BK-6, BK-10 |
| F24 (A.9) | A | 12 | da fare | 5a-R3, 5a-R4, 4b-R9, BK-10 |
| F30-tel (A.9) | A | 75 | da fare | BK-1, BK-6, BK-10, 5a-R5 |
| F43-tel (A.10) | A | 80 | da fare | VE-1, VE-9, VE-10, LA-3, LA-4, 5b-R3, 5b-R9, 5b-R10, 5b-R16 |
| FF-8 (A.11) | A | 12 | da fare | RE-3, RE-4, NA-7, NA-12, D-2 |
| F39-adb (A.12) | A | 12 | da fare | IN-3, IN-13, LA-5, 5b-R8 (la SecurityException del provider è C-26) |
| 4b-R3 suite (A.13) | A | 20 | da fare | 4b-R3, 4b-R4 |
| F16-PC (A.14) | A | 150 | da fare | 4b-R4, 4b-R8 |
| F30-PC (A.14) | A | 120 | da fare | 5a-R1, 5a-R3 |
| F43-PC (A.14) | A | 60 | da fare | 5b-R5, 5b-R14, 5b-R16 |
| F13 S25 (A.15) | A | 40 | da fare | — (guardia delle prestazioni) |

### Blocco B (24 prove, 18 sessioni)

| id | blocco | durata | stato | difetti da cercare |
|---|---|---|---|---|
| T-09 (S1) | B | 15 | da fare | VE-2, VE-5, GA-7, LA-6, LA-7, LA-8, 5b-I2, D-3 |
| F25 (S2) | B | 8 | da fare | 5a-R1, BK-7 |
| F26 (S2) | B | 3 | da fare | BK-3, BK-12 |
| F4 (S3) | B | 4 | da fare | 4a-R6, 4a-R8, NA-3 |
| F5 (S3) | B | 3 | da fare | 4a-R6, 4a-R8 |
| F9 (S3) | B | 6 | da fare | NA-4, NA-5, NA-7, NA-9, 4a-R9 |
| F15 (S4) | B | 15 (+2) | da fare | NA-1, NA-2, NA-3, 4b-R2, 4b-R6, 4b-R7, 4b-R8, 4b-R10, 4b-R11, GA-7 |
| F20 (S5) | B | 3 (+6) | da fare | 4b-R1, 4b-R3, 4b-R4, 4b-R5 |
| F12 (S5) | B | 6 | da fare | 4a-R2, RE-8 |
| F16-tel (S5) | B | 5 (+55) | da fare | NA-12, 4b-R8, 4b-R9 |
| F34 e T-16 a (S6) | B | 13 (+15) | da fare | VE-1, VE-9, VE-10, MC-3, 5b-R3, 5b-R9, 5b-R10 |
| F34-bis (S7) | B | 8 | da fare | VE-9, VE-10, 5b-R16 |
| F27 (S7) | B | 4 | da fare | BK-9, 5a-R7 |
| F29 (S8) | B | 10 | da fare | 5a-R7, BK-11 |
| F37 (S9) | B | 15 | da fare | LS-2, LS-5, LS-6, LS-7, LS-8, LS-11, LS-12, LS-13, 5b-I2, D-3 |
| F38 (S10) | B | 10 | da fare | LS-1, LS-3, LS-4, LS-14 |
| F35 (S11) | B | 10 | da fare | VE-1, VE-6 |
| F36 (S12) | B | 12 (+15) | da fare | VE-2, VE-3, VE-4, VE-5, VE-8, 5b-R12 |
| T-14 e F39 (S13) | B | 15 (+5) | da fare | IN-2, IN-3, IN-4, IN-9, IN-10, 5b-R8 |
| T-15 e F40 (S14) | B | 15 | da fare | IN-1, IN-3, IN-5, IN-6, IN-7, IN-8, IN-11, IN-12, 5b-R5, 5b-R14, 5b-R15 |
| T-16 b e F41 (S15) | B | 15 | da fare | MC-3, MC-4, MC-5, MC-6, 5b-R11, LA-2 |
| F42 (S16) | B | 12 (+5) | da fare | MC-1, MC-2, 5b-R1, 5b-R2, 5b-R4, 5b-R12, 5b-R13 |
| 5a-R5 permessi (S17) | B | 10 (+15) | da fare | 5a-R5, GA-4, BK-3 |
| FF-4 VPN (S18) | B | 10 | da fare | RE-1, RE-2, ON-7 |

### Blocco C (26 prove)

| id | blocco | durata | stato | difetti da cercare |
|---|---|---|---|---|
| C-1 F2 | C | 60 | da fare | 4a-R2, 4a-R3 |
| C-2 F6/M2 (tablet) | C | 20 | da fare | — |
| C-3 F7 | C | 190 | da fare | 4a-R1, 4a-R5 |
| C-4 F8 | C | 30 | da fare (bloccata) | 4a-R1, 4a-R5 |
| C-5 F14 e F45 | C | 60 | da fare | — (M11, L43) |
| C-6 F14-bis | C | 20 | da fare | 4a-R11 |
| C-7 F17 | C | 20 | da fare | — |
| C-8 F18 e F33 | C | 14 giorni | da fare | RE-7, RE-8 |
| C-9 F19 e F44 | C | 90 | da fare | IN-6, LS-1, VE-7, LA-5, 5a-R2 (e FF-17) |
| C-10 F27 (2 dispositivi) | C | 15 | da fare | — |
| C-11 Fuori casa (F28) | C | 60 | da fare | MC-6, IN-5 |
| C-12 F31 | C | 30 | da fare | — |
| C-13 F31-bis | C | 90 | da fare | LA-2, BK-11 |
| C-14 F32 | C | una notte | da fare | BK-1, BK-2, BK-10 (e FF-6) |
| C-15 F13 Tab S9 | C | 40 | da fare | — |
| C-16 Famiglia (§9) | C | 120 | da fare | ON-1, ON-2, ON-3, ON-4, ON-5, ON-6, ON-11, 5b-R6, 5b-R7, 5b-R18, 5b-R19 |
| C-17 NAS come amministratore | C | 90 | da fare | 4a-I4, 4b-R1, 4b-R3, 5b-I10, 5b-R5 |
| C-18 File a mano in mcg-prova | C | 40 | da fare (serve un OK) | 5a-R1, 5a-R6, 4a-I3 |
| C-19 Tempo e riavvio | C | 6 ore | da fare | LS-12, 4a-R2 |
| C-20 Senza blocco schermo | C | 10 | da fare | IN-10 |
| C-21 File speciali (F42 esteso) | C | 30 | da fare | 5b-R1, 5b-R2 |
| C-22 NAS davvero spento | C | 15 | da fare (non si fa) | E-X1, E-X4 |
| C-23 Sovrapposizioni | C | 15 | da fare | 4b-R7, 5b-R8 |
| C-24 VPN WireGuard (lunga) | C | 60 | da fare | RE-1, RE-2, ON-7 |
| C-25 Password e indirizzo del NAS | C | 40 | da fare | RE-5, RE-9 |
| C-26 Provider senza MANAGE_DOCUMENTS | C | 20 | da fare | IN-2, IN-3 |

## 8. Punti aperti: prove impossibili, o in contraddizione con le regole

Da leggere prima di cominciare. I numeri sono quelli richiamati nel resto del file.

1. **L'app sul telefono non era configurata con l'account di prova.** Il 20/10 (17:30-17:55) la sessione che ha provato il blocco A ha trovato `alessio` (area privata `alessio`, area comune `Public`, percorso `RECOVER_ADMIN`, configurazione del 19/10). Con un account vero D13 è spenta: backup, "Libera spazio", "Elimina solo dal telefono", correzioni e versioni agirebbero sulle foto vere. D12 e D31 hanno tenuto (niente scritto sulle share vere), ma si è fermata. Il brief di questo copione dice che l'app è già configurata con `test`: **va ricontrollato** (2.5) e, se non è così, finché l'utente non la riconfigura **non parte niente** di A e B che scriva o cancelli. Rifarla vuole la password (solo l'utente) e, su questo S25, vuol dire perdere dal telefono la **chiave di famiglia** dell'admin: serve prima il kit di recupero con il codice o un secondo admin (2.8, punto 0). Alternative: un altro profilo del telefono, o il Tab S9 con `test2`.
2. **L'APK di release non è installabile.** `app\build\outputs\apk\release\app-release-unsigned.apk` non è firmato: in `local.properties` c'è solo `sdk.dir`, non le chiavi `pampa.*`. Senza firma niente `adb install`. F2, F19, F32, F44, FF-17, 5a-R2 e i messaggi di release (LS-1, IN-6, VE-7) restano in C. Firmarlo con la chiave di debug (`apksigner`) sarebbe possibile ma è una scelta dell'utente.
3. **F8 non si può eseguire.** `tools/hostile-index` non esiste. E scrivere segmenti ostili nella `.mycloudgallery` vera di `test` è fuori da `mcg-it/<giro>/`, quindi fuori dalla regola sulle scritture dal PC.
4. **Il verificatore non guarda dove D13 mette le versioni.** `BackupAreaVerifier` cerca `Versioni/` alla radice della share (`test\Versioni`), mentre con la recinzione del telefono le versioni stanno in `test\mcg-campo\Versioni\Backup\...` (`VersionPaths`, `docs/decisioni.md` «Fase 5B: P8»). Per F34, F36 e F43 sul telefono l'invariante 13 darà quindi un rosso "l'originale di una modifica del telefono non c'e' piu' sul NAS" che non è un difetto del backup: si controlla a mano (l'utente conta i file "(originale)" in `mcg-campo\Versioni`). Correzione consigliata, da fare altrove: `dir?.child("Versioni")` come per `Backup`.
5. **Il cestino di sistema su One UI (D-3 del 20/10).** Dopo `createTrashRequest` i file escono da `mcg-prova` e vanno nel cestino di Samsung Gallery; la prova guidata non li ritrova e "Ripristina quelle foto" dice "Niente da fare". Conseguenze: i ripristini li fa l'utente dalla Galleria; `s25.ps1 -Step verify -AllowFreed` va bene (le foto "tolte" non sono un cambiamento); e "Libera spazio" potrebbe non riconoscere le righe cestinate (da guardare in B-S9). Se invece comparissero file `.trashed-<ora>-<nome>` nella cartella, `s25.ps1` li leggerebbe come impronte sconosciute anche con `-AllowFreed` (falso rosso): in quel caso solo `VERIFICA-NAS`.
6. **La batteria si misura male.** Il telefono è collegato in USB con un cavo lento: il 20/10 la batteria è scesa dal 63% al 59% in 25 minuti a schermo acceso. La misura di F22 (batteria entro ±30%) e di F18/F33 è grossolana; passare a `adb` senza cavo è una modifica delle impostazioni. Si annota il livello e basta.
7. **`exiftool` non c'è sul PC** (`where exiftool` non lo trova). F36 e F42 si controllano in modo **indicativo** con `Exif-Info` e `Check-NoGps` (§5); la prova vera vuole `exiftool`, cioè scaricare un programma: serve il sì dell'utente.
8. **L'APK può non essere quello del commit.** L'APK di debug presente è del 20/10 alle 16:22, il commit `f705a6f` è delle 16:28, e l'albero di lavoro ha modifiche non committate in `BackupGraph.kt`, `RunProgress.kt`, `BackupStages.kt` e altri. Prima di installare si confrontano le date (2.2) e si annotano `git status` e l'impronta dell'APK.
9. **Il telefono deve restare sbloccato e con lo schermo acceso** per tutto il blocco A. Il timeout dello schermo è una impostazione: la cambia l'utente, se vuole. Nessun comando sbloccherà mai il telefono.
10. **F29: l'atteso del progetto è superato.** `fase5-progetto.md` §5.6 dice "copie private in `Backup/Area comune/` (Q4 a)", ma il 15/10 l'utente ha scelto Q4 (b). B-S8 osserva e annota.
11. **Quello che il piano chiama "dal PC" dentro `mcg-campo/`** (F4, F5, F9, F25, F29, F31-bis, F34-bis, F37) contraddice la regola "dal PC solo sotto `mcg-it/<giro>/`" se lo fa l'orchestratore. Nel copione lo fa l'**utente** in Esplora risorse. L'orchestratore scrive sul NAS solo con `area.ps1` (semina in `mcg-it`) e con la suite di `core-nas`.
12. **Dati di prova fatti a mano.** 5a-R1 (nomi da 255 byte), 5a-R6 (allungare un file senza rileggere il MediaStore), 4a-I3 (nomi `.nomedia`) e F35 simulato vogliono file che la semina non crea, e la regola dice che i dati di prova si mettono solo con `s25.ps1 -Step seed`. Sono in C-18 fino a un OK esplicito o a un passo nuovo dello script.
13. **I numeri della Diagnostica non si leggono dallo schermo.** Le voci hanno solo titolo e codice, e l'anteprima dell'esportazione non è leggibile da `uiautomator` (O-4 del 20/10). Per F3, F6a, F22, F23 (riscontro), F4 e i contatori delle integrazioni serve che l'**utente salvi l'esportazione** (un dialogo di sistema, 30 secondi): due esportazioni fisse (E1, E2) più quelle nelle sessioni. Per questo alcune misure di A non sono davvero "da solo". Una strada migliore (da fare altrove) sarebbe un file dell'app con i contatori, leggibile con `run-as`, come già fanno le prove guidate.
14. **F39 da `adb`: l'atteso della revisione 5B è sbagliato.** Dalla shell il provider dei documenti risponde, perché la shell ha `MANAGE_DOCUMENTS` (O-3 del 20/10). La `SecurityException` vale per un'app senza quel permesso, e non c'è un'app così tra quelle ammesse (C-26). Resta provato dal `FileProvider` non esportato.
15. **`area.ps1` mette `ANDROID_SERIAL=none`** dentro il suo processo (le sue prove da PC non usano `adb`). Non contagia la shell se si lancia con `powershell -ExecutionPolicy Bypass -File` (come in tutto il copione). Nessuna prova con `adb` passa da lì.
16. **"NAS spento" è simulato con il Wi-Fi spento** (C-22), al massimo 120 secondi per volta (limite di `s25.ps1`). Il caso "NAS irraggiungibile con il telefono a casa" non si prova.
17. **D34 ("Proteggi subito le foto nuove") non è provabile in debug.** Sono foto della fotocamera, che D13 non lascia leggere. Si prova solo dopo Q3, con la release e il backup vero (C-14).
18. **F1 non si ripete, e D-2.** Se la prima scansione dell'area di prova è già stata fatta, rifarla vorrebbe dire azzerare l'app e rifare l'onboarding con le password: non si fa. In più, la prima scansione dopo un avvio a freddo può fallire e non riprovare (D-2 del 20/10): A.2 e A.11 la guardano.
19. **I nomi dei passi di F21, F25 e F26.** Il progetto parla di "Fai ora" sulla singola foto; nell'app il primo backup parte da "Vedi il riepilogo → Inizia ora" e "Fai ora" compare dopo. In F26, "senza ricaricare" è ambiguo (la foto non era ancora stata caricata): qui si legge come "finisce al sicuro una volta sola".
20. **Il formato della riga `pre=`** (percorso dal punto di vista del verificatore) non ha un esempio: B-S2 dice come correggerlo se il rapporto lo contesta.
21. **Il video da 1 GB di F39 e F40** non si fa con la semina (una sola dimensione per i video grandi: qui 3 GB). Si usa un video dell'utente; senza, resta il rifiuto del video da 3 GB.
22. **B-S17 (permessi)** chiede all'utente di cambiare i permessi di MyCloud Gallery nelle impostazioni di Android. È una modifica delle impostazioni, fatta dall'utente e rimessa a posto; l'orchestratore non la fa. Se la regola va letta in modo più stretto, passa a C.
23. **`docs/android.md` e la pagina delle prove guidate** dicono di seminare con `s25.ps1 -Mode seed` e che la semina fa circa 300 righe: il comando giusto è `-Mode backup -Step seed`, con `-Photos 520` per le 500 righe di T-09 (f). (Anche D-5 del 20/10.) Inoltre F1–F20 stanno nel §5.7 di `fase4-progetto.md`, non nel §5.6.
24. **I file finti dei fixture e due screenshot (O-1, O-2 del 20/10).** `casuali.png`, `testo.jpg` e `video-falso.mp4` non entrano mai nel catalogo: qui sono tolti dalla semina (2.4). Due `Screenshot_*.png` non risultano in nessuna sorgente con D13: se il backup non li salva, F30 darà `LOSS` di 2 file. Decidere se è voluto (screenshot fuori dalla sorgente `mcg-prova`) o un difetto.


## 9. Fase 6, revisione C: gli strumenti per le prove F54 e F61

Questa sezione dice come si usano due strumenti sul telefono che la revisione C ha corretto (DB-2, DB-4). Le prove F46–F71 stanno nel §5 di `docs/fase6-progetto.md`; qui ci sono solo i comandi.

### 9.1 F61 · il verificatore dei dati dell'AI (`s25.ps1 -Mode ai`)

```powershell
# a secco: il calendario e i comandi, senza telefono
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode ai -DryRun -Interruptions 100 -Seed 61
# la prova: almeno 100 interruzioni (kill -9 e Wi-Fi spento), poi il verificatore
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode ai -Serial RFCY61FZDHW -Interruptions 100 -Seed 61 -SettleSeconds 300 -Verify | Tee-Object "$out\f61.log"
# o a parte, a prova finita (i record rovinati ammessi sono 9 per ogni morte: qui al massimo 100 morti)
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode ai -Step verify -Serial RFCY61FZDHW -Interruptions 100
```

Cosa cambia rispetto a prima (DB-2): il file dei vettori si chiama `vectors-embeddinggemma2_float_v1-<generazione>.bin` e sta in `no_backup/search/index/`; è little-endian (il segno `MCGV` è `56 47 43 4D`). Prima lo script cercava un altro nome e un altro ordine dei byte, non trovava niente e diceva sempre "tutto a posto". Adesso nel racconto (`build\chaos\s25-*.log`) deve comparire la riga `search/: un file di vettori, N record buoni, M rovinati, coda troncata K byte`: **se manca, la verifica non ha letto niente e F61 non è passata**. Un file `vectors-*` con un nome che l'app non scrive, o non vuoto senza nemmeno un record riconoscibile, fa fallire la verifica (codice 4). Una morte durante la scrittura lascia una coda troncata (meno di un record): è normale e il verificatore la accetta.

### 9.2 F54 · il set OCR dentro l'app e le etichette fuori (DB-4)

Dalla shell di `adb` la cartella dell'app non si scrive (serve `run-as`, che c'è solo per la build di debug): per questo ci sono due passi dello script, che toccano **solo** `no_backup/eval/` dell'app di debug.

```powershell
# 1. il set OCR sintetico sul PC (testi inventati, nessun dato personale; non va committato)
powershell -ExecutionPolicy Bypass -File tools\fixtures\generate-ocr.ps1
# 2. dentro l'app: adb push in /data/local/tmp, poi `run-as <pacchetto> cp` in no_backup/eval/ocr/, poi la copia temporanea si toglie
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode ai -Step eval-push -Serial RFCY61FZDHW -EvalSource tools\fixtures\out\MCG-ocr -DryRun
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode ai -Step eval-push -Serial RFCY61FZDHW -EvalSource tools\fixtures\out\MCG-ocr
# 3. nell'app: Impostazioni > Sviluppo > Valutazione della ricerca (le foto locali si vedono: DB-1), poi "Lettura di prova" per il set OCR
# 4. i risultati sul PC: labels.mcgeval, report-NN.txt, ocr\letto.tsv e ocr\ocr-resoconto.txt (e nient'altro) in build\chaos\eval-<data>\
powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode ai -Step eval-pull -Serial RFCY61FZDHW
powershell -ExecutionPolicy Bypass -File tools\fixtures\generate-ocr.ps1 -Resoconto build\chaos\eval-<data>\ocr\letto.tsv -Etichetta "ML Kit su S25"
```

Le **etichette sono dati privati**: la cartella di arrivo è sotto `build\` (ignorata da git) e non vanno mai nel repository; per tenerle si copiano a mano in `%USERPROFILE%\.mycloudgallery\eval\`. I resoconti (`report-NN.txt`) hanno solo numeri e si possono copiare in `docs/decisioni.md`. `eval-push` ed `eval-pull` valgono solo con `-Mode ai` e la build di debug; da soli non toccano il Wi-Fi.

## 10. Fase 10: F160-F189 e i tre momenti dell'utente

Il copione completo (preparazione, prove, atteso) è in `docs/fase10-progetto.md` §5.5; gli scenari del §10 del piano e i tempi dell'utente nel
§2.14. Le prove girano **da sole** sull'S25 (build debug e account `test`; la **release candidata** per le prove della release) e
sull'emulatore (`test`, acceso solo per la prova); il verificatore dal PC controlla il NAS di prova. Gli agenti confermano solo i dialoghi della
nostra app e usano Galleria Samsung e Archivio solo sui file di prova. Esiti in `docs/prove-campo-esiti.md` (P14).

| # | Prova | Dove | Pacchetto |
|---|---|---|---|
| F160-F164 | Widget: tre misure, nascosti (anche da un altro dispositivo), 24 ore di aggiornamenti, blocco e tocco, azzeramento | S25, emulatore | P3, P13 |
| F165-F169 | Tablet: pannelli e rotazione, DeX e finestre libere, due finestre e blocco, trascinamento in uscita e in arrivo | Tab S9 (momento A) | P4, P5, P6 |
| F170-F181 | Revisione finale 10.4 (S1-S9, S13): account diversi, dispositivi canaglia, segmenti e immagini ostili, dispositivo perso, backup di Android, log della release, modello e manifest manomessi, rete estranea, cambio di massa, traffico e componenti | PC; S25 (debug con `test`: le voci sull'app che leggono il NAS); emulatore (rete estranea, backup, log e componenti della release candidata) | P11 |
| F182-F185 | Accessibilità: TalkBack su ogni schermata, testo 2,0x e contrasto alto, movimento ridotto e tastiera, servizio non "strumento" | S25, Tab S9, emulatore | P8 |
| F186-F187 | Release candidata (firmata con `pampa.jks`, D32): installazione e aggiornamento con la stessa firma, un APK con un'altra firma rifiutato; tutte le funzioni con R8 | S25 | P12, P13 |
| F188 | Prestazioni della fase (§5.4 del progetto) | S25; Tab S9 nel momento A | P9 |
| F189 | Gli otto scenari del §10 del piano | tutti | P14 |

**F170-F181 e F185 dopo la seconda parte di P11 (02/11, pronte e provate contro i finti, non eseguite)**: il copione è `tools/security/PROSSIMA-FINESTRA.md`. Cambia il dove: l'emulatore non è "casa" (L600 di `docs/sicurezza.md`), quindi F170 (con `test`, il controllo; con `test2` solo con un dispositivo a casa con `test2`), F171, F172, F176, F177 e F179 sull'app si fanno sull'**S25** con la build debug, dopo il **reset della famiglia di prova** (decisione 1 dell'utente del 02/11: la famiglia `test` rifatta da capo, l'utente scrive una volta la password di `test` e appoggia il dito ai fogli della chiave); F173 (S4) resta **bloccata** finché un emulatore non è casa; F174 (S5) sull'emulatore con `bmgr`, il "device transfer" dichiarato; F175 (S6) con la release candidata sull'emulatore (o sull'S25 con `s25.ps1 -Mode security -Release`), con il controllo degli stack trace; F178 (S8) con la cattura `-tcpdump` dell'emulatore, la parte (c) dichiarata (PCAPdroid no, decisione 3); F180 con `netstats` per UID (byte, non host: dichiarata la parte host); F181 con `dumpsys package` contro l'elenco chiuso di `gradle/verify-release-manifest.gradle`; F185 (S12) sull'emulatore `Pampa_Tablet` (Android 15, API 35: `accessibilityDataSensitive` c'è da Android 14), con le impostazioni di accessibilità salvate e rimesse, comprese le tre chiavi che l'accensione di un servizio svuota.

**I tre momenti dell'utente** (tutto il resto gira da solo, di notte o quando il telefono è libero):
- **Momento A, tablet (circa 25 minuti, una volta)**: profili "Prova 1" (`test`) e "Prova 2" (`test2`), password, WireGuard di prova nel
  profilo "Prova 1", tablet collegato al PC per la notte (F165-F169, F182-F184 sul tablet e i momenti con il tablet ancora in sospeso).
- **Momento B, una sera (circa 5 minuti)**: tablet sulla rete "fuori casa", NAS spento; al mattino NAS riacceso (scenari 1 e 2).
- **Momento C (circa 10 minuti)**: il codice del kit della famiglia di prova (scenario 6) e, se vuole, 5 minuti con TalkBack (F182).
- **Prima delle prove**: le due chiavi del manifest K1 e K2 (circa 10 minuti) e la verifica della copia offline di `pampa.jks`; **nessuna
  chiave dell'APK nuova** (D32).
- **Dopo le prove**: un solo sì per il passaggio alle share vere (Q6 (b), circa 10 minuti con i numeri della simulazione); lo scenario 8 si
  misura nella settimana dopo; al rilascio, `docs/sicurezza.md` §1 (15 minuti) e il giorno della famiglia (Q7 (a)).
