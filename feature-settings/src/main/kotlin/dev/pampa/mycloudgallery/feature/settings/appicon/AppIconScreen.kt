package dev.pampa.mycloudgallery.feature.settings.appicon

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryStatusMark
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.RowValue
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsDimens

// LA PAGINA «ICONA DELL'APP» (I5, 12/11/2026), con il metro della fase I («se non sapessi leggere, potrei usarla?»): tre anteprime grandi
// dell'icona vera, una accanto all'altra, e la scelta di adesso con l'anello e la spunta; il tocco sceglie subito. Sotto, una riga sola: su alcuni
// telefoni l'icona sparisce un attimo dalla Home e torna in un altro posto (il launcher toglie un ingresso e ne aggiunge un altro). Nient'altro.
// Per TalkBack ogni anteprima e' una scelta con il suo nome e il suo stato.

@Composable
internal fun AppIconRoute(onBack: () -> Unit, viewModel: AppIconViewModel = hiltViewModel()) {
  val choice by viewModel.choice.collectAsStateWithLifecycle()
  AppIconScreen(choice = choice ?: AppIconChoice.DEFAULT, art = viewModel.art, onBack = onBack, onChoose = viewModel::choose)
}

/** La pagina per uno stato dato. [art] null (nessun collegamento di `:app`) = la pagina mostra solo l'avviso: non c'e' un'icona da disegnare. */
@Composable
fun AppIconScreen(choice: AppIconChoice, art: AppIconArt?, onBack: () -> Unit, onChoose: (AppIconChoice) -> Unit) {
  FluidScreen(title = stringResource(R.string.app_icon_title), onBack = onBack) {
    if (art != null) {
      item {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(ContinuousCornerShape(FluidRadius.Group))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = SettingsDimens.CardPadding)
            .selectableGroup(),
          horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
          // Giro 2 (CS-22): tre terzi uguali, cosi' col testo grande «Automatica» non tocca «Chiara».
          AppIconChoice.entries.forEach { option ->
            AppIconOption(
              option = option,
              selected = option == choice,
              art = art,
              onClick = { if (option != choice) onChoose(option) },
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.app_icon_warning)) }
  }
}

/** Una scelta: l'anteprima grande, l'anello e la spunta se e' quella di adesso, il nome sotto. Per TalkBack una scelta con nome e stato. */
@Composable
private fun AppIconOption(option: AppIconChoice, selected: Boolean, art: AppIconArt, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val label = stringResource(appIconLabel(option))
  val selectedState = stringResource(R.string.selected)
  val ringShape = ContinuousCornerShape(SettingsDimens.AppIconRadius + SettingsDimens.AppIconRingGap + SettingsDimens.AppIconRing)
  Column(
    modifier = modifier
      .fluidPressable(onClick = onClick, role = Role.RadioButton)
      .semantics(mergeDescendants = true) {
        role = Role.RadioButton
        contentDescription = label
        this.selected = selected
        if (selected) stateDescription = selectedState
      },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(SettingsDimens.CardGap),
  ) {
    Box {
      Box(
        modifier = Modifier
          .then(if (selected) Modifier.border(SettingsDimens.AppIconRing, MaterialTheme.colorScheme.primary, ringShape) else Modifier)
          .padding(SettingsDimens.AppIconRing + SettingsDimens.AppIconRingGap),
      ) {
        AppIconPreview(choice = option, art = art, side = SettingsDimens.AppIconPreview, radius = SettingsDimens.AppIconRadius)
      }
      if (selected) {
        GalleryStatusMark(
          status = GalleryIconStatus.Done,
          modifier = Modifier.align(Alignment.BottomEnd),
          size = SettingsDimens.AppIconMark,
          ring = MaterialTheme.colorScheme.surfaceContainerLow,
          contentDescription = null,
        )
      }
    }
    Text(
      text = label,
      // Giro 2 (CS-22): col testo grande l'etichetta scende di un gradino, cosi' «Automatica» resta dentro il suo terzo con un po' d'aria.
      style = if (LocalDensity.current.fontScale >= LargeLabelScale) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
    )
  }
}

