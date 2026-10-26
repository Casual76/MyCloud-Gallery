package dev.pampa.mycloudgallery.core.nas.ops.models

import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.SmbNasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Fase 6, revisione B (MO-4 e I5 della revisione di sicurezza): la SCRITTURA A FLUSSO della copia di un modello sul NAS (D18). Un modello
// pesa fino a 370 MiB e `NasFileSystem.publishExclusive` prende un `ByteArray`: qui si scrive a blocchi da 1 MiB, nello stile delle altre
// scritture di core-nas:
//
//  1. un temporaneo **invisibile** (`.mycloudgallery/tmp/<io>-md-<casuale>`: cartella col punto, nessuna estensione, riconosciuto come
//     temporaneo proprio dalla pulizia dei propri file, che dopo un giorno toglie i resti di una morte del processo);
//  2. i byte a blocchi, ognuno con la sessione, il cancello di rete, la recinzione D33 delle share di prova e il sorvegliante di sempre
//     (`SmbNasFileOps`, `runOp`): niente qui aggira il gestore della sessione;
//  3. **verifica** sullo stesso handle: la lunghezza del temporaneo deve essere quella dei byte consegnati dalla sorgente;
//  4. il **nome definitivo** con una rinomina **senza sostituire** (come `publishExclusive`): un nome occupato e' `Exists`, nessun byte
//     di nessuno e' sovrascritto.
//
// **Solo la propria cartella, solo la copia di un modello.** Il percorso nasce da `NasLayout.modelCopy(<io>, <impronta>)`, mai da un nome
// letto dal NAS; non c'e' un metodo che scriva un percorso qualunque ne' che tolga un file qualunque. Non c'e' un marcatore di adesione perche'
// non e' una primitiva generale: la cartella `models/` del proprio dispositivo e' l'unico posto raggiungibile (`Phase6ArchitectureTest` e il
// test di questo modulo fissano l'insieme dei metodi). Il cancello di scrittura F4-D31 (share vere in sola lettura: niente si scrive) lo
// controlla chi chiama (`NasModelShelf.publish`, `pruneOwn`) prima di ogni chiamata.

/** I byte da copiare, letti **per posizione** dal file gia' verificato (nessuno spostamento condiviso). */
interface NasModelCopySource {
  val size: Long

  /** Legge al piu' [len] byte da [offset]; `-1` oltre la fine. Bloccante: gira fuori dal thread principale. */
  fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int
}

/** L'esito di [NasModelShelfOps.publishCopy]. */
sealed interface ModelCopyWrite {
  /** La copia c'e' con tutti i suoi byte (temporaneo scritto, lunghezza verificata, rinomina senza sostituire). */
  data object Written : ModelCopyWrite

  /** Il nome esisteva gia': nessun byte e' stato sovrascritto e il temporaneo e' stato tolto. */
  data object Exists : ModelCopyWrite
}

/** L'esito di [NasModelShelfOps.deleteOwnCopy]. */
sealed interface ModelCopyDelete {
  data object Deleted : ModelCopyDelete

  data object Missing : ModelCopyDelete

  /** Il file non ha piu' la dimensione vista dall'elenco: non si tocca. */
  data object SizeChanged : ModelCopyDelete

  data object NotAFile : ModelCopyDelete

  /** Aperto da un altro programma: non adesso. */
  data object Locked : ModelCopyDelete
}

/**
 * Le due cose che si possono fare, per UN dispositivo ([ownerDeviceId]), nella sua cartella `devices/<id>/models/` dell'area comune.
 * Non lancia mai per un errore del NAS: [NasResult].
 */
interface NasModelShelfOps {
  val ownerDeviceId: String

  /**
   * Scrive [source] come `devices/<ownerDeviceId>/models/<sha256>` di [share]: temporaneo invisibile, lunghezza verificata, rinomina senza
   * sostituire. Annullabile fra un blocco e l'altro (il temporaneo parziale si toglie). [sha256] e' esadecimale minuscolo, 64 cifre.
   */
  suspend fun publishCopy(share: String, sha256: String, source: NasModelCopySource, purpose: Purpose): NasResult<ModelCopyWrite>

  /** Toglie la **propria** copia [sha256] solo se ha ancora la dimensione [size] (letta dall'elenco), sullo stesso handle. */
  suspend fun deleteOwnCopy(share: String, sha256: String, size: Long, purpose: Purpose): NasResult<ModelCopyDelete>
}

/** Nessun NAS (o una sessione che non e' SMB: un fake): niente si scrive, ogni chiamata e' negata prima di toccare la rete. */
class NoNasModelShelfOps(override val ownerDeviceId: String = "") : NasModelShelfOps {
  private val notConfigured = NasError.NotAllowedHere(DenyReason.NOT_CONFIGURED)

  override suspend fun publishCopy(share: String, sha256: String, source: NasModelCopySource, purpose: Purpose): NasResult<ModelCopyWrite> =
    NasResult.Err(notConfigured)

  override suspend fun deleteOwnCopy(share: String, sha256: String, size: Long, purpose: Purpose): NasResult<ModelCopyDelete> =
    NasResult.Err(notConfigured)
}

/** Chi consegna le primitive dello scaffale sopra una sessione vera (le chiama `:app`, che passa la sessione e riceve questo). */
object NasModelShelfOpsFactory {
  fun create(session: NasSessionManager, ownerDeviceId: String): NasModelShelfOps = when (session) {
    is SmbNasSessionManager -> SmbNasModelShelfOps(SmbNasFileOps(session, ownerDeviceId))
    else -> NoNasModelShelfOps(ownerDeviceId)
  }
}

