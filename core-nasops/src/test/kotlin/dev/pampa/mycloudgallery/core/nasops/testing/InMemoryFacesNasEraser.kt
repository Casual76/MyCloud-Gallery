package dev.pampa.mycloudgallery.core.nasops.testing

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.nasops.contract.EraseRoundReport
import dev.pampa.mycloudgallery.core.nasops.contract.FacesCurrent
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseCheck
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseFacts
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseLimits
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseReason
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import dev.pampa.mycloudgallery.core.nasops.contract.FacesGenerations
import dev.pampa.mycloudgallery.core.nasops.contract.FacesNasEraser
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale
import kotlin.time.Duration

/**
 * Il NAS in memoria per i dati dei volti (P0, §5.2, `InMemoryFacesNasEraser`): le share con i loro file (senza distinzione di maiuscole,
 * come Samba), il cestino di rete facoltativo delle aree private ([recycleBin]: un file tolto finisce in `<cestino>/<percorso>`, E-F34),
 * guasti a tempo ([failNext]: le prossime N operazioni falliscono) e il diario di ogni file tolto ([removed]: il banco controlla che sia
 * sempre sotto `faces/`). Le cartelle esistono implicitamente.
 */
class InMemoryFacesNas(val commonShare: String = "test-comune") {
  private val shares = HashMap<String, LinkedHashMap<String, SharePath>>()

  /** Il nome della cartella del cestino di rete delle aree private (es. `.recycle`), o null se spento (oggi e' spento: inventario-nas §1). */
  @Volatile var recycleBin: String? = null

  /** Le prossime N operazioni (elenco o cancellazione) falliscono come un NAS che cade. */
  @Volatile var failNext: Int = 0

  /** Ogni file tolto, nell'ordine: share e percorso. */
  val removed: MutableList<Pair<String, SharePath>> = java.util.Collections.synchronizedList(mutableListOf())

  private fun key(share: String) = share.lowercase(Locale.ROOT)

  private fun pathKey(path: SharePath) = path.value.lowercase(Locale.ROOT)

  @Synchronized
  fun put(share: String, path: SharePath) {
    shares.getOrPut(key(share)) { LinkedHashMap() }[pathKey(path)] = path
  }

  @Synchronized
  fun exists(share: String, path: SharePath): Boolean = shares[key(share)]?.containsKey(pathKey(path)) == true

  /** Tutti i file della share, in ordine. */
  @Synchronized
  fun files(share: String): List<SharePath> = shares[key(share)]?.values?.sortedBy { it.value }.orEmpty()

  /** I file sotto la cartella [dir] (a qualunque profondita'). */
  @Synchronized
  fun filesUnder(share: String, dir: SharePath): List<SharePath> {
    val prefix = pathKey(dir) + "/"
    return shares[key(share)]?.filter { it.key.startsWith(prefix) }?.values?.toList().orEmpty()
  }

  /** I nomi dei figli diretti di [dir]. */
  @Synchronized
  internal fun childNames(share: String, dir: SharePath): List<String> =
    filesUnder(share, dir).map { it.components[dir.components.size] }.distinctBy { it.lowercase(Locale.ROOT) }.sorted()

  /** Toglie [path]: con il cestino acceso lo sposta nel cestino. */
  @Synchronized
  internal fun remove(share: String, path: SharePath) {
    val files = shares[key(share)] ?: return
    if (files.remove(pathKey(path)) == null) return
    removed += share to path
    recycleBin?.takeIf { !share.equals(commonShare, ignoreCase = true) }?.let { bin ->
      val moved = path.components.fold(SharePath.of(bin)) { acc, c -> acc.child(c) }
      files[pathKey(moved)] = moved
    }
  }

  /** Vero se l'operazione deve fallire adesso (consuma un guasto). */
  @Synchronized
  internal fun fails(): Boolean {
    if (failNext <= 0) return false
    failNext--
    return true
  }
}

/** Cio' che chi cancella sa, a mano (P0): la generazione corrente e i dispositivi revocati o persi. */
class FakeFacesEraseFacts(
  @Volatile var current: FacesCurrent = FacesCurrent.Known(null),
  val revoked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
) : FacesEraseFacts {
  override suspend fun current(): FacesCurrent = current

  override suspend fun revokedOrLost(deviceId: String): Boolean = deviceId in revoked
}

/**
 * L'ESECUTORE DI RIFERIMENTO della cancellazione stretta dei volti (P0, D31, §2.8 passi 8-9): le regole del contratto [FacesNasEraser]
 * scritte nel modo piu' semplice, sopra [InMemoryFacesNas]. La stessa suite (`FacesNasEraserContract`) gira sul `FacesNasEraser` vero di P8
 * con il simulatore SMB: stessi file tolti, stessi esiti. [privateShare] e' l'area privata dichiarata dell'account; [gate] e' il cancello
 * di D35 (null = si puo').
 */
