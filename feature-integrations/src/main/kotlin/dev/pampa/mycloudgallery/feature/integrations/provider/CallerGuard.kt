package dev.pampa.mycloudgallery.feature.integrations.provider

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.provider.DocumentsContract

// Fase 5B (P10, docs/fase5-progetto.md §6.1, C17; riga P10 del §4.4: "un'altra app non ottiene niente senza un tocco e senza un grant"):
// chi sta chiamando il provider dei documenti. Il manifest lo protegge con `MANAGE_DOCUMENTS` (solo il sistema) e `grantUriPermissions`
// (un'altra app entra solo con un permesso su UNO URI, dato dal sistema dopo un tocco dell'utente): lo fa rispettare il sistema prima di
// chiamare il provider. Questa e' la **seconda** cintura, dentro il provider, per due motivi:
//  - `getType` non passa dai permessi del provider: senza questo controllo chiunque potrebbe chiedere il tipo di un documento;
//  - il manifest e la sua policy possono sbagliare in futuro; il provider non deve dipendere da loro per non servire un estraneo.
//
// Le regole: si entra se si e' il nostro stesso processo (il selettore, i test), oppure se si ha `MANAGE_DOCUMENTS` (il sistema), oppure,
// **per quel documento soltanto**, se si ha un permesso di lettura sul suo URI. Le radici e gli elenchi sono solo del sistema: nessun permesso
// su un documento apre la cartella che lo contiene.

/** Le domande che il provider fa al sistema su chi lo chiama. Un'interfaccia: Robolectric non simula i binder. */
interface CallerChecks {
  /** Il chiamante e' questo processo (nessuna transazione binder in corso, o stesso uid). */
  fun isOwnProcess(): Boolean

  /** Il chiamante ha `MANAGE_DOCUMENTS` (il selettore di documenti di sistema). */
  fun hasManageDocuments(): Boolean

  /** Il chiamante ha un permesso di lettura su [uri] (dato con `FLAG_GRANT_READ_URI_PERMISSION`). */
  fun hasReadGrant(uri: Uri): Boolean
}

/** Le domande vere, al sistema. */
class AndroidCallerChecks(context: Context) : CallerChecks {
  private val context = context.applicationContext

  override fun isOwnProcess(): Boolean = Binder.getCallingUid() == Process.myUid()

  override fun hasManageDocuments(): Boolean =
    context.checkCallingPermission(MANAGE_DOCUMENTS) == PackageManager.PERMISSION_GRANTED

  override fun hasReadGrant(uri: Uri): Boolean =
    context.checkCallingUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED

  companion object {
    const val MANAGE_DOCUMENTS: String = "android.permission.MANAGE_DOCUMENTS"
  }
}

/** L'autorita' del provider di questa installazione: la stessa del manifest (`${applicationId}.integrations.documents`). */
object DocumentsAuthority {
  const val SUFFIX: String = ".integrations.documents"

  fun of(context: Context): String = context.packageName + SUFFIX
}

/** Il guardiano: lancia `SecurityException` per un chiamante che non deve entrare. Il messaggio non dice niente su cosa c'e' dentro. */
class CallerGuard(private val checks: CallerChecks, private val authority: String) {

  /** Radici ed elenchi: solo il sistema (o noi). */
  fun requireSystem() {
    if (checks.isOwnProcess() || checks.hasManageDocuments()) return
    refuse()
  }

  /** Un documento: il sistema, o chi ha un permesso di lettura su QUESTO documento (non sulla cartella, non su un altro). */
  fun requireDocument(documentId: String?) {
    if (checks.isOwnProcess() || checks.hasManageDocuments()) return
    if (documentId != null && checks.hasReadGrant(DocumentsContract.buildDocumentUri(authority, documentId))) return
    refuse()
  }

  private fun refuse(): Nothing {
    IntegrationCounters.add(IntegrationCounter.PROVIDER_STRANGERS_REFUSED)
    throw SecurityException("Non autorizzato.")
  }
}
