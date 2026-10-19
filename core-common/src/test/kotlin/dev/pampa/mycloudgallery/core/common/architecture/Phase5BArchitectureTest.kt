package dev.pampa.mycloudgallery.core.common.architecture

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * I test di architettura della Fase 5B (`docs/fase5-progetto.md` §1.1, §4.3, §6.3, §6.4, D2, D21, D29; riga P0-B del §4.4). Come
 * [Phase5ArchitectureTest] leggono `settings.gradle`, i `build.gradle`, i manifest e i sorgenti di **tutti** i moduli (ingressi dei test
 * dichiarati in `core-common/build.gradle`). Le regole della 4B e della 5A restano in vigore per i moduli nuovi.
 *
 * Cosa vietano in piu':
 * 1. `feature-integrations` che vede `core-nas` o `core-nasops` (i byte di un media solo-NAS arrivano da una porta che `:app` lega);
 * 2. dipendenze di `feature-integrations` oltre il §1.1, e moduli nuovi senza `contract`/`noop`/`testing` e senza i test di guardia;
 * 3. una `PhoneConfirmation` costruita da altri che da `PhoneConfirmationIssuer`, e l'emittente o il foglio usati fuori dalla UI
 *    (`feature-backup`, `feature-integrations`, `feature-viewer`, `:app`); i servizi che toccano il telefono (`FreeSpace.verify`,
 *    `VersionKeeper.restoreOriginal`, `Corrections.apply`, `IncomingUploads.save`) che non pretendono piu' la conferma;
 * 4. `NasBackupOps` con una primitiva in piu' (una rinomina libera, un cestino, una cancellazione) o con `archiveVersion`/`restoreVersion`
 *    senza il timbro obbligatorio, e un'implementazione che non controlla i percorsi di `VersionPaths`;
 * 5. le richieste di sistema sul telefono (`createWriteRequest`/`createDeleteRequest`/`createTrashRequest`) fuori da `core-mediastore`, e
 *    `PhoneMediaRewriter` fuori da `core-mediastore` e `:app`;
 * 6. un modulo diverso da `:app` e `feature-integrations` che dichiara componenti nel manifest; `feature-integrations` con un componente in piu'
 *    o diverso dai quattro approvati (Q5), con un permesso, o con un'esposizione diversa; i quattro componenti anche nel manifest di `:app`;
 * 7. `releaseManifestPolicy` e `docs/sicurezza.md` §4-bis che non dicono la stessa cosa del manifest (elenco chiuso degli esportati, il provider
 *    dei documenti con `MANAGE_DOCUMENTS`, nessun permesso nuovo);
 * 8. un `FileProvider` che serve piu' di `cacheDir/share/` e `cacheDir/edit/`;
 * 9. gli strumenti delle prove sul campo (`FieldProbe...`) fuori da `app/src/debug` e `app/src/testDebug`, o non elencati fra i resti di debug
 *    che `tools/check.ps1` cerca nel dex di release.
 *
 * Ogni regola e' stata provata reintroducendo la violazione (il test fallisce) e togliendola (P0-B, `docs/decisioni.md`).
 */
class Phase5BArchitectureTest {

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

  private fun sources(module: String, sets: List<String> = listOf("main", "test", "androidTest")): Sequence<File> =
    sets.asSequence().map { File(root, "$module/src/$it") }.filter { it.isDirectory }
      .flatMap { dir -> dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }

  private fun mentionsIn(module: String, pattern: Regex, sets: List<String> = listOf("main", "test", "androidTest")): List<String> =
    sources(module, sets).filter { pattern.containsMatchIn(NoNetworkSources.stripComments(it.readText())) }
      .map { it.relativeTo(root).invariantSeparatorsPath }.toList()

  /** Ogni sorgente di ogni set di ogni modulo che nomina [pattern], come (modulo, percorso relativo alla radice). */
  private fun mentions(pattern: Regex, sets: List<String> = ALL_SETS): List<Pair<String, String>> = modules.flatMap { module ->
    mentionsIn(module, pattern, sets).map { module to it }
  }

