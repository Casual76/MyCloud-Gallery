package dev.pampa.mycloudgallery.feature.backup.free

import android.content.IntentSender
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.TrashBatch
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAssessment
import dev.pampa.mycloudgallery.core.model.MediaId

// Fase 5B (P12, docs/fase5-progetto.md §1.1, §1.5, §2.5, D19, D21, D22): le PORTE che le schermate "Libera spazio", "Versioni" e "Correggi"
// leggono e che `:app` lega (P14). I servizi veri (`FreeSpace`, `VersionKeeper`, `Corrections`, `PendingTasks`) sono contratti di core-backup:
// qui ci sono solo le cose che core-backup non puo' avere perche' non vede il sistema operativo. Ogni porta e' **opzionale per Dagger**
// (`FiveBOptionalBindings`): finche' `:app` non le lega le schermate dicono che la funzione non e' ancora pronta e un tocco non fa niente.

/**
 * I dialoghi del sistema che chiedono il permesso all'utente (Android 11+). feature-backup non vede `core-mediastore`: `:app` mappa queste
 * due richieste su `PhoneMediaIntents` (con la recinzione D13 in debug). **L'app non toglie ne' riscrive mai un file da sola**: ogni riga
 * passa da qui, dopo un tocco dell'utente su un foglio di conferma, e la risposta e' quella del sistema.
 */
interface PhoneSystemDialogs {

  /** Il sistema ha questi dialoghi (Android 11 o piu'). Senza, "Libera spazio" non esiste (D14). */
  val available: Boolean

  /**
   * Il dialogo che chiede di togliere dal telefono le righe di [batch]: il cestino di Android con [dev.pampa.mycloudgallery.core.backup.contract.TrashMode.ANDROID_TRASH],
   * l'eliminazione definitiva con `DELETE_NOW`. Null se il dialogo non c'e'.
   */
  fun removeRequest(batch: TrashBatch): IntentSender?

  /** Il dialogo che chiede il permesso di riscrivere [rows] (correzioni e "Ripristina originale", E-E10). Null se non c'e'. */
  fun writeRequest(rows: List<PhoneRef>): IntentSender?
}

/** Nessun dialogo: la funzione non e' ancora legata, o il sistema non li ha. Fallisce chiuso. */
object NoPhoneSystemDialogs : PhoneSystemDialogs {
  override val available: Boolean = false

  override fun removeRequest(batch: TrashBatch): IntentSender? = null

  override fun writeRequest(rows: List<PhoneRef>): IntentSender? = null
}

/** Cosa ha risposto l'utente a un dialogo del sistema. */
enum class DialogAnswer {
  /** Ha detto di si': il sistema ha dato il permesso per tutte le righe. */
  ALLOWED,

  /** Ha detto di no (o ha chiuso il dialogo): niente e' cambiato. */
  REFUSED,

  /** Il dialogo non c'e' (nessun permesso di sistema da chiedere, nessuna porta legata). */
  NOT_AVAILABLE,
}

/** Chi sa chiedere all'utente di togliere un lotto dal telefono: la UI, con il dialogo del sistema; i test, con una risposta scelta. */
fun interface RemoveDialog {
  suspend fun ask(batch: TrashBatch): DialogAnswer
}

/** Chi sa chiedere all'utente il permesso di riscrivere delle righe del telefono. */
fun interface WriteDialog {
  suspend fun ask(rows: List<PhoneRef>): DialogAnswer
}

/**
 * "Elimina solo dal telefono" (D22, E-D14, E-B14): che cosa propone il foglio per i media scelti. `:app` la lega a `SafeFreeSpace.assessPhoneOnly`
 * (dal catalogo locale: nessuna lettura del NAS). Senza, il foglio non c'e' e il viewer elimina come prima.
 */
fun interface PhoneOnlyAssessor {
  suspend fun assess(ids: Set<MediaId>): PhoneOnlyAssessment
}
