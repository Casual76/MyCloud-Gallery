package dev.pampa.mycloudgallery.core.faces.people

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.PeopleWorld
import dev.pampa.mycloudgallery.core.faces.contract.PersonTarget
import dev.pampa.mycloudgallery.core.faces.contract.SplitOutcome
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.model.PersonId
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Fase 8 (P9, E-F5, E-F22, E-F23, E-F20, D22, D24): **due dispositivi finti dello stesso account**, ognuno con il suo archivio vero e le sue
 * persone sopra, che si scambiano le voci `people` come farebbero la pubblicazione e la replica di P3 (una voce da datare prende l'ora del NAS
 * quando il suo dispositivo pubblica; l'altro la riceve con quell'ora). Nomi, unioni e divisioni **convergono**: dopo lo scambio le due
 * persone vedono le stesse cose, qualunque sia l'ordine delle azioni (l'ultima modifica per ora del NAS vince, uguale ovunque).
 */
@RunWith(AndroidJUnit4::class)
class PeopleTwoDevicesTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val rigs = mutableListOf<PeopleRig>()

  /** Un dispositivo fuori casa: le sue voci restano "da datare" finche' non pubblica ([publish]) e prendono l'ora del NAS in quel momento. */
  private fun device(name: String, seed: Long): PeopleRig =
    PeopleRig(File(folder.root, "banco-$name"), PeopleWorld(), device = name.padEnd(26, '0'), nasNow = null, ids = GroupingStories.ids(seed)).also { rigs += it }

  @After
  fun chiudeTutto() {
    rigs.forEach { it.close() }
  }

  /** Il dispositivo [from] pubblica: le sue voci da datare prendono l'ora [at] del NAS. */
  private suspend fun publish(from: PeopleRig, at: Long) {
    val pending = from.archive.people().values.filter { it.atNas == null }
    from.archive.addPeople(pending.map { it.copy(atNas = at) })
  }

  /** Il dispositivo [to] replica le voci gia' datate dell'altro. */
  private suspend fun receive(to: PeopleRig, from: PeopleRig) {
    to.archive.addPeople(from.archive.people().values.filter { it.atNas != null })
    to.people.refresh()
  }

  /** Tutti pubblicano a ore diverse (nell'ordine) e tutti ricevono da tutti. */
  private suspend fun settle(vararg all: PeopleRig, from: Long = 1_800_000_100_000L) {
    all.forEachIndexed { i, r -> publish(r, from + i * 1_000L) }
    for (to in all) for (other in all) if (other !== to) receive(to, other)
    all.forEach { it.people.refresh() }
  }

  private suspend fun look(r: PeopleRig, ids: Collection<PersonId>): String {
    r.people.refresh()
    val list = r.people.list.value
    return buildString {
      append(list.named.joinToString { "${it.id.value}:${it.name}:${it.photos}" })
      append('|').append(list.othersCount).append('|').append(list.hiddenCount).append('|')
      append(ids.joinToString { "${it.value}->${r.people.resolve(it)?.value}" })
    }
  }

  @Test
  fun unNomeSuUnDispositivoArrivaSulAltro() = runBlocking<Unit> {
    val a = device("A", 1)
    val b = device("B", 2)
    val p = a.world.a
    assertThat(a.people.rename(p, "Marco")).isEqualTo(EditOutcome.Done)
    // Prima della pubblicazione l'altro non ne sa niente; a chi l'ha scritto il nome vale gia'.
    assertThat(a.people.list.value.named.map { it.name }).containsExactly("Marco")
    assertThat(b.people.list.value.named).isEmpty()
    settle(a, b)
    assertThat(b.people.list.value.named.map { it.id to it.name }).containsExactly(p to "Marco")
    assertThat(look(a, listOf(p))).isEqualTo(look(b, listOf(p)))
  }

  /** E-F22: lo stesso nome dato quasi insieme: vince l'ultima modifica per ora del NAS, uguale ovunque (anche se il dispositivo e' minore). */
  @Test
  fun dueNomiQuasiInsiemeVinceLUltimaPerOraDelNas() = runBlocking<Unit> {
    val a = device("A", 1)
    val b = device("B", 2)
    val p = a.world.a
    a.people.rename(p, "Anna")
    b.people.rename(p, "Bea")
    publish(a, 1_800_000_100_000L)
    publish(b, 1_800_000_200_000L)
    receive(a, b)
    receive(b, a)
    assertThat(a.people.list.value.named.map { it.name }).containsExactly("Bea")
    assertThat(b.people.list.value.named.map { it.name }).containsExactly("Bea")
    // Il contrario: B pubblica prima, vince A.
    a.people.rename(p, "Cora")
    b.people.rename(p, "Dina")
    publish(b, 1_800_000_300_000L)
    publish(a, 1_800_000_400_000L)
    receive(a, b)
    receive(b, a)
    assertThat(a.people.list.value.named.map { it.name }).containsExactly("Cora")
    assertThat(b.people.list.value.named.map { it.name }).containsExactly("Cora")
  }

  /** E-F23: due unioni contrarie da due dispositivi (A in B, B in A): un ciclo, la radice e' l'id minore, uguale ovunque; nessun nome perso. */
  @Test
  fun dueUnioniContrarieFannoUnCicloConLaStessaRadiceOvunque() = runBlocking<Unit> {
    val a = device("A", 1)
    val b = device("B", 2)
    val (p, q) = a.world.a to a.world.b
    a.people.merge(listOf(p, q), keepNameOf = q)
    b.people.merge(listOf(q, p), keepNameOf = p)
    settle(a, b)
    val root = minOf(p, q)
    assertThat(a.people.resolve(p)).isEqualTo(root)
    assertThat(a.people.resolve(q)).isEqualTo(root)
    assertThat(b.people.resolve(p)).isEqualTo(root)
    assertThat(b.people.resolve(q)).isEqualTo(root)
    assertThat(look(a, listOf(p, q))).isEqualTo(look(b, listOf(p, q)))
  }

  /** E-F20: una divisione fatta su un dispositivo arriva sull'altro con lo stesso id, le stesse foto, "separata" dalla prima. */
  @Test
  fun unaDivisioneArrivaConLoStessoId() = runBlocking<Unit> {
    val a = device("A", 1)
    val b = device("B", 2)
    val p = a.world.b
    val chosen = a.world.facesOf(p).take(2).map { it.locator }
    val fresh = (a.people.split(p, chosen) as SplitOutcome.Split).newPerson
    settle(a, b)
    val seen = b.people.person(fresh).first()
    assertThat(seen!!.card.photos).isEqualTo(2)
    assertThat(b.people.person(p).first()!!.card.photos).isEqualTo(PeopleWorld.PHOTOS - 2)
    assertThat(look(a, listOf(p, fresh))).isEqualTo(look(b, listOf(p, fresh)))
  }

  /** Una scelta fatta senza l'ora del NAS (fuori casa) vale subito, e arriva datata quando si pubblica: l'ultima scelta dell'utente vince. */
  @Test
  fun unaSceltaFattaFuoriCasaValeSubitoEPoiArriva() = runBlocking<Unit> {
    val away = PeopleRig(File(folder.root, "banco-fuori"), PeopleWorld(), device = "A".padEnd(26, '0'), nasNow = null).also { rigs += it }
    val home = device("B", 2)
    val p = away.world.a
    home.people.rename(p, "Dal NAS")
    publish(home, 1_800_000_100_000L)
    away.people.rename(p, "Da fuori")
    assertThat(away.people.list.value.named.map { it.name }).containsExactly("Da fuori")
    // Il telefono riceve il nome dell'altro, ma la scelta di adesso (da datare) vince su tutte.
    receive(away, home)
    assertThat(away.people.list.value.named.map { it.name }).containsExactly("Da fuori")
    publish(away, 1_800_000_200_000L)
    receive(home, away)
    receive(away, home)
    assertThat(home.people.list.value.named.map { it.name }).containsExactly("Da fuori")
    assertThat(away.people.list.value.named.map { it.name }).containsExactly("Da fuori")
  }

  /** Storie a caso (nomi, nascondi, unisci, dividi, "E' …", "Non e' …", "Ignora", dimentica, copertina) su due dispositivi: dopo lo scambio sono uguali. */
  @Test
  fun storieACasoConvergono() = runBlocking<Unit> {
    for (seed in 1..STORIES) {
      val a = device("A$seed", seed * 2L)
      val b = device("B$seed", seed * 2L + 1)
      val random = Random(seed)
      val world = a.world
      val ids = mutableListOf(world.a, world.b, world.c)
      var clock = 1_800_000_100_000L
      repeat(OPERATIONS) {
        val rig = if (random.nextBoolean()) a else b
        val person = ids.random(random)
        val face = world.faces.random(random).locator
        when (random.nextInt(11)) {
          0 -> rig.people.rename(person, listOf("Anna", "Bea", "Cora", null).random(random))
          1 -> rig.people.setHidden(person, random.nextBoolean())
          2 -> rig.people.merge(listOf(person, ids.random(random)), keepNameOf = null)
          3 -> (rig.people.split(person, world.faces.filter { random.nextInt(3) == 0 }.map { it.locator }) as? SplitOutcome.Split)?.let { ids += it.newPerson }
          4 -> rig.people.assign(face, PersonTarget.Existing(person))
          5 -> rig.people.reject(face, person)
          6 -> rig.people.ignore(face)
          7 -> rig.people.assign(face, PersonTarget.New("Nuova"))
          8 -> if (random.nextInt(4) == 0) rig.people.forget(person)
          9 -> rig.people.setCover(person, face)
          else -> {
            // Ogni tanto un dispositivo pubblica e l'altro riceve.
            publish(rig, clock)
            clock += 1_000L
            receive(if (rig === a) b else a, rig)
          }
        }
      }
      settle(a, b, from = clock + 10_000L)
      val everyone = ids.distinct()
      assertWithMessage("storia $seed: i due dispositivi vedono cose diverse").that(look(a, everyone)).isEqualTo(look(b, everyone))
      // Le voci sono le stesse, una per chiave.
      assertThat(a.records().map { it.key to it.atNas }.toSet()).isEqualTo(b.records().map { it.key to it.atNas }.toSet())
    }
  }

  private companion object {
    const val STORIES = 6
    const val OPERATIONS = 40
  }
}
