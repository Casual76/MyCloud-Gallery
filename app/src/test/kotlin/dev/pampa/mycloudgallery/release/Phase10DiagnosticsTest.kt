package dev.pampa.mycloudgallery.release

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailSource
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.release.contract.AppVersion
import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.release.contract.RejectReason
import dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel
import dev.pampa.mycloudgallery.core.release.contract.ReleaseDiag
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.ReleaseState
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.widget.contract.WidgetBlankCause
import dev.pampa.mycloudgallery.core.widget.contract.WidgetDiagCodes
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetLock
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetMemories
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetPrefs
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportKeys
import dev.pampa.mycloudgallery.feature.integrations.drag.DragCounter
import dev.pampa.mycloudgallery.feature.integrations.drag.DragCounters
import dev.pampa.mycloudgallery.feature.widget.host.WidgetPlaceNames
import dev.pampa.mycloudgallery.integrations.IntegrationsGraph
import dev.pampa.mycloudgallery.widget.LocalWidgetThumbs
import dev.pampa.mycloudgallery.widget.WidgetCounters
import dev.pampa.mycloudgallery.widget.WidgetDiagnostics
import dev.pampa.mycloudgallery.widget.WidgetGraph
import dev.pampa.mycloudgallery.window.WindowDiagnostics
import dev.pampa.mycloudgallery.window.WindowRegistry
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fase 10 (P13, docs/fase10-progetto.md §2.13, D39, C449): le voci della Diagnostica di widget, manifest, trascinamento e finestre. Ogni codice e' nel catalogo con la sua
 * sezione, ogni chiave e' nell'elenco chiuso di `ExportKeys` (altrimenti l'esportazione la toglierebbe in silenzio), i codici del widget sono quelli di `WidgetDiagCodes`,
 * un rifiuto per firma o chiave sconosciuta e' un avviso e **nessuna** chiave parla di accessibilita' (D39: se un servizio e' attivo non si registra).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Phase10DiagnosticsTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()

  @After
  fun tearDown() = DragCounters.reset()

  /** Lo stato del manifest finto: [status] e [diag] decisi dal test. */
  private class FixedState(status: ReleaseStatus, diag: ReleaseDiag) : ReleaseState {
    override val status: StateFlow<ReleaseStatus> = MutableStateFlow(status)
    override val diag: StateFlow<ReleaseDiag> = MutableStateFlow(diag)

    override suspend fun load() = Unit

    override suspend fun offer(bytes: ByteArray, origin: ManifestOrigin): OfferResult = OfferResult.REJECTED

    override fun extraUrls(id: String, sha256: String): List<String> = emptyList()

    override suspend fun clear() = Unit
  }

  private fun release(status: ReleaseStatus, rejected: Map<RejectReason, Int>): ReleaseGraph = ReleaseGraph(
    dir = temp.newFolder(),
    version = AppVersion(12, "1.1.0-beta.2", ReleaseChannel.BETA),
    state = FixedState(status, ReleaseDiag(4, Instant.now().minusSeconds(3 * 86_400), status, 2, rejected)),
  )

  private fun widget(): WidgetGraph {
    val visibility = FakeVisibility()
    return WidgetGraph.create(
      context = app,
      feed = dev.pampa.mycloudgallery.core.library.testing.FakeWidgetFeed(visibility, visibility),
      visible = visibility,
      mask = visibility,
      memories = FakeWidgetMemories(),
      thumbs = LocalWidgetThumbs({ null }, NoThumbnailSource),
      lock = FakeWidgetLock(),
      prefs = FakeWidgetPrefs(),
      places = WidgetPlaceNames { null },
      awaitShelf = {},
      stoppedByRelease = { false },
      counters = WidgetCounters(),
      ledger = InMemoryWidgetLedger(),
    )
  }

  private fun assertExportable(items: List<DiagItem>, section: DiagSection) {
    assertThat(items).isNotEmpty()
    for (item in items) {
      assertWithMessage("${item.code.id} nel catalogo").that(item.code in DiagCatalog).isTrue()
      assertWithMessage("${item.code.id} nella sezione").that(DiagCatalog.entry(item.code)?.section).isEqualTo(section)
      val outside = item.export.keys.filterNot(ExportKeys::isAllowed)
      assertWithMessage("chiavi fuori da ExportKeys in ${item.code.id}").that(outside).isEmpty()
      for (key in item.export.keys) {
        assertWithMessage("$key parla di accessibilita' (D39)").that(Regex("(?i)a11y|accessib|talkback|tool").containsMatchIn(key)).isFalse()
      }
    }
  }

  @Test
  fun iCodiciDelWidgetSonoQuelliDelContratto() {
    assertThat(DiagCodes.WIDGET_STATE.id).isEqualTo(WidgetDiagCodes.STATE)
    assertThat(DiagCodes.WIDGET_SKIPPED.id).isEqualTo(WidgetDiagCodes.SKIPPED)
    assertThat(DiagCodes.WIDGET_BLANKED.id).isEqualTo(WidgetDiagCodes.BLANKED)
    assertThat(DiagCodes.WIDGET_LATE.id).isEqualTo(WidgetDiagCodes.LATE)
    assertThat(DiagCodes.WIDGET_ERROR.id).isEqualTo(WidgetDiagCodes.ERROR)
  }

  @Test
  fun leVociDelWidgetSonoSoloNumeriNellElenco() = runTest {
    val graph = widget()
    val diagnostics = WidgetDiagnostics(graph)
    // Senza niente da dire, la sola voce di stato.
    assertThat(diagnostics.collect().map { it.code }).containsExactly(DiagCodes.WIDGET_STATE)
    graph.counters.skippedNoThumb(3)
    graph.counters.skippedLocked(1)
    graph.counters.blanked(WidgetBlankCause.HIDDEN, 2)
    graph.counters.planned(WidgetPlan(WidgetSlot(1), WidgetSize.SMALL, WidgetMode.Message(WidgetEmpty.ERROR), emptyList(), 1))
    val items = diagnostics.collect()
    assertThat(items.map { it.code }).containsExactly(DiagCodes.WIDGET_STATE, DiagCodes.WIDGET_SKIPPED, DiagCodes.WIDGET_BLANKED, DiagCodes.WIDGET_ERROR)
    assertExportable(items, DiagSection.WIDGET)
    assertThat(items.first { it.code == DiagCodes.WIDGET_ERROR }.severity).isEqualTo(DiagSeverity.WARNING)
  }

  @Test
  fun leVociDelManifestNonDiconoIlContenuto() = runTest {
    val normal = ReleaseDiagnostics(release(ReleaseStatus.Normal, emptyMap())).collect()
    assertThat(normal.map { it.code }).containsExactly(DiagCodes.RELEASE_STATE)
    assertExportable(normal, DiagSection.RELEASE)

    val malformed = ReleaseDiagnostics(release(ReleaseStatus.Normal, mapOf(RejectReason.MALFORMED to 1))).collect()
    assertThat(malformed.first { it.code == DiagCodes.RELEASE_MANIFEST_REJECTED }.severity).isEqualTo(DiagSeverity.INFO)

    val forged = ReleaseDiagnostics(release(ReleaseStatus.Stopped(ReleaseMessage.SECURITY), mapOf(RejectReason.SIGNATURE to 2))).collect()
    assertThat(forged.map { it.code }).containsExactly(DiagCodes.RELEASE_STATE, DiagCodes.RELEASE_MANIFEST_REJECTED, DiagCodes.RELEASE_BLOCKED)
    assertExportable(forged, DiagSection.RELEASE)
    assertThat(forged.first { it.code == DiagCodes.RELEASE_MANIFEST_REJECTED }.severity).isEqualTo(DiagSeverity.WARNING)
    assertThat(forged.first { it.code == DiagCodes.RELEASE_MANIFEST_REJECTED }.export.keys).containsExactly("rejected.signature")
    assertThat(forged.first { it.code == DiagCodes.RELEASE_BLOCKED }.severity).isEqualTo(DiagSeverity.WARNING)
    val state = forged.first { it.code == DiagCodes.RELEASE_STATE }.export
    assertThat(state.keys).containsAtLeast("versionCode", "channel", "seq", "state", "extraUrls", "issuedDays")
  }

  @Test
  fun ilTrascinamentoELeFinestreHannoLeLoroVoci() = runTest {
    DragCounters.add(DragCounter.OUT_READY)
    DragCounters.add(DragCounter.OUT_REFUSED_HIDDEN)
    DragCounters.add(DragCounter.IN_DROPPED)
    DragCounters.add(DragCounter.TEMP_CLEANED, 3)
    val drag = IntegrationsGraph.dragItems(DiagSection.BACKUP)
    assertThat(drag.map { it.code }).containsExactly(DiagCodes.DRAG_OUT, DiagCodes.DRAG_IN, DiagCodes.DRAG_TEMP_CLEANED)
    assertExportable(drag, DiagSection.BACKUP)
    // Ogni contatore del trascinamento ha la sua chiave, in una sola voce.
    assertThat(drag.flatMap { it.export.keys }.toSet()).isEqualTo(DragCounter.entries.map { it.code }.toSet())

    val window = WindowDiagnostics(app, WindowRegistry()).collect()
    assertExportable(window, DiagSection.PROFILES)
    assertThat(window.single().export.keys).containsExactly("windows", "widthClass")
  }

  @Test
  fun lOraDellUltimoAggiornamentoVieneDalRegistroSuFile() = runTest {
    val graph = widget()
    graph.ledgerFile.parentFile?.mkdirs()
    graph.ledgerFile.writeBytes(byteArrayOf(1))
    val now = System.currentTimeMillis()
    graph.ledgerFile.setLastModified(now - 12 * 60_000L)
    val diag = WidgetDiagnostics(graph, nowMs = { now }).read()
    assertThat(diag.minutesSinceUpdate).isEqualTo(12)
    graph.ledgerFile.delete()
    assertThat(File(graph.ledgerFile.path).exists()).isFalse()
    assertThat(WidgetDiagnostics(graph).read().minutesSinceUpdate).isNull()
  }
}
