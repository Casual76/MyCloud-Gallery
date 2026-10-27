package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishRules
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.FacesPut
import dev.pampa.mycloudgallery.core.faces.contract.FacesRead
import dev.pampa.mycloudgallery.core.faces.contract.FacesRemote
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.ClusterEntry
import dev.pampa.mycloudgallery.core.trust.data.EncodedSegment
import dev.pampa.mycloudgallery.core.trust.data.FacesBody
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesHead
import dev.pampa.mycloudgallery.core.trust.data.FacesSegmentContent
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.PeopleDoc
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SnapshotMark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * IL PUBBLICATORE DEI DATI DEI VOLTI DI QUESTO DISPOSITIVO (Fase 8, P3; docs/fase8-progetto.md §2.4 "Pubblicazione", D12, D21, D30, D34-D36;
 * formato §5.11). Accoda ([stage], durevole in [store]) e pubblica ([flush]) il **contributo intero** di questo dispositivo nella generazione
 * corrente, ogni volta per intero e in segmenti stabili (di solito cambia un segmento solo):
 * - **tutti** i corpi `faces` calcolati qui (vincenti o no: il contributo di un dispositivo non si restringe mai, cosi' chi legge le stesse
 *   teste sceglie lo stesso vincitore), **solo** per le chiavi che [attestation] dice di media del NAS di questo account con timbro e
 *   `qh1` freschi ("M3 dei volti");
 * - lo **stato unito** delle voci `people` che conosce (F4-D26: un telefono spento non porta via i nomi), datate con l'ora del NAS;
 * - l'ultima istantanea che il custode (P5, D21) ha chiesto di pubblicare.
 *
 * **Scrive solo sotto `faces/<G>/<questo dispositivo>/` dell'area privata, solo con `publishExclusive`** (la porta [remote] non permette
 * altro: `NasFacesRemote`), **mai nell'area comune** (L23), **mai sulle share vere** finche' la recinzione e' accesa (D34, Q5), **mai senza
 * lo stato fresco** che dice `On(G)` (D30), dietro [gate] (D35). Prima i segmenti (nome = impronta; un nome occupato da altri byte ferma la
 * pubblicazione), poi la testa al primo numero libero; al piu' una testa ogni 5 minuti d'ora del NAS, salvo la fine di un'unita' o della
 * manutenzione. La generazione che cambia a meta' ferma la pubblicazione prima della testa ([FacesPublishOutcome.NotFresh]).
 */
