package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.backup.contract.BackupDiaryCounts
import dev.pampa.mycloudgallery.core.backup.contract.BackupEngine
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupRunResult
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nas.ops.backup.BackupTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.backup.ReopenOutcome
import dev.pampa.mycloudgallery.core.nas.ops.backup.TempPoint
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.MessageDigest
import java.util.Locale
import kotlin.time.Duration

// Gli ERRORI INTRODOTTI APPOSTA del banco ha i denti (BackupChaosMutationTest, docs/fase5-progetto.md riga P6 del §4.4): quattro difetti che un
// motore del backup potrebbe avere, ciascuno scritto come uno stack "rotto" che avvolge le porte del motore sano (le primitive del NAS, la
// lettura, il diario) e rompe una promessa. Come nella 4B ("ogni difetto avvolge un pezzo dello stack e rompe una promessa") perche' il motore
// stesso sta in `core-backup/src/main`, che il banco non tocca; ma ogni mutante riproduce l'EFFETTO dell'errore nel motore:
//
//  1. pubblicare PRIMA della verifica: il file diventa visibile con il suo nome definitivo appena il caricamento finisce, prima della rilettura;
//  2. saltare la rilettura: la "verifica" si fida di cio' che il motore ha scritto invece di rileggere dal NAS;
//  3. scrivere il diario DOPO la chiamata invece che prima: il valore di un passo (il temporaneo, il punto di ripresa) entra nel diario solo
//     dopo che la chiamata al NAS e' tornata (o alla fine del giro); una morte in mezzo lascia un file che il diario non cita;
//  4. il temporaneo tolto dalla pulizia: la pulizia dei propri file (D33) non rispetta i temporanei citati dal diario.
//
// Ogni mutante sta SOPRA i decoratori dei colpi (il motore vede: mutante → colpi → NAS), cosi' i guasti del banco (byte alterati, morti del
// processo) colpiscono anche lui.

/** Lo stack rotto costruito per un processo: le porte avvolte e, se serve, il motore avvolto (il diario in ritardo si svuota quando un giro finisce). */
class Built(val env: EngineEnv, val wrap: (BackupEngine) -> BackupEngine = { it })

/** Uno stack rotto: lo stack sano con le porte avvolte da [build] (e, per la pulizia, [cleanerHonorsCitations]). */
class Mutant(
  private val base: BackupChaosStackFactory,
  private val label: String,
  override val cleanerHonorsCitations: Boolean = true,
  private val build: (EngineEnv) -> Built = { Built(it) },
) : BackupChaosStackFactory {
  override val name: String = "${base.name} + difetto iniettato: $label"

  override fun create(env: EngineEnv): BackupEngine {
    val built = build(env)
    return built.wrap(base.create(built.env))
  }
}

object Mutants {

  /** 1. Pubblicare prima della verifica. */
  fun publishBeforeVerify(base: BackupChaosStackFactory): BackupChaosStackFactory = Mutant(base, "pubblica prima di verificare") { env ->
    val inner = env.ops
    val ops = object : NasBackupOps by inner {
      override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<BackupTempWriter> =
        when (val opened = inner.openTemp(share, temp, purpose)) {
          is NasResult.Err -> opened
          is NasResult.Ok -> NasResult.Ok(EarlyPublishWriter(opened.value) { early(env, inner, share, it) })
        }

      override suspend fun reopenTemp(share: String, temp: SharePath, keepBytes: Long, tempId: Long, purpose: Purpose): NasResult<ReopenOutcome> =
        when (val reopened = inner.reopenTemp(share, temp, keepBytes, tempId, purpose)) {
          is NasResult.Err -> reopened
          is NasResult.Ok -> when (val outcome = reopened.value) {
            is ReopenOutcome.Reopened -> NasResult.Ok(ReopenOutcome.Reopened(EarlyPublishWriter(outcome.writer) { early(env, inner, share, it) }))
            else -> reopened
          }
        }
    }
    Built(env.with(ops = ops))
  }

  /** Come un motore che pubblica appena ha finito di scrivere: il temporaneo prende il nome definitivo, senza rileggerlo. */
  private suspend fun early(env: EngineEnv, ops: NasBackupOps, share: String, temp: SharePath) {
    val item = env.world.store.all().firstOrNull { it.temp == temp } ?: return
    ops.publishTemp(share, temp, item.finalPath ?: item.planned, Purpose.BACKGROUND)
  }

  private class EarlyPublishWriter(
    private val inner: BackupTempWriter,
    private val publish: suspend (SharePath) -> Unit,
  ) : BackupTempWriter by inner {
    override suspend fun finish(expectedSize: Long): NasResult<TempFinish> {
      val result = inner.finish(expectedSize)
      if (result is NasResult.Ok && result.value is TempFinish.Complete) publish(inner.temp)
      return result
    }
  }

