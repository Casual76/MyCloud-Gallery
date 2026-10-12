package dev.pampa.mycloudgallery.core.designsystem.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import kotlin.math.roundToInt

/**
 * Cio' che un badge puo' dire di un media. Sono tipi di *interfaccia*: la galleria non sa come si
 * decide che un media e' "al sicuro" o "solo sul NAS", lo sa chi costruisce la lista (4.4, 5.3).
 */
@Immutable
sealed interface GalleryBadge {

  /** Un video, con la durata gia' formattata ("0:42", "1:03:20"). */
  data class VideoDuration(val duration: String) : GalleryBadge

  /** L'originale esiste solo sul NAS. */
  data object NasOnly : GalleryBadge

  /** L'originale esiste solo sul telefono: non e' ancora stato salvato. */
  data object PhoneOnly : GalleryBadge

  /** Una copia privata verificata sul NAS: il solo caso in cui un media e' "al sicuro" (piano, 5.3). */
  data object Safe : GalleryBadge

  /** In caricamento sul NAS. [progress] 0..1, o null quando non si sa a che punto e'. */
  data class Uploading(val progress: Float? = null) : GalleryBadge

  /** Il caricamento o la preparazione sono falliti. */
  data object Failed : GalleryBadge

  /** Un preferito. */
  data object Favorite : GalleryBadge

  /** Il media sta in un album condiviso, o nell'area comune. */
  data object Shared : GalleryBadge

  /** In app si vede una versione modificata; l'originale resta recuperabile. */
  data object EditedVersion : GalleryBadge

  /** La data non e' certa: ripiego sulla data del file (piano, 3.1). */
  data object EstimatedDate : GalleryBadge
}

/** I badge che sono solo un'icona, e quindi possono stare insieme in un [GalleryBadgeCluster]. */
val GalleryBadge.isIconOnly: Boolean
  get() = when (this) {
    GalleryBadge.NasOnly,
    GalleryBadge.PhoneOnly,
    GalleryBadge.Safe,
    GalleryBadge.Favorite,
    GalleryBadge.Shared,
    GalleryBadge.EditedVersion,
    GalleryBadge.EstimatedDate,
    -> true
    is GalleryBadge.VideoDuration, is GalleryBadge.Uploading, GalleryBadge.Failed -> false
  }

private val GalleryBadge.glyph: ImageVector?
  get() = when (this) {
    is GalleryBadge.VideoDuration -> Icons.Rounded.PlayArrow
    GalleryBadge.NasOnly -> Icons.Rounded.Cloud
    GalleryBadge.PhoneOnly -> Icons.Rounded.PhoneAndroid
    GalleryBadge.Safe -> Icons.Rounded.GppGood
    GalleryBadge.Favorite -> Icons.Rounded.Favorite
    GalleryBadge.Shared -> Icons.Rounded.Group
    GalleryBadge.EditedVersion -> Icons.Rounded.Tune
    GalleryBadge.EstimatedDate -> Icons.Rounded.HistoryToggleOff
    is GalleryBadge.Uploading, GalleryBadge.Failed -> null
  }

/** La frase che un lettore di schermo pronuncia per questo badge. */
@Composable
fun GalleryBadge.description(): String = when (this) {
  is GalleryBadge.VideoDuration -> stringResource(R.string.badge_video_duration, duration)
  GalleryBadge.NasOnly -> stringResource(R.string.badge_nas_only)
  GalleryBadge.PhoneOnly -> stringResource(R.string.badge_phone_only)
  GalleryBadge.Safe -> stringResource(R.string.badge_safe)
  is GalleryBadge.Uploading -> {
    val fraction = progress
    if (fraction == null) {
      stringResource(R.string.badge_uploading)
    } else {
      stringResource(R.string.badge_uploading_progress, (fraction.coerceIn(0f, 1f) * 100).roundToInt())
    }
  }
  GalleryBadge.Failed -> stringResource(R.string.badge_failed)
  GalleryBadge.Favorite -> stringResource(R.string.badge_favorite)
  GalleryBadge.Shared -> stringResource(R.string.badge_shared)
  GalleryBadge.EditedVersion -> stringResource(R.string.badge_edited)
  GalleryBadge.EstimatedDate -> stringResource(R.string.badge_estimated_date)
}

/** Il testo breve accanto all'icona, nella forma con etichetta: la durata per un video, altrimenti la frase. */
@Composable
private fun GalleryBadge.label(): String =
  if (this is GalleryBadge.VideoDuration) duration else description()

