package dev.pampa.mycloudgallery.core.library.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode

// IL TRASCINAMENTO, TIPI PURI (Fase 10, P0; docs/fase10-progetto.md §1.1 "core-library", §2.5, D20, D21; C436, C437). Nessuna classe della
// piattaforma qui: la porta con il `ClipData` sta in `DragPort.kt`, l'implementazione (provider dei documenti, descrittore a richiesta, copie
// senza posizione) in `feature-integrations/.../drag/` (P6). I feature-* restano ciechi fra loro: trascinano attraverso la porta.

/** Da dove parte un trascinamento verso un'altra app. **Mai dai Nascosti** (E-T18): non c'e' una voce per loro. */
enum class DragOrigin(override val code: String) : StableCode {
  LIBRARY("library"),
  ALBUM("album"),
  SEARCH("search"),
  MEMORY("memory"),
  MAP_ZONE("map_zone"),
  VIEWER("viewer"),
}

/**
 * Che cosa si vuole trascinare: [ids] in ordine (la cella o la selezione), [removeLocation] la scelta "Rimuovi la posizione" (E-T13: copie
 * senza GPS, o niente). I tetti ([DragLimits]) li applica chi prepara, **prima** di cominciare (E-T12).
 */
data class DragRequest(val origin: DragOrigin, val ids: List<MediaId>, val removeLocation: Boolean) {
  init {
    require(ids.isNotEmpty()) { "Niente da trascinare." }
    require(ids.distinct().size == ids.size) { "Elementi ripetuti." }
  }

  override fun toString(): String = "DragRequest(${origin.code}, ${ids.size}, posizione tolta=$removeLocation)"
}

/** Perche' un trascinamento non parte (motivo per l'utente e per la Diagnostica `DRAG_OUT`, mai un id). */
enum class DragRefusal(override val code: String) : StableCode {
  /** Oltre [DragLimits.MAX_ITEMS] elementi (E-X6, E-T12). */
  TOO_MANY("too_many"),

  /** Oltre [DragLimits.MAX_BYTES] in tutto (E-X6, E-T12). */
  TOO_LARGE("too_large"),

  /** Un elemento e' nascosto (o lo e' diventato): il provider dei documenti non lo da' mai (E-H11). */
  HIDDEN("hidden"),

  /** "Rimuovi la posizione" acceso e la posizione non si puo' togliere: quell'elemento non parte (E-X5, E-T13). */
  LOCATION_NOT_REMOVABLE("location_not_removable"),

  /** Un media solo sul NAS e il NAS non risponde (E-T11). */
  NAS_UNREACHABLE("nas_unreachable"),

  /** Blocco dell'app attivo: prima lo sblocco (E-X7). */
  LOCKED("locked"),

  /** Il trascinamento non e' disponibile (porta non legata, profilo cambiato, schermo spento: E-T17). */
  UNAVAILABLE("unavailable"),
}

/** I tetti e i tempi del trascinamento (D20, E-X6). */
object DragLimits {
  /** Al piu' 100 elementi. */
  const val MAX_ITEMS: Int = 100

  /** Al piu' 2 GiB in tutto. */
  const val MAX_BYTES: Long = 2L * 1024 * 1024 * 1024

  /** Se la preparazione supera 300 ms si mostra "Preparo…" e il trascinamento parte quando e' pronto. */
  const val PREPARING_NOTICE_MS: Long = 300

  /** Le copie temporanee vivono finche' l'app e' in primo piano piu' 10 minuti, e comunque non oltre il prossimo avvio (`ShareCache`). */
  const val TEMP_GRACE_MS: Long = 10 * 60 * 1_000L
}
