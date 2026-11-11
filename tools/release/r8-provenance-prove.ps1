# Dati SINTETICI scritti a mano, senza Gradle o Android.
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot\..\..").Path
$work=Join-Path $root 'build\prova-r8'
New-Item -ItemType Directory -Force $work | Out-Null
Add-Type -Path (Join-Path $PSScriptRoot 'r8-provenance.cs')
@'
allowed.Owner -> residual.Fused:
    1:2:void allowed.Owner.run():10:11 -> run
    3:4:void forbidden.Owner.run():20:21 -> run
    5:5:void forbidden.Inline.work():30:30 -> run
    5:5:void allowed.Owner.run():12 -> run
    6:6:void allowed.Owner.run():13:13 -> run
    6:6:java.lang.Object allowed.Owner.run(java.lang.Object):14:14 -> run
missing.Positions -> missing.Positions:
    1:1:void allowed.Owner.run():1 -> run
    2:2:void forbidden.Owner.run():2 -> run
ambiguous.Owner -> ambiguous.Owner:
    1:4:void allowed.Owner.run():1 -> run
    2:5:void forbidden.Owner.run():2 -> run
unique.Owner -> unique.Owner:
    void run() -> run
library.Specialized -> library.Specialized:
    1:1:void accept(java.lang.Object):1 -> accept
      # {"id":"com.android.tools.r8.residualsignature","signature":"(Landroidx/glance/Thing;)V"}
    2:2:void accept(java.lang.Object):2 -> accept
library.Real -> library.Real:
    1:1:void accept(androidx.glance.Thing):1 -> accept
      # {"id":"com.android.tools.r8.residualsignature","signature":"(Landroidx/glance/Thing;)V"}
library.NoMetadata -> library.NoMetadata:
    1:1:void accept(java.lang.Object):1 -> accept
androidx.glance.InlineMixed -> androidx.glance.InlineMixed:
    10:10:void androidx.glance.First.run():1 -> run
    10:10:void dev.pampa.mycloudgallery.backup.Forbidden.run():1 -> run
androidx.glance.Good -> androidx.glance.Good:
    10:10:java.lang.Boolean kotlin.runtime.Innocent.box(boolean):1 -> run
    10:10:void run():1 -> run
    11:11:void run():2 -> run
kotlin.runtime.Innocent -> kotlin.runtime.Innocent:
    1:1:java.lang.Boolean box(boolean):1 -> box
androidx.glance.Bad -> androidx.glance.Bad:
    10:10:java.lang.Boolean kotlin.runtime.Evil.box(boolean):1 -> run
    10:10:void run():1 -> run
    11:11:void run():2 -> run
kotlin.runtime.Evil -> kotlin.runtime.Evil:
    1:1:java.lang.Boolean box(boolean):1 -> box
androidx.glance.Allowed -> androidx.glance.Allowed:
    10:10:void androidx.glance.First.run():1 -> run
    11:11:void androidx.glance.Second.run():1 -> run
androidx.glance.Mixed -> androidx.glance.Mixed:
    10:10:void androidx.glance.First.run():1 -> run
    11:11:void dev.pampa.mycloudgallery.backup.Forbidden.run():1 -> run
androidx.glance.Empty -> androidx.glance.Empty:
    int field -> field
dev.pampa.mycloudgallery.backup.Residual -> dev.pampa.mycloudgallery.backup.Residual:
    10:10:void androidx.glance.First.run():1 -> run
