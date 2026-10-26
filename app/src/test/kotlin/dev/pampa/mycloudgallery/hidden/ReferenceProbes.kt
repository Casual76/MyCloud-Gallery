package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumContent
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
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
import dev.pampa.mycloudgallery.core.albums.testing.SmartMembership
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first

// Fase 7 (P9, D22, E-H5, E-H6): le SONDE di riferimento degli album (resta quella dello smart di P6; i viaggi di P7 sono codice vero: `RealTripsProbes`). Nel frattempo la
// sonda gira sull'ESECUTORE DI RIFERIMENTO del contratto (`InMemoryAlbums`) sopra una Libreria finta costruita dallo stesso
// mondo casuale. Quando il codice vero arriva, il registro fallisce finche' la sonda non gira su di lui (marcatori `landedWhen`).

/** L'esecutore di riferimento degli album sopra una Libreria finta fatta dal mondo [w]; i media nascosti si nascondono **dopo** aver scelto le copertine. */
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

}
