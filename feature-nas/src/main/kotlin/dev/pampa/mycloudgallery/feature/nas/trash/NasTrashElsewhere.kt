package dev.pampa.mycloudgallery.feature.nas.trash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.feature.nas.R
import dev.pampa.mycloudgallery.feature.nas.ui.NasEmptyState
import dev.pampa.mycloudgallery.feature.nas.ui.NasLabel
import dev.pampa.mycloudgallery.feature.nas.ui.NasPage

// ED-1 (R4 della revisione di Fase 7): il Cestino di Cartelle NAS non elenca niente in questo modulo. Nome e cartella di quello che e' stato eliminato
// possono essere di un media nascosto, e i Nascosti si leggono solo dietro la credenziale di sistema: la pagina vera sono gli Eliminati di recente
// (`:app` la passa a `NasScreenContent`). Se `:app` non ne passa una, questa pagina dice dove andare e non mostra nessun nome.

/** La pagina che manda agli Eliminati di recente: nessun elenco, nessun nome, nessun gesto sul NAS. */
@Composable
internal fun NasTrashElsewhere(
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  NasPage(
    label = NasLabel(title = stringResource(R.string.nas_hub_trash_title), subtitle = null),
    modifier = modifier,
    onBack = onBack,
    backdropOut = backdropOut,
    hostPadding = hostPadding,
  ) { insets ->
    Box(modifier = Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
      NasEmptyState(
        icon = Icons.Rounded.Lock,
        title = stringResource(R.string.nas_trash_elsewhere_title),
        detail = stringResource(R.string.nas_trash_elsewhere_detail),
        tone = GalleryIllustrationTone.Brand,
      )
    }
  }
}
