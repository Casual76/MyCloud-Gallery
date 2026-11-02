package dev.pampa.mycloudgallery.feature.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.feature.backup.ui.BackupActionTexts
import dev.pampa.mycloudgallery.feature.backup.ui.BackupProblemTexts
import dev.pampa.mycloudgallery.feature.backup.ui.BackupTone
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Ogni problema ha un messaggio e un'azione" (`docs/fase5-progetto.md` §2.6, riga P5 del §4.4). L'elenco si legge dal **contratto**
 * (`BackupProblemCode.entries`, `BackupAction.entries`), non da una lista scritta qui: un codice o un'azione nuovi senza testo fanno
 * fallire questo test (e il `when` esaustivo di `BackupProblemTexts` non compila nemmeno). Il messaggio di ogni codice deve anche
 * coincidere con quello del documento, e la terza voce del piede (feature-library, che non vede core-backup) deve avere la sua frase
 * breve e il suo dettaglio per ogni codice: lo dice il suo file di stringhe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class BackupMessagesTest {

  private val context: Application = ApplicationProvider.getApplicationContext()
  private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle").isFile }

  private fun normalized(text: String): String = text.replace('’', '\'').replace('«', '"').replace('»', '"').lowercase().trim().trimEnd('.')

  @Test
  fun ogniCodiceDelContrattoHaMessaggioDettaglioEAzione() {
    assertThat(BackupProblemCode.entries).isNotEmpty()
    for (code in BackupProblemCode.entries) {
      val text = BackupProblemTexts.of(code)
      // Il messaggio e' il suo, non il generico di ripiego: un codice nuovo nel contratto senza la sua riga fa fallire qui.
      assertWithMessage("$code: ha il suo messaggio (la riga in BackupProblemTexts)").that(BackupProblemTexts.knows(code)).isTrue()
      assertWithMessage("$code: messaggio").that(context.getString(text.title)).isNotEmpty()
      assertWithMessage("$code: detail").that(text.detail?.let { context.getString(it) }.orEmpty()).isNotEmpty()
      // Un problema di un media dice anche quanti media riguarda ("3 copie non integre").
      if (code.marksMedia) {
        assertWithMessage("$code: plurale del numero dei media").that(text.titleCount).isNotNull()
        val one = context.resources.getQuantityString(text.titleCount!!, 1, "1")
        val many = context.resources.getQuantityString(text.titleCount!!, 3, "3")
        assertThat(one).contains("1")
        assertThat(many).contains("3")
        assertThat(one).isNotEqualTo(many)
      }
      // Ogni azione del §2.6 ha il suo testo, e un'azione senza tasto e' solo una frase.
      for (action in code.actions) {
        assertWithMessage("$code: azione $action").that(context.getString(BackupActionTexts.of(action).label)).isNotEmpty()
      }
    }
  }

  /**
   * CA-1 della revisione funzionale della Fase 10: con le scritture ferme dal manifest firmato la pagina Backup dice la causa e cosa fare, mai "il backup riprende
   * quando le riattivi" (l'utente non ha niente da riattivare). Gli altri problemi non cambiano.
   */
  @Test
  fun ca1_conIlManifestLeScrittureFermeDiconoLaCausaENonPromettonoDiRiprendere() {
    val text = BackupProblemTexts.of(BackupProblemCode.WRITES_STOPPED, heldByRelease = true)
    assertThat(text).isSameInstanceAs(BackupProblemTexts.releaseReadOnly)
    val detail = normalized(context.getString(checkNotNull(text.detail)))
    assertThat(detail).contains("pampa store")
    assertThat(detail).doesNotContain("riprende")
    assertThat(detail).doesNotContain("riattiv")
    assertThat(BackupProblemTexts.of(BackupProblemCode.WRITES_STOPPED)).isNotSameInstanceAs(BackupProblemTexts.releaseReadOnly)
    assertThat(BackupProblemTexts.of(BackupProblemCode.NAS_FULL, heldByRelease = true)).isSameInstanceAs(BackupProblemTexts.of(BackupProblemCode.NAS_FULL))
  }

  @Test
  fun ogniAzioneDelContrattoHaUnTesto() {
    for (action in BackupAction.entries) {
      assertWithMessage("$action: ha il suo testo (la riga in BackupActionTexts)").that(BackupActionTexts.knows(action)).isTrue()
      val text = BackupActionTexts.of(action)
      assertWithMessage("$action").that(context.getString(text.label)).isNotEmpty()
    }
    // Solo "Chiedi all'admin del NAS" e' una frase senza tasto: il resto si tocca.
    assertThat(BackupAction.entries.filter { !BackupActionTexts.of(it).isButton }).containsExactly(BackupAction.ASK_ADMIN)
  }

  @Test
  fun ilMessaggioDiOgniCodiceCoincideConQuelloDelDocumento() {
    val section = File(root, "docs/fase5-progetto.md").readText().substringAfter("### 2.6").substringBefore("### 2.7")
    val rows = Regex("""(?m)^\|\s*`([A-Z_]+)`\s*\|([^|]*)\|([^|]*)\|""").findAll(section).associate { it.groupValues[1] to it.groupValues[2].trim() }
    assertThat(rows.keys).containsExactlyElementsIn(BackupProblemCode.entries.map { it.name })
    for (code in BackupProblemCode.entries) {
      val text = BackupProblemTexts.of(code)
      // "N copie non integre" nel documento e' "3 copie non integre" con N = 3.
      val title = text.titleCount?.let { context.resources.getQuantityString(it, 3, "3") } ?: context.getString(text.title)
      val doc = rows.getValue(code.name).replace("N ", "3 ")
      assertWithMessage("$code: il documento dice «$doc», la schermata «$title»").that(normalized(doc)).startsWith(normalized(title))
    }
  }

  @Test
  fun laTerzaVoceDelPiedeHaUnaFraseEUnDettaglioPerOgniCodice() {
    val xml = File(root, "feature-library/src/main/res/values/strings_backup_footer.xml").readText()
    for (code in BackupProblemCode.entries) {
      assertWithMessage("${code.code}: frase breve del piede").that(xml).contains("""<string name="backup_footer_${code.code}_label">""")
      assertWithMessage("${code.code}: dettaglio del piede").that(xml).contains("""<string name="backup_footer_${code.code}_detail">""")
      if (code.marksMedia) {
        assertWithMessage("${code.code}: plurale del piede").that(xml).contains("""<plurals name="backup_footer_${code.code}_label_count">""")
      }
    }
  }

  @Test
  fun iToniDicono_ChiServe() {
    // Senza l'utente il backup non riparte: allarme. Si riprende da solo: attesa.
    for (code in listOf(BackupProblemCode.AUTH, BackupProblemCode.QUOTA, BackupProblemCode.NAS_FULL, BackupProblemCode.MEDIA_PERMISSION, BackupProblemCode.VERIFY_FAILED)) {
      assertThat(BackupProblemTexts.of(code).tone).isEqualTo(BackupTone.Alarm)
    }
    for (code in listOf(BackupProblemCode.WAITING_UNLOCK, BackupProblemCode.PROFILE_INACTIVE, BackupProblemCode.TEST_ONLY)) {
      assertThat(BackupProblemTexts.of(code).tone).isEqualTo(BackupTone.Waiting)
    }
  }

  @Test
  fun nessunTestoDelleSchermateNominaFileOCartelle() {
    // Solo numeri e codici (§4.3): nessuna stringa di questo modulo cita un nome di file o un percorso d'esempio.
    val strings = File("src/main/res/values").listFiles { f -> f.name.startsWith("strings_backup") }.orEmpty().joinToString("\n") { it.readText() }
    assertThat(strings).isNotEmpty()
    assertThat(Regex("""\.(jpe?g|png|heic|mp4|mov)\b""", RegexOption.IGNORE_CASE).containsMatchIn(strings)).isFalse()
    assertThat(strings).doesNotContain("IMG_")
    assertThat(strings).doesNotContain("/storage/")
  }

  /**
   * `COMMON_COPY_LOST` (P2, in arrivo nel contratto): un media la cui unica copia era nell'area comune e' sparito, senza copia privata ne'
   * del telefono; l'azione e' "Recuperalo dal cestino del NAS" (30 giorni). Messaggio, dettaglio, plurale, la frase del piede, la
   * didascalia del viewer e il testo dell'azione sono gia' scritti: quando il contratto ha il codice basta una riga per legare l'azione
   * (`BackupActionTexts`, la chiave e' il suo codice, di solito `open_nas_trash`) e il test sopra lo esige per ogni voce di `entries`.
   */
  @Test
  fun ilCodiceCommonCopyLostHaGiaIlSuoTesto() {
    val pkg = context.packageName
    fun string(name: String) = context.resources.getIdentifier(name, "string", pkg)
    fun plural(name: String) = context.resources.getIdentifier(name, "plurals", pkg)
    assertThat(string("backup_problem_common_copy_lost_title")).isNotEqualTo(0)
    assertThat(string("backup_problem_common_copy_lost_detail")).isNotEqualTo(0)
    assertThat(plural("backup_problem_common_copy_lost_title_count")).isNotEqualTo(0)
    assertThat(string("backup_action_open_nas_trash")).isNotEqualTo(0)
    assertThat(context.getString(string("backup_action_open_nas_trash"))).isEqualTo("Recuperalo dal cestino del NAS")
    val footer = File(root, "feature-library/src/main/res/values/strings_backup_footer.xml").readText()
    assertThat(footer).contains("""<string name="backup_footer_common_copy_lost_label">""")
    assertThat(footer).contains("""<string name="backup_footer_common_copy_lost_detail">""")
    assertThat(footer).contains("""<plurals name="backup_footer_common_copy_lost_label_count">""")
    // Se il contratto ha gia' il codice, ha anche tutto il resto (l'enumerazione si legge per nome, senza nominarla).
    BackupProblemCode.entries.firstOrNull { it.code == "common_copy_lost" }?.let { code ->
      assertThat(BackupProblemTexts.knows(code)).isTrue()
    }
  }
}
