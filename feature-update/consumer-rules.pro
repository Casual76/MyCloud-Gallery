# Regole R8 di feature-update per l'app (pacchetto U, 12/11/2026; tools/check.ps1 passo 7e).
#
# Il passo 7e legge il bytecode della release e pretende che i client HTTP (`HttpURLConnection`, `URL.openConnection`...) siano chiamati da codice
# nostro solo dai pacchetti di rete dichiarati: per l'aggiornamento `feature/update/net/` (e il client dell'engine che quel pacchetto avvolge,
# `dev/antigravity/fluidengine/net/`). R8, se puo', fonde le classi piccole in altre classi e sposta i metodi (lezione di feature-map, P1 della
# Fase 9): il controllo vedrebbe la chiamata da una classe che non c'entra. Le classi del pacchetto restano quello che sono; quelle che nessuno usa
# si tolgono lo stesso (allowshrinking).
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.update.net.** { *; }
-keep,allowshrinking class dev.antigravity.fluidengine.net.** { *; }
