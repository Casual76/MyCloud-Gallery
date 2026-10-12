package dev.pampa.mycloudgallery.core.qr.scanner

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidPressFeedback
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.fluidReadingWidth
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustration
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import dev.pampa.mycloudgallery.core.qr.R
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.security.input.SecureInput

/**
 * La schermata dello scanner, pagina per pagina, a partire da un [ScannerUi]. Non sa niente di fotocamera,
 * permessi o CameraX: il mirino vero (o un finto, negli screenshot) arriva da [feed].
 *
 * **Layout.** Una colonna di lettura centrata (larga al piu' [PageMaxWidth]): su un tablet in orizzontale il
 * mirino non diventa un lenzuolo. Se il chiamante da' un'altezza limitata, la colonna scorre (con il testo
 * al massimo la pagina non si taglia); se la da' illimitata (dentro un'altra lista), non scorre da sola.
 */
@Composable
internal fun ScannerContent(
  ui: ScannerUi,
  actions: ScannerActions,
  modifier: Modifier = Modifier,
  feed: @Composable BoxScope.() -> Unit,
) {
  BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
    val scroll = if (constraints.hasBoundedHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .fluidReadingWidth(PageMaxWidth)
        .then(scroll)
        .padding(vertical = PageVerticalPadding),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(GalleryDimens.ItemSpacing),
    ) {
      when (ui.page) {
        ScannerPage.Ask -> AskPage(actions)
        ScannerPage.Viewfinder -> ViewfinderPage(ui, actions, feed)
        ScannerPage.Manual -> ManualPage(ui, actions)
      }
    }
  }
}

// --- pagina: spiegazione e richiesta del permesso -----------------------------------------------------------

@Composable
private fun AskPage(actions: ScannerActions) {
  PageHeader(
    icon = Icons.Rounded.QrCodeScanner,
    tone = GalleryIllustrationTone.Brand,
    title = R.string.qr_perm_ask_title,
    detail = R.string.qr_perm_ask_detail,
  )
  ButtonStack {
    PageButton(R.string.qr_perm_ask_allow, FluidButtonStyle.Filled, actions.onRequestPermission)
    PageButton(R.string.qr_manual_open, FluidButtonStyle.Tinted, actions.onOpenManual)
    PageButton(R.string.qr_cancel, FluidButtonStyle.Plain, actions.onCancel)
  }
}

// --- pagina: il mirino --------------------------------------------------------------------------------------

@Composable
private fun ViewfinderPage(ui: ScannerUi, actions: ScannerActions, feed: @Composable BoxScope.() -> Unit) {
  val description = stringResource(R.string.qr_viewfinder_description)
  Box(
    modifier = Modifier
      .widthIn(max = ViewfinderMaxSide)
      .fillMaxWidth()
      .aspectRatio(1f)
      .clip(ContinuousCornerShape(FluidRadius.Group))
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
      .then(if (ui.read) Modifier else Modifier.semantics { contentDescription = description }),
  ) {
    if (ui.read) {
      Icon(
        imageVector = Icons.Rounded.CheckCircle,
        contentDescription = null,
        modifier = Modifier
          .align(Alignment.Center)
          .size(ReadGlyph),
        tint = MaterialTheme.colorScheme.primary,
      )
    } else {
      feed()
      CornerBrackets(modifier = Modifier.fillMaxSize())
      if (ui.torch.available) {
        TorchButton(
          torch = ui.torch,
          onToggle = actions.onToggleTorch,
          modifier = Modifier
            // In basso al centro: ai quattro angoli ci sono gli angoli del mirino, e la torcia non deve coprirli.
            .align(Alignment.BottomCenter)
            .padding(TorchInset),
        )
      }
    }
  }
  StatusLine(ui = ui, idle = stringResource(R.string.qr_viewfinder_hint))
  if (!ui.read) {
    ButtonStack {
      PageButton(R.string.qr_manual_open, FluidButtonStyle.Tinted, actions.onOpenManual)
      PageButton(R.string.qr_cancel, FluidButtonStyle.Plain, actions.onCancel)
    }
  }
}

