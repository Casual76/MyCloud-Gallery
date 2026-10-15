package dev.pampa.mycloudgallery.feature.diagnostics.catalog

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportKeys
import java.io.File
import org.junit.Test

/**
 * Il catalogo della Fase 4B (P16; docs/fase4-progetto.md §2.7, §2.9): **ogni edge case della 4B che produce un avviso in Diagnostica ha
 * il suo codice** nella sezione «NAS nella Libreria», e i casi che non sono un avviso hanno il loro motivo.
 *
 * I codici sono quelli del contributore di `:app` (`NasOpsDiagnostics`), scritti qui a mano (questo modulo non vede :app; un test di
 * `:app` verifica che coincidano). Gli identificativi citati nei §2.7 e §2.9 vengono **letti dal documento**.
 */
class Fase4BCatalogTest {

  @Test
  fun iCodiciDellaFase4BHannoGliIdGliEdgeCaseLaSezioneETitoloECosaFare() {
    CODES_4B.forEach { (id, edgeCases) ->
      val entry = checkNotNull(DiagCatalog.entry(DiagCode(id))) { "$id non e' nel catalogo" }
      assertWithMessage(id).that(entry.code.edgeCases).containsExactlyElementsIn(edgeCases)
      assertWithMessage(id).that(entry.section).isEqualTo(DiagSection.NAS_LIBRARY)
      assertWithMessage(id).that(entry.titleRes).isNotEqualTo(0)
      assertWithMessage(id).that(entry.guideRes).isNotEqualTo(0)
    }
  }

  /** Le chiavi d'esportazione della 4B sono nell'elenco chiuso; i motivi per codice passano dalla famiglia `reason.`. */
  @Test
  fun leChiaviDellaFase4BSonoNellElencoChiuso() {
    val keys = listOf(
      "opsActive", "opsRunning", "opsPaused", "opsLocked", "opsClosed", "pauseReason", "count", "bytes", "expired", "purged",
      "alreadyGone", "deferred", "stoppedBy", "compacted", "skipped", "removed", "failed", "status", "reason", "published", "applied",
      "stillUndated", "unresolved", "rejectedSegments", "rejectedHeads",
      // Motivi: OpErrorCode, KeepReason, PersonalVerdict (costanti del codice, in maiuscolo).
      "reason.SOURCE_CHANGED", "reason.LOCKED_TOO_LONG", "reason.NOT_EXPIRED", "reason.AUTHOR_NOT_ALLOWED", "reason.RESERVED_KIND",
    )
    keys.forEach { assertWithMessage(it).that(ExportKeys.isAllowed(it)).isTrue() }
    // Un nome di file o di share sotto la famiglia non passa (minuscole, punti, spazi).
    listOf("reason.foto.jpg", "reason.test-comune", "reason.Famiglia Rossi").forEach { assertThat(ExportKeys.isAllowed(it)).isFalse() }
  }

  /** Gli edge case e le decisioni della 4B che **producono un avviso** (o una voce di stato) in Diagnostica. */
  private val warningEdgeCases = setOf("E-D2", "E-D4", "E-D5", "E-D6", "E-D9", "E-D13", "E-N9", "E-N11", "E-N13", "E-S11", "E-H4", "E-F5", "M6")

  @Test
  fun ogniEdgeCaseDellaFase4BCheProduceUnAvvisoHaIlSuoCodice() {
    val nasCodes = DiagCatalog.entriesOf(DiagSection.NAS_LIBRARY).map { it.code }
    warningEdgeCases.forEach { edgeCase ->
      assertWithMessage("$edgeCase non ha un codice nella sezione «NAS nella Libreria»").that(nasCodes.filter { edgeCase in it.edgeCases }).isNotEmpty()
    }
  }

