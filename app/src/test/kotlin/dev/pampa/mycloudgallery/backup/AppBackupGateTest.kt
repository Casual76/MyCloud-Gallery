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
 *
 * Revisione di sicurezza della 5A: **R2** (in release un account di prova non scrive in nessuna share, tutte le combinazioni), **R4** (la
 * pausa dell'utente e' un cancello, dopo le ragioni piu' gravi) e **R7** (la share chiesta deve essere l'area privata di adesso).
 */
class AppBackupGateTest {

  private class Rig(
    var familyReady: Boolean = true,
    var privateShare: String? = "test",
    var account: String? = "mario",
    var writesStopped: Boolean = false,
    var frozen: Set<String> = emptySet(),
    var readOnly: Set<String> = emptySet(),
    var paused: Boolean = false,
    val flags: FeatureFlags = FeatureFlags.forBuild(debugBuild = false),
  ) {
    val gate = AppBackupGate(
      BackupGateInputs(
        flags = flags,
        familyReady = { familyReady },
        privateShare = { privateShare },
        account = { account },
        writesStopped = { writesStopped },
        frozen = { it in frozen },
        readOnly = { it in readOnly },
        paused = { paused },
      ),
    )
  }

  private val release = FeatureFlags.forBuild(debugBuild = false)
  private val debug = FeatureFlags.forBuild(debugBuild = true)

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
    val rig = Rig(privateShare = "mario", writesStopped = true, frozen = setOf("mario"), readOnly = setOf("mario"), paused = true)
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
    assertThat(Rig(readOnly = setOf("test"), paused = true).gate.stateOf("test")).isEqualTo(BackupGateState.READ_ONLY_AREA)
    assertThat(Rig(paused = true).gate.stateOf("test")).isEqualTo(BackupGateState.PAUSED)
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

  // --- R2: l'account di prova nella release -------------------------------------------------------------------------------------

  /**
   * Tutte le combinazioni: build (release o debug) x account (di prova o no) x share (di prova o no). Il cancello e' sempre quello di
   * `AppBackupGate`, la decisione quella di `FeatureFlags` (che e' la stessa del codice di release, `RELEASE`).
   */
  @Test
  fun r2_tutteLeCombinazioniDiBuildAccountEShare() {
    data class Case(val build: String, val flags: FeatureFlags, val account: String, val share: String, val expected: BackupGateState)
    val cases = listOf(
      // Release: un account di prova non scrive in nessuna share, nemmeno di prova (D13 non c'e').
      Case("release", release, "test", "test", BackupGateState.TEST_ONLY),
      Case("release", release, "test2", "test2", BackupGateState.TEST_ONLY),
      Case("release", release, "test", "test-comune", BackupGateState.TEST_ONLY),
      Case("release", release, "Test2", "alessio", BackupGateState.TEST_ONLY),
      // Release, account vero: decide D12 (share di prova si', share vera no).
      Case("release", release, "mario", "test", BackupGateState.OPEN),
      Case("release", release, "alessio", "alessio", BackupGateState.TEST_ONLY),
      // Debug: la recinzione del telefono (D13) c'e', quindi l'account di prova puo' provare il backup; D12 resta.
      Case("debug", debug, "test", "test", BackupGateState.OPEN),
      Case("debug", debug, "test2", "test2", BackupGateState.OPEN),
      Case("debug", debug, "test", "alessio", BackupGateState.TEST_ONLY),
      Case("debug", debug, "mario", "test", BackupGateState.OPEN),
      Case("debug", debug, "alessio", "alessio", BackupGateState.TEST_ONLY),
    )
    for (c in cases) {
      val rig = Rig(privateShare = c.share, account = c.account, flags = c.flags)
      assertWithMessage("${c.build}, account '${c.account}', share '${c.share}'").that(rig.gate.stateOf(c.share)).isEqualTo(c.expected)
    }
  }

