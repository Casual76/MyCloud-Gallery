package dev.pampa.mycloudgallery.feature.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.widget.contract.WidgetClock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetScheduleRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetClock
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.feature.widget.glance.GalleryWidgetReceiver
import dev.pampa.mycloudgallery.feature.widget.host.WidgetHost
import dev.pampa.mycloudgallery.feature.widget.host.WidgetServices
import dev.pampa.mycloudgallery.feature.widget.host.WidgetServicesProvider
import dev.pampa.mycloudgallery.feature.widget.host.WidgetThumbnails
import dev.pampa.mycloudgallery.feature.widget.refresh.WidgetEngine
import dev.pampa.mycloudgallery.feature.widget.refresh.WidgetEngines
import dev.pampa.mycloudgallery.feature.widget.refresh.WidgetTargets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Il ricevitore del widget (Fase 10, P3, D6, D8, D40, E-W4, E-P13). Il "Fatto quando": **un processo freddo non mostra mai un contenuto non verificato**, provato con **il grafo
 * non montato** (la lezione di NT-1 della Fase 9: il lavoro non si fida di un grafo montato da altri). Un widget che nasce, un aggiornamento chiesto dal sistema o un
 * ridimensionamento in un processo senza fornitore delle porte **non consegnano niente** al launcher; con le porte pronte disegnano.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GalleryWidgetReceiverTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val manager: AppWidgetManager get() = AppWidgetManager.getInstance(context)
  private lateinit var previousScope: CoroutineScope

  @Before
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
    previousScope = GalleryWidgetReceiver.scope
    // Il lavoro del ricevitore gira subito, nello stesso thread: il test non aspetta niente.
    GalleryWidgetReceiver.scope = CoroutineScope(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    GalleryWidgetReceiver.scope = previousScope
    WidgetHost.uninstall()
    WidgetEngines.override(null)
  }

  /** Un widget vero sulla Home di prova, con il suo layout iniziale: [shadowOf]`(manager).getViewFor` dice cosa mostra. */
  private fun addWidget(): Int = shadowOf(manager).createWidget(GalleryWidgetReceiver::class.java, R.layout.widget_initial)

  private fun shownText(id: Int): String = (shadowOf(manager).getViewFor(id) as android.view.ViewGroup).let { group ->
    (0 until group.childCount).map { group.getChildAt(it) }.filterIsInstance<TextView>().joinToString { it.text.toString() }
  }

  private fun works() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WidgetScheduleRules.WORK_NAME).get()

  @Test
  fun unProcessoFreddoSenzaIlGrafoNonConsegnaMaiNiente() {
    val id = addWidget()
    val before = shadowOf(manager).getViewFor(id)
    assertThat(shownText(id)).isEqualTo("Apri MyCloud Gallery")
    val receiver = GalleryWidgetReceiver()
    // Nessun fornitore installato: P13 non c'e', o il grafo non e' montato.
    receiver.onUpdate(context, manager, intArrayOf(id))
    receiver.onAppWidgetOptionsChanged(context, manager, id, Bundle())
    assertThat(WidgetHost.isInstalled).isFalse()
    // Il launcher ha ancora quello che aveva: la stessa vista, il suo testo "Apri MyCloud Gallery" (nessuna foto, nessun titolo).
    assertThat(shadowOf(manager).getViewFor(id)).isSameInstanceAs(before)
    assertThat(shownText(id)).isEqualTo("Apri MyCloud Gallery")
  }

  @Test
  fun unFornitoreCheNonRiescePiuAMontareLePorteNonConsegnaMaiNiente() {
    val id = addWidget()
    val before = shadowOf(manager).getViewFor(id)
    // Il fornitore c'e' ma il grafo non si monta nel tempo (E-W4: la Libreria non e' pronta in 1,5 s).
    WidgetHost.install(object : WidgetServicesProvider {
      override val clock: WidgetClock = FakeWidgetClock()

      override suspend fun await(budgetMs: Long): WidgetServices? = null
    })
    GalleryWidgetReceiver().onUpdate(context, manager, intArrayOf(id))
    assertThat(shadowOf(manager).getViewFor(id)).isSameInstanceAs(before)
  }

  @Test
  fun conLePorteProntePerIlWidgetDisegna() {
    val id = addWidget()
    val before = shadowOf(manager).getViewFor(id)
    val events = mutableListOf<String>()
    val targets = RecordingTargets(mutableMapOf(WidgetSlot(id) to (160 to 160)), events)
    val services = FakeServices(ScriptedPlanner(events) { s, size, _ -> photoPlan(s, size, 1) }, InMemoryWidgetLedger(), WidgetThumbnails { _, _ -> thumb() })
    WidgetEngines.override(WidgetEngine({ services }, targets, RecordingComposer(context, events), { context.resources }))
    GalleryWidgetReceiver().onUpdate(context, manager, intArrayOf(id))
    assertThat(events.filter { it.startsWith("push:") }).containsExactly("push:$id")
    assertThat(shadowOf(manager).getViewFor(id)).isSameInstanceAs(before)
  }

  @Test
  fun unRidimensionamentoRipianifica() {
    val id = addWidget()
    val events = mutableListOf<String>()
    val targets = RecordingTargets(mutableMapOf(WidgetSlot(id) to (320 to 160)), events)
    val services = FakeServices(ScriptedPlanner(events) { s, size, _ -> photoPlan(s, size, size.cells) }, InMemoryWidgetLedger(), WidgetThumbnails { _, _ -> thumb() })
    val composer = RecordingComposer(context, events)
    WidgetEngines.override(WidgetEngine({ services }, targets, composer, { context.resources }))
    val receiver = GalleryWidgetReceiver()
    receiver.onAppWidgetOptionsChanged(context, manager, id, Bundle())
    assertThat(composer.frames.single().cells).hasSize(4)
    // Un id che non e' di un widget (zero, negativo) non fa niente.
    receiver.onAppWidgetOptionsChanged(context, manager, 0, Bundle())
    receiver.onUpdate(context, manager, intArrayOf(0, -3))
    assertThat(composer.frames).hasSize(1)
  }

  @Test
  fun toltoDallaHomeIlRegistroLoDimentica() {
    val id = addWidget()
    val ledger = InMemoryWidgetLedger()
    val events = mutableListOf<String>()
    val targets = RecordingTargets(mutableMapOf(WidgetSlot(id) to (160 to 160)), events)
    val services = FakeServices(ScriptedPlanner(events) { s, size, _ -> photoPlan(s, size, 1) }, ledger, WidgetThumbnails { _, _ -> thumb() })
    val engine = WidgetEngine({ services }, targets, RecordingComposer(context, events), { context.resources })
    WidgetEngines.override(engine)
    GalleryWidgetReceiver().onUpdate(context, manager, intArrayOf(id))
    kotlinx.coroutines.runBlocking {
      assertThat(ledger.slots()).containsExactly(WidgetSlot(id))
      GalleryWidgetReceiver().onDeleted(context, intArrayOf(id))
      assertThat(ledger.slots()).isEmpty()
    }
  }

  @Test
  fun ilPrimoWidgetProgrammaIlLavoroEl_UltimoLoToglie() {
    val receiver = GalleryWidgetReceiver()
    receiver.onEnabled(context)
    assertThat(works().single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    receiver.onDisabled(context)
    assertThat(works().all { it.state == WorkInfo.State.CANCELLED }).isTrue()
  }

  @Test
  fun ilRicevitoreNonEEsportato() {
    val info = context.packageManager.getReceiverInfo(android.content.ComponentName(context, GalleryWidgetReceiver::class.java), 0)
    assertThat(info.exported).isFalse()
    assertThat(info.permission).isNull()
  }
}
