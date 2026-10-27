package dev.pampa.mycloudgallery.feature.people.activation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.IndexerState
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesRequirements
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesSwitch
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

// Il banco delle schermate di attivazione, dell'avviso e di Impostazioni -> Persone (Fase 8, P7): tema chiaro o scuro, testo a 1,3x (accessibilita'), e i
// pezzi finti che le porte legano. I controllori girano su un dispatcher immediato: ogni lancio finisce prima di tornare.

/** Mostra [content] nel tema chiaro o scuro, con il testo a [fontScale] se dato (1,3x per l'accessibilita'). */
internal fun ComposeContentTestRule.setPeopleContent(dark: Boolean = false, fontScale: Float? = null, content: @Composable () -> Unit) {
  setContent {
    MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) { content() }
    }
  }
  waitForIdle()
}

internal fun immediateScope(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined + Job())

/** Una generazione di prova. */
internal val sampleGeneration: FaceGeneration = FaceGeneration.fromBytes(ByteArray(16) { (it + 7).toByte() })

internal fun consent(version: Int = FacesSwitch.CURRENT_TEXT_VERSION): FacesActivationConsent = FacesActivationConsent(version, 1L)

/** Le porte di `:app`, a mano: l'interruttore finto (di P0), i requisiti, il ruolo e le informazioni di Impostazioni. */
internal class TestPorts(
  override val switch: FakeFacesSwitch = FakeFacesSwitch(),
  override val requirements: FakeFacesRequirements = FakeFacesRequirements(),
  override var indexerRole: ActivationIndexerRole? = null,
  override var activatedBy: FacesActivatedBy? = null,
) : ActivationPorts {
  override val facts = MutableStateFlow(PeopleSettingsFacts())
}

internal fun metRequirements(localOnly: Boolean = false) = FakeFacesRequirements(
  privateArea = PrivateAreaState.Declared("test"),
  indexers = IndexerState(accountIndexers = 1, thisDevice = true, canBecomeIndexer = true),
  localOnly = localOnly,
)

internal fun activeSwitch(generation: FaceGeneration = sampleGeneration, here: HereState = HereState.ACTIVE): FakeFacesSwitch =
  FakeFacesSwitch(initial = FacesState.On(generation, here))

internal fun awaitingSwitch(generation: FaceGeneration = sampleGeneration): FakeFacesSwitch =
  FakeFacesSwitch(initial = FacesState.AwaitingNotice(generation, FacesSwitch.CURRENT_TEXT_VERSION))

internal val emptyLocal = FacesLocal.EMPTY
