package dev.pampa.mycloudgallery.feature.settings.about

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.ui.fluid.FluidEngineLicenses
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.settings.R
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Informazioni e licenze" (Fase 3, P11; I6 della revisione di sicurezza): l'attribuzione di GeoNames, **tutte** le librerie
 * di altri autori dell'APK di release con la loro licenza, le opere del Fluid Engine, i testi brevi delle licenze e la
 * versione dell'app. Gli indirizzi delle licenze sono **testo, mai un collegamento** (la Fase 3 non fa traffico di
 * rete, D18/D24): lo prova sia la schermata (nessun tocco su quelle righe) sia il sorgente (nessun intent).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h1800dp-xhdpi")
class AboutScreenTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun show(versionName: String? = "1.2.3", onBack: () -> Unit = {}) {
    rule.setContent {
      MyCloudGalleryTheme {
        AboutScreen(versionName = versionName, onBack = onBack)
      }
    }
    rule.waitForIdle()
  }

  private fun string(id: Int): String = rule.activity.getString(id)

  // --- GeoNames (CC BY 4.0) -----------------------------------------------------------------------------------

  @Test
  fun mostraLAttribuzioneDiGeoNamesParolaPerParola() {
    show()

    // Il testo che la licenza chiede, uguale a PlacesAttribution.TEXT (lo verifica un test di :app).
    val expected = "Nomi e fusi orari dei luoghi: dati di GeoNames (geonames.org), licenza Creative Commons Attribuzione 4.0. " +
      "I dati sono stati modificati: ridotti, in parte tradotti in italiano e convertiti in un formato compatto."
    assertThat(string(R.string.about_geonames_attribution)).isEqualTo(expected)
    rule.onNodeWithText(expected).assertIsDisplayed()
  }

  @Test
  fun diceCheIDatiDeiLuoghiSonoDentroLAppESenzaRete() {
    show()

    // I5: l'asset e' nell'APK (SHA-256 fissato), quindi la nota non dice piu' "non ancora inclusi".
    rule.onNodeWithText(
      "I dati dei luoghi sono dentro l’app e si leggono sul telefono, senza internet. Le foto senza posizione non hanno un luogo.",
    ).assertIsDisplayed()
    rule.onAllNodesWithText("non sono ancora inclusi", substring = true).assertCountEquals(0)
  }

  @Test
  fun mostraNomeEIndirizzoDellaLicenzaCcByComeTestoSenzaTocco() {
    show()

    rule.onNodeWithText("Creative Commons Attribuzione 4.0 (CC BY 4.0)").assertIsDisplayed()
    // L'indirizzo e' una riga di testo: niente azione di clic (nessun browser, nessuna rete).
    rule.onNodeWithText("https://creativecommons.org/licenses/by/4.0/").assertIsDisplayed().assertHasNoClickAction()
  }

  // --- Le librerie ----------------------------------------------------------------------------------------------

  /** Porta in vista la riga con [text]: la pagina e' una lista pigra e le righe in fondo non sono ancora composte. */
  private fun scrollTo(text: String, substring: Boolean = false) {
    rule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = substring))
    rule.waitForIdle()
  }

  @Test
  fun elencaTutteLeLibrerieDellApkConLaLoroLicenza() {
    show()

    for (library in ABOUT_LIBRARIES) {
      scrollTo(library.name)
      rule.onAllNodesWithText(library.name).onFirst().assertIsDisplayed()
      rule.onAllNodesWithText(library.subtitle).onFirst().assertExists()
    }
  }

  @Test
  fun lElencoCopreLeFamiglieDelleDipendenzeDiRuntimeDellaRelease() {
    val names = ABOUT_LIBRARIES.map { it.name }
    val text = ABOUT_LIBRARIES.joinToString("\n") { "${it.name} ${it.subtitle}" }

    // Dall'albero `releaseRuntimeClasspath`: AndroidX, Kotlin/kotlinx, Hilt/Dagger, Room, smbj con le sue dipendenze
    // (bcprov, mbassador, asn-one, slf4j), ZXing, CameraX, Media3, telephoto.
    for (family in listOf(
      "AndroidX", "Jetpack Compose", "Room", "WorkManager", "DataStore", "ExifInterface", "Media3", "CameraX", "Kotlin", "kotlinx",
      "Dagger e Hilt", "Guava", "smbj", "asn-one", "Bouncy Castle", "mbassador", "SLF4J", "Okio", "ZXing", "telephoto", "Poko",
    )) {
      assertWithMessage("manca $family").that(names.any { it.contains(family) }).isTrue()
    }
    // E gli artefatti piccoli che arrivano da sole: annotazioni, inject, libyuv di CameraX.
    for (small in listOf("javax.inject", "jakarta.inject", "JSR 305", "AutoValue", "JSpecify", "libyuv", "failureaccess")) {
      assertWithMessage("manca $small").that(text).contains(small)
    }
  }

  @Test
  fun ogniLibreriaHaAutoreDettaglioELicenzaEUnaSolaVoltaIlNome() {
    for (library in ABOUT_LIBRARIES) {
      assertThat(library.name).isNotEmpty()
      assertThat(library.author).isNotEmpty()
      assertThat(library.detail).isNotEmpty()
    }
    assertThat(ABOUT_LIBRARIES.map { it.name }).containsNoDuplicates()
    // Le licenze che chiedono la riga di copyright (MIT e Bouncy Castle) ce l'hanno nella riga della libreria.
    val mit = ABOUT_LIBRARIES.filter { it.license == LibraryLicense.MIT || it.license == LibraryLicense.BOUNCY_CASTLE }
    assertThat(mit.map { it.name }).containsAtLeast("Bouncy Castle Provider (bcprov)", "mbassador", "SLF4J API")
    assertThat(mit.first { it.name.startsWith("Bouncy") }.author).contains("The Legion of the Bouncy Castle Inc.")
    assertThat(mit.first { it.name == "SLF4J API" }.author).contains("QOS.ch")
  }

  /**
   * La pagina non resta indietro: le versioni fissate in `versions-app.gradle` (quelle delle librerie dell'APK) sono quelle
   * che la pagina scrive. Cambiare una versione senza aggiornare l'elenco fa fallire questa prova.
   */
  @Test
  fun leVersioniDellaPaginaSonoQuelleFissateNelBuild() {
    val gradle = File("../versions-app.gradle")
    assertThat(gradle.isFile).isTrue()
    val pinned = Regex("""^\s*(\w+)\s*:\s*'([^']+)'""", RegexOption.MULTILINE).findAll(gradle.readText())
      .associate { it.groupValues[1] to it.groupValues[2] }

    for (library in ABOUT_LIBRARIES.filter { it.versionKey != null }) {
      assertWithMessage("${library.name}: ${library.versionKey}").that(pinned[library.versionKey]).isEqualTo(library.version)
      assertThat(library.detail).contains(library.version!!)
    }
    // Le dipendenze di runtime fissate dall'app (non i test, non i plugin di build) compaiono con la loro versione.
    val text = ABOUT_LIBRARIES.joinToString("\n") { "${it.name} ${it.subtitle}" }
    for (key in listOf("hilt", "androidxHilt", "room", "work", "coroutines", "serializationJson", "biometric", "smbj", "zxing", "camerax", "exifinterface", "media3", "telephoto", "profileInstaller")) {
      val version = pinned[key]
      assertWithMessage("manca la chiave $key in versions-app.gradle").that(version).isNotNull()
      assertWithMessage("la pagina non scrive $key $version").that(text).contains(version!!)
    }
  }

  @Test
  fun mostraLeOpereDelFluidEngineComeTestoSenzaTocco() {
    show()

    for (work in FluidEngineLicenses) {
      scrollTo(work.name)
      rule.onAllNodesWithText(work.name).onFirst().assertIsDisplayed()
      // Autore, licenza e indirizzo del progetto sono una riga di testo: nessuna azione di clic (nessun browser).
      rule.onNode(hasText("${work.author} · ${work.licence}", substring = true)).assertExists().assertHasNoClickAction()
      rule.onNode(hasText(work.url, substring = true)).assertExists().assertHasNoClickAction()
    }
    // Inter e il vetro sono nell'APK: l'avviso c'e'. Dalla 3.0 anche LibrePods, GPL-3.0 (il suo codice e' dentro l'engine).
    assertThat(FluidEngineLicenses.map { it.name }).containsAtLeast("AndroidLiquidGlass (backdrop)", "Inter", "LibrePods")
    assertThat(FluidEngineLicenses.first { it.name == "LibrePods" }.licence).isEqualTo("GPL-3.0")
  }

  /**
   * Adozione del Fluid Engine 3.0: l'app e' GPL-3.0 (l'engine porta codice di LibrePods) e la pagina lo dice con le "informazioni legali" che la GPL chiede a
   * un'interfaccia: la licenza, il diritto ai sorgenti e a ridistribuire, nessuna garanzia, dove si legge il testo. Testo, mai un collegamento.
   */
  @Test
  fun diceCheLAppEGpl3_conISorgentiENessunaGaranzia() {
    show()

    rule.onNodeWithText("Software libero, GPL-3.0").assertIsDisplayed().assertHasNoClickAction()
    val detail = string(R.string.about_app_license_detail)
    for (part in listOf("GNU General Public License, versione 3", "ridistribuirla e modificarla", "sorgenti completi", "senza alcuna garanzia", "LibrePods", "LICENSE")) {
      assertWithMessage("la frase della licenza non dice: $part").that(detail).contains(part)
    }
    rule.onNodeWithText(detail).assertIsDisplayed().assertHasNoClickAction()
    // Il testo breve della GPL-3.0 sta con le altre licenze, con il suo indirizzo come testo.
    assertThat(string(LibraryLicense.GPL_3.url)).isEqualTo("https://www.gnu.org/licenses/gpl-3.0.html")
    // Il file LICENSE alla radice dei sorgenti e' la GPL-3.0.
    val license = File("../LICENSE")
    assertThat(license.isFile).isTrue()
    assertThat(license.readText().replace("\r\n", "\n")).startsWith("                    GNU GENERAL PUBLIC LICENSE\n                       Version 3, 29 June 2007")
  }

  @Test
  fun scriveNomeEIndirizzoDiOgniLicenzaComeTestoSenzaTocco() {
    show()

    assertThat(ABOUT_LICENSES_IN_USE).containsAtLeast(LibraryLicense.APACHE_2, LibraryLicense.MIT, LibraryLicense.BOUNCY_CASTLE, LibraryLicense.SIL_OFL, LibraryLicense.GPL_3)
    for (license in ABOUT_LICENSES_IN_USE) {
      val name = string(license.fullName)
      val url = string(license.url)
      scrollTo(name)
      rule.onNodeWithText(name).assertIsDisplayed()
      rule.onNodeWithText(url).assertIsDisplayed().assertHasNoClickAction()
    }
    rule.onNodeWithText("Apache License, versione 2.0").assertExists()
    rule.onNodeWithText("https://www.apache.org/licenses/LICENSE-2.0").assertExists()
  }

  @Test
  fun dichiaraCheLeLibrerieNonInviamoDatiENonCiSonoConnessioniDiRete() {
    show()

    scrollTo("Le librerie non inviano dati.", substring = true)
    rule.onNodeWithText(
      "Le librerie non inviano dati. Per la galleria del telefono l’app non fa nessuna connessione di rete; solo la mappa dettagliata, se è " +
        "accesa, chiede le zone guardate a OpenFreeMap. Gli indirizzi qui sotto sono solo testo: non si aprono da soli.",
    ).assertIsDisplayed()
  }

  // --- La versione ----------------------------------------------------------------------------------------------------

  @Test
  fun mostraLaVersioneDellApp() {
    show(versionName = "1.2.3")

    rule.onNodeWithText("MyCloud Gallery").assertIsDisplayed()
    rule.onNodeWithText("Versione 1.2.3").assertIsDisplayed()
  }

  @Test
  fun senzaVersioneNonMostraLaRiga() {
    show(versionName = null)

    rule.onNodeWithText("MyCloud Gallery").assertIsDisplayed()
    rule.onAllNodesWithText("Versione ", substring = true).assertCountEquals(0)
  }

  @Test
  fun laVersioneVieneDalGestoreDeiPacchetti() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).versionName = "9.8.7"

    assertThat(appVersionName(context)).isEqualTo("9.8.7")
  }

  // --- Navigazione --------------------------------------------------------------------------------------------------

  @Test
  fun ilTastoIndietroTornaAlleImpostazioni() {
    var back = 0
    show(onBack = { back++ })

    rule.onNodeWithContentDescription("Indietro").performClick()

    assertThat(back).isEqualTo(1)
  }

  // --- Nessun traffico di rete ----------------------------------------------------------------------------------

  @Test
  fun ilSorgenteDellaPaginaNonApreCollegamentiNeFaRete() {
    val sources = File("src/main/kotlin/dev/pampa/mycloudgallery/feature/settings/about").walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .toList()
    assertThat(sources).isNotEmpty()

    // Un indirizzo qui e' testo (sta nelle risorse), non un intent ne' un collegamento ne' una connessione.
    val forbidden = Regex(
      """\bIntent\b|ACTION_VIEW|LocalUriHandler|UriHandler|openUri|LinkAnnotation|ClickableText|Uri\.parse|\bURL\(|HttpURLConnection|https?://""",
    )
    val found = sources.flatMap { file ->
      file.readLines().mapIndexedNotNull { index, line ->
        val code = line.substringBefore("//")
        val trimmed = code.trimStart()
        val isDoc = trimmed.startsWith("*") || trimmed.startsWith("/**")
        if (!isDoc && forbidden.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
      }
    }
    assertThat(found).isEmpty()
  }
}
