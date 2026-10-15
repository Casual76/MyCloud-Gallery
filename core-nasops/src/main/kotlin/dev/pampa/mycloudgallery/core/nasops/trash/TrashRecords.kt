package dev.pampa.mycloudgallery.core.nasops.trash

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.engine.RecordWrite
import dev.pampa.mycloudgallery.core.nasops.engine.TrashRecordDraft
import dev.pampa.mycloudgallery.core.nasops.engine.TrashRecordWriter
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.TrashRecord
import dev.pampa.mycloudgallery.core.trust.data.TrashRecordReason
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Fase 4B, P12 (formato-nas.md §5.7, M5; D25): i record FIRMATI del cestino. Si scrivono per mostrare l'elemento agli altri dispositivi
// ("Eliminati di recente") e per sapere dove ripristinarlo; **non decidono mai una cancellazione** (§9): la pulizia legge solo il diario
// locale di chi ha cestinato. Si leggono dal NAS solo per mostrare.

/** L'identita' con cui firmare: famiglia e chiave del dispositivo (null finche' la famiglia non e' pronta). */
class TrashSigningIdentity(val familyId: String, val signer: Signer) {
  override fun toString(): String = "TrashSigningIdentity"
}

/**
 * Pubblica il record firmato in `trash/<io>/rec/<id>` della share con `publishExclusive` ("esiste gia'" = gia' scritto da un giro
 * precedente: va bene). [identity] null = famiglia non pronta: nessun record, l'operazione prosegue con il solo diario locale.
 */
class SignedTrashRecords(
  private val deviceId: String,
  private val identity: () -> TrashSigningIdentity?,
  private val fileSystem: (String) -> NasFileSystem,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) : TrashRecordWriter {

  init {
    require(TrustIds.isValid(deviceId)) { "Id di dispositivo non valido." }
  }

  override suspend fun publish(draft: TrashRecordDraft): RecordWrite {
    val id = identity() ?: return RecordWrite.Unavailable
    if (TrustIds.deviceIdOf(id.signer.publicKey) != deviceId) return RecordWrite.Unavailable
    val bytes = try {
      TrashRecord(
        familyId = id.familyId,
        deviceId = deviceId,
        share = draft.share,
        trashId = draft.trashId,
        originalPath = draft.originalPath,
        size = draft.size,
        trashedAt = draft.trashedAtNas,
        reason = if (draft.reason == TrashReason.MOVE) TrashRecordReason.MOVE else TrashRecordReason.DELETE,
        folder = draft.isFolder,
        // Il formato vuole il conteggio per ogni cartella: se non si e' potuto contare, 0 ("non so" per chi lo mostra).
        count = if (draft.isFolder) draft.fileCount ?: 0 else null,
      ).sign(id.signer)
    } catch (_: TrustViolation) {
      // Un campo fuori formato (non succede con i dati del diario): niente record, l'elemento resta "senza record valido".
      return RecordWrite.Unavailable
    }
    return try {
      withContext(io) { fileSystem(draft.share).publishExclusive(NasLayout.trashRecordFile(deviceId, draft.trashId), bytes) }
      RecordWrite.Written
    } catch (_: IOException) {
      RecordWrite.Retry
    }
  }
}

/** Un elemento trovato nel cestino di una share, come lo mostra "Eliminati di recente": il record valido, o null se manca o non vale. */
class RemoteTrashEntry(val share: String, val ownerDeviceId: String, val trashId: String, val isFolder: Boolean, val record: TrashRecord?) {
  override fun toString(): String = "RemoteTrashEntry(valid=${record != null})"
}

sealed interface RemoteTrashRead {
  data class Entries(val entries: List<RemoteTrashEntry>, val truncated: Boolean) : RemoteTrashRead

  data object Unreachable : RemoteTrashRead
}

/** Chi legge il cestino di una share (solo per mostrare). */
fun interface RemoteTrashSource {
  suspend fun read(share: String): RemoteTrashRead

  companion object {
    val NONE: RemoteTrashSource = RemoteTrashSource { RemoteTrashRead.Entries(emptyList(), truncated = false) }
  }
}

/**
 * Legge `trash/<dispositivo>/files/<id>` di una share (gli elementi che ci sono davvero) e, per ognuno, il record firmato
 * `trash/<dispositivo>/rec/<id>` verificato con il registro accettato ([TrashRecord.verify]: firmatario approvato, famiglia, posto,
 * percorso di media validato). Nomi non validi ignorati (formato §2.4); tetti per share. **Niente di cio' che si legge qui arriva a
 * un'azione** se non come voce che l'utente tocca (ripristino, con la sua conferma).
 */
class SignedTrashRecordSource(
  private val fileSystem: (String) -> NasFileSystem,
  private val registry: () -> RegistryState?,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val maxEntries: Int = MAX_ENTRIES_PER_SHARE,
) : RemoteTrashSource {

  override suspend fun read(share: String): RemoteTrashRead = withContext(io) {
    val fs = fileSystem(share)
    val state = registry()
    try {
      val trashDir = NasLayout.ROOT.child("trash")
      val devices = fs.list(trashDir, MAX_DEVICES)
      val out = ArrayList<RemoteTrashEntry>()
      var truncated = devices.truncated
      for (device in devices.names.filter(TrustIds::isValid)) {
        val filesDir = trashDir.child(device).child("files")
        val listing = fs.list(filesDir, maxEntries - out.size + 1)
        if (listing.truncated) truncated = true
        for (trashId in listing.names.filter(TrustIds::isValid)) {
          if (out.size >= maxEntries) {
            truncated = true
            break
          }
          val record = record(fs, state, share, device, trashId)
          // Senza record valido: una cartella cestinata si riconosce dai figli (un file ha un elenco vuoto).
          val isFolder = record?.folder ?: fs.list(filesDir.child(trashId), 1).names.isNotEmpty()
          out += RemoteTrashEntry(share, device, trashId, isFolder, record)
        }
      }
      RemoteTrashRead.Entries(out, truncated)
    } catch (_: NasIoException) {
      RemoteTrashRead.Unreachable
    } catch (_: IOException) {
      RemoteTrashRead.Unreachable
    }
  }

  private fun record(fs: NasFileSystem, state: RegistryState?, share: String, device: String, trashId: String): TrashRecord? {
    state ?: return null
    val read = fs.read(NasLayout.trashRecordFile(device, trashId), DocType.TRASH_RECORD.maxFileBytes)
    val bytes = (read as? NasRead.Data)?.bytes ?: return null
    return when (val verdict = TrashRecord.verify(bytes, state, fs.shareName, device, trashId)) {
      is Verdict.Accepted -> verdict.value.takeIf { it.share.equals(share, ignoreCase = true) }
      is Verdict.Rejected -> {
        GalleryLog.d(TAG) { "record del cestino rifiutato: ${verdict.reason}" }
        null
      }
    }
  }

  private companion object {
    const val TAG = "NasTrash"
    const val MAX_DEVICES = 256
    const val MAX_ENTRIES_PER_SHARE = 5_000
  }
}

/** Il percorso del file nel cestino di [owner] per [trashId] (derivato, mai letto). */
internal fun trashedPath(owner: String, trashId: String): SharePath = NasLayout.trashItemFile(owner, trashId)
