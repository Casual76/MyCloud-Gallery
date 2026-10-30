package dev.pampa.mycloudgallery.core.faces.grouping

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.ClusterSnapshot
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingReport
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.sync.FacesWire
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le correzioni della Fase 8 sul custode (pacchetto "formato e replica dei volti"): R3 (le voci `people` non creano persone dal nulla; una
 * `forget` revocata con la sua provenienza fa tornare la persona), R4 (un'istantanea con la `seq` vicina al tetto non ferma il custode per
 * sempre), R6 (al piu' quattro riallineamenti per passo). Ogni prova fallisce senza la sua correzione.
 */
class CustodianFormatCorrectionsTest {
  private val budget = 5.minutes
  private val story = FamilyStories.story(FamilyStories.Config(seed = 808, identities = 6, photos = 120, siblings = 0, strangerShare = 0.05f))

  private fun random(n: Int): PersonId = PersonId.fromBytes(MessageDigest.getInstance("SHA-256").digest("a-caso-$n".toByteArray()).copyOf(16))

  private suspend fun custodian(): ClusterDevice {
    val nas = ClusterNas()
    val a = ClusterDevice("aaaa", nas, idsSeed = 11)
    a.receive(story.faces)
    a.grouping.incremental(budget)
    a.grouping.nightly(budget)
    return a
  }

  @Test
  fun r3_migliaiaDiVociVersoIdACasoNonCreanoPersone() = runTest {
    val a = custodian()
    val before = a.archive.grouping()
    val someone = before.persons.values.first { it.state == PersonState.ACTIVE }.id
    // Un dispositivo compromesso: 2.000 alias, nascite e "Dimentica" verso id che nessuna istantanea e nessun volto nominano.
    val at = a.nas.now
    for (n in 0 until 1_000) a.name(PeopleEdit.Alias(random(n), random(n + 50_000)), at)
    for (n in 1_000 until 1_500) a.name(PeopleEdit.Born(random(n), someone), at)
    for (n in 1_500 until 2_000) a.name(PeopleEdit.Forget(random(n)), at)
    a.grouping.incremental(budget)
    a.grouping.nightly(budget)
    val after = a.archive.grouping()
    assertThat(after.persons.size).isEqualTo(before.persons.size)
    assertThat(after.persons.keys).isEqualTo(before.persons.keys)
    // E l'istantanea resta pubblicabile.
    assertThat(FacesWire.snapshotEntries(after, a.archive.groupFaces())).isNotNull()
  }

  @Test
  fun r3_unaDimenticataLaCuiVoceSparisceConLaRevocaTorna() = runTest {
    val a = custodian()
    val start = a.archive.grouping()
    val victim = start.persons.values.filter { it.state == PersonState.ACTIVE }.maxBy { p -> start.assignments.values.count { it.person == p.id } }.id
    val faces = start.assignments.values.filter { it.person == victim && it.origin != AssignOrigin.IGNORED }.map { it.face }.toSet()
    assertThat(faces).isNotEmpty()
    // Una "Dimentica" falsa, portata da un dispositivo compromesso (provenienza "male").
    a.archive.addPeopleFrom("male", listOf(dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord(PeopleEdit.Forget(victim), a.nas.now, "bbbb")))
    a.grouping.incremental(budget)
    val forgotten = a.archive.grouping()
    assertThat(forgotten.persons.getValue(victim).state).isEqualTo(PersonState.FORGOTTEN)
    // La revoca di "male" toglie la voce con la sua provenienza: al passo dopo la persona torna, con i suoi volti.
    a.archive.dropAuthors(setOf("male"))
    a.grouping.incremental(budget)
    val back = a.archive.grouping()
    assertThat(back.persons.getValue(victim).state).isEqualTo(PersonState.ACTIVE)
    val again = back.assignments.values.filter { it.person == victim && it.origin != AssignOrigin.IGNORED }.map { it.face }.toSet()
    // I suoi volti tornano a lei (il passo puo' poi espellere qualche volto lontano dagli esemplari, come per ogni persona).
    assertThat(faces).containsAtLeastElementsIn(again)
    assertThat(again.size).isAtLeast(faces.size * 8 / 10)
  }

  @Test
  fun r3_unaDimenticataVeraResta() = runTest {
    val a = custodian()
    val start = a.archive.grouping()
    val victim = start.persons.values.first { it.state == PersonState.ACTIVE }.id
    a.name(PeopleEdit.Forget(victim), a.nas.now)
    a.grouping.incremental(budget)
    a.grouping.nightly(budget)
    assertThat(a.archive.grouping().persons.getValue(victim).state).isEqualTo(PersonState.FORGOTTEN)
  }

  @Test
  fun r4_unIstantaneaAdottataVicinoAlTettoNonFermaIlCustode() = runTest {
    val a = custodian()
    val honest = a.nas.latest("aaaa")!!.state.seq
    // Lo stato locale e' quello di un'istantanea adottata da un dispositivo compromesso con `seq` = 10^9 (prima della correzione si accettava).
    val local = a.archive.grouping()
    a.archive.saveGrouping(GroupingState(1_000_000_000L, 999_999_999L, local.persons.mapValues { (_, p) -> p.copy(changedSeq = 1_000_000_000L) }, local.assignments, local.unassignedAtBirths))
    a.receive(FamilyStories.story(FamilyStories.Config(seed = 809, identities = 3, photos = 40, siblings = 0, strangerShare = 0.0f)).faces)
    a.grouping.incremental(budget)
    a.grouping.nightly(budget)
    val after = a.archive.grouping()
    assertThat(after.seq).isAtMost(TrustLimits.MAX_SNAPSHOT_SEQ)
    assertThat(after.seq).isGreaterThan(honest)
    // L'istantanea si pubblica di nuovo e passa le regole di chi legge.
    assertThat(FacesWire.snapshotEntries(after, a.archive.groupFaces())).isNotNull()
    assertThat(a.nas.latest("aaaa")!!.state.seq).isEqualTo(after.seq)
  }

  @Test
  fun r6_alPiuQuattroRiallineamentiPerPasso() = runTest {
    val a = custodian()
    val nas = a.nas
    val base = a.archive.grouping()
    // Sei dispositivi pubblicano istantanee piu' vecchie ciascuna con una persona orfana: il custode le assorbe al piu' quattro per passo.
    for (k in 0 until 6) {
      val orphan = random(90_000 + k)
      val persons = base.persons + (orphan to GroupPerson(orphan, PersonState.ACTIVE, null, 1, 1, emptyList()))
      nas.published.getOrPut("d00$k") { ArrayList() } += ClusterSnapshot("d00$k", GroupingState(1, 0, persons, emptyMap()))
    }
    val rebased = a.grouping.rebased.value
    val report: GroupingReport = a.grouping.incremental(budget)
    assertThat(report).isNotNull()
    assertThat(a.grouping.rebased.value - rebased).isAtMost(CustodianGrouping.MAX_REALIGNS_PER_PASS)
    a.grouping.incremental(budget)
    assertThat(a.grouping.rebased.value - rebased).isAtLeast(6)
  }
}
