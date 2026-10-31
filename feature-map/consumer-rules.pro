# Regole R8 di feature-map per l'app (Fase 9, P1; docs/fase9-progetto.md D21, D23, §5.5; tools/check.ps1 passo 7h).
#
# Il passo 7h di tools/check.ps1 legge il bytecode della release e pretende che OkHttp sia chiamato solo da feature/map/net/ (e da MapLibre) e
# che MapLibre sia chiamato solo da feature/map/. R8, se puo', FONDE le classi senza stato in altre classi (anche di librerie diverse) e sposta i
# metodi: provato il 30/10 sull'APK di release con MapLibre dentro, metodi di Compose e dell'app finivano in classi di OkHttp e di MapLibre
# (`okhttp3/HttpUrl$Companion`, `org/maplibre/android/http/HttpRequestUrl`...), e il controllo vedeva "OkHttp chiamato" da mezza app. Cosi' il
# controllo non proverebbe niente. Le classi qui sotto restano quello che sono (niente fusioni, niente inlining fuori dal loro posto), ma quelle
# che nessuno usa si tolgono lo stesso (allowshrinking): il peso cresce di poco.
-keep,allowshrinking class okhttp3.** { *; }
-keep,allowshrinking class org.maplibre.** { *; }
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.map.net.** { *; }
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.map.map.MapLibreInit { *; }
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.map.map.MapLibreInit$* { *; }
#
# Fase 9, P13 (come previsto da P4): con :app che collega la mappa, R8 fondeva una lambda della mappa vera (`MapLibreSurfaceContent$Content$...`) in una
# lambda di :app (`PeopleBridge$bind$1`), e il passo 7h vedeva "MapLibre chiamato da codice nostro fuori da feature/map/". Le due sole classi di P4 che
# chiamano MapLibre (la superficie della mappa e la mini-mappa), con le loro classi interne e lambda, restano al loro posto.
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.map.map.MapLibreSurface* { *; }
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.map.map.MapLibreSurface*$* { *; }
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.map.mini.MapLibreMiniMap { *; }
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.map.mini.MapLibreMiniMap$* { *; }
#
# Fase 9, P5: il motore degli scaricamenti delle regioni nomina `OfflineManager` di MapLibre (e le sue classi interne, anche le lambda dei callback): resta al suo posto.
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.map.regions.MapLibreRegionEngine { *; }
-keep,allowshrinking class dev.pampa.mycloudgallery.feature.map.regions.MapLibreRegionEngine$* { *; }
