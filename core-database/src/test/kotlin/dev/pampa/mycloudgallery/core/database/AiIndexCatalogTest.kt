package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.entity.SEARCH_HISTORY_MAX
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.model.VecSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L'indice locale dei dati AI per chiave di contenuto (Fase 6, P1; D23, D25, D32): righe dei vettori, testo letto con l'FTS4 e il
 * vocabolario, cronologia della ricerca. Database vero in memoria (Robolectric: SQLite con FTS4 come sul telefono).
 */
@RunWith(AndroidJUnit4::class)
class AiIndexCatalogTest : CatalogTestBase() {

  private val ai get() = catalog.aiIndex

  private fun key(n: Int): ContentKey = ContentKey(n.toString(16).padStart(64, '0'))

  private fun text(n: Int, text: String, recipe: Int = 1, lines: Int = 3, chars: Int = 40) =
    AiTextRecord(key(n), recipe, OcrEngineCode.MLKIT_V2_LATIN, OcrSource.ANALYSIS, text, lines, chars, createdAt = T0)

  private fun vector(n: Int, recipe: Int = 1, frame: Int = 0, offset: Long = 1_000L * n + frame, space: SpaceCode = SpaceCode.CURRENT) =
    AiVectorRow(key(n), space, recipe, frame, if (frame == 0) VecSource.ANALYSIS else VecSource.FRAMES, if (frame == 0) null else 1_000L * frame, offset, 1_536, 123L + n, T0)

  // --- Vettori ---------------------------------------------------------------------------------------------------------

  @Test
  fun levettoriSiSalvanoPerChiaveERiscrivereLaStessaRigaLaSostituisce() = runBlocking<Unit> {
    ai.putVectors(listOf(vector(1), vector(2)))
    assertThat(ai.vectors(listOf(key(1), key(2), key(3))).keys).containsExactly(key(1), key(2))
    assertThat(ai.vectors(listOf(key(1))).getValue(key(1)).single().byteOffset).isEqualTo(1_000L)
    ai.putVectors(listOf(vector(1, offset = 77)))
    assertThat(ai.vectors(listOf(key(1))).getValue(key(1)).single().byteOffset).isEqualTo(77L)
    assertThat(ai.countVectorKeys()).isEqualTo(2)
  }

  @Test
  fun perOgniChiaveVinceLaRicettaPiuAltaConTuttiISuoiFotogrammi() = runBlocking<Unit> {
    ai.putVectors((0..2).map { vector(1, recipe = 1, frame = it) } + (0..1).map { vector(1, recipe = 2, frame = it) })
    val rows = ai.vectors(listOf(key(1))).getValue(key(1))
    assertThat(rows.map { it.recipe }).containsExactly(2, 2)
    assertThat(rows.map { it.frame }).containsExactly(0, 1).inOrder()
    assertThat(ai.keysWithVector(listOf(key(1)), minRecipe = 2)).containsExactly(key(1))
    assertThat(ai.keysWithVector(listOf(key(1)), minRecipe = 3)).isEmpty()
    assertThat(ai.countVectorKeys(minRecipe = 2)).isEqualTo(1)
    // Le ricette vecchie escono per le chiavi che ne hanno una nuova.
    assertThat(ai.removeSupersededVectors(SpaceCode.CURRENT, 2)).isEqualTo(3)
    assertThat(db.aiIndexDao().countVectorRows(SpaceCode.CURRENT.code)).isEqualTo(2)
  }

