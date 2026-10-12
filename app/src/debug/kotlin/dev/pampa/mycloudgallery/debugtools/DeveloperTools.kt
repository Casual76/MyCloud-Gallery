package dev.pampa.mycloudgallery.debugtools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.designsystem.catalog.ComponentCatalogPlaceholder
import dev.pampa.mycloudgallery.core.family.contract.BlockReason
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.testing.FakeFamilySetupRepository
import kotlinx.serialization.Serializable

/** SOLO debug: il catalogo dei componenti di core-designsystem. */
@Serializable
data object CatalogRoute

/** SOLO debug: il fake dello stato del dispositivo (vedi SetupStateModule di src/debug). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DeveloperEntryPoint {
  fun fakeSetup(): FakeFamilySetupRepository
}

/**
 * SOLO build debug: gli strumenti per percorrere lo scaffold sul telefono, prima che esistano le
 * implementazioni vere (P1-P8):
 *  - l'ingresso al **Catalogo componenti** (in Impostazioni → Sviluppo), come nella Fase 1;
 *  - gli interruttori dello **stato finto** del dispositivo (onboarding, attesa, galleria, azzerato, fermo).
 *
 * La versione release di questo file (src/release) e' vuota: niente di qui e' referenziato in release,
 * quindi R8 non lo mette nell'APK (tools/check.ps1 cerca catalogo e fake nel dex di release).
 */
internal object DeveloperTools {

  fun NavGraphBuilder.destinations(nav: NavController) {
    composable<CatalogRoute> {
      ComponentCatalogPlaceholder(onBack = { nav.popBackStack() })
    }
  }

  fun settingsSection(nav: NavController): (@Composable () -> Unit)? = {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.debug_catalog_title),
        subtitle = stringResource(R.string.debug_catalog_detail),
        onClick = { nav.navigate(CatalogRoute) },
      )
      FluidListDivider()
      SetupStateRows()
    }
  }

  val stateSwitcher: (@Composable () -> Unit)? = {
    FluidListGroup { SetupStateRows() }
  }

  /** Con il fake, "Continua" nell'onboarding porta alla galleria (con P4 lo fara' lo stato vero). */
  @Composable
  fun onboardingDone(): () -> Unit {
    val fake = rememberFakeSetup()
    return { fake.state.value = SetupState.Ready(admin = true) }
  }
}

@Composable
private fun rememberFakeSetup(): FakeFamilySetupRepository {
  val context = LocalContext.current.applicationContext
  return remember(context) { EntryPointAccessors.fromApplication(context, DeveloperEntryPoint::class.java).fakeSetup() }
}

@Composable
private fun SetupStateRows() {
  val fake = rememberFakeSetup()
  val choices = listOf(
    R.string.debug_state_onboarding to SetupState.NotConfigured,
    R.string.debug_state_awaiting to SetupState.AwaitingApproval(code = DEBUG_CODE, since = 0L),
    R.string.debug_state_ready to SetupState.Ready(admin = true),
    R.string.debug_state_wiped to SetupState.Wiped(at = 0L),
    R.string.debug_state_blocked to SetupState.Blocked(BlockReason.NEEDS_APP_UPDATE),
  )
  choices.forEachIndexed { index, (label, state) ->
    if (index > 0) FluidListDivider()
    FluidListRow(
      title = stringResource(label),
      subtitle = stringResource(R.string.debug_state_detail),
      onClick = { fake.state.value = state },
    )
  }
}

/** Un codice d'esempio, nel formato a gruppi del codice di approvazione. */
private const val DEBUG_CODE = "K7QD-2M9X-FA4T"
