package dev.pampa.mycloudgallery.core.common.architecture

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/**
 * I test di architettura della Fase 6 (`docs/fase6-progetto.md` §1.1, §4.3, §6.3, §6.4, D2, D33, D38, D41; riga P0 del §4.4). Come
 * [Phase5ArchitectureTest] leggono `settings.gradle`, i `build.gradle`, i manifest e i sorgenti di tutti i moduli (ingressi dei test
 * dichiarati in `core-common/build.gradle`). Le regole delle fasi precedenti restano in vigore per i moduli nuovi.
 *
 * Cosa vietano:
 * 1. `litertlm`, `litert` (anche l'API `org.tensorflow.lite`) o ML Kit fuori da `core-ai`, nel `build.gradle` o nei sorgenti;
 * 2. `com.google.mlkit` nei sorgenti di produzione fuori da `core-ai/.../ocr/` (D41: con la lettura spenta ML Kit non si tocca);
 *    LiteRT fuori da `core-ai/.../cutout/`;
 * 3. `MlKit.initialize` fuori dal cancello `core-ai/.../ocr/MlKitBootstrap.kt`;
 * 4. un `TextReadingConsent` costruito fuori dalla pagina di attivazione di `feature-search` (`TextReadingActivation*.kt`);
 * 5. codice HTTP/TLS (`javax.net.ssl`, `java.net.URL`, `URLConnection`, `SSLSocket`, `android.net.http`, librerie; I3 della revisione:
 *    anche `Network.openConnection`, `DownloadManager`, `WebView`, `HttpEngine`, `java.net.http`) fuori da
 *    `core-models/.../download/https/`, **nei moduli dell'engine compresi** (`engine-foundation`, `engine-ui`: l'APK li contiene ma
 *    `settings.gradle` li include con un ciclo); un altro modulo che nomina quel pacchetto; una sua classe non `internal`; un'eccezione a
 *    `NoNetworkSources` usata da un altro modulo o diversa da quella;
 * 6. `core-search` che vede `core-models`, `core-index`, `core-nas` (o `core-prepare`, `core-nasops`, `core-backup`), anche solo
 *    nominandone i pacchetti; di `core-ai` solo i contratti; `feature-search` che vede `core-nas`, `core-index`, `core-nasops` o
 *    `core-backup`, o nomina il codice dei motori e dello scaricamento;
 * 7. `feature-viewer` che vede `core-ai` o `core-search` (o `core-models`), nemmeno per inferenza;
 * 8. chi legge il NAS o mostra la Libreria che raggiunge `core-ai`, `core-models` o `core-search`;
 * 9. dipendenze dei moduli nuovi oltre il §1.1; chi dipende dai moduli nuovi oltre un elenco chiuso; moduli nuovi senza
 *    `contract`/`noop`/`testing` o senza i test di guardia; `core-*` nuovi con Hilt; manifest con componenti o permessi (core-ai: solo
 *    la rimozione di `MlKitInitProvider` e le due `uses-native-library`);
 * 10. `-Xskip-metadata-version-check` fuori da `core-ai`; l'API degli strumenti di LiteRT-LM (che usa kotlin-reflect, tolto) e
 *    `AiPackModelProvider` di LiteRT (che usa Play AI Delivery, tolto) nei sorgenti; le due esclusioni tolte da core-ai/build.gradle;
 * 11. `VerifiedModel` costruito fuori da `core-models` (il verificatore, D16).
 *
 * Ogni regola e' stata provata reintroducendo la violazione (il test fallisce) e togliendola (P0 della Fase 6, docs/decisioni.md).
 */
class Phase6ArchitectureTest {

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

