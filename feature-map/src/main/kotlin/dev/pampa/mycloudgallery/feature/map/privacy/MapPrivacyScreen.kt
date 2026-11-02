package dev.pampa.mycloudgallery.feature.map.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.feature.map.R
import dev.pampa.mycloudgallery.feature.map.contract.MapPrivacyState
import dev.pampa.mycloudgallery.feature.map.ui.MapDimens

// IMPOSTAZIONI -> MAPPA (Fase 9, P4; docs/fase9-progetto.md §1.5 `MapPrivacyState`, §2.8, D24, Q1 (c)). Righe raggruppate di Fluid (regola 2 del design system: il tono sulla piastrella, mai sulla riga): l'interruttore
// della mappa dettagliata con la nota breve sotto (cosa esce, a chi, come evitarlo), "Solo sul Wi-Fi" con l'avviso sul consumo di dati quando e' spento, "Zone senza Internet". Ogni controllo e' da almeno 48 dp e
// nessuno ne copre un altro. Nessun dialogo: spegnere e riaccendere sono un tocco.

/** Cosa la pagina chiede fuori da se': la navigazione e i due interruttori (passano dal controllore). */
internal interface MapPrivacyHandlers {
  fun onBack()

  fun onOnline(on: Boolean)

  fun onWifiOnly(on: Boolean)

  fun onOpenRegions()
}

internal object NoMapPrivacyHandlers : MapPrivacyHandlers {
  override fun onBack() = Unit

  override fun onOnline(on: Boolean) = Unit

  override fun onWifiOnly(on: Boolean) = Unit

  override fun onOpenRegions() = Unit
}

/** Una riga con un interruttore: tutta la riga si tocca; TalkBack legge "acceso" o "spento". */
@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  FluidListRow(
    title = title,
    subtitle = subtitle,
    modifier = Modifier.semantics {
      role = Role.Switch
      toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
    },
    badge = { FluidSwitch(checked = checked, onCheckedChange = null) },
    onClick = { onChange(!checked) },
    disclosure = false,
  )
}

/** La pagina per lo stato dato: nessun effetto, la usano la rotta, i test e gli screenshot. */
@Composable
internal fun MapPrivacyScreen(state: MapPrivacyState, handlers: MapPrivacyHandlers, modifier: Modifier = Modifier) {
  FluidScreen(title = stringResource(R.string.mappa_settings_title), modifier = modifier, onBack = handlers::onBack, contentMaxWidth = MapDimens.SheetWidth) {
    when (state) {
      MapPrivacyState.Unavailable -> item(key = "unavailable") {
        Column(verticalArrangement = Arrangement.spacedBy(MapDimens.BlockGap)) {
          FluidEmptyState(title = stringResource(R.string.mappa_settings_unavailable_title), detail = stringResource(R.string.mappa_settings_unavailable_detail))
        }
      }
      is MapPrivacyState.Shown -> shown(state, handlers)
    }
  }
}

private fun LazyListScope.shown(state: MapPrivacyState.Shown, handlers: MapPrivacyHandlers) {
  val on = state.online is MapOnline.On
  item(key = "header-online") { GallerySectionHeader(title = stringResource(R.string.mappa_settings_online_header)) }
  item(key = "online") {
    FluidListGroup {
      SwitchRow(
        title = stringResource(R.string.mappa_settings_online),
        subtitle = stringResource(if (on) R.string.mappa_settings_online_on else R.string.mappa_settings_online_off),
        checked = on,
        onChange = handlers::onOnline,
      )
      if (on) {
        FluidListDivider()
        SwitchRow(
          title = stringResource(R.string.mappa_settings_wifi),
          subtitle = stringResource(if (state.wifiOnly) R.string.mappa_settings_wifi_on else R.string.mappa_settings_wifi_off),
          checked = state.wifiOnly,
          onChange = handlers::onWifiOnly,
        )
      }
    }
  }
  // R2 (31/10): il file delle impostazioni si era rovinato e la scelta si e' persa: la mappa dettagliata e' spenta (fallisce chiusa) finche' l'utente non la riaccende. Una riga, sotto l'interruttore.
  if (state.damaged) item(key = "damaged") { FluidSectionFootnote(text = stringResource(R.string.mappa_settings_damaged)) }
  // La nota breve (Q1 (c), `MapPrivacyNote`): cosa esce, a chi (anche attorno a una foto aperta sulla mappa: R1) e che vale solo su questo dispositivo.
  item(key = "note") { FluidSectionFootnote(text = stringResource(R.string.mappa_settings_note)) }
  item(key = "header-zones") { GallerySectionHeader(title = stringResource(R.string.mappa_settings_zones_header)) }
  item(key = "zones") {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.mappa_settings_zones),
        subtitle = zonesDetail(state.regions),
        tone = FluidTone.Info,
        leading = { Icon(imageVector = Icons.Rounded.CloudDownload, contentDescription = null) },
        onClick = handlers::onOpenRegions,
      )
    }
  }
  item(key = "zones-note") { FluidSectionFootnote(text = stringResource(R.string.mappa_settings_zones_note)) }
}

@Composable
private fun zonesDetail(regions: Int): String {
  val resources = LocalContext.current.resources
  return if (regions == 0) stringResource(R.string.mappa_settings_zones_none) else resources.getQuantityString(R.plurals.mappa_settings_zones_count, regions, regions)
}
