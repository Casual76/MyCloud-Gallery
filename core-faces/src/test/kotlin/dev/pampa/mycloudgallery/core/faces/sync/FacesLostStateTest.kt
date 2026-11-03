package dev.pampa.mycloudgallery.core.faces.sync

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Test stabili (03/11): chi perde lo stato locale e pubblica **prima** di aver riletto la propria testa (un giro che non l'ha letta per un guasto del NAS, una
 * pubblicazione di ripresa al primo avvio) non fa sparire dalla propria testa le **sue** voci `people`. La nuova testa sostituisce la vecchia, e gli altri
 * dispositivi non ripubblicano le voci portate da un altro (R3): se la ripresa riprendeva solo i corpi, una voce scritta qui e rimasta solo nella testa vecchia
 * si perdeva per sempre (il banco `FacesSyncBenchTest` lo trovava una volta su cento, secondo l'ordine degli id dei dispositivi, scritta sul Portatile).
 */
open class FacesLostStateTest {

  protected open fun newWorld(): FacesSyncWorld = FacesSyncWorld()

  private val a = FacesSyncWorld.person(1)
  private val b = FacesSyncWorld.person(2)

  private fun nameOf(device: SyncDevice): String? = kotlinx.coroutines.runBlocking {
    (device.archive.people()[PeopleKey(a.base32(), "name")]?.edit as? PeopleEdit.Name)?.name
  }

  private fun hiddenOf(device: SyncDevice): Boolean? = kotlinx.coroutines.runBlocking {
    (device.archive.people()[PeopleKey(b.base32(), "hidden")]?.edit as? PeopleEdit.Hidden)?.hidden
  }

  @Test
  fun leMieVociTornanoNellaTestaAnchePubblicandoPrimaDelGiro() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono", "test")
    val tablet = world.device("Tablet", "test")
    phone.edit(PeopleEdit.Name(a, "Persona Uno"), PeopleEdit.Hidden(b, true))
    assertThat(phone.publisher.flush(FlushReason.UNIT_FINISHED)).isInstanceOf(FacesPublishOutcome.Published::class.java)

    // Lo stato locale del telefono e' perso; pubblica subito, senza un giro che abbia riletto la sua testa.
    world.clock.advanceMillis(10)
    phone.restart(loseLocal = true)
    phone.publisher.flush(FlushReason.UNIT_FINISHED)

    // Un dispositivo che legge solo adesso trova le voci nella testa piu' recente del telefono, e il telefono le ha di nuovo qui.
    tablet.round()
    assertThat(nameOf(tablet)).isEqualTo("Persona Uno")
    assertThat(hiddenOf(tablet)).isTrue()
    assertThat(nameOf(phone)).isEqualTo("Persona Uno")
    assertThat(hiddenOf(phone)).isTrue()
  }
}
