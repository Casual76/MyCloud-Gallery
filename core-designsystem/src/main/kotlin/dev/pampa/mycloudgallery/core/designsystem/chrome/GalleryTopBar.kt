package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassFalloff
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.GlassTint
import dev.antigravity.fluidengine.ui.fluid.LocalGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.a11y.galleryGlass
import dev.antigravity.fluidengine.ui.fluid.rememberCombinedGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.currentGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassControlSurface
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto

/** Che cosa mostra il tasto a sinistra della barra. */
enum class GalleryNavigationIcon { Back, Close }

/**
 * L'inchiostro dei controlli di una barra: il colore delle icone sulle lenti.
 *
 * Su una pagina e' l'accento (come in `FluidScreen`). Sopra una foto l'accento non regge: il viola
 * sul grigio che la pellicola diventa sopra una foto scura e' un contrasto di 1,3:1. La barra
 * "sopra una foto" lo cambia in `onSurface`, la coppia di cui l'engine garantisce il contrasto sulla
 * pellicola, e chi mette un controllo nella barra lo legge con [galleryBarInk].
 */
val LocalGalleryBarInk = compositionLocalOf<Color?> { null }

/** Il colore delle icone dei controlli nella barra in cui ci si trova. */
@Composable
fun galleryBarInk(): Color = LocalGalleryBarInk.current ?: MaterialTheme.colorScheme.primary

/**
 * Il materiale di una barra o di una capsula sopra una foto.
 *
 * Sul lato chiaro e' la coppia dell'engine (`lightBarTint`). Sul lato scuro l'engine ha `darkBarTint`
 * al 42% di nero, e il testo chiaro sopra una foto bianca ci cade a 2,7:1: qui sale al [GalleryOnPhoto]
 * `ScrimAlpha` (60%), con cui il bianco regge 4,5:1 anche sulla foto piu' chiara. E' l'unica deroga
 * ai tint dell'engine, e per questa ragione sola.
 */
@Composable
fun galleryTintOnPhoto(floating: Boolean = false): GlassTint {
  val scheme = MaterialTheme.colorScheme
  return if (GlassDefaults.isDarkSurface()) {
    GlassTint(
      overlay = GalleryOnPhoto.scrim(),
      fallback = scheme.surface.copy(alpha = FallbackAlpha),
      hairline = GalleryOnPhoto.Content.copy(alpha = if (floating) FloatingHairline else BarHairline),
    )
  } else if (floating) {
    GlassDefaults.lightFloatingTint()
  } else {
    GlassDefaults.lightBarTint()
  }
}

/**
 * Un tasto di vetro nella barra: un'icona su una lente da 44 dp, dentro un bersaglio da **48 dp** che
 * e' tutto cliccabile.
 *
 * `FluidGlassIconButton` dell'engine ha il clic dentro la lente (44 dp) e il resto e' margine, quindi il
 * bersaglio vero e' di 44: sotto il minimo di accessibilita'. Qui il clic sta sul riquadro esterno e la
 * lente e' solo materiale; la lente si china e si accende da sola sotto il dito, come nel tasto dell'engine
 * (la scala della pressione sta a 1: scalare il layer sposterebbe il fondale campionato dentro il vetro).
 * L'icona prende l'inchiostro della barra ([galleryBarInk]), cosi' sta bene sulla pagina e sopra una foto.
 */
@Composable
fun GalleryBarButton(
  icon: ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  backdrop: GlassBackdropState = currentGlassBackdrop(),
) {
  Box(
    modifier = modifier
      .size(GalleryDimens.MinTouchTarget)
      .alpha(if (enabled) 1f else DisabledAlpha)
      .semantics { this.contentDescription = contentDescription }
      .fluidPressable(
        onClick = onClick,
        enabled = enabled,
        pressedScale = 1f,
        role = Role.Button,
        focusShape = FluidCapsuleShape,
      ),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .size(BarLens)
        .glassControlSurface(backdrop = backdrop, shape = FluidCapsuleShape, interactive = enabled),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = galleryBarInk(),
        modifier = Modifier.size(BarGlyph),
      )
    }
  }
}

