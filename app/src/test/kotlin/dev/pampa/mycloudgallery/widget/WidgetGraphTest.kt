package dev.pampa.mycloudgallery.widget

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailSource
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.security.AppLockMode
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.widget.contract.WidgetBlankCause
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCell
import dev.pampa.mycloudgallery.core.widget.contract.WidgetChoice
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.schedule.DefaultWidgetSchedule
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetClock
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetLock
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetMemories
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetPrefs
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.core.widget.testing.RecordingWidgetRefresher
import dev.pampa.mycloudgallery.feature.settings.widget.WidgetContent
import dev.pampa.mycloudgallery.feature.settings.widget.WidgetRefresh
import dev.pampa.mycloudgallery.feature.widget.host.WidgetPlaceNames
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fase 10 (P13, §1.4 "WidgetGraph", §2.1, D5, D42, E-W4): il grafo del widget in `:app` sopra porte finte. Il fornitore fallisce chiuso (Libreria non pronta, versione fermata,
 * dopo l'azzeramento); il giro dei dati personali dice al ridisegno i media **mostrati** che ora sono nascosti; l'azzeramento svuota **prima** e poi toglie il registro; i
 * contatori della Diagnostica contano solo numeri.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WidgetGraphTest {

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  private val library = SyntheticLibrary(seed = 3, count = 40)

  @After
  fun tearDown() = scope.cancel()

  private class Rig(val graph: WidgetGraph, val ledger: InMemoryWidgetLedger, val visibility: FakeVisibility, val refresher: RecordingWidgetRefresher, val stopped: MutableStateFlow<Boolean>)

  private fun rig(snapshotReady: Boolean = true): Rig {
    val ids = (0 until library.snapshot.size).map { library.snapshot.idAt(it) }.toSet()
    val visibility = FakeVisibility(snapshot = if (snapshotReady) library.snapshot else null, present = ids)
    val ledger = InMemoryWidgetLedger()
    val counters = WidgetCounters()
    val stopped = MutableStateFlow(false)
    val graph = WidgetGraph.create(
      context = app,
      feed = dev.pampa.mycloudgallery.core.library.testing.FakeWidgetFeed(visibility, visibility),
      visible = visibility,
      mask = visibility,
      memories = FakeWidgetMemories(),
      thumbs = LocalWidgetThumbs({ null }, NoThumbnailSource, counters),
      lock = FakeWidgetLock(),
      prefs = FakeWidgetPrefs(),
      places = WidgetPlaceNames { null },
      awaitShelf = {},
      stoppedByRelease = { stopped.value },
      counters = counters,
      clock = FakeWidgetClock(),
      ledger = ledger,
    )
    val refresher = RecordingWidgetRefresher()
    graph.refresher = refresher
    return Rig(graph, ledger, visibility, refresher, stopped)
  }

  private fun plan(slot: Int, epoch: Long, vararg media: Any) = WidgetPlan(
    WidgetSlot(slot), WidgetSize.WIDE, WidgetMode.Photos, media.mapIndexed { i, id -> WidgetCell(i, id as MediaId, MediaKind.PHOTO, 20250101) }, epoch,
  )

  @Test
  fun ilFornitoreFallisceChiuso() = runTest {
    val notReady = rig(snapshotReady = false)
    assertThat(notReady.graph.provider.await(0)).isNull()
    assertThat(notReady.graph.provider.await(50)).isNull()

    val ready = rig()
    assertThat(ready.graph.provider.await(0)).isSameInstanceAs(ready.graph.services)
    ready.stopped.value = true
    assertThat(ready.graph.provider.await(100)).isNull()
    ready.stopped.value = false
    assertThat(ready.graph.provider.await(0)).isNotNull()
    // L'orologio c'e' subito, anche senza porte (le ore di quiete del lavoro periodico).
    assertThat(notReady.graph.provider.clock).isNotNull()
  }

  @Test
  fun ilGiroDeiDatiPersonaliSvuotaSoloIMostratiOraNascosti() = runTest {
    val r = rig()
    val shown = (0 until 6).map { library.snapshot.idAt(it) }
    r.ledger.record(plan(1, 1, shown[0], shown[1], shown[2]))
    r.ledger.record(plan(2, 1, shown[3], shown[4]))
    assertThat(r.graph.watch()).isEmpty()
    assertThat(r.refresher.hidden).isEmpty()

    // Da un altro dispositivo: uno dei mostrati diventa nascosto, uno sparisce dal catalogo, uno non mostrato si nasconde (non conta).
    r.visibility.hidden += shown[1]
    r.visibility.present -= shown[4]
    r.visibility.hidden += shown[5]
    val gone = r.graph.watch()
    assertThat(gone).containsExactly(shown[1], shown[4])
    assertThat(r.refresher.hidden).containsExactly(setOf(shown[1], shown[4]))
  }

  @Test
  fun lAzzeramentoSvuotaPrimaEPoiTogliIlRegistro() = runTest {
    val r = rig()
    r.ledger.record(plan(1, 1, library.snapshot.idAt(0)))
    val order = mutableListOf<String>()
    r.graph.refresher = object : dev.pampa.mycloudgallery.core.widget.contract.WidgetRefresher by r.refresher {
      override suspend fun blankAll(reason: WidgetEmpty) {
        order += "svuota:${reason.code}"
        order += "registro:${r.ledger.slots().size}"
      }
    }
    r.graph.stop()
    order += "registro:${r.ledger.slots().size}"
    assertThat(order).containsExactly("svuota:${WidgetEmpty.NOT_CONFIGURED.code}", "registro:1", "registro:0").inOrder()
    assertThat(r.graph.isStopped).isTrue()
    assertThat(r.graph.provider.await(0)).isNull()
    assertThat(r.graph.watch()).isEmpty()
    // Una seconda volta (i soli dati dell'account dopo l'azzeramento completo) non svuota di nuovo.
    r.graph.stop()
    assertThat(order.count { it.startsWith("svuota") }).isEqualTo(1)
  }

  @Test
  fun iContatoriContanoPerCausaESoloNumeri() = runTest {
    val r = rig()
    val a = library.snapshot.idAt(0)
    val b = library.snapshot.idAt(1)
    r.ledger.record(plan(1, 1, a))
    r.ledger.record(plan(2, 1, a, b))
    val counting = CountingWidgetRefresher(r.refresher, r.ledger, r.graph.counters, { 3 }, scope)
    counting.hiddenChanged(setOf(a))
    counting.blankAll(WidgetEmpty.NOT_CONFIGURED)
    r.graph.counters.skippedNoThumb(4)
    r.graph.counters.skippedLocked(2)
    r.graph.counters.planned(WidgetPlan(WidgetSlot(1), WidgetSize.SMALL, WidgetMode.Message(WidgetEmpty.ERROR), emptyList(), 2))
    val diag = r.graph.counters.diag(mapOf(WidgetSize.WIDE to 2), minutesSinceUpdate = 5, period = WidgetPeriod.HOURS_6, lateRuns7d = 0)
    assertThat(diag.blanked[WidgetBlankCause.HIDDEN]).isEqualTo(2)
    assertThat(diag.blanked[WidgetBlankCause.RESET]).isEqualTo(3)
    assertThat(diag.skippedNoThumb).isEqualTo(4)
    assertThat(diag.skippedLocked).isEqualTo(2)
    assertThat(diag.lastError).isEqualTo(WidgetEmpty.ERROR)
    assertThat(diag.widgets).containsExactly(WidgetSize.SMALL, 0, WidgetSize.WIDE, 2, WidgetSize.LARGE, 0)
    // Il ridisegno vero riceve le stesse cause, in ordine.
    assertThat(r.refresher.calls).containsExactly("hiddenChanged", "blankAll").inOrder()
    r.graph.counters.reset()
    assertThat(r.graph.counters.diag(emptyMap(), null, WidgetPeriod.DEFAULT, 0).blanked.values.sum()).isEqualTo(0)
  }

  @Test
  fun iGiriInRitardoSiContanoFuoriDalleOreDiQuiete() {
    val schedule = DefaultWidgetSchedule()
    val last = LocalDateTime.of(2026, 10, 30, 9, 0)
    // Ogni 3 ore: 12, 15, 18, 21; la notte niente; poi 7 e 10 del giorno dopo. Alle 11:00 ne sono passati 6 (con i 30 minuti di flessibilita').
    assertThat(WidgetCounters.lateRuns(schedule, WidgetPeriod.HOURS_3, last, LocalDateTime.of(2026, 10, 31, 11, 0))).isEqualTo(6)
    assertThat(WidgetCounters.lateRuns(schedule, WidgetPeriod.HOURS_3, last, last.plusHours(3).plusMinutes(10))).isEqualTo(0)
    assertThat(WidgetCounters.lateRuns(schedule, WidgetPeriod.DAILY, null, last)).isEqualTo(0)
    assertThat(WidgetCounters.lateRuns(schedule, WidgetPeriod.DAILY, last, last.minusHours(1))).isEqualTo(0)
  }

  @Test
  fun leScelteEIlBloccoFallisconoChiusi() {
    val content = MutableStateFlow(WidgetContent.PHOTOS_ONLY)
    val period = MutableStateFlow(WidgetRefresh.DAILY)
    val settings = object : dev.pampa.mycloudgallery.feature.settings.widget.WidgetSettings {
      override val content = content
      override val period = period

      override suspend fun setContent(content: WidgetContent) {
        this.content.value = content
      }

      override suspend fun setPeriod(period: WidgetRefresh) {
        this.period.value = period
      }
    }
    val prefs = SettingsWidgetPrefs(settings)
    assertThat(prefs.choice.value).isEqualTo(WidgetChoice.PHOTOS_ONLY)
    assertThat(prefs.period.value).isEqualTo(WidgetPeriod.DAILY)
    content.value = WidgetContent.MEMORIES_ONLY
    assertThat(prefs.choice.value).isEqualTo(WidgetChoice.MEMORIES_ONLY)
    for (c in WidgetContent.entries) assertThat(SettingsWidgetPrefs.choiceOf(c).code).isEqualTo(c.code)
    for (p in WidgetRefresh.entries) assertThat(SettingsWidgetPrefs.periodOf(p).code).isEqualTo(p.code)

    // Il blocco: impostazioni non ancora lette = acceso (Q3 (c), fallire chiusi); spento solo se le impostazioni lo dicono.
    val lockSettings = MutableStateFlow<AppLockSettings?>(null)
    val lock = SnapshotWidgetLock(lockSettings, MutableStateFlow(library.snapshot))
    assertThat(lock.appLocked.value).isTrue()
    lockSettings.value = AppLockSettings(mode = AppLockMode.OFF)
    assertThat(lock.appLocked.value).isFalse()
    lockSettings.value = AppLockSettings(mode = AppLockMode.SYSTEM_CREDENTIAL)
    assertThat(lock.appLocked.value).isTrue()
  }

  @Test
  fun ilBloccoMostraSoloIMediaDelTelefono() = runTest {
    val counters = WidgetCounters()
    val lock = SnapshotWidgetLock(MutableStateFlow(null), MutableStateFlow(library.snapshot), counters)
    val all = (0 until library.snapshot.size).map { library.snapshot.idAt(it) }
    val onPhone = lock.onPhone(all)
    for (id in all) {
      val flags = library.snapshot.flagsAt(library.snapshot.indexOf(id))
      assertThat(id in onPhone).isEqualTo(dev.pampa.mycloudgallery.core.model.CellFlags.has(flags, dev.pampa.mycloudgallery.core.model.CellFlags.ON_PHONE))
    }
    assertThat(lock.onPhone(listOf(MediaId(UUID(9, 9).toString())))).isEmpty()
    assertThat(counters.diag(emptyMap(), null, WidgetPeriod.DEFAULT, 0).skippedLocked).isEqualTo(all.size - onPhone.size + 1)
    // Senza istantanea, nessuno.
    assertThat(SnapshotWidgetLock(MutableStateFlow(null), MutableStateFlow(null)).onPhone(all)).isEmpty()
  }
}
