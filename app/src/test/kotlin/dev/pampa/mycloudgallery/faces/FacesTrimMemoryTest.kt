package dev.pampa.mycloudgallery.faces

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.integration.FacesRig
import dev.pampa.mycloudgallery.integration.TestNas
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * I6 (dalla fase H, CU): sul grafo vero dei volti, lo svuotamento dei ritagli **per memoria** (`FacesGraph.onTrimMemory`) non e' una revoca per le
 * Raccolte ([AppCollectionFaceCrops.revocations] non cambia: i volti tenuti restano, niente ritagli rifatti), e con l'app nascosta i ritagli nuovi
 * aspettano che l'app torni visibile (il primo piano di `ProcessWatcher`, `AppGraph.appContext`). Le revoche di privacy restano: la cancellazione
 * (`clear`) cambia il segno come prima.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class FacesTrimMemoryTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private var rig: FacesRig? = null

  @After
  fun close() {
    rig?.close()
  }

  @Test
  fun lAppNascostaSvuotaPerMemoria_senzaRevoca_eRitagliaSoloAlRitorno() = runBlocking<Unit> {
    val faces = FacesRig(app, temp.root).also { rig = it }
    val node = faces.node("telefono", faces.ai.foundedDevice(), TestNas.ADMIN_USER)
    val live = node.live()
    val collection = AppCollectionFaceCrops(node.faces)
    val appContext = node.device.graph.appContext
    val first = withTimeout(5_000) { collection.revocations.first() }

    // L'app va in secondo piano: la finestra si ferma, poi il sistema chiede memoria.
    appContext.update(inForeground = false, profileActive = true)
    node.faces.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    assertThat(live.crops.cachedBytes).isEqualTo(0)
    assertThat(withTimeout(5_000) { collection.revocations.first() }).isEqualTo(first)
    assertThat(live.crops.paused.value).isTrue()

    // Torna visibile: i ritagli ripartono, sempre senza revoca.
    appContext.update(inForeground = true, profileActive = true)
    withTimeout(5_000) { live.crops.paused.first { !it } }
    assertThat(withTimeout(5_000) { collection.revocations.first() }).isEqualTo(first)

    // Poca memoria in primo piano: svuota, nessuna attesa, nessuna revoca (niente lampo dei volti visibili).
    node.faces.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
    assertThat(live.crops.paused.value).isFalse()
    assertThat(withTimeout(5_000) { collection.revocations.first() }).isEqualTo(first)

    // La cancellazione (una ragione di privacy) revoca come prima.
    live.crops.clear()
    assertThat(withTimeout(5_000) { collection.revocations.first() }).isNotEqualTo(first)
  }
}
