package dev.pampa.mycloudgallery.core.models.chaos

import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import dev.pampa.mycloudgallery.core.models.contract.ShelfDelete
import dev.pampa.mycloudgallery.core.models.contract.ShelfSource
import dev.pampa.mycloudgallery.core.models.contract.ShelfWrite
import dev.pampa.mycloudgallery.core.models.contract.ShelfWriter
import dev.pampa.mycloudgallery.core.models.testing.InMemoryShelfWriter
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

// Il NAS del giro, visto dal banco. Due facce con la stessa interfaccia: in memoria (`InMemoryNasMedia`, con `FaultPlan` sulle letture)
// e dietro il simulatore SMB con la sessione e la lettura vere (`ModelChaosSmb.kt`). Il lettore che l'app vede e' sempre avvolto da
// [WatchedReader]: registra da dove si legge, per l'invariante 3 ("riprendi dal blocco, mai da capo").

/** Il NAS visto dal banco: cio' che vede l'app ([reader]) e cio' che fanno "il PC" e "gli altri dispositivi" ([put], [change]). */
interface ChaosNasSide : Closeable {
  val share: String

  /** Il lettore con cui l'app legge (avvolto da [WatchedReader]). */
  val reader: NasMediaReader

  /** Un file messo da fuori (il PC, un altro dispositivo): il banco lo ricorda, cosi' sa cosa NON e' scritto dall'app. */
  fun put(path: SharePath, bytes: ByteArray)

  /** Cambia il contenuto di un file da fuori (byte diversi). */
  fun change(path: SharePath, bytes: ByteArray)

  /** Letture che cadono (connessione persa) con questa probabilita' percentuale, prima o dopo la risposta. */
  fun armDrops(seed: Long, percent: Int)

  /** Alla lettura numero [n] di un blocco il file di [path] cambia in [bytes]. */
  fun armChangeAtRead(n: Int, path: SharePath, bytes: ByteArray)

  /** La rete torna: niente piu' guasti. */
  fun heal()

  fun shelfWriter(device: String): ShelfWriter

  /** Fa fallire la prossima scrittura dello scaffale prima di lasciare un file. */
  fun failNextShelfWrite()

  /** Tutti i file della share con la dimensione: percorso (testo) → byte. Letti con il lettore non avvolto. */
  suspend fun listAll(): Map<String, Long>

  /** I percorsi messi con [put]. */
  val external: Set<String>

  /** Quanti guasti di lettura il `FaultPlan` ha fatto scattare. */
  val firedFaults: Int

  /** Il contenuto di un file (per riconoscere una copia scritta a meta'). */
  suspend fun contentOf(path: SharePath): ByteArray?
}

/** Quello che il lettore ha visto, e dove l'invariante 3 viene violata. */
class ReadLog(private val partialLength: () -> Long, private val total: () -> Long) {
  val opens = AtomicInteger()
  val reads = AtomicInteger()
  val violations: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

  /** Il punto da cui si deve riprendere adesso: il confine di blocco sotto il parziale che c'e' sul disco. */
  fun resumePoint(): Long {
    val length = partialLength()
    return if (length >= total()) total() else length / ModelLimits.BLOCK_BYTES * ModelLimits.BLOCK_BYTES
  }
}

/** Avvolge un lettore: ogni file aperto controlla che si legga dal punto di ripresa e mai prima (niente di gia' salvato si rilegge). */
class WatchedReader(private val inner: NasMediaReader, private val log: ReadLog, private val isModelFile: (SharePath) -> Boolean) :
  NasMediaReader by inner {
  override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> {
    val opened = inner.open(share, path, purpose)
    if (opened !is NasResult.Ok || !isModelFile(path)) return opened
    log.opens.incrementAndGet()
    return NasResult.Ok(WatchedFile(opened.value, log, log.resumePoint()))
  }

  private class WatchedFile(private val file: NasRandomFile, private val log: ReadLog, private val resume: Long) : NasRandomFile by file {
    @Volatile private var lowest = Long.MAX_VALUE

    override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
      log.reads.incrementAndGet()
      if (offset < resume) log.violations += "letto l'offset $offset ma il parziale buono arriva a $resume"
      if (offset < lowest) lowest = offset
      return file.read(offset, buf, off, len)
    }

    override fun close() {
      // La prima lettura deve cadere esattamente sul punto di ripresa (un'apertura senza letture e' lecita: tempo finito o errore).
      if (lowest != Long.MAX_VALUE && lowest != resume) log.violations += "prima lettura all'offset $lowest, punto di ripresa $resume"
      file.close()
    }
  }
}

