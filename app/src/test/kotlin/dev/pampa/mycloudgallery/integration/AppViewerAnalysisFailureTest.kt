package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.ai.AppViewerAnalysis
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.menu.MediaAnalysis
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * XS3-3 (Pacchetto XF, giro 3), il visore: (1) l'**insuccesso di `analyzeNow`** (il catalogo non ha il media, il lavoro non si accoda) e' **osservabile** da chi ha toccato,
 * nello stato (`MediaAnalysis.askFailed`), non un crash; (2) un errore degli **ascoltatori di sistema** (la registrazione rifiutata) non fa cadere lo stato del visore. Il
 * montaggio e' vero (`AiGraph` vero, `AppViewerAnalysis` vero); manca solo un catalogo che non ha il media. Mutazioni: togliere la segnalazione dell'insuccesso
 * (`askFailures`); togliere il `catch` degli eventi dell'ambiente (l'errore risale a chi raccoglie e il visore non emette niente).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class AppViewerAnalysisFailureTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private lateinit var rig: AiRig

  @Before
  fun setUp() {
    rig = AiRig(app, temp.root)
  }

  @After
  fun tearDown() = rig.close()

  @Test
  fun unaRichiestaNonAccodataSiVedeNelloStatoEUnErroreDellAmbienteNonFaCadereIlVisore() = runBlocking<Unit> {
    // Gli eventi dell'ambiente si guastano (la registrazione di sistema rifiuta): il visore continua.
    rig.triggersOverride = flow<Unit> { throw IllegalStateException("registrazione rifiutata") }
    val device = rig.founder()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      // Un catalogo che non ha il media: la richiesta non si puo' registrare.
      val viewer = AppViewerAnalysis(app, { null }, device.stack.ai, MutableStateFlow(0L), scope)
      val id = MediaId(UUID(0L, 77L).toString())
      val seen = CopyOnWriteArrayList<MediaAnalysis>()
      val watching = scope.launch { viewer.observe(id).collect { seen += it } }
      withTimeout(Attese.TETTO) { while (seen.isEmpty()) delay(20) }
      assertThat(seen.last().askFailed).isFalse()

      viewer.analyzeNow(id)
      withTimeout(Attese.TETTO) { while (seen.none { it.askFailed }) delay(20) }
      // Chi ha toccato lo vede, e la richiesta non e' nel registro.
      assertThat(seen.last().askFailed).isTrue()
      assertThat(device.stack.ai.urgent.urgent()).isEmpty()
      watching.cancel()
    } finally {
      scope.cancel()
    }
  }
}
