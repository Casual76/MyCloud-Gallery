package dev.pampa.mycloudgallery.core.ai.runtime

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.testing.AiTestInputs
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R9 della revisione di sicurezza della Fase 6: il ripiego da GPU a CPU valeva solo in memoria. Una GPU che fa **cadere il processo** (driver,
 * memoria) non lancia niente: nessun ripiego, e al processo dopo si riprovava la GPU, a ogni tappa, senza lasciare traccia. Ora il segno "GPU in
 * prova" sta su disco; due cadute di fila e il motore parte su CPU, con `AI_GPU_FALLBACK`.
 */
class GpuTrialTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val base: File by lazy { File(tmp.newFolder("cache"), "0123abcd-gpu") }

  private fun trial() = GpuTrial(base)

  // --- GpuTrial ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun senzaSegniLaGpuSiProva() {
    val t = trial()
    assertThat(t.crashCount).isEqualTo(0)
    assertThat(t.allowed).isTrue()
  }

  @Test
  fun unProcessoMortoConLaGpuInProvaEUnaCadutaEDueDiFilaLaGpuNonSiRiprova() {
    // Processo 1: apre la GPU e muore (nessuno chiama passed/handled).
    trial().begin()
    // Processo 2: vede il segno, conta una caduta, la GPU si riprova ancora una volta.
    val second = trial()
    assertThat(second.crashCount).isEqualTo(1)
    assertThat(second.allowed).isTrue()
    second.begin()
    // Processo 3: due cadute di fila. La GPU non si prova piu'.
    val third = trial()
    assertThat(third.crashCount).isEqualTo(2)
    assertThat(third.allowed).isFalse()
    // E resta cosi' al processo dopo, senza altre prove.
    assertThat(trial().allowed).isFalse()
  }

  @Test
  fun unVettoreValidoDallaGpuAzzeraIlConto() {
    trial().begin()
    val second = trial()
    assertThat(second.crashCount).isEqualTo(1)
    second.begin()
    second.passed()
    val third = trial()
    assertThat(third.crashCount).isEqualTo(0)
    assertThat(third.allowed).isTrue()
  }

  @Test
  fun unaProvaFinitaBeneOGestitaNonEUnaCaduta() {
    val first = trial()
    first.begin()
    first.handled() // la GPU ha lanciato e il ripiego l'ha preso, o il motore si e' chiuso pulito
    assertThat(trial().crashCount).isEqualTo(0)
  }

  @Test
  fun leMemorieStannoAccantoAllaCartellaDeiProgrammiCompilatiNonDentro() {
    trial().begin()
    trial()
    assertThat(File(base.path + ".crashes").isFile).isTrue()
    // Togliere la cartella dei programmi compilati (a ogni inizializzazione fallita) non fa dimenticare le cadute.
    File(base.path).mkdirs()
    File(base.path).deleteRecursively()
    assertThat(trial().crashCount).isEqualTo(1)
  }

  @Test
  fun senzaDiscoNienteSiRicordaMaNienteSiRompe() {
    val t = GpuTrial(null)
    t.begin()
    t.passed()
    t.handled()
    assertThat(t.allowed).isTrue()
  }

  @Test
  fun unFileDelConteggioRovinatoNonFaMale() {
    base.parentFile!!.mkdirs()
    File(base.path + ".crashes").writeText("not-a-number")
    assertThat(trial().crashCount).isEqualTo(0)
    File(base.path + ".crashes").writeBytes(ByteArray(500) { 'x'.code.toByte() })
    assertThat(trial().crashCount).isEqualTo(0)
  }

  // --- Il motore delle immagini ------------------------------------------------------------------------------------------------------

  private fun embedder(factory: FakeSessionFactory, trial: GpuTrial, diags: MutableList<DiagCode> = mutableListOf()) =
    LiteRtLmImageEmbedder(factory, trial) { diags += it }

  private val image = AiTestInputs.image("cane spiaggia")

  @Test
  fun dopoDueCaduteDiFilaIlMotorePartePerSuCpuEDiceloUnaVolta() = runTest {
    trial().begin()
    trial().begin() // due processi morti con la GPU in prova
    val third = trial()
    assertThat(third.allowed).isFalse()

    val factory = FakeSessionFactory()
    val diags = mutableListOf<DiagCode>()
    val engine = embedder(factory, third, diags)
    assertThat(engine.backend.value).isEqualTo(AiBackend.CPU)
    engine.embed(listOf(image))
    engine.release()
    engine.embed(listOf(image))

    // Mai una sessione GPU: nessun'altra prova che potrebbe far cadere il processo.
    assertThat(factory.attempts).containsExactly(AiBackend.CPU, AiBackend.CPU)
    // Detto una volta sola, non a ogni apertura.
    assertThat(diags).containsExactly(AiDiag.AI_GPU_FALLBACK)
  }

  @Test
  fun laGpuInProvaSiSegnaSuDiscoEFinoAlPrimoVettoreValidoESoloAllora() = runTest {
    val mark = File(base.path + ".trial")
    val factory = FakeSessionFactory()
    var markSeenWhileEmbedding = false
    factory.onImage = { _, jpeg ->
      // Mentre la GPU lavora (prima del primo vettore) il segno c'e': un processo che muore qui lascia la traccia.
      markSeenWhileEmbedding = markSeenWhileEmbedding || mark.exists()
      FakeSessionFactory.unitVector("i:" + jpeg.contentHashCode())
    }
    val engine = embedder(factory, trial())
    engine.embed(listOf(image))

    assertThat(markSeenWhileEmbedding).isTrue()
    // Un vettore valido: la prova e' riuscita, il segno non c'e' piu' e il conto e' zero.
    assertThat(mark.exists()).isFalse()
    assertThat(trial().crashCount).isEqualTo(0)
    assertThat(factory.attempts).containsExactly(AiBackend.GPU)
  }

  @Test
  fun unaGpuCheNonPartePerEccezioneNonEUnaCaduta() = runTest {
    val factory = FakeSessionFactory()
    factory.openFailure[AiBackend.GPU] = IllegalStateException("OpenCL non disponibile")
    val engine = embedder(factory, trial())
    engine.embed(listOf(image))
    assertThat(File(base.path + ".trial").exists()).isFalse()
    assertThat(trial().crashCount).isEqualTo(0)
  }

  @Test
  fun chiudereIlMotoreDuranteLaProvaTogliIlSegnoSenzaContareUnaCaduta() = runTest {
    val factory = FakeSessionFactory()
    val engine = embedder(factory, trial())
    engine.embed(listOf(image))
    engine.release()
    assertThat(trial().crashCount).isEqualTo(0)
  }
}
