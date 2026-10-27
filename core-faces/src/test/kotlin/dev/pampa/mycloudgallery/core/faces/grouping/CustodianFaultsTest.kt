package dev.pampa.mycloudgallery.core.faces.grouping

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.ClusterSnapshots
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesLease
import dev.pampa.mycloudgallery.core.faces.contract.FacesLeases
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.GoneContents
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingCorrections
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.HolderState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * LA MORTE DEL PROCESSO A OGNI CHIAMATA (docs/fase8-progetto.md §4.3 "ogni passo in piu' tappe e' idempotente e ha il test 'guasto a ogni
 * chiamata' e la morte del processo a ogni salvataggio": qui l'istantanea). Un giro del custode muore alla chiamata k-esima di una porta
 * (prima o dopo il suo effetto); un processo nuovo (stesso archivio, stesso NAS, stessa coda del pubblicatore, durevoli) rifa' il giro:
 * l'istantanea pubblicata e' quella dell'archivio, nessuna persona si perde, le regole valgono. E la generazione che cambia a meta'.
 */
class CustodianFaultsTest {
  private val budget = 5.minutes
  private val story = FamilyStories.story(FamilyStories.Config(seed = 505, identities = 8, photos = 150, siblings = 1))
  private val keys = story.faces.map { it.ref.contentKey }.distinct()
  private val first = story.faces.filter { keys.indexOf(it.ref.contentKey) < keys.size / 2 }

  private class ProcessDeath : RuntimeException("morte del processo")

  /** Il piano dei guasti: la chiamata numero [dieAt] (contando tutte le porte) muore prima ([afterEffect] falso) o dopo il suo effetto. */
  private class Plan(val dieAt: Int, val afterEffect: Boolean) {
    var calls = 0
    var died = false

    suspend fun <T> around(block: suspend () -> T): T {
      calls++
      val here = calls
      if (here == dieAt && !afterEffect) die()
      val result = block()
      if (here == dieAt && afterEffect) die()
      return result
    }

    private fun die(): Nothing {
      died = true
      throw ProcessDeath()
    }
  }

  private class FaultyArchive(private val inner: InMemoryFacesArchive, private val plan: Plan) : FacesArchive by inner {
    override suspend fun groupFaces(): List<GroupFace> = plan.around { inner.groupFaces() }
    override suspend fun grouping(): GroupingState = plan.around { inner.grouping() }
    override suspend fun saveGrouping(state: GroupingState) = plan.around { inner.saveGrouping(state) }
    override suspend fun people(): Map<PeopleKey, PeopleRecord> = plan.around { inner.people() }
  }

  private fun custodian(device: ClusterDevice, plan: Plan, seed: Long): CustodianGrouping {
    val leases = device.nas.leases(device.id)
    val publisher = device.publisher
    return CustodianGrouping(
      archives = { FaultyArchive(device.archive, plan) },
      deviceId = device.id,
      leases = object : FacesLeases {
        override suspend fun acquire(generation: FaceGeneration, resource: FacesResource) = plan.around { leases.acquire(generation, resource) }
        override suspend fun renew(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held) = plan.around { leases.renew(generation, resource, held) }
        override suspend fun release(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held) = plan.around { leases.release(generation, resource, held) }
      },
      snapshots = ClusterSnapshots { g -> plan.around { device.nas.snapshots.latest(g) } },
      publisher = object : FacesPublisher {
        override val pending: StateFlow<Int> = publisher.pending
        override suspend fun stage(change: FacesChange) = plan.around { publisher.stage(change) }
        override suspend fun flush(reason: FlushReason): FacesPublishOutcome = plan.around { publisher.flush(reason) }
      },
      gone = GoneContents { _ -> plan.around { emptySet<ContentKey>() } },
      ids = GroupingStories.ids(seed),
    )
  }

