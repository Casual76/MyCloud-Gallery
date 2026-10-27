package dev.pampa.mycloudgallery.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.shared.LevelHistory
import dev.pampa.mycloudgallery.core.albums.shared.MergedAlbum
import dev.pampa.mycloudgallery.core.albums.shared.SharedAlbumView
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 7 (P13; E-SH16, E-SH20): la cartella nuova di un album condiviso la propone il catalogo di chi legge, **solo al creatore** (o all'erede), solo
 * quando nessun elemento sta piu' nella cartella dichiarata e la maggioranza sta in una stessa cartella fuori dall'area dell'app. Gli avvisi chiusi non
 * tornano; quelli degli album che non vedo non ci sono.
 */
class AlbumFolderWatchTest {

  private val me = AccountId("test")
  private val other = AccountId("test2")
  private val album = AlbumId(UUID(3L, 3L).toString())
  private val media = (1..4).map { MediaId(UUID(4L, it.toLong()).toString()) }

  private fun view(creator: AccountId = me, state: SharedState = SharedState.ACTIVE) = SharedAlbumView(
    MergedAlbum(
      album = album, creator = creator, nonceHex = "0".repeat(32), title = "Prova Oslo", folder = SharePath.parse("Album condivisi/Prova Oslo"), state = state,
      afterCloseCode = null, createdAt = 1L, closedAt = null, levels = LevelHistory.NONE, heirs = emptyList(), cover = null, sort = null,
      items = emptyMap(), winners = emptyMap(), denied = emptyList(), updatedAt = 1L,
    ),
    attested = media.associateWith { it },
  )

  private fun scan(v: SharedAlbumView, where: (MediaId) -> Set<String>) = runBlocking {
    AlbumFolderWatch.scan(listOf(v), me) { ids -> ids.associateWith { id -> where(id).map(SharePath::parse).toSet() } }
  }

  @Test
  fun laCartellaRinominataDalPcSiProponeAlCreatore() {
    val moves = scan(view()) { setOf("Album condivisi/Oslo 2026") }
    assertThat(moves[album]?.folder).isEqualTo(SharePath.parse("Album condivisi/Oslo 2026"))
  }

  @Test
  fun unElementoAncoraNellaCartellaDichiarataNonProponeNiente() {
    // Anche con le maiuscole diverse e in una sottocartella: l'area comune non distingue le maiuscole.
    val moves = scan(view()) { id -> if (id == media[0]) setOf("album condivisi/PROVA OSLO/sotto") else setOf("Altrove") }
    assertThat(moves).isEmpty()
  }

  @Test
  fun elementiSparsiOLAreaDellAppNonSonoUnaCartellaNuova() {
    assertThat(scan(view()) { id -> setOf("Cartella ${media.indexOf(id)}") }).isEmpty()
    assertThat(scan(view()) { setOf(".mycloudgallery/albums") }).isEmpty()
  }

  @Test
  fun soloIlCreatoreOLEredeLaVedeESoloPerUnAlbumAttivo() {
    assertThat(scan(view(creator = other)) { setOf("Album condivisi/Oslo 2026") }).isEmpty()
    assertThat(scan(view(state = SharedState.CLOSED)) { setOf("Album condivisi/Oslo 2026") }).isEmpty()
    assertThat(runBlocking { AlbumFolderWatch.scan(listOf(view()), null) { emptyMap() } }).isEmpty()
  }

  @Test
  fun gliAvvisiChiusiNonTornanoEQuelliDegliAlbumCheNonVedoNonCiSono() {
    val move = FolderMove(album, "Prova Oslo", SharePath.parse("Album condivisi/Oslo 2026"))
    val titles = mapOf(album to "Prova Oslo")
    val notices = AlbumFolderWatch.notices(mapOf(album to move), mapOf(album to 2), titles, emptySet())
    assertThat(notices).containsExactly(
      SharedAlbumNotice.FolderChanged(album, "Prova Oslo", "Album condivisi/Oslo 2026"),
      SharedAlbumNotice.RevokedItems(album, "Prova Oslo", 2),
    )
    val closed = setOf(AlbumFolderWatch.folderKey(move), AlbumFolderWatch.revokedKey(album, 2))
    assertThat(AlbumFolderWatch.notices(mapOf(album to move), mapOf(album to 2), titles, closed)).isEmpty()
    // Un nuovo cambio di cartella (o un numero diverso di elementi) si dice di nuovo.
    val again = FolderMove(album, "Prova Oslo", SharePath.parse("Album condivisi/Oslo"))
    assertThat(AlbumFolderWatch.notices(mapOf(album to again), mapOf(album to 3), titles, closed)).hasSize(2)
    assertThat(AlbumFolderWatch.notices(mapOf(album to move), mapOf(album to 2), emptyMap(), emptySet())).isEmpty()
  }
}
