package dev.pampa.mycloudgallery.core.albums.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumFamily
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumWriteGate
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Il pubblicatore da provare, con gli orologi, il cancello e la famiglia che lo governano. */
class PublisherWorld(val publisher: AlbumPublisher, val clocks: FakeAlbumClocks, val gate: FakeAlbumWriteGate, val family: FakeAlbumFamily)

/**
 * LA SUITE DI CONTRATTO di [AlbumPublisher] (docs/fase7-progetto.md D6, D30, D38; §2.5 passo 6). La passa il pubblicatore finto; P2 la fa
 * passare al pubblicatore vero (teste di tipo 6, segmenti `album`, `publishExclusive`) sopra un NAS finto.
 */
abstract class AlbumPublisherContract {

  protected abstract fun world(): PublisherWorld

  private val nonce = ByteArray(16) { it.toByte() }
  private val album = AlbumId.derived(AccountId("test"), nonce)
  private val definition = SharedAlbumChange.Definition(
    "Prova", SharePath.of("Album condivisi", "Prova"), SharedState.ACTIVE, AccountId("test"), nonce.joinToString("") { "%02x".format(it) }, null,
  )

  @Test
  fun accodareEIdempotenteEPubblicareLoDice() = runBlocking<Unit> {
    val w = world()
    val first = w.publisher.stage(album, listOf(definition, SharedAlbumChange.Level(ShareLevel.ADD)))
    assertThat(w.publisher.stage(album, listOf(definition)).seq).isAtMost(first.seq)
    assertThat(w.publisher.pending.value).isEqualTo(2)
    assertThat(w.publisher.publishedUpTo(album).seq).isEqualTo(0)
    val out = w.publisher.flush(album, FlushReason.SYNC_ROUND)
    assertThat(out).isInstanceOf(PublishOutcome.Published::class.java)
    assertThat((out as PublishOutcome.Published).upTo).isEqualTo(first)
    assertThat(w.publisher.publishedUpTo(album)).isEqualTo(first)
    assertThat(w.publisher.pending.value).isEqualTo(0)
    // Una modifica gia' pubblicata e identica non si ripubblica.
    assertThat(w.publisher.stage(album, listOf(definition)).seq).isAtMost(first.seq)
    assertThat(w.publisher.flush(album, FlushReason.SYNC_ROUND)).isEqualTo(PublishOutcome.NothingToPublish)
  }

  /** D6: al piu' una testa ogni 5 minuti; le modifiche accorpate in 30 s. */
  @Test
  fun unaTestaOgniCinqueMinutiEAccorpamento() = runBlocking<Unit> {
    val w = world()
    w.publisher.stage(album, listOf(definition))
    w.clocks.advance(PublishRules.DEBOUNCE_MS - 1)
    assertThat(w.publisher.flush(album, FlushReason.DEBOUNCE)).isInstanceOf(PublishOutcome.TooSoon::class.java)
    w.clocks.advance(1)
    assertThat(w.publisher.flush(album, FlushReason.DEBOUNCE)).isInstanceOf(PublishOutcome.Published::class.java)
    w.publisher.stage(album, listOf(SharedAlbumChange.Sort(AlbumSort.OLDEST_FIRST)))
    w.clocks.advance(PublishRules.DEBOUNCE_MS)
    val soon = w.publisher.flush(album, FlushReason.SYNC_ROUND)
    assertThat(soon).isInstanceOf(PublishOutcome.TooSoon::class.java)
    w.clocks.advance((soon as PublishOutcome.TooSoon).retryAfterMs)
    assertThat(w.publisher.flush(album, FlushReason.SYNC_ROUND)).isInstanceOf(PublishOutcome.Published::class.java)
  }

  /** D30, D38: un cancello chiuso non pubblica e non perde niente. */
  @Test
  fun unCancelloChiusoTieneLeModificheInCoda() = runBlocking<Unit> {
    val w = world()
    val ticket = w.publisher.stage(album, listOf(definition))
    w.family.common = "famiglia"
    assertThat(w.publisher.flush(album, FlushReason.SYNC_ROUND)).isEqualTo(PublishOutcome.Blocked(AlbumWriteBlock.TEST_SHARES_ONLY))
    w.family.common = "test-comune"
    w.gate.blocks["test-comune"] = AlbumWriteBlock.CLOCK_JUMP
    assertThat(w.publisher.flush(album, FlushReason.SYNC_ROUND)).isEqualTo(PublishOutcome.Blocked(AlbumWriteBlock.CLOCK_JUMP))
    assertThat(w.publisher.pending.value).isEqualTo(1)
    w.gate.blocks.clear()
    assertThat((w.publisher.flush(album, FlushReason.SYNC_ROUND) as PublishOutcome.Published).upTo).isEqualTo(ticket)
  }
}
