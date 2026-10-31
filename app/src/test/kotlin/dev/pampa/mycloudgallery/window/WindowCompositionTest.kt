package dev.pampa.mycloudgallery.window

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.notify.OpenMemoriesRequests
import dev.pampa.mycloudgallery.notify.ServeOpenMemoriesRequests
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Cio' che una finestra sa di se' dentro la composizione (Fase 10, P4, D16, D18): chi serve la notifica quando le finestre sono due, e la pagina da cui parte una finestra
 * nuova. Due finestre finte nella stessa composizione, ognuna col suo ruolo.
 */
@RunWith(RobolectricTestRunner::class)
class WindowCompositionTest {

  private val rule = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(rule)

  // --- chi serve la notifica ------------------------------------------------------------------------------------------------

  @Test
  fun conDueFinestreIlTocco_sullaNotifica_loServeSoloLaPrincipale_unaVoltaSola() {
    val requests = OpenMemoriesRequests()
    var servedByPrimary = 0
    var servedBySecondary = 0
    // La secondaria compare PRIMA nella composizione: se bastasse arrivare per prima, la richiesta la prenderebbe lei.
    rule.setContent {
      Column {
        CompositionLocalProvider(LocalWindowRole provides WindowRole.SECONDARY) {
          ServesEntryRequests { ServeOpenMemoriesRequests(requests) { servedBySecondary++ } }
        }
        CompositionLocalProvider(LocalWindowRole provides WindowRole.PRIMARY) {
          ServesEntryRequests { ServeOpenMemoriesRequests(requests) { servedByPrimary++ } }
        }
      }
    }
    requests.request()
    rule.waitForIdle()

    assertThat(servedByPrimary).isEqualTo(1)
    assertThat(servedBySecondary).isEqualTo(0)
    assertThat(requests.pending.value).isFalse()
    // Una richiesta nuova, di nuovo la principale.
    requests.request()
    rule.waitForIdle()
    assertThat(servedByPrimary).isEqualTo(2)
    assertThat(servedBySecondary).isEqualTo(0)
  }

  @Test
  fun senzaLaPrincipaleLaRichiestaRestaInAttesa_finoAChePassaAllaSecondaria() {
    val requests = OpenMemoriesRequests()
    var served = 0
    var role by androidx.compose.runtime.mutableStateOf(WindowRole.SECONDARY)
    rule.setContent {
      CompositionLocalProvider(LocalWindowRole provides role) {
        ServesEntryRequests { ServeOpenMemoriesRequests(requests) { served++ } }
      }
    }
    requests.request()
    rule.waitForIdle()
    // Solo la secondaria e' composta: non serve, la richiesta aspetta (la principale e' da riportare davanti).
    assertThat(served).isEqualTo(0)
    assertThat(requests.pending.value).isTrue()

    // La principale si chiude: la secondaria prende il suo posto e serve la richiesta in attesa.
    rule.runOnUiThread { role = WindowRole.PRIMARY }
    rule.waitForIdle()
    assertThat(served).isEqualTo(1)
    assertThat(requests.pending.value).isFalse()
  }

  @Test
  fun unaFinestraSenzaRuoloEPrincipale() {
    val requests = OpenMemoriesRequests().also { it.request() }
    var served = 0
    rule.setContent { ServesEntryRequests { ServeOpenMemoriesRequests(requests) { served++ } } }
    rule.waitForIdle()
    assertThat(served).isEqualTo(1)
  }

  // --- la pagina da cui parte una finestra ---------------------------------------------------------------------------------------

  private class Calls {
    var map = 0
    val albums = mutableListOf<AlbumId>()
    val memories = mutableListOf<MemoryId>()
  }

  private fun showStart(request: WindowStartRequest?, calls: Calls) {
    rule.setContent {
      CompositionLocalProvider(LocalWindowStart provides request) {
        WindowStartEffect(onMap = { calls.map++ }, onAlbum = { calls.albums += it }, onMemory = { calls.memories += it })
      }
    }
    rule.waitForIdle()
  }

  @Test
  fun laFinestraCheParteDaUnAlbum_apreLaPaginaDellAlbum_unaVoltaSola() {
    val album = AlbumId.random()
    val calls = Calls()
    val request = WindowStartRequest(WindowTarget.Album(album))
    var tick by mutableIntStateOf(0)
    rule.setContent {
      CompositionLocalProvider(LocalWindowStart provides request) {
        @Suppress("UNUSED_EXPRESSION") tick
        WindowStartEffect(onMap = { calls.map++ }, onAlbum = { calls.albums += it }, onMemory = { calls.memories += it })
      }
    }
    rule.waitForIdle()
    // Anche dopo una ricomposizione l'evento e' uno solo.
    rule.runOnUiThread { tick++ }
    rule.waitForIdle()

    assertThat(calls.albums).containsExactly(album)
    assertThat(calls.map).isEqualTo(0)
    assertThat(calls.memories).isEmpty()
  }

  @Test
  fun laFinestraCheParteDaUnRicordoODallaMappa() {
    val memory = MemoryId("0123456789abcdefghjkmnpqr0")
    val memoryCalls = Calls()
    showStart(WindowStartRequest(WindowTarget.Memory(memory)), memoryCalls)
    assertThat(memoryCalls.memories).containsExactly(memory)
    assertThat(memoryCalls.map).isEqualTo(0)
  }

  @Test
  fun laFinestraCheParteDallaMappa_apreLaMappa() {
    val calls = Calls()
    showStart(WindowStartRequest(WindowTarget.Map), calls)
    assertThat(calls.map).isEqualTo(1)
    assertThat(calls.albums).isEmpty()
  }

  @Test
  fun laLibreriaELaPaginaDiSempre_enessunaRichiestaNonApreNiente() {
    val libraryCalls = Calls()
    showStart(WindowStartRequest(WindowTarget.Library), libraryCalls)
    assertThat(libraryCalls.map + libraryCalls.albums.size + libraryCalls.memories.size).isEqualTo(0)
  }

  @Test
  fun senzaLaRichiestaDellaFinestraNonSuccedeNiente() {
    val calls = Calls()
    showStart(null, calls)
    assertThat(calls.map + calls.albums.size + calls.memories.size).isEqualTo(0)
  }
}
