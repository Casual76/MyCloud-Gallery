package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.currentGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.fluidRowPressable
import dev.pampa.mycloudgallery.core.designsystem.a11y.galleryGlass
import dev.pampa.mycloudgallery.core.designsystem.a11y.gallerySolidFill
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.design.ContentRange
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeDefaults
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryChromeStyles
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryLegibility
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto

/** Una voce del menu ⋮: un'azione, oppure l'interruzione fra due gruppi di azioni. */
@Immutable
sealed interface GalleryMenuEntry {

  /**
   * Un'azione. [destructive] la disegna col colore di errore, e vale al massimo una per menu: e' la
   * voce che cancella, e sta in fondo, dopo un'interruzione di gruppo.
   */
  data class Item(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
  ) : GalleryMenuEntry

  /** L'interruzione fra due gruppi: una banda piena, piu' marcata del filo fra due voci. */
  data object GroupBreak : GalleryMenuEntry
}

/**
 * Il contenuto del menu ⋮: le voci in colonna, un filo fra due voci vicine, una banda fra due gruppi.
 *
 * E' **solo il contenuto**: il vetro lo mette chi lo ospita. Dentro un [GalleryMenuButton] lo mette
 * il pop-up dell'engine (`FluidGlassModalPortal`); fuori, [GalleryMenuSurface] disegna la stessa
 * pellicola. Ogni riga e' alta almeno 48 dp (bersaglio di tocco), l'etichetta sta a sinistra e
 * l'icona a destra come nel menu contestuale dell'engine, la voce distruttiva usa il colore di
 * errore dello schema.
 *
 * @param onItemSelected chiamata subito prima dell'azione di ogni voce abilitata: il menu si chiude.
 * @param destructiveColor il colore della voce distruttiva: di serie l'errore dello schema; sopra una foto quello che regge sul pannello
 *   ([GalleryMenuLegibility.onPhoto]).
 */
@Composable
fun GalleryMenuPanel(
  entries: List<GalleryMenuEntry>,
  modifier: Modifier = Modifier,
  onItemSelected: () -> Unit = {},
  destructiveColor: Color = MaterialTheme.colorScheme.error,
) {
  Column(
    modifier = modifier
      .widthIn(min = MenuMinWidth, max = MenuMaxWidth)
      .width(IntrinsicSize.Max),
  ) {
    entries.forEachIndexed { index, entry ->
      when (entry) {
        is GalleryMenuEntry.Item -> {
          if (entries.getOrNull(index - 1) is GalleryMenuEntry.Item) FluidHairline(startInset = MenuRowInset)
          GalleryMenuRow(entry, onItemSelected, destructiveColor)
        }
        GalleryMenuEntry.GroupBreak -> GroupBand()
      }
    }
  }
}

@Composable
private fun GalleryMenuRow(item: GalleryMenuEntry.Item, onSelected: () -> Unit, destructiveColor: Color) {
  val color = if (item.destructive) destructiveColor else MaterialTheme.colorScheme.onSurface
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = GalleryDimens.MinTouchTarget)
      .semantics(mergeDescendants = true) { role = Role.Button }
      .fluidRowPressable(
        onClick = {
          onSelected()
          item.onClick()
        },
        enabled = item.enabled,
      )
      .padding(horizontal = MenuRowInset, vertical = MenuRowVerticalPadding)
      .alpha(if (item.enabled) 1f else DisabledAlpha),
    horizontalArrangement = Arrangement.spacedBy(MenuRowGap),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = item.label,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.Medium,
      color = color,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    item.icon?.let {
      Icon(
        imageVector = it,
        contentDescription = null,
        modifier = Modifier.size(MenuGlyph),
        tint = color,
      )
    }
  }
}

@Composable
private fun GroupBand() {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(GroupBandHeight)
      .background(MaterialTheme.colorScheme.onSurface.copy(alpha = GroupBandAlpha)),
  )
}

/**
 * La pellicola di vetro del menu, per chi mostra [GalleryMenuPanel] fuori da un pop-up: stesso
 * materiale (`Modal`), stesso raggio (`FluidRadius.Group`) del pannello che l'engine apre da un tasto.
 */
