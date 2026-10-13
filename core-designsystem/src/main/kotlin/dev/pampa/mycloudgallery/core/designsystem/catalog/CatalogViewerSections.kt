package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarAction
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuButton
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryInfoHeader
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryInfoRow
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryInfoSection
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryNoticeProgress
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryVideoBar
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryViewerChrome
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryViewerNotice

/**
 * Il gruppo **Viewer** del catalogo (Fase 3, P8): il chrome sulla foto, la barra del video, il pannello informazioni e
 * le note di vetro per i casi in cui l'originale non c'e'. Sono i pezzi di `designsystem/viewer/`; il comportamento
 * (zoom, pager, gesti) vive in `feature-viewer`. Le stringhe del gruppo stanno in `values/strings_catalog_viewer.xml`.
 */
internal fun viewerSections(): List<CatalogSection> = listOf(
  CatalogSection(R.string.sec_viewer_chrome, R.string.sec_viewer_chrome_detail) { ViewerChromeDemo() },
  CatalogSection(R.string.sec_viewer_videobar, R.string.sec_viewer_videobar_detail) { VideoBarDemo() },
  CatalogSection(R.string.sec_viewer_info, R.string.sec_viewer_info_detail) { InfoDemo() },
  CatalogSection(R.string.sec_viewer_notes, R.string.sec_viewer_notes_detail) { NoticesDemo() },
)

@Composable
private fun ViewerChromeDemo() {
  Column(verticalArrangement = Arrangement.spacedBy(StageGap)) {
    ChromeStage(SampleScene.Dark)
    ChromeStage(SampleScene.Bright)
  }
}

@Composable
private fun ChromeStage(scene: SampleScene) {
  CatalogPhotoStage(scene = scene, height = ChromeStageHeight) { backdrop ->
    GalleryViewerChrome(
      visible = true,
      title = stringResource(R.string.sample_date_long),
      subtitle = stringResource(R.string.sample_time_place),
      backdrop = backdrop,
      onClose = {},
      topActions = {
        GalleryBarButton(
          icon = Icons.Rounded.Info,
          contentDescription = stringResource(R.string.bar_action_info),
          onClick = {},
        )
        GalleryMenuButton(entries = sampleViewerMenu(), backdrop = backdrop)
      },
      bottomActions = listOf(
        GalleryBarAction(Icons.Rounded.IosShare, stringResource(R.string.bar_action_share), onClick = {}),
        GalleryBarAction(Icons.Rounded.Favorite, stringResource(R.string.bar_action_favorite), onClick = {}),
        GalleryBarAction(Icons.Rounded.Info, stringResource(R.string.bar_action_info), onClick = {}),
        GalleryBarAction(Icons.Rounded.DeleteForever, stringResource(R.string.bar_action_delete), onClick = {}, destructive = true),
      ),
    )
  }
}

@Composable
private fun VideoBarDemo() {
  Column(verticalArrangement = Arrangement.spacedBy(StageGap)) {
    VideoBarStage(SampleScene.Dark, playing = true, initialPosition = 12_000L)
    VideoBarStage(SampleScene.Bright, playing = false, initialPosition = 71_000L)
  }
}

@Composable
private fun VideoBarStage(scene: SampleScene, playing: Boolean, initialPosition: Long) {
  var position by remember { mutableLongStateOf(initialPosition) }
  var isPlaying by remember { mutableStateOf(playing) }
  var muted by remember { mutableStateOf(false) }
  CatalogPhotoStage(scene = scene, height = VideoStageHeight) { backdrop ->
    GalleryVideoBar(
      positionMs = position,
      durationMs = VideoDurationMs,
      playing = isPlaying,
      backdrop = backdrop,
      onPlayPause = { isPlaying = !isPlaying },
      onScrub = { position = it },
      onScrubFinished = { position = it },
      muted = muted,
      onToggleMute = { muted = !muted },
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 16.dp),
    )
  }
}

@Composable
private fun InfoDemo() {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(ContinuousCornerShape(FluidRadius.Sheet))
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .padding(bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(StageGap),
  ) {
    GalleryInfoHeader(title = stringResource(R.string.sample_info_title), onClose = {})
    Column(
      modifier = Modifier.padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(StageGap),
    ) {
      GalleryInfoSection(
        rows = listOf(
          GalleryInfoRow(
            title = stringResource(R.string.sample_info_date),
            caption = stringResource(R.string.sample_info_zone),
            icon = Icons.Rounded.CalendarMonth,
          ),
          GalleryInfoRow(
            title = stringResource(R.string.sample_info_date_estimated),
            caption = stringResource(R.string.sample_info_zone_unknown),
            icon = Icons.Rounded.CalendarMonth,
            pill = stringResource(R.string.sample_info_estimated),
          ),
        ),
      )
      GalleryInfoSection(
        header = stringResource(R.string.sample_info_place_header),
        rows = listOf(
          GalleryInfoRow(
            title = stringResource(R.string.sample_info_place),
            caption = stringResource(R.string.sample_info_place_detail),
            icon = Icons.Rounded.Place,
            tone = FluidTone.Primary,
            onClick = {},
          ),
        ),
      )
      GalleryInfoSection(
        header = stringResource(R.string.sample_info_camera_header),
        rows = listOf(
          GalleryInfoRow(
            title = stringResource(R.string.sample_info_camera),
            caption = stringResource(R.string.sample_info_camera_detail),
            icon = Icons.Rounded.CameraAlt,
          ),
          GalleryInfoRow(
            title = stringResource(R.string.sample_info_file),
            caption = stringResource(R.string.sample_info_file_detail),
            icon = Icons.Rounded.Image,
          ),
          GalleryInfoRow(
            title = stringResource(R.string.sample_info_folder),
            caption = stringResource(R.string.sample_info_folder_detail),
            icon = Icons.Rounded.Folder,
          ),
        ),
      )
    }
  }
}

@Composable
private fun NoticesDemo() {
  Column(verticalArrangement = Arrangement.spacedBy(StageGap)) {
    NoticeStage { backdrop ->
      GalleryViewerNotice(
        icon = Icons.Rounded.CloudOff,
        title = stringResource(R.string.sample_notice_nas_title),
        detail = stringResource(R.string.sample_notice_nas_detail),
        backdrop = backdrop,
        progress = GalleryNoticeProgress.Indeterminate,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
      )
    }
    NoticeStage { backdrop ->
      GalleryViewerNotice(
        icon = Icons.Rounded.NetworkCell,
        title = stringResource(R.string.sample_notice_mobile_title),
        detail = stringResource(R.string.sample_notice_mobile_detail),
        backdrop = backdrop,
        actionLabel = stringResource(R.string.sample_notice_mobile_action),
        onAction = {},
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
      )
    }
    NoticeStage { backdrop ->
      GalleryViewerNotice(
        icon = Icons.Rounded.ReportProblem,
        title = stringResource(R.string.sample_notice_failed_title),
        detail = stringResource(R.string.sample_notice_failed_detail),
        backdrop = backdrop,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
      )
    }
  }
}

@Composable
private fun NoticeStage(content: @Composable androidx.compose.foundation.layout.BoxScope.(dev.antigravity.fluidengine.ui.fluid.GlassBackdropState) -> Unit) {
  CatalogPhotoStage(scene = SampleScene.Sunset, height = NoticeStageHeight) { backdrop -> content(backdrop) }
}

private val StageGap = 12.dp
private val ChromeStageHeight = 300.dp
private val VideoStageHeight = 120.dp
private val NoticeStageHeight = 236.dp
private const val VideoDurationMs = 134_000L
