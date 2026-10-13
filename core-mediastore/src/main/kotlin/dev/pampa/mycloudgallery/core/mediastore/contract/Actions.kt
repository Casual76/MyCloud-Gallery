package dev.pampa.mycloudgallery.core.mediastore.contract

import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import dev.pampa.mycloudgallery.core.model.MediaRef

/**
 * Le azioni del sistema sui media del telefono: l'URI, la condivisione e il cestino di sistema.
 * Un'interfaccia (nel progetto e' un `object`) per poterla fingere nei test del viewer e della Libreria.
 *
 * **Niente permessi speciali.** Nessun `MANAGE_MEDIA` e nessun accesso a tutti i file (docs/sicurezza.md §4-bis,
 * "Non richiesti"): cestino e ripristino passano da `MediaStore.createTrashRequest`, cioe' dal dialogo di sistema,
 * che e' una seconda conferma gratuita (D14). L'app non cancella mai da sola.
 */
interface PhoneMediaIntents {

  /** Se il cestino di sistema esiste (Android 11+). Su Android 10 l'azione "Elimina dal telefono" non c'e' (D14). */
  val canTrash: Boolean

  /** L'URI `content://media/...` della riga: con questo, e solo questo, si legge o si condivide. */
  fun uriOf(ref: MediaRef.Phone): Uri

  /**
   * L'intento di condivisione di [refs]: `ACTION_SEND` o `ACTION_SEND_MULTIPLE` con `FLAG_GRANT_READ_URI_PERMISSION`
   * e `ClipData` (obbligatori da Android 17/18). La posizione dentro le foto la regola il sistema dell'app che
   * riceve; "rimuovi posizione" arriva con la 5.7. Null se non c'e' niente da condividere.
   */
  fun share(refs: List<MediaRef.Phone>): Intent?

  /**
   * La richiesta di spostare [refs] nel cestino di sistema ([trash] = true) o di ripristinarli (false): il chiamante
   * la lancia con `startIntentSenderForResult`. Null se il cestino non c'e' ([canTrash] falso) o [refs] e' vuoto.
   */
  fun trashRequest(refs: List<MediaRef.Phone>, trash: Boolean): IntentSender?
}

/**
 * Un elemento nel cestino di sistema, per "Eliminati di recente" (D14, D17).
 *
 * @property expiresAtMs quando il sistema lo cancellera' (30 giorni dall'eliminazione): da qui i giorni rimasti.
 */
data class TrashedItem(
  val ref: MediaRef.Phone,
  val isVideo: Boolean,
  val trashedAtMs: Long,
  val expiresAtMs: Long,
)

/**
 * Il cestino di sistema (`QUERY_ARG_MATCH_TRASHED`, solo Android 11+). **Non** e' il cestino di Samsung Gallery,
 * che toglie la riga dal MediaStore e che l'app non puo' leggere senza l'accesso a tutti i file (E-P11): una frase
 * nella schermata lo chiarisce. Il ripristino passa da [PhoneMediaIntents.trashRequest] con `trash = false`.
 */
interface RecentlyDeletedSource {
  suspend fun list(): List<TrashedItem>
}
