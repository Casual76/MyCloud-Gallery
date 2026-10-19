package dev.pampa.mycloudgallery.core.backup

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.PhoneDenied
import java.io.File
import org.junit.Test

/**
 * §2.6 ("Ogni problema ha un messaggio e un'azione"): i codici di [BackupProblemCode] sono **esattamente** quelli della tabella del
 * progetto, nello stesso ordine, con le stesse azioni (presenti o assenti) e gli stessi edge case. La tabella si legge dal documento:
 * se cambia una delle due parti, il test lo dice. P5 (messaggi) e P7 (Diagnostica) leggono l'elenco dal contratto.
 */
class BackupProblemCatalogTest {

  private class Row(val code: String, val action: String, val edgeCases: Set<String>)

  private fun table(): List<Row> {
    val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle").isFile }
    val text = File(root, "docs/fase5-progetto.md").readText()
    val section = text.substringAfter("### 2.6").substringBefore("### 2.7")
    return Regex("""(?m)^\|\s*`([A-Z_]+)`\s*\|([^|]*)\|([^|]*)\|([^|]*)\|\s*$""").findAll(section).map { m ->
      Row(
        code = m.groupValues[1],
        action = m.groupValues[3].trim(),
        edgeCases = m.groupValues[4].split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
      )
    }.toList()
  }

  @Test
  fun iCodiciSonoQuelliDellaTabellaDelProgetto() {
    val rows = table()
    assertWithMessage("la tabella del §2.6 si legge").that(rows.size).isAtLeast(15)
    assertThat(BackupProblemCode.entries.map { it.name }).containsExactlyElementsIn(rows.map { it.code }).inOrder()
    for (row in rows) {
      val code = BackupProblemCode.valueOf(row.code)
      val noAction = row.action.startsWith("—")
      assertWithMessage("${row.code}: azione").that(code.actions.isEmpty()).isEqualTo(noAction)
      assertWithMessage("${row.code}: edge case").that(code.edgeCases).isEqualTo(row.edgeCases)
      assertWithMessage("${row.code}: codice stabile").that(code.code).isEqualTo(row.code.lowercase())
    }
  }

  @Test
  fun soloIProblemiDiUnMediaAccendonoIlSegnoDellaCella() {
    assertThat(BackupProblemCode.entries.filter { it.marksMedia })
      .containsExactly(
        BackupProblemCode.LOST_BEFORE_BACKUP, BackupProblemCode.VERIFY_FAILED, BackupProblemCode.UNREADABLE, BackupProblemCode.COMMON_COPY_LOST,
        BackupProblemCode.UNEXPECTED,
      )
  }

  @Test
  fun cancelloETelefonoHannoIlLoroCodice() {
    // NOT_READY (famiglia non pronta) e PAUSED ("Ferma" dell'utente, R4 della revisione 5A) non sono problemi: nessun codice.
    val withoutProblem = setOf(BackupGateState.NOT_READY, BackupGateState.PAUSED)
    assertThat(BackupGateState.entries.filter { !it.open && it !in withoutProblem }.map { it.problem }).doesNotContain(null)
    assertThat(BackupGateState.PAUSED.problem).isNull()
    assertThat(BackupGateState.PAUSED.open).isFalse()
    assertThat(BackupGateState.TEST_ONLY.problem).isEqualTo(BackupProblemCode.TEST_ONLY)
    assertThat(PhoneDenied.entries.map { it.problem })
      .containsExactly(BackupProblemCode.MEDIA_PERMISSION, BackupProblemCode.MEDIA_LOCATION, BackupProblemCode.PARTIAL_ACCESS)
  }
}