/**
 * Il testo di un badge: l'11 sp semibold dell'engine con le cifre tabulari, perche' una durata che
 * cambia ("0:09" -> "0:10") non deve far ballare il badge.
 */
@Composable
private fun badgeTextStyle() = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")

/**
 * Un badge sopra una foto.
 *
 * **Perche' uno scrim e non vetro.** Un badge sta sopra una foto qualunque: chiara, scura, a righe.
 * La regola del vetro e' che sta sul *contenitore* — una griglia con tre badge per cella e centomila
 * celle sarebbe tre `GraphicsLayer` per cella. Lo scrim e' una tinta piatta al 60% di nero con testo
 * bianco: costa un rettangolo arrotondato, e il contrasto col bianco e' sopra i 4,5:1 anche sulla
 * peggiore delle foto (vedi [GalleryOnPhoto]). Il vetro vero sta sulle barre, che sono poche.
 *
 * Compatto per default: solo l'icona (e la durata, per un video). Con [labeled] mostra anche la
 * frase, per i pannelli di informazioni e per la legenda del catalogo.
 */
@Composable
fun GalleryBadgeView(
  badge: GalleryBadge,
  modifier: Modifier = Modifier,
  labeled: Boolean = false,
) {
  val description = badge.description()
  when (badge) {
    is GalleryBadge.Uploading -> UploadingBadge(badge.progress, description, modifier, labeled)
    GalleryBadge.Failed -> FailedBadge(description, modifier, labeled)
    else -> PillBadge(badge, description, labeled, modifier)
  }
}

@Composable
private fun PillBadge(
  badge: GalleryBadge,
  description: String,
  labeled: Boolean,
  modifier: Modifier,
) {
  val showLabel = labeled || badge is GalleryBadge.VideoDuration
  Row(
    modifier = modifier
      .height(GalleryDimens.BadgeHeight)
      .defaultMinSize(minWidth = GalleryDimens.BadgeHeight)
      .clip(FluidCapsuleShape)
      .background(GalleryOnPhoto.scrim())
      .border(width = BadgeHairline, color = GalleryOnPhoto.Content.copy(alpha = HairlineAlpha), shape = FluidCapsuleShape)
      .padding(horizontal = if (showLabel) BadgeLabeledPadding else BadgeIconPadding)
      .semantics { contentDescription = description },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(BadgeGlyphGap, Alignment.CenterHorizontally),
  ) {
    badge.glyph?.let { glyph ->
      Icon(
        imageVector = glyph,
        contentDescription = null,
        modifier = Modifier.size(GalleryDimens.BadgeGlyph),
        tint = GalleryOnPhoto.Content,
      )
    }
    if (showLabel) {
      Text(
        text = badge.label(),
        modifier = Modifier.clearAndSetSemantics { },
        style = badgeTextStyle(),
        color = GalleryOnPhoto.Content,
        maxLines = 1,
      )
    }
  }
}

/** In caricamento: un anello che si riempie (o, senza una percentuale, il cerchio di attesa dell'engine). */
@Composable
private fun UploadingBadge(
  progress: Float?,
  description: String,
  modifier: Modifier,
  labeled: Boolean,
) {
  Row(
    modifier = modifier
      .height(GalleryDimens.BadgeHeight)
      .defaultMinSize(minWidth = GalleryDimens.BadgeHeight)
      .clip(FluidCapsuleShape)
      .background(GalleryOnPhoto.scrim())
      .border(width = BadgeHairline, color = GalleryOnPhoto.Content.copy(alpha = HairlineAlpha), shape = FluidCapsuleShape)
      .padding(horizontal = if (labeled) BadgeLabeledPadding else 0.dp)
      .semantics { contentDescription = description },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(BadgeGlyphGap, Alignment.CenterHorizontally),
  ) {
    Box(modifier = Modifier.size(GalleryDimens.BadgeHeight), contentAlignment = Alignment.Center) {
      if (progress == null) {
        FluidSpinner(size = GalleryDimens.BadgeGlyph, color = GalleryOnPhoto.Content)
      } else {
        GalleryProgressRing(
          progress = progress,
          modifier = Modifier.size(BadgeRingSize),
          color = GalleryOnPhoto.Content,
          trackColor = GalleryOnPhoto.Content.copy(alpha = RingTrackAlpha),
        )
        Icon(
          imageVector = Icons.Rounded.ArrowUpward,
          contentDescription = null,
          modifier = Modifier.size(GalleryDimens.BadgeMiniGlyph),
          tint = GalleryOnPhoto.Content,
        )
      }
    }
    if (labeled) {
      Text(
        text = description,
        modifier = Modifier.clearAndSetSemantics { },
        style = badgeTextStyle(),
        color = GalleryOnPhoto.Content,
        maxLines = 1,
      )
    }
  }
}

