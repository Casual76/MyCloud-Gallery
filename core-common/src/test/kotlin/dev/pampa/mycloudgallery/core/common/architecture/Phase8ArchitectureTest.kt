package dev.pampa.mycloudgallery.core.common.architecture

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/**
 * I test di architettura della Fase 8 (`docs/fase8-progetto.md` §1.1, §4.3, §6.3, §6.4, D2, D11-D13, D31, D34, D37; riga P0 del §4.4). Come
 * [Phase7ArchitectureTest] leggono `settings.gradle`, i `build.gradle`, i manifest e i sorgenti di tutti i moduli (ingressi dei test
 * dichiarati in `core-common/build.gradle`). Le regole delle fasi precedenti restano in vigore per i moduli nuovi (cicli, feature che non si
 * conoscono, `NasFileOps` solo in core-nas e core-nasops, LiteRT e ML Kit solo in core-ai, tutti i moduli in `galleryModules`).
 *
 * Cosa vietano in piu':
 * 1. `core-faces` che nomina `core.nasops`, `core.backup`, `core.nas`, `core.nasmedia`, `core.search`, `core.albums` o la rete (`java.net`,
 *    `javax.net`) nei sorgenti di produzione (i test: niente rete); che dipende da quei moduli, li vede a compilazione o raggiunge
 *    core-nasops, core-backup, core-search o core-albums anche a runtime (D2: nessuna strada nuova verso operazioni distruttive);
 * 2. `NasFileSystem` (e ogni nome che lo contiene) in core-faces fuori da `faces/sync/` (§1.1: solo la pubblicazione e la replica dei file
 *    `faces/<G>/<io>/`);
 * 3. `core.database` in core-faces fuori da `faces/store/` o oltre il pacchetto `faces` del database; `core.security` fuori da `faces/store/`
 *    e `faces/erase/` o oltre il pacchetto `faces` (la chiave dei volti), nel codice di produzione;
 * 4. di `core-ai` e `core-models`, in core-faces, piu' dei contratti: nel codice di produzione solo `contract` (in `testing/` anche
 *    `testing`, nei test anche `noop`);
 * 5. LiteRT fra le classi dei volti di core-ai fuori da `faces/FaceLiteRtSessions.kt`; LiteRT esposto da core-ai come `api`;
 * 6. `feature-people` che vede `core-nas`, `core-nasops`, `core-trust`, `core-ai`, `core-models`, `core-search`, `core-albums`,
 *    `core-backup` o `core-index` (anche per inferenza), che raggiunge core-nasops o core-backup, o che nomina quei pacchetti o il codice
 *    interno di core-faces (`switch`, `compute`, `grouping`, `people`, `sync`, `erase`, `store`): solo contratti, null-object e fake;
 * 7. un `FacesActivationConsent` costruito fuori da `feature-people/.../activation/FacesActivation*.kt` o un `FacesEraseConfirmation`
 *    costruito fuori da `feature-people/.../erase/FacesErase*.kt` (codice di produzione; `testing/` e i test possono); le firme che le
 *    pretendono (`FacesSwitch.activate`, `FacesEraser.erase`);
 * 8. `PersonalKind.PERSON` nel codice di produzione (D13: i nomi delle persone non vanno mai in una testa `personal`; il magazzino lo
 *    rifiuta e lo prova `Phase8FacesContractTest` di core-personal) e le prove di guardia che mancano (PERSON, area comune);
 * 9. dipendenze dei moduli nuovi oltre il §1.1; chi dipende da loro oltre un elenco chiuso; un altro modulo (tranne :app) che raggiunge
 *    core-faces; moduli nuovi senza `contract`/`noop`/`testing` o senza i test di guardia; core-faces con Hilt; manifest con componenti o
 *    permessi (§6.4);
 * 10. D34 non tenuta nella release (la regola R8 di `core-common/consumer-rules.pro` e il passo di `tools/check.ps1`) e il passo del
 *    bytecode "LiteRT solo in core-ai" tolto da `tools/check.ps1`.
 *
 * Ogni regola e' stata provata reintroducendo la violazione (il test fallisce) e togliendola (P0 della Fase 8, `docs/decisioni.md`).
 */
