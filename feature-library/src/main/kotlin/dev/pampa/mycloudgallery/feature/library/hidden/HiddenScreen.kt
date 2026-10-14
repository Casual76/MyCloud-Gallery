package dev.pampa.mycloudgallery.feature.library.hidden

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.theme.FluidLoading
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import dev.pampa.mycloudgallery.core.security.screen.SecureScreen
import dev.pampa.mycloudgallery.feature.library.ActionNotice
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEmptyState
import dev.pampa.mycloudgallery.feature.library.collections.CollectionGridPage
import dev.pampa.mycloudgallery.feature.library.grid.gridTopChromeHeight
import dev.pampa.mycloudgallery.feature.library.grid.ChromeLabelText
import dev.pampa.mycloudgallery.feature.library.grid.GridTopChrome
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsDimens
import dev.pampa.mycloudgallery.feature.library.collections.GridEnvironment
import dev.pampa.mycloudgallery.feature.library.collections.SelectionAction

/**
 * La pagina dei **Nascosti** (D17, §2.4): la credenziale di sistema a ogni ingresso con un minuto di tolleranza (E-H3),
 * `SecureScreen` (screenshot bloccati, E-S5) e recenti oscurati, gli elementi letti dal database solo dopo lo sblocco, il
 * viewer aperto da qui con `secure = true`.
 *
 * @param onBack il tasto indietro; null nel pannello di dettaglio del tablet.
 * @param backdropOut il fondale per la pillola di `:app`; null dove la pillola non c'e'.
 */
@Composable
fun HiddenRoute(
  onOpenViewer: (ViewerRequest) -> Unit,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  viewModel: HiddenViewModel = hiltViewModel(),
) {
  HiddenHost(
    controller = viewModel.controller,
    environment = viewModel.environment,
    keepRecentsHidden = viewModel::keepRecentsHidden,
    onOpenViewer = onOpenViewer,
    modifier = modifier,
    onBack = onBack,
    contentPadding = contentPadding,
    backdropOut = backdropOut,
    authenticator = rememberHiddenAuthenticator(),
  )
}

/**
 * La pagina dei Nascosti con tutto cio' che fa con la finestra e con il ciclo di vita ([GatedWindow]: `SecureScreen`,
 * recenti oscurati, ingresso e uscita, sblocco automatico), intorno alla pagina vera ([HiddenScreen]).
 */
@Composable
internal fun HiddenHost(
  controller: HiddenController,
  environment: GridEnvironment,
  keepRecentsHidden: () -> Boolean,
  onOpenViewer: (ViewerRequest) -> Unit,
  authenticator: SystemCredentialAuthenticator?,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
) {
  val state by controller.state.collectAsState()
  val notice by controller.notice.collectAsState()
  val unlock = GatedWindow(
    texts = HiddenGateTexts,
    authenticator = authenticator,
    keepRecentsHidden = keepRecentsHidden,
    locked = state == HiddenUiState.Locked,
    authenticating = state == HiddenUiState.Authenticating,
    onShown = controller::shown,
    onHidden = controller::hidden,
    onUnlock = controller::unlock,
    onUnlockUnavailable = controller::unlockUnavailable,
  )

  HiddenScreen(
    state = state,
    environment = environment,
    onUnlock = unlock,
    onRetry = controller::shown,
    onOpenSecuritySettings = rememberOpenSecuritySettings(),
    onShow = controller::show,
    onOpenViewer = onOpenViewer,
    modifier = modifier,
    onBack = onBack,
    contentPadding = contentPadding,
    backdropOut = backdropOut,
    notice = notice,
    onDismissNotice = controller::dismissNotice,
  )
}

/**
 * La pagina dei Nascosti per uno [state]: i media quando sono sbloccati, altrimenti una schermata che dice perche' e'
 * chiusa e cosa fare. Senza effetti collaterali: la provano i test e gli screenshot passando lo stato.
 */
@Composable
internal fun HiddenScreen(
  state: HiddenUiState,
  environment: GridEnvironment,
  onUnlock: () -> Unit,
  onRetry: () -> Unit,
  onOpenSecuritySettings: () -> Unit,
  onShow: (Set<MediaId>) -> Unit,
  onOpenViewer: (ViewerRequest) -> Unit,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(),
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  notice: ActionNotice? = null,
  onDismissNotice: () -> Unit = {},
) {
  if (state is HiddenUiState.Content) {
    CollectionGridPage(
      title = stringResource(R.string.hidden_title),
      items = state.items,
      environment = environment,
      secure = true,
      onOpenViewer = onOpenViewer,
      empty = {
        CollectionEmptyState(
          icon = Icons.Rounded.Lock,
          title = stringResource(R.string.hidden_empty_title),
          detail = stringResource(R.string.hidden_empty_detail),
        )
      },
      modifier = modifier,
      onBack = onBack,
      contentPadding = contentPadding,
      backdropOut = backdropOut,
      footnote = stringResource(R.string.hidden_footnote),
      actionsFor = { _, _ -> listOf(SelectionAction(Icons.Rounded.Visibility, R.string.selection_show, onShow)) },
      notice = notice,
      onDismissNotice = onDismissNotice,
    )
    return
  }
  HiddenClosedPage(
    title = stringResource(R.string.hidden_title),
    texts = HiddenGateTexts,
    state = state,
    onUnlock = onUnlock,
    onRetry = onRetry,
    onOpenSecuritySettings = onOpenSecuritySettings,
    modifier = modifier,
    onBack = onBack,
    contentPadding = contentPadding,
    backdropOut = backdropOut,
  )
}

