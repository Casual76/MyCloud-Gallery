package dev.pampa.mycloudgallery.hidden

import java.io.File

// Fase 7 (P9, D22): la lettura dei sorgenti del repository per il registro delle superfici (come i test d'architettura di `core-common`).

internal object RepoFiles {

  /** La radice del repository: sopra la cartella di lavoro dei test, dove c'e' `settings.gradle`. */
  val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle").isFile && File(it, "core-common").isDirectory }
    ?: error("Non trovo la radice del progetto (settings.gradle) sopra ${File(".").absolutePath}")

  /** I set di sorgenti che finiscono in un APK (il resto sono prove). */
  private val PRODUCTION_SETS = listOf("main", "debug", "release", "benchmarkRelease")

  /** Le parti del percorso dove stanno contratti, null-object e fake: non sono mai superfici. */
  private val NOT_SURFACES = listOf("/contract/", "/noop/", "/testing/")

  private val INCLUDE = Regex("""^\s*include\s+['"]:([A-Za-z0-9\-]+)['"]""")

  fun modules(from: File = root): List<String> =
    File(from, "settings.gradle").takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { INCLUDE.find(it.substringBefore("//"))?.groupValues?.get(1) }

  fun rel(file: File, from: File = root): String = file.relativeTo(from).invariantSeparatorsPath

  /** Tutti i sorgenti Kotlin di produzione di tutti i moduli sotto [from]. */
  fun production(from: File = root): Sequence<File> =
    modules(from).asSequence().flatMap { module ->
      PRODUCTION_SETS.asSequence().map { File(from, "$module/src/$it") }.filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" } }
    }

  private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
  private val LINE_COMMENT = Regex("""//[^\n]*""")

  /** Il codice di [file] senza commenti (una rete a maglie larghe: basta per cercare i nomi dei contratti). */
  fun code(file: File): String = file.readText().replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

  private fun mentions(file: File, names: List<String>): Boolean {
    val code = code(file)
    return names.any { Regex("""\b$it\b""").containsMatchIn(code) }
  }

  /** I file di produzione che nominano uno dei [names] (i contratti dei nascosti), esclusi contratti, null-object e fake. */
  fun consumers(names: List<String>, from: File = root): List<String> =
    production(from).map { rel(it, from) }
      .filter { path -> NOT_SURFACES.none { it in "/$path" } }
      .filter { path -> mentions(File(from, path), names) }
      .toList()

  /**
   * I file di produzione di `feature-*` e di `:app` (le schermate e chi le lega) che nominano uno dei [names]: i tipi che portano **nome e cartella**
   * di un file del NAS. I `core-*` producono i dati e non li disegnano. Esclusi contratti, null-object e fake come per [consumers].
   */
  fun nameSurfaces(names: List<String>, from: File = root): List<String> =
    consumers(names, from).filter { it.startsWith("feature-") || it.startsWith("app/") }

  /** I file che nominano i tipi dei nomi e non sono ne' in una superficie, ne' in un lettore dietro il cancello, ne' nella pagina inattiva. */
  fun unaccountedNames(entries: List<SurfaceEntry>, gated: List<GatedReader>, dormant: List<String>, names: List<String>, from: File = root): List<String> {
    val known = entries.flatMap { it.sources }.toSet() + gated.flatMap { it.sources } + dormant
    return nameSurfaces(names, from).filter { it !in known }
  }

  /** I file che nominano i contratti dei nascosti e non sono ne' elencati da una superficie ne' fra quelli che li legano. */
  fun unaccounted(entries: List<SurfaceEntry>, wiring: List<String>, names: List<String>, from: File = root): List<String> {
    val known = entries.flatMap { it.sources }.toSet() + wiring
    return consumers(names, from).filter { it !in known }
  }

  /** Le classi di test del repository (nei set di prova di ogni modulo): per sapere se una prova citata esiste davvero. */
  fun testClassNames(from: File = root): Set<String> {
    val out = HashSet<String>()
    val declared = Regex("""\b(?:abstract\s+|open\s+|internal\s+)*class\s+(\w+)""")
    for (module in modules(from)) {
      for (set in listOf("test", "testDebug", "androidTest")) {
        val dir = File(from, "$module/src/$set")
        if (!dir.isDirectory) continue
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
          declared.findAll(file.readText()).forEach { out += it.groupValues[1] }
        }
      }
    }
    return out
  }

  fun exists(path: String, from: File = root): Boolean = File(from, path).exists()
}