class Phase8ArchitectureTest {

  private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle").isFile && File(it, "core-common").isDirectory }
    ?: error("Non trovo la radice del progetto (settings.gradle) sopra ${File(".").absolutePath}")

  private val modules: List<String> = File(root, "settings.gradle").readLines()
    .mapNotNull { INCLUDE.find(it.substringBefore("//"))?.groupValues?.get(1) }

  private class Edge(val to: String, val api: Boolean)

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

  private fun sources(module: String, sets: List<String>): Sequence<File> =
    sets.asSequence().map { File(root, "$module/src/$it") }.filter { it.isDirectory }
      .flatMap { dir -> dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }

  private fun rel(file: File): String = file.relativeTo(root).invariantSeparatorsPath

  private fun code(file: File): String = NoNetworkSources.stripComments(file.readText())

  private fun mentionsIn(module: String, pattern: Regex, sets: List<String>): List<String> =
    sources(module, sets).filter { pattern.containsMatchIn(code(it)) }.map { rel(it) }.toList()

  private fun mentions(pattern: Regex, sets: List<String>): List<Pair<String, String>> =
    modules.flatMap { module -> mentionsIn(module, pattern, sets).map { module to it } }

  private fun buildText(module: String): String {
    val file = File(root, "$module/build.gradle")
    return if (file.isFile) NoNetworkSources.stripComments(file.readText()) else ""
  }

  /** I sotto-pacchetti di [prefix] nominati nel file (es. `contract` per `dev.pampa.mycloudgallery.core.ai.contract.FaceImage`). */
  private fun subPackages(file: File, prefix: String): Set<String> =
    Regex("""\b${Regex.escape(prefix)}\.(\w+)""").findAll(code(file)).map { it.groupValues[1] }.toSet()

  @Test
  fun ilControlloVedeIModuliDellaFase8() {
    assertThat(modules).containsAtLeast(FACES, FEATURE_PEOPLE, "core-ai", "core-models", "core-personal", "core-trust", "core-library")
    assertThat(direct(FACES)).containsAtLeast("core-trust", "core-personal", "core-ai", "core-library")
    assertThat(compileVisible(FEATURE_PEOPLE)).contains(FACES)
    assertThat(File(root, "$FACES_MAIN/contract/FacesState.kt").isFile).isTrue()
    assertThat(File(root, "$FACES_MAIN/contract/Erase.kt").isFile).isTrue()
    assertThat(sources(FACES, PRODUCTION_SETS).count()).isAtLeast(10)
  }

  /** 1. D2: core-faces non cancella, non cerca, non tocca gli album e non parla in rete da se': passa da porte legate da :app. */
  @Test
  fun coreFacesNonVedeNasopsBackupNasRicercaAlbumNeLaRete() {
    val named = mentionsIn(FACES, FACES_FORBIDDEN_PACKAGES, PRODUCTION_SETS)
    assertWithMessage("core-faces nomina pacchetti di moduli vietati o la rete nel codice di produzione").that(named).isEmpty()
    assertWithMessage("i test di core-faces nominano la rete").that(mentionsIn(FACES, NETWORK_PACKAGES, TEST_SETS)).isEmpty()
    assertWithMessage("core-faces dipende da moduli vietati").that(direct(FACES) intersect FACES_NEVER_SEES).isEmpty()
    val visible = compileVisible(FACES)
    for (forbidden in FACES_NEVER_SEES) assertWithMessage("core-faces vede $forbidden").that(visible).doesNotContain(forbidden)
    val reach = closure(FACES)
    for (forbidden in FACES_NEVER_REACHES) assertWithMessage("core-faces raggiunge $forbidden").that(reach).doesNotContain(forbidden)
  }

