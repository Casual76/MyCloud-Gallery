package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.noop.NoShareRunner
import dev.pampa.mycloudgallery.core.albums.noop.NoSharedAlbums
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.albums.choose.AddToAlbumController
import dev.pampa.mycloudgallery.feature.albums.contract.AccountNamesAsIs
import dev.pampa.mycloudgallery.feature.albums.contract.AddToAlbumRequest
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPageState
import dev.pampa.mycloudgallery.feature.albums.contract.FixedAlbumScreensClock
import dev.pampa.mycloudgallery.feature.albums.contract.NoAlbumMembers
import dev.pampa.mycloudgallery.feature.albums.contract.NoAlbumPending
import dev.pampa.mycloudgallery.feature.albums.contract.SuggestionsState
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageController
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageUi
import dev.pampa.mycloudgallery.feature.albums.page.SuggestionKind
import dev.pampa.mycloudgallery.feature.albums.page.SuggestionsController
import dev.pampa.mycloudgallery.integration.Attese
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

// Fase 7 (P10, D22, D35): le SONDE sul codice VERO delle schermate degli album (`feature-albums`): "Aggiungi ad album" e le pagine degli album (la pagina
// e "Suggerite"), con i controllori veri sopra lo STACK VERO di P3 (`CatalogAlbums`, il catalogo vero del mondo casuale, la visibilita' vera). Prendono il posto
// della riga "PENDING" di "Aggiungi ad album": adesso la superficie esiste e la sua sonda gira ogni volta che gira il registro.
//
// Cio' che si prova: i media che il chooser passa agli album sono **esattamente** quelli che `VisibleMedia` lascia mostrare (un nascosto non si offre fuori dai
// Nascosti, e senza la porta della visibilita' non se ne offre nessuno); le pagine degli album mostrano solo elementi mostrabili e il menu di un elemento non
// si apre per un id che la vista non ha (un nascosto, anche cercandolo per id).

internal object RealAlbumScreensProbes {

  private val clock = FixedAlbumScreensClock(LocalDate.of(2026, 10, 21), 0L)

  /** Il tetto di ogni attesa su un evento: quello di tutti i test sul grafo vero (venti secondi non bastavano con altre build sul PC). */
  private val WAIT_MS = Attese.TETTO_MS

  private fun idsOf(view: OrderedTimelineView?): List<MediaId> = view?.let { v -> (0 until v.size).map { v.snapshot.idAt(v.snapshotIndex(it)) } }.orEmpty()

  /** "Aggiungi ad album": cio' che il chooser vero offre e passa agli album, su tutti i media del mondo (nascosti compresi, come potrebbe passarli un chiamante). */
  val addToAlbum = SurfaceProbe { w ->
    RealPersonalStack.build(w).use { stack ->
      val problems = ArrayList<String>()
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
      try {
        val recorded = LinkedHashSet<MediaId>()
        val recording = object : Albums by stack.albums {
          override suspend fun add(id: AlbumId, media: List<MediaId>): AddOutcome {
            recorded += media
            return stack.albums.add(id, media)
          }
        }
        fun chooser(request: AddToAlbumRequest, withVisibility: Boolean = true) = AddToAlbumController(
          request, recording, NoSharedAlbums, if (withVisibility) stack.visibility else null, NoShareRunner, clock, scope,
        ).also { it.start() }

        // 1. Fuori dai Nascosti: si offre solo cio' che si puo' mostrare, e solo questo arriva agli album.
        val normal = chooser(AddToAlbumRequest(w.ids))
        val ready = withTimeout(WAIT_MS) { normal.ui.first { it.ready } }
        if (ready.count != w.liveShowable.size) problems += "il chooser offre ${ready.count} media, ne attendeva ${w.liveShowable.size}"
        val target = stack.albums.all.value.byId(stack.manual)
        if (target == null) {
          problems += "l'album manuale non e' fra le scelte"
        } else {
          if (ready.choices.none { it.id == target.id }) problems += "l'album manuale non e' fra le scelte del foglio"
          // Se il mondo e' tutto nascosto non si offre niente e l'aggiunta non parte (nessun risultato da aspettare).
          if (ready.count > 0) {
            normal.add(target)
            withTimeout(WAIT_MS) { normal.ui.first { it.result != null || it.message != null } }
          }
        }
        val offered = LinkedHashSet(recorded)

        // 2. Senza la porta della visibilita' non si offre niente (fallisce chiuso).
        val blind = chooser(AddToAlbumRequest(w.ids), withVisibility = false)
        val blindReady = withTimeout(WAIT_MS) { blind.ui.first { it.ready } }
        if (blindReady.count != 0) problems += "senza la porta della visibilita' il chooser offre ${blindReady.count} media"

        // 3. Dai Nascosti si offre tutto (D22: e' la pagina dei nascosti): e' una scelta di chi chiama, qui si prova solo che il conto sia quello.
        val fromHidden = chooser(AddToAlbumRequest(w.ids, fromHidden = true))
        val hiddenReady = withTimeout(WAIT_MS) { fromHidden.ui.first { it.ready } }
        if (hiddenReady.count != w.ids.distinct().size) problems += "dai Nascosti il chooser offre ${hiddenReady.count} media, ne attendeva ${w.ids.distinct().size}"

        // 4. Dopo l'aggiunta l'album non mostra un nascosto (la vista nasce dal catalogo).
        val content = stack.albums.content(stack.manual).first()
        val after = idsOf(content?.items).toSet()
        for (media in w.liveMustNeverShow) if (media in after) problems += "l'album mostra un nascosto dopo l'aggiunta"
        Probed(shown = offered, expected = w.liveShowable, within = w.liveShowable, problems = problems)
      } finally {
        scope.coroutineContext[Job]?.cancelAndJoin()
      }
    }
  }

