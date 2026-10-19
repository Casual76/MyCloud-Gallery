package dev.pampa.mycloudgallery.feature.integrations.share

import android.net.Uri
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode
import java.io.File
import java.util.Locale

// Fase 5B (P11, docs/fase5-progetto.md §2.7 "Condividere senza posizione", D26, E-X5, E-X6, E-V7, L41; §1.5 `ShareOutState`): i tipi di
// "Condividi" dal viewer con la posizione secondo l'impostazione. Con "Rimuovi la posizione" acceso si condivide una COPIA in cache senza GPS,
// ricontrollata con piu' lettori; se togliere la posizione non riesce la condivisione si ferma con il motivo e **non** parte mai il file con la
// posizione. Con l'impostazione spenta un media del telefono si condivide com'e' (il suo indirizzo del MediaStore), uno solo-NAS si scarica in
// cache (E-V7).

/** I tetti di una condivisione (E-X6: lo stesso 100 elementi e 2 GiB del selettore; D24: almeno 1 GiB libero dopo la copia). */
object ShareLimits {
  const val MAX_ITEMS: Int = 100
  const val MAX_TOTAL_BYTES: Long = 2L * 1024 * 1024 * 1024
  const val MIN_FREE_AFTER_BYTES: Long = 1L * 1024 * 1024 * 1024
}

/** Perche' una condivisione si e' fermata. I codici sono stabili e vanno alla Diagnostica; il testo per l'utente sta in `strings_integrations_share.xml`. */
enum class ShareStop(override val code: String) : StableCode {
  /** Niente da condividere. */
  NOTHING("nothing"),

  /** Piu' di [ShareLimits.MAX_ITEMS] elementi. */
  TOO_MANY("too_many"),

  /** Oltre [ShareLimits.MAX_TOTAL_BYTES] in tutto. */
  TOO_BIG("too_big"),

  /** Dopo la copia resterebbe meno di 1 GiB libero. */
  NO_SPACE("no_space"),

  /** Un media non c'e' piu' (cancellato, spostato) o non si legge. */
  UNAVAILABLE("unavailable"),

  /** Un media solo-NAS con il NAS che non risponde: errore subito, mai un'attesa infinita (E-X1). */
  NAS_UNREACHABLE("nas_unreachable"),

  /** Il formato non si sa trattare (GIF, BMP, RAW, un HEIC senza decodificatore...): "condividila con la posizione o annulla" (L41). */
  CANNOT_REMOVE_LOCATION("cannot_remove_location"),

  /** Dopo averla tolta, un lettore ha trovato ancora una posizione: il file non si condivide (D26, fallire chiusi). */
  LOCATION_REMAINS("location_remains"),

  /** Un guasto (disco, lettura): niente e' partito. */
  FAILED("failed"),
}

/** Un file pronto da consegnare: l'indirizzo, il tipo vero e la dimensione. Il nome del file e' un dato dell'utente: mai in un log. */
class SharedFile(val uri: Uri, val mime: String, val size: Long, val file: File?) {
  override fun toString(): String = "SharedFile($mime, $size B)"
}

/** L'esito di [LocationFreeShare.prepare]. */
sealed interface SharePreparation {

  /**
   * Tutto pronto. [stripped] sono i file di cui si e' tolta la posizione e che i lettori hanno ricontrollato; gli altri si condividono come
   * sono (impostazione spenta). [lease] si rilascia dopo l'uso: toglie le copie e revoca i permessi sugli indirizzi.
   */
  class Ready(val files: List<SharedFile>, val lease: ShareLease, val stripped: Int) : SharePreparation {
    override fun toString(): String = "Ready(${files.size} file, $stripped senza posizione)"
  }

  /** Fermata al media numero [index] (da zero) per il motivo [stop]; nessuna copia resta in cache. */
  class Stopped(val stop: ShareStop, val index: Int) : SharePreparation {
    override fun toString(): String = "Stopped(${stop.code}, #$index)"
  }
}

/** Lo stato per la UI di un esito di [LocationFreeShare.prepare]. */
fun SharePreparation.toState(): ShareOutState = when (this) {
  is SharePreparation.Ready -> ShareOutState.Ready(verified = stripped, direct = files.size - stripped)
  is SharePreparation.Stopped -> ShareOutState.Refused(stop, index)
}

/** Lo stato per la UI (§1.5 `ShareOutState`): copia in corso con l'avanzamento, pronta e verificata, rifiutata con il motivo. */
sealed interface ShareOutState {
  data object Idle : ShareOutState

  data class Preparing(val done: Int, val total: Int) : ShareOutState

  /** [verified] = quanti file sono stati ricontrollati senza posizione; [direct] = quanti si condividono come sono (impostazione spenta). */
  data class Ready(val verified: Int, val direct: Int) : ShareOutState

  data class Refused(val stop: ShareStop, val index: Int) : ShareOutState
}

/**
 * I byte di un media da condividere, com'e' adesso. [bytes] si legge a intervalli e si chiude con [close] (lo fa chi lo apre). [phoneUri] e'
 * l'indirizzo del MediaStore di una copia del telefono (per condividerla com'e' quando la posizione non va tolta), o null per un solo-NAS.
 */
class OpenedForShare(
  val bytes: MediaBytes,
  val mime: String,
  val displayName: String?,
  val phoneUri: Uri?,
  private val onClose: () -> Unit = {},
) : AutoCloseable {
  override fun close() = onClose()

  override fun toString(): String = "OpenedForShare($mime, ${bytes.size} B)"
}

/** Da dove vengono i byte (`:app` lo lega al telefono e a `NasMediaReader`). Un solo-NAS con il NAS irraggiungibile risponde subito. */
interface ShareSource {
  suspend fun open(mediaId: MediaId): ShareOpen
}

sealed interface ShareOpen {
  class Opened(val media: OpenedForShare) : ShareOpen

  /** Il media non c'e' piu' o non si legge. */
  data object Gone : ShareOpen

  /** Un solo-NAS con il NAS che non risponde (E-X1). */
  data object NasUnreachable : ShareOpen
}

/** Come si chiamano i file condivisi: il nome dell'utente, ripulito, con l'estensione del tipo vero (un HEIC diventato JPEG si chiama `.jpg`). */
object ShareNames {

  private const val MAX_BASE = 80

  fun of(displayName: String?, mime: String): String = "${base(displayName)}.${extensionOf(mime, displayName)}"

  private fun base(displayName: String?): String {
    val withoutExtension = displayName?.substringBeforeLast('.', displayName).orEmpty()
    val cleaned = buildString {
      var lastWasSeparator = false
      for (c in withoutExtension) {
        val keep = c.isLetterOrDigit() || c == ' ' || c == '.' || c == '_' || c == '-' || c == '(' || c == ')'
        val out = if (keep) c else '_'
        if (out == '_' && lastWasSeparator) continue
        lastWasSeparator = out == '_'
        append(out)
      }
    }.trim(' ', '.', '_')
    return cleaned.take(MAX_BASE).trim(' ', '.', '_').ifEmpty { "media" }
  }

  private fun extensionOf(mime: String, displayName: String?): String = when (mime.lowercase(Locale.ROOT)) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/heic" -> "heic"
    "image/heif" -> "heif"
    "image/avif" -> "avif"
    "video/mp4" -> "mp4"
    "video/quicktime" -> "mov"
    "video/3gpp" -> "3gp"
    "video/3gpp2" -> "3g2"
    "video/webm" -> "webm"
    "video/x-matroska" -> "mkv"
    else -> displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.takeIf { it.length in 1..5 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } } ?: "bin"
  }
}
