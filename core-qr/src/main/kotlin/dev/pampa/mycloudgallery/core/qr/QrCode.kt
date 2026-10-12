package dev.pampa.mycloudgallery.core.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.qr.codec.ZxingQrCodec
import dev.pampa.mycloudgallery.core.qr.contract.QrBits
import dev.pampa.mycloudgallery.core.qr.render.QrGeometry
import dev.pampa.mycloudgallery.core.qr.render.QrPalette
import dev.pampa.mycloudgallery.core.qr.render.darkRuns
import dev.pampa.mycloudgallery.core.security.screen.SecureScreen

/**
 * Un QR da mostrare (invito della famiglia, chiave del dispositivo, stato della famiglia).
 *
 * **Rendering.** Moduli neri su fondo bianco in qualunque tema (vedi [QrPalette]), con un modulo di
 * numero intero di pixel e una zona di rispetto di almeno quattro moduli: i bordi sono nitidi e gli
 * scanner lo leggono a distanza. E' quadrato e non supera i 400 dp (su un tablet un QR a tutta larghezza
 * non si legge meglio, e mangia la schermata); se il chiamante gli da' una misura fissa (`Modifier.size`),
 * quella vale. Dentro un contenitore piu' largo si centra da solo.
 *
 * **Protezione.** Con [secure] la finestra prende `FLAG_SECURE` finche' il QR e' in composizione
 * (ScreenProtection di core-security): l'invito non finisce in uno screenshot ne' nell'anteprima dei recenti
 * (E-S8). Il QR si disegna **dopo** che la protezione e' stata presa, non nello stesso fotogramma, cosi' non
 * c'e' un'istante in cui e' visibile e catturabile.
 *
 * **Accessibilita'.** [contentDescription] e' quello che il lettore di schermo dice del QR: va scritto dal
 * chiamante ("QR d'invito per Marta") e **non deve contenere il contenuto** del QR, che e' un segreto.
 *
 * **Se non si puo' codificare** ([content] vuoto o oltre [QrLimits.MAX_CHARS] caratteri) non si disegna niente
 * che somigli a un QR: un riquadro con la spiegazione, perche' un QR finto o troncato che qualcuno
 * scansiona e' peggio di nessun QR.
 */
@Composable
fun QrCode(
  content: String,
  contentDescription: String,
  secure: Boolean,
  modifier: Modifier = Modifier,
) {
  SecureScreen(enabled = secure)
  // Prima la protezione, poi il QR: l'effetto di SecureScreen gira prima di questo SideEffect, che accende
  // il disegno al fotogramma successivo.
  var windowProtected by remember(secure) { mutableStateOf(!secure) }
  SideEffect { if (!windowProtected) windowProtected = true }

  val bits = remember(content) { encodeOrNull(content) }
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    if (bits == null) {
      QrUnavailable()
    } else {
      QrPlate(bits = bits, contentDescription = contentDescription, drawModules = windowProtected)
    }
  }
}

/** La matrice di [content], o null se non si puo' codificare (e nel log solo la lunghezza, mai il testo). */
private fun encodeOrNull(content: String): QrBits? = try {
  ZxingQrCodec.encode(content)
} catch (_: IllegalArgumentException) {
  GalleryLog.w(TAG) { "QR non generato: ${content.length} caratteri" }
  null
}

@Composable
private fun QrPlate(bits: QrBits, contentDescription: String, drawModules: Boolean) {
  val runs = remember(bits) { bits.darkRuns() }
  val shape = ContinuousCornerShape(FluidRadius.Card)
  Box(
    modifier = Modifier
      .widthIn(max = QrMaxSide)
      .fillMaxWidth()
      .aspectRatio(1f)
      .clip(shape)
      .background(QrPalette.Plate)
      .semantics {
        this.contentDescription = contentDescription
        role = Role.Image
      },
  ) {
    if (drawModules) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val g = QrGeometry.of(size.width, size.height, bits.size)
        val module = g.module
        var i = 0
        while (i < runs.size) {
          drawRect(
            color = QrPalette.Module,
            topLeft = Offset(g.originX + runs[i + 1] * module, g.originY + runs[i] * module),
            size = Size(runs[i + 2] * module, module),
          )
          i += RUN_FIELDS
        }
      }
    }
  }
}

@Composable
private fun QrUnavailable() {
  val message = stringResource(R.string.qr_code_unavailable)
  Column(
    modifier = Modifier
      .widthIn(max = QrMaxSide)
      .fillMaxWidth()
      .aspectRatio(1f)
      .clip(ContinuousCornerShape(FluidRadius.Card))
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
      .padding(GalleryDimens.Gutter)
      .clearAndSetSemantics { contentDescription = message },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(UnavailableGap, Alignment.CenterVertically),
  ) {
    Icon(
      imageVector = Icons.Rounded.ErrorOutline,
      contentDescription = null,
      modifier = Modifier.size(UnavailableGlyph),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = message,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

private const val TAG = "QrCode"
private const val RUN_FIELDS = 3
private val QrMaxSide = 400.dp
private val UnavailableGlyph = 40.dp
private val UnavailableGap = 8.dp
