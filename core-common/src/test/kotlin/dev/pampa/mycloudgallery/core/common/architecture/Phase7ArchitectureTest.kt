package dev.pampa.mycloudgallery.core.common.architecture

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import org.junit.Test

/**
 * I test di architettura della Fase 7 (`docs/fase7-progetto.md` §1.1, §4.3, §6.3, §6.4, D1, D2, D4, D12, D18, D30; riga P0 del §4.4). Come
 * [Phase6ArchitectureTest] leggono `settings.gradle`, i `build.gradle`, i manifest e i sorgenti di tutti i moduli (ingressi dei test
 * dichiarati in `core-common/build.gradle`). Le regole delle fasi precedenti restano in vigore per i moduli nuovi (cicli, feature che non si
 * conoscono, `NasFileOps` solo in core-nas e core-nasops, `UserConfirmation` solo da `ConfirmationIssuer`, tutti i moduli in
 * `galleryModules`).
 *
 * Cosa vietano in piu':
 * 1. `core-albums` che nomina `core.nasops`, `core.backup`, `core.nas` (o `core.nasmedia`, `core.index`, `core.ai`, `core.models`) o la
 *    rete (`java.net`, `javax.net`) nei sorgenti di produzione; che dipende da quei moduli, li vede a compilazione o raggiunge
 *    core-nasops o core-backup anche a runtime (D1, D2: nessuna strada nuova verso operazioni distruttive);
 * 2. `NasFileSystem` (e ogni nome che lo contiene) in core-albums fuori da `albums/shared/` (§1.1: solo il pubblicatore e la replica dei
 *    file `albums/`);
 * 3. `home_estimate` (o `HomeEstimate`) nel codice che va in un APK fuori da core-database e da `core-albums/.../albums/trips/` (D18: la
 *    "casa" solo locale, mai fuori dai viaggi);
 * 4. una `ShareConfirmation` costruita fuori dal suo file, o con il costruttore non `internal`; emittente e foglio usati fuori dalla UI
 *    (feature-albums, :app) e, in core-albums, dai test e da `testing/`; `ShareRunner.start`, `AlbumMover.move` e `AlbumUploader.upload`
 *    che non pretendono piu' la conferma; core-albums e feature-albums che nominano le conferme di core-nasops (`UserConfirmation`,
 *    `ConfirmationIssuer`, `ConfirmationSheet`, `AcceptedPlan`): quelle le emette solo :app (la regola della 4B in Phase4ArchitectureTest);
 * 5. un `PersonalKind` nuovo (D4: un tipo nuovo farebbe scartare il segmento intero alle app vecchie: i campi nuovi stanno dentro i tipi
 *    esistenti);
 * 6. feature-albums che vede core-nas, core-nasops, core-backup, core-index o core-trust (anche per inferenza) o nomina il codice interno
 *    di core-albums (`shared`, `share`, `smart`, `trips`, `personal`): solo contratti, null-object e fake;
 * 7. dipendenze dei moduli nuovi oltre il §1.1; chi dipende da loro oltre un elenco chiuso; moduli nuovi senza `contract`/`noop`/`testing` o
 *    senza i test di guardia; core-albums con Hilt; manifest con componenti o permessi (§6.4);
 * 8. D30 non tenuta nella release (la regola R8 di `core-common/consumer-rules.pro` e il passo di `tools/check.ps1`).
 *
 * Ogni regola e' stata provata reintroducendo la violazione (il test fallisce) e togliendola (P0 della Fase 7, `docs/decisioni.md`).
 */
class Phase7ArchitectureTest {

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

  @Test
  fun ilControlloVedeIModuliDellaFase7() {
    assertThat(modules).containsAtLeast(ALBUMS, FEATURE_ALBUMS, "core-personal", "core-search", "core-library", "core-database")
    assertThat(direct(ALBUMS)).containsAtLeast("core-personal", "core-trust", "core-library")
    assertThat(compileVisible(FEATURE_ALBUMS)).contains(ALBUMS)
    assertThat(File(root, "$ALBUMS_MAIN/contract/ShareConfirmation.kt").isFile).isTrue()
  }