internal class SmbNasModelShelfOps(
  private val ops: SmbNasFileOps,
  private val random: SecureRandom = SecureRandom(),
) : NasModelShelfOps {

  init {
    require(TrustIds.isValid(ops.ownerDeviceId)) { "Il dispositivo proprietario e' un id valido." }
  }

  override val ownerDeviceId: String get() = ops.ownerDeviceId

  override suspend fun publishCopy(share: String, sha256: String, source: NasModelCopySource, purpose: Purpose): NasResult<ModelCopyWrite> {
    // Il nome definitivo nasce solo da qui: la propria cartella, un'impronta.
    val final = NasLayout.modelCopy(ownerDeviceId, sha256)
    require(source.size in 1..MAX_COPY_BYTES) { "Dimensione di una copia non ammessa." }
    val temp = tempName()
    val writer = when (val opened = ops.openTemp(share, temp, purpose)) {
      is NasResult.Err -> return opened
      is NasResult.Ok -> opened.value
    }
    // Il temporaneo si scrive e si verifica con lo scrittore aperto; solo dopo, chiuso, si rinomina (come il caricamento del backup).
    val written = try {
      writer.use { pump(it, source) }
    } catch (c: kotlinx.coroutines.CancellationException) {
      // Annullati a meta': il temporaneo parziale non serve a nessuno. Non si aspetta niente (siamo annullati): lo toglie il giro dopo o la pulizia.
      withContext(kotlinx.coroutines.NonCancellable) { dropTemp(share, temp, purpose) }
      throw c
    }
    if (written is NasResult.Err) {
      dropTemp(share, temp, purpose)
      return written
    }
    return when (val published = ops.publishAppTemp(share, temp, final, purpose)) {
      is NasResult.Err -> {
        // La risposta puo' essersi persa dopo la rinomina: togliere il temporaneo non fa male (se non c'e' piu' e' `Missing`).
        dropTemp(share, temp, purpose)
        published
      }
      is NasResult.Ok -> when (published.value) {
        is PublishOutcome.Published -> NasResult.Ok(ModelCopyWrite.Written)
        PublishOutcome.Collision -> {
          dropTemp(share, temp, purpose)
          NasResult.Ok(ModelCopyWrite.Exists)
        }
        PublishOutcome.Locked -> {
          dropTemp(share, temp, purpose)
          NasResult.Err(NasError.FileBusy)
        }
        PublishOutcome.TempMissing -> NasResult.Err(NasError.Unexpected(null))
      }
    }
  }

  override suspend fun deleteOwnCopy(share: String, sha256: String, size: Long, purpose: Purpose): NasResult<ModelCopyDelete> {
    val path = NasLayout.modelCopy(ownerDeviceId, sha256)
    return when (val r = ops.deleteIfUnchanged(share, path, size, purpose)) {
      is NasResult.Err -> r
      is NasResult.Ok -> NasResult.Ok(
        when (r.value) {
          DeleteOutcome.Deleted -> ModelCopyDelete.Deleted
          DeleteOutcome.Missing -> ModelCopyDelete.Missing
          is DeleteOutcome.SizeChanged -> ModelCopyDelete.SizeChanged
          DeleteOutcome.NotAFile -> ModelCopyDelete.NotAFile
          DeleteOutcome.Locked -> ModelCopyDelete.Locked
        },
      )
    }
  }

  /** I byte a blocchi da 1 MiB, poi la verifica della lunghezza sullo stesso handle. */
  private suspend fun pump(writer: NasTempWriter, source: NasModelCopySource): NasResult<Unit> {
    val buffer = ByteArray(BLOCK_BYTES)
    var offset = 0L
    while (offset < source.size) {
      currentCoroutineContext().ensureActive()
      val want = minOf(BLOCK_BYTES.toLong(), source.size - offset).toInt()
      var filled = 0
      // Una lettura puo' tornare con meno: si ripete fino al blocco intero. Meno byte della dimensione dichiarata = sorgente rotta.
      while (filled < want) {
        val n = withContext(Dispatchers.IO) { source.read(offset + filled, buffer, filled, want - filled) }
        if (n <= 0) return NasResult.Err(NasError.Unexpected(null))
        filled += n
      }
      when (val w = writer.write(offset, buffer, 0, want)) {
        is NasResult.Err -> return w
        is NasResult.Ok -> offset += want
      }
    }
    return when (val f = writer.finish(source.size)) {
      is NasResult.Err -> f
      is NasResult.Ok -> if (f.value is TempFinish.Complete) NasResult.Ok(Unit) else NasResult.Err(NasError.Unexpected(null))
    }
  }

  private suspend fun dropTemp(share: String, temp: SharePath, purpose: Purpose) {
    // Best effort: un temporaneo che resta lo toglie la pulizia dei propri file dopo un giorno.
    runCatching { ops.deleteOwnTemp(share, temp, purpose) }
  }

  /** `.mycloudgallery/tmp/<dispositivo>-md-<32 cifre esadecimali>`: un temporaneo proprio ([OwnFile.Temp]), distinto da quelli del backup e delle operazioni. */
  private fun tempName(): SharePath {
    val bytes = ByteArray(16).also(random::nextBytes)
    val token = bytes.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    return NasLayout.ROOT.child("tmp").child("$ownerDeviceId-md-$token")
  }

  private companion object {
    const val BLOCK_BYTES = 1 shl 20

    /** Nessun modello del catalogo supera questo: un tetto alla dimensione dichiarata dalla sorgente. */
    const val MAX_COPY_BYTES = 1024L * 1024 * 1024
  }
}
