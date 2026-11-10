package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRing
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStatus
import dev.pampa.mycloudgallery.core.designsystem.state.galleryStatusStyle
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

// Il piede delle griglie (G2, docs/grafica-progetto.md §12): sotto i piu' recenti, sulla pagina, lo stato della rete e
// del NAS. Non e' vetro: sta nel contenuto, non galleggia (il vetro solo dove galleggia qualcosa, §1.4). Della chrome
// prende la grammatica (capsule, glifo e testo pieno, la gerarchia con taglia e peso), dell'indicatore di stato globale i
// toni: le coppie contenitore/contenuto dello schema che `GalleryContrastTest` verifica su ogni accento.

/** Il tono di una voce del piede: quanto chiede attenzione. Sono i toni dell'indicatore di stato globale. */
enum class GalleryFooterTone {
  /** Tutto a posto (aggiornato, collegato). */
  Calm,

  /** Lavoro in corso (lettura, allineamento). */
  Working,

  /** Fermo per una ragione normale (in prova, a riposo). */
  Waiting,

  /** Da guardare, senza colpa (non raggiungibile, rete non riconosciuta). */
  Attention,

  /** Serve l'utente (lavori fermi, password). */
  Alarm,
}

/**
 * Una voce del piede: la frase breve della capsula, il glifo (o l'avanzamento), e sotto il dettaglio.
 *
 * @property progress 0..1 se l'avanzamento e' noto (anello nella capsula e barra sotto), null se no.
 * @property busy lavoro senza un totale: il cerchio che gira al posto del glifo.
 * @property description la frase intera per TalkBack (la capsula e il dettaglio insieme); null = [label] e [detail].
 */
@Immutable
data class GalleryFooterNote(
  val tone: GalleryFooterTone,
  val label: String,
  val glyph: ImageVector? = null,
  val detail: String? = null,
  val progress: Float? = null,
  val busy: Boolean = false,
  val description: String? = null,
)

/**
 * La **capsula** di una voce del piede: tonale, alta 36 dp dentro un bersaglio di 48, con il glifo e la frase. Se [onClick]
 * c'e' (i dettagli in Diagnostica, la password, WireGuard) e' un tasto; se no e' solo un'etichetta.
 *
 * [trailing]: un glifo in coda alla frase, nel colore del glifo. Nel pop-up della nuvola le capsule che si toccano portano la
 * freccia (GF-12, review della beta): accanto a quelle dei problemi, uguali ma ferme, non si capiva quali fossero tasti.
 */
@Composable
fun GalleryFooterPill(note: GalleryFooterNote, onClick: (() -> Unit)?, modifier: Modifier = Modifier, trailing: ImageVector? = null) {
  val style = galleryFooterStyle(note.tone, MaterialTheme.colorScheme)
  val container by animateColorAsState(style.container, FluidMotion.color(), label = "piede contenitore")
  val text by animateColorAsState(style.text, FluidMotion.color(), label = "piede testo")
  val glyphColor by animateColorAsState(style.glyph, FluidMotion.color(), label = "piede glifo")
  val spoken = note.description ?: listOfNotNull(note.label, note.detail).joinToString(". ")
  val press = if (onClick != null) {
    Modifier.fluidPressable(onClick = onClick, role = Role.Button, focusShape = FluidCapsuleShape)
  } else {
    Modifier
  }
  Box(
    modifier = modifier
      .defaultMinSize(minWidth = GalleryDimens.MinTouchTarget, minHeight = GalleryDimens.MinTouchTarget)
      .semantics(mergeDescendants = true) { contentDescription = spoken }
      .then(press),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier
        .defaultMinSize(minHeight = PillHeight)
        .clip(FluidCapsuleShape)
        .background(container)
        .padding(horizontal = PillPaddingH),
      horizontalArrangement = Arrangement.spacedBy(PillGap),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FooterGlyph(note, glyphColor)
      Text(
        text = note.label,
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      if (trailing != null) Icon(imageVector = trailing, contentDescription = null, modifier = Modifier.size(GlyphSize), tint = glyphColor)
    }
  }
}

