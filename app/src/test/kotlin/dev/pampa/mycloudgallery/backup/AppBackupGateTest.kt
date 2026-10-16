package dev.pampa.mycloudgallery.backup

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import org.junit.Test

/**
 * Il cancello del backup (Fase 5A, P7; docs/fase5-progetto.md §1.4, D12, D32): **D12 e' la regola piu' importante**. Sulle share vere
 * non si scrive niente, in ogni build (anche quella di release, anche con la famiglia pronta, l'area privata scelta, nessuna emergenza,
 * nessun congelamento e il permesso di scrittura): lo stato e' `TEST_ONLY` e basta. Tutto il resto viene dopo.
 */
class AppBackupGateTest {

  private class Rig(
    var familyReady: Boolean = true,
    var privateShare: String? = "test",
    var writesStopped: Boolean = false,
    var frozen: Set<String> = emptySet(),
    var readOnly: Set<String> = emptySet(),
    val flags: FeatureFlags = FeatureFlags.forBuild(debugBuild = false),
  ) {
    val gate = AppBackupGate(
      BackupGateInputs(
        flags = flags,
        familyReady = { familyReady },
        privateShare = { privateShare },
        writesStopped = { writesStopped },
        frozen = { it in frozen },
        readOnly = { it in readOnly },
      ),
    )
  }

  @Test
  fun unaShareDiProvaConTuttoAPostoEAperta() {
    assertThat(Rig().gate.stateOf("test")).isEqualTo(BackupGateState.OPEN)
    assertThat(Rig(privateShare = "test2").gate.stateOf("test2")).isEqualTo(BackupGateState.OPEN)
  }

  @Test
  fun d12UnaShareVeraNonSiScriveMaiEStatoTestOnlyAncheInRelease() {
    // La release: `forBuild(debugBuild = false)`; ma D12 vale anche con `debugBuild = true` (non si spegne da nessuna parte).
    for (debug in listOf(false, true)) {
      val rig = Rig(privateShare = "famiglia-rossi", flags = FeatureFlags.forBuild(debugBuild = debug))
      val state = rig.gate.stateOf("famiglia-rossi")
      assertWithMessage("debug=$debug").that(state).isEqualTo(BackupGateState.TEST_ONLY)
      assertWithMessage("debug=$debug").that(state.open).isFalse()
      assertThat(state.problem).isEqualTo(BackupProblemCode.TEST_ONLY)
    }
  }

  @Test
  fun d12VinceSuEmergenzaCongelamentoESolaLettura() {
    // Con una share vera anche tutte le altre condizioni insieme dicono `TEST_ONLY`: la ragione piu' grave e' D12.
    val rig = Rig(privateShare = "mario", writesStopped = true, frozen = setOf("mario"), readOnly = setOf("mario"))
    assertThat(rig.gate.stateOf("mario")).isEqualTo(BackupGateState.TEST_ONLY)
  }

  @Test
  fun nessunaShareVeraPuoMaiAprireIlCancello() {
    for (share in listOf("mario", "famiglia", "Test-Comune-Famiglia", "foto", "backup", "testo", "test-comune2x")) {
      val rig = Rig(privateShare = share)
      assertWithMessage(share).that(rig.gate.stateOf(share).open).isFalse()
    }
  }

  @Test
  fun lOrdineDelleRagioni() {
    assertThat(Rig(familyReady = false, privateShare = null).gate.stateOf("test")).isEqualTo(BackupGateState.NOT_READY)
    assertThat(Rig(privateShare = null).gate.stateOf("test")).isEqualTo(BackupGateState.NO_PRIVATE_AREA)
    assertThat(Rig(writesStopped = true, frozen = setOf("test")).gate.stateOf("test")).isEqualTo(BackupGateState.WRITES_STOPPED)
    assertThat(Rig(frozen = setOf("test"), readOnly = setOf("test")).gate.stateOf("test")).isEqualTo(BackupGateState.FROZEN)
    assertThat(Rig(readOnly = setOf("test")).gate.stateOf("test")).isEqualTo(BackupGateState.READ_ONLY_AREA)
  }

  @Test
  fun ilCancelloSiChiudeAlVoloCambiandoLeCondizioni() {
    val rig = Rig()
    assertThat(rig.gate.stateOf("test")).isEqualTo(BackupGateState.OPEN)
    rig.writesStopped = true
    assertThat(rig.gate.stateOf("test")).isEqualTo(BackupGateState.WRITES_STOPPED)
    rig.writesStopped = false
    rig.frozen = setOf("test")
    assertThat(rig.gate.stateOf("test")).isEqualTo(BackupGateState.FROZEN)
    rig.frozen = emptySet()
    rig.familyReady = false
    assertThat(rig.gate.stateOf("test")).isEqualTo(BackupGateState.NOT_READY)
  }
}
