package dev.pampa.mycloudgallery.core.albums.share

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPublisher
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ReferenceWorld
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationIssuer
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.StageTicket
import dev.pampa.mycloudgallery.core.albums.contract.TransferItemOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TransferRefusal
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumPublisher
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.albums.testing.SimulatedDeath
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * "Nessuna voce per un file fuori dall'area comune" come **proprieta'** (riga P4 del §4.4; invariante 6 del §5.5): su 400 storie casuali
 * (album nuovi, aggiunte a un album di un altro, "Riporta", livelli cambiati a meta' (E-SH13), album chiusi dal creatore, rifiuti e attese
 * delle porte, esiti per file (E-SH18), il pubblicatore che non riesce, annullamenti, la morte del processo a un salvataggio qualunque del
 * diario) ogni voce `item:` che l'orchestratore **accoda** e ogni voce che **finisce in una testa** rimanda a un file che il catalogo vede in
 * quel momento nell'area comune, al percorso che la voce porta. In piu' alla fine: ogni elemento finito, nessun media sparito dal catalogo,
 * ogni trasferimento nato da una conferma registrata nel diario con la stessa impronta (invariante 7).
 */
class ShareEntriesPropertyTest {

  /** Il pubblicatore finto con il controllo della proprieta' a ogni voce accodata e a ogni voce pubblicata. */
  private class CheckingPublisher(private val inner: FakeAlbumPublisher, private val library: FakeAlbumLibrary) : AlbumPublisher {
    val violations = mutableListOf<String>()
    var checked = 0
      private set

    private fun check(item: SharedAlbumChange.Item, what: String) {
      checked++
      val attested = library.attestedNow(listOf(item.contentKey))[item.contentKey]
      if (attested == null) {
        violations += "voce $what senza il file nell'area comune"
        return
      }
      val m = library[attested]
      if (m?.place != MediaPlace.COMMON || m.path != item.path.value) violations += "voce $what con un percorso che non e' quello del file"
    }

    override suspend fun stage(album: AlbumId, changes: List<SharedAlbumChange>): StageTicket {
      changes.filterIsInstance<SharedAlbumChange.Item>().forEach { check(it, "accodata") }
      return inner.stage(album, changes)
    }

    override suspend fun flush(album: AlbumId, reason: FlushReason): PublishOutcome {
      val before = inner.published(album)
      val out = inner.flush(album, reason)
      for ((key, value) in inner.published(album)) if (before[key] != value && value is SharedAlbumChange.Item) check(value, "pubblicata")
      return out
    }

    override suspend fun publishedUpTo(album: AlbumId): StageTicket = inner.publishedUpTo(album)

    override val pending: StateFlow<Int> get() = inner.pending
  }

  private val base = 1_767_225_600_000L

  private companion object {
    val FAILURES = listOf(TransferItemOutcome.SOURCE_CHANGED, TransferItemOutcome.SOURCE_GONE, TransferItemOutcome.FAILED, TransferItemOutcome.CANCELLED)
  }

