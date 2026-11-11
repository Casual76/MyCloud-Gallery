package dev.pampa.mycloudgallery.core.designsystem.grid

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.util.lerp
import kotlin.math.min
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryCellDefaults

// I dischi di presenza di una riga di celle, disegnati **dalla riga** e non da ogni cella (Tab S9, 04/11).
//
// Il disco della presenza (telefono, NAS, al sicuro...) sta quasi su ogni cella: sono tre disegni a cella (il fondo, il filo e il glifo).
// Se ogni cella li disegna subito dopo la sua foto, le foto e i dischi si alternano nel flusso di disegno e la GPU non puo' unire i dischi
// fra loro (ogni foto sta in mezzo e li copre): a "Anni" del tablet, 16 celle per riga e una decina di righe, sono quasi 500 disegni in piu'
// a fotogramma. Disegnati tutti dopo le foto della riga e uno dopo l'altro (prima i fondi, poi i fili, poi i glifi) si uniscono in pochi
// disegni. Cio' che si vede e' identico: stessi dischi, stesse misure ([badgeSlots]), stesso ordine sopra la foto, e i dischi non si
// sovrappongono mai fra loro.

/**
 * La presenza delle celle di una riga: per ogni cella il suo stato (null = nessun disco), dove sta e quanto e' grande (pixel della riga), e la
 * sua chiave (la pressione di una cella rimpicciolisce anche il suo disco, come quando lo disegnava la cella).
 * Si confronta per contenuto: una riga ricomposta con gli stessi dati non ridisegna.
 */
@Immutable
class GalleryRowPresence(
  internal val kinds: Array<GalleryPresence?>,
  internal val lefts: IntArray,
  internal val widths: IntArray,
  internal val height: Int,
  internal val keys: Array<Any>,
) {
  init {
    require(kinds.size == lefts.size && kinds.size == widths.size && kinds.size == keys.size) { "Una voce per cella." }
  }

  /** Vero se almeno una cella della riga ha uno stato di presenza (poi, con [drawsAt], se la cella e' abbastanza grande per mostrarlo). */
  val any: Boolean get() = kinds.any { it != null }

  /** Vero se almeno un disco si vede con lo stile [style]: sotto il lato minimo dei segni (il livello Anni del telefono) la cella non ne ha. */
  internal fun drawsAt(style: GalleryGridCellStyle): Boolean {
    for (i in kinds.indices) if (kinds[i] != null && min(widths[i], height).toFloat() >= style.badgesMinSide) return true
    return false
  }

  override fun equals(other: Any?): Boolean =
    other is GalleryRowPresence && other.height == height && other.kinds.contentEquals(kinds) &&
      other.lefts.contentEquals(lefts) && other.widths.contentEquals(widths) && other.keys.contentEquals(keys)

  override fun hashCode(): Int {
    var h = height
    h = 31 * h + kinds.contentHashCode()
    h = 31 * h + lefts.contentHashCode()
    h = 31 * h + widths.contentHashCode()
    return 31 * h + keys.contentHashCode()
  }
}

/**
 * I dischi di presenza di una riga ([presence]), sopra le sue celle (che per questo si disegnano senza il disco). Va **dopo** le celle fra i
 * figli della riga, alto e largo come lei; non ha semantica ne' tocchi (la presenza la dice gia' la descrizione della cella). Sta su un
 * layer suo: cambia solo se cambia la riga.
 */
@Composable
fun GalleryGridRowPresence(
  presence: GalleryRowPresence,
  style: GalleryGridCellStyle,
  interaction: GalleryGridInteractionState,
  modifier: Modifier = Modifier,
) {
  // Niente disco da vedere (celle troppo piccole per i segni): niente nodo.
  if (!presence.drawsAt(style)) return
  Spacer(modifier.graphicsLayer { }.then(GalleryRowPresenceElement(presence, style, interaction)))
}

