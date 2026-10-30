package dev.pampa.mycloudgallery.feature.people.activation

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
import androidx.compose.runtime.remember
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
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.ActivationProblem
import dev.pampa.mycloudgallery.feature.people.contract.AnalyzeNowState
import dev.pampa.mycloudgallery.feature.people.contract.FacesActivationState

// LA PAGINA DI ATTIVAZIONE DI PERSONE (docs/fase8-progetto.md §2.1, D3, D4, D5, D32, D34; riga P7 del §4.4). Dice in frasi brevi cosa fa, dove restano
// i dati e come si cancella, poi "Attiva" e "Non ora" **con lo stesso peso**: nessuna spinta verso "Attiva". Senza area privata dice "Serve un'area
// privata sul NAS" e nient'altro; senza indicizzatori propone "Fallo su questo dispositivo". Composable fatte solo con i componenti dell'engine (G2, D38).
//
// **E' l'unico file che costruisce un `FacesActivationConsent`** (Phase8ArchitectureTest, `activation/FacesActivation*.kt`): senza questa pagina Persone
// non si accende. Il consenso porta la versione del testo di questa pagina ([FacesActivationText.VERSION]) e l'istante (orologio monotono) in cui la
// pagina e' comparsa sullo schermo.

@Composable
fun FacesActivationScreen(
  state: FacesActivationState?,
  onActivate: (FacesActivationConsent) -> Unit,
  onNotNow: () -> Unit,
  onBecomeIndexer: () -> Unit,
  onOpenLicenses: () -> Unit,
  onRetry: () -> Unit,
  onDone: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  /** CA-2: a Persone attivata, la stima e «Analizza ora» (di serie non si offre). */
  analyze: AnalyzeNowState = AnalyzeNowState.Hidden,
  onAnalyzeAsk: () -> Unit = {},
  onAnalyzeCancel: () -> Unit = {},
  onAnalyzeConfirm: () -> Unit = {},
) {
  // L'istante (orologio monotono) in cui la pagina di spiegazione e' sullo schermo: il consenso lo porta con se'. Si ripete se la pagina torna (testo cambiato).
  val shownAt = remember(state is FacesActivationState.Explain) { System.nanoTime() }
  FluidScreen(title = stringResource(R.string.faces_activation_title), modifier = modifier, onBack = onBack) {
    when (state) {
      null -> loadingBlock(R.string.faces_activation_loading)
      is FacesActivationState.Explain -> explain(state, shownAt, onActivate, onNotNow, onOpenLicenses)
      is FacesActivationState.NeedsIndexer -> needsIndexer(state.canBecomeIndexer, onBecomeIndexer, onDone)
      FacesActivationState.NoPrivateArea -> noPrivateArea(onDone)
      FacesActivationState.Activating -> loadingBlock(R.string.faces_activation_activating)
      is FacesActivationState.Activated -> activated(state.syncPending, analyze, onAnalyzeAsk, onAnalyzeCancel, onAnalyzeConfirm, onDone)
      is FacesActivationState.Failed -> failed(state.problem, onRetry, onDone)
    }
  }
}

