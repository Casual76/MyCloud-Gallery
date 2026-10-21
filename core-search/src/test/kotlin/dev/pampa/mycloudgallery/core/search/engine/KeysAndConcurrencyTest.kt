package dev.pampa.mycloudgallery.core.search.engine

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.CellContent
import dev.pampa.mycloudgallery.core.database.MemberContent
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.engine.EngineFixture.Companion.ids
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.core.search.index.ListCellKeys
import dev.pampa.mycloudgallery.core.search.index.LocalAiIndex
import dev.pampa.mycloudgallery.core.search.index.key
import dev.pampa.mycloudgallery.core.search.index.vecBody
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Richiesta di P1: le chiavi delle celle **a pagine** (mai la lettura intera), solo i media nuovi alla volta dopo, le celle cambiate
 * rilette (anche la copertina di un membro cambiato). E le ricerche insieme alle scritture e alla compattazione.
 */
class KeysAndConcurrencyTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun id(n: Int) = MediaId(UUID(0L, n.toLong() + 1).toString())

  @Test
  fun leChiaviSiLegonoAPagineEPoiSoloLeNovita() = runTest {
    val source = ListCellKeys((0 until 12_000).map { CellContent(it + 1L, id(it), listOf(key(it)), emptyList()) })
    val keys = CellKeys(source, pageSize = 5_000)
    assertThat(keys.sync()).isTrue()
    assertThat(source.pages).isEqualTo(3)
    assertThat(keys.size).isEqualTo(12_000)
    assertThat(keys.sync()).isFalse()
    assertThat(source.pages).isEqualTo(4)
    // Un media nuovo arriva con la pagina dopo l'ultimo rowid visto.
    source.cells += CellContent(20_000, id(20_000), listOf(key(20_000)), listOf(MemberContent(id(30_000), listOf(key(30_000)))))
    assertThat(keys.sync()).isTrue()
    assertThat(keys[id(20_000)]!!.members).hasSize(1)
    // Il membro si nasconde: rilette la sua copertina, che non lo porta piu'.
    source.replace(CellContent(20_000, id(20_000), listOf(key(20_000)), emptyList()))
    keys.invalidate(listOf(id(30_000)))
    keys.sync()
    assertThat(keys[id(20_000)]!!.members).isEmpty()
    // Una cella nascosta o eliminata: il catalogo non la da' piu', esce.
    source.cells.removeAll { it.mediaId == id(5) }
    keys.invalidate(listOf(id(5)))
    keys.sync()
    assertThat(keys[id(5)]).isNull()
  }

  @Test
  fun ricercheInsiemeAScrittureECompattazione() = runBlocking {
    val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = 9, count = 3_000))
    f.open()
    f.putAll((0 until 3_000).associate { it.toLong() to listOf(f.image(if (it % 50 == 0) "cane" else "albero", it.toLong())) })
    val dogs = (0 until 3_000 step 50).map { f.id(it) }
    val engine = f.engine()
    val writer = async(Dispatchers.Default) {
      repeat(15) { round ->
        f.index.applyReplica("comune", (1 until 3_000 step 7).filter { it % 50 != 0 }.map {
          LocalAiIndex.ReplicaItem.Vectors(key(it), "dev-$round", vecBody(f.image("albero", it * 100L + round)))
        })
        if (round % 5 == 4) f.index.maintain(force = true)
      }
    }
    var searches = 0
    while (!writer.isCompleted || searches < 10) {
      val found = ids(engine.search(SearchRequest("cane")).confident)
      assertThat(found).containsAtLeastElementsIn(dogs)
      searches++
    }
    writer.await()
    assertThat(f.repair.keys).isEmpty()
    assertThat(f.index.counters.compactions.get()).isEqualTo(3)
    f.index.close()
  }
}
