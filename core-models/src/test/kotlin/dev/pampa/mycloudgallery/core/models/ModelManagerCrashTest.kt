package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.support.ModelRig
import dev.pampa.mycloudgallery.core.models.support.TestModels
import dev.pampa.mycloudgallery.core.models.support.assertModelsDirInvariant
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 6, P3 (§4.3 "morte del processo a ogni salvataggio", §5.5 invarianti 1-3): il processo muore prima o dopo OGNI chiamata al
 * fetcher; una nuova istanza sulla stessa cartella riprende, non rifa' cio' che c'e', e in `models/` non entra mai un file non
 * verificato. Poi la cartella come l'ha lasciata un processo morto in mezzo alla promozione.
 */
class ModelManagerCrashTest {
  private val long = 10.minutes

  /** Un fetcher che fa morire il "processo" (un'eccezione che nessuno prende) alla chiamata [dieAt], prima o dopo il salvataggio. */
  private class Dying(private val inner: () -> ModelFetcher, private val dieAt: Int, private val afterSave: Boolean) : ModelFetcher {
    // Un `Error`, non un'eccezione: il gestore prende ogni `Exception` (R10: nessun guasto cade sul lavoro) ma la morte del processo non e' un guasto.
    class ProcessDied : Error("processo morto")

    private val calls = AtomicInteger()

    override suspend fun fetch(spec: ModelSpec, source: ModelSource, partial: File, budget: Duration): FetchResult {
      val n = calls.incrementAndGet()
      if (n == dieAt && !afterSave) throw ProcessDied()
      val result = inner().fetch(spec, source, partial, budget)
      if (n == dieAt && afterSave) throw ProcessDied()
      return result
    }
  }

  @Test
  fun laMorteDelProcessoAOgniSalvataggioNonPerdeNienteEMaiUnFileNonVerificato() {
    val bytes = TestModels.bytes()
    val blocks = (bytes.size + TestModels.BLOCK - 1) / TestModels.BLOCK
    for (afterSave in listOf(false, true)) {
      for (dieAt in 1..blocks) {
        val dir = TestModels.tempDir()
        lateinit var dying: ModelRig
        dying = ModelRig(bytes, dir, chunk = TestModels.BLOCK, fetcher = Dying({ dying.fake }, dieAt, afterSave))
        dying.nasCopy()
        // Il "processo" muore: l'eccezione attraversa il gestore; la cartella resta com'era in quel momento.
        val died = runCatching { runBlocking { dying.manager.ensure(dying.spec.id, ModelTrigger.RULES, long) } }
        assertThat(died.exceptionOrNull()).isInstanceOf(Dying.ProcessDied::class.java)
        assertModelsDirInvariant(dir, listOf(dying.spec))
        assertThat(dying.finalFile.exists()).isFalse()

        // Un nuovo processo, sulla stessa cartella, riprende.
        val next = ModelRig(bytes, dir, chunk = TestModels.BLOCK)
        next.nasCopy(deviceId = dying.device)
        var rounds = 0
        var result: EnsureResult
        do {
          rounds++
          result = runBlocking { next.manager.ensure(next.spec.id, ModelTrigger.RULES, long) }
          assertModelsDirInvariant(dir, listOf(next.spec))
        } while (result != EnsureResult.Ready && rounds < 10)
        assertThat(result).isEqualTo(EnsureResult.Ready)
        assertThat(next.finalFile.readBytes()).isEqualTo(bytes)
        // Invariante 3: nessun blocco riscaricato. Con la morte DOPO il salvataggio il blocco c'e' gia'; PRIMA non e' mai esistito.
        val saved = if (afterSave) dieAt else dieAt - 1
        assertThat(next.fake.calls).hasSize(blocks - saved)
        assertThat(File(dir, "partial").listFiles().orEmpty()).isEmpty()
      }
    }
  }

  @Test
  fun unParzialeCompletoMaSbagliatoNonVienePromosso() {
    // Un processo muore con il parziale completo ma NON verificato e con un byte sbagliato: la ripartenza non lo promuove.
    val bytes = TestModels.bytes()
    val rig = ModelRig(bytes)
    rig.installPartial(TestModels.flipped(bytes, 2_000_000))
    rig.nasCopy()
    assertThat(runBlocking { rig.manager.ensure(rig.spec.id, ModelTrigger.RULES, long) }).isEqualTo(EnsureResult.Ready)
    assertThat(rig.finalFile.readBytes()).isEqualTo(bytes)
    assertModelsDirInvariant(rig.dir, listOf(rig.spec))
  }

  @Test
  fun mortoDopoLaRinominaMaPrimaDellaNotaDiProvenienza() {
    // models/<sha256> c'e' (verificato), la nota `meta/` no, restano i residui del parziale.
    val bytes = TestModels.bytes()
    val dir = TestModels.tempDir()
    val prepared = ModelRig(bytes, dir)
    prepared.installFinal()
    prepared.installPartial(ByteArray(10))
    File(prepared.partial.path + ".etag").writeText("\"v1\"")
    File(prepared.partial.path + ".src").writeText("NAS")
    val rig = ModelRig(bytes, dir)
    assertThat(runBlocking { rig.manager.ensure(rig.spec.id, ModelTrigger.RULES, long) }).isEqualTo(EnsureResult.Ready)
    assertThat(rig.fake.calls).isEmpty()
    // Senza la nota l'etichetta e' quella di ripiego; il modello e' lo stesso.
    assertThat((rig.stateOfModel() as ModelState.Ready).source).isEqualTo(ModelSourceKind.HTTPS)
    assertThat(File(dir, "partial").listFiles().orEmpty()).isEmpty()
  }

  @Test
  fun unaNotaDiProvenienzaSenzaIlModelloNonFaDanni() {
    val bytes = TestModels.bytes()
    val dir = TestModels.tempDir()
    File(dir, "meta").mkdirs()
    val sha = ModelRig(bytes, dir).spec.sha256.hex
    File(dir, "meta/$sha").writeText("PC_FOLDER")
    val rig = ModelRig(bytes, dir)
    rig.nasCopy()
    assertThat(rig.stateOfModel()).isEqualTo(ModelState.Absent)
    assertThat(runBlocking { rig.manager.ensure(rig.spec.id, ModelTrigger.RULES, long) }).isEqualTo(EnsureResult.Ready)
    assertThat((rig.stateOfModel() as ModelState.Ready).source).isEqualTo(ModelSourceKind.NAS)
  }

  @Test
  fun unaNotaRovinataNonFermaNiente() {
    val bytes = TestModels.bytes()
    val dir = TestModels.tempDir()
    val prepared = ModelRig(bytes, dir)
    prepared.installFinal()
    File(dir, "meta").mkdirs()
    File(dir, "meta/${prepared.spec.sha256.hex}").writeBytes(ByteArray(500) { 0xff.toByte() })
    val rig = ModelRig(bytes, dir)
    assertThat(runBlocking { rig.manager.ensure(rig.spec.id, ModelTrigger.RULES, long) }).isEqualTo(EnsureResult.Ready)
  }
}
