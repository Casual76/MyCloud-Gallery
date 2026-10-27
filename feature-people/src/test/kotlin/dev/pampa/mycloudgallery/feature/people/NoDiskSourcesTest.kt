package dev.pampa.mycloudgallery.feature.people

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * D25 (Fase 8, P9): **i ritagli e le copertine dei volti non toccano mai il disco**. Si fanno al momento dall'immagine che c'e' gia' sul dispositivo e
 * vivono in una cache in memoria (LRU da 8 MB, `GuardedPeopleCrops`). Questo test guarda i sorgenti di `src/main` di `:feature-people`: nessun file,
 * nessuna cartella di cache, nessun database, nessuna preferenza, nessuna compressione di bitmap. Le righe di commento non contano.
 */
class NoDiskSourcesTest {

  private val sources: List<File> by lazy {
    File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
  }

  private fun violations(pattern: Regex): List<String> =
    sources.flatMap { file ->
      file.readLines().mapIndexedNotNull { index, line ->
        val code = line.substringBefore("//")
        val trimmed = code.trimStart()
        val isDoc = trimmed.startsWith("*") || trimmed.startsWith("/**")
        if (!isDoc && pattern.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
      }
    }

  @Test
  fun sorgentiTrovati() {
    assertWithMessage("src/main/kotlin non trovato dalla cartella ${File(".").absolutePath}").that(sources).isNotEmpty()
  }

  @Test
  fun nessunFileNessunaCartellaDiCache() {
    val found = violations(
      Regex(
        """\bjava\.io\.File\b|\bimport java\.io\.(File|FileOutputStream|FileInputStream|RandomAccessFile)\b|\bjava\.nio\.file\b|\bFiles\.(write|newOutputStream|createTempFile)\b|""" +
          """\b(cacheDir|filesDir|externalCacheDir|noBackupFilesDir|codeCacheDir|openFileOutput|createTempFile)\b""",
      ),
    )
    assertWithMessage("File o cartelle di cache (i ritagli dei volti non vanno su disco, D25):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaCompressioneDiBitmap() {
    val found = violations(Regex("""\.compress\(|\bCompressFormat\b|\bBitmapFactory\.decode(File|Stream)\b"""))
    assertWithMessage("Una bitmap compressa o letta da file (i ritagli si fanno al momento):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunDatabaseNessunaPreferenza() {
    val found = violations(Regex("""\bandroidx\.room\b|\bSharedPreferences\b|\bgetSharedPreferences\b|\bandroidx\.datastore\b|\bSQLiteDatabase\b"""))
    assertWithMessage("Persistenza nelle schermate delle persone (i volti non si salvano qui):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaCacheDiImmaginiDiTerzi() {
    // Coil, Glide e Picasso hanno una cache su disco per default: i ritagli passano solo da GuardedPeopleCrops.
    val found = violations(Regex("""\b(coil|com\.bumptech\.glide|com\.squareup\.picasso)\b"""))
    assertWithMessage("Una libreria di immagini con cache su disco:\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