@Composable
fun GalleryMenuSurface(
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val shape = ContinuousCornerShape(FluidRadius.Group)
  Box(
    modifier = modifier
      .galleryGlass(
        state = backdrop,
        tint = GlassDefaults.modalTint(),
        shape = shape,
        role = GlassRole.Modal,
      )
      .clip(shape),
  ) { content() }
}

/**
 * Il tasto ⋮: una lente di vetro nella barra che, toccata, apre il menu **sul tasto stesso** (il
 * pop-up dell'engine cresce dal rettangolo del tasto, e il tasto gli cede il posto).
 *
 * Il pop-up vive alla radice dell'app (`FluidGlassModalHost`): senza un host nello scope il tocco non
 * apre niente, ed e' la degradazione giusta, non un crash. Per il menu in un tasto che si trasforma
 * al *tenere premuto*, e senza separatori, c'e' `FluidBarAction(actions = ...)`. Il ⋮ del viewer, sopra una
 * foto, e' [GalleryViewerMenuButton]: lo stesso pop-up, con il velo e il tasto di vetro tondo.
 */
@Composable
fun GalleryMenuButton(
  entries: List<GalleryMenuEntry>,
  modifier: Modifier = Modifier,
  contentDescription: String = stringResource(R.string.topbar_more),
  backdrop: GlassBackdropState = currentGlassBackdrop(),
) {
  var open by remember { mutableStateOf(false) }
  var bounds by remember { mutableStateOf<Rect?>(null) }
  GalleryBarButton(
    icon = Icons.Rounded.MoreVert,
    contentDescription = contentDescription,
    onClick = { open = true },
    modifier = modifier.onGloballyPositioned { bounds = it.boundsInRoot() },
    backdrop = backdrop,
  )
  FluidGlassModalPortal(
    visible = open,
    onDismissRequest = { open = false },
    origin = { bounds },
    presentation = FluidGlassModalPresentation.Popover,
    paneTitle = contentDescription,
  ) {
    Box(modifier = Modifier.gallerySolidFill()) { GalleryMenuPanel(entries = entries, onItemSelected = { open = false }) }
  }
}

/** Il velo e gli inchiostri del pop-up di un menu sopra una foto: il velo sotto le voci, il testo delle voci, il rosso della voce distruttiva. */
@Immutable
data class GalleryMenuInks(val veil: Color, val ink: Color, val destructive: Color)

/**
 * La leggibilita' del pop-up di un menu che si apre **sopra una foto** (la chrome della Libreria, il ⋮ del viewer), scritta come aritmetica come
 * [GalleryLegibility]: funzioni pure, provate sul caso peggiore (`GalleryMenuLegibilityTest`).
 *
 * Il pannello del pop-up e' la pellicola dei modali dell'engine (`GlassDefaults.modalTint`, la stessa di `panelTint()` senza colore) sopra lo scrim
 * che l'engine stende sotto il pop-up, sopra la foto. Sotto puo' passare il bianco o il nero pieno: nel tema scuro la pellicola grigia al 50% sopra
 * una foto bianca lascia il testo chiaro a 3,2:1 (misurato sull'emulatore) e il rosso dell'errore a 2,2:1. Per questo, sotto le voci, un velo del
 * colore della pagina **quanto basta**. La sfocatura del pop-up media il fondo e aiuta: qui non la si conta, per stare larghi.
 */
object GalleryMenuLegibility {

  /**
   * Il velo per il testo delle voci: `onSurface` a 4,5:1 sul caso peggiore, con il margine di [GalleryChromeDefaults.MeasuredTarget] (la pellicola
   * che l'engine disegna davvero non e' solo la sua tinta). Con le superfici piene (piu' contrasto, meno trasparenza) il velo e' pieno: il pop-up
   * non lascia vedere niente. Con il contrasto di sistema medio o alto (Android 14+) `modalTint()` sale da sola verso il pieno: il velo, calcolato
   * da lei, scende di conseguenza. E' il velo del pop-up della chrome della Libreria.
   */
  fun textVeil(scheme: ColorScheme, film: Color, dark: Boolean, solid: Boolean): Color {
    if (solid) return scheme.surface.copy(alpha = 1f)
    val alpha = GalleryLegibility.minimalFilmAlpha(scheme.onSurface, scheme.surface, worstCase(scheme, film, dark), GalleryChromeDefaults.MeasuredTarget, 0f, VeilCeiling)
    return scheme.surface.copy(alpha = alpha)
  }