  @Test
  fun unaMorteAOgniChiamataNonPerdeNienteENonPubblicaAMeta() = runTest {
    // Quante chiamate fa un giro normale.
    val probe = ClusterNas()
    val counted = ClusterDevice("aaaa", probe, idsSeed = 1).apply { receive(first) }
    val plan0 = Plan(dieAt = Int.MAX_VALUE, afterEffect = false)
    custodian(counted, plan0, 1).incremental(budget)
    val calls = plan0.calls
    assertThat(calls).isAtLeast(8)
    for (k in 1..calls) {
      for (after in listOf(false, true)) {
        val nas = ClusterNas()
        val device = ClusterDevice("aaaa", nas, idsSeed = 1)
        device.receive(first)
        // Un giro che muore alla chiamata k.
        val plan = Plan(dieAt = k, afterEffect = after)
        try {
          custodian(device, plan, 10L + k).incremental(budget)
        } catch (e: ProcessDeath) {
          // Il processo e' morto: il suo stato in memoria (prenotazione tenuta, custode) e' perso.
        }
        assertWithMessage("chiamata $k").that(plan.died).isTrue()
        val partial = device.archive.grouping()
        // Un processo nuovo rifa' il giro (con altri volti arrivati intanto) e porta tutto in pari.
        device.receive(story.faces)
        val again = custodian(device, Plan(Int.MAX_VALUE, false), 100L + k)
        again.incremental(budget)
        again.nightly(budget)
        val state = device.archive.grouping()
        assertWithMessage("chiamata $k (dopo=$after): custode").that(again.holder.value).isInstanceOf(HolderState.Holder::class.java)
        val published = nas.latest("aaaa")
        assertWithMessage("chiamata $k (dopo=$after): istantanea pubblicata").that(published).isNotNull()
        assertWithMessage("chiamata $k (dopo=$after): pubblicata e' quella salvata").that(published!!.state.seq).isEqualTo(state.seq)
        assertThat(published.state.persons).isEqualTo(state.persons)
        assertThat(device.staged).isEmpty()
        for (id in partial.persons.keys) assertWithMessage("chiamata $k: persona persa").that(state.persons).containsKey(id)
        assertThat(Invariants.violations(state, story.faces, GroupingCorrections.NONE)).isEmpty()
      }
    }
  }

  @Test
  fun laGenerazioneCheCambiaNonUsaNeLaPrenotazioneNeLeIstantaneeVecchie() = runTest {
    val old = ClusterNas()
    val device = ClusterDevice("aaaa", old, idsSeed = 1)
    device.receive(first)
    device.grouping.incremental(budget)
    assertThat(old.latest("aaaa")).isNotNull()
    // "Disattiva e cancella" e una nuova attivazione: generazione nuova, archivio nuovo e vuoto. Le chiamate portano la generazione nuova:
    // la prenotazione vecchia non si rinnova, le istantanee della generazione vecchia non si leggono.
    val fresh = ClusterNas(FaceGeneration.fromBytes(ByteArray(16) { 8 }))
    val renewed = ArrayList<FaceGeneration>()
    val acquired = ArrayList<FaceGeneration>()
    val read = ArrayList<FaceGeneration>()
    var archive = device.archive
    val grouping = CustodianGrouping(
      archives = { archive },
      deviceId = "aaaa",
      leases = object : FacesLeases {
        override suspend fun acquire(generation: FaceGeneration, resource: FacesResource): FacesLease {
          acquired += generation
          return (if (generation == old.generation) old else fresh).leases("aaaa").acquire(generation, resource)
        }
        override suspend fun renew(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held): FacesLease {
          renewed += generation
          return (if (generation == old.generation) old else fresh).leases("aaaa").renew(generation, resource, held)
        }
        override suspend fun release(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held) = Unit
      },
      snapshots = ClusterSnapshots { g ->
        read += g
        old.snapshots.latest(g) + fresh.snapshots.latest(g)
      },
      publisher = device.publisher,
      gone = GoneContents { emptySet() },
      ids = GroupingStories.ids(3),
    )
    grouping.incremental(budget)
    archive = InMemoryFacesArchive(fresh.generation)
    ClusterDevice.bodies(first).forEach { archive.saveBody(it) }
    grouping.incremental(budget)
    assertThat(acquired).containsExactly(old.generation, fresh.generation).inOrder()
    assertThat(renewed).doesNotContain(fresh.generation)
    assertThat(read.last()).isEqualTo(fresh.generation)
    // Nell'archivio nuovo niente della generazione vecchia: le persone sono nate da capo.
    val oldPersons = old.latest("aaaa")!!.state.persons.keys
    assertThat(archive.grouping().persons.keys.intersect(oldPersons)).isEmpty()
  }
}