  /** 1. D1, D2: core-albums non sposta, non carica, non cancella e non parla in rete da se'. */
  @Test
  fun coreAlbumsNonVedeNasopsBackupNasNeLaRete() {
    val named = mentionsIn(ALBUMS, ALBUMS_FORBIDDEN_PACKAGES, PRODUCTION_SETS)
    assertWithMessage("core-albums nomina pacchetti di moduli vietati o la rete nel codice di produzione").that(named).isEmpty()
    // I test possono usare i banchi di core-nasops e core-backup (P4, P5: simulatore SMB, motore vero), mai la rete.
    assertWithMessage("i test di core-albums nominano la rete").that(mentionsIn(ALBUMS, NETWORK_PACKAGES, TEST_SETS)).isEmpty()
    assertWithMessage("core-albums dipende da moduli vietati").that(direct(ALBUMS) intersect ALBUMS_NEVER_SEES).isEmpty()
    val visible = compileVisible(ALBUMS)
    for (forbidden in ALBUMS_NEVER_SEES) assertWithMessage("core-albums vede $forbidden").that(visible).doesNotContain(forbidden)
    val reach = closure(ALBUMS)
    for (forbidden in listOf(NASOPS, BACKUP)) assertWithMessage("core-albums raggiunge $forbidden").that(reach).doesNotContain(forbidden)
  }

  /** 2. §1.1: `NasFileSystem` (scrive solo con `publishExclusive`) solo in core-albums/.../albums/shared/. */
  @Test
  fun nasFileSystemSoloInShared() {
    val outside = mentionsIn(ALBUMS, NAS_FILE_SYSTEM, ALL_SETS).filter { "/dev/pampa/mycloudgallery/core/albums/shared/" !in it }
    assertWithMessage("NasFileSystem in core-albums fuori da albums/shared/").that(outside).isEmpty()
  }

  /** 3. D18: la "casa" solo in core-database (la tabella) e in core-albums/.../albums/trips/, nel codice che va in un APK. */
  @Test
  fun homeEstimateSoloInTrips() {
    val outside = mentions(HOME_ESTIMATE, PRODUCTION_SETS).filter { (module, path) ->
      module != "core-database" && !path.startsWith("$ALBUMS_MAIN/trips/")
    }
    assertWithMessage("home_estimate fuori da core-database e albums/trips/").that(outside.map { it.second }).isEmpty()
  }

  /** 4. D12, C26: la conferma della condivisione la costruisce solo il foglio. */
  @Test
  fun unaConfermaDellaCondivisioneLaCostruisceSoloIlFoglio() {
    val confirmationFile = "$ALBUMS_MAIN/contract/ShareConfirmation.kt"
    val constructors = mentions(SHARE_CONFIRMATION_BUILT, ALL_SETS).filter { it.second != confirmationFile }
    assertWithMessage("ShareConfirmation costruita fuori dal file della conferma").that(constructors.map { it.second }).isEmpty()
    val text = code(File(root, confirmationFile))
    assertWithMessage("il costruttore di ShareConfirmation deve essere internal").that(INTERNAL_SHARE_CONFIRMATION.containsMatchIn(text)).isTrue()
    val misplaced = mentions(SHARE_ISSUERS, ALL_SETS).filter { (module, path) ->
      when (module) {
        FEATURE_ALBUMS, "app" -> false
        ALBUMS -> !(path == confirmationFile || "/src/test/" in path || "/testing/" in path)
        else -> true
      }
    }
    assertWithMessage("emittente o foglio della conferma fuori dalla UI").that(misplaced.map { it.second }).isEmpty()
    // Le conferme di core-nasops: solo :app le emette (Phase4ArchitectureTest); gli album non le nominano mai.
    val nasops = (mentionsIn(ALBUMS, NASOPS_CONFIRMATIONS, ALL_SETS) + mentionsIn(FEATURE_ALBUMS, NASOPS_CONFIRMATIONS, ALL_SETS))
    assertWithMessage("core-albums o feature-albums nominano le conferme di core-nasops").that(nasops).isEmpty()
  }

  /** 4. Le firme che pretendono la conferma: un'implementazione non puo' "dimenticarla" senza far fallire questo test. */
  @Test
  fun leFirmeChePortanoUnoSpostamentoPretendonoLaConferma() {
    val share = code(File(root, "$ALBUMS_MAIN/contract/Share.kt"))
    val ports = code(File(root, "$ALBUMS_MAIN/contract/Ports.kt"))
    assertWithMessage("ShareRunner.start vuole una ShareConfirmation")
      .that(Regex("""suspend\s+fun\s+start\(\s*plan:\s*SharePlan,\s*confirmation:\s*Share""" + """Confirmation\s*\)""").containsMatchIn(share)).isTrue()
    assertWithMessage("AlbumMover.move vuole una ShareConfirmation")
      .that(Regex("""fun\s+move\([^)]*confirmation:\s*Share""" + """Confirmation\s*\)""").containsMatchIn(ports)).isTrue()
    assertWithMessage("AlbumUploader.upload vuole una ShareConfirmation")
      .that(Regex("""fun\s+upload\([^)]*confirmation:\s*Share""" + """Confirmation\s*\)""").containsMatchIn(ports)).isTrue()
  }

