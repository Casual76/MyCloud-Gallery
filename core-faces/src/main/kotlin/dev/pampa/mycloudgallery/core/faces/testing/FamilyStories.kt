package dev.pampa.mycloudgallery.core.faces.testing

import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentities
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticIdentity
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId
import java.util.Random
import kotlin.math.pow

/**
 * LE FAMIGLIE SINTETICHE del raggruppamento (Fase 8 P5; docs/fase8-progetto.md §2.5 "Prove dell'algoritmo", §5.2 "Dati senza persone
 * vere"): archivi finti con la **verita'** (chi e' ogni volto), piu' vicini a una Libreria di famiglia delle storie di base di
 * [GroupingStories]. Solo vettori a 128 dimensioni di identita' sintetiche ([SyntheticIdentities] di core-ai): **nessuna immagine, nessun
 * volto, nessun nome di persone vere**. In src/main/testing per i test di P5, P9, P11, P12; mai nella release (tools/check.ps1).
 *
 * Cosa c'e' in una storia ([Config]):
 * - persone ricorrenti con popolarita' diversa (alcune in molte foto, altre in poche: legge di Zipf con esponente [Config.popularity]);
 * - **fratelli** (centri a coseno [Config.siblingCosine]) e **gemelli** (centri a [Config.twinCosine]): E-F1;
 * - **eta'**: ogni foto ha un'eta' in [0, [Config.years]] anni; i **bambini** ([Config.children]) cambiano in fretta (0,08 l'anno), gli adulti
 *   poco (0,01): E-F2;
 * - **foto di gruppo** da 3 a 8 volti ([Config.groupShare]) e foto con da 1 a [Config.maxFacesPerPhoto] volti;
 * - **volti deboli** (WEAK, rumore 1,4: piccoli, sfocati, di profilo): E-F13;
 * - **sconosciuti** sullo sfondo (un volto in una sola foto, identita' sempre nuova);
 * - **video** con da 2 a 4 fotogrammi, volti WEAK (E-F17);
 * - la **stessa persona due volte** nella stessa foto (specchio, collage): E-F15;
 * - un **poster** (lo stesso volto, quasi identico, in molte foto: una TV, un quadro): E-F3.
 */
object FamilyStories {

  data class Config(
    val seed: Long,
    val identities: Int = 12,
    val photos: Int = 160,
    val maxFacesPerPhoto: Int = 3,
    val groupShare: Float = 0.15f,
    val weakShare: Float = 0.15f,
    val siblings: Int = 2,
    val siblingCosine: Float = 0.6f,
    val twins: Int = 0,
    val twinCosine: Float = 0.8f,
    val years: Float = 0f,
    val children: Int = 0,
    val strangerShare: Float = 0.1f,
    val videoShare: Float = 0.04f,
    val mirrorShare: Float = 0.01f,
    val posterShare: Float = 0f,
    val popularity: Float = 0.8f,
  ) {
    init {
      require(identities >= 2 && photos >= 1 && maxFacesPerPhoto in 1..8) { "Storia non valida." }
      require(2 * (siblings + twins) <= identities) { "Troppe coppie per le identita'." }
    }
  }

  /** L'identita' vera di uno sconosciuto (sempre nuova) parte da qui; il poster e' [POSTER]. */
  const val STRANGERS_FROM: Int = 1_000_000
  const val POSTER: Int = 999_999

  /** Una storia: i volti (in ordine di riferimento), la verita' e l'insieme delle identita' ricorrenti (non sconosciuti). */
  class Story(val faces: List<GroupFace>, val truth: Map<FaceRef, Int>, val recurring: Set<Int>) {
    fun asBasic(): GroupingStories.Story = GroupingStories.Story(faces, truth)

    override fun toString(): String = "FamilyStory(${faces.size} volti, ${recurring.size} ricorrenti)"
  }

