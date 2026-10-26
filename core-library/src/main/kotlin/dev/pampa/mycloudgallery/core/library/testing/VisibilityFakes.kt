package dev.pampa.mycloudgallery.core.library.testing

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.contract.WidgetFeed
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fase 7 (P0, D22): la visibilita' in memoria dei test, fedele al contratto. [present] sono i media del catalogo, [hidden] quelli nascosti
 * e [groups] i membri di ogni gruppo (il nascosto e' contagioso: un membro di un gruppo con un nascosto e' nascosto). [snapshot] la decide
 * chi costruisce la Libreria finta (che i nascosti non li ha). Nessun thread: le modifiche valgono alla chiamata dopo.
 */
class FakeVisibility(
  snapshot: TimelineSnapshot? = null,
  present: Set<MediaId> = emptySet(),
  hidden: Set<MediaId> = emptySet(),
  groups: List<Set<MediaId>> = emptyList(),
) : VisibleMedia, HiddenMask {

  override val snapshot: MutableStateFlow<TimelineSnapshot?> = MutableStateFlow(snapshot)

  val present: MutableSet<MediaId> = present.toMutableSet()
  val hidden: MutableSet<MediaId> = hidden.toMutableSet()
  val groups: MutableList<Set<MediaId>> = groups.toMutableList()

  /** Le chiamate a [hiddenAmong]: un test controlla che la superficie abbia applicato il filtro finale. */
  var maskCalls: Int = 0
    private set

  private fun hiddenNow(id: MediaId): Boolean = id in hidden || groups.any { id in it && it.any { member -> member in hidden } }

  override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> = ids.filter { it in present && !hiddenNow(it) }.toSet()

  override suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> {
    maskCalls++
    return ids.filter { hiddenNow(it) }.toSet()
  }
}

/**
 * Il widget finto (D22): a giro sulle celle dell'istantanea di [visibility], dalla piu' recente, con il filtro finale di [mask]. Mai un
 * nascosto: e' la regola che il widget di prova di P9 e quello vero della Fase 10 devono rispettare.
 */
class FakeWidgetFeed(private val visibility: VisibleMedia, private val mask: HiddenMask) : WidgetFeed {
  private var cursor = 0

  override suspend fun next(size: Int): List<MediaId> {
    val snap = visibility.snapshot.value ?: return emptyList()
    if (snap.size == 0 || size <= 0) return emptyList()
    val want = size.coerceAtMost(WidgetFeed.MAX_SIZE).coerceAtMost(snap.size)
    val picked = ArrayList<MediaId>(want)
    var steps = 0
    while (picked.size < want && steps < snap.size) {
      picked += snap.idAt(cursor % snap.size)
      cursor = (cursor + 1) % snap.size
      steps++
    }
    val hiddenNow = mask.hiddenAmong(picked)
    val visibleNow = visibility.visible(picked)
    return picked.filter { it !in hiddenNow && it in visibleNow }
  }
}
