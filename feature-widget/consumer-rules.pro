# Regole R8 di feature-widget per l'app (Fase 10, P3; docs/fase10-progetto.md D10, D40; tools/check.ps1 passo 7i).
#
# Il passo 7i di tools/check.ps1 legge il bytecode della release e pretende che Glance (`androidx.glance`) sia chiamato solo da Glance stesso, dal kit
# dell'engine (`dev.antigravity.fluidengine.widget`) e da `feature/widget/`, e che da `feature/widget/` ce ne sia almeno una (il widget e' nell'APK). R8, se
# puo', FONDE le classi senza stato in altre classi (anche di librerie diverse) e sposta i metodi (lo si e' visto con OkHttp e MapLibre nella Fase 9, passo 7h):
# le classi qui sotto restano quello che sono (niente fusioni, niente inlining fuori dal loro posto), ma quelle che nessuno usa si tolgono lo stesso
# (allowshrinking): il peso cresce di poco. Le lambda SINTETICHE di Kotlin (`...$$ExternalSyntheticLambdaN`) non si possono nominare in una regola e R8 le fonde
# comunque in classi con il nome di un'altra: il passo 7i le riconosce dal ponte `$r8$lambda$` che chiamano (tools/check.ps1, Test-R8LambdaBridgeCall).
-keep,allowshrinking class androidx.glance.** { *; }
-keep,allowshrinking class dev.antigravity.fluidengine.widget.** { *; }
#
# **Finche' P13 non lega le porte, le classi del widget sono radici** (`-keep` senza `allowshrinking`). `WidgetServices` e `WidgetServicesProvider` non hanno nessuna implementazione
# nell'APK (le scrive `:app`, P13): R8 lo vede, dichiara morta ogni chiamata alle loro porte e toglie il piano, la decodifica e la composizione con Glance, cioe' il disegno
# vero del widget (provato il 2/11: il passo 7i non vedeva nessuna chiamata a Glance da feature/widget/, ne' con `allowshrinking` ne' tenendo solo `WidgetHost`). Il widget deve
# stare nell'APK com'e' scritto, e P13 potra' tornare a `allowshrinking` quando le implementazioni ci saranno.
# Non `testing/` (gli esempi per gli screenshot non entrano nella release: lo pretende `tools/check.ps1`).
-keep class dev.pampa.mycloudgallery.feature.widget.frame.** { *; }
-keep class dev.pampa.mycloudgallery.feature.widget.glance.** { *; }
-keep class dev.pampa.mycloudgallery.feature.widget.host.** { *; }
-keep class dev.pampa.mycloudgallery.feature.widget.refresh.** { *; }
-keep class dev.pampa.mycloudgallery.feature.widget.tap.** { *; }
-keep class dev.pampa.mycloudgallery.feature.widget.ui.** { *; }
-keep class dev.pampa.mycloudgallery.feature.widget.work.** { *; }
