package dev.pampa.mycloudgallery.core.backup.versions

import dev.pampa.mycloudgallery.core.backup.contract.UploadFlow
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.backup.testing.BackupDeath
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * Il diario delle versioni in memoria (P8, per i test e per il banco di P13): **le stesse verifiche** che un adattatore Room deve fare.
 * Rifiuta ogni transizione fuori da `UploadStateMachine` per il cammino del lavoro, un lavoro sconosciuto, un cambio d'identita' (tipo,
 * media, versione, share, solito percorso, dimensione), un temporaneo gia' scritto che cambia e un cambio di cammino; e' idempotente per
 * versione e tipo. Sopravvive alla "morte del processo": e' il disco. Tiene il registro delle transizioni (invariante 8 del §5.4).
 *
 * Gli id partono da [firstId] (lontani da quelli del diario del backup della 5A nei test che li usano insieme: i temporanei `<io>-bk-<id>`
 * non si incrociano, come nella stessa tabella Room). [others] dice se un'altra parte del diario (la 5A) ha gia' un elemento aperto.
 */
class InMemoryVersionJournal(
  firstId: Long = 1_000_001,
  private val others: suspend (VersionId) -> Boolean = { false },
) : VersionJournal {

  data class Transition(val job: VersionJobId, val from: UploadState, val to: UploadState, val flow: UploadFlow)

  private val lock = Any()
  private val jobs = LinkedHashMap<Long, VersionJob>()
  private var nextId = firstId
  private val log = ArrayList<Transition>()

  val transitions: List<Transition> get() = synchronized(lock) { log.toList() }

  fun all(): List<VersionJob> = synchronized(lock) { jobs.values.toList() }

  override suspend fun create(job: NewVersionJob): VersionJob = synchronized(lock) {
    jobs.values.firstOrNull { it.versionId == job.versionId && it.kind == job.kind && !it.state.terminal }?.let { return it }
    val created = VersionJob(
      id = VersionJobId(nextId++),
      kind = job.kind,
      state = UploadState.PLANNED,
      mediaId = job.mediaId,
      versionId = job.versionId,
      size = job.size,
      share = job.share,
      usualPath = job.usualPath,
      source = job.source,
      target = job.target,
      current = job.current,
      sha256 = job.sha256,
    )
    jobs[created.id.value] = created
    log += Transition(created.id, UploadState.PLANNED, UploadState.PLANNED, created.flow)
    created
  }

  override suspend fun job(id: VersionJobId): VersionJob? = synchronized(lock) { jobs[id.value] }

  override suspend fun open(limit: Int): List<VersionJob> = synchronized(lock) {
    require(limit > 0) { "Limite non positivo." }
    jobs.values.filter { !it.state.terminal }.take(limit)
  }

  override suspend fun update(job: VersionJob) {
    synchronized(lock) {
      val before = jobs[job.id.value] ?: throw IllegalArgumentException("Lavoro sconosciuto.")
      require(
        before.kind == job.kind && before.mediaId == job.mediaId && before.versionId == job.versionId && before.share == job.share &&
          before.usualPath == job.usualPath && before.size == job.size,
      ) { "L'identita' di un lavoro non cambia." }
      require(before.temp == null || before.temp == job.temp) { "Il temporaneo di un lavoro non cambia una volta scritto." }
      require(before.flow == job.flow) { "Il cammino di un lavoro non cambia." }
      require(before.source::class == job.source::class) { "La sorgente di un lavoro non cambia tipo." }
      check(UploadStateMachine.canTransition(before.flow, before.state, job.state)) {
        "Transizione non ammessa: ${before.state.code} -> ${job.state.code} (${before.flow.code})."
      }
      jobs[job.id.value] = job
      log += Transition(job.id, before.state, job.state, before.flow)
    }
  }

  override suspend fun busy(versionId: VersionId): Boolean =
    synchronized(lock) { jobs.values.any { it.versionId == versionId && !it.state.terminal } } || others(versionId)

  override suspend fun forMedia(mediaId: MediaId): List<VersionJob> = synchronized(lock) { jobs.values.filter { it.mediaId == mediaId } }

  override suspend fun inShare(share: String): List<VersionJob> = synchronized(lock) {
    jobs.values.filter { it.share.equals(share, ignoreCase = true) }
  }

  override suspend fun citedTemps(): Set<SharePath> = synchronized(lock) {
    jobs.values.filter { !it.state.terminal }.mapNotNullTo(LinkedHashSet()) { it.temp }
  }
}

/** Il diario delle versioni che muore: le scritture contano come passi della [BackupDeath]. */
class DyingVersionJournal(private val inner: VersionJournal, private val death: BackupDeath) : VersionJournal by inner {
  override suspend fun create(job: NewVersionJob): VersionJob = death.around { inner.create(job) }

  override suspend fun update(job: VersionJob) = death.around { inner.update(job) }
}