  @Test
  fun nessunaVoceRimandaAUnFileFuoriDallAreaComune() = runBlocking<Unit> {
    var checks = 0
    var deaths = 0
    for (seed in 0 until 400) {
      val rnd = Random(seed * 7919L + 13)
      val w = ReferenceWorld()
      val publisher = CheckingPublisher(w.publisher, w.library)
      val places = listOf(MediaPlace.MY_PRIVATE, MediaPlace.MY_PRIVATE, MediaPlace.PHONE_ONLY, MediaPlace.COMMON, MediaPlace.OTHERS_PRIVATE)
      val count = 5 + rnd.nextInt(8)
      for (n in 1..count) {
        val place = places[rnd.nextInt(places.size)]
        w.library.put(
          FakeMedia(
            FakeAlbumLibrary.id(n), base + n * 3_600_000L, place = place, onPhone = place == MediaPlace.PHONE_ONLY || rnd.nextBoolean(),
            mine = place != MediaPlace.OTHERS_PRIVATE && (place != MediaPlace.COMMON || rnd.nextBoolean()), hidden = rnd.nextInt(7) == 0, size = 500L + n,
          ),
        )
      }
      val ids = (1..count).map { FakeAlbumLibrary.id(it) }
      val theirs = w.albumBy(AccountId("test2"), "Di Bruno", ShareLevel.entries[rnd.nextInt(ShareLevel.entries.size)])
      fun ports() = w.ports().forOrchestrator().let { p ->
        ShareOrchestratorPorts(p.diary, p.facts, p.shared, p.allowed, p.mover, p.uploader, p.tickets, publisher, p.safeCopies, p.origins, p.family, p.gate, p.clocks)
      }
      var runner = ShareOrchestrator(ports(), newNonce = { w.nextNonce() })
      val mineAlbums = mutableListOf<AlbumId>()
      suspend fun guarded(block: suspend () -> Unit) {
        try {
          block()
        } catch (e: SimulatedDeath) {
          deaths++
          w.diary.crashAtSave = null
          runner = ShareOrchestrator(ports(), newNonce = { w.nextNonce() })
        }
      }
      repeat(8 + rnd.nextInt(10)) {
        when (rnd.nextInt(12)) {
          0, 1 -> guarded {
            val pick = ids.shuffled(rnd).take(1 + rnd.nextInt(count))
            val hidden = if (rnd.nextBoolean()) HiddenChoice.LEAVE_OUT else HiddenChoice.SHARE_TOO
            val out = runner.plan(ShareRequest.NewAlbum("Album $seed-$it", ShareLevel.entries[rnd.nextInt(3)], pick, hidden))
            if (out is SharePlanOutcome.Ready) {
              runner.start(out.plan, ShareConfirmationIssuer.issue(out.plan, w.clocks.monotonicMs()))
              mineAlbums += out.plan.album
            }
          }
          2 -> guarded {
            val out = runner.plan(ShareRequest.AddToShared(theirs, ids.shuffled(rnd).take(1 + rnd.nextInt(3))))
            if (out is SharePlanOutcome.Ready) runner.start(out.plan, ShareConfirmationIssuer.issue(out.plan, w.clocks.monotonicMs()))
          }
          3 -> guarded {
            val album = (mineAlbums + theirs).random(rnd)
            val members = w.shared.album(album)?.items?.values?.filter { it.addedBy == AccountId("test") }?.map { it.media }.orEmpty()
            if (members.isNotEmpty()) {
              val out = runner.plan(ShareRequest.BringBack(album, members.shuffled(rnd).take(1 + rnd.nextInt(members.size))))
              if (out is SharePlanOutcome.Ready) {
                assertThat(out.plan.items.map { it.action }.toSet()).containsExactly(ShareItemAction.MOVE_OUT)
                runner.start(out.plan, ShareConfirmationIssuer.issue(out.plan, w.clocks.monotonicMs()))
              }
            }
          }
          4 -> w.shared.apply(theirs, AccountId("test2"), listOf(SharedAlbumChange.Level(ShareLevel.entries[rnd.nextInt(3)])))
          5 -> when (rnd.nextInt(5)) {
            0 -> w.transfers.refuseNext = TransferRefusal.entries[rnd.nextInt(TransferRefusal.entries.size)]
            1 -> w.transfers.waiting = if (w.transfers.waiting == null) ShareWait.entries[rnd.nextInt(ShareWait.entries.size)] else null
            // Solo gli esiti di un file che non arriva: "arrivato" lo decide il trasferimento finto, che aggiorna anche il catalogo (D29).
            2 -> w.transfers.outcomes[ids.random(rnd)] = FAILURES[rnd.nextInt(FAILURES.size)]
            3 -> w.transfers.crashAfterStart = true
            else -> w.publisher.failNext = true
          }
          6 -> if (rnd.nextInt(4) == 0) {
            val a = w.shared.album(theirs)
            if (a != null && a.state == SharedState.ACTIVE) {
              w.shared.apply(theirs, AccountId("test2"), listOf(SharedAlbumChange.Definition(a.title, a.folder, SharedState.CLOSED, a.creator, a.nonceHex, OthersAfterClose.KEEP_COPY)))
            }
          }
          7 -> guarded { w.diary.openOps().randomOrNull(rnd)?.let { runner.cancel(it.id) } }
          8 -> {
            // La morte del processo a un salvataggio qualunque, o subito (la conferma in memoria si perde).
            if (rnd.nextBoolean()) {
              w.diary.crashAtSave = w.diary.saves + 1 + rnd.nextInt(6)
              w.diary.crashAfterWrite = rnd.nextBoolean()
            } else {
              runner = ShareOrchestrator(ports(), newNonce = { w.nextNonce() })
            }
          }
          9 -> guarded {
            mineAlbums.randomOrNull(rnd)?.let { w.sharedAlbums.close(it, if (rnd.nextBoolean()) CloseMode.STOP else CloseMode.DELETE, OthersAfterClose.REMOVE) }
          }
          else -> guarded {
            runner.resumeAll(1.minutes)
            w.clocks.advance((1 + rnd.nextInt(7)) * 60_000L)
          }
        }
      }
      // Fine dei guasti: le porte tornano normali e il lavoro arriva in fondo.
      w.diary.crashAtSave = null
      w.transfers.refuseNext = null
      w.transfers.waiting = null
      w.transfers.outcomes.clear()
      w.transfers.crashAfterStart = false
      w.publisher.failNext = false
      runner = ShareOrchestrator(ports(), newNonce = { w.nextNonce() })
      repeat(10) {
        runner.resumeAll(1.minutes)
        w.clocks.advance(6 * 60_000L)
      }
      val label = "seme $seed"
      assertWithMessage(label).that(publisher.violations).isEmpty()
      assertWithMessage("$label: elementi non finiti").that(w.diary.allItems().filter { !it.state.terminal }).isEmpty()
      assertWithMessage("$label: media spariti").that(w.library.all().map { it.id }).containsExactlyElementsIn(ids)
      val planHashes = w.diary.allOps().map { it.planHash }.toSet()
      for (t in w.transfers.started) assertWithMessage("$label: trasferimento senza conferma nel diario").that(t.planHash).isIn(planHashes)
      checks += publisher.checked
    }
    assertThat(checks).isAtLeast(400)
    assertThat(deaths).isAtLeast(20)
    println("P4 proprieta': 400 storie, $checks voci controllate, $deaths morti del processo, 0 violazioni")
  }
}
