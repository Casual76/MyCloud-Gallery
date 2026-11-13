package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryIndexSync
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pacchetto XN (decisione dell'utente, 13/11/2026): la sezione dell'analisi nel pannello della nuvola, disegnata nella Libreria vera (sintetica).
 *  - per **ogni** stato dell'analisi il tasto della nuvola e il titolo del pannello restano quelli del backup («Tutto al sicuro»), lo spazio del
 *    NAS resta, e la sezione dice lo stato una volta (in corso, in coda, in pausa con **ciascun** motivo, finita, ferma per un problema, senza
 *    modello) o non c'e' (spenta, fatta altrove, niente ricerca);
 *  - la riga «Sul NAS»: avvenuta (l'ora), mai avvenuta, non applicabile (assente);
 *  - senza NAS e senza backup l'analisi non fa nascere la nuvola.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h780dp-xhdpi")
class LibraryCloudAnalysisSectionTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val context: Application get() = ApplicationProvider.getApplicationContext()

  private object SpaceCommands : LibraryCommands {
    override val canDeleteFromPhone: Boolean get() = true
    override val canFreeSpace: Boolean get() = true
    override suspend fun nasSpace(): LibraryNasSpace = LibraryNasSpace.Ready(usedBytes = 1_250_000_000_000L, totalBytes = 3_600_000_000_000L)
  }

  private lateinit var analysis: MutableStateFlow<LibraryAnalysisStatus>
  private lateinit var sync: MutableStateFlow<LibraryIndexSync>

  private fun mount(
    nas: LibraryNasStatus = LibraryNasStatus.UpToDate(atNasMillis = System.currentTimeMillis()),
    backup: LibraryBackupStatus = LibraryBackupStatus.AllSafe(atNasMillis = System.currentTimeMillis()),
    status: LibraryAnalysisStatus = LibraryAnalysisStatus.Idle,
    indexSync: LibraryIndexSync = LibraryIndexSync.NotApplicable,
  ) {
    val state = syntheticLibraryState(nas = nas, backup = backup, commands = SpaceCommands, analysis = status, indexSync = indexSync)
    analysis = state.analysis as MutableStateFlow<LibraryAnalysisStatus>
    sync = state.indexSync as MutableStateFlow<LibraryIndexSync>
    composeRule.setContent {
      MyCloudGalleryTheme {
        val modal = rememberFluidGlassModalHostState()
        val rootBackdrop = rememberGlassBackdrop()
        CompositionLocalProvider(LocalFluidGlassModalHostState provides modal) {
          Box(modifier = Modifier.fillMaxSize()) {
            LibraryTab(contentPadding = PaddingValues(), backdropOut = {}, onOpenViewer = {}, onOpenSettings = {}, state = state, network = null)
            FluidGlassModalHost(state = modal, backdrop = rootBackdrop)
          }
        }
      }
    }
    settle()
  }

  private fun settle() {
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
    composeRule.waitForIdle()
  }

  /** Le descrizioni del tasto della nuvola (TalkBack): il tasto dice lo stato che il suo glifo disegna. */
  private fun buttonDescriptions(): List<String> =
    composeRule.onAllNodesWithContentDescription(PREFIX, substring = true, useUnmergedTree = true).fetchSemanticsNodes()
      .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
      .filter { it.startsWith(PREFIX) }
      .distinct()

  private fun openPanel() {
    composeRule.onAllNodesWithContentDescription(PREFIX, substring = true)[0].performClick()
    settle()
  }

  private fun texts(text: String, substring: Boolean = false): Int =
    composeRule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().size

  /** Quante volte [text] si vede o si sente (testo o descrizione, anche dentro un nodo che unisce i figli). */
  private fun seen(text: String): Int =
    texts(text, substring = true) +
      composeRule.onAllNodesWithContentDescription(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size

  /** La riga «Sul NAS» come la sente TalkBack (e' anche il testo che si vede); null se non c'e'. */
  private fun syncLine(): String? =
    composeRule.onAllNodesWithTag(IndexSyncTag, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
      ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.single()

  private fun tagged(tag: String): Int = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

  /** La descrizione della riga con l'anello (lo stato detto una volta, con il conto o il perche'). */
  private fun rowDescription(): String =
    composeRule.onAllNodesWithTag(AnalysisRowTag).fetchSemanticsNodes().single().config[SemanticsProperties.ContentDescription].joinToString()

  private fun show(status: LibraryAnalysisStatus) {
    analysis.value = status
    settle()
  }

  private val safe: String get() = context.getString(R.string.backup_footer_all_safe)

  /** Il tasto e il titolo sono del backup, lo spazio c'e'; nessun titolo di un altro stato. */
  private fun assertBackupOwnsCloud(why: String) {
    assertWithMessage("tasto, $why").that(buttonDescriptions()).containsExactly(context.getString(R.string.library_cloud_button, safe))
    assertWithMessage("titolo, $why").that(texts(safe)).isAtLeast(1)
    for (other in listOf(
      R.string.library_cloud_title_syncing, R.string.library_cloud_title_waiting, R.string.library_cloud_title_paused,
      R.string.library_cloud_title_attention, R.string.library_cloud_title_alarm,
    )) {
      assertWithMessage("nessun altro titolo, $why").that(texts(context.getString(other))).isEqualTo(0)
    }
    assertWithMessage("spazio, $why").that(tagged(SpaceRowTag)).isEqualTo(1)
  }

  private val running = LibraryAnalysisStatus.Running(remaining = 2_700, etaMinutes = 40, ready = 12_300, total = 15_000)

  @Test
  fun lAnalisiNonCambiaMaiTitoloETastoDellaNuvola() {
    mount()
    val statuses = listOf(
      LibraryAnalysisStatus.Idle,
      running,
      LibraryAnalysisStatus.Running(remaining = null, etaMinutes = null, ready = null, total = null),
      LibraryAnalysisStatus.Queued(remaining = 2_700, ready = 12_300, total = 15_000),
      LibraryAnalysisStatus.AllReady,
      LibraryAnalysisStatus.Problem("AI_STORAGE_LOW"),
      LibraryAnalysisStatus.Problem("AI_ENGINE_FAILED"),
      LibraryAnalysisStatus.ModelMissing,
      LibraryAnalysisStatus.Disabled,
      LibraryAnalysisStatus.NotHere(ready = 3, total = 4),
    ) + LibraryAnalysisWait.entries.map { LibraryAnalysisStatus.Paused(it, remaining = 2_700, ready = 12_300, total = 15_000) }
    // A pannello chiuso: il tasto (il glifo e la sua descrizione) non si muove.
    for (status in statuses) {
      show(status)
      assertWithMessage("tasto chiuso, $status").that(buttonDescriptions()).containsExactly(context.getString(R.string.library_cloud_button, safe))
    }
    // A pannello aperto: il titolo resta del backup e lo spazio resta, per ogni stato.
    show(LibraryAnalysisStatus.Idle)
    openPanel()
    for (status in statuses) {
      show(status)
      assertBackupOwnsCloud(status.toString())
    }
  }

  @Test
  fun inCorsoLAnelloDiceIlContoEIlTastoPortaAllaPagina() {
    mount(status = running, indexSync = LibraryIndexSync.At(today(14, 1)))
    openPanel()
    assertThat(tagged(AnalysisSectionTag)).isEqualTo(1)
    assertThat(tagged(AnalysisDividerTag)).isEqualTo(1)
    assertThat(rowDescription()).isEqualTo("Analisi in corso. 12.300 / 15.000")
    // Niente minuti (XU-1) e niente «Restano» quando c'e' il conto.
    assertThat(seen(" min")).isEqualTo(0)
    assertThat(seen("Restano")).isEqualTo(0)
    assertThat(syncLine()).isEqualTo("Sul NAS: oggi alle 14:01")
  }

  @Test
  fun inCorsoSenzaTotaleDiceQuanteNeRestano() {
    mount(status = LibraryAnalysisStatus.Running(remaining = 40, etaMinutes = null, ready = null, total = null))
    openPanel()
    assertThat(rowDescription()).isEqualTo("Analisi in corso. Restano 40 foto")
  }

  @Test
  fun inCodaDiceLoStatoUnaVolta() {
    mount(status = LibraryAnalysisStatus.Queued(remaining = 2_700, ready = 12_300, total = 15_000))
    openPanel()
    assertThat(rowDescription()).isEqualTo("Analisi in coda. 12.300 / 15.000")
    // Senza numeri, «Parte da sola».
    show(LibraryAnalysisStatus.Queued(remaining = null, ready = null, total = null))
    assertThat(rowDescription()).isEqualTo("Analisi in coda. Parte da sola")
  }

  @Test
  fun inPausaConCiascunMotivoVero() {
    mount(status = LibraryAnalysisStatus.Paused(LibraryAnalysisWait.THERMAL, remaining = 2_700, ready = 12_300, total = 15_000))
    openPanel()
    val expected = mapOf(
      LibraryAnalysisWait.NIGHT_WINDOW to "Fuori dall’orario",
      LibraryAnalysisWait.CHARGING to "Serve la carica",
      LibraryAnalysisWait.BATTERY to context.getString(R.string.library_cloud_analysis_wait_battery),
      LibraryAnalysisWait.POWER_SAVER to "Risparmio energetico",
      LibraryAnalysisWait.THERMAL to "Telefono caldo",
      LibraryAnalysisWait.WIFI to "Aspetta il Wi-Fi",
      LibraryAnalysisWait.HOME_WIFI to "Aspetta il Wi-Fi di casa",
      LibraryAnalysisWait.NAS to "Il NAS non risponde",
      LibraryAnalysisWait.DATA_CAP to "Dati del mese finiti",
      LibraryAnalysisWait.PREPARATION to "Foto in preparazione",
      LibraryAnalysisWait.ANDROID_LIMIT to "Aspetta il momento giusto",
      LibraryAnalysisWait.PROFILE to "Aspetta il momento giusto",
      LibraryAnalysisWait.OTHER to "Aspetta il momento giusto",
    )
    assertThat(expected.keys).containsExactlyElementsIn(LibraryAnalysisWait.entries)
    for ((reason, why) in expected) {
      show(LibraryAnalysisStatus.Paused(reason, remaining = 2_700, ready = 12_300, total = 15_000))
      assertWithMessage(reason.name).that(rowDescription()).isEqualTo("Analisi in pausa. $why")
      // La pausa (anche quella pianificata) e' uno stato tranquillo: nessuna capsula d'allarme, nessun tasto in piu'.
      assertWithMessage(reason.name).that(seen(context.getString(R.string.library_cloud_action_analysis))).isEqualTo(0)
    }
  }

  @Test
  fun finitaDiceTutteAnalizzate() {
    mount(status = LibraryAnalysisStatus.AllReady, indexSync = LibraryIndexSync.At(today(9, 30)))
    openPanel()
    assertThat(rowDescription()).isEqualTo("Tutte analizzate")
    assertThat(seen("100%")).isEqualTo(0)
    assertThat(syncLine()).isEqualTo("Sul NAS: oggi alle 09:30")
  }

  @Test
  fun fermaPerUnProblemaHaLaCapsulaEIlSuoTasto() {
    mount(status = LibraryAnalysisStatus.Problem("AI_STORAGE_LOW"), indexSync = LibraryIndexSync.Never)
    openPanel()
    assertThat(seen("Memoria quasi piena")).isAtLeast(1)
    assertThat(seen(context.getString(R.string.library_cloud_action_analysis))).isAtLeast(1)
    assertThat(tagged(AnalysisRowTag)).isEqualTo(0)
    assertThat(syncLine()).isEqualTo("Non ancora sul NAS")
    show(LibraryAnalysisStatus.ModelMissing)
    assertThat(seen("Manca il modello")).isAtLeast(1)
    assertThat(seen(context.getString(R.string.library_cloud_action_analysis))).isAtLeast(1)
  }

  @Test
  fun spentaOFattaAltroveLaSezioneNonCe() {
    mount(status = LibraryAnalysisStatus.Disabled, indexSync = LibraryIndexSync.At(today(14, 1)))
    openPanel()
    for (status in listOf(LibraryAnalysisStatus.Disabled, LibraryAnalysisStatus.NotHere(3, 4), LibraryAnalysisStatus.Idle)) {
      show(status)
      assertWithMessage(status.toString()).that(tagged(AnalysisSectionTag)).isEqualTo(0)
      assertWithMessage(status.toString()).that(tagged(AnalysisDividerTag)).isEqualTo(0)
      assertWithMessage(status.toString()).that(tagged(IndexSyncTag)).isEqualTo(0)
    }
  }

  @Test
  fun laRigaSulNasAvvenutaMaiONonApplicabile() {
    mount(status = running, indexSync = LibraryIndexSync.At(today(14, 1)))
    openPanel()
    assertThat(syncLine()).isEqualTo("Sul NAS: oggi alle 14:01")
    sync.value = LibraryIndexSync.At(dayBefore(3, 12))
    settle()
    assertThat(syncLine()).matches("Sul NAS: \\d{1,2} \\p{L}+ \\d{4} alle 03:12")
    sync.value = LibraryIndexSync.Never
    settle()
    assertThat(syncLine()).isEqualTo("Non ancora sul NAS")
    sync.value = LibraryIndexSync.NotApplicable
    settle()
    assertThat(tagged(IndexSyncTag)).isEqualTo(0)
    // La sezione resta: manca solo la riga.
    assertThat(tagged(AnalysisSectionTag)).isEqualTo(1)
  }

  @Test
  fun senzaNasNeBackupLAnalisiNonFaNascereLaNuvola() {
    mount(nas = LibraryNasStatus.Idle, backup = LibraryBackupStatus.Idle, status = running, indexSync = LibraryIndexSync.NotApplicable)
    assertThat(buttonDescriptions()).isEmpty()
  }

  private fun today(hour: Int, minute: Int): Long = LocalDate.now().atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

  private fun dayBefore(hour: Int, minute: Int): Long =
    LocalDate.now().minusDays(2).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

  private companion object {
    const val PREFIX = "Stato della sincronizzazione"
    const val SETTLE_MILLIS = 1_500L
  }
}
