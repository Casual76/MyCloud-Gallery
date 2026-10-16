package dev.pampa.mycloudgallery.feature.viewer

import android.content.Context
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.MediaBackupState
import dev.pampa.mycloudgallery.core.backup.contract.NotOnNasReason
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat
import dev.pampa.mycloudgallery.feature.viewer.info.MediaBackupStates
import dev.pampa.mycloudgallery.feature.viewer.info.backupInfoRow
import dev.pampa.mycloudgallery.feature.viewer.info.problemCaptionRes
import dev.pampa.mycloudgallery.feature.viewer.info.queuedReasonRes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * La riga "Backup" del pannello informazioni (Fase 5A, `docs/fase5-progetto.md` §1.5 `MediaBackupState`, D15, D16; riga P5 del §4.4):
 * **ogni caso del contratto ha la sua riga** (l'elenco dei casi si legge dalla classe sigillata), ogni codice del §2.6 ha una didascalia,
 * il pannello segue lo stato mentre e' aperto e senza una fonte la sezione non c'e'. Screenshot in `docs/screenshots/5-P5/`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ViewerBackupInfoTest(private val dark: Boolean) : ViewerTestBase() {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))

    /** Un esempio per ogni caso del contratto. */
    val cases: List<MediaBackupState> = listOf(
      MediaBackupState.NotMine,
      MediaBackupState.NotOnNas(NotOnNasReason.SOURCE_OFF),
      MediaBackupState.NotOnNas(NotOnNasReason.BACKUP_OFF),
      MediaBackupState.NotOnNas(NotOnNasReason.NOT_YET),
      MediaBackupState.Queued(WaitReason.CHARGING),
      MediaBackupState.Queued(null),
      MediaBackupState.Uploading(bytesDone = 12_000_000, bytesTotal = 48_000_000),
      MediaBackupState.Uploading(bytesDone = 0, bytesTotal = 0),
      MediaBackupState.Verifying(bytesDone = 30_000_000, bytesTotal = 48_000_000),
      MediaBackupState.Safe(verifiedAtMillis = 1_792_119_120_000),
      MediaBackupState.OnlyCommonCopy,
      MediaBackupState.Unverified,
      MediaBackupState.Problem(BackupProblemCode.VERIFY_FAILED),
      MediaBackupState.LostBeforeBackup,
    )
  }

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val format = ViewerFormat(context)

  /** La fonte di prova: lo stato lo cambia il test, e si ricorda quante volte e' stata ascoltata. */
  private class FakeStates(first: MediaBackupState) : MediaBackupStates {
    val state = MutableStateFlow(first)
    var observed = 0

    override fun observe(id: MediaId): Flow<MediaBackupState> {
      observed++
      return state
    }
  }

  private fun shot(name: String, device: String = "telefono") {
    composeRule.onRoot().captureRoboImage("../docs/screenshots/5-P5/viewer-$name-${if (dark) "scuro" else "chiaro"}-$device.png")
  }

  private fun openInfo() {
    pagerSwipeUp()
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(800)
  }

  private fun pagerSwipeUp() {
    composeRule.onNodeWithTag(ViewerTags.Pager).performTouchInput { swipeUp() }
  }

  // --- Le righe, dal contratto --------------------------------------------------------------------------------------------------

  @Test
  fun ogniCasoDelContrattoHaLaSuaRiga() {
    // L'elenco dei casi della classe sigillata: un caso nuovo senza riga non compila (`when` esaustivo) e senza esempio qui fallisce.
    val declared = MediaBackupState::class.java.classes.toSet()
    assertThat(declared).isNotEmpty()
    assertThat(cases.map { it::class.java }.toSet()).containsExactlyElementsIn(declared)
    for (state in cases) {
      val row = backupInfoRow(format, state)
      assertWithMessage("$state: titolo").that(row.title).isNotEmpty()
      assertWithMessage("$state: didascalia").that(row.caption).isNotEmpty()
      assertWithMessage("$state: icona").that(row.icon).isNotNull()
    }
  }

  @Test
  fun leRigheDiconoCioCheSuccede() {
    assertThat(backupInfoRow(format, MediaBackupState.Safe(1_792_119_120_000)).title).isEqualTo("Al sicuro sul NAS")
    assertThat(backupInfoRow(format, MediaBackupState.Safe(1_792_119_120_000)).caption).contains(" alle ")
    assertThat(backupInfoRow(format, MediaBackupState.Uploading(12_000_000, 48_000_000)).caption).contains("25%")
    assertThat(backupInfoRow(format, MediaBackupState.Uploading(0, 0)).caption).isEqualTo("Sto calcolando.")
    assertThat(backupInfoRow(format, MediaBackupState.Verifying(30_000_000, 48_000_000)).title).isEqualTo("Controllo della copia sul NAS")
    assertThat(backupInfoRow(format, MediaBackupState.Queued(WaitReason.CHARGING)).caption).isEqualTo("Aspetta che il telefono sia in carica.")
    assertThat(backupInfoRow(format, MediaBackupState.OnlyCommonCopy).title).isEqualTo("Solo nell’area comune")
    assertThat(backupInfoRow(format, MediaBackupState.LostBeforeBackup).title).isEqualTo("Cancellato prima del backup")
  }

  @Test
  fun unMediaAlSicuroNonDiceMaiProblemaEViceversa() {
    val safe = backupInfoRow(format, MediaBackupState.Safe(1L))
    val problem = backupInfoRow(format, MediaBackupState.Problem(BackupProblemCode.VERIFY_FAILED))
    assertThat(safe.title).doesNotContain("Problema")
    assertThat(problem.title).isEqualTo("Problema con il backup")
    assertThat(problem.title).doesNotContain("sicuro")
  }

  @Test
  fun ogniCodiceDelContrattoHaUnaDidascalia() {
    for (code in BackupProblemCode.entries) {
      val caption = context.getString(problemCaptionRes(code))
      assertWithMessage("$code: didascalia").that(caption).isNotEmpty()
      // Un problema di un media ha la sua frase; gli altri mandano alla pagina Backup (il testo non e' mai muto).
      if (code.marksMedia) assertThat(caption).isNotEqualTo(context.getString(R.string.viewer_backup_problem_generic))
    }
  }

  @Test
  fun ogniMotivoDiAttesaHaLaSuaFrase() {
    for (reason in WaitReason.entries + listOf(null)) {
      assertWithMessage("$reason").that(context.getString(queuedReasonRes(reason))).isNotEmpty()
    }
  }

  // --- Il pannello ----------------------------------------------------------------------------------------------------------------

  @Test
  fun senzaUnaFonteLaSezioneNonCe() {
    show(dark = dark)
    openInfo()
    composeRule.onNodeWithText("Informazioni").assertExists()
    assertThat(composeRule.onAllNodesWithText("Backup", ignoreCase = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun ilPannelloAperto_SegueLoStatoEChiuso_NonAscolta() {
    val source = FakeStates(MediaBackupState.Uploading(12_000_000, 48_000_000))
    show(services = services(backup = source), dark = dark)
    assertThat(source.observed).isEqualTo(0)
    openInfo()
    composeRule.onNodeWithText("In caricamento sul NAS").assertExists()
    composeRule.onNodeWithText("Backup", ignoreCase = true).assertExists()
    shot("info-backup-in-caricamento")
    // Lo stato cambia mentre il pannello e' aperto: la riga lo segue.
    source.state.value = MediaBackupState.Safe(1_792_119_120_000)
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Al sicuro sul NAS").assertExists()
    assertThat(composeRule.onAllNodesWithText("In caricamento sul NAS").fetchSemanticsNodes()).isEmpty()
    shot("info-backup-al-sicuro")
  }

  @Test
  fun screenshotDeiCasiPrincipali() {
    val source = FakeStates(MediaBackupState.Problem(BackupProblemCode.VERIFY_FAILED))
    show(services = services(backup = source), dark = dark)
    openInfo()
    composeRule.onNodeWithText("Problema con il backup").assertExists()
    shot("info-backup-problema")
    source.state.value = MediaBackupState.OnlyCommonCopy
    composeRule.waitForIdle()
    shot("info-backup-area-comune")
    source.state.value = MediaBackupState.Queued(WaitReason.CHARGING)
    composeRule.waitForIdle()
    shot("info-backup-in-coda")
    source.state.value = MediaBackupState.LostBeforeBackup
    composeRule.waitForIdle()
    shot("info-backup-cancellato")
  }

  @Test
  fun testoGrande() {
    composeRule.activity.resources.configuration.fontScale = 1.3f
    val source = FakeStates(MediaBackupState.Safe(1_792_119_120_000))
    show(services = services(backup = source), dark = dark)
    openInfo()
    composeRule.onNodeWithText("Al sicuro sul NAS").assertExists()
    shot("info-backup-al-sicuro-testo-grande")
  }

  @Config(qualifiers = TABLET)
  @Test
  fun pannelloSulTablet() {
    val source = FakeStates(MediaBackupState.Safe(1_792_119_120_000))
    show(services = services(backup = source), dark = dark)
    composeRule.onNodeWithContentDescription("Informazioni").performClick()
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithText("Al sicuro sul NAS").fetchSemanticsNodes().isNotEmpty() }
    composeRule.mainClock.advanceTimeBy(800)
    shot("info-backup-al-sicuro", device = "tablet")
  }
}
