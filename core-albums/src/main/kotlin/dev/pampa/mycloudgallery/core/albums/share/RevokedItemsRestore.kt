package dev.pampa.mycloudgallery.core.albums.share

import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.AlbumFamily
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPublisher
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.contract.LevelStep
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.shared.AlbumIdentity
import dev.pampa.mycloudgallery.core.albums.shared.AlbumReplicaStore
import dev.pampa.mycloudgallery.core.albums.shared.ResolvedItem
import dev.pampa.mycloudgallery.core.albums.shared.SharedAlbumReplica
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus

/** L'esito di "Ripristina gli elementi di un dispositivo revocato". */
sealed interface RestoreOutcome {
  /** [items] elementi (e, per un album mio rimasto senza definizione, la definizione) in coda al pubblicatore a nome di questo dispositivo. */
  data class Restored(val items: Int, val definition: Boolean) : RestoreOutcome

  /** Niente da ripristinare: nessun elemento di un mio dispositivo escluso i cui file il mio catalogo veda ancora nell'area comune. */
  data object Nothing : RestoreOutcome

  /** L'album non permette piu' di aggiungere (il livello e' cambiato): gli elementi restano fuori. */
  data object NotAllowed : RestoreOutcome

  data class Blocked(val reason: AlbumWriteBlock) : RestoreOutcome
}

/**
 * E-SH20 (A7 di `docs/formato-nas.md` §4.6, D8): un dispositivo del mio account viene **revocato** (o perso): i suoi contributi agli album spariscono
 * per tutti, anche gli elementi che aveva aggiunto. Un altro dispositivo dello stesso account (questo) li puo' **ripubblicare** con un'azione locale,
 * "Ripristina gli elementi di un dispositivo revocato", **solo** per gli elementi i cui file il mio catalogo vede ancora nell'area comune (D9).
 *
 * - Album attivo: gli elementi del mio account che stavano solo nei contributi esclusi (nella memoria della replica, mai mostrati) e che non sono
 *   elementi vivi tornano come mie voci `item:` (stesso media, stessa chiave di contenuto, stesso percorso), se la tabella mi lascia aggiungere.
 * - Album mio rimasto senza definizione (il dispositivo revocato era l'unico ad averla scritta): la definizione con lo stesso creatore e nonce
 *   (l'id torna), la storia dei livelli con le ore di allora, l'ordine e gli elementi attestati (come la ricostituzione dell'erede di P2, D11).
 *
 * **Nessun file si muove**: e' solo la definizione che torna. Dietro gli stessi cancelli delle scritture degli album (D30, D38).
 */
