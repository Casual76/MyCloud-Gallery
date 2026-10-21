package dev.pampa.mycloudgallery.core.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidate
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.ai.testing.FakeAiBuffer
import dev.pampa.mycloudgallery.core.ai.testing.FakeAiCandidates
import dev.pampa.mycloudgallery.core.ai.testing.FakeAiCompute
import dev.pampa.mycloudgallery.core.ai.testing.FakeAiResults
import dev.pampa.mycloudgallery.core.ai.testing.FakeAiRoles
import dev.pampa.mycloudgallery.core.ai.testing.FakeImageEmbedder
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextReader
import dev.pampa.mycloudgallery.core.ai.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.index.ai.AiEntry
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.testing.FakeAiDoneClasses
import dev.pampa.mycloudgallery.core.index.testing.FakeAiIndexPublisher
import dev.pampa.mycloudgallery.core.index.testing.FakeAnalysisFetcher
import dev.pampa.mycloudgallery.core.index.testing.MapWriteGate
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 6 (P0): l'esecutore di riferimento del calcolo (`FakeAiCompute`) rispetta gli invarianti che P5 deve rispettare (§2.2, D9,
 * D10, D22, D41, E-A13, E-A15): salvare prima di pubblicare, niente rifatto dopo la morte del processo, lavoro altrui saltato, OCR
 * solo con la lettura attiva, telefono solo in locale, F4-D31 = in attesa senza ricalcolo.
 */
class ReferenceComputeTest {

  private val share = "test-comune"
  private fun key(n: Int) = ContentKey(n.toString(16).padStart(64, '0'))
  private fun photo(n: Int, vararg missing: AiClass = arrayOf(AiClass.VEC, AiClass.OCR)) =
    AiCandidate(key(n), MediaKind.PHOTO, MediaSource.CAMERA, missing.toSet())

  private class World(textReading: Boolean = false) {
    val roles = FakeAiRoles(indexer = true, textReadingHere = textReading)
    val candidates = FakeAiCandidates()
    val buffer = FakeAiBuffer()
    val images = FakeImageEmbedder()
    val reader = FakeTextReader(readingHere = { roles.textReadingHere.value })
    val results = FakeAiResults()
    val gate = MapWriteGate()
    val publisher = FakeAiIndexPublisher(gate = gate)
    val others = FakeAiDoneClasses()
    val fetcher = FakeAnalysisFetcher()
    val compute = FakeAiCompute(roles, candidates, buffer, images, reader, results, publisher, others, fetcher)
  }

  @Test
  fun calcolaDalBufferEDalNasESalvaPrimaDiPubblicare() = runTest {
    val w = World()
    w.candidates.add(share, photo(1))
    w.candidates.add(share, photo(2))
    w.buffer.put(key(1), SegmentClass.ANALYSIS, "FAKE:cane".toByteArray())
    w.fetcher.put(share, key(2), SegmentClass.ANALYSIS, "FAKE:tramonto".toByteArray())
    val result = w.compute.run(AiScope.Share(share), 8.minutes)
    assertThat(result.computed).isEqualTo(2)
    assertThat(result.stoppedBy).isNull()
    // Lettura del testo spenta: solo vettori, il lettore non e' mai stato chiamato (fallirebbe).
    assertThat(w.results.saves.map { it.second }.toSet()).containsExactly(AiClass.VEC)
    assertThat(w.reader.reads).isEmpty()
    val published = w.publisher.published.single().entries
    assertThat(published.map { it.contentKey }).containsExactly(key(1), key(2))
    assertThat(published.all { it is AiEntry.Vector }).isTrue()
    // Il buffer si svuota di cio' che e' fatto.
    assertThat(w.buffer.contains(key(1), SegmentClass.ANALYSIS)).isFalse()
  }

  /** D41: con la lettura attiva qui si legge anche il testo; con quella spenta il lettore non si chiama mai. */
  @Test
  fun ilTestoSiLeggeSoloConLaLetturaAttiva() = runTest {
    val w = World(textReading = true)
    w.candidates.add(share, photo(1))
    w.buffer.put(key(1), SegmentClass.ANALYSIS, "FAKE:scontrino".toByteArray())
    w.compute.run(AiScope.Share(share), 8.minutes)
    assertThat(w.results.saves.map { it.second }).containsExactly(AiClass.VEC, AiClass.OCR).inOrder()
    assertThat(w.reader.reads).hasSize(1)
  }

