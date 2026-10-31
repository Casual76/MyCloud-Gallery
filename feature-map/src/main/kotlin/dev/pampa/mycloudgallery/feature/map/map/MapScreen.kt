package dev.pampa.mycloudgallery.feature.map.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.feature.map.R
import dev.pampa.mycloudgallery.feature.map.contract.MapNotice
import dev.pampa.mycloudgallery.feature.map.contract.MapScreenState
import dev.pampa.mycloudgallery.feature.map.contract.MapThumbnails
import dev.pampa.mycloudgallery.feature.map.ui.MapChip
import dev.pampa.mycloudgallery.feature.map.ui.MapControlButton
import dev.pampa.mycloudgallery.feature.map.ui.MapCredit
import dev.pampa.mycloudgallery.feature.map.ui.MapDimens
import dev.pampa.mycloudgallery.feature.map.ui.MapText

// LA PAGINA DELLA MAPPA (Fase 9, P4; docs/fase9-progetto.md §1.5 `MapScreenState`, §2.10 "La schermata", D24, D28, D29, L430; "Avvio e pagine istantanei"). La carta (MapLibre o la
// finta degli screenshot) a tutto schermo, le bolle sopra, in alto "indietro", il titolo e le Impostazioni della mappa, sotto la riga discreta dell'avviso (mai un dialogo) e in basso il
// credito della carta (l'attribuzione di MapLibre, che apre un collegamento, e' spenta). **Primo fotogramma subito**: finche' la camera non e' decisa la carta e' un fondo con i controlli;
// la mappa vera nasce pigra dopo, e l'indice delle bolle si costruisce fuori dal thread principale. Controlli e righe su superfici piene (nessun vetro sopra la carta), da 48 dp, mai uno
// sopra l'altro; a testo grande le righe vanno a capo.

/** Cosa la pagina chiede fuori da se': la navigazione e il tocco su una bolla (passa dal controllore). */
internal interface MapHandlers {
  fun onBack()

  fun onBubble(bubble: PlacedBubble)

  /** L'avviso discreto: apre Impostazioni -> Mappa. */
  fun onNotice()

  fun onSettings()

  /** "Riprova" dopo "Non riesco a leggere le posizioni delle foto" (MA-1). */
  fun onRetry()
}

/** Nessuna azione: per le anteprime e gli screenshot. */
internal object NoMapHandlers : MapHandlers {
  override fun onBack() = Unit

  override fun onBubble(bubble: PlacedBubble) = Unit

  override fun onNotice() = Unit

  override fun onSettings() = Unit

  override fun onRetry() = Unit
}

/** La pagina per lo stato dato: nessun effetto, la usano la rotta, i test e gli screenshot. */
@Composable
internal fun MapScreen(
  ui: MapUi,
  surface: MapSurface,
  content: MapSurfaceContent,
  thumbnails: MapThumbnails,
  snapshot: TimelineSnapshot?,
  handlers: MapHandlers,
  modifier: Modifier = Modifier,
  probe: LayoutProbe? = null,
) {
  val scheme = MaterialTheme.colorScheme
  val initial = ui.initial
  val request = remember(ui.style, initial, ui.selection, scheme.primary, scheme.surface) {
    initial?.let { MapSurfaceRequest(ui.style, it, ui.selection, scheme.primary.toArgb(), scheme.surface.toArgb()) }
  }
  Box(modifier = modifier.fillMaxSize().background(scheme.surfaceContainer)) {
    // La carta nasce solo quando la camera e' decisa (ultima posizione locale, o tutte le foto con lo zoom <= 5): mai una carta in un posto sbagliato per un attimo.
    if (request != null) content.Content(surface, request, Modifier.fillMaxSize())
    if (request != null) BubbleLayer(ui.bubbles, surface, thumbnails, snapshot, handlers::onBubble, Modifier.fillMaxSize(), probe)
    Column(
      modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(MapDimens.Inset).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(MapDimens.TightGap),
    ) {
      TopRow(handlers)
      NoticeRow(ui.screen, handlers)
      ui.download?.let { percent -> MapChip(stringResource(R.string.mappa_download, percent), modifier = Modifier.widthIn(max = MapDimens.PageWidth), icon = Icons.Rounded.CloudDownload) }
    }
    Column(
      modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(MapDimens.Inset).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(MapDimens.TightGap),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      StateRow(ui.screen, handlers)
      MapCredit(ui.style.attribution, Modifier.align(Alignment.Start))
    }
  }
}