'@ | Set-Content (Join-Path $work 'mapping.txt')
$prefixes=@('core/models/download/https/', 'feature/map/net/', 'feature/map/', 'feature/widget/', 'core/ai/', 'core/ai/ocr/MlKitBootstrap', 'core/faces/sync/', 'albums/', 'di/')
foreach($prefix in $prefixes) {
 $owner='dev.pampa.mycloudgallery.'+$prefix.Replace('/','.')+'Owner'
 @("$owner -> ${owner}:", "    1:1:void run():1 -> run", "    2:2:void forbidden.Owner.run():2 -> run") | Add-Content (Join-Path $work 'mapping.txt')
}
$policyCases=@(
 @('HTTPS','kotlin.io.TextStreamsKt','core/models/download/https','Ljava/net/URL;.openStream:', 'invoke-virtual {v0}, Ljava/net/URL;.openStream:()Ljava/io/InputStream; // method@1'),
 @('OkHttp','okhttp3.OkHttpClient','feature/map/net','Lokhttp3/', 'invoke-virtual {v0,v1}, Lokhttp3/OkHttpClient;.newCall:(Lokhttp3/Request;)Lokhttp3/Call; // method@1'),
 @('Glance','androidx.glance.appwidget.GlanceAppWidget','feature/widget','Landroidx/glance/', 'invoke-virtual {v0}, Landroidx/glance/appwidget/GlanceAppWidget;.update:()V // method@1'),
 @('LiteRT','com.google.ai.edge.litert.Interpreter','core/ai','Lcom/google/ai/edge/litert/', 'invoke-virtual {v0}, Lcom/google/ai/edge/litert/Interpreter;.run:()V // method@1'),
 @('MapLibre','org.maplibre.android.MapLibre','feature/map','Lorg/maplibre/', 'invoke-virtual {v0}, Lorg/maplibre/android/maps/MapView;.onCreate:()V // method@1')
)
foreach($case in $policyCases) {
 $tag=$case[0];$lib=$case[1];$allowed='dev.pampa.mycloudgallery.'+$case[2].Replace('/','.')
 foreach($kind in @('Bad','Good','Chain','Engine')) {
  $owner=if($kind -eq 'Good') {"$allowed.Consumer$tag"} elseif($kind -eq 'Engine') {"dev.antigravity.fluidengine.ui.Evil$tag"} else {"dev.pampa.mycloudgallery.backup.Evil$tag$kind"}
  $rows=@("$owner -> ${owner}:")
  $rows+=if($kind -eq 'Good') {"    1:1:void $allowed.Helper.run():1 -> run"} else {"    1:1:void $lib.inner():1 -> run"}
  if($kind -eq 'Chain') {$rows+='    1:1:void kotlin.collections.CollectionsKt.middle():1 -> run'}
  $rows+='    1:1:void run():12 -> run'
  $rows | Add-Content (Join-Path $work 'mapping.txt')
 }
}
[R8Provenance]::MappingPath=Join-Path $work 'mapping.txt'
$count=0
function Check($name,[bool]$ok) { if(-not $ok) {throw "FAIL $name"}; $script:count++; Write-Host "[OK] $name" }
function Throws($name,[scriptblock]$body) { $caught=$false; try { & $body | Out-Null } catch {$caught=$true}; Check $name $caught }
function Dump($class,$signature,$positions,[string]$instruction='invoke-static {}, Ljava/net/HttpURLConnection;.disconnect:()V // method@1') {
 $lines=@("Class descriptor : '$class'", "name : 'run'", "type : '$signature'", ('1000: 0000 |0000: '+$instruction)) + $positions
 [R8Provenance]::ReadDump($class+$signature,[string[]]$lines)
 return $lines[3]
}
$call=Dump 'Lresidual/Fused;' '()V' @(' 0x0000 line=1')
Check 'fusione origine ammessa' ([R8Provenance]::Origin('Lresidual/Fused;()V',$call) -eq 'Lallowed/Owner;.run')
$call=Dump 'Lresidual/Fused;' '()V' @(' 0x0000 line=3')
Check 'fusione inversa origine vietata sotto nome ammesso' ([R8Provenance]::Origin('Lresidual/Fused;()V',$call) -eq 'Lforbidden/Owner;.run')
$call=Dump 'Lresidual/Fused;' '()V' @(' 0x0000 line=5')
Check 'inline frame interno vietato prevale' ([R8Provenance]::Origin('Lresidual/Fused;()V',$call) -eq 'Lforbidden/Inline;.work')
$call=Dump 'Lresidual/Fused;' '(Ljava/lang/Object;)Ljava/lang/Object;' @(' 0x0000 line=6')
Check 'overload firma residuale' ([R8Provenance]::Origin('Lresidual/Fused;(Ljava/lang/Object;)Ljava/lang/Object;',$call) -eq 'Lallowed/Owner;.run')
$call=Dump 'Lmissing/Positions;' '()V' @()
Throws 'fusione senza posizioni cade' { [R8Provenance]::Origin('Lmissing/Positions;()V',$call) }
$call=Dump 'Lambiguous/Owner;' '()V' @(' 0x0000 line=3')
Throws 'intervalli sovrapposti cadono' { [R8Provenance]::Origin('Lambiguous/Owner;()V',$call) }
$call=Dump 'Lunknown/Owner;' '()V' @(' 0x0000 line=1')
Throws 'mapping assente cade' { [R8Provenance]::Origin('Lunknown/Owner;()V',$call) }
$call=Dump 'Lunique/Owner;' '()V' @()
Check 'identita unica senza posizioni' ([R8Provenance]::Origin('Lunique/Owner;()V',$call) -eq 'Lunique/Owner;.run')
foreach($prefix in $prefixes) {
 $class='Ldev/pampa/mycloudgallery/'+$prefix+'Owner;'
 $call=Dump $class '()V' @(' 0x0000 line=1')
 Check "policy $prefix origine ammessa passa" ([R8Provenance]::Origin($class+'()V',$call).StartsWith('Ldev/pampa/mycloudgallery/'+$prefix))
 $call=Dump $class '()V' @(' 0x0000 line=2')
 Check "policy $prefix fusione inversa cade" (-not [R8Provenance]::Origin($class+'()V',$call).StartsWith('Ldev/pampa/mycloudgallery/'+$prefix))
}
function Outline($owner,$body) {
 $class='L'+$owner+'$$ExternalSyntheticApiModelOutline0;'
 [R8Provenance]::ReadDump('outline',[string[]]@("Class descriptor : '$class'", "name : 'm'", "type : '()V'", ('2000: 0000 |0000: '+$body), '2002: 0000 |0003: return-void'))
 return ('{}, '+$class+'.m:()V // method@1')
}
$ref=Outline 'org/maplibre/Fake' 'invoke-static {}, Landroid/app/Activity;.platform:()V'
Check 'outline nome MapLibre corpo Android accettato' ([R8Provenance]::PlatformOutline($ref))
foreach($target in @('Lorg/maplibre/Real;','Lokhttp3/Real;','Landroidx/glance/Real;','Ljava/net/HttpURLConnection;','Landroid/webkit/WebView;')) {
 $ref=Outline 'org/maplibre/Fake' ('invoke-static {}, '+$target+'.real:()V')
 Check "outline $target non esentato" (-not [R8Provenance]::PlatformOutline($ref))
 Check "outline $target propagato al chiamante" ([R8Provenance]::OutlineBody($ref).Contains($target))
}
Throws 'outline corpo assente cade' {[R8Provenance]::PlatformOutline('{}, Lmissing/Fake$$ExternalSyntheticApiModelOutline0;.m:()V // method@1')}
$ref=Outline 'org/maplibre/Fake' 'invoke-custom {}, call_site@1'
Throws 'outline indiretto cade' {[R8Provenance]::PlatformOutline($ref)}
$ref=Outline 'org/maplibre/Fake' 'invoke-static {}, Landroid/Fake$$ExternalSyntheticApiModelOutline1;.m:()V'
Throws 'outline annidato non dimostrato cade' {[R8Provenance]::PlatformOutline($ref)}
$ref=Outline 'org/maplibre/Fake' 'return-void'
Check 'outline no-op non chiama librerie' ([R8Provenance]::PlatformOutline($ref))
foreach($callee in @('Ldev/pampa/mycloudgallery/feature/map/net/Good;', 'Lforbidden/Bad;')) {
 $line='3000: 0000 |0000: invoke-static {}, '+$callee+'.$r8$lambda$0:(Lokhttp3/Client;)V // method@1'
 [R8Provenance]::ReadDump('bridge',[string[]]@('Class descriptor : ''Lallowed/Fused$$ExternalSyntheticLambda0;''', "name : 'run'", "type : '()V'", $line))
 Check "ponte sintetico attribuito a $callee" ([R8Provenance]::Origin('bridge',$line).StartsWith($callee))
}
# La stessa quantificazione universale usata dalle policy e dalle prove positive dei cancelli.
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $root 'tools/check.ps1'),[ref]$null,[ref]$null)
$function=$ast.FindAll({param($a) $a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Test-R8EveryOrigin'},$false)[0]
Invoke-Expression $function.Extent.Text
$glancePolicy={param($o) $o.StartsWith('Landroidx/glance/')}
$call=Dump 'Landroidx/glance/Allowed;' '()V' @(' 0x0000 line=1')
$group=[R8Provenance]::OriginGroup('Landroidx/glance/Allowed;()V',$call)
Check 'prologo tutte origini ammesse passa' (Test-R8EveryOrigin $group $glancePolicy)
Check 'prologo include entrambe origini e residua' (($group -split "`n").Count -eq 3)
$call=Dump 'Landroidx/glance/Mixed;' '()V' @(' 0x0000 line=1')
$group=[R8Provenance]::OriginGroup('Landroidx/glance/Mixed;()V',$call)
Check 'prologo origine nostra vietata sotto libreria cade' (-not (Test-R8EveryOrigin $group $glancePolicy))
Check 'prologo nomina origine vietata' ($group.Contains('Ldev/pampa/mycloudgallery/backup/Forbidden;'))
$call=Dump 'Ldev/pampa/mycloudgallery/backup/Residual;' '()V' @(' 0x0000 line=1')
$group=[R8Provenance]::OriginGroup('Ldev/pampa/mycloudgallery/backup/Residual;()V',$call)
Check 'anche residua deve essere ammessa' (-not (Test-R8EveryOrigin $group $glancePolicy))
$call=Dump 'Landroidx/glance/Empty;' '()V' @(' 0x0000 line=1')
Throws 'mapping senza nessuna origine cade' {[R8Provenance]::Origins('Landroidx/glance/Empty;()V',$call,'Landroidx/glance/')}
$call=Dump 'Landroidx/glance/Allowed;' '()V' @(' 0x0002 line=10')
Check 'prima voce tabella successiva al prologo usa insieme' ([R8Provenance]::Origins('Landroidx/glance/Allowed;()V',$call,'').Count -eq 3)
# Runtime innocuo e runtime dal nome Kotlin che contiene davvero Glance.
foreach($kind in @('Good','Bad')) {
 $runtime=if($kind -eq 'Good') {'Innocent'} else {'Evil'}
 $runtimeBody=if($kind -eq 'Good') {'new-instance v0, Ljava/lang/Boolean;'} else {'invoke-static {}, Landroidx/glance/Forbidden;.call:()V'}
 [R8Provenance]::ReadDump(('runtime'+$kind),[string[]]@("Class descriptor : 'Lkotlin/runtime/$runtime;'", "name : 'box'", "type : '(Z)Ljava/lang/Boolean;'", ('9000: 0000 |0000: '+$runtimeBody), ' 0x0000 line=1'))
 $line="8000: 0000 |0000: invoke-direct {v0}, Landroidx/glance/Continuation;.<init>:(Landroidx/glance/$kind;)V // method@1"
 [R8Provenance]::ReadDump($kind,[string[]]@("Class descriptor : 'Landroidx/glance/$kind;'", "name : 'run'", "type : '()V'", $line, '8014: 0000 |000a: new-instance v0, Ljava/lang/Boolean;', ' 0x0000 line=1', ' 0x000a line=10'))
 $origins=[R8Provenance]::Origins($kind,$line,'Landroidx/glance/')
 $ok=Test-R8EveryOrigin ($origins -join "`n") $glancePolicy
 Check "runtime $runtime verifica del corpo" ($ok -eq ($kind -eq 'Good'))
 if($kind -eq 'Bad') {Check 'runtime pericoloso nomina origine Kotlin' (($origins -join ',').Contains('Lkotlin/runtime/Evil;'))}
 # Il corpo INLINE stesso diventa pericoloso: nessuna esenzione dal nome Kotlin.
 if($kind -eq 'Good') {
  [R8Provenance]::ReadDump($kind,[string[]]@("Class descriptor : 'Landroidx/glance/$kind;'", "name : 'run'", "type : '()V'", $line, '8014: 0000 |000a: invoke-static {}, Landroidx/glance/Forbidden;.call:()V', ' 0x0000 line=1', ' 0x000a line=10'))
  $origins=[R8Provenance]::Origins($kind,$line,'Landroidx/glance/')
  Check 'runtime inline che nomina Glance cade' (-not (Test-R8EveryOrigin ($origins -join "`n") $glancePolicy))
 }
}
$call=Dump 'Landroidx/glance/InlineMixed;' '()V' @(' 0x0000 line=1')
$origins=[R8Provenance]::Origins('Landroidx/glance/InlineMixed;()V',$call,'')
Check 'insieme include anche frame esterno inline vietato' (($origins -join ',').Contains('Ldev/pampa/mycloudgallery/backup/Forbidden;'))
Check 'frame esterno inline vietato cade' (-not (Test-R8EveryOrigin ($origins -join "`n") $glancePolicy))
Check 'prova positiva non usa un solo candidato ammesso' (-not (Test-R8EveryOrigin $group {param($o) $o.StartsWith('Landroidx/glance/First;')}))
Check 'firma specializzata senza dipendenza originale passa' ([R8Provenance]::SignatureOnlyArtifact('{}, Llibrary/Specialized;.accept:(Landroidx/glance/Thing;)V // method@1','Landroidx/glance/'))
Check 'firma originale nomina Glance quindi cade' (-not [R8Provenance]::SignatureOnlyArtifact('{}, Llibrary/Real;.accept:(Landroidx/glance/Thing;)V // method@1','Landroidx/glance/'))
Check 'firma senza residualsignature non esentata' (-not [R8Provenance]::SignatureOnlyArtifact('{}, Llibrary/NoMetadata;.accept:(Landroidx/glance/Thing;)V // method@1','Landroidx/glance/'))
Check 'callee di Glance non esentato dalla firma' (-not [R8Provenance]::SignatureOnlyArtifact('{}, Landroidx/glance/Real;.accept:(Landroidx/glance/Thing;)V // method@1','Landroidx/glance/'))
# residualsignature si applica a tutti gli intervalli della stessa firma, non solo al primo.
[R8Provenance]::ReadDump('signature',[string[]]@("Class descriptor : 'Llibrary/Specialized;'", "name : 'accept'", "type : '(Landroidx/glance/Thing;)V'", '7000: 0000 |0000: invoke-static {}, Ljava/lang/Object;.call:()V', ' 0x0000 line=2'))
Check 'residualsignature propagata al secondo intervallo' ([R8Provenance]::Origin('signature','7000: 0000 |0000: invoke-static {}, Ljava/lang/Object;.call:()V') -eq 'Llibrary/Specialized;.accept')
$line='8000: 0000 |0000: invoke-direct {v0}, Landroidx/glance/Continuation;.<init>:(Landroidx/glance/Good;)V // method@1'
[R8Provenance]::ReadDump('Good',[string[]]@("Class descriptor : 'Landroidx/glance/Good;'", "name : 'run'", "type : '()V'", $line, '8014: 0000 |000a: check-cast v0, Landroidx/glance/GenericElement;', ' 0x0000 line=1', ' 0x000a line=10'))
$origins=[R8Provenance]::Origins('Good',$line,'Landroidx/glance/')
Check 'cast generico inline non e una chiamata alla libreria' (Test-R8EveryOrigin ($origins -join "`n") $glancePolicy)
[R8Provenance]::ReadDump('runtimeGood',[string[]]@("Class descriptor : 'Lkotlin/runtime/Innocent;'", "name : 'box'", "type : '(Z)Ljava/lang/Boolean;'", '9000: 0000 |0000: check-cast v0, Landroidx/glance/Forbidden;', ' 0x0000 line=1'))
$origins=[R8Provenance]::Origins('Good',$line,'Landroidx/glance/')
Check 'corpo originale Kotlin che nomina Glance anche senza invoke cade' (-not (Test-R8EveryOrigin ($origins -join "`n") $glancePolicy))
# Esegue i cicli REALI di check.ps1 sui fatti sintetici, senza APK/Gradle.
function Invoke-ActualPolicy($policy,$target,$origins,$instruction) {
 $title=switch($policy) {'HTTPS' {'Step "motori on-device'} 'LiteRT' {'Step "LiteRT solo'} 'Glance' {'Step "Glance al suo posto'} default {'Step "OkHttp e MapLibre'}}
 $node=$ast.FindAll({param($a) $a -is [Management.Automation.Language.CommandAst] -and $a.GetCommandName() -eq 'Step' -and $a.Extent.Text.StartsWith($title)},$false)[0]
 if(-not $node) {throw "policy assente: $policy"}
 $body=$node.CommandElements[-1]
 $wanted=@('$initialize','$ownPrefixes','$aiPrefix','$facesPrefix','$facesSessions','$okhttp','$mapLibre','$okhttpCallers','$mapPrefix','$netPrefix','$setClient','$getInstance','$installer','$initializer','$glance','$glanceCallers','$widgetPrefix')
 foreach($a in $body.FindAll({param($a) $a -is [Management.Automation.Language.AssignmentStatementAst] -and $a.Left.Extent.Text -in $wanted},$true)) {Invoke-Expression $a.Extent.Text}
 $text=$body.Extent.Text
 $start=if($policy -in @('HTTPS','LiteRT')) {'  foreach ($call in $facts.Calls) {'} else {'  for ($callIndex = 0;'}
 $end=switch($policy) {'HTTPS' {'  # P11: da quando'} 'LiteRT' {'  if (@($ours |'} 'Glance' {'  if ($widgetInApp'} default {'  if ($mapInApp)'}}
 $a=$text.IndexOf($start);$b=$text.IndexOf($end,$a)
 if($a -lt 0 -or $b -le $a) {throw "ciclo non riconosciuto: $policy"}
 $facts=[pscustomobject]@{Calls=@($target+'|'+($origins -join "`n"));Insns=@($instruction)}
 $problems=@();$ours=@();$okCount=0;$mapCount=0;$okFromNet=0;$installs=0;$inits=0;$bridged=0;$glanceCount=0;$fromWidget=0
 Invoke-Expression $text.Substring($a,$b-$a)
 return ($problems -join '; ')
}
foreach($case in $policyCases) {
 $tag=$case[0];$allowed='dev.pampa.mycloudgallery.'+$case[2].Replace('/','.')
 foreach($kind in @('Bad','Good','Chain','Engine')) {
  $owner=if($kind -eq 'Good') {"$allowed.Consumer$tag"} elseif($kind -eq 'Engine') {"dev.antigravity.fluidengine.ui.Evil$tag"} else {"dev.pampa.mycloudgallery.backup.Evil$tag$kind"}
  $descriptor='L'+$owner.Replace('.','/')+';'
  $call=Dump $descriptor '()V' @(' 0x0000 line=1') $case[4]
  $origins=[R8Provenance]::Origins($descriptor+'()V',$call,$case[3])
  $problem=Invoke-ActualPolicy $tag $case[3] $origins $call
  if($kind -eq 'Good') {Check "CR-3 $tag aiutante nostro ammesso in chiamante ammesso passa" ([string]::IsNullOrEmpty($problem))}
  else {Check "CR-3 $tag $kind librerie trasparenti ciclo reale cade" (-not [string]::IsNullOrEmpty($problem))}
 }
 # Nessuna posizione: il giro 2 deve conservare anche il candidato nostro vietato.
 $descriptor='Ldev/pampa/mycloudgallery/backup/Evil'+$tag+'Bad;'
 $call=Dump $descriptor '()V' @(' 0x0002 line=1') $case[4]
 $origins=[R8Provenance]::Origins($descriptor+'()V',$call,$case[3])
 Check "CR-3 $tag prologo con autore nostro vietato resta bloccato" (-not [string]::IsNullOrEmpty((Invoke-ActualPolicy $tag $case[3] $origins $call)))
}
Write-Host "$count prove OK"
