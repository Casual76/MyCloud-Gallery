package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.albums.contract.AttestRequest
import dev.pampa.mycloudgallery.core.albums.contract.CatalogFacts
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumKey
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// LA PROIEZIONE DEGLI ALBUM CONDIVISI (§2.5 "Ricevere", punti 2 e 3; D9): dalla replica ([AlbumReplicaStore]) ai contributi ancora fidati
// per il registro di adesso, la fusione ([AlbumFusion], con i permessi), l'attestazione sul catalogo di chi legge ([CatalogFacts.attest]:
// un elemento si mostra solo se il mio catalogo vede, NELL'AREA COMUNE, un file con la stessa chiave di contenuto intera). Il risultato
// ([views]) lo leggono `ReplicaSharedAlbums` e P13 (tabelle `album`/`album_item` di v6, quando P1 le consegna). Mai un'azione sui file.

/**
 * Il mio contributo come lo vedo io prima di rileggerlo dal NAS: le voci gia' pubblicate e quelle in coda del pubblicatore (vista immediata
 * sui miei dispositivi; per gli altri vale cio' che leggono). Lo implementa [NasAlbumPublisher].
 */
interface LocalContributions {
  suspend fun albums(): Set<AlbumId>

  suspend fun local(album: AlbumId): VerifiedContribution?

  companion object {
    val NONE: LocalContributions = object : LocalContributions {
      override suspend fun albums(): Set<AlbumId> = emptySet()

      override suspend fun local(album: AlbumId): VerifiedContribution? = null
    }
  }
}

/** Un album condiviso per questo dispositivo: lo stato fuso e, per ogni elemento vivo, il media del mio catalogo che lo attesta (D9). */
data class SharedAlbumView(val merged: MergedAlbum, val attested: Map<MediaId, MediaId>) {
  /** Elementi senza un file nell'area comune visto dal mio catalogo (`ALBUM_ITEM_UNATTESTED`): non si mostrano e non si contano. */
  val unattested: Int get() = merged.items.size - attested.size

  override fun toString(): String = "SharedAlbumView(${merged.state.code}, ${attested.size}/${merged.items.size})"
}

/**
 * La proiezione. Una per processo; le chiamate si serializzano. R1 della revisione di sicurezza della Fase 7: tiene i segni locali di questo
 * dispositivo ([marks]: l'album visto attivo, la prima chiusura vista, la copia personale gia' scritta) e fa valere la chiusura vista come
 * definitiva (D31): una definizione `active` letta dopo non riapre l'album, finche' il dispositivo che l'ha chiuso resta fidato (la revoca toglie
 * cio' che ha causato).
 */