  /** I sorgenti Kotlin/Java di [module] negli insiemi [sets] (`main`, `test`, `androidTest`, `debug`...). */
  /**
   * I3: i moduli dell'engine che entrano nell'APK, come percorsi dalla radice (`engine/engine-ui`). `settings.gradle` li include con un
   * ciclo (`include ":$name"`), quindi [modules] non li vede: si leggono dal blocco "fluid-engine".
   */
  private val engineModules: List<String> by lazy {
    val text = File(root, "settings.gradle").readText()
    val block = text.substringAfter("fluid-engine (inizio)", "").substringBefore("fluid-engine (fine)")
    ENGINE_NAME.findAll(block).map { "engine/${it.groupValues[1]}" }.toList()
  }

  private fun sources(module: String, sets: List<String>): Sequence<File> =
    sets.asSequence().map { File(root, "$module/src/$it") }.filter { it.isDirectory }
      .flatMap { dir -> dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }

  private fun rel(file: File): String = file.relativeTo(root).invariantSeparatorsPath

  private fun code(file: File): String = NoNetworkSources.stripComments(file.readText())

  /** I file di produzione (tutti i moduli, `main` e le varianti) che contengono [pattern], come percorsi dalla radice. */
  private fun productionMentions(pattern: Regex, sets: List<String> = PRODUCTION_SETS): List<String> =
    (modules + engineModules).flatMap { module -> sources(module, sets).filter { pattern.containsMatchIn(code(it)) }.map { rel(it) }.toList() }

  private fun buildText(module: String): String {
    val file = File(root, "$module/build.gradle")
    return if (file.isFile) NoNetworkSources.stripComments(file.readText()) else ""
  }

  @Test
  fun ilControlloVedeIModuliDellaFase6() {
    assertThat(modules).containsAtLeast(MODELS, AI, SEARCH, FEATURE_SEARCH, "core-library", "feature-viewer")
    assertThat(direct(AI)).contains(MODELS)
    assertThat(direct(SEARCH)).contains(AI)
    assertThat(File(root, "$AI/src/main/AndroidManifest.xml").isFile).isTrue()
  }

  /** 1. D2, D12, D44: le tre librerie nuove solo in core-ai, nel build.gradle e nei sorgenti. */
  @Test
  fun leLibrerieDeiMotoriStannoSoloInCoreAi() {
    val libs = modules.filter { it != AI && ENGINE_LIBS_IN_BUILD.containsMatchIn(buildText(it)) }
    assertWithMessage("build.gradle che nominano litertlm, litert o ML Kit fuori da core-ai").that(libs).isEmpty()
    assertWithMessage("core-ai non dichiara le tre librerie").that(ENGINE_LIBS_IN_BUILD.findAll(buildText(AI)).count()).isAtLeast(3)
    val outside = modules.filter { it != AI }.flatMap { module ->
      sources(module, ALL_SETS).filter { ENGINE_PACKAGES.containsMatchIn(code(it)) }.map { rel(it) }.toList()
    }
    assertWithMessage("sorgenti fuori da core-ai che nominano litertlm, litert o ML Kit").that(outside).isEmpty()
  }

  /** 2. D41: ML Kit solo in core-ai/.../ocr/ (produzione); LiteRT solo in core-ai/.../cutout/ (§6.4). */
  @Test
  fun mlKitSoloNelPacchettoOcrELiteRtSoloNelPacchettoCutout() {
    val mlkit = productionMentions(MLKIT_PACKAGE).filter { !it.startsWith("$AI/src/main/kotlin/$AI_PATH/ocr/") }
    assertWithMessage("ML Kit fuori da core-ai/.../ocr/").that(mlkit).isEmpty()
    val litert = productionMentions(LITERT_PACKAGE).filter { !it.startsWith("$AI/src/main/kotlin/$AI_PATH/cutout/") }
    assertWithMessage("LiteRT fuori da core-ai/.../cutout/").that(litert).isEmpty()
  }