/**
 * L'icona come la compone il launcher, con le risorse vere: lo sfondo pieno ([AppIconChoice.LIGHT] chiaro, [AppIconChoice.DARK] scuro,
 * [AppIconChoice.AUTO] meta' e meta' in diagonale: segue il tema) e il primo piano ingrandito di 108/72 (la parte visibile di un'icona adattiva),
 * ritagliati con gli angoli del launcher.
 */
@Composable
internal fun AppIconPreview(choice: AppIconChoice, art: AppIconArt, side: Dp, radius: Dp, modifier: Modifier = Modifier) {
  val light = colorResource(art.lightBackground)
  val dark = colorResource(art.darkBackground)
  val foreground = painterResource(art.foreground)
  val shape = ContinuousCornerShape(radius)
  Box(
    modifier = modifier
      .size(side)
      .clip(shape)
      // Un filo del colore dei contorni: la «Scura» sul fondo scuro e la «Chiara» sul fondo chiaro restano un'icona, non una macchia.
      .border(SettingsDimens.AppIconOutline, MaterialTheme.colorScheme.outlineVariant, shape)
      .drawBehind {
        when (choice) {
          AppIconChoice.LIGHT -> drawRect(light)
          AppIconChoice.DARK -> drawRect(dark)
          AppIconChoice.AUTO -> {
            drawRect(light)
            val lowerRight = Path().apply {
              moveTo(size.width, 0f)
              lineTo(size.width, size.height)
              lineTo(0f, size.height)
              close()
            }
            drawPath(lowerRight, dark)
          }
        }
      },
  ) {
    Image(
      painter = foreground,
      contentDescription = null,
      contentScale = ContentScale.Fit,
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
          scaleX = ADAPTIVE_LAYER_SCALE
          scaleY = ADAPTIVE_LAYER_SCALE
        },
    )
  }
}

/**
 * L'icona dell'app come la vede il launcher, a una misura qualunque, con gli angoli in proporzione a quelli dell'anteprima: la figura dell'aggiornamento
 * dentro l'app (pacchetto U, UU-7: «un'anteprima, non un simbolo»), che `:app` le passa.
 */
@Composable
fun AppIconFigure(choice: AppIconChoice, art: AppIconArt, side: Dp, modifier: Modifier = Modifier) {
  AppIconPreview(choice = choice, art = art, side = side, radius = SettingsDimens.AppIconRadius * (side / SettingsDimens.AppIconPreview), modifier = modifier)
}

/** La voce della pagina principale: a sinistra l'icona scelta in piccolo (la vera), a destra il suo nome. */
@Composable
internal fun AppIconEntryRow(choice: AppIconChoice, art: AppIconArt, onClick: () -> Unit) {
  val title = stringResource(R.string.app_icon_title)
  val value = stringResource(appIconLabel(choice))
  FluidListRow(
    title = title,
    subtitle = "",
    badge = { RowValue(value) },
    modifier = Modifier.semantics { contentDescription = "$title, $value" },
    tone = FluidTone.Info,
    leading = { AppIconPreview(choice = choice, art = art, side = SettingsDimens.AppIconRow, radius = SettingsDimens.AppIconRowRadius) },
    leadingTile = false,
    onClick = onClick,
  )
}

internal fun appIconLabel(choice: AppIconChoice): Int = when (choice) {
  AppIconChoice.AUTO -> R.string.app_icon_auto
  AppIconChoice.LIGHT -> R.string.app_icon_light
  AppIconChoice.DARK -> R.string.app_icon_dark
}

/** Un'icona adattiva e' di 108 dp e se ne vedono i 72 centrali: l'anteprima ingrandisce il primo piano di 108/72. */
private const val ADAPTIVE_LAYER_SCALE: Float = 108f / 72f

/** Da questa scala del testo l'etichetta della scelta usa il corpo piu' piccolo. */
private const val LargeLabelScale = 1.2f
