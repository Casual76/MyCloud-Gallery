package dev.pampa.mycloudgallery.core.albums.trips

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidates
import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.CandidatePage
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripProposal
import dev.pampa.mycloudgallery.core.albums.contract.TripProposals
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.common.log.LogLevel
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.TripKind
import java.io.File
import java.lang.reflect.Modifier
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * NESSUNA COORDINATA FUORI DA `trips/` (docs/fase7-progetto.md D18, §1.1, §2.3 "Privacy", D37; riga P7 del §4.4):
 *  1. **architettura sulle API pubbliche**: di `trips/` sono pubblici solo `CatalogTrips` e `AwayFromHome`; i loro membri sono un elenco chiuso
 *     e nessuna firma nomina un tipo con coordinate (ne' un numero decimale: la distanza esce come intero arrotondato a 10 km);
 *  2. **sui log**: una rilevazione completa, "Crea album", "Non mi interessa", il ricaricamento e un guasto scrivono solo righe del formato di
 *     `TripLog` (numeri e codici); mai coordinate, casa, luoghi, titoli, date o id di media;
 *  3. i `toString()` non mostrano niente; la casa si legge e si scrive in un solo file.
 * Ogni controllo e' stato provato al contrario reintroducendo la violazione (docs/decisioni.md, "Fase 7 P7").
 */
class TripPrivacyTest {

  private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle").isFile }
  private val tripsDir = File(root, "core-albums/src/main/kotlin/dev/pampa/mycloudgallery/core/albums/trips")
  private val albumsMain = File(root, "core-albums/src/main/kotlin")

  private val blockComment = Regex("""/\*[\s\S]*?\*/""")
  private val lineComment = Regex("""//[^\n]*""")

  /** Il codice senza commenti: un commento puo' nominare quello che il codice non deve nominare. */
  private fun code(text: String): String = text.replace(blockComment, "").replace(lineComment, "")

  private fun sources(): List<Pair<String, String>> =
    tripsDir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }.map { it.name to code(it.readText()) }

  // --- 1. le API pubbliche -----------------------------------------------------------------------------------------------------------------

  /** Cio' che e' pubblico a livello di file in trips/: due cose, e solo due. */
  @Test
  fun dellaCartellaTripsSonoPubbliciSoloCatalogTripsEAwayFromHome() {
    val declaration = Regex(
      """(?m)^((?:(?:public|internal|private|protected|data|sealed|enum|abstract|open|inline|value|annotation|fun|suspend|const|override|lateinit)\s+)*)(class|interface|object|fun|val|var|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)""",
    )
    val publicNames = sources().flatMap { (_, code) ->
      declaration.findAll(code).filter { m -> !Regex("""\b(internal|private)\b""").containsMatchIn(m.groupValues[1]) }.map { it.groupValues[3] }.toList()
    }
    assertThat(publicNames).containsExactly("CatalogTrips", "AwayFromHome")
  }

  /** I membri pubblici di `CatalogTrips` e `AwayFromHome` sono un elenco chiuso: aggiungerne uno vuol dire passare da qui. */
  @Test
  fun iMembriPubbliciSonoUnElencoChiusoESenzaCoordinate() {
    // R16: `homeClearance` restituisce un oracolo che riceve punti e risponde si'/no (vedi `homeClearanceRispondeSoloSiENo`).
    // Fase 9 (P8, D12): `past` e' la porta dei ricordi (`TripMemories`), scritta sopra `TripReader`: riceve un giorno e un tempo, risponde con le
    // `TripDigest` (nessuna coordinata, nessun decimale: la sua firma e' controllata da `laPortaDeiRicordiNonHaCoordinateNeCasa`).
    val allowed = setOf("getProposals", "detect", "accept", "dismiss", "load", "refresh", "isAway", "distanceKm", "homeClearance", "past")
    val banned = listOf("GeoPoint", "HomeEstimate", "Anchor", "GeoIndex", "HomeTimeline", "TripArea", "Detection", "DetectedItem", "StoredProposal", "double", "Double", "float", "Pair", "Triple")
    for (type in listOf(CatalogTrips::class.java, AwayFromHome::class.java)) {
      val methods = type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
      // I nomi dei metodi con tipi inline (TripId, Duration) hanno il suffisso del compilatore: `detect-VtjQ1oo`.
      val extra = methods.map { it.name.substringBefore('-') }.toSet() - allowed
      assertWithMessage("membri pubblici nuovi in ${type.simpleName}").that(extra).isEmpty()
      for (m in methods) {
        val signature = m.genericReturnType.typeName + " " + m.genericParameterTypes.joinToString { it.typeName }
        for (b in banned) assertWithMessage("${type.simpleName}.${m.name}: $signature").that(signature).doesNotContain(b)
      }
      for (c in type.declaredConstructors.filter { Modifier.isPublic(it.modifiers) }) {
        val signature = c.genericParameterTypes.joinToString { it.typeName }
        for (b in banned) assertWithMessage("costruttore di ${type.simpleName}: $signature").that(signature).doesNotContain(b)
      }
      assertThat(type.declaredFields.filter { Modifier.isPublic(it.modifiers) }).isEmpty()
    }
    assertThat(allowed - CatalogTrips::class.java.declaredMethods.map { it.name.substringBefore('-') }.toSet()).isEmpty()
  }

  /**
   * Fase 9 (P0, D12, C31): la porta dei viaggi per i ricordi e il suo dato non portano coordinate ne' casa. Per riflessione: i metodi di
   * `TripMemories` e i campi e i getter di `TripDigest` non nominano tipi con coordinate, numeri decimali o la casa; i campi sono un elenco
   * chiuso. Aggiungere un campo vuol dire passare da qui.
   */
  @Test
  fun laPortaDeiRicordiNonHaCoordinateNeCasa() {
    val banned = listOf("GeoPoint", "GeoE", "HomeEstimate", "HomeClearance", "AwayFromHome", "Anchor", "double", "Double", "float", "Float", "BigDecimal")
    val port = dev.pampa.mycloudgallery.core.albums.contract.TripMemories::class.java
    val portMethods = port.declaredMethods.filter { !it.isSynthetic }
    assertThat(portMethods.map { it.name.substringBefore('-') }).containsExactly("past")
    for (m in portMethods) {
      val signature = m.genericReturnType.typeName + " " + m.genericParameterTypes.joinToString { it.typeName }
      for (b in banned) assertWithMessage("TripMemories.${m.name}: $signature").that(signature).doesNotContain(b)
    }
    val digest = dev.pampa.mycloudgallery.core.albums.contract.TripDigest::class.java
    val fields = digest.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
    assertThat(fields.map { it.name }).containsExactly("id", "kind", "days", "label", "placeIds", "members", "album", "startQuarter")
    for (f in fields) for (b in banned) assertWithMessage("TripDigest.${f.name}: ${f.genericType.typeName}").that(f.genericType.typeName).doesNotContain(b)
    for (m in digest.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }) {
      val signature = m.genericReturnType.typeName + " " + m.genericParameterTypes.joinToString { it.typeName }
      for (b in banned) assertWithMessage("TripDigest.${m.name}: $signature").that(signature).doesNotContain(b)
    }
  }

  /** R16: l'oracolo «troppo vicino a casa» riceve punti e risponde solo si'/no: nessun tipo con coordinate, nessun numero esce dalla casa. */
  @Test
  fun homeClearanceRispondeSoloSiENo() {
    val methods = dev.pampa.mycloudgallery.core.albums.contract.HomeClearance::class.java.declaredMethods.filter { !it.isSynthetic }
    assertThat(methods.map { it.name }).containsExactly("closeToHome")
    // Il risultato e' una lista di booleani (la coroutine aggiunge il parametro di continuazione: il tipo di ritorno e' Object).
    assertThat(methods.single().parameterTypes.size).isEqualTo(2)
    val impl = CatalogTrips::class.java.declaredMethods.single { it.name == "homeClearance" }
    assertThat(impl.returnType).isEqualTo(dev.pampa.mycloudgallery.core.albums.contract.HomeClearance::class.java)
  }

  /** Una `data class` con coordinate scriverebbe la posizione nel `toString()`: in trips/ non ce ne sono senza un `toString()` suo. */
  @Test
  fun leDataClassDiTripsHannoUnToStringSicuro() {
    for ((name, code) in sources()) {
      val data = Regex("""\bdata class\s+(\w+)""").findAll(code).count()
      val own = Regex("""override fun toString\(\)""").findAll(code).count()
      assertWithMessage("$name: data class senza toString() proprio").that(own).isAtLeast(data)
    }
  }

  /** La casa si legge e si scrive in un solo file di trips/ e in nessun altro posto di core-albums. */
  @Test
  fun laCasaSiLeggeESiScriveInUnSoloFile() {
    val home = Regex("""HomeEstimateEntity|upsertHome|clearHome|\.home\(\)|home_estimate""")
    val inTrips = sources().filter { (_, code) -> home.containsMatchIn(code) }.map { it.first }
    assertThat(inTrips).containsExactly("TripStore.kt")
    val outside = albumsMain.walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.replace('\\', '/').contains("/albums/trips/") }
      .filter { home.containsMatchIn(code(it.readText())) }.map { it.name }.toList()
    assertThat(outside).isEmpty()
  }

  /** Il log e' scritto in un solo file di trips/ (`TripLog`): tutti gli altri non possono scrivere niente. */
  @Test
  fun soloTripLogScriveUnLog() {
    val writes = Regex("""GalleryLog|android\.util\.Log|\bLog\.[dviwe]\(|println\(|System\.(out|err)|printStackTrace|Timber""")
    val files = sources().filter { (_, code) -> writes.containsMatchIn(code) }.map { it.first }
    assertThat(files).containsExactly("TripLog.kt")
  }

  // --- 2. i log ---------------------------------------------------------------------------------------------------------------------------

  private class Captured(val level: LogLevel, val tag: String, val message: String, val error: Throwable?)

  private fun <T> capturing(block: (MutableList<Captured>) -> T): T {
    val lines = ArrayList<Captured>()
    GalleryLog.install { level, tag, message, error -> lines += Captured(level, tag, message, error) }
    try {
      return block(lines)
    } finally {
      GalleryLog.uninstall()
    }
  }

  /** Il solo formato ammesso di una riga di log di trips/: numeri, booleani e codici. */
  private val allowedLines = listOf(
    Regex("""^rilevazione: \d+ viaggi, \d+ eventi, casa stimata=(true|false), -?\d+ ms$"""),
    Regex("""^TRIP_NO_GPS: nessuna casa stimabile, solo eventi$"""),
    Regex("""^rilevazione interrotta: il tempo a disposizione e' finito, le proposte restano quelle di prima$"""),
    Regex("""^proposte ricontrollate: \d+$"""),
    Regex("""^operazione non riuscita \(\w+\)$"""),
  )

  private fun everything(story: Story, extra: (TripsHarness) -> Unit = {}): List<Captured> = capturing { lines ->
    runBlocking {
      TripsHarness(StoryLibrary(story.shots), story.today).use { h ->
        h.trips.detect(1.minutes)
        val shown = h.trips.proposals.value.proposals
        shown.take(3).forEach { h.trips.accept(it.id) }
        shown.drop(3).take(1).forEach { h.trips.dismiss(it.id) }
        h.trips.load()
        h.trips.refresh()
        h.trips.isAway(story.today.minusDays(40))
        h.trips.distanceKm(story.today.minusDays(40))
        extra(h)
      }
    }
    lines
  }

  @Test
  fun iLogDiUnaRilevazioneCompletaHannoSoloNumeriECodici() {
    for (name in listOf("Firenze", "Trasloco", "Viaggi in altri fusi", "Tutto insieme", "Nessun GPS")) {
      val story = TripStories.all().first { it.name.startsWith(name) }
      val lines = everything(story)
      assertThat(lines).isNotEmpty()
      for (line in lines) {
        assertWithMessage("${story.name}: riga fuori formato: ${line.message}").that(allowedLines.any { it.matches(line.message) }).isTrue()
        assertThat(line.tag).isEqualTo("Trips")
        assertThat(line.error).isNull()
      }
    }
  }

  @Test
  fun inOgniCasoNeCoordinateNeLuoghiNeTitoliNeDateNeIdNeiLog() {
    val story = TripStories.all().first { it.name.startsWith("Tutto insieme") }
    val lines = everything(story)
    val text = lines.joinToString("\n") { it.message }
    // Le coordinate di ogni scatto, a due decimali, e i nomi dei luoghi.
    val coordinates = story.shots.mapNotNull { it.gps }.flatMap { listOf("%.2f".format(it.latitude), "%.2f".format(it.longitude)) }.toSet()
    for (c in coordinates) assertThat(text).doesNotContain(c)
    val names = listOf("Firenze", "Milano", "Bologna", "Roma", "Oslo", "Londra", "Parigi", "Lisbona", "Tokyo", "New York", "Alassio", "Pavia", "Viaggio", "Sabato")
    for (n in names) assertThat(text).doesNotContain(n)
    // Date, mesi e id.
    assertThat(Regex("""\d{4}-\d{2}-\d{2}|\d{1,2} (gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre)""").containsMatchIn(text)).isFalse()
    assertThat(Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{16,}""").containsMatchIn(text)).isFalse()
  }

  /** Un guasto scrive il nome della classe dell'eccezione e basta: il suo messaggio potrebbe avere un luogo, una coordinata o un id. */
  @Test
  fun unGuastoNonScriveIlMessaggioDellEccezione() {
    val story = TripStories.all().first { it.name.startsWith("Firenze") }
    val leaking = object : AlbumCandidates {
      override suspend fun page(filter: CandidateFilter, after: CandidateCursor?, limit: Int): CandidatePage =
        throw IllegalStateException("Firenze 43.7696,11.2558 Via Roma 1 ${MediaId.random()}")

      override suspend fun visibleTotal(): Int = 0
    }
    val lines = capturing { captured ->
      runBlocking {
        val library = StoryLibrary(story.shots)
        val trips = CatalogTrips(
          leaking, TripVisibility(library, library), MemoryTripStore(), MemoryDismissals(), dev.pampa.mycloudgallery.core.albums.noop.NoAlbums,
          TripTitles(dev.pampa.mycloudgallery.core.places.testing.FakePlaces()), { story.today }, dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks(),
          kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined), refreshDelayMs = 0,
        )
        try {
          trips.detect(1.minutes)
        } catch (expected: IllegalStateException) {
          // la rilevazione rilancia: la riprende chi la chiama (il lavoro della notte)
        }
      }
      captured
    }
    assertThat(lines.map { it.message }).contains("operazione non riuscita (IllegalStateException)")
    for (line in lines) {
      assertThat(allowedLines.any { it.matches(line.message) }).isTrue()
      assertThat(line.error).isNull()
    }
  }

  // --- 3. i toString e la distanza -------------------------------------------------------------------------------------------------------

  @Test
  fun iToStringNonMostranoNiente() {
    val day = LocalDate.of(2026, 3, 14)
    val range = LocalDayRange(day, day.plusDays(4))
    val media = MediaId.random()
    val anchor = Anchor(43.769612, 11.255834, 3.2, 40)
    val anchors = Anchors(anchor, listOf(Anchor(41.891977, 12.511301, 2.0, 12)), 120)
    val candidate = AlbumCandidate(
      media, day, 1L, java.time.LocalTime.of(21, 0), dev.pampa.mycloudgallery.core.model.GeoPoint(43.769612, 11.255834), 3176959, "questo-telefono",
      dev.pampa.mycloudgallery.core.albums.contract.SmartSource.MY_PHONE, dev.pampa.mycloudgallery.core.model.MediaKind.PHOTO, false, 12_000_000,
    )
    val item = DetectedItem(TripKind.TRIP, range, listOf(candidate), listOf(candidate), PlaceStats.of(listOf(candidate)))
    val proposal = TripProposal(item.id, TripKind.TRIP, range, "Firenze, marzo 2026", 5, media, listOf(3176959))
    val stored = StoredProposal(item.id, TripKind.TRIP, range, "Firenze, marzo 2026", 5, media, listOf(3176959), AlbumId.random(), 1L)
    val shown = listOf(
      anchor, anchors, GeoIndex(listOf(candidate)), HomeTimeline.build(GeoIndex(listOf(candidate)), day), TripArea(43.77, 11.25, 30), item, Detection(anchors, listOf(item)),
      stored, item.places, proposal, TripProposals(listOf(proposal), true), item.id, range, candidate,
    ).map { it.toString() }
    for (text in shown) {
      for (secret in listOf("43.7", "11.25", "41.89", "12.51", "Firenze", "3176959", "2026-03", media.value, "questo-telefono")) assertThat(text).doesNotContain(secret)
    }
  }

  @Test
  fun laDistanzaEsceArrotondataA10Km() = runBlocking<Unit> {
    val story = TripStories.all().first { it.name.startsWith("Firenze") }
    TripsHarness(StoryLibrary(story.shots), story.today).use { h ->
      h.trips.detect(1.minutes)
      var seen = 0
      var day = story.today.minusDays(700)
      while (day.isBefore(story.today)) {
        val km = h.trips.distanceKm(day)
        if (km != null) {
          seen++
          assertThat(km % 10).isEqualTo(0)
          // Lontano o vicino in modo netto (vicino alla soglia l'arrotondamento puo' stare da una parte e la mediana dall'altra).
          if (km >= 60) assertThat(h.trips.isAway(day)).isTrue()
          if (km <= 40) assertThat(h.trips.isAway(day)).isFalse()
        }
        day = day.plusDays(1)
      }
      assertThat(seen).isGreaterThan(200)
      assertThat(TripGeometry.roundedKm(4.9)).isEqualTo(0)
      assertThat(TripGeometry.roundedKm(5.0)).isEqualTo(10)
      assertThat(TripGeometry.roundedKm(14.9)).isEqualTo(10)
      assertThat(TripGeometry.roundedKm(9_876.0)).isEqualTo(9_880)
    }
  }

}