  /** 5. D4: nessun tipo nuovo di dato personale (ne' un codice cambiato). */
  @Test
  fun nessunPersonalKindNuovo() {
    val file = File(root, "core-personal/src/main/kotlin/dev/pampa/mycloudgallery/core/personal/contract/Personal.kt")
    val text = code(file)
    val start = text.indexOf("enum class Personal" + "Kind(")
    assertThat(start).isAtLeast(0)
    val body = text.substring(text.indexOf('{', start) + 1, text.indexOf("\n}", start))
    val entries = Regex("""(?m)^\s*([A-Z][A-Z0-9_]*)\s*\(\s*"([a-z0-9_]+)"""").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
    assertWithMessage("PersonalKind e' un elenco chiuso (D4): i campi nuovi stanno dentro i tipi esistenti").that(entries).containsExactly(
      "MEDIA_FLAG", "media_flag", "ALBUM", "album", "MEMORY", "memory", "SETTING", "setting", "PERSON", "person",
    )
  }

  /** 6. §1.1: feature-albums vede di core-albums solo contratti, null-object e fake; mai il NAS, le operazioni, il backup, l'indice o trust. */
  @Test
  fun featureAlbumsECiecaAlNasEAlCodiceInternoDegliAlbum() {
    val visible = compileVisible(FEATURE_ALBUMS)
    for (forbidden in FEATURE_NEVER_SEES) assertWithMessage("feature-albums vede $forbidden").that(visible).doesNotContain(forbidden)
    val reach = closure(FEATURE_ALBUMS)
    for (forbidden in listOf(NASOPS, BACKUP)) assertWithMessage("feature-albums raggiunge $forbidden").that(reach).doesNotContain(forbidden)
    val named = mentionsIn(FEATURE_ALBUMS, FEATURE_FORBIDDEN_PACKAGES, ALL_SETS)
    assertWithMessage("feature-albums nomina il NAS, trust o il codice interno di core-albums").that(named).isEmpty()
  }

  /** 7. §1.1: dipendenze dei moduli nuovi, chi dipende da loro, regole dei moduli. */
  @Test
  fun iModuliDellaFase7RispettanoIlGrafoELeRegole() {
    for ((module, allowed) in ALLOWED_DEPS) {
      assertWithMessage("$module manca in settings.gradle").that(modules).contains(module)
      assertWithMessage("$module dipende da moduli fuori dal §1.1").that(direct(module) - allowed).isEmpty()
    }
    for ((module, allowed) in ALLOWED_DEPENDENTS) {
      val dependents = modules.filter { module in direct(it) }
      assertWithMessage("chi dipende da $module").that(dependents - allowed).isEmpty()
    }
    // core-search non vede core-albums (la dipendenza e' solo nel verso albums -> contratti di search).
    assertThat(closure("core-search")).doesNotContain(ALBUMS)
    for (module in listOf(ALBUMS, FEATURE_ALBUMS)) {
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
        Regex("""<(uses-permission|permission|activity|activity-alias|service|receiver|provider|uses-feature)\b""").containsMatchIn(manifest),
      ).isFalse()
    }
    assertWithMessage("core-albums usa Hilt (i core-* no)").that(Regex("""hilt|dagger|kapt""").containsMatchIn(buildText(ALBUMS))).isFalse()
  }

  /** 8. D30: "album condivisi solo sulle share di prova" resta nella release anche prima che il cancello sia legato (P13). */
  @Test
  fun laRecinzioneDegliAlbumCondivisiStaNellaRelease() {
    val rules = File(root, "core-common/consumer-rules.pro").readText()
    assertThat(rules).contains("public boolean getSharedAlbumsTestSharesOnly();")
    assertThat(rules).contains("public boolean sharedAlbumsMayUse(java.lang.String);")
    val check = File(root, "tools/check.ps1").readText().lineSequence().joinToString("\n") { it.substringBefore('#') }
    assertWithMessage("tools/check.ps1 non cerca D30 nel dex di release").that(check).contains("\"sharedAlbumsMayUse\"")
  }

