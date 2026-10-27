package dev.pampa.mycloudgallery.core.albums.contract

import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import kotlin.time.Duration

// I VIAGGI PER I RICORDI (Fase 9, docs/fase9-progetto.md §1.2 "core-albums", D12, E-MEM13, E-H8; riga P8 del §4.4). Firma di P0: la porta, il
// null-object e il fake (`InMemoryTripMemories`, con la suite `TripMemoriesContract`); l'implementazione vera sta in trips/ sopra
// `TripReader` (P8). **Nessuna coordinata e nessuna casa**: nessun tipo qui porta un `GeoPoint`, un numero decimale o la stima della casa
// (TripPrivacyTest controlla le firme per riflessione, Phase9ArchitectureTest il sorgente). core-memories non vede questo modulo: :app lega
// questa porta a `MemoryTrips` di core-memories (P13), copiando i soli campi qui sotto.

/**
 * Un viaggio o un evento passato, per i ricordi (D12). [days] i giorni locali dello scatto; [label] il titolo della Fase 7 gia' in italiano
 * ("Oslo, marzo 2025", "Sabato 12 aprile 2025"); [placeIds] i luoghi `CITY`/`NEAR` (vuoto per un evento senza GPS); [members] i soli media
 * **propri e visibili** (`visibleOrThrow`), in ordine di scatto; [album] l'album nato da "Crea album" (null se la proposta non e' stata
 * accettata: il ricordo offre "Apri l'album" solo se c'e', E-MEM13); [startQuarter] per un **evento** il quarto d'ora locale del primo scatto
 * (0..95: la chiave `event:<giorno>:<quarto d'ora>` del ricordo, D9), null per un viaggio. Nel `toString()` solo tipo e conteggi.
 */
data class TripDigest(
  val id: TripId,
  val kind: TripKind,
  val days: LocalDayRange,
  val label: String,
  val placeIds: List<Int>,
  val members: List<MediaId>,
  val album: AlbumId?,
  val startQuarter: Int?,
) {
  init {
    require(startQuarter == null || startQuarter in 0 until QUARTERS_PER_DAY) { "Quarto d'ora fuori intervallo." }
    require(kind == TripKind.EVENT || startQuarter == null) { "Solo un evento ha l'ora d'inizio." }
  }

  override fun toString(): String = "TripDigest(${kind.code}, ${days.days} giorni, ${members.size} media)"

  companion object {
    const val QUARTERS_PER_DAY: Int = 96
  }
}

/**
 * I viaggi e gli eventi passati per i ricordi (D12): accettati (diventati album) e proposti, **scartati esclusi** ("Non mi interessa",
 * `trip_dismissed` per impronta e, per un viaggio, per giorno d'inizio: `TripId.startKey`), solo media propri e visibili, mai coordinate ne'
 * casa. Le proposte che non hanno piu' un media visibile non ci sono (E-H7).
 */
interface TripMemories {
  /**
   * I viaggi e gli eventi **finiti prima di** [until] (un viaggio in corso non e' un ricordo), dal piu' recente (inizio decrescente, poi
   * impronta). [budget]: il tempo a disposizione; finito il tempo risponde con quelli fatti, senza errore. Un guasto della lettura dei media
   * visibili si propaga (R17: un database guasto non e' "nessun viaggio").
   */
  suspend fun past(until: LocalDate, budget: Duration): List<TripDigest>
}