  fun story(config: Config): Story {
    val generator = SyntheticIdentities(config.seed)
    var next = 0
    for (s in 0 until config.siblings) {
      generator.define(SyntheticIdentity(id = next + 1, twinOf = next, twinCosine = config.siblingCosine, driftPerYear = ADULT_DRIFT))
      generator.define(SyntheticIdentity(id = next, driftPerYear = ADULT_DRIFT))
      next += 2
    }
    for (t in 0 until config.twins) {
      generator.define(SyntheticIdentity(id = next + 1, twinOf = next, twinCosine = config.twinCosine, driftPerYear = ADULT_DRIFT))
      generator.define(SyntheticIdentity(id = next, driftPerYear = ADULT_DRIFT))
      next += 2
    }
    // I bambini sono le ultime identita' (non fratelli ne' gemelli, per non mescolare le due prove).
    for (id in next until config.identities) {
      val child = id >= config.identities - config.children
      generator.define(SyntheticIdentity(id = id, driftPerYear = if (child) CHILD_DRIFT else ADULT_DRIFT))
    }
    val random = Random(config.seed xor 0x2F4B_19A3_77C1L)
    val weights = DoubleArray(config.identities) { 1.0 / (it + 1.0).pow(config.popularity.toDouble()) }
    val faces = ArrayList<GroupFace>()
    val truth = HashMap<FaceRef, Int>()
    var strangers = 0
    for (photo in 0 until config.photos) {
      val key = GroupingStories.contentKey(config.seed, photo)
      val age = random.nextFloat() * config.years
      val video = random.nextFloat() < config.videoShare
      val count = when {
        video -> 1 + random.nextInt(2)
        random.nextFloat() < config.groupShare -> 3 + random.nextInt(6)
        else -> 1 + random.nextInt(config.maxFacesPerPhoto)
      }
      val who = pick(random, weights, minOf(count, config.identities)).toMutableList()
      if (!video && who.size < 8 && random.nextFloat() < config.mirrorShare) who += who.first()
      if (!video && who.size < 8 && random.nextFloat() < config.posterShare) who += POSTER
      while (!video && who.size < 8 && random.nextFloat() < config.strangerShare) who += STRANGERS_FROM + strangers++
      val frames = if (video) 2 + random.nextInt(3) else 1
      var index = 0
      for (frame in 0 until frames) {
        for ((slot, identity) in who.withIndex()) {
          if (index >= 8) break
          val weak = video || random.nextFloat() < config.weakShare
          val noise = when {
            identity == POSTER -> POSTER_NOISE
            weak -> SyntheticIdentities.WEAK_NOISE
            else -> SyntheticIdentities.CORE_NOISE
          }
          val ref = FaceRef(key, frame, index, bodyDigest = config.seed * 31 + photo)
          val x0 = 1_000 + slot * 8_000
          val locator = FaceLocator(key, frame, FaceBox(x0, 10_000, x0 + 7_000, 20_000))
          val vector = generator.vector(identity, sample = config.seed * 1_000_003 + photo * 64L + index, noise = noise, ageYears = age)
          faces += GroupFace(ref, locator, if (weak) FaceTier.WEAK else FaceTier.CORE, vector)
          truth[ref] = identity
          index++
        }
      }
    }
    return Story(faces.sortedBy { it.ref }, truth, (0 until config.identities).toSet())
  }

  /** [count] identita' diverse estratte con i pesi [weights] (senza ripetizioni). */
  private fun pick(random: Random, weights: DoubleArray, count: Int): List<Int> {
    val left = weights.copyOf()
    val out = ArrayList<Int>(count)
    repeat(count) {
      val total = left.sum()
      var r = random.nextDouble() * total
      var chosen = left.indices.last { left[it] > 0 }
      for (i in left.indices) {
        if (left[i] <= 0) continue
        r -= left[i]
        if (r <= 0) {
          chosen = i
          break
        }
      }
      out += chosen
      left[chosen] = 0.0
    }
    return out
  }

  /** La deriva l'anno di un adulto e di un bambino (E-F2). */
  const val ADULT_DRIFT: Float = 0.01f
  const val CHILD_DRIFT: Float = 0.08f

  /** Il poster e' sempre la stessa immagine: rumore basso (cambiano solo luce e angolo). */
  const val POSTER_NOISE: Float = 0.35f

  // --- Le misure (§5.3): purezza, copertura, frammentazione, errori per volto ---------------------------------------------------------

  /** I volti vivi (automatici o confermati) di ogni persona. */
  fun groups(state: GroupingState): Map<PersonId, List<FaceRef>> =
    state.assignments.values.filter { it.origin != AssignOrigin.IGNORED }.groupBy({ it.person }, { it.face })

  /** La purezza dei gruppi (§5.3): la parte delle persone con almeno un volto in cui tutti i volti sono della stessa identita'. */
  fun purity(state: GroupingState, truth: Map<FaceRef, Int>): Double = GroupingStories.purity(state, truth)

  /** I volti nella persona sbagliata: per ogni persona quelli che non sono della sua identita' piu' frequente, su tutti i volti assegnati. */
  fun faceErrors(state: GroupingState, truth: Map<FaceRef, Int>): Double {
    var wrong = 0
    var total = 0
    for ((_, refs) in groups(state)) {
      val ids = refs.mapNotNull { truth[it] }
      val major = ids.groupingBy { it }.eachCount().maxByOrNull { it.value }?.value ?: 0
      wrong += ids.size - major
      total += ids.size
    }
    return if (total == 0) 0.0 else wrong.toDouble() / total
  }

  /**
   * La copertura dei volti CORE "raggruppabili": quelli delle identita' che hanno almeno 3 volti CORE in almeno 2 foto (gli sconosciuti di
   * una foto sola non possono mai fare una persona). Il §5.3 vuole almeno il 50% dei volti CORE.
   */
  fun coverableCoverage(state: GroupingState, story: Story): Double {
    val core = story.faces.filter { it.tier == FaceTier.CORE }
    val byIdentity = core.groupBy { story.truth.getValue(it.ref) }
    val coverable = byIdentity.filter { (_, fs) -> fs.size >= 3 && fs.map { it.ref.contentKey }.distinct().size >= 2 }.values.flatten()
    if (coverable.isEmpty()) return 1.0
    val assigned = coverable.count { state.assignments[it.ref]?.origin?.let { o -> o != AssignOrigin.IGNORED } == true }
    return assigned.toDouble() / coverable.size
  }

  /** La frammentazione: quante persone ha in media un'identita' ricorrente che ne ha almeno una (1,0 = nessuna divisa). */
  fun fragmentation(state: GroupingState, story: Story): Double {
    val personsOf = HashMap<Int, MutableSet<Any>>()
    for ((person, refs) in groups(state)) {
      val ids = refs.mapNotNull { story.truth[it] }
      val major = ids.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: continue
      if (major in story.recurring) personsOf.getOrPut(major) { HashSet() } += person
    }
    return if (personsOf.isEmpty()) 1.0 else personsOf.values.sumOf { it.size }.toDouble() / personsOf.size
  }
}
