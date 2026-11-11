package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.places.contract.PlacesAttribution
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.place.ViewerMapPlace
import dev.pampa.mycloudgallery.feature.viewer.place.ViewerPlaceMap

// LA MINI-MAPPA DELLE INFORMAZIONI (CU-6: l'unico ingresso alla mappa; prima c'era anche la riga "Mostra sulla mappa") (Fase 9, P4; docs/fase9-progetto.md §1.5 `MiniMapState`, §2.10 "Mini-mappa del viewer", D29, D30, E-MAP16, E-MAP19, E-H9). Sotto il luogo del media, **solo se il media ha una
// posizione**: la mini-mappa (un'immagine statica di 320 x 160 dp con la sola carta di base e un segno sul punto, disegnata dalla porta [ViewerPlaceMap]: **mai in rete**, con il credito della carta sotto) e la riga "Mostra sulla mappa", che
// apre la mappa vera dell'app centrata sul media (con le sue regole: a mappa dettagliata accesa chiede le tessere di quella zona, azione dell'utente, E-MAP19). "Apri in Mappe" (un'altra app) resta com'e'. Senza la porta
// ([dev.pampa.mycloudgallery.feature.viewer.ViewerServices.placeMap] null) la riga non c'e': il pannello e' quello di prima. **Mai per un media aperto dai Nascosti** (`ViewerRequest.secure`): in quel caso la porta **non
// viene nemmeno chiamata**, e la riga non c'e'. Solo componenti dell'engine e del design system.

/** La sezione della mappa delle informazioni: quello che `ViewerOverlay` monta dentro il pannello. */
internal class PlaceMapPanel(val sections: List<InfoSectionModel>) {
  companion object {
    /** Nessuna sezione: il viewer senza la mappa, un media senza posizione, o un media dei Nascosti. */
    val NONE: PlaceMapPanel = PlaceMapPanel(emptyList())
  }
}

/**
 * La sezione della mappa per il media [media] mentre il pannello e' aperto ([active]). [secure] (media aperto dai Nascosti): niente, e la porta non si chiama. Mentre la porta legge la posizione o disegna la carta non c'e' niente
 * (la sezione compare quando c'e' la posizione, senza saltare); senza posizione non c'e' mai.
 *
 * CU-6: **la mini-mappa e' l'unico ingresso alla mappa**: toccarla apre la mappa dell'app sul media; [onOpenMaps] (un'altra app, "Apri in Mappe",
 * con la sua conferma) e' un tasto piccolo dentro la carta, in alto a destra. Niente pillole sotto.
 */
@Composable
internal fun rememberPlaceMapPanel(
  source: ViewerPlaceMap?,
  media: MediaId,
  active: Boolean,
  secure: Boolean,
  onOpenMaps: (() -> Unit)? = null,
): PlaceMapPanel {
  if (source == null || !active || secure) return PlaceMapPanel.NONE
  val density = LocalDensity.current
  val widthPx = with(density) { MiniMapWidth.roundToPx() }
  val place by produceState<ViewerMapPlace>(initialValue = ViewerMapPlace.Loading, source, media, widthPx) {
    source.place(media, widthPx).collect { value = it }
  }
  val located = place as? ViewerMapPlace.Located ?: return PlaceMapPanel.NONE
  val latestOpenMaps by rememberUpdatedState(onOpenMaps)
  val external = onOpenMaps != null
  val section = remember(located, media, source, external) {
    InfoSectionModel(header = null, rows = emptyList(), content = {
      PlaceMapCard(located, onShow = { source.show(media) }, onOpenMaps = if (external) ({ latestOpenMaps?.invoke() }) else null)
    })
  }
  return PlaceMapPanel(listOf(section))
}

/**
 * La carta: la mini-mappa (o, finche' la carta di base non c'e', un riquadro con il segno della mappa e "Mostra sulla mappa": lo stesso posto e lo
 * stesso tocco, niente salti quando arriva), il credito sotto, e in alto a destra il tasto per l'altra app.
 */
@Composable
private fun PlaceMapCard(place: ViewerMapPlace.Located, onShow: () -> Unit, onOpenMaps: (() -> Unit)?) {
  val resources = LocalContext.current.resources
  val scheme = MaterialTheme.colorScheme
  val bitmap = place.miniMap
  val shape = ContinuousCornerShape(FluidRadius.Card)
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CreditGap)) {
    Box(
      modifier = Modifier
        .widthIn(max = MiniMapWidth)
        .fillMaxWidth()
        .aspectRatio(MiniMapAspect),
    ) {
      if (bitmap != null) {
        Image(
          bitmap = remember(bitmap) { bitmap.asImageBitmap() },
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .semantics { contentDescription = resources.getString(R.string.viewer_place_map_image) }
            .fluidPressable(onClick = onShow, role = Role.Button),
        )
      } else {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(scheme.surfaceContainerHigh)
            .fluidPressable(onClick = onShow, role = Role.Button),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Icon(imageVector = Icons.Rounded.Map, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(PinSize))
          Text(text = resources.getString(R.string.viewer_place_show_map), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
        }
      }
      if (onOpenMaps != null) {
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .size(GalleryDimens.MinTouchTarget)
            .fluidPressable(onClick = onOpenMaps, role = Role.Button)
            .semantics { contentDescription = resources.getString(R.string.viewer_place_open_maps) },
          contentAlignment = Alignment.Center,
        ) {
          Box(
            modifier = Modifier.size(OpenMapsDisc).clip(FluidCapsuleShape).background(scheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
          ) {
            Icon(imageVector = Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(OpenMapsGlyph))
          }
        }
      }
    }
    if (bitmap != null) {
      Text(text = PlacesAttribution.MAP_BASE_SHORT, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
  }
}

/** La mini-mappa e' di 320 x 160 dp (§2.10): la larghezza che si chiede alla porta, in pixel. */
private val MiniMapWidth = 320.dp
private const val MiniMapAspect = 2f
private val CreditGap = 8.dp
private val PinSize = 28.dp
private val OpenMapsDisc = 32.dp
private val OpenMapsGlyph = 18.dp
