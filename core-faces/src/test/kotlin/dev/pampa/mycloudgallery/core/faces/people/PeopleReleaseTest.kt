package dev.pampa.mycloudgallery.core.faces.people

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.PeopleList
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * R11 (correzioni A della Fase 8): dopo "Disattiva e cancella" (o quando Persone si spegne qui) nella memoria del processo non restano i nomi, il
 * raggruppamento e le proposte che `ArchivePeople` aveva letto: prima `loaded` e `proposalMemo` restavano finche' il processo non moriva, anche con la
 * disponibilita' spenta (la lista si svuotava, i dati no).
 */
@RunWith(AndroidJUnit4::class)
class PeopleReleaseTest {

  @get:Rule
  val folder = TemporaryFolder()

  private var count = 0

  private fun rig() = PeopleRig(File(folder.root, "banco-${count++}"))

  @Test
  fun release_buttaNomiERaggruppamentoELaListaTornaVuota() = runBlocking<Unit> {
    rig().use { r ->
      r.people.rename(r.world.b, "Anna")
      r.people.refresh()
      assertThat(r.people.list.value.named.map { it.name }).containsExactly("Anna")
      assertThat(r.people.holdsArchiveData).isTrue()
      r.people.release()
      assertThat(r.people.holdsArchiveData).isFalse()
      assertThat(r.people.list.value).isEqualTo(PeopleList.EMPTY)
      // Una lettura dopo (se l'archivio c'e' ancora, per esempio un cambio di generazione) rilegge da capo: niente si rompe.
      r.people.refresh()
      assertThat(r.people.list.value.named.map { it.name }).containsExactly("Anna")
    }
  }

  @Test
  fun conLaDisponibilitaSpentaLaMemoriaSiSvuotaAnchePerDavvero() = runBlocking<Unit> {
    rig().use { r ->
      r.people.rename(r.world.b, "Anna")
      assertThat(r.people.holdsArchiveData).isTrue()
      r.available.value = false
      r.people.refresh()
      assertThat(r.people.list.value).isEqualTo(PeopleList.EMPTY)
      assertThat(r.people.holdsArchiveData).isFalse()
    }
  }
}