class InMemoryFacesNasEraser(
  private val nas: InMemoryFacesNas,
  private val privateShare: String,
  private val facts: FacesEraseFacts,
  private val gate: suspend (String) -> FacesEraseStop? = { null },
  private val maxFilesPerRound: Int = FacesEraseLimits.MAX_FILES_PER_ROUND,
) : FacesNasEraser {

  /** Le chiamate per giro (il banco controlla che una cancellazione bloccata non tocchi il NAS). */
  val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

  private fun isPrivate(share: String): Boolean =
    share.equals(privateShare, ignoreCase = true) && !share.equals(nas.commonShare, ignoreCase = true)

  private suspend fun preflight(share: String): FacesEraseStop? {
    if (!isPrivate(share)) return FacesEraseStop.NOT_PRIVATE_AREA
    return gate(share)
  }

  override suspend fun listGenerations(share: String): FacesGenerations {
    calls += "list"
    preflight(share)?.let { return FacesGenerations.Stopped(it) }
    if (nas.fails()) return FacesGenerations.Stopped(FacesEraseStop.UNREACHABLE)
    val names = nas.childNames(share, NasLayout.FACES_DIR)
    val valid = names.mapNotNull { FaceGeneration.parse(it) }
    val truncated = valid.size > TrustLimits.MAX_LIST_FACES_GENERATIONS
    return FacesGenerations.Listed(valid.take(TrustLimits.MAX_LIST_FACES_GENERATIONS), names.size - valid.size, truncated)
  }

  override suspend fun eraseGeneration(share: String, generation: FaceGeneration, reason: FacesEraseReason, budget: Duration): EraseRoundReport {
    calls += "generation:${reason.code}"
    preflight(share)?.let { return stopped(it) }
    if (reason == FacesEraseReason.DEVICE_REVOKED) return stopped(FacesEraseStop.NOT_ALLOWED)
    when (val current = facts.current()) {
      FacesCurrent.Unknown -> return stopped(FacesEraseStop.UNSURE)
      is FacesCurrent.Known -> if (current.generation == generation) return stopped(FacesEraseStop.NOT_ALLOWED)
    }
    return erase(share, NasLayout.facesGenerationDir(generation))
  }

  override suspend fun eraseDevice(share: String, generation: FaceGeneration, deviceId: String, reason: FacesEraseReason, budget: Duration): EraseRoundReport {
    calls += "device:${reason.code}"
    preflight(share)?.let { return stopped(it) }
    if (!TrustIds.isValid(deviceId)) return stopped(FacesEraseStop.NOT_ALLOWED)
    val current = facts.current()
    if (current == FacesCurrent.Unknown) return stopped(FacesEraseStop.UNSURE)
    val allowed = when (reason) {
      FacesEraseReason.DEVICE_REVOKED -> facts.revokedOrLost(deviceId)
      FacesEraseReason.NOT_CURRENT, FacesEraseReason.USER_CONFIRMED -> (current as FacesCurrent.Known).generation != generation
    }
    if (!allowed) return stopped(FacesEraseStop.NOT_ALLOWED)
    return erase(share, NasLayout.facesDeviceDir(generation, deviceId))
  }

  override suspend fun verify(share: String, generation: FaceGeneration): FacesEraseCheck {
    calls += "verify"
    if (!isPrivate(share)) return FacesEraseCheck.Stopped(FacesEraseStop.NOT_PRIVATE_AREA)
    if (nas.fails()) return FacesEraseCheck.Stopped(FacesEraseStop.UNREACHABLE)
    val present = nas.filesUnder(share, NasLayout.facesGenerationDir(generation)).isNotEmpty()
    val bin = FacesEraseLimits.RECYCLE_BIN_DIRS.any { name ->
      nas.filesUnder(share, NasLayout.FACES_DIR.components.fold(SharePath.of(name)) { acc, c -> acc.child(c) }).isNotEmpty()
    }
    return FacesEraseCheck.Checked(present, bin)
  }

  /** Dal fondo, file per file, poi le cartelle rimaste vuote; al piu' [maxFilesPerRound] file. */
  private fun erase(share: String, target: SharePath): EraseRoundReport {
    val files = nas.filesUnder(share, target).sortedWith(compareByDescending<SharePath> { it.components.size }.thenBy { it.value })
    val dirsBefore = dirsUnder(files, target)
    var removed = 0
    for (file in files) {
      if (removed >= maxFilesPerRound) break
      if (nas.fails()) return report(share, target, removed, dirsBefore, FacesEraseStop.UNREACHABLE)
      nas.remove(share, file)
      removed++
    }
    val left = nas.filesUnder(share, target).size
    return report(share, target, removed, dirsBefore, if (left == 0) FacesEraseStop.DONE else FacesEraseStop.BUDGET)
  }

  private fun report(share: String, target: SharePath, removed: Int, dirsBefore: Set<String>, stop: FacesEraseStop): EraseRoundReport {
    val remaining = nas.filesUnder(share, target)
    val dirsAfter = dirsUnder(remaining, target)
    return EraseRoundReport(removed, (dirsBefore - dirsAfter).size, remaining.size, stop)
  }

  /** Le cartelle (dal bersaglio compreso in giu') che contengono almeno uno dei [files]. */
  private fun dirsUnder(files: List<SharePath>, target: SharePath): Set<String> {
    val out = HashSet<String>()
    for (file in files) {
      for (depth in target.components.size until file.components.size) out += file.components.take(depth).joinToString("/").lowercase(Locale.ROOT)
    }
    return out
  }

  private fun stopped(stop: FacesEraseStop) = EraseRoundReport(0, 0, null, stop)
}