  private companion object {
    val INCLUDE = Regex("""^\s*include\s+['"]:([A-Za-z0-9\-]+)['"]""")
    val PROJECT_DEP = Regex("""^\s*(\w+)\s*\(?\s*project\(\s*['"]:([A-Za-z0-9\-]+)['"]\s*\)""")
    val PRODUCTION_CONFIG = Regex("""^(\w*?)(api|Api|implementation|Implementation|compileOnly|CompileOnly|runtimeOnly|RuntimeOnly)$""")

    const val ALBUMS = "core-albums"
    const val FEATURE_ALBUMS = "feature-albums"
    const val NASOPS = "core-nasops"
    const val BACKUP = "core-backup"
    const val ALBUMS_MAIN = "core-albums/src/main/kotlin/dev/pampa/mycloudgallery/core/albums"

    val PRODUCTION_SETS = listOf("main", "debug", "release", "benchmarkRelease")
    val TEST_SETS = listOf("test", "androidTest", "testDebug")
    val ALL_SETS = PRODUCTION_SETS + TEST_SETS

    // I nomi vietati sono scritti in due pezzi: questo file non contiene mai il nome intero, cosi' non si segnala da solo.

    /** core-albums non vede questi moduli (D2): ne' a compilazione ne' per nome. */
    val ALBUMS_NEVER_SEES = setOf(NASOPS, BACKUP, "core-nas", "core-nasmedia", "core-index", "core-ai", "core-models")
    val ALBUMS_FORBIDDEN_PACKAGES = Regex(
      """\bdev\.pampa\.mycloudgallery\.core\.(na""" + """s|naso""" + """ps|nas""" + """media|back""" + """up|in""" + """dex|a""" + """i|mod""" + """els)\.|\bjava""" +
        """x?\.n""" + """et\b""",
    )
    val NETWORK_PACKAGES = Regex("""\bjava""" + """x?\.n""" + """et\b""")

    val NAS_FILE_SYSTEM = Regex("""\b\w*NasFile""" + """System\w*\b""")
    val HOME_ESTIMATE = Regex("""(?i)\bhome_?""" + """estimate\w*""")

    val SHARE_CONFIRMATION_BUILT = Regex("""\bShare""" + """Confirmation\s*\(""")
    val INTERNAL_SHARE_CONFIRMATION = Regex("""class\s+Share""" + """Confirmation\s+internal\s+constructor""")
    val SHARE_ISSUERS = Regex("""\b(Share""" + """Confirmation""" + """Issuer|Share""" + """Confirmation""" + """Sheet)\b""")
    val NASOPS_CONFIRMATIONS = Regex("""\b(User""" + """Confirmation|Confirmation""" + """Issuer|Confirmation""" + """Sheet|Accepted""" + """Plan)\b""")

    /** feature-albums: niente NAS, niente operazioni, niente backup, niente indice, niente trust; di core-albums niente codice interno. */
    val FEATURE_NEVER_SEES = listOf("core-nas", NASOPS, BACKUP, "core-index", "core-trust")
    val FEATURE_FORBIDDEN_PACKAGES = Regex(
      """\bdev\.pampa\.mycloudgallery\.core\.(na""" + """s|naso""" + """ps|back""" + """up|in""" + """dex|tru""" + """st)\.|""" +
        """\bdev\.pampa\.mycloudgallery\.core\.albums\.(sha""" + """red|sha""" + """re|sma""" + """rt|tri""" + """ps|perso""" + """nal)\b""",
    )

    /** §1.1. core-library: le viste e la visibilita' (VisibleMedia, HiddenMask) dei contratti; le altre come dice il grafo. */
    val ALLOWED_DEPS: Map<String, Set<String>> = mapOf(
      ALBUMS to setOf("core-common", "core-model", "core-trust", "core-database", "core-personal", "core-places", "core-search", "core-library"),
      FEATURE_ALBUMS to setOf(ALBUMS, "core-library", "core-imaging", "core-designsystem", "core-common", "core-model"),
    )

    /** Chi puo' dipendere dai moduli nuovi (elenco chiuso). */
    val ALLOWED_DEPENDENTS: Map<String, Set<String>> = mapOf(
      ALBUMS to setOf(FEATURE_ALBUMS, "app"),
      FEATURE_ALBUMS to setOf("app"),
    )
  }
}
