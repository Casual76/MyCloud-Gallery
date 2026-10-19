package dev.pampa.mycloudgallery.feature.integrations.picker

import android.content.Intent
import dev.pampa.mycloudgallery.core.model.MediaKind
import java.util.Locale

// Fase 5B (P10, docs/fase5-progetto.md §2.7, D23, C17): cio' che un'altra app chiede al selettore. L'intento viene da fuori: e' input non
// fidato. Si leggono solo l'azione, i tipi e tre booleani, ognuno in modo difensivo (un extra di un tipo sbagliato o un pacchetto rovinato non
// lancia: vale come assente). **Niente altro dell'intento si legge**: nessun URI in `data` (con `PICK` l'altra app puo' passarne uno: non si
// apre mai), nessun extra sconosciuto.

/** L'azione che ha aperto il selettore. */
enum class PickerAction { GET_CONTENT, PICK }

/**
 * La richiesta di un'altra app: quali tipi di media, se piu' di uno, se solo sul dispositivo.
 *
 * @property images vero se la richiesta ammette foto (il tipo jolly di tutte le immagini, il tipo jolly di tutto, o un tipo preciso come `image/jpeg`).
 * @property videos vero se ammette video.
 * @property allowMultiple `EXTRA_ALLOW_MULTIPLE`: senza, un tocco sceglie e consegna.
 * @property localOnly `EXTRA_LOCAL_ONLY`: l'altra app non vuole dati remoti. Un media solo-NAS lo e' (sta sul NAS di casa, non sul telefono):
 *   con questo extra non si offre.
 * @property mimeFilters i tipi chiesti, normalizzati (minuscolo, senza parametri), per ricontrollare quelli precisi (`image/jpeg`) alla consegna.
 */
data class PickerRequest(
  val action: PickerAction,
  val images: Boolean,
  val videos: Boolean,
  val allowMultiple: Boolean,
  val localOnly: Boolean,
  val mimeFilters: List<String>,
) {

  /** Se un media di questo tipo si offre. */
  fun acceptsKind(kind: MediaKind): Boolean = if (kind.isVideo) videos else images

  /** Se un file di questo tipo MIME soddisfa la richiesta. */
  fun acceptsMime(mime: String): Boolean {
    val value = normalize(mime) ?: return false
    if (mimeFilters.isEmpty()) return value.startsWith("image/") && images || value.startsWith("video/") && videos
    return mimeFilters.any { filter ->
      when {
        filter == "*/*" -> value.startsWith("image/") && images || value.startsWith("video/") && videos
        filter.endsWith("/*") -> value.startsWith(filter.dropLast(1))
        else -> value == filter
      }
    }
  }

  /** Se la richiesta chiede tipi precisi (non un tipo jolly come tutte le immagini, tutti i video o tutto): i soli per cui serve il tipo di ogni file scelto. */
  val hasSpecificTypes: Boolean get() = mimeFilters.any { !it.endsWith("/*") }

  companion object {

    /** La richiesta di [intent], o null se non e' una richiesta che questo selettore serve (azione sconosciuta, nessun tipo di media). */
    fun from(source: Intent?): PickerRequest? {
      val intent = source ?: return null
      val action = when (intent.action) {
        Intent.ACTION_GET_CONTENT -> PickerAction.GET_CONTENT
        Intent.ACTION_PICK -> PickerAction.PICK
        else -> return null
      }
      val extraTypes = safe { intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES) }.orEmpty().mapNotNull { normalize(it) }
      val typed = if (extraTypes.isNotEmpty()) extraTypes else listOfNotNull(safe { intent.type }?.let { normalize(it) })
      // Nessun tipo (un'azione esplicita senza tipo): foto e video, come il tipo jolly di tutto.
      val filters = typed.distinct()
      val images = filters.isEmpty() || filters.any { it == "*/*" || it.startsWith("image/") }
      val videos = filters.isEmpty() || filters.any { it == "*/*" || it.startsWith("video/") }
      if (!images && !videos) return null
      return PickerRequest(
        action = action,
        images = images,
        videos = videos,
        allowMultiple = safe { intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) } ?: false,
        localOnly = safe { intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false) } ?: false,
        mimeFilters = filters,
      )
    }

    private val TYPE = Regex("^(\\*|[a-z0-9][a-z0-9.+-]{0,100})/(\\*|[a-z0-9][a-z0-9.+_-]{0,100})$")

    /** `tipo/sottotipo` minuscolo senza parametri, o null se non e' un tipo. */
    internal fun normalize(raw: String): String? {
      val value = raw.substringBefore(';').trim().lowercase(Locale.ROOT)
      return value.takeIf { TYPE.matches(it) }
    }

    /** Un extra ostile (pacchetto rovinato, tipo sbagliato) vale come assente: il selettore non cade per un intento. */
    private inline fun <T> safe(read: () -> T): T? = try {
      read()
    } catch (e: Exception) {
      null
    }
  }
}
