package dev.pampa.mycloudgallery.core.albums.contract

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.albums.testing.FakeSharedWorld
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Due dispositivi di due account ([alice] = `test`, creatore; [bruno] = `test2`) sopra la stessa area comune [common]. */
class SharedWorld(val common: FakeSharedWorld, val alice: ReferenceWorld, val bruno: ReferenceWorld)

/**
 * LA SUITE DI CONTRATTO di [SharedAlbums] (docs/fase7-progetto.md §2.4, §2.6; D10, D11, D31 adeguata alla risposta Q5; E-SH7, E-SH11,
 * E-SH14, E-SH21). La passano i finti (`FakeSharedAlbums` sopra `FakeSharedWorld`); P2 e P4 la fanno passare a replica, permessi e
 * chiusura veri. La prima prova e' generata dalla tabella del §2.4: cio' che `allowed` offre a chi scrive e' esattamente cio' che chi legge
 * accetta, per ogni livello, ogni riga con una voce e ogni ruolo.
 */
abstract class SharedAlbumsContract {

  protected abstract fun world(): SharedWorld

  private var n = 0

  /** Un file nell'area comune, visto dal catalogo di entrambi (ognuno scansiona l'area comune da se', D9). */
  private fun SharedWorld.commonFile(owner: ReferenceWorld): FakeMedia {
    val m = FakeMedia(FakeAlbumLibrary.id(++n), 1_767_225_600_000L + n * 60_000L, place = MediaPlace.COMMON, path = "Album condivisi/Gita/f$n.jpg")
    alice.library.put(m.copy(mine = owner === alice))
    bruno.library.put(m.copy(mine = owner === bruno))
    return m
  }

  private fun SharedWorld.item(m: FakeMedia, by: ReferenceWorld) =
    common.apply(currentAlbum, by.me, listOf(SharedAlbumChange.Item(m.id, null, m.contentKey, SharePath.parse(m.path!!), 1_000L * n, by.me)))

  private var current: AlbumId? = null
  private val currentAlbum: AlbumId get() = current!!

  /** Un album di alice al livello [level], con un elemento di alice e uno di bruno (aggiunti quando il livello lo permetteva). */
  private fun SharedWorld.album(level: ShareLevel): Triple<AlbumId, FakeMedia, FakeMedia> {
    current = alice.albumBy(alice.me, "Gita", ShareLevel.ALL)
    val a = commonFile(alice)
    val b = commonFile(bruno)
    item(a, alice)
    item(b, bruno)
    common.apply(currentAlbum, alice.me, listOf(SharedAlbumChange.Level(level)))
    return Triple(currentAlbum, a, b)
  }

  @Test
  fun allowedECioCheChiLeggeAccettaCoincidonoPerOgniRigaELivello() = runBlocking<Unit> {
    for (level in ShareLevel.entries) {
      for (actorIsCreator in listOf(true, false)) {
        val cases = listOf(AlbumOp.ADD_MEDIA, AlbumOp.REMOVE_ITEM, AlbumOp.RENAME, AlbumOp.SET_COVER, AlbumOp.SET_SORT, AlbumOp.CHANGE_LEVEL)
        for (op in cases) {
          for (ownItem in listOf(true, false)) {
            if (op != AlbumOp.REMOVE_ITEM && !ownItem) continue
            val w = world()
            val (album, a, b) = w.album(level)
            val actor = if (actorIsCreator) w.alice else w.bruno
            val target = if (ownItem == actorIsCreator) a else b
            val offered = actor.sharedAlbums.allowed(album, op, target.id.takeIf { op == AlbumOp.REMOVE_ITEM })
            val fresh = w.commonFile(actor)
            val change = when (op) {
              AlbumOp.ADD_MEDIA -> SharedAlbumChange.Item(fresh.id, null, fresh.contentKey, SharePath.parse(fresh.path!!), 9_000, actor.me)
              AlbumOp.REMOVE_ITEM -> SharedAlbumChange.ItemRemoved(target.id)
              AlbumOp.RENAME -> SharedAlbumChange.Definition(
                "Altro titolo", w.common.album(album)!!.folder, SharedState.ACTIVE, w.alice.me, w.common.album(album)!!.nonceHex, null,
              )
              AlbumOp.SET_COVER -> SharedAlbumChange.Cover(a.id, a.contentKey)
              AlbumOp.SET_SORT -> SharedAlbumChange.Sort(AlbumSort.OLDEST_FIRST)
              else -> SharedAlbumChange.Level(ShareLevel.VIEW)
            }
            val accepted = w.common.apply(album, actor.me, listOf(change)) == 1
            assertWithMessage("$level ${op.code} creatore=$actorIsCreator proprio=$ownItem").that(offered).isEqualTo(accepted)
          }
        }
      }
    }
  }

