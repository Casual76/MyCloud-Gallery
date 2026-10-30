package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.faces.contract.ClusterSnapshot
import dev.pampa.mycloudgallery.core.faces.contract.ClusterSnapshots
import dev.pampa.mycloudgallery.core.faces.contract.FaceGrouping
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesLease
import dev.pampa.mycloudgallery.core.faces.contract.FacesLeases
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.GoneContents
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingCorrections
import dev.pampa.mycloudgallery.core.faces.contract.GroupingEngine
import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingReport
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.HolderState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleState
import dev.pampa.mycloudgallery.core.faces.contract.PersonIdSource
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * IL LAVORO DEL RAGGRUPPAMENTO CON IL CUSTODE (docs/fase8-progetto.md §2.5 "Il custode e le istantanee", D21, D22; E-F25, E-F26).
 *
 * A ogni giro ([incremental] a fine unita', [nightly] di notte, [recluster] dopo un modello nuovo):
 * 1. **chi sono**: la prenotazione `faces/<G>/cluster` ([FacesLeases], 1 ora, rinnovata a ogni giro): presa = **custode**; contesa
 *    (cartella anomala) = custode anche lui (E-F26: vince l'istantanea piu' alta e l'altro si riallinea); recinzione (D34: share vere, prove,
 *    nessuna area privata o NAS) = **custode per se', in locale**, senza istantanee ne' pubblicazioni; tenuta da un altro dispositivo = solo
 *    **provvisorio**; congelamento, emergenza, salto dell'ora, sola lettura, fiducia o errore del NAS = provvisorio finche' non passa;
 * 2. **da dove parto**: l'istantanea locale dell'archivio e quelle lette dal NAS ([ClusterSnapshots], separate da quella locale). Se una
 *    istantanea letta batte la locale (`seq` piu' alta, o pari `seq` di un dispositivo minore e diversa), il custode **si riallinea** su di
 *    lei ([Realign.onto]: le sue persone nate solo qui restano, come alias o con lo stesso id); un'istantanea che contiene persone che lui
 *    non ha (un custode che ha perso, uno spento prima di pubblicare) **si assorbe** allo stesso modo. Cosi' nessuna persona e nessun nome si
 *    perdono e gli id sono gli stessi su ogni dispositivo;
 * 3. **il passo** del nucleo [engine] sui volti dell'archivio, con le correzioni delle voci `people` ([PeopleState.corrections]) e i
 *    contenuti spariti ([GoneContents]); chi non e' custode fa solo il provvisorio (mai nascite, mai unioni, mai pubblicato);
 * 4. **salva** se e' cambiato qualcosa e, da custode, **mette in coda** l'istantanea ([FacesChange.Snapshot], idempotente: anche dopo una
 *    morte fra il salvataggio e la coda) e chiede la pubblicazione (fine unita' o fine manutenzione).
 *
 * Correzioni della Fase 8 (pacchetto "formato e replica dei volti"): le voci `people` creano persone solo per id che un'istantanea o un volto
 * nominano ([BoundedCorrections], R3); una persona la cui voce `forget` e' sparita con la revoca torna ([Unforget], R3); un'istantanea con la `seq`
 * oltre il tetto riparte da una `seq` ammessa ([Renumber], R4); al piu' [MAX_REALIGNS_PER_PASS] riallineamenti per passo (R6).
 *
 * L'archivio e' quello della generazione corrente ([archives], null = Persone non attiva qui): un archivio chiuso a meta' (generazione
 * cambiata, chiave distrutta: `IllegalStateException` dall'archivio) ferma il giro senza salvare ne' pubblicare. Mai un'eccezione per un
 * caso previsto; mai vettori, id o nomi nei log (D37). I giri non si sovrappongono.
 */
class CustodianGrouping(
  private val archives: suspend () -> FacesArchive?,
  private val deviceId: String,
  private val leases: FacesLeases,
  private val snapshots: ClusterSnapshots,
  private val publisher: FacesPublisher,
  private val gone: GoneContents,
  private val engine: GroupingEngine = FastGrouping(),
  private val ids: PersonIdSource = PersonIdSource.RANDOM,
  private val params: GroupingParams = GroupingParams.DEFAULT,
) : FaceGrouping {

  private val holderState = MutableStateFlow<HolderState>(HolderState.Unknown)
  override val holder: StateFlow<HolderState> = holderState

  private val rebasedCount = MutableStateFlow(0)

  /** Quante volte questo dispositivo si e' riallineato o ha assorbito un'istantanea (codice `FACES_HOLDER_REBASED`, solo numeri). */
  val rebased: StateFlow<Int> = rebasedCount

  private val mutex = Mutex()
  private var held: FacesLease.Held? = null
  private var heldGeneration: FaceGeneration? = null

  /** R3: le persone con una voce `forget` al passo prima (per generazione). In memoria: dopo una morte del processo non si ripristina niente. */
  private var lastForgotten: Pair<FaceGeneration, Set<PersonId>>? = null

  private enum class Kind { INCREMENTAL, NIGHTLY, RECLUSTER }

  private enum class Role(val custodian: Boolean, val publishes: Boolean, val readsNas: Boolean) {
    HOLDER(true, true, true),
    CONTENDED(true, true, true),
    LOCAL(true, false, false),
    FOLLOWER(false, false, true),
    PAUSED(false, false, true),
  }

  override suspend fun incremental(budget: Duration): GroupingReport = run(Kind.INCREMENTAL, budget)

  override suspend fun nightly(budget: Duration): GroupingReport = run(Kind.NIGHTLY, budget)

  override suspend fun recluster(budget: Duration): GroupingReport = run(Kind.RECLUSTER, budget)

  private suspend fun run(kind: Kind, budget: Duration): GroupingReport = mutex.withLock {
    if (!budget.isPositive()) return GroupingReport.EMPTY
    val archive = archives() ?: return GroupingReport.EMPTY.also { holderState.value = HolderState.Unknown }
    val generation = closedSafe { archive.generation } ?: return GroupingReport.EMPTY
    val role = role(generation)
    val faces = closedSafe { archive.groupFaces() } ?: return GroupingReport.EMPTY
    val records = closedSafe { archive.people() } ?: return GroupingReport.EMPTY
    val local = closedSafe { archive.grouping() } ?: return GroupingReport.EMPTY
    val all = PeopleState.of(records.values).corrections()
    val keys = (faces.map { it.ref.contentKey } + local.assignments.keys.map { it.contentKey }).toSet()
    val goneKeys = gone.gone(keys)
    val remote = if (role.readsNas) snapshots.latest(generation) else emptyList()
    // R3: le voci creano persone solo per id che un'istantanea (questa o una letta) o un volto di adesso nominano.
    val corrections = BoundedCorrections.within(all, local.persons.keys + remote.flatMap { it.state.persons.keys }, faces)
    val unforgotten = lastForgotten?.takeIf { it.first == generation }?.second.orEmpty() - all.forgotten
    val outcome = if (role.custodian) {
      custodial(kind, role, local, remote, faces, corrections, goneKeys, unforgotten)
    } else {
      provisional(local, remote, faces, corrections, goneKeys)
    }
    val state = outcome.first
    if (!same(state, local) && !saved(archive, state)) return GroupingReport.EMPTY
    lastForgotten = generation to all.forgotten
    // Da custode si pubblica cio' che non c'e' ancora sul NAS: non la stessa istantanea di un altro (dopo averla adottata senza cambiarla).
    val alreadyThere = remote.any { sameContent(it.state, state) }
    if (role.publishes && state.seq > publishedSeq(remote) && !alreadyThere) {
      try {
        publisher.stage(FacesChange.Snapshot(state.seq))
        publisher.flush(if (kind == Kind.INCREMENTAL) FlushReason.UNIT_FINISHED else FlushReason.MAINTENANCE_FINISHED)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // O1: la messa in coda o la pubblicazione non e' riuscita (un errore del database, l'outbox chiusa): l'istantanea e' salvata nell'archivio e la sua `seq` supera ancora
        // quella pubblicata, quindi la prossima volta si riprova; il raggruppamento non cade.
      }
    }
    holderState.value = when (role) {
      Role.HOLDER -> HolderState.Holder(state.seq)
      Role.CONTENDED -> HolderState.Contended
      Role.LOCAL -> HolderState.LocalOnly
      Role.FOLLOWER, Role.PAUSED -> HolderState.NotHolder
    }
    outcome.second
  }

  /** Il custode: si riallinea o assorbe le istantanee lette, poi il passo chiesto. */
  private fun custodial(
    kind: Kind,
    role: Role,
    local: GroupingState,
    remote: List<ClusterSnapshot>,
    faces: List<GroupFace>,
    corrections: GroupingCorrections,
    goneKeys: Set<ContentKey>,
    unforgotten: Set<PersonId>,
  ): Pair<GroupingState, GroupingReport> {
    var base = local
    var realigns = 0
    val winner = ClusterSnapshot.winner(remote)
    if (role.readsNas && winner != null && beatsLocal(winner, local)) {
      base = Realign.onto(winner.state, local, faces, corrections, params)
      rebasedCount.value++
      realigns++
    }
    for (s in remote.sortedWith { a, b -> if (ClusterSnapshot.beats(a, b)) -1 else if (ClusterSnapshot.beats(b, a)) 1 else 0 }) {
      if (Realign.contains(base, s.state)) continue
      // R6: al piu' qualche riallineamento per passo (il resto al passo dopo): un passo costa al piu' MAX_REALIGNS_PER_PASS volte l'istantanea.
      if (realigns >= MAX_REALIGNS_PER_PASS) break
      base = Realign.onto(base, s.state, faces, corrections, params)
      rebasedCount.value++
      realigns++
    }
    // R3: una persona la cui voce `forget` e' sparita (revocata con la sua provenienza) torna attiva con i suoi volti.
    base = Unforget.restore(base, unforgotten, faces, params)
    val input = GroupingInput(base, faces, corrections, params, goneKeys)
    val out = when (kind) {
      Kind.INCREMENTAL -> engine.incremental(input, ids)
      Kind.NIGHTLY -> engine.nightly(input, ids)
      Kind.RECLUSTER -> engine.recluster(input, base, ids)
    }
    // R4: una `seq` oltre il tetto del formato (adottata da chi l'aveva messa vicino al tetto) riparte da una ammessa.
    val state = Renumber.within(out.state, remote)
    if (state !== out.state) rebasedCount.value++
    return state to out.report
  }

  /** Chi non e' custode: l'istantanea vincente (con le persone che solo questo dispositivo ha, se ne ha) e l'assegnazione provvisoria. */
  private fun provisional(
    local: GroupingState,
    remote: List<ClusterSnapshot>,
    faces: List<GroupFace>,
    corrections: GroupingCorrections,
    goneKeys: Set<ContentKey>,
  ): Pair<GroupingState, GroupingReport> {
    // Sempre dall'istantanea vincente (con le persone che solo questo dispositivo ha, se ne ha): la vista provvisoria tiene il numero
    // della vincente, cosi' la prossima istantanea del custode la sostituisce sempre.
    val winner = ClusterSnapshot.winner(remote)
    val basis = if (winner == null) {
      local
    } else {
      val joined = Realign.onto(winner.state, local, faces, corrections, params)
      GroupingState(winner.state.seq, winner.state.base, joined.persons, joined.assignments, winner.state.unassignedAtBirths)
    }
    val out = engine.provisional(GroupingInput(basis, faces, corrections, params, goneKeys))
    return out.state to out.report
  }

  /** Un'istantanea letta batte quella locale (che e' di questo dispositivo): `seq` piu' alta, o pari `seq`, dispositivo minore e diversa. */
  private fun beatsLocal(s: ClusterSnapshot, local: GroupingState): Boolean =
    s.state.seq > local.seq || (s.state.seq == local.seq && s.author < deviceId && !sameContent(s.state, local))

  /** La `seq` piu' alta che questo dispositivo ha gia' pubblicato (dalle istantanee lette), o -1. */
  private fun publishedSeq(remote: List<ClusterSnapshot>): Long = remote.filter { it.author == deviceId }.maxOfOrNull { it.state.seq } ?: -1

  private suspend fun role(generation: FaceGeneration): Role {
    if (heldGeneration != generation) {
      held = null
      heldGeneration = generation
    }
    val current = held
    val lease = if (current != null) leases.renew(generation, FacesResource.Cluster, current) else leases.acquire(generation, FacesResource.Cluster)
    if (lease !is FacesLease.Failed) held = lease as? FacesLease.Held
    return when (lease) {
      is FacesLease.Held -> Role.HOLDER
      FacesLease.Contended -> Role.CONTENDED
      is FacesLease.Blocked -> if (lease.reason in LOCAL_REASONS) Role.LOCAL else Role.PAUSED
      FacesLease.Busy, FacesLease.Lost -> Role.FOLLOWER
      FacesLease.Failed -> Role.PAUSED
    }
  }

  /** Un archivio chiuso (generazione cambiata, chiave distrutta) risponde `IllegalStateException`: il giro si ferma (null). */
  /** Salva l'istantanea; falso se non e' riuscito (archivio chiuso, o un errore del database: O1) e il raggruppamento di questa volta si ferma, senza cadere. */
  private suspend fun saved(archive: FacesArchive, state: GroupingState): Boolean = try {
    archive.saveGrouping(state)
    true
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    false
  }

  private suspend fun <T> closedSafe(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    // I5: un archivio chiuso a meta' o un errore del database (O1) fermano il giro senza cadere.
    null
  }

  internal companion object {
    /** R6: i riallineamenti (adozioni e assorbimenti di istantanee lette) in un passo. Di solito uno: il custode e' uno solo. */
    const val MAX_REALIGNS_PER_PASS: Int = 4

    /** I motivi per cui il raggruppamento resta locale per sempre (finche' valgono): ogni dispositivo e' custode per se' (D34). */
    val LOCAL_REASONS = setOf(FacesWriteBlock.TEST_SHARES_ONLY, FacesWriteBlock.FENCE, FacesWriteBlock.NO_PRIVATE_AREA, FacesWriteBlock.NO_NAS)

    fun same(a: GroupingState, b: GroupingState): Boolean =
      a.seq == b.seq && a.base == b.base && a.unassignedAtBirths == b.unassignedAtBirths && a.persons == b.persons && a.assignments == b.assignments

    /**
     * Lo stesso contenuto per un'istantanea passata dal NAS: numeri, persone e assegnazioni (volto, persona, origine). Il formato (§2.4)
     * arrotonda il punteggio a 1e-4 e non porta `unassignedAtBirths`: non contano.
     */
    fun sameContent(a: GroupingState, b: GroupingState): Boolean {
      if (a.seq != b.seq || a.base != b.base || a.persons != b.persons || a.assignments.size != b.assignments.size) return false
      return a.assignments.all { (face, x) -> b.assignments[face]?.let { y -> y.person == x.person && y.origin == x.origin && y.locator == x.locator } == true }
    }
  }
}