  /** Gli identificativi citati nei §2.7 e §2.9 che NON producono un avviso, col motivo. */
  private val notADiagnosticWarning = mapOf(
    "E-D1" to "media su telefono e NAS: la scelta «Solo dal telefono / Ovunque» e' del foglio di eliminazione",
    "E-D3" to "ripristino con la cartella sparita: la cartella si ricrea (regola dell'esecutore)",
    "E-D7" to "eliminazione di massa: riepilogo e conferma nel foglio, annullabile dal cestino",
    "E-D8" to "album condivisi: Fase 7",
    "E-D10" to "cartella intera: una rinomina sola nel cestino (regola dell'esecutore)",
    "E-D11" to "«Ovunque» dove non si puo': il piano mostra gli elementi esclusi nel foglio",
    "E-D12" to "copia lato server rifiutata: ripiego dal telefono come attivita' pesante; in pausa per condizioni (NAS_OPS_STATE)",
    "E-N7" to "rinomina solo di maiuscole: passa da un nome temporaneo (regola dell'esecutore)",
    "C19" to "i nascosti restano nell'area privata: regola di pubblicazione dei dati personali",
    "E-O7" to "reinstallazione: chiave nuova e nuova approvazione, poi i dati personali tornano dal NAS (comportamento, nessun avviso)",
  )

  @Test
  fun leEccezioniDellaFase4BHannoUnMotivoENonSonoCoperteDaUnCodiceDellaFase4B() {
    val covered4B = CODES_4B.values.flatten().toSet()
    notADiagnosticWarning.forEach { (id, reason) ->
      assertWithMessage(id).that(reason).isNotEmpty()
      assertWithMessage("$id e' coperto da un codice della 4B: toglilo dall'elenco delle eccezioni").that(id in covered4B).isFalse()
    }
  }

  @Test
  fun ogniIdentificativoCitatoNeiFlussiDellaFase4BEClassificato() {
    val cited = citedIdentifiers()
    assertThat(cited).containsAtLeast("E-D1", "E-D13", "E-S11", "E-H4")
    val unclassified = cited.filterNot { it in DiagCatalog.coveredEdgeCases || it in notADiagnosticWarning }
    assertWithMessage("Identificativi citati nei §2.7 e §2.9 senza un DiagCode e non classificati: $unclassified").that(unclassified).isEmpty()
    val stale = notADiagnosticWarning.keys.filterNot { it in cited }
    assertWithMessage("Eccezioni che il documento non cita piu': $stale").that(stale).isEmpty()
  }

  /** Gli identificativi citati in «### 2.7 Operazioni sui file» e «### 2.9 Dati personali» (la 4B). */
  private fun citedIdentifiers(): Set<String> {
    val doc = File("../docs/fase4-progetto.md")
    assertWithMessage("docs/fase4-progetto.md non trovato dalla cartella ${File(".").absolutePath}").that(doc.exists()).isTrue()
    val text = doc.readText(Charsets.UTF_8)
    fun section(from: String, to: String): String {
      val a = text.indexOf(from)
      val b = text.indexOf(to)
      assertWithMessage("$from non trovato").that(a >= 0 && b > a).isTrue()
      return text.substring(a, b)
    }
    val body = section("### 2.7 Operazioni sui file", "### 2.8 Regole applicate") + section("### 2.9 Dati personali", "### 2.10 Stima dei costi")
    return Regex("""\b(E-[A-Z]{1,2}[0-9]{1,3}|[BCLM][0-9]{1,3}|T-[0-9]{1,3})\b""").findAll(body).map { it.value }.toSortedSet()
  }

  companion object {
    /** I codici della 4B (NasOpsDiagCodes di :app), con i loro edge case: tutti nella sezione «NAS nella Libreria». */
    val CODES_4B: Map<String, Set<String>> = mapOf(
      "NAS_OPS_STATE" to setOf("E-D6"),
      "NAS_OPS_LOCKED" to setOf("E-N13"),
      "NAS_OPS_FAILED" to setOf("E-D5", "E-D9", "E-D13", "E-N9", "E-N11"),
      "NAS_TRASH_STATE" to setOf("E-D2"),
      "NAS_TRASH_PURGE" to setOf("E-D4", "E-S11"),
      "NAS_INDEX_MAINTENANCE" to setOf("M6"),
      "NAS_PERSONAL_STATE" to setOf("E-H4"),
      "NAS_PERSONAL_REJECTED" to setOf("E-F5", "D26"),
    )
  }
}
