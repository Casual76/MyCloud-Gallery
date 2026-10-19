package dev.pampa.mycloudgallery.core.backup.free

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import java.io.File
import org.junit.Test

/**
 * P9, §4.3: **nessuna scrittura sul telefono** fuori dai dialoghi di sistema, e nessuna primitiva che tocca il NAS. "Libera spazio" produce
 * solo lotti ([dev.pampa.mycloudgallery.core.backup.contract.TrashBatch]) che la UI traduce in `createTrashRequest`/`createDeleteRequest`
 * (core-mediastore, contratti di P0-B). Il motore vede il telefono solo con la porta di sola lettura ([PhoneBytes]) e il NAS solo con il
 * lettore ([NasMediaReader]); non nomina il riscrittore, le richieste di sistema, il MediaStore, le primitive del backup, l'emittente della
 * conferma. Il test fallisce se uno di questi nomi compare nei sorgenti di `free/` (provato reintroducendoli, vedi `docs/decisioni.md`).
 */
class FreeSpaceSourcesTest {

  private val root = File("src/main/kotlin/dev/pampa/mycloudgallery/core/backup/free")

  /**
   * I nomi vietati e perche'. Scritti a pezzi (come in `Phase5ArchitectureTest`), cosi' questo file non li nomina per intero: il test di
   * architettura di core-common cerca le primitive distruttive anche nei test di core-backup.
   */
  private val forbidden = listOf(
    Regex("""\bcreate(Trash|Delete|Write)Request\b""") to "una richiesta di sistema (solo core-mediastore, dalla UI)",
    Regex("""\b(ContentResolver|MediaStore|contentResolver)\b""") to "il MediaStore (il telefono si legge solo da PhoneBytes)",
    Regex("""\bPhone(Media)?Rewriter\b|\bPhoneWriteGrant\b""") to "la riscrittura di un file del telefono",
    Regex("""\bNasBackupOps\b|\bNas""" + """FileOps\b|\bDestructive""" + """NasAccess\b|\bAppendOnlyNasAccess\b""") to "una primitiva di scrittura sul NAS",
    Regex("""\bPhone""" + """Confirmation(Issuer|Sheet)\b""") to "l'emittente della conferma (solo la UI)",
    Regex("""\b(trashMove|deleteIfUnchanged|deleteOwnTemp|publishTemp|openTemp|archiveVersion|restoreVersion)\b""") to "una primitiva che scrive sul NAS",
  )

  @Test
  fun freeNonScriveNeSulTelefonoNeSulNas() {
    assertThat(root.isDirectory).isTrue()
    val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    assertThat(files.map { it.name }).containsAtLeast("SafeFreeSpace.kt", "FreeSpaceEvaluator.kt", "FreeSpacePorts.kt", "EverywhereCopies.kt", "PhoneOnlyDeletion.kt")
    val found = ArrayList<String>()
    for (file in files) {
      val code = withoutComments(file.readText())
      code.lineSequence().forEachIndexed { i, line ->
        for ((pattern, why) in forbidden) if (pattern.containsMatchIn(line)) found += "${file.name}:${i + 1}: $why"
      }
    }
    assertWithMessage("free/ nomina cio' che scrive sul telefono o sul NAS").that(found).isEmpty()
  }

  /** Il codice senza i commenti (devono poter dire cosa si vieta): blocchi `/* */` e righe `//`, con le righe al loro posto. */
  private fun withoutComments(source: String): String {
    val blocks = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(source) { m -> "\n".repeat(m.value.count { it == '\n' }) }
    return blocks.lineSequence().joinToString("\n") { line -> line.substringBefore("//") }
  }

  @Test
  fun ilMotoreVedeIlTelefonoEIlNasSoloInLettura() {
    val types = SafeFreeSpace::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() }.toSet()
    assertThat(types).contains(PhoneBytes::class.java)
    assertThat(types).contains(NasMediaReader::class.java)
    val names = types.map { it.simpleName }
    assertThat(names.filter { "Rewriter" in it || "Ops" in it || "Writer" in it || "Intents" in it }).isEmpty()
  }
}
