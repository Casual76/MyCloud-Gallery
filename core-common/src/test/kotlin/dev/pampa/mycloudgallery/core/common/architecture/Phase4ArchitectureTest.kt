package dev.pampa.mycloudgallery.core.common.architecture

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/**
 * I test di architettura della Fase 4 (`docs/fase4-progetto.md` §1.1, §4.3, D2, D28; righe P0 e P0-B del §4.4). Leggono
 * `settings.gradle`, i `build.gradle` e i sorgenti di **tutti** i moduli della galleria: per questo stanno qui, nel
 * modulo foglia, e `core-common/build.gradle` dichiara quei file come ingressi dei test (un cambio in un altro modulo
 * fa rieseguire questi test invece di lasciarli "aggiornati").
 *
 * Cosa vietano:
 * 1. cicli nel grafo dei moduli;
 * 2. dipendenze dei moduli nuovi oltre quelle del §1.1;
 * 3. `core-nasops` (4B: l'unico con operazioni distruttive) visto da chi legge il NAS o mostra la Libreria;
 * 4. `core-prepare` che conosce il NAS (riceve solo byte, D12 e §2.11: la Fase 5 lo chiama con i byte del telefono);
 * 5. due `feature-*` che si conoscono;
 * 6. smbj (`com.hierynomus`) fuori da `core-nas`, nei sorgenti e nei build;
 * 7. le primitive di scrittura del NAS (il contratto `NasFileOps`, anche con un prefisso come `Smb...`) e il marcatore
 *    `DestructiveNasAccess` fuori da `core-nas` e `core-nasops`; `feature-nas` che vede `core-nas`;
 * 8. una `UserConfirmation` costruita da altri che da `ConfirmationIssuer`, e `ConfirmationIssuer`/`ConfirmationSheet` usati
 *    fuori dalla UI (`feature-nas`, `feature-viewer`, `:app`) e dai test di `core-nasops`; un `AcceptedPlan` (l'unico ingresso del
 *    diario) costruito fuori da `ConfirmationGate`;
 * 9. moduli nuovi senza `contract`/`noop`/`testing`, senza i test "nessuna rete" e "nessun orologio da muro", con
 *    Hilt, permessi o componenti; moduli fuori dal controllo generale (`galleryModules`).
 *
 * Ogni regola e' stata provata reintroducendo la violazione (il test fallisce) e togliendola: P0 per le regole della 4A, P0-B per
 * quelle della 4B (primitive di scrittura, `UserConfirmation`, `AcceptedPlan`, marcatore).
 */
class Phase4ArchitectureTest {

  private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle").isFile && File(it, "core-common").isDirectory }
    ?: error("Non trovo la radice del progetto (settings.gradle) sopra ${File(".").absolutePath}")

