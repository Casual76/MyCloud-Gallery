package dev.pampa.mycloudgallery.core.faces.grouping

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.ClusterSnapshot
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.GroupingReport
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.HolderState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleState
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.noop.NoGoneContents
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * IL CUSTODE E LE ISTANTANEE (docs/fase8-progetto.md §2.5, D21, D22; E-F25, E-F26; riga P5 del §4.4 "custode unico, due custodi e
 * riallineamento senza nomi persi, passaggio del custode"). Due o tre dispositivi dello stesso account sopra un NAS finto ([ClusterNas]):
 * gli id delle persone sono gli stessi ovunque, sopravvivono al cambio di custode e a due custodi insieme; i nomi non si perdono.
 */
class CustodianGroupingTest {
  private val budget = 5.minutes
  private val story = FamilyStories.story(FamilyStories.Config(seed = 404, identities = 10, photos = 220, siblings = 1, strangerShare = 0.05f))
  private val keys = story.faces.map { it.ref.contentKey }.distinct()
  private val firstHalf = story.faces.filter { keys.indexOf(it.ref.contentKey) < keys.size / 2 }
  private val secondHalf = story.faces.filter { keys.indexOf(it.ref.contentKey) >= keys.size / 2 }

  /** Persone nuove (un'altra famiglia sintetica): fanno nascere persone al giro dopo. */
  private val newcomers = FamilyStories.story(FamilyStories.Config(seed = 405, identities = 6, photos = 140, siblings = 0, strangerShare = 0.05f)).faces

  private fun assignments(state: GroupingState): Map<FaceRef, PersonId> =
    state.assignments.values.filter { it.origin != AssignOrigin.IGNORED }.associate { it.face to it.person }

  /** Ogni volto che due stati hanno tutti e due in una persona e' nella stessa persona (a meno degli alias di [b]). */
  private fun sameIds(a: GroupingState, b: GroupingState): Boolean =
    assignments(a).all { (face, person) -> b.assignments[face]?.let { b.resolve(it.person) == b.resolve(person) } ?: true }

  private fun active(state: GroupingState): Set<PersonId> = state.persons.values.filter { it.state == PersonState.ACTIVE }.map { it.id }.toSet()

  @Test
  fun unSoloCustodeEGliStessiIdSuOgniDispositivo() = runTest {
    val nas = ClusterNas()
    val a = ClusterDevice("aaaa", nas, idsSeed = 1)
    val b = ClusterDevice("bbbb", nas, idsSeed = 2)
    a.receive(firstHalf)
    b.receive(firstHalf)
    a.grouping.incremental(budget)
    a.grouping.nightly(budget)
    assertThat(a.grouping.holder.value).isInstanceOf(HolderState.Holder::class.java)
    val snapshot = nas.latest("aaaa")!!.state
    assertThat(active(snapshot)).isNotEmpty()
    // B non e' custode: prende l'istantanea di A e assegna in provvisorio i volti che A non ha ancora, senza nascite e senza pubblicare.
    b.receive(secondHalf)
    val report = b.grouping.incremental(budget)
    assertThat(b.grouping.holder.value).isEqualTo(HolderState.NotHolder)
    assertThat(report.born).isEqualTo(0)
    assertThat(nas.latest("bbbb")).isNull()
    val view = b.archive.grouping()
    assertThat(view.seq).isEqualTo(snapshot.seq)
    assertThat(view.persons.keys).isEqualTo(snapshot.persons.keys)
    assertWithMessage("B vede le persone di A con gli stessi id").that(sameIds(snapshot, view)).isTrue()
    assertThat(assignments(view).size).isGreaterThan(assignments(snapshot).size)
    // A riceve gli stessi volti e li raggruppa: B, al giro dopo, ha di nuovo esattamente l'istantanea di A piu' il provvisorio.
    a.receive(secondHalf)
    a.grouping.incremental(budget)
    a.grouping.nightly(budget)
    b.grouping.incremental(budget)
    val next = nas.latest("aaaa")!!.state
    assertThat(b.archive.grouping().persons.keys).isEqualTo(next.persons.keys)
    assertThat(sameIds(next, b.archive.grouping())).isTrue()
    assertThat(sameIds(snapshot, next)).isTrue()
  }

  @Test
  fun ilCustodeSpentoOltreUnOraPassaLaManoEGliIdRestano() = runTest {
    val nas = ClusterNas()
    val a = ClusterDevice("aaaa", nas, idsSeed = 1)
    val b = ClusterDevice("bbbb", nas, idsSeed = 2)
    a.receive(firstHalf)
    b.receive(firstHalf)
    a.grouping.incremental(budget)
    a.grouping.nightly(budget)
    val fromA = nas.latest("aaaa")!!.state
    b.grouping.incremental(budget)
    // E-F25: A si spegne; dopo un'ora la prenotazione scade e B la prende alla sua tappa, ripartendo dall'ultima istantanea.
    nas.now += 61.minutes.inWholeMilliseconds
    b.receive(secondHalf)
    b.grouping.incremental(budget)
    b.grouping.nightly(budget)
    assertThat(b.grouping.holder.value).isInstanceOf(HolderState.Holder::class.java)
    val fromB = nas.latest("bbbb")!!.state
    assertThat(fromB.seq).isGreaterThan(fromA.seq)
    assertThat(fromB.base).isAtLeast(fromA.seq)
    assertWithMessage("gli id di A restano con B").that(sameIds(fromA, fromB)).isTrue()
    assertThat(fromB.persons.keys).containsAtLeastElementsIn(fromA.persons.keys)
    // A torna: B tiene la prenotazione, A vede l'istantanea di B (stessi id) e non pubblica.
    a.receive(secondHalf)
    a.grouping.incremental(budget)
    assertThat(a.grouping.holder.value).isEqualTo(HolderState.NotHolder)
    assertThat(sameIds(fromB, a.archive.grouping())).isTrue()
    assertThat(nas.latest("aaaa")!!.state.seq).isEqualTo(fromA.seq)
  }

  @Test
  fun dueCustodiInsiemeSiRiallineanoSenzaPerdereNomi() = runTest {
    val nas = ClusterNas()
    val a = ClusterDevice("aaaa", nas, idsSeed = 1)
    val b = ClusterDevice("bbbb", nas, idsSeed = 2)
    a.receive(firstHalf)
    b.receive(firstHalf)
    a.grouping.incremental(budget)
    a.grouping.nightly(budget)
    b.grouping.incremental(budget)
    // E-F26: la prenotazione e' contesa; tutti e due raggruppano gli stessi volti nuovi senza vedere l'altro (le pubblicazioni partono
    // dopo) e pubblicano la stessa seq con id diversi.
    val before = nas.latest("aaaa")!!.state
    nas.contended = true
    a.offline = true
    b.offline = true
    a.receive(newcomers)
    b.receive(newcomers)
    a.grouping.incremental(budget)
    b.grouping.incremental(budget)
    a.offline = false
    b.offline = false
    a.publisher.flush(FlushReason.SYNC_ROUND)
    b.publisher.flush(FlushReason.SYNC_ROUND)
    val sa = nas.latest("aaaa")!!.state
    val sb = nas.latest("bbbb")!!.state
    assertThat(sa.seq).isEqualTo(sb.seq)
    val bornA = active(sa) - active(before)
    val bornB = active(sb) - active(before)
    assertThat(bornA).isNotEmpty()
    assertThat(bornB).isNotEmpty()
    assertThat(bornA intersect bornB).isEmpty()
    // Ognuno da' un nome a una persona nata solo da lui (le voci `people` arrivano a tutti e due).
    val nameA = PeopleEdit.Name(bornA.min(), "Persona A")
    val nameB = PeopleEdit.Name(bornB.min(), "Persona B")
    for (d in listOf(a, b)) {
      d.name(nameA, nas.now)
      d.name(nameB, nas.now + 1)
    }
    repeat(3) {
      a.grouping.incremental(budget)
      b.grouping.incremental(budget)
    }
    assertThat(a.grouping.rebased.value + b.grouping.rebased.value).isAtLeast(1)
    val finalA = a.archive.grouping()
    val finalB = b.archive.grouping()
    val winner = ClusterSnapshot.winner(listOf(nas.latest("aaaa")!!, nas.latest("bbbb")!!))!!
    for (state in listOf(finalA, finalB, winner.state)) {
      for (id in bornA + bornB) assertWithMessage("una persona nata da un custode si e' persa").that(state.persons).containsKey(id)
      for (named in listOf(nameA.person, nameB.person)) {
        val root = state.resolve(named)
        assertWithMessage("un nome si e' perso").that(root).isNotNull()
        val names = PeopleState.of(a.archive.people().values).names
        assertWithMessage("il nome non e' sulla persona che si vede").that(names[root]).isNotNull()
      }
    }
    assertThat(sameIds(finalA, finalB)).isTrue()
  }

  @Test
  fun unCustodeSpentoPrimaDiPubblicareNonPerdeLePersone() = runTest {
    val nas = ClusterNas()
    val a = ClusterDevice("aaaa", nas, idsSeed = 1)
    val b = ClusterDevice("bbbb", nas, idsSeed = 2)
    a.receive(firstHalf)
    b.receive(firstHalf)
    a.grouping.incremental(budget)
    b.grouping.incremental(budget)
    // A raggruppa i volti nuovi ma il NAS cade prima della pubblicazione, e A resta spento oltre l'ora.
    a.offline = true
    a.receive(newcomers)
    a.grouping.incremental(budget)
    val unpublished = a.archive.grouping()
    assertThat(a.staged).isNotEmpty()
    a.name(PeopleEdit.Name(active(unpublished).max(), "Nata offline"), nas.now)
    nas.now += 2 * ClusterNas.HOUR
    b.receive(newcomers)
    b.grouping.incremental(budget)
    b.grouping.nightly(budget)
    // A torna: la sua istantanea in coda parte (come la rimanda P3), il custode B la assorbe e nessuna persona di A si perde.
    a.offline = false
    a.publisher.flush(FlushReason.SYNC_ROUND)
    a.grouping.incremental(budget)
    a.publisher.flush(FlushReason.SYNC_ROUND)
    b.grouping.incremental(budget)
    val fromB = nas.latest("bbbb")!!.state
    for (id in unpublished.persons.keys) assertWithMessage("persona di A persa").that(fromB.persons).containsKey(id)
    for (id in active(unpublished)) assertWithMessage("persona di A senza radice").that(fromB.resolve(id)).isNotNull()
  }

  @Test
  fun unSeguaceConPersoneSoloSueSegueSempreLaNuovaIstantanea() = runTest {
    val nas = ClusterNas()
    val a = ClusterDevice("aaaa", nas, idsSeed = 1)
    val b = ClusterDevice("bbbb", nas, idsSeed = 2)
    a.receive(firstHalf)
    b.receive(firstHalf)
    a.grouping.incremental(budget)
    b.grouping.incremental(budget)
    // A fa nascere persone che non riesce a pubblicare, poi perde la prenotazione; B diventa custode e pubblica.
    a.offline = true
    a.receive(newcomers)
    a.grouping.incremental(budget)
    val orphans = active(a.archive.grouping()) - active(nas.latest("aaaa")!!.state)
    assertThat(orphans).isNotEmpty()
    nas.now += 2 * ClusterNas.HOUR
    b.grouping.incremental(budget)
    // A (ancora senza pubblicare) segue B: tiene le sue persone e il numero dell'istantanea di B.
    for (round in 0 until 3) {
      val more = newcomers.map { it.ref.contentKey }.distinct().take(20 * (round + 1)).toSet()
      b.receive(story.faces + newcomers.filter { it.ref.contentKey in more })
      b.grouping.incremental(budget)
      b.grouping.nightly(budget)
      a.grouping.incremental(budget)
      val view = a.archive.grouping()
      val latest = nas.latest("bbbb")!!.state
      assertWithMessage("giro $round").that(view.seq).isEqualTo(latest.seq)
      assertThat(view.persons.keys).containsAtLeastElementsIn(latest.persons.keys)
      assertThat(view.persons.keys).containsAtLeastElementsIn(orphans)
      assertThat(sameIds(latest, view)).isTrue()
    }
  }

  @Test
  fun conLaRecinzioneOgniDispositivoECustodePerSeInLocale() = runTest {
    val nas = ClusterNas().apply { blocked = FacesWriteBlock.TEST_SHARES_ONLY }
    val a = ClusterDevice("aaaa", nas, idsSeed = 1)
    val b = ClusterDevice("bbbb", nas, idsSeed = 2)
    for (d in listOf(a, b)) {
      d.receive(story.faces)
      d.grouping.incremental(budget)
      d.grouping.nightly(budget)
      assertThat(d.grouping.holder.value).isEqualTo(HolderState.LocalOnly)
      assertThat(active(d.archive.grouping())).isNotEmpty()
      assertThat(d.staged).isEmpty()
    }
    assertThat(nas.published).isEmpty()
  }

  @Test
  fun conIlCancelloChiusoSoloProvvisorio() = runTest {
    val nas = ClusterNas()
    val a = ClusterDevice("aaaa", nas, idsSeed = 1)
    a.receive(firstHalf)
    a.grouping.incremental(budget)
    val before = a.archive.grouping()
    for (reason in listOf(FacesWriteBlock.FROZEN, FacesWriteBlock.EMERGENCY_STOP, FacesWriteBlock.CLOCK_JUMP, FacesWriteBlock.READ_ONLY)) {
      nas.blocked = reason
      a.receive(secondHalf)
      val report = a.grouping.nightly(budget)
      assertThat(report.born).isEqualTo(0)
      assertThat(report.merged).isEqualTo(0)
      assertThat(a.grouping.holder.value).isEqualTo(HolderState.NotHolder)
      assertThat(a.archive.grouping().persons.keys).isEqualTo(before.persons.keys)
    }
    nas.blocked = null
    nas.failing = true
    assertThat(a.grouping.nightly(budget).born).isEqualTo(0)
    nas.failing = false
    a.grouping.nightly(budget)
    assertThat(a.grouping.holder.value).isInstanceOf(HolderState.Holder::class.java)
  }

  @Test
  fun unArchivioChiusoAMetaNonSalvaENonPubblica() = runTest {
    val nas = ClusterNas()
    val inner = ClusterDevice("aaaa", nas, idsSeed = 1).archive
    ClusterDevice.bodies(story.faces).forEach { inner.saveBody(it) }
    val closing = ClosingArchive(inner, closeAfter = 1)
    var stagedCount = 0
    val device = ClusterDevice("aaaa", nas, idsSeed = 1)
    val grouping = CustodianGrouping(
      archives = { closing }, deviceId = "aaaa", leases = nas.leases("aaaa"), snapshots = nas.snapshots,
      publisher = object : FacesPublisher by device.publisher {
        override suspend fun stage(change: FacesChange) {
          stagedCount++
        }
      },
      gone = NoGoneContents,
    )
    assertThat(grouping.incremental(budget)).isEqualTo(GroupingReport.EMPTY)
    assertThat(stagedCount).isEqualTo(0)
    assertThat(nas.published).isEmpty()
  }

  @Test
  fun unArchivioRifattoRiparteDallaPropriaIstantanea() = runTest {
    val nas = ClusterNas()
    val a = ClusterDevice("aaaa", nas, idsSeed = 1)
    a.receive(story.faces)
    a.grouping.incremental(budget)
    a.grouping.nightly(budget)
    val published = nas.latest("aaaa")!!.state
    // E-F19: la chiave dei volti e' sparita, l'archivio locale si rifa' vuoto; i corpi tornano dalla replica.
    a.archive = InMemoryFacesArchive(nas.generation)
    a.receive(story.faces)
    a.grouping.incremental(budget)
    val again = a.archive.grouping()
    assertThat(again.persons.keys).containsAtLeastElementsIn(published.persons.keys)
    assertThat(sameIds(published, again)).isTrue()
  }

  @Test
  fun senzaBudgetOSenzaPersoneNienteGiro() = runTest {
    val nas = ClusterNas()
    val a = ClusterDevice("aaaa", nas, idsSeed = 1)
    a.receive(story.faces)
    assertThat(a.grouping.incremental(kotlin.time.Duration.ZERO)).isEqualTo(GroupingReport.EMPTY)
    assertThat(nas.holder).isNull()
    val off = CustodianGrouping({ null }, "aaaa", nas.leases("aaaa"), nas.snapshots, a.publisher, NoGoneContents)
    assertThat(off.nightly(budget)).isEqualTo(GroupingReport.EMPTY)
    assertThat(off.holder.value).isEqualTo(HolderState.Unknown)
  }
}