  /**
   * Il velo e gli inchiostri del menu ⋮ del viewer (K1, GF-1): oltre al testo, anche la voce distruttiva ("Elimina dal telefono", "Elimina…") a
   * 4,5:1 sul caso peggiore, con lo stesso margine del testo ([DestructiveTarget]). Il rosso va verso il testo al piu' di [DestructiveShift] (resta
   * un rosso: in scuro un rosso chiaro, in chiaro un rosso scuro); il resto lo fa il velo, che cresce quanto serve. Il prezzo e' un pannello piu'
   * coprente di quello della Libreria, che il rosso non ce l'ha. Con le superfici piene il velo e' pieno e il rosso si sposta solo se lo schema non
   * regge da se'.
   */
  fun onPhoto(scheme: ColorScheme, film: Color, dark: Boolean, solid: Boolean): GalleryMenuInks {
    val range = worstCase(scheme, film, dark)
    val text = textVeil(scheme, film, dark, solid)
    val veil = if (solid) {
      text
    } else {
      val shifted = lerp(scheme.error, scheme.onSurface, DestructiveShift)
      val forRed = GalleryLegibility.minimalFilmAlpha(shifted, scheme.surface, range, DestructiveTarget, 0f, VeilCeiling)
      scheme.surface.copy(alpha = maxOf(text.alpha, forRed))
    }
    val destructive = GalleryChromeStyles.legibleTowards(scheme.error, scheme.onSurface, veil, range, DestructiveTarget)
    return GalleryMenuInks(veil = veil, ink = scheme.onSurface, destructive = destructive)
  }

  /**
   * Il fondo del pannello senza velo, sui due casi peggiori (la foto bianca e quella nera): la pellicola [film] sopra lo scrim del pop-up sopra la
   * foto. Opaco: e' cio' su cui il velo si stende.
   */
  fun worstCase(scheme: ColorScheme, film: Color, dark: Boolean): ContentRange {
    val scrim = scheme.scrim.copy(alpha = if (dark) PopoverScrimDark else PopoverScrimLight)
    return ContentRange(listOf(GalleryOnPhoto.Content, scheme.scrim.copy(alpha = 1f)).map { film.compositeOver(scrim.compositeOver(it)) })
  }

  /**
   * Il contrasto con cui si calcola la voce distruttiva: 4,5:1 (testo normale) con il margine dei veli sotto il vetro dell'engine
   * ([GalleryChromeDefaults.MeasuredTarget]), come il testo. Senza margine, sui pixel del viewer sulla JVM il rosso si fermava a 4,52:1: la pellicola
   * vera dell'engine (lente, riflesso, vibrancy) lo puo' portare sotto 4,5 sul telefono.
   */
  const val DestructiveTarget: Float = GalleryChromeDefaults.MeasuredTarget

  /** Quanto il rosso della voce distruttiva puo' andare verso il testo prima che cresca il velo: meno di un terzo, e resta un rosso. */
  const val DestructiveShift: Float = 0.3f

  /** Il velo non va oltre: sopra, il pop-up e' una superficie piena e non piu' vetro. */
  private const val VeilCeiling = 0.9f

  /**
   * Lo scrim che l'engine stende sotto un pop-up (`FluidGlassModalScrim`): nero al 20% in scuro e al 12% in chiaro. Sono numeri dell'engine
   * (verificati sulla 3.0.4, uguali dalla 2.13.0): **da rivedere a ogni aggiornamento dell'engine**.
   */
  private const val PopoverScrimDark = 0.20f
  private const val PopoverScrimLight = 0.12f
}

private val MenuMinWidth = 220.dp
private val MenuMaxWidth = 320.dp
private val MenuRowInset = 16.dp
private val MenuRowVerticalPadding = 12.dp
private val MenuRowGap = 12.dp
private val MenuGlyph = 20.dp
private val GroupBandHeight = 8.dp
private const val GroupBandAlpha = 0.07f
private const val DisabledAlpha = 0.38f