  /** Q5 (a): l'album sparisce per gli altri con l'avviso, senza copia; il creatore lo tiene come personale (D31). */
  @Test
  fun smettereConSparisceLAlbumSparisceSenzaCopia() = runBlocking<Unit> {
    val w = world()
    val (album, _, _) = w.album(ShareLevel.ADD)
    assertThat(w.bruno.sharedAlbums.visible.value.map { it.id }).containsExactly(album)
    assertThat(w.bruno.sharedAlbums.close(album, CloseMode.STOP, OthersAfterClose.REMOVE)).isEqualTo(CloseOutcome.NotAllowed(AlbumOp.STOP_SHARING))
    assertThat(w.alice.sharedAlbums.close(album, CloseMode.STOP, OthersAfterClose.REMOVE)).isEqualTo(CloseOutcome.Closed)
    assertThat(w.bruno.sharedAlbums.visible.value).isEmpty()
    val notice = w.bruno.sharedAlbums.notices.value.single() as SharedAlbumNotice.Closed
    assertThat(notice.mode).isEqualTo(CloseMode.STOP)
    assertThat(notice.keptCopy).isNull()
    assertThat(w.bruno.sharedAlbums.keptCopies()).isEmpty()
    assertThat(w.alice.sharedAlbums.keptCopies().single().items).hasSize(2)
    // I file restano nell'area comune.
    assertThat(w.bruno.library.all().count { it.place == MediaPlace.COMMON }).isEqualTo(2)
  }

  /** Q5 (b): ognuno tiene una copia personale con le stesse foto (id deterministico per account), anche dopo "Elimina". */
  @Test
  fun eliminareConLaCopiaLasciaUnaCopiaAGliAltri() = runBlocking<Unit> {
    val w = world()
    val (album, a, b) = w.album(ShareLevel.ADD)
    val preview = (w.alice.sharedAlbums.closePreview(album, CloseMode.DELETE) as ClosePreviewOutcome.Ready).preview
    assertThat(preview.bringBackCandidates).containsExactly(a.id)
    assertThat(preview.bringBackHurtsCopies).isTrue()
    assertThat(w.alice.sharedAlbums.close(album, CloseMode.DELETE, OthersAfterClose.KEEP_COPY)).isEqualTo(CloseOutcome.Closed)
    val copy = SharedAlbumCopies.copyIdFor(album, w.bruno.me)
    assertThat((w.bruno.sharedAlbums.notices.value.single() as SharedAlbumNotice.Closed).keptCopy).isEqualTo(copy)
    val kept = w.bruno.sharedAlbums.keptCopies().single()
    assertThat(kept.copy).isEqualTo(copy)
    assertThat(kept.items).containsExactly(a.id, b.id)
    // Il creatore che elimina non tiene niente.
    assertThat(w.alice.sharedAlbums.keptCopies()).isEmpty()
    assertThat(copy).isNotEqualTo(album)
    assertThat(SharedAlbumCopies.copyIdFor(album, w.alice.me)).isNotEqualTo(copy)
  }

