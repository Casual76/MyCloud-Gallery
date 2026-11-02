package dev.pampa.mycloudgallery.feature.diagnostics.release

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lo stato della schermata: prima del giro, durante, con il resoconto. */
sealed interface StepSimulationState {
  data object Idle : StepSimulationState

  data object Running : StepSimulationState

  data class Done(val report: StepReport) : StepSimulationState
}

/**
 * Il giro della simulazione e il suo stato. Un giro alla volta (un secondo tocco mentre gira non ne fa partire un altro); se il lavoro viene
 * cancellato (la schermata si chiude) lo stato torna a [StepSimulationState.Idle]. Non scrive mai: [StepSimulation] non ha scritture.
 */
class StepSimulationModel(private val simulation: StepSimulation) {
  private val flow = MutableStateFlow<StepSimulationState>(StepSimulationState.Idle)
  val state: StateFlow<StepSimulationState> = flow.asStateFlow()

  suspend fun run() {
    val before = flow.value
    if (before is StepSimulationState.Running || !flow.compareAndSet(before, StepSimulationState.Running)) return
    try {
      flow.value = StepSimulationState.Done(simulation.run())
    } catch (e: kotlinx.coroutines.CancellationException) {
      flow.value = StepSimulationState.Idle
      throw e
    }
  }
}

/**
 * "Simula il passo" (Fase 10 P10, §2.10; solo build debug dell'admin, da Impostazioni -> Sviluppo): sola lettura, le quattro aree in un
 * giro solo. Una riga per area con numeri e cartelle di primo livello, il totale, lo spazio libero dopo il passo e le cose che lo fermano.
 * Senza stile scritto a mano: solo componenti dell'engine.
 *
 * @param copied il resoconto e' appena stato copiato.
 */
@Composable
fun StepSimulationScreen(
  state: StepSimulationState,
  copied: Boolean,
  onRun: () -> Unit,
  onCopy: () -> Unit,
  onBack: () -> Unit,
) {
  FluidScreen(title = StepSimulationText.TITLE, onBack = onBack) {
    item {
      FluidInlineMessage(
        title = StepSimulationText.READ_ONLY_TITLE,
        message = StepSimulationText.READ_ONLY_MESSAGE,
        tone = FluidTone.Info,
      )
    }
    item {
      FluidButton(
        text = StepSimulationText.RUN,
        onClick = onRun,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    }
    when (state) {
      StepSimulationState.Idle -> item { FluidSectionFootnote(text = StepSimulationText.NOT_RUN_FOOTNOTE) }
      StepSimulationState.Running -> item { FluidLoadingBlock() }
      is StepSimulationState.Done -> {
        val report = state.report
        item { FluidSectionHeader(title = StepSimulationText.TOTAL_TITLE) }
        item {
          FluidListGroup {
            report.forecasts.forEachIndexed { index, forecast ->
              if (index > 0) FluidListDivider()
              FluidListRow(
                title = StepSimulationText.title(forecast),
                subtitle = StepSimulationText.detail(forecast),
                eyebrow = forecast.area.fence,
                tone = if (forecast is StepForecast.Unavailable) FluidTone.Danger else FluidTone.Neutral,
                disclosure = false,
              )
            }
          }
        }
        item {
          FluidListGroup {
            FluidListRow(
              title = StepSimulationText.SUM_TITLE,
              subtitle = "${StepSimulationText.files(report.totalFiles)}, ${StepSimulationText.bytes(report.totalBytes)}",
              disclosure = false,
            )
            FluidListDivider()
            FluidListRow(
              title = StepSimulationText.SPACE_TITLE,
              subtitle = report.space?.let { "${StepSimulationText.bytes(it.freeBytes)}; dopo il passo: ${StepSimulationText.bytes(report.freeAfterBytes ?: 0L)}" } ?: "Non letto",
              tone = if (StepAttention.SPACE_UNKNOWN in report.attention || StepAttention.SPACE_BELOW_MARGIN in report.attention) FluidTone.Danger else FluidTone.Neutral,
              disclosure = false,
            )
          }
        }
        for (attention in report.attention) {
          item(key = "attention-${attention.name}") {
            FluidInlineMessage(
              title = if (attention.blocking) StepSimulationText.CHECK_TITLE else StepSimulationText.INFO_TITLE,
              message = StepSimulationText.attentionText(attention),
              tone = if (attention.blocking) FluidTone.Danger else FluidTone.Info,
            )
          }
        }
        item {
          FluidButton(
            text = if (copied) StepSimulationText.COPIED else StepSimulationText.COPY,
            onClick = onCopy,
            size = FluidButtonSize.Large,
            fillWidth = true,
          )
        }
      }
    }
  }
}
