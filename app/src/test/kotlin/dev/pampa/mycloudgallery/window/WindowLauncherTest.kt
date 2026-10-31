package dev.pampa.mycloudgallery.window

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.MainActivity
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryWindow
import dev.pampa.mycloudgallery.core.designsystem.adaptive.PointerKind
import dev.pampa.mycloudgallery.core.model.AlbumId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/**
 * "Apri in una nuova finestra" (Fase 10, P4, D16, E-T6): l'intent della finestra nuova e' pulito (nessun dato), le finestre sono al piu' due, la terza porta davanti
 * quella che c'e' con un messaggio, sotto Android 12 una finestra sola. La nascita vera (`singleInstancePerTask`) la prova la corsa sull'emulatore.
 */
@RunWith(RobolectricTestRunner::class)
class WindowLauncherTest {

  private val app: Application get() = ApplicationProvider.getApplicationContext()
  private val registry = WindowRegistry()
  private val starts = WindowStarts()

  private fun activity(): Activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

  /** Una finestra "viva" dell'app, e un'altra: la richiedente e quella che c'e'. */
  private fun twoWindows(requester: Int): Int {
    val other = requester + 1_000
    registry.admit(requester, 2)
    registry.admit(other, 2)
    return other
  }

  // --- l'intent ----------------------------------------------------------------------------------------------------------

  @Test
  fun lIntentDellaFinestraNuovaEQuelloPulitoDelLauncherPiuITreFlag() {
    val intent = WindowLauncher.newWindowIntent(app)

    assertThat(intent.flags).isEqualTo(
      Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT,
    )
    assertThat(intent.action).isEqualTo(Intent.ACTION_MAIN)
    assertThat(intent.categories).containsExactly(Intent.CATEGORY_LAUNCHER)
    assertThat(intent.component?.className).isEqualTo(MainActivity::class.java.name)
    // Mai dati nell'intent: ne' extra, ne' un URI, ne' un tipo (la pagina da aprire sta in WindowStarts).
    assertThat(intent.extras).isNull()
    assertThat(intent.data).isNull()
    assertThat(intent.type).isNull()
    // L'intent del launcher resta com'era: i tre flag non sono suoi.
    assertThat(MainActivity.launcherIntent(app).flags).isEqualTo(0)
  }

  @Test
  fun lAzioneDellaFinestraNuovaNonEUnAzioneNota_nonApreIRicordi() {
    // La finestra nuova nasce con l'azione MAIN, che `entryOf` non riconosce come una richiesta: nessun evento interno parte dall'intent della finestra.
    val entry = MainActivity.entryOf(app, WindowLauncher.newWindowIntent(app))
    assertThat(entry.opensMemories).isFalse()
    assertThat(entry.needsPrimaryWindow).isFalse()
  }

  // --- aprire -------------------------------------------------------------------------------------------------------------

