package dev.pampa.mycloudgallery.feature.integrations.receive.testing

import dev.pampa.mycloudgallery.core.backup.contract.DestinationArea
import dev.pampa.mycloudgallery.core.backup.contract.IncomingOrigin
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStage
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import dev.pampa.mycloudgallery.core.backup.contract.NasDestination
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.integrations.receive.DestinationChoice
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingDestinations
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingFiles
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingQueue
import dev.pampa.mycloudgallery.feature.integrations.receive.NasWait
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

// Fase 5B (P11): i fake per provare la ricezione senza altre app e senza NAS. Mai nella release: `tools/check.ps1` vieta i package `testing` nel dex.

/** Un flusso in arrivo di prova con i suoi byte. */
fun incomingStream(
  bytes: ByteArray,
  origin: IncomingOrigin = IncomingOrigin.CONTENT_OF_ANOTHER_APP,
  displayName: String? = "IMG_1.jpg",
  declaredMime: String? = null,
  declaredSize: Long? = bytes.size.toLong(),
): IncomingStream = IncomingStream(origin, displayName, declaredMime, declaredSize) { ByteArrayInputStream(bytes) }

/** Un flusso che, se qualcuno lo apre, lo segnala: prova che un `file://` o un provider nostro non si apre mai. */
class OpenCounter {
  var opened: Int = 0
    private set

  fun stream(origin: IncomingOrigin, bytes: ByteArray = ByteArray(16)): IncomingStream =
    IncomingStream(origin, "x", null, bytes.size.toLong()) {
      opened++
      ByteArrayInputStream(bytes)
    }
}

/** Le cartelle di prova: una radice privata e una comune, con qualche sottocartella. */
class FakeIncomingDestinations(
  private val roots: List<DestinationChoice> = defaultRoots(),
  private val children: Map<DestinationChoice, List<DestinationChoice>> = emptyMap(),
) : IncomingDestinations {

  var reachable: Boolean = true

  /** Se non e' null, perche' il NAS non risponde (default: offline se `reachable` e' falso). Come `AppIncomingDestinations`: i dati mobili non sono "NAS spento". */
  var wait: NasWait? = null

  /** IN-6: il backup in prova (D12, R2): niente si salva, e si dice prima di copiare. */
  var trialOnly: Boolean = false

  /** Quanto dura la prova del NAS (col NAS spento, fino a 5 s): la schermata non la aspetta piu' prima di dire "In coda". */
  var nasProbeDelayMs: Long = 0

  /** Quante volte e' stata chiesta la prova del NAS. */
  var probes: Int = 0
    private set

  override suspend fun roots(): List<DestinationChoice> = roots

  override suspend fun children(parent: DestinationChoice): List<DestinationChoice> = children[parent].orEmpty()

  override suspend fun nasReachable(): Boolean = reachable

  override suspend fun nasWait(): NasWait {
    probes++
    if (nasProbeDelayMs > 0) kotlinx.coroutines.delay(nasProbeDelayMs)
    return wait ?: if (reachable) NasWait.NONE else NasWait.OFFLINE
  }

  override suspend fun savingBlockedByTrial(): Boolean = trialOnly

  companion object {
    fun choice(name: String, area: DestinationArea = DestinationArea.PRIVATE, folder: String = name): DestinationChoice =
      DestinationChoice(NasDestination("alessio", SharePath.parse(folder), area), name)

    fun defaultRoots(): List<DestinationChoice> =
      listOf(choice("Ricevute", DestinationArea.PRIVATE, "Ricevute"), choice("Famiglia", DestinationArea.COMMON, "Famiglia"))
  }
}

/** Un diario che registra ciò che gli si chiede e risponde come il test ha deciso. */
class FakeIncomingQueue : IncomingQueue {

  var allowed: Boolean = true

  /** Come il diario vero con un file di cui non legge piu' l'impronta (IN-12): salta il primo e non lo mette in coda. */
  var skipOne: Boolean = false
  var failWith: Exception? = null
  val enqueued: MutableList<Pair<NasDestination, List<String>>> = ArrayList()
  var lastStage: IncomingStage? = null

  /** Campo, gruppo A (D1): le condivisioni che il diario cita ancora (le loro copie non si scartano); [citesFails] = il diario non risponde. */
  val cited: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
  var citesFails: Boolean = false

  override suspend fun destinationAllowed(dest: NasDestination): Boolean = allowed

  override suspend fun cites(stage: dev.pampa.mycloudgallery.core.backup.contract.IncomingStageId): Boolean {
    if (citesFails) throw java.io.IOException("diario non disponibile")
    return stage.value in cited
  }

  override suspend fun enqueue(stage: IncomingStage, dest: NasDestination, files: IncomingFiles): Int {
    failWith?.let { throw it }
    lastStage = stage
    // Come farebbe il diario vero: ogni file locale deve esistere ed essere leggibile.
    val names = stage.accepted.mapNotNull { item -> files.fileOf(stage.id, item)?.takeIf { it.isFile }?.let { item.name } }.let { if (skipOne) it.drop(1) else it }
    enqueued += dest to names
    return names.size
  }
}

/** Un flusso che non consegna mai un byte finche' non lo si chiude (un provider ostile che si pianta). */
class HangingStream : InputStream() {
  private val released = java.util.concurrent.CountDownLatch(1)

  @Volatile
  var closed: Boolean = false
    private set

  override fun read(): Int = block()

  override fun read(b: ByteArray, off: Int, len: Int): Int = block()

  private fun block(): Int {
    released.await()
    throw IOException("Flusso chiuso.")
  }

  override fun close() {
    closed = true
    released.countDown()
  }
}
