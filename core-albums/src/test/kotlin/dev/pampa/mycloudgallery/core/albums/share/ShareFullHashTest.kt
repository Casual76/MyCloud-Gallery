package dev.pampa.mycloudgallery.core.albums.share

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.ReferenceWorld
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationIssuer
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.model.Sha256
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * I1 (revisione di sicurezza della Fase 7): le voci degli elementi portano l'hash completo quando il catalogo di chi aggiunge lo conosce
 * (chi legge lo confronta nell'attestazione, D9). Prima nessuna voce lo portava: l'attestazione di chi legge valeva sempre per chiave rapida e
 * dimensione (L32), e un file diverso con la stessa chiave rapida si mostrava al posto del media aggiunto.
 */
class ShareFullHashTest {

  private val base = 1_767_225_600_000L

  private fun media(n: Int, place: MediaPlace, sha: Sha256?) =
    FakeMedia(FakeAlbumLibrary.id(n), base + n * 3_600_000L, place = place, size = 1_000L * n, sha256 = sha)

  private fun sha(n: Int) = Sha256("%064x".format(n))

  private val ref = ReferenceWorld()

  private fun runner() = ShareOrchestrator(ref.ports().let { p ->
    ShareOrchestratorPorts(p.diary, p.facts, p.shared, p.allowed, p.mover, p.uploader, p.tickets, p.publisher, p.safeCopies, p.origins, p.family, p.gate, p.clocks)
  }, newNonce = { ref.nextNonce() })

  private suspend fun ShareOrchestrator.shareAll(title: String, vararg ids: Int): SharePlan {
    val plan = (plan(ShareRequest.NewAlbum(title, ShareLevel.ADD, ids.map { FakeAlbumLibrary.id(it) })) as SharePlanOutcome.Ready).plan
    start(plan, ShareConfirmationIssuer.issue(plan, ref.clocks.monotonicMs()))
    repeat(6) {
      resumeAll(1.minutes)
      ref.clocks.advance(6 * 60_000L)
    }
    return plan
  }

  @Test
  fun laVoceDellElementoPortaLHashCompletoQuandoENoto() = runBlocking<Unit> {
    ref.library.put(media(1, MediaPlace.MY_PRIVATE, sha(1)), media(2, MediaPlace.MY_PRIVATE, null))
    val plan = runner().shareAll("Hash", 1, 2)
    val items = ref.publisher.published(plan.album).values.filterIsInstance<SharedAlbumChange.Item>().associateBy { it.media }
    assertThat(items.keys).containsExactly(FakeAlbumLibrary.id(1), FakeAlbumLibrary.id(2))
    assertThat(items.getValue(FakeAlbumLibrary.id(1)).sha256).isEqualTo(sha(1))
    assertThat(items.getValue(FakeAlbumLibrary.id(2)).sha256).isNull()
  }
}
