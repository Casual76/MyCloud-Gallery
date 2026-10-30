package dev.pampa.mycloudgallery.feature.memories.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.feature.memories.R
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerState
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerUi
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesDimens
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesText
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryCover
import dev.pampa.mycloudgallery.feature.memories.ui.coverRequest
import androidx.compose.runtime.LaunchedEffect

// LA CARTA FINALE (Fase 9, P9; docs/fase9-progetto.md §2.4 "Fine"): le miniature di tutto il ricordo e "Rivedi". Nessuna condivisione o
// esportazione in questa fase (§3, idea per dopo). Le miniature non si toccano (non portano da nessuna parte): sono la prova di cio' che si e'
// visto; i due bottoni stanno fuori dalla griglia e non si sovrappongono a niente. Le immagini sono solo le miniature locali.

@Composable
internal fun EndCard(ui: PlayerUi, state: PlayerState.Ended, snapshot: TimelineSnapshot, thumbnails: MemoriesThumbnails, handlers: PlayerHandlers) {
  val resources = LocalContext.current.resources
  // Le miniature della carta: quelle in vista adesso; il caricatore le prepara tutte (sono al piu' 40).
  LaunchedEffect(snapshot, ui.items) {
    val requests = ui.items.mapNotNull { coverRequest(thumbnails, snapshot, it.media, ThumbBucket.S) }
    thumbnails.loader.window(requests.take(VISIBLE_THUMBS), requests.drop(VISIBLE_THUMBS))
  }
  Column(
    modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(MemoriesDimens.PlayerInset),
    verticalArrangement = Arrangement.spacedBy(MemoriesDimens.BlockGap),
  ) {
    Column(
      modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
      verticalArrangement = Arrangement.spacedBy(MemoriesDimens.TightGap / 2),
    ) {
      Text(text = stringResource(R.string.memorie_player_end_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
      Text(text = MemoriesText.photos(resources, state.count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LazyVerticalGrid(
      columns = GridCells.Adaptive(MemoriesDimens.EndThumbMin),
      modifier = Modifier.weight(1f).fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(MemoriesDimens.TightGap / 2),
      verticalArrangement = Arrangement.spacedBy(MemoriesDimens.TightGap / 2),
    ) {
      items(ui.items, key = { it.media.value }) { item ->
        MemoryCover(thumbnails, snapshot, item.media, Modifier.fillMaxWidth().aspectRatio(1f), ThumbBucket.S)
      }
    }
    FluidButton(
      text = stringResource(R.string.memorie_player_restart), onClick = handlers::onRestart, size = FluidButtonSize.Large, fillWidth = true,
      leading = { Icon(imageVector = Icons.Rounded.Replay, contentDescription = null) },
    )
    FluidButton(
      text = stringResource(R.string.memorie_player_close_short), onClick = handlers::onExit, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true,
      leading = { Icon(imageVector = Icons.Rounded.Close, contentDescription = null) },
    )
  }
}

private const val VISIBLE_THUMBS = 24
