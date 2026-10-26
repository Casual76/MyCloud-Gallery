package dev.pampa.mycloudgallery.core.nasops.engine

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.noop.NoMoveCompletionSink
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.sync.Mutex

// Fase 4B, P12: le PORTE dell'esecutore delle operazioni verso cio' che non e' il NAS ne' il diario (le lega :app, P16). Nessun tipo
// di core-nas qui: chi lega le porte (`:app`) non deve vedere le primitive di scrittura.

/**
 * Il lucchetto delle scritture distruttive di questo dispositivo: l'esecutore delle operazioni, la pulizia del cestino e quella
 * dei propri file lavorano **uno alla volta**. Cosi' la pulizia non elimina mai un elemento che un ripristino sta spostando, e due
 * riprese non eseguono lo stesso elemento insieme. Uno per processo, condiviso dai tre.
 */
class NasOpsLock {
  internal val mutex = Mutex()
}

/** Il record firmato del cestino da pubblicare in `trash/<io>/rec/<id>` (formato §5.7, M5). Solo da mostrare agli altri. */
data class TrashRecordDraft(
  val share: String,
  val trashId: String,
  val originalPath: SharePath,
  val size: Long,
  val trashedAtNas: Long,
  val reason: TrashReason,
  val isFolder: Boolean,
  /** Solo per le cartelle: quanti file conteneva; null se non si e' potuto contare. */
  val fileCount: Int?,
) {
  override fun toString(): String = "TrashRecordDraft(${reason.code}, folder=$isFolder)"
}

/** Com'e' andata la pubblicazione di un record del cestino. */
sealed interface RecordWrite {
  /** Pubblicato adesso o gia' presente (una ripresa): va bene uguale. */
  data object Written : RecordWrite

  /**
   * Nessuna identita' per firmare (famiglia non pronta, dispositivo non approvato): il record non si scrive. L'operazione prosegue:
   * il record serve solo a mostrare l'elemento agli altri dispositivi ("senza record valido"); il diario locale c'e' comunque.
   */
  data object Unavailable : RecordWrite

  /** Il NAS non ha risposto: si riprova alla ripresa (passo non fatto). */
  data object Retry : RecordWrite
}

/** Chi scrive i record firmati del cestino. */
fun interface TrashRecordWriter {
  suspend fun publish(draft: TrashRecordDraft): RecordWrite

  companion object {
    /** Nessun record (test, famiglia assente): solo il diario locale. */
    val NONE: TrashRecordWriter = TrashRecordWriter { RecordWrite.Unavailable }
  }
}

/**
 * L'impronta completa gia' nota di un file (dal catalogo: hash completo calcolato su questo dispositivo, D8), per non rileggere la
 * sorgente nella verifica di uno spostamento tra share ("calcolato se non noto", §2.7). Vale solo per quel timbro esatto.
 */
fun interface KnownHashes {
  suspend fun sha256(share: String, path: SharePath, stamp: NasStamp): Sha256?

  companion object {
    val NONE: KnownHashes = KnownHashes { _, _, _ -> null }
  }
}

/**
 * Le regole delle attivita' pesanti per il ripiego dal telefono (E-D12: la copia lato server rifiutata si fa leggendo e scrivendo dal
 * telefono "come attivita' pesante"). Falso = non adesso (dati mobili, batteria): l'elemento aspetta con l'operazione in pausa.
 */
fun interface HeavyWorkPolicy {
  fun allowedNow(): Boolean

  companion object {
    val ALWAYS: HeavyWorkPolicy = HeavyWorkPolicy { true }
  }
}

/**
 * R3 (revisione 4B): i file che il **catalogo** vede ancora presenti in una share, per `fileId`, con i loro percorsi. La scansione non entra
 * mai in `.mycloudgallery/`: un file del proprio cestino che il catalogo vede presente **fuori** dalla cartella cestinata e' raggiungibile
 * anche da li' (un collegamento simbolico che Samba presenta come cartella), e la pulizia non lo tocca. Serve solo a **non** eliminare: un
 * catalogo vuoto o vecchio non fa eliminare niente in piu' di quanto il diario gia' permetta.
 */
