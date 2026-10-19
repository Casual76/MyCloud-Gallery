package dev.pampa.mycloudgallery.feature.settings.sharing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L'impostazione "Rimuovi la posizione" delle condivisioni (Fase 5B, P12; `docs/fase5-progetto.md` §1.3, §2.7, C18): spenta di default, vale subito per
 * chi la legge, resta dopo un riavvio dell'app e non tiene nient'altro che quel valore.
 */
@RunWith(AndroidJUnit4::class)
class SharingSettingsTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Before
  fun pulisci() {
    context.getSharedPreferences(SharedPrefsSharingSettings.FILE, Context.MODE_PRIVATE).edit().clear().commit()
  }

  @Test
  fun diDefaultLaPosizioneResta_C18() {
    assertThat(SharedPrefsSharingSettings(context).removeLocation.value).isFalse()
    assertThat(SharedPrefsSharingSettings.DEFAULT_REMOVE_LOCATION).isFalse()
  }

  @Test
  fun scrivereVaLeSubitoPerChiLeggeIlFlusso() = runTest {
    val settings = SharedPrefsSharingSettings(context)
    settings.setRemoveLocation(true)
    assertThat(settings.removeLocation.value).isTrue()
    settings.setRemoveLocation(false)
    assertThat(settings.removeLocation.value).isFalse()
  }

  @Test
  fun ilValoreRestaDopoUnRiavvio() = runTest {
    SharedPrefsSharingSettings(context).setRemoveLocation(true)
    // Un'altra istanza (l'app riaperta) legge quello scritto.
    assertThat(SharedPrefsSharingSettings(context).removeLocation.value).isTrue()
  }

  @Test
  fun tieneUnSoloValore_NienteDiPersonale() = runTest {
    SharedPrefsSharingSettings(context).setRemoveLocation(true)
    val all = context.getSharedPreferences(SharedPrefsSharingSettings.FILE, Context.MODE_PRIVATE).all
    assertThat(all.keys).containsExactly(SharedPrefsSharingSettings.KEY_REMOVE_LOCATION)
    assertThat(all.values.single()).isEqualTo(true)
  }
}

/** Un'impostazione in memoria per i test della pagina e dell'app. */
class FakeSharingSettings(initial: Boolean = false) : SharingSettings {
  private val flow = MutableStateFlow(initial)
  override val removeLocation: StateFlow<Boolean> = flow
  val writes = mutableListOf<Boolean>()

  override suspend fun setRemoveLocation(on: Boolean) {
    writes += on
    flow.value = on
  }
}

/** Il ViewModel della pagina scrive nell'impostazione e mostra quello che c'e'. */
@OptIn(ExperimentalCoroutinesApi::class)
class SharingViewModelTest {

  @Before
  fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

  @After
  fun reset() = Dispatchers.resetMain()

  @Test
  fun mostraLImpostazioneDiAdesso() {
    assertThat(SharingViewModel(FakeSharingSettings(false)).removeLocation.value).isFalse()
    assertThat(SharingViewModel(FakeSharingSettings(true)).removeLocation.value).isTrue()
  }

  @Test
  fun accendereEspegnereScriveNellImpostazione() {
    val settings = FakeSharingSettings()
    val viewModel = SharingViewModel(settings)
    viewModel.setRemoveLocation(true)
    assertThat(viewModel.removeLocation.value).isTrue()
    viewModel.setRemoveLocation(false)
    assertThat(viewModel.removeLocation.value).isFalse()
    assertThat(settings.writes).containsExactly(true, false).inOrder()
  }

  @Test
  fun laVoceDelleImpostazioniLeggeLoStessoValore() {
    val settings = FakeSharingSettings()
    val entry = SharingEntryViewModel(settings)
    assertThat(entry.removeLocation.value).isFalse()
    SharingViewModel(settings).setRemoveLocation(true)
    assertThat(entry.removeLocation.value).isTrue()
  }
}
