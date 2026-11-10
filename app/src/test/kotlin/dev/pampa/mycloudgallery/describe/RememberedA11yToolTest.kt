package dev.pampa.mycloudgallery.describe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.describe.contract.DescribePeople
import dev.pampa.mycloudgallery.core.describe.contract.DescribeSurface
import dev.pampa.mycloudgallery.core.describe.contract.DescribeText
import dev.pampa.mycloudgallery.core.describe.contract.MediaFacts
import dev.pampa.mycloudgallery.core.describe.rules.DefaultMediaDescriber
import dev.pampa.mycloudgallery.core.describe.rules.ItalianDescribeWords
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.places.noop.NoPlaceNames
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * P2 (prestazioni sull'S25, 10/11): lo strumento di accessibilita' **ricordato in memoria** ([RememberedA11yTool]). La chiamata a `system_server` non si
 * rifa' per ogni cella; la risposta si aggiorna quando il sistema dice che l'elenco dei servizi e' cambiato; senza servizi accesi, sotto Android 13 e
 * su un errore il comportamento e' quello di prima. E cio' che ne dipende: le descrizioni con i nomi, quando lo strumento si accende ad app aperta.
 */
class RememberedA11yToolTest {

  /** Il sistema finto: quante volte e' stato interrogato, e l'avviso da far partire a mano. */
  private class FakeSystem(
    var services: Boolean = true,
    var tool: Boolean = false,
    private val notifies: Boolean = true,
    var failing: Boolean = false,
    private val watchFails: Boolean = false,
  ) : A11ySystem {
    var reads = 0
    var watches = 0
    private var onChange: (() -> Unit)? = null

    override val anyServiceEnabled: Boolean get() = services

    override fun toolAmongEnabled(): Boolean {
      reads++
      if (failing) throw SecurityException("servizio non raggiungibile")
      return tool
    }

    override fun watchServices(onChange: () -> Unit): Boolean {
      watches++
      if (watchFails) throw IllegalStateException("registrazione rifiutata")
      if (notifies) this.onChange = onChange
      return notifies
    }

    /** Il sistema dice che l'elenco dei servizi accesi e' cambiato. */
    fun servicesChanged() {
      onChange?.invoke()
    }
  }

  @Test
  fun laRispostaSiRicorda_unaSolaChiamataPerTanteCelle() {
    val system = FakeSystem(tool = true)
    val remembered = RememberedA11yTool(system)
    repeat(1_500) { assertThat(remembered.toolEnabled()).isTrue() }
    assertThat(system.reads).isEqualTo(1)
    assertThat(system.watches).isEqualTo(1)
  }

  @Test
  fun siAggiornaQuandoIlSistemaDiceCheLElencoECambiato() {
    val system = FakeSystem(tool = false)
    val remembered = RememberedA11yTool(system)
    assertThat(remembered.toolEnabled()).isFalse()
    // Lo strumento si accende, ma il sistema non ha ancora avvisato: vale la risposta ricordata.
    system.tool = true
    assertThat(remembered.toolEnabled()).isFalse()
    system.servicesChanged()
    assertThat(remembered.toolEnabled()).isTrue()
    assertThat(remembered.toolEnabled()).isTrue()
    assertThat(system.reads).isEqualTo(2)
    // E si spegne: stesso giro al contrario.
    system.tool = false
    system.servicesChanged()
    assertThat(remembered.toolEnabled()).isFalse()
    assertThat(system.reads).isEqualTo(3)
  }

  @Test
  fun senzaServiziAccesi_noSenzaChiamate_eNienteRestaRicordato() {
    val system = FakeSystem(services = false, tool = true)
    val remembered = RememberedA11yTool(system)
    repeat(10) { assertThat(remembered.toolEnabled()).isFalse() }
    assertWithMessage("senza servizi accesi il sistema non si interroga, come prima").that(system.reads).isEqualTo(0)
    assertThat(system.watches).isEqualTo(0)

    // Un servizio si accende: si legge. Poi tutto si spegne e si riaccende **senza** che l'avviso sia ancora arrivato: si rilegge lo stesso.
    system.services = true
    assertThat(remembered.toolEnabled()).isTrue()
    system.services = false
    assertThat(remembered.toolEnabled()).isFalse()
    system.services = true
    system.tool = false
    assertThat(remembered.toolEnabled()).isFalse()
    assertThat(system.reads).isEqualTo(2)
  }

  @Test
  fun doveIlSistemaNonAvvisa_siRileggeOgniVolta_comePrima() {
    val system = FakeSystem(tool = true, notifies = false)
    val remembered = RememberedA11yTool(system)
    assertThat(remembered.toolEnabled()).isTrue()
    system.tool = false
    assertThat(remembered.toolEnabled()).isFalse()
    assertThat(system.reads).isEqualTo(2)
    assertWithMessage("gli avvisi si chiedono una volta sola").that(system.watches).isEqualTo(1)
  }

  @Test
  fun seLaRichiestaDegliAvvisiFallisce_siRileggeOgniVolta() {
    val system = FakeSystem(tool = true, watchFails = true)
    val remembered = RememberedA11yTool(system)
    assertThat(remembered.toolEnabled()).isTrue()
    system.tool = false
    assertThat(remembered.toolEnabled()).isFalse()
    assertThat(system.reads).isEqualTo(2)
  }

  @Test
  fun unErroreENo_eNonSiRicorda() {
    val system = FakeSystem(tool = true, failing = true)
    val remembered = RememberedA11yTool(system)
    assertThat(remembered.toolEnabled()).isFalse()
    system.failing = false
    assertWithMessage("l'errore non e' una risposta: alla chiamata dopo si rilegge").that(remembered.toolEnabled()).isTrue()
    assertThat(system.reads).isEqualTo(2)
  }

  @Test
  fun unAvvisoArrivatoMentreSiLeggeva_buttaViaLaRisposta() {
    // Il sistema avvisa proprio durante la lettura: la risposta letta e' della generazione vecchia e non si tiene.
    var reads = 0
    var tool = false
    var change: (() -> Unit)? = null
    val system = object : A11ySystem {
      override val anyServiceEnabled: Boolean get() = true

      override fun toolAmongEnabled(): Boolean {
        reads++
        val read = tool
        if (reads == 1) {
          tool = true
          change?.invoke()
        }
        return read
      }

      override fun watchServices(onChange: () -> Unit): Boolean {
        change = onChange
        return true
      }
    }
    val remembered = RememberedA11yTool(system)
    assertThat(remembered.toolEnabled()).isFalse()
    assertWithMessage("la lettura dopo vede il cambio").that(remembered.toolEnabled()).isTrue()
    assertThat(reads).isEqualTo(2)
  }

  // --- Cio' che ne dipende: la descrizione delle celle per chi usa uno screen reader ------------------------------------------------------------

  private fun id(n: Int) = MediaId(UUID(43L, n.toLong()).toString())

  private val photo = MediaFacts(MediaKind.PHOTO, 20240312, null, null, null, 0, 1, false, null)

  @Test
  fun loStrumentoAccesoAdAppAperta_leDescrizioniPrendonoINomi_eLiPerdonoQuandoSiSpegne() = runTest {
    val system = FakeSystem(services = false, tool = false)
    var asked = 0
    val people = DescribePeople { ids ->
      asked++
      ids.associateWith { listOf("Anna") }
    }
    val describer = DefaultMediaDescriber(NoPlaceNames, people, DescribeText { _, _ -> emptyMap() }, ItalianDescribeWords.VALUE, RememberedA11yTool(system))

    // Nessuno strumento: `warm` non chiede niente e la descrizione e' fatta dei soli fatti.
    describer.warm(listOf(id(1)))
    assertThat(asked).isEqualTo(0)
    val plain = describer.describe(id(1), photo, DescribeSurface.IN_APP)
    assertThat(plain).doesNotContain("Anna")

    // TalkBack si accende ad app aperta: il sistema avvisa, e dal giro dopo le celle in vista hanno i nomi.
    system.services = true
    system.tool = true
    system.servicesChanged()
    describer.warm(listOf(id(1)))
    assertThat(asked).isEqualTo(1)
    assertThat(describer.describe(id(1), photo, DescribeSurface.IN_APP)).contains("Anna")

    // E si spegne: i nomi non si dicono piu', anche se sono ancora nella cache del descrittore (E-AC1).
    system.tool = false
    system.servicesChanged()
    assertThat(describer.describe(id(1), photo, DescribeSurface.IN_APP)).isEqualTo(plain)
  }
}