  @Test
  fun lePagineDelloSpazioNonDividonoMaLeRighePerChiave() = runBlocking<Unit> {
    // 5 chiavi, la 2 e la 4 con 3 fotogrammi: una pagina da 2 chiavi contiene tutte le loro righe.
    ai.putVectors(listOf(vector(1)) + (0..2).map { vector(2, frame = it) } + listOf(vector(3)) + (0..2).map { vector(4, frame = it) } + listOf(vector(5)))
    val seen = ArrayList<AiVectorRow>()
    var after: ContentKey? = null
    var pages = 0
    while (true) {
      val page = ai.vectorPage(SpaceCode.CURRENT, after, 2)
      if (page.next == null) break
      pages++
      assertThat(page.rows.map { it.contentKey }.distinct().size).isAtMost(2)
      seen += page.rows
      after = page.next
    }
    assertThat(pages).isEqualTo(3)
    assertThat(seen).hasSize(9)
    assertThat(seen.map { it.contentKey.hex }).isInOrder()
    assertThat(seen.groupBy { it.contentKey }.getValue(key(2))).hasSize(3)
  }

  @Test
  fun unCodiceSconosciutoLettoDaUnAltraAppNonEPerMeEUnoSpazioSiTogliePerIntero() = runBlocking<Unit> {
    ai.putVectors(listOf(vector(1)))
    db.openHelper.writableDatabase.execSQL(
      "INSERT INTO ai_vector (content_key, space, recipe, frame, source, instant_ms, byte_offset, byte_length, crc, created_at) VALUES " +
        "('${key(9).hex}', 'spazio_futuro', 1, 0, 'analysis', NULL, 0, 10, 1, 0), ('${key(8).hex}', '${SpaceCode.CURRENT.code}', 1, 0, 'sorgente_futura', NULL, 0, 10, 1, 0)",
    )
    assertThat(ai.vectors(listOf(key(1), key(8), key(9))).keys).containsExactly(key(1))
    assertThat(ai.vectorPage(SpaceCode.CURRENT, null, 10).rows.map { it.contentKey }).containsExactly(key(1))
    ai.removeVectorSpace(SpaceCode.CURRENT)
    assertThat(ai.countVectorKeys()).isEqualTo(0)
    assertThat(db.aiIndexDao().countVectorRows("spazio_futuro")).isEqualTo(1)
    ai.putVectors(listOf(vector(2), vector(3)))
    ai.removeVectors(listOf(key(2), key(9)))
    assertThat(ai.vectors(listOf(key(2), key(3), key(9))).keys).containsExactly(key(3))
  }

  @Test
  fun leRigheDeiVettoriNonValidiSiRifiutano() {
    val bad = listOf<() -> AiVectorRow>(
      { vector(1, recipe = 0) }, { vector(1, recipe = 256) }, { vector(1, frame = 9) }, { vector(1, offset = -1) },
      { vector(1).copy(byteLength = 0) }, { vector(1).copy(crc = 4_294_967_296L) }, { vector(1).copy(crc = -1) },
    )
    bad.forEach { make -> assertThat(runCatching { make() }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java) }
  }

  // --- Testo e FTS4 ----------------------------------------------------------------------------------------------------

  @Test
  fun ilTestoNormalizzatoSiCercaConLFts4_ParolaEFrase() = runBlocking<Unit> {
    ai.putText(text(1, "scontrino esselunga totale 12 euro"))
    ai.putText(text(2, "ricevuta farmacia totale 8 euro"))
    ai.putText(text(3, "il mare di sera"))

    assertThat(ai.textMatches(listOf(listOf("esselunga"))).all).containsExactly(key(1))
    assertThat(ai.textMatches(listOf(listOf("totale"))).all).containsExactly(key(1), key(2))
    // Frase: parole di seguito, nell'ordine.
    assertThat(ai.textMatches(listOf(listOf("totale 12 euro"))).all).containsExactly(key(1))
    assertThat(ai.textMatches(listOf(listOf("euro totale"))).all).isEmpty()
    // Il tokenizzatore `simple` non cerca prefissi ne' pezzi di parola: la parola e' intera.
    assertThat(ai.textMatches(listOf(listOf("esselung"))).all).isEmpty()
    assertThat(ai.textMatches(listOf(listOf("sera"))).all).containsExactly(key(3))
  }

