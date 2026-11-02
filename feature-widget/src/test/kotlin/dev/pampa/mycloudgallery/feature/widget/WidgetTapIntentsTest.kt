package dev.pampa.mycloudgallery.feature.widget

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.view.View
import android.widget.TextView
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapRequest
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapUri
import dev.pampa.mycloudgallery.core.widget.tap.WidgetTap
import dev.pampa.mycloudgallery.feature.widget.WidgetTestKit.clickables
import dev.pampa.mycloudgallery.feature.widget.WidgetTestKit.descendants
import dev.pampa.mycloudgallery.feature.widget.glance.GlanceWidgetComposer
import dev.pampa.mycloudgallery.feature.widget.tap.WidgetTapIntents
import dev.pampa.mycloudgallery.feature.widget.tap.WidgetTapTargets
import java.lang.reflect.Modifier
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Il tocco sul widget, dalla parte del widget (Fase 10, P3, D9, C17, C433): **`PendingIntent` immutabile ed esplicito, e solo i due numeri**. Si prova su due livelli: l'intent che
 * `tap/` costruisce (esplicito, azione fissa, URI `mcg-widget:<id>/<cella>`, niente extra, categorie o flag) e i `PendingIntent` che Glance mette nei `RemoteViews` composti davvero
 * (immutabili, uno diverso per cella, che al tocco avviano quell'intent e nessun altro). La parte della `MainActivity` e' in `EntryHardeningTest` di `:app`.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetTapIntentsTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val slot = WidgetSlot(7)

  @Test
  fun lIntentDiUnaCellaEEsplicitoConAzioneFissaEDueNumeri() {
    for (cell in 0 until WidgetTapUri.CELLS) {
      val intent = WidgetTapIntents.cellIntent(context, slot, cell)
      assertThat(intent.action).isEqualTo("dev.pampa.mycloudgallery.action.OPEN_WIDGET")
      // Esplicito: il componente e' la MainActivity del nostro pacchetto (nessun'altra app lo risolve).
      assertThat(intent.component?.packageName).isEqualTo(context.packageName)
      assertThat(intent.component?.className).isEqualTo(WidgetTapTargets.MAIN_ACTIVITY_CLASS)
      assertThat(intent.`package`).isNull()
      // Solo i due numeri, in un URI opaco: nessun extra, nessuna categoria, nessun flag, nessun tipo.
      assertThat(intent.dataString).isEqualTo("mcg-widget:7/$cell")
      assertThat(intent.data?.isOpaque).isTrue()
      assertThat(intent.extras).isNull()
      assertThat(intent.categories).isNull()
      assertThat(intent.flags).isEqualTo(0)
      assertThat(intent.type).isNull()
      // E `MainActivity` lo rilegge tale e quale.
      assertThat(WidgetTap.parse(intent.dataString)).isEqualTo(WidgetTapRequest(7, cell))
    }
  }

  @Test
  fun leCelleHannoRichiesteDistinte() {
    val intents = (0 until WidgetTapUri.CELLS).map { WidgetTapIntents.cellIntent(context, slot, it) } + WidgetTapIntents.cellIntent(context, WidgetSlot(8), 0)
    // Android confronta i `PendingIntent` dall'URI (`Intent.filterEquals`): due celle, due widget = due richieste, nessuna ne sovrascrive un'altra.
    for (i in intents.indices) for (j in intents.indices) if (i != j) assertThat(intents[i].filterEquals(intents[j])).isFalse()
  }

  @Test
  fun apriMyCloudGalleryEIlLauncherEsplicito() {
    val intent = WidgetTapIntents.openAppIntent(context)
    assertThat(intent.action).isEqualTo(Intent.ACTION_MAIN)
    assertThat(intent.categories).containsExactly(Intent.CATEGORY_LAUNCHER)
    assertThat(intent.component?.className).isEqualTo(WidgetTapTargets.MAIN_ACTIVITY_CLASS)
    assertThat(intent.data).isNull()
    assertThat(intent.extras).isNull()
    assertThat(intent.flags).isEqualTo(0)
  }

  /** I `PendingIntent` dentro i `RemoteViews` (per riflessione: i campi privati di `RemoteViews` dell'SDK 35 dei test). */
  private fun pendingIntentsOf(views: RemoteViews): List<PendingIntent> {
    val found = ArrayList<PendingIntent>()
    fun visit(value: Any?, depth: Int) {
      if (value == null || depth > 4) return
      if (value is PendingIntent) {
        found += value
        return
      }
      if (value is Collection<*>) {
        value.forEach { visit(it, depth + 1) }
        return
      }
      val type = value.javaClass
      if (!type.name.startsWith("android.widget.RemoteViews")) return
      var c: Class<*>? = type
      while (c != null && c != Any::class.java) {
        for (field in c.declaredFields) {
          if (Modifier.isStatic(field.modifiers)) continue
          val target = field.type
          if (target.isPrimitive || target == String::class.java) continue
          field.isAccessible = true
          visit(field.get(value), depth + 1)
        }
        c = c.superclass
      }
    }
    visit(views, 0)
    return found
  }

  @Test
  fun iPendingIntentDeiRemoteViewsSonoImmutabili() = runBlocking {
    for (size in WidgetSize.entries) {
      val frame = WidgetTestKit.memory(size)
      WidgetTestKit.registerWidget(context, frame.slot)
      val (w, h) = WidgetTestKit.dp(size)
      val views = GlanceWidgetComposer(context).compose(frame, DpSize(w.dp, h.dp), EngineSettings())
      val pending = pendingIntentsOf(views)
      // Una per cella, piu' il titolo (la copertina), piu' la superficie intera: mai meno delle celle.
      assertWithMessage("$size: PendingIntent trovati").that(pending.size).isAtLeast(size.cells)
      for (p in pending) {
        val flags = shadowOf(p).flags
        assertThat(flags and PendingIntent.FLAG_IMMUTABLE).isEqualTo(PendingIntent.FLAG_IMMUTABLE)
        assertThat(flags and PendingIntent.FLAG_MUTABLE).isEqualTo(0)
        assertThat(shadowOf(p).isActivityIntent).isTrue()
        // Esplicito, e solo cio' che `tap/` ha costruito.
        val intent = shadowOf(p).savedIntent
        assertThat(intent.component?.className).isEqualTo(WidgetTapTargets.MAIN_ACTIVITY_CLASS)
        assertThat(intent.component?.packageName).isEqualTo(context.packageName)
      }
    }
  }

  @Test
  fun alToccoSiApreLaMainActivityConILSoloNumeroDellaCella() = runBlocking {
    val frame = WidgetTestKit.memory(WidgetSize.WIDE)
    val view = WidgetTestKit.render(context, frame, WidgetSize.WIDE)
    val started = ArrayList<Intent>()
    for (clickable in view.clickables()) {
      clickable.performClick()
      shadowOf(context).nextStartedActivity?.let { started += it }
    }
    // Ogni tocco avvia un'activity e **nessun'altra cosa**: o la cella `mcg-widget:7/<n>` o "Apri MyCloud Gallery".
    assertThat(started).isNotEmpty()
    val cellTaps = started.filter { it.action == WidgetTapUri.ACTION }
    assertThat(cellTaps.mapNotNull { WidgetTap.parse(it.dataString)?.cell }.toSet()).containsExactly(0, 1, 2, 3)
    for (intent in started) {
      assertThat(intent.component?.className).isEqualTo(WidgetTapTargets.MAIN_ACTIVITY_CLASS)
      assertThat(intent.component?.packageName).isEqualTo(context.packageName)
      // Glance non aggiunge dati al nostro intent: nessun id di media, nessun titolo.
      val extras = intent.extras
      assertThat(extras == null || extras.keySet().isEmpty()).isTrue()
    }
    assertThat(started.filter { it.action == WidgetTapUri.ACTION }.all { it.dataString?.startsWith("mcg-widget:7/") == true }).isTrue()
    assertThat(started.any { it.action == Intent.ACTION_MAIN && it.hasCategory(Intent.CATEGORY_LAUNCHER) }).isTrue()
  }

  @Test
  fun ilTitoloPortaAllaCopertina() = runBlocking<Unit> {
    val frame = WidgetTestKit.memory(WidgetSize.LARGE)
    val view = WidgetTestKit.render(context, frame, WidgetSize.LARGE)
    // Il testo del titolo sta dentro una vista che si tocca (quella del titolo), e porta alla copertina (cella 0): la pagina del ricordo.
    var node: View? = view.descendants().filterIsInstance<TextView>().first { it.text.toString() == "Un anno fa · Oslo" }
    while (node != null && !node.hasOnClickListeners()) node = node.parent as? View
    assertThat(node).isNotNull()
    node!!.performClick()
    val intent = shadowOf(context).nextStartedActivity
    assertThat(WidgetTap.parse(intent.dataString)).isEqualTo(WidgetTapRequest(7, 0))
  }
}
