package dev.pampa.mycloudgallery.core.designsystem.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidSlider
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.chrome.galleryTintOnPhoto
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import java.util.Locale

/** Le misure della barra del video. */
object GalleryVideoBarDefaults {

  /** La larghezza massima: su un tablet la barra non si allunga fino ai due bordi. */
  val MaxWidth: Dp = 520.dp

  /** Il margine laterale sul telefono. */
  val SideMargin: Dp = 16.dp

  /** La larghezza riservata a un tempo ("1:02:03" sta dentro): i numeri non spostano la barra mentre cambiano. */
  val TimeWidth: Dp = 52.dp
}

/**
 * La barra del video: play/pausa, il tempo, la barra di scorrimento, la durata e l'audio, in una capsula di vetro
 * sopra la foto (`galleryTintOnPhoto`).
 *
 * **Scorrere con precisione.** Mentre il dito trascina la barra, [onScrub] riceve la posizione ad ogni passo e chi
 * ascolta cerca il fotogramma chiave piu' vicino (`CLOSEST_SYNC`: veloce, il video "scorre"); al rilascio
 * [onScrubFinished] riceve la posizione definitiva e chi ascolta cerca il fotogramma esatto. Durante il gesto la
 * barra mostra la posizione del dito, non quella del video, che arriva in ritardo: altrimenti la maniglia
 * tornerebbe indietro sotto il dito a ogni aggiornamento.
 *
 * I tempi sono in cifre tabulari (non ballano) e hanno una larghezza fissa. Ogni tasto ha un bersaglio da 48 dp.
 *
 * @param muted `null` se il video non ha audio: il tasto non compare. Altrimenti lo stato, e [onToggleMute] lo cambia.
 * @param ended a fine video il tasto principale diventa "Rivedi" (riparte da capo).
 */
@Composable
fun GalleryVideoBar(
  positionMs: Long,
  durationMs: Long,
  playing: Boolean,
  backdrop: GlassBackdropState,
  onPlayPause: () -> Unit,
  onScrub: (Long) -> Unit,
  onScrubFinished: (Long) -> Unit,
  modifier: Modifier = Modifier,
  ended: Boolean = false,
  muted: Boolean? = null,
  onToggleMute: () -> Unit = {},
) {
  val scheme = MaterialTheme.colorScheme
  val duration = durationMs.coerceAtLeast(0L)
  var scrub by remember { mutableStateOf<Float?>(null) }
  val fraction = scrub ?: if (duration > 0L) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
  val shownMs = scrub?.let { (it * duration).toLong() } ?: positionMs.coerceIn(0L, duration.coerceAtLeast(positionMs))
  val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TabularFigures)
  val timeText = GalleryVideoTime.format(shownMs)
  val durationText = GalleryVideoTime.format(duration)
  val timeDescription = stringResource(R.string.videobar_time_description, timeText, durationText)

  Row(
    modifier = modifier
      .padding(horizontal = GalleryVideoBarDefaults.SideMargin)
      .widthIn(max = GalleryVideoBarDefaults.MaxWidth)
      .fillMaxWidth()
      .glassSurface(
        state = backdrop,
        tint = galleryTintOnPhoto(floating = true),
        shape = FluidCapsuleShape,
        role = GlassRole.Floating,
      )
      .padding(horizontal = BarPadding),
    horizontalArrangement = Arrangement.spacedBy(BarGap),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BarIconButton(
      icon = when {
        ended -> Icons.Rounded.Replay
        playing -> Icons.Rounded.Pause
        else -> Icons.Rounded.PlayArrow
      },
      description = stringResource(
        when {
          ended -> R.string.videobar_replay
          playing -> R.string.videobar_pause
          else -> R.string.videobar_play
        },
      ),
      onClick = onPlayPause,
    )
    Text(
      text = timeText,
      modifier = Modifier
        .widthIn(min = GalleryVideoBarDefaults.TimeWidth)
        .semantics { contentDescription = timeDescription },
      style = timeStyle,
      color = scheme.onSurface,
      textAlign = TextAlign.Center,
      maxLines = 1,
    )
    FluidSlider(
      value = fraction,
      onValueChange = { value ->
        scrub = value
        onScrub((value * duration).toLong())
      },
      onValueChangeFinished = {
        val value = scrub ?: fraction
        scrub = null
        onScrubFinished((value * duration).toLong())
      },
      modifier = Modifier.weight(1f),
      enabled = duration > 0L,
    )
    Text(
      text = durationText,
      modifier = Modifier.widthIn(min = GalleryVideoBarDefaults.TimeWidth),
      style = timeStyle,
      color = scheme.onSurface,
      textAlign = TextAlign.Center,
      maxLines = 1,
    )
    if (muted != null) {
      BarIconButton(
        icon = if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
        description = stringResource(if (muted) R.string.videobar_unmute else R.string.videobar_mute),
        onClick = onToggleMute,
      )
    }
  }
}

@Composable
private fun BarIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .size(GalleryDimens.MinTouchTarget)
      .semantics { contentDescription = description }
      .fluidPressable(
        onClick = onClick,
        pressedScale = PressedScale,
        role = Role.Button,
        focusShape = FluidCapsuleShape,
      ),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      modifier = Modifier.size(BarGlyph),
      tint = MaterialTheme.colorScheme.onSurface,
    )
  }
}

/** Il tempo di un video come lo si legge su un lettore: `0:07`, `12:41`, `1:02:03`. Funzione pura. */
object GalleryVideoTime {

  fun format(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) / MillisPerSecond)
    val hours = totalSeconds / SecondsPerHour
    val minutes = (totalSeconds % SecondsPerHour) / SecondsPerMinute
    val seconds = totalSeconds % SecondsPerMinute
    return if (hours > 0L) {
      String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
  }

  private const val MillisPerSecond = 1000L
  private const val SecondsPerMinute = 60L
  private const val SecondsPerHour = 3600L
}

private const val TabularFigures = "tnum"
private val BarPadding = 4.dp
private val BarGap = 2.dp
private val BarGlyph = 24.dp
private const val PressedScale = 0.9f
