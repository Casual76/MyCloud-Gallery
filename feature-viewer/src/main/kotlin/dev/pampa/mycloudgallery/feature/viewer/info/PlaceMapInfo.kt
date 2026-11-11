package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.places.contract.PlacesAttribution
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.place.ViewerMapPlace
import dev.pampa.mycloudgallery.feature.viewer.place.ViewerPlaceMap

// LA RIGA "MOSTRA SULLA MAPPA" E LA MINI-MAPPA DELLE INFORMAZIONI (Fase 9, P4; docs/fase9-progetto.md §1.5 `MiniMapState`, §2.10 "Mini-mappa del viewer", D29, D30, E-MAP16, E-MAP19, E-H9). Sotto il luogo del media, **solo se il media ha una
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
 * (la riga compare quando c'e' la posizione, senza saltare); senza posizione non c'e' mai.
 */
@Composable
internal fun rememberPlaceMapPanel(source: ViewerPlaceMap?, media: MediaId, active: Boolean, secure: Boolean): PlaceMapPanel {
  if (source == null || !active || secure) return PlaceMapPanel.NONE
  val density = LocalDensity.current
  val widthPx = with(density) { MiniMapWidth.roundToPx() }
  val place by produceState<ViewerMapPlace>(initialValue = ViewerMapPlace.Loading, source, media, widthPx) {
    source.place(media, widthPx).collect { value = it }
  }
  val located = place as? ViewerMapPlace.Located ?: return PlaceMapPanel.NONE
  // H2: senza intestazione e senza righe a elenco: la carta parla da se', e sotto c'e' una pillola per aprirla.
  val section = remember(located, media, source) {
    InfoSectionModel(header = null, rows = emptyList(), content = { PlaceMapSection(located, onShow = { source.show(media) }) })
  }
  return PlaceMapPanel(listOf(section))
}

@Composable
private fun PlaceMapSection(place: ViewerMapPlace.Located, onShow: () -> Unit) {
  val resources = LocalContext.current.resources
  val bitmap = place.miniMap
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CreditGap)) {
    if (bitmap != null) {
      Image(
        bitmap = remember(bitmap) { bitmap.asImageBitmap() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
          .widthIn(max = MiniMapWidth)
          .fillMaxWidth()
          .aspectRatio(MiniMapAspect)
          .clip(MaterialTheme.shapes.large)
          .semantics { contentDescription = resources.getString(R.string.viewer_place_map_image) }
          .fluidPressable(onClick = onShow, role = Role.Button),
      )
      Text(text = PlacesAttribution.MAP_BASE_SHORT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    FluidChip(
      label = resources.getString(R.string.viewer_place_show_map),
      selected = false,
      onClick = onShow,
      leading = { Icon(imageVector = Icons.Rounded.Map, contentDescription = null) },
    )
  }
}

/** La mini-mappa e' di 320 x 160 dp (§2.10): la larghezza che si chiede alla porta, in pixel. */
private val MiniMapWidth = 320.dp
private const val MiniMapAspect = 2f
private val CreditGap = 8.dp
