package dev.pampa.mycloudgallery.root

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.family.contract.BlockReason

// Le schermate degli stati del dispositivo che non sono ne' l'onboarding ne' la galleria. Scheletro P0:
// testi e struttura definitivi li decidono P6 (attesa) e P9 (azzerato, fermo). [extra] e' il punto in cui la
// build debug aggiunge i suoi interruttori (DeveloperTools); in release e' vuoto.

/** In attesa dell'approvazione: il codice da far confrontare all'admin (E-S14, sola lettura). */
@Composable
fun AwaitingApprovalScreen(code: String, extra: (@Composable () -> Unit)? = null) {
  FluidScreen(
    title = stringResource(R.string.awaiting_title),
    subtitle = stringResource(R.string.awaiting_subtitle),
  ) {
    item { FluidSectionHeader(title = stringResource(R.string.awaiting_code_title)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = code,
          subtitle = stringResource(R.string.awaiting_code_detail),
          disclosure = false,
        )
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.awaiting_note)) }
    if (extra != null) item { extra() }
  }
}

/** Il dispositivo si e' azzerato dopo la segnalazione di smarrimento (E-S2). */
@Composable
fun WipedScreen(extra: (@Composable () -> Unit)? = null) {
  FluidScreen(
    title = stringResource(R.string.wiped_title),
    subtitle = stringResource(R.string.wiped_subtitle),
  ) {
    item {
      FluidEmptyState(
        title = stringResource(R.string.wiped_title),
        detail = stringResource(R.string.wiped_detail),
      )
    }
    if (extra != null) item { extra() }
  }
}

/** Fermo per un motivo che l'utente deve conoscere. */
@Composable
fun BlockedScreen(reason: BlockReason, extra: (@Composable () -> Unit)? = null) {
  val detail = when (reason) {
    BlockReason.NEEDS_APP_UPDATE -> R.string.blocked_update
    BlockReason.REGISTRY_FORK -> R.string.blocked_fork
    BlockReason.WIPE_IN_PROGRESS -> R.string.blocked_wipe
    BlockReason.STORAGE_LOCKED -> R.string.blocked_storage
  }
  FluidScreen(title = stringResource(R.string.blocked_title)) {
    item {
      FluidEmptyState(
        title = stringResource(R.string.blocked_title),
        detail = stringResource(detail),
      )
    }
    if (extra != null) item { extra() }
  }
}
