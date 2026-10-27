package dev.pampa.mycloudgallery.core.albums.smart

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.PeopleClass
import dev.pampa.mycloudgallery.core.albums.contract.PeopleMembership
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import java.time.LocalDate
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 8 (P10, D27, riga P10 del §4.4): **la regola delle persone su 200 album sintetici** e il ricalcolo incrementale con le persone. Un mondo a caso (GPS, dispositivi,
 * sorgenti, screenshot, video, fusi) e cinque persone con volti "sicuri" e "forse" a caso. Per ogni album con le altre regole (periodo e/o luogo) il verdetto con le persone
 * e' **esattamente** quello dell'album gemello senza persone, combinato con la classe del media: fuori se non e' di nessuna persona, uguale se e' sicuro per le persone,
 * suggerito (S6) se un sicuro ha solo un volto "forse", uguale se era gia' suggerito. E poi: dove i volti non ci sono nessuna riga cambia, e l'incrementale con le persone e'
 * uguale al ricalcolo da zero.
 */
class SmartPeoplePropertyTest {

  private val persons = List(5) { PersonId.fromBytes(ByteArray(PersonId.BYTES) { _ -> (it + 1).toByte() }) }

  /** Le classi di ogni persona, cambiabili (un'assegnazione nuova, una correzione): il banco del riferimento `FakePeopleMembership` non toglie mai. */
  private class Membership : PeopleMembership {
    override val available = MutableStateFlow(true)
    val sure = HashMap<PersonId, MutableSet<MediaId>>()
    val likely = HashMap<PersonId, MutableSet<MediaId>>()

    fun classOf(person: PersonId, media: MediaId): PeopleClass? = when {
      media in sure[person].orEmpty() -> PeopleClass.SURE
      media in likely[person].orEmpty() -> PeopleClass.LIKELY
      else -> null
    }

    fun classOf(rule: PeopleRule, media: MediaId): PeopleClass? {
      val classes = rule.ids.map { classOf(it, media) }
      return when (rule.mode) {
        PeopleMode.ALL -> if (classes.all { it == PeopleClass.SURE }) PeopleClass.SURE else if (classes.all { it != null }) PeopleClass.LIKELY else null
        PeopleMode.ANY -> if (classes.any { it == PeopleClass.SURE }) PeopleClass.SURE else if (classes.any { it != null }) PeopleClass.LIKELY else null
      }
    }

    override suspend fun classify(rule: PeopleRule, candidates: Collection<MediaId>): Map<MediaId, PeopleClass> {
      if (!available.value) return emptyMap()
      return candidates.mapNotNull { m -> classOf(rule, m)?.let { m to it } }.toMap()
    }
  }

  private fun Membership.randomize(media: List<MediaId>, rnd: Random) {
    for (p in persons) {
      sure[p] = media.filterTo(LinkedHashSet()) { rnd.nextInt(100) < 14 }
      likely[p] = media.filterTo(LinkedHashSet()) { it !in sure.getValue(p) && rnd.nextInt(100) < 8 }
    }
  }

  private fun randomPeopleRule(rnd: Random): PeopleRule {
    val chosen = persons.shuffled(rnd).take(1 + rnd.nextInt(3))
    return PeopleRule(chosen, if (rnd.nextBoolean()) PeopleMode.ALL else PeopleMode.ANY)
  }

  private class Row(val sure: Boolean, val reason: String?)

  private fun InMemorySmartRows.verdicts(id: dev.pampa.mycloudgallery.core.model.AlbumId): Map<MediaId, Row> {
    val (auto, suggested) = snapshot(id)
    return auto.mapValues { Row(true, null) } + suggested.mapValues { Row(false, it.value.reason) }
  }

  private fun engine(world: MemoryWorld, rows: InMemorySmartRows, people: PeopleMembership) =
    RulesSmartEngine(rows, world, world, world, NoSmartContentSearch, DeviceNormalizer("Backup/questo-telefono"), Spots.LOOKUP, FakeAlbumClocks(), InMemorySeenMarks(), people)

  @Test
  fun duecentoAlbumSintetici_ConLePersoneVaIlEConLeAltreRegole() = runTest {
    val rnd = Random(2026)
    val world = MemoryWorld()
    val pool = (1..400).map { RandomWorlds.candidate(it, rnd) }
    world.put(*pool.toTypedArray())
    val membership = Membership().also { it.randomize(pool.map { c -> c.media }, rnd) }
    val rows = InMemorySmartRows()
    val engine = engine(world, rows, membership)
    var compared = 0
    var withSure = 0
    var withLikely = 0
    var gated = 0
    for (n in 1..200) {
      val base = RandomWorlds.rules(rnd)
      val people = randomPeopleRule(rnd)
      val withPeople = base.copy(people = people)
      val a = albumId(n)
      val twin = albumId(1_000 + n)
      rows.create(a, withPeople)
      rows.create(twin, base)
      engine.evaluate(a, Duration.INFINITE)
      engine.evaluate(twin, Duration.INFINITE)
      val got = rows.verdicts(a)
      // Sempre: dentro ci sono solo media di una persona della regola, e un sicuro solo se la regola li da' sicuri.
      for ((media, row) in got) {
        val cls = membership.classOf(people, media)
        assertWithMessage("album $n: un media senza le persone della regola").that(cls).isNotNull()
        if (row.sure) assertWithMessage("album $n: un sicuro con le persone solo \"forse\"").that(cls).isEqualTo(PeopleClass.SURE)
      }
      if (base.period == null && base.place == null) {
        // Senza altre regole il gemello e' vuoto (E-AL9) e non dice niente: niente da confrontare, solo le proprieta' di sopra.
        gated++
        continue
      }
      val expected = HashMap<MediaId, Row>()
      for ((media, twinRow) in rows.verdicts(twin)) {
        when (membership.classOf(people, media)) {
          null -> Unit
          PeopleClass.SURE -> expected[media] = twinRow
          PeopleClass.LIKELY -> expected[media] = if (twinRow.sure) Row(false, "s6") else twinRow
        }
      }
      assertWithMessage("album $n").that(got.mapValues { it.value.sure to it.value.reason }).isEqualTo(expected.mapValues { it.value.sure to it.value.reason })
      compared++
      if (got.values.any { it.sure }) withSure++
      if (got.values.any { it.reason == "s6" }) withLikely++
    }
    // La prova ha di che provare: molti album confrontati, con sicuri, con suggeriti dalle persone.
    assertThat(compared).isAtLeast(120)
    assertThat(withSure).isAtLeast(60)
    assertThat(withLikely).isAtLeast(30)
    assertThat(gated).isAtLeast(1)
  }

