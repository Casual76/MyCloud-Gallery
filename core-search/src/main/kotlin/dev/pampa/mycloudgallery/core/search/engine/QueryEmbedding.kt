package dev.pampa.mycloudgallery.core.search.engine

import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.TextEmbedder
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Il motore delle query visto dalla ricerca (D35, E-Q11): il vettore della query o null se adesso non c'e' (modello assente, in
 * caricamento, guasto). Filtri e testo non lo aspettano mai. :app lo lega al 270M (caricato all'apertura di Cerca, rilasciato 3
 * minuti dopo l'uscita); lo stato "in caricamento" lo dice [readiness].
 */
interface QueryEmbedding {
  val readiness: StateFlow<SearchReadiness>

  suspend fun vector(text: String): FloatArray?
}

/**
 * La [QueryEmbedding] sopra un [TextEmbedder] (o nessuno: modello assente). Un errore del motore spegne la ricerca per contenuto per
 * questa query e lo dice ([SearchReadiness.FiltersAndTextOnly]); un vettore di un altro spazio o non finito non si usa mai.
 */
class EmbedderQueryEmbedding(
  private val embedder: TextEmbedder?,
  private val space: SpaceCode = SpaceCode.CURRENT,
) : QueryEmbedding {

  override val readiness: MutableStateFlow<SearchReadiness> =
    MutableStateFlow(if (embedder == null) SearchReadiness.FiltersAndTextOnly(modelMissing = true) else SearchReadiness.Ready)

  override suspend fun vector(text: String): FloatArray? {
    val engine = embedder ?: return null
    return try {
      val v = engine.embed(text)
      val values = v.values
      if (v.space != space.space || values.size != space.space.dimension || values.any { !it.isFinite() }) return null
      readiness.value = SearchReadiness.Ready
      values
    } catch (e: AiEngineException) {
      readiness.value = SearchReadiness.FiltersAndTextOnly(modelMissing = false)
      null
    }
  }
}