/**
 * Errore: l'unico badge pieno, con la coppia `error`/`onError` dello schema (un colore con il suo
 * contenuto, mai separati) e un anello bianco che lo stacca da una foto dello stesso rosso.
 */
@Composable
private fun FailedBadge(description: String, modifier: Modifier, labeled: Boolean) {
  val scheme = MaterialTheme.colorScheme
  Row(
    modifier = modifier
      .height(GalleryDimens.BadgeHeight)
      .defaultMinSize(minWidth = GalleryDimens.BadgeHeight)
      .clip(FluidCapsuleShape)
      .background(scheme.error)
      .border(width = BadgeRingStroke, color = GalleryOnPhoto.Content, shape = FluidCapsuleShape)
      .padding(horizontal = if (labeled) BadgeLabeledPadding else BadgeIconPadding)
      .semantics { contentDescription = description },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(BadgeGlyphGap, Alignment.CenterHorizontally),
  ) {
    Icon(
      imageVector = Icons.Rounded.PriorityHigh,
      contentDescription = null,
      modifier = Modifier.size(GalleryDimens.BadgeGlyph),
      tint = scheme.onError,
    )
    if (labeled) {
      Text(
        text = description,
        modifier = Modifier.clearAndSetSemantics { },
        style = badgeTextStyle(),
        color = scheme.onError,
        maxLines = 1,
      )
    }
  }
}

/**
 * Piu' icone in **una** pillola sola: preferito, condiviso, versione modificata... Una cella con tre
 * badge accanto ne disegnerebbe tre, e tre tondini sopra una foto sono rumore; una pillola e' un
 * segno. I badge che non sono solo un'icona (durata, avanzamento, errore) restano a parte.
 */
@Composable
fun GalleryBadgeCluster(
  badges: List<GalleryBadge>,
  modifier: Modifier = Modifier,
) {
  val icons = badges.filter { it.isIconOnly }
  if (icons.isEmpty()) return
  Row(
    modifier = modifier
      .height(GalleryDimens.BadgeHeight)
      .defaultMinSize(minWidth = GalleryDimens.BadgeHeight)
      .clip(FluidCapsuleShape)
      .background(GalleryOnPhoto.scrim())
      .border(width = BadgeHairline, color = GalleryOnPhoto.Content.copy(alpha = HairlineAlpha), shape = FluidCapsuleShape)
      .padding(horizontal = BadgeIconPadding),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(BadgeClusterGap, Alignment.CenterHorizontally),
  ) {
    icons.forEach { badge ->
      badge.glyph?.let { glyph ->
        Icon(
          imageVector = glyph,
          contentDescription = badge.description(),
          modifier = Modifier.size(GalleryDimens.BadgeGlyph),
          tint = GalleryOnPhoto.Content,
        )
      }
    }
  }
}

/**
 * Un anello di avanzamento: una traccia e un arco che parte da ore dodici. Usato dal badge di
 * caricamento e dall'indicatore di stato globale.
 */
@Composable
fun GalleryProgressRing(
  progress: Float,
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.primary,
  trackColor: Color = color.copy(alpha = RingTrackAlpha),
  strokeWidth: Dp = RingStroke,
) {
  Canvas(modifier = modifier) {
    val stroke = strokeWidth.toPx()
    val inset = stroke / 2f
    val arcSize = Size(size.width - stroke, size.height - stroke)
    val topLeft = Offset(inset, inset)
    drawArc(
      color = trackColor,
      startAngle = 0f,
      sweepAngle = FullCircle,
      useCenter = false,
      topLeft = topLeft,
      size = arcSize,
      style = Stroke(width = stroke),
    )
    val fraction = progress.coerceIn(0f, 1f)
    if (fraction > 0f) {
      drawArc(
        color = color,
        startAngle = StartAtTop,
        sweepAngle = FullCircle * fraction,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
      )
    }
  }
}

private val BadgeIconPadding = 4.dp
private val BadgeLabeledPadding = 7.dp
private val BadgeGlyphGap = 3.dp
private val BadgeClusterGap = 4.dp
private val BadgeRingSize = 18.dp
private val BadgeRingStroke = 1.5.dp
private val BadgeHairline = 0.5.dp
private const val HairlineAlpha = 0.28f
private val RingStroke = 2.dp
private const val RingTrackAlpha = 0.28f
private const val FullCircle = 360f
private const val StartAtTop = -90f
