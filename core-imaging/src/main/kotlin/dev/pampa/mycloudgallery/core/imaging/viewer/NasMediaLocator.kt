package dev.pampa.mycloudgallery.core.imaging.viewer

import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.atomic.AtomicBoolean

// Dove sta, sul NAS, il media che il viewer sta aprendo (Fase 4, P8; docs/fase4-progetto.md §2.6). Contratto nuovo di
// questo pacchetto: `MediaRef.Nas` porta solo l'id della posizione (il percorso del NAS e' un dato sensibile come il GPS
// e non gira per la UI, §1.2), quindi chi apre l'originale lo chiede qui. :app (P11) lo realizza leggendo il catalogo
// (`Location.Nas` della posizione); i test lo realizzano con una mappa.

/**
 * La posizione di un media sul NAS, quanto basta ad aprirlo.
 *
 * @property share la share com'e' scritta sul NAS.
 * @property path il percorso dentro la share (gia' validato: [SharePath] non ammette `..` ne' `.mycloudgallery`).
 * @property sizeBytes il peso secondo il catalogo: serve alla nota "dati mobili" (E-V2) e al nome del temporaneo.
 * @property stamp il timbro che il catalogo conosce, se noto: un temporaneo con un timbro diverso e' di un file modificato
 *   dal PC e non si riusa.
 * @property width @property height le dimensioni **dritte** dell'originale secondo il catalogo, se note: servono ai limiti
 *   dello zoom prima che l'originale sia sul telefono (la nitida arriva dall'HQ della replica, piu' piccola).
 * @property knownSha256 l'hash completo secondo l'indice, se c'e' (D8: vale per confrontare, mai per fidarsi).
 */
class NasMediaLocation(
  val share: String,
  val path: SharePath,
  val sizeBytes: Long,
  val stamp: NasStamp? = null,
  val width: Int? = null,
  val height: Int? = null,
  val knownSha256: Sha256? = null,
) {
  init {
    require(sizeBytes >= 0) { "Dimensione negativa." }
    require((width == null) == (height == null) && (width ?: 1) > 0 && (height ?: 1) > 0) { "Dimensioni non valide." }
  }

  /** Il nome del file: e' un dato dell'utente, mai nei log. */
  val fileName: String get() = path.name

  override fun toString(): String = "NasMediaLocation($sizeBytes B)"
}

/**
 * Risolve un [MediaRef.Nas] nella sua posizione. Chiamata dal viewer fuori dal thread principale; `null` = il media non
 * e' piu' nel catalogo (sparito da un altro dispositivo, E-V6): il viewer lo tratta come "non c'e' piu'".
 */
fun interface NasMediaLocator {
  suspend fun locate(ref: MediaRef.Nas): NasMediaLocation?
}

/**
 * Se il collegamento verso il NAS e' a consumo (dati mobili, anche attraverso la VPN, o un hotspot): D23, E-V2. L'originale
 * di un media che non e' sul telefono non si scarica senza che l'utente l'abbia accettato per quel media.
 */
fun interface NasLink {
  fun isMetered(): Boolean

  companion object {
    /** Nessuna rete a consumo (casa): di default, e per i test. */
    val Unmetered: NasLink = NasLink { false }
  }
}

/**
 * Le conferme "dati mobili" dell'utente, **per media e per sessione** (§2.6): vivono quanto il processo e non si salvano.
 * Un'istanza sola per l'app, condivisa dall'origine delle foto, dal video e dal "Scarica sul telefono".
 */
class MobileDataConsent {
  private val confirmed: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
  private val everything = AtomicBoolean(false)

  fun confirm(locationId: Long) {
    confirmed += locationId
  }

  fun confirm(ref: MediaRef) {
    if (ref is MediaRef.Nas) confirm(ref.locationId)
  }

  fun isConfirmed(locationId: Long): Boolean = everything.get() || locationId in confirmed

  /** Una scelta piu' ampia (per esempio "per questa sessione, sempre"): le Impostazioni possono accenderla. */
  fun confirmAll() {
    everything.set(true)
  }

  fun reset() {
    confirmed.clear()
    everything.set(false)
  }
}
