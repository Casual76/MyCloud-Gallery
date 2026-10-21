package dev.pampa.mycloudgallery.core.search.engine

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.EmbeddingSpace
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.core.search.index.LocalAiIndex
import kotlinx.coroutines.flow.StateFlow

/**
 * Il contratto [SearchData] di P0 sopra l'indice locale: istantanea e nascosti da [base] (:app), vettori e testo dalle celle e
 * dall'indice con le **stesse** regole della mappa delle celle (per membro la versione corrente, o la piu' recente con il dato:
 * E-A10). Serve all'esecutore di riferimento (`InMemorySearchEngine`) per la parita' sugli stessi dati, e a chi vuole i vettori di
 * una cella (P9).
 */
class IndexSearchData(
  private val base: SearchData,
  private val keys: CellKeys,
  private val index: LocalAiIndex,
) : SearchData {

  override val snapshot: StateFlow<TimelineSnapshot?> get() = base.snapshot

  override suspend fun vectors(ids: Collection<MediaId>, space: EmbeddingSpace): Map<MediaId, List<FloatArray>> {
    if (space != index.space.space) return emptyMap()
    keys.sync()
    val out = HashMap<MediaId, List<FloatArray>>()
    for (id in ids) {
      val cell = keys[id] ?: continue
      val list = members(cell.own, cell.members.map { it.keys }).flatMap { versions ->
        versions.firstOrNull { index.vectors(it).isNotEmpty() }?.let { index.vectors(it) }.orEmpty()
      }
      if (list.isNotEmpty()) out[id] = list
    }
    return out
  }

  override suspend fun texts(ids: Collection<MediaId>): Map<MediaId, String> {
    keys.sync()
    val out = HashMap<MediaId, String>()
    for (id in ids) {
      val cell = keys[id] ?: continue
      val parts = members(cell.own, cell.members.map { it.keys }).mapNotNull { versions ->
        versions.firstOrNull { index.text(it) != null }?.let { index.text(it) }
      }
      if (parts.isNotEmpty()) out[id] = parts.joinToString(" ")
    }
    return out
  }

  override suspend fun hidden(ids: Collection<MediaId>): Set<MediaId> = base.hidden(ids)

  private fun members(own: List<ContentKey>, others: List<List<ContentKey>>): List<List<ContentKey>> = listOf(own) + others
}
