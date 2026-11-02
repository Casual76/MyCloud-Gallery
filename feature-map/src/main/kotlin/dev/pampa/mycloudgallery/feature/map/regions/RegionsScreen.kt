package dev.pampa.mycloudgallery.feature.map.regions

import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.LocationSearching
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.map.contract.PlaceHit
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.map.contract.RegionStatus
import dev.pampa.mycloudgallery.feature.map.R
import dev.pampa.mycloudgallery.feature.map.ui.MapDimens

// ZONE SENZA INTERNET: LA PAGINA (Fase 9, P5; docs/fase9-progetto.md §2.9, D26; decisione dell'utente del 31/10). Righe raggruppate di Fluid (come Impostazioni -> Mappa, regola 2 del design system: il tono
// sulla piastrella, mai sulla riga): le zone scaricate (con l'avanzamento), le 20 regioni italiane, i paesi vicini e "La zona attorno a un luogo" (ricerca per nome, raggio di 15, 30 o 60 km). Ogni riga
// dice la dimensione stimata e il livello di dettaglio, o che e' troppo grande per i limiti. Ogni controllo e' da almeno 48 dp. Nessuno scaricamento parte da qui senza il foglio di conferma
// (`RegionDownloadSheet.kt`). Nessun dialogo: i fogli sono quelli della galleria.

internal interface RegionsHandlers : RegionSheetHandlers {
  fun onBack()

  fun onCatalog(spec: RegionSpec)

  fun onDownloaded(id: RegionId)

  fun onOpenAround()

  fun onCloseAround()

  fun onQuery(text: String)

  fun onPick(hit: PlaceHit)

  fun onChangePlace()

  fun onRadius(offer: RadiusOffer)

  fun onDismissMessage()
}

internal object NoRegionsHandlers : RegionsHandlers {
  override fun onBack() = Unit
  override fun onCatalog(spec: RegionSpec) = Unit
  override fun onDownloaded(id: RegionId) = Unit
  override fun onOpenAround() = Unit
  override fun onCloseAround() = Unit
  override fun onQuery(text: String) = Unit
  override fun onPick(hit: PlaceHit) = Unit
  override fun onChangePlace() = Unit
  override fun onRadius(offer: RadiusOffer) = Unit
  override fun onDismissMessage() = Unit
  override fun onConfirm() = Unit
  override fun onDismiss() = Unit
  override fun onResume() = Unit
  override fun onUpdate() = Unit
  override fun onAskDelete() = Unit
  override fun onDelete() = Unit
}

/** La pagina per lo stato dato: nessun effetto, la usano la rotta, i test e gli screenshot. */
@Composable
internal fun RegionsScreen(ui: RegionsUi, handlers: RegionsHandlers, modifier: Modifier = Modifier) {
  val around = ui.around
  Box(modifier = modifier.fillMaxSize()) {
    if (around != null) {
      FluidScreen(title = stringResource(R.string.regions_around_title), onBack = handlers::onCloseAround, contentMaxWidth = MapDimens.SheetWidth) { aroundPage(around, handlers) }
    } else {
      FluidScreen(title = stringResource(R.string.regions_title), onBack = handlers::onBack, contentMaxWidth = MapDimens.SheetWidth) { catalogPage(ui, handlers) }
    }
    RegionSheets(ui.sheet, handlers)
  }
}

// --- il catalogo ----------------------------------------------------------------------------------------------------------------