  /** 3. D41: `MlKit.initialize` solo nel cancello. */
  @Test
  fun mlKitInitializeSoloNelCancello() {
    val calls = productionMentions(MLKIT_INITIALIZE).filter { it != "$AI/src/main/kotlin/$AI_PATH/ocr/MlKitBootstrap.kt" }
    assertWithMessage("MlKit.initialize fuori da core-ai/.../ocr/MlKitBootstrap.kt").that(calls).isEmpty()
  }

  /** 4. D41: il consenso alla lettura del testo lo costruisce solo la pagina di attivazione (e i fake dei test). */
  @Test
  fun ilConsensoSiCostruisceSoloNellaPaginaDiAttivazione() {
    val outside = modules.flatMap { module ->
      sources(module, PRODUCTION_SETS)
        .filter { file -> code(file).lineSequence().any { CONSENT_BUILT.containsMatchIn(it) && !CONSENT_DECLARED.containsMatchIn(it) } }
        .map { rel(it) }
        .filter { path -> "/testing/" !in path && !CONSENT_PAGE.matches(path) }
        .toList()
    }
    assertWithMessage("TextReadingConsent costruito fuori dalla pagina di attivazione").that(outside).isEmpty()
  }

  /** I3: il controllo vede i moduli dell'engine (un controllo che non li legge non prova niente su di loro). */
  @Test
  fun ilControlloHttpsVedeAncheLEngine() {
    assertThat(engineModules).containsExactly("engine/engine-foundation", "engine/engine-ui")
    for (module in engineModules) {
      assertWithMessage("$module senza sorgenti").that(sources(module, PRODUCTION_SETS).count()).isGreaterThan(0)
    }
    // Il difetto di prima: un file dell'engine che apriva una connessione non era controllato da nessuno. Ora un suo file e' tra quelli letti.
    val file = sources("engine/engine-ui", PRODUCTION_SETS).first()
    assertThat(productionMentions(Regex("""\bpackage\b"""))).contains(rel(file))
  }

  /** I3: i nomi del client HTTPS vietati fuori dal pacchetto ammesso coprono i buchi della revisione, e non toccano i nomi innocui. */
  @Test
  fun iNomiDelClientHttpsVietatiCoprono_IBuchiDellaRevisione() {
    val banned = listOf(
      "URL(url).open" + "Connection()",
      "val c = u as javax.net.s" + "sl.HttpsURLConnection",
      "java.net.U" + "RL(u).openStream()",
      "import java.net.URL" + "Connection",
      "network.open" + "Connection(url)",
      "getSystemService(Download" + "Manager::class.java)",
      "Web" + "View(context).loadUrl(u)",
      "import android.net.ht" + "tp.HttpEngine",
      "import android.web" + "kit.Web" + "View",
      "import java.net.ht" + "tp.HttpClient",
      "okht" + "tp3.OkHttpClient()",
      "val s: SSL" + "Socket",
    )
    for (line in banned) assertWithMessage(line).that(HTTP_CLIENT.containsMatchIn(line)).isTrue()
    // I nomi di casa (un `openStream` di una sorgente di byte nostra, MimeTypeMap, i socket del NAS) non si toccano.
    val harmless = listOf(
      "image.openStream().use { }",
      "import android.webkit.MimeTypeMap",
      "network.socketFactory.createSocket()",
      "val s = Socket()",
      "val openConnectionState = 1",
    )
    for (line in harmless) assertWithMessage(line).that(HTTP_CLIENT.containsMatchIn(line)).isFalse()
  }

