package dev.pampa.mycloudgallery.core.describe.contract

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import dev.pampa.mycloudgallery.core.model.StableCode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// I FATTI DI UNA DESCRIZIONE (docs/fase10-progetto.md §1.2 "core-describe", §2.6, D22). Contratto di P0. Gli stessi fatti che oggi usa
// `GridText.describe` di feature-library (P7 lo sostituisce con `MediaDescriber`), presi dall'istantanea senza nessun dato in piu'. Nessun
// `toString()` mostra date, luoghi o durate.

/** Dove si legge una descrizione (D22). **Non esiste** una superficie per log, Diagnostica o esportazione: per costruzione. */
enum class DescribeSurface(override val code: String) : StableCode {
  /** Griglie, viewer, ricordi, mappa, ricerca, album: tutto (persone e testo letto alle condizioni di D22). */
  IN_APP("in_app"),

  /** Dentro l'area protetta dei Nascosti: come [IN_APP]. */
  HIDDEN_AREA("hidden_area"),

  /** Il widget sulla Home: solo tipo e data (E-AC11). */
  WIDGET("widget"),

  /** Le notifiche: niente (il testo della notifica basta). */
  NOTIFICATION("notification"),
}

/** I pezzi di una descrizione (le colonne della tabella del §2.6). */
enum class DescribeFact(override val code: String) : StableCode {
  KIND("kind"),
  DATE("date"),
  TIME("time"),
  PLACE("place"),
  DURATION("duration"),
  PEOPLE("people"),
  TEXT("text"),

  /** Preferita, modificata, data stimata, non leggibile, gruppo o copie simili, presenza ("solo sul NAS"). */
  STATE("state"),
}

/** La tabella del §2.6: che cosa entra per superficie. Dati, non logica: la usa la regola di P7 e la pretende la suite. */
object DescribeSurfaces {
  fun facts(surface: DescribeSurface): Set<DescribeFact> = when (surface) {
    DescribeSurface.IN_APP, DescribeSurface.HIDDEN_AREA -> DescribeFact.entries.toSet()
    DescribeSurface.WIDGET -> setOf(DescribeFact.KIND, DescribeFact.DATE)
    DescribeSurface.NOTIFICATION -> emptySet()
  }
}

/** I tetti di D22. */
object DescribeLimits {
  /** Al piu' 3 nomi, poi "e altri N". */
  const val MAX_NAMES: Int = 3

  /** Le prime 12 parole del testo letto, ripulite. */
  const val MAX_WORDS: Int = 12

  /** La cache di `warm` (nomi e prime parole delle celle in vista), solo in memoria, vuotata al blocco e in secondo piano. */
  const val WARM_CACHE: Int = 500
}

/**
 * I fatti di un media (D22): tipo, giorno locale (`aaaammgg`), ora locale **solo se coerente** con il giorno e la data non stimata, luogo
 * (id GeoNames, null se nessuno), durata dei video, i bit di `CellFlags`, quanti media rappresenta la cella, se e' una pila di copie simili,
 * la presenza.
 */
data class MediaFacts(
  val kind: MediaKind,
  val localDay: Int,
  val time: LocalTime?,
  val placeId: Int?,
  val durationMs: Long?,
  val flags: Int,
  val groupSize: Int,
  val similarCopies: Boolean,
  val presence: PresenceBadge?,
) {
  init {
    require(localDay in 1_000_01_01..9_999_12_31) { "Giorno non valido." }
    require(placeId == null || placeId > 0) { "Luogo non valido." }
    require(groupSize >= 1) { "Gruppo non valido." }
  }

  /** Il giorno come data. */
  val day: LocalDate get() = LocalDate.of(localDay / 10_000, (localDay / 100) % 100, localDay % 100)

  override fun toString(): String = "MediaFacts(${kind.code})"

  companion object {
    /**
     * I fatti della cella [index] di [snapshot] (come `GridText.describe` oggi): l'ora e' quella del fuso [zone] del telefono e si omette se
     * non concorda con il giorno nell'ora del posto (uno scatto in viaggio) o se la data e' stimata.
     */
    fun of(snapshot: TimelineSnapshot, index: Int, zone: ZoneId): MediaFacts {
      val kind = snapshot.kindAt(index)
      val flags = snapshot.flagsAt(index)
      val localDay = snapshot.localDayAt(index)
      val day = LocalDate.of(localDay / 10_000, (localDay / 100) % 100, localDay % 100)
      val instant = Instant.ofEpochMilli(snapshot.sortTimeAt(index)).atZone(zone)
      val time = if (instant.toLocalDate() == day && !CellFlags.has(flags, CellFlags.DATE_ESTIMATED)) instant.toLocalTime() else null
      return MediaFacts(
        kind = kind,
        localDay = localDay,
        time = time,
        placeId = snapshot.placeAt(index).takeIf { it > 0 },
        durationMs = if (kind == MediaKind.VIDEO) snapshot.durationMsAt(index) else null,
        flags = flags,
        groupSize = snapshot.groupSizeAt(index).coerceAtLeast(1),
        similarCopies = CellFlags.has(flags, CellFlags.SIMILAR_COPIES),
        presence = snapshot.presenceAt(index),
      )
    }
  }
}