  @Test
  fun r2_inReleaseUnAccountNonNotoNonScrive_InDebugSi() {
    assertThat(Rig(account = null, flags = release).gate.stateOf("test")).isEqualTo(BackupGateState.TEST_ONLY)
    assertThat(Rig(account = null, flags = debug).gate.stateOf("test")).isEqualTo(BackupGateState.OPEN)
  }

  /** R2 e' una ragione di D12 (lo stesso stato, lo stesso problema): vale prima dell'emergenza, del congelamento, della sola lettura e della pausa. */
  @Test
  fun r2_VinceSuEmergenzaCongelamentoSolaLetturaEPausa() {
    val rig = Rig(account = "test", writesStopped = true, frozen = setOf("test"), readOnly = setOf("test"), paused = true)
    val state = rig.gate.stateOf("test")
    assertThat(state).isEqualTo(BackupGateState.TEST_ONLY)
    assertThat(state.problem).isEqualTo(BackupProblemCode.TEST_ONLY)
  }

  // --- R7: la share deve essere l'area privata di adesso ------------------------------------------------------------------------

  @Test
  fun r7_UnaShareCheNonELAreaPrivataDiAdessoNonSiScrive() {
    val rig = Rig(privateShare = "test")
    assertThat(rig.gate.stateOf("test")).isEqualTo(BackupGateState.OPEN)
    // Un elemento del diario (o un motore costruito) di un'altra share: il cancello dice che non e' la tua area privata.
    val other = rig.gate.stateOf("test2")
    assertThat(other).isEqualTo(BackupGateState.NO_PRIVATE_AREA)
    assertThat(other.problem).isEqualTo(BackupProblemCode.NO_PRIVATE_AREA)
    assertThat(rig.gate.stateOf("test-comune")).isEqualTo(BackupGateState.NO_PRIVATE_AREA)
    // L'admin cambia la configurazione a processo vivo: la share di prima si chiude subito, quella nuova si apre.
    rig.privateShare = "test2"
    assertThat(rig.gate.stateOf("test")).isEqualTo(BackupGateState.NO_PRIVATE_AREA)
    assertThat(rig.gate.stateOf("test2")).isEqualTo(BackupGateState.OPEN)
    rig.privateShare = null
    assertThat(rig.gate.stateOf("test2")).isEqualTo(BackupGateState.NO_PRIVATE_AREA)
  }

  @Test
  fun r7_IlConfrontoDelleShareNonDistingueLeMaiuscole() {
    val rig = Rig(privateShare = "Test")
    for (share in listOf("test", "TEST", "Test", "tEsT")) assertWithMessage(share).that(rig.gate.stateOf(share)).isEqualTo(BackupGateState.OPEN)
    assertThat(Rig(privateShare = "test").gate.stateOf("TEST2")).isEqualTo(BackupGateState.NO_PRIVATE_AREA)
  }

  /** Una share che non e' l'area privata e non e' di prova: nessuna delle due porte si apre (la prima ragione e' quella dell'area). */
  @Test
  fun r7_UnaShareVeraDiUnAltraAreaNonSiApreMai() {
    val rig = Rig(privateShare = "test")
    for (share in listOf("alessio", "pampa", "nas", "public", "")) {
      assertWithMessage(share).that(rig.gate.stateOf(share).open).isFalse()
    }
  }

  // --- R4: la pausa ----------------------------------------------------------------------------------------------------------------

  @Test
  fun r4_LaPausaChiudeIlCancelloSenzaUnProblema() {
    val rig = Rig(paused = true)
    val state = rig.gate.stateOf("test")
    assertThat(state).isEqualTo(BackupGateState.PAUSED)
    assertThat(state.open).isFalse()
    // Non e' un problema: nessun codice, nessun "Backup in prova", nessun messaggio d'allarme.
    assertThat(state.problem).isNull()
    rig.paused = false
    assertThat(rig.gate.stateOf("test")).isEqualTo(BackupGateState.OPEN)
  }
}
