package dev.pampa.mycloudgallery.albums

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPermissions
import dev.pampa.mycloudgallery.core.albums.contract.AlbumRole
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.shared.LevelHistory
import dev.pampa.mycloudgallery.core.albums.shared.MergedAlbum
import dev.pampa.mycloudgallery.core.albums.shared.ResolvedItem
import dev.pampa.mycloudgallery.core.albums.shared.SharedAlbumRules
import dev.pampa.mycloudgallery.core.albums.shared.SharedAlbumView
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.data.LevelChange
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import org.junit.Test

/**
 * Revisione B, R3 (docs/review/7-fase7.md; docs/fase7-progetto.md §2.4, E-D16): le righe "Eliminare il file" e "Nuova versione" della tabella dei
 * permessi, lato **scrittore**, **generate dalla tabella**: per ogni livello e per ogni ruolo (il creatore, chi ha aggiunto l'elemento, un altro
 * familiare) il file di un elemento di un album condiviso e' protetto ([AlbumFileRules], la decisione di [AlbumFileGuard] che il piano di
 * core-nasops chiede per ogni cestino, spostamento e rinomina) esattamente quando la tabella non permette `DELETE_FILE` a quel ruolo.
 *
 * "Nuova versione" ha le stesse celle di "Eliminare il file" e nessuna strada dell'app scrive una versione nell'area comune (P14 della 5B: la
 * correzione di un media che sta solo nell'area comune si ferma con `COMMON_AREA_NOTICE`): la riga e' rispettata per costruzione a ogni livello.
 */
class AlbumFileRulesTest {

  private val creator = AccountId("test")
  private val adder = AccountId("test2")
  private val other = AccountId("test3")
  private val now = 1_800_000_000_000L
  private val nonce = ByteArray(16) { (it * 5 + 1).toByte() }
  private val albumId = AlbumId.derived(creator, nonce)
  private val published = MediaId("00000000-0000-4000-8000-0000000000b1")

  /** Lo stesso file nel mio catalogo ha un altro id (un altro dispositivo l'ha pubblicato): la tabella guarda l'elemento pubblicato. */
  private val local = MediaId("00000000-0000-4000-8000-0000000000c2")

  private fun view(level: ShareLevel, state: SharedState = SharedState.ACTIVE): SharedAlbumView {
    val item = ResolvedItem(
      published, ContentKey.of(QuickHash("ab".repeat(32)), 10), null, SharePath.parse("Album condivisi/Prova/a.jpg"), 1_000, adder, null,
      now - 10_000, now - 10_000,
    )
    val merged = MergedAlbum(
      album = albumId, creator = creator, nonceHex = nonce.joinToString("") { "%02x".format(it.toInt() and 0xff) }, title = "Prova",
      folder = SharePath.parse("Album condivisi/Prova"), state = state, afterCloseCode = if (state == SharedState.ACTIVE) null else "remove",
      createdAt = now - 100_000, closedAt = if (state == SharedState.ACTIVE) null else now - 1_000, levels = LevelHistory(listOf(LevelChange(now - 50_000, level))),
      heirs = emptyList(), cover = null, sort = null, items = mapOf(published to item), winners = emptyMap(), denied = emptyList(), updatedAt = now,
    )
    return SharedAlbumView(merged, mapOf(published to local))
  }

  private fun protectedFor(view: SharedAlbumView, actor: AccountId, leaving: Set<MediaId> = emptySet()): Set<MediaId> =
    AlbumFileRules.protectedMedia(listOf(view), leaving) { _, media ->
      SharedAlbumRules.allows(view.merged, actor, false, AlbumOp.DELETE_FILE, publishedIdIn(view, media), now, false)
    }

  @Test
  fun laRigaEliminareIlFileProteggeIlFileDoveLaTabellaNonLoPermette() {
    val actors = mapOf("creatore" to creator, "chi l'ha aggiunto" to adder, "un altro familiare" to other)
    var checked = 0
    for (row in listOf(AlbumOp.DELETE_FILE, AlbumOp.NEW_VERSION)) {
      for (level in ShareLevel.entries) {
        for ((name, actor) in actors) {
          val roles = AlbumPermissions.rolesOf(actor, creator, null, adder, false)
          val allowed = AlbumPermissions.admits(row, level, roles)
          // Le due righe hanno le stesse celle (§2.4): "Nuova versione" vale dove vale "Eliminare il file".
          assertWithMessage("${row.code} a ${level.code} per $name").that(allowed).isEqualTo(AlbumPermissions.admits(AlbumOp.DELETE_FILE, level, roles))
          if (row == AlbumOp.DELETE_FILE) {
            assertWithMessage("file protetto a ${level.code} per $name").that(local in protectedFor(view(level), actor)).isEqualTo(!allowed)
          }
          checked++
        }
      }
    }
    assertThat(checked).isEqualTo(2 * ShareLevel.entries.size * actors.size)
    // La tabella, per memoria: a "Gli altri guardano" solo il creatore; a "Gli altri aggiungono" anche chi l'ha aggiunto; a "Tutti possono tutto" tutti.
    assertThat(AlbumPermissions.cell(AlbumOp.DELETE_FILE, ShareLevel.VIEW).roles).containsExactly(AlbumRole.CREATOR)
    assertThat(AlbumPermissions.cell(AlbumOp.DELETE_FILE, ShareLevel.ADD).roles).containsExactly(AlbumRole.CREATOR, AlbumRole.ADDER)
  }

  @Test
  fun unRiportaInCorsoEUnAlbumChiusoNonProteggono() {
    // "Riporta nelle mie cartelle" ha la sua riga (BRING_BACK) e il suo controllo nel piano della condivisione.
    assertThat(protectedFor(view(ShareLevel.VIEW), adder, leaving = setOf(local))).isEmpty()
    // Un album chiuso o eliminato non ha elementi vivi (D31): i file restano dell'area comune.
    assertThat(protectedFor(view(ShareLevel.VIEW, SharedState.CLOSED), other)).isEmpty()
  }

  @Test
  fun laLapideDiRiportaVaSullIdPubblicato() {
    // Revisione B, SB-1: il mio catalogo attesta l'elemento pubblicato `published` con il suo `local`; la lapide va su `published`. Prima valeva
    // `media.takeIf { it in members }`, e `members()` comprende anche l'id locale che attesta: la lapide andava su `local`, l'elemento restava vivo.
    val view = view(ShareLevel.ADD)
    assertThat(publishedIdIn(view, local)).isEqualTo(published)
    assertThat(publishedIdIn(view, published)).isEqualTo(published)
    assertThat(publishedIdIn(view, MediaId("00000000-0000-4000-8000-0000000000d3"))).isNull()
    assertThat(publishedIdIn(null, local)).isNull()
  }
}
