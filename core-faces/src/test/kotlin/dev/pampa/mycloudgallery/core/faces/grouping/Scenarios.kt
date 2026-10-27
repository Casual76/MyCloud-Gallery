package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentities
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingCorrections
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PersonIdSource
import dev.pampa.mycloudgallery.core.faces.contract.PersonPair
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceTier
import kotlin.random.Random

/**
 * Le storie a caso dei test di P5 (parita', proprieta', stabilita'): famiglie sintetiche piccole ([FamilyStories]), arrivi a lotti di foto,
 * correzioni dell'utente a caso derivate dallo stato. Solo vettori sintetici, nessun dato personale.
 */
internal object Scenarios {

  /** Una famiglia piccola a caso (da 4 a 13 identita', da 30 a 149 foto) con fratelli, gemelli, eta', deboli, sconosciuti, video, poster. */
  fun config(seed: Long): FamilyStories.Config {
    val r = Random(seed)
    val identities = 4 + r.nextInt(10)
    val siblings = r.nextInt(identities / 4 + 1)
    val twins = if (2 * siblings + 2 <= identities && r.nextInt(4) == 0) 1 else 0
    val children = minOf(r.nextInt(3), identities - 2 * (siblings + twins))
    return FamilyStories.Config(
      seed = seed,
      identities = identities,
      photos = 30 + r.nextInt(120),
      maxFacesPerPhoto = 1 + r.nextInt(4),
      groupShare = r.nextFloat() * 0.3f,
      weakShare = r.nextFloat() * 0.3f,
      siblings = siblings,
      twins = twins,
      years = if (r.nextBoolean()) r.nextFloat() * 20f else 0f,
      children = children,
      strangerShare = r.nextFloat() * 0.3f,
      videoShare = r.nextFloat() * 0.1f,
      mirrorShare = r.nextFloat() * 0.05f,
      posterShare = if (r.nextInt(5) == 0) 0.2f else 0f,
    )
  }

  /** Le foto della storia in [parts] arrivi successivi: l'arrivo k contiene i volti delle foto delle parti 0..k (cumulativo). */
  fun arrivals(faces: List<GroupFace>, parts: Int, random: Random): List<List<GroupFace>> {
    val keys = faces.map { it.ref.contentKey }.distinct().shuffled(random)
    val partOf = keys.withIndex().associate { (i, k) -> k to i * parts / keys.size }
    return (0 until parts).map { p -> faces.filter { partOf.getValue(it.ref.contentKey) <= p } }
  }

  /** Alcuni contenuti spariti a caso (E-F39), da [faces]. */
  fun gone(faces: List<GroupFace>, random: Random, share: Float): Set<ContentKey> =
    faces.map { it.ref.contentKey }.distinct().filter { random.nextFloat() < share }.toSet()

  /** Lo stesso archivio ricalcolato (E-F24): stessi posti, impronte dei corpi nuove, vettori un po' diversi. */
  fun recomputed(faces: List<GroupFace>, truth: Map<FaceRef, Int>, seed: Long): Pair<List<GroupFace>, Map<FaceRef, Int>> {
    val identities = SyntheticIdentities(seed)
    val out = faces.map { f ->
      val ref = FaceRef(f.ref.contentKey, f.ref.frame, f.ref.index, f.ref.bodyDigest + 7)
      val noise = if (f.tier == FaceTier.CORE) 0.9f else 1.4f
      GroupFace(ref, f.locator, f.tier, mix(f.vector(), identities.vector(truth.getValue(f.ref), 77_000L + f.ref.index + f.ref.contentKey.short, noise)))
    }
    return out to out.associate { it.ref to truth.getValue(FaceRef(it.ref.contentKey, it.ref.frame, it.ref.index, it.ref.bodyDigest - 7)) }
  }

  /** Un vettore a meta' fra due (normalizzato): il "nuovo modello" vede la stessa persona in modo simile ma non uguale. */
  private fun mix(a: FloatArray, b: FloatArray): FloatArray = SyntheticIdentities.normalize(FloatArray(a.size) { 0.8f * a[it] + 0.2f * b[it] })

  /**
   * [count] correzioni a caso sopra [previous], derivate dallo stato [state] e dai volti [faces]: "E' …" (a una persona esistente o nuova),
   * "Non e' …", "Ignora", "Unisci" (alias), "Dimentica", "Separati", nomi, "Dividi" (persona nata, volti fissati, separate) e lapidi di
   * "E' …" (F1). Gli id nuovi vengono da [ids] (non quelli del nucleo).
   */
  fun corrections(
    previous: GroupingCorrections,
    state: GroupingState,
    faces: List<GroupFace>,
    random: Random,
    ids: PersonIdSource,
    count: Int,
  ): GroupingCorrections {
    var c = previous
    val active = state.persons.values.filter { it.state == PersonState.ACTIVE }.map { it.id }.sorted()
    val live = state.assignments.values.filter { it.origin != AssignOrigin.IGNORED }.sortedBy { it.face }
    if (faces.isEmpty()) return c
    repeat(count) {
      when (random.nextInt(11)) {
        0 -> {
          val face = faces.random(random)
          val to = if (active.isNotEmpty() && random.nextInt(3) > 0) active.random(random) else ids.next().also { c = c.copy(named = c.named + it) }
          c = c.copy(fixed = c.fixed + (face.locator to to))
        }
        1 -> if (live.isNotEmpty()) {
          val a = live.random(random)
          c = c.copy(rejected = c.rejected + (a.locator to (c.rejected[a.locator].orEmpty() + a.person)))
        }
        2 -> c = c.copy(ignored = c.ignored + faces.random(random).locator)
        3 -> if (active.size >= 2) {
          val (a, b) = active.shuffled(random).take(2)
          c = c.copy(aliases = c.aliases + (a to b))
        }
        4 -> if (active.isNotEmpty() && random.nextInt(2) == 0) c = c.copy(forgotten = c.forgotten + active.random(random))
        5 -> if (active.size >= 2) {
          val (a, b) = active.shuffled(random).take(2)
          c = c.copy(apart = c.apart + PersonPair.of(a, b))
        }
        6 -> if (active.isNotEmpty()) c = c.copy(named = c.named + active.random(random))
        7 -> {
          val byPerson = live.groupBy { it.person }.filter { it.value.size >= 2 }
          if (byPerson.isNotEmpty()) {
            val (person, list) = byPerson.entries.sortedBy { it.key }.random(random)
            val fresh = ids.next()
            val chosen = list.shuffled(random).take(1 + random.nextInt(list.size - 1))
            c = c.copy(
              fixed = c.fixed + chosen.associate { it.locator to fresh },
              born = c.born + fresh,
              apart = c.apart + PersonPair.of(fresh, person),
            )
          }
        }
        8 -> if (c.fixed.isNotEmpty()) c = c.copy(fixed = c.fixed - c.fixed.keys.sortedBy { it.encode() }.random(random))
        9 -> if (live.isNotEmpty() && active.size >= 2) {
          val a = live.random(random)
          val other = active.filter { it != a.person }.random(random)
          c = c.copy(fixed = c.fixed + (a.locator to other))
        }
        else -> if (live.isNotEmpty()) {
          val a = live.random(random)
          c = c.copy(rejected = c.rejected + (a.locator to (c.rejected[a.locator].orEmpty() + a.person)))
        }
      }
    }
    return c
  }
}
