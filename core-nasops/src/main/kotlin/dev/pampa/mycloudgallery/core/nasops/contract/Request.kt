package dev.pampa.mycloudgallery.core.nasops.contract

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Fase 4B (docs/fase4-progetto.md §1.2 "core-nasops", §2.7, D24, D25): cosa si puo' chiedere a `NasOperations`. Tipi di P0-B.
//
// Una richiesta nasce da un gesto dell'utente (una selezione, un nome scritto, una scelta di destinazione) nella schermata, mai da
// un dato letto dal NAS: gli elementi di [NasItem] arrivano dalla selezione dell'utente nel catalogo o nell'elenco di una cartella,
// i riferimenti al cestino da una voce di "Eliminati di recente" che l'utente ha toccato. Con una richiesta non si fa ancora niente:
// serve il piano ([OpPlan]) e poi la conferma ([UserConfirmation]).

/** Che cosa fa un'operazione. I codici si salvano in `nas_op.kind`: non cambiano. */
enum class OpKind(override val code: String, val permanent: Boolean = false) : StableCode {
  CREATE_FOLDER("create_folder"),
  RENAME("rename"),
  MOVE("move"),

  /** Elimina: i file vanno nel cestino del NAS per 30 giorni (D25), annullabile finche' ci sono. */
  TRASH("trash"),
  RESTORE("restore"),

  /** "Elimina subito" dal cestino del NAS (Q8): definitivo, con doppia conferma e l'avviso sul backup esterno. */
  PURGE_NOW("purge_now", permanent = true),
}

/**
 * Un file o una cartella del NAS scelti dall'utente. [size] e' la dimensione del file, o il totale del contenuto di una cartella
 * (dal catalogo, per il riepilogo); [stamp] e' il timbro visto dall'utente per un **file** (null per una cartella): l'operazione
 * si ferma se il file non e' piu' quello (E-D13, E-N11).
 */
data class NasItem(
  val share: String,
  val path: SharePath,
  val isFolder: Boolean,
  val size: Long,
  val stamp: NasStamp?,
  /** Per una cartella, quanti file contiene (per "Eliminati di recente"). */
  val fileCount: Int? = null,
) {
  init {
    require(size >= 0) { "Dimensione negativa." }
    require(!isFolder || stamp == null) { "Una cartella non ha un timbro da confrontare." }
  }

  /** Mai il percorso nei log: e' un dato dell'utente (§4.3). */
  override fun toString(): String = "NasItem(folder=$isFolder, size=$size)"
}

/**
 * Una voce del cestino da ripristinare. Il ripristino lo puo' fare qualunque dispositivo con il permesso della share (D25), quindi
 * il proprietario ([ownerDeviceId]) puo' essere un altro; [originalPath] viene dal record firmato mostrato all'utente e rientra
 * come percorso di media validato (`SharePath.parseMediaPath`). Il ripristino non sovrascrive mai (nome alternativo) e sposta solo
 * un file che sta davvero in `trash/<proprietario>/files/<id>`.
 */
data class TrashRestoreRef(
  val share: String,
  val ownerDeviceId: String,
  val trashId: String,
  val originalPath: SharePath,
  val isFolder: Boolean,
  val size: Long,
) {
  init {
    require(TrustIds.isValid(ownerDeviceId) && TrustIds.isValid(trashId)) { "Id di cestino non validi." }
    require(size >= 0) { "Dimensione negativa." }
  }

  override fun toString(): String = "TrashRestoreRef(folder=$isFolder, size=$size)"
}

/**
 * Una voce del **proprio** cestino da eliminare subito (Q8). Solo l'id: quello che si elimina lo ricava `core-nasops` dal diario
 * locale (percorso, dimensione, share), mai da un record remoto. Un id che nel diario non c'e' non elimina niente.
 */
data class OwnTrashRef(val trashId: String) {
  init {
    require(TrustIds.isValid(trashId)) { "Id di cestino non valido." }
  }

  override fun toString(): String = "OwnTrashRef"
}

/** Le richieste. Nessuna fa niente da sola. */
sealed interface OpRequest {
  val kind: OpKind

  /** Crea la cartella [name] dentro [parent] (null = radice della share). Il nome si controlla (E-D9). */
  data class CreateFolder(val share: String, val parent: SharePath?, val name: String) : OpRequest {
    override val kind: OpKind get() = OpKind.CREATE_FOLDER

    override fun toString(): String = "CreateFolder"
  }

  /** Rinomina [item] in [newName], nella stessa cartella. */
  data class Rename(val item: NasItem, val newName: String) : OpRequest {
    override val kind: OpKind get() = OpKind.RENAME

    override fun toString(): String = "Rename"
  }

  /** Sposta [items] in [destFolder] di [destShare] (null = radice). Fra share diverse: copia lato server, verifica, cestino (D24). */
  data class Move(val items: List<NasItem>, val destShare: String, val destFolder: SharePath?) : OpRequest {
    override val kind: OpKind get() = OpKind.MOVE

    override fun toString(): String = "Move(${items.size} elementi)"
  }

  /** Elimina [items]: nel cestino del NAS (D25). "Ovunque" (con il cestino di Android) lo orchestra la UI, non questa richiesta. */
  data class Trash(val items: List<NasItem>) : OpRequest {
    override val kind: OpKind get() = OpKind.TRASH

    override fun toString(): String = "Trash(${items.size} elementi)"
  }

  data class Restore(val entries: List<TrashRestoreRef>) : OpRequest {
    override val kind: OpKind get() = OpKind.RESTORE

    override fun toString(): String = "Restore(${entries.size} elementi)"
  }

  data class PurgeNow(val entries: List<OwnTrashRef>) : OpRequest {
    override val kind: OpKind get() = OpKind.PURGE_NOW

    override fun toString(): String = "PurgeNow(${entries.size} elementi)"
  }
}