  /** D31: chiuso o eliminato e' definitivo; una definizione `active` dopo non lo riapre. */
  @Test
  fun unAlbumChiusoNonSiRiapre() = runBlocking<Unit> {
    val w = world()
    val (album, _, _) = w.album(ShareLevel.ALL)
    w.alice.sharedAlbums.close(album, CloseMode.STOP, OthersAfterClose.REMOVE)
    val state = w.common.album(album)!!
    val reopen = SharedAlbumChange.Definition(state.title, state.folder, SharedState.ACTIVE, w.alice.me, state.nonceHex, null)
    assertThat(w.common.apply(album, w.alice.me, listOf(reopen))).isEqualTo(0)
    assertThat(w.bruno.sharedAlbums.visible.value).isEmpty()
  }

  /** E-SH21: lasciare l'album lo nasconde solo a me; lo ritrovo fra gli album lasciati. */
  @Test
  fun lasciareNascondeSoloAMe() = runBlocking<Unit> {
    val w = world()
    val (album, _, _) = w.album(ShareLevel.ADD)
    assertThat(w.alice.sharedAlbums.leave(album)).isEqualTo(EditOutcome.NotAllowed(AlbumOp.LEAVE))
    assertThat(w.bruno.sharedAlbums.leave(album)).isEqualTo(EditOutcome.Done)
    assertThat(w.bruno.sharedAlbums.visible.value).isEmpty()
    assertThat(w.bruno.sharedAlbums.left.value.map { it.id }).containsExactly(album)
    assertThat(w.alice.sharedAlbums.visible.value.map { it.id }).containsExactly(album)
    assertThat(w.bruno.sharedAlbums.rejoin(album)).isEqualTo(EditOutcome.Done)
    assertThat(w.bruno.sharedAlbums.visible.value.map { it.id }).containsExactly(album)
  }

  /** D11, E-SH11: l'admin eredita solo quando il creatore non ha piu' dispositivi nel registro. */
  @Test
  fun lEreditaSoloConIlCreatoreUscito() = runBlocking<Unit> {
    val w = world()
    val (album, _, _) = w.album(ShareLevel.VIEW)
    assertThat(w.bruno.sharedAlbums.inherit(album)).isEqualTo(InheritOutcome.NotAdmin)
    w.common.admins += w.bruno.me
    assertThat(w.bruno.sharedAlbums.inherit(album)).isEqualTo(InheritOutcome.CreatorStillInFamily)
    assertThat(w.bruno.sharedAlbums.allowed(album, AlbumOp.RENAME)).isFalse()
    w.common.gone += w.alice.me
    assertThat(w.bruno.sharedAlbums.inherit(album)).isEqualTo(InheritOutcome.Inherited)
    assertThat(w.bruno.sharedAlbums.allowed(album, AlbumOp.RENAME)).isTrue()
    assertThat(w.bruno.sharedAlbums.visible.value.single().mine).isTrue()
  }

  /** D7, E-SH14: una definizione con un id che non torna con l'account di chi scrive si rifiuta intera; nessun album compare. */
  @Test
  fun unaDefinizioneFalsaSiRifiuta() = runBlocking<Unit> {
    val w = world()
    val nonce = ByteArray(16) { 3 }
    val claimed = AlbumId.derived(AccountId("test"), nonce)
    val forged = SharedAlbumChange.Definition(
      "Foto da cancellare", SharePath.of("Album condivisi", "X"), SharedState.ACTIVE, AccountId("test"), nonce.joinToString("") { "%02x".format(it) }, null,
    )
    assertThat(w.common.apply(claimed, w.bruno.me, listOf(forged))).isEqualTo(0)
    assertThat(w.common.rejectedContributions).isEqualTo(1)
    assertThat(w.alice.sharedAlbums.visible.value).isEmpty()
  }
}
