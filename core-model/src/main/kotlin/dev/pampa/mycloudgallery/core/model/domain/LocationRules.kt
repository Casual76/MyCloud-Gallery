package dev.pampa.mycloudgallery.core.model.domain

import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.LocationType
import dev.pampa.mycloudgallery.core.model.MediaLocation
import java.time.Duration

/**
 * Il ciclo di vita di una posizione: presente, assente (con tolleranza), non raggiungibile, rimossa.
 *
 * **Rimuovere una posizione non tocca le altre**: cancellare dal telefono non cancella dal NAS
 * (piano §2), il media diventa "solo NAS" e mantiene identita', album e preferiti (5.5).
 */
object LocationRules {

  /**
   * Quanto resta una posizione del telefono sparita prima di essere dimenticata: il massimo che
   * Samsung riporta per il suo cestino (`docs/android.md` 4.3, punto 4). Se la foto torna entro
   * questo tempo si ricollega allo stesso media senza ricaricarla (E-P11).
   */
  val PHONE_ABSENCE_TOLERANCE_MILLIS: Long = Duration.ofDays(60).toMillis()

  /**
   * Lo stesso per il NAS: copre il cestino della share (30 giorni) e gli spostamenti fatti dal PC
   * che la scansione vede in due giri diversi (E-N1). Intanto il media non conta come presente li'
   * (E-N3: esce dalla Libreria senza passare dal cestino dell'app).
   */
  val NAS_ABSENCE_TOLERANCE_MILLIS: Long = Duration.ofDays(30).toMillis()

  fun toleranceFor(type: LocationType): Long = when (type) {
    LocationType.PHONE -> PHONE_ABSENCE_TOLERANCE_MILLIS
    LocationType.NAS -> NAS_ABSENCE_TOLERANCE_MILLIS
  }

  /** Non piu' vista dove era. Se era gia' assente si conserva l'istante della prima assenza. */
  fun vanished(location: MediaLocation, nowMillis: Long): MediaLocation =
    if (location.state == LocationState.ABSENT) {
      location
    } else {
      location.copy(state = LocationState.ABSENT, absentSinceMillis = nowMillis)
    }

  /** Il contenitore non e' raggiungibile (microSD tolta, E-P6): non e' una cancellazione. */
  fun unavailable(location: MediaLocation): MediaLocation =
    if (location.state == LocationState.PRESENT) location.copy(state = LocationState.UNAVAILABLE) else location

  /** Se una posizione assente si puo' dimenticare. Le altre posizioni del media restano dove sono. */
  fun isExpired(location: MediaLocation, nowMillis: Long): Boolean {
    val since = location.absentSinceMillis ?: return false
    return location.state == LocationState.ABSENT && nowMillis - since >= toleranceFor(location.location.type)
  }
}
