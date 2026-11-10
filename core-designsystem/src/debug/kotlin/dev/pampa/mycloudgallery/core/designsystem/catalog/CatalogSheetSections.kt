package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidGrabber
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.pampa.mycloudgallery.core.designsystem.a11y.galleryGlass
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocation
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetDefaults
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocationKind
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteScope
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteSheetContent
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeviceApprovalSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeviceApprovalSheetContent
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeviceRequest
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryMobileDataSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryMobileDataSheetContent
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryShareSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryShareSheetContent

/**
 * I fogli di conferma. Ognuno e' mostrato **due volte**: l'anteprima statica, che serve alla
 * revisione sugli screenshot, e un tasto che apre il foglio vero (un pop-up dell'engine, con
 * il trascinamento per chiudere) quando c'e' un host alla radice, cioe' nel catalogo vivo.
 */
internal fun sheetSections(): List<CatalogSection> = listOf(
  CatalogSection(R.string.sec_sheets_delete_phone, R.string.sec_sheets_delete_phone_detail) {
    DeleteSample(GalleryDeleteScope.PhoneOnly)
  },
  CatalogSection(R.string.sec_sheets_delete_everywhere, R.string.sec_sheets_delete_everywhere_detail) {
    DeleteSample(GalleryDeleteScope.Everywhere)
  },
  CatalogSection(R.string.sec_sheets_share, R.string.sec_sheets_share_detail) { ShareSample() },
  CatalogSection(R.string.sec_sheets_mobile, R.string.sec_sheets_mobile_detail) { MobileDataSample() },
  CatalogSection(R.string.sec_sheets_device, R.string.sec_sheets_device_detail) { DeviceSample() },
)

/**
 * Il palco di un foglio: la pagina sotto (una griglia di foto, scurita come fa lo scrim) e il foglio
 * sopra, di vetro `Modal`, con la sua maniglia. E' la stessa pellicola del pop-up vero, ferma.
 */
@Composable
private fun SheetStage(content: @Composable ColumnScope.() -> Unit) {
  val backdrop = rememberGlassBackdrop()
  val scheme = MaterialTheme.colorScheme
  val sheetShape = ContinuousCornerShape(topStart = FluidRadius.Sheet, topEnd = FluidRadius.Sheet)
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(ContinuousCornerShape(FluidRadius.Group)),
  ) {
    Box(
      modifier = Modifier
        .matchParentSize()
        .clipToBounds()
        .glassBackdropSource(backdrop),
    ) {
      CatalogStaticGrid(columns = PageColumns, rows = PageRows, firstSeed = 60, modifier = Modifier.fillMaxWidth())
      Box(Modifier.matchParentSize().background(scheme.scrim.copy(alpha = ScrimAlpha)))
    }
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = PageVisible)
        .heightIn(max = GallerySheetDefaults.MaxHeight)
        .galleryGlass(
          state = backdrop,
          tint = GlassDefaults.modalTint(),
          shape = sheetShape,
          role = GlassRole.Modal,
        )
        .clip(sheetShape),
    ) {
      FluidGrabber()
      content()
    }
  }
}

@Composable
private fun OpenButton(onClick: () -> Unit) {
  FluidButton(
    text = stringResource(R.string.catalog_open_sheet),
    onClick = onClick,
    style = FluidButtonStyle.Tinted,
    size = FluidButtonSize.Large,
    fillWidth = true,
  )
}

@Composable
private fun sampleLocations(): List<GalleryDeleteLocation> = listOf(
  GalleryDeleteLocation(
    title = stringResource(R.string.sample_loc_phone),
    kind = GalleryDeleteLocationKind.Phone,
  ),
  GalleryDeleteLocation(
    title = stringResource(R.string.sample_loc_nas_private),
    kind = GalleryDeleteLocationKind.NasPrivate,
  ),
  GalleryDeleteLocation(
    title = stringResource(R.string.sample_loc_nas_shared),
    kind = GalleryDeleteLocationKind.NasShared,
    deletable = false,
  ),
)

@Composable
private fun DeleteSample(initial: GalleryDeleteScope) {
  var open by remember { mutableStateOf(false) }
  var scope by remember { mutableStateOf(initial) }
  Column(verticalArrangement = Arrangement.spacedBy(CatalogButtonGap)) {
    SheetStage {
      GalleryDeleteSheetContent(
        count = SampleCount,
        scope = initial,
        onScopeChange = {},
        locations = sampleLocations(),
        onConfirm = {},
        onCancel = {},
      )
    }
    OpenButton { open = true }
  }
  GalleryDeleteSheet(
    visible = open,
    count = SampleCount,
    scope = scope,
    onScopeChange = { scope = it },
    locations = sampleLocations(),
    onConfirm = { open = false },
    onDismissRequest = { open = false },
  )
}

@Composable
private fun ShareSample() {
  var open by remember { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(CatalogButtonGap)) {
    SheetStage {
      GalleryShareSheetContent(
        count = SampleCount,
        onConfirm = {},
        onCancel = {},
        albumName = stringResource(R.string.sample_album),
        permissionLabel = stringResource(R.string.sample_permission),
      )
    }
    OpenButton { open = true }
  }
  GalleryShareSheet(
    visible = open,
    count = SampleCount,
    onConfirm = { open = false },
    onDismissRequest = { open = false },
    albumName = stringResource(R.string.sample_album),
    permissionLabel = stringResource(R.string.sample_permission),
  )
}

@Composable
private fun MobileDataSample() {
  var open by remember { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(CatalogButtonGap)) {
    SheetStage {
      GalleryMobileDataSheetContent(
        estimate = stringResource(R.string.sample_mobile_estimate),
        onConfirm = {},
        onWaitForWifi = {},
        budgetLabel = stringResource(R.string.sample_mobile_budget),
        budgetUsedFraction = SampleBudgetUsed,
      )
    }
    OpenButton { open = true }
  }
  GalleryMobileDataSheet(
    visible = open,
    estimate = stringResource(R.string.sample_mobile_estimate),
    onConfirm = { open = false },
    onWaitForWifi = { open = false },
    onDismissRequest = { open = false },
    budgetLabel = stringResource(R.string.sample_mobile_budget),
    budgetUsedFraction = SampleBudgetUsed,
  )
}

@Composable
private fun sampleRequest() = GalleryDeviceRequest(
  deviceName = stringResource(R.string.sample_device_name),
  profileName = stringResource(R.string.sample_device_profile),
  model = stringResource(R.string.sample_device_model),
  requestedAgo = stringResource(R.string.sample_device_ago),
  fingerprint = stringResource(R.string.sample_device_fingerprint),
)

@Composable
private fun DeviceSample() {
  var open by remember { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(CatalogButtonGap)) {
    SheetStage {
      GalleryDeviceApprovalSheetContent(request = sampleRequest(), onApprove = {}, onReject = {})
    }
    OpenButton { open = true }
  }
  GalleryDeviceApprovalSheet(
    visible = open,
    request = sampleRequest(),
    onApprove = { open = false },
    onReject = { open = false },
    onDismissRequest = { open = false },
  )
}

private val PageVisible = 56.dp
private val CatalogButtonGap = 10.dp
private const val PageColumns = 5
private const val PageRows = 14
private const val ScrimAlpha = 0.34f
private const val SampleCount = 12
private const val SampleBudgetUsed = 0.24f
