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
# Le classi di produzione del widget (Fase 10, P13: le porte ci sono). Fino a P13 erano radici (`-keep` senza `allowshrinking`): `WidgetServices` e `WidgetServicesProvider` non
# avevano un'implementazione nell'APK e R8 toglieva il disegno vero. Da P13 `:app` le implementa (`WidgetGraph`, `WidgetPorts`) e le installa in `WidgetHost` all'avvio: il
# disegno e' raggiungibile, quindi le classi restano quello che sono (niente fusioni) ma quelle che nessuno usa si tolgono (`allowshrinking`). Il passo 7i dice se il disegno
# resta nel dex (chiamate a Glance da `feature/widget/`). Non `testing/` (gli esempi per gli screenshot non entrano nella release: lo pretende `tools/check.ps1`).
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.widget.frame.** { *; }
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.widget.glance.** { *; }
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.widget.host.** { *; }
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.widget.refresh.** { *; }
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.widget.tap.** { *; }
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.widget.ui.** { *; }
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.widget.work.** { *; }
