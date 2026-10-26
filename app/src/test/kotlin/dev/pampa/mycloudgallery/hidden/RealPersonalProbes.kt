package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.CoverRef
import dev.pampa.mycloudgallery.core.albums.personal.PersonalAlbumCopies
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.first

// Fase 7 (P9, D22, E-H6; integrazione 26/10): le SONDE sul codice VERO di P3 (album personali: `CatalogAlbums`, la lettura, la proiezione sulle tabelle v6, le
// copie di Q5 e gli elementi in attesa), sul catalogo vero del mondo casuale e con lo store vero dei dati personali. Prendono il posto della sonda di
// riferimento degli album manuali (`ReferenceProbes.manualAlbums`).
//
// Cio' che l'elenco dice si confronta con l'oracolo ricavato dal mondo: un album mostra **esattamente** i suoi elementi che sono celle della Libreria adesso
// (non nascosti, non membri di un gruppo con un nascosto, una cella per gruppo: D20, D22, E-L11), il conteggio e' quello, e la copertina e' un elemento
// mostrabile (E-H6: una copertina scelta altrove e nascosta qui diventa quella automatica). La Libreria e' quella dell'istantanea (che puo' essere piu'
// vecchia dei nascosti dell'ultimo momento: il filtro finale li toglie comunque).

internal object RealPersonalProbes {

  /** Le celle della Libreria (adesso) fra gli elementi [indices] del mondo. */
  private fun cells(w: BuiltWorld, indices: Collection<Int>): Set<MediaId> = indices.map { w.ids[it] }.filterTo(LinkedHashSet()) { it in w.liveLibraryCells }

  /** I media mostrabili (adesso) fra gli elementi [indices]: una copertina puo' essere anche un membro non copertina di un gruppo visibile. */
  private fun showable(w: BuiltWorld, indices: Collection<Int>): Set<MediaId> = indices.map { w.ids[it] }.filterTo(LinkedHashSet()) { it in w.liveShowable }

  private fun idsOf(view: OrderedTimelineView?): List<MediaId> = view?.let { v -> (0 until v.size).map { v.snapshot.idAt(v.snapshotIndex(it)) } }.orEmpty()

  /** Cio' che serve a ogni album: il conteggio, gli elementi della vista e la copertina; le violazioni si scrivono in [problems]. */
  private fun check(
    name: String,
    w: BuiltWorld,
    album: Album?,
    items: List<MediaId>,
    members: List<Int>,
    chosen: Int?,
    problems: MutableList<String>,
    shown: MutableSet<MediaId>,
  ) {
    if (album == null) {
      problems += "l'album $name non e' fra quelli dell'elenco"
      return
    }
    val want = cells(w, members)
    if (items.toSet() != want || items.size != want.size) problems += "l'album $name non mostra esattamente i suoi elementi visibili"
    if (album.count != want.size) problems += "il conteggio dell'album $name e' ${album.count}, ne attendeva ${want.size}"
    shown += items
    val cover = album.cover
    cover.media?.let { shown += it }
    val ok = showable(w, members)
    val memberIds = members.map { w.ids[it] }.toSet()
    if (album.count > 0 && ok.isNotEmpty() && cover.media == null) problems += "l'album $name ha elementi visibili ma nessuna copertina"
    if (album.count == 0 && cover != CoverRef.None) problems += "l'album $name senza elementi visibili ha una copertina"
    if (cover.media != null && cover.media !in memberIds) problems += "la copertina dell'album $name non e' un suo elemento"
    if (cover.media != null && cover.media !in ok) problems += "la copertina dell'album $name non e' un elemento mostrabile"
    if (chosen != null) {
      val pick = w.ids[chosen]
      // E-H6: la copertina scelta altrove vale se qui si puo' mostrare; se qui e' nascosta, la copertina e' quella automatica.
      if (pick !in w.liveShowable && cover is CoverRef.Chosen) problems += "la copertina scelta dell'album $name e' un mio nascosto e resta la copertina"
      if (pick in w.liveShowable && album.count > 0 && cover != CoverRef.Chosen(pick)) problems += "la copertina scelta dell'album $name e' visibile ma non e' quella dell'album"
    }
  }

  /** Gli album manuali veri: elenco, conteggio, copertina scelta altrove (E-H6) e contenuto. */
  val manualAlbums = SurfaceProbe { w ->
    RealPersonalStack.build(w).use { stack ->
      val problems = ArrayList<String>()
      val shown = LinkedHashSet<MediaId>()
      val listed = stack.albums.all.value.byId(stack.manual) as? Album.Manual
      val content = stack.albums.content(stack.manual).first()
      if (content == null) problems += "il contenuto dell'album manuale non c'e'"
      check("manuale", w, listed, idsOf(content?.items), stack.manualMembers, stack.chosenCover, problems, shown)
      // Ogni elenco che l'album mostra (anche "tutti gli album") non ha mai un nascosto; il resto lo dice `Probed.within`.
      Probed(shown = shown, within = w.liveShowable, problems = problems)
    }
  }

