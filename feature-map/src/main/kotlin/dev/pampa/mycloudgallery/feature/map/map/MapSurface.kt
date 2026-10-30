package dev.pampa.mycloudgallery.feature.map.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6

// LA SUPERFICIE DELLA MAPPA (Fase 9, P4; docs/fase9-progetto.md §2.10, D21, D28). Il controllore e le bolle non parlano con MapLibre: parlano con una
// [MapSurface], che la mappa vera (`MapLibreSurface`) e quella finta degli screenshot e dei test (`FakeMapSurface`, in `testing/`) implementano. MapLibre
// non si disegna in Robolectric: la parte vera si prova sul telefono (F119, F122); tutto il resto della schermata si prova qui, sopra questa interfaccia.

/** Gli eventi di movimento della camera, sul thread principale. */
interface MapSurfaceListener {
  /** La camera si sta muovendo (a ogni fotogramma): le bolle si **riposizionano** da sole, qui si decide solo se cambiare il livello. */
  fun onMoved(viewport: MapViewport)

  /** La camera si e' fermata: si ricalcolano le bolle e si salva l'ultima posizione (solo in locale). */
  fun onIdle(viewport: MapViewport)
}

/**
 * La mappa com'e' vista dalle bolle e dal controllore. [frame] **cambia a ogni fotogramma di movimento**: le bolle lo leggono dentro il loro layer grafico
 * (`graphicsLayer { frame; project(...) }`), cosi' un gesto le sposta senza ricomporre e senza **nessuna nuova misura del layout** (§2.10, test di composizione).
 */
abstract class MapSurface {
  private val ticks = mutableLongStateOf(0L)

  /** Il numero del fotogramma: leggerlo in un layer grafico o in un effetto lo fa rifare a ogni movimento. */
  val frame: Long get() = ticks.longValue

  /** Chi si accorge del movimento (il controllore); uno solo. */
  var listener: MapSurfaceListener? = null

  /** Un fotogramma di movimento in piu': la camera e' cambiata. Sul thread principale. */
  protected fun tick() {
    ticks.longValue = ticks.longValue + 1
  }

  /** La vista di adesso, o null finche' la mappa non e' pronta. */
  abstract val viewport: MapViewport?

  /** Dove cade [point] nella vista, in pixel, o null se la mappa non e' pronta. Sul thread principale. */
  abstract fun project(point: GeoE6): Offset?

  /** La camera su [spot] senza animazione. */
  abstract fun moveTo(spot: CameraSpot)

  /** La camera su [spot] con l'animazione della mappa (la durata viene dai token di movimento). */
  abstract fun animateTo(spot: CameraSpot)
}

/**
 * Cosa deve disegnare la mappa: lo stile, da dove parte la camera e il punto selezionato ("Mostra sulla mappa", E-MAP19). I colori del punto sono quelli del tema
 * (interi ARGB: nessun colore scritto a mano). [initial] e' deciso dal controllore **prima** di creare la mappa.
 */
class MapSurfaceRequest(
  val style: MapStyleChoice,
  val initial: MapInitial,
  val selection: GeoE6?,
  val selectionColor: Int,
  val selectionRing: Int,
) {
  override fun toString(): String = "MapSurfaceRequest(${style.asset}, selezione=${selection != null})"
}

/**
 * Chi disegna la mappa dentro la schermata: la vera con MapLibre ([MapLibreSurfaceContent]) o la finta. [Content] riempie lo spazio e **collega** [surface]
 * (il vero chiama `surface.attach(map)`); la mappa vera nasce **pigra**, alla prima composizione, con MapLibre inizializzato prima (`MapEngineStarter`).
 */
interface MapSurfaceContent {
  /** La superficie di questa mappa (una per pagina): quella vera o quella finta. */
  fun createSurface(): MapSurface

  @Composable
  fun Content(surface: MapSurface, request: MapSurfaceRequest, modifier: Modifier)
}
