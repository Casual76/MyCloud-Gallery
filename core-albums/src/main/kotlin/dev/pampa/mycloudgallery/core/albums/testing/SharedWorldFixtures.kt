package dev.pampa.mycloudgallery.core.albums.testing

import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// COSTRUTTORI DI PROVA PER CHI NON VEDE CORE-TRUST (Fase 7, P10). I percorsi dell'area comune (`SharePath`) sono tipi di core-trust, e feature-albums non
// li vede ne' li nomina (Phase7ArchitectureTest, regola 6, anche nei test): per riempire l'area comune finta ([FakeSharedWorld]) i test delle schermate
// passano di qui. Solo test: stanno in testing/ e non cambiano nessun contratto.

/** Un album condiviso nuovo creato da [creator] al livello [level], nell'area comune, nella cartella con il nome del titolo. */
fun FakeSharedWorld.createAlbum(creator: AccountId, title: String, level: ShareLevel): AlbumId {
  val bytes = ByteArray(16) { (it * 7 + title.length).toByte() }
  val id = AlbumId.derived(creator, bytes)
  val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
  apply(
    id, creator,
    listOf(SharedAlbumChange.Definition(title, SharePath.of("Album condivisi", title), SharedState.ACTIVE, creator, hex, null), SharedAlbumChange.Level(level)),
  )
  return id
}

/** Mette in [album] i [media] (file gia' dell'area comune, con il loro percorso) come aggiunti da [by]. */
fun FakeSharedWorld.addItems(album: AlbumId, by: AccountId, media: List<FakeMedia>) {
  apply(
    album, by,
    media.mapIndexed { i, m -> SharedAlbumChange.Item(m.id, m.version, m.contentKey, SharePath.parse(requireNotNull(m.path)), (i + 1) * 1_000L, by) },
  )
}

/** Chiude [id] ([SharedState.CLOSED] o [SharedState.DELETED]) con la scelta per gli altri [afterClose] (Q5): la definizione con lo stato nuovo. */
fun FakeSharedWorld.closeAlbum(id: AlbumId, by: AccountId, state: SharedState, afterClose: OthersAfterClose) {
  val current = requireNotNull(album(id)) { "Album sconosciuto." }
  apply(id, by, listOf(SharedAlbumChange.Definition(current.title, current.folder, state, current.creator, current.nonceHex, afterClose)))
}
