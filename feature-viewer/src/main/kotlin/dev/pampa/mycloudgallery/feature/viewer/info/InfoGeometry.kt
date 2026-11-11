package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.util.lerp
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryPaneRules
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryWindow
import dev.pampa.mycloudgallery.feature.viewer.page.ContentLayer
import kotlin.math.max
import kotlin.math.min

/**
 * La geometria del pannello informazioni (H2), in funzioni pure: si prova senza schermo. E' la descrizione dell'utente (31/10, 11/11), che
 * prende a riferimento la Galleria di Samsung.
 *
 * Sul telefono, scena alta `H`, il contenuto a riposo e' la foto intera nella scena (`content`, centrata: sopra e sotto possono restare due
 * fasce vuote). Il pannello aperto e' alto **esattamente `H/2`**: il suo bordo alto sta a meta' schermo, qualunque cosa contenga.
 *  1. **Il pannello sale e occupa prima la fascia vuota** sotto il contenuto: finche' il suo bordo alto non tocca il bordo basso del
 *     contenuto, il contenuto non si muove e non si rimpicciolisce.
 *  2. **Poi il bordo del pannello e il bordo del contenuto restano attaccati**: il contenuto sale con il pannello, alla stessa misura, senza
 *     rimpicciolirsi. Un contenuto verticale o di proporzioni normali (piu' alto di meta' schermo) a pannello aperto ha il bordo basso a meta'
 *     schermo e la parte alta fuori dallo schermo.
 *  3. **Un contenuto molto orizzontale** (piu' basso di meta' schermo) si ingrandisce un poco mentre sale, quanto basta a **riempire la meta'
 *     alta** (ritagliato ai lati), fino a [MaxFillScale]: un panorama non diventa una striscia irriconoscibile.
 *  4. **Un pannello lungo** (le informazioni non stanno in meta' schermo) cresce oltre la meta' scorrendo, e porta con se' il contenuto che
 *     resta attaccato al bordo: e' lo stesso conto, con il bordo piu' in alto.
 * Tutto e' continuo: le fasi si toccano nello stesso punto (stessa posizione, stessa scala), e tornando indietro si ripercorrono.
 */
internal object InfoGeometry {

  /** Il pannello aperto occupa questa frazione dell'altezza: la meta' esatta. */
  const val OpenFraction: Float = 0.5f

  /** Quanto si ingrandisce al piu' un contenuto molto orizzontale per riempire la meta' alta: oltre, un panorama mostrerebbe un dettaglio. */
  const val MaxFillScale: Float = 2f

  /** Un pannello laterale prende al piu' questa frazione della larghezza (il resto e' del contenuto). */
  const val MaxSideFraction: Float = 0.5f

  /** Sotto questa larghezza (dp) una finestra bassa non ha posto per una colonna: resta il foglio dal basso. */
  const val SideMinWidthDp: Int = 600

  /** La corsa del pannello dal chiuso all'aperto: meta' scena. */
  fun travel(stageHeight: Float): Float = stageHeight * OpenFraction

  /**
   * Il bordo alto del pannello (coordinate della scena): [progress] 0 = chiuso (sotto lo schermo), 1 = aperto (a meta'); [lift] e' quanto un
   * pannello lungo e' cresciuto oltre la meta' scorrendo.
   */
  fun panelTop(stageHeight: Float, progress: Float, lift: Float = 0f): Float =
    stageHeight - progress.coerceIn(0f, 1f) * travel(stageHeight) - lift.coerceAtLeast(0f)

  /**
   * Dove sta il contenuto con il bordo del pannello a [panelTop]. [content] e' il contenuto a riposo nelle coordinate della scena (la foto
   * intera, `fitRect`). Lo schermo vede `scale * locale + translation`.
   */
  fun contentLayer(stage: Size, content: Rect, panelTop: Float): ContentLayer {
    if (content.isEmpty || stage.height <= 0f) return ContentLayer.Identity
    val bottom = content.bottom
    if (panelTop >= bottom) return ContentLayer.Identity
    val half = stage.height * OpenFraction
    val height = content.height
    // Quanto il contenuto deve crescere per riempire la meta' alta (1 per un contenuto alto almeno meta' schermo).
    val fill = if (height < half) min(half / height, MaxFillScale) else 1f
    // La crescita va con la corsa: 0 quando il pannello tocca il contenuto, 1 quando il bordo arriva a meta' schermo (e oltre resta 1).
    val span = bottom - half
    val t = if (span <= 1f) 1f else ((bottom - panelTop) / span).coerceIn(0f, 1f)
    val scale = lerp(1f, fill, t)
    // Scala intorno al centro del bordo basso, e il bordo basso sul bordo del pannello.
    val centerX = content.center.x
    return ContentLayer(scale = scale, translation = Offset(centerX * (1f - scale), panelTop - scale * bottom))
  }