  @Test
  fun tuttiITerminiSonoSicuroAlmenoMetaSonoMenoProbabili_ConLeAlternative() = runBlocking<Unit> {
    ai.putText(text(1, "scontrino esselunga totale 12 euro"))
    ai.putText(text(2, "ricevuta farmacia totale 8 euro"))
    ai.putText(text(3, "scontrino del bar"))

    val two = ai.textMatches(listOf(listOf("scontrino"), listOf("totale")))
    assertThat(two.terms).isEqualTo(2)
    assertThat(two.all).containsExactly(key(1))
    assertThat(two.atLeastHalf).containsExactly(key(1), key(2), key(3))
    val three = ai.textMatches(listOf(listOf("scontrino"), listOf("totale"), listOf("farmacia")))
    assertThat(three.all).isEmpty()
    assertThat(three.atLeastHalf).containsExactly(key(1), key(2))
    // Un refuso corretto dal vocabolario: le alternative di un termine sono in "o".
    val typo = ai.textMatches(listOf(listOf("scontrno", "scontrino"), listOf("bar")))
    assertThat(typo.all).containsExactly(key(3))
    // Nessun termine, nessuna risposta (e nessun errore).
    assertThat(ai.textMatches(emptyList()).hits).isEmpty()
    assertThat(ai.textMatches(listOf(listOf(""), listOf("  *  "))).terms).isEqualTo(0)
  }

  @Test
  fun leParoleDellUtenteSonoSempreLetterali_NessunaSintassiMatch() = runBlocking<Unit> {
    ai.putText(text(1, "a or b"))
    ai.putText(text(2, "a"))
    ai.putText(text(3, "b"))
    ai.putText(text(4, "esselungaxyz near foo"))
    ai.putText(text(5, "text foo"))
    val hostile = listOf(
      "a\" OR \"b", "a OR b", "esselunga*", "NEAR(a b)", "text:foo", "-a", "a b NOT", "\"", "(a", "a)", "^a", "{a b}", "a AND b", "text:\"foo\"",
    )
    hostile.forEach { term ->
      val found = runCatching { ai.textMatches(listOf(listOf(term))).hits.keys }
      assertWithMessage("termine ostile $term").that(found.isSuccess).isTrue()
    }
    // "a OR b" resta una frase di tre parole: solo il testo con quelle tre parole di seguito, non "a" oppure "b".
    assertThat(ai.textMatches(listOf(listOf("a OR b"))).all).containsExactly(key(1))
    assertThat(ai.textMatches(listOf(listOf("a\" OR \"b"))).all).containsExactly(key(1))
    // Un asterisco non e' un prefisso, "text:" non sceglie la colonna, "-a" non esclude.
    assertThat(ai.textMatches(listOf(listOf("esselunga*"))).all).isEmpty()
    assertThat(ai.textMatches(listOf(listOf("text:foo"))).all).containsExactly(key(5))
    assertThat(ai.textMatches(listOf(listOf("-a"))).all).containsExactly(key(1), key(2))
    // Troncature: pochi termini, poche alternative, frasi corte.
    val many = ai.textMatches((1..40).map { listOf("a") })
    assertThat(many.terms).isEqualTo(AiIndexCatalog.MAX_TERMS)
  }

  @Test
  fun cambiareOTogliereUnTestoAggiornaLIndiceELeParole() = runBlocking<Unit> {
    ai.putText(text(1, "scontrino esselunga"))
    assertThat(ai.textMatches(listOf(listOf("esselunga"))).all).containsExactly(key(1))
    // UPDATE: il vecchio testo esce dall'indice e il nuovo entra (trigger dell'FTS4).
    assertThat(ai.putText(text(1, "ricevuta farmacia", recipe = 2))).isTrue()
    assertThat(ai.textMatches(listOf(listOf("esselunga"))).hits).isEmpty()
    assertThat(ai.textMatches(listOf(listOf("farmacia"))).all).containsExactly(key(1))
    assertThat(ai.wordCounts(listOf("esselunga", "farmacia", "ricevuta"))).containsExactly("farmacia", 1, "ricevuta", 1)
    // Una ricetta piu' bassa non sostituisce quella piu' alta.
    assertThat(ai.putText(text(1, "vecchio testo", recipe = 1))).isFalse()
    assertThat(ai.text(key(1))!!.text).isEqualTo("ricevuta farmacia")
    // A parita' di ricetta si riscrive.
    assertThat(ai.putText(text(1, "ricevuta bar", recipe = 2))).isTrue()
    assertThat(ai.textMatches(listOf(listOf("farmacia"))).hits).isEmpty()

    assertThat(ai.removeTexts(listOf(key(1), key(2)))).isEqualTo(1)
    assertThat(ai.textMatches(listOf(listOf("bar"))).hits).isEmpty()
    assertThat(ai.vocabularySize()).isEqualTo(0)
    assertThat(db.aiIndexDao().textOf(key(1).hex)).isNull()
  }

