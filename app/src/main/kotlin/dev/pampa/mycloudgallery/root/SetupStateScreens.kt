package dev.pampa.mycloudgallery.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustration
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.core.family.contract.BlockReason

// Le schermate degli stati del dispositivo che non sono ne' l'onboarding ne' la galleria. L'attesa dell'approvazione
// e' quella di feature-onboarding (codice e QR della chiave, P6); qui "azzerato" e "fermo".

/**
 * Il dispositivo si e' azzerato dopo la segnalazione di smarrimento (E-S2). "Configura di nuovo" dimentica il diario
 * dell'azzeramento e riporta al benvenuto: il dispositivo rientra con una chiave nuova e va riapprovato.
 */
@Composable
fun WipedScreen(onReconfigure: () -> Unit) {
  FluidScreen(
    title = stringResource(R.string.wiped_title),
    subtitle = stringResource(R.string.wiped_subtitle),
  ) {
    item {
      Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ScreenGap),
      ) {
        GalleryIllustration(icon = Icons.Rounded.RestartAlt, tone = GalleryIllustrationTone.Brand)
        FluidEmptyState(
          title = stringResource(R.string.wiped_reason_title),
          detail = stringResource(R.string.wiped_detail),
        )
        FluidButton(
          text = stringResource(R.string.wiped_reconfigure),
          onClick = onReconfigure,
          size = FluidButtonSize.Large,
        )
      }
    }
  }
}

/** Fermo per un motivo che l'utente deve conoscere. */
@Composable
fun BlockedScreen(reason: BlockReason) {
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
  }
}

private val ScreenPadding = 24.dp
private val ScreenGap = 16.dp
