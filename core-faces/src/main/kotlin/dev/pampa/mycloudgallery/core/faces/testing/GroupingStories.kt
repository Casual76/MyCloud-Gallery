package dev.pampa.mycloudgallery.core.faces.testing

import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentities
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentity
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PersonIdSource
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId
import java.security.MessageDigest
import java.util.Random

/**
 * Le STORIE SINTETICHE del raggruppamento (docs/fase8-progetto.md §5.1, §5.2, "Dati senza persone vere"): foto con volti di identita'
 * sintetiche ([SyntheticIdentities] di core-ai), con la **verita'** (chi e' ogni volto). Nessuna immagine, nessun dato personale: solo
 * vettori. P5 le allarga (gemelli, eta', foto di gruppo, 500 storie); qui le basi per la suite `GroupingContract` (test di core-faces) e
 * per l'esecutore di riferimento.
 */
object GroupingStories {

  /** Una storia: i volti (in ordine di riferimento) e l'identita' vera di ognuno. */
  class Story(val faces: List<GroupFace>, val truth: Map<FaceRef, Int>) {
    override fun toString(): String = "Story(${faces.size} volti, ${truth.values.toSet().size} identita')"
  }

  /** Un generatore di id deterministico (gli id nuovi delle persone, D22, uguali a ogni corsa con lo stesso [seed]). */
  fun ids(seed: Long): PersonIdSource {
    val random = Random(seed)
    return PersonIdSource { PersonId.fromBytes(ByteArray(PersonId.BYTES).also { random.nextBytes(it) }) }
  }

  /** La chiave di contenuto della foto [photo] della storia [seed]. */
  fun contentKey(seed: Long, photo: Int): ContentKey {
    val digest = MessageDigest.getInstance("SHA-256").digest("mcg-storia-$seed-$photo".toByteArray())
    return ContentKey(digest.joinToString("") { "%02x".format(it.toInt() and 0xff) })
  }

  /** Il volto [index] (0..7) della foto [photo]: riquadri affiancati che non si sovrappongono. */
  fun face(
    identities: SyntheticIdentities,
    seed: Long,
    photo: Int,
    index: Int,
    identity: Int,
    tier: FaceTier = FaceTier.CORE,
    noise: Float = if (tier == FaceTier.CORE) SyntheticIdentities.CORE_NOISE else SyntheticIdentities.WEAK_NOISE,
    ageYears: Float = 0f,
    frame: Int = 0,
  ): GroupFace {
    require(index in 0..7) { "Al piu' 8 volti per foto nelle storie." }
    val key = contentKey(seed, photo)
    val x0 = 1_000 + index * 8_000
    val box = FaceBox(x0, 10_000, x0 + 7_000, 20_000)
    val ref = FaceRef(key, frame, index, bodyDigest = seed * 31 + photo)
    val vector = identities.vector(identity, sample = seed * 1_000_003 + photo * 64L + index, noise = noise, ageYears = ageYears)
    return GroupFace(ref, FaceLocator(key, frame, box), tier, vector)
  }

  /**
   * Una storia a caso: [identities] identita' (le prime [twins] coppie sono gemelli, con i centri a coseno 0,7), [photos] foto con da 1 a
   * [maxFacesPerPhoto] volti di identita' diverse (mai la stessa identita' due volte in una foto), una parte [weakShare] di volti WEAK.
   */
  fun story(
    seed: Long,
    identities: Int,
    photos: Int,
    maxFacesPerPhoto: Int = 3,
    weakShare: Float = 0f,
    twins: Int = 0,
  ): Story {
    require(identities >= 1 && maxFacesPerPhoto in 1..8 && maxFacesPerPhoto <= identities) { "Storia non valida." }
    val generator = SyntheticIdentities(seed)
    for (t in 0 until twins) generator.define(SyntheticIdentity(id = 2 * t + 1, twinOf = 2 * t, twinCosine = 0.7f))
    val random = Random(seed xor 0x5DEECE66DL)
    val faces = ArrayList<GroupFace>()
    val truth = HashMap<FaceRef, Int>()
    for (photo in 0 until photos) {
      val count = 1 + random.nextInt(maxFacesPerPhoto)
      val who = (0 until identities).shuffled(random).take(count)
      for ((index, identity) in who.withIndex()) {
        val tier = if (random.nextFloat() < weakShare) FaceTier.WEAK else FaceTier.CORE
        val face = face(generator, seed, photo, index, identity, tier)
        faces += face
        truth[face.ref] = identity
      }
    }
    return Story(faces.sortedBy { it.ref }, truth)
  }

  /** La purezza (§5.3): la parte delle persone attive con almeno un volto in cui tutti i volti sono della stessa identita'. */
  fun purity(state: GroupingState, truth: Map<FaceRef, Int>): Double {
    val byPerson = state.assignments.values.filter { it.origin != AssignOrigin.IGNORED }.groupBy { it.person }
    if (byPerson.isEmpty()) return 1.0
    val pure = byPerson.values.count { list -> list.mapNotNull { truth[it.face] }.toSet().size <= 1 }
    return pure.toDouble() / byPerson.size
  }

  /** La copertura: la parte dei volti CORE della storia che stanno in una persona. */
  fun coverage(state: GroupingState, story: Story): Double {
    val core = story.faces.filter { it.tier == FaceTier.CORE }
    if (core.isEmpty()) return 1.0
    val assigned = core.count { state.assignments[it.ref]?.origin?.let { o -> o != AssignOrigin.IGNORED } == true }
    return assigned.toDouble() / core.size
  }
}