private fun LazyListScope.catalogPage(ui: RegionsUi, handlers: RegionsHandlers) {
  ui.message?.let { message -> item(key = "message") { MessageRow(message, handlers) } }
  item(key = "intro") { FluidSectionFootnote(text = stringResource(R.string.regions_intro)) }
  if (ui.downloaded.isNotEmpty()) {
    item(key = "header-downloaded") { GallerySectionHeader(title = stringResource(R.string.regions_header_downloaded)) }
    item(key = "downloaded") {
      FluidListGroup {
        ui.downloaded.forEachIndexed { index, state ->
          if (index > 0) FluidListDivider()
          DownloadedRow(state, handlers)
        }
      }
    }
    val running = ui.downloaded.firstOrNull { it.status == RegionStatus.DOWNLOADING }
    if (running != null) item(key = "progress") { DownloadProgress(running) }
    item(key = "total") { Total(ui.usedBytes) }
  }
  item(key = "header-around") { GallerySectionHeader(title = stringResource(R.string.regions_header_around)) }
  item(key = "around") {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.regions_around_row),
        subtitle = stringResource(R.string.regions_around_detail),
        tone = FluidTone.Info,
        leading = { Icon(imageVector = Icons.Rounded.LocationSearching, contentDescription = null) },
        onClick = handlers::onOpenAround,
      )
    }
  }
  item(key = "header-italian") { GallerySectionHeader(title = stringResource(R.string.regions_header_italian)) }
  item(key = "italian") { OfferGroup(ui.italian, handlers) }
  item(key = "header-countries") { GallerySectionHeader(title = stringResource(R.string.regions_header_countries)) }
  item(key = "countries") { OfferGroup(ui.countries, handlers) }
  item(key = "bottom") { Box(Modifier.padding(bottom = MapDimens.ListBottom)) }
}

@Composable
private fun MessageRow(message: RegionMessage, handlers: RegionsHandlers) {
  val text = when (message) {
    RegionMessage.TooLarge -> stringResource(R.string.regions_msg_too_large)
    RegionMessage.NeedsWifi -> stringResource(R.string.regions_msg_needs_wifi)
    RegionMessage.Busy -> stringResource(R.string.regions_msg_busy)
    RegionMessage.Expired -> stringResource(R.string.regions_msg_expired)
    is RegionMessage.NoSpace -> stringResource(R.string.regions_msg_no_space, RegionText.size(LocalContext.current.resources, message.neededBytes))
  }
  FluidListGroup(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
    FluidListRow(
      title = text,
      subtitle = stringResource(R.string.regions_msg_dismiss),
      tone = FluidTone.Warning,
      leading = { Icon(imageVector = Icons.Rounded.Pause, contentDescription = null) },
      onClick = handlers::onDismissMessage,
      disclosure = false,
    )
  }
}

@Composable
private fun Total(used: Long) {
  FluidSectionFootnote(text = stringResource(R.string.regions_total, RegionText.size(LocalContext.current.resources, used)))
}

@Composable
private fun DownloadProgress(state: RegionState) {
  val percent = state.percent
  val label = stringResource(R.string.regions_row_downloading, percent)
  FluidProgressBar(
    progress = { percent / 100f },
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = MapDimens.TightGap)
      .semantics { contentDescription = label },
  )
}

@Composable
private fun DownloadedRow(state: RegionState, handlers: RegionsHandlers) {
  val resources = LocalContext.current.resources
  val (subtitle, icon) = downloadedText(resources, state)
  FluidListRow(
    title = RegionText.title(resources, state.spec),
    subtitle = subtitle,
    tone = when (state.status) {
      RegionStatus.READY -> FluidTone.Success
      RegionStatus.NEEDS_UPDATE -> FluidTone.Warning
      RegionStatus.DOWNLOADING -> FluidTone.Info
      RegionStatus.PARTIAL -> if (state.stop?.terminal == true) FluidTone.Danger else FluidTone.Warning
    },
    leading = { Icon(imageVector = icon, contentDescription = null) },
    onClick = { handlers.onDownloaded(state.spec.id) },
  )
}

private fun downloadedText(resources: Resources, state: RegionState) = when (state.status) {
  RegionStatus.READY ->
    resources.getString(R.string.regions_row_ready, RegionText.size(resources, state.bytes), RegionText.detail(resources, state.maxZoom)) to Icons.Rounded.CheckCircle
  RegionStatus.NEEDS_UPDATE -> resources.getString(R.string.regions_row_needs_update, RegionText.size(resources, state.bytes)) to Icons.Rounded.Update
  RegionStatus.DOWNLOADING -> resources.getString(R.string.regions_row_downloading, state.percent) to Icons.Rounded.CloudDownload
  RegionStatus.PARTIAL -> resources.getString(R.string.regions_row_partial, state.percent, RegionText.stop(resources, state.stop)) to Icons.Rounded.Pause
}

