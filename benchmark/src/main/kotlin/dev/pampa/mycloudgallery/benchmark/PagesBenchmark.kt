package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L'apertura delle **pagine principali al primo ingresso** dopo un avvio a freddo (Avvio istantaneo, 28/10): dal tocco al primo
 * fotogramma con l'ultimo stato conosciuto o con lo scheletro dimensionato, **mai una rotella**. La misura e' la sezione asincrona
 * che l'app apre al tocco e chiude al primo disegno della pagina (`AvvioTrace.PAGE_*`), piu' il fotogramma del RenderThread che la
 * segue ([PageOpenMetric]).
 *
 * Quattro prove, scenario `pagine` di `soglie.json`:
 *  - `collectionsPage`: la scheda Raccolte (`raccoltePageMs`);
 *  - `searchPage`: la scheda Cerca a riposo (`cercaPageMs`);
 *  - `albumPage`: la pagina di un album dalle Raccolte (`albumPageMs`);
 *  - `peoplePage`: la sezione Persone dalle Raccolte (`personePageMs`).
 *
 * Ogni iterazione e' un avvio a freddo (il primo ingresso e' quello che conta: dopo, la pagina e' in memoria). La variante di misura non
 * monta album e persone (nessun NAS, L287): `albumPage` e `peoplePage` si **saltano** con il motivo finche' la variante non li ha, mai un
 * falso verde.
 */
@RunWith(AndroidJUnit4::class)
class PagesBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  private var readiness: BenchReadiness? = null

  @Before
  fun prepare() {
    readiness = BenchSession.requireLibrary()
  }

  @Test
  fun collectionsPage() = measurePage(BenchmarkTarget.TRACE_PAGE_COLLECTIONS, "raccoltePage") { PagesUi.tab(device, "Raccolte") }

  @Test
  fun searchPage() = measurePage(BenchmarkTarget.TRACE_PAGE_SEARCH, "cercaPage") { PagesUi.tab(device, "Cerca") }

  @Test
  fun albumPage() {
    Assume.assumeTrue(AlbumBenchTarget.missingReason(readiness?.dataset), AlbumBenchTarget.seeded(readiness?.dataset))
    measurePage(BenchmarkTarget.TRACE_PAGE_ALBUM, "albumPage") {
      PagesUi.tab(device, "Raccolte")
      PagesUi.row(device, AlbumBenchTarget.BIG_TITLE)
    }
  }

  @Test
  fun peoplePage() {
    Assume.assumeTrue("La variante di misura non monta Persone (nessun NAS, L287): nessuna sezione Persone da aprire.", PagesUi.PEOPLE_IN_BENCH)
    measurePage(BenchmarkTarget.TRACE_PAGE_PEOPLE, "personePage") {
      PagesUi.tab(device, "Raccolte")
      PagesUi.row(device, PagesUi.PEOPLE)
    }
  }

  @OptIn(ExperimentalMetricApi::class)
  private fun measurePage(section: String, name: String, open: MacrobenchmarkScope.() -> Unit) {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(PageOpenMetric(section, name), FrameTimingMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(ITERATIONS),
      setupBlock = {
        killProcess()
        startActivityAndWait()
        check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede dopo l'avvio." }
        device.waitForIdle()
      },
      measureBlock = {
        open()
        // Il primo fotogramma della pagina e quelli che seguono (i dati veri al posto dello scheletro) stanno nella traccia.
        Thread.sleep(SETTLE_MS)
      },
    )
  }

  private companion object {
    const val ITERATIONS = 10
    const val LIBRARY_TIMEOUT_MS = 20_000L
    const val SETTLE_MS = 1_200L
  }
}

/** I tocchi delle prove delle pagine, solo con l'albero di accessibilita'. */
object PagesUi {

  /** La riga della sezione Persone nelle Raccolte (`collections_section_people`). */
  const val PEOPLE: String = "Persone"

  private const val TIMEOUT_MS = 8_000L

  /** Tocca la scheda [label] della pillola. */
  fun tab(device: androidx.test.uiautomator.UiDevice, label: String) {
    BenchUi.ensureForeground(device)
    val tab = device.wait(Until.findObject(byDesc(label)), TIMEOUT_MS) ?: device.findObject(By.pkg(BenchSession.targetPackage).text(label))
    checkNotNull(tab) { "La scheda '$label' non c'e'." }.click()
  }

  /** Tocca la riga con il testo [title] (nelle Raccolte). */
  fun row(device: androidx.test.uiautomator.UiDevice, title: String) {
    val row = device.wait(Until.findObject(By.pkg(BenchSession.targetPackage).text(title)), TIMEOUT_MS)
    checkNotNull(row) { "La riga '$title' non c'e'." }.click()
  }

  /**
   * La variante di misura monta Persone? Oggi no: Persone si monta solo con il NAS e l'archivio dei dati personali (L287). Quando la
   * variante li avra', diventa vero e la prova misura.
   */
  const val PEOPLE_IN_BENCH: Boolean = false

  private fun byDesc(label: String): BySelector = By.pkg(BenchSession.targetPackage).desc(label)
}
