package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumContent
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.noop.NoShareRunner
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumFamily
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.albums.testing.FakeSharedAlbums
import dev.pampa.mycloudgallery.core.albums.testing.FakeSharedWorld
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryAlbums
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryTrips
import dev.pampa.mycloudgallery.core.albums.testing.SmartMembership
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.Today
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first

// Fase 7 (P9, D22, E-H5, E-H6, E-H7): le SONDE di riferimento di album e viaggi. Il codice vero arriva con P3, P6, P2/P4 e P7: nel frattempo la
// sonda gira sugli ESECUTORI DI RIFERIMENTO del contratto (`InMemoryAlbums`, `InMemoryTrips`) sopra una Libreria finta costruita dallo stesso
// mondo casuale. Quando il codice vero arriva, il registro fallisce finche' la sonda non gira su di lui (marcatori `landedWhen`).

/** Gli esecutori di riferimento di album e viaggi sopra una Libreria finta fatta dal mondo [w]; i media nascosti si nascondono **dopo** aver scelto le copertine. */
internal class ReferenceStack(
  private val w: BuiltWorld,
  account: AccountId = AccountId("test"),
  val sharedWorld: FakeSharedWorld = FakeSharedWorld(),
) {
  val library = FakeAlbumLibrary()
  val family = FakeAlbumFamily(account)
  val clocks = FakeAlbumClocks()
  val shared = FakeSharedAlbums(sharedWorld, family, library)
  var smart: SmartMembership = SmartMembership.EMPTY
  private var nonce = 0
  val albums = InMemoryAlbums(library, family, clocks, shared, NoShareRunner, { smart }, { ByteArray(16) { (it + 17 * ++nonce).toByte() } })

  /** I media del mondo, tutti visibili: nascosti ancora no. */
  val media: List<FakeMedia> = w.spec.media.map { m ->
    val utc = m.day.atStartOfDay().plusMinutes(m.minuteOfDay.toLong()).toInstant(ZoneOffset.ofHours(2)).toEpochMilli()
    FakeMedia(
      id = w.ids[m.index], takenAtUtcMs = utc, zoneOffsetMinutes = 120, kind = m.kind, mediaSource = m.source,
      place = when (m.where) {
        Where.PHONE -> MediaPlace.PHONE_ONLY
        Where.NAS_PRIVATE, Where.PHONE_AND_PRIVATE -> MediaPlace.MY_PRIVATE
        Where.NAS_COMMON -> MediaPlace.COMMON
      },
      onPhone = m.onPhone,
    )
  }

  init {
    library.put(*media.toTypedArray())
  }

  /** Un sottoinsieme riproducibile del mondo ([salt] lo cambia): i candidati di un album, nascosti compresi. */
  fun pick(salt: Int, share: Int = 3): List<MediaId> = w.ids.filterIndexed { i, _ -> (i * 7 + salt + w.spec.seed.toInt()).mod(share) == 0 }

  /** Adesso i nascosti si nascondono, con le copertine gia' scelte (E-H6: nascosta da un altro dispositivo dello stesso account). */
  fun hideThemAll() {
    w.mustNeverShow.forEach { library.hide(it) }
  }

  suspend fun create(title: String, kind: AlbumKind, rules: SmartRules? = null): AlbumId =
    (albums.create(title, kind, rules) as CreateOutcome.Created).id

  suspend fun contentOf(id: AlbumId): AlbumContent = checkNotNull(albums.content(id).first()) { "album sparito" }

  fun idsOf(view: TimelineView?): Set<MediaId> =
    view?.let { v -> (0 until v.size).mapTo(LinkedHashSet()) { v.snapshot.idAt(v.snapshotIndex(it)) } }.orEmpty()
}

internal object ReferenceProbes {

  private fun leak(name: String, shown: Collection<MediaId>, forbidden: Set<MediaId>): String? =
    if (shown.any { it in forbidden }) "$name mostra un nascosto" else null

  /**
   * Viaggi ed eventi (E-H7): una storia casuale con la casa a Firenze, viaggi a Oslo e una festa, con nascosti a caso e, a seed dispari, un viaggio
   * fatto solo di nascosti. La sonda controlla da se' (gli id della storia non sono quelli del catalogo): nessuna proposta ha una copertina nascosta
   * o conta un nascosto, e un viaggio di soli nascosti non si propone.
   */
  val trips = SurfaceProbe { w ->
    val random = Random(w.spec.seed * 31 + 7)
    val today = LocalDate.of(2026, 10, 22)
    val firenze = GeoPoint(43.7696, 11.2558)
    val oslo = GeoPoint(59.9139, 10.7522)
    val library = FakeAlbumLibrary()
    var n = 0
    fun shot(day: LocalDate, hour: Int, gps: GeoPoint?, minute: Int = 0, hidden: Boolean = false): FakeMedia {
      val utc = day.atTime(hour, 0).plusMinutes(minute.toLong()).toInstant(ZoneOffset.ofHours(1)).toEpochMilli()
      return FakeMedia(FakeAlbumLibrary.id(++n), utc, zoneOffsetMinutes = 60, gps = gps, hidden = hidden, onPhone = true)
    }
    val tripA = LocalDayRange(LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 16))
    val tripB = LocalDayRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))
    for (d in 0 until 60) {
      val day = today.minusDays(10L + d * 4L)
      if (day in tripA || day in tripB) continue
      library.put(shot(day, 21, firenze, hidden = random.nextInt(100) < 15), shot(day, 13, firenze, hidden = random.nextInt(100) < 15))
    }
    val wholeTripAHidden = w.spec.seed % 2L == 1L
    val all = ArrayList<FakeMedia>()
    for ((range, allHidden) in listOf(tripA to wholeTripAHidden, tripB to false)) {
      var day = range.from
      while (!day.isAfter(range.to)) {
        repeat(5) { h -> all += shot(day, 10 + h, oslo, hidden = allHidden || random.nextInt(100) < 25) }
        day = day.plusDays(1)
      }
    }
    library.put(*all.toTypedArray())
    val party = LocalDate.of(2026, 4, 11)
    library.put(*(0 until 40).map { shot(party, 18, null, minute = it * 3, hidden = random.nextInt(100) < 20) }.toTypedArray())

    val hidden = library.all().filter { it.hidden }.map { it.id }.toSet()
    val clocks = FakeAlbumClocks()
    val albums = InMemoryAlbums(library, FakeAlbumFamily(AccountId("test")), clocks)
    val found = InMemoryTrips(library, Today { today }, albums).also { it.detect(1.minutes) }.proposals.value
    val problems = ArrayList<String>()
    for (proposal in found.proposals) {
      if (proposal.cover in hidden) problems += "una proposta di viaggio ha come copertina un nascosto"
      val inRange = library.all().filter { it.day in proposal.days && !it.hidden }
      if (proposal.count > inRange.size) problems += "una proposta di viaggio conta piu' media dei visibili"
    }
    if (wholeTripAHidden && found.proposals.any { it.days.from >= tripA.from && it.days.to <= tripA.to }) problems += "un viaggio di soli nascosti e' stato proposto"
    // Gli id della storia non sono quelli del catalogo del mondo: la sonda si controlla da sola e non mostra niente al controllo generale.
    Probed(shown = emptySet(), problems = problems)
  }
}
