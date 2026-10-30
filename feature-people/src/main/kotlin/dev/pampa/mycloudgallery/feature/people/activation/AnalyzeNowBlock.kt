package dev.pampa.mycloudgallery.feature.people.activation

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.AnalyzeNowState
import dev.pampa.mycloudgallery.feature.people.contract.FacesAnalyzeEstimate

// «ANALIZZA ORA» COME SI VEDE (CA-2, correzioni C; risposta dell'utente del 30/10): la stima, il tasto, la conferma e l'avviso di richiesta partita, in un blocco solo
// per Impostazioni -> Persone e per la pagina di attivazione. Composable fatte solo con i componenti dell'engine (G2, D38). La stima arriva dopo il primo disegno
// ([AnalyzeNowState.Counting] e' lo scheletro). D37: solo numeri.

/** Le frasi della stima: funzioni pure, si provano senza comporre. */
internal object AnalyzeNowText {

  /** "circa 37 minuti", "2 ore", "2 ore e 10 minuti": il tempo, mai in secondi e mai meno di un minuto. */
  fun duration(resources: Resources, minutes: Int): String {
    val total = minutes.coerceAtLeast(1)
    val hours = total / 60
    val rest = total % 60
    val minutesText = resources.getQuantityString(R.plurals.faces_analyze_minutes, rest, rest)
    if (hours == 0) return minutesText
    val hoursText = resources.getQuantityString(R.plurals.faces_analyze_hours, hours, hours)
    return if (rest == 0) hoursText else resources.getString(R.string.faces_analyze_hours_and_minutes, hoursText, minutesText)
  }

  /** "Analizzare le tue 12.000 foto richiede circa 37 minuti." alla prima analisi; poi "Restano 4.200 foto da analizzare: servono circa 11 minuti." */
  fun estimate(resources: Resources, estimate: FacesAnalyzeEstimate): String {
    val count = estimate.remaining
    val plural = count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    val id = if (estimate.isFirstRun) R.plurals.faces_analyze_estimate_first else R.plurals.faces_analyze_estimate_left
    return resources.getQuantityString(id, plural, GalleryNumbers.format(count), duration(resources, estimate.minutes))
  }

  /** Il corpo della conferma: cosa succede e quanto dura, piu' (sui dati mobili) la frase delle regole con la stima dei dati. */
  fun confirm(resources: Resources, estimate: FacesAnalyzeEstimate, network: String?): String {
    val base = resources.getString(R.string.faces_analyze_confirm_message, duration(resources, estimate.minutes))
    return if (network == null) base else "$base\n\n$network"
  }
}

/** Il blocco di «Analizza ora» dentro la lista di una pagina. [state] `Hidden` non aggiunge niente. */
internal fun LazyListScope.analyzeNowBlock(
  state: AnalyzeNowState,
  onAsk: () -> Unit,
  onCancel: () -> Unit,
  onConfirm: () -> Unit,
) {
  when (state) {
    AnalyzeNowState.Hidden -> Unit
    AnalyzeNowState.Counting -> item(key = "analyze-counting") {
      Text(
        text = stringResource(R.string.faces_analyze_counting),
        modifier = Modifier.fillMaxWidth().padding(top = ActivationDimens.BlockGap).semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    is AnalyzeNowState.Ready -> {
      item(key = "analyze-estimate") { EstimateText(state.estimate) }
      item(key = "analyze-action") {
        val description = stringResource(R.string.faces_analyze_action_description)
        FluidButton(
          text = stringResource(R.string.faces_analyze_action),
          onClick = onAsk,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
          modifier = Modifier.semantics { contentDescription = description },
        )
      }
      item(key = "analyze-footnote") { FluidSectionFootnote(text = stringResource(R.string.faces_analyze_footnote)) }
    }
    is AnalyzeNowState.Confirming -> {
      item(key = "analyze-confirm") {
        val resources = LocalContext.current.resources
        Column(verticalArrangement = Arrangement.spacedBy(ActivationDimens.BlockGap), modifier = Modifier.padding(top = ActivationDimens.BlockGap)) {
          FluidInlineMessage(
            message = AnalyzeNowText.confirm(resources, state.estimate, state.network),
            title = stringResource(R.string.faces_analyze_confirm_title),
            tone = FluidTone.Warning,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
          )
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ActivationDimens.TightGap)) {
            FluidButton(
              text = stringResource(R.string.faces_analyze_confirm_cancel),
              onClick = onCancel,
              style = FluidButtonStyle.Tinted,
              size = FluidButtonSize.Large,
              modifier = Modifier.weight(1f),
            )
            FluidButton(
              text = stringResource(R.string.faces_analyze_confirm_go),
              onClick = onConfirm,
              style = FluidButtonStyle.Filled,
              size = FluidButtonSize.Large,
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }
    AnalyzeNowState.Started -> item(key = "analyze-started") {
      FluidInlineMessage(
        message = stringResource(R.string.faces_analyze_started),
        title = stringResource(R.string.faces_analyze_action),
        tone = FluidTone.Success,
        modifier = Modifier.padding(top = ActivationDimens.BlockGap).semantics { liveRegion = LiveRegionMode.Polite },
      )
    }
  }
}

@Composable
private fun EstimateText(estimate: FacesAnalyzeEstimate) {
  val resources = LocalContext.current.resources
  Column(modifier = Modifier.fillMaxWidth().padding(top = ActivationDimens.BlockGap, bottom = ActivationDimens.TightGap), verticalArrangement = Arrangement.spacedBy(ActivationDimens.TightGap)) {
    Text(
      text = AnalyzeNowText.estimate(resources, estimate),
      modifier = Modifier.widthIn(max = ActivationDimens.ReadingWidth).fillMaxWidth(),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = stringResource(R.string.faces_analyze_rules),
      modifier = Modifier.widthIn(max = ActivationDimens.ReadingWidth).fillMaxWidth(),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
