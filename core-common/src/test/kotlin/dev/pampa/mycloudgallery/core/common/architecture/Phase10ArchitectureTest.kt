package dev.pampa.mycloudgallery.core.common.architecture

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/**
 * I test di architettura della Fase 10 (`docs/fase10-progetto.md` §1.1, §1.3, §4.3, §6.4, D2, D9, D10, D22, D29, D31, D32, D40; risposte Q8 (a) e
 * D32 del 31/10; riga P0 del §4.4). Come [Phase9ArchitectureTest] leggono `settings.gradle`, i `build.gradle`, i manifest e i sorgenti di tutti
 * i moduli (ingressi dei test dichiarati in `core-common/build.gradle`) e `tools/check.ps1`. Le regole delle fasi precedenti restano in vigore.
 *
 * Cosa vietano in piu':
 * 1. `core-widget`, `core-release`, `core-describe` che nominano classi Android nel codice di produzione o dichiarano librerie, Compose, Hilt o
 *    KSP (JVM puri, D2);
 * 2. `core-widget` che nomina moduli che non deve vedere (volti, ricerca, album, motori, modelli, NAS, rete, database, miniature, mappa,
 *    rilascio, descrizioni, il codice interno di core-memories) o **una persona** nelle firme (`PersonId`, porte delle persone, campi `person`);
 * 3. `core-release` che nomina altro che le fondamenta o la rete; il manifest letto da Internet (Q8 (a): nessun `raw.githubusercontent`,
 *    nessuna classe `Manifest*Https`); i campi del manifest, le chiavi e il verificatore nominati fuori da core-release (solo `ReleaseStatus` ed
 *    `ExtraModelSources` escono; `app/src/debug` puo' passare la chiave di prova); un `PinnedKey` costruito fuori da `keys/` e `testing/`;
 * 4. `core-describe` che nomina volti, ricerca, motori, NAS, rete, database, indice, dati personali; una superficie di esportazione o di log
 *    in `DescribeSurface`; feature-diagnostics che nomina core-describe;
 * 5. Glance ed `engine-widget` nominati fuori da `feature-widget` (sorgenti di ogni insieme e `build.gradle`); un `PendingIntent` del widget
 *    fuori da `feature-widget/.../tap/`;
 * 6. un pacchetto di rete nuovo: i moduli con un'eccezione a `NoNetworkSources` sono solo quelli di prima (core-common, core-models,
 *    feature-map);
 * 7. D32, L559: un `sharedUserId` o un `<permission>` (anche `signature`) in qualunque manifest della galleria: un'altra app Pampa firmata con
 *    la stessa chiave non deve ottenere niente;
 * 8. dipendenze dei moduli nuovi oltre il §1.1; chi dipende da loro oltre un elenco chiuso; moduli nuovi senza `contract`/`noop`/`testing` o
 *    senza i test di guardia; `core-*` nuovi con Hilt o con componenti o permessi; `feature-widget` con componenti diversi da ricevitori non
 *    esportati o con permessi;
 * 9. i passi della Fase 10 tolti da `tools/check.ps1` (Glance al suo posto nel dex, chiavi private nel repository, la chiave di prova KT e lo
 *    strumento del manifest nella release).
 *
 * Ogni regola e' stata provata reintroducendo la violazione (il test fallisce) e togliendola (P0 della Fase 10, `docs/decisioni.md`).
 */
class Phase10ArchitectureTest {

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

  private fun productionBuildLines(module: String): List<String> = buildText(module).lineSequence()
    .filter { line -> TEST_CONFIG.find(line) == null }.toList()

  private fun manifests(): List<File> = modules.flatMap { module ->
    File(root, "$module/src").listFiles().orEmpty().map { File(it, "AndroidManifest.xml") }.filter { it.isFile }
  }

  private fun xml(file: File): String = file.readText().replace(Regex("""<!--[\s\S]*?-->"""), "")

  @Test
  fun ilControlloVedeIModuliDellaFase10() {
    assertThat(modules).containsAtLeast(WIDGET, RELEASE, DESCRIBE, FEATURE_WIDGET, "core-library", "core-memories", "core-models", "core-designsystem")
    assertThat(compileVisible(FEATURE_WIDGET)).contains(WIDGET)
    assertThat(File(root, "$WIDGET_MAIN/contract/Widget.kt").isFile).isTrue()
    assertThat(File(root, "$RELEASE_MAIN/contract/Manifest.kt").isFile).isTrue()
    assertThat(File(root, "$DESCRIBE_MAIN/contract/Facts.kt").isFile).isTrue()
    for (module in listOf(WIDGET, RELEASE, DESCRIBE)) assertThat(sources(module, PRODUCTION_SETS).count()).isAtLeast(5)
    assertThat(manifests().size).isAtLeast(40)
  }

