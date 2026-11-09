package dev.pampa.mycloudgallery.core.albums.personal

import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumContent
import dev.pampa.mycloudgallery.core.albums.contract.AlbumFamily
import dev.pampa.mycloudgallery.core.albums.contract.AlbumList
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.albums.contract.SmartChoices
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.noop.NoShareRunner
import dev.pampa.mycloudgallery.core.albums.upkeep.SmartWorkSink
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbumState
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// GLI ALBUM PERSONALI VERI (docs/fase7-progetto.md D3-D5, D12, D16, D21, D22, D39; §2.1): l'implementazione di `Albums` (e delle scelte
// sugli smart) sopra i dati personali e le tabelle v6. Le modifiche sono voci dei dati personali ([AlbumEditor]); le tabelle le segue la
// proiezione ([CatalogAlbumProjection]) **subito**, senza aspettare la sincronizzazione, e a ogni cambiamento dello stato che arriva dagli altri
// dispositivi; la lettura ([AlbumReader]) non conta e non mostra mai un nascosto (D22). Nessun file e' mai toccato (D39).
//
// Passa `AlbumsContract` come `InMemoryAlbums` (stessa suite, stessi esiti). Gli album condivisi non sono qui: se `shared` e `runner` sono dati,
// l'elenco li comprende e le modifiche di un album condiviso passano da loro (aggiungere = il piano da confermare, mai un'aggiunta diretta,
// D12), cosi' :app lega un solo `Albums` alle schermate; senza, e' l'elenco degli album personali.
//
// Cosa deve collegare :app (P13): [onCatalogChanged] dopo ogni scansione con novita' o unione d'identita'; la sincronizzazione dei dati personali
// non serve (lo stato si segue da solo). Mai titoli, id o luoghi nei log (D37).