  /** 2. §1.1, D12: `NasFileSystem` (scrive solo con `publishExclusive`) solo in core-faces/.../faces/sync/. */
  @Test
  fun nasFileSystemSoloInSync() {
    val outside = mentionsIn(FACES, NAS_FILE_SYSTEM, ALL_SETS).filter { "/dev/pampa/mycloudgallery/core/faces/sync/" !in it }
    assertWithMessage("NasFileSystem in core-faces fuori da faces/sync/").that(outside).isEmpty()
  }

  /** 3. D14, D15, C39: il database dei volti solo da store/; la chiave dei volti solo da store/ ed erase/ (la distrugge per prima). */
  @Test
  fun databaseEChiaveSoloDaStoreEdErase() {
    for (file in sources(FACES, PRODUCTION_SETS)) {
      val path = rel(file)
      val database = subPackages(file, DATABASE_PACKAGE)
      if (database.isNotEmpty()) {
        assertWithMessage("$path nomina core.database fuori da faces/store/").that("$FACES_PATH/store/" in path).isTrue()
        assertWithMessage("$path nomina core.database oltre il pacchetto faces").that(database).containsExactly("faces")
      }
      val security = subPackages(file, SECURITY_PACKAGE)
      if (security.isNotEmpty()) {
        assertWithMessage("$path nomina core.security fuori da faces/store/ e faces/erase/")
          .that("$FACES_PATH/store/" in path || "$FACES_PATH/erase/" in path).isTrue()
        assertWithMessage("$path nomina core.security oltre il pacchetto faces").that(security).containsExactly("faces")
      }
    }
  }

  /** 4. D2: di core-ai e core-models solo i contratti (i motori finti nei test e in testing/). */
  @Test
  fun diCoreAiECoreModelsSoloIContratti() {
    for ((sets, allowed) in listOf(PRODUCTION_SETS to setOf("contract"), TEST_SETS to setOf("contract", "testing", "noop"))) {
      for (file in sources(FACES, sets)) {
        val path = rel(file)
        val ok = if ("/testing/" in path) allowed + "testing" else allowed
        for (prefix in listOf(AI_PACKAGE, MODELS_PACKAGE)) {
          assertWithMessage("$path nomina $prefix oltre ${ok.sorted()}").that(subPackages(file, prefix) - ok).isEmpty()
        }
      }
    }
  }

  /** 5. D2, §6.4: fra le classi dei volti di core-ai solo le sessioni nominano LiteRT; core-ai non espone LiteRT (`api`) a chi lo usa. */
  @Test
  fun liteRtFraIVoltiSoloNelleSessioni() {
    val faces = sources("core-ai", PRODUCTION_SETS).map { rel(it) to it }.filter { (path, _) -> "/core/ai/faces/" in path }
    val outside = faces.filter { (path, file) -> LITERT_PACKAGE.containsMatchIn(code(file)) && !path.endsWith("/core/ai/faces/FaceLiteRtSessions.kt") }
    assertWithMessage("LiteRT fra le classi dei volti fuori da FaceLiteRtSessions.kt").that(outside.map { it.first }.toList()).isEmpty()
    assertWithMessage("core-ai espone LiteRT come api").that(LITERT_AS_API.containsMatchIn(buildText("core-ai"))).isFalse()
    for (module in listOf(FACES, FEATURE_PEOPLE)) {
      assertWithMessage("$module nomina LiteRT").that(mentionsIn(module, LITERT_PACKAGE, ALL_SETS)).isEmpty()
    }
  }

