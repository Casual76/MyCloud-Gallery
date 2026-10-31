package dev.pampa.mycloudgallery.window

import android.app.Application
import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.MainActivity
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element

/**
 * Le impostazioni delle finestre della `MainActivity` (Fase 10, P4, D14-D16): lette dal **sorgente** del manifest (i valori scritti) e dall'activity cosi' come Android
 * la vede (il manifest compilato). `verifyReleaseManifest` pretende le stesse cose sull'APK di release (`mainActivityPolicy`).
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityWindowsManifestTest {

  private fun mainActivityElement(): Element {
    val file = File("src/main/AndroidManifest.xml")
    assertThat(file.isFile).isTrue()
    val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    val document = factory.newDocumentBuilder().parse(file)
    val activities = document.getElementsByTagName("activity")
    for (i in 0 until activities.length) {
      val element = activities.item(i) as Element
      if (element.getAttributeNS(ANDROID, "name") == ".MainActivity") return element
    }
    throw AssertionError("manca la MainActivity nel manifest")
  }

  private fun Element.android(name: String): String? = if (hasAttributeNS(ANDROID, name)) getAttributeNS(ANDROID, name) else null

  @Test
  fun ilManifestDichiaraLeImpostazioniDiD14D15D16() {
    val main = mainActivityElement()

    assertThat(main.android("launchMode")).isEqualTo("singleInstancePerTask")
    assertThat(main.android("resizeableActivity")).isEqualTo("true")
    // StrandHogg (F13): l'affinita' resta vuota.
    assertThat(main.android("taskAffinity")).isEqualTo("")
    val declared = main.android("configChanges").orEmpty().split("|").toSet()
    assertThat(declared).containsExactly(
      "orientation", "screenSize", "smallestScreenSize", "screenLayout", "keyboard", "keyboardHidden", "navigation",
    )
    val layout = main.getElementsByTagName("layout")
    assertThat(layout.length).isEqualTo(1)
    assertThat((layout.item(0) as Element).android("minWidth")).isEqualTo("320dp")
    assertThat((layout.item(0) as Element).android("minHeight")).isEqualTo("400dp")
  }

  /** Densita', lingua, tema e dimensione del testo **ricreano** l'activity: e' qui che si prova lo stato salvato (D14). */
  @Test
  fun chiRicreaNonEDichiarato() {
    val declared = mainActivityElement().android("configChanges").orEmpty().split("|").toSet()
    for (recreating in listOf("density", "locale", "layoutDirection", "uiMode", "fontScale", "colorMode", "fontWeightAdjustment")) {
      assertThat(declared).doesNotContain(recreating)
    }
  }

  @Test
  fun androidVedeLActivityComeScritta() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val info = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)

    assertThat(info.launchMode).isEqualTo(ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK)
    assertThat(info.taskAffinity.orEmpty()).isEmpty()
    for (flag in listOf(
      ActivityInfo.CONFIG_ORIENTATION, ActivityInfo.CONFIG_SCREEN_SIZE, ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE,
      ActivityInfo.CONFIG_SCREEN_LAYOUT, ActivityInfo.CONFIG_KEYBOARD, ActivityInfo.CONFIG_KEYBOARD_HIDDEN, ActivityInfo.CONFIG_NAVIGATION,
    )) {
      assertThat(info.configChanges and flag).isEqualTo(flag)
    }
    for (flag in listOf(ActivityInfo.CONFIG_DENSITY, ActivityInfo.CONFIG_LOCALE, ActivityInfo.CONFIG_UI_MODE, ActivityInfo.CONFIG_FONT_SCALE)) {
      assertThat(info.configChanges and flag).isEqualTo(0)
    }
    assertThat(info.exported).isTrue()
  }

  private companion object {
    const val ANDROID = "http://schemas.android.com/apk/res/android"
  }
}
