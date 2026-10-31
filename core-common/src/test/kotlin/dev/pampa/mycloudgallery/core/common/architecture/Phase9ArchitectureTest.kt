package dev.pampa.mycloudgallery.core.common.architecture

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/**
 * I test di architettura della Fase 9 (`docs/fase9-progetto.md` §1.1, §4.3, §6.1, §6.3, §6.4, D2, D12, D23, D24, D26, D29, D35; risposte Q1 (c)
 * e Q6 (a) del 27/10; riga P0 del §4.4). Come [Phase8ArchitectureTest] leggono `settings.gradle`, i `build.gradle`, i manifest e i sorgenti di
 * tutti i moduli (ingressi dei test dichiarati in `core-common/build.gradle`), e `tools/check.ps1`. Le regole delle fasi precedenti restano in
 * vigore per i moduli nuovi (cicli, feature che non si conoscono, tutti i moduli in `galleryModules`, la regola 5 della Fase 6 che dalla Fase 9
 * ammette **due** pacchetti di rete: `core-models/.../download/https/` e `feature-map/.../net/`, nessun `PersonalKind` nuovo di
 * [Phase7ArchitectureTest], `PersonalKind.PERSON` mai scritto di [Phase8ArchitectureTest]).
 *
 * Cosa vietano in piu':
 * 1. `core-memories` e `core-map` che nominano classi Android (`android.`, `androidx.`) nel codice di produzione, o che dichiarano librerie,
 *    Compose, Hilt o KSP nel `build.gradle` (JVM puri, D2);
 * 2. `core-memories` che nomina `core.faces`, `core.search`, `core.albums`, `core.ai`, `core.models`, `core.nas*`, `core.network`,
 *    `core.database`, `core.backup`, `core.index`, `core.trust`, `core.map` o la rete; che maneggia coordinate (`GeoPoint`, latitudini,
 *    longitudini); che scrive voci dei dati personali da se' (`PersonalKey`, `PersonalKind`, `PersonalStore`: solo dalla porta
 *    `MemoriesPersonalSettings`); che dipende da quei moduli, li vede a compilazione o raggiunge faces, search, albums, ai, models, nasops, backup,
 *    map;
 * 3. `core-map` che nomina rete, NAS, database, album, volti, ricordi o dati personali, o MapLibre e OkHttp; che dipende da altro che le
 *    fondamenta (piu', se servira', Libreria e luoghi); che li vede a compilazione o li raggiunge;
 * 4. MapLibre (`org.maplibre`, `com.mapbox`) nominato fuori da `feature-map` (sorgenti di ogni insieme, `build.gradle`); OkHttp e Okio fuori da
 *    `feature-map/.../net/` nel codice di produzione (i test di guardia degli altri moduli li nominano come esempi); un altro modulo che
 *    nomina il pacchetto `net/`; una dichiarazione non `internal` in `net/`;
 * 5. un `MapOnlineChoice` costruito fuori da `feature-map/.../privacy/MapPrivacy*.kt` o una `RegionDownloadConfirmation` costruita fuori da
 *    `feature-map/.../regions/RegionDownload*.kt` (codice di produzione; `testing/` e i test possono); le firme che le pretendono
 *    (`MapSettings.enable`, `MapRegions.download`);
 * 6. coordinate o casa nella porta dei viaggi per i ricordi (`TripMemories`, `TripDigest`, `TripDigestView`); la "casa" (`home_estimate`,
 *    `HomeEstimate`, `HomeClearance`, `AwayFromHome`, `closeToHome`, `isAway`, `distanceKm`) nominata dai moduli della Fase 9 (D29, C31);
 * 7. `feature-memories` che vede o nomina core-faces, core-search, core-albums, core-map, core-personal, il NAS, l'indice, trust, i motori,
 *    i modelli o la rete, o il codice interno di core-memories (`plan`, `pick`, `shelf`, `settings`, `notify`): solo contratti, null-object e fake;
 * 8. `feature-map` che vede o nomina core-network, core-nas, core-albums, core-memories, core-faces, core-search, core-personal, l'indice, trust, i
 *    motori o i modelli, o il codice interno di core-map (`points`, `regions`, `policy`);
 * 9. `feature-viewer` che raggiunge core-map, feature-map o core-memories, o nomina MapLibre (la mappa del viewer e' una porta, D30);
 * 10. dipendenze dei moduli nuovi oltre il §1.1; chi dipende da loro oltre un elenco chiuso; moduli nuovi senza `contract`/`noop`/`testing` o
 *    senza i test di guardia; `core-*` nuovi con Hilt; manifest con componenti o permessi (D35: nessun componente, nessun permesso nuovo);
 * 11. Q6 (a): nessun filtro automatico dei ricordi tristi (nessun segnale "triste" nei contratti); i passi della Fase 9 tolti da
 *    `tools/check.ps1` (OkHttp e MapLibre al loro posto nel bytecode, lo strumento di valutazione dei ricordi assente dalla release).
 *
 * Ogni regola e' stata provata reintroducendo la violazione (il test fallisce) e togliendola (P0 della Fase 9, `docs/decisioni.md`).
 */
