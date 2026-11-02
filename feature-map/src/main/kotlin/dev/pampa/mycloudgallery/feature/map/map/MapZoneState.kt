package dev.pampa.mycloudgallery.feature.map.map

import androidx.lifecycle.SavedStateHandle
import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.feature.map.grid.ZoneGridController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// LA ZONA APERTA ACCANTO ALLA MAPPA (Fase 10, P5, D13 (2), D14, E-T2; docs/fase10-progetto.md §2.3). Da finestra estesa (840 dp) toccando una bolla la griglia della zona si apre a destra della mappa
// (mappa 60%, griglia 40%) senza cambiare pagina; chiusa, la mappa e' intera. La zona aperta ("bolla aperta") sta nel `SavedStateHandle` della pagina: sopravvive alla ricreazione vera e alla morte del
// processo, e passa fra pannello e pagina (ruotando in verticale la griglia diventa una pagina, tornando in orizzontale torna un pannello) senza che nessuno la perda. La griglia e' **la stessa vista
// viva** della pagina singola (`ZoneGridController`): nessuna copia dei media, stesso filtro finale dei Nascosti. Senza Compose: la provano i test sulla JVM.

internal class MapZoneState(
  private val handle: SavedStateHandle,
  private val newController: (CellKey, CoroutineScope) -> ZoneGridController,
  private val parent: CoroutineScope,
) {
  /** La zona aperta, o null (mappa intera). Una zona che non si decodifica (dato vecchio o ostile) vale "nessuna". */
  val zone: StateFlow<CellKey?> = handle.getStateFlow(KEY, "").map(::decode).stateIn(parent, SharingStarted.Eagerly, decode(handle.get<String>(KEY)))

  private var current: Pair<CellKey, Pair<ZoneGridController, CoroutineScope>>? = null

  fun open(key: CellKey) {
    handle[KEY] = encode(key)
  }

  fun close() {
    handle[KEY] = ""
    release()
  }

  /** Il controllore della griglia di [key]: uno solo alla volta, con il suo lavoro che si ferma quando la zona si chiude o cambia. */
  fun controllerFor(key: CellKey): ZoneGridController {
    current?.let { (open, held) -> if (open == key) return held.first }
    release()
    val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    val controller = newController(key, scope)
    current = key to (controller to scope)
    return controller
  }

  /** Ferma il lavoro della zona che si lascia (la pagina che muore, la zona che si chiude). */
  fun release() {
    current?.second?.second?.cancel()
    current = null
  }

  companion object {
    /** La chiave nel `SavedStateHandle` della pagina della mappa: "livello:prefisso" (mai una coordinata chiara). */
    const val KEY: String = "zone"

    fun encode(key: CellKey): String = "${key.level}:${key.prefix}"

    fun decode(text: String?): CellKey? {
      val parts = text?.split(':') ?: return null
      if (parts.size != 2) return null
      val level = parts[0].toIntOrNull() ?: return null
      val prefix = parts[1].toLongOrNull() ?: return null
      return runCatching { CellKey(level, prefix) }.getOrNull()
    }
  }
}