/** Quattro angoli ad L attorno al quadrato del mirino: dicono dove mettere il codice, senza coprire l'immagine. */
@Composable
private fun CornerBrackets(modifier: Modifier = Modifier) {
  val halo = GalleryOnPhoto.scrim()
  val line = GalleryOnPhoto.Content
  Box(
    modifier = modifier.drawWithContent {
      drawContent()
      val inset = size.minDimension * BracketInset
      val arm = size.minDimension * BracketArm
      // Un alone scuro sotto il tratto chiaro: si vede sia su una parete bianca che su un tavolo nero.
      drawBrackets(inset, arm, halo, BracketStroke.toPx() + 2 * BracketHalo.toPx())
      drawBrackets(inset, arm, line, BracketStroke.toPx())
    },
  )
}

private fun DrawScope.drawBrackets(inset: Float, arm: Float, color: Color, width: Float) {
  val left = inset
  val top = inset
  val right = size.width - inset
  val bottom = size.height - inset
  fun corner(x: Float, y: Float, dx: Float, dy: Float) {
    drawLine(color, Offset(x, y), Offset(x + dx * arm, y), width, StrokeCap.Round)
    drawLine(color, Offset(x, y), Offset(x, y + dy * arm), width, StrokeCap.Round)
  }
  corner(left, top, 1f, 1f)
  corner(right, top, -1f, 1f)
  corner(left, bottom, 1f, -1f)
  corner(right, bottom, -1f, -1f)
}

@Composable
private fun TorchButton(torch: TorchUi, onToggle: () -> Unit, modifier: Modifier = Modifier) {
  val label = stringResource(R.string.qr_torch)
  val state = stringResource(if (torch.on) R.string.qr_torch_on else R.string.qr_torch_off)
  Box(
    modifier = modifier
      .size(GalleryDimens.MinTouchTarget)
      .fluidPressable(onClick = onToggle, role = Role.Switch, feedback = FluidPressFeedback.Expand)
      .clip(FluidCapsuleShape)
      .background(GalleryOnPhoto.scrim())
      .semantics {
        contentDescription = label
        stateDescription = state
      },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = if (torch.on) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
      contentDescription = null,
      modifier = Modifier.size(TorchGlyph),
      tint = GalleryOnPhoto.Content,
    )
  }
}

// --- pagina: inserimento a mano -----------------------------------------------------------------------------

@Composable
private fun ManualPage(ui: ScannerUi, actions: ScannerActions) {
  val header = when (ui.access) {
    CameraAccess.Denied -> Header(
      Icons.Rounded.CameraAlt,
      GalleryIllustrationTone.Caution,
      R.string.qr_perm_denied_title,
      R.string.qr_perm_denied_detail,
    )
    CameraAccess.DeniedPermanently -> Header(
      Icons.Rounded.NoPhotography,
      GalleryIllustrationTone.Danger,
      R.string.qr_perm_blocked_title,
      R.string.qr_perm_blocked_detail,
    )
    CameraAccess.NoCamera -> Header(
      Icons.Rounded.VideocamOff,
      GalleryIllustrationTone.Caution,
      R.string.qr_no_camera_title,
      R.string.qr_no_camera_detail,
    )
    CameraAccess.Granted, CameraAccess.NotAsked -> Header(
      Icons.Rounded.Keyboard,
      GalleryIllustrationTone.Brand,
      R.string.qr_manual_title,
      R.string.qr_manual_detail,
    )
  }
  PageHeader(header.icon, header.tone, header.title, header.detail, illustrationSize = CompactIllustration)
  FluidTextField(
    value = ui.manualText,
    onValueChange = actions.onManualTextChange,
    label = stringResource(R.string.qr_manual_label),
    placeholder = stringResource(R.string.qr_manual_placeholder),
    singleLine = false,
    minLines = ManualMinLines,
    maxLines = ManualMaxLines,
    isError = ui.rejection != null && !ui.read,
    readOnly = ui.read,
    // Il codice e' un segreto (un invito, una chiave): niente suggerimenti ne' apprendimento della tastiera.
    keyboardOptions = SecureInput.keyboardOptions(ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { actions.onManualSubmit() }),
  )
  StatusLine(ui = ui, idle = null)
  if (!ui.read) {
    ButtonStack {
      PageButton(
        label = R.string.qr_manual_submit,
        style = FluidButtonStyle.Filled,
        onClick = actions.onManualSubmit,
        enabled = ScanDispatcher.normalizeManual(ui.manualText).isNotEmpty(),
      )
      when (ui.access) {
        CameraAccess.Denied -> PageButton(R.string.qr_perm_denied_retry, FluidButtonStyle.Tinted, actions.onRequestPermission)
        CameraAccess.DeniedPermanently -> PageButton(R.string.qr_perm_blocked_settings, FluidButtonStyle.Tinted, actions.onOpenSettings)
        CameraAccess.NoCamera -> Unit
        CameraAccess.Granted, CameraAccess.NotAsked -> PageButton(R.string.qr_manual_back_to_camera, FluidButtonStyle.Tinted, actions.onCloseManual)
      }
      PageButton(R.string.qr_cancel, FluidButtonStyle.Plain, actions.onCancel)
    }
  }
}

