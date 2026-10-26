package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.ai.AiDiagnostics
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
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
 * DG-2 e DG-5 della verifica funzionale della Fase 6, sulla sezione "Ricerca" della Diagnostica montata come nell'app (telefono vero, NAS finto,
 * motori finti): i guasti dei modelli arrivano con il loro codice (prima `AI_MODEL_FETCH_FAILED`, `AI_MODEL_MISMATCH` e `AI_MODEL_NO_SPACE` non li
 * emetteva nessuno), `AI_MODEL_MISSING` conta solo i modelli che servono a questo dispositivo (prima "models=2" per sempre: il ritaglio sta nell'APK
 * e il 440M di un tablet che non analizza non manca a nessuno) e lo spazio dei modelli e' un numero.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class AiDiagnosticsTest {

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

  private fun List<DiagItem>.byCode(code: dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode): DiagItem? = firstOrNull { it.code == code }

  private fun DiagItem.num(key: String): Long = (export[key] as ExportValue.Num).value

  @Test
  fun dg5_iModelliMancantiSonoSoloQuelliCheServonoAQuestoDispositivo() = runBlocking<Unit> {
    val device = rig.founder()
    val items = AiDiagnostics(device.stack).collect()
    val missing = items.byCode(DiagCodes.AI_MODEL_MISSING)
    assertThat(missing).isNotNull()
    // Il fondatore e' un Indicizzatore: il 270M e il 440M (non il ritaglio, che sta nell'APK).
    assertThat(device.stack.ai.indexer.value).isTrue()
    assertThat(missing!!.num("models")).isEqualTo(2)
    assertThat(ModelCatalog.ALL.size).isEqualTo(3)
  }

  /** Le chiavi della sezione Ricerca non erano nell'elenco chiuso dell'esportazione: il file le lasciava fuori in silenzio. */
  @Test
  fun dg5_leChiaviDellaSezioneRicercaSonoNellElencoDellEsportazione() = runBlocking<Unit> {
    val device = rig.founder()
    // Con qualche guasto dei modelli in piu', cosi' escono anche le voci dei contatori.
    device.stack.localModels.manager.ensure(ModelCatalog.QUERY_270M.id, ModelTrigger.USER_NOW, 1.minutes)
    val items = AiDiagnostics(device.stack).collect()
    val outside = items.flatMap { it.export.keys }.filterNot(dev.pampa.mycloudgallery.feature.diagnostics.export.ExportKeys::isAllowed).distinct()
    assertThat(outside).isEmpty()
  }

  @Test
  fun dg5_loSpazioDeiModelliEUnNumeroNelloStatoDellaRicerca() = runBlocking<Unit> {
    val device = rig.founder()
    val state = AiDiagnostics(device.stack).collect().byCode(DiagCodes.SEARCH_STATE)
    assertThat(state).isNotNull()
    assertThat(state!!.export).containsKey("modelsBytes")
    assertThat(state.num("modelsBytes")).isAtLeast(0)
  }

  @Test
  fun dg2_unoScaricamentoFallitoArrivaInDiagnosticaConIlSuoCodice() = runBlocking<Unit> {
    val device = rig.founder()
    // Il fetcher del banco non ha niente da dare (`NotFound` per ogni sorgente): nessuna sorgente ha il modello.
    val result = device.stack.localModels.manager.ensure(ModelCatalog.QUERY_270M.id, ModelTrigger.USER_NOW, 1.minutes)
    assertThat(result).isEqualTo(EnsureResult.Failed(ModelFailCode.FETCH_FAILED))

    val failed = AiDiagnostics(device.stack).collect().byCode(DiagCodes.AI_MODEL_FETCH_FAILED)
    assertThat(failed).isNotNull()
    assertThat(failed!!.num("count")).isAtLeast(1)
  }
}
