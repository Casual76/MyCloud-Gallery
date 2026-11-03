package dev.pampa.mycloudgallery.feature.library.library

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import java.io.File
import org.junit.Test

/**
 * La tabella dei problemi del piede (`BackupFooterProblems`) e il file delle stringhe dicono la stessa cosa: ogni codice ha la sua frase
 * breve e il suo dettaglio, e nessuna riga del file resta senza codice. L'elenco dei codici di `BackupProblemCode` (core-backup, che
 * questo modulo non vede, §1.1) lo confronta `BackupMessagesTest` di feature-backup con lo stesso file.
 */
class BackupFooterProblemsTest {

  private val xml: String = File("src/main/res/values/strings_backup_footer.xml").readText()

  /** I codici per cui il file ha una frase breve (`backup_footer_<codice>_label`). */
  private val codesInFile: List<String> =
    Regex("""<string name="backup_footer_([a-z_]+)_label">""").findAll(xml).map { it.groupValues[1] }.toList()

  @Test
  fun ogniRigaDelFileHaIlSuoCodiceENelloStessoOrdine() {
    assertThat(codesInFile).isNotEmpty()
    assertThat(BackupFooterProblems.all.map { it.code }).containsExactlyElementsIn(codesInFile).inOrder()
  }

  @Test
  fun ogniCodiceHaFraseBreveEDettaglio() {
    for (code in codesInFile) {
      assertWithMessage("$code: dettaglio").that(xml).contains("""<string name="backup_footer_${code}_detail">""")
    }
    assertThat(BackupFooterProblems.all.map { it.code }).containsNoDuplicates()
  }

  /**
   * CA-1 della revisione funzionale della Fase 10: con le scritture ferme dal manifest firmato il piede dice la causa ("versione da aggiornare") invece di mandare a
   * cercare in Diagnostica un interruttore che l'utente non ha toccato. Gli altri problemi non cambiano.
   */
  @Test
  fun ca1_conIlManifestLeScrittureFermeDiconoLaCausa() {
    assertThat(BackupFooterProblems.of("writes_stopped", heldByRelease = true)).isSameInstanceAs(BackupFooterProblems.releaseReadOnly)
    assertThat(BackupFooterProblems.of("writes_stopped")).isNotSameInstanceAs(BackupFooterProblems.releaseReadOnly)
    assertThat(BackupFooterProblems.of("nas_full", heldByRelease = true).code).isEqualTo("nas_full")
    val label = Regex("""<string name="backup_footer_release_read_only">([^<]*)<""").find(xml)?.groupValues?.get(1).orEmpty()
    val hint = Regex("""<string name="backup_footer_release_read_only_hint">([^<]*)<""").find(xml)?.groupValues?.get(1).orEmpty()
    assertThat(label.lowercase()).contains("aggiornare")
    assertThat(hint).contains("Pampa Store")
  }

  @Test
  fun unCodiceNuovoNonLasciaIlPiedeMuto() {
    assertThat(BackupFooterProblems.of("un_codice_che_non_esiste")).isSameInstanceAs(BackupFooterProblems.generic)
    assertThat(BackupFooterProblems.generic.tone).isEqualTo(GalleryFooterTone.Attention)
  }

  @Test
  fun iToniSonoSoloQuelliDelPiede() {
    // I cinque toni di `GalleryFooterTone` sono quelli che `GalleryFooterStatusTest` verifica su ogni accento, chiaro e scuro.
    assertThat(BackupFooterProblems.all.map { it.tone }.toSet()).isNotEmpty()
    assertThat(GalleryFooterTone.entries).hasSize(5)
  }

  @Test
  fun serveLUtenteSeIlBackupSenzaDiLuiNonRiparte() {
    val alarm = BackupFooterProblems.all.filter { it.tone == GalleryFooterTone.Alarm }.map { it.code }
    assertThat(alarm).containsAtLeast("auth", "quota", "nas_full", "media_permission", "read_only_area", "no_private_area", "frozen", "verify_failed")
    val calm = BackupFooterProblems.all.filter { it.tone == GalleryFooterTone.Waiting }.map { it.code }
    // P14: anche le versioni ferme per il PC ripartono da sole (dopo la verifica del NAS): un'attesa, non un allarme.
    assertThat(calm).containsExactly("test_only", "source_changed", "waiting_unlock", "profile_inactive")
  }

  @Test
  fun lePluraliDeiProblemiDiUnMediaEsistono() {
    for (problem in BackupFooterProblems.all.filter { it.labelCount != null }) {
      assertWithMessage("${problem.code}: plurale").that(xml).contains("""<plurals name="backup_footer_${problem.code}_label_count">""")
    }
    assertThat(BackupFooterProblems.all.filter { it.labelCount != null }.map { it.code })
      .containsExactly("lost_before_backup", "verify_failed", "common_copy_lost", "unreadable", "unexpected", "incoming_failed")
  }

  /**
   * IN-7: i file ricevuti non salvati hanno la loro frase, e non coprono mai un allarme: e' un'attenzione (si sistema da Backup), non un allarme e non
   * un'attesa che passa da sola.
   */
  @Test
  fun iFileRicevutiNonSalvatiHannoLaLoroFrase() {
    val problem = BackupFooterProblems.of("incoming_failed")
    assertThat(problem).isNotSameInstanceAs(BackupFooterProblems.generic)
    assertThat(problem.code).isEqualTo("incoming_failed")
    assertThat(problem.tone).isEqualTo(GalleryFooterTone.Attention)
    assertThat(problem.labelCount).isNotNull()
    assertThat(xml).contains("""<string name="backup_footer_incoming_failed_label">File ricevuti non salvati</string>""")
    assertThat(xml).contains("""<string name="backup_footer_incoming_failed_detail">""")
  }
}
