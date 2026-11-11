package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryActionBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarAction
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryNavigationIcon
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTopBar

/** Le barre in vetro: superiore (Libreria e viewer), delle schede, azioni. */
internal fun barSections(): List<CatalogSection> = listOf(
  CatalogSection(R.string.sec_bars_library, R.string.sec_bars_library_detail) { LibraryBar() },
  CatalogSection(R.string.sec_bars_viewer, R.string.sec_bars_viewer_detail) { ViewerBars() },
  CatalogSection(R.string.sec_bars_tabs, R.string.sec_bars_tabs_detail) { TabBars() },
  CatalogSection(R.string.sec_bars_actions, R.string.sec_bars_actions_detail) { ActionBars() },
)

/** Il fondale tipico della Libreria: una griglia di miniature, che la barra rifrange. */
@Composable
private fun GridPage() {
  CatalogStaticGrid(columns = GridColumns, rows = GridRows, firstSeed = 4)
}

@Composable
private fun LibraryBar() {
  CatalogPhotoStage(scene = SampleScene.Meadow, height = LibraryStageHeight, photo = { GridPage() }) { backdrop ->
    GalleryTopBar(
      title = stringResource(R.string.tab_library),
      backdrop = backdrop,
      modifier = Modifier.align(Alignment.TopCenter),
      actions = {
        GalleryBarButton(
          icon = Icons.Rounded.Checklist,
          contentDescription = stringResource(R.string.bar_action_select),
          onClick = {},
        )
        GalleryMenuButton(entries = sampleLibraryMenu(), backdrop = backdrop)
      },
    )
  }
}

@Composable
private fun ViewerBars() {
  Column(verticalArrangement = Arrangement.spacedBy(StageGap)) {
    ViewerBarStage(SampleScene.Bright)
    ViewerBarStage(SampleScene.Dark)
  }
}

@Composable
private fun ViewerBarStage(scene: SampleScene) {
  CatalogPhotoStage(scene = scene, height = ViewerStageHeight) { backdrop ->
    GalleryTopBar(
      title = stringResource(R.string.sample_date_long),
      subtitle = stringResource(R.string.sample_time_place),
      backdrop = backdrop,
      modifier = Modifier.align(Alignment.TopCenter),
      onPhoto = true,
      onNavigate = {},
      navigationIcon = GalleryNavigationIcon.Back,
      actions = {
        GalleryBarButton(
          icon = Icons.Rounded.Info,
          contentDescription = stringResource(R.string.bar_action_info),
          onClick = {},
        )
        GalleryMenuButton(entries = sampleViewerMenu(), backdrop = backdrop)
      },
    )
  }
}

@Composable
private fun TabBars() {
  Column(verticalArrangement = Arrangement.spacedBy(StageGap)) {
    TabBarStage(GalleryTab.Library)
    TabBarStage(GalleryTab.Albums)
  }
}

@Composable
private fun TabBarStage(selected: GalleryTab) {
  CatalogPhotoStage(scene = SampleScene.Meadow, height = TabStageHeight, photo = { GridPage() }) { backdrop ->
    GalleryTabBar(
      selected = selected,
      onSelect = {},
      backdrop = backdrop,
      onOpenSearch = {},
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(horizontal = FluidFloatingTabBarDefaults.HorizontalMargin)
        .padding(bottom = FluidFloatingTabBarDefaults.BottomMargin),
    )
  }
}

@Composable
private fun ActionBars() {
  Column(verticalArrangement = Arrangement.spacedBy(StageGap)) {
    ActionBarStage(SampleScene.Dark)
    ActionBarStage(SampleScene.Bright)
  }
}

@Composable
private fun ActionBarStage(scene: SampleScene) {
  CatalogPhotoStage(scene = scene, height = ActionStageHeight) { backdrop ->
    GalleryActionBar(
      actions = listOf(
        GalleryBarAction(Icons.Rounded.IosShare, stringResource(R.string.bar_action_share), onClick = {}),
        GalleryBarAction(Icons.Rounded.Favorite, stringResource(R.string.bar_action_favorite), onClick = {}),
        GalleryBarAction(Icons.Rounded.Info, stringResource(R.string.bar_action_info), onClick = {}),
        GalleryBarAction(Icons.Rounded.Delete, stringResource(R.string.bar_action_delete), onClick = {}, destructive = true),
      ),
      backdrop = backdrop,
      onPhoto = true,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 16.dp),
    )
  }
}

private val StageGap = 12.dp
private val LibraryStageHeight = 232.dp
private val ViewerStageHeight = 188.dp
private val TabStageHeight = 214.dp
private val ActionStageHeight = 132.dp
private const val GridColumns = 5
private const val GridRows = 4