fun interface CatalogPresence {
  suspend fun presentPaths(share: String, fileIds: Collection<Long>): Map<Long, List<SharePath>>

  companion object {
    /** Nessun catalogo (test, banco): solo il diario e il NAS decidono. */
    val NONE: CatalogPresence = CatalogPresence { _, _ -> emptyMap() }
  }
}

/** Le share in sola lettura per l'account (E-N9): dall'ultima prova della share (onboarding, Cartelle NAS). */
fun interface ShareAccessView {
  fun readOnly(share: String): Boolean

  companion object {
    val ALL_WRITABLE: ShareAccessView = ShareAccessView { false }
  }
}

/**
 * Campo, gruppo A (D7; Fase 7 §0 punto 11): uno spostamento tra share ha **pubblicato** la copia verificata di un file a [dstPath] di [dstShare] (con il
 * timbro [stamp] letto adesso) e la sorgente [srcPath] di [srcShare] sta per andare nel cestino. Chi lega la porta (`:app`) collega la posizione nuova allo
 * stesso media del catalogo **prima** che la sorgente esca. Mai un'azione sul NAS: solo il catalogo locale.
 *
 * **Fase 7 P4 (D29): unificata con [MoveCompletionSink].** Il motore chiama una porta sola, la porta di completamento ([MoveCompletionSink],
 * vincolante: un errore ferma l'elemento in `PUBLISHED` e si riprova, la sorgente non va mai nel cestino prima che il catalogo conosca la
 * destinazione). Questa forma resta per chi la lega gia' (`CatalogMoveCompletions` di :app, legata in `NasOpsConfig.moves`): [asSink] la fa
 * diventare la porta di completamento, con le **stesse** regole. Prima (campo A) un errore non fermava lo spostamento: adesso lo ferma,
 * finche' il catalogo non risponde (docs/decisioni.md, "Fase 7 P4"). Due porte legate insieme sono un errore ([completionPortOf]): il catalogo
 * non si aggiorna mai due volte in due modi diversi.
 */
fun interface MoveCompletions {
  suspend fun moved(srcShare: String, srcPath: SharePath, dstShare: String, dstPath: SharePath, stamp: NasStamp)

  companion object {
    /** Nessun catalogo (test, banco): il catalogo lo aggiorna la scansione. */
    val NONE: MoveCompletions = MoveCompletions { _, _, _, _, _ -> }
  }
}

/**
 * Fase 7 P4 (D29): [MoveCompletions] come porta di completamento. Stessi argomenti (sorgente, destinazione, timbro letto adesso), stesse regole
 * della porta: chiamata dopo `PUBLISHED` e prima di `SOURCE_RECORDED`, a ogni ripresa; un'eccezione ferma l'elemento e si riprova.
 */
fun MoveCompletions.asSink(): MoveCompletionSink = if (this === MoveCompletions.NONE) {
  NoMoveCompletionSink
} else {
  MoveCompletionSink { _, _, source, dest, stamp, _ -> moved(source.share, source.path, dest.share, dest.path, stamp) }
}

/**
 * Fase 7 P4 (D29): la sola porta di completamento del motore, da quella vera ([sink]) o da quella del campo A ([moves]). Al piu' una delle due
 * puo' essere legata: con due porte lo stesso spostamento aggiornerebbe il catalogo due volte, in due modi.
 */
fun completionPortOf(sink: MoveCompletionSink, moves: MoveCompletions): MoveCompletionSink {
  require(sink === NoMoveCompletionSink || moves === MoveCompletions.NONE) {
    "Una sola porta di completamento: MoveCompletionSink oppure MoveCompletions, non entrambe."
  }
  return if (sink !== NoMoveCompletionSink) sink else moves.asSink()
}
