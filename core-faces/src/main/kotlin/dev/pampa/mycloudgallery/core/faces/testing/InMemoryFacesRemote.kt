package dev.pampa.mycloudgallery.core.faces.testing

import dev.pampa.mycloudgallery.core.faces.contract.FacesListing
import dev.pampa.mycloudgallery.core.faces.contract.FacesPut
import dev.pampa.mycloudgallery.core.faces.contract.FacesRead
import dev.pampa.mycloudgallery.core.faces.contract.FacesRemote
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale

/**
 * L'area privata di un account in memoria, per i soli file dei volti (P0, §5.2): i file per percorso (senza distinzione di maiuscole, come
 * Samba), condivisa fra i dispositivi finti dell'account; [put] scrive come un PC o un attaccante con la password (spazzatura, cartelle di
 * altri account, file con nomi strani); [failNext] fa fallire le prossime N operazioni; [writes] registra ogni scrittura di un dispositivo
 * (la prova "mai fuori da `faces/<G>/<io>/`"). Se [share] e' l'area comune ([commonShare]) nessun dispositivo ci scrive (L23).
 */
class InMemoryFacesArea(val share: String, val commonShare: String = "test-comune") {
  private val files = LinkedHashMap<String, Pair<SharePath, ByteArray>>()

  @Volatile var failNext: Int = 0

  /** Ogni scrittura di un dispositivo: chi e dove. */
  val writes: MutableList<Pair<String, SharePath>> = java.util.Collections.synchronizedList(mutableListOf())

  val isCommon: Boolean get() = share.equals(commonShare, ignoreCase = true)

  private fun key(path: SharePath) = path.value.lowercase(Locale.ROOT)

  @Synchronized
  fun put(path: SharePath, bytes: ByteArray) {
    files[key(path)] = path to bytes.copyOf()
  }

  @Synchronized
  fun exists(path: SharePath): Boolean = key(path) in files

  @Synchronized
  fun paths(): List<SharePath> = files.values.map { it.first }.sortedBy { it.value }

  @Synchronized
  internal fun read(path: SharePath): ByteArray? = files[key(path)]?.second?.copyOf()

  @Synchronized
  internal fun names(dir: SharePath): List<String> {
    val prefix = key(dir) + "/"
    return files.filter { it.key.startsWith(prefix) }.values.map { it.first.components[dir.components.size] }
      .distinctBy { it.lowercase(Locale.ROOT) }.sorted()
  }

  /** Scrive solo se il nome non c'e' (mai sovrascrivere): vero se ha creato. */
  @Synchronized
  internal fun create(device: String, path: SharePath, bytes: ByteArray): Boolean {
    if (key(path) in files) return false
    files[key(path)] = path to bytes.copyOf()
    writes += device to path
    return true
  }

  @Synchronized
  internal fun fails(): Boolean {
    if (failNext <= 0) return false
    failNext--
    return true
  }
}

/**
 * L'ESECUTORE DI RIFERIMENTO DEL NAS DEI VOLTI (docs/fase8-progetto.md §5.2 `InMemoryFacesRemote`): il contratto [FacesRemote] sopra
 * [InMemoryFacesArea], con i percorsi di `NasLayout`. Scrive **solo** sotto `faces/<G>/<[deviceId]>/` e solo se il nome non c'e'; nell'area
 * comune non fa niente. Il pubblicatore e la replica di P3 ci lavorano sopra prima del NAS finto vero; la stessa suite (`FacesRemoteContract`)
 * la passa la porta vera di sync/ sopra `NasFileSystem`.
 */
class InMemoryFacesRemote(private val area: InMemoryFacesArea, override val deviceId: String) : FacesRemote {

  init {
    require(TrustIds.isValid(deviceId)) { "Id di dispositivo non valido." }
  }

  override val share: String get() = area.share

  private val denied = RemoteResult.Failed(RemoteFailure.NOT_ALLOWED)
  private val down = RemoteResult.Failed(RemoteFailure.UNREACHABLE)

  private inline fun <T> call(block: () -> RemoteResult<T>): RemoteResult<T> = when {
    area.isCommon -> denied
    area.fails() -> down
    else -> block()
  }

  override suspend fun generations(): RemoteResult<FacesListing<FaceGeneration>> = call {
    val names = area.names(NasLayout.FACES_DIR)
    val valid = names.mapNotNull { FaceGeneration.parse(it) }
    RemoteResult.Ok(FacesListing(valid, names.size - valid.size, truncated = false))
  }

  override suspend fun devices(generation: FaceGeneration): RemoteResult<FacesListing<String>> = call {
    val names = area.names(NasLayout.facesGenerationDir(generation))
    val valid = names.filter { TrustIds.isValid(it) }
    val ignored = names.count { !TrustIds.isValid(it) && it != NasLayout.FACES_LEASES }
    RemoteResult.Ok(FacesListing(valid, ignored, truncated = false))
  }

  override suspend fun headNumbers(generation: FaceGeneration, deviceId: String): RemoteResult<FacesListing<Long>> = call {
    if (!TrustIds.isValid(deviceId)) return@call denied
    val names = area.names(NasLayout.facesHeadDir(generation, deviceId))
    val valid = names.mapNotNull { NasLayout.parseVersionName(it) }.sorted()
    RemoteResult.Ok(FacesListing(valid, names.size - valid.size, truncated = false))
  }

  override suspend fun readHead(generation: FaceGeneration, deviceId: String, version: Long, maxBytes: Int): RemoteResult<FacesRead> = call {
    if (!TrustIds.isValid(deviceId) || version < 1) return@call denied
    RemoteResult.Ok(readCapped(NasLayout.facesHead(generation, deviceId, version), maxBytes))
  }

  override suspend fun readSegment(generation: FaceGeneration, deviceId: String, hash: String, maxBytes: Int): RemoteResult<FacesRead> = call {
    if (!TrustIds.isValid(deviceId) || !Hashes.isHashName(hash)) return@call denied
    RemoteResult.Ok(readCapped(NasLayout.facesSegment(generation, deviceId, hash), maxBytes))
  }

  override suspend fun publishSegment(generation: FaceGeneration, hash: String, bytes: ByteArray): RemoteResult<FacesPut> = call {
    if (!Hashes.isHashName(hash)) return@call denied
    put(NasLayout.facesSegment(generation, deviceId, hash), bytes)
  }

  override suspend fun publishHead(generation: FaceGeneration, version: Long, bytes: ByteArray): RemoteResult<FacesPut> = call {
    if (version < 1) return@call denied
    put(NasLayout.facesHead(generation, deviceId, version), bytes)
  }

  private fun put(path: SharePath, bytes: ByteArray): RemoteResult<FacesPut> =
    RemoteResult.Ok(if (area.create(deviceId, path, bytes)) FacesPut.CREATED else FacesPut.ALREADY_EXISTS)

  private fun readCapped(path: SharePath, maxBytes: Int): FacesRead {
    val bytes = area.read(path) ?: return FacesRead.NotFound
    return if (bytes.size > maxBytes) FacesRead.TooLarge else FacesRead.Data(bytes)
  }
}
