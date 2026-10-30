package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.faces.contract.FacesListing
import dev.pampa.mycloudgallery.core.faces.contract.FacesPut
import dev.pampa.mycloudgallery.core.faces.contract.FacesRead
import dev.pampa.mycloudgallery.core.faces.contract.FacesRemote
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * IL NAS DEI VOLTI VERO (Fase 8, P3; docs/fase8-progetto.md §2.4, D11, D12, L23): il contratto [FacesRemote] sopra la share privata
 * dell'account ([fs], `NasFileSystem` di core-trust: solo `list`, `read` e `publishExclusive`, nessuna cancellazione, nessuna sovrascrittura).
 * E' l'unico punto di core-faces che tocca il NAS (Phase8ArchitectureTest: `NasFileSystem` solo in sync/) e passa la suite di P0
 * (`FacesRemoteContract`).
 *
 * - Scrive **solo** sotto `faces/<G>/<[deviceId]>/`: i percorsi li costruisce `NasLayout` da valori validati, la firma della porta non permette
 *   altro, e la sola scrittura e' `publishExclusive` (un nome occupato resta com'e').
 * - **Mai nell'area comune**: se [fs] e' l'area comune del registro accettato (o il registro non c'e'), ogni chiamata e' `NOT_ALLOWED` e nessuna
 *   richiesta parte (L23, E-F42).
 * - Elenchi con i tetti del formato (§7), letture con il tetto chiesto e mai oltre quello del tipo; un guasto e' un esito, mai un'eccezione.
 */
class NasFacesRemote(
  private val fs: NasFileSystem,
  override val deviceId: String,
  /** L'area comune del registro accettato (null = famiglia non pronta: niente si legge ne' si scrive). */
  private val commonShare: () -> String?,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) : FacesRemote {

  init {
    require(TrustIds.isValid(deviceId)) { "Id di dispositivo non valido." }
  }

  override val share: String get() = fs.shareName

  private val denied = RemoteResult.Failed(RemoteFailure.NOT_ALLOWED)

  private suspend fun <T> call(block: () -> RemoteResult<T>): RemoteResult<T> {
    val common = commonShare() ?: return denied
    if (common.equals(fs.shareName, ignoreCase = true)) return denied
    return try {
      withContext(io) { block() }
    } catch (e: IOException) {
      RemoteResult.Failed(RemoteFailure.UNREACHABLE)
    }
  }

  override suspend fun generations(): RemoteResult<FacesListing<FaceGeneration>> = call {
    val listing = fs.list(NasLayout.FACES_DIR, TrustLimits.MAX_LIST_FACES_GENERATIONS)
    val valid = listing.names.mapNotNull(FaceGeneration::parse).distinct()
    RemoteResult.Ok(FacesListing(valid, listing.names.size - valid.size, listing.truncated))
  }

  override suspend fun devices(generation: FaceGeneration): RemoteResult<FacesListing<String>> = call {
    // Il registro ha al piu' 256 dispositivi; in piu' la cartella delle prenotazioni. Oltre, l'elenco e' "inondato" (chi legge prende i
    // dispositivi dell'account dal registro).
    val listing = fs.list(NasLayout.facesGenerationDir(generation), TrustLimits.MAX_DEVICES + 1)
    val valid = listing.names.filter(TrustIds::isValid).distinct()
    val ignored = listing.names.count { !TrustIds.isValid(it) && it != NasLayout.FACES_LEASES }
    RemoteResult.Ok(FacesListing(valid, ignored, listing.truncated))
  }

  override suspend fun headNumbers(generation: FaceGeneration, deviceId: String): RemoteResult<FacesListing<Long>> {
    if (!TrustIds.isValid(deviceId)) return denied
    return call {
      val listing = fs.list(NasLayout.facesHeadDir(generation, deviceId), TrustLimits.MAX_LIST_HEADS)
      val valid = listing.names.mapNotNull(NasLayout::parseVersionName).distinct().sorted()
      RemoteResult.Ok(FacesListing(valid, listing.names.size - valid.size, listing.truncated))
    }
  }

  override suspend fun readHead(generation: FaceGeneration, deviceId: String, version: Long, maxBytes: Int): RemoteResult<FacesRead> {
    if (!TrustIds.isValid(deviceId) || version !in 1..TrustLimits.MAX_VERSION || maxBytes < 0) return denied
    return call { RemoteResult.Ok(read(NasLayout.facesHead(generation, deviceId, version), minOf(maxBytes, DocType.FACES_HEAD.maxFileBytes))) }
  }

  override suspend fun readSegment(generation: FaceGeneration, deviceId: String, hash: String, maxBytes: Int): RemoteResult<FacesRead> {
    if (!TrustIds.isValid(deviceId) || !Hashes.isHashName(hash) || maxBytes < 0) return denied
    return call { RemoteResult.Ok(read(NasLayout.facesSegment(generation, deviceId, hash), minOf(maxBytes, TrustLimits.MAX_FACES_SEGMENT_BYTES))) }
  }

  override suspend fun publishSegment(generation: FaceGeneration, hash: String, bytes: ByteArray): RemoteResult<FacesPut> {
    if (!Hashes.isHashName(hash) || bytes.size > TrustLimits.MAX_FACES_SEGMENT_BYTES) return denied
    return call { RemoteResult.Ok(put(NasLayout.facesSegment(generation, deviceId, hash), bytes)) }
  }

  override suspend fun segmentNames(generation: FaceGeneration): RemoteResult<FacesListing<String>> = call {
    val listing = fs.list(NasLayout.facesSegmentDir(generation, deviceId), MAX_SEGMENT_NAMES)
    val valid = listing.names.filter(Hashes::isHashName).distinct()
    RemoteResult.Ok(FacesListing(valid, listing.names.size - valid.size, listing.truncated))
  }

  override suspend fun publishHead(generation: FaceGeneration, version: Long, bytes: ByteArray): RemoteResult<FacesPut> {
    if (version !in 1..TrustLimits.MAX_VERSION || bytes.size > DocType.FACES_HEAD.maxFileBytes) return denied
    return call { RemoteResult.Ok(put(NasLayout.facesHead(generation, deviceId, version), bytes)) }
  }

  /** La sola scrittura: un nome nuovo, mai sopra uno che c'e' (§2.3). Il percorso e' sempre sotto `faces/<G>/<deviceId>/`. */
  private fun put(path: SharePath, bytes: ByteArray): FacesPut = when (fs.publishExclusive(path, bytes)) {
    PublishResult.CREATED -> FacesPut.CREATED
    PublishResult.ALREADY_EXISTS -> FacesPut.ALREADY_EXISTS
  }

  private fun read(path: SharePath, maxBytes: Int): FacesRead = when (val read = fs.read(path, maxBytes)) {
    is NasRead.Data -> FacesRead.Data(read.bytes)
    NasRead.NotFound -> FacesRead.NotFound
    NasRead.TooLarge -> FacesRead.TooLarge
  }

  override fun toString(): String = "NasFacesRemote"

  private companion object {
    /** R7: i propri segmenti elencati al piu' (le ultime teste e il diario della pulizia ne tengono qualche volta [TrustLimits.MAX_HEAD_SEGMENTS]). */
    const val MAX_SEGMENT_NAMES: Int = 4 * TrustLimits.MAX_HEAD_SEGMENTS
  }
}