class RevokedItemsRestore(
  private val identity: AlbumIdentity,
  private val family: AlbumFamily,
  private val store: AlbumReplicaStore,
  private val replica: SharedAlbumReplica,
  private val publisher: AlbumPublisher,
  private val gate: AlbumWriteGate,
  private val clocks: AlbumClocks,
  /** D22 (Fase 7, P9): un elemento nascosto non si conta ne' si ripristina (la riga "Ripristina N elementi" non fa trapelare i nascosti). */
  private val visibility: VisibleMedia,
  /** La tabella del §2.4 applicata a me (`SharedAlbums.allowed`). */
  private val allowed: (AlbumId, AlbumOp, MediaId?) -> Boolean,
) {

  /** Le chiavi di [items] che il mio catalogo attesta nell'area comune (D9) **e** che si possono mostrare (D22). */
  private suspend fun shownKeys(items: Collection<ResolvedItem>): Set<ContentKey> {
    val byKey = replica.attestedMedia(items)
    if (byKey.isEmpty()) return emptySet()
    val visible = visibility.visible(byKey.values.distinct())
    return byKey.filterValues { it in visible }.keys
  }

  private fun me(): AccountId? = identity.registry()?.registry?.device(identity.deviceId)?.nasUser?.let(AccountId::of)

  /** Un dispositivo del mio account che il registro di adesso esclude (revocato, perso, dismesso senza testa congelata valida). */
  private fun excludedMine(device: String, me: AccountId): Boolean {
    if (device == identity.deviceId) return false
    val entry = identity.registry()?.registry?.device(device) ?: return false
    return AccountId.of(entry.nasUser) == me && entry.status != DeviceStatus.APPROVED
  }

  /** Gli elementi ripristinabili di [album]: miei, solo nei contributi esclusi, non vivi, con il file nell'area comune. */
  private suspend fun restorable(album: AlbumId, me: AccountId, live: Set<MediaId>): List<ResolvedItem> {
    val out = LinkedHashMap<MediaId, ResolvedItem>()
    for (c in store.contributions(album).filter { it.barred && excludedMine(it.device, me) }) {
      for (stored in c.entries) {
        val e = stored.entry
        val field = e.field as? AlbumField.Item ?: continue
        val value = e.value as? AlbumValue.Item ?: continue
        if (field.media in live || value.addedBy != me) continue
        val previous = out[field.media]
        if (previous == null || previous.at < e.at) {
          out[field.media] = ResolvedItem(field.media, value.contentKey, value.sha256, value.path, value.position, value.addedBy, value.version, e.at, e.at)
        }
      }
    }
    if (out.isEmpty()) return emptyList()
    val attested = shownKeys(out.values)
    return out.values.filter { it.contentKey in attested }.sortedWith(compareBy({ it.position }, { it.media.value }))
  }

  /** Gli album con qualcosa da ripristinare e quanti elementi (per la riga "Ripristina…" della pagina dell'album). */
  suspend fun candidates(): Map<AlbumId, Int> {
    val me = me() ?: return emptyMap()
    val out = LinkedHashMap<AlbumId, Int>()
    for (album in store.albums()) {
      val view = replica.view(album)
      val orphan = replica.orphans.value[album]?.takeIf { it.creator == me && it.state == SharedState.ACTIVE }
      if (view == null && orphan == null) continue
      if (view != null && view.merged.state != SharedState.ACTIVE) continue
      val count = restorable(album, me, view?.merged?.items?.keys.orEmpty()).size
      if (count > 0 || (view == null && orphan != null)) out[album] = count
    }
    return out
  }

  private suspend fun blocked(): AlbumWriteBlock? {
    val share = family.commonShare() ?: return AlbumWriteBlock.NO_NAS
    gate.blockFor(share)?.let { return it }
    if (identity.registry()?.canWrite(identity.deviceId) != true) return AlbumWriteBlock.NOT_TRUSTED
    return if (clocks.nasNowMs() == null) AlbumWriteBlock.NO_NAS else null
  }

  /** "Ripristina gli elementi di un dispositivo revocato" su [album] (un tocco dell'utente). */
  suspend fun restore(album: AlbumId): RestoreOutcome {
    val me = me() ?: return RestoreOutcome.Blocked(AlbumWriteBlock.NOT_TRUSTED)
    val view = replica.view(album)
    val orphan = if (view == null) replica.orphans.value[album]?.takeIf { it.creator == me && it.state == SharedState.ACTIVE } else null
    if (view == null && orphan == null) return RestoreOutcome.Nothing
    if (view != null && view.merged.state != SharedState.ACTIVE) return RestoreOutcome.Nothing
    val items = restorable(album, me, view?.merged?.items?.keys.orEmpty())
    if (view != null) {
      if (items.isEmpty()) return RestoreOutcome.Nothing
      if (!allowed(album, AlbumOp.ADD_MEDIA, null)) return RestoreOutcome.NotAllowed
    }
    blocked()?.let { return RestoreOutcome.Blocked(it) }
    val changes = ArrayList<SharedAlbumChange>()
    if (orphan != null) {
      changes += SharedAlbumChange.Definition(orphan.title, orphan.folder, SharedState.ACTIVE, orphan.creator, orphan.nonceHex, null)
      if (orphan.levels.changes.isNotEmpty()) changes += SharedAlbumChange.LevelHistory(orphan.levels.changes.map { LevelStep(it.at, it.level) })
      orphan.sort?.let { changes += SharedAlbumChange.Sort(it) }
      // Nella memoria di un album senza definizione valida anche gli elementi "vivi" sono solo dei contributi esclusi: tutti quelli miei attestati.
      val mine = orphan.items.values.filter { it.addedBy == me }
      val attested = shownKeys(mine)
      for (item in mine.filter { it.contentKey in attested }) {
        changes += SharedAlbumChange.Item(item.media, item.version, item.contentKey, item.path, item.position, me, item.sha256)
      }
      for (item in items.filter { i -> mine.none { it.media == i.media } }) {
        changes += SharedAlbumChange.Item(item.media, item.version, item.contentKey, item.path, item.position, me, item.sha256)
      }
    } else {
      for (item in items) changes += SharedAlbumChange.Item(item.media, item.version, item.contentKey, item.path, item.position, me, item.sha256)
    }
    publisher.stage(album, changes)
    replica.recompute(listOf(album))
    return RestoreOutcome.Restored(changes.count { it is SharedAlbumChange.Item }, definition = orphan != null)
  }
}