  /** 2. Saltare la rilettura: la verifica dice l'impronta di cio' che il motore ha scritto, senza rileggere il file dal NAS. */
  fun skipReread(base: BackupChaosStackFactory): BackupChaosStackFactory = Mutant(base, "salta la rilettura (si fida di cio' che ha scritto)") { env ->
    val intent = Intent()
    Built(env.with(ops = IntentOps(env.ops, intent), reader = TrustingReader(env.reader, intent)))
  }

  /** Cio' che il motore ha scritto in ogni temporaneo (l'impronta dei byte che ha PASSATO alla scrittura, in ordine, da zero). */
  private class Intent {
    class Written(val digest: MessageDigest, var next: Long, var valid: Boolean)

    val byPath = HashMap<String, Written>()

    fun begin(path: SharePath, valid: Boolean): Written =
      Written(MessageDigest.getInstance("SHA-256"), 0, valid).also { byPath[path.value.lowercase(Locale.ROOT)] = it }
  }

  private class IntentOps(private val inner: NasBackupOps, private val intent: Intent) : NasBackupOps by inner {
    override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<BackupTempWriter> =
      when (val opened = inner.openTemp(share, temp, purpose)) {
        is NasResult.Err -> opened
        is NasResult.Ok -> NasResult.Ok(IntentWriter(opened.value, intent.begin(temp, valid = true)))
      }

    // Dopo una ripresa il prefisso non lo conosce: la sua impronta non vale (si rilegge davvero).
    override suspend fun reopenTemp(share: String, temp: SharePath, keepBytes: Long, tempId: Long, purpose: Purpose): NasResult<ReopenOutcome> =
      when (val reopened = inner.reopenTemp(share, temp, keepBytes, tempId, purpose)) {
        is NasResult.Err -> reopened
        is NasResult.Ok -> when (val outcome = reopened.value) {
          is ReopenOutcome.Reopened -> NasResult.Ok(ReopenOutcome.Reopened(IntentWriter(outcome.writer, intent.begin(temp, valid = false))))
          else -> reopened
        }
      }
  }

  private class IntentWriter(private val inner: BackupTempWriter, private val written: Intent.Written) : BackupTempWriter by inner {
    override suspend fun write(offset: Long, buf: ByteArray, off: Int, len: Int): NasResult<Unit> {
      val result = inner.write(offset, buf, off, len)
      if (result is NasResult.Ok && written.valid) {
        if (offset == written.next) {
          written.digest.update(buf, off, len)
          written.next += len
        } else {
          written.valid = false
        }
      }
      return result
    }
  }

  private class TaggedFile(val inner: NasRandomFile, val path: String) : NasRandomFile by inner

  /** La lettura che non legge: per un temporaneo scritto da zero da questo processo risponde l'impronta di cio' che ha scritto. */
  private class TrustingReader(private val inner: NasMediaReader, private val intent: Intent) : NasMediaReader {
    override suspend fun list(share: String, dir: SharePath?, purpose: Purpose, pageSize: Int, onPage: suspend (List<NasEntry>) -> Unit): NasResult<ListOutcome> =
      inner.list(share, dir, purpose, pageSize, onPage)

    override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> = inner.stat(share, path, purpose)

    override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> =
      when (val opened = inner.open(share, path, purpose)) {
        is NasResult.Err -> opened
        is NasResult.Ok -> NasResult.Ok(TaggedFile(opened.value, path.value.lowercase(Locale.ROOT)))
      }