  private fun code(path: String): String = NoNetworkSources.stripComments(File(root, path).readText())

  private fun manifest(module: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    .newDocumentBuilder().parse(File(root, "$module/src/main/AndroidManifest.xml")).documentElement

  private fun children(parent: Element, tag: String): List<Element> =
    (0 until parent.childNodes.length).map { parent.childNodes.item(it) }
      .filter { it.nodeType == Node.ELEMENT_NODE && it.nodeName == tag }
      .map { it as Element }

  private fun components(module: String): List<Pair<String, Element>> {
    val app = children(manifest(module), "application").firstOrNull() ?: return emptyList()
    return COMPONENT_TAGS.flatMap { tag -> children(app, tag).map { tag to it } }
  }

  @Test
  fun ilControlloVedeIlModuloDellaFase5B() {
    assertThat(modules).containsAtLeast(FEATURE_INTEGRATIONS, BACKUP, "core-mediastore", "app")
    assertThat(compileVisible(FEATURE_INTEGRATIONS)).contains(BACKUP)
  }

  /** §1.1: le dipendenze dirette di feature-integrations. */
  @Test
  fun featureIntegrationsDipendeSoloDaCioCheIlProgettoPermette() {
    assertWithMessage("feature-integrations dipende da moduli fuori dal §1.1")
      .that(direct(FEATURE_INTEGRATIONS) - ALLOWED_INTEGRATIONS_DEPS).isEmpty()
    assertThat(direct(FEATURE_INTEGRATIONS)).containsAtLeast(BACKUP, "core-library", "core-mediastore", "core-designsystem")
  }

  /** §1.1: i byte di un media solo-NAS arrivano da una porta che `:app` lega a `NasMediaReader`; nessuna primitiva del NAS e' visibile qui. */
  @Test
  fun featureIntegrationsNonVedeCoreNasNeCoreNasops() {
    val visible = compileVisible(FEATURE_INTEGRATIONS)
    assertWithMessage("feature-integrations vede core-nas").that(visible).doesNotContain("core-nas")
    assertWithMessage("feature-integrations vede core-nasops").that(visible).doesNotContain(NASOPS)
    assertWithMessage("feature-integrations raggiunge core-nasops").that(closure(FEATURE_INTEGRATIONS)).doesNotContain(NASOPS)
    assertWithMessage("feature-integrations nomina core-nas o core-nasops")
      .that(mentionsIn(FEATURE_INTEGRATIONS, NAS_OR_NASOPS_PACKAGE, listOf("main", "test"))).isEmpty()
    // Nemmeno i tipi delle primitive, per nome: l'argomento del compilatore le terrebbe fuori, ma il nome non deve comparire.
    assertWithMessage("feature-integrations nomina le primitive del NAS")
      .that(mentionsIn(FEATURE_INTEGRATIONS, NAS_PRIMITIVE_NAMES, listOf("main", "test"))).isEmpty()
  }

  /** Le regole dei moduli (README; fase5 §4.3): pacchetti fissi, test di guardia, nessun permesso Android nuovo. */
  @Test
  fun featureIntegrationsRispettaLeRegoleDeiModuli() {
    val main = File(root, "$FEATURE_INTEGRATIONS/src/main/kotlin")
    for (pkg in listOf("contract", "noop", "testing")) {
      val found = main.walkTopDown().any { it.isDirectory && it.name == pkg && (it.listFiles()?.any { f -> f.extension == "kt" } == true) }
      assertWithMessage("$FEATURE_INTEGRATIONS senza il package $pkg").that(found).isTrue()
    }
    val tests = sources(FEATURE_INTEGRATIONS, listOf("test")).map { it.nameWithoutExtension }.toSet()
    assertWithMessage("$FEATURE_INTEGRATIONS senza NoNetworkSourcesTest").that(tests).contains("NoNetworkSourcesTest")
    assertWithMessage("$FEATURE_INTEGRATIONS senza NoWallClockSourcesTest").that(tests).contains("NoWallClockSourcesTest")
    val text = File(root, "$FEATURE_INTEGRATIONS/src/main/AndroidManifest.xml").readText().replace(Regex("""<!--[\s\S]*?-->"""), "")
    assertWithMessage("$FEATURE_INTEGRATIONS dichiara permessi Android").that(Regex("""<(uses-permission|permission)\b""").containsMatchIn(text)).isFalse()
    val build = NoNetworkSources.stripComments(File(root, "$FEATURE_INTEGRATIONS/build.gradle").readText())
    assertWithMessage("$FEATURE_INTEGRATIONS non e' un modulo della galleria").that(modules).contains(FEATURE_INTEGRATIONS)
    assertThat(build).contains("com.android.library")
  }

  /**
   * D21, §1.1: una `PhoneConfirmation` nasce solo da un tocco dell'utente su un foglio. La costruisce il suo file ([PhoneConfirmationIssuer]), la
   * emette solo la UI (`feature-backup`, `feature-integrations`, `feature-viewer`, `:app`) e, dentro `core-backup`, i fake e i test. Il resto di
   * `core-backup` (piano, verifica, lotti, versioni, correzioni, arrivi) non nomina mai l'emittente ne' il foglio.
   */
  @Test
  fun unaConfermaDelTelefonoLaCostruisceSoloLaUi() {
    val confirmationFile = "$BACKUP/src/main/kotlin/dev/pampa/mycloudgallery/core/backup/contract/PhoneConfirmation.kt"
    val uiModules = setOf("feature-backup", FEATURE_INTEGRATIONS, "feature-viewer", "app")

    val misplacedIssuers = mentions(PHONE_ISSUERS).filter { (module, path) ->
      when (module) {
        in uiModules -> false
        BACKUP -> !(path == confirmationFile || "/src/test/" in path || "/testing/" in path)
        else -> true
      }
    }
    assertWithMessage("chi costruisce la conferma del telefono e il suo foglio, fuori dalla UI").that(misplacedIssuers).isEmpty()

    val constructors = mentions(PHONE_CONSTRUCTOR).filter { it.second != confirmationFile }
    assertWithMessage("PhoneConfirmation costruita fuori dal file della conferma").that(constructors).isEmpty()

    val file = code(confirmationFile)
    assertWithMessage("il costruttore di PhoneConfirmation deve essere internal").that(INTERNAL_PHONE_CONFIRMATION.containsMatchIn(file)).isTrue()
  }

  /** I servizi che toccano il telefono o i file dell'utente vogliono una `PhoneConfirmation`: la firma lo dice e questo test la inchioda. */
  @Test
  fun iServiziCheToccanoIlTelefonoPretendonoLaConferma() {
    val dir = "$BACKUP/src/main/kotlin/dev/pampa/mycloudgallery/core/backup/contract"
    val required = mapOf(
      "FreeSpace.kt" to Regex("""suspend\s+fun\s+verify\(\s*plan:\s*FreeSpacePlan,\s*confirmation:\s*Phone""" + """Confirmation,"""),
      "Versions.kt" to Regex("""suspend\s+fun\s+restoreOriginal\(\s*mediaId:\s*MediaId,\s*confirmation:\s*Phone""" + """Confirmation\b"""),
      "Corrections.kt" to Regex("""suspend\s+fun\s+apply\(\s*plan:\s*CorrectionPlan,\s*confirmation:\s*Phone""" + """Confirmation,"""),
      "IncomingUploads.kt" to Regex(
        """suspend\s+fun\s+save\(\s*stage:\s*IncomingStage,\s*dest:\s*NasDestination,\s*confirmation:\s*Phone""" + """Confirmation\)""",
      ),
    )
    for ((file, regex) in required) {
      assertWithMessage("$file: il servizio non pretende piu' la conferma dell'utente").that(regex.containsMatchIn(code("$dir/$file"))).isTrue()
    }
  }

  /** D2: la vista del backup ha un insieme **chiuso** di primitive; le due rinomine delle versioni hanno il timbro obbligatorio. */
  @Test
  fun leDueRinomineDelleVersioniSonoLeUnichePrimitiveCheSpostanoUnFile() {
    val file = "core-nas/src/main/kotlin/dev/pampa/mycloudgallery/core/nas/ops/backup/NasBackup" + "Ops.kt"
    val text = code(file)
    val start = text.indexOf("interface NasBackup" + "Ops {")
    assertThat(start).isAtLeast(0)
    var depth = 0
    var end = -1
    for (i in text.indexOf('{', start) until text.length) {
      if (text[i] == '{') depth++ else if (text[i] == '}' && --depth == 0) { end = i; break }
    }
    assertThat(end).isGreaterThan(start)
    val body = text.substring(start, end)
    val functions = Regex("""\bfun\s+(\w+)\s*\(""").findAll(body).map { it.groupValues[1] }.toSet()
    assertWithMessage("le primitive del backup non sono piu' un insieme chiuso (D2): una rinomina libera, il cestino o una cancellazione?")
      .that(functions).containsExactly(
        "mkdirs", "openTemp", "reopenTemp", "copyToTemp", "publishTemp", "deleteOwnTemp", "archiveVersion", "restoreVersion", "space",
      )

    // Il timbro e' obbligatorio (non nullo) e il risultato e' una rinomina senza sostituire.
    for (name in listOf("archiveVersion", "restoreVersion")) {
      val signature = Regex(
        """suspend\s+fun\s+$name\(\s*share:\s*String,\s*from:\s*SharePath,\s*to:\s*SharePath,\s*expect:\s*NasStamp,\s*purpose:\s*Purpose\s*\):\s*NasResult<RenameOutcome>""",
      )
      assertWithMessage("$name: il timbro non e' piu' obbligatorio, o la firma e' cambiata").that(signature.containsMatchIn(body)).isTrue()
    }

    // Ogni implementazione controlla i percorsi (una estremita' sotto Versioni/) prima di toccare il NAS: P8 non puo' dimenticarsene.
    val implementers = listOf(
      "core-nas/src/main/kotlin/dev/pampa/mycloudgallery/core/nas/noop/NasBackupNullObjects.kt",
      "core-nas/src/main/kotlin/dev/pampa/mycloudgallery/core/nas/ops/backup/NasBackup" + "OpsFactory.kt",
      "core-nas/src/main/kotlin/dev/pampa/mycloudgallery/core/nas/testing/InMemoryNasMedia.kt",
    )
    for (implementer in implementers) {
      val implementation = code(implementer)
      assertWithMessage("$implementer non controlla i percorsi di archiveVersion").that(implementation).contains("VersionPaths.requireArchive(")
      assertWithMessage("$implementer non controlla i percorsi di restoreVersion").that(implementation).contains("VersionPaths.requireRestore(")
    }
  }

  /** §4.3: nessuna scrittura sul telefono fuori dai dialoghi di sistema, che stanno in `core-mediastore`; il riscrittore e' di `core-mediastore` e `:app`. */
  @Test
  fun leRichiesteDiSistemaEIlRiscrittoreStannoSoloDoveDevono() {
    val raw = Regex("""\b(create""" + """WriteRequest|create""" + """DeleteRequest|create""" + """TrashRequest)\b""")
    // Solo il codice che va in un APK: i test degli altri moduli possono nominarle per vietarle (CollectionsStyleTest).
    val misplaced = mentions(raw, PRODUCTION_SETS).filter { it.first != "core-mediastore" }
    assertWithMessage("richieste di sistema sul telefono fuori da core-mediastore").that(misplaced).isEmpty()

    val rewriter = Regex("""\b\w*Phone""" + """MediaRewriter\w*\b""")
    val misplacedRewriter = mentions(rewriter, PRODUCTION_SETS).filter { it.first != "core-mediastore" && it.first != "app" }
    assertWithMessage("il riscrittore del telefono fuori da core-mediastore e :app").that(misplacedRewriter).isEmpty()
  }

  /** Q5, D29, C17: solo `:app` e `feature-integrations` dichiarano componenti, e `feature-integrations` solo i quattro approvati. */
  @Test
  fun soloIQuattroComponentiApprovatiSonoNelManifestDiFeatureIntegrations() {
    for (module in modules) {
      if (module == "app" || module == FEATURE_INTEGRATIONS || module == "benchmark") continue
      val manifestFile = File(root, "$module/src/main/AndroidManifest.xml")
      if (!manifestFile.isFile) continue
      assertWithMessage("$module dichiara componenti nel manifest").that(components(module)).isEmpty()
    }
    val declared = components(FEATURE_INTEGRATIONS).associate { (tag, element) ->
      element.getAttributeNS(ANDROID, "name") to (tag to element.getAttributeNS(ANDROID, "exported"))
    }
    assertThat(declared).containsExactly(
      "$INTEGRATIONS_PACKAGE.picker.PickerActivity", "activity" to "true",
      "$INTEGRATIONS_PACKAGE.receive.ShareReceiverActivity", "activity" to "true",
      "$INTEGRATIONS_PACKAGE.provider.MediaDocumentsProvider", "provider" to "true",
      "$INTEGRATIONS_PACKAGE.share.IntegrationFileProvider", "provider" to "false",
    )
    // L'unico esportato con un permesso suo: il provider dei documenti, protetto da MANAGE_DOCUMENTS.
    val documents = components(FEATURE_INTEGRATIONS).single { it.second.getAttributeNS(ANDROID, "name").endsWith("MediaDocumentsProvider") }.second
    assertThat(documents.getAttributeNS(ANDROID, "permission")).isEqualTo("android.permission.MANAGE_DOCUMENTS")
    // I quattro non stanno anche nel manifest di :app (un solo posto, nessuna doppia dichiarazione).
    val appNames = components("app").map { it.second.getAttributeNS(ANDROID, "name") }
    assertThat(appNames.filter { name -> INTEGRATION_CLASS_NAMES.any { name.endsWith(it) } }).isEmpty()
  }

  /** La policy di release e il documento di sicurezza dicono la stessa cosa del manifest: elenco chiuso degli esportati, nessun permesso nuovo. */
  @Test
  fun laPolicyDiReleaseEIlDocumentoAmmettonoSoloIComponentiApprovati() {
    val policy = NoNetworkSources.stripComments(File(root, "gradle/verify-release-manifest.gradle").readText())
    val exported = Regex("""allowedExported\s*:\s*\[([\s\S]*?)\]""").find(policy)!!.groupValues[1]
    val exportedNames = Regex("""'([^']+)'""").findAll(exported).map { it.groupValues[1] }.toSet()
    assertThat(exportedNames).containsExactly(
      "dev.pampa.mycloudgallery.MainActivity",
      "androidx.work.impl.background.systemjob.SystemJobService",
      "$INTEGRATIONS_PACKAGE.picker.PickerActivity",
      "$INTEGRATIONS_PACKAGE.receive.ShareReceiverActivity",
      "$INTEGRATIONS_PACKAGE.provider.MediaDocumentsProvider",
    )
    // Il FileProvider non e' esportato: non puo' comparire fra gli esportati ammessi.
    assertThat(exportedNames.any { it.endsWith("IntegrationFileProvider") }).isFalse()
    val protectedBy = Regex("""exportedRequiresPermission\s*:\s*\[([\s\S]*?)\]""").find(policy)!!.groupValues[1]
    val pairs = Regex("""'([^']+)'\s*:\s*'([^']+)'""").findAll(protectedBy).associate { it.groupValues[1] to it.groupValues[2] }
    assertThat(pairs).containsEntry("$INTEGRATIONS_PACKAGE.provider.MediaDocumentsProvider", "android.permission.MANAGE_DOCUMENTS")
    assertThat(pairs).containsEntry("androidx.work.impl.background.systemjob.SystemJobService", "android.permission.BIND_JOB_SERVICE")
    // I due permessi che l'app ha deciso di non chiedere mai hanno un rifiuto con nome e ragione (`forbiddenPermissions`).
    val forbiddenBlock = Regex("""forbiddenPermissions\s*:\s*\[([\s\S]*?)\n\s*\]""").find(policy)!!.groupValues[1]
    for (name in listOf("MANAGE_MEDIA", "MANAGE_EXTERNAL_STORAGE")) {
      assertWithMessage("la policy non rifiuta per nome $name").that(forbiddenBlock).contains("'android.permission.$name'")
    }
    // Nessun permesso nuovo: niente MANAGE_MEDIA, niente accesso a tutti i file, e MANAGE_DOCUMENTS non e' un permesso dell'app.
    val permissions = Regex("""allowedPermissions\s*:\s*\[([\s\S]*?)\n\s*\]""").find(policy)!!.groupValues[1]
    for (forbidden in listOf("MANAGE_MEDIA", "MANAGE_EXTERNAL_STORAGE", "MANAGE_DOCUMENTS", "FOREGROUND_SERVICE_DATA_SYNC", "ALL_FILES")) {
      assertWithMessage("permesso non approvato nella policy: $forbidden").that(permissions).doesNotContain(forbidden)
    }

    val security = File(root, "docs/sicurezza.md").readText()
    val section = security.substring(security.indexOf("### 4-bis"), security.indexOf("## 5. Stato attuale"))
    for (name in listOf("PickerActivity", "ShareReceiverActivity", "MediaDocumentsProvider", "FileProvider", "MANAGE_DOCUMENTS", "BackupJobService")) {
      assertWithMessage("docs/sicurezza.md §4-bis non nomina $name").that(section).contains(name)
    }
  }

  /** C17: il FileProvider serve solo `cacheDir/share/` e `cacheDir/edit/`. */
  @Test
  fun ilFileProviderServeSoloShareEEdit() {
    val xml = File(root, "$FEATURE_INTEGRATIONS/src/main/res/xml/integration_file_paths.xml").readText().replace(Regex("""<!--[\s\S]*?-->"""), "")
    val tags = Regex("""<([a-z-]+)\s+name="([^"]*)"\s+path="([^"]*)"\s*/>""").findAll(xml).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()
    assertThat(tags).containsExactly(Triple("cache-path", "share", "share/"), Triple("cache-path", "edit", "edit/"))
    assertThat(Regex("""<(files-path|external-[a-z-]*|root-path)\b""").containsMatchIn(xml)).isFalse()
  }

  /**
   * Le prove guidate sul campo (T-09, T-14, T-15, T-16) sono strumenti di **debug**: le classi `FieldProbe...` stanno solo in `app/src/debug`
   * (e i loro test in `app/src/testDebug`), mai in `main`, `release` ne' nei moduli, e `tools/check.ps1` le cerca nel dex dell'APK di release.
   * I loro componenti di prova (activity e provider di debug) li dichiara solo il manifest di debug.
   */
  @Test
  fun gliStrumentiDelleProveSulCampoStannoSoloInDebug() {
    val probe = Regex("""\bField""" + """Probe\w*""")
    val allowedSets = listOf("debug", "testDebug")
    val misplaced = modules.flatMap { module ->
      ALL_SETS_WITH_VARIANTS.filter { set -> !(module == "app" && set in allowedSets) }
        .flatMap { set -> mentionsIn(module, probe, listOf(set)) }
    }
    assertWithMessage("strumenti delle prove sul campo fuori da app/src/debug e app/src/testDebug").that(misplaced).isEmpty()
    val appMain = File(root, "app/src/main/AndroidManifest.xml").readText()
    assertThat(probe.containsMatchIn(appMain)).isFalse()
    val debugManifest = File(root, "app/src/debug/AndroidManifest.xml")
    assertWithMessage("app/src/debug/AndroidManifest.xml (i componenti di prova di debug)").that(debugManifest.isFile).isTrue()
    val debugNames = components("app").map { it.second.getAttributeNS(ANDROID, "name") }
    assertThat(debugNames.any { probe.containsMatchIn(it) }).isFalse() // i componenti di prova non sono nel manifest principale
    val debugDoc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(debugManifest).documentElement
    val debugApp = children(debugDoc, "application").single()
    for (tag in COMPONENT_TAGS) {
      for (element in children(debugApp, tag)) {
        val name = element.getAttributeNS(ANDROID, "name")
        assertWithMessage("componente del manifest di debug che non e' una prova sul campo: $name").that(probe.containsMatchIn(name)).isTrue()
      }
    }
    // tools/check.ps1 cerca nel dex di release il prefisso delle classi di prova.
    val check = File(root, "tools/check.ps1").readText()
    val remnants = check.substring(check.indexOf("\$debugRemnants = @("), check.indexOf("Step \"nessun resto degli strumenti di debug"))
    // Senza i commenti (`#`): il nome fra apici nell'elenco, non una parola in una riga di spiegazione.
    val remnantNames = remnants.lineSequence().joinToString("\n") { it.substringBefore('#') }
    assertWithMessage("tools/check.ps1 non cerca i resti delle prove sul campo nel dex di release").that(remnantNames).contains("\"Field" + "Probe\"")
  }

  private companion object {
    val INCLUDE = Regex("""^\s*include\s+['"]:([A-Za-z0-9\-]+)['"]""")
    val PROJECT_DEP = Regex("""^\s*(\w+)\s*\(?\s*project\(\s*['"]:([A-Za-z0-9\-]+)['"]\s*\)""")
    val PRODUCTION_CONFIG = Regex("""^(\w*?)(api|Api|implementation|Implementation|compileOnly|CompileOnly|runtimeOnly|RuntimeOnly)$""")

    const val ANDROID = "http://schemas.android.com/apk/res/android"
    const val BACKUP = "core-backup"
    const val FEATURE_INTEGRATIONS = "feature-integrations"
    const val NASOPS = "core-nasops"
    const val INTEGRATIONS_PACKAGE = "dev.pampa.mycloudgallery.feature.integrations"

    val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")
    val ALL_SETS = listOf("main", "test", "androidTest")
    val PRODUCTION_SETS = listOf("main", "debug", "release", "benchmarkRelease")
    val ALL_SETS_WITH_VARIANTS = listOf("main", "test", "androidTest", "debug", "release", "testDebug", "testRelease", "benchmarkRelease")

    /** §1.1: feature-integrations → backup, library, imaging, mediastore (contratti), designsystem (+ le fondamenta). */
    val ALLOWED_INTEGRATIONS_DEPS = setOf(BACKUP, "core-library", "core-imaging", "core-mediastore", "core-designsystem", "core-common", "core-model")

    val INTEGRATION_CLASS_NAMES = listOf("PickerActivity", "ShareReceiverActivity", "MediaDocumentsProvider", "IntegrationFileProvider")

    // I nomi vietati sono scritti in due pezzi: questo file non contiene mai il nome intero, cosi' non si segnala da solo.
    val NAS_OR_NASOPS_PACKAGE = Regex("""\bdev\.pampa\.mycloudgallery\.core\.nas(ops)?\.""")
    val NAS_PRIMITIVE_NAMES = Regex("""\b(\w*Nas""" + """FileOps\w*|\w*Nas""" + """BackupOps\w*|NasMedia""" + """Reader)\b""")

    val PHONE_ISSUERS = Regex("""\b(Phone""" + """Confirmation""" + """Issuer|Phone""" + """Confirmation""" + """Sheet)\b""")
    val PHONE_CONSTRUCTOR = Regex("""\bPhone""" + """Confirmation\s*\(""")
    val INTERNAL_PHONE_CONFIRMATION = Regex("""class\s+Phone""" + """Confirmation\s+internal\s+constructor""")
  }
}