class Phase9ArchitectureTest {

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

  /** Le righe del `build.gradle` di [module] che non sono di test (le dipendenze di produzione). */
  private fun productionBuildLines(module: String): List<String> = buildText(module).lineSequence()
    .filter { line -> TEST_CONFIG.find(line) == null }.toList()

  @Test
  fun ilControlloVedeIModuliDellaFase9() {
    assertThat(modules).containsAtLeast(MEMORIES, FEATURE_MEMORIES, MAP, FEATURE_MAP, "core-albums", "core-personal", "feature-viewer")
    assertThat(compileVisible(FEATURE_MEMORIES)).contains(MEMORIES)
    assertThat(compileVisible(FEATURE_MAP)).contains(MAP)
    assertThat(File(root, "$MAP_MAIN/contract/Policy.kt").isFile).isTrue()
    assertThat(File(root, "$MEMORIES_MAIN/contract/Memories.kt").isFile).isTrue()
    assertThat(sources(MEMORIES, PRODUCTION_SETS).count()).isAtLeast(10)
    assertThat(sources(MAP, PRODUCTION_SETS).count()).isAtLeast(8)
  }

  /** 1. D2: core-memories e core-map sono JVM puri: niente Android nel codice, niente librerie, Compose, Hilt o KSP nel build. */
  @Test
  fun iDueCoreSonoJvmPuri() {
    for (module in listOf(MEMORIES, MAP)) {
      assertWithMessage("$module nomina classi Android").that(mentionsIn(module, ANDROID, PRODUCTION_SETS)).isEmpty()
      val build = buildText(module)
      assertWithMessage("$module con Compose, Hilt o KSP").that(Regex("""compose|hilt|dagger|kapt|ksp""").containsMatchIn(build)).isFalse()
      val libraries = productionBuildLines(module).filter { Regex("""\bappDeps\.|['"][\w.-]+:[\w.-]+:""").containsMatchIn(it) }
      assertWithMessage("$module dichiara librerie di produzione").that(libraries).isEmpty()
    }
  }

  /** 2. D2, D10: core-memories non vede volti, ricerca, album, motori, NAS, rete, database ne' la mappa; niente coordinate; dati personali solo dalla porta. */
  @Test
  fun coreMemoriesNonVedeCioCheNonDeve() {
    assertWithMessage("core-memories nomina moduli vietati o la rete").that(mentionsIn(MEMORIES, MEMORIES_FORBIDDEN_PACKAGES, PRODUCTION_SETS)).isEmpty()
    assertWithMessage("core-memories maneggia coordinate").that(mentionsIn(MEMORIES, COORDINATES, PRODUCTION_SETS)).isEmpty()
    assertWithMessage("core-memories scrive voci dei dati personali da se'").that(mentionsIn(MEMORIES, PERSONAL_RECORDS, PRODUCTION_SETS)).isEmpty()
    assertWithMessage("i test di core-memories nominano la rete").that(mentionsIn(MEMORIES, NETWORK_PACKAGES, TEST_SETS)).isEmpty()
    assertWithMessage("core-memories dipende da moduli vietati").that(direct(MEMORIES) intersect MEMORIES_NEVER_SEES).isEmpty()
    val visible = compileVisible(MEMORIES)
    for (forbidden in MEMORIES_NEVER_SEES) assertWithMessage("core-memories vede $forbidden").that(visible).doesNotContain(forbidden)
    val reach = closure(MEMORIES)
    for (forbidden in MEMORIES_NEVER_REACHES) assertWithMessage("core-memories raggiunge $forbidden").that(reach).doesNotContain(forbidden)
  }

