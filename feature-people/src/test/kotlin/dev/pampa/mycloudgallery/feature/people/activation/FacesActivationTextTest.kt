package dev.pampa.mycloudgallery.feature.people.activation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il testo della pagina di attivazione (docs/fase8-progetto.md §2.1 "La pagina di attivazione", D3, D32, D33, Q3): i sette paragrafi del progetto, in
 * frasi brevi, con la loro versione. Se qualcuno cambia una parola che regge una promessa (niente esce da casa, restano tuoi, si cancella davvero, non stima
 * l'eta') questo test lo vede; se cambia il testo, cambia la versione e tutti i dispositivi tornano all'avviso.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "it")
class FacesActivationTextTest {

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

  private val text: String get() = (FacesActivationText.paragraphs(resources).map { it.text } + FacesActivationText.models(resources)).joinToString("\n")

  @Test
  fun ilTestoHaUnaVersioneEQuellaDelInterruttore() {
    assertThat(FacesActivationText.VERSION).isEqualTo(FacesSwitch.CURRENT_TEXT_VERSION)
    assertThat(FacesActivationText.VERSION).isGreaterThan(0)
  }

  @Test
  fun iSettePezziDelProgetto() {
    assertThat(FacesActivationText.paragraphs(resources)).hasSize(6)
    val paragraphs = FacesActivationText.paragraphs(resources).map { it.text }
    assertThat(paragraphs[0]).isEqualTo("Persone raggruppa le tue foto per volto: dai un nome a chi conosci e lo ritrovi con un tocco, anche nella ricerca e negli album.")
    assertThat(paragraphs[1]).isEqualTo("Il lavoro lo fa un tuo dispositivo che analizza le foto, di notte, in carica. Nessuna foto e nessun volto escono da casa.")
    assertThat(paragraphs[2]).isEqualTo(
      "I gruppi e i nomi sono solo tuoi: stanno nella tua area privata del NAS e sui tuoi dispositivi. Nessun familiare li vede, nemmeno per le foto dell’area comune.",
    )
    assertThat(paragraphs[3]).isEqualTo(
      "Nelle foto ci sono anche amici e bambini: anche i loro volti vengono raggruppati. L’app non stima età, sesso o altro. Puoi nascondere o far dimenticare chiunque.",
    )
    assertThat(paragraphs[4]).isEqualTo("«Disattiva e cancella» toglie tutto, dal NAS e da tutti i tuoi dispositivi; un dispositivo spento lo fa appena si riaccende.")
    assertThat(paragraphs[5]).isEqualTo("Su ogni tuo dispositivo puoi dire «Non su questo dispositivo».")
    assertThat(FacesActivationText.models(resources)).isEqualTo("Modelli usati: YuNet (licenza MIT) e SFace (licenza Apache 2.0), dentro l’app.")
  }

  @Test
  fun ilGrassettoCoprePropriolECheConta() {
    assertThat(FacesActivationText.boldPhrases(resources)).containsExactly(
      "Nessuna foto e nessun volto escono da casa.",
      "I gruppi e i nomi sono solo tuoi",
      "non stima",
      "«Disattiva e cancella»",
      "«Non su questo dispositivo»",
    ).inOrder()
  }

  /** Le promesse che la pagina fa e il resto del codice mantiene (D11, D14, D29, D32, D33). */
  @Test
  fun lePromesseCheLaPaginaFaNonSiAllargano() {
    assertThat(text).contains("Nessuna foto e nessun volto escono da casa")
    assertThat(text).contains("solo tuoi")
    assertThat(text).contains("Nessun familiare li vede")
    assertThat(text).contains("non stima età, sesso o altro")
    assertThat(text).contains("toglie tutto, dal NAS e da tutti i tuoi dispositivi")
    assertThat(text).contains("un dispositivo spento lo fa appena si riaccende")
    assertThat(text).contains("amici e bambini")
  }

  /** Mai parole che direbbero il contrario o che non sono vere (nessuna rete, nessun invio, nessuna cifratura del NAS promessa). */
  @Test
  fun nienteDiCioCheNonEVero() {
    val lower = text.lowercase()
    for (word in listOf("inviat", "cloud", "server", "google", "cifrat", "crittograf", "gratis", "sicuro al 100", "garant")) {
      assertThat(lower).doesNotContain(word)
    }
  }

  @Test
  fun ogniParagrafoEBreve() {
    // "In frasi brevi": nessun paragrafo oltre 200 caratteri.
    for (paragraph in FacesActivationText.paragraphs(resources)) assertThat(paragraph.text.length).isAtMost(200)
  }
}