@Composable
private fun OfferGroup(offers: List<RegionOffer>, handlers: RegionsHandlers) {
  val resources = LocalContext.current.resources
  FluidListGroup {
    offers.forEachIndexed { index, offer ->
      if (index > 0) FluidListDivider()
      FluidListRow(
        title = RegionText.title(resources, offer.spec),
        subtitle = offerSubtitle(resources, offer),
        tone = if (offer.present != null) FluidTone.Success else FluidTone.Neutral,
        leading = { Icon(imageVector = if (offer.present != null) Icons.Rounded.CheckCircle else Icons.Rounded.CloudDownload, contentDescription = null) },
        onClick = { handlers.onCatalog(offer.spec) },
      )
    }
  }
}

private fun offerSubtitle(resources: Resources, offer: RegionOffer): String {
  val estimate = offer.estimate
  return when {
    offer.present != null -> resources.getString(R.string.regions_row_present)
    estimate == null -> ""
    !estimate.offered -> resources.getString(R.string.regions_row_too_large)
    else -> resources.getString(R.string.regions_row_offer, RegionText.approx(resources, estimate.bytes), RegionText.detail(resources, estimate.maxZoom))
  }
}

// --- la zona attorno a un luogo ---------------------------------------------------------------------------------------------------

private fun LazyListScope.aroundPage(around: AroundState, handlers: RegionsHandlers) {
  item(key = "message") { FluidSectionFootnote(text = stringResource(R.string.regions_around_note)) }
  if (!around.available) {
    item(key = "unavailable") { FluidEmptyState(title = stringResource(R.string.regions_around_title), detail = stringResource(R.string.regions_around_unavailable)) }
    return
  }
  if (around.picked == null) {
    item(key = "field") {
      FluidTextField(value = around.query, onValueChange = handlers::onQuery, placeholder = stringResource(R.string.regions_around_hint), modifier = Modifier.fillMaxWidth())
    }
    if (around.hits.isNotEmpty()) {
      item(key = "hits") {
        FluidListGroup {
          around.hits.forEachIndexed { index, hit ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = hit.name,
              subtitle = hit.detail.orEmpty(),
              tone = FluidTone.Info,
              leading = { Icon(imageVector = Icons.Rounded.LocationSearching, contentDescription = null) },
              onClick = { handlers.onPick(hit) },
            )
          }
        }
      }
    } else if (around.searched) {
      item(key = "none") { FluidSectionFootnote(text = stringResource(R.string.regions_around_none)) }
    }
  } else {
    val picked = around.picked
    item(key = "picked") {
      FluidListGroup {
        FluidListRow(
          title = picked.name,
          subtitle = stringResource(R.string.regions_around_change),
          tone = FluidTone.Info,
          leading = { Icon(imageVector = Icons.Rounded.LocationSearching, contentDescription = null) },
          onClick = handlers::onChangePlace,
        )
      }
    }
    item(key = "header-radius") { GallerySectionHeader(title = stringResource(R.string.regions_around_radius_header, picked.name)) }
    item(key = "radii") {
      val resources = LocalContext.current.resources
      FluidListGroup {
        around.radii.forEachIndexed { index, offer ->
          if (index > 0) FluidListDivider()
          val estimate = offer.estimate
          FluidListRow(
            title = stringResource(R.string.regions_around_radius, offer.radius.km),
            subtitle = when {
              offer.present != null -> resources.getString(R.string.regions_row_present)
              estimate == null -> ""
              !estimate.offered -> resources.getString(R.string.regions_row_too_large)
              else -> resources.getString(R.string.regions_row_offer, RegionText.approx(resources, estimate.bytes), RegionText.detail(resources, estimate.maxZoom))
            },
            tone = if (offer.present != null) FluidTone.Success else FluidTone.Neutral,
            leading = { Icon(imageVector = Icons.Rounded.CloudDownload, contentDescription = null) },
            onClick = { handlers.onRadius(offer) },
          )
        }
      }
    }
  }
}
