package dev.pampa.mycloudgallery.core.designsystem.testing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `A11yAssertions` provata su alberi piccoli e sbagliati apposta: ogni regola trova la sua violazione e lascia passare
 * il caso giusto (un'utilita' di prova che non e' provata non vale niente, D28).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class A11yAssertionsTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun show(content: @Composable () -> Unit) {
    composeRule.setContent { MyCloudGalleryTheme { content() } }
    composeRule.waitForIdle()
  }

  private fun root(): SemanticsNode = composeRule.onRoot().fetchSemanticsNode()

  // La radice di queste prove e' grande quanto il contenuto: nessuna riga a tutta larghezza da ammettere senza ruolo.
  private fun config() = A11yConfig(density = composeRule.density, allowRolelessRows = false)

  private fun rules(): List<String> = A11yAssertions.check(root(), config()).map { it.rule }

  @Test
  fun unElementoAttivoSenzaNomeVieneTrovato() {
    show { Spacer(Modifier.size(60.dp).clickable(role = Role.Button) {}) }
    assertThat(rules()).contains("NAME")
  }

  @Test
  fun unElementoAttivoSenzaRuoloVieneTrovato() {
    show { Text("Apri", Modifier.size(60.dp).clickable {}) }
    assertThat(rules()).contains("ROLE")
    assertThat(rules()).doesNotContain("NAME")
  }

  @Test
  fun illimiteDelCampoDelFluidSiLasciaPassareSoloInQuellaForma() {
    show {
      Column {
        // Come FluidTextField: etichetta accanto al campo (vuoto, senza segnaposto) e un tasto piccolo senza nome che lo svuota.
        Text("Parola d'ordine")
        Row {
          BasicTextField(value = "", onValueChange = {}, modifier = Modifier.width(200.dp).height(48.dp))
          Spacer(Modifier.size(28.dp).clickable {})
        }
      }
    }
    // Con il limite ammesso (di serie) il campo e il tasto passano; senza, sono violazioni di nome e di ruolo.
    assertThat(A11yAssertions.check(root(), config()).filter { it.rule == "NAME" || it.rule == "ROLE" }).isEmpty()
    val strict = A11yConfig(density = composeRule.density, allowRolelessRows = false, allowEngineTextFieldLimits = false)
    assertThat(A11yAssertions.check(root(), strict).map { it.rule }).containsAtLeast("NAME", "ROLE")
  }

  @Test
  fun ilCampoDiTestoVeroDelFluidPassaConIlLimiteAmmesso() {
    show {
      Column {
        // Il campo vero dell'engine: con un testo (il tasto che svuota si vede) e vuoto, con l'etichetta e senza segnaposto.
        FluidTextField(value = "marta", onValueChange = {}, label = "Utente")
        FluidTextField(value = "", onValueChange = {}, label = "Parola d'ordine")
      }
    }
    val found = A11yAssertions.check(root(), config()).filter { it.rule == "NAME" || it.rule == "ROLE" }
    val tree = A11yAssertions.nodes(root()).joinToString(" // ") { "#${it.id}<-${it.parent?.id} ${it.size.width}x${it.size.height} ${it.config.joinToString(",") { p -> p.key.name }.take(60)}" }
    assertWithMessage(found.joinToString(" | ") + " ARBORE: " + tree).that(found).isEmpty()
    val strict = A11yConfig(density = composeRule.density, allowRolelessRows = false, allowEngineTextFieldLimits = false)
    assertThat(A11yAssertions.check(root(), strict).map { it.rule }).contains("NAME")
  }

  @Test
  fun unaEtichettaLungaInUnFluidButtonAHeightFissaSiLasciaPassareSoloInAltezza() {
    show {
      val density = androidx.compose.ui.platform.LocalDensity.current
      CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
        // 50 dp di altezza fissa, 150 dp di larghezza: a testo 2,0 l'etichetta va a capo e le righe in piu' escono dal bottone.
        FluidButton(text = "Apri il cestino di Galleria", onClick = {}, modifier = Modifier.width(150.dp), size = FluidButtonSize.Large)
      }
    }
    assertThat(rules()).doesNotContain("CLIPPED")
    val strict = A11yConfig(density = composeRule.density, allowRolelessRows = false, allowEngineButtonLabels = false)
    assertThat(A11yAssertions.check(root(), strict).map { it.rule }).contains("CLIPPED")
  }

  @Test
  fun unTastoPiccoloSenzaCampoAccantoRestaUnaViolazioneAnchePerIlLimiteDelFluid() {
    show { Spacer(Modifier.size(28.dp).clickable {}) }
    assertThat(rules()).containsAtLeast("NAME", "ROLE")
  }

  @Test
  fun unBersaglioSotto48DpVieneTrovatoEUnoDa48No() {
    show {
      // Compose allarga da solo ogni bersaglio a 48 dp (`ViewConfiguration.minimumTouchTargetSize`): per provare la regola lo si toglie.
      val base = LocalViewConfiguration.current
      val smallTouch = remember(base) {
        object : ViewConfiguration by base {
          override val minimumTouchTargetSize: DpSize get() = DpSize.Zero
        }
      }
      CompositionLocalProvider(LocalViewConfiguration provides smallTouch) {
        Column {
          Spacer(Modifier.size(30.dp).semantics { contentDescription = "Piccolo" }.clickable(role = Role.Button) {})
          Spacer(Modifier.size(48.dp).semantics { contentDescription = "Giusto" }.clickable(role = Role.Button) {})
        }
      }
    }
    val found = A11yAssertions.check(root(), config()).filter { it.rule == "TARGET" }
    assertThat(found.map { it.node }.joinToString()).contains("Piccolo")
    assertThat(found.map { it.node }.joinToString()).doesNotContain("Giusto")
  }

  @Test
  fun unaDescrizioneConUnIdUnPercorsoOUnNomeDiFileVieneTrovata() {
    val leaks = listOf(
      "Foto 3f2a9c1e-77b4-4c1d-9a3e-0123456789ab",
      "Foto 0123456789abcdef0123",
      "content://media/external/images/media/42",
      "Foto in /storage/emulated/0/DCIM/Camera",
      "IMG_20240312_101500.jpg",
      "Video 2024.mp4",
      "MediaId(42)",
    )
    leaks.forEach { assertThat(A11yAssertions.leakIn(it)).isNotNull() }
    val clean = listOf(
      "Foto, 12 marzo 2024, 10:15, Firenze, preferita, solo sul NAS",
      "Video, 3 agosto 2025, 1 minuto e 12 secondi, con Anna e Marco",
      "Elimina 12 elementi dal telefono",
      "Backup: 12 in coda",
      "Foto del 12/03/2024 alle 10:15",
    )
    clean.forEach { assertThat(A11yAssertions.leakIn(it)).isNull() }
  }

  @Test
  fun unaDescrizioneConUnNomeDiFileNellAlberoVieneTrovata() {
    show {
      Spacer(Modifier.size(60.dp).semantics { contentDescription = "Foto IMG_0001.jpg" }.clickable(role = Role.Button) {})
    }
    assertThat(rules()).contains("LEAK")
  }

  @Test
  fun unTestoTagliatoVieneTrovatoEUnaEllissiNo() {
    show {
      Column {
        // Altezza fissa e nessuna ellissi: il testo grande esce dal contenitore (il caso del testo 2,0x).
        Text(
          "Una frase abbastanza lunga da andare a capo su piu' righe dentro un contenitore stretto e basso",
          Modifier.width(80.dp).height(14.dp).testTag("tagliato"),
          overflow = TextOverflow.Clip,
        )
        // Ellissi voluta: TalkBack legge il testo intero, nessun taglio.
        Text(
          "Una frase abbastanza lunga da andare a capo su piu' righe dentro un contenitore stretto e basso",
          Modifier.width(80.dp).testTag("ellissi"),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    val found = A11yAssertions.check(root(), config()).filter { it.rule == "CLIPPED" }
    assertThat(found.joinToString { it.node }).contains("tagliato")
    assertThat(found.joinToString { it.node }).doesNotContain("ellissi")
  }

  @Test
  fun unaSchermataGiustaEPulita() {
    show {
      Column {
        Text("Titolo", Modifier.semantics { heading() })
        Row {
          Spacer(Modifier.size(48.dp).semantics { contentDescription = "Preferito" }.clickable(role = Role.Button) {})
          Text("Condividi", Modifier.size(48.dp).clickable(role = Role.Button) {})
        }
      }
    }
    A11yAssertions.assertClean(root(), config())
  }

  @Test
  fun leIntestazioniLeRaccolteLeAzioniEGliAnnunciSiLeggono() {
    show {
      Column {
        Text("Marzo 2024", Modifier.semantics { heading() })
        Column(Modifier.semantics { collectionInfo = CollectionInfo(rowCount = 500, columnCount = 3) }) {
          Spacer(
            Modifier.size(60.dp).testTag("cella").semantics {
              contentDescription = "Foto, 12 marzo 2024"
              role = Role.Button
              customActions = listOf(
                CustomAccessibilityAction("Preferito") { true },
                CustomAccessibilityAction("Condividi") { true },
              )
            }.clickable(role = Role.Button) {},
          )
        }
        Text("Backup: 12 in coda", Modifier.testTag("stato").semantics { liveRegion = LiveRegionMode.Polite })
      }
    }
    val r = root()
    assertThat(A11yAssertions.headingNames(r)).containsExactly("Marzo 2024")
    assertThat(A11yAssertions.collections(r).map { it.second }).containsExactly(500 to 3)
    val cell = A11yAssertions.nodes(r).first { A11yAssertions.name(it).startsWith("Foto,") }
    assertThat(A11yAssertions.customActionLabels(cell)).containsExactly("Preferito", "Condividi").inOrder()
    val status = A11yAssertions.nodes(r).first { A11yAssertions.name(it).startsWith("Backup") }
    assertThat(A11yAssertions.liveRegion(status)).isNotNull()
  }

  @Test
  fun lOrdineDiLetturaVaDallAltoInBassoESiPuoForzare() {
    show {
      Column {
        Text("Barra in alto")
        Text("Contenuto")
        Text("Barra in basso")
      }
    }
    val names = A11yAssertions.readingOrder(root()).map(A11yAssertions::name)
    assertThat(names.indexOf("Barra in alto")).isLessThan(names.indexOf("Contenuto"))
    assertThat(names.indexOf("Contenuto")).isLessThan(names.indexOf("Barra in basso"))
  }

  @Test
  fun unNodoSvuotatoDaClearAndSetSemanticsNonContaComeSenzaNome() {
    show {
      Spacer(Modifier.size(60.dp).clearAndSetSemantics { }.clickable {})
    }
    // clearAndSetSemantics toglie anche l'azione: niente da leggere, niente da pretendere.
    assertThat(rules()).doesNotContain("NAME")
  }
}
