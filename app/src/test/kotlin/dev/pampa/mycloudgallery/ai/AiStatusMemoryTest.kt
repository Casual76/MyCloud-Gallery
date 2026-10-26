package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.platform.health.ExitReasonKind
import dev.pampa.mycloudgallery.core.rules.platform.health.ExitRecord
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * IX-2 e DG-3 della verifica funzionale della Fase 6. Lo stato del calcolo vive in memoria e nasceva "Off": un'app riaperta dopo la notte diceva
 * "Questo dispositivo non analizza le foto". Ora si ricorda l'ultimo stato (solo codici). E `AI_KILLED_MEMORY`, dichiarato ma emesso da nessuno:
 * ora una tappa morta lascia un segno e Android dice se l'ha chiusa per memoria.
 */
class AiStatusMemoryTest {

  @get:Rule
  val temp = TemporaryFolder()

  private fun memory(name: String = AiStatusMemory.FILE) = AiStatusMemory(File(temp.root, name))

  // --- IX-2: l'ultimo stato si ritrova in un altro processo ---------------------------------------------------------------------------

  @Test
  fun unaAttesaDelleRegoleSiRitrovaDopoUnRiavvio() {
    memory().save(AiStatus.Waiting(AiWaitReason.HOT))
    assertThat(memory().load()).isEqualTo(AiStatus.Waiting(AiWaitReason.HOT))
  }

  @Test
  fun ogniAttesaEOgniProblemaSiRicordano() {
    for (reason in AiWaitReason.entries) {
      memory().save(AiStatus.Waiting(reason))
      assertThat(memory().load()).isEqualTo(AiStatus.Waiting(reason))
    }
    memory().save(AiStatus.Problem(AiDiag.AI_STORAGE_LOW))
    assertThat(memory().load()).isEqualTo(AiStatus.Problem(AiDiag.AI_STORAGE_LOW))
    memory().save(AiStatus.ModelMissing)
    assertThat(memory().load()).isEqualTo(AiStatus.ModelMissing)
    memory().save(AiStatus.BufferOnlyDone(12))
    assertThat(memory().load()).isEqualTo(AiStatus.BufferOnlyDone(12))
  }

  @Test
  fun tuttoAnalizzatoSiRicordaSenzaLaData() {
    memory().save(AiStatus.Idle(1_700_000_000_000L))
    // Solo il codice: nessuna ora (D39/D40).
    assertThat(File(temp.root, AiStatusMemory.FILE).readText()).isEqualTo("idle")
    assertThat(memory().load()).isEqualTo(AiStatus.Idle(null))
  }

  @Test
  fun inCorsoESpentoNonSiRicordano() {
    memory().save(AiStatus.Waiting(AiWaitReason.RULES))
    val m = memory()
    m.save(AiStatus.Off)
    m.save(AiStatus.Running(3, 10, null, AiBackend.CPU))
    assertThat(memory().load()).isEqualTo(AiStatus.Waiting(AiWaitReason.RULES))
  }

  @Test
  fun senzaFileOConUnFileRovinatoNessunoStato() {
    assertThat(memory().load()).isNull()
    val file = File(temp.root, AiStatusMemory.FILE)
    for (junk in listOf("", "waiting NONE", "problem ../../x", "buffer -3", "qualcosa di strano", "x".repeat(600))) {
      file.writeText(junk)
      assertThat(memory().load()).isNull()
    }
  }

  @Test
  fun siScriveSoloSeCambiaEAzzerareLoToglie() {
    val m = memory()
    m.save(AiStatus.Waiting(AiWaitReason.NEXT_STAGE))
    val file = File(temp.root, AiStatusMemory.FILE)
    val stamp = file.lastModified()
    m.save(AiStatus.Waiting(AiWaitReason.NEXT_STAGE))
    assertThat(file.lastModified()).isEqualTo(stamp)
    m.clear()
    assertThat(file.exists()).isFalse()
    assertThat(memory().load()).isNull()
  }

  @Test
  fun senzaCartellaNienteSiRompe() {
    val m = AiStatusMemory(null)
    m.save(AiStatus.Waiting(AiWaitReason.HOT))
    assertThat(m.load()).isNull()
    m.clear()
  }

  // --- Il motivo di un'attesa in parole della pagina ----------------------------------------------------------------------------------

  @Test
  fun ilMotivoDelleRegoleDiventaUnaParolaDellaPagina() {
    assertThat(AiStatusMemory.waitReasonOf(WaitReason.Thermal(ThermalStatus.SEVERE))).isEqualTo(AiWaitReason.HOT)
    assertThat(AiStatusMemory.waitReasonOf(WaitReason.Offline)).isEqualTo(AiWaitReason.NO_NETWORK)
    assertThat(AiStatusMemory.waitReasonOf(WaitReason.Roaming)).isEqualTo(AiWaitReason.NO_NETWORK)
    assertThat(AiStatusMemory.waitReasonOf(WaitReason.NasUnreachable)).isEqualTo(AiWaitReason.NAS_UNREACHABLE)
    assertThat(AiStatusMemory.waitReasonOf(WaitReason.NeedsPower)).isEqualTo(AiWaitReason.RULES)
  }

  // --- DG-3: la tappa morta e il segno ------------------------------------------------------------------------------------------------

  @Test
  fun unaTappaFinitaNonLasciaSegno() {
    val mark = AiStageMark(File(temp.root, AiStageMark.FILE))
    mark.begin(1_000L)
    mark.end()
    assertThat(mark.interruptedAt()).isNull()
  }

  @Test
  fun unaTappaMortaLasciaIlSegnoEUnaVoltaSolaSiDice() {
    val file = File(temp.root, AiStageMark.FILE)
    AiStageMark(file).begin(5_000L)
    // Il processo dopo trova il segno...
    val next = AiStageMark(file)
    assertThat(next.interruptedAt()).isEqualTo(5_000L)
    // ...e lo toglie: non si ripete.
    assertThat(next.interruptedAt()).isNull()
    assertThat(file.exists()).isFalse()
  }

  @Test
  fun unSegnoRovinatoValeNessunaMorte() {
    val file = File(temp.root, AiStageMark.FILE)
    file.writeText("non e' un numero")
    assertThat(AiStageMark(file).interruptedAt()).isNull()
    file.writeText("1".repeat(100))
    assertThat(AiStageMark(file).interruptedAt()).isNull()
  }

  @Test
  fun androidHaChiusoIlProcessoPerMemoriaSoloSeLUscitaEDopoLaPartenzaDellaTappa() {
    val exits = listOf(ExitRecord(ExitReasonKind.LOW_MEMORY, 10_000L))
    assertThat(AiKilledByMemory.wasKilled(markedAt = 9_000L, exits = exits)).isTrue()
    // Un'uscita per memoria vecchia (prima della tappa) non e' la sua morte.
    assertThat(AiKilledByMemory.wasKilled(markedAt = 11_000L, exits = exits)).isFalse()
    // Altri motivi non sono memoria.
    for (reason in listOf(ExitReasonKind.CRASH, ExitReasonKind.USER_REQUESTED, ExitReasonKind.ANR, ExitReasonKind.UNKNOWN)) {
      assertThat(AiKilledByMemory.wasKilled(9_000L, listOf(ExitRecord(reason, 10_000L)))).isFalse()
    }
    assertThat(AiKilledByMemory.wasKilled(9_000L, listOf(ExitRecord(ExitReasonKind.EXCESSIVE_RESOURCE_USAGE, 10_000L)))).isTrue()
    assertThat(AiKilledByMemory.wasKilled(9_000L, emptyList())).isFalse()
  }
}
