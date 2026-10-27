package dev.pampa.mycloudgallery.core.memories.contract

import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import kotlin.math.sqrt

// LA SCELTA DELLE FOTO (docs/fase9-progetto.md §1.2 "Scelta", §2.2, D5-D7; risposta Q6 (a) del 27/10: **nessun filtro automatico dei ricordi
// tristi**, solo i comandi dell'utente). Contratto di P0: la cella candidata, i segnali, i pesi del punteggio e il punteggio stesso (una sola
// definizione, calibrata in P11), il risultato con i soli conteggi. La scelta vera e' di P7 (pick/), il riferimento senza ottimizzazioni e'
// `ReferencePicker` (testing/) con la suite `PickerContract`.

/** Una cella candidata: i dati della Libreria che servono a scegliere (niente coordinate, niente nomi). */
data class CandidateCell(
  val id: MediaId,
  val timeMs: Long,
  val day: Int,
  val kind: MediaKind,
  val source: MediaSource,
  val flags: Int,
  val durationMs: Long,
  val aspectMilli: Int,
  val thumbHash: ThumbHash?,
  val cutout: Boolean,
) {
  override fun toString(): String = "CandidateCell(${kind.code})"
}

/**
 * I segnali della scelta, chiesti **una volta** per tutti i candidati di un giro (§2.1, passo 5). [vectors] dove ci sono (E-MEM16); [lookAlike]
 * la firma visiva dove i vettori mancano; [pixels] le misure della miniatura locale; [albums] i media in un album manuale; [people] le persone
 * **con nome e visibili** di ogni media; [choices] le scelte dell'utente ("Mai" per persone e periodi, "Mostra meno" per la copertina).
 */
class PickSignals(
  val vectors: Map<MediaId, FloatArray> = emptyMap(),
  val lookAlike: LookAlike? = null,
  val pixels: Map<MediaId, PixelProbe> = emptyMap(),
  val albums: Set<MediaId> = emptySet(),
  val people: Map<MediaId, Set<PersonId>> = emptyMap(),
  val choices: MemoriesChoices = MemoriesChoices.NONE,
) {
  /** Se nel media [id] c'e' una persona "Mai nei ricordi" (D11): il media esce. */
  fun hasNeverPerson(id: MediaId): Boolean = people[id].orEmpty().any { choices.people[it] == MemoryLessLevel.NEVER }

  /** Se nel media [id] c'e' una persona "Mostra meno" o "Mai": mai copertina (E-MEM11). */
  fun hasLessPerson(id: MediaId): Boolean = people[id].orEmpty().any { choices.people[it] != null }

  override fun toString(): String = "PickSignals(vettori=${vectors.size}, pixel=${pixels.size})"
}

/** I pesi del punteggio (D6) e le soglie di doppioni e varieta' (D7): **provvisori**, li calibra P11 (§5.3) cambiando solo questa tabella. */
object PickWeights {
  const val FAVORITE: Double = 3.0
  const val EDITED: Double = 1.0
  const val IN_ALBUM: Double = 1.0
  const val NAMED_PERSON: Double = 1.0
  const val PANORAMA: Double = 0.5
  const val MOTION: Double = 0.3
  const val VIDEO_SHORT: Double = 0.5
  const val VIDEO_LONG: Double = -0.5
  const val WHATSAPP: Double = -1.5
  const val BAD_LIGHT: Double = -1.0
  const val BLURRY: Double = -1.5

  const val VIDEO_SHORT_MIN_MS: Long = 3_000
  const val VIDEO_SHORT_MAX_MS: Long = 60_000
  const val VIDEO_LONG_MS: Long = 300_000
  const val DARK_LUMA: Double = 0.12
  const val BURNT_LUMA: Double = 0.92

  /** Varianza del laplaciano sulla miniatura da 256 px sotto la quale la foto e' mossa [provvisoria]. */
  const val SHARPNESS_MIN: Float = 100f

  /** Due foto con coseno almeno questo (spazio `embeddinggemma2`) sono doppioni (D7). */
  const val DUPLICATE_COSINE: Double = 0.92

  /** Senza vettori ne' firma: due scatti a meno di 3 minuti sono doppioni (D7). */
  const val DUPLICATE_WINDOW_MS: Long = 180_000

