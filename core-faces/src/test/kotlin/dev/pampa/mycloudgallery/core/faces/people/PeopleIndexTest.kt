package dev.pampa.mycloudgallery.core.faces.people

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.MatchLevel
import dev.pampa.mycloudgallery.core.faces.contract.PeopleWorld
import dev.pampa.mycloudgallery.core.faces.contract.PersonTarget
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Fase 8 (P9, D26, D27, §2.7): l'indice che la ricerca e gli album smart vedono dalle loro porte (`PeopleIndex`): il dizionario dei nomi
 * (solo persone con un nome, non nascoste, con almeno un media visibile), le chiavi di contenuto "una qualunque" o "tutte" al livello
 * **sicuro** (volto assegnato) o **forse** (fra `T_SUGGEST` e `T_JOIN` per quella persona, dai vettori veri), e gli alias (una persona unita
 * o dimenticata). Sul mondo con i volti di `b` avvicinati a quelli di `a` ([nearer]) la somiglianza cade nella fascia "forse".
 */
@RunWith(AndroidJUnit4::class)
class PeopleIndexTest {

  @get:Rule
  val folder = TemporaryFolder()

  private var count = 0

  private fun rig(vectors: (PeopleWorld) -> (GroupFace) -> FloatArray = { { it.vector() } }): PeopleRig {
    val world = PeopleWorld()
    return PeopleRig(File(folder.root, "banco-${count++}"), world, vectorOf = vectors(world))
  }

  /** I volti di `b` avvicinati ad `a` (peso 0,52 del centro): la somiglianza con `a` e' circa 0,43, fra 0,40 e 0,45. */
  private fun nearer(world: PeopleWorld): (GroupFace) -> FloatArray = ArchiveSeeding.nearer(world.facesOf(world.a), world.facesOf(world.b), 0.52f)

  @Test
  fun ilDizionarioHaSoloPersoneConUnNomeVisibiliENonNascoste() = runBlocking<Unit> {
    rig().use { r ->
      assertThat(r.people.dictionary()).isEmpty()
      r.people.rename(r.world.a, "Marco")
      r.people.rename(r.world.b, "Anna")
      r.people.rename(r.world.c, "Solo nascosti")
      val dictionary = r.people.dictionary()
      assertThat(dictionary.map { it.name to it.visibleMedia }).containsExactly("Anna" to PeopleWorld.PHOTOS, "Marco" to PeopleWorld.PHOTOS - 1).inOrder()
      r.people.setHidden(r.world.a, true)
      assertThat(r.people.dictionary().map { it.name }).containsExactly("Anna")
      r.people.forget(r.world.b)
      assertThat(r.people.dictionary()).isEmpty()
    }
  }

  @Test
  fun unaQualunqueOTutte_alLivelloSicuro() = runBlocking<Unit> {
    rig().use { r ->
      val (a, b) = r.world.a to r.world.b
      val keysA = r.world.facesOf(a).map { it.ref.contentKey }.toSet()
      val keysB = r.world.facesOf(b).map { it.ref.contentKey }.toSet()
      assertThat(r.people.contentWith(setOf(a, b), PeopleMode.ANY, MatchLevel.SURE).keys).containsExactlyElementsIn(keysA + keysB)
      assertThat(r.people.contentWith(setOf(a, b), PeopleMode.ALL, MatchLevel.SURE)).isEmpty()
      // Una persona che non c'e': "tutte" non trova niente, "una qualunque" conta le altre.
      assertThat(r.people.contentWith(setOf(a, PersonId.random()), PeopleMode.ALL, MatchLevel.SURE)).isEmpty()
      assertThat(r.people.contentWith(setOf(a, PersonId.random()), PeopleMode.ANY, MatchLevel.SURE).keys).containsExactlyElementsIn(keysA)
      // Un volto di `a` messo in `b` a mano: ora quella foto ha tutte e due.
      val shared = r.world.facesOf(a).first().locator
      r.people.assign(shared, PersonTarget.Existing(b))
      assertThat(r.people.contentWith(setOf(a, b), PeopleMode.ALL, MatchLevel.SURE).keys).isEmpty()
      assertThat(r.people.contentWith(setOf(b), PeopleMode.ANY, MatchLevel.SURE).keys).contains(shared.contentKey)
    }
  }

  @Test
  fun alLivelloForseCiSonoIVoltiVicini() = runBlocking<Unit> {
    rig(::nearer).use { r ->
      val (a, b) = r.world.a to r.world.b
      val keysB = r.world.facesOf(b).map { it.ref.contentKey }.toSet()
      // Sicuri: solo quelli assegnati. Forse: in piu' i volti di `b` che somigliano ad `a` quanto basta per "forse" (non per entrare).
      assertThat(r.people.contentWith(setOf(a), PeopleMode.ANY, MatchLevel.SURE).values.toSet()).containsExactly(MatchLevel.SURE)
      val likely = r.people.contentWith(setOf(a), PeopleMode.ANY, MatchLevel.LIKELY)
      assertThat(likely.filterValues { it == MatchLevel.LIKELY }.keys).containsExactlyElementsIn(keysB)
      assertThat(likely.filterValues { it == MatchLevel.SURE }.keys).containsExactlyElementsIn(r.world.facesOf(a).map { it.ref.contentKey })
      // "Tutte" a e b: le foto di b sono sicure per b e "forse" per a, quindi "forse" per tutte e due; nessuna foto di altri ci entra, e il livello di una
      // foto e' il piu' basso fra le persone (qui "forse"). Le foto di a entrano solo se somigliano abbastanza a b: dipende dalla geometria, non si impone.
      val keysA = r.world.facesOf(a).map { it.ref.contentKey }.toSet()
      val both = r.people.contentWith(setOf(a, b), PeopleMode.ALL, MatchLevel.LIKELY)
      assertThat(both.keys).containsAtLeastElementsIn(keysB)
      assertThat(keysA + keysB).containsAtLeastElementsIn(both.keys)
      assertThat(both.values.toSet()).containsExactly(MatchLevel.LIKELY)
      assertThat(r.people.contentWith(setOf(a, b), PeopleMode.ALL, MatchLevel.SURE)).isEmpty()
      // Una persona da cui il volto e' stato escluso non lo ha nemmeno "forse".
      val rejected = r.world.facesOf(b).first().locator
      r.people.reject(rejected, a)
      assertThat(r.people.contentWith(setOf(a), PeopleMode.ANY, MatchLevel.LIKELY).keys).doesNotContain(rejected.contentKey)
    }
  }

  @Test
  fun unaPersonaUnitaSegueLAlias_unaDimenticataNonEPiu() = runBlocking<Unit> {
    rig().use { r ->
      val (a, b) = r.world.a to r.world.b
      r.people.merge(listOf(a, b), keepNameOf = null)
      val root = r.people.resolve(a)!!
      assertThat(r.people.resolve(b)).isEqualTo(root)
      val all = r.world.facesOf(a).map { it.ref.contentKey } + r.world.facesOf(b).map { it.ref.contentKey }
      assertThat(r.people.contentWith(setOf(a), PeopleMode.ANY, MatchLevel.SURE).keys).containsExactlyElementsIn(all)
      assertThat(r.people.contentWith(setOf(b), PeopleMode.ANY, MatchLevel.SURE).keys).containsExactlyElementsIn(all)
      r.people.forget(root)
      assertThat(r.people.resolve(a)).isNull()
      assertThat(r.people.contentWith(setOf(a, b), PeopleMode.ANY, MatchLevel.SURE)).isEmpty()
    }
  }
}