@Composable
private fun TopRow(handlers: MapHandlers) {
  Row(horizontalArrangement = Arrangement.spacedBy(MapDimens.TightGap), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    MapControlButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.mappa_back), handlers::onBack)
    MapChip(stringResource(R.string.mappa_title), modifier = Modifier.weight(1f), maxLines = 1)
    MapControlButton(Icons.Rounded.Settings, stringResource(R.string.mappa_settings), handlers::onSettings)
  }
}

/** La riga discreta sotto la barra (§2.10 "Avvisi"): carta semplice, solo sul Wi-Fi, nessuna rete. Una regione viva: TalkBack la legge quando compare. */
@Composable
private fun NoticeRow(screen: MapScreenState, handlers: MapHandlers) {
  val notice = (screen as? MapScreenState.Ready)?.notice ?: return
  val (text, icon) = when (notice) {
    MapNotice.BASE_ONLY -> stringResource(R.string.mappa_notice_base) to Icons.Rounded.Layers
    MapNotice.WIFI_ONLY -> stringResource(R.string.mappa_notice_wifi) to Icons.Rounded.Wifi
    MapNotice.OFFLINE -> stringResource(R.string.mappa_notice_offline) to Icons.Rounded.WifiOff
  }
  // Solo "Carta semplice" porta da qualche parte (Impostazioni -> Mappa): le altre dicono come stanno le cose.
  val onClick = if (notice == MapNotice.BASE_ONLY || notice == MapNotice.WIFI_ONLY) handlers::onNotice else null
  MapChip(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.widthIn(max = MapDimens.PageWidth), icon = icon, onClick = onClick)
}

/** In basso, sopra il credito: lo stato dell'indice. Le bolle compaiono da sole quando l'indice e' pronto (la pagina non aspetta). */
@Composable
private fun StateRow(screen: MapScreenState, handlers: MapHandlers) {
  val resources = LocalContext.current.resources
  when (screen) {
    MapScreenState.Loading -> MapChip(stringResource(R.string.mappa_loading), icon = Icons.Rounded.PhotoLibrary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    is MapScreenState.Empty -> Column(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = MapDimens.SheetWidth)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
        .padding(MapDimens.Inset)
        .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
      FluidEmptyState(
        title = stringResource(R.string.mappa_empty_title),
        detail = if (screen.withoutPosition > 0) MapText.withoutPosition(resources, screen.withoutPosition) else stringResource(R.string.mappa_empty_detail),
      )
    }
    // MA-1: un errore di lettura non e' "nessuna foto con la posizione": lo dice e offre di riprovare (le foto non sono perse).
    MapScreenState.Failed -> Column(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = MapDimens.SheetWidth)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
        .padding(MapDimens.Inset)
        .semantics { liveRegion = LiveRegionMode.Polite },
      verticalArrangement = Arrangement.spacedBy(MapDimens.BlockGap),
    ) {
      FluidEmptyState(title = stringResource(R.string.mappa_failed_title), detail = stringResource(R.string.mappa_failed_detail))
      FluidButton(
        text = stringResource(R.string.mappa_retry),
        onClick = handlers::onRetry,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    }
    is MapScreenState.Ready -> Unit
  }
}

/** Se il tema e' scuro (per scegliere lo stile): dalla luminanza dello sfondo del tema dell'app. */
@Composable
internal fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < DARK_BELOW

private const val DARK_BELOW = 0.5f