  /** La massima rilevanza marginale: punteggio - LAMBDA x somiglianza massima con le gia' scelte (D7). */
  const val MMR_LAMBDA: Double = 0.5

  /** I media ricevuti da WhatsApp: al piu' un quarto del ricordo, mai copertina (D5). */
  const val WHATSAPP_MAX_SHARE: Double = 0.25
}

/** Il punteggio di una foto senza capirne il contenuto (D6): una sola definizione, per il riferimento e per P7. */
object PhotoScore {
  fun of(cell: CandidateCell, signals: PickSignals): Double {
    var score = 0.0
    if (CellFlags.has(cell.flags, CellFlags.FAVORITE)) score += PickWeights.FAVORITE
    if (CellFlags.has(cell.flags, CellFlags.EDITED)) score += PickWeights.EDITED
    if (cell.id in signals.albums) score += PickWeights.IN_ALBUM
    if (signals.people[cell.id].orEmpty().isNotEmpty()) score += PickWeights.NAMED_PERSON
    if (CellFlags.has(cell.flags, CellFlags.PANORAMA)) score += PickWeights.PANORAMA
    if (CellFlags.has(cell.flags, CellFlags.MOTION)) score += PickWeights.MOTION
    if (cell.kind == MediaKind.VIDEO) {
      if (cell.durationMs in PickWeights.VIDEO_SHORT_MIN_MS..PickWeights.VIDEO_SHORT_MAX_MS) score += PickWeights.VIDEO_SHORT
      if (cell.durationMs > PickWeights.VIDEO_LONG_MS) score += PickWeights.VIDEO_LONG
    }
    if (cell.source == MediaSource.WHATSAPP) score += PickWeights.WHATSAPP
    val probe = signals.pixels[cell.id]
    val luma = probe?.luma?.toDouble() ?: cell.thumbHash?.let { ThumbLight.averageLuma(it) }
    if (luma != null && (luma < PickWeights.DARK_LUMA || luma > PickWeights.BURNT_LUMA)) score += PickWeights.BAD_LIGHT
    if (probe != null && probe.sharpness < PickWeights.SHARPNESS_MIN) score += PickWeights.BLURRY
    return score
  }

  /** Il coseno fra due vettori (0 se uno e' nullo o le lunghezze differiscono). */
  fun cosine(a: FloatArray, b: FloatArray): Double {
    if (a.size != b.size || a.isEmpty()) return 0.0
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
      dot += a[i] * b[i]
      na += a[i] * a[i]
      nb += b[i] * b[i]
    }
    if (na == 0.0 || nb == 0.0) return 0.0
    return dot / (sqrt(na) * sqrt(nb))
  }
}

/**
 * L'esito della scelta (D5-D7): [members] in ordine di scatto (la presentazione racconta il giorno o il viaggio), [cover] uno dei [members]
 * (mai un video, mai WhatsApp, mai una persona "Mostra meno"); vuoto e null se il ricordo non nasce (sotto il minimo, nessuna copertina
 * possibile). [dropped] **solo conteggi** per motivo (D33): mai gli id.
 */
class PickResult(val members: List<MediaId>, val cover: MediaId?, val dropped: Map<DropReason, Int>) {
  val accepted: Boolean get() = cover != null && members.isNotEmpty()

  override fun toString(): String = "PickResult(${members.size}, copertina=${cover != null}, esclusi=${dropped.values.sum()})"

  companion object {
    fun rejected(dropped: Map<DropReason, Int>): PickResult = PickResult(emptyList(), null, dropped)
  }
}

/**
 * La scelta delle foto (D5-D7): esclusioni (D5, "Mai" per persone e periodi), punteggio ([PhotoScore]), doppioni (vettori, firma, 3 minuti),
 * varieta' (massima rilevanza marginale con i tetti per giorno e per ora di [PickSpec]), WhatsApp al piu' un quarto, copertina. **Pura e
 * deterministica**: a parita' vince lo scatto piu' vecchio, poi l'id minore. Nessun filtro sul contenuto (Q6 (a)).
 */
fun interface PhotoPicker {
  fun pick(candidates: List<CandidateCell>, spec: PickSpec, signals: PickSignals): PickResult
}
