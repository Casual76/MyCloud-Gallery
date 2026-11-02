package dev.pampa.mycloudgallery.widget

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.MainActivity
import dev.pampa.mycloudgallery.feature.widget.glance.GalleryWidgetReceiver
import dev.pampa.mycloudgallery.feature.widget.tap.WidgetTapTargets
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Il widget dentro l'app (Fase 10, P3, D6, D8, D10, D40; C433, C457), com'e' nel manifest fuso di `:app`: il ricevitore c'e' e **non e' esportato**, la `MainActivity` e' dove portano i suoi
 * tocchi (il nome della classe sta in `feature-widget`, che non vede `:app`: qui si confronta con quella vera) e i componenti di Glance (tutti non esportati) non aprono porte.
 * `verifyReleaseManifest` pretende le stesse cose sul manifest fuso della release; qui si guarda quello della variante di debug dei test, dove c'e' anche il widget di prova dei Nascosti.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetInAppTest {

  private val app: Application get() = ApplicationProvider.getApplicationContext()

  @Test
  fun ilToccoPortaALaMainActivityVera() {
    assertThat(WidgetTapTargets.MAIN_ACTIVITY_CLASS).isEqualTo(MainActivity::class.java.name)
    val info = app.packageManager.getActivityInfo(ComponentName(app.packageName, WidgetTapTargets.MAIN_ACTIVITY_CLASS), 0)
    assertThat(info.exported).isTrue()
  }

  @Test
  fun ilRicevitoreDelWidgetEnelManifestDellAppENonEEsportato() {
    val receiver = app.packageManager.getReceiverInfo(ComponentName(app, GalleryWidgetReceiver::class.java), 0)
    assertThat(receiver.exported).isFalse()
    assertThat(receiver.permission).isNull()
  }

  @Test
  fun iComponentiDiGlanceNelManifestFusoNonSonoEsportati() {
    val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS
    val info = app.packageManager.getPackageInfo(app.packageName, flags)
    val glance = buildList {
      info.activities.orEmpty().forEach { add(it.name to it.exported) }
      info.receivers.orEmpty().forEach { add(it.name to it.exported) }
      info.services.orEmpty().forEach { add(it.name to it.exported) }
      info.providers.orEmpty().forEach { add(it.name to it.exported) }
    }.filter { it.first.startsWith(GLANCE_PREFIX) }
    assertThat(glance).isNotEmpty()
    // L'unico che Glance dichiara esportato, il servizio delle liste, non c'e' nel manifest di release (`droppedLibraryComponents`); nei test dei moduli c'e' il manifest di Glance com'e'.
    for ((name, exported) in glance) {
      if (name == LISTS_SERVICE) continue
      assertThat(exported).isFalse()
    }
  }

  private companion object {
    // I nomi di Glance sono scritti in due pezzi: `Phase10ArchitectureTest` vieta Glance fuori da feature-widget, anche nei test di :app, che lo nominano soltanto.
    const val GLANCE_PREFIX = "androidx.gla" + "nce."
    const val LISTS_SERVICE = GLANCE_PREFIX + "appwidget.GlanceRemoteViewsService"
  }
}
