package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.ui.semantics.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

// LA «i» DELLA GALLERIA (fase I, «una sola grammatica»: una sola «i»). Il perche' di uno stato, una regola, un'istruzione lunga stanno dietro la «i»:
// un tocco apre la frase **sotto, nella pagina** (non un foglio: un foglio e' per decidere), un altro tocco la richiude. La «i» e' vuota da chiusa e
// piena da aperta. Tre pezzi: il segno ([GalleryInfoGlyph], per una riga intera che si tocca, come il titolo o la capsula del pop-up), il tasto da
// 48 dp ([GalleryInfoButton], quando la «i» sta da sola accanto a un testo) e la frase ([GalleryInfoDetail]). La frase non cambia misura con
// un'animazione: sta spesso su un vetro, dove un'animazione di misura costa (regola del vetro). TalkBack legge il perche' nella descrizione di chi lo
// mostra, senza tocco: la frase aperta non si rilegge.

/** Le misure della «i». */
object GalleryInfoDefaults {
  /** Il segno dentro una riga di testo. */
  val Glyph: Dp = 18.dp

  /** Il segno a sinistra di una riga della «i» e lo spazio fra i pezzi. */
  val RowGlyph: Dp = 20.dp
  val RowGap: Dp = 8.dp
}

/** Il segno «i»: vuoto da chiuso, pieno da aperto. Decorativo: la riga che lo contiene dice che cosa apre. */
@Composable
fun GalleryInfoGlyph(open: Boolean, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
  Icon(
    imageVector = if (open) Icons.Rounded.Info else Icons.Outlined.Info,
    contentDescription = null,
    tint = tint,
    modifier = modifier.size(GalleryInfoDefaults.Glyph),
  )
}

/**
 * La «i» da sola: un tasto da 48 dp con il segno. [label] dice a TalkBack che cosa spiega («Perche' in pausa»); lo stato aperto/chiuso lo dice
 * [openState]/[closedState] (le parole le da' chi lo usa, nella sua lingua).
 */
@Composable
fun GalleryInfoButton(
  label: String,
  open: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
  openState: String? = null,
  closedState: String? = null,
  tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  Box(
    modifier = modifier
      .size(GalleryDimens.MinTouchTarget)
      .semantics {
        contentDescription = label
        role = Role.Button
        (if (open) openState else closedState)?.let { stateDescription = it }
      }
      .fluidPressable(onClick = onToggle, role = Role.Button, focusShape = FluidCapsuleShape)
      .testTag(GalleryInfoButtonTag),
    contentAlignment = Alignment.Center,
  ) {
    GalleryInfoGlyph(open = open, tint = tint)
  }
}

/** La frase dietro la «i», quando e' aperta. Si vede; TalkBack la sente gia' nella descrizione di chi la apre. */
@Composable
fun GalleryInfoDetail(
  text: String,
  visible: Boolean,
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.onSurface,
  textAlign: TextAlign? = null,
) {
  if (!visible) return
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
    color = color,
    textAlign = textAlign,
    modifier = modifier.semantics { hideFromAccessibility() },
  )
}

/** L'etichetta di test del tasto «i» (la frase prende quella di chi la mostra). */
const val GalleryInfoButtonTag: String = "galleria-i"

/**
 * La riga della «i» (CS-21, fase I): il segno facoltativo ([glyph]), le due parole ([label]) e la «i»; tutta la riga si tocca (48 dp) e apre la frase
 * ([detail]) sotto, un altro tocco la richiude. TalkBack sente «[label]. [detail]» come un tasto, senza tocco. E' la riga che prima ogni pagina scriveva
 * a mano (la nota del backup, le impostazioni, il cestino del NAS, gli stati vuoti, le persone, l'onboarding).
 *
 * @param glyph il segno a sinistra (lo stato, il tempo); null = nessuno.
 * @param glyphTint il colore del segno (un avviso lo vuole nel colore dell'errore); la «i» e le parole hanno sempre le tinte comuni.
 * @param labelStyle il corpo delle due parole.
 * @param spoken quello che sente TalkBack; di serie «[label]. [detail]».
 * @param initiallyOpen aperta al primo disegno (gli screenshot della frase aperta).
 */
@Composable
fun GalleryInfoRow(
  label: String,
  detail: String,
  modifier: Modifier = Modifier,
  glyph: ImageVector? = null,
  glyphTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
  labelStyle: TextStyle = MaterialTheme.typography.titleSmall,
  spoken: String = "$label. $detail",
  initiallyOpen: Boolean = false,
) {
  var open by rememberSaveable(label, detail) { mutableStateOf(initiallyOpen) }
  Column(modifier = modifier) {
    Row(
      modifier = Modifier
        .defaultMinSize(minHeight = GalleryDimens.MinTouchTarget)
        .fluidPressable(onClick = { open = !open }, role = Role.Button, focusShape = FluidCapsuleShape)
        .clearAndSetSemantics {
          contentDescription = spoken
          role = Role.Button
          testTag = GalleryInfoRowTag
        },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(GalleryInfoDefaults.RowGap),
    ) {
      if (glyph != null) Icon(imageVector = glyph, contentDescription = null, tint = glyphTint, modifier = Modifier.size(GalleryInfoDefaults.RowGlyph))
      Text(text = label, style = labelStyle, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
      GalleryInfoGlyph(open = open)
    }
    GalleryInfoDetail(text = detail, visible = open, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** L'etichetta di test della riga della «i». */
const val GalleryInfoRowTag: String = "galleria-i-riga"