private class Header(
  val icon: ImageVector,
  val tone: GalleryIllustrationTone,
  @param:StringRes val title: Int,
  @param:StringRes val detail: Int,
)

// --- pezzi comuni --------------------------------------------------------------------------------------------

@Composable
private fun PageHeader(
  icon: ImageVector,
  tone: GalleryIllustrationTone,
  @StringRes title: Int,
  @StringRes detail: Int,
  illustrationSize: Dp = RegularIllustration,
) {
  GalleryIllustration(icon = icon, tone = tone, size = illustrationSize)
  Text(
    text = stringResource(title),
    modifier = Modifier.semantics { heading() },
    style = MaterialTheme.typography.headlineSmall,
    color = MaterialTheme.colorScheme.onSurface,
    textAlign = TextAlign.Center,
  )
  Text(
    text = stringResource(detail),
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
  )
}

@Composable
private fun ButtonStack(content: @Composable () -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(ButtonGap),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    content()
  }
}

@Composable
private fun PageButton(
  @StringRes label: Int,
  style: FluidButtonStyle,
  onClick: () -> Unit,
  enabled: Boolean = true,
) {
  FluidButton(
    text = stringResource(label),
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    style = style,
    size = FluidButtonSize.Large,
    enabled = enabled,
    fillWidth = true,
  )
}

/**
 * La riga di stato sotto il mirino o il campo: un suggerimento, il motivo dell'ultimo rifiuto o "Codice letto".
 *
 * E' **un solo nodo che cambia testo**, con regione "viva" cortese: il lettore di schermo annuncia ogni
 * cambio (anche "Codice letto", a lettura avvenuta) senza spostare il fuoco. Il rifiuto ha anche
 * un'icona: il colore da solo non basta a chi non lo distingue.
 */
@Composable
private fun StatusLine(ui: ScannerUi, idle: String?) {
  val scheme = MaterialTheme.colorScheme
  val rejection = ui.rejection
  val text = when {
    ui.read -> stringResource(R.string.qr_read_done)
    rejection != null -> stringResource(rejection.message())
    else -> idle.orEmpty()
  }
  val isRejection = rejection != null && !ui.read
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    horizontalArrangement = Arrangement.spacedBy(StatusGap, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (isRejection) {
      Icon(
        imageVector = Icons.Rounded.ErrorOutline,
        contentDescription = null,
        modifier = Modifier.size(StatusGlyph),
        tint = scheme.error,
      )
    }
    if (text.isNotEmpty()) {
      Text(
        text = text,
        modifier = Modifier.weight(1f, fill = false),
        style = if (ui.read) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
        color = when {
          isRejection -> scheme.error
          ui.read -> scheme.onSurface
          else -> scheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
      )
    }
  }
}

/** Il testo che spiega ogni [ScanRejection]. */
@StringRes
internal fun ScanRejection.message(): Int = when (this) {
  ScanRejection.NOT_OURS -> R.string.qr_reject_not_ours
  ScanRejection.WIREGUARD_SECRET -> R.string.qr_reject_wireguard
  ScanRejection.OTHER_FAMILY -> R.string.qr_reject_other_family
  ScanRejection.EXPIRED -> R.string.qr_reject_expired
  ScanRejection.MALFORMED -> R.string.qr_reject_malformed
}

private val PageMaxWidth = 460.dp
private val PageVerticalPadding = 12.dp
private val ViewfinderMaxSide = 360.dp
private val RegularIllustration = 132.dp
private val CompactIllustration = 96.dp
private val ButtonGap = 8.dp
private val StatusGap = 8.dp
private val StatusGlyph = 20.dp
private val ReadGlyph = 96.dp
private val TorchInset = 12.dp
private val TorchGlyph = 24.dp
private val BracketStroke = 4.dp
private val BracketHalo = 1.5.dp
private const val BracketInset = 0.10f
private const val BracketArm = 0.14f
private const val ManualMinLines = 3
private const val ManualMaxLines = 6