  /**
   * La colonna laterale (tablet, telefono in orizzontale): il contenuto si fa piu' piccolo quanto basta a stare nello spazio che resta a
   * sinistra, al centro di quello spazio, con l'orologio del pannello ([progress]).
   */
  fun sideLayer(stage: Size, content: Rect, progress: Float, panelWidth: Float): ContentLayer {
    if (content.isEmpty || stage.width <= 0f || progress <= 0f) return ContentLayer.Identity
    val room = (stage.width - panelWidth).coerceAtLeast(1f)
    val fit = min(1f, min(room / content.width, stage.height / content.height))
    val scale = lerp(1f, fit, progress)
    val target = Offset(room / 2f, stage.height / 2f)
    val center = Offset(lerp(content.center.x, target.x, progress), lerp(content.center.y, target.y, progress))
    return ContentLayer(scale = scale, translation = Offset(center.x - scale * content.center.x, center.y - scale * content.center.y))
  }

  /**
   * Il pannello sta di lato e non sotto: da 840 dp (la colonna di sempre) e nelle finestre basse e larghe (il telefono in orizzontale), dove
   * meta' altezza sarebbe una striscia inservibile.
   */
  fun side(window: GalleryWindow): Boolean =
    GalleryPaneRules.infoBeside(window) || (window.shortHeight && window.widthDp >= SideMinWidthDp)
}

/**
 * Dove si trova il pannello dopo un passo del dito: [progress] (0 chiuso, 1 aperto) e [offset] (quanto si e' scorso un pannello lungo).
 * Funzione pura di [InfoPanelState.drag].
 */
@Immutable
internal data class InfoDragStep(val progress: Float, val offset: Float)

internal object InfoScroll {

  /**
   * Un passo del dito di [dy] pixel (positivo = verso il basso) su un pannello alto [travel] di corsa e lungo [maxOffset] oltre la meta'.
   * Verso l'alto **prima si apre** (fino ad aperto), **poi scorre** (il pannello cresce e porta il contenuto, poi le informazioni scorrono
   * dentro); verso il basso **prima torna in cima**, **poi si chiude**. Un solo gesto per tutto, senza scatti fra un tratto e l'altro.
   */
  fun step(progress: Float, offset: Float, dy: Float, travel: Float, maxOffset: Float): InfoDragStep {
    if (travel <= 0f) return InfoDragStep(progress, offset)
    var p = progress.coerceIn(0f, 1f)
    var o = offset.coerceIn(0f, max(0f, maxOffset))
    var rest = dy
    if (rest < 0f) {
      val opening = min(-rest, (1f - p) * travel)
      p += opening / travel
      rest += opening
      if (rest < 0f && p >= 1f) o = min(o - rest, max(0f, maxOffset))
    } else if (rest > 0f) {
      val back = min(rest, o)
      o -= back
      rest -= back
      if (rest > 0f) p = (p - rest / travel).coerceAtLeast(0f)
    }
    return InfoDragStep(p.coerceIn(0f, 1f), o)
  }

  /** Di quanto il pannello e' cresciuto oltre la meta' per uno scorrimento [offset]: al piu' [maxLift], poi scorrono le informazioni. */
  fun lift(offset: Float, maxLift: Float): Float = offset.coerceIn(0f, max(0f, maxLift))

  /** Quanto scorrono le informazioni dentro il pannello (oltre la crescita). */
  fun inner(offset: Float, maxLift: Float): Float = (offset - lift(offset, maxLift)).coerceAtLeast(0f)
}