  @Test
  fun conPostoLaFinestraParte_eLaPaginaVaNelProcessoNonNellIntent() {
    val activity = activity()
    registry.admit(activity.taskId, 2)
    val album = AlbumId.random()

    val result = WindowLauncher.open(activity, WindowTarget.Album(album), registry, starts, sdkInt = 34, nowMs = { 100L }, live = setOf(activity.taskId))

    assertThat(result).isEqualTo(WindowOpenResult.OPENED)
    val started = shadowOf(activity).nextStartedActivity
    assertThat(started.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK).isNotEqualTo(0)
    assertThat(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    assertThat(started.component?.className).isEqualTo(MainActivity::class.java.name)
    assertThat(started.extras).isNull()
    assertThat(started.data).isNull()
    // La pagina la prende la finestra che nasce, dal processo.
    assertThat(starts.claim(nowMs = 200L)).isEqualTo(WindowTarget.Album(album))
  }

  @Test
  fun laTerzaFinestraNonParte_siPortaDavantiQuellaCheC_eEUnMessaggio() {
    val activity = activity()
    val other = twoWindows(activity.taskId)
    val brought = mutableListOf<Int>()

    val result = WindowLauncher.open(
      activity, WindowTarget.Library, registry, starts, sdkInt = 34, live = setOf(activity.taskId, other), front = { brought += it; true },
    )

    assertThat(result).isEqualTo(WindowOpenResult.TOO_MANY)
    assertThat(brought).containsExactly(other)
    assertThat(shadowOf(activity).nextStartedActivity).isNull()
    assertThat(starts.isPending).isFalse()
    assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(app.getString(R.string.window_too_many))
    assertThat(registry.count).isEqualTo(2)
  }

  @Test
  fun unPostoCheIlSistemaNonElencaPiuNonImpedisceLaSeconda() {
    val activity = activity()
    val other = twoWindows(activity.taskId)

    // L'altra finestra e' stata chiusa dai recenti senza che nessuno ce lo dicesse: c'e' posto.
    val result = WindowLauncher.open(activity, WindowTarget.Map, registry, starts, sdkInt = 34, live = setOf(activity.taskId))

    assertThat(result).isEqualTo(WindowOpenResult.OPENED)
    assertThat(registry.windows.value).doesNotContain(other)
  }

  @Test
  fun sottoAndroid12_nonSiApreUnaSecondaFinestra() {
    val activity = activity()
    registry.admit(activity.taskId, 1)

    val result = WindowLauncher.open(activity, WindowTarget.Library, registry, starts, sdkInt = 30, live = setOf(activity.taskId))

    assertThat(result).isEqualTo(WindowOpenResult.UNAVAILABLE)
    assertThat(shadowOf(activity).nextStartedActivity).isNull()
    assertThat(starts.isPending).isFalse()
  }

  // --- la porta delle schermate ---------------------------------------------------------------------------------------------

  @Test
  fun laPortaSiOffreSoloDaEstesaODaPiuFinestre() {
    val port = AppNewWindowPort(activity(), registry, starts, sdkInt = 34)

    assertThat(port.isOffered(GalleryWindow.of(412, 900))).isFalse()
    assertThat(port.isOffered(GalleryWindow.of(700, 900))).isFalse()
    assertThat(port.isOffered(GalleryWindow.of(840, 700, PointerKind.MOUSE))).isTrue()
    assertThat(port.isOffered(GalleryWindow.of(1280, 800))).isTrue()
    // Sotto Android 12 mai, neppure da una finestra larga.
    assertThat(AppNewWindowPort(activity(), registry, starts, sdkInt = 30).isOffered(GalleryWindow.of(1280, 800))).isFalse()
  }

  @Test
  fun unIdCheNonEUnIdVeroNonApreNiente() {
    val activity = activity()
    registry.admit(activity.taskId, 2)
    val port = AppNewWindowPort(activity, registry, starts, sdkInt = 34)

    port.openAlbum("non-un-id")
    port.openMemory("")
    port.openAlbum("../../x")

    assertThat(shadowOf(activity).nextStartedActivity).isNull()
    assertThat(starts.isPending).isFalse()
  }

  @Test
  fun laPortaApreLeQuattroPagine() {
    val activity = activity()
    registry.admit(activity.taskId, 2)
    val port = AppNewWindowPort(activity, registry, starts, sdkInt = 34)

    port.openMap()
    assertThat(shadowOf(activity).nextStartedActivity).isNotNull()
    assertThat(starts.claim(nowMsNow())).isEqualTo(WindowTarget.Map)

    port.openLibrary()
    assertThat(shadowOf(activity).nextStartedActivity).isNotNull()
    assertThat(starts.claim(nowMsNow())).isEqualTo(WindowTarget.Library)

    val album = AlbumId.random()
    port.openAlbum(album.value)
    assertThat(shadowOf(activity).nextStartedActivity).isNotNull()
    assertThat(starts.claim(nowMsNow())).isEqualTo(WindowTarget.Album(album))
  }

  private fun nowMsNow(): Long = android.os.SystemClock.elapsedRealtime()
}
