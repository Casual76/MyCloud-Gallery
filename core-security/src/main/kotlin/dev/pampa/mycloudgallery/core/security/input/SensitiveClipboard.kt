package dev.pampa.mycloudgallery.core.security.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Copia negli appunti dati sensibili (password, codice di recupero) marcandoli come tali
 * (`ClipDescription.EXTRA_IS_SENSITIVE`): da Android 13 il sistema non ne mostra l'anteprima nel
 * messaggio "Copiato" e le tastiere non lo propongono tra i suggerimenti. Su versioni precedenti l'extra e'
 * ignorato, ma e' innocuo.
 *
 * Le foto e i testi normali NON passano da qui. Gli appunti restano comunque leggibili dall'app in
 * primo piano: non copiare una password se si puo' evitare (docs/sicurezza.md C17).
 */
object SensitiveClipboard {

  /**
   * Valore di `ClipDescription.EXTRA_IS_SENSITIVE`. Scritto qui perche' la costante esiste dall'API 33 e
   * il modulo arriva all'API 29; un test controlla che sia identica.
   */
  const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

  /** Copia [text] marcandolo come sensibile. Restituisce falso se il sistema non ha gli appunti. */
  fun copy(context: Context, label: CharSequence, text: CharSequence): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(newSensitiveClip(label, text))
    return true
  }

  internal fun newSensitiveClip(label: CharSequence, text: CharSequence): ClipData {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    return clip
  }
}