  /** Le pagine degli album (la pagina e "Suggerite") coi controllori veri: cio' che la pagina mostra, il menu di un elemento e le viste dei dubbi. */
  val albumPages = SurfaceProbe { w ->
    RealPersonalStack.build(w).use { stack ->
      val problems = ArrayList<String>()
      val shown = LinkedHashSet<MediaId>()
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
      try {
        val albums = listOf("manuale" to stack.manual, "smart" to stack.smart, "copia personale" to stack.copy, "con elementi in attesa" to stack.pending)
        var menuBroken = false
        for ((name, id) in albums) {
          val page = AlbumPageController(id, stack.albums, NoSharedAlbums, stack.albums, NoShareRunner, AccountNamesAsIs, NoAlbumMembers, NoAlbumPending, clock, scope)
          val ui = withTimeout(WAIT_MS) { page.ui.first { it.page is AlbumPageState.Ready } }
          val ready = ui.page as AlbumPageState.Ready
          val items = idsOf(ready.content.items)
          shown += items
          ready.content.album.cover.media?.takeIf { it in w.allIds }?.let { shown += it }
          (ui.suggested as? SuggestionsState.Some)?.let { shown += idsOf(it.view) }
          // Un nascosto non si raggiunge nemmeno chiedendo il menu per id: la vista non lo ha, il menu non si apre.
          for (hidden in w.liveMustNeverShow) {
            page.openItemMenu(hidden)
            if (page.ui.value.itemMenu != null) problems += "la pagina dell'album $name apre il menu di un nascosto"
          }
          // Controllo positivo: per un elemento che la vista mostra il menu si apre (altrimenti il controllo qui sopra passerebbe sempre). La vista e il menu della pagina sono
          // un solo flusso `ui` messo insieme da piu' sorgenti su thread diversi: subito dopo `openItemMenu` il valore letto puo' essere ancora quello di prima (una lettura del
          // catalogo in volo), e la prova cadeva solo a volte (seme 18, sotto carico). Si aspetta lo stato preciso: il menu di un elemento che la vista mostra **adesso**, e se la
          // vista e' cambiata sotto i piedi si chiede di nuovo (come farebbe un secondo tocco). Una pagina che non lo apre mai da' il suo problema dopo il tetto, una volta per mondo.
          if (!menuBroken && items.isNotEmpty()) {
            var lastSeen: AlbumPageUi? = null
            val opened = withTimeoutOrNull(WAIT_MS) {
              page.ui.first { state ->
                lastSeen = state
                val candidate = (state.page as? AlbumPageState.Ready)?.content?.items?.let { idsOf(it).firstOrNull() }
                when {
                  candidate == null -> false
                  state.itemMenu?.media == candidate -> true
                  else -> {
                    page.openItemMenu(candidate)
                    false
                  }
                }
              }
            }
            if (opened == null) {
              menuBroken = true
              val now = lastSeen
              problems += "la pagina dell'album $name non apre il menu di un elemento che mostra (pagina ${now?.page?.let { it::class.simpleName }}, elementi nella vista: ${(now?.page as? AlbumPageState.Ready)?.content?.items?.size}, menu: ${now?.itemMenu != null})"
            }
            page.closeItemMenu()
          }
        }
        // "Suggerite" dello smart: la vista dei dubbi e' del catalogo, senza nascosti.
        val suggestions = SuggestionsController(stack.smart, SuggestionKind.SUGGESTED, stack.albums, stack.albums, NoShareRunner, clock, scope)
        val suggested = withTimeout(WAIT_MS) { suggestions.ui.first { it.loaded } }
        shown += idsOf(suggested.view)
        for (media in suggestions.target()) if (media in w.liveMustNeverShow) problems += "le Suggerite propongono un nascosto"
        Probed(shown = shown, within = w.liveShowable, problems = problems)
      } finally {
        scope.coroutineContext[Job]?.cancelAndJoin()
      }
    }
  }
}
