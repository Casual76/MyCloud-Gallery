package dev.pampa.mycloudgallery.core.faces.people

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.PeopleContract
import dev.pampa.mycloudgallery.core.faces.contract.PeopleWorld
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Fase 8 (P9, §5.2): le persone **vere** (`ArchivePeople` sopra l'archivio cifrato vero, la Libreria finta e il pubblicatore che registra)
 * passano la stessa suite di contratto dell'esecutore di riferimento `InMemoryPeople` (`PeopleContract`): Q2 (a) e (b), unione con due
 * nomi, divisione, "E' …", "Non e' …", "Ignora", Nascondi e Dimentica, nascosti mai, proposte e indice. Le voci che la suite guarda sono
 * quelle accodate alla pubblicazione, nell'ordine in cui sono state scritte.
 *
 * Una differenza voluta: le proposte "E' la stessa persona?" qui le fa il motore vero del raggruppamento dai vettori (`GroupingProposals`),
 * non una lista data. Il mondo ha tre identita' lontane fra loro, quindi si avvicinano i volti di una persona ai volti di un'altra con un
 * vettore **avvicinato** (`ArchiveSeeding.nearer`) (stessa cosa che il tempo fa a un bambino che cresce, E-F2): il legame medio fra gli esemplari cade nella fascia
 * delle proposte (0,40-0,52) e non oltre (sarebbe un'unione automatica della notte).
 */
@RunWith(AndroidJUnit4::class)
class ArchivePeopleContractTest : PeopleContract() {

  @get:Rule
  val folder = TemporaryFolder()

  private val rigs = mutableListOf<PeopleRig>()

  override suspend fun create(world: PeopleWorld, namedOnly: Boolean): UnderTest {
    val rig = PeopleRig(File(folder.root, "banco-${rigs.size}"), world, namedOnly = namedOnly, vectorOf = nearerTo(world))
    rigs += rig
    return UnderTest(rig.people, rig.people) { rig.publisher.edits() }
  }

  @After
  fun chiudeTutto() {
    rigs.forEach { it.close() }
  }

  companion object {
    /** I volti di `b` avvicinati ad `a` (peso 0,56 del centro: legame medio ~0,45 con gli esemplari di `a`, dentro la fascia delle proposte). */
    fun nearerTo(world: PeopleWorld): (GroupFace) -> FloatArray = ArchiveSeeding.nearer(world.facesOf(world.a), world.facesOf(world.b), NEAR)

    private const val NEAR = 0.56f
  }
}
