package dev.pampa.mycloudgallery.core.common.architecture

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/**
 * I test di architettura della Fase 5A (`docs/fase5-progetto.md` §1.1, §4.3, D2, D13, D28; riga P0 del §4.4). Come
 * [Phase4ArchitectureTest] leggono `settings.gradle`, i `build.gradle` e i sorgenti di tutti i moduli della galleria (ingressi dei test
 * dichiarati in `core-common/build.gradle`). Le regole della Fase 4 restano in vigore anche per i moduli nuovi (cicli, feature che non si
 * conoscono, smbj solo in core-nas, `NasFileOps` solo in core-nas e core-nasops, tutti i moduli in `galleryModules`).
 *
 * Cosa vietano in piu':
 * 1. `core-backup` che vede `core-nasops`, `core-mediastore` o nomina `NasFileOps`, `DestructiveNasAccess`, le conferme della 4B o un
 *    tipo di `core-nasops` (D2: il backup gira da solo ogni notte e deve essere **incapace** di togliere o sostituire un file);
 * 2. le primitive in sola aggiunta (`NasBackupOps`, anche con un prefisso: `Smb...`, `No...`, `...Factory`) e il loro marcatore
 *    `AppendOnlyNasAccess` fuori da `core-nas` e `core-backup`, anche come argomento del compilatore;
 * 3. un modulo che legge il NAS o mostra la Libreria che raggiunge `core-backup` (nessun dato letto dal NAS diventa un'azione del
 *    backup se non passando dal catalogo locale o dalla UI); chi puo' dipendere da `core-backup` e' un elenco chiuso;
 * 4. `feature-backup` che vede `core-nas` o `core-nasops`;
 * 5. dipendenze dei moduli nuovi oltre il §1.1; moduli nuovi senza `contract`/`noop`/`testing`, senza i test "nessuna rete" e
 *    "nessun orologio da muro", con permessi o componenti (e `core-backup` con Hilt);
 * 6. D13: la recinzione del telefono in debug (`DebugBackupFence`) nominata dal codice di `:app` che finisce nella release.
 *
 * Ogni regola e' stata provata reintroducendo la violazione (il test fallisce) e togliendola (P0 della 5A, `docs/decisioni.md`).
 */
class Phase5ArchitectureTest {

  private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle").isFile && File(it, "core-common").isDirectory }
    ?: error("Non trovo la radice del progetto (settings.gradle) sopra ${File(".").absolutePath}")

  private val modules: List<String> = File(root, "settings.gradle").readLines()
    .mapNotNull { INCLUDE.find(it.substringBefore("//"))?.groupValues?.get(1) }

  private class Edge(val to: String, val api: Boolean)

  /** Le dipendenze di produzione (`api`, `implementation`, `compileOnly`, `runtimeOnly`, anche per variante). */
  private val graph: Map<String, List<Edge>> by lazy {
    modules.associateWith { module ->
      val build = File(root, "$module/build.gradle")
      if (!build.isFile) return@associateWith emptyList()
      NoNetworkSources.stripComments(build.readText()).lineSequence().mapNotNull { line ->
        val m = PROJECT_DEP.find(line) ?: return@mapNotNull null
        val kind = PRODUCTION_CONFIG.matchEntire(m.groupValues[1]) ?: return@mapNotNull null
        val prefix = kind.groupValues[1]
        if (prefix.startsWith("test") || prefix.startsWith("androidTest")) return@mapNotNull null
        Edge(m.groupValues[2], api = kind.groupValues[2].equals("api", ignoreCase = true))
      }.toList()
    }
  }

  private fun direct(module: String): Set<String> = graph[module].orEmpty().map { it.to }.toSet()

  private fun closure(module: String): Set<String> {
    val seen = LinkedHashSet<String>()
    val queue = ArrayDeque(direct(module))
    while (queue.isNotEmpty()) {
      val next = queue.removeFirst()
      if (seen.add(next)) queue.addAll(direct(next))
    }
    return seen
  }

  private fun compileVisible(module: String): Set<String> {
    val seen = LinkedHashSet<String>()
    val queue = ArrayDeque(direct(module))
    while (queue.isNotEmpty()) {
      val next = queue.removeFirst()
      if (seen.add(next)) queue.addAll(graph[next].orEmpty().filter { it.api }.map { it.to })
    }
    return seen
  }

  private fun sources(module: String, sets: List<String> = listOf("main", "test", "androidTest")): Sequence<File> =
    sets.asSequence().map { File(root, "$module/src/$it") }.filter { it.isDirectory }
      .flatMap { dir -> dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }

  private fun offenders(pattern: Regex, allowedModules: Set<String>): List<String> =
    modules.filter { it !in allowedModules }.flatMap { module ->
      sources(module).filter { pattern.containsMatchIn(NoNetworkSources.stripComments(it.readText())) }
        .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }

  private fun mentionsIn(module: String, pattern: Regex, sets: List<String>): List<String> =
    sources(module, sets).filter { pattern.containsMatchIn(NoNetworkSources.stripComments(it.readText())) }
      .map { it.relativeTo(root).invariantSeparatorsPath }.toList()