  @Test
  fun senzaVoltiNessunaRigaCambiaSuDuecentoAlbum() = runTest {
    val rnd = Random(7)
    val world = MemoryWorld()
    val pool = (1..300).map { RandomWorlds.candidate(it, rnd) }
    world.put(*pool.toTypedArray())
    val membership = Membership().also { it.randomize(pool.map { c -> c.media }, rnd) }
    val rows = InMemorySmartRows()
    val engine = engine(world, rows, membership)
    val albums = (1..200).map { n -> albumId(n).also { rows.create(it, RandomWorlds.rules(rnd).copy(people = randomPeopleRule(rnd))) } }
    for (id in albums) engine.evaluate(id, Duration.INFINITE)
    val before = albums.associateWith { rows.snapshot(it) }
    val applies = rows.applies
    membership.available.value = false
    for (id in albums) {
      engine.evaluate(id, Duration.INFINITE)
      engine.reevaluate(id, listOf(RandomWorlds.BASE.plusDays(3)))
    }
    assertThat(rows.applies).isEqualTo(applies)
    for (id in albums) assertWithMessage("album $id").that(rows.snapshot(id)).isEqualTo(before.getValue(id))
  }

  private data class Snap(val auto: Map<MediaId, Long>, val suggested: Map<MediaId, String>)

  private fun InMemorySmartRows.snap(id: dev.pampa.mycloudgallery.core.model.AlbumId): Snap {
    val (auto, suggested) = snapshot(id)
    return Snap(auto.mapValues { it.value.position }, suggested.mapValues { it.value.reason })
  }

  private suspend fun scratch(world: MemoryWorld, rules: SmartRules, membership: PeopleMembership): Snap {
    val rows = InMemorySmartRows()
    val id = albumId(1)
    rows.create(id, rules)
    engine(world, rows, membership).evaluate(id, Duration.INFINITE)
    return rows.snap(id)
  }

  /** L'incrementale con le persone e' uguale alla valutazione intera: arrivano e spariscono media, si nascondono, e le persone cambiano (assegnazioni nuove e tolte). */
  @Test
  fun lIncrementaleConLePersoneEUgualeAlRicalcoloDaZero() = runTest {
    var compared = 0
    for (seed in 1..40) {
      val rnd = Random(seed.toLong() * 104_729)
      val rules = RandomWorlds.rules(rnd).copy(people = randomPeopleRule(rnd))
      val world = MemoryWorld()
      val pool = (1..700).map { RandomWorlds.candidate(it, rnd) }
      world.put(*pool.take(350).toTypedArray())
      var next = 350
      val membership = Membership().also { it.randomize(pool.map { c -> c.media }, rnd) }
      val rows = InMemorySmartRows()
      val id = albumId(1)
      rows.create(id, rules)
      val engine = engine(world, rows, membership)
      engine.evaluate(id, Duration.INFINITE)
      assertWithMessage("subito dopo la valutazione intera (seme $seed)").that(rows.snap(id)).isEqualTo(scratch(world, rules, membership))
      repeat(6) { step ->
        val touched = LinkedHashSet<LocalDate>()
        // Media nuovi (con le loro persone gia' decise dal sorteggio di sopra).
        repeat(10) {
          if (next < pool.size) {
            val c = pool[next++]
            world.put(c)
            touched += c.c.day
          }
        }
        // Un'assegnazione cambia: una persona perde un volto e ne prende un altro (un media "sicuro" diventa "forse" o esce).
        repeat(6) {
          val c = pool[rnd.nextInt(next)]
          val p = persons[rnd.nextInt(persons.size)]
          val to = rnd.nextInt(3)
          membership.sure.getOrPut(p) { LinkedHashSet() }.remove(c.media)
          membership.likely.getOrPut(p) { LinkedHashSet() }.remove(c.media)
          when (to) {
            0 -> membership.sure.getValue(p) += c.media
            1 -> membership.likely.getValue(p) += c.media
          }
          touched += c.c.day
        }
        // Qualcuno sparisce o si nasconde.
        pool.take(next).shuffled(rnd).take(3).forEach { c ->
          if (rnd.nextBoolean()) world.hide(c.media) else world.remove(c.media)
          touched += c.c.day
        }
        engine.reevaluate(id, touched)
        assertWithMessage("dopo il lotto $step (seme $seed)").that(rows.snap(id)).isEqualTo(scratch(world, rules, membership))
        compared++
      }
    }
    assertThat(compared).isEqualTo(240)
  }
}