/**
 * Una voce di stato: la capsula e, sotto, il dettaglio. Centrata in fondo a una griglia; allineata al testo ([alignment]
 * `Start`) in cima a una pagina di elenco (le Raccolte), dove lo stato e' la prima riga.
 */
@Composable
fun GalleryFooterEntry(
  note: GalleryFooterNote,
  onClick: (() -> Unit)?,
  modifier: Modifier = Modifier,
  alignment: Alignment.Horizontal = Alignment.CenterHorizontally,
) {
  Column(modifier = modifier, horizontalAlignment = alignment, verticalArrangement = Arrangement.spacedBy(EntryGap)) {
    GalleryFooterPill(note = note, onClick = onClick)
    GalleryFooterDetail(note, alignment = alignment)
  }
}

/** Il dettaglio sotto la capsula: la barra dell'avanzamento (se c'e') e la frase, centrati, larghi al piu' una colonna. */
@Composable
fun GalleryFooterDetail(note: GalleryFooterNote, modifier: Modifier = Modifier, alignment: Alignment.Horizontal = Alignment.CenterHorizontally) {
  if (note.detail == null && note.progress == null) return
  Column(
    modifier = modifier.widthIn(max = DetailMaxWidth),
    horizontalAlignment = alignment,
    verticalArrangement = Arrangement.spacedBy(DetailGap),
  ) {
    note.progress?.let { p -> FluidProgressBar(progress = { p }, modifier = Modifier.widthIn(max = BarMaxWidth)) }
    note.detail?.let { detail ->
      Text(
        text = detail,
        // Il secondo rigo: piu' piccolo e nel colore secondario del tema, ma di peso medio, cosi' a 13 sp il glifo resta
        // pieno (misurato sull'emulatore: il regolare sulla pagina chiara scendeva a 4,6:1).
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (alignment == Alignment.Start) TextAlign.Start else TextAlign.Center,
      )
    }
  }
}

@Composable
private fun FooterGlyph(note: GalleryFooterNote, color: Color) {
  when {
    note.progress != null -> GalleryProgressRing(
      progress = note.progress,
      modifier = Modifier.size(GlyphSize),
      color = color,
      trackColor = color.copy(alpha = RingTrackAlpha),
    )
    note.busy -> FluidSpinner(size = GlyphSize, color = color)
    note.glyph != null -> Icon(imageVector = note.glyph, contentDescription = null, modifier = Modifier.size(GlyphSize), tint = color)
  }
}

/** I colori di un tono: il contenitore, il testo e il glifo. Le stesse coppie dell'indicatore di stato globale. */
@Immutable
internal data class FooterStyle(val container: Color, val text: Color, val glyph: Color)

/** Il tono risolto da uno schema (pura: il contrasto di ogni coppia si verifica senza comporre, `GalleryFooterStatusTest`). */
internal fun galleryFooterStyle(tone: GalleryFooterTone, scheme: ColorScheme): FooterStyle {
  val status = when (tone) {
    GalleryFooterTone.Calm -> GalleryStatus.AllSafe
    GalleryFooterTone.Working -> GalleryStatus.BackingUp(0, 1)
    GalleryFooterTone.Waiting -> GalleryStatus.WaitingForCharge
    GalleryFooterTone.Attention -> GalleryStatus.NasUnreachable
    GalleryFooterTone.Alarm -> GalleryStatus.Error("")
  }
  val s = galleryStatusStyle(status, scheme)
  return FooterStyle(container = s.container, text = s.text, glyph = s.glyph)
}

private val PillHeight: Dp = 36.dp
private val PillPaddingH: Dp = 12.dp
private val PillGap: Dp = 8.dp
private val GlyphSize: Dp = 18.dp
private val DetailMaxWidth: Dp = 320.dp
private val BarMaxWidth: Dp = 220.dp
private val DetailGap: Dp = 6.dp
private val EntryGap: Dp = 2.dp
private const val RingTrackAlpha = 0.28f
