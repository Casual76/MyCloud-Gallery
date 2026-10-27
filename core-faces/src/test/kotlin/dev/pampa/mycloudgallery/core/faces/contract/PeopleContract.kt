package dev.pampa.mycloudgallery.core.faces.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La SUITE DI CONTRATTO delle persone (docs/fase8-progetto.md §2.6, §2.9, D24-D28, Q2 (b), E-F20-E-F28, E-F38). Gira sull'esecutore di
 * riferimento ([InMemoryPeopleContractTest]) e sulle persone vere di P9, che la estendono con le schermate e i due dispositivi finti.
 * Ogni azione: le voci giuste e l'effetto **subito**; i nascosti mai.
 */
abstract class PeopleContract {

  /** Quello che la suite prova: le persone, il loro indice e le voci scritte. */
  class UnderTest(val people: People, val index: PeopleIndex, val edits: () -> List<PeopleEdit>)

  /** Le persone sopra [world] (istantanea, volti, voci, visibilita'); [namedOnly] = Q2 (b). */
  protected abstract suspend fun create(world: PeopleWorld, namedOnly: Boolean = true): UnderTest

  private val world = PeopleWorld()

  @Test
  fun q2b_inPrimoPianoSoloChiHaUnNome_eGliAltriInAltriVolti() = runTest {
    val t = create(world)
    val list = t.people.list.value
    assertThat(list.named).isEmpty()
    assertThat(list.unnamed).isEmpty()
    // b ha 5 foto visibili, a 4 (una nascosta), c solo nascoste: non compare (E-F38).
    assertThat(t.people.others().first().people.map { it.id }).containsExactly(world.b, world.a).inOrder()
    assertThat(list.othersCount).isEqualTo(2)
    assertThat(t.people.rename(world.a, "  Marco  ")).isEqualTo(EditOutcome.Done)
    val renamed = t.people.list.value
    assertThat(renamed.named.map { it.id to it.name }).containsExactly(world.a to "Marco")
    assertThat(renamed.named.single().photos).isEqualTo(PeopleWorld.PHOTOS - 1)
    assertThat(t.edits()).containsExactly(PeopleEdit.Name(world.a, "Marco"))
    assertThat(t.people.rename(world.b, "")).isEqualTo(EditOutcome.InvalidName)
    assertThat(t.people.rename(world.b, "x".repeat(65))).isEqualTo(EditOutcome.InvalidName)
    assertThat(t.people.rename(world.a, null)).isEqualTo(EditOutcome.Done)
    assertThat(t.people.list.value.named).isEmpty()
  }

  @Test
  fun q2a_tuttiInPersone() = runTest {
    val t = create(world, namedOnly = false)
    t.people.rename(world.b, "Anna")
    val list = t.people.list.value
    assertThat(list.named.map { it.id }).containsExactly(world.b)
    assertThat(list.unnamed.map { it.id }).containsExactly(world.a)
    assertThat(t.people.others().first().people).isEmpty()
  }

  /** E-F23: con due nomi si sceglie quale tenere; l'unione e' un alias (D22). */
  @Test
  fun unisciConDueNomiChiedeQualeTenere() = runTest {
    val t = create(world)
    t.people.rename(world.a, "Marco")
    t.people.rename(world.b, "Anna")
    assertThat(t.people.merge(listOf(world.a, world.b), keepNameOf = null)).isEqualTo(EditOutcome.NeedsNameChoice)
    assertThat(t.people.merge(listOf(world.a), keepNameOf = null)).isEqualTo(EditOutcome.Invalid)
    assertThat(t.people.merge(listOf(world.a, world.b), keepNameOf = world.b)).isEqualTo(EditOutcome.Done)
    assertThat(t.edits().last()).isEqualTo(PeopleEdit.Alias(world.a, world.b))
    assertThat(t.index.resolve(world.a)).isEqualTo(world.b)
    val list = t.people.list.value
    assertThat(list.named.map { it.id to it.name }).containsExactly(world.b to "Anna")
    assertThat(list.named.single().photos).isEqualTo(2 * PeopleWorld.PHOTOS - 1)
  }

  /** E-F20: "Dividi" crea una persona nuova con i volti scelti, "separata" dalla prima. */
  @Test
  fun dividiCreaUnaPersonaSeparata() = runTest {
    val t = create(world)
    val chosen = world.facesOf(world.b).take(2).map { it.locator }
    val out = t.people.split(world.b, chosen) as SplitOutcome.Split
    val fresh = out.newPerson
    assertThat(t.edits()).containsAtLeast(PeopleEdit.Born(fresh, world.b), PeopleEdit.Apart(fresh, world.b, true))
    assertThat(t.edits().filterIsInstance<PeopleEdit.Is>().map { it.person }.toSet()).containsExactly(fresh)
    assertThat(t.people.person(fresh).first()!!.card.photos).isEqualTo(2)
    assertThat(t.people.person(world.b).first()!!.card.photos).isEqualTo(PeopleWorld.PHOTOS - 2)
    val other = world.facesOf(world.a).first().locator
    assertThat((t.people.split(world.b, listOf(other)) as SplitOutcome.Failed).outcome).isEqualTo(EditOutcome.Invalid)
  }

