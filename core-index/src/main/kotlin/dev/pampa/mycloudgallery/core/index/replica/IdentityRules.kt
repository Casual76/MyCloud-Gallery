package dev.pampa.mycloudgallery.core.index.replica

import dev.pampa.mycloudgallery.core.index.replica.store.IdentityOutcome
import dev.pampa.mycloudgallery.core.index.replica.store.SharedIdentity
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.domain.MergeRules
import java.security.MessageDigest

/**
 * Il media a cui **questo** dispositivo ha gia' legato un file del NAS (dalla propria scansione o da un'adozione
 * precedente).
 *
 * @property readHere la chiave di contenuto calcolata **qui** dai byte del file (impronta rapida letta dalla scansione);
 *   `null` se il legame viene solo dall'indice.
 */
data class LocalBinding(val mediaId: MediaId, val readHere: ContentKey?)

/**
 * L'ADOZIONE delle identita' condivise (D7, con i limiti di D8), pura. Il catalogo la applica dentro la propria
 * transazione (P2 `adoptIdentity`, P11 adattatore; [dev.pampa.mycloudgallery.core.index.replica.store.MemoryReplicaCatalog]
 * nei test).
 *
 * Ordine delle regole:
 * 1. stesso id gia' legato al file → [IdentityOutcome.ALREADY];
 * 2. l'id remoto e' legato qui a media con **altri** contenuti (dimensione o `qh1` diversi) → [IdentityOutcome.CONFLICT]:
 *    senza questa regola un dispositivo compromesso potrebbe fondere media diversi e farli "sparire" (D7);
 * 3. il file e' stato letto qui e i suoi byte non sono quelli del record → [IdentityOutcome.CONFLICT];
 * 4. l'id remoto e' gia' noto qui (altri file) ma l'impronta di questo file non e' stata letta qui → adottarlo vorrebbe
 *    dire unire un media esistente (anche del telefono) a un file del NAS sulla parola dell'indice: vietato da D8 →
 *    [IdentityOutcome.DEFERRED] (separato; la scansione leggera' l'impronta);
 * 5. nessun media locale → [IdentityOutcome.ADOPTED];
 * 6. due id per lo stesso file convergono al **minore** (`MergeRules.survivorOf`): [IdentityOutcome.CONVERGED] se il
 *    remoto e' minore, altrimenti [IdentityOutcome.KEPT_LOCAL].
 */
object IdentityRules {

  /**
   * @param remote l'identita' fusa dall'indice (D6/D7).
   * @param local il media gia' legato qui al file, se c'e'.
   * @param remoteIdHere i contenuti a cui l'id remoto e' gia' legato qui (versioni di quel media, alias risolti);
   *   `null` = id sconosciuto qui.
   */
  fun decide(remote: SharedIdentity, local: LocalBinding?, remoteIdHere: Set<ContentKey>?): IdentityOutcome {
    if (local != null && local.mediaId == remote.mediaId) return IdentityOutcome.ALREADY
    if (remoteIdHere != null && remote.content !in remoteIdHere) return IdentityOutcome.CONFLICT
    if (local?.readHere != null && local.readHere != remote.content) return IdentityOutcome.CONFLICT
    if (remoteIdHere != null && local?.readHere == null) return IdentityOutcome.DEFERRED
    if (local == null) return IdentityOutcome.ADOPTED
    return if (MergeRules.survivorOf(local.mediaId, remote.mediaId) == remote.mediaId) IdentityOutcome.CONVERGED else IdentityOutcome.KEPT_LOCAL
  }

  /**
   * L'id **deterministico** di un file che non puo' adottare l'identita' remota (conflitto, rinvio): uguale su ogni
   * ricostruzione della replica (E-I7) e su ogni dispositivo, diverso per share, percorso e contenuto. Forma di UUID
   * versione 8 (RFC 9562, "personalizzata").
   */
  fun separateId(share: String, pathKey: String, content: ContentKey): MediaId {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("mcg-sep".toByteArray(Charsets.US_ASCII))
    digest.update(0)
    digest.update(share.toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(pathKey.toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(content.bytes())
    val b = digest.digest().copyOf(16)
    b[6] = ((b[6].toInt() and 0x0f) or 0x80).toByte()
    b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return MediaId("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
  }
}
