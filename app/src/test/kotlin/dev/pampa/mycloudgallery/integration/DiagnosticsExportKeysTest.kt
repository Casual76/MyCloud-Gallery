package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.feature.diagnostics.aggregate.DiagnosticsSnapshot
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.export.DiagnosticsExporter
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportKeys
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Revisione di sicurezza della Fase 2, F4: l'esportazione accetta solo codici del catalogo e chiavi dell'elenco chiuso
 * (`ExportKeys`). Qui i contributori **veri** di rete e fiducia, collegati da `:app` sopra il NAS simulato, non devono
 * perdere niente per strada: ogni loro chiave e' nell'elenco e ogni valore arriva nel file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DiagnosticsExportKeysTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend = TestNas.backend(time)
  private val devices = mutableListOf<TestDevice>()

  private fun device(name: String): TestDevice =
    TestDevice(app, File(temp.root, name), TestPlatform(time, backend)).also { devices += it }

  @After
  fun tearDown() = devices.forEach { it.close() }

  @Test
  fun leChiaviDeiContributoriDiReteEFiduciaSonoTutteNellElenco() = runBlocking {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    val member = device("tablet")
    member.graph.start()
    member.graph.joinWith(phone.graph.inviteMember())

    for (graph in listOf(phone.graph, member.graph)) {
      val items: List<DiagItem> = graph.networkDiagnostics.collect() + graph.trustDiagnostics.collect()
      assertThat(items).isNotEmpty()
      val outsideKeys = items.flatMap { it.export.keys }.filterNot(ExportKeys::isAllowed).distinct()
      assertWithMessage("chiavi fuori da ExportKeys: $outsideKeys").that(outsideKeys).isEmpty()
      val outsideCodes = items.map { it.code }.filterNot { it in DiagCatalog }.map { it.id }
      assertWithMessage("codici fuori dal catalogo: $outsideCodes").that(outsideCodes).isEmpty()

      // Nel file ogni voce ha tutti i suoi valori (nessuno lasciato fuori dagli elenchi chiusi).
      val snapshot = DiagnosticsSnapshot(items.groupBy { it.section }, recentEvents = 0)
      val sections = JSONObject(DiagnosticsExporter.render(snapshot).text).getJSONObject("sections")
      for ((section, sectionItems) in items.groupBy { it.section }) {
        val exported = sections.getJSONArray(section.name)
        sectionItems.forEachIndexed { index, item ->
          val entry = exported.getJSONObject(index)
          assertThat(entry.getString("code")).isEqualTo(item.code.id)
          val count = if (entry.has("values")) entry.getJSONObject("values").length() else 0
          assertWithMessage("valori di ${item.code.id}").that(count).isEqualTo(item.export.size)
        }
      }
    }
  }
}