  @Test
  fun unTestoVuotoEUnaLetturaSenzaTestoENonSiRilegge() = runBlocking<Unit> {
    ai.putText(text(1, "", lines = 0, chars = 0))
    assertThat(ai.keysWithText(listOf(key(1), key(2)))).containsExactly(key(1))
    assertThat(ai.keysWithText(listOf(key(1)), minRecipe = 2)).isEmpty()
    assertThat(ai.textMatches(listOf(listOf("qualcosa"))).hits).isEmpty()
    assertThat(ai.vocabularySize()).isEqualTo(0)
    assertThat(ai.countTexts()).isEqualTo(1)
  }

  @Test
  fun ilTestoNonNormalizzatoESiRifiutatoEIlLayoutRestaOpaco() = runBlocking<Unit> {
    listOf("Esselunga", "perché", "due  spazi", " inizio", "fine ", "con-trattino", "12,50", "con\nacapo").forEach { bad ->
      assertWithMessage("testo non normalizzato").that(runCatching { text(1, bad) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }
    assertThat(AiTextRecord.isNormalized("scontrino 12 euro")).isTrue()
    val layout = byteArrayOf(9, 8, 7)
    ai.putText(AiTextRecord(key(1), 1, OcrEngineCode.MLKIT_V2_LATIN, OcrSource.HQ, "ciao", 1, 4, layout, T0))
    layout[0] = 0 // la copia non cambia
    val read = ai.text(key(1))!!
    assertThat(read.layout!!.toList()).containsExactly(9.toByte(), 8.toByte(), 7.toByte()).inOrder()
    assertThat(read.source).isEqualTo(OcrSource.HQ)
    assertThat(read.engine).isEqualTo(OcrEngineCode.MLKIT_V2_LATIN)
    assertThat(ai.textStats(listOf(key(1)))).containsExactly(key(1), AiTextStats(1, 4))
    assertThat(read.toString()).doesNotContain("ciao")
  }

  // --- Vocabolario -----------------------------------------------------------------------------------------------------

  @Test
  fun ilVocabolarioContaInQuantiTestiCompareUnaParolaEFaiCompletamenti() = runBlocking<Unit> {
    ai.putText(text(1, "scontrino esselunga totale totale"))
    ai.putText(text(2, "scontrino farmacia"))
    ai.putText(text(3, "scuola elementare"))
    // Una parola conta una volta per testo, anche se ripetuta.
    assertThat(ai.wordCounts(listOf("totale", "scontrino", "assente"))).containsExactly("totale", 1, "scontrino", 2)
    assertThat(ai.completeWords("sc", 10).map { it.word }).containsExactly("scontrino", "scuola").inOrder()
    assertThat(ai.completeWords("sco", 10).map { it.word }).containsExactly("scontrino")
    assertThat(ai.completeWords("sc", 1)).hasSize(1)
    assertThat(ai.completeWords("", 10)).isEmpty()
    assertThat(ai.completeWords("zz", 10)).isEmpty()
    assertThat(ai.vocabularyPage("", 100).map { it.word }).isInOrder()
    assertThat(ai.vocabularyPage("scontrino", 2).map { it.word }).containsExactly("scuola", "totale").inOrder()

    // Cambiare il testo di 1: "scontrino" resta (anche in 2), "esselunga" e "totale" escono.
    ai.putText(text(1, "scontrino bar", recipe = 2))
    assertThat(ai.wordCounts(listOf("scontrino", "esselunga", "totale", "bar"))).containsExactly("scontrino", 2, "bar", 1)
    ai.clearTexts()
    assertThat(ai.vocabularySize()).isEqualTo(0)
    assertThat(ai.countTexts()).isEqualTo(0)
    assertThat(ai.textMatches(listOf(listOf("scontrino"))).hits).isEmpty()
  }

  // --- Cronologia ------------------------------------------------------------------------------------------------------

  @Test
  fun laCronologiaHaAlPiu50VociDallaPiuRecente() = runBlocking<Unit> {
    (1..60).forEach { ai.recordSearch("ricerca $it", listOf("Chip $it")) }
    val entries = ai.searchHistory()
    assertThat(entries).hasSize(SEARCH_HISTORY_MAX)
    assertThat(entries.first().text).isEqualTo("ricerca 60")
    assertThat(entries.last().text).isEqualTo("ricerca 11")
    assertThat(entries.map { it.id }).isInOrder(Comparator.reverseOrder<Long>())
    assertThat(db.searchHistoryDao().count()).isEqualTo(50)
    assertThat(ai.searchHistory(5)).hasSize(5)
  }

  @Test
  fun laStessaRicercaEUnaVoltaSolaEPassaInCimaEDelEliminaEAzzera() = runBlocking<Unit> {
    ai.recordSearch("firenze 2023", listOf("Firenze", "2023"))
    ai.recordSearch("mare", emptyList())
    ai.recordSearch("  firenze 2023  ", listOf("Firenze"))
    ai.recordSearch("   ", listOf("x"))
    ai.recordSearch("", emptyList())
    val entries = ai.searchHistory()
    assertThat(entries.map { it.text }).containsExactly("firenze 2023", "mare").inOrder()
    assertThat(entries.first().chipLabels).containsExactly("Firenze")
    assertThat(entries.last().chipLabels).isEmpty()
    // Un'etichetta con un a capo non rompe la separazione dei chip.
    ai.recordSearch("spiaggia", listOf("Sera\ne notte", "Mare"))
    assertThat(ai.searchHistory().first().chipLabels).containsExactly("Sera e notte", "Mare").inOrder()

    ai.deleteSearch(ai.searchHistory().first().id)
    assertThat(ai.searchHistory().map { it.text }).containsExactly("firenze 2023", "mare").inOrder()
    ai.clearSearchHistory()
    assertThat(ai.searchHistory()).isEmpty()
    // Il tetto vale anche con il testo e i chip lunghi.
    ai.recordSearch("x".repeat(1_000), (1..40).map { "c".repeat(500) })
    val long = ai.searchHistory().single()
    assertThat(long.text.length).isEqualTo(AiIndexCatalog.MAX_HISTORY_TEXT)
    assertThat(long.chipLabels).hasSize(AiIndexCatalog.MAX_HISTORY_CHIPS)
  }

  @Test
  fun laCronologiaVivaSiRiemetteAOgniCambiamento() = runBlocking<Unit> {
    assertThat(ai.searchHistoryChanges().first()).isEmpty()
    ai.recordSearch("uno", emptyList())
    assertThat(ai.searchHistoryChanges().first().map { it.text }).containsExactly("uno")
    ai.recordSearch("due", emptyList())
    assertThat(ai.searchHistoryChanges().first().map { it.text }).containsExactly("due", "uno").inOrder()
  }

  @Test
  fun ilTettoDellaCronologiaECostanteDelContrattoDiCoreSearch() {
    // `SearchHistory.MAX_ENTRIES` (core-search) non e' visibile da qui: il numero e' quello della decisione D32.
    assertThat(SEARCH_HISTORY_MAX).isEqualTo(50)
  }
}
