package dev.pampa.mycloudgallery.feature.albums.page

import dev.pampa.mycloudgallery.core.common.files.QuietWrites
import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumContent
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.InheritOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.albums.contract.SmartChoices
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumAction
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumBanner
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMembers
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMessage
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPending
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPageState
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPeople
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPolicy
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumScreensClock
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumUnavailableReason
import dev.pampa.mycloudgallery.feature.albums.contract.ItemActions
import dev.pampa.mycloudgallery.feature.albums.contract.NoPeopleChoices
import dev.pampa.mycloudgallery.feature.albums.contract.PeopleChoices
import dev.pampa.mycloudgallery.feature.albums.contract.PermissionInfo
import dev.pampa.mycloudgallery.feature.albums.contract.SuggestionsState
import dev.pampa.mycloudgallery.feature.albums.contract.WhyBlocked
import dev.pampa.mycloudgallery.feature.albums.share.CloseSheetController
import dev.pampa.mycloudgallery.feature.albums.share.ShareSheetController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// LA PAGINA DELL'ALBUM (Fase 7, P10; docs/fase7-progetto.md §1.5 `AlbumPageState`, `PermissionInfo`, `SuggestionsState`, §2.1, §2.4, §2.5, D9, D17,
// D21, D22, D35, D39). Non sa niente di Compose. Compone l'album (`Albums.content`), le operazioni di condivisione in corso, gli avvisi e i
// permessi, e dice **quali azioni si offrono** con la stessa tabella di chi legge ([AlbumPolicy]): un'azione che la tabella non da' a me non
// c'e' (C25). Ogni scrittura passa dai contratti `Albums` e `SharedAlbums`, che rispondono con un esito: nessun caso previsto e' un'eccezione.
//
// Mai un nascosto: le viste vengono da `Albums.content`, che nasce dall'istantanea (senza nascosti) e dal filtro finale. Questa classe non
// le allarga e non ne costruisce altre. **Nessuna azione sui file**: l'unico modo di spostarne uno e' il foglio di conferma ([share]).

/** L'azione di un elemento, offerta con la pressione prolungata su una cella (e da TalkBack): cosa si puo' fare e di chi e'. */
data class ItemMenu(
  val media: MediaId,
  val actions: ItemActions,
  /** Chi l'ha aggiunto (Q6: "Aggiunta da Marco"); null per un album personale o se non si sa. */
  val addedBy: AccountId?,
  val isCover: Boolean,
  /** Per uno smart: un elemento automatico non si "rimuove", si "toglie" (diventa un'esclusione che dura per sempre, E-AL5). */
  val removeIsExclude: Boolean,
)

/** Tutto cio' che la pagina mostra: lo stato del §1.5, le azioni del menu, i due blocchi di "dubbi" e i messaggi. */
data class AlbumPageUi(
  /** Null prima della prima lettura: la pagina mostra solo il titolo che le arriva dal chiamante. */
  val page: AlbumPageState? = null,
  val actions: List<AlbumAction> = emptyList(),
  /** "Suggerite" (uno smart, D14). */
  val suggested: SuggestionsState = SuggestionsState.None,
  /** "Da condividere" (uno smart condiviso, D17). */
  val toShare: SuggestionsState = SuggestionsState.None,
  val creatorName: String? = null,
  val message: AlbumMessage? = null,
  /** "Scegli copertina": il tocco su una cella la sceglie invece di aprirla. */
  val pickingCover: Boolean = false,
  val itemMenu: ItemMenu? = null,
)

