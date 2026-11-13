package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat
import dev.pampa.mycloudgallery.feature.viewer.menu.MediaAnalysis
import dev.pampa.mycloudgallery.feature.viewer.menu.MediaAnalysisHold
import dev.pampa.mycloudgallery.feature.viewer.menu.MediaAnalysisKind

// Pacchetto X (12/11/2026), grafica XU: lo stato dell'analisi del media aperto nel pannello informazioni. Una capsula sola, con **la piastrella
// dell'analisi** (la foto con la lente, nella tinta della ricerca: la stessa icona della pagina "Analisi delle foto" e della nuvola) e **il segno di
// stato** appoggiato sull'angolo, dallo stesso vocabolario della testata: gia' pronta = la spunta, in coda = la clessidra, in analisi adesso = le
// frecce che girano, non si fa da qui = il trattino, manca il modello = il punto esclamativo. Accanto due parole; il perche' (una richiesta che non
// parte subito) in una riga sotto. In coda la capsula **e' il tasto**: dice «Analizza adesso» con la freccia; un tocco la fa passare davanti.
//
// Il riscontro a fine analisi: quando il segno cambia (da «in analisi» a «pronta») la spunta **entra con un piccolo rimbalzo** (una molla di
// FluidMotion, una volta; ferma con il movimento ridotto) e il visore da' un tocco aptico leggero. Nessun foglio, nessuna frase.

/** La capsula dell'analisi: le parole ([note]) e il segno di stato ([mark]). */
@Immutable
internal class AnalysisBadge(val note: GalleryFooterNote, val mark: GalleryIconStatus)

/** La capsula per [media], o null se non c'e' niente da dire. [video] sceglie "pronta"/"pronto". */
internal fun analysisBadge(format: ViewerFormat, media: MediaAnalysis, video: Boolean): AnalysisBadge? =
  analysisNote(format, media, video)?.let { AnalysisBadge(it, analysisMark(media.kind)) }

/** Il segno di stato di un caso (un vocabolario solo con la testata dell'analisi e la nuvola). */
internal fun analysisMark(kind: MediaAnalysisKind): GalleryIconStatus = when (kind) {
  MediaAnalysisKind.READY -> GalleryIconStatus.Done
  MediaAnalysisKind.QUEUED, MediaAnalysisKind.FIRST -> GalleryIconStatus.Waiting
  MediaAnalysisKind.ANALYZING -> GalleryIconStatus.Running
  MediaAnalysisKind.MODEL_MISSING -> GalleryIconStatus.Attention
  MediaAnalysisKind.UNSUPPORTED, MediaAnalysisKind.NOT_HERE, MediaAnalysisKind.UNKNOWN -> GalleryIconStatus.Stopped
}

/** Le parole della capsula per [media] (e la frase per TalkBack), o null se non c'e' niente da dire. */
internal fun analysisNote(format: ViewerFormat, media: MediaAnalysis, video: Boolean): GalleryFooterNote? {
  val res = format.resources
  fun note(tone: GalleryFooterTone, label: Int, glyph: ImageVector?, busy: Boolean = false, why: Int? = null): GalleryFooterNote {
    val text = res.getString(label)
    val detail = media.hold?.let { res.getString(holdRes(it)) } ?: why?.let { res.getString(it) }
    val spoken = listOfNotNull(res.getString(R.string.analysis_badge_spoken, text), detail).joinToString(". ")
    return GalleryFooterNote(tone = tone, label = text, glyph = glyph, detail = detail, busy = busy, description = spoken)
  }
  return when (media.kind) {
    MediaAnalysisKind.UNKNOWN -> null
    MediaAnalysisKind.READY -> note(
      GalleryFooterTone.Calm,
      if (video) R.string.analysis_badge_ready_video else R.string.analysis_badge_ready_photo,
      Icons.Rounded.Check,
    )
    MediaAnalysisKind.QUEUED -> note(GalleryFooterTone.Waiting, R.string.analysis_badge_queued, Icons.Rounded.HourglassTop)
    MediaAnalysisKind.FIRST -> note(
      GalleryFooterTone.Working,
      if (video) R.string.analysis_badge_first_video else R.string.analysis_badge_first_photo,
      Icons.Rounded.HourglassTop,
    )
    MediaAnalysisKind.ANALYZING -> note(GalleryFooterTone.Working, R.string.analysis_badge_analyzing, Icons.Rounded.Sync, busy = true)
    // Giro 2 (XU-13): il perche' in una riga.
    MediaAnalysisKind.UNSUPPORTED -> note(GalleryFooterTone.Waiting, R.string.analysis_badge_unsupported, Icons.Rounded.Remove, why = R.string.analysis_unsupported_why)
    MediaAnalysisKind.MODEL_MISSING -> note(GalleryFooterTone.Attention, R.string.analysis_badge_model_missing, Icons.Rounded.Download)
    MediaAnalysisKind.NOT_HERE -> note(
      GalleryFooterTone.Waiting,
      if (video) R.string.analysis_badge_not_here_video else R.string.analysis_badge_not_here,
      Icons.Rounded.Remove,
    )
  }
}

