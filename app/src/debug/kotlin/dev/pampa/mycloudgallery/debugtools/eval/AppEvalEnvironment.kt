package dev.pampa.mycloudgallery.debugtools.eval

import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.ai.AiStack
import dev.pampa.mycloudgallery.core.ai.contract.TextEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.TextReader
import dev.pampa.mycloudgallery.core.ai.runtime.LiteRtLmTextEmbedder
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.MediaText
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.QueryParser
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.search.engine.LibraryPlaces
import dev.pampa.mycloudgallery.core.search.engine.QueryEmbedding
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.search.GeocoderPlaceLookup
import java.io.File

/**
 * SOLO debug (Fase 6, P11): la porta dello strumento di valutazione (P9) sopra il grafo della ricerca vero: lo stesso indice, lo stesso
 * motore, lo stesso motore delle query, il lettore dietro il cancello. Le miniature non ci sono (riquadro grigio): lo strumento le chiede
 * solo locali e la pipeline delle miniature e' della Libreria.
 */
internal class AppEvalEnvironment(private val stack: AiStack, private val gallery: GalleryGraph, noBackup: File) : EvalEnvironment {
  private val search = stack.search
  private val libraryPlaces = LibraryPlaces(GeocoderPlaceLookup(gallery.places))
  private val cacheRoot = File(File(noBackup, "models"), "cache")

  override val data: SearchData get() = search.data
  override val engine: SearchEngine get() = search.engine
  override val embedding: QueryEmbedding get() = search.embedding
  override val embedder: TextEmbedder?
    get() = if (stack.localModels.manager.states.value[ModelCatalog.QUERY_270M.id] is ModelState.Ready) {
      LiteRtLmTextEmbedder(stack.localModels.files, cacheRoot)
    } else {
      null
    }
  override val parser: QueryParser = ItalianQueryParser()
  override val today: Today get() = search.today

  override fun places(): PlaceDictionary {
    val snapshot = gallery.repository.snapshot.value ?: return PlaceDictionary(emptyList())
    return libraryPlaces.dictionary(snapshot)
  }

  override val mediaText: MediaText get() = search.mediaText

  override fun keyOf(id: MediaId): String? = search.keys[id]?.own?.firstOrNull()?.hex

  override suspend fun prepare() {
    search.keys.sync()
  }

  override suspend fun thumbnail(id: MediaId): ImageBitmap? = null

  override val textReader: TextReader? get() = if (stack.textSwitch.state.value.activeHere) stack.ai.parts.reader else null
}