  /** 1. D2: i tre core nuovi sono JVM puri: niente Android nel codice, niente librerie, Compose, Hilt o KSP nel build. */
  @Test
  fun iTreCoreSonoJvmPuri() {
    for (module in listOf(WIDGET, RELEASE, DESCRIBE)) {
      assertWithMessage("$module nomina classi Android").that(mentionsIn(module, ANDROID, PRODUCTION_SETS)).isEmpty()
      assertWithMessage("$module con Compose, Hilt o KSP").that(Regex("""compose|hilt|dagger|kapt|ksp""").containsMatchIn(buildText(module))).isFalse()
      val libraries = productionBuildLines(module).filter { Regex("""\bappDeps\.|['"][\w.-]+:[\w.-]+:""").containsMatchIn(it) }
      assertWithMessage("$module dichiara librerie di produzione").that(libraries).isEmpty()
      assertWithMessage("i test di $module nominano la rete").that(mentionsIn(module, NETWORK_PACKAGES, TEST_SETS)).isEmpty()
    }
  }

  /** 2. D2, D4: core-widget non vede cio' che non deve e non nomina mai una persona nelle firme. */
  @Test
  fun coreWidgetNonVedeCioCheNonDeveNeLePersone() {
    assertWithMessage("core-widget nomina moduli vietati o la rete").that(mentionsIn(WIDGET, WIDGET_FORBIDDEN, PRODUCTION_SETS)).isEmpty()
    assertWithMessage("core-widget nomina una persona").that(mentionsIn(WIDGET, PERSON_IN_SIGNATURES, PRODUCTION_SETS)).isEmpty()
    assertWithMessage("core-widget dipende da moduli vietati").that(direct(WIDGET) intersect WIDGET_NEVER_SEES).isEmpty()
    val visible = compileVisible(WIDGET)
    for (forbidden in WIDGET_NEVER_SEES) assertWithMessage("core-widget vede $forbidden").that(visible).doesNotContain(forbidden)
    val reach = closure(WIDGET)
    for (forbidden in WIDGET_NEVER_REACHES) assertWithMessage("core-widget raggiunge $forbidden").that(reach).doesNotContain(forbidden)
  }

  /** 3. D2, D29, D31, Q8 (a): core-release vede solo le fondamenta; il manifest solo dal NAS; i suoi campi e le chiavi solo dentro core-release. */
  @Test
  fun ilManifestStaDentroCoreReleaseESoloSulNas() {
    assertWithMessage("core-release nomina altri moduli o la rete").that(mentionsIn(RELEASE, RELEASE_FORBIDDEN, PRODUCTION_SETS)).isEmpty()
    assertWithMessage("core-release dipende da altro che le fondamenta").that(direct(RELEASE) - setOf("core-common", "core-model")).isEmpty()
    // Pacchetto U (12/11/2026, decisione dell'utente): l'ELENCO DEGLI AGGIORNAMENTI (non firmato, non il manifest dei blocchi) si legge da Internet, e il suo
    // indirizzo sta in un solo file di feature-update. Il manifest firmato dei blocchi resta solo sul NAS: altrove l'host resta vietato.
    val internet = mentions(MANIFEST_FROM_INTERNET, PRODUCTION_SETS).map { it.second }.filter { it != UPDATE_SOURCE_FILE }
    assertWithMessage("il manifest letto da Internet (Q8 (a): solo dal NAS)").that(internet).isEmpty()
    assertWithMessage("core-release o i suoi contratti nominati dalla sorgente degli aggiornamenti")
      .that(RELEASE_FORBIDDEN_IN_UPDATES.containsMatchIn(code(File(root, UPDATE_SOURCE_FILE)))).isFalse()
    val outside = mentions(MANIFEST_INTERNALS, PRODUCTION_SETS).filter { (module, path) -> module != RELEASE && !path.startsWith("app/src/debug/") }
    assertWithMessage("campi del manifest, chiavi o verificatore nominati fuori da core-release").that(outside.map { it.second }).isEmpty()
    val keysBuilt = modules.flatMap { module ->
      sources(module, PRODUCTION_SETS)
        .filter { file -> code(file).lineSequence().any { PINNED_KEY_BUILT.containsMatchIn(it) && !PINNED_KEY_DECLARED.containsMatchIn(it) } }
        .map { rel(it) }
        .filter { path -> "/testing/" !in path && !path.startsWith("$RELEASE_MAIN/keys/") }
        .toList()
    }
    assertWithMessage("PinnedKey costruita fuori da core-release/.../keys/ e testing/").that(keysBuilt).isEmpty()
    val origin = code(File(root, "$RELEASE_MAIN/contract/Ports.kt")).substringAfter("enum class ManifestOrigin").substringBefore("}")
    assertWithMessage("ManifestOrigin ha un'origine diversa dal NAS").that(Regex("""\b[A-Z_]+\(""").findAll(origin).map { it.value }.toList()).containsExactly("NAS(")
  }

