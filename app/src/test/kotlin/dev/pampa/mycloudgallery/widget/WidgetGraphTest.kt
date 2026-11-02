package dev.pampa.mycloudgallery.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
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
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRefresher
import dev.pampa.mycloudgallery.core.widget.contract.WidgetScheduleRules
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
import dev.pampa.mycloudgallery.feature.widget.glance.GalleryWidgetReceiver
import dev.pampa.mycloudgallery.feature.widget.host.WidgetHost
import dev.pampa.mycloudgallery.feature.widget.host.WidgetPlaceNames
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
  fun tearDown() {
    WidgetHost.uninstall()
    scope.cancel()
  }

  private class Rig(val graph: WidgetGraph, val ledger: InMemoryWidgetLedger, val visibility: FakeVisibility, val refresher: RecordingWidgetRefresher, val stopped: MutableStateFlow<Boolean>)

  private fun rig(snapshotReady: Boolean = true, prefs: FakeWidgetPrefs = FakeWidgetPrefs()): Rig {
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
      prefs = prefs,
      places = WidgetPlaceNames { null },
      awaitShelf = {},
      stoppedByRelease = { stopped.value },
      counters = counters,
      clock = FakeWidgetClock(),
      ledger = ledger,
      releaseStopped = stopped,
    )
    val refresher = RecordingWidgetRefresher()
    graph.refresher = refresher
    return Rig(graph, ledger, visibility, refresher, stopped)
  }

  /** Un grafo con un registro dato (per un registro che lancia): il grafo e il suo ridisegno finto. */
  private fun rigWith(ledger: WidgetLedger): Pair<WidgetGraph, RecordingWidgetRefresher> {
    val ids = (0 until library.snapshot.size).map { library.snapshot.idAt(it) }.toSet()
    val visibility = FakeVisibility(snapshot = library.snapshot, present = ids)
    val counters = WidgetCounters()
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
      stoppedByRelease = { false },
      counters = counters,
      clock = FakeWidgetClock(),
      ledger = ledger,
    )
    val refresher = RecordingWidgetRefresher()
    graph.refresher = refresher
    return graph to refresher
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

  // --- Correzioni della Fase 10, pacchetto B ----------------------------------------------------------------------------------------------

  /** I widget veri sulla Home di prova (Robolectric): `WidgetGraph.slots()` li legge da `AppWidgetManager`. */
  private fun addWidgets(count: Int): List<Int> =
    List(count) { shadowOf(AppWidgetManager.getInstance(app)).createWidget(GalleryWidgetReceiver::class.java, dev.pampa.mycloudgallery.feature.widget.R.layout.widget_initial) }

  private fun works() = WorkManager.getInstance(app).getWorkInfosForUniqueWork(WidgetScheduleRules.WORK_NAME).get()

  /** R5: il widget si dice fermo **prima** di svuotare: mentre `blankAll` lavora il fornitore risponde gia' null, quindi un giro gia' partito non rimette foto. */
  @Test
  fun lAzzeramentoSiDiceFermoPrimaDiSvuotare() = runTest {
    val r = rig()
    var providerDuringBlank: Any? = "non chiamato"
    var stoppedDuringBlank = false
    r.graph.refresher = object : WidgetRefresher by r.refresher {
      override suspend fun blankAll(reason: WidgetEmpty) {
        stoppedDuringBlank = r.graph.isStopped
        providerDuringBlank = r.graph.provider.await(0)
      }
    }
    assertThat(r.graph.provider.await(0)).isNotNull()
    r.graph.stop()
    assertThat(stoppedDuringBlank).isTrue()
    assertThat(providerDuringBlank).isNull()
  }

  /** WG-1: dopo l'azzeramento il lavoro periodico non c'e' piu'; alla nuova configurazione (nello stesso processo) `resume` e `publishAtStart` lo rimettono, alla frequenza scelta. */
  @Test
  fun dopoLAzzeramentoLaNuovaConfigurazioneRimetteIlLavoro() = runTest {
    WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
    val r = rig(prefs = FakeWidgetPrefs(period = WidgetPeriod.DAILY))
    // Come nel processo vero: il fornitore del grafo e' gia' in `WidgetHost` quando un widget nasce (il ricevitore programma il lavoro con la frequenza scelta, WG-2).
    WidgetHost.install(r.graph.provider)
    addWidgets(2)
    WidgetWiring.publishAtStart(r.graph)
    assertThat(works().single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    assertThat(works().single().periodicityInfo?.repeatIntervalMillis).isEqualTo(TimeUnit.HOURS.toMillis(24))

    r.graph.stop()
    assertThat(works().all { it.state == WorkInfo.State.CANCELLED }).isTrue()
    // Con il widget fermo (un azzeramento partito dopo l'avvio) l'avvio tardivo non rimette il lavoro sotto i dati che si cancellano.
    r.refresher.calls.clear()
    WidgetWiring.publishAtStart(r.graph)
    assertThat(works().all { it.state == WorkInfo.State.CANCELLED }).isTrue()
    assertThat(r.refresher.calls).isEmpty()

    // La galleria riparte con una configurazione nuova: il fornitore torna a rispondere, il lavoro riparte e un giro ridisegna i widget.
    r.graph.resume()
    assertThat(r.graph.isStopped).isFalse()
    assertThat(r.graph.provider.await(0)).isNotNull()
    WidgetWiring.publishAtStart(r.graph)
    assertThat(works().single { it.state == WorkInfo.State.ENQUEUED }.periodicityInfo?.repeatIntervalMillis).isEqualTo(TimeUnit.HOURS.toMillis(24))
    assertThat(r.refresher.calls).containsExactly("shelfChanged")
    // E si puo' azzerare di nuovo.
    r.graph.stop()
    assertThat(r.graph.isStopped).isTrue()
  }

  /** WG-1: senza widget sulla Home il lavoro non rinasce (D6: nessun widget, nessun lavoro). */
  @Test
  fun senzaWidgetLAvvioNonProgrammaIlLavoro() = runTest {
    WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
    val r = rig()
    WidgetWiring.publishAtStart(r.graph)
    assertThat(works()).isEmpty()
    assertThat(r.refresher.calls).isEmpty()
  }

  /** WG-2: il fornitore dice la frequenza scelta senza montare le porte (anche con la Libreria non pronta). */
  @Test
  fun ilFornitoreDiceLaFrequenzaSceltaSenzaMontareLePorte() = runTest {
    val prefs = FakeWidgetPrefs(period = WidgetPeriod.HOURS_6)
    val r = rig(snapshotReady = false, prefs = prefs)
    assertThat(r.graph.provider.period).isEqualTo(WidgetPeriod.HOURS_6)
    prefs.period.value = WidgetPeriod.DAILY
    assertThat(r.graph.provider.period).isEqualTo(WidgetPeriod.DAILY)
  }

  /** R4: un widget sulla Home che il registro non conosce (registro perso, riparte vuoto) si svuota e si ridisegna anche per un nascosto arrivato da un altro dispositivo. */
  @Test
  fun conIlRegistroPersoIlGiroDeiDatiPersonaliSvuotaTuttiIWidget() = runTest {
    addWidgets(2)
    val r = rig()
    // Il registro e' vuoto (file rovinato): `slots()` dice nessuno, ma due widget sono sulla Home e mostrano le foto di prima.
    assertThat(r.ledger.slots()).isEmpty()
    assertThat(r.graph.watch()).isEmpty()
    assertThat(r.refresher.calls).containsExactly("blankAll", "shelfChanged").inOrder()
    assertThat(r.refresher.blanked).containsExactly(WidgetEmpty.NO_MEDIA)
  }

  /** R4: con il registro che conosce tutti i widget il giro non svuota niente di piu' (nessuno svuotamento a ogni istantanea). */
  @Test
  fun conIlRegistroCheConosceTuttiNonSvuotaNiente() = runTest {
    val ids = addWidgets(2)
    val r = rig()
    ids.forEachIndexed { i, id -> r.ledger.record(plan(id, 1, library.snapshot.idAt(i))) }
    assertThat(r.graph.watch()).isEmpty()
    assertThat(r.refresher.calls).isEmpty()
    // E con un nascosto fra i mostrati, solo quello (come prima).
    r.visibility.hidden += library.snapshot.idAt(1)
    assertThat(r.graph.watch()).containsExactly(library.snapshot.idAt(1))
    assertThat(r.refresher.calls).containsExactly("hiddenChanged")
  }

  /** R4: un registro che non si legge per niente vale come "non so": tutti i widget. Con la versione fermata il giro non fa niente. */
  @Test
  fun unRegistroCheLanciaSvuotaTuttiEUnaVersioneFermataNonFaNiente() = runTest {
    addWidgets(1)
    val r = rig()
    val broken = object : WidgetLedger by r.ledger {
      override suspend fun slots(): Set<WidgetSlot> = throw java.io.IOException("registro illeggibile")
    }
    val (graph, refresher) = rigWith(broken)
    assertThat(graph.watch()).isEmpty()
    assertThat(refresher.calls).containsExactly("blankAll", "shelfChanged").inOrder()
    r.stopped.value = true
    assertThat(r.graph.watch()).isEmpty()
    assertThat(r.refresher.calls).isEmpty()
  }

  /** R1 (widget): con la versione fermata dal manifest i widget si svuotano ("Apri MyCloud Gallery") e il registro e' vuoto; allo stato normale un giro li ridisegna. */
  @Test
  fun conLaVersioneFermataIWidgetSiSvuotanoETornanoNormaliAlloStatoNormale() = runTest {
    val r = rig()
    r.ledger.record(plan(1, 1, library.snapshot.idAt(0)))
    WidgetWiring.followRelease(r.graph, scope)
    // Il valore di partenza "non fermata" non fa niente.
    assertThat(r.refresher.calls).isEmpty()
    r.stopped.value = true
    assertThat(r.refresher.calls).containsExactly("blankAll")
    assertThat(r.refresher.blanked).containsExactly(WidgetEmpty.NO_MEDIA)
    assertThat(r.ledger.slots()).isEmpty()
    assertThat(r.graph.provider.await(0)).isNull()
    // Stato normale (o sola lettura): il giro intero rimette le foto.
    r.stopped.value = false
    assertThat(r.refresher.calls).containsExactly("blankAll", "shelfChanged").inOrder()
    assertThat(r.graph.provider.await(0)).isNotNull()
  }

  /** R1: un azzeramento in corso ha la precedenza (niente giro "torna normale" su dati che spariscono). */
  @Test
  fun dopoLAzzeramentoIlManifestNonRimetteIWidget() = runTest {
    val r = rig()
    r.graph.stop()
    r.refresher.calls.clear()
    r.graph.releaseChanged(true)
    r.graph.releaseChanged(false)
    assertThat(r.refresher.calls).isEmpty()
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