class CatalogAlbums(
  private val store: PersonalStore,
  private val catalog: MediaCatalog,
  private val database: GalleryDatabase,
  private val visible: VisibleMedia,
  mask: HiddenMask,
  private val family: AlbumFamily,
  scope: CoroutineScope,
  private val shared: SharedAlbums? = null,
  private val runner: ShareRunner = NoShareRunner,
  /** Gli elementi mostrabili di un album condiviso, nell'ordine dell'album (li sa la replica di P2; :app lega la sua lettura). */
  private val sharedItems: suspend (AlbumId) -> List<MediaId> = { emptyList() },
  private val newNonce: () -> ByteArray = { ByteArray(AlbumId.NONCE_BYTES).also { SecureRandom().nextBytes(it) } },
  /** La proiezione sul catalogo; condivisa con chi la deve richiamare (lavori di :app), altrimenti ne nasce una. */
  val projection: CatalogAlbumProjection = CatalogAlbumProjection(catalog, database),
) : Albums, SmartChoices, SmartWorkSink {

  private val reader = AlbumReader(catalog, database, visible, mask)
  private val editor = AlbumEditor(store, catalog, database, MediaResolver(catalog, database), reader::shown)
  private val albums get() = editor.albums

  private val mutableAll = MutableStateFlow(AlbumList.EMPTY)
  private val revision = MutableStateFlow(0L)
  private val editLock = Mutex()
  private val syncLock = Mutex()
  private val refreshLock = Mutex()
  private val diff = StateDiff()

  override val all: StateFlow<AlbumList> = mutableAll

  /** AP-1: l'elenco e' stato letto almeno una volta. Prima, `all` e' vuoto perche' non ha ancora letto, non perche' non ci sono album. */
  private val loaded = MutableStateFlow(false)

  /** AS-2: gli smart che aspettano il primo calcolo del motore (nuovi, o arrivati da un altro dispositivo) o lo stanno facendo. */
  private val awaiting = MutableStateFlow<Set<AlbumId>>(emptySet())

  /**
   * Gli smart che [create] sta creando: il segno "in attesa" c'e' gia' ma l'album non e' ancora nelle tabelle (la proiezione viene dopo). Un giro dell'elenco che e'
   * partito in mezzo non li vede e non deve crederli spariti (vedi [refresh]); il segno si toglie da qui solo con le tabelle a posto, sotto `refreshLock`.
   */
  private val creating: MutableSet<AlbumId> = ConcurrentHashMap.newKeySet()

  /**
   * `Loading` finche' l'elenco non e' stato letto (AP-1); `Recomputing` mentre uno smart senza righe aspetta il primo calcolo (AS-2: la pagina dice
   * "cerco le foto", non "nessuna foto"; senza numeri, `Recomputing(0, 0)`); altrimenti `Ready`.
   */
  override val status: StateFlow<AlbumsStatus> = combine(loaded, awaiting) { done, waiting ->
    when {
      !done -> AlbumsStatus.Loading
      waiting.isNotEmpty() -> AlbumsStatus.Recomputing(done = 0, total = 0)
      else -> AlbumsStatus.Ready
    }
  }.stateIn(scope, SharingStarted.Eagerly, AlbumsStatus.Loading)

  init {
    // Lo stato dei dati personali si segue da solo: un'aggiunta fatta su un altro dispositivo arriva qui e diventa righe e conteggi.
    scope.launch { store.effectiveEntries.collect { reconcile(emptySet()) } }
    // La Libreria cambia (un nascosto, un media nuovo o sparito): conteggi e copertine si ricalcolano.
    // Una scansione fa cambiare l'istantanea mille volte: l'elenco si ricalcola al piu' una volta ogni [REFRESH_GAP_MS].
    scope.launch { visible.snapshot.collect { refresh().also { delay(REFRESH_GAP_MS) } } }
    shared?.let { scope.launch { it.visible.collect { refresh().also { delay(REFRESH_GAP_MS) } } } }
  }

  // --- Lettura ----------------------------------------------------------------------------------------------------------------

  override fun content(id: AlbumId): Flow<AlbumContent?> = combine(revision, visible.snapshot) { _, snapshot -> snapshot }.map { build(id, it) }

  private suspend fun build(id: AlbumId, snapshot: TimelineSnapshot?): AlbumContent? {
    snapshot ?: return null
    val states = statesById(store.effectiveEntries.value)
    val album = reader.personalOne(id.value, states, family.me())
    if (album == null) {
      val sharedAlbum = shared?.visible?.value?.firstOrNull { it.id == id } ?: return null
      return AlbumContent(sharedAlbum, IndexOrderedTimelineView.of(snapshot, sharedItems(id)))
    }
    val rows = database.albumDao().items(id.value)
    val ok = reader.shown(rows.map { MediaId(it.mediaId) })
    val ordered = order(album.sort, rows.filter { MediaId(it.mediaId) in ok }, snapshot)
    val suggested = if (album is Album.Smart && !album.frozen) {
      val ids = database.albumChoiceDao().suggestions(id.value).map { MediaId(it.mediaId) }
      val suggestedOk = reader.shown(ids)
      IndexOrderedTimelineView.of(snapshot, order(album.sort, ids.filter { it in suggestedOk }.map { AlbumItemEntity(id.value, it.value, 0, 0) }, snapshot))
    } else {
      null
    }
    return AlbumContent(album, IndexOrderedTimelineView.of(snapshot, ordered), suggested)
  }

  /** L'ordine dell'album (D21): dal piu' recente o dal piu' vecchio secondo l'istantanea (ordinata dal piu' recente), o per posizione. */
  private fun order(sort: AlbumSort, rows: List<AlbumItemEntity>, snapshot: TimelineSnapshot): List<MediaId> = when (sort) {
    AlbumSort.CUSTOM -> rows.sortedWith(compareBy({ it.position }, { it.fp.orEmpty() }, { it.mediaId })).map { MediaId(it.mediaId) }
    AlbumSort.NEWEST_FIRST -> rows.map { MediaId(it.mediaId) }.sortedBy { snapshot.indexOf(it) }
    AlbumSort.OLDEST_FIRST -> rows.map { MediaId(it.mediaId) }.sortedByDescending { snapshot.indexOf(it) }
  }

  private fun statesById(entries: Map<PersonalKey, PersonalEntry>): Map<String, PersonalAlbumState> =
    PersonalAlbums.statesOf(entries).associateBy { it.id }

  /** Ricalcola l'elenco dalle tabelle e dallo stato: dopo ogni modifica, ogni cambiamento di Libreria e ogni novita' degli album condivisi. */
  private suspend fun refresh() {
    refreshLock.withLock {
      // Gli smart in attesa all'inizio di questo giro: solo questi si possono togliere (uno annunciato mentre si legge non era ancora nelle tabelle).
      val waitingBefore = awaiting.value
      val entries = store.effectiveEntries.value
      val states = statesById(entries)
      val sort = AlbumListSort.fromCode(albums.listSort(entries))
      val list = reader.personal(states, family.me()) + shared?.visible?.value.orEmpty()
      mutableAll.value = AlbumList(sorted(list, sort, states), sort)
      revision.value = revision.value + 1
      loaded.value = true
      // Uno smart eliminato (o mai arrivato) non resta "in attesa del primo calcolo" per sempre. Non uno che `create` sta ancora scrivendo nelle tabelle: questo giro puo'
      // essere partito dopo il suo segno e prima della proiezione, e non lo vede ancora (`creating` si svuota solo sotto questo stesso lucchetto: o il giro finisce
      // prima, e lo vede in `creating`, o parte dopo, e le tabelle ci sono).
      val gone = waitingBefore.filterTo(HashSet()) { id -> id !in creating && list.none { it.id == id } }
      if (gone.isNotEmpty()) awaiting.update { it - gone }
    }
  }

  private fun sorted(list: List<Album>, sort: AlbumListSort, states: Map<String, PersonalAlbumState>): List<Album> = when (sort) {
    AlbumListSort.CUSTOM -> list.sortedWith(compareBy<Album>({ states[it.id.value]?.order ?: Long.MAX_VALUE }, { it.id.value }))
    AlbumListSort.RECENT -> list.sortedWith(compareByDescending<Album> { it.updatedAtNas }.thenBy { it.id.value })
    AlbumListSort.NAME -> list.sortedWith(compareBy<Album>({ fold(it.title) }, { it.id.value }))
    AlbumListSort.MEDIA_DATE -> list.sortedWith(compareByDescending<Album> { it.newestDay?.toEpochDay() ?: Long.MIN_VALUE }.thenBy { it.id.value })
  }

  // --- Sincronizzazione con le tabelle ------------------------------------------------------------------------------------------

  /**
   * Porta nelle tabelle gli album cambiati dall'ultimo giro (e [extra]); il primo giro li porta tutti. Poi aggiorna l'elenco. Legge lo stato
   * **dentro** il lucchetto: chi arriva dopo una modifica piu' recente non riporta le tabelle indietro.
   */
  private suspend fun reconcile(extra: Set<String>) {
    syncLock.withLock {
      val entries = store.effective()
      val change = diff.update(entries)
      val touched = change.albums + extra
      if (change.all) projection.project(entries, null) else if (touched.isNotEmpty()) projection.project(entries, touched)
      if (change.all || touched.isNotEmpty() || change.listSort) refresh()
    }
  }

  /** Dopo una modifica locale: le tabelle seguono subito. */
  private suspend fun settle(vararg touched: String) = reconcile(touched.toSet())

  /**
   * Dopo una scansione con novita' o un'unione d'identita' che hanno toccato [changed]: gli elementi "in attesa" tornano se il loro media e'
   * arrivato, due elementi che indicano lo stesso media diventano uno. Poi l'elenco si ricalcola (conteggi e copertine).
   */
  suspend fun onCatalogChanged(changed: Collection<MediaId> = emptyList()) {
    syncLock.withLock {
      val affected = projection.affectedBy(changed)
      if (affected.isNotEmpty()) projection.project(store.effective(), affected)
    }
    refresh()
  }

  /** Porta nelle tabelle tutti gli album e ricalcola l'elenco (all'avvio, o dopo aver svuotato le tabelle). */
  suspend fun reconcileAll() {
    syncLock.withLock {
      val entries = store.effective()
      projection.rebuild(entries)
      diff.update(entries)
    }
    refresh()
  }

  /**
   * Porta nelle tabelle lo stato dei dati personali **di adesso** e aspetta che sia fatto (AS-9): chi deve leggere `album_rule` (il motore degli smart,
   * dopo una regola arrivata da un altro dispositivo) non indovina con un'attesa quanto ci mette la proiezione; chiede qui. Se un altro collettore la sta
   * facendo, aspetta che finisca (stesso lucchetto) e non rifa' niente.
   */
  suspend fun reconcileNow() = reconcile(emptySet())

  // --- SmartWorkSink: il motore degli smart (AS-1, AS-2) ------------------------------------------------------------------------

  override fun smartWorkStarted(albums: Collection<AlbumId>) {
    awaiting.update { it + albums }
  }

  override suspend fun smartWorkFinished(albums: Collection<AlbumId>) {
    // Prima le righe nuove (elenco e pagine si rileggono), poi lo stato torna a Ready: la pagina non passa da "nessuna foto".
    refresh()
    awaiting.update { it - albums.toSet() }
  }

  override suspend fun smartRowsChanged() = refresh()

  // --- Modifiche --------------------------------------------------------------------------------------------------------------

  private fun isShared(id: AlbumId): Boolean = editor.definedState(id) == null && shared?.visible?.value?.any { it.id == id } == true

  override suspend fun create(title: String, kind: AlbumKind, rules: SmartRules?): CreateOutcome = editLock.withLock {
    val me = family.me() ?: return@withLock CreateOutcome.Unavailable
    val outcome = editor.create(me, newNonce(), title, kind, rules)
    if (outcome is CreateOutcome.Created) {
      // AS-2: uno smart nuovo non ha righe finche' il motore non lo calcola. Il segno "aspetta il calcolo" c'e' prima che l'album compaia: la sua pagina
      // non passa mai per "nessuna foto corrisponde alle regole".
      if (kind == AlbumKind.SMART) {
        creating += outcome.id
        smartWorkStarted(listOf(outcome.id))
      }
      try {
        settle(outcome.id.value)
      } finally {
        // Le tabelle hanno l'album (o la proiezione e' fallita): un giro dell'elenco d'ora in poi lo vede. Si toglie sotto `refreshLock`, cioe' dopo un giro in corso.
        if (kind == AlbumKind.SMART) withContext(NonCancellable) { refreshLock.withLock { creating -= outcome.id } }
      }
    }
    outcome
  }

  override suspend fun rename(id: AlbumId, title: String): EditOutcome = editLock.withLock {
    if (isShared(id)) return@withLock shared!!.rename(id, title).also { refresh() }
    editor.rename(id, title).also { settle(id.value) }
  }

  override suspend fun delete(id: AlbumId): EditOutcome = editLock.withLock {
    if (isShared(id)) return@withLock EditOutcome.NotAllowed(AlbumOp.DELETE_ALBUM)
    editor.delete(id).also { settle(id.value) }
  }

  override suspend fun add(id: AlbumId, media: List<MediaId>): AddOutcome = editLock.withLock {
    if (isShared(id)) {
      return@withLock when (val plan = runner.plan(ShareRequest.AddToShared(id, media))) {
        is SharePlanOutcome.Ready -> AddOutcome.NeedsShareConfirmation(plan.plan)
        is SharePlanOutcome.Blocked -> AddOutcome.Blocked(plan.reason)
        is SharePlanOutcome.NotAllowed -> AddOutcome.NotAllowed(plan.op)
        is SharePlanOutcome.NothingToShare -> AddOutcome.NothingToAdd(plan.excluded)
        is SharePlanOutcome.InvalidTitle -> AddOutcome.NothingToAdd(emptyMap())
      }
    }
    editor.add(id, media).also { settle(id.value) }
  }

  override suspend fun remove(id: AlbumId, media: List<MediaId>): EditOutcome = editLock.withLock {
    if (isShared(id)) return@withLock shared!!.remove(id, media).also { refresh() }
    editor.remove(id, media).also { settle(id.value) }
  }

  override suspend fun setCover(id: AlbumId, media: MediaId?): EditOutcome = editLock.withLock {
    if (isShared(id)) return@withLock shared!!.setCover(id, media).also { refresh() }
    editor.setCover(id, media).also { settle(id.value) }
  }

  override suspend fun setSort(id: AlbumId, sort: AlbumSort): EditOutcome = editLock.withLock {
    if (isShared(id)) return@withLock shared!!.setSort(id, sort).also { refresh() }
    editor.setSort(id, sort).also { settle(id.value) }
  }

  override suspend fun reorder(id: AlbumId, moved: MediaId, after: MediaId?): EditOutcome = editLock.withLock {
    if (isShared(id)) return@withLock EditOutcome.NotAllowed(AlbumOp.SET_SORT)
    editor.reorder(id, moved, after).also { settle(id.value) }
  }

  override suspend fun setListSort(sort: AlbumListSort) {
    editLock.withLock {
      albums.setListSort(sort.code)
      settle()
    }
  }

  override suspend fun moveInList(id: AlbumId, after: AlbumId?): EditOutcome = editLock.withLock {
    val ids = mutableAll.value.albums.map { it.id }
    if (id !in ids || (after != null && after !in ids)) return@withLock EditOutcome.NotFound
    if (after == id) return@withLock EditOutcome.Invalid(null)
    val states = statesById(store.effectiveEntries.value)
    val sequence = ids.sortedWith(compareBy({ states[it.value]?.order ?: Long.MAX_VALUE }, { it.value }))
    val orders = sequence.map { states[it.value]?.order }
    // Con un posto per ogni album basta una voce; altrimenti la prima volta si assegna un posto a tutti.
    val plan = if (orders.all { it != null }) {
      AlbumPositions.insertAfter(sequence.mapIndexed { i, a -> PositionedItem(a.value, orders[i]!!) }, id.value, after?.value)
    } else {
      val order = sequence.map { it.value }.filter { it != id.value }.toMutableList()
      order.add(if (after == null) 0 else order.indexOf(after.value) + 1, id.value)
      PositionPlan(AlbumPositions.compact(order), recompacted = true)
    }
    // Il posto di un album sta nella sua voce `order`, anche per quelli condivisi (la voce non ha definizione: e' solo un posto, D21).
    // In un lotto solo (review generale, OA-1): la prima volta ogni album prende un posto, e ogni voce da sola riscriverebbe il registro intero.
    albums.batch { for (change in plan.changes) setOrder(change.media, change.position) }
    settle()
    EditOutcome.Done
  }

  /**
   * Cambia le regole di uno smart (D13, E-AL13). **Non e' nel contratto `Albums`** (P0 non lo aveva): P6 e P10 ne hanno bisogno per l'editor
   * delle regole di un album che c'e' gia'; vedi "Modifiche ai contratti" in `docs/decisioni.md` (Fase 7 P3).
   */
  override suspend fun setRules(id: AlbumId, rules: SmartRules): EditOutcome = editLock.withLock { editor.setRules(id, rules).also { settle(id.value) } }

  // --- SmartChoices -----------------------------------------------------------------------------------------------------------

  override suspend fun exclude(id: AlbumId, media: List<MediaId>): EditOutcome = editLock.withLock { editor.exclude(id, media).also { settle(id.value) } }

  override suspend fun accept(id: AlbumId, media: List<MediaId>): EditOutcome = editLock.withLock { editor.accept(id, media).also { settle(id.value) } }

  override suspend fun freeze(id: AlbumId): EditOutcome = editLock.withLock { editor.freeze(id).also { settle(id.value) } }

  override suspend fun unfreeze(id: AlbumId): EditOutcome = editLock.withLock { editor.unfreeze(id).also { settle(id.value) } }

  // --- Che cosa e' cambiato nello stato ---------------------------------------------------------------------------------------

  /** Che cosa e' cambiato (negli album e nell'ordine dell'elenco) dall'ultimo stato visto. Il primo stato cambia tutto. */
  private class Change(val all: Boolean, val albums: Set<String>, val listSort: Boolean)

  private class StateDiff {
    private var seen: Map<PersonalKey, PersonalEntry>? = null

    fun update(entries: Map<PersonalKey, PersonalEntry>): Change {
      val now = HashMap<PersonalKey, PersonalEntry>()
      for ((key, entry) in entries) if (relevant(key)) now[key] = entry
      val before = seen
      seen = now
      if (before == null) return Change(all = true, albums = emptySet(), listSort = true)
      val albums = HashSet<String>()
      var listSort = false
      for ((key, entry) in now) {
        if (before[key] == entry) continue
        if (key.kind == PersonalKind.ALBUM) albums += key.subject else listSort = true
      }
      for (key in before.keys) {
        if (key in now) continue
        if (key.kind == PersonalKind.ALBUM) albums += key.subject else listSort = true
      }
      return Change(all = false, albums = albums, listSort = listSort)
    }

    private fun relevant(key: PersonalKey): Boolean =
      key.kind == PersonalKind.ALBUM ||
        (key.kind == PersonalKind.SETTING &&
          key.field == PersonalFields.SETTING_ALBUMS_SORT)
  }

  private companion object {
    const val REFRESH_GAP_MS: Long = 150

    /** L'ordine "Nome": minuscolo, senza accenti. */
    fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ITALIAN)
  }
}
