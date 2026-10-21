package dev.pampa.mycloudgallery.core.search.engine

import dev.pampa.mycloudgallery.core.ai.testing.FakeConcepts
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextEmbedder
import dev.pampa.mycloudgallery.core.database.CellContent
import dev.pampa.mycloudgallery.core.database.MemberContent
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceName
import dev.pampa.mycloudgallery.core.search.coverage.CoverageTracker
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.core.search.index.InMemoryTextRows
import dev.pampa.mycloudgallery.core.search.index.ListCellKeys
import dev.pampa.mycloudgallery.core.search.index.LocalAiIndex
import dev.pampa.mycloudgallery.core.search.index.RecordingRepair
import dev.pampa.mycloudgallery.core.search.index.key
import dev.pampa.mycloudgallery.core.search.index.ocrBody
import dev.pampa.mycloudgallery.core.search.index.vecBody
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.core.search.testing.FixedToday
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchEngine
import java.io.File

/**
 * Il banco del motore (P6): una Libreria sintetica, le sue celle con le chiavi di contenuto (la cella `i` ha la chiave `i`, i membri dei
 * gruppi chiavi da 1.000.000), l'indice locale vero su file, il motore e l'esecutore di riferimento sugli **stessi** dati.
 */
internal class EngineFixture(dir: File, val library: SyntheticLibrary = SyntheticLibrary(seed = 6, count = 600, placeIds = PLACE_IDS)) {
  val snapshot = library.snapshot
  val data = FakeSearchData(snapshot)
  val texts = InMemoryTextRows()
  val repair = RecordingRepair()
  val index = LocalAiIndex(dir, texts, repair)
  val today = FixedToday()

  /** I membri di gruppo di ogni copertina (posizione -> id dei membri). */
  val members: Map<Int, List<MediaId>> = run {
    val byGroup = library.groupMembers.groupBy { it.groupId }
    (0 until snapshot.size).mapNotNull { i -> library.cellAt(i).groupId?.let { g -> i to byGroup[g].orEmpty().map { it.id } } }.toMap()
  }
  val memberKeys: Map<MediaId, Long> = members.values.flatten().mapIndexed { j, id -> id to 1_000_000L + j }.toMap()

  val cells = ListCellKeys((0 until snapshot.size).map { cellOf(it) })
  val keys = CellKeys(cells)
  val places = LibraryPlaces(PlaceLookup { id -> PLACES[id] })

  fun cellOf(i: Int, own: List<Long> = listOf(i.toLong())): CellContent = CellContent(
    seq = i + 1L, mediaId = snapshot.idAt(i), own = own.map { key(it) },
    members = members[i].orEmpty().map { MemberContent(it, listOf(key(memberKeys.getValue(it)))) },
  )

  suspend fun open() {
    index.open()
  }

  /** Il vettore "con questi concetti" della cella o del membro [seed] (come il motore finto delle immagini, rumore per immagine). */
  fun image(concepts: String, seed: Long): FloatArray = FakeConcepts.mix(FakeConcepts.conceptsOf(concepts), noise = 0.3f, noiseSeed = "img$seed")

  suspend fun putImage(keyId: Long, concepts: String) {
    index.saveVectors(key(keyId), vecBody(image(concepts, keyId)))
  }

  suspend fun putVideo(keyId: Long, vararg frames: String) {
    index.saveVectors(key(keyId), vecBody(*frames.mapIndexed { f, c -> image(c, keyId * 16 + f) }.toTypedArray()))
  }

  /** Molti vettori in una consegna della replica (una scrittura sola: i test grandi non pagano un `force` per media). */
  suspend fun putAll(vectors: Map<Long, List<FloatArray>>, texts: Map<Long, String> = emptyMap()) {
    for (chunk in vectors.entries.chunked(2_000)) {
      index.applyReplica("comune", chunk.map { (k, v) -> LocalAiIndex.ReplicaItem.Vectors(key(k), "dev-a", vecBody(*v.toTypedArray())) })
    }
    if (texts.isNotEmpty()) {
      index.applyReplica("comune", texts.map { (k, t) -> LocalAiIndex.ReplicaItem.Text(key(k), "dev-a", ocrBody(t)) })
    }
  }

  suspend fun putText(keyId: Long, text: String) {
    index.saveText(key(keyId), ocrBody(text))
  }

  fun engine(embedder: FakeTextEmbedder? = FakeTextEmbedder(), coverage: CoverageTracker = CoverageTracker()) =
    LocalSearchEngine(data, index, keys, texts, EmbedderQueryEmbedding(embedder), today, places, coverageTracker = coverage)

  fun reference(embedder: FakeTextEmbedder? = FakeTextEmbedder()) =
    InMemorySearchEngine(IndexSearchData(data, keys, index), embedder, ItalianQueryParser(), today, { places.dictionary(snapshot) })

  fun id(i: Int): MediaId = snapshot.idAt(i)

  companion object {
    val PLACE_IDS = intArrayOf(101, 102, 103, 104)
    val PLACES = mapOf(
      101 to PlaceName("Firenze", "Toscana", "Italia"),
      102 to PlaceName("Pisa", "Toscana", "Italia"),
      103 to PlaceName("Roma", "Lazio", "Italia"),
      104 to PlaceName("Monaco di Baviera", "Baviera", "Germania"),
      // Solo nei nascosti: non deve mai comparire.
      999 to PlaceName("Segretopoli", "Nascosta", "Italia"),
    )

    fun ids(view: TimelineView): List<MediaId> = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }

    fun positions(view: TimelineView): List<Int> = (0 until view.size).map { view.snapshotIndex(it) }
  }
}