  /** E-F21, E-F3: "E' …", "Non e' …", "Ignora questo volto". */
  @Test
  fun eNonEIgnora() = runTest {
    val t = create(world)
    val face = world.facesOf(world.a).first().locator
    assertThat(t.people.assign(face, PersonTarget.Existing(world.b))).isEqualTo(EditOutcome.Done)
    assertThat(t.people.person(world.b).first()!!.card.photos).isEqualTo(PeopleWorld.PHOTOS + 1)
    val other = world.facesOf(world.b).first().locator
    assertThat(t.people.reject(other, world.b)).isEqualTo(EditOutcome.Done)
    assertThat(t.people.person(world.b).first()!!.card.photos).isEqualTo(PeopleWorld.PHOTOS)
    assertThat(t.people.ignore(face)).isEqualTo(EditOutcome.Done)
    assertThat(t.people.person(world.b).first()!!.card.photos).isEqualTo(PeopleWorld.PHOTOS - 1)
    assertThat(t.people.assign(world.facesOf(world.b)[2].locator, PersonTarget.New("Zia"))).isEqualTo(EditOutcome.Done)
    assertThat(t.people.list.value.named.map { it.name }).containsExactly("Zia")
    assertThat(t.people.assign(world.facesOf(world.b)[3].locator, PersonTarget.New(" "))).isEqualTo(EditOutcome.InvalidName)
  }

  /** E-F28, E-F27: Nascondi e Dimentica. */
  @Test
  fun nascondiEDimentica() = runTest {
    val t = create(world)
    t.people.rename(world.a, "Marco")
    t.people.rename(world.b, "Anna")
    assertThat(t.people.setHidden(world.a, true)).isEqualTo(EditOutcome.Done)
    assertThat(t.people.list.value.named.map { it.id }).containsExactly(world.b)
    assertThat(t.people.list.value.hiddenCount).isEqualTo(1)
    assertThat(t.people.person(world.a).first()!!.hidden).isTrue()
    assertThat(t.index.dictionary().map { it.id }).containsExactly(world.b)
    assertThat(t.people.forget(world.b)).isEqualTo(EditOutcome.Done)
    assertThat(t.edits().takeLast(2)).containsExactly(PeopleEdit.Forget(world.b), PeopleEdit.Name(world.b, null)).inOrder()
    assertThat(t.index.resolve(world.b)).isNull()
    assertThat(t.people.person(world.b).first()).isNull()
    assertThat(t.index.dictionary()).isEmpty()
    assertThat(t.people.rename(world.b, "Di nuovo")).isEqualTo(EditOutcome.NotFound)
  }

  /** D28, E-F38: mai un nascosto in conteggi, copertine, pagine e dizionario. */
  @Test
  fun nascostiMai() = runTest {
    val t = create(world)
    t.people.rename(world.c, "Solo nascosti")
    t.people.rename(world.a, "Marco")
    assertThat(t.people.list.value.named.map { it.id }).containsExactly(world.a)
    assertThat(t.index.dictionary().map { it.id }).containsExactly(world.a)
    val page = t.people.person(world.a).first()!!
    val shown = (0 until page.view.size).map { page.view.snapshot.idAt(page.view.snapshotIndex(it)) }
    assertThat(shown).containsNoneIn(world.hidden)
    assertThat(shown).hasSize(PeopleWorld.PHOTOS - 1)
    val hiddenFace = world.facesOf(world.a).last().locator
    t.people.setCover(world.a, hiddenFace)
    assertThat(t.people.list.value.named.single().cover).isNotEqualTo(hiddenFace)
    assertThat(t.people.list.value.named.single().cover).isNotNull()
  }

  /** "E' la stessa persona?": "No" separa per sempre, "Si'" unisce. */
  @Test
  fun proposteERisposte() = runTest {
    val t = create(world)
    val proposals = t.people.proposals()
    assertThat(proposals.map { it.pair }).containsExactly(PersonPair.of(world.a, world.b))
    assertThat(t.people.person(world.a).first()!!.maybeAlso.map { it.id }).containsExactly(world.b)
    assertThat(t.people.answer(proposals.single(), same = false)).isEqualTo(EditOutcome.Done)
    val apart = t.edits().last() as PeopleEdit.Apart
    assertThat(PersonPair.of(apart.person, apart.other)).isEqualTo(PersonPair.of(world.a, world.b))
    assertThat(apart.apart).isTrue()
    assertThat(t.people.proposals()).isEmpty()
  }

  /** D26, D27: le chiavi delle persone, "una qualunque" o "tutte", e la riga delle info del viewer. */
  @Test
  fun indiceEVoltiDelMedia() = runTest {
    val t = create(world)
    val keysA = world.facesOf(world.a).map { it.ref.contentKey }.toSet()
    assertThat(t.index.contentWith(setOf(world.a), PeopleMode.ANY, MatchLevel.SURE).keys).isEqualTo(keysA)
    assertThat(t.index.contentWith(setOf(world.a, world.b), PeopleMode.ALL, MatchLevel.SURE)).isEmpty()
    assertThat(t.index.contentWith(setOf(PersonId.random()), PeopleMode.ANY, MatchLevel.SURE)).isEmpty()
    t.people.rename(world.a, "Marco")
    val first = world.facesOf(world.a).first()
    val chips = t.people.facesOf(world.media.getValue(first.ref.contentKey))
    assertThat(chips.filter { it.tier != FaceTier.NONE }.map { it.person }).containsExactly(world.a)
    assertThat(chips.count { it.tier == FaceTier.NONE }).isEqualTo(1)
    t.people.setHidden(world.a, true)
    assertThat(t.people.facesOf(world.media.getValue(first.ref.contentKey)).filter { it.tier != FaceTier.NONE }).isEmpty()
  }
}
