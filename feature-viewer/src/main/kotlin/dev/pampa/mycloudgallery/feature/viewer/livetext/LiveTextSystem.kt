package dev.pampa.mycloudgallery.feature.viewer.livetext

import android.content.Context
import android.content.Intent
import dev.pampa.mycloudgallery.core.security.input.SensitiveClipboard

/**
 * Le azioni di sistema del testo dal vivo (D43): tutte esplicite, tutte a un tocco dell'utente. Mai nei log: il testo passa da qui agli
 * appunti o alla condivisione e basta (D40).
 */
internal object LiveTextSystem {

  /**
   * Il testo negli appunti **segnato come sensibile** (`ClipDescription.EXTRA_IS_SENSITIVE`, Android 13+: niente anteprima negli
   * appunti e nei suggerimenti della tastiera). Falso se il sistema non ha gli appunti.
   */
  fun copy(context: Context, label: String, text: String): Boolean = SensitiveClipboard.copy(context, label, text)

  /** `ACTION_SEND` di testo semplice nel selettore di sistema. Nessun URI, nessun permesso: solo il testo scelto. */
  fun shareIntent(text: String): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, text)
    }
    return Intent.createChooser(send, null)
  }
}
