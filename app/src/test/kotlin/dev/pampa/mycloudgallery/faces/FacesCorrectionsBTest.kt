package dev.pampa.mycloudgallery.faces

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.di.FacesModule
import dev.pampa.mycloudgallery.feature.people.contract.PersonPageState
import dev.pampa.mycloudgallery.feature.people.page.PersonPageController
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Correzioni dopo le revisioni della Fase 8, pacchetto B (sezione Persone delle Raccolte e prima apertura), nella parte di `:app`:
 * - **AT-2**: prima del montaggio una persona non e' "non trovata" ma "non ancora letta", e la sezione delle Raccolte non c'e' (non dice "spenta");
 * - **AT-3**: il fornitore della sezione e' uno solo per processo, con i suoi collettori;
 * - **P8, C130, C131**: la sezione non apre archivi per disegnarsi.
 *
 * Il grafo e' quello vero ma **non montato** (`FacesGraph` con una costruzione che non riesce mai: come nei secondi dopo l'avvio a freddo, prima che la galleria sia
 * partita e i dati personali agganciati). Il montaggio vero, con la sezione che segue lo stato e il ruolo, e' in `FacesIntegrationTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FacesCorrectionsBTest {

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

  private fun unmounted(scope: kotlinx.coroutines.CoroutineScope) = FacesGraph(scope, { null }, MutableStateFlow(false))

  /**
   * AT-2: la facciata delle persone prima del montaggio **non emette niente** per una persona (la pagina resta `Loading`): prima emetteva `null`, che la pagina
   * leggeva come `NotFound` ("Questa persona non c'e'") a chi aveva solo la pagina ripristinata dopo la morte del processo.
   */
  @Test
  fun at2_primaDelMontaggioUnaPersonaNonEUnaPersonaNonTrovata() = runTest(UnconfinedTestDispatcher()) {
    val graph = unmounted(backgroundScope)
    val facade = FacesPeopleFacade(graph, backgroundScope)
    val id = PersonId.random()

    val seen = ArrayList<Any?>()
    backgroundScope.launch { facade.person(id).collect { seen += it } }
    assertThat(graph.live.value).isNull()
    assertThat(seen).isEmpty()

    val page = PersonPageController(id, facade, facade, backgroundScope)
    assertThat(page.ui.value.page).isEqualTo(PersonPageState.Loading)
  }

  /**
   * AT-2: prima del montaggio la sezione delle Raccolte non c'e' (il fornitore consegna `null`: come dice L297), e gli ingressi dicono "non ancora", non "spenta".
   * Prima la facciata dell'interruttore rispondeva `Off` e la sezione proponeva "Attiva riconoscimento dei volti" a chi aveva gia' Persone accesa.
   */
  @Test
  fun at2_primaDelMontaggioLaSezioneNonCe() = runTest(UnconfinedTestDispatcher()) {
    val graph = unmounted(backgroundScope)
    val provider = peopleSectionProvider(resources, graph, backgroundScope)
    assertThat(graph.live.value).isNull()
    assertThat(provider.group.value).isNull()
    assertThat(peopleSectionInputs(graph, flowOf(true)).first()).isNull()
  }

  /**
   * AT-3: il fornitore della sezione e' `@Singleton`. Senza, ogni `CollectionsLayoutViewModel` (a ogni apertura della scheda Raccolte) riceveva un fornitore
   * nuovo, con tre collettori `Eagerly` su `graph.scope` che non finivano piu'.
   */
  @Test
  fun at3_ilFornitoreDellaSezioneEUnoSoloPerProcesso() {
    val methods = FacesModule::class.java.declaredMethods.filter { it.name == "peopleSection" }
    assertThat(methods).hasSize(1)
    assertThat(methods.single().isAnnotationPresent(Singleton::class.java)).isTrue()
  }

  /**
   * P8, C130, C131: la sezione si disegna da cio' che le persone montate hanno gia' (interruttore, lista, stato del calcolo) e **non apre archivi** ne' prima ne'
   * dopo il montaggio: le porte della sezione e delle facciate non nominano `FacesStores` ne' aprono un archivio.
   */
  @Test
  fun p8_laSezioneNonApreArchiviPerDisegnarsi() {
    val base = File("src/main/kotlin/dev/pampa/mycloudgallery/faces")
    val ports = withoutComments(File(base, "FacesScreenPorts.kt").readText())
    val section = ports.substringAfter("internal fun peopleSectionInputs")
    for (opener in listOf("FacesStores", "archives.open(", "stores.archives()", "stores.replicaStore()", "stores.publishStore()", "opened()")) {
      assertThat(ports).doesNotContain(opener)
    }
    assertThat(section).contains("faces.live.flatMapLatest")
  }

  private fun withoutComments(source: String): String =
    source.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""(?m)(^|\s)//.*$"""), "")
}