private fun holdRes(hold: MediaAnalysisHold): Int = when (hold) {
  MediaAnalysisHold.HOT -> R.string.analysis_hold_hot
  MediaAnalysisHold.NAS -> R.string.analysis_hold_nas
  MediaAnalysisHold.PREPARATION -> R.string.analysis_hold_preparation
  MediaAnalysisHold.RULES -> R.string.analysis_hold_rules
  MediaAnalysisHold.WIFI -> R.string.analysis_hold_wifi
}

/**
 * La capsula: la piastrella dell'analisi con il segno, le due parole (o «Analizza adesso» con il ▶, se [onClick] c'e'), il perche' sotto. Un
 * nodo solo per TalkBack: lo stato e, se si tocca, l'azione.
 */
@Composable
internal fun AnalysisCapsule(badge: AnalysisBadge, onClick: (() -> Unit)?, actionLabel: String, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  // In coda la capsula e' un tasto tonale (il colore dei tasti tonali dell'app); negli altri casi un'etichetta del colore dei gruppi.
  val ground = if (onClick != null) scheme.primaryContainer else scheme.surfaceContainerHigh
  val ink = if (onClick != null) scheme.onPrimaryContainer else scheme.onSurface
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  // Il segno nuovo entra con un piccolo rimbalzo (la spunta a fine analisi), una volta; letto nel livello, nessuna ricomposizione a ogni passo.
  val pop = remember { Animatable(1f) }
  val first = remember { booleanArrayOf(true) }
  LaunchedEffect(badge.mark) {
    if (first[0] || reduced) {
      first[0] = false
      pop.snapTo(1f)
      return@LaunchedEffect
    }
    pop.snapTo(PopFrom)
    pop.animateTo(1f, FluidMotion.fluid())
  }
  val spoken = listOfNotNull(badge.note.description ?: badge.note.label, if (onClick != null) actionLabel else null).joinToString(". ")
  val press = if (onClick != null) Modifier.fluidPressable(onClick = onClick, role = Role.Button, focusShape = FluidCapsuleShape) else Modifier
  Column(verticalArrangement = Arrangement.spacedBy(AnalysisCapsuleDimens.LineGap), modifier = modifier) {
    Row(
      modifier = Modifier
        .then(press)
        .defaultMinSize(minHeight = AnalysisCapsuleDimens.Height)
        .clip(FluidCapsuleShape)
        .background(ground)
        .padding(start = AnalysisCapsuleDimens.Start, end = if (onClick != null) AnalysisCapsuleDimens.EndWithAction else AnalysisCapsuleDimens.End)
        .clearAndSetSemantics {
          contentDescription = spoken
          if (onClick != null) role = Role.Button
        }
        .testTag(AnalysisCapsuleTag),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(AnalysisCapsuleDimens.Gap),
    ) {
      GalleryIconTile(
        icon = Icons.Rounded.ImageSearch,
        family = GalleryIconFamily.Search,
        size = AnalysisCapsuleDimens.Tile,
        status = badge.mark,
        ring = ground,
        statusDescription = null,
        modifier = Modifier.graphicsLayer {
          scaleX = pop.value
          scaleY = pop.value
        },
      )
      // XU2-3 (giro 4): quando si tocca, il ▶ di «Analizza ora» della testata dopo le parole (la stessa icona per la stessa azione): chi non legge
      // distingue il tasto dalla capsula del backup accanto, dello stesso colore. Parole e ▶ piu' vicini fra loro che alla piastrella.
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AnalysisCapsuleDimens.ActionGap)) {
        Text(
          text = if (onClick != null) actionLabel else badge.note.label,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = ink,
          maxLines = 2,
          modifier = Modifier.weight(1f, fill = false),
        )
        if (onClick != null) {
          Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, tint = ink, modifier = Modifier.size(AnalysisCapsuleDimens.Action))
        }
      }
    }
    badge.note.detail?.let { detail ->
      Text(
        text = detail,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.padding(start = AnalysisCapsuleDimens.Start).clearAndSetSemantics { },
      )
    }
  }
}

internal const val AnalysisCapsuleTag: String = "visore-analisi"

private const val PopFrom = 0.55f