private fun LazyListScope.loadingBlock(message: Int) {
  item(key = "loading") {
    Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
      FluidLoadingBlock()
      Text(
        text = stringResource(message),
        modifier = Modifier.fillMaxWidth().padding(vertical = ActivationDimens.BlockGap),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun LazyListScope.explain(
  state: FacesActivationState.Explain,
  shownAt: Long,
  onActivate: (FacesActivationConsent) -> Unit,
  onNotNow: () -> Unit,
  onOpenLicenses: () -> Unit,
) {
  if (state.requirements.localOnly) {
    item(key = "fence") {
      FluidInlineMessage(
        message = stringResource(R.string.faces_activation_fence),
        title = stringResource(R.string.faces_activation_fence_title),
        tone = FluidTone.Info,
        modifier = Modifier.padding(bottom = ActivationDimens.BlockGap),
      )
    }
  }
  item(key = "paragraphs") {
    val resources = LocalContext.current.resources
    val paragraphs = remember(resources) { FacesActivationText.paragraphs(resources) }
    Column(verticalArrangement = Arrangement.spacedBy(ActivationDimens.ParagraphGap)) {
      paragraphs.forEach { paragraph ->
        Text(
          text = paragraph,
          modifier = Modifier.widthIn(max = ActivationDimens.ReadingWidth).fillMaxWidth(),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      Text(
        text = FacesActivationText.models(resources),
        modifier = Modifier.widthIn(max = ActivationDimens.ReadingWidth).fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
  item(key = "licenses") {
    val description = stringResource(R.string.faces_activation_licenses_description)
    FluidButton(
      text = stringResource(R.string.faces_activation_licenses),
      onClick = onOpenLicenses,
      style = FluidButtonStyle.Plain,
      size = FluidButtonSize.Large,
      modifier = Modifier.semantics { contentDescription = description },
    )
  }
  item(key = "buttons") {
    val enable = stringResource(R.string.faces_activation_enable)
    val notNow = stringResource(R.string.faces_activation_not_now)
    val enableDescription = stringResource(R.string.faces_activation_enable_description)
    val notNowDescription = stringResource(R.string.faces_activation_not_now_description)
    // Lo stesso peso, la stessa forma, la stessa larghezza: l'utente sceglie, la pagina non spinge.
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = ActivationDimens.BlockGap, bottom = GalleryDimens.Gutter),
      horizontalArrangement = Arrangement.spacedBy(ActivationDimens.TightGap),
    ) {
      FluidButton(
        text = notNow,
        onClick = onNotNow,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        modifier = Modifier.weight(1f).semantics { contentDescription = notNowDescription },
      )
      FluidButton(
        text = enable,
        onClick = { onActivate(FacesActivationConsent(FacesActivationText.VERSION, shownAt)) },
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        modifier = Modifier.weight(1f).semantics { contentDescription = enableDescription },
      )
    }
  }
}

private fun LazyListScope.needsIndexer(canBecomeIndexer: Boolean, onBecomeIndexer: () -> Unit, onClose: () -> Unit) {
  item(key = "indexer") {
    FluidInlineMessage(
      message = stringResource(R.string.faces_activation_indexer_message),
      title = stringResource(R.string.faces_activation_indexer_title),
      tone = FluidTone.Warning,
      modifier = Modifier.padding(bottom = ActivationDimens.BlockGap),
    )
  }
  if (canBecomeIndexer) {
    item(key = "make") {
      val description = stringResource(R.string.faces_activation_indexer_make_description)
      FluidButton(
        text = stringResource(R.string.faces_activation_indexer_make),
        onClick = onBecomeIndexer,
        style = FluidButtonStyle.Filled,
        size = FluidButtonSize.Large,
        fillWidth = true,
        modifier = Modifier.semantics { contentDescription = description },
      )
    }
  } else {
    item(key = "other") {
      Text(
        text = stringResource(R.string.faces_activation_indexer_other),
        modifier = Modifier.widthIn(max = ActivationDimens.ReadingWidth).fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
  closeButton(onClose)
}

private fun LazyListScope.noPrivateArea(onClose: () -> Unit) {
  item(key = "area") {
    FluidInlineMessage(
      message = stringResource(R.string.faces_activation_area_message),
      title = stringResource(R.string.faces_activation_area_title),
      tone = FluidTone.Warning,
      modifier = Modifier.padding(bottom = ActivationDimens.BlockGap),
    )
  }
  closeButton(onClose)
}

private fun LazyListScope.activated(
  syncPending: Boolean,
  analyze: AnalyzeNowState,
  onAnalyzeAsk: () -> Unit,
  onAnalyzeCancel: () -> Unit,
  onAnalyzeConfirm: () -> Unit,
  onDone: () -> Unit,
) {
  item(key = "done") {
    FluidInlineMessage(
      message = stringResource(R.string.faces_activation_done_message),
      title = stringResource(R.string.faces_activation_done_title),
      tone = FluidTone.Success,
      modifier = Modifier.padding(bottom = ActivationDimens.BlockGap).semantics { liveRegion = LiveRegionMode.Polite },
    )
  }
  if (syncPending) {
    item(key = "pending") {
      Text(
        text = stringResource(R.string.faces_activation_done_pending),
        modifier = Modifier.widthIn(max = ActivationDimens.ReadingWidth).fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  // CA-2: la stima di quanto richiede analizzare le foto e, se si vuole, «Analizza ora» (con la sua conferma): il primo calcolo, altrimenti, segue la notte.
  analyzeNowBlock(analyze, onAsk = onAnalyzeAsk, onCancel = onAnalyzeCancel, onConfirm = onAnalyzeConfirm)
  item(key = "ok") {
    FluidButton(
      text = stringResource(R.string.faces_activation_done_action),
      onClick = onDone,
      style = FluidButtonStyle.Filled,
      size = FluidButtonSize.Large,
      fillWidth = true,
      modifier = Modifier.padding(top = ActivationDimens.BlockGap),
    )
  }
}

private fun LazyListScope.failed(problem: ActivationProblem, onRetry: () -> Unit, onClose: () -> Unit) {
  val (title, message) = when (problem) {
    ActivationProblem.STALE_CONSENT -> R.string.faces_activation_stale_title to R.string.faces_activation_stale_message
    ActivationProblem.NEEDS_UPDATE -> R.string.faces_activation_update_title to R.string.faces_activation_update_message
    ActivationProblem.UNAVAILABLE -> R.string.faces_activation_unavailable_title to R.string.faces_activation_unavailable_message
  }
  item(key = "problem") {
    FluidInlineMessage(
      message = stringResource(message),
      title = stringResource(title),
      tone = FluidTone.Warning,
      modifier = Modifier.padding(bottom = ActivationDimens.BlockGap).semantics { liveRegion = LiveRegionMode.Polite },
    )
  }
  // Rileggere il testo (o riprovare) ha senso quando qualcosa puo' cambiare; "Aggiorna l'app" no: si chiude.
  if (problem != ActivationProblem.NEEDS_UPDATE) {
    item(key = "retry") {
      FluidButton(
        text = stringResource(R.string.faces_activation_retry),
        onClick = onRetry,
        style = FluidButtonStyle.Filled,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    }
  }
  closeButton(onClose)
}

private fun LazyListScope.closeButton(onClose: () -> Unit) {
  item(key = "close") {
    FluidButton(
      text = stringResource(R.string.faces_activation_close),
      onClick = onClose,
      style = FluidButtonStyle.Tinted,
      size = FluidButtonSize.Large,
      fillWidth = true,
      modifier = Modifier.padding(top = ActivationDimens.TightGap, bottom = GalleryDimens.Gutter),
    )
  }
}
