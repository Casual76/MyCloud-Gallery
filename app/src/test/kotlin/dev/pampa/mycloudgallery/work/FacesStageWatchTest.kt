package dev.pampa.mycloudgallery.work

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.ai.AiStageMark
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.rules.platform.health.ExitReasonKind
import dev.pampa.mycloudgallery.core.rules.platform.health.ExitRecord
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 8, P13 (§2.2 "Memoria", E-A16): Android che chiude l'app per memoria durante una tappa del calcolo dei volti si vede in Diagnostica, come per il
 * calcolo AI (`AI_KILLED_MEMORY`). Il segno della tappa e' un file; alla tappa dopo, se c'e' ancora, il processo e' morto in mezzo; i motivi delle uscite
 * (`ApplicationExitInfo`) dicono se era la memoria. Solo il codice, mai altro.
 */
class FacesStageWatchTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val reported = ArrayList<DiagCode>()
  private var clock = 1_000L

  private fun watch(exits: List<ExitRecord>, file: File = File(temp.root, "faces-stage-running")) =
    FacesStageWatch(AiStageMark(file), { exits }, { reported += it }, { clock })

  private val killed = FacesDiag.FACES_KILLED_MEMORY

  @Test
  fun unaTappaFinitaBeneNonLasciaSegniENonDiceNiente() = runBlocking<Unit> {
    val file = File(temp.root, "faces-stage-running")
    val result = watch(listOf(ExitRecord(ExitReasonKind.LOW_MEMORY, 5_000L)), file).around {
      assertThat(file.isFile).isTrue()
      7
    }
    assertThat(result).isEqualTo(7)
    assertThat(file.exists()).isFalse()
    // Una seconda tappa non trova niente da attribuire.
    watch(listOf(ExitRecord(ExitReasonKind.LOW_MEMORY, 5_000L)), file).around { }
    assertThat(reported).isEmpty()
  }

  @Test
  fun unaTappaAnnullataToglieComunqueIlSegno() = runBlocking<Unit> {
    val file = File(temp.root, "faces-stage-running")
    try {
      watch(emptyList(), file).around<Unit> { throw kotlinx.coroutines.CancellationException("fermata") }
    } catch (e: kotlinx.coroutines.CancellationException) {
      // atteso
    }
    assertThat(file.exists()).isFalse()
  }

  @Test
  fun unProcessoMortoPerMemoriaDuranteLaTappaSiVedeAllaTappaDopo() = runBlocking<Unit> {
    val file = File(temp.root, "faces-stage-running")
    clock = 1_000L
    // La tappa parte e il processo muore: il segno resta (qui lo lascio io, senza `end`).
    AiStageMark(file).begin(clock)
    watch(listOf(ExitRecord(ExitReasonKind.LOW_MEMORY, 2_000L)), file).around { }
    assertThat(reported.map { it.id }).containsExactly(killed)
    assertThat(file.exists()).isFalse()
  }

  @Test
  fun lUsoEccessivoDiRisorseValeComeLaMemoria() = runBlocking<Unit> {
    val file = File(temp.root, "faces-stage-running")
    AiStageMark(file).begin(1_000L)
    watch(listOf(ExitRecord(ExitReasonKind.EXCESSIVE_RESOURCE_USAGE, 1_500L)), file).around { }
    assertThat(reported.map { it.id }).containsExactly(killed)
  }

  @Test
  fun unaMorteAltroveONonPerMemoriaONonDellaTappaNonSiAttribuisce() = runBlocking<Unit> {
    val file = File(temp.root, "faces-stage-running")
    // Un'uscita per memoria PRIMA della tappa non e' la morte della tappa.
    AiStageMark(file).begin(5_000L)
    watch(listOf(ExitRecord(ExitReasonKind.LOW_MEMORY, 4_000L)), file).around { }
    assertThat(reported).isEmpty()
    // Un'uscita per altro motivo (l'utente ha chiuso l'app) nemmeno.
    AiStageMark(file).begin(1_000L)
    watch(listOf(ExitRecord(ExitReasonKind.USER_REQUESTED, 2_000L)), file).around { }
    assertThat(reported).isEmpty()
  }

  @Test
  fun seAndroidNonRispondeNonSiDiceNienteENonSiFermaLaTappa() = runBlocking<Unit> {
    val file = File(temp.root, "faces-stage-running")
    AiStageMark(file).begin(1_000L)
    var ran = false
    FacesStageWatch(AiStageMark(file), { error("Android non risponde") }, { reported += it }, { clock }).around { ran = true }
    assertThat(ran).isTrue()
    assertThat(reported).isEmpty()
  }

  @Test
  fun ilSegnoDeiVoltiNonEQuelloDelCalcoloAi() {
    assertThat(dev.pampa.mycloudgallery.faces.FacesWiring.STAGE_MARK).isNotEqualTo(AiStageMark.FILE)
    assertThat(dev.pampa.mycloudgallery.faces.FacesWiring.FILES).contains(dev.pampa.mycloudgallery.faces.FacesWiring.STAGE_MARK)
  }
}
