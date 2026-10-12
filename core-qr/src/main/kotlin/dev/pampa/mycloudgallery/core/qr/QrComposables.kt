package dev.pampa.mycloudgallery.core.qr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import dev.pampa.mycloudgallery.core.security.screen.SecureScreen

/**
 * Un QR da mostrare (invito della famiglia, chiave del dispositivo, stato della famiglia).
 *
 * Con [secure] la finestra prende `FLAG_SECURE` finche' il QR e' in composizione (ScreenProtection di
 * core-security): l'invito non finisce in uno screenshot ne' nell'anteprima dei recenti (E-S8).
 *
 * **Scheletro P0**: disegna un segnaposto con la sola descrizione accessibile. Il rendering vero dei moduli
 * (da `QrCodec.encode`) e' di P5; la firma resta questa.
 */
@Composable
fun QrCode(
  content: String,
  contentDescription: String,
  secure: Boolean,
  modifier: Modifier = Modifier,
) {
  SecureScreen(enabled = secure)
  Box(
    modifier = modifier
      .fillMaxWidth()
      .aspectRatio(1f)
      .clip(ContinuousCornerShape(FluidRadius.Card))
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
      .semantics { this.contentDescription = contentDescription },
    contentAlignment = Alignment.Center,
  ) {
    // Il contenuto non si mostra in chiaro: e' il QR stesso a portarlo. Qui serve solo a decidere la forma.
    Icon(
      imageVector = Icons.Rounded.QrCode2,
      contentDescription = null,
      modifier = Modifier.size(PlaceholderGlyph),
      tint = if (content.isEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * Lo scanner dei QR dentro l'app (D4): mirino della fotocamera (CameraX), decodifica con ZXing, gestione
 * del permesso della fotocamera e ripiego manuale. [accept] riceve ogni testo letto e decide
 * ([ScanVerdict]): lo scanner si ferma solo su [ScanVerdict.Accept].
 *
 * **Scheletro P0**: niente fotocamera, solo il messaggio e il tasto per tornare indietro (che porta al
 * ripiego manuale di chi lo usa). La fotocamera, il permesso e il mirino sono di P5; la firma resta questa.
 */
@Composable
fun QrScanner(
  accept: (String) -> ScanVerdict,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = PlaceholderPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(PlaceholderGap),
  ) {
    Icon(
      imageVector = Icons.Rounded.QrCodeScanner,
      contentDescription = null,
      modifier = Modifier.size(PlaceholderGlyph),
      tint = MaterialTheme.colorScheme.primary,
    )
    Text(
      text = stringResource(R.string.qr_scanner_unavailable_title),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    Text(
      text = stringResource(R.string.qr_scanner_unavailable_detail),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    FluidButton(
      text = stringResource(R.string.qr_scanner_manual),
      onClick = onCancel,
      style = FluidButtonStyle.Tinted,
    )
  }
}

private val PlaceholderGlyph = 64.dp
private val PlaceholderPadding = 24.dp
private val PlaceholderGap = 12.dp