  /** 5. D33: l'unico codice HTTPS sta in core-models/.../download/https/, con classi `internal`, e nessuno lo nomina da fuori. */
  @Test
  fun ilCodiceHttpsStaSoloNelPacchettoAmmesso() {
    val http = productionMentions(HTTP_CLIENT).filter { !it.startsWith(HTTPS_DIR) && it !in HTTP_RULE_DEFINITIONS }
    assertWithMessage("codice HTTP/TLS fuori da core-models/.../download/https/").that(http).isEmpty()
    val users = productionMentions(DOWNLOAD_PACKAGE, ALL_SETS).filter { !it.startsWith("$MODELS/") }
    assertWithMessage("moduli che nominano il pacchetto download di core-models").that(users).isEmpty()
    val httpsFiles = File(root, HTTPS_DIR).takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "kt" }?.toList().orEmpty()
    for (file in httpsFiles) {
      val declarations = TOP_LEVEL_DECLARATION.findAll(code(file)).map { it.value.trim() }.toList()
      val public = declarations.filter { !it.startsWith("internal") && !it.startsWith("private") }
      assertWithMessage("dichiarazioni non internal in ${rel(file)}").that(public).isEmpty()
    }
    // L'eccezione di NoNetworkSources: solo il NoNetworkSourcesTest di core-models (e la prova della regola in core-common), solo su
    // quel pacchetto.
    val exceptions = modules.flatMap { module ->
      sources(module, ALL_SETS).filter { NETWORK_EXCEPTION.containsMatchIn(code(it)) }.map { rel(it) }.toList()
    }
    assertWithMessage("eccezioni a NoNetworkSources fuori dal test di core-models").that(exceptions - NETWORK_EXCEPTION_USERS).isEmpty()
    val modelsTest = File(root, "$MODELS/src/test/kotlin/dev/pampa/mycloudgallery/core/models/NoNetworkSourcesTest.kt").readText()
    assertThat(modelsTest).contains("\"kotlin/dev/pampa/mycloudgallery/core/models/download/https/\"")
  }

  /** 6. D33: core-search non vede ne' rete ne' NAS ne' i modelli; di core-ai solo i contratti. */
  @Test
  fun coreSearchNonVedeReteNasNeModelli() {
    val visible = compileVisible(SEARCH)
    for (forbidden in SEARCH_NEVER_SEES) assertWithMessage("core-search vede $forbidden").that(visible).doesNotContain(forbidden)
    assertWithMessage("core-search dipende direttamente da moduli vietati").that(direct(SEARCH) intersect SEARCH_NEVER_SEES.toSet()).isEmpty()
    val named = sources(SEARCH, ALL_SETS).filter { SEARCH_FORBIDDEN_PACKAGES.containsMatchIn(code(it)) }.map { rel(it) }.toList()
    assertWithMessage("core-search nomina pacchetti di moduli vietati").that(named).isEmpty()
    val aiOutsideContracts = sources(SEARCH, listOf("main")).filter { file ->
      val testing = "/testing/" in rel(file)
      AI_IMPORT.findAll(code(file)).any { m -> m.groupValues[1] != "contract" && !(testing && m.groupValues[1] == "testing") }
    }.map { rel(it) }.toList()
    assertWithMessage("core-search usa di core-ai qualcosa che non e' un contratto").that(aiOutsideContracts).isEmpty()
  }

  /** 6. §1.1: feature-search e' cieca a core-nas, core-index, core-nasops e core-backup (miniature solo locali, D28). */
  @Test
  fun featureSearchNonVedeIlNas() {
    val visible = compileVisible(FEATURE_SEARCH)
    for (forbidden in listOf("core-nas", "core-index", "core-nasops", "core-backup")) {
      assertWithMessage("feature-search vede $forbidden").that(visible).doesNotContain(forbidden)
    }
    val reach = closure(FEATURE_SEARCH)
    for (forbidden in listOf("core-nasops", "core-backup")) assertWithMessage("feature-search raggiunge $forbidden").that(reach).doesNotContain(forbidden)
    val named = sources(FEATURE_SEARCH, ALL_SETS).filter { FEATURE_SEARCH_FORBIDDEN_PACKAGES.containsMatchIn(code(it)) }.map { rel(it) }.toList()
    assertWithMessage("feature-search nomina pacchetti del NAS o dei motori").that(named).isEmpty()
  }

  /** 7. §1.1: feature-viewer non vede core-ai, core-search ne' core-models (testo dal vivo e ritaglio passano da porte sue). */
  @Test
  fun featureViewerNonVedeIMotoriNeLaRicerca() {
    val reach = closure("feature-viewer")
    for (forbidden in listOf(AI, SEARCH, MODELS)) assertWithMessage("feature-viewer raggiunge $forbidden").that(reach).doesNotContain(forbidden)
  }

  /** 8. §1.1: chi legge il NAS o mostra la Libreria non raggiunge i moduli della ricerca (nessun ciclo). */
  @Test
  fun chiLeggeIlNasOMostraLaLibreriaNonVedeLaRicerca() {
    for (module in NEVER_SEE_SEARCH) {
      if (module !in modules) continue
      val reach = closure(module)
      for (forbidden in listOf(AI, MODELS, SEARCH)) assertWithMessage("$module raggiunge $forbidden").that(reach).doesNotContain(forbidden)
    }
  }

  /** 9. §1.1: dipendenze dei moduli nuovi e chi dipende da loro. */
  @Test
  fun iModuliDellaFase6DipendonoSoloDaCioCheIlProgettoPermette() {
    for ((module, allowed) in ALLOWED_DEPS) {
      assertWithMessage("$module manca in settings.gradle").that(modules).contains(module)
      assertWithMessage("$module dipende da moduli fuori dal §1.1").that(direct(module) - allowed).isEmpty()
    }
    for ((module, allowed) in ALLOWED_DEPENDENTS) {
      val dependents = modules.filter { module in direct(it) }
      assertWithMessage("chi dipende da $module").that(dependents - allowed).isEmpty()
    }
    // core-models e core-ai non vedono le primitive del NAS che scrivono o tolgono (Fasi 4 e 5).
    for (module in listOf(MODELS, AI)) {
      val reach = closure(module)
      assertWithMessage("$module raggiunge core-nasops").that(reach).doesNotContain("core-nasops")
      assertWithMessage("$module raggiunge core-backup").that(reach).doesNotContain("core-backup")
    }
  }

  /** 9. Le regole dei moduli: pacchetti fissi, test di guardia, niente Hilt nei core, manifest senza componenti ne' permessi. */
  @Test
  fun iModuliDellaFase6RispettanoLeRegoleDeiModuli() {
    for (module in NEW_MODULES) {
      val main = File(root, "$module/src/main/kotlin")
      for (pkg in listOf("contract", "noop", "testing")) {
        val found = main.walkTopDown().any { it.isDirectory && it.name == pkg && (it.listFiles()?.any { f -> f.extension == "kt" } == true) }
        assertWithMessage("$module senza il package $pkg").that(found).isTrue()
      }
      val tests = sources(module, listOf("test")).map { it.nameWithoutExtension }.toSet()
      assertWithMessage("$module senza NoNetworkSourcesTest").that(tests).contains("NoNetworkSourcesTest")
      assertWithMessage("$module senza NoWallClockSourcesTest").that(tests).contains("NoWallClockSourcesTest")
      if (module.startsWith("core-")) {
        assertWithMessage("$module usa Hilt (i core-* no)").that(Regex("""hilt|dagger|kapt""").containsMatchIn(buildText(module))).isFalse()
      }
      val manifest = File(root, "$module/src/main/AndroidManifest.xml").readText().replace(Regex("""<!--[\s\S]*?-->"""), "")
      val elements = Regex("""<(uses-permission|permission|activity|activity-alias|service|receiver|provider|uses-native-library|uses-feature)\b[^>]*>""")
        .findAll(manifest).map { it.value.replace(Regex("""\s+"""), " ") }.toList()
      if (module == AI) {
        // Solo rimozioni (`tools:node="remove"`) e le due librerie native facoltative della GPU (D3, D38, D41).
        val declared = elements.filter { "tools:node=\"remove\"" !in it }
        assertWithMessage("core-ai dichiara qualcosa oltre le due uses-native-library").that(
          declared.map { NAME.find(it)?.groupValues?.get(1) },
        ).containsExactly("libOpenCL.so", "libvndksupport.so")
        assertWithMessage("le uses-native-library di core-ai devono essere facoltative").that(declared.all { "android:required=\"false\"" in it }).isTrue()
        assertWithMessage("core-ai non toglie MlKitInitProvider").that(
          elements.any { it.startsWith("<provider") && ("com.google.ml" + "kit.common.internal.MlKitInitProvider") in it && "tools:node=\"remove\"" in it },
        ).isTrue()
      } else {
        assertWithMessage("$module dichiara permessi o componenti").that(elements).isEmpty()
      }
    }
  }

  /** 10. D2: il salto dei metadati di Kotlin solo in core-ai; niente API degli strumenti ne' AI pack; le esclusioni restano. */
  @Test
  fun leEccezioniDiKotlinEDelleDipendenzeRestanoInCoreAi() {
    val skip = modules.filter { it != AI && SKIP_METADATA.containsMatchIn(buildText(it)) }
    assertWithMessage("-Xskip-metadata-version-check fuori da core-ai").that(skip).isEmpty()
    val ai = buildText(AI)
    assertWithMessage("core-ai non toglie kotlin-reflect da litertlm-android").that(ai).contains("module: 'kotlin-reflect'")
    assertWithMessage("core-ai non ferma le coroutines di litertlm-android").that(ai).contains("exclude group: 'org.jetbrains.kotlinx'")
    assertWithMessage("core-ai non toglie Play AI Delivery da litert").that(ai).contains("module: 'ai-delivery'")
    val tools = productionMentions(UNSUPPORTED_ENGINE_API, ALL_SETS)
    assertWithMessage("API degli strumenti di LiteRT-LM o AI pack di LiteRT nei sorgenti (dipendenze tolte)").that(tools).isEmpty()
  }

  /** 11. D16: un modello verificato lo costruisce solo il verificatore di core-models. */
  @Test
  fun unModelloVerificatoNasceSoloInCoreModels() {
    val outside = productionMentions(VERIFIED_MODEL_BUILT, ALL_SETS).filter { !it.startsWith("$MODELS/") }
    assertWithMessage("VerifiedModel costruito fuori da core-models").that(outside).isEmpty()
  }

  private companion object {
    val ENGINE_NAME = Regex("""'(engine-[a-z\-]+)'""")
    val INCLUDE = Regex("""^\s*include\s+['"]:([A-Za-z0-9\-]+)['"]""")
    val PROJECT_DEP = Regex("""^\s*(\w+)\s*\(?\s*project\(\s*['"]:([A-Za-z0-9\-]+)['"]\s*\)""")
    val PRODUCTION_CONFIG = Regex("""^(\w*?)(api|Api|implementation|Implementation|compileOnly|CompileOnly|runtimeOnly|RuntimeOnly)$""")
    val NAME = Regex("""android:name="([^"]+)"""")

    const val MODELS = "core-models"
    const val AI = "core-ai"
    const val SEARCH = "core-search"
    const val FEATURE_SEARCH = "feature-search"
    const val AI_PATH = "dev/pampa/mycloudgallery/core/ai"
    const val HTTPS_DIR = "core-models/src/main/kotlin/dev/pampa/mycloudgallery/core/models/download/https/"

    val NEW_MODULES = listOf(MODELS, AI, SEARCH, FEATURE_SEARCH)
    val PRODUCTION_SETS = listOf("main", "debug", "release", "benchmarkRelease")
    val ALL_SETS = listOf("main", "debug", "release", "benchmarkRelease", "test", "androidTest", "testDebug")

    // I nomi vietati sono scritti in due pezzi: questo file non contiene mai il nome intero, cosi' non si segnala da solo.

    /** Le tre librerie (coordinate o chiavi di appDeps) in un build.gradle. */
    val ENGINE_LIBS_IN_BUILD = Regex("""\bcom\.google\.ai\.edge\.lite""" + """rt|\bcom\.google\.ml""" + """kit\b|\bappDeps\.(lite""" + """rtlmAndroid|mlkit""" + """TextRecognition|lite""" + """rt)\b""")

    /** I pacchetti delle tre librerie nei sorgenti. */
    val ENGINE_PACKAGES = Regex("""\bcom\.google\.ai\.edge\.lite""" + """rt|\bcom\.google\.ml""" + """kit\b|\borg\.tensorflow\.li""" + """te\b""")

    val MLKIT_PACKAGE = Regex("""\bcom\.google\.ml""" + """kit\b""")

    val LITERT_PACKAGE = Regex("""\bcom\.google\.ai\.edge\.lite""" + """rt\.|\borg\.tensorflow\.li""" + """te\b""")

    val MLKIT_INITIALIZE = Regex("""\bMl""" + """Kit\s*\.\s*initialize\b""")

    val CONSENT_BUILT = Regex("""\bTextReading""" + """Consent\s*\(""")
    val CONSENT_DECLARED = Regex("""\bclass\s+TextReading""" + """Consent\b""")
    val CONSENT_PAGE = Regex("""^feature-search/src/main/kotlin/dev/pampa/mycloudgallery/feature/search/(.+/)?TextReading""" + """Activation\w*\.kt$""")

    /** Client HTTP/TLS: cio' che serve per parlare con un server web (i socket di core-network e core-nas restano permessi). */
    val HTTP_CLIENT = Regex(
      """\bjavax\.net\.s""" + """sl\b|\bjava\.net\.U""" + """RL\b|\b(Http|Https)?URL""" + """Connection\b|\bSSL""" + """Socket\w*\b|\bandroid\.net\.ht""" + """tp\b|\bokht""" + """tp3\b|\bretro""" + """fit2\b|\bio\.kt""" + """or\b|\bCro""" + """net\w*\b""" +
        // I3 (revisione di Fase 6): `Network.openConnection` (la rete scelta dal sistema, senza `java.net.URL` in vista), il gestore dei
        // download di sistema, le pagine web, `HttpEngine` per nome e il client di `java.net.http`. `openStream` da solo non si vieta: e' il
        // nome di una nostra sorgente di byte (core-imaging); chi apre un URL deve comunque nominare `java.net.URL`, gia' vietato.
        """|\.open""" + """Connection\s*\(|\bDownload""" + """Manager\b|\bWeb""" + """View\w*\b|\bandroid\.web""" + """kit\.Web\w*|\bHttp""" + """Engine\b|\bjava\.net\.ht""" + """tp\b|\bUrl""" + """Request\b""",
    )

    /** Il file che DEFINISCE la regola "nessuna rete" (le stringhe con i nomi vietati sono il suo contenuto). */
    val HTTP_RULE_DEFINITIONS = setOf("core-common/src/main/kotlin/dev/pampa/mycloudgallery/core/common/testing/NoNetworkSources.kt")

    /** Chi usa l'eccezione di `NoNetworkSources` (la funzione con un pacchetto ammesso). */
    val NETWORK_EXCEPTION = Regex("""\bviolations""" + """Except\b""")
    val NETWORK_EXCEPTION_USERS = setOf(
      "core-models/src/test/kotlin/dev/pampa/mycloudgallery/core/models/NoNetworkSourcesTest.kt",
      "core-common/src/test/kotlin/dev/pampa/mycloudgallery/core/common/testing/NoNetworkSourcesTest.kt",
      "core-common/src/main/kotlin/dev/pampa/mycloudgallery/core/common/testing/NoNetworkSources.kt",
    )

    val DOWNLOAD_PACKAGE = Regex("""\bdev\.pampa\.mycloudgallery\.core\.models\.down""" + """load\b""")

    val TOP_LEVEL_DECLARATION = Regex("""(?m)^(?:(?:public|internal|private|data|sealed|abstract|open|enum|fun|value|annotation|inline)\s+)*(?:class|interface|object|fun|val|var|typealias)\b""")

    /** core-search non vede questi moduli, nemmeno nei sorgenti (pacchetti). */
    val SEARCH_NEVER_SEES = listOf(MODELS, "core-index", "core-nas", "core-prepare", "core-nasops", "core-backup")
    val SEARCH_FORBIDDEN_PACKAGES = Regex(
      """\bdev\.pampa\.mycloudgallery\.core\.(mod""" + """els|in""" + """dex|na""" + """s|pre""" + """pare|naso""" + """ps|back""" + """up)\.""",
    )
    val AI_IMPORT = Regex("""\bdev\.pampa\.mycloudgallery\.core\.ai\.(\w+)""")

    /** feature-search: niente NAS, niente indice, e dei motori e dei modelli solo contratti (e i fake nei test). */
    val FEATURE_SEARCH_FORBIDDEN_PACKAGES = Regex(
      """\bdev\.pampa\.mycloudgallery\.core\.(na""" + """s|in""" + """dex|naso""" + """ps|back""" + """up)\.|\bdev\.pampa\.mycloudgallery\.core\.(ai|models)\.(runtime|ocr|cutout|compute|down""" + """load|verify|source|shelf)\b""",
    )

    /** Chi legge il NAS, prepara o mostra la Libreria (e le loro fondamenta): non arriva mai alla ricerca. */
    val NEVER_SEE_SEARCH = listOf(
      "core-index", "core-nasmedia", "core-prepare", "core-library", "core-imaging", "core-nas", "core-trust", "core-model",
      "core-database", "core-mediastore", "core-metadata", "core-places", "core-rules", "core-nasops", "core-backup", "core-personal",
      "core-family", "feature-library", "feature-nas", "feature-viewer", "feature-backup", "feature-integrations",
    )

    /** §1.1. */
    val ALLOWED_DEPS: Map<String, Set<String>> = mapOf(
      MODELS to setOf("core-common", "core-model", "core-trust", "core-nas", "core-rules"),
      AI to setOf("core-common", "core-model", "core-trust", "core-index", "core-prepare", MODELS, "core-rules"),
      SEARCH to setOf("core-common", "core-model", "core-database", "core-library", "core-places", AI),
      FEATURE_SEARCH to setOf(SEARCH, "core-library", "core-imaging", "core-designsystem", AI, MODELS, "core-common", "core-model"),
    )

    /** Chi puo' dipendere dai moduli nuovi (elenco chiuso). */
    val ALLOWED_DEPENDENTS: Map<String, Set<String>> = mapOf(
      MODELS to setOf(AI, FEATURE_SEARCH, "app"),
      AI to setOf(SEARCH, FEATURE_SEARCH, "app"),
      SEARCH to setOf(FEATURE_SEARCH, "app"),
      FEATURE_SEARCH to setOf("app"),
    )

    val SKIP_METADATA = Regex("""Xskip-meta""" + """data-version-check""")

    /** L'API degli strumenti di LiteRT-LM (kotlin-reflect) e le AI pack di LiteRT (Play AI Delivery): dipendenze tolte. */
    val UNSUPPORTED_ENGINE_API = Regex(
      """\bcom\.google\.ai\.edge\.lite""" + """rtlm\.(Tool|ToolKt|ReflectionTool|ToolSet|ToolProvider|ToolManager|OpenApiTool|tool)\b|\bAiPack""" + """ModelProvider\b|\bcom\.google\.android\.play\.core\.ai""" + """packs\b""",
    )

    val VERIFIED_MODEL_BUILT = Regex("""\bVerified""" + """Model\s*\(""")
  }
}
