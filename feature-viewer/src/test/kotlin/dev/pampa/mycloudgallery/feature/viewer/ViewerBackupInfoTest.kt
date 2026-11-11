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
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat
import dev.pampa.mycloudgallery.feature.viewer.info.MediaBackupStates
import dev.pampa.mycloudgallery.feature.viewer.info.backupNote
import dev.pampa.mycloudgallery.feature.viewer.info.problemCaptionRes
import dev.pampa.mycloudgallery.feature.viewer.info.queuedReasonRes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * Il backup nel pannello informazioni (Fase 5A, `docs/fase5-progetto.md` §1.5 `MediaBackupState`, D15, D16; riga P5 del §4.4; H2: una
 * capsula con un'icona e due parole): **ogni caso del contratto ha la sua capsula** (l'elenco dei casi si legge dalla classe sigillata), ogni
 * codice del §2.6 ha una didascalia, il pannello segue lo stato mentre e' aperto e senza una fonte la capsula non c'e'. Screenshot in
 * `docs/screenshots/5-P5/`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = SHOT_PHONE)
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
  fun ogniCasoDelContrattoHaLaSuaCapsula() {
    // L'elenco dei casi della classe sigillata: un caso nuovo senza capsula non compila (`when` esaustivo) e senza esempio qui fallisce.
    val declared = MediaBackupState::class.java.classes.toSet()
    assertThat(declared).isNotEmpty()
    assertThat(cases.map { it::class.java }.toSet()).containsExactlyElementsIn(declared)
    for (state in cases) {
      val note = backupNote(format, state, onPhone = true)
      assertWithMessage("$state: etichetta").that(note.label).isNotEmpty()
      // H2: due parole, non una frase.
      assertWithMessage("$state: etichetta corta").that(note.label.split(' ').size).isAtMost(3)
      assertWithMessage("$state: icona").that(note.glyph).isNotNull()
      // TalkBack legge l'argomento: "Backup: Telefono e NAS".
      assertWithMessage("$state: descrizione").that(note.description).startsWith("Backup: " + note.label)
    }
  }

  @Test
  fun leCapsuleDiconoCioCheSuccedeInDueParole() {
    val safe = backupNote(format, MediaBackupState.Safe(1_792_119_120_000), onPhone = true)
    assertThat(safe.label).isEqualTo("Telefono e NAS")
    assertThat(safe.tone).isEqualTo(GalleryFooterTone.Calm)
    // Al sicuro non servono spiegazioni.
    assertThat(safe.detail).isNull()
    assertThat(backupNote(format, MediaBackupState.Safe(1L), onPhone = false).label).isEqualTo("Sul NAS")

    val uploading = backupNote(format, MediaBackupState.Uploading(12_000_000, 48_000_000), onPhone = true)
    assertThat(uploading.label).isEqualTo("In caricamento")
    assertThat(uploading.tone).isEqualTo(GalleryFooterTone.Working)
    assertThat(uploading.detail).contains("25%")
    assertThat(uploading.progress).isWithin(0.001f).of(0.25f)
    val unknown = backupNote(format, MediaBackupState.Uploading(0, 0), onPhone = true)
    assertThat(unknown.detail).isEqualTo("Sto calcolando.")
    assertThat(unknown.progress).isNull()

    val verifying = backupNote(format, MediaBackupState.Verifying(30_000_000, 48_000_000), onPhone = true)
    assertThat(verifying.label).isEqualTo("In verifica")
    assertThat(verifying.busy).isTrue()

    val queued = backupNote(format, MediaBackupState.Queued(WaitReason.CHARGING), onPhone = true)
    assertThat(queued.label).isEqualTo("In coda")
    assertThat(queued.detail).isEqualTo("Aspetta che il telefono sia in carica.")

    assertThat(backupNote(format, MediaBackupState.NotOnNas(NotOnNasReason.NOT_YET), onPhone = true).label).isEqualTo("Solo sul telefono")
    assertThat(backupNote(format, MediaBackupState.OnlyCommonCopy, onPhone = true).label).isEqualTo("Solo area comune")
    assertThat(backupNote(format, MediaBackupState.NotMine, onPhone = false).label).isEqualTo("Non è tuo")
    val lost = backupNote(format, MediaBackupState.LostBeforeBackup, onPhone = false)
    assertThat(lost.label).isEqualTo("Non salvato")
    assertThat(lost.tone).isEqualTo(GalleryFooterTone.Alarm)
  }

  @Test
  fun unMediaAlSicuroNonDiceMaiProblemaEViceversa() {
    val safe = backupNote(format, MediaBackupState.Safe(1L), onPhone = true)
    val problem = backupNote(format, MediaBackupState.Problem(BackupProblemCode.VERIFY_FAILED), onPhone = true)
    assertThat(safe.label).doesNotContain("Problema")
    assertThat(safe.tone).isNotEqualTo(GalleryFooterTone.Alarm)
    assertThat(problem.label).isEqualTo("Problema di backup")
    assertThat(problem.tone).isEqualTo(GalleryFooterTone.Alarm)
    assertThat(problem.detail).isEqualTo(context.getString(problemCaptionRes(BackupProblemCode.VERIFY_FAILED)))
    assertThat(problem.label).doesNotContain("sicuro")
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
    composeRule.onNodeWithTag(ViewerTags.InfoPanel).assertExists()
    assertThat(composeRule.onAllNodesWithText("Backup", ignoreCase = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun ilPannelloAperto_SegueLoStatoEChiuso_NonAscolta() {
    val source = FakeStates(MediaBackupState.Uploading(12_000_000, 48_000_000))
    show(services = services(backup = source), dark = dark)
    assertThat(source.observed).isEqualTo(0)
    openInfo()
    composeRule.onNodeWithText("In caricamento").assertExists()
    composeRule.onNodeWithContentDescription("Backup: In caricamento", substring = true).assertExists()
    shot("info-backup-in-caricamento")
    // Lo stato cambia mentre il pannello e' aperto: la capsula lo segue.
    source.state.value = MediaBackupState.Safe(1_792_119_120_000)
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Telefono e NAS").assertExists()
    assertThat(composeRule.onAllNodesWithText("In caricamento").fetchSemanticsNodes()).isEmpty()
    shot("info-backup-al-sicuro")
  }

  @Test
  fun screenshotDeiCasiPrincipali() {
    val source = FakeStates(MediaBackupState.Problem(BackupProblemCode.VERIFY_FAILED))
    show(services = services(backup = source), dark = dark)
    openInfo()
    composeRule.onNodeWithText("Problema di backup").assertExists()
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
    composeRule.onNodeWithText("Telefono e NAS").assertExists()
    shot("info-backup-al-sicuro-testo-grande")
  }

  @Config(qualifiers = TABLET)
  @Test
  fun pannelloSulTablet() {
    val source = FakeStates(MediaBackupState.Safe(1_792_119_120_000))
    show(services = services(backup = source), dark = dark)
    composeRule.onNodeWithContentDescription("Informazioni").performClick()
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithText("Telefono e NAS").fetchSemanticsNodes().isNotEmpty() }
    composeRule.mainClock.advanceTimeBy(800)
    shot("info-backup-al-sicuro", device = "tablet")
  }
}
