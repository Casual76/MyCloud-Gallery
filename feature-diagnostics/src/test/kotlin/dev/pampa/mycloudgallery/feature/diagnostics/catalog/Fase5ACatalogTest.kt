package dev.pampa.mycloudgallery.feature.diagnostics.catalog

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportKeys
import java.io.File
import org.junit.Test

/**
 * Il catalogo della Fase 5A (P7; docs/fase5-progetto.md §2.6, "Ogni problema ha un messaggio e un'azione ... un test li confronta con il
 * catalogo della Diagnostica"): **ogni riga della tabella dei problemi del backup ha il suo codice** nella sezione «Backup», con gli stessi
 * edge case del documento. La tabella viene **letta dal documento**: una riga nuova senza codice fa fallire il test.
 *
 * I codici sono quelli del contributore di `:app` (`BackupDiagnostics`, `BackupDiagCodes`), scritti qui a mano (questo modulo non vede
 * :app ne' core-backup; un test di `:app` verifica che coincidano con `BackupProblemCode`). Un test per codice sta in
 * [Fase5ACodeCatalogTest].
 */
class Fase5ACatalogTest {

  /** Ogni riga della tabella del §2.6 ha il suo `DiagCode` («BACKUP_» + il codice) con gli stessi edge case. */
  @Test
  fun ogniRigaDellaTabellaDelleProblemiDelBackupHaIlSuoCodice() {
    val rows = problemRows()
    assertThat(rows.keys).containsAtLeast("NAS_UNREACHABLE", "UNREADABLE", "COMMON_COPY_LOST", "TEST_ONLY", "STALE")
    rows.forEach { (code, edgeCases) ->
      val id = "BACKUP_$code"
      val entry = checkNotNull(DiagCatalog.entry(DiagCode(id))) { "$id non e' nel catalogo" }
      assertWithMessage(id).that(entry.code.edgeCases).containsExactlyElementsIn(edgeCases)
      assertWithMessage(id).that(entry.section).isEqualTo(DiagSection.BACKUP)
    }
  }

  /** E viceversa: nessun codice «BACKUP_*» del catalogo sta fuori dalla tabella, tranne le tre voci di stato. */
  @Test
  fun nessunCodiceDelBackupStaFuoriDallaTabella() {
    val rows = problemRows().keys.map { "BACKUP_$it" }.toSet()
    // P14: nella stessa sezione anche i contatori delle integrazioni della 5B (selettore, documenti, ricevuti, modifica, condivisioni).
    // Fase 10 (P13): e accanto a loro quelli del trascinamento fra app (P6, par. 2.13 di docs/fase10-progetto.md).
    val stateCodes = setOf("BACKUP_STATE", "BACKUP_LAST_ROUND", "BACKUP_SIMILAR_COPIES", "INTEGRATIONS_COUNTERS", "DRAG_OUT", "DRAG_IN", "DRAG_TEMP_CLEANED")
    val inCatalog = DiagCatalog.entriesOf(DiagSection.BACKUP).map { it.code.id }.toSet()
    assertThat(inCatalog - rows - stateCodes).isEmpty()
    assertThat(inCatalog).containsAtLeastElementsIn(rows + stateCodes)
  }

  /** Gli edge case citati nel §2.6 sono tutti coperti da un codice del catalogo (nessuno resta senza una voce che lo racconti). */
  @Test
  fun ogniEdgeCaseDellaTabellaECopertoDalCatalogo() {
    val cited = problemRows().values.flatten().toSet()
    val missing = cited.filterNot { it in DiagCatalog.coveredEdgeCases }
    assertWithMessage("Edge case del §2.6 senza un DiagCode: $missing").that(missing).isEmpty()
  }

  /** Le voci di stato non hanno edge case e hanno titolo e guida. */
  @Test
  fun leVociDiStatoHannoTitoloEGuida() {
    listOf("BACKUP_STATE", "BACKUP_LAST_ROUND", "BACKUP_SIMILAR_COPIES").forEach { id ->
      val entry = checkNotNull(DiagCatalog.entry(DiagCode(id))) { "$id non e' nel catalogo" }
      assertWithMessage(id).that(entry.section).isEqualTo(DiagSection.BACKUP)
      assertWithMessage(id).that(entry.titleRes).isNotEqualTo(0)
      assertWithMessage(id).that(entry.guideRes).isNotEqualTo(0)
    }
  }

  /** Le chiavi d'esportazione del backup sono nell'elenco chiuso; i problemi per codice passano dalla famiglia `problem.`. */
  @Test
  fun leChiaviDelBackupSonoNellElencoChiuso() {
    val keys = listOf(
      "safe", "queued", "unverified", "onlyCommonCopy", "noNasCopy", "sources", "folderChosen", "gate", "backupStatus", "done", "linked", "gone",
      "waiting", "bytesUploaded", "bytesReread", "diaryOpen", "diaryBytesOpen", "pending", "similarRunning", "available", "lastProblem",
      "startedAgeMs", "lastCompleteAgeMs", "beatAgeMs", "stoppedBy", "failed", "count",
      "problem.verify_failed", "problem.common_copy_lost", "problem.unreadable", "problem.lost_before_backup", "problem.unexpected",
    )
    keys.forEach { assertWithMessage(it).that(ExportKeys.isAllowed(it)).isTrue() }
    // Un nome di file, di cartella o di share sotto la famiglia non passa (maiuscole, punti, spazi, trattini).
    listOf("problem.foto.jpg", "problem.Famiglia Rossi", "problem.test-comune", "problem.IMG_0001").forEach {
      assertThat(ExportKeys.isAllowed(it)).isFalse()
    }
  }

  /** Le righe del §2.6: codice → edge case, dal documento. */
  private fun problemRows(): Map<String, Set<String>> {
    val doc = File("../docs/fase5-progetto.md")
    assertWithMessage("docs/fase5-progetto.md non trovato dalla cartella ${File(".").absolutePath}").that(doc.exists()).isTrue()
    val text = doc.readText(Charsets.UTF_8)
    val from = text.indexOf("### 2.6 Stato del backup")
    val to = text.indexOf("### 2.7", from)
    assertWithMessage("§2.6 non trovato").that(from >= 0 && to > from).isTrue()
    val rows = LinkedHashMap<String, Set<String>>()
    // | `NAS_UNREACHABLE` | Il NAS non risponde | Riprova · Diagnostica | E-R5 |
    val row = Regex("""^\|\s*`([A-Z_]+)`\s*\|(.*)\|([^|]*)\|\s*$""")
    text.substring(from, to).lines().forEach { line ->
      val match = row.find(line.trim()) ?: return@forEach
      val edge = match.groupValues[3].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
      rows[match.groupValues[1]] = edge
    }
    return rows
  }
}
