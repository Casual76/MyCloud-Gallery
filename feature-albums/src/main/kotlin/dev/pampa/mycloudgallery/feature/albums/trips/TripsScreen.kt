package dev.pampa.mycloudgallery.feature.albums.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripProposal
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.contract.TripsState
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumCoverThumb
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumDimens
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumText

// LE PROPOSTE DI VIAGGIO (Fase 7, P10; docs/fase7-progetto.md §1.5 `TripsState`, D19, D20, E-AL16). Una scheda per proposta: la copertina in cima (larga, mai con
// testo sopra), sotto il nome ("Oslo, marzo 2026"), quante foto e i giorni, e due bottoni a tutta larghezza uno sotto l'altro, "Crea album" e
// "Non mi interessa": a 1,3x il testo non si stringe e i due bersagli non si toccano. Senza una casa stimabile (nessuna foto con la posizione) la
// pagina lo spiega una volta e propone gli eventi. Nessuna coordinata: il contratto non le porta.

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
  listState: LazyListState = rememberLazyListState(),
) {
  val shown = when (state) {
    is TripsState.Proposals -> state.proposals
    is TripsState.NoGps -> state.events
    TripsState.Empty -> emptyList()
  }
  val requests = remember(shown) { shown.mapNotNull { thumbnails.refs.request(it.cover) } }
  LaunchedEffect(requests, thumbnails) { thumbnails.loader.window(requests, emptyList()) }
  FluidScreen(
    title = stringResource(R.string.trips_title),
    subtitle = stringResource(R.string.trips_subtitle),
    modifier = modifier,
    onBack = handlers::onBack,
    listState = listState,
    contentMaxWidth = AlbumDimens.SheetWidth,
  ) {
    when (state) {
      TripsState.Empty -> item(key = "empty") { FluidEmptyState(title = stringResource(R.string.trips_empty_title), detail = stringResource(R.string.trips_empty_detail)) }
      is TripsState.NoGps -> {
        item(key = "no-gps") {
          FluidInlineMessage(message = stringResource(R.string.trips_no_gps_detail), title = stringResource(R.string.trips_no_gps_title), tone = FluidTone.Info)
        }
        item(key = "events-header") { FluidSectionHeader(title = stringResource(R.string.trips_events_header)) }
        if (state.events.isEmpty()) {
          item(key = "events-empty") { Text(stringResource(R.string.trips_events_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
          proposalCards(state.events, thumbnails, handlers, creating)
        }
      }
      is TripsState.Proposals -> {
        proposalCards(state.proposals, thumbnails, handlers, creating)
        if (state.hasOlder) {
          item(key = "older") {
            FluidButton(text = stringResource(R.string.trips_show_older), onClick = handlers::onShowOlder, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
          }
        }
      }
    }
  }
}

private fun LazyListScope.proposalCards(proposals: List<TripProposal>, thumbnails: AlbumThumbnails, handlers: TripsHandlers, creating: TripId?) {
  proposals.forEach { trip ->
    item(key = trip.id.value) { ProposalCard(trip, thumbnails, handlers, creating == trip.id) }
  }
}

@Composable
private fun ProposalCard(trip: TripProposal, thumbnails: AlbumThumbnails, handlers: TripsHandlers, busy: Boolean) {
  val resources = LocalContext.current.resources
  val days = if (trip.days.from == trip.days.to) AlbumText.day(trip.days.from) else resources.getString(R.string.trips_days, AlbumText.shortDay(trip.days.from), AlbumText.shortDay(trip.days.to))
  FluidCard {
    AlbumCoverThumb(
      media = trip.cover, thumbnails = thumbnails,
      modifier = Modifier.fillMaxWidth().height(AlbumDimens.TripCoverHeight),
      shape = ContinuousCornerShape(FluidRadius.Control),
      glyph = if (trip.kind == TripKind.TRIP) Icons.Rounded.Flight else Icons.Rounded.Event,
    )
    Column(verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap)) {
      Text(text = trip.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
      Text(
        text = resources.getQuantityString(R.plurals.trips_count, trip.count, GalleryNumbers.format(trip.count)) + " · " + days,
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      FluidStatusBadge(label = stringResource(if (trip.kind == TripKind.TRIP) R.string.trips_kind_trip else R.string.trips_kind_event), tone = FluidTone.Info)
    }
    FluidButton(
      text = stringResource(if (busy) R.string.trips_creating else R.string.trips_create), onClick = { handlers.onCreate(trip.id) },
      enabled = !busy, loading = busy, size = FluidButtonSize.Large, fillWidth = true,
    )
    FluidButton(text = stringResource(R.string.trips_dismiss), onClick = { handlers.onDismiss(trip.id) }, style = FluidButtonStyle.Plain, size = FluidButtonSize.Large, enabled = !busy, fillWidth = true)
  }
}
