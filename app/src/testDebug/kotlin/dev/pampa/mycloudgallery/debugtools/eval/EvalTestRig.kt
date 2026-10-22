package dev.pampa.mycloudgallery.debugtools.eval

import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.ai.contract.TextEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.TextReader
import dev.pampa.mycloudgallery.core.ai.contract.TextVector
import dev.pampa.mycloudgallery.core.ai.testing.FakeConcepts
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextEmbedder
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.MediaText
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.QueryParser
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.search.engine.EmbedderQueryEmbedding
import dev.pampa.mycloudgallery.core.search.engine.QueryEmbedding
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import dev.pampa.mycloudgallery.core.search.testing.FakeMediaText
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.core.search.testing.FixedToday
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchEngine

/**
 * Il banco di prova dello strumento di valutazione: una Libreria sintetica da 300 celle con concetti noti (la cella `i` ha il concetto `i % 6`), il
 * catalogo e il motore di riferimento sugli stessi dati, e la chiave di contenuto della cella `i` come `i` in esadecimale a 64 cifre. Nessun dato
 * personale, nessuna query vera. `withoutKeyBelow` toglie la chiave alle prime celle (media senza chiave di contenuto).
 */
internal class EvalTestRig(withoutKeyBelow: Int = 0) : EvalEnvironment {
  val library = SyntheticLibrary(seed = 5, count = 300)
  val snapshot = library.snapshot
  val fakeData = FakeSearchData(snapshot)
  private val fixedToday = FixedToday()

  init {
    for (i in 0 until 300) fakeData.vectors[snapshot.idAt(i)] = listOf(FakeConcepts.mix(FakeConcepts.conceptsOf(CONCEPTS[i % CONCEPTS.size]), noise = 0.3f, noiseSeed = "i$i"))
  }

  private val keyless = (0 until withoutKeyBelow).map { snapshot.idAt(it) }.toSet()
  private val embedderValue = FakeTextEmbedder()

  override val data: SearchData = fakeData
  override val engine: SearchEngine = InMemorySearchEngine(fakeData, embedderValue, ItalianQueryParser(), fixedToday)
  override val embedding: QueryEmbedding = EmbedderQueryEmbedding(embedderValue)

  /** Il motore del testo di "Documenti", con il conto dei rilasci (DB-3): chi lo usa lo deve rilasciare. */
  val documentEmbedder = CountingEmbedder(embedderValue)
  override val embedder: TextEmbedder? = documentEmbedder
  override val parser: QueryParser = ItalianQueryParser()
  override val today: Today = fixedToday
  override fun places(): PlaceDictionary = PlaceDictionary.EMPTY
  override val mediaText: MediaText = FakeMediaText()
  override val textReader: TextReader? = null

  override fun keyOf(id: MediaId): String? = if (id in keyless) null else snapshot.indexOf(id).takeIf { it >= 0 }?.let { keyHex(it) }

  override suspend fun prepare() = Unit

  override suspend fun thumbnail(id: MediaId): ImageBitmap? = null

  /** Le chiavi delle celle con il concetto [concept]. */
  fun keysOf(concept: String): Set<String> = (0 until 300).filter { CONCEPTS[it % CONCEPTS.size] == concept }.map { keyHex(it) }.toSet()

  /** Un motore del testo che conta quante volte e' stato usato e rilasciato. */
  class CountingEmbedder(private val inner: TextEmbedder) : TextEmbedder by inner {
    var embeds: Int = 0
      private set
    var releases: Int = 0
      private set

    /** Se vero, la prossima frase non si legge (il motore si e' rotto a meta'). */
    var failing: Boolean = false

    override suspend fun embed(text: String): TextVector {
      check(!failing) { "motore del testo rotto" }
      embeds++
      return inner.embed(text)
    }

    override suspend fun release() {
      releases++
      inner.release()
    }
  }

  companion object {
    val CONCEPTS = listOf("cane", "gatto", "spiaggia", "documento", "scontrino", "auto")

    fun keyHex(i: Int): String = "%064x".format(i)
  }
}
