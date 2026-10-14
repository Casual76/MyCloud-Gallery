package dev.pampa.mycloudgallery.core.index.replica.store

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Cio' che la replica chiede al CATALOGO (docs/fase4-progetto.md §2.2 "Applicazione": osservazioni con
// `WhenUndecided.KEEP_SEPARATE`, adozione d'identita' D7). Porta di P4; P2 scrive in `MediaCatalog` le operazioni
// (`adoptIdentity` con il rifiuto di D7, `nasRefs`), P11 la lega. Le regole di D7 sono in `replica/IdentityRules`:
// l'adattatore le applica dentro la transazione del catalogo, [MemoryReplicaCatalog] le usa nei test.

/** L'identita' condivisa di un file del NAS secondo l'indice (D7): il `MediaId` minore fra i record validi. */
data class SharedIdentity(
  val mediaId: MediaId,
  val versionId: VersionId,
  /** La chiave di contenuto (`qh1` + dimensione) del record da cui viene l'identita'. */
  val content: ContentKey,
) {
  override fun toString(): String = "SharedIdentity(${content})"
}

/**
 * La vista fusa di un percorso di una share (D6): il record vincente fra quelli **validi** (timbro uguale all'ultimo
 * elenco della scansione, oppure nessun elenco ancora = [provisional]), e l'identita' a cui convergere (D7).
 * [winner] `null` = nessun record valido per questo percorso (lapidi, timbro diverso E-N12, dispositivo dimenticato):
 * il catalogo toglie cio' che veniva solo dall'indice; cio' che ha visto la scansione resta.
 */
data class MergedFile(
  val pathKey: String,
  val path: SharePath?,
  val winner: MetaRecord.Present?,
  val winnerDevice: String?,
  val provisional: Boolean,
  val identity: SharedIdentity?,
) {
  override fun toString(): String = "MergedFile(winner=${winner != null}, provisional=$provisional)"
}

/** Come e' finita l'adozione d'identita' di un file (D7), per i conteggi della Diagnostica. */
enum class IdentityOutcome(override val code: String) : StableCode {
  /** Il file non aveva un media qui: nasce con gli id dell'indice. */
  ADOPTED("adopted"),

  /** Gli id coincidevano gia'. */
  ALREADY("already"),

  /** Il media locale aveva un id maggiore: converge al minore (alias, come `MergeRules`), preferiti e album compresi (E-I18). */
  CONVERGED("converged"),

  /** Il media locale ha l'id minore: resta; gli altri convergeranno al nostro. */
  KEPT_LOCAL("kept_local"),

  /**
   * L'id remoto e' gia' noto qui per altri file e l'impronta di **questo** file non e' stata letta qui: nessuna unione
   * da dati dell'indice (D8), si aspetta la scansione. Il file resta separato.
   */
  DEFERRED("deferred"),

  /** Rifiutata: l'id remoto e' legato qui a un contenuto diverso (dimensione o `qh1`), o il record descrive altri byte. */
  CONFLICT("conflict"),

  /** Nessun record valido: tolto cio' che veniva solo dall'indice. */
  REMOVED("removed"),
}

/** La porta del catalogo per la replica. */
interface ReplicaCatalog {

  /**
   * L'ultimo timbro della scansione (D6) dei percorsi [pathKeys] di [share]; mancano quelli mai elencati (il record vale
   * "provvisorio" fino alla prima scansione).
   */
  suspend fun listingStamps(share: String, pathKeys: Collection<String>): Map<String, NasStamp>

  /**
   * Applica le viste fuse di [share] in **una** transazione: osservazioni dei file solo-NAS con
   * `WhenUndecided.KEEP_SEPARATE`, adozione d'identita' con le regole di `IdentityRules` (D7, D8). Gli esiti sono
   * nello stesso ordine di [files].
   */
  suspend fun applyMerged(share: String, files: List<MergedFile>): List<IdentityOutcome>

  /** E-I8: la share e' sparita o negata da due giorni: si tolgono i media che esistevano solo per l'indice. */
  suspend fun dropShare(share: String)
}