  /** 4. D22, C438: core-describe non vede volti, ricerca, NAS, rete, dati personali; nessuna superficie di esportazione o di log. */
  @Test
  fun leDescrizioniNonEsconoDalTelefono() {
    assertWithMessage("core-describe nomina moduli vietati o la rete").that(mentionsIn(DESCRIBE, DESCRIBE_FORBIDDEN, PRODUCTION_SETS)).isEmpty()
    assertWithMessage("core-describe dipende da moduli vietati").that(direct(DESCRIBE) intersect DESCRIBE_NEVER_SEES).isEmpty()
    val visible = compileVisible(DESCRIBE)
    for (forbidden in DESCRIBE_NEVER_SEES) assertWithMessage("core-describe vede $forbidden").that(visible).doesNotContain(forbidden)
    val surfaces = code(File(root, "$DESCRIBE_MAIN/contract/Facts.kt")).substringAfter("enum class DescribeSurface").substringBefore("\n}")
    val names = Regex("""(?m)^\s*([A-Z_]+)\(""").findAll(surfaces).map { it.groupValues[1] }.toList()
    assertWithMessage("le superfici delle descrizioni").that(names).containsExactly("IN_APP", "HIDDEN_AREA", "WIDGET", "NOTIFICATION")
    assertWithMessage("feature-diagnostics nomina le descrizioni").that(mentionsIn("feature-diagnostics", DESCRIBE_PACKAGE, PRODUCTION_SETS)).isEmpty()
    assertWithMessage("feature-diagnostics dipende da core-describe").that(closure("feature-diagnostics")).doesNotContain(DESCRIBE)
  }

  /** 5. D10, D9, C433: Glance ed engine-widget solo in feature-widget; il PendingIntent del widget solo in feature-widget/.../tap/. */
  @Test
  fun glanceEIlToccoSoloNelWidget() {
    val glance = mentions(GLANCE, ALL_SETS).filter { it.first != FEATURE_WIDGET }.map { it.second }
    assertWithMessage("Glance o engine-widget nominati fuori da feature-widget").that(glance).isEmpty()
    val builds = modules.filter { it != FEATURE_WIDGET && GLANCE_IN_BUILD.containsMatchIn(buildText(it)) }
    assertWithMessage("build.gradle con Glance o engine-widget fuori da feature-widget").that(builds).isEmpty()
    val intents = sources(FEATURE_WIDGET, PRODUCTION_SETS).filter { PENDING_INTENT.containsMatchIn(code(it)) }.map { rel(it) }
      .filter { "/feature/widget/tap/" !in it }.toList()
    assertWithMessage("PendingIntent in feature-widget fuori da tap/").that(intents).isEmpty()
    val elsewhere = mentions(PENDING_INTENT, PRODUCTION_SETS).filter { (module, path) -> module != FEATURE_WIDGET && WIDGET_TAP.containsMatchIn(code(File(root, path))) }
    assertWithMessage("un PendingIntent del widget fuori da feature-widget").that(elsewhere.map { it.second }).isEmpty()
  }

