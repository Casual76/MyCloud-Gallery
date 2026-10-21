package dev.pampa.mycloudgallery.core.models.shelf

import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelShelf
import dev.pampa.mycloudgallery.core.models.contract.ModelShelfGate
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ShelfCopy
import dev.pampa.mycloudgallery.core.models.contract.ShelfDelete
import dev.pampa.mycloudgallery.core.models.contract.ShelfResult
import dev.pampa.mycloudgallery.core.models.contract.ShelfSource
import dev.pampa.mycloudgallery.core.models.contract.ShelfWrite
import dev.pampa.mycloudgallery.core.models.contract.ShelfWriter
import dev.pampa.mycloudgallery.core.models.contract.VerifiedModel
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

// Lo SCAFFALE dei modelli sul NAS (docs/fase6-progetto.md §2.1 "Copia sul NAS", D18, E-A2, F4-D31): il primo dispositivo che ha un
// modello verificato lo pubblica in `<area comune>/.mycloudgallery/devices/<id>/models/<sha256>`; gli altri lo leggono da li' e lo
// verificano (un file falso costa solo un ripiego). Regole:
//  - SOLO la cartella del PROPRIO dispositivo: il percorso nasce da `ownDeviceId` e da un'impronta, mai da un nome letto dal NAS; il
//    fallimento per costruzione di scrivere o togliere altrove (e lo scrittore rifiuta comunque ogni altro percorso);
//  - scrittura ESCLUSIVA, nessuna sostituzione: una copia che c'e' gia' (nostra, o messa da un'altra istanza nello stesso momento) si
//    lascia com'e' (`AlreadyThere`);
//  - solo da un file VERIFICATO adesso (`ModelFiles.open` ricalcola dimensione e SHA-256) e leggendo dallo stesso descrittore: un
//    modello rovinato o sostituito sul telefono non arriva mai sul NAS;
//  - solo se la share e' pubblicabile (cancello di scrittura, F4-D31): sotto un blocco non si scrive ne' si toglie niente;
//  - `pruneOwn` toglie solo le PROPRIE copie, solo con nome da impronta, solo se non sono nell'elenco da tenere e hanno ancora la
//    dimensione vista nell'elenco. Un elenco da tenere vuoto non toglie niente (non puo' essere "l'app non elenca piu' nulla").
// Le copie degli altri dispositivi si leggono (`copies`) e basta.

class NasModelShelf(
  private val ownDeviceId: String,
  private val files: ModelFiles,
  private val reader: NasMediaReader,
  private val writer: ShelfWriter,
  private val gate: ModelShelfGate,
  private val purpose: Purpose = Purpose.BACKGROUND,
) : ModelShelf {

  init {
    require(TrustIds.isValid(ownDeviceId)) { "Id di dispositivo non valido." }
    require(writer.ownerDeviceId == ownDeviceId) { "Lo scrittore dello scaffale e' di un altro dispositivo." }
  }

  override suspend fun publish(spec: ModelSpec, share: String): ShelfResult {
    // Un modello che viaggia nell'APK non serve agli altri sul NAS.
    if (spec.bundledAsset != null) return ShelfResult.Blocked(BUNDLED)
    gate.blockFor(share)?.let { return ShelfResult.Blocked(it) }
    val path = NasLayout.modelCopy(ownDeviceId, spec.sha256.hex)
    when (val stat = reader.stat(share, path, purpose)) {
      is NasResult.Err -> return ShelfResult.Failed
      is NasResult.Ok -> stat.value?.let { return if (it.isDirectory) ShelfResult.Failed else ShelfResult.AlreadyThere }
    }
    val model = files.open(spec.id) ?: return ShelfResult.Failed
    return model.use {
      if (it.spec.sha256 != spec.sha256 || it.spec.sizeBytes != spec.sizeBytes) {
        ShelfResult.Failed
      } else {
        when (writer.publishExclusive(share, path, VerifiedSource(it))) {
          ShelfWrite.Written -> ShelfResult.Published
          ShelfWrite.Exists -> ShelfResult.AlreadyThere
          ShelfWrite.NoSpace, ShelfWrite.NetworkError, is ShelfWrite.Refused -> ShelfResult.Failed
        }
      }
    }
  }

  override suspend fun copies(share: String, sha256: Sha256): List<ShelfCopy> {
    val devices = ArrayList<String>()
    val listed = reader.list(share, NasLayout.ROOT.child("devices"), purpose) { page ->
      for (entry in page) {
        // Solo cartelle con la forma di un id di dispositivo: un nome del NAS non diventa mai un percorso (E-S11).
        if (entry.isDirectory && !entry.isReparsePoint && devices.size < ModelLimits.MAX_NAS_DEVICES && TrustIds.isValid(entry.name)) {
          devices += entry.name
        }
      }
    }
    if (listed is NasResult.Err) return emptyList()
    val out = ArrayList<ShelfCopy>()
    for (device in devices.sorted()) {
      val stat = reader.stat(share, NasLayout.modelCopy(device, sha256.hex), purpose)
      val entry = (stat as? NasResult.Ok)?.value ?: continue
      if (entry.isDirectory || entry.isReparsePoint) continue
      out += ShelfCopy(share, device, sha256, entry.size)
    }
    return out
  }

  override suspend fun pruneOwn(share: String, keep: Set<Sha256>): Int {
    if (keep.isEmpty()) return 0
    if (gate.blockFor(share) != null) return 0
    val own = NasLayout.ROOT.child("devices").child(ownDeviceId).child("models")
    val found = ArrayList<NasEntry>()
    val listed = reader.list(share, own, purpose) { page ->
      for (entry in page) {
        if (!entry.isDirectory && !entry.isReparsePoint && HEX64.matches(entry.name) && found.size < MAX_OWN) found += entry
      }
    }
    if (listed is NasResult.Err) return 0
    var removed = 0
    for (entry in found) {
      if (Sha256(entry.name) in keep) continue
      if (writer.deleteOwn(share, NasLayout.modelCopy(ownDeviceId, entry.name), entry.size) == ShelfDelete.Deleted) removed++
    }
    return removed
  }

  /** I byte del modello verificato, letti per posizione dal descrittore su cui e' stato verificato (altrimenti riaprendo il file). */
  private class VerifiedSource(private val model: VerifiedModel) : ShelfSource {
    override val size: Long = model.spec.sizeBytes

    override fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
      if (offset >= size) return -1
      val want = minOf(len.toLong(), size - offset).toInt()
      val channel = model.channel
      if (channel != null) return channel.read(ByteBuffer.wrap(buf, off, want), offset)
      return try {
        RandomAccessFile(model.file, "r").use {
          it.seek(offset)
          it.read(buf, off, want)
        }
      } catch (e: IOException) {
        -1
      }
    }
  }

  private companion object {
    const val BUNDLED = "bundled"
    const val MAX_OWN = 1024
    val HEX64 = Regex("^[0-9a-f]{64}$")
  }
}
