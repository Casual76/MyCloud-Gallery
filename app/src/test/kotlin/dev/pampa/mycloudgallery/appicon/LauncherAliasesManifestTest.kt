package dev.pampa.mycloudgallery.appicon

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.MainActivity
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * I5: gli ingressi del launcher nel manifest **unito** di debug (`processDebugManifest`: quello che finisce nell'APK, con i manifest delle librerie e il
 * filtro di `filterDebugNativeLibraries`; non quello di `test_config.properties`, che e' dell'APK delle prove):
 * tre `activity-alias` della MainActivity con la forma che `verifyReleaseManifest` (`launcherAliasPolicy`), `tools/check.ps1` e `tools/release`
 * pretendono, uno solo abilitato di serie (l'automatica), nessun altro ingresso del launcher; e il PackageManager li vede cosi'.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LauncherAliasesManifestTest {

  private val app: Application = ApplicationProvider.getApplicationContext()

  private fun mergedManifest(): Document {
    // Le prove girano dalla cartella del modulo (come `MainActivityWindowsManifestTest`, che legge src/main): il manifest unito di debug di AGP.
    val file = File("build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
    assertWithMessage("il manifest unito di debug non c'e': ${file.absolutePath}").that(file.isFile).isTrue()
    val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    return factory.newDocumentBuilder().parse(file)
  }

  private fun Element.android(name: String): String? = if (hasAttributeNS(ANDROID, name)) getAttributeNS(ANDROID, name) else null

  private fun Element.children(tag: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

  private fun Document.components(tag: String): List<Element> =
    (0 until getElementsByTagName(tag).length).map { getElementsByTagName(tag).item(it) as Element }

  private fun Element.isLauncher(): Boolean =
    children("intent-filter").any { filter -> filter.children("category").any { it.android("name") == LAUNCHER } }

  @Test
  fun treAliasDellaMainActivityConLaFormaAttesa() {
    val manifest = mergedManifest()
    val aliases = manifest.components("activity-alias").associateBy { it.android("name") }
    val icons = mapOf(
      LauncherAliases.AUTO to "@mipmap/ic_launcher",
      LauncherAliases.LIGHT to "@mipmap/ic_launcher_light",
      LauncherAliases.DARK to "@mipmap/ic_launcher_dark",
    )
    for (name in LauncherAliases.all) {
      val alias = aliases[name]
      assertWithMessage("manca l'alias $name nel manifest unito").that(alias).isNotNull()
      alias!!
      assertWithMessage("$name: targetActivity").that(alias.android("targetActivity")).isEqualTo(MainActivity::class.java.name)
      assertWithMessage("$name: e' un ingresso del launcher, esportato").that(alias.android("exported")).isEqualTo("true")
      assertWithMessage("$name: nessun permesso diverso da quello della MainActivity (che non ne ha)").that(alias.android("permission")).isNull()
      assertWithMessage("$name: la sua icona").that(alias.android("icon")).isEqualTo(icons.getValue(name))
      assertWithMessage("$name: l'etichetta dell'app").that(alias.android("label")).isEqualTo("@string/app_name")
      val filters = alias.children("intent-filter")
      assertWithMessage("$name: un solo intent-filter").that(filters).hasSize(1)
      val parts = (0 until filters[0].childNodes.length).map { filters[0].childNodes.item(it) }.filterIsInstance<Element>()
        .map { it.tagName + ":" + it.android("name") }
      assertWithMessage("$name: il filtro ha solo MAIN e LAUNCHER").that(parts)
        .containsExactly("action:android.intent.action.MAIN", "category:$LAUNCHER")
    }
  }

  @Test
  fun soloLAutomaticaEAbilitataDiSerieENessunAltroIngressoDelLauncher() {
    val manifest = mergedManifest()
    val aliases = manifest.components("activity-alias").associateBy { it.android("name") }
    assertThat(aliases.getValue(LauncherAliases.AUTO).android("enabled")).isEqualTo("true")
    assertThat(aliases.getValue(LauncherAliases.LIGHT).android("enabled")).isEqualTo("false")
    assertThat(aliases.getValue(LauncherAliases.DARK).android("enabled")).isEqualTo("false")

    val launchers = (manifest.components("activity") + manifest.components("activity-alias")).filter { it.isLauncher() }.map { it.android("name") }
    assertWithMessage("gli ingressi del launcher sono solo i tre alias (la MainActivity non ha piu' il filtro suo)").that(launchers)
      .containsExactlyElementsIn(LauncherAliases.all)

    val main = manifest.components("activity").single { it.android("name") == MainActivity::class.java.name }
    assertWithMessage("la MainActivity resta esportata (la aprono per nome notifiche, widget, finestre, benchmark)").that(main.android("exported")).isEqualTo("true")
  }

  @Test
  fun ilPackageManagerVedeUnSoloIngressoAbilitatoECambiaSenzaChiudereIlProcesso() {
    val components = AndroidLauncherComponents(app)
    assertThat(LauncherAliases.all.filter { components.isEnabled(it) }).containsExactly(LauncherAliases.AUTO)
    val info = app.packageManager.getActivityInfo(ComponentName(app, LauncherAliases.DARK), PackageManager.MATCH_DISABLED_COMPONENTS)
    assertThat(info.targetActivity).isEqualTo(MainActivity::class.java.name)

    components.setEnabled(LauncherAliases.DARK, true)
    components.setEnabled(LauncherAliases.AUTO, false)

    assertThat(app.packageManager.getComponentEnabledSetting(ComponentName(app, LauncherAliases.DARK)))
      .isEqualTo(PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    assertThat(LauncherAliases.all.filter { components.isEnabled(it) }).containsExactly(LauncherAliases.DARK)
  }

  @Test
  fun lIngressoDiSerieDiceAlLauncherDiAprireLaMainActivity() {
    // `ProcessRestart` riapre l'app da qui: deve trovare l'ingresso abilitato, che porta alla MainActivity.
    val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
    assertWithMessage("nessun ingresso del launcher abilitato").that(launch).isNotNull()
    assertThat(launch!!.component?.className).isEqualTo(LauncherAliases.AUTO)
  }

  private companion object {
    const val ANDROID = "http://schemas.android.com/apk/res/android"
    const val LAUNCHER = "android.intent.category.LAUNCHER"
  }
}
