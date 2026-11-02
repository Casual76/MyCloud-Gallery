# Regole R8 di feature-widget per l'app (Fase 10, P3; docs/fase10-progetto.md D10, D40; tools/check.ps1 passo 7i).
#
# Il passo 7i di tools/check.ps1 legge il bytecode della release e pretende che Glance (`androidx.glance`) sia chiamato solo da Glance stesso, dal kit
# dell'engine (`dev.antigravity.fluidengine.widget`) e da `feature/widget/`, e che da `feature/widget/` ce ne sia almeno una (il widget e' nell'APK). R8, se
# puo', FONDE le classi senza stato in altre classi (anche di librerie diverse) e sposta i metodi (lo si e' visto con OkHttp e MapLibre nella Fase 9, passo 7h):
# un metodo del widget finito dentro una classe di Compose, o uno di Compose dentro una classe di Glance, farebbe dire al controllo cio' che non e'. Le classi qui sotto
# restano quello che sono (niente fusioni, niente inlining fuori dal loro posto), ma quelle che nessuno usa si tolgono lo stesso (allowshrinking): il peso cresce di poco.
-keep,allowshrinking class androidx.glance.** { *; }
-keep,allowshrinking class dev.antigravity.fluidengine.widget.** { *; }
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.widget.** { *; }
