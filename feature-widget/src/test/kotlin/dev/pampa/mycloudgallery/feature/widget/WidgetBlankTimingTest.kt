package dev.pampa.mycloudgallery.feature.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.feature.widget.WidgetTestKit.images
import dev.pampa.mycloudgallery.feature.widget.WidgetTestKit.texts
import dev.pampa.mycloudgallery.feature.widget.glance.GalleryWidgetReceiver
import dev.pampa.mycloudgallery.feature.widget.glance.GlanceWidgetComposer
import dev.pampa.mycloudgallery.feature.widget.host.WidgetThumbnails
import dev.pampa.mycloudgallery.feature.widget.refresh.AppWidgetTargets
import dev.pampa.mycloudgallery.feature.widget.refresh.WidgetEngine
import dev.pampa.mycloudgallery.feature.widget.refresh.WidgetTargets
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * **Lo svuotamento in meno di 100 ms dopo `hiddenChanged`** (Fase 10, P3, D5, "Fatto quando"), con tutto il codice vero: il motore, la composizione di Glance con la palette del kit e
 * i widget di `AppWidgetManager` (quelli di Robolectric). Si misura dal momento in cui il widget **sa** del nascosto (`hiddenChanged`) al momento in cui il disegno **senza immagini**
 * e' consegnato al launcher (`updateAppWidget`), dopo un giro di riscaldamento (la JVM che ha gia' caricato Glance, come un processo vivo). Sono i millisecondi di una JVM da PC dentro
 * Robolectric: sul telefono la prova e' F161.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetBlankTimingTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val manager: AppWidgetManager get() = AppWidgetManager.getInstance(context)

  /** I widget veri, con in piu' l'ora della consegna di ogni disegno. */
  private class TimedTargets(private val inner: WidgetTargets) : WidgetTargets by inner {
    val pushes = mutableListOf<Pair<WidgetSlot, Long>>()
    val views = mutableListOf<RemoteViews>()

    override fun push(slot: WidgetSlot, views: RemoteViews) {
      pushes += slot to System.nanoTime()
      this.views += views
      inner.push(slot, views)
    }
  }

  @Before
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
  }

  private class Rig(val engine: WidgetEngine, val targets: TimedTargets, val slots: List<WidgetSlot>)

  /** Tre widget veri (Robolectric), il motore con Glance vero e le porte finte; ogni widget gia' disegnato una volta (come un processo che ha gia' lavorato). */
  private suspend fun rig(): Rig {
    val ids = List(3) { shadowOf(manager).createWidget(GalleryWidgetReceiver::class.java, R.layout.widget_initial) }
    val slots = ids.map { WidgetSlot(it) }
    // Il widget deve esistere anche per Glance (`getGlanceIdBy` vuole le informazioni del provider).
    slots.forEach { WidgetTestKit.registerWidget(context, it) }
    val targets = TimedTargets(AppWidgetTargets(context))
    val events = mutableListOf<String>()
    val planner = ScriptedPlanner(events) { slot, size, call -> photoPlan(slot, size, size.cells, epoch = call.toLong()) }
    val services = FakeServices(planner, InMemoryWidgetLedger(), WidgetThumbnails { _, _ -> WidgetTestKit.bitmap(1, 96, 96) })
    val engine = WidgetEngine({ services }, targets, GlanceWidgetComposer(context), { context.resources }, prewarmBlanks = true)
    // Il riscaldamento: il giro intero (piano, decodifica, composizione con foto, consegna e svuotamento in anticipo), due volte, per far caricare Glance e Compose.
    repeat(2) { for (slot in slots) engine.refresh(slot) }
    return Rig(engine, targets, slots)
  }

  /** I millisecondi dalla chiamata a `hiddenChanged` alla consegna dell'ultimo dei tre svuotamenti, con le prove che sono davvero svuotamenti. */
  private suspend fun blankTime(r: Rig, cold: Boolean = false): Long {
    for (slot in r.slots) r.engine.refresh(slot)
    if (cold) r.engine.clearBlankCache()
    r.targets.pushes.clear()
    r.targets.views.clear()
    val start = System.nanoTime()
    r.engine.hiddenChanged(setOf(mediaId(0)))
    // I primi tre disegni dopo la chiamata sono gli svuotamenti, uno per widget, prima di ogni piano.
    val blanks = r.targets.pushes.take(r.slots.size)
    assertThat(blanks.map { it.first }).containsExactlyElementsIn(r.slots)
    // I tre disegni dello svuotamento non hanno **nessuna immagine** e dicono "Apri MyCloud Gallery".
    for (views in r.targets.views.take(r.slots.size)) {
      val parent = FrameLayout(context)
      val view = views.apply(context, parent) as ViewGroup
      assertThat(view.images().none { it.drawable is BitmapDrawable }).isTrue()
      assertThat(view.texts()).containsExactly("Apri MyCloud Gallery")
    }
    return (blanks.last().second - start) / 1_000_000
  }

  /** Il caso normale: il processo ha gia' disegnato i widget, quindi lo svuotamento di ognuno e' **gia' composto** e si consegna soltanto. */
  @Test
  fun loSvuotamentoArrivaEntroCentoMillisecondi() = runBlocking<Unit> {
    val r = rig()
    val times = List(RUNS) { blankTime(r) }
    println("svuotamento di 3 widget gia' composti (millisecondi, JVM del PC dentro Robolectric): $times")
    val sorted = times.sorted()
    // Tutti e tre i widget svuotati in meno di 100 ms (mediana: ogni singolo widget ne usa un terzo), e mai oltre mezzo secondo (un caso isolato per un GC).
    assertThat(sorted[sorted.size / 2]).isLessThan(WIDGET_BLANK_MS)
    assertThat(sorted.last()).isLessThan(500)
  }

  /** Il caso lento, detto: un processo che non ha ancora disegnato niente deve **comporre** lo svuotamento (qui senza la memoria dei disegni gia' composti). Non e' un tetto: e' il numero. */
  @Test
  fun senzaIlDisegnoGiaComposto_IlNumeroDelCasoLento() = runBlocking<Unit> {
    val r = rig()
    val times = List(3) { blankTime(r, cold = true) }
    println("svuotamento di 3 widget da comporre (millisecondi, JVM del PC dentro Robolectric): $times")
    // Il tetto qui e' solo "non si pianta": la prova sul telefono (F161) chiede 1 s.
    assertThat(times.max()).isLessThan(10_000)
  }

  private companion object {
    const val RUNS = 7
    const val WIDGET_BLANK_MS = 100L
  }
}