  /** 6. §1.1: feature-people vede di core-faces solo contratti di stato e di comando; mai il NAS, trust, i motori, la ricerca o gli album. */
  @Test
  fun featurePeopleECiecaAlNasAiMotoriEAlCodiceInternoDeiVolti() {
    val visible = compileVisible(FEATURE_PEOPLE)
    for (forbidden in FEATURE_NEVER_SEES) assertWithMessage("feature-people vede $forbidden").that(visible).doesNotContain(forbidden)
    val reach = closure(FEATURE_PEOPLE)
    for (forbidden in listOf(NASOPS, BACKUP)) assertWithMessage("feature-people raggiunge $forbidden").that(reach).doesNotContain(forbidden)
    val named = mentionsIn(FEATURE_PEOPLE, FEATURE_FORBIDDEN_PACKAGES, ALL_SETS)
    assertWithMessage("feature-people nomina il NAS, trust, i motori, la ricerca, gli album o il codice interno di core-faces").that(named).isEmpty()
  }

  /** 7. D3, D31, C37: il consenso lo costruisce solo la pagina di attivazione, la conferma della cancellazione solo il foglio. */
  @Test
  fun leDueConfermeSiCostruisconoSoloNelLoroFile() {
    for ((built, declared, page) in listOf(
      Triple(CONSENT_BUILT, CONSENT_DECLARED, CONSENT_PAGE),
      Triple(ERASE_BUILT, ERASE_DECLARED, ERASE_SHEET),
    )) {
      val outside = modules.flatMap { module ->
        sources(module, PRODUCTION_SETS)
          .filter { file -> code(file).lineSequence().any { built.containsMatchIn(it) && !declared.containsMatchIn(it) } }
          .map { rel(it) }
          .filter { path -> "/testing/" !in path && !page.matches(path) }
          .toList()
      }
      assertWithMessage("conferma costruita fuori dal suo file (${page.pattern})").that(outside).isEmpty()
    }
    val state = code(File(root, "$FACES_MAIN/contract/FacesState.kt"))
    assertWithMessage("FacesSwitch.activate vuole il consenso")
      .that(Regex("""suspend\s+fun\s+activate\(\s*consent:\s*FacesActivation""" + """Consent\s*\)""").containsMatchIn(state)).isTrue()
    val erase = code(File(root, "$FACES_MAIN/contract/Erase.kt"))
    assertWithMessage("FacesEraser.erase vuole la conferma")
      .that(Regex("""suspend\s+fun\s+erase\(\s*confirmation:\s*FacesErase""" + """Confirmation\s*,""").containsMatchIn(erase)).isTrue()
  }

  /** 8. D13: nessun codice di produzione nomina il tipo riservato delle persone; le prove di guardia ci sono. */
  @Test
  fun ilTipoRiservatoDellePersoneNonSiScriveMai() {
    val named = mentions(PERSON_KIND, PRODUCTION_SETS).map { it.second }
    assertWithMessage("PersonalKind.PERSON nel codice di produzione (i nomi stanno nella classe people sotto faces/)").that(named).isEmpty()
    val guards = mapOf(
      "core-personal/src/test/kotlin/dev/pampa/mycloudgallery/core/personal/Phase8FacesContractTest.kt" to "fun unaVoceRiservataNonSiPubblicaMai",
      "core-faces/src/test/kotlin/dev/pampa/mycloudgallery/core/faces/contract/FacesRemoteContract.kt" to "fun nellAreaComuneNiente",
      "core-faces/src/test/kotlin/dev/pampa/mycloudgallery/core/faces/contract/ReferenceContractTests.kt" to "class InMemoryFacesRemoteContractTest",
    )
    for ((path, marker) in guards) {
      val file = File(root, path)
      assertWithMessage("manca la prova di guardia $path").that(file.isFile).isTrue()
      assertWithMessage("$path senza $marker").that(code(file)).contains(marker)
    }
  }