    override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> {
      val tagged = file as? TaggedFile
      val real = tagged?.inner ?: file
      val written = tagged?.let { intent.byPath[it.path] }
      if (tagged != null && written != null && written.valid && written.next == file.size) {
        return NasResult.Ok(Sha256((written.digest.clone() as MessageDigest).digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }))
      }
      return inner.readAll(real, sink, progress)
    }
  }

  /** 3. Scrivere il diario dopo la chiamata invece che prima. */
  fun diaryAfterCall(base: BackupChaosStackFactory): BackupChaosStackFactory = Mutant(base, "scrive il diario dopo la chiamata al NAS") { env ->
    val diary = DeferredDiary(env.store)
    Built(
      env.with(ops = FlushingOps(env.ops, diary), reader = FlushingReader(env.reader, diary), store = diary),
      wrap = { engine -> FlushingEngine(engine, diary) },
    )
  }

  /**
   * Un diario che rimanda le riscritture: `update` si mette in coda e arriva al diario vero solo DOPO la chiamata al NAS che segue (o alla
   * prima lettura del diario, che deve vedere le proprie scritture). La coda e' del processo: se muore, e' persa.
   */
  private class DeferredDiary(private val inner: BackupStore) : BackupStore {
    private val queue = ArrayDeque<BackupItem>()

    suspend fun flush() {
      while (queue.isNotEmpty()) inner.update(queue.first().also { queue.removeFirst() })
    }

    override suspend fun create(items: List<NewBackupItem>): List<BackupItem> {
      flush()
      return inner.create(items)
    }

    override suspend fun item(id: BackupItemId): BackupItem? {
      flush()
      return inner.item(id)
    }

    override suspend fun itemsToResume(limit: Int): List<BackupItem> {
      flush()
      return inner.itemsToResume(limit)
    }

    override suspend fun update(item: BackupItem) {
      queue.addLast(item)
    }

    override suspend fun openFor(versionId: VersionId): BackupItem? {
      flush()
      return inner.openFor(versionId)
    }

    override suspend fun citedTemps(): Set<SharePath> {
      flush()
      return inner.citedTemps()
    }

    override suspend fun counts(): BackupDiaryCounts {
      flush()
      return inner.counts()
    }
  }

  /** Alla fine di un giro il diario in ritardo arriva al diario vero (se il processo non e' morto prima). */
  private class FlushingEngine(private val inner: BackupEngine, private val diary: DeferredDiary) : BackupEngine by inner {
    override suspend fun run(kind: BackupKind, budget: Duration, trigger: BackupTrigger): BackupRunResult =
      inner.run(kind, budget, trigger).also { diary.flush() }

    override suspend fun resumeAll(budget: Duration): BackupRunResult = inner.resumeAll(budget).also { diary.flush() }
  }

  private class FlushingOps(private val inner: NasBackupOps, private val diary: DeferredDiary) : NasBackupOps by inner {
    override suspend fun mkdirs(share: String, dir: SharePath, purpose: Purpose): NasResult<Unit> = inner.mkdirs(share, dir, purpose).also { diary.flush() }

    override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<BackupTempWriter> =
      inner.openTemp(share, temp, purpose).let { r ->
        val wrapped = if (r is NasResult.Ok) NasResult.Ok<BackupTempWriter>(FlushingWriter(r.value, diary)) else r
        diary.flush()
        wrapped
      }

    override suspend fun reopenTemp(share: String, temp: SharePath, keepBytes: Long, tempId: Long, purpose: Purpose): NasResult<ReopenOutcome> =
      inner.reopenTemp(share, temp, keepBytes, tempId, purpose).let { r ->
        val wrapped = if (r is NasResult.Ok && r.value is ReopenOutcome.Reopened) {
          NasResult.Ok<ReopenOutcome>(ReopenOutcome.Reopened(FlushingWriter((r.value as ReopenOutcome.Reopened).writer, diary)))
        } else {
          r
        }
        diary.flush()
        wrapped
      }

    override suspend fun copyToTemp(
      fromShare: String,
      from: SharePath,
      toShare: String,
      temp: SharePath,
      expect: NasStamp?,
      purpose: Purpose,
    ): NasResult<CopyOutcome> = inner.copyToTemp(fromShare, from, toShare, temp, expect, purpose).also { diary.flush() }

    override suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome> =
      inner.publishTemp(share, temp, final, purpose).also { diary.flush() }

    override suspend fun deleteOwnTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<DeleteOutcome> =
      inner.deleteOwnTemp(share, temp, purpose).also { diary.flush() }

    override suspend fun space(share: String, purpose: Purpose): NasResult<NasSpaceInfo> = inner.space(share, purpose).also { diary.flush() }
  }

  private class FlushingWriter(private val inner: BackupTempWriter, private val diary: DeferredDiary) : BackupTempWriter by inner {
    override suspend fun write(offset: Long, buf: ByteArray, off: Int, len: Int): NasResult<Unit> = inner.write(offset, buf, off, len).also { diary.flush() }

    override suspend fun checkpoint(): NasResult<TempPoint> = inner.checkpoint().also { diary.flush() }

    override suspend fun finish(expectedSize: Long): NasResult<TempFinish> = inner.finish(expectedSize).also { diary.flush() }
  }

  private class FlushingReader(private val inner: NasMediaReader, private val diary: DeferredDiary) : NasMediaReader {
    override suspend fun list(share: String, dir: SharePath?, purpose: Purpose, pageSize: Int, onPage: suspend (List<NasEntry>) -> Unit): NasResult<ListOutcome> =
      inner.list(share, dir, purpose, pageSize, onPage).also { diary.flush() }

    override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> = inner.stat(share, path, purpose).also { diary.flush() }

    override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> = inner.open(share, path, purpose).also { diary.flush() }

    override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> =
      inner.readAll(file, sink, progress).also { diary.flush() }
  }

  /** 4. Il temporaneo tolto dalla pulizia: la pulizia dei propri file non rispetta i temporanei citati dal diario (D33). */
  fun cleanupRemovesTemp(base: BackupChaosStackFactory): BackupChaosStackFactory =
    Mutant(base, "la pulizia toglie anche i temporanei citati dal diario", cleanerHonorsCitations = false)
}
