package dev.pampa.mycloudgallery.core.search.engine

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.ai.testing.FakeConcepts
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.engine.EngineFixture.Companion.positions
import dev.pampa.mycloudgallery.core.search.index.TypoIndex
import java.util.SplittableRandom
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * La PARITA' del §5.2: sulle stesse celle e sugli stessi vettori (letti dall'indice vero) il motore e l'esecutore di riferimento a
 * forza bruta (`InMemorySearchEngine`) danno la **stessa classe** (sicuro, meno probabile, fuori) per ogni cella e lo **stesso
 * ordine** in "Recenti" e "Pertinenti", su 1.000 query con i motori finti (contenuto, testo, tipi, anni, frasi esatte, video, gruppi,
 * celle senza vettore). Poi lo stesso sopra la soglia della seconda passata (6.000 celle: prima passata, campione, rilettura) e per
 * "Trova simili".
 */
class EngineParityTest {
  @get:Rule val tmp = TemporaryFolder()

  private val concepts = listOf("cane", "gatto", "spiaggia", "montagna", "tramonto", "torta", "auto", "bicicletta", "fiore", "albero", "pizza", "bambino")
  private val queryWords = concepts + listOf("cagnolino", "mare", "neve", "macchina", "bici", "rose", "bosco", "dolce", "cuccioli", "gattini")
  private val textWords = listOf("esselunga", "biglietto", "ferroviario", "promemoria", "lavatrice", "condominio", "bolletta", "assicurazione")

  private suspend fun fill(f: EngineFixture) {
    val vectors = HashMap<Long, List<FloatArray>>()
    val texts = HashMap<Long, String>()
    for (i in 0 until f.snapshot.size) {
      val r = SplittableRandom(i * 31L + 7)
      if (r.nextInt(100) < 5) continue // in attesa: nessun vettore
      fun mix() = (0 until 1 + r.nextInt(2)).joinToString(" ") { concepts[r.nextInt(concepts.size)] }
      vectors[i.toLong()] = if (f.snapshot.kindAt(i) == MediaKind.VIDEO) List(3) { f.image(mix(), i * 16L + it) } else listOf(f.image(mix(), i.toLong()))
      if (r.nextInt(100) < 12) texts[i.toLong()] = (0 until 2 + r.nextInt(3)).joinToString(" ") { textWords[r.nextInt(textWords.size)] }
    }
    for ((id, k) in f.memberKeys) vectors[k] = listOf(f.image(concepts[(k % concepts.size).toInt()], k))
    f.putAll(vectors, texts)
  }

  private fun query(r: SplittableRandom): String {
    fun w() = queryWords[r.nextInt(queryWords.size)]
    fun t() = textWords[r.nextInt(textWords.size)]
    return when (r.nextInt(20)) {
      in 0..10 -> if (r.nextBoolean()) w() else "${w()} ${w()}"
      in 11..13 -> if (r.nextBoolean()) t() else "${t()} ${t()}"
      14 -> "${w()} ${t()}"
      15 -> "video ${w()}"
      16 -> "${w()} 2024"
      17 -> "\"${t()} ${t()}\""
      18 -> "screenshot ${t()}"
      else -> "${w()} in ${w()}"
    }
  }

  private suspend fun compare(f: EngineFixture, queries: Int, seed: Long) {
    // Precondizione: le parole del testo non sono refusi delle parole cercate (l'esecutore di riferimento non corregge refusi).
    for (a in queryWords + textWords) for (b in textWords) if (a != b) assertThat(TypoIndex.distance(a, b)).isGreaterThan(2)
    val engine = f.engine()
    val reference = f.reference()
    val r = SplittableRandom(seed)
    var nonEmpty = 0
    repeat(queries) { n ->
      val text = query(r)
      val order = if (n % 2 == 0) ResultOrder.RECENT else ResultOrder.RELEVANT
      val mine = engine.search(SearchRequest(text, order = order))
      val ref = reference.search(SearchRequest(text, order = order))
      assertWithMessage("sicuri di «$text» ($order)").that(positions(mine.confident)).containsExactlyElementsIn(positions(ref.confident)).inOrder()
      assertWithMessage("meno probabili di «$text» ($order)").that(positions(mine.likely)).containsExactlyElementsIn(positions(ref.likely)).inOrder()
      if (mine.confident.size > 0) nonEmpty++
    }
    // Le query trovano davvero qualcosa (la parita' su risultati vuoti non dimostrerebbe niente).
    assertThat(nonEmpty).isGreaterThan(queries / 2)
  }

  @Test
  fun milleQueryStesseClassiEStessoOrdineDelRiferimento() = runTest {
    val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = 11, count = 2_000, placeIds = EngineFixture.PLACE_IDS))
    f.open()
    fill(f)
    compare(f, 1_000, 99)
  }

  @Test
  fun oltreLaSogliaDellaSecondaPassataLeClassiNonCambiano() = runTest {
    val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = 12, count = 6_000, placeIds = EngineFixture.PLACE_IDS))
    f.open()
    fill(f)
    compare(f, 200, 7)
  }

  @Test
  fun trovaSimiliComeIlRiferimento() = runTest {
    val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = 13, count = 1_500, placeIds = EngineFixture.PLACE_IDS))
    f.open()
    fill(f)
    val engine = f.engine()
    val reference = f.reference()
    var found = 0
    for (i in 0 until f.snapshot.size step 37) {
      for (order in ResultOrder.entries) {
        val mine = engine.similar(f.id(i), order)
        val ref = reference.similar(f.id(i), order)
        assertWithMessage("simili a $i").that(positions(mine.confident)).containsExactlyElementsIn(positions(ref.confident)).inOrder()
        assertWithMessage("simili a $i").that(positions(mine.likely)).containsExactlyElementsIn(positions(ref.likely)).inOrder()
        found += mine.confident.size
      }
    }
    assertThat(found).isGreaterThan(0)
    assertThat(FakeConcepts.DIMENSION).isEqualTo(768)
  }
}