  /** 9. §1.1: dipendenze dei moduli nuovi, chi dipende da loro, regole dei moduli. */
  @Test
  fun iModuliDellaFase8RispettanoIlGrafoELeRegole() {
    for ((module, allowed) in ALLOWED_DEPS) {
      assertWithMessage("$module manca in settings.gradle").that(modules).contains(module)
      assertWithMessage("$module dipende da moduli fuori dal §1.1").that(direct(module) - allowed).isEmpty()
    }
    for ((module, allowed) in ALLOWED_DEPENDENTS) {
      val dependents = modules.filter { module in direct(it) }
      assertWithMessage("chi dipende da $module").that(dependents - allowed).isEmpty()
    }
    // Nessun altro modulo arriva a core-faces, nemmeno per inferenza: ricerca, album, viewer e impostazioni passano da porte legate da :app.
    val reaching = modules.filter { it !in setOf(FACES, FEATURE_PEOPLE, "app") && FACES in closure(it) }
    assertWithMessage("moduli che raggiungono core-faces").that(reaching).isEmpty()
    for (module in listOf(FACES, FEATURE_PEOPLE)) {
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
        Regex("""<(uses-permission|permission|activity|activity-alias|service|receiver|provider|uses-feature|uses-native-library)\b""").containsMatchIn(manifest),
      ).isFalse()
    }
    assertWithMessage("core-faces usa Hilt (i core-* no)").that(Regex("""hilt|dagger|kapt""").containsMatchIn(buildText(FACES))).isFalse()
  }

  /** 10. D34 (Q5): "dati dei volti solo sulle share di prova" resta nella release; LiteRT controllato anche nel bytecode. */
  @Test
  fun laRecinzioneDeiVoltiStaNellaReleaseELiteRtNelBytecode() {
    val rules = File(root, "core-common/consumer-rules.pro").readText()
    assertThat(rules).contains("public boolean getFacesSyncTestSharesOnly();")
    assertThat(rules).contains("public boolean facesSyncMayUse(java.lang.String);")
    val check = File(root, "tools/check.ps1").readText().lineSequence().joinToString("\n") { it.substringBefore('#') }
    assertWithMessage("tools/check.ps1 non cerca D34 nel dex di release").that(check).contains("\"facesSyncMayUse\"")
    assertWithMessage("tools/check.ps1 non controlla LiteRT nel bytecode").that(check).contains("core/ai/faces/FaceLiteRtSessions\"")
  }

  private companion object {
    val INCLUDE = Regex("""^\s*include\s+['"]:([A-Za-z0-9\-]+)['"]""")
    val PROJECT_DEP = Regex("""^\s*(\w+)\s*\(?\s*project\(\s*['"]:([A-Za-z0-9\-]+)['"]\s*\)""")
    val PRODUCTION_CONFIG = Regex("""^(\w*?)(api|Api|implementation|Implementation|compileOnly|CompileOnly|runtimeOnly|RuntimeOnly)$""")

    const val FACES = "core-faces"
    const val FEATURE_PEOPLE = "feature-people"
    const val NASOPS = "core-nasops"
    const val BACKUP = "core-backup"
    const val FACES_PATH = "dev/pampa/mycloudgallery/core/faces"
    const val FACES_MAIN = "core-faces/src/main/kotlin/$FACES_PATH"

    val PRODUCTION_SETS = listOf("main", "debug", "release", "benchmarkRelease")
    val TEST_SETS = listOf("test", "androidTest", "testDebug")
    val ALL_SETS = PRODUCTION_SETS + TEST_SETS

    // I nomi vietati sono scritti in due pezzi: questo file non contiene mai il nome intero, cosi' non si segnala da solo.

    /** core-faces non vede questi moduli (D2): ne' a compilazione ne' per nome; e non raggiunge [FACES_NEVER_REACHES] nemmeno a runtime. */
    val FACES_NEVER_SEES = setOf(NASOPS, BACKUP, "core-nas", "core-nasmedia", "core-search", "core-albums")
    val FACES_NEVER_REACHES = setOf(NASOPS, BACKUP, "core-search", "core-albums")
    val FACES_FORBIDDEN_PACKAGES = Regex(
      """\bdev\.pampa\.mycloudgallery\.core\.(na""" + """s|naso""" + """ps|nas""" + """media|back""" + """up|sea""" + """rch|alb""" + """ums)\.|\bjava""" +
        """x?\.n""" + """et\b""",
    )
    val NETWORK_PACKAGES = Regex("""\bjava""" + """x?\.n""" + """et\b""")

    val NAS_FILE_SYSTEM = Regex("""\b\w*NasFile""" + """System\w*\b""")

    const val DATABASE_PACKAGE = "dev.pampa.mycloudgallery.core." + "database"
    const val SECURITY_PACKAGE = "dev.pampa.mycloudgallery.core." + "security"
    const val AI_PACKAGE = "dev.pampa.mycloudgallery.core." + "ai"
    const val MODELS_PACKAGE = "dev.pampa.mycloudgallery.core." + "models"

    val LITERT_PACKAGE = Regex("""\bcom\.google\.ai\.edge\.lite""" + """rt\.|\borg\.tensorflow\.li""" + """te\b""")
    val LITERT_AS_API = Regex("""(?m)^\s*api\s*\(?\s*appDeps\.lite""" + """rt\b""")

    /** feature-people: niente NAS, operazioni, backup, indice, trust, motori, modelli, ricerca o album; di core-faces niente codice interno. */
    val FEATURE_NEVER_SEES = listOf("core-nas", NASOPS, BACKUP, "core-index", "core-trust", "core-ai", "core-models", "core-search", "core-albums")
    val FEATURE_FORBIDDEN_PACKAGES = Regex(
      """\bdev\.pampa\.mycloudgallery\.core\.(na""" + """s|naso""" + """ps|nas""" + """media|back""" + """up|in""" + """dex|tru""" + """st|a""" + """i|mod""" +
        """els|sea""" + """rch|alb""" + """ums)\.|""" +
        """\bdev\.pampa\.mycloudgallery\.core\.faces\.(swi""" + """tch|comp""" + """ute|group""" + """ing|peo""" + """ple|sy""" + """nc|era""" + """se|sto""" + """re)\b""",
    )

    val CONSENT_BUILT = Regex("""\bFacesActivation""" + """Consent\s*\(""")
    val CONSENT_DECLARED = Regex("""\bclass\s+FacesActivation""" + """Consent\b""")
    val CONSENT_PAGE = Regex("""^feature-people/src/main/kotlin/dev/pampa/mycloudgallery/feature/people/activation/FacesActivation\w*\.kt$""")
    val ERASE_BUILT = Regex("""\bFacesErase""" + """Confirmation\s*\(""")
    val ERASE_DECLARED = Regex("""\bclass\s+FacesErase""" + """Confirmation\b""")
    val ERASE_SHEET = Regex("""^feature-people/src/main/kotlin/dev/pampa/mycloudgallery/feature/people/erase/FacesErase\w*\.kt$""")

    val PERSON_KIND = Regex("""\bPersonalKind\s*\.\s*PER""" + """SON\b""")

    /** §1.1. core-library: le viste e la visibilita' (VisibleMedia, HiddenMask) dei contratti; le altre come dice il grafo. */
    val ALLOWED_DEPS: Map<String, Set<String>> = mapOf(
      FACES to setOf(
        "core-common", "core-model", "core-library", "core-trust", "core-index", "core-personal", "core-ai", "core-models",
        "core-database", "core-security",
      ),
      FEATURE_PEOPLE to setOf(FACES, "core-library", "core-imaging", "core-designsystem", "core-common", "core-model"),
    )

    /** Chi puo' dipendere dai moduli nuovi (elenco chiuso). */
    val ALLOWED_DEPENDENTS: Map<String, Set<String>> = mapOf(
      FACES to setOf(FEATURE_PEOPLE, "app"),
      FEATURE_PEOPLE to setOf("app"),
    )
  }
}