  /** 6. D2, §1.1 "Rete": nessuna eccezione nuova a NoNetworkSources (nessun pacchetto di rete nuovo). */
  @Test
  fun nessunPacchettoDiReteNuovo() {
    val withException = modules.filter { mentionsIn(it, NETWORK_EXCEPTION, listOf("test")).isNotEmpty() }.toSet()
    // Pacchetto U (12/11/2026, decisione dell'utente): l'aggiornamento dentro l'app e' la terza eccezione, dichiarata (Phase6ArchitectureTest regola 5).
    assertWithMessage("moduli con un'eccezione di rete").that(withException).isEqualTo(setOf("core-common", "core-models", "feature-map", "feature-update"))
  }

  /** 7. D32, L559: nessun sharedUserId e nessun permesso definito dall'app (un'altra app Pampa con la stessa firma non ottiene niente). */
  @Test
  fun laFirmaCondivisaNonApreNiente() {
    val shared = manifests().filter { SHARED_USER.containsMatchIn(xml(it)) }.map { rel(it) }
    assertWithMessage("sharedUserId in un manifest").that(shared).isEmpty()
    val defined = manifests().filter { DEFINED_PERMISSION.containsMatchIn(xml(it)) }.map { rel(it) }
    assertWithMessage("<permission> definito in un manifest (anche signature)").that(defined).isEmpty()
  }