/**
 * La barra superiore della Libreria e del viewer: una fascia di vetro con titolo, un sottotitolo
 * facoltativo (nel viewer: "12:41 · Oslo") e le azioni a destra.
 *
 * E' la stessa fascia di `FluidScreen` (vetro `Bar` che sfuma verso il basso: una barra uniforme
 * finisce con una riga che attraversa lo schermo, e quella riga e' la cosa piu' finta di un vetro),
 * e per questo **le sue azioni rifrangono la barra, non la pagina**: la barra pubblica il proprio
 * materiale (`exports`) e i controlli la compongono con la pagina (`rememberCombinedGlassBackdrop`).
 * Chi ci mette dentro un `FluidBarAction` o un [GalleryMenuButton] lo riceve senza fare niente.
 *
 * [onPhoto] cambia la tinta: sopra una foto a tutta pagina (il viewer) la pellicola di famiglia e'
 * troppo chiara e solleva il pavimento proprio dove i controlli devono restare leggibili, e sopra una
 * foto chiara la barra sparirebbe. `barTintOnPhoto` e' la coppia dell'engine per questo caso.
 *
 * Il contenuto cresce col testo di sistema (nessuna altezza fissa): a 1,3x il titolo e il
 * sottotitolo non si tagliano.
 *
 * @param backdrop il corpo della pagina, che la barra rifrange. Mai la barra stessa.
 */
@Composable
fun GalleryTopBar(
  title: String,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  onPhoto: Boolean = false,
  onNavigate: (() -> Unit)? = null,
  navigationIcon: GalleryNavigationIcon = GalleryNavigationIcon.Back,
  /**
   * Il tasto di navigazione e' il tasto di vetro dell'engine ([GalleryGlassIconButton]) e non [GalleryBarButton]: e' quello del viewer, che con le azioni a destra ha lo stesso vetro.
   * Di serie no: il resto dell'app tiene il suo tasto.
   */
  fluidButtons: Boolean = false,
  actions: @Composable RowScope.() -> Unit = {},
) {
  val scheme = MaterialTheme.colorScheme
  val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val tint = if (onPhoto) galleryTintOnPhoto() else GlassDefaults.barTint()
  val barGlass = rememberGlassBackdrop()
  val controlBackdrop = rememberCombinedGlassBackdrop(backdrop, barGlass)

  Box(modifier = modifier.fillMaxWidth()) {
    Box(
      modifier = Modifier
        .matchParentSize()
        .galleryGlass(
          state = backdrop,
          tint = tint,
          falloff = GlassFalloff.FadeDown,
          role = GlassRole.Bar,
          exports = barGlass,
        ),
    )
    CompositionLocalProvider(
      LocalGlassBackdrop provides controlBackdrop,
      LocalGalleryBarInk provides if (onPhoto) scheme.onSurface else null,
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(statusBar))
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = FluidScreenDefaults.ControlRowHeight),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            modifier = Modifier.padding(horizontal = FluidScreenDefaults.CompactTitleInset),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(
              text = title,
              style = MaterialTheme.typography.titleMedium,
              color = scheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              textAlign = TextAlign.Center,
            )
            if (!subtitle.isNullOrBlank()) {
              Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                // Sopra una foto il secondario non regge: la pellicola si scurisce o si schiarisce
                // con la foto, `onSurface` e' la coppia che resta leggibile su entrambe.
                color = if (onPhoto) scheme.onSurface.copy(alpha = OnPhotoSubtitleAlpha) else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
              )
            }
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (onNavigate != null) {
              NavigationButton(onNavigate, navigationIcon, fluidButtons)
            } else {
              Spacer(Modifier.width(FluidScreenDefaults.HorizontalPadding))
            }
            Spacer(Modifier.weight(1f))
            Row(
              horizontalArrangement = Arrangement.spacedBy(ActionSpacing),
              verticalAlignment = Alignment.CenterVertically,
              content = actions,
            )
            Spacer(Modifier.width(ActionEndInset))
          }
        }
        // La coda del vetro, sotto la riga dei controlli: e' dove la fascia sfuma nel contenuto.
        Spacer(Modifier.height(FluidScreenDefaults.GlassFadeTail))
      }
    }
  }
}

@Composable
private fun NavigationButton(onNavigate: () -> Unit, icon: GalleryNavigationIcon, fluid: Boolean) {
  val vector = when (icon) {
    GalleryNavigationIcon.Back -> Icons.AutoMirrored.Rounded.ArrowBackIos
    GalleryNavigationIcon.Close -> Icons.Rounded.Close
  }
  val description = stringResource(
    when (icon) {
      GalleryNavigationIcon.Back -> R.string.topbar_back
      GalleryNavigationIcon.Close -> R.string.topbar_close
    },
  )
  if (fluid) {
    GalleryGlassIconButton(icon = vector, contentDescription = description, onClick = onNavigate, modifier = Modifier.padding(start = ActionEndInset))
  } else {
    GalleryBarButton(icon = vector, contentDescription = description, onClick = onNavigate, modifier = Modifier.padding(start = ActionEndInset))
  }
}

private val ActionSpacing = 2.dp
private val ActionEndInset = 6.dp
private val BarGlyph = 22.dp
private val BarLens = 44.dp
private const val DisabledAlpha = 0.38f
private const val FallbackAlpha = 0.96f
private const val OnPhotoSubtitleAlpha = 0.82f
private const val BarHairline = 0.14f
private const val FloatingHairline = 0.2f