/** La faccia in memoria: `InMemoryNasMedia` con i guasti di lettura del simulatore (`FaultPlan`). */
class MemoryNasSide(override val share: String, log: ReadLog, isModelFile: (SharePath) -> Boolean) : ChaosNasSide {
  private val nas = InMemoryNasMedia().apply { addShare(share) }
  private val putPaths = LinkedHashSet<String>()
  private val reads = AtomicInteger()
  private val writers = HashMap<String, InMemoryShelfWriter>()
  private var plan: FaultPlan? = null

  override val firedFaults: Int get() = plan?.fired?.size ?: 0

  override val reader: NasMediaReader = WatchedReader(nas.reader, log, isModelFile)
  override val external: Set<String> get() = putPaths

  override fun put(path: SharePath, bytes: ByteArray) {
    putPaths += path.value
    nas.putFile(share, path.value, NasContent.of(bytes))
  }

  override fun change(path: SharePath, bytes: ByteArray) {
    nas.modify(share, path.value, NasContent.of(bytes))
  }

  override fun armDrops(seed: Long, percent: Int) {
    val p = percent / 200.0
    plan = FaultPlan()
      .random(seed, p, FaultPhase.BEFORE, SimFault.DropConnection)
      .random(seed + 1, p, FaultPhase.AFTER, SimFault.DropConnection)
    nas.readFaults = plan
  }

  override fun armChangeAtRead(n: Int, path: SharePath, bytes: ByteArray) {
    nas.fault = FakeNasFault { op, _, _ ->
      if (op == FakeNasOp.READ && reads.incrementAndGet() == n) nas.modify(share, path.value, NasContent.of(bytes))
      null
    }
  }

  override fun heal() {
    nas.readFaults = null
    nas.fault = null
  }

  override fun shelfWriter(device: String): ShelfWriter = writers.getOrPut(device) { InMemoryShelfWriter(nas, device) }

  override fun failNextShelfWrite() {
    writers.values.forEach { it.failAfterBytes = 100L * 1024 }
  }

  override suspend fun listAll(): Map<String, Long> = walkAll(nas.reader, share)

  override suspend fun contentOf(path: SharePath): ByteArray? = readWhole(nas.reader, share, path)

  override fun close() = Unit
}

/** Tutti i file della share, in ricorsione, con il lettore dato (e senza guasti: chi chiama prima fa [ChaosNasSide.heal]). */
suspend fun walkAll(reader: NasMediaReader, share: String): Map<String, Long> {
  val out = LinkedHashMap<String, Long>()
  suspend fun walk(dir: SharePath?) {
    val entries = ArrayList<dev.pampa.mycloudgallery.core.nas.contract.NasEntry>()
    reader.list(share, dir, Purpose.BACKGROUND) { entries += it }
    for (e in entries) {
      val path = dir?.child(e.name) ?: SharePath.of(e.name)
      if (e.isDirectory) walk(path) else out[path.value] = e.size
    }
  }
  walk(null)
  return out
}

/** Il contenuto intero di un file, letto con [reader] (null se non c'e'). */
suspend fun readWhole(reader: NasMediaReader, share: String, path: SharePath): ByteArray? {
  val file = (reader.open(share, path, Purpose.BACKGROUND) as? NasResult.Ok)?.value ?: return null
  return file.use { it.readRange(0L until it.size) }
}

/** Un [ShelfWriter] che non scrive mai (per le facce senza scaffale). */
object NoShelfWriter : ShelfWriter {
  override val ownerDeviceId: String = "none"

  override suspend fun publishExclusive(share: String, path: SharePath, source: ShelfSource): ShelfWrite = ShelfWrite.Refused("no_shelf")

  override suspend fun deleteOwn(share: String, path: SharePath, size: Long): ShelfDelete = ShelfDelete.Failed
}