  /** D9: cio' che un altro indicizzatore ha gia' pubblicato non si rifa'; D10, E-A15: senza anteprima si aspetta. */
  @Test
  fun ilLavoroAltruiSiSaltaESenzaAnteprimaSiAspetta() = runTest {
    val w = World()
    w.candidates.add(share, photo(1, AiClass.VEC))
    w.candidates.add(share, photo(2, AiClass.VEC))
    w.others.mark(share, key(1), recipe = 1, AiClass.VEC)
    // key(2) ha solo un blob NON attestato: non si usa.
    w.fetcher.put(share, key(2), SegmentClass.ANALYSIS, "FAKE:cane".toByteArray(), attested = false)
    val result = w.compute.run(AiScope.Share(share), 8.minutes)
    assertThat(result.skipped).isEqualTo(1)
    assertThat(result.waitingForPreparation).isEqualTo(1)
    assertThat(w.images.images).isEqualTo(0)
  }

  /** D22: dopo la morte del processo durante un salvataggio, i media gia' salvati non si ricalcolano. */
  @Test
  fun dopoLaMorteDelProcessoNienteSiRifa() = runTest {
    val w = World()
    for (n in 1..3) {
      w.candidates.add(share, photo(n, AiClass.VEC))
      w.buffer.put(key(n), SegmentClass.ANALYSIS, "FAKE:fiore $n".toByteArray())
    }
    assertThat(w.compute.run(AiScope.Share(share), 8.minutes).computed).isEqualTo(3)
    assertThat(w.results.saves).hasSize(3)
    val before = w.images.images
    // Secondo giro sugli stessi media (il catalogo non li ha ancora tolti dai candidati): tutto e' gia' salvato, niente si ricalcola.
    val again = w.compute.run(AiScope.Share(share), 8.minutes)
    assertThat(again.computed).isEqualTo(0)
    assertThat(again.skipped).isEqualTo(3)
    assertThat(w.images.images).isEqualTo(before)

    // Una morte vera durante il salvataggio: lo stesso media si rifa' al giro dopo (non era salvato), gli altri no.
    val v = World()
    for (n in 1..2) {
      v.candidates.add(share, photo(n, AiClass.VEC))
      v.buffer.put(key(n), SegmentClass.ANALYSIS, "FAKE:fiore $n".toByteArray())
    }
    v.results.dieOnNextSave = true
    assertThat(runCatching { v.compute.run(AiScope.Share(share), 8.minutes) }.exceptionOrNull()).isInstanceOf(ProcessDeath::class.java)
    assertThat(v.publisher.staged).isEmpty()
    val resumed = v.compute.run(AiScope.Share(share), 8.minutes)
    assertThat(resumed.computed).isEqualTo(2)
  }

  /** E-A13: Indicizzatore spento = niente. I media del solo telefono si calcolano ma non si pubblicano. */
  @Test
  fun ruoloSpentoNienteTelefonoSoloInLocale() = runTest {
    val w = World()
    w.roles.indexer.value = false
    assertThat(w.compute.run(AiScope.Share(share), 8.minutes).stoppedBy).isEqualTo(AiStop.ROLE_OFF)
    w.roles.indexer.value = true
    w.candidates.phone += photo(9, AiClass.VEC)
    w.buffer.put(key(9), SegmentClass.ANALYSIS, "FAKE:gatto".toByteArray())
    assertThat(w.compute.run(AiScope.PhoneOnly, 8.minutes).computed).isEqualTo(1)
    assertThat(w.publisher.staged).isEmpty()
    assertThat(w.results.vectors.keys).containsExactly(key(9))
  }

  /** D8: con F4-D31 (blocco permanente) le voci restano in attesa e si pubblicano dopo, SENZA ricalcolo. */
  @Test
  fun conLaShareNonPubblicabileSiPubblicaDopoSenzaRicalcolare() = runTest {
    val w = World()
    w.gate.block(share, WriteBlock.LOCAL_ONLY)
    w.candidates.add(share, photo(1, AiClass.VEC))
    w.buffer.put(key(1), SegmentClass.ANALYSIS, "FAKE:montagna".toByteArray())
    w.compute.run(AiScope.Share(share), 8.minutes)
    assertThat(w.publisher.published).isEmpty()
    assertThat(w.publisher.waitingFor(share)).hasSize(1)
    val computedBefore = w.images.images
    w.gate.block(share, null)
    assertThat(w.publisher.releasePending(share)).isEqualTo(1)
    w.publisher.flush(share, dev.pampa.mycloudgallery.core.index.contract.FlushReason.PERIODIC)
    assertThat(w.publisher.published.single().entries).hasSize(1)
    assertThat(w.images.images).isEqualTo(computedBefore)
  }
}
