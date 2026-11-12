package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.models.contract.ModelDiag
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
import dev.pampa.mycloudgallery.core.search.contract.SearchDiag
import dev.pampa.mycloudgallery.feature.search.models.CodeAction
import dev.pampa.mycloudgallery.feature.search.models.SearchCodes
import dev.pampa.mycloudgallery.feature.search.models.failureCode
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Ogni codice del §2.6 ha il suo messaggio e la sua azione" (Fase 6, P8): l'elenco dei codici lo legge dai CONTRATTI (`AiDiag`, `ModelDiag`, `SearchDiag`)
 * e dalla tabella del §2.6 di `docs/fase6-progetto.md` (i codici che stanno in altri moduli, che feature-search non vede). Un codice nuovo senza messaggio
 * fa fallire il test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "it")
class SearchCodesTest {

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

  private val contractCodes: List<DiagCode> = AiDiag.ALL + ModelDiag.ALL + SearchDiag.ALL

  @Test
  fun ognCodiceDeiContrattiHaIlSuoMessaggio() {
    assertThat(contractCodes).isNotEmpty()
    for (code in contractCodes) {
      val entry = SearchCodes.entryOf(code)
      assertWithMessage("manca il messaggio di ${code.id}").that(entry).isNotNull()
      val text = entry!!.text(resources, amount = "12")
      assertWithMessage("messaggio vuoto per ${code.id}").that(text).isNotEmpty()
      assertWithMessage("il messaggio di ${code.id} mostra il codice grezzo").that(text).doesNotContain(code.id)
      assertWithMessage("segnaposto non risolto per ${code.id}").that(text).doesNotContain("%")
    }
  }

  @Test
  fun ognCodiceDellaTabellaDelParagrafo2_6HaMessaggioEAzioneComeScritto() {
    val doc = File("../docs/fase6-progetto.md").readText()
    val section = doc.substringAfter("**Ogni problema ha un codice e un'azione**").substringBefore("### 2.7")
    val rows = section.lines().filter { it.startsWith("| `") }
    assertThat(rows).isNotEmpty()
    for (row in rows) {
      val cells = row.trim('|').split('|').map { it.trim() }
      val code = cells[0].substringBefore('`', "").ifEmpty { cells[0].removePrefix("`").substringBefore('`') }
      val entry = SearchCodes.entryOf(DiagCode(code))
      assertWithMessage("manca il codice $code della tabella del §2.6").that(entry).isNotNull()
      val action = cells[2]
      if (action.startsWith("—")) {
        assertWithMessage("$code: la tabella non ha azione").that(entry!!.actions).isEmpty()
      } else {
        assertWithMessage("$code: la tabella ha l'azione «$action»").that(entry!!.actions).isNotEmpty()
        for (part in action.split('·').map { it.trim() }) {
          val expected = when {
            part.startsWith("Scarica") -> CodeAction.DOWNLOAD_NOW
            part.startsWith("Riprova") -> CodeAction.RETRY
            part.startsWith("Diagnostica") -> CodeAction.DIAGNOSTICS
            part.startsWith("Libera spazio") -> CodeAction.FREE_SPACE
            part.startsWith("Attiva su questo dispositivo") -> CodeAction.ENABLE_INDEXER
            else -> error("azione della tabella non riconosciuta: $part")
          }
          assertWithMessage("$code: manca l'azione «$part»").that(entry.actions).contains(expected)
        }
      }
    }
  }

  @Test
  fun inCatalogoNonCeNienteCheIlProgettoNonElenchi() {
    val doc = File("../docs/fase6-progetto.md").readText()
    for (entry in SearchCodes.ENTRIES) assertWithMessage("${entry.code} non e' nel §2.6").that(doc).contains("`${entry.code}`")
    assertThat(SearchCodes.ENTRIES.map { it.code }).containsNoDuplicates()
  }

  @Test
  fun ilMessaggioConUnNumeroLoMostra() {
    val needed = SearchCodes.message(resources, ModelDiag.AI_MODEL_NO_SPACE, amount = "370 MB")
    assertThat(needed).isEqualTo("Spazio insufficiente per il modello (serve 370 MB)")
    assertThat(SearchCodes.message(resources, AiDiag.OCR_FAILED, "7")).isEqualTo("Testo non letto in 7 foto")
    assertThat(SearchCodes.message(resources, AiDiag.AI_VECTOR_INVALID, "3")).isEqualTo("3 foto e video non analizzati (errore del motore)")
  }

  @Test
  fun unCodicePiuNuovoDellAppDiceUnaFraseGenericaEPortaAllaDiagnostica() {
    val future = DiagCode("AI_CODICE_DEL_FUTURO")
    assertThat(SearchCodes.message(resources, future)).isEqualTo("Problema della ricerca")
    assertThat(SearchCodes.actions(future)).containsExactly(CodeAction.DIAGNOSTICS)
  }

  @Test
  fun ognMotivoDiFallimentoDiUnModelloHaUnCodiceConMessaggio() {
    for (fail in ModelFailCode.entries) {
      val code = failureCode(fail)
      assertThat(code).isIn(ModelDiag.ALL)
      assertThat(SearchCodes.entryOf(code)).isNotNull()
    }
    assertThat(failureCode(ModelFailCode.NO_SPACE)).isEqualTo(ModelDiag.AI_MODEL_NO_SPACE)
    assertThat(failureCode(ModelFailCode.MISMATCH)).isEqualTo(ModelDiag.AI_MODEL_MISMATCH)
  }

  @Test
  fun leEtichetteDelleAzioniSonoLeStesseDellaTabella() {
    assertThat(resources.getString(CodeAction.DOWNLOAD_NOW.label)).isEqualTo("Scarica ora")
    assertThat(resources.getString(CodeAction.RETRY.label)).isEqualTo("Riprova")
    assertThat(resources.getString(CodeAction.DIAGNOSTICS.label)).isEqualTo("Diagnostica")
    assertThat(resources.getString(CodeAction.FREE_SPACE.label)).isEqualTo("Libera spazio")
    assertThat(resources.getString(CodeAction.ENABLE_INDEXER.label)).isEqualTo("Attiva su questo dispositivo")
  }
}