  /** 8. §1.1, D40: dipendenze, chi dipende dai moduli nuovi, regole dei moduli, manifest. */
  @Test
  fun iModuliDellaFase10RispettanoIlGrafoELeRegole() {
    for ((module, allowed) in ALLOWED_DEPS) {
      assertWithMessage("$module manca in settings.gradle").that(modules).contains(module)
      assertWithMessage("$module dipende da moduli fuori dal §1.1").that(direct(module) - allowed).isEmpty()
    }
    for ((module, allowed) in ALLOWED_DEPENDENTS) {
      val dependents = modules.filter { module in direct(it) }
      assertWithMessage("chi dipende da $module").that(dependents - allowed).isEmpty()
    }
    for ((target, allowed) in ALLOWED_REACHING) {
      val reaching = modules.filter { it != target && it != "app" && target in closure(it) && it !in allowed }
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
      val manifest = xml(File(root, "$module/src/main/AndroidManifest.xml"))
      assertWithMessage("$module dichiara permessi").that(Regex("""<(uses-permission|permission|uses-feature|uses-native-library)\b""").containsMatchIn(manifest)).isFalse()
      val components = Regex("""<(activity|activity-alias|service|receiver|provider)\b[^>]*>""").findAll(manifest).map { it.value }.toList()
      if (module.startsWith("core-")) {
        assertWithMessage("$module dichiara componenti").that(components).isEmpty()
        assertWithMessage("$module usa Hilt (i core-* no)").that(Regex("""hilt|dagger|kapt""").containsMatchIn(buildText(module))).isFalse()
      } else {
        val bad = components.filter { !it.startsWith("<receiver") || !Regex("""android:exported\s*=\s*"false"""").containsMatchIn(it) }
        assertWithMessage("$module: solo ricevitori non esportati").that(bad).isEmpty()
      }
    }
  }

  /** 9. I passi della Fase 10 di `tools/check.ps1`: Glance nel dex, chiavi private nel repository, KT e lo strumento del manifest nella release. */
  @Test
  fun iPassiDellaFase10NelCheck() {
    val check = File(root, "tools/check.ps1").readText().lineSequence().joinToString("\n") { it.substringBefore('#') }
    for (marker in listOf("\"Landroidx/glance/\"", "\"Ldev/pampa/mycloudgallery/feature/widget/\"", "\"ReleaseTestKeys\"", "\"ManifestTool\"", "Find-PrivateKeyFiles", "chiavi-prove.ps1")) {
      assertWithMessage("tools/check.ps1 senza $marker").that(check).contains(marker)
    }
    // R6 della revisione di sicurezza: il riconoscimento delle chiavi private sta in un file a parte, che il passo carica e prova con chiavi vere di Java.
    val keys = File(root, "tools/release/chiavi-private.ps1").readText().lineSequence().joinToString("\n") { it.substringBefore('#') }
    for (marker in listOf("\"PRIVATE KEY\"", "Get-Base64Markers", "function Find-PrivateKeyFiles", "src/(test|testDebug)/")) {
      assertWithMessage("tools/release/chiavi-private.ps1 senza $marker").that(keys).contains(marker)
    }
  }

  private companion object {
    val INCLUDE = Regex("""^\s*include\s+['"]:([A-Za-z0-9\-]+)['"]""")
    val PROJECT_DEP = Regex("""^\s*(\w+)\s*\(?\s*project\(\s*['"]:([A-Za-z0-9\-]+)['"]\s*\)""")
    val PRODUCTION_CONFIG = Regex("""^(\w*?)(api|Api|implementation|Implementation|compileOnly|CompileOnly|runtimeOnly|RuntimeOnly)$""")
    val TEST_CONFIG = Regex("""^\s*(test|androidTest)\w*\s""")

    const val WIDGET = "core-widget"
    const val RELEASE = "core-release"
    const val DESCRIBE = "core-describe"
    const val FEATURE_WIDGET = "feature-widget"
    const val WIDGET_MAIN = "core-widget/src/main/kotlin/dev/pampa/mycloudgallery/core/widget"
    const val RELEASE_MAIN = "core-release/src/main/kotlin/dev/pampa/mycloudgallery/core/release"
    const val DESCRIBE_MAIN = "core-describe/src/main/kotlin/dev/pampa/mycloudgallery/core/describe"

    val NEW_MODULES = listOf(WIDGET, RELEASE, DESCRIBE, FEATURE_WIDGET)
    val PRODUCTION_SETS = listOf("main", "debug", "release", "benchmarkRelease")
    val TEST_SETS = listOf("test", "androidTest", "testDebug")
    val ALL_SETS = PRODUCTION_SETS + TEST_SETS

    // I nomi vietati sono scritti in due pezzi: questo file non contiene mai il nome intero, cosi' non si segnala da solo.

    val ANDROID = Regex("""\band""" + """roidx?\.""")
    val NETWORK_PACKAGES = Regex("""\bjava""" + """x?\.n""" + """et\b""")
    val NETWORK_EXCEPTION = Regex("""\bviolations""" + """Except\b""")

    val WIDGET_NEVER_SEES = setOf(
      "core-faces", "core-search", "core-albums", "core-ai", "core-models", "core-nas", "core-nasops", "core-nasmedia", "core-network",
      "core-backup", "core-security", "core-personal", "core-map", RELEASE, DESCRIBE, FEATURE_WIDGET,
    )
    val WIDGET_NEVER_REACHES = setOf("core-faces", "core-search", "core-albums", "core-ai", "core-models", "core-nasops", "core-backup", "core-map", RELEASE)
    val WIDGET_FORBIDDEN = Regex(
      """\bdev\.pampa\.mycloudgallery\.core\.(fa""" + """ces|sea""" + """rch|alb""" + """ums|a""" + """i|mod""" + """els|na""" + """s|naso""" + """ps|nas""" +
        """media|netw""" + """ork|data""" + """base|back""" + """up|in""" + """dex|tru""" + """st|ma""" + """p|secu""" + """rity|ima""" + """ging|perso""" + """nal|rel""" +
        """ease|desc""" + """ribe)\.|\bdev\.pampa\.mycloudgallery\.core\.memories\.(pl""" + """an|pi""" + """ck|she""" + """lf|sett""" + """ings|noti""" + """fy)\b|""" +
        """\bandroidx\.gla""" + """nce\b|\bjava""" + """x?\.n""" + """et\b|\bokht""" + """tp3\b""",
    )
    val PERSON_IN_SIGNATURES = Regex("""\bPerson""" + """Id\b|\bMemory""" + """People\b|\bPeople\w*|\bDescribe""" + """People\b|\bperson\w*\s*:|\bname[sd]?\s*:""")

    val RELEASE_FORBIDDEN = Regex(
      """\bdev\.pampa\.mycloudgallery\.(core\.(?!common\b|model\b|release\b)\w+|feature\.\w+)\b|\bjava""" + """x?\.n""" + """et\b|\bokht""" + """tp3\b|\bandroid""" + """x?\.""",
    )
    val MANIFEST_FROM_INTERNET = Regex("""raw\.github""" + """usercontent|\bManifest\w*Ht""" + """tps\b""")

    /** Pacchetto U: l'unico file di produzione con l'host dell'elenco degli aggiornamenti (le costanti della sorgente). */
    const val UPDATE_SOURCE_FILE = "feature-update/src/main/kotlin/dev/pampa/mycloudgallery/feature/update/source/UpdateSource.kt"

    /** Il manifest firmato e le sue chiavi non c'entrano con l'elenco degli aggiornamenti: la sorgente non li nomina. */
    val RELEASE_FORBIDDEN_IN_UPDATES = Regex("""\bdev\.pampa\.mycloudgallery\.core\.rel""" + """ease\b|\bRelease""" + """Manifest\b|\bPinned""" + """Key\b""")
    val MANIFEST_INTERNALS = Regex("""\b(Release""" + """Manifest|Version""" + """Block|Model""" + """Hint|Pinned""" + """Key|Release""" + """Keys|Manifest""" + """Verifier|Manifest""" + """Codec)\b""")
    val PINNED_KEY_BUILT = Regex("""\bPinned""" + """Key\s*\(""")
    val PINNED_KEY_DECLARED = Regex("""\bclass\s+Pinned""" + """Key\b""")

    val DESCRIBE_NEVER_SEES = setOf(
      "core-faces", "core-search", "core-ai", "core-models", "core-nas", "core-nasops", "core-nasmedia", "core-network", "core-backup", "core-index",
      "core-security", "core-personal", "core-albums", "core-memories", "core-map", WIDGET, RELEASE,
    )
    val DESCRIBE_FORBIDDEN = Regex(
      """\bdev\.pampa\.mycloudgallery\.core\.(fa""" + """ces|sea""" + """rch|a""" + """i|mod""" + """els|na""" + """s|naso""" + """ps|nas""" + """media|netw""" + """ork|data""" +
        """base|back""" + """up|in""" + """dex|tru""" + """st|secu""" + """rity|perso""" + """nal|alb""" + """ums|memo""" + """ries|ma""" + """p|wid""" + """get|rel""" + """ease)\.|""" +
        """\bjava""" + """x?\.n""" + """et\b|\bokht""" + """tp3\b""",
    )
    val DESCRIBE_PACKAGE = Regex("""\bdev\.pampa\.mycloudgallery\.core\.desc""" + """ribe\b""")

    val GLANCE = Regex("""\bandroidx\.gla""" + """nce\b|\bdev\.antigravity\.fluidengine\.wid""" + """get\b""")
    val GLANCE_IN_BUILD = Regex("""\bandroidx\.gla""" + """nce\b|appDeps\.gla""" + """nce\w*|:engine-wid""" + """get\b""")
    val PENDING_INTENT = Regex("""\bPending""" + """Intent\b""")
    val WIDGET_TAP = Regex("""\bOPEN_WID""" + """GET\b|\bWidgetTap""" + """Uri\b|mcg-wid""" + """get""")

    val SHARED_USER = Regex("""\bandroid:sharedUser""" + """Id\b""")
    val DEFINED_PERMISSION = Regex("""<permi""" + """ssion\b""")

    /** Chi usa le descrizioni (P7: la stringa nelle griglie e nel viewer; P3: la superficie WIDGET). */
    val DESCRIBE_USERS = setOf(
      "feature-library", "feature-viewer", "feature-albums", "feature-memories", "feature-map", "feature-search", "feature-people", FEATURE_WIDGET, "app",
    )

    /** §1.1. */
    val ALLOWED_DEPS: Map<String, Set<String>> = mapOf(
      WIDGET to setOf("core-common", "core-model", "core-library", "core-memories"),
      RELEASE to setOf("core-common", "core-model"),
      DESCRIBE to setOf("core-common", "core-model", "core-library", "core-places"),
      FEATURE_WIDGET to setOf(WIDGET, DESCRIBE, "core-imaging", "core-designsystem", "core-common", "core-model", "engine-widget"),
    )

    /** Chi puo' dipendere dai moduli nuovi (elenco chiuso). */
    val ALLOWED_DEPENDENTS: Map<String, Set<String>> = mapOf(
      WIDGET to setOf(FEATURE_WIDGET, "app"),
      RELEASE to setOf("app"),
      DESCRIBE to DESCRIBE_USERS,
      FEATURE_WIDGET to setOf("app"),
    )

    /** Chi, oltre ad :app, puo' raggiungerli (nemmeno per inferenza: il manifest entra solo da :app). */
    val ALLOWED_REACHING: Map<String, Set<String>> = mapOf(
      WIDGET to setOf(FEATURE_WIDGET),
      RELEASE to emptySet(),
      DESCRIBE to DESCRIBE_USERS,
      FEATURE_WIDGET to emptySet(),
    )
  }
}
