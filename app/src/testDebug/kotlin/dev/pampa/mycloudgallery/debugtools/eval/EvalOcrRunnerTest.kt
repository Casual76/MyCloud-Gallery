package dev.pampa.mycloudgallery.debugtools.eval

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextReader
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * La lettura di prova del set OCR (P9, solo debug). La lettura vera con ML Kit e' sul dispositivo e non e' di P9; qui si prova il percorso: il set che
 * manca, il manifesto, le immagini mancanti (contate, mai lette), i file scritti e il resoconto di soli numeri. Il lettore finto **fallisce se chiamato**
 * con la lettura spenta: con il set che non si puo' leggere non viene mai toccato.
 */
class EvalOcrRunnerTest {
  @get:Rule val tmp = TemporaryFolder()

  private val manifest = "id\tfile\tgenere\tlarghezza\tpx\tdegradazione\ttesto\n" +
    "a\timmagini/a.jpg\tscontrino\t1024\t12\tpulita\tTotale € 24,50\\nCaffè espresso\n" +
    "b\timmagini/b.jpg\tchat\t2048\t20\tsfocata\tCi vediamo alle 18:30\n"

  private fun store() = EvalStore(tmp.newFolder("eval"))

  /** Un lettore che non deve mai essere chiamato. */
  private val neverCalled = FakeTextReader(readingHere = { false })

  @Test
  fun senzaIlSetNonSiParte() {
    val runner = EvalOcrRunner(neverCalled, store())
    assertThat(runner.problem()).contains("Manca il set OCR")
  }

  @Test
  fun unManifestoVuotoNonVaBene() {
    val store = store()
    store.ocrDir.mkdirs()
    File(store.ocrDir, "manifest.tsv").writeText("id\tfile\tgenere\tlarghezza\tpx\tdegradazione\ttesto\n")
    assertThat(EvalOcrRunner(neverCalled, store).problem()).contains("vuoto")
  }

  @Test
  fun leImmaginiMancantiSiContanoENonSiLeggono() = runTest {
    val store = store()
    store.ocrDir.mkdirs()
    File(store.ocrDir, "manifest.tsv").writeText(manifest)
    val runner = EvalOcrRunner(neverCalled, store)
    assertThat(runner.problem()).isNull()
    var last = 0 to 0
    val result = runner.run("lettore di prova") { done, total -> last = done to total }
    assertThat(result.failed).isEqualTo(2)
    assertThat(result.read).isEqualTo(0)
    assertThat(last).isEqualTo(2 to 2)
    assertThat(File(store.ocrDir, "letto.tsv").readText()).isEqualTo("a\t\nb\t\n")
    // Il resoconto: tutto sbagliato (nessuna lettura), solo numeri e condizioni.
    val report = File(store.ocrDir, "ocr-resoconto.txt").readText()
    assertThat(report).isEqualTo(result.report)
    assertThat(report).contains("Lettore: lettore di prova")
    assertThat(report).contains("1024 px 12")
    assertThat(report).doesNotContain("Totale")
    assertThat(report).doesNotContain("Caffè")
  }

  @Test
  fun iFileDelSetSonoSoloSottoLaCartellaDiValutazione() {
    val store = store()
    assertThat(store.ocrDir.parentFile).isEqualTo(File(tmp.root, "eval"))
    assertThat(store.ocrDir.name).isEqualTo("ocr")
  }
}