  /** 3. D2, D23: core-map non vede rete, NAS, database, album, volti, ricordi ne' dati personali; niente MapLibre, niente OkHttp. */
  @Test
  fun coreMapNonVedeCioCheNonDeve() {
    assertWithMessage("core-map nomina moduli vietati, la rete, MapLibre o OkHttp").that(mentionsIn(MAP, MAP_FORBIDDEN_PACKAGES, PRODUCTION_SETS)).isEmpty()
    assertWithMessage("i test di core-map nominano la rete").that(mentionsIn(MAP, NETWORK_PACKAGES, TEST_SETS)).isEmpty()
    assertWithMessage("core-map dipende da moduli vietati").that(direct(MAP) intersect MAP_NEVER_SEES).isEmpty()
    val visible = compileVisible(MAP)
    for (forbidden in MAP_NEVER_SEES) assertWithMessage("core-map vede $forbidden").that(visible).doesNotContain(forbidden)
    val reach = closure(MAP)
    for (forbidden in MAP_NEVER_REACHES) assertWithMessage("core-map raggiunge $forbidden").that(reach).doesNotContain(forbidden)
  }

  /** 4. D21, D23: MapLibre solo in feature-map; OkHttp solo in feature-map/.../net/ (produzione); nessuno nomina net/ da fuori. */
  @Test
  fun mapLibreEOkHttpSoloDoveAmmessi() {
    val mapLibre = mentions(MAPLIBRE, ALL_SETS).filter { it.first != FEATURE_MAP }.map { it.second }
    assertWithMessage("MapLibre nominato fuori da feature-map").that(mapLibre).isEmpty()
    val builds = modules.filter { it != FEATURE_MAP && MAPLIBRE_IN_BUILD.containsMatchIn(buildText(it)) }
    assertWithMessage("build.gradle con MapLibre o OkHttp fuori da feature-map").that(builds).isEmpty()
    val okhttp = mentions(OKHTTP, PRODUCTION_SETS).map { it.second }.filter { !it.startsWith(MAP_NET_DIR) }
    assertWithMessage("OkHttp o Okio nel codice di produzione fuori da feature-map/.../net/").that(okhttp).isEmpty()
    val netUsers = mentions(NET_PACKAGE, ALL_SETS).filter { it.first != FEATURE_MAP }.map { it.second }
    assertWithMessage("il pacchetto net/ di feature-map nominato da fuori").that(netUsers).isEmpty()
    val netFiles = File(root, MAP_NET_DIR).takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "kt" }?.toList().orEmpty()
    for (file in netFiles) {
      val public = TOP_LEVEL_DECLARATION.findAll(code(file)).map { it.value.trim() }.filter { !it.startsWith("internal") && !it.startsWith("private") }.toList()
      assertWithMessage("dichiarazioni non internal in ${rel(file)}").that(public).isEmpty()
    }
  }

  /** 5. Q1 (c), D26: riaccendere la mappa online e scaricare una regione si costruiscono solo dalla loro pagina, dopo un tocco. */
  @Test
  fun leDueConfermeSiCostruisconoSoloNelLoroFile() {
    for ((built, declared, page) in listOf(
      Triple(CHOICE_BUILT, CHOICE_DECLARED, CHOICE_PAGE),
      Triple(REGION_BUILT, REGION_DECLARED, REGION_SHEET),
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
    val settings = code(File(root, "$MAP_MAIN/contract/Settings.kt"))
    assertWithMessage("MapSettings.enable vuole la scelta della pagina")
      .that(Regex("""suspend\s+fun\s+enable\(\s*choice:\s*MapOnline""" + """Choice\s*\)""").containsMatchIn(settings)).isTrue()
    val regions = code(File(root, "$MAP_MAIN/contract/Regions.kt"))
    assertWithMessage("MapRegions.download vuole la conferma del foglio")
      .that(Regex("""suspend\s+fun\s+download\(\s*spec:\s*RegionSpec,\s*confirmation:\s*RegionDownload""" + """Confirmation\s*\)""").containsMatchIn(regions)).isTrue()
  }

  /** 6. D12, D29, C31: niente coordinate ne' casa nella porta dei viaggi per i ricordi; la casa non entra nei moduli della Fase 9. */
  @Test
  fun niente_CoordinateNeCasa() {
    val trips = code(File(root, "core-albums/src/main/kotlin/dev/pampa/mycloudgallery/core/albums/contract/TripMemories.kt"))
    assertWithMessage("TripMemories nomina coordinate, decimali o la casa").that(TRIP_PORT_FORBIDDEN.findAll(trips).map { it.value }.toList()).isEmpty()
    val view = code(File(root, "$MEMORIES_MAIN/contract/Ports.kt"))
    assertWithMessage("TripDigestView nomina coordinate o la casa").that(TRIP_PORT_FORBIDDEN.containsMatchIn(view.substringAfter("class TripDigestView").substringBefore("interface MemoryTrips"))).isFalse()
    for (module in NEW_MODULES) {
      assertWithMessage("$module nomina la casa").that(mentionsIn(module, HOME, ALL_SETS)).isEmpty()
    }
  }

  /** 7. §1.1: feature-memories vede di core-memories solo contratti, null-object e fake; mai volti, ricerca, album, mappa, dati personali, NAS. */
  @Test
  fun featureMemoriesECieca() {
    val visible = compileVisible(FEATURE_MEMORIES)
    for (forbidden in FEATURE_MEMORIES_NEVER_SEES) assertWithMessage("feature-memories vede $forbidden").that(visible).doesNotContain(forbidden)
    val named = mentionsIn(FEATURE_MEMORIES, FEATURE_MEMORIES_FORBIDDEN, PRODUCTION_SETS)
    assertWithMessage("feature-memories nomina moduli vietati o il codice interno di core-memories").that(named).isEmpty()
  }

  /** 8. §1.1: feature-map vede di core-map solo contratti, null-object e fake; mai la rete di sistema, il NAS, gli album, i ricordi, i volti. */
  @Test
  fun featureMapECieca() {
    val visible = compileVisible(FEATURE_MAP)
    for (forbidden in FEATURE_MAP_NEVER_SEES) assertWithMessage("feature-map vede $forbidden").that(visible).doesNotContain(forbidden)
    val named = mentionsIn(FEATURE_MAP, FEATURE_MAP_FORBIDDEN, PRODUCTION_SETS)
    assertWithMessage("feature-map nomina moduli vietati o il codice interno di core-map").that(named).isEmpty()
  }

  /** 9. D30: il viewer non vede la mappa ne' i ricordi (la mappa del viewer e' una porta sua, legata da :app). */
  @Test
  fun ilViewerNonVedeMappaNeRicordi() {
    val reach = closure("feature-viewer")
    for (forbidden in listOf(MAP, FEATURE_MAP, MEMORIES, FEATURE_MEMORIES)) assertWithMessage("feature-viewer raggiunge $forbidden").that(reach).doesNotContain(forbidden)
    assertWithMessage("feature-viewer nomina MapLibre").that(mentionsIn("feature-viewer", MAPLIBRE, ALL_SETS)).isEmpty()
  }

  /** 10. §1.1, D35: dipendenze dei moduli nuovi, chi dipende da loro, regole dei moduli, manifest senza componenti ne' permessi. */
  @Test
  fun iModuliDellaFase9RispettanoIlGrafoELeRegole() {
    for ((module, allowed) in ALLOWED_DEPS) {
      assertWithMessage("$module manca in settings.gradle").that(modules).contains(module)
      assertWithMessage("$module dipende da moduli fuori dal §1.1").that(direct(module) - allowed).isEmpty()
    }
    for ((module, allowed) in ALLOWED_DEPENDENTS) {
      val dependents = modules.filter { module in direct(it) }
      assertWithMessage("chi dipende da $module").that(dependents - allowed).isEmpty()
    }
    for (target in NEW_MODULES) {
      val reaching = modules.filter { it != target && it != "app" && target in closure(it) && it !in ALLOWED_REACHING.getValue(target) }
      assertWithMessage("moduli che raggiungono $target").that(reaching).isEmpty()
    }
    for (module in NEW_MODULES) {
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
      if (module.startsWith("core-")) {
        assertWithMessage("$module usa Hilt (i core-* no)").that(Regex("""hilt|dagger|kapt""").containsMatchIn(buildText(module))).isFalse()
      }
    }
    // Solo feature-map ha un'eccezione di rete, e solo net/: gli altri tre moduli nuovi usano il controllo pieno.
    for (module in listOf(MEMORIES, FEATURE_MEMORIES, MAP)) {
      assertWithMessage("$module usa un'eccezione di rete").that(mentionsIn(module, NETWORK_EXCEPTION, listOf("test"))).isEmpty()
    }
  }

  /** 11. Q6 (a): nessun filtro automatico; e i passi della Fase 9 di `tools/check.ps1` (bytecode di OkHttp e MapLibre, valutazione dei ricordi). */
  @Test
  fun nessunFiltroTristeEIPassiDelCheck() {
    for (module in listOf(MEMORIES, FEATURE_MEMORIES)) {
      assertWithMessage("$module ha un filtro dei ricordi tristi (Q6 (a): no)").that(mentionsIn(module, SAD_FILTER, PRODUCTION_SETS)).isEmpty()
    }
    val check = File(root, "tools/check.ps1").readText().lineSequence().joinToString("\n") { it.substringBefore('#') }
    for (marker in listOf("\"Lokhttp3/\"", "\"Lorg/maplibre/\"", "\"Ldev/pampa/mycloudgallery/feature/map/net/\"", "\"debugtools/eval/memories\"")) {
      assertWithMessage("tools/check.ps1 senza $marker").that(check).contains(marker)
    }
  }

  private companion object {
    val INCLUDE = Regex("""^\s*include\s+['"]:([A-Za-z0-9\-]+)['"]""")
    val PROJECT_DEP = Regex("""^\s*(\w+)\s*\(?\s*project\(\s*['"]:([A-Za-z0-9\-]+)['"]\s*\)""")
    val PRODUCTION_CONFIG = Regex("""^(\w*?)(api|Api|implementation|Implementation|compileOnly|CompileOnly|runtimeOnly|RuntimeOnly)$""")
    val TEST_CONFIG = Regex("""^\s*(test|androidTest)\w*\s""")

    const val MEMORIES = "core-memories"
    const val FEATURE_MEMORIES = "feature-memories"
    const val MAP = "core-map"
    const val FEATURE_MAP = "feature-map"
    const val MEMORIES_MAIN = "core-memories/src/main/kotlin/dev/pampa/mycloudgallery/core/memories"
    const val MAP_MAIN = "core-map/src/main/kotlin/dev/pampa/mycloudgallery/core/map"
    const val MAP_NET_DIR = "feature-map/src/main/kotlin/dev/pampa/mycloudgallery/feature/map/net/"

    val NEW_MODULES = listOf(MEMORIES, FEATURE_MEMORIES, MAP, FEATURE_MAP)
    val PRODUCTION_SETS = listOf("main", "debug", "release", "benchmarkRelease")
    val TEST_SETS = listOf("test", "androidTest", "testDebug")
    val ALL_SETS = PRODUCTION_SETS + TEST_SETS

    // I nomi vietati sono scritti in due pezzi: questo file non contiene mai il nome intero, cosi' non si segnala da solo.

    val ANDROID = Regex("""\band""" + """roidx?\.""")
    val NETWORK_PACKAGES = Regex("""\bjava""" + """x?\.n""" + """et\b""")

    // core-trust no: core-personal lo espone con `api` (SharePath nelle firme dei contratti); core-memories non lo nomina (regola sui nomi).
    val MEMORIES_NEVER_SEES = setOf(
      "core-faces", "core-search", "core-albums", "core-ai", "core-models", "core-nas", "core-nasops", "core-nasmedia", "core-network",
      "core-backup", "core-index", "core-security", MAP, FEATURE_MAP,
    )
    val MEMORIES_NEVER_REACHES = setOf("core-faces", "core-search", "core-albums", "core-ai", "core-models", "core-nasops", "core-backup", MAP)
    val MEMORIES_FORBIDDEN_PACKAGES = Regex(
      """\bdev\.pampa\.mycloudgallery\.core\.(fa""" + """ces|sea""" + """rch|alb""" + """ums|a""" + """i|mod""" + """els|na""" + """s|naso""" + """ps|nas""" +
        """media|netw""" + """ork|data""" + """base|back""" + """up|in""" + """dex|tru""" + """st|ma""" + """p|secu""" + """rity)\.|\bjava""" + """x?\.n""" +
        """et\b|\bokht""" + """tp3\b""",
    )
    val COORDINATES = Regex("""\bGeo""" + """Point\b|\blati""" + """tude\b|\blongi""" + """tude\b|\bGeo""" + """E6\b""")
    val PERSONAL_RECORDS = Regex("""\bPersonal""" + """(Key|Kind|Store|Entry|Value)\b""")

    val MAP_NEVER_SEES = setOf(
      "core-network", "core-nas", "core-nasops", "core-nasmedia", "core-albums", "core-faces", MEMORIES, FEATURE_MEMORIES, "core-search",
      "core-ai", "core-models", "core-backup", "core-index", "core-trust", "core-personal", "core-security",
    )
    val MAP_NEVER_REACHES = setOf("core-albums", "core-faces", MEMORIES, "core-search", "core-ai", "core-models", "core-nasops", "core-backup", "core-nasmedia")
    val MAP_FORBIDDEN_PACKAGES = Regex(
      """\bdev\.pampa\.mycloudgallery\.core\.(netw""" + """ork|na""" + """s|naso""" + """ps|nas""" + """media|data""" + """base|alb""" + """ums|fa""" + """ces|memo""" +
        """ries|perso""" + """nal|sea""" + """rch|a""" + """i|mod""" + """els|back""" + """up|in""" + """dex|tru""" + """st|secu""" + """rity)\.|\bjava""" + """x?\.n""" +
        """et\b|\bokht""" + """tp3\b|\bok""" + """io\b|\borg\.map""" + """libre\b|\bcom\.map""" + """box\b""",
    )

    val MAPLIBRE = Regex("""\borg\.map""" + """libre\b|\bcom\.map""" + """box\b""")
    val MAPLIBRE_IN_BUILD = Regex("""\borg\.map""" + """libre\b|\bcom\.map""" + """box\b|appDeps\.(map""" + """libre|okh""" + """ttp|ok""" + """io)\w*|com\.squareup\.okh""" + """ttp3""")
    val OKHTTP = Regex("""\bokht""" + """tp3\b|\bok""" + """io\.""")
    val NET_PACKAGE = Regex("""\bdev\.pampa\.mycloudgallery\.feature\.map\.n""" + """et\b""")
    val TOP_LEVEL_DECLARATION = Regex("""(?m)^(?:(?:public|internal|private|data|sealed|abstract|open|enum|fun|value|annotation|inline)\s+)*(?:class|interface|object|fun|val|var|typealias)\b""")
    val NETWORK_EXCEPTION = Regex("""\bviolations""" + """Except\b""")

    val CHOICE_BUILT = Regex("""\bMapOnline""" + """Choice\s*\(""")
    val CHOICE_DECLARED = Regex("""\bclass\s+MapOnline""" + """Choice\b""")
    val CHOICE_PAGE = Regex("""^feature-map/src/main/kotlin/dev/pampa/mycloudgallery/feature/map/privacy/MapPrivacy\w*\.kt$""")
    val REGION_BUILT = Regex("""\bRegionDownload""" + """Confirmation\s*\(""")
    val REGION_DECLARED = Regex("""\bclass\s+RegionDownload""" + """Confirmation\b""")
    val REGION_SHEET = Regex("""^feature-map/src/main/kotlin/dev/pampa/mycloudgallery/feature/map/regions/RegionDownload\w*\.kt$""")

    val TRIP_PORT_FORBIDDEN = Regex("""\bGeo""" + """Point\b|\bDou""" + """ble\b|\bFlo""" + """at\b|\bHome\w*|\bhome_\w*|\blati""" + """tude\b|\blongi""" + """tude\b|\bGeo""" + """E6\b""")
    val HOME = Regex("""(?i)\bhome_?""" + """estimate\w*|\bHome""" + """Clearance\b|\bAway""" + """FromHome\b|\bclose""" + """ToHome\b|\bis""" + """Away\b|\bdistance""" + """Km\b""")
    val SAD_FILTER = Regex("""(?i)\b\w*(sad""" + """Score|sad""" + """Filter|tri""" + """stezz|ospe""" + """dale|fune""" + """rale)\w*""")

    val FEATURE_MEMORIES_NEVER_SEES = listOf(
      "core-faces", "core-search", "core-albums", MAP, FEATURE_MAP, "core-personal", "core-nas", "core-nasops", "core-index", "core-trust", "core-backup",
      "core-ai", "core-models", "core-network",
    )
    val FEATURE_MEMORIES_FORBIDDEN = Regex(
      """\bdev\.pampa\.mycloudgallery\.core\.(fa""" + """ces|sea""" + """rch|alb""" + """ums|ma""" + """p|perso""" + """nal|na""" + """s|naso""" + """ps|nas""" +
        """media|in""" + """dex|tru""" + """st|back""" + """up|a""" + """i|mod""" + """els|netw""" + """ork)\.|""" +
        """\bdev\.pampa\.mycloudgallery\.core\.memories\.(pl""" + """an|pi""" + """ck|she""" + """lf|sett""" + """ings|noti""" + """fy)\b|""" +
        """\bdev\.pampa\.mycloudgallery\.feature\.(?!memories\b)\w+""",
    )

    val FEATURE_MAP_NEVER_SEES = listOf(
      "core-network", "core-nas", "core-albums", MEMORIES, FEATURE_MEMORIES, "core-faces", "core-search", "core-personal", "core-nasops", "core-index",
      "core-trust", "core-backup", "core-ai", "core-models",
    )
    val FEATURE_MAP_FORBIDDEN = Regex(
      """\bdev\.pampa\.mycloudgallery\.core\.(netw""" + """ork|na""" + """s|naso""" + """ps|nas""" + """media|alb""" + """ums|memo""" + """ries|fa""" + """ces|sea""" +
        """rch|perso""" + """nal|in""" + """dex|tru""" + """st|back""" + """up|a""" + """i|mod""" + """els)\.|""" +
        """\bdev\.pampa\.mycloudgallery\.core\.map\.(poi""" + """nts|regi""" + """ons|pol""" + """icy)\b|""" +
        """\bdev\.pampa\.mycloudgallery\.feature\.(?!map\b)\w+""",
    )

    /** §1.1. core-memories: le fondamenta, la Libreria e i dati personali (porta); core-map: le fondamenta (piu', se servira', Libreria e luoghi). */
    val ALLOWED_DEPS: Map<String, Set<String>> = mapOf(
      MEMORIES to setOf("core-common", "core-model", "core-library", "core-personal", "core-places"),
      FEATURE_MEMORIES to setOf(MEMORIES, "core-library", "core-imaging", "core-designsystem", "core-common", "core-model"),
      MAP to setOf("core-common", "core-model", "core-library", "core-places"),
      FEATURE_MAP to setOf(MAP, "core-library", "core-imaging", "core-places", "core-designsystem", "core-common", "core-model"),
    )

    /** Chi puo' dipendere dai moduli nuovi (elenco chiuso). */
    val ALLOWED_DEPENDENTS: Map<String, Set<String>> = mapOf(
      // Fase 10 (P0, docs/fase10-progetto.md §1.1, D2, D4): core-widget usa i soli contratti dello scaffale (`MemoryId`, `MemoryKind`) per il
      // ricordo di oggi sul widget; Phase10ArchitectureTest gli vieta il codice interno di core-memories.
      MEMORIES to setOf(FEATURE_MEMORIES, "app", "core-widget"),
      FEATURE_MEMORIES to setOf("app"),
      MAP to setOf(FEATURE_MAP, "app"),
      FEATURE_MAP to setOf("app"),
    )

    /** Chi, oltre ad :app, puo' raggiungere i moduli nuovi (nemmeno per inferenza: viewer, impostazioni, Raccolte passano da porte). */
    val ALLOWED_REACHING: Map<String, Set<String>> = mapOf(
      // Fase 10 (P0): il widget vede i contratti dello scaffale (core-widget con `api`, quindi anche feature-widget); mai il codice interno.
      MEMORIES to setOf(FEATURE_MEMORIES, "core-widget", "feature-widget"),
      FEATURE_MEMORIES to emptySet(),
      MAP to setOf(FEATURE_MAP),
      FEATURE_MAP to emptySet(),
    )
  }
}