  @Test
  fun ilControlloVedeIModuliDellaFase5() {
    assertThat(modules).containsAtLeast(BACKUP, FEATURE_BACKUP, "core-nas", "core-nasops", "core-library")
    assertThat(direct(BACKUP)).contains("core-nas")
    assertThat(compileVisible(FEATURE_BACKUP)).contains(BACKUP)
  }

  /** §1.1: le dipendenze dirette dei moduli della Fase 5. */
  @Test
  fun iModuliDellaFase5DipendonoSoloDaCioCheIlProgettoPermette() {
    for ((module, allowed) in ALLOWED_DEPS) {
      assertWithMessage("$module manca in settings.gradle").that(modules).contains(module)
      assertWithMessage("$module dipende da moduli fuori dal §1.1").that(direct(module) - allowed).isEmpty()
    }
  }

  /**
   * D2: core-backup vede il NAS **solo** in sola aggiunta. Non raggiunge core-nasops ne' core-mediastore (i byte del telefono arrivano
   * da una porta) e non nomina le primitive distruttive, il loro marcatore, le conferme della 4B o un tipo di core-nasops.
   */
  @Test
  fun coreBackupNonVedeLePrimitiveDistruttiveNeCoreNasops() {
    assertWithMessage("core-backup raggiunge core-nasops").that(closure(BACKUP)).doesNotContain(NASOPS)
    assertWithMessage("core-backup raggiunge core-mediastore").that(closure(BACKUP)).doesNotContain("core-mediastore")
    for ((what, pattern) in FORBIDDEN_IN_BACKUP) {
      assertWithMessage("core-backup nomina $what").that(mentionsIn(BACKUP, pattern, listOf("main", "test", "androidTest"))).isEmpty()
    }
    val build = NoNetworkSources.stripComments(File(root, "$BACKUP/build.gradle").readText())
    assertWithMessage("core-backup aderisce al marcatore delle primitive distruttive").that(DESTRUCTIVE_MARKER.containsMatchIn(build)).isFalse()
  }

  /** D2: le primitive in sola aggiunta (e ogni nome che le contiene) solo dove sono definite (core-nas) e in core-backup. */
  @Test
  fun lePrimitiveDelBackupSoloInCoreNasECoreBackup() {
    assertThat(offenders(BACKUP_PRIMITIVES, allowedModules = setOf("core-nas", BACKUP))).isEmpty()
  }

  /** Il marcatore `AppendOnlyNasAccess`: nessun altro modulo lo nomina ne' lo passa al compilatore. */
  @Test
  fun ilMarcatoreDelBackupNonSiNominaFuoriDaiDueModuli() {
    assertThat(offenders(APPEND_ONLY_MARKER, allowedModules = setOf("core-nas", BACKUP))).isEmpty()
    val optIns = modules.filter { module ->
      val build = File(root, "$module/build.gradle")
      build.isFile && APPEND_ONLY_MARKER.containsMatchIn(NoNetworkSources.stripComments(build.readText())) && module !in setOf("core-nas", BACKUP)
    }
    assertThat(optIns).isEmpty()
  }

  /** §1.1: chi legge il NAS o mostra la Libreria non vede core-backup; chi dipende da core-backup e' un elenco chiuso. */
  @Test
  fun chiLeggeIlNasOMostraLaLibreriaNonVedeCoreBackup() {
    for (module in NEVER_SEE_BACKUP) {
      if (module !in modules) continue
      assertWithMessage("$module raggiunge core-backup").that(closure(module)).doesNotContain(BACKUP)
    }
    val dependents = modules.filter { BACKUP in direct(it) }
    assertWithMessage("chi dipende da core-backup").that(dependents - BACKUP_DEPENDENTS).isEmpty()
  }

  /** §1.1: feature-backup parla con i contratti di core-backup; core-nas e core-nasops non sono visibili, nemmeno per inferenza. */
  @Test
  fun featureBackupNonVedeCoreNasNeCoreNasops() {
    val visible = compileVisible(FEATURE_BACKUP)
    assertWithMessage("feature-backup vede core-nas").that(visible).doesNotContain("core-nas")
    assertWithMessage("feature-backup vede core-nasops").that(visible).doesNotContain(NASOPS)
    assertWithMessage("feature-backup raggiunge core-nasops").that(closure(FEATURE_BACKUP)).doesNotContain(NASOPS)
    assertWithMessage("feature-backup nomina core-nas").that(mentionsIn(FEATURE_BACKUP, NAS_PACKAGE, listOf("main", "test"))).isEmpty()
  }

