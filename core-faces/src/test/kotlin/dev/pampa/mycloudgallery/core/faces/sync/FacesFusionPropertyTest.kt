package dev.pampa.mycloudgallery.core.faces.sync

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.SnapshotMark
import java.util.Random
import org.junit.Test

/**
 * La fusione dei contributi e' commutativa, associativa e idempotente (Fase 8, P3, "Fatto quando": test di proprieta' su **10.000 storie di
 * 4 dispositivi**; §2.4, D21). In ogni storia quattro dispositivi dell'account pubblicano da 2 a 5 teste ciascuno, con voci `people` in
 * conflitto (stesse chiavi, ore uguali, autori diversi, lapidi, voci da datare), corpi dello stesso contenuto con ricette, autori e impronte
 * in conflitto e istantanee con la stessa `seq`. Qualunque ordine di arrivo e qualunque raggruppamento devono dare lo stesso stato su ogni
 * dispositivo.
 */
class FacesFusionPropertyTest {

  private val persons = (1..3).map { FacesSyncWorld.person(it) }
  private val keys = (1..4).map { FacesSyncWorld.key(it) }
  private val names = listOf("Anna", "Bea", "Carla", null)

  private class Story(val devices: List<String>, val contributions: List<FusedFaces>)

  private fun story(random: Random): Story {
    val devices = (0 until 4).map { TrustIds.random() }.sorted()
    val bodies = ArrayList<FaceBody>()
    val contributions = ArrayList<FusedFaces>()
    for (device in devices) {
      repeat(2 + random.nextInt(4)) { head ->
        val records = (0 until random.nextInt(6)).map { record(random, devices) }
        val own = (0 until random.nextInt(3)).map { body(random, device, bodies) }
        val snapshot = if (random.nextInt(3) == 0) {
          SnapshotCandidate(device, SnapshotMark(1L + random.nextInt(4), random.nextInt(1).toLong()), 1L + head, "h${random.nextInt(2)}")
        } else {
          null
        }
        contributions += FacesFusion.of(records, own, snapshot)
      }
    }
    return Story(devices, contributions)
  }

  /** Una voce con ore spesso uguali (spareggi sull'autore e sul valore), qualche lapide e qualche voce da datare. */
  private fun record(random: Random, devices: List<String>): PeopleRecord {
    val person = persons[random.nextInt(persons.size)]
    val edit = when (random.nextInt(3)) {
      0 -> PeopleEdit.Name(person, names[random.nextInt(names.size)])
      1 -> PeopleEdit.Hidden(person, random.nextBoolean())
      else -> PeopleEdit.Apart(person, persons.first { it != person }, random.nextBoolean())
    }
    val at = if (random.nextInt(12) == 0) null else 1_800_000_000_000L + random.nextInt(4)
    return PeopleRecord(edit, at, devices[random.nextInt(devices.size)])
  }

  /** Un corpo (senza volti: conta la scelta del vincitore) con ricetta, autore e impronta che spesso coincidono. */
  private fun body(random: Random, author: String, all: MutableList<FaceBody>): FaceBody {
    val key: ContentKey = keys[random.nextInt(keys.size)]
    val body = FaceBody(key, FaceSpaceCode.CURRENT, FaceDetectorCode.YUNET_2023MAR, 1 + random.nextInt(3), FaceSource.ANALYSIS, author, emptyList(), random.nextInt(3).toLong())
    all += body
    return body
  }

  private fun same(a: FusedFaces, b: FusedFaces): Boolean =
    a.people == b.people && a.snapshot == b.snapshot && a.bodies.keys == b.bodies.keys &&
      a.bodies.all { (key, body) -> b.bodies.getValue(key).let { it.author == body.author && it.recipe == body.recipe && it.digest == body.digest } }

  private fun foldAll(items: List<FusedFaces>): FusedFaces = items.fold(FusedFaces.EMPTY, FacesFusion::join)

  /** Un raggruppamento casuale: si spezza la lista in pezzi, si fonde ogni pezzo, poi i pezzi. */
  private fun foldGrouped(items: List<FusedFaces>, random: Random): FusedFaces {
    if (items.size <= 1) return foldAll(items)
    val cut = 1 + random.nextInt(items.size - 1)
    return FacesFusion.join(foldGrouped(items.subList(0, cut), random), foldGrouped(items.subList(cut, items.size), random))
  }

  @Test
  fun diecimilaStorieDiQuattroDispositivi() {
    val random = Random(20261027)
    repeat(10_000) { n ->
      val story = story(random)
      val parts = story.contributions
      val reference = foldAll(parts)
      // Ordine d'arrivo e raggruppamento qualunque: stesso stato.
      val shuffled = parts.shuffled(random)
      assertWithMessage("storia $n: ordine").that(same(foldAll(shuffled), reference)).isTrue()
      assertWithMessage("storia $n: raggruppamento").that(same(foldGrouped(shuffled, random), reference)).isTrue()
      // Commutativa, associativa, idempotente su tre contributi a caso.
      val a = parts[random.nextInt(parts.size)]
      val b = parts[random.nextInt(parts.size)]
      val c = parts[random.nextInt(parts.size)]
      assertWithMessage("storia $n: commutativa").that(same(FacesFusion.join(a, b), FacesFusion.join(b, a))).isTrue()
      assertWithMessage("storia $n: associativa")
        .that(same(FacesFusion.join(FacesFusion.join(a, b), c), FacesFusion.join(a, FacesFusion.join(b, c)))).isTrue()
      assertWithMessage("storia $n: idempotente").that(same(FacesFusion.join(a, a), a)).isTrue()
      assertWithMessage("storia $n: ripetere un contributo non cambia niente").that(same(FacesFusion.join(reference, b), reference)).isTrue()
      // Ogni dispositivo che ha visto tutti i contributi (in ordini diversi) ha lo stesso stato: i quattro convergono.
      val views = story.devices.map { foldAll(parts.shuffled(random)) }
      assertWithMessage("storia $n: convergenza").that(views.all { same(it, reference) }).isTrue()
    }
  }

  /** Lo spareggio delle istantanee (D21): `seq` piu' alta, poi il dispositivo minore; un ordine totale anche sulla stessa `seq`. */
  @Test
  fun lIstantaneaVincente() {
    val low = "0".repeat(26)
    val high = "z".repeat(26)
    val a = SnapshotCandidate(high, SnapshotMark(5, 4), 1, "a")
    val b = SnapshotCandidate(low, SnapshotMark(4, 3), 9, "a")
    val c = SnapshotCandidate(low, SnapshotMark(5, 2), 1, "b")
    assertWithMessage("seq piu' alta").that(FacesFusion.better(a, b)).isEqualTo(a)
    assertWithMessage("a pari seq il dispositivo minore").that(FacesFusion.better(a, c)).isEqualTo(c)
    assertWithMessage("ordine indifferente").that(FacesFusion.winner(listOf(a, b, c))).isEqualTo(FacesFusion.winner(listOf(c, b, a)))
    val d = SnapshotCandidate(low, SnapshotMark(5, 2), 2, "a")
    assertWithMessage("stesso custode e stessa seq: la testa piu' nuova").that(FacesFusion.better(c, d)).isEqualTo(d)
  }
}
