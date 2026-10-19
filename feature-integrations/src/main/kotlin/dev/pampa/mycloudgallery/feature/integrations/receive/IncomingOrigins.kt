package dev.pampa.mycloudgallery.feature.integrations.receive

import android.net.Uri
import dev.pampa.mycloudgallery.core.backup.contract.IncomingOrigin
import java.util.Locale

// Fase 5B (P11, docs/fase5-progetto.md D24, C18, E-X2): da dove viene un flusso che un'altra app ci passa, guardando il SOLO indirizzo. Ammesso un
// solo caso: un `content://` di un'altra app. Tutto il resto si rifiuta **senza aprirlo**:
//  - `file://` (e ogni indirizzo che non sia `content://`: `http`, `android.resource`, `intent`...): l'altra app ci indica un percorso, non ci da'
//    un flusso, e il percorso potrebbe essere un file nostro (il database, le preferenze, le copie in cache) che il nostro processo sa leggere;
//  - un `content://` di un provider **dell'app stessa** (il nostro `FileProvider`, il provider dei documenti): ci manderebbe a leggere i nostri file
//    con i nostri permessi. Un provider nostro si riconosce dall'autorita' che e' il nome del pacchetto o comincia per `<pacchetto>.`; si guarda
//    l'**host**, non l'autorita' intera (`0@dev.pampa...` e' lo stesso provider con l'utente davanti), senza badare alle maiuscole.

object IncomingOrigins {

  fun classify(uri: Uri, ownPackage: String): IncomingOrigin {
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "content") return IncomingOrigin.FILE_SCHEME
    val host = uri.host?.lowercase(Locale.ROOT)
    // Un `content://` senza autorita' non e' un provider: non c'e' niente da leggere in modo sicuro.
    if (host.isNullOrEmpty()) return IncomingOrigin.FILE_SCHEME
    val own = ownPackage.lowercase(Locale.ROOT)
    return if (host == own || host.startsWith("$own.")) IncomingOrigin.OWN_PROVIDER else IncomingOrigin.CONTENT_OF_ANOTHER_APP
  }
}