  /** Le regole dei moduli (README; fase5 §4.3): pacchetti fissi, test di guardia, niente permessi; i core-* niente Hilt. */
  @Test
  fun iModuliDellaFase5RispettanoLeRegoleDeiModuli() {
    for (module in listOf(BACKUP, FEATURE_BACKUP)) {
      val main = File(root, "$module/src/main/kotlin")
      for (pkg in listOf("contract", "noop", "testing")) {
        val found = main.walkTopDown().any { it.isDirectory && it.name == pkg && (it.listFiles()?.any { f -> f.extension == "kt" } == true) }
        assertWithMessage("$module senza il package $pkg").that(found).isTrue()
      }
      val tests = sources(module, listOf("test")).map { it.nameWithoutExtension }.toSet()
      assertWithMessage("$module senza NoNetworkSourcesTest").that(tests).contains("NoNetworkSourcesTest")
      assertWithMessage("$module senza NoWallClockSourcesTest").that(tests).contains("NoWallClockSourcesTest")
      val manifest = File(root, "$module/src/main/AndroidManifest.xml").readText().replace(Regex("""<!--[\s\S]*?-->"""), "")
      assertWithMessage("$module dichiara permessi o componenti").that(
        Regex("""<(uses-permission|permission|activity|service|receiver|provider)\b""").containsMatchIn(manifest),
      ).isFalse()
    }
    val build = NoNetworkSources.stripComments(File(root, "$BACKUP/build.gradle").readText())
    assertWithMessage("core-backup usa Hilt (i core-* no)").that(Regex("""hilt|dagger|kapt""").containsMatchIn(build)).isFalse()
  }

  /** D13: la recinzione del telefono vive in `app/src/debug`; il codice di `:app` che va in release non la nomina mai. */
  @Test
  fun laRecinzioneDelTelefonoNonEntraNellaRelease() {
    assertThat(File(root, "app/src/debug/kotlin/dev/pampa/mycloudgallery/debugtools/DebugBackup" + "Fence.kt").isFile).isTrue()
    assertThat(mentionsIn("app", DEBUG_FENCE, listOf("main", "release"))).isEmpty()
    assertThat(offenders(DEBUG_FENCE, allowedModules = setOf("app"))).isEmpty()
  }

  private companion object {
    val INCLUDE = Regex("""^\s*include\s+['"]:([A-Za-z0-9\-]+)['"]""")
    val PROJECT_DEP = Regex("""^\s*(\w+)\s*\(?\s*project\(\s*['"]:([A-Za-z0-9\-]+)['"]\s*\)""")
    val PRODUCTION_CONFIG = Regex("""^(\w*?)(api|Api|implementation|Implementation|compileOnly|CompileOnly|runtimeOnly|RuntimeOnly)$""")

    const val BACKUP = "core-backup"
    const val FEATURE_BACKUP = "feature-backup"
    const val NASOPS = "core-nasops"

    // I nomi vietati sono scritti in due pezzi: questo file non contiene mai il nome intero, cosi' non si segnala da solo.

    /** Le primitive in sola aggiunta e ogni nome che le contiene (Smb..., No..., ...Factory). */
    val BACKUP_PRIMITIVES = Regex("""\b\w*Nas""" + """BackupOps\w*\b""")

    val APPEND_ONLY_MARKER = Regex("""\bAppendOnly""" + """NasAccess\b""")

    val DESTRUCTIVE_MARKER = Regex("""\bDestructive""" + """NasAccess\b""")

    /** Cio' che core-backup non nomina mai (D2, §1.1). */
    val FORBIDDEN_IN_BACKUP: List<Pair<String, Regex>> = listOf(
      "le primitive distruttive" to Regex("""\b\w*Nas""" + """FileOps\w*\b"""),
      "il loro marcatore" to DESTRUCTIVE_MARKER,
      "le conferme della 4B" to Regex("""\b(Confirmation""" + """Issuer|Confirmation""" + """Sheet|User""" + """Confirmation|Accepted""" + """Plan)\b"""),
      "un tipo di core-nasops" to Regex("""\bdev\.pampa\.mycloudgallery\.core\.nas""" + """ops\b"""),
      "core-mediastore" to Regex("""\bdev\.pampa\.mycloudgallery\.core\.media""" + """store\b"""),
    )

    val NAS_PACKAGE = Regex("""\bdev\.pampa\.mycloudgallery\.core\.nas\.""")

    val DEBUG_FENCE = Regex("""\bDebugBackup""" + """Fence\b""")

    /** §1.1. */
    val ALLOWED_DEPS: Map<String, Set<String>> = mapOf(
      BACKUP to setOf(
        "core-common", "core-model", "core-trust", "core-nas", "core-database", "core-index", "core-prepare", "core-metadata", "core-rules",
      ),
      FEATURE_BACKUP to setOf(BACKUP, "core-library", "core-imaging", "core-designsystem", "core-common", "core-model"),
    )

    /** Chi legge il NAS, prepara o mostra la Libreria (e le loro fondamenta): non arriva mai al backup. */
    val NEVER_SEE_BACKUP = listOf(
      "core-index", "core-nasmedia", "core-prepare", "core-personal", "core-library", "core-imaging",
      "core-nas", "core-trust", "core-model", "core-database", "core-mediastore", "core-metadata", "core-rules", NASOPS,
      "feature-library", "feature-nas",
    )

    /** Chi puo' dipendere da core-backup: le sue schermate, la riga del viewer e la voce di Impostazioni (P5), la Diagnostica (P7), :app. */
    val BACKUP_DEPENDENTS = setOf(FEATURE_BACKUP, "feature-viewer", "feature-settings", "feature-diagnostics", "feature-integrations", "app")
  }
}