  /** I moduli inclusi in settings.gradle (non quelli dell'engine, inclusi a parte dal loro blocco). */
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
        val config = m.groupValues[1]
        val kind = PRODUCTION_CONFIG.matchEntire(config) ?: return@mapNotNull null
        val prefix = kind.groupValues[1]
        if (prefix.startsWith("test") || prefix.startsWith("androidTest")) return@mapNotNull null
        Edge(m.groupValues[2], api = kind.groupValues[2].equals("api", ignoreCase = true))
      }.toList()
    }
  }

  private fun direct(module: String): Set<String> = graph[module].orEmpty().map { it.to }.toSet()

  /** Tutto cio' che [module] raggiunge (runtime). */
  private fun closure(module: String): Set<String> {
    val seen = LinkedHashSet<String>()
    val queue = ArrayDeque(direct(module))
    while (queue.isNotEmpty()) {
      val next = queue.removeFirst()
      if (seen.add(next)) queue.addAll(direct(next))
    }
    return seen
  }

  /** Cio' che [module] vede a compilazione: le sue dipendenze dirette e, di queste, le catene di `api`. */
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

  /** Ogni sorgente (main, test, androidTest) che nomina [pattern], come (modulo, percorso relativo alla radice). */
  private fun mentions(pattern: Regex): List<Pair<String, String>> = modules.flatMap { module ->
    sources(module).filter { pattern.containsMatchIn(NoNetworkSources.stripComments(it.readText())) }
      .map { module to it.relativeTo(root).invariantSeparatorsPath }.toList()
  }

  @Test
  fun ilControlloVedeIModuliEleDipendenze() {
    assertThat(modules).containsAtLeast("core-common", "core-nas", "core-index", "core-nasmedia", "core-prepare")
    assertThat(modules).containsAtLeast(NASOPS, "core-personal", "feature-nas")
    assertThat(direct("core-nas")).contains("core-trust")
    assertThat(compileVisible("feature-library")).contains("core-model")
  }

  @Test
  fun ilGrafoDeiModuliNonHaCicli() {
    val cycles = modules.filter { it in closure(it) }
    assertWithMessage("moduli che dipendono da se stessi").that(cycles).isEmpty()
  }

  /** §1.1: le dipendenze dirette dei moduli nuovi (e, quando esisteranno, di quelli della 4B). */
  @Test
  fun iModuliNuoviDipendonoSoloDaCioCheIlProgettoPermette() {
    for ((module, allowed) in ALLOWED_DEPS) {
      if (module !in modules) continue
      assertWithMessage("$module dipende da moduli fuori dal §1.1").that(direct(module) - allowed).isEmpty()
    }
  }

  /** §1.1, D2, M10: nessun dato letto dal NAS arriva a un'operazione distruttiva se non passando dalla UI. */
  @Test
  fun chiLeggeIlNasOMostraLaLibreriaNonVedeCoreNasops() {
    for (module in NEVER_SEE_NASOPS) {
      if (module !in modules) continue
      assertWithMessage("$module raggiunge core-nasops").that(closure(module)).doesNotContain(NASOPS)
    }
    val dependents = modules.filter { NASOPS in direct(it) }
    assertWithMessage("solo feature-nas, il menu del viewer e :app dipendono da core-nasops")
      .that(dependents - NASOPS_DEPENDENTS).isEmpty()
  }

  /** §1.1: `core-prepare` lavora su `MediaBytes` e su un temporaneo; non conosce SMB ne' i contratti del NAS. */
  @Test
  fun corePrepareNonConosceIlNas() {
    assertThat(compileVisible("core-prepare")).doesNotContain("core-nas")
    val imports = sources("core-prepare", listOf("main"))
      .filter { Regex("""\bdev\.pampa\.mycloudgallery\.core\.nas\.""").containsMatchIn(NoNetworkSources.stripComments(it.readText())) }
      .map { it.name }.toList()
    assertThat(imports).isEmpty()
  }

  @Test
  fun iFeatureNonSiConosconoFraLoro() {
    val features = modules.filter { it.startsWith("feature-") }
    for (feature in features) {
      assertWithMessage("$feature vede un'altra feature").that(closure(feature).filter { it.startsWith("feature-") }).isEmpty()
    }
  }

  /** D28: SMB solo in core-nas. Anche i test degli altri moduli passano dai contratti (§5.4, punto 6). */
  @Test
  fun smbjSoloInCoreNas() {
    assertThat(offenders(Regex("""\bcom\.hierynomus\b"""), allowedModules = setOf("core-nas"))).isEmpty()
    val builds = modules.filter { it != "core-nas" }.filter { module ->
      val build = File(root, "$module/build.gradle")
      build.isFile && Regex("""appDeps\.smbj\b|com\.hierynomus""").containsMatchIn(NoNetworkSources.stripComments(build.readText()))
    }
    assertThat(builds).isEmpty()
  }

  /**
   * D2, §4.3: le primitive di scrittura del NAS (4B) solo dove sono definite (core-nas/ops) e in core-nasops. Vale per il contratto e
   * per ogni suo nome derivato (`Smb...`, `InMemory...`, `No...`): un'implementazione fuori da qui sarebbe una porta di servizio.
   */
  @Test
  fun lePrimitiveDiScritturaSoloInCoreNasECoreNasops() {
    assertThat(offenders(WRITE_PRIMITIVES, allowedModules = setOf("core-nas", NASOPS))).isEmpty()
  }

  /** Il marcatore di adesione (`@RequiresOptIn`) delle primitive: nessun altro modulo lo nomina, quindi nessuno puo' aderirvi. */
  @Test
  fun ilMarcatoreDelleScrittureNonSiNominaFuoriDaiDueModuli() {
    assertThat(offenders(WRITE_MARKER, allowedModules = setOf("core-nas", NASOPS))).isEmpty()
    val optIns = modules.filter { module ->
      val build = File(root, "$module/build.gradle")
      build.isFile && WRITE_MARKER.containsMatchIn(NoNetworkSources.stripComments(build.readText())) && module !in setOf("core-nas", NASOPS)
    }
    assertThat(optIns).isEmpty()
  }

  /** `feature-nas` e il menu del viewer parlano con core-nasops: da li' non si arriva alle primitive nemmeno per inferenza. */
  @Test
  fun featureNasNonVedeCoreNas() {
    if ("feature-nas" !in modules) return
    assertThat(compileVisible("feature-nas")).doesNotContain("core-nas")
  }

  /**
   * D24, §6.3: una operazione distruttiva parte solo da una conferma costruita dalla UI dopo un tocco, mai da dati letti dal NAS.
   *  - il costruttore di `UserConfirmation` e' `internal` e lo chiama solo il file della conferma;
   *  - `ConfirmationIssuer` e `ConfirmationSheet` li usano solo `feature-nas`, `feature-viewer`, `:app` e, dentro core-nasops, il file
   *    della conferma, i test e il package `testing` (che sta fuori dalla release): mai l'esecuzione, il cestino, le pulizie;
   *  - un `AcceptedPlan` (l'unico ingresso del diario) lo costruisce solo `ConfirmationGate`, nello stesso file.
   */
  @Test
  fun unaConfermaLaCostruisceSoloLaUi() {
    if (NASOPS !in modules) return
    val confirmationFile = "$NASOPS/src/main/kotlin/dev/pampa/mycloudgallery/core/nasops/contract/Confirmation.kt"
    val uiModules = setOf("feature-nas", "feature-viewer", "app")

    val misplacedIssuers = mentions(CONFIRMATION_ISSUERS).filter { (module, path) ->
      when (module) {
        in uiModules -> false
        NASOPS -> !(path == confirmationFile || "/src/test/" in path || "/testing/" in path)
        else -> true
      }
    }
    assertWithMessage("chi costruisce la conferma e il suo foglio, fuori dalla UI").that(misplacedIssuers).isEmpty()

    val constructors = mentions(CONFIRMATION_CONSTRUCTORS).filter { it.second != confirmationFile }
    assertWithMessage("conferma o piano accettato costruiti fuori dal file della conferma").that(constructors).isEmpty()

    val file = File(root, confirmationFile)
    assertThat(file.isFile).isTrue()
    val code = NoNetworkSources.stripComments(file.readText())
    assertWithMessage("il costruttore di UserConfirmation deve essere internal").that(INTERNAL_USER_CONFIRMATION.containsMatchIn(code)).isTrue()
    assertWithMessage("il costruttore di AcceptedPlan deve essere internal").that(INTERNAL_ACCEPTED_PLAN.containsMatchIn(code)).isTrue()
  }

  /** Il diario accetta solo un piano accettato, e l'avvio solo una conferma: le firme non si indeboliscono senza far fallire questo test. */
  @Test
  fun ilDiarioELAvvioPretendonoLaConferma() {
    if (NASOPS !in modules) return
    val dir = File(root, "$NASOPS/src/main/kotlin/dev/pampa/mycloudgallery/core/nasops/contract")
    val diary = NoNetworkSources.stripComments(File(dir, "Diary.kt").readText())
    val operations = NoNetworkSources.stripComments(File(dir, "Operations.kt").readText())
    assertWithMessage("OpsStore.create vuole un AcceptedPlan")
      .that(Regex("""fun\s+create\(\s*accepted:\s*Accepted""" + """Plan""").containsMatchIn(diary)).isTrue()
    assertWithMessage("NasOperations.start vuole una UserConfirmation")
      .that(Regex("""fun\s+start\(\s*plan:\s*OpPlan,\s*confirmation:\s*User""" + """Confirmation""").containsMatchIn(operations)).isTrue()
  }

  /** Le regole dei moduli (README; fase4 §4.3): pacchetti fissi, test di guardia, niente Hilt, niente permessi. */
  @Test
  fun iModuliNuoviRispettanoLeRegoleDeiModuli() {
    for (module in PHASE4_MODULES) {
      if (module !in modules) continue
      val main = File(root, "$module/src/main/kotlin")
      for (pkg in listOf("contract", "noop", "testing")) {
        val found = main.walkTopDown().any { it.isDirectory && it.name == pkg && (it.listFiles()?.any { f -> f.extension == "kt" } == true) }
        assertWithMessage("$module senza il package $pkg").that(found).isTrue()
      }
      val tests = sources(module, listOf("test")).map { it.nameWithoutExtension }.toSet()
      assertWithMessage("$module senza NoNetworkSourcesTest").that(tests).contains("NoNetworkSourcesTest")
      assertWithMessage("$module senza NoWallClockSourcesTest").that(tests).contains("NoWallClockSourcesTest")
      val build = NoNetworkSources.stripComments(File(root, "$module/build.gradle").readText())
      assertWithMessage("$module usa Hilt (i core-* no)").that(Regex("""hilt|dagger|kapt""").containsMatchIn(build)).isFalse()
      val manifest = File(root, "$module/src/main/AndroidManifest.xml").readText().replace(Regex("""<!--[\s\S]*?-->"""), "")
      assertWithMessage("$module dichiara permessi o componenti").that(
        Regex("""<(uses-permission|permission|activity|service|receiver|provider)\b""").containsMatchIn(manifest),
      ).isFalse()
    }
  }

  /** I moduli della galleria sono tutti nel controllo generale (`galleryTest`/`galleryLint`): uno fuori non avrebbe i test eseguiti. */
  @Test
  fun ogniModuloEFraQuelliDelControlloGenerale() {
    val build = NoNetworkSources.stripComments(File(root, "build.gradle").readText())
    val block = Regex("""ext\.galleryModules\s*=\s*\[([\s\S]*?)\n\]""").find(build)?.groupValues?.get(1)
      ?: error("Non trovo ext.galleryModules in build.gradle")
    val listed = Regex("""':([A-Za-z0-9\-]+)'""").findAll(block).map { it.groupValues[1] }.toSet()
    val missing = modules.filter { it != "benchmark" && it !in listed }
    assertWithMessage("moduli fuori da galleryModules (nessun test eseguito)").that(missing).isEmpty()
  }

  /** `feature-nas` (4B) segue le regole dei moduli che hanno senso per una schermata: niente rete, niente orologio del telefono, niente permessi. */
  @Test
  fun featureNasRispettaLeRegoleDeiModuli() {
    for (module in PHASE4_FEATURES) {
      if (module !in modules) continue
      val tests = sources(module, listOf("test")).map { it.nameWithoutExtension }.toSet()
      assertWithMessage("$module senza NoNetworkSourcesTest").that(tests).contains("NoNetworkSourcesTest")
      assertWithMessage("$module senza NoWallClockSourcesTest").that(tests).contains("NoWallClockSourcesTest")
      val manifest = File(root, "$module/src/main/AndroidManifest.xml").readText().replace(Regex("""<!--[\s\S]*?-->"""), "")
      assertWithMessage("$module dichiara permessi o componenti").that(
        Regex("""<(uses-permission|permission|activity|service|receiver|provider)\b""").containsMatchIn(manifest),
      ).isFalse()
    }
  }

  private companion object {
    val INCLUDE = Regex("""^\s*include\s+['"]:([A-Za-z0-9\-]+)['"]""")
    val PROJECT_DEP = Regex("""^\s*(\w+)\s*\(?\s*project\(\s*['"]:([A-Za-z0-9\-]+)['"]\s*\)""")
    val PRODUCTION_CONFIG = Regex("""^(\w*?)(api|Api|implementation|Implementation|compileOnly|CompileOnly|runtimeOnly|RuntimeOnly)$""")

    const val NASOPS = "core-nasops"

    /** I moduli della Fase 4 (4A e 4B). */
    val PHASE4_MODULES = listOf("core-index", "core-nasmedia", "core-prepare", NASOPS, "core-personal")

    /** Le schermate della Fase 4 (4B): regole piu' leggere dei moduli `core-*`. */
    val PHASE4_FEATURES = listOf("feature-nas")

    // I nomi vietati sono scritti in due pezzi: questo file non contiene mai il nome intero, cosi' non si segnala da solo.

    /** Il contratto delle primitive di scrittura e ogni nome che lo contiene (Smb..., InMemory..., No...). */
    val WRITE_PRIMITIVES = Regex("""\b\w*Nas""" + """FileOps\w*\b""")

    /** Il marcatore di adesione alle primitive di scrittura. */
    val WRITE_MARKER = Regex("""\bDestructive""" + """NasAccess\b""")

    /** Chi costruisce la conferma dell'utente: la UI, dopo un tocco. */
    val CONFIRMATION_ISSUERS = Regex("""\b(Confirmation""" + """Issuer|Confirmation""" + """Sheet)\b""")

    /** Le chiamate ai costruttori della conferma e del piano accettato (le uniche vie verso il diario). */
    val CONFIRMATION_CONSTRUCTORS = Regex("""\b(User""" + """Confirmation|Accepted""" + """Plan)\s*\(""")

    val INTERNAL_USER_CONFIRMATION = Regex("""class\s+User""" + """Confirmation\s+internal\s+constructor""")

    val INTERNAL_ACCEPTED_PLAN = Regex("""class\s+Accepted""" + """Plan\s+internal\s+constructor""")

    /** §1.1. Le righe della 4B valgono quando P0-B creera' i moduli (fino ad allora si saltano). */
    val ALLOWED_DEPS: Map<String, Set<String>> = mapOf(
      // core-database: outbox e replica stanno nel database (§2.1, §2.2); il grafo del §1.1 non lo elencava.
      "core-index" to setOf("core-common", "core-model", "core-trust", "core-nas", "core-rules", "core-database"),
      "core-nasmedia" to setOf("core-common", "core-model", "core-trust", "core-nas", "core-metadata", "core-database", "core-index"),
      "core-prepare" to setOf("core-common", "core-model", "core-metadata", "core-imaging", "core-index"),
      NASOPS to setOf("core-common", "core-model", "core-trust", "core-nas", "core-database", "core-index"),
      "core-personal" to setOf("core-common", "core-model", "core-trust", "core-nas", "core-database", "core-index"),
      // feature-nas vede il contratto delle operazioni e non core-nas (§1.1); la Libreria e le miniature per le voci con anteprima.
      "feature-nas" to setOf(NASOPS, "core-index", "core-library", "core-imaging", "core-designsystem", "core-common", "core-model"),
    )

    /** Chi legge il NAS, prepara o mostra: non deve poter arrivare a un'operazione distruttiva. */
    val NEVER_SEE_NASOPS = listOf(
      "core-index", "core-nasmedia", "core-prepare", "core-personal",
      "core-nas", "core-trust", "core-model", "core-database",
      "core-library", "core-imaging", "core-mediastore", "core-metadata", "feature-library",
    )

    /** Chi puo' dipendere da core-nasops: le schermate della 4B, il menu del viewer (P14) e :app. */
    val NASOPS_DEPENDENTS = setOf("feature-nas", "feature-viewer", "app")
  }
}
