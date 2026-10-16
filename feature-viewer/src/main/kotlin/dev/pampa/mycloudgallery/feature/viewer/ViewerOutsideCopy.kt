package dev.pampa.mycloudgallery.feature.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeIconButton
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.viewer.page.PageSpec
import dev.pampa.mycloudgallery.feature.viewer.page.ViewerPage
import dev.pampa.mycloudgallery.feature.viewer.video.ViewerPlayback

// Fase 5A, P7 (docs/fase5-progetto.md §2.12, D36): "tocco su una riga" della sezione "N copie" per una copia che NON sta nell'indice. Le copie
// simili sono unite in una cella: l'indice ha solo la copertina, le altre copie stanno nel catalogo e non nel pager. Il viewer le mostra a
// parte, sopra se stesso, con il suo tasto per tornare alla foto; per le foto dell'indice invece si scorre il pager fino a loro.

/**
 * Dove stanno i byte di una copia fuori dall'indice. [kind] decide se e' una foto o un video (un video fuori dall'indice si vede fermo,
 * con il suo fotogramma: l'altra copia si guarda per scegliere, non per riprodurre).
 */
class OutsideCopy(val ref: MediaRef, val kind: MediaKind) {
  override fun toString(): String = "OutsideCopy($kind)"
}

/** Cerca una copia fuori dall'indice dal catalogo (`:app`): null se non ha una copia leggibile adesso. */
fun interface OutsideCopyLookup {
  suspend fun find(id: MediaId): OutsideCopy?
}

/**
 * L'altra copia [id] a tutto schermo, sopra il viewer. "Indietro" e il tasto in alto la chiudono e tornano alla foto di prima. Usa la
 * stessa pagina del pager ([ViewerPage]): stesse immagini, stessi limiti di decodifica (E-V5), stesso zoom.
 */
@Composable
internal fun OutsideCopyLayer(id: MediaId, services: ViewerServices, playback: ViewerPlayback, onClose: () -> Unit, modifier: Modifier = Modifier) {
  // `false` = la ricerca non e' finita: il messaggio "non disponibile" compare solo dopo, mai nel primo fotogramma.
  val lookup by produceState<Pair<Boolean, OutsideCopy?>>(initialValue = false to null, id, services) {
    value = true to services.outsideCopy?.find(id)
  }
  val lookedUp = lookup.first
  val copy = lookup.second
  val state = remember(id) { ViewerState(id) }
  val description = stringResource(R.string.viewer_outside_copy_description)
  BackHandler(onBack = onClose)
  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.scrim)
      // Il fondo assorbe i tocchi: sotto c'e' il viewer, che non deve reagire.
      .pointerInput(Unit) {},
  ) {
    val screen = IntSize(constraints.maxWidth, constraints.maxHeight)
    val shown = copy
    if (shown != null) {
      val spec = remember(id, shown) {
        PageSpec(
          id = id,
          ref = shown.ref,
          kind = shown.kind,
          aspect = 0f,
          description = description,
          thumbHash = null,
          thumbKey = ThumbKey(id, 0L, ThumbBucket.L),
        )
      }
      ViewerPage(
        spec = spec,
        screen = screen,
        loadActive = true,
        isCurrent = true,
        state = state,
        services = services,
        playback = playback,
        onTap = {},
        onMotionHold = {},
        onMotionRelease = {},
      )
    } else if (lookedUp) {
      Text(
        text = stringResource(R.string.viewer_outside_copy_missing),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
      )
    }
    Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
      GalleryChromeIconButton(
        icon = Icons.Rounded.Close,
        contentDescription = stringResource(R.string.viewer_outside_copy_close),
        onClick = onClose,
      )
    }
  }
}
