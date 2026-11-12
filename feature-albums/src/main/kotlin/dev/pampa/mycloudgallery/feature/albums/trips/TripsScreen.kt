package dev.pampa.mycloudgallery.feature.albums.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.rememberFluidScreenMetrics
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripProposal
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryCoverCard
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.contract.TripsState
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumCoverThumb
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumDimens
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumText

// LE PROPOSTE DI VIAGGIO (Fase 7, P10; docs/fase7-progetto.md §1.5 `TripsState`, D19, D20, E-AL16). I4 (fase I, «l'app parla troppo»): non piu' una
// scheda di testo con due bottoni per proposta, ma **una griglia di schede** come «Tutti i ricordi» di I1: la copertina a tutta scheda, il nome
// («Oslo, marzo 2026») e «Viaggio» o «Evento» sopra la sfumatura; la ✕ di «Non mi interessa» in alto a destra e «Crea album» sotto la scheda. Il
// numero delle foto e i giorni li dice TalkBack. Senza una casa stimabile (nessuna foto con la posizione) la pagina lo spiega una volta e propone
// gli eventi. Nessuna coordinata: il contratto non le porta. La scheda e' quella comune di core-designsystem (`GalleryCoverCard`), la stessa dei ricordi.

interface TripsHandlers {
  fun onBack()

  fun onCreate(id: TripId)

  fun onDismiss(id: TripId)

  fun onShowOlder()
}

object NoTripsHandlers : TripsHandlers {
  override fun onBack() = Unit

  override fun onCreate(id: TripId) = Unit

  override fun onDismiss(id: TripId) = Unit

  override fun onShowOlder() = Unit
}