class AlbumPageController(
  val id: AlbumId,
  private val albums: Albums,
  private val shared: SharedAlbums,
  private val choices: SmartChoices,
  private val runner: ShareRunner,
  private val people: AlbumPeople,
  private val members: AlbumMembers,
  private val pending: AlbumPending,
  clock: AlbumScreensClock,
  private val scope: CoroutineScope,
  /** Fase 8 (P10): per dire "Le persone non sono attive su questo dispositivo" e "Una persona della regola non e' piu' riconosciuta" (E-F37, E-F27). */
  private val peopleChoices: PeopleChoices = NoPeopleChoices,
) {
  /** Il foglio di condivisione di questa pagina ("Condividi", "Aggiungi", "Da condividere", "Riporta", file dal PC). */
  val share: ShareSheetController = ShareSheetController(runner, clock, scope)

  /** I fogli di chiusura di questa pagina ("Smetti di condividere", "Elimina l'album"). */
  val close: CloseSheetController = CloseSheetController(shared, share, scope)

  private data class Local(val message: AlbumMessage? = null, val pickingCover: Boolean = false, val itemMenu: ItemMenu? = null)

  private val local = MutableStateFlow(Local())
  private val _deleted = MutableStateFlow(false)

  /** Vero dopo "Elimina l'album" personale: la pagina non ha piu' niente da mostrare e la schermata torna indietro. */
  val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

  /**
   * Fase 8 (E-F37, E-F27): se questo album ha la regola delle persone, la striscia che dice che qui i volti non ci sono (l'album non cambia) o che una persona della
   * regola non e' piu' riconosciuta. Null per ogni altro album. Si rifa' quando cambia la regola o quando i volti si accendono o si spengono.
   */
  private val peopleBanner: StateFlow<AlbumBanner?> = combine(
    albums.all.map { list -> (list.byId(id) as? Album.Smart)?.rules?.people }.distinctUntilChanged(),
    peopleChoices.available,
  ) { rule, on -> rule to on }
    .map { (rule, on) ->
      when {
        rule == null -> null
        !on -> AlbumBanner.PeopleOff
        else -> {
          val lost = try {
            peopleChoices.inspect(rule.ids).lost.count { it in rule.ids }
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (_: Exception) {
            0
          }
          if (lost > 0) AlbumBanner.PeopleLost(lost) else null
        }
      }
    }
    .stateIn(scope, SharingStarted.Eagerly, null)

  val ui: StateFlow<AlbumPageUi> = combine(
    combine(albums.content(id), albums.status, albums.all) { content, status, list -> Triple(content, status, list.byId(id)) },
    combine(runner.operations, shared.notices, shared.left) { ops, notices, left -> Triple(ops, notices, left) },
    combine(local, peopleBanner) { loc, people -> loc to people },
  ) { (content, status, listed), (ops, notices, left), (loc, people) -> build(content, status, listed, ops, notices, left, loc, people) }
    .stateIn(scope, SharingStarted.Eagerly, AlbumPageUi())

  private fun build(
    content: AlbumContent?,
    status: AlbumsStatus,
    listed: Album?,
    ops: List<ShareOpState>,
    notices: List<SharedAlbumNotice>,
    left: List<Album.Shared>,
    loc: Local,
    peopleBanner: AlbumBanner?,
  ): AlbumPageUi {
    val mine = notices.filter { it.album == id }
    val closed = mine.filterIsInstance<SharedAlbumNotice.Closed>().firstOrNull()
    if (content == null) {
      // Revisione B (SC-1): l'ho appena chiuso io (o lo sto chiudendo): la pagina aspetta di portarmi all'album personale, non dice "non trovato".
      val closingHere = close.closed.value?.album == id || close.ui.value?.let { it.busy && it.sheet.album == id } == true
      val page: AlbumPageState? = when {
        closed != null -> AlbumPageState.Closed(closed)
        closingHere -> null
        left.any { it.id == id } -> AlbumPageState.Unavailable(AlbumUnavailableReason.LEFT)
        // Il catalogo non ha ancora la vista (l'album c'e' nell'elenco): ancora in lettura, non "non trovato".
        listed != null -> null
        else -> AlbumPageState.Unavailable(AlbumUnavailableReason.NOT_FOUND)
      }
      return AlbumPageUi(page = page, message = loc.message)
    }
    val album = content.album
    val shown = album as? Album.Shared
    val allowedOps = if (shown != null) shared.allowedOps(id) else emptySet()
    val permissions = shown?.let { PermissionInfo(it.level, it.creator, it.mine, allowedOps) }
    val page: AlbumPageState =
      if (status is AlbumsStatus.Recomputing && album is Album.Smart && content.items.size == 0) {
        AlbumPageState.Recomputing(album, status.done, status.total)
      } else {
        AlbumPageState.Ready(content, album.sort, permissions, banners(content, mine, ops, peopleBanner))
      }
    val suggested = content.suggested?.takeIf { it.size > 0 }?.let { SuggestionsState.Some(it.size, it) } ?: SuggestionsState.None
    val toShare = content.toShare?.takeIf { it.size > 0 }?.let { SuggestionsState.ToShare(it.size, it) } ?: SuggestionsState.None
    // Revisione B (SH-3, D39): "Aggiungi i file della cartella" solo quando ci sono davvero file aggiunti dal PC (prima c'era sempre e spesso non faceva niente).
    val pcFiles = mine.any { it is SharedAlbumNotice.FilesAddedFromPc && it.count > 0 }
    return AlbumPageUi(
      page = page,
      actions = AlbumPolicy.pageActions(album, allowedOps).filter { it != AlbumAction.ADOPT_FOLDER_FILES || pcFiles },
      suggested = suggested,
      toShare = toShare,
      creatorName = shown?.let { people.nameOf(it.creator) },
      message = loc.message,
      pickingCover = loc.pickingCover,
      itemMenu = loc.itemMenu,
    )
  }

  private fun banners(content: AlbumContent, notices: List<SharedAlbumNotice>, ops: List<ShareOpState>, peopleBanner: AlbumBanner?): List<AlbumBanner> = buildList {
    // Prima cio' che e' in corso, poi cio' che chiede un gesto, poi le informazioni.
    ops.filter { it.album == id && it !is ShareOpState.Done }.forEach { add(AlbumBanner.Sharing(it)) }
    if (content.incoming > 0) add(AlbumBanner.Incoming(content.incoming))
    val toShare = content.toShare?.size ?: 0
    if (toShare > 0) add(AlbumBanner.ToShare(toShare))
    notices.forEach { notice ->
      when (notice) {
        is SharedAlbumNotice.FilesAddedFromPc -> add(AlbumBanner.FilesFromPc(notice.count))
        // P13 (E-SH16): con la cartella nuova nota, il creatore la puo' adottare con un tocco.
        is SharedAlbumNotice.FolderChanged -> add(notice.newFolder?.let { AlbumBanner.UseNewFolder(it) } ?: AlbumBanner.FolderChanged)
        is SharedAlbumNotice.RevokedItems -> if (notice.count > 0) add(AlbumBanner.RevokedItems(notice.count))
        is SharedAlbumNotice.NotAddedLevelChanged -> add(AlbumBanner.NotAdded(notice.count))
        is SharedAlbumNotice.Inherited -> add(AlbumBanner.Inherited)
        is SharedAlbumNotice.Closed -> Unit
      }
    }
    val smart = content.album as? Album.Smart
    if (smart != null && smart.newSinceSeen > 0) add(AlbumBanner.NewItems(smart.newSinceSeen))
    // Fase 8: le persone della regola (E-F37, E-F27) -- solo per uno smart, e solo se c'e' qualcosa da dire.
    if (smart != null && peopleBanner != null) add(peopleBanner)
  }

  // --- Messaggi e modalita' -----------------------------------------------------------------------------------------------------

  fun dismissMessage() = local.update { it.copy(message = null) }

  private fun say(message: AlbumMessage?) = local.update { it.copy(message = message) }

  /**
   * Una modifica che scrive il registro dei dati personali: se non si salva (memoria piena, `IOException`) lo dice [AlbumMessage.NotSaved] e niente e' cambiato (K3b, SOL-10, OA-11:
   * prima l'eccezione arrivava all'ambito della pagina e l'app si chiudeva). La cancellazione passa, un errore di programmazione resta un errore. Vero se e' andata.
   */
  private suspend fun save(label: String, write: suspend () -> EditOutcome): Boolean {
    val out = QuietWrites.attemptResult(label, write)
    if (out == null) {
      say(AlbumMessage.NotSaved)
      return false
    }
    return report(out)
  }

  /** Un esito di modifica diventa, se non e' andata, un messaggio sotto l'intestazione. */
  private fun report(out: EditOutcome): Boolean {
    when (out) {
      EditOutcome.Done -> return true
      EditOutcome.NotFound -> say(AlbumMessage.NotFound)
      is EditOutcome.NotAllowed -> say(AlbumMessage.NotAllowed)
      is EditOutcome.Invalid -> say(if (out.problem != null) AlbumMessage.InvalidTitle(out.problem) else AlbumMessage.NotInAlbum)
      is EditOutcome.Blocked -> say(AlbumMessage.Blocked(WhyBlocked.of(out.reason)))
    }
    return false
  }

  fun startPickingCover() = local.update { it.copy(pickingCover = true, itemMenu = null, message = null) }

  fun stopPickingCover() = local.update { it.copy(pickingCover = false) }

  // --- Azioni sull'album --------------------------------------------------------------------------------------------------------

  fun rename(title: String) {
    scope.launch { save("nome dell'album") { albums.rename(id, title) } }
  }

  fun setSort(sort: AlbumSort) {
    scope.launch { save("ordine dell'album") { albums.setSort(id, sort) } }
  }

  /** La copertina scelta (da "Scegli copertina" o dal menu di una cella); null = automatica. */
  fun setCover(media: MediaId?) {
    scope.launch {
      if (save("copertina dell'album") { albums.setCover(id, media) }) local.update { it.copy(pickingCover = false, itemMenu = null) }
    }
  }

  fun setLevel(level: ShareLevel) {
    scope.launch { save("livello dell'album condiviso") { shared.setLevel(id, level) } }
  }

  fun leave() {
    scope.launch { save("album lasciato") { shared.leave(id) } }
  }

  fun inherit() {
    scope.launch {
      when (val out = QuietWrites.attemptResult("album ereditato") { shared.inherit(id) }) {
        null -> say(AlbumMessage.NotSaved)
        is InheritOutcome.Blocked -> say(AlbumMessage.Blocked(WhyBlocked.of(out.reason)))
        InheritOutcome.Inherited -> Unit
        else -> say(AlbumMessage.NotAllowed)
      }
    }
  }

  /** "Elimina l'album" di un album personale: i media restano dove sono (D39). Un condiviso si chiude dal foglio del creatore. */
  fun deletePersonal() {
    scope.launch { if (save("album eliminato") { albums.delete(id) }) _deleted.value = true }
  }

  fun freeze() {
    scope.launch { save("album congelato") { choices.freeze(id) } }
  }

  fun unfreeze() {
    scope.launch { save("album scongelato") { choices.unfreeze(id) } }
  }

  /**
   * Aggiunge [media] (scelti dal selettore, che non offre mai i nascosti). Un album personale li prende subito; uno condiviso apre il
   * foglio di conferma di D12 (mai un'aggiunta diretta, D35).
   */
  fun add(media: List<MediaId>) {
    if (media.isEmpty()) return
    scope.launch {
      val out = QuietWrites.attemptResult("foto aggiunte all'album") { albums.add(id, media) }
      if (out == null) {
        say(AlbumMessage.NotSaved)
        return@launch
      }
      when (out) {
        is AddOutcome.Added -> Unit
        is AddOutcome.NeedsShareConfirmation, is AddOutcome.Blocked -> share.onAddOutcome(out)
        is AddOutcome.NothingToAdd -> say(AlbumMessage.NothingToAdd(out.excluded))
        is AddOutcome.NotAllowed -> say(AlbumMessage.NotAllowed)
        AddOutcome.NotFound -> say(AlbumMessage.NotFound)
      }
    }
  }

  /** "Condividi...": un album personale diventa un album condiviso nuovo (livello di partenza Q2, nascosti lasciati fuori, Q3). */
  fun shareThisAlbum() {
    share.request(ShareRequest.FromPersonal(id, ShareLevel.DEFAULT))
  }

  /** Dal banner "Da condividere" (D17, Q4 a): le foto nuove di uno smart condiviso, con un tocco che apre la conferma col conteggio. */
  fun shareNew(media: List<MediaId>) {
    if (media.isEmpty()) return
    share.request(ShareRequest.AddToShared(id, media))
  }

  /** Dal banner "N file nuovi nella cartella" (D39): adotta i file aggiunti dal PC, con la conferma. I file li dice la porta [AlbumPending]. */
  fun adoptFolderFiles() {
    scope.launch {
      val media = pending.filesFromFolder(id)
      if (media.isNotEmpty()) share.request(ShareRequest.FolderAdoption(id, media))
    }
  }

  /** Dal banner "Non aggiunte: l'album ora e' in sola visione" (E-SH13): "Riporta nelle mie cartelle", con la conferma. */
  fun bringBackRejected() {
    scope.launch {
      val media = pending.notAdded(id)
      if (media.isNotEmpty()) share.request(ShareRequest.BringBack(id, media))
    }
  }

  /**
   * E-SH16 (P13): "Usa la nuova cartella" (solo il creatore o l'erede): la definizione prende la cartella dove gli elementi stanno ora. Revisione B (R15):
   * un errore imprevisto (una cartella che il formato degli album non accetta) diventa un messaggio, non la caduta dell'app.
   */
  fun useNewFolder() {
    scope.launch { guarded { shared.useNewFolder(id) }?.let(::report) }
  }

  /** E-SH20 (P13): "Ripristina gli elementi" di un mio dispositivo revocato: tornano come mie voci (nessun file si muove). Revisione B (R15): come sopra. */
  fun restoreRevoked() {
    scope.launch { guarded { shared.restoreRevoked(id) }?.let(::report) }
  }

  /** Revisione B (R15): un'azione dell'album che lancia non arriva all'ambito della pagina: lo dice un messaggio, niente e' cambiato. */
  private suspend fun guarded(block: suspend () -> EditOutcome): EditOutcome? = try {
    block()
  } catch (e: kotlinx.coroutines.CancellationException) {
    throw e
  } catch (e: Exception) {
    say(AlbumMessage.NotDone)
    null
  }

  /**
   * Revisione B (SH-2, E-SH18, §2.5 "Errori"): "Riprova" dalla striscia di una condivisione finita con dei problemi. Gli elementi da riprovare li
   * dice l'orchestratore; diventano un piano nuovo e il foglio di sempre, con la conferma dell'utente. Niente da riprovare: la striscia si chiude.
   */
  fun retryShare() {
    val op = runner.operations.value.firstOrNull { it.album == id && it is ShareOpState.PartlyDone } ?: return
    scope.launch {
      val request = runner.retryRequest(op.op)
      if (request == null) runner.dismiss(op.op) else share.request(request)
    }
  }

  /** Dall'ordine personalizzato (D21): [media] in cima o in fondo all'album. */
  fun moveToStart(media: MediaId) {
    local.update { it.copy(itemMenu = null) }
    scope.launch { save("ordine delle foto dell'album") { albums.reorder(id, media, null) } }
  }

  fun moveToEnd(media: MediaId) {
    local.update { it.copy(itemMenu = null) }
    val view = (ui.value.page as? AlbumPageState.Ready)?.content?.items ?: return
    val last = (view.size - 1 downTo 0).map { view.snapshot.idAt(view.snapshotIndex(it)) }.firstOrNull { it != media } ?: return
    scope.launch { save("ordine delle foto dell'album") { albums.reorder(id, media, last) } }
  }

  /** "Riporta nelle mie cartelle" per un elemento solo (D31): un piano, e il foglio. */
  fun bringBack(media: MediaId) {
    local.update { it.copy(itemMenu = null) }
    share.request(ShareRequest.BringBack(id, listOf(media)))
  }

  /** Toglie [media] dall'album (D39: il file resta dov'e'; su uno smart un automatico diventa un'esclusione). */
  fun remove(media: MediaId) {
    local.update { it.copy(itemMenu = null) }
    scope.launch { save("foto tolta dall'album") { albums.remove(id, listOf(media)) } }
  }

  fun dismissNotice(notice: SharedAlbumNotice) {
    scope.launch { QuietWrites.attempt("avviso letto") { shared.dismissNotice(notice) } }
  }

  // --- Il menu di un elemento --------------------------------------------------------------------------------------------------

  /** Apre il menu di [media]: cosa se ne puo' fare secondo la tabella, e chi l'ha aggiunto (Q6). */
  fun openItemMenu(media: MediaId) {
    val content = ui.value.page.let { (it as? AlbumPageState.Ready)?.content } ?: return
    // D22: il menu si apre solo per un elemento che la vista dell'album mostra; un id che non c'e' (un nascosto, uno tolto) non apre niente.
    if (content.items.viewIndexOf(media) < 0) return
    val album = content.album
    scope.launch {
      val albumOps = if (album is Album.Shared) shared.allowedOps(id) else emptySet()
      val itemOps = if (album is Album.Shared) shared.allowedOps(id, media) else emptySet()
      val actions = AlbumPolicy.itemActions(album, albumOps, itemOps)
      if (!actions.any) return@launch
      val addedBy = if (album is Album.Shared) members.addedBy(id, media) else null
      local.update {
        it.copy(itemMenu = ItemMenu(media, actions, addedBy, isCover = album.cover.media == media, removeIsExclude = album is Album.Smart && !album.frozen), message = null)
      }
    }
  }

  fun closeItemMenu() = local.update { it.copy(itemMenu = null) }
}