class NasFacesPublisher(
  private val identity: FacesSyncIdentity,
  private val switch: FacesSwitch,
  private val remote: FacesRemote,
  private val archives: FacesArchives,
  private val store: FacesPublishStore,
  private val attestation: FacesAttestation,
  private val gate: FacesWriteGate,
  private val clock: FacesClock,
  private val flags: FeatureFlags = FeatureFlags.RELEASE,
) : FacesPublisher {

  private val mutex = Mutex()
  private val guard = FacesSyncGuard(identity, switch, { remote.share }, flags)
  private val pendingCount = MutableStateFlow(0)

  override val pending: StateFlow<Int> = pendingCount

  @Volatile private var lastStageNanos: Long? = null

  /** Quante voci l'ultima pubblicazione non ha potuto scrivere perche' il formato non le ammetteva (solo un numero, per la Diagnostica). */
  @Volatile var notPublishable: Int = 0
    private set

  override suspend fun stage(change: FacesChange): Unit = mutex.withLock {
    val on = switch.currentState() as? FacesState.On ?: return@withLock
    if (on.here == HereState.EXCLUDED) return@withLock
    val generation = on.generation
    var state = store.load(generation)
    state = when (change) {
      // Idempotente (la stessa chiave due volte = una voce): una chiave gia' in coda, con qualcosa gia' da pubblicare, non copia l'insieme e non scrive niente. Serve alla
      // rimessa in coda dei corpi propri di un'unita' (dopo una morte, o per un media del solo telefono arrivato sul NAS, O2), che dopo un riavvio li ripassa tutti.
      is FacesChange.Faces -> when {
        change.contentKey in state.keys && state.pending > 0 -> return@withLock
        change.contentKey in state.keys -> state
        else -> state.copy(keys = state.keys + change.contentKey)
      }
      is FacesChange.Snapshot -> stageSnapshot(generation, state, change.seq)
      // Le voci stanno nell'archivio (P9 le scrive li'): si ripubblica lo stato unito, che le comprende.
      is FacesChange.People -> state
    }
    state = state.copy(pending = state.pending + 1)
    store.save(generation, state)
    pendingCount.value = state.pending
    lastStageNanos = clock.monotonicNanos()
  }

  /**
   * I corpi della mia testa che la replica ha appena letto: le loro chiavi restano fra quelle da pubblicare (se lo stato locale le ha perse,
   * la testa nuova non li fa sparire per gli altri). Idempotente.
   */
  internal suspend fun ownBodies(generation: FaceGeneration, keys: Collection<ContentKey>): Unit = mutex.withLock {
    val state = store.load(generation)
    val missing = keys.filterNot { it in state.keys }
    if (missing.isNotEmpty()) store.save(generation, state.copy(keys = state.keys + missing))
  }

  /** L'istantanea [seq] dell'archivio (il custode l'ha appena salvata), nelle voci del formato; una `seq` gia' superata si ignora. */
  private suspend fun stageSnapshot(generation: FaceGeneration, state: FacesPublishState, seq: Long): FacesPublishState {
    if ((state.snapshot?.mark?.seq ?: 0L) >= seq) return state
    val archive = archives.open(generation)?.takeIf { it.generation == generation } ?: return state
    val grouping = archive.grouping()
    if (grouping.seq != seq) return state
    val entries = FacesWire.snapshotEntries(grouping, archive.groupFaces())
    if (entries == null) {
      notPublishable++
      return state
    }
    return state.copy(snapshot = StagedSnapshot(SnapshotMark(grouping.seq, grouping.base), entries))
  }

  override suspend fun flush(reason: FlushReason): FacesPublishOutcome = mutex.withLock {
    val open = when (val checked = guard.check()) {
      // Registro, area comune, recinzione: un cancello. Persone non attiva qui o stato non fresco: niente si pubblica (D30).
      is FacesGate.Closed -> return@withLock checked.block?.let { FacesPublishOutcome.Blocked(it) } ?: FacesPublishOutcome.NotFresh
      is FacesGate.Open -> checked
    }
    gate.blockFor(remote.share)?.let { return@withLock FacesPublishOutcome.Blocked(it) }
    if (reason == FlushReason.DEBOUNCE) {
      val since = lastStageNanos?.let { (clock.monotonicNanos() - it) / NANOS_PER_MILLI }
      if (since != null && since < FacesPublishRules.DEBOUNCE_MS) return@withLock FacesPublishOutcome.TooSoon(FacesPublishRules.DEBOUNCE_MS - since)
    }
    val nasNow = clock.nasNowMillis() ?: return@withLock FacesPublishOutcome.Failed
    val archive = archives.open(open.generation)?.takeIf { it.generation == open.generation } ?: return@withLock FacesPublishOutcome.Failed
    try {
      publish(open, archive, reason, nasNow)
    } catch (e: CancellationException) {
      throw e
    } catch (closed: IllegalStateException) {
      // L'archivio si e' chiuso a meta' (generazione cambiata, chiave distrutta): nessuna testa.
      FacesPublishOutcome.NotFresh
    } catch (e: Exception) {
      // O1: un errore del database (l'outbox, la lettura dei corpi) a meta' non e' un'eccezione per chi chiama: la pubblicazione non e' riuscita e si riprova. Niente si perde:
      // l'outbox tiene le chiavi, e se una testa e' forse uscita senza che lo si sappia `uncertain` era gia' salvato prima di provarci.
      FacesPublishOutcome.Failed
    }
  }

  private class Plan(val segments: List<EncodedSegment>, val snapshot: SnapshotMark?, val bodies: Int, val people: Int, val cluster: Int, val skipped: Int) {
    val entries: Int get() = bodies + people + cluster
  }

  private suspend fun publish(open: FacesGate.Open, archive: FacesArchive, reason: FlushReason, nasNow: Long): FacesPublishOutcome {
    val generation = open.generation
    var state = store.load(generation)
    if (!state.adopted) {
      state = adoptOwnHead(open, archive, state, nasNow) ?: return FacesPublishOutcome.Failed
      store.save(generation, state)
    }
    val last = state.publication
    val urgent = reason == FlushReason.UNIT_FINISHED || reason == FlushReason.MAINTENANCE_FINISHED
    if (last != null && !urgent && last.publishedAtNas <= nasNow) {
      val elapsed = nasNow - last.publishedAtNas
      if (elapsed < FacesPublishRules.MIN_HEAD_INTERVAL_MS) return FacesPublishOutcome.TooSoon(FacesPublishRules.MIN_HEAD_INTERVAL_MS - elapsed)
    }
    // D36: le scelte fatte qui senza l'ora del NAS la prendono adesso (prima di ripubblicare lo stato unito).
    archive.datePeople(nasNow)
    val plan = plan(open, archive, state, nasNow)
    notPublishable = plan.skipped
    // Oltre il tetto per testa la pubblicazione si ferma (non si tronca: un contributo a meta' farebbe sparire dati per gli altri).
    if (plan.bodies + plan.people > TrustLimits.MAX_FACES_HEAD_ENTRIES) return FacesPublishOutcome.Failed
    val names = plan.segments.mapTo(LinkedHashSet()) { it.ref.hash }
    val unchanged = !state.uncertain && if (last == null) plan.segments.isEmpty() else last.segments == names && last.snapshot == plan.snapshot
    if (unchanged) {
      if (state.pending != 0) store.save(generation, state.copy(pending = 0))
      pendingCount.value = 0
      return FacesPublishOutcome.NothingToPublish
    }
    // Il diario prima dei segmenti: uno scritto e poi rimasto senza testa (morte del processo) resta "mio" per la pulizia (P8, M6).
    state = state.copy(diary = (state.diary + names).let { if (it.size > FacesPublishState.MAX_DIARY) it.drop(it.size - FacesPublishState.MAX_DIARY).toSet() else it })
    store.save(generation, state)
    for (segment in plan.segments) {
      if (last != null && segment.ref.hash in last.segments) continue
      // Una generazione che non e' piu' la corrente non riceve altri file (nemmeno segmenti senza testa).
      if (!guard.stillOpen(open)) return FacesPublishOutcome.NotFresh
      when (val put = remote.publishSegment(generation, segment.ref.hash, segment.bytes)) {
        is RemoteResult.Failed -> return FacesPublishOutcome.Failed
        is RemoteResult.Ok -> if (put.value == FacesPut.ALREADY_EXISTS && !sameBytes(generation, segment)) return FacesPublishOutcome.Failed
      }
    }
    // D30: la generazione che cambia a meta' (o lo stato che non e' piu' fresco) ferma tutto prima della testa.
    if (!guard.stillOpen(open)) return FacesPublishOutcome.NotFresh
    val classed = plan.segments.map { it.classed }.sortedBy { it.ref.hash }
    val registry = open.registry
    // Da qui una testa potrebbe finire sul NAS anche se la risposta non arriva: lo si ricorda prima di provarci.
    state = state.copy(uncertain = true)
    store.save(generation, state)
    val result = FacesHeadSeries.publish(
      remote,
      registry,
      generation,
      open.account,
      after = last?.version ?: 0L,
      matches = { own -> own.head.segments == classed && own.head.snapshot == plan.snapshot },
    ) { version ->
      FacesDocs.encodeHead(
        FacesHead(registry.registry.familyId, open.account, generation, identity.deviceId, version, registry.registry.version, nasNow, plan.snapshot, classed),
        identity.signer,
      )
    }
    return when (result) {
      is FacesHeadPublish.Published -> done(generation, state, names, plan, result.version, result.mark, nasNow)
      is FacesHeadPublish.ExistingOwn -> done(generation, state, names, plan, result.version, result.head.mark, result.head.head.createdAt)
      FacesHeadPublish.Blocked, FacesHeadPublish.NoRoom, is FacesHeadPublish.Failed -> FacesPublishOutcome.Failed
    }
  }

  /** Il nome e' l'impronta: un segmento gia' presente deve avere gli stessi byte (una ripresa), mai un file messo li' da altri. */
  private suspend fun sameBytes(generation: FaceGeneration, segment: EncodedSegment): Boolean {
    val read = remote.readSegment(generation, identity.deviceId, segment.ref.hash, segment.bytes.size)
    val data = ((read as? RemoteResult.Ok)?.value as? FacesRead.Data)?.bytes ?: return false
    return data.contentEquals(segment.bytes)
  }

  private suspend fun done(
    generation: FaceGeneration,
    state: FacesPublishState,
    names: Set<String>,
    plan: Plan,
    version: Long,
    mark: HeadMark,
    createdAt: Long,
  ): FacesPublishOutcome {
    val publication = FacesPublication(version, mark, createdAt, names, plan.snapshot, plan.entries)
    store.save(generation, state.copy(publication = publication, pending = 0, uncertain = false))
    pendingCount.value = 0
    return FacesPublishOutcome.Published(version, plan.entries)
  }

  /** Il contributo di questo dispositivo adesso, nei segmenti stabili delle tre classi. */
  private suspend fun plan(open: FacesGate.Open, archive: FacesArchive, state: FacesPublishState, nasNow: Long): Plan {
    var skipped = 0
    // Corpi: **tutti** quelli calcolati qui, vincenti o no, per le chiavi attestate ("M3 dei volti"). Il contributo di un dispositivo non si
    // restringe mai (un corpo che oggi perde resta pubblicato): cosi' chi legge le stesse teste ha gli stessi corpi e sceglie lo stesso
    // vincitore, anche dopo che un dispositivo ha perso il suo stato locale.
    val attested = if (state.keys.isEmpty()) emptySet() else attestation.publishable(state.keys).intersect(state.keys)
    val bodies = ArrayList<Pair<ContentKey, FacesBody>>()
    for ((key, body) in archive.bodiesOf(identity.deviceId, attested)) {
      if (body.author != identity.deviceId || key != body.contentKey) continue
      if (!FacesWire.admits(body)) {
        skipped++
        continue
      }
      bodies += key to FacesWire.doc(body)
    }
    // Voci `people`: lo stato unito, gia' datato, con autori dell'account non revocati (le altre non dovrebbero esserci: si contano).
    val rules = PeopleRules(open.registry, open.account, nasNow)
    val docs = ArrayList<PeopleDoc>()
    for (record in archive.people().values) {
      val at = record.atNas
      val doc = if (at != null && rules.verdict(record.author, at) == PeopleVerdict.VALID) FacesWire.doc(record, at) else null
      if (doc == null) skipped++ else docs += doc
    }
    val snapshot = state.snapshot
    val segments = FacesSegmentPlan.bodies(bodies) + FacesSegmentPlan.people(docs) + snapshot?.let { FacesSegmentPlan.cluster(it.entries) }.orEmpty()
    return Plan(segments, snapshot?.mark, bodies.size, docs.size, snapshot?.entries?.size ?: 0, skipped)
  }

  /**
   * La ripresa dopo la perdita dello stato locale (o al primo uso): la mia testa piu' recente sul NAS dice il numero, l'ora, le chiavi dei
   * corpi gia' pubblicati e l'istantanea, cosi' la testa nuova li ripubblica invece di farli sparire per tutti. Null = il NAS non risponde
   * adesso (si riprova). Senza una mia testa valida si parte da zero.
   */
  private suspend fun adoptOwnHead(open: FacesGate.Open, archive: FacesArchive, state: FacesPublishState, nasNow: Long): FacesPublishState? {
    val generation = open.generation
    val read = FacesHeadSeries.readLatest(
      remote, open.registry, generation, identity.deviceId, null, open.account, FacesReadBudget(TrustLimits.MAX_HEAD_READS_PER_SHARE), nasNow,
    )
    val accepted = when (read) {
      is FacesHeadRead.Accepted -> read.head
      FacesHeadRead.None, FacesHeadRead.NoChange, is FacesHeadRead.Rejected -> return state.copy(adopted = true)
      FacesHeadRead.OutOfBudget, is FacesHeadRead.Failed -> return null
    }
    val head = accepted.head
    var keys = state.keys
    val cluster = ArrayList<ClusterEntry>()
    var clusterComplete = true
    for (segment in head.segments) {
      val fetched = when (val r = remote.readSegment(generation, identity.deviceId, segment.ref.hash, segment.ref.size.toInt())) {
        is RemoteResult.Failed -> return null
        is RemoteResult.Ok -> (r.value as? FacesRead.Data)?.bytes
      }
      val content = fetched?.let { (FacesDocs.decodeSegment(it, segment.ref, segment.cls) as? Verdict.Accepted)?.value }
      when {
        content is FacesSegmentContent.Bodies -> {
          keys = keys + content.entries.map { it.contentKey }
          // I miei corpi che qui mancano (stato locale perso) tornano nell'archivio prima della prossima testa, che altrimenti li farebbe
          // sparire per gli altri; un corpo mio gia' presente (un ricalcolo non ancora pubblicato) resta.
          val present = archive.bodiesOf(identity.deviceId, content.entries.map { it.contentKey }).keys
          for (entry in content.entries) {
            if (entry.contentKey !in present) archive.saveBody(FacesWire.body(entry.contentKey, identity.deviceId, entry.body, entry.digest))
          }
        }
        content is FacesSegmentContent.Cluster -> cluster += content.entries
        content == null && segment.cls == SegmentClass.CLUSTER -> clusterComplete = false
      }
    }
    val mark = head.snapshot
    var snapshot = state.snapshot
    if (mark != null && clusterComplete && (snapshot == null || snapshot.mark.seq < mark.seq) && FacesDocs.checkSnapshot(mark, cluster) is Verdict.Accepted) {
      snapshot = StagedSnapshot(mark, cluster)
    }
    val names = head.segments.mapTo(LinkedHashSet()) { it.ref.hash }
    val publication = state.publication?.takeIf { it.version >= head.version }
      ?: FacesPublication(head.version, accepted.mark, head.createdAt, names, head.snapshot, 0)
    return state.copy(keys = keys, snapshot = snapshot, publication = publication, diary = state.diary + names, adopted = true)
  }

  private companion object {
    const val NANOS_PER_MILLI = 1_000_000L
  }
}
