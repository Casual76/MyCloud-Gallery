package dev.pampa.mycloudgallery.feature.memories.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.feature.memories.R
import dev.pampa.mycloudgallery.feature.memories.contract.LessSheetState
import dev.pampa.mycloudgallery.feature.memories.ui.CancelButton
import dev.pampa.mycloudgallery.feature.memories.ui.ChoiceRow
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesDimens

// I FOGLI "MOSTRA MENO" E "NON RICORDARMI QUESTO PERIODO" (Fase 9, P9; docs/fase9-progetto.md §1.5 `LessSheetState`, §2.3, D11). Fogli di vetro del
// design system (`GalleryConfirmSheet`, con la difesa dal tapjacking di tutti gli altri); bottoni da 50 dp, mai due controlli sovrapposti. Il nome
// della persona arriva gia' letto e sta solo nel titolo del foglio: mai nei log.

/** Cosa i fogli chiedono fuori da se': ogni voce passa dal controllore della pagina. */
internal interface LessSheetHandlers {
  fun onChooseLess(level: LessLevel?)

  fun onAdjustDays(fromDelta: Int, toDelta: Int)

  fun onOnlyFirstDay()

  fun onWholePeriod()

  fun onConfirmDays()

  fun onDismissSheet()
}

/** "Mostra meno <nome>": le due scelte e, se ce n'e' una, "Togli la scelta". Scegliere chiude il foglio. */
@Composable
internal fun LessPersonSheet(state: LessSheetState.Person?, handlers: LessSheetHandlers) {
  val title = if (state?.name != null) stringResource(R.string.memorie_less_title, state.name) else stringResource(R.string.memorie_less_title_unnamed)
  GalleryConfirmSheet(visible = state != null, onDismissRequest = handlers::onDismissSheet, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.VisibilityOff,
      tone = GallerySheetTone.Brand,
      title = title,
      message = stringResource(R.string.memorie_less_message),
      body = {
        Column(verticalArrangement = Arrangement.spacedBy(MemoriesDimens.TightGap / 2)) {
          ChoiceRow(
            title = stringResource(R.string.memorie_less_option_less),
            detail = stringResource(R.string.memorie_less_option_less_detail),
            selected = state?.level == LessLevel.LESS,
            onClick = { handlers.onChooseLess(LessLevel.LESS) },
          )
          ChoiceRow(
            title = stringResource(R.string.memorie_less_option_never),
            detail = stringResource(R.string.memorie_less_option_never_detail),
            selected = state?.level == LessLevel.NEVER,
            onClick = { handlers.onChooseLess(LessLevel.NEVER) },
          )
        }
      },
      actions = {
        if (state?.level != null) {
          FluidButton(
            text = stringResource(R.string.memorie_less_clear), onClick = { handlers.onChooseLess(null) },
            style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true,
          )
        }
        CancelButton(handlers::onDismissSheet)
      },
    )
  }
}

/**
 * "Non ricordarmi questo periodo": i giorni del ricordo, che si spostano di un giorno alla volta ("Dal" e "Al", ognuno con -1 e +1 giorno), al piu'
 * 366; "Solo un giorno" e "Tutto il periodo del ricordo" per le due scelte piu' comuni. Vale solo "Mai" (D11).
 */
@Composable
internal fun NeverDaysSheet(state: LessSheetState.Days?, handlers: LessSheetHandlers) {
  val resources = LocalContext.current.resources
  val title = stringResource(R.string.memorie_days_title)
  GalleryConfirmSheet(visible = state != null, onDismissRequest = handlers::onDismissSheet, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.EventBusy,
      tone = GallerySheetTone.Caution,
      title = title,
      message = stringResource(R.string.memorie_days_message),
      body = {
        if (state != null) {
          Column(verticalArrangement = Arrangement.spacedBy(MemoriesDimens.BlockGap)) {
            Text(
              text = resources.getQuantityString(R.plurals.memorie_days_count, state.range.days, state.range.days),
              modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
            DayStepper(stringResource(R.string.memorie_days_from), GalleryDates.date(state.range.from)) { handlers.onAdjustDays(it, 0) }
            DayStepper(stringResource(R.string.memorie_days_to), GalleryDates.date(state.range.to)) { handlers.onAdjustDays(0, it) }
            Row(horizontalArrangement = Arrangement.spacedBy(MemoriesDimens.TightGap), modifier = Modifier.fillMaxWidth()) {
              FluidButton(
                text = stringResource(R.string.memorie_days_only_first), onClick = handlers::onOnlyFirstDay,
                style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, modifier = Modifier.weight(1f),
              )
              FluidButton(
                text = stringResource(R.string.memorie_days_whole), onClick = handlers::onWholePeriod,
                style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, modifier = Modifier.weight(1f),
              )
            }
            Text(text = stringResource(R.string.memorie_days_limit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      },
      actions = {
        FluidButton(
          text = stringResource(R.string.memorie_days_confirm), onClick = handlers::onConfirmDays,
          style = FluidButtonStyle.Destructive, size = FluidButtonSize.Large, fillWidth = true,
        )
        CancelButton(handlers::onDismissSheet)
      },
    )
  }
}

/** Un estremo del periodo: l'etichetta e la data sopra, "-1 giorno" e "+1 giorno" sotto, due bottoni da 50 dp che si dividono la riga. */
@Composable
private fun DayStepper(label: String, date: String, onStep: (Int) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(MemoriesDimens.TightGap / 2)) {
    Text(text = "$label $date", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    Row(horizontalArrangement = Arrangement.spacedBy(MemoriesDimens.TightGap), modifier = Modifier.fillMaxWidth()) {
      FluidButton(
        text = stringResource(R.string.memorie_days_earlier), onClick = { onStep(-1) },
        style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, modifier = Modifier.weight(1f),
      )
      FluidButton(
        text = stringResource(R.string.memorie_days_later), onClick = { onStep(1) },
        style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, modifier = Modifier.weight(1f),
      )
    }
  }
}

/** Il periodo massimo di una scelta (366 giorni), per i test e per chi mostra il limite. */
internal const val MAX_NEVER_DAYS: Int = LessTarget.Days.MAX_DAYS
