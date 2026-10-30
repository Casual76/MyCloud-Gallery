package dev.pampa.mycloudgallery.feature.map.privacy

import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapOnlineChoice
import dev.pampa.mycloudgallery.core.map.contract.MapPrivacyNote
import dev.pampa.mycloudgallery.core.map.contract.MapRegions
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.feature.map.contract.MapPrivacyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// IMPOSTAZIONI -> MAPPA (Fase 9, P4; docs/fase9-progetto.md §1.5 `MapPrivacyState`, §2.8, D24, **risposta Q1 (c) del 27/10**; contratti `MapSettings` e `MapOnlineChoice` di core-map). **Q1 (c): la mappa
// dettagliata e' accesa per tutti fin dall'inizio, senza pagina di consenso**; qui c'e' l'interruttore per spegnerla **su questo dispositivo**, con la nota breve su cosa esce (`MapPrivacyNote`: la
// zona guardata e l'indirizzo della connessione, mai le foto ne' la loro posizione), "Solo sul Wi-Fi" e "Zone senza Internet". Spegnere non chiede niente e ferma subito gli scaricamenti in corso (E-MAP20);
// **riaccendere** dopo averla spenta e' un tocco dell'utente sull'interruttore, e l'unico posto che costruisce la scelta (`MapOnlineChoice`) e' questo file: nessun altro codice puo' riaccendere da solo cio'
// che l'utente ha spento (Phase9ArchitectureTest, regola 5).

class MapPrivacyController(
  private val settings: MapSettings,
  private val regions: MapRegions,
  private val scope: CoroutineScope,
  /** L'ora **monotona** in millisecondi (mai l'orologio del telefono: nessun testo di scelta porta una data). */
  private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {

  /** Lo stato del §1.5: l'interruttore, "Solo sul Wi-Fi", la versione della nota e quante zone sono scaricate; o "non disponibile" con l'interruttore di build spento. */
  val state: StateFlow<MapPrivacyState> = combine(settings.online, settings.wifiOnly, regions.regions) { online, wifiOnly, mine ->
    if (online is MapOnline.Unavailable) MapPrivacyState.Unavailable else MapPrivacyState.Shown(online, wifiOnly, MapPrivacyNote.VERSION, mine.size)
  }.stateIn(scope, SharingStarted.Eagerly, initial())

  private fun initial(): MapPrivacyState = settings.online.value.let { online ->
    if (online is MapOnline.Unavailable) MapPrivacyState.Unavailable else MapPrivacyState.Shown(online, settings.wifiOnly.value, MapPrivacyNote.VERSION, regions.regions.value.size)
  }

  /**
   * Il tocco sull'interruttore. **Acceso**: la scelta dell'utente (la nota che aveva davanti e l'ora monotona) riaccende la mappa dettagliata. **Spento**: nessuna richiesta da subito, gli
   * scaricamenti in corso si fermano (restano parziali: riprendono solo con un tocco, E-MAP20), la cache resta.
   */
  fun setOnline(on: Boolean) {
    scope.launch {
      if (on) {
        settings.enable(MapOnlineChoice(MapPrivacyNote.VERSION, nowMs()))
      } else {
        settings.disable()
        regions.pauseAll()
      }
    }
  }

  /** "Solo sul Wi-Fi" (acceso di serie; spento: la mappa dettagliata puo' usare i dati mobili, e la pagina avvisa del consumo). Mai in roaming in ogni caso. */
  fun setWifiOnly(on: Boolean) {
    scope.launch { settings.setWifiOnly(on) }
  }

  private companion object {
    const val NANOS_PER_MS = 1_000_000L
  }
}
