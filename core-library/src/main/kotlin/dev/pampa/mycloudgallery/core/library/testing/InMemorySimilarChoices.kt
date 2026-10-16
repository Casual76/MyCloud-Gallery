package dev.pampa.mycloudgallery.core.library.testing

import dev.pampa.mycloudgallery.core.library.similar.MediaPair
import dev.pampa.mycloudgallery.core.library.similar.SeparatedPairs
import dev.pampa.mycloudgallery.core.library.similar.SimilarChoices
import dev.pampa.mycloudgallery.core.model.MediaId

/** Fase 5A (P15): le scelte dell'utente in memoria, per i test del rilevatore e del viewer. Nessuna sincronizzazione. */
class InMemorySimilarChoices : SimilarChoices {

  private val covers = LinkedHashMap<MediaId, Long>()
  private val apart = LinkedHashSet<MediaPair>()
  private var clock = 0L

  /** Le copertine scelte in questo momento, la piu' recente per ultima. */
  val chosen: List<MediaId> get() = synchronized(this) { covers.entries.sortedBy { it.value }.map { it.key } }

  /** Le coppie separate. */
  val separations: Set<MediaPair> get() = synchronized(this) { apart.toSet() }

  /** Quante volte e' stata scritta una scelta (per i test di "una scelta alla volta"). */
  var writes: Int = 0
    private set

  override suspend fun chosenCover(members: Collection<MediaId>): MediaId? = synchronized(this) {
    members.filter { it in covers }.maxByOrNull { covers.getValue(it) }
  }

  override suspend fun separatedPairs(ids: Collection<MediaId>): SeparatedPairs = synchronized(this) {
    val set = ids.toSet()
    SeparatedPairs(apart.filter { it.low in set && it.high in set })
  }

  override suspend fun saveCover(chosen: MediaId, others: Collection<MediaId>) = synchronized(this) {
    writes++
    others.forEach { covers.remove(it) }
    covers[chosen] = ++clock
    Unit
  }

  override suspend fun saveSeparation(member: MediaId, others: Collection<MediaId>) = synchronized(this) {
    writes++
    others.filter { it != member }.forEach { apart += MediaPair(member, it) }
    Unit
  }

  /** Toglie una separazione ("di nuovo unibili"), come farebbe una lapide arrivata da un altro dispositivo. */
  fun allowAgain(a: MediaId, b: MediaId) = synchronized(this) {
    apart -= MediaPair(a, b)
    Unit
  }
}