@Composable
fun TripsScreen(
  state: TripsState,
  thumbnails: AlbumThumbnails,
  handlers: TripsHandlers,
  modifier: Modifier = Modifier,
  creating: TripId? = null,
  /** VG-2: un messaggio sopra le proposte (la proposta non c'e' piu'); null = nessuno. */
  notice: String? = null,
  onDismissNotice: () -> Unit = {},
  listState: LazyListState = rememberLazyListState(),
) {
  val shown = when (state) {
    is TripsState.Proposals -> state.proposals
    is TripsState.NoGps -> state.events
    TripsState.Empty, TripsState.Searching -> emptyList()
  }
  val requests = remember(shown) { shown.mapNotNull { thumbnails.refs.request(it.cover) } }
  LaunchedEffect(requests, thumbnails) { thumbnails.loader.window(requests, emptyList()) }
  val metrics = rememberFluidScreenMetrics()
  val columns = metrics.columns(minColumn = AlbumDimens.TripCardMin, spacing = AlbumDimens.BlockGap, maxColumns = MAX_COLUMNS)
  FluidScreen(
    title = stringResource(R.string.trips_title),
    modifier = modifier,
    onBack = handlers::onBack,
    listState = listState,
    metrics = metrics,
    contentMaxWidth = AlbumDimens.PageWidth,
  ) {
    if (notice != null) item(key = "notice") { FluidInlineMessage(message = notice, tone = FluidTone.Info, onDismiss = onDismissNotice) }
    when (state) {
      // CU-23 (fase I): gli stati vuoti nella forma comune (figura e titolo), non una parola in alto a sinistra.
      TripsState.Searching -> item(key = "searching") { GalleryFigureState(icon = Icons.Rounded.TravelExplore, title = stringResource(R.string.trips_searching_title), fill = false) }
      TripsState.Empty -> item(key = "empty") { GalleryFigureState(icon = Icons.Rounded.Luggage, title = stringResource(R.string.trips_empty_title), fill = false) }
      is TripsState.NoGps -> {
        // Senza una casa stimabile la pagina lo dice una volta (e' il perche' dei soli eventi), poi gli eventi.
        item(key = "no-gps") {
          FluidInlineMessage(message = stringResource(R.string.trips_no_gps_detail), title = stringResource(R.string.trips_no_gps_title), tone = FluidTone.Info)
        }
        item(key = "events-header") { GallerySectionHeader(title = stringResource(R.string.trips_events_header)) }
        if (state.events.isEmpty()) {
          item(key = "events-empty") { GalleryFigureState(icon = Icons.Rounded.Event, title = stringResource(R.string.trips_events_empty), fill = false) }
        } else {
          proposalCards(state.events, columns, thumbnails, handlers, creating)
        }
      }
      is TripsState.Proposals -> {
        proposalCards(state.proposals, columns, thumbnails, handlers, creating)
        if (state.hasOlder) {
          item(key = "older") {
            FluidButton(text = stringResource(R.string.trips_show_older), onClick = handlers::onShowOlder, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
          }
        }
      }
    }
  }
}

/** Le proposte in una griglia, a righe di [columns] schede (le righe sono gli elementi della lista: si compongono solo quelle sullo schermo). */
private fun LazyListScope.proposalCards(proposals: List<TripProposal>, columns: Int, thumbnails: AlbumThumbnails, handlers: TripsHandlers, creating: TripId?) {
  proposals.chunked(columns).forEach { row ->
    item(key = "row-" + row.first().id.value, contentType = "trip-row") {
      Row(horizontalArrangement = Arrangement.spacedBy(AlbumDimens.BlockGap), modifier = Modifier.fillMaxWidth()) {
        row.forEach { trip -> ProposalCard(trip, thumbnails, handlers, creating == trip.id, Modifier.weight(1f)) }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
      }
    }
  }
}

/**
 * Una proposta (I4, fase I): come le schede di «Tutti i ricordi» di I1, la copertina a tutta scheda con il titolo in basso sopra la sfumatura
 * (la coppia di `GalleryOnPhoto`) e al piu' una riga piccola, «Viaggio» o «Evento» (il mese e' gia' nel titolo). Il numero delle foto e i giorni
 * li dice TalkBack. In alto a destra la ✕ di «Non mi interessa»; sotto la scheda il tasto «Crea album» con il +, largo quanto la scheda.
 */
@Composable
private fun ProposalCard(trip: TripProposal, thumbnails: AlbumThumbnails, handlers: TripsHandlers, busy: Boolean, modifier: Modifier) {
  val resources = LocalContext.current.resources
  val days = if (trip.days.from == trip.days.to) AlbumText.day(trip.days.from) else resources.getString(R.string.trips_days, AlbumText.shortDay(trip.days.from), AlbumText.shortDay(trip.days.to))
  val kind = stringResource(if (trip.kind == TripKind.TRIP) R.string.trips_kind_trip else R.string.trips_kind_event)
  val spoken = listOf(trip.label, kind, resources.getQuantityString(R.plurals.trips_count, trip.count, GalleryNumbers.format(trip.count)), days).joinToString(", ")
  val dismiss = stringResource(R.string.trips_dismiss_for, trip.label)
  val shape = ContinuousCornerShape(FluidRadius.Card)
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap)) {
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(TRIP_CARD_ASPECT)) {
      // La forma comune delle schede di copertina (core-designsystem), come «Tutti i ricordi»: la descrizione dice tutto, i testi non si rileggono.
      GalleryCoverCard(
        modifier = Modifier.matchParentSize().semantics(mergeDescendants = true) { contentDescription = spoken }.clip(shape),
        title = trip.label,
        caption = kind,
        labelsSpoken = false,
      ) {
        AlbumCoverThumb(
          media = trip.cover,
          thumbnails = thumbnails,
          modifier = Modifier.matchParentSize(),
          shape = shape,
          glyph = if (trip.kind == TripKind.TRIP) Icons.Rounded.Flight else Icons.Rounded.Event,
        )
      }
      // La ✕: bersaglio di 48 dp, il segno su un tondo velato (si legge su qualunque foto).
      Box(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .size(AlbumDimens.Touch)
          .semantics { contentDescription = dismiss }
          .fluidPressable(onClick = { handlers.onDismiss(trip.id) }, enabled = !busy, role = Role.Button, focusShape = FluidCapsuleShape),
        contentAlignment = Alignment.Center,
      ) {
        Box(
          modifier = Modifier.size(AlbumDimens.TripDismissDisc).background(GalleryOnPhoto.scrim(), FluidCapsuleShape),
          contentAlignment = Alignment.Center,
        ) {
          Icon(imageVector = Icons.Rounded.Close, contentDescription = null, tint = GalleryOnPhoto.Content, modifier = Modifier.size(AlbumDimens.TileGlyph))
        }
      }
    }
    FluidButton(
      text = stringResource(if (busy) R.string.trips_creating else R.string.trips_create),
      onClick = { handlers.onCreate(trip.id) },
      enabled = !busy,
      loading = busy,
      style = FluidButtonStyle.Tinted,
      size = FluidButtonSize.Medium,
      fillWidth = true,
      leading = { Icon(imageVector = Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(AlbumDimens.TileGlyph)) },
      modifier = Modifier.heightIn(min = AlbumDimens.Touch),
    )
  }
}

/** Quante colonne al piu' (il tablet largo), come «Tutti i ricordi». */
private const val MAX_COLUMNS = 6

/** La forma della scheda: 3:4, come le schede dei ricordi. */
private const val TRIP_CARD_ASPECT = 3f / 4f
