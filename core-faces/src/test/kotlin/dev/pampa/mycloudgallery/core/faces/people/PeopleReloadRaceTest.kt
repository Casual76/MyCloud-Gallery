package dev.pampa.mycloudgallery.core.faces.people

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PeopleWorld
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesClock
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * "E' la stessa persona?" -> "No" vale **subito**, anche se una lettura dell'archivio partita prima (la prima delle viste, un giro di replica, il
 * custode) finisce dopo l'azione. Test stabili (03/11): `ArchivePeopleContractTest.proposteERisposte` cadeva una volta su cento sotto carico
 * perche' la lettura iniziale, partita su un altro thread, rimetteva in memoria le voci di **prima** sopra quelle che l'azione aveva appena scritto;
 * `proposals()` mostrava ancora la coppia respinta. Qui l'ordine non e' lasciato al caso: la lettura vecchia si ferma dopo aver preso le voci, l'azione
 * scrive, e solo allora la lettura riprende.
 */
class PeopleReloadRaceTest {

  @Test
  fun unNoDettoMentreUnaLetturaVecchiaFinisceValeSubito() = runBlocking<Unit> {
    val world = PeopleWorld()
    val real = InMemoryFacesArchive(GENERATION)
    ArchiveSeeding.seed(real, world.faces, world.state, listOf(world.none), ArchivePeopleContractTest.nearerTo(world))
    val archive = StallingArchive(real)
    val publisher = StagedPublisher()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      // La lettura iniziale di `init` (un altro thread) prende le voci di prima e si ferma prima di rimetterle in memoria.
      val people = ArchivePeople(
        archives = { archive },
        cells = WorldCells(world),
        visibility = PeopleVisibility(world.visibility, world.visibility),
        publisher = publisher,
        clock = FakeFacesClock(),
        author = world.author,
        scope = scope,
        ids = GroupingStories.ids(99),
      )
      // Solo una guardia contro un'attesa che non finisce (un guasto della prova): l'ordine dei fatti non dipende dal tempo.
      withTimeout(GUARD_MS) {
        archive.reading.await()
        val proposal = people.proposals().single()
        // "No": le voci nuove sono scritte e in memoria (`staged`), poi l'azione aspetta le viste, ferme dietro la lettura vecchia.
        val answer = async(Dispatchers.Default) { people.answer(proposal, same = false) }
        publisher.staged.await()
        archive.gate.complete(Unit)
        assertThat(answer.await()).isEqualTo(EditOutcome.Done)
        assertThat(people.proposals()).isEmpty()
        assertThat(people.person(world.a).first()!!.maybeAlso).isEmpty()
      }
    } finally {
      scope.cancel()
    }
  }

  /** L'archivio vero con una sola lettura delle voci che si ferma, dopo averle prese, finche' la prova non la lascia andare. */
  private class StallingArchive(private val real: FacesArchive) : FacesArchive by real {
    @Volatile private var stall = true
    val reading = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()

    override suspend fun people(): Map<PeopleKey, PeopleRecord> {
      val now = real.people()
      if (stall) {
        stall = false
        reading.complete(Unit)
        gate.await()
      }
      return now
    }
  }

  /** Il pubblicatore che dice quando gli e' arrivata la prima voce: l'azione ha gia' scritto e messo in memoria le sue voci. */
  private class StagedPublisher : FacesPublisher {
    val staged = CompletableDeferred<Unit>()

    override suspend fun stage(change: FacesChange) {
      staged.complete(Unit)
    }

    override suspend fun flush(reason: FlushReason): FacesPublishOutcome = FacesPublishOutcome.NothingToPublish

    override val pending: StateFlow<Int> = MutableStateFlow(0)
  }

  private companion object {
    val GENERATION: FaceGeneration = FaceGeneration.fromBytes(ByteArray(16) { 9 })
    const val GUARD_MS = 60_000L
  }
}