/**
 * Una schermata protetta quando non si vede: chiusa, in attesa della schermata di sistema, rifiutata o in lettura. La
 * usano i Nascosti e gli Eliminati di recente (con le loro parole, [texts]).
 */
@Composable
internal fun HiddenClosedPage(
  title: String,
  texts: GateTexts,
  state: HiddenUiState,
  onUnlock: () -> Unit,
  onRetry: () -> Unit,
  onOpenSecuritySettings: () -> Unit,
  modifier: Modifier,
  onBack: (() -> Unit)?,
  contentPadding: PaddingValues,
  backdropOut: ((GlassBackdropState) -> Unit)?,
) {
  val backdrop = rememberGlassBackdrop()
  if (backdropOut != null) SideEffect { backdropOut(backdrop) }
  val topChrome = gridTopChromeHeight()

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(top = topChrome, bottom = contentPadding.calculateBottomPadding()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        when (state) {
          HiddenUiState.Locked -> CollectionEmptyState(
            icon = Icons.Rounded.Lock,
            title = stringResource(texts.lockedTitle),
            detail = stringResource(texts.lockedDetail),
            actionLabel = stringResource(R.string.hidden_unlock),
            onAction = onUnlock,
          )
          HiddenUiState.Authenticating -> Waiting(stringResource(R.string.hidden_unlocking))
          HiddenUiState.Loading -> Waiting(stringResource(R.string.hidden_loading))
          HiddenUiState.Failed -> CollectionEmptyState(
            icon = Icons.Rounded.Lock,
            title = stringResource(R.string.hidden_failed_title),
            detail = stringResource(R.string.hidden_failed_detail),
            tone = GalleryIllustrationTone.Caution,
            actionLabel = stringResource(R.string.hidden_retry),
            onAction = onRetry,
          )
          is HiddenUiState.Denied -> DeniedState(texts, state.reason, onUnlock, onOpenSecuritySettings)
          is HiddenUiState.Content -> Unit
        }
      }
    }
    // La stessa chrome delle griglie (G2): l'etichetta con il nome e, se c'e', l'indietro. Niente comandi: e' chiusa.
    GridTopChrome(
      grid = null,
      backdrop = backdrop,
      label = ChromeLabelText(title, null),
      topChrome = topChrome,
      modifier = Modifier.align(Alignment.TopStart),
      onBack = onBack,
      showCommands = false,
    ) {}
  }
}

@Composable
private fun DeniedState(texts: GateTexts, reason: HiddenDenial, onUnlock: () -> Unit, onOpenSecuritySettings: () -> Unit) {
  if (reason == HiddenDenial.NO_SCREEN_LOCK) {
    CollectionEmptyState(
      icon = Icons.Rounded.Lock,
      title = stringResource(R.string.hidden_no_screen_lock_title),
      detail = stringResource(R.string.hidden_no_screen_lock_detail),
      tone = GalleryIllustrationTone.Caution,
      actionLabel = stringResource(R.string.hidden_open_security_settings),
      onAction = onOpenSecuritySettings,
    )
    return
  }
  val detail = when (reason) {
    HiddenDenial.CANCELLED -> texts.cancelledDetail
    HiddenDenial.INTERRUPTED -> R.string.hidden_interrupted_detail
    HiddenDenial.LOCKED_OUT -> R.string.hidden_lockout_detail
    HiddenDenial.UNAVAILABLE -> R.string.hidden_unavailable_detail
    HiddenDenial.ERROR, HiddenDenial.NO_SCREEN_LOCK -> R.string.hidden_error_detail
  }
  CollectionEmptyState(
    icon = Icons.Rounded.Lock,
    title = stringResource(texts.lockedTitle),
    detail = stringResource(detail),
    tone = if (reason == HiddenDenial.CANCELLED) GalleryIllustrationTone.Brand else GalleryIllustrationTone.Caution,
    actionLabel = stringResource(R.string.hidden_unlock),
    onAction = onUnlock,
    actionStyle = FluidButtonStyle.Filled,
  )
}

@Composable
private fun Waiting(text: String) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(CollectionsDimens.StateGap),
  ) {
    FluidLoading()
    Text(
      text = text,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