private class GalleryRowPresenceElement(
  val presence: GalleryRowPresence,
  val style: GalleryGridCellStyle,
  val interaction: GalleryGridInteractionState,
) : ModifierNodeElement<GalleryRowPresenceNode>() {

  override fun create(): GalleryRowPresenceNode = GalleryRowPresenceNode(presence, style, interaction)

  override fun update(node: GalleryRowPresenceNode) {
    val changed = node.presence != presence || node.style !== style || node.interaction !== interaction
    node.presence = presence
    node.style = style
    node.interaction = interaction
    if (changed) node.invalidateDraw()
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "galleryGridRowPresence"
  }

  override fun equals(other: Any?): Boolean =
    other is GalleryRowPresenceElement && other.presence == presence && other.style === style && other.interaction === interaction

  override fun hashCode(): Int {
    var h = presence.hashCode()
    h = 31 * h + System.identityHashCode(style)
    return 31 * h + System.identityHashCode(interaction)
  }
}

private class GalleryRowPresenceNode(
  var presence: GalleryRowPresence,
  var style: GalleryGridCellStyle,
  var interaction: GalleryGridInteractionState,
) : DrawModifierNode, Modifier.Node() {

  override fun ContentDrawScope.draw() {
    // Letti qui, nel disegno: la pressione di una cella ridisegna le righe (come ridisegnava la cella), niente altro.
    val pressedKey = interaction.pressedKey
    val pressAmount = if (pressedKey != null) interaction.pressProgress.value.coerceIn(0f, 1f) else 0f
    drawPresence(presence, style, pressedKey, pressAmount, interaction.hiddenKey)
  }
}

/** Le tre passate: il fondo di ogni disco, poi il filo, poi il glifo. Ogni passata e' una serie di disegni uguali, che la GPU unisce. */
private fun DrawScope.drawPresence(p: GalleryRowPresence, s: GalleryGridCellStyle, pressedKey: Any?, pressAmount: Float, hiddenKey: Any? = null) {
  val rects = Array(p.kinds.size) { i ->
    p.kinds[i]?.let { kind -> badgeSlots(p.widths[i].toFloat(), p.height.toFloat(), s.metrics, 0, null, kind).presence }
  }
  val pressScale = lerp(1f, GalleryCellDefaults.PressedScale, pressAmount)
  for (pass in 0 until Passes) {
    for (i in p.kinds.indices) {
      val kind = p.kinds[i] ?: continue
      val rect = rects[i] ?: continue
      // CU-1: il disco della cella vuota (la foto del viewer ci sta rientrando) non si disegna.
      if (hiddenKey != null && p.keys[i] == hiddenKey) continue
      val pressed = pressAmount > 0f && pressedKey != null && p.keys[i] == pressedKey
      val cellWidth = p.widths[i].toFloat()
      val cellHeight = p.height.toFloat()
      withTransform({
        translate(p.lefts[i].toFloat(), 0f)
        // La cella premuta rimpicciolisce attorno al suo centro con tutto il contenuto, disco compreso.
        if (pressed) scale(pressScale, pressScale, Offset(cellWidth / 2f, cellHeight / 2f))
        translate(rect.left, rect.top)
      }) {
        val side = Size(rect.width, rect.height)
        val outline = s.presenceOutline(kind.prominent)
        when (pass) {
          FillPass -> drawOutline(outline, s.badgeScrim)
          HairlinePass -> drawOutline(outline, s.badgeHairline, style = Stroke(width = s.hairline))
          else -> {
            val glyph = if (kind.prominent) s.metrics.presenceGlyph else s.metrics.quietGlyph
            withTransform({ translate((side.width - glyph) / 2f, (side.height - glyph) / 2f) }) {
              with(s.presenceGlyphs[kind.ordinal]) { draw(Size(glyph, glyph), colorFilter = s.onPhotoFilter) }
            }
          }
        }
      }
    }
  }
}

private const val Passes = 3
private const val FillPass = 0
private const val HairlinePass = 1
