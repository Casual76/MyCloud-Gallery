package dev.pampa.mycloudgallery.core.memories.contract

import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// LE PORTE DEI RICORDI (docs/fase9-progetto.md §1.2 "Porte", §1.4, D2, D12, D36). core-memories **non vede** core-albums, core-faces,
// core-search, core-ai, core-imaging per i pixel, il NAS ne' la rete (Phase9ArchitectureTest): viaggi, persone, vettori, pixel, album, ritagli e
// ora arrivano da qui, legati da :app (P13). Ogni porta ha un null-object (noop/) che risponde "niente": i ricordi di quel tipo tacciono, il
// resto funziona. Nessuna porta porta coordinate, la casa o un nome da salvare.

/** Un viaggio o evento passato come lo vede core-memories (copia dei campi di `TripDigest` di core-albums, D12): mai coordinate ne' casa. */
data class TripDigestView(
  val kind: TripKind,
  val days: DayRange,
  val label: String,
  val placeIds: List<Int>,
  val members: List<MediaId>,
  val album: AlbumId?,
  val startQuarter: Int?,
) {
  init {
    require(kind == TripKind.EVENT || startQuarter == null) { "Solo un evento ha l'ora d'inizio." }
    require(startQuarter == null || startQuarter in 0 until 96) { "Quarto d'ora fuori intervallo." }
  }

  /** La chiave del ricordo di questo viaggio o evento (D9). Un evento senza ora d'inizio prende il quarto d'ora 0. */
  fun key(): MemoryKey = when (kind) {
    TripKind.TRIP -> MemoryKey.Trip(days)
    TripKind.EVENT -> MemoryKey.Event(days.from, startQuarter ?: 0)
  }

  override fun toString(): String = "TripDigestView(${kind.code}, ${days.days} giorni, ${members.size})"
}

/** I viaggi ed eventi passati (D12): legata da :app a `TripMemories` di core-albums (solo media propri e visibili, scartati esclusi). */
interface MemoryTrips {
  suspend fun past(until: LocalDate, budget: Duration): List<TripDigestView>
}

/** Una persona con un nome, non nascosta ne' dimenticata, con i suoi media **visibili** (D12, E-H10). Solo l'id. */
data class PersonDigest(val person: PersonId, val members: List<MediaId>) {
  override fun toString(): String = "PersonDigest(${members.size})"
}

/**
 * Le persone per i ricordi (D12, Q9), legate da :app sopra `PeopleIndex`/`PeopleLookup`/`PeopleVisibility` di core-faces (dopo P13 della Fase 8;
 * prima il null-object). [available] falso se Persone non e' `On` con `ACTIVE` o `LOCAL_ONLY` qui: allora niente ricordi `PERSON`, e i "Mai"
 * per persona non si possono applicare alle foto (L274). Una persona nascosta, dimenticata o senza nome non c'e' mai.
 */
interface MemoryPeople {
  val available: StateFlow<Boolean>

  /**
   * Vero se lo stato di Persone nell'account **non e' ancora noto** (all'avvio, prima che Persone sia montata, o con lo stato in aggiornamento): "non lo so", distinto
   * da "no" ([available] falso). Finche' e' vero la pulizia dello scaffale **non toglie** i ricordi `PERSON` per cio' che dipende da Persone (non disponibile, persona
   * non trovata): si potano solo con Persone spenta davvero o con una scelta esplicita dell'utente (MO-1, 31/10). Di serie falso (le porte senza stato).
   */
  val undecided: Boolean get() = false

  /** Le persone con un nome e i loro media visibili. */
  suspend fun named(): List<PersonDigest>

  /** Per ogni media di [ids], le persone **con nome e visibili** che vi compaiono (per "Mai", "Mostra meno", copertina e punteggio). */
  suspend fun peopleIn(ids: Collection<MediaId>): Map<MediaId, Set<PersonId>>

  /** La persona di adesso per [person] (alias risolto dopo un'unione, E-MEM10); null se e' nascosta, dimenticata o non c'e' piu'. */
  suspend fun resolve(person: PersonId): PersonId?

  /** Il nome di [person] **da mostrare adesso** (mai salvato in un ricordo, in un dato personale o in un log). */
  suspend fun label(person: PersonId): String?
}

/** I vettori della ricerca (Fase 6, spazio `embeddinggemma2`) per doppioni e varieta' (D7): solo dove ci sono (E-MEM16). */
fun interface MemoryVectors {
  suspend fun vectors(ids: Collection<MediaId>): Map<MediaId, FloatArray>
}

/**
 * La firma visiva della Fase 5 (`SignatureAffinity`) dove i vettori mancano (D7, E-MEM16): "sono quasi lo stesso scatto?". Null = non si sa
 * (allora vale la finestra di 3 minuti).
 */
fun interface LookAlike {
  fun similar(a: MediaId, b: MediaId): Boolean?
}

fun interface MemoryLookAlikes {
  suspend fun among(ids: Collection<MediaId>): LookAlike?
}

/** Le misure della miniatura locale (D6): nitidezza (varianza del laplaciano su 256 px) e luminanza media (0..1). */
data class PixelProbe(val sharpness: Float, val luma: Float) {
  init {
    require(sharpness >= 0f && luma in 0f..1f) { "Misura fuori intervallo." }
  }
}

/** Le misure dei pixel (D6), dalla miniatura **locale** (mai il NAS, mai la rete), con una cache locale; entro [budget]. */
fun interface MemoryPixels {
  suspend fun probe(ids: Collection<MediaId>, budget: Duration): Map<MediaId, PixelProbe>
}

/** Quali di [ids] stanno in un album manuale (D6: +1). */
fun interface MemoryAlbumSignals {
  suspend fun inManualAlbums(ids: Collection<MediaId>): Set<MediaId>
}

/** I ritagli del soggetto (`Ritagli/`, F8-D18): esclusi dai ricordi (D5). Pochi: si chiedono tutti. */
fun interface MemoryCutouts {
  suspend fun cutouts(): Set<MediaId>
}

/** Un clip breve di un video **del telefono** per la presentazione (D13): un riferimento opaco per il lettore, al piu' 8 s. */
data class ClipSource(val token: String, val durationMs: Long) {
  init {
    require(token.isNotEmpty() && durationMs in 1..MAX_MS) { "Clip non valido." }
  }

  override fun toString(): String = "ClipSource($durationMs ms)"

  companion object {
    const val MAX_MS: Long = 8_000
  }
}

/** I clip della presentazione (D13): null per i video solo sul NAS (E-MEM17: fotogramma di copertina). */
fun interface MemoryClips {
  suspend fun clip(id: MediaId): ClipSource?
}

/** L'ora dei ricordi (D36): "oggi" e l'ora locale del dispositivo dalla porta `Today` di :app, l'orologio monotono per budget e durate. */
interface MemoryClock {
  fun today(): LocalDate

  fun nowLocal(): LocalDateTime

  fun monotonicMs(): Long
}