class SharedAlbumReplica(
  private val identity: AlbumIdentity,
  private val store: AlbumReplicaStore,
  private val catalog: CatalogFacts,
  @Volatile var local: LocalContributions = LocalContributions.NONE,
  val marks: SharedAlbumMarks = MemorySharedAlbumMarks(),
) {
  private val mutex = Mutex()
  private val state = MutableStateFlow<Map<AlbumId, SharedAlbumView>>(emptyMap())
  private val orphanState = MutableStateFlow<Map<AlbumId, MergedAlbum>>(emptyMap())
  private val listeners = CopyOnWriteArrayList<suspend () -> Unit>()

  val views: StateFlow<Map<AlbumId, SharedAlbumView>> = state

  /**
   * Gli album **senza** una creazione valida che la memoria locale ricorda (contributi ora esclusi, A7): quelli il cui creatore e' uscito dalla
   * famiglia (D11, "Album di familiari usciti": l'admin li puo' ereditare e ricostituire) e quelli il cui creatore sono io (E-SH20: un altro
   * mio dispositivo ripubblica la definizione). Mai mostrati come album: solo per quelle azioni locali.
   */
  val orphans: StateFlow<Map<AlbumId, MergedAlbum>> = orphanState

  private val unresolvedState = MutableStateFlow<Set<AlbumId>>(emptySet())

  /**
   * Gli album di cui la replica ha contributi validi per il formato ma **nessuna** creazione valida, ne' come album ne' come memoria (E-SH14:
   * una definizione scritta da chi non e' il creatore, voci per un id che nessuno ha creato). Non si mostrano; la Diagnostica li conta con
   * `ALBUM_CONTRIB_REJECTED` (P13).
   */
  val unresolved: StateFlow<Set<AlbumId>> = unresolvedState

  fun view(album: AlbumId): SharedAlbumView? = state.value[album]

  /** Chi mostra gli album (le schermate, la proiezione su v6) si registra per ricalcolare dopo ogni cambiamento. */
  fun addListener(listener: suspend () -> Unit) {
    listeners += listener
  }

  /** La proiezione e' in memoria: dopo la morte del processo il primo ricalcolo la ricostruisce tutta dallo store. */
  private var loaded = false

  /** Album che non si sono potuti ricalcolare (errori isolati, per la Diagnostica). */
  @Volatile var failures: Int = 0
    private set

  /** R1: album di cui questo dispositivo tiene chiusa la vista nonostante una definizione `active` letta dopo la chiusura (Diagnostica). */
  @Volatile var reopeningsRefused: Int = 0
    private set

  /**
   * Ricalcola [albums] (null = tutti: replica, viste attuali e album con voci mie in coda). Da chiamare dopo un giro della replica, dopo una
   * modifica accodata e dopo un cambiamento del catalogo che tocca l'area comune (una scansione con novita': l'attestazione cambia). Il primo
   * ricalcolo di un processo li fa tutti.
   */
  suspend fun recompute(albums: Collection<AlbumId>? = null) {
    val done = mutex.withLock {
      val registry = identity.registry() ?: return@withLock false
      val all = albums == null || !loaded
      val ids = if (!all) albums!!.toSet() else store.albums() + state.value.keys + orphanState.value.keys + unresolvedState.value + local.albums()
      loaded = true
      val next = state.value.toMutableMap()
      val nextOrphans = orphanState.value.toMutableMap()
      val nextUnresolved = unresolvedState.value.toMutableSet()
      // R1: i segni locali. Illeggibili: nessuna chiusura imposta e niente si segna (le copie, che li chiedono, non nascono).
      val known = try {
        marks.all()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        failures++
        null
      }
      val seen = HashMap<AlbumId, (AlbumMarks) -> AlbumMarks>()
      for (id in ids) {
        // Un album che non si riesce a ricalcolare tiene la vista di prima e non ferma gli altri (isolamento, come la replica).
        try {
          val view = compute(id, registry, known?.get(id)?.closure)
          if (view != null && known != null) noteSeen(view.merged, known[id] ?: AlbumMarks())?.let { seen[id] = it }
          val orphan = if (view == null) orphan(id, registry) else null
          if (view == null) next.remove(id) else next[id] = view
          if (orphan == null) nextOrphans.remove(id) else nextOrphans[id] = orphan
          if (view == null && orphan == null && store.contributions(id).any { !it.barred }) nextUnresolved += id else nextUnresolved -= id
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          failures++
        }
      }
      if (seen.isNotEmpty()) {
        try {
          marks.update { all -> seen.forEach { (id, change) -> all[id] = change(all[id] ?: AlbumMarks()) } }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          failures++
        }
      }
      state.value = next
      orphanState.value = nextOrphans
      unresolvedState.value = nextUnresolved
      true
    }
    if (done) listeners.forEach { it() }
  }

  /**
   * R1: cosa segnare di [merged] (null: niente di nuovo). Attivo: visto attivo. Chiuso o eliminato la prima volta: la chiusura (stato, scelta per
   * gli altri, ora, il dispositivo che l'ha firmata, titolo e cartella), che da qui in poi vale come definitiva.
   */
  private fun noteSeen(merged: MergedAlbum, mark: AlbumMarks): ((AlbumMarks) -> AlbumMarks)? {
    if (merged.state == SharedState.ACTIVE) return if (mark.seenActive) null else { m -> m.copy(seenActive = true) }
    if (mark.closure != null) return null
    val author = merged.winners[AlbumKey.of(merged.album, AlbumField.Definition)]?.author.orEmpty()
    val closure = ClosureMark(merged.state, merged.afterCloseCode, merged.closedAt ?: merged.updatedAt, author, merged.title, merged.folder)
    return { m -> if (m.closure != null) m else m.copy(closure = closure) }
  }

  private suspend fun compute(album: AlbumId, registry: RegistryState, closure: ClosureMark? = null): SharedAlbumView? {
    val stored = store.contributions(album).filter { !it.barred && trusted(registry, it) }.map { it.verified() }
    val mine = if (registry.canWrite(identity.deviceId)) local.local(album) else null
    val contributions = if (mine == null) stored else stored.filter { it.device != identity.deviceId } + mine
    // R1, D31: la chiusura gia' vista resta, se chi l'ha firmata e' ancora fidato (approvato o dismesso); dopo di lei nessuna voce vale.
    val enforced = closure?.takeIf { authorTrusted(registry, it.author) }
    var merged = AlbumFusion.merge(contributions, RegistryAlbumView(registry), enforced?.at) ?: return null
    if (enforced != null && merged.state == SharedState.ACTIVE) {
      reopeningsRefused++
      merged = merged.copy(
        state = enforced.state, afterCloseCode = enforced.afterClose, closedAt = enforced.at, title = enforced.title, folder = enforced.folder,
      )
    }
    val requests = merged.items.values.map { AttestRequest(it.contentKey, it.sha256) }
    val byKey = if (requests.isEmpty()) emptyMap() else catalog.attest(requests)
    val attested = merged.items.mapNotNull { (media, item) -> byKey[item.contentKey]?.let { media to it } }.toMap()
    return SharedAlbumView(merged, attested)
  }

  /** Le chiavi di contenuto di [items] che il mio catalogo attesta nell'area comune (D9). */
  suspend fun attest(items: Collection<ResolvedItem>): Set<ContentKey> = attestedMedia(items).keys

  /** Come [attest], con il media del mio catalogo che attesta ogni chiave: serve a chiedere se si puo' mostrare (D22). */
  suspend fun attestedMedia(items: Collection<ResolvedItem>): Map<ContentKey, MediaId> {
    if (items.isEmpty()) return emptyMap()
    return catalog.attest(items.map { AttestRequest(it.contentKey, it.sha256) })
  }

  /** La memoria di un album senza creazione valida: la fusione dei contributi ricordati, esclusi compresi; solo se e' un orfano (sopra). */
  private suspend fun orphan(album: AlbumId, registry: RegistryState): MergedAlbum? {
    val all = store.contributions(album).map { it.verified() }
    if (all.isEmpty()) return null
    val memory = AlbumFusion.merge(all, RegistryAlbumView(registry)) ?: return null
    val me = registry.registry.device(identity.deviceId)?.nasUser?.let(AccountId::of)
    return memory.takeIf { RegistryAlbumView(registry).accountLeft(it.creator) || it.creator == me }
  }

  companion object {
    /** R1: il dispositivo che ha firmato una chiusura vale ancora (approvato o dismesso); revocato, perso o sconosciuto no. */
    fun authorTrusted(registry: RegistryState, device: String): Boolean = when (registry.registry.device(device)?.status) {
      DeviceStatus.APPROVED, DeviceStatus.RETIRED -> true
      else -> false
    }

    /**
     * Un contributo vale con il registro di adesso (D8, A7): approvato; dismesso solo se la sua testa e' fra le congelate; revocato, perso o
     * sconosciuto mai (i suoi elementi spariscono anche se la replica non e' ancora stata ripulita dal giro, E-SH20).
     */
    fun trusted(registry: RegistryState, contribution: StoredContribution): Boolean {
      val device = registry.registry.device(contribution.device) ?: return false
      return when (device.status) {
        DeviceStatus.APPROVED -> true
        DeviceStatus.RETIRED -> contribution.mark.contentHash in device.frozenHeads
        DeviceStatus.REVOKED, DeviceStatus.LOST -> false
      }
    }
  }
}