  /** Gli album smart veri (la lettura di P3): conteggio, "Suggerite" e contenuto, con righe del motore (P6) e suggeriti che includono nascosti. */
  val smartAlbums = SurfaceProbe { w ->
    RealPersonalStack.build(w).use { stack ->
      val problems = ArrayList<String>()
      val shown = LinkedHashSet<MediaId>()
      val listed = stack.albums.all.value.byId(stack.smart) as? Album.Smart
      val content = stack.albums.content(stack.smart).first()
      if (content == null) problems += "il contenuto dell'album smart non c'e'"
      check("smart", w, listed, idsOf(content?.items), stack.smartAccepted + stack.smartAuto, null, problems, shown)
      val wantSuggested = cells(w, stack.smartSuggested)
      val suggested = idsOf(content?.suggested)
      shown += suggested
      if (suggested.toSet() != wantSuggested || suggested.size != wantSuggested.size) problems += "le Suggerite non sono esattamente le visibili"
      if (listed != null && listed.suggested != wantSuggested.size) problems += "il numero delle Suggerite e' ${listed.suggested}, ne attendeva ${wantSuggested.size}"
      Probed(shown = shown, within = w.liveShowable, problems = problems)
    }
  }

  /** Le copie personali di Q5: l'album che `PersonalAlbumCopies` scrive (anche con elementi nascosti qui) si legge senza i nascosti, e un secondo giro non riscrive. */
  val personalCopies = SurfaceProbe { w ->
    RealPersonalStack.build(w).use { stack ->
      val problems = ArrayList<String>()
      val shown = LinkedHashSet<MediaId>()
      if (stack.copyResult != PersonalAlbumCopies.Result.CREATED) problems += "la copia personale non e' stata creata"
      if (stack.copyAgain != PersonalAlbumCopies.Result.UNCHANGED) problems += "un secondo giro riscrive la copia personale"
      val listed = stack.albums.all.value.byId(stack.copy) as? Album.Manual
      val content = stack.albums.content(stack.copy).first()
      if (content == null) problems += "il contenuto della copia personale non c'e'"
      check("copia personale", w, listed, idsOf(content?.items), stack.copyMembers, null, problems, shown)
      Probed(shown = shown, within = w.liveShowable, problems = problems)
    }
  }

  /**
   * Gli elementi in attesa: id che questo catalogo non conosce. Quelli ritrovati per impronta valgono come gli altri (anche un nascosto qui resta nascosto),
   * quelli che non arrivano non si contano, quello arrivato e subito nascosto non compare e non si conta, quello arrivato e visibile si conta.
   */
  val pendingItems = SurfaceProbe { w ->
    RealPersonalStack.build(w).use { stack ->
      val problems = ArrayList<String>()
      val shown = LinkedHashSet<MediaId>()
      val listed = stack.albums.all.value.byId(stack.pending) as? Album.Manual
      val content = stack.albums.content(stack.pending).first()
      val want = cells(w, stack.byFingerprint)
      val items = idsOf(content?.items)
      shown += items
      if (listed == null) {
        problems += "l'album con gli elementi in attesa non e' fra quelli dell'elenco"
      } else {
        // L'elemento arrivato e visibile si conta (la sua cella e' nell'istantanea di dopo); quello arrivato nascosto e quelli che non arrivano no.
        if (listed.count != want.size + 1) problems += "il conteggio dell'album con elementi in attesa e' ${listed.count}, ne attendeva ${want.size + 1}"
        // La copertina puo' essere il media arrivato adesso (non e' un media del mondo): si controlla qui, non nell'insieme di quelli mostrati.
        listed.cover.media?.takeIf { it in w.allIds }?.let { shown += it }
        if (listed.cover.media == stack.arrivedHidden) problems += "la copertina e' il media arrivato e subito nascosto"
      }
      if (items.toSet() != want || items.size != want.size) problems += "l'album con elementi in attesa non mostra esattamente i suoi elementi visibili"
      if (stack.arrivedHidden in items) problems += "il media arrivato e subito nascosto compare"
      val waiting = stack.albums.projection.pendingOf(stack.pending.value)
      if (waiting != stack.neverArrive) problems += "gli elementi in attesa sono $waiting, ne attendeva ${stack.neverArrive}"
      Probed(shown = shown, within = w.liveShowable, problems = problems)
    }
  }

  /** Il viewer aperto da un album scorre la vista dell'album (`AlbumContent.items`): non si arriva a un nascosto nemmeno cercandolo per id. */
  val albumViewer = SurfaceProbe { w ->
    RealPersonalStack.build(w).use { stack ->
      val problems = ArrayList<String>()
      val shown = LinkedHashSet<MediaId>()
      for ((name, id) in listOf("manuale" to stack.manual, "smart" to stack.smart, "copia personale" to stack.copy, "con elementi in attesa" to stack.pending)) {
        val view = stack.albums.content(id).first()?.items ?: continue
        shown += idsOf(view)
        for (hidden in w.liveMustNeverShow) if (view.viewIndexOf(hidden) >= 0) problems += "il viewer dell'album $name arriva a un nascosto"
      }
      Probed(shown = shown, within = w.liveShowable, problems = problems)
    }
  }
}
