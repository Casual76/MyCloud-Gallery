package dev.pampa.mycloudgallery.feature.viewer.video

import dev.pampa.mycloudgallery.core.metadata.contract.ByteRange
import dev.pampa.mycloudgallery.core.metadata.contract.MotionPhotoLocator
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerBytes
import dev.pampa.mycloudgallery.core.model.MediaRef

/**
 * Dove sta la parte video di una motion photo (E-M5) e quando cade la foto dentro il video, cercati **quando servono** (la
 * pagina ferma e le sue vicine, il badge), non in anticipo: il catalogo sa che una foto e' una motion photo, ma non salva
 * l'intervallo (cambia con il formato e con le correzioni).
 *
 * `Found` porta l'intervallo e l'**istante della foto** ([stillUs], D50): il campo dichiarato dal file se e' valido, altrimenti
 * la regola della specifica Android, la **meta' del video** (E-V16: campo assente, `-1` o oltre la durata). E' null solo se non
 * c'e' il campo e la durata del video non si legge: l'animazione non si fa, il badge funziona. `Unrecognized` e' E-M16 (un formato
 * che nessun localizzatore riconosce, per esempio l'HEIC di Samsung): niente animazione ne' badge, la nota nelle informazioni.
 *
 * @property durationUs la durata del video (dal box `mvhd` dell'MP4), se si e' letta.
 */
sealed interface MotionVideo {

  data class Found(val range: ByteRange, val stillUs: Long?, val durationUs: Long? = null) : MotionVideo {

    /** Il tratto dell'animazione d'apertura: i 1,2 s che precedono la foto; null se la foto cade troppo vicino all'inizio o non si sa. */
    val openingClip: PlaybackClip? get() = MotionInstant.openingClip(stillUs)
  }

  data object Unrecognized : MotionVideo
}

/** Trova la parte video di una motion photo. Un'interfaccia: nei test restituisce un intervallo scelto dal test. */
fun interface MotionVideoLocator {
  suspend fun locate(ref: MediaRef): MotionVideo
}

/** L'istante della foto dentro il video e il tratto d'apertura (D50, §2.11, E-V16). Funzioni pure. */
object MotionInstant {

  /** La lunghezza del tratto che precede la foto: 1,2 s (D50: fra 1 e 1,5 s). */
  const val OPENING_MS: Long = 1_200L

  /** Sotto questo istante il tratto sarebbe quasi vuoto: niente animazione. */
  const val MIN_OPENING_MS: Long = 100L

  /**
   * L'istante della foto: [declaredUs] se e' valido (non negativo, `-1` e' "non impostato", e dentro la durata se questa e' nota),
   * altrimenti la meta' di [durationUs]; null se non c'e' ne' l'uno ne' l'altra.
   */
  fun resolve(declaredUs: Long?, durationUs: Long?): Long? {
    val duration = durationUs?.takeIf { it > 0L }
    val valid = declaredUs?.takeIf { it >= 0L && (duration == null || it <= duration) }
    return valid ?: duration?.let { it / 2L }
  }

  /** `[istante - 1,2 s, istante]` in millisecondi, o null se l'istante manca o e' troppo vicino all'inizio. */
  fun openingClip(stillUs: Long?): PlaybackClip? {
    val endMs = (stillUs ?: return null) / 1_000L
    if (endMs < MIN_OPENING_MS) return null
    return PlaybackClip(startMs = (endMs - OPENING_MS).coerceAtLeast(0L), endMs = endMs)
  }
}

/** Il localizzatore comune: legge testa, coda e (per la durata) l'inizio del video da una sorgente di byte a intervalli. */
internal object MotionLocating {

  const val HEAD_BYTES: Long = 256L * 1024L
  const val TAIL_BYTES: Long = 128L * 1024L

  /**
   * [read] legge un intervallo di byte del file (estremi inclusi, come `MediaBytes.read`); [size] e' la dimensione del file.
   * Legge poche centinaia di KiB, mai il file: un JPEG da 200 MP pesa 60 MB.
   */
  suspend fun locate(size: Long, read: suspend (LongRange) -> ByteArray): MotionVideo {
    if (size <= 0L) return MotionVideo.Unrecognized
    val head = read(0L until minOf(size, HEAD_BYTES))
    val tail = read(maxOf(0L, size - TAIL_BYTES) until size)
    val part = MotionPhotoLocator.locate(XmpPacket.find(head), tail, size) ?: return MotionVideo.Unrecognized
    val range = part.videoRange
    // Un intervallo che esce dal file o e' vuoto non e' un video: meglio la foto ferma che un decoder in errore.
    if (range.isEmpty || range.endExclusive > size) return MotionVideo.Unrecognized
    val duration = try {
      Mp4Duration.read(range.length) { offset, length -> read(range.start + offset until range.start + offset + length) }
    } catch (_: java.io.IOException) {
      null
    }
    return MotionVideo.Found(range, stillUs = MotionInstant.resolve(part.stillPresentationUs, duration), durationUs = duration)
  }
}

/**
 * Il localizzatore vero: legge la testa del file (dove sta l'XMP) e la coda (dove Samsung scrive il suo trailer SEF) e li passa a
 * [MotionPhotoLocator] di core-metadata, che conosce i tre formati (Google v1, MicroVideo, SEF). Per i media del NAS i byte
 * arrivano da [NasMotionSource], che li legge a intervalli solo dove la rete lo permette (D51).
 */
class BytesMotionVideoLocator(private val bytes: ViewerBytes) : MotionVideoLocator {

  override suspend fun locate(ref: MediaRef): MotionVideo {
    val file = bytes.open(ref) ?: return MotionVideo.Unrecognized
    return MotionLocating.locate(file.size) { range -> file.read(range) }
  }
}

/** Il blocco XMP di un file, cercato nei primi byte. Funzione pura, provata con file sintetici. */
object XmpPacket {

  private const val OPEN = "<x:xmpmeta"
  private const val CLOSE = "</x:xmpmeta>"

  /** L'XMP in [head] (UTF-8), o null se non c'e' un pacchetto completo. */
  fun find(head: ByteArray): String? {
    // ISO-8859-1 mappa un byte su un carattere: gli indici del testo sono gli indici dei byte.
    val text = String(head, Charsets.ISO_8859_1)
    val start = text.indexOf(OPEN)
    if (start < 0) return null
    val closeAt = text.indexOf(CLOSE, startIndex = start)
    if (closeAt < 0) return null
    val end = closeAt + CLOSE.length
    return String(head, start, end - start, Charsets.UTF_8)
  }
}
