package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.PlaceRef
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * I candidati degli album smart e dei viaggi (Fase 7, P1; `AlbumCatalog.candidates`): una cella visibile per media, mai un nascosto,
 * l'ordine (giorno, istante, id) con la paginazione esatta, i tre indici guida (giorno, dispositivo, luogo), le sorgenti e la
 * traduzione dei dati. Le colonne derivate (`origin_device`, `copy_areas`) le scrive il catalogo (`derive`), non il test.
 */
@RunWith(AndroidJUnit4::class)
class AlbumCandidatesTest : CatalogTestBase() {

  private val albums get() = catalog.albums

  private fun utc(day: LocalDate, hour: Int = 12, minute: Int = 0): Long =
    day.atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

  /** Un media del telefono con la data di scatto [taken] (UTC, fuso +02:00), il GPS e il luogo se dati. */
  private suspend fun phone(
    n: Int,
    day: LocalDate,
    hour: Int = 12,
    gps: Pair<Double, Double>? = null,
    place: Int? = null,
    source: MediaSource = MediaSource.CAMERA,
    width: Int = 4000,
    height: Int = 3000,
  ): MediaId {
    val taken = utc(day, hour)
    val id = catalog.record(phoneObservation("IMG_$n.jpg", 100L + n, smallFull(n), taken, source = source)).mediaId!!
    catalog.updateMetadata(
      catalog.versionOf(id),
      MediaMetadata(
        mimeType = "image/jpeg",
        width = width,
        height = height,
        captureTime = CaptureTime(taken, 120, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET),
        location = gps?.let { GeoPoint(it.first, it.second) },
        place = place?.let { PlaceRef(it, 1) },
      ),
    )
    return id
  }

  private suspend fun allCandidates(query: CandidateQuery, pageSize: Int): List<CatalogCandidate> {
    val out = ArrayList<CatalogCandidate>()
    var after: CandidateKey? = null
    do {
      val page = albums.candidates(query, after, pageSize)
      assertThat(page.items.size).isAtMost(pageSize)
      out += page.items
      after = page.next
    } while (after != null)
    return out
  }

  @Test
  fun iCandidatiEsconoOrdinatiPerGiornoIstanteEIdInPagineEsatte() = runBlocking<Unit> {
    val days = (1..9).map { LocalDate.of(2026, 3, it) }
    // 27 media: tre al giorno, in ordine d'inserimento sparso; due allo stesso istante per provare la chiave d'identita'.
    val expected = ArrayList<Pair<MediaId, Long>>()
    var n = 1
    for (day in days.reversed()) {
      for (hour in listOf(18, 9, 9)) expected += phone(n++, day, hour) to utc(day, hour)
    }
    val sorted = expected.sortedWith(compareBy({ it.second }, { it.first.value })).map { it.first }

    for (pageSize in listOf(1, 4, 5, 27, 100)) {
      val found = allCandidates(CandidateQuery(), pageSize)
      assertThat(found.map { it.media }).containsExactlyElementsIn(sorted).inOrder()
    }
    // Un intervallo di giorni: solo quelli, estremi compresi, anche con una pagina che comincia a meta' di un giorno.
    val range = allCandidates(CandidateQuery(days = 20260303..20260305), pageSize = 2)
    assertThat(range.map { it.day }.distinct()).containsExactly(days[2], days[3], days[4]).inOrder()
    assertThat(range).hasSize(9)
  }

  @Test
  fun unNascostoUnMembroDiGruppoEUnMediaSenzaCopieNonSonoMaiCandidati() = runBlocking<Unit> {
    val day = LocalDate.of(2026, 3, 4)
    val visible = phone(1, day)
    val hidden = phone(2, day)
    val coverOfBurst = phone(3, day)
    val burstMember = phone(4, day)
    val gone = phone(5, day)
    catalog.setPersonalFlags(hidden, PersonalFlags(hidden = true))
    catalog.group(MediaGroupKind.BURST, listOf(coverOfBurst, burstMember), coverOfBurst)
    catalog.markPhoneLocationsVanished(catalog.media(gone)!!.locations.map { it.id })

    val found = allCandidates(CandidateQuery(), pageSize = 10).map { it.media }

    assertThat(found).containsExactly(visible, coverOfBurst)
    assertThat(albums.visibleCount()).isEqualTo(2)
    // Nascondere un media lo toglie subito, anche dal conteggio.
    catalog.setPersonalFlags(visible, PersonalFlags(hidden = true))
    assertThat(allCandidates(CandidateQuery(), pageSize = 10).map { it.media }).containsExactly(coverOfBurst)
    assertThat(albums.visibleCount()).isEqualTo(1)
    assertThat(albums.candidatesOf(listOf(visible, hidden, burstMember, gone, coverOfBurst)).map { it.media }).containsExactly(coverOfBurst)
  }

  @Test
  fun leSorgentiSonoUnaPartizioneTelefonoPoiAreaPrivataPoiFamiliariPoiComune() = runBlocking<Unit> {
    val day = LocalDate.of(2026, 3, 4)
    val onPhone = phone(1, day)
    // Solo area privata (del mio account), solo area comune (proprietario sconosciuto), area comune di un familiare, e un media che e'
    // sul telefono e nell'area comune: resta del telefono.
    val privateOnly = catalog.record(nasObservation("Backup/S25/2026/03/b.jpg", smallQuick(2))).mediaId!!
    val commonOnly = catalog.record(nasObservation("Album condivisi/Oslo/c.jpg", smallQuick(3), share = "NAS")).mediaId!!
    val familyCommon = catalog.record(nasObservation("Album condivisi/Oslo/d.jpg", smallQuick(4), share = "NAS")).mediaId!!
    db.mediaDao().setOwner(familyCommon.value, "marco", T0)
    val phoneAndCommon = phone(5, day)
    catalog.attachCopy(phoneAndCommon, catalog.versionOf(phoneAndCommon), nasLocation("Album condivisi/e.jpg", "NAS"), stampOf(phoneAndCommon), null)

    val bySource = allCandidates(CandidateQuery(), 50).associate { it.media to it.source }
    assertThat(bySource[onPhone]).isEqualTo(CandidateSource.MY_PHONE)
    assertThat(bySource[phoneAndCommon]).isEqualTo(CandidateSource.MY_PHONE)
    assertThat(bySource[privateOnly]).isEqualTo(CandidateSource.MY_PRIVATE)
    assertThat(bySource[commonOnly]).isEqualTo(CandidateSource.COMMON)
    assertThat(bySource[familyCommon]).isEqualTo(CandidateSource.FAMILY)

    fun ids(vararg sources: CandidateSource) = runBlocking { allCandidates(CandidateQuery(sources = sources.toSet()), 50).map { it.media } }
    assertThat(ids(CandidateSource.MY_PHONE)).containsExactly(onPhone, phoneAndCommon)
    assertThat(ids(CandidateSource.MY_PRIVATE)).containsExactly(privateOnly)
    assertThat(ids(CandidateSource.COMMON)).containsExactly(commonOnly)
    assertThat(ids(CandidateSource.FAMILY)).containsExactly(familyCommon)
    assertThat(ids(CandidateSource.COMMON, CandidateSource.FAMILY)).containsExactly(commonOnly, familyCommon)
    assertThat(allCandidates(CandidateQuery(sources = CandidateSource.ALL), 50)).hasSize(5)
    // Un mio media passato nell'area comune non e' piu' "mine" per sorgente, ma lo dice `ownedByMe`.
    val mineInCommon = catalog.record(nasObservation("Album condivisi/Oslo/f.jpg", smallQuick(6), share = "NAS")).mediaId!!
    db.mediaDao().setOwner(mineInCommon.value, "alessio", T0)
    val candidate = allCandidates(CandidateQuery(), 50).single { it.media == mineInCommon }
    assertThat(candidate.source).isEqualTo(CandidateSource.COMMON)
    assertThat(candidate.ownedByMe).isTrue()
  }

  @Test
  fun iDatiDelCandidatoSonoGiornoOraLocaleGpsLuogoPixelETipoDiSchermo() = runBlocking<Unit> {
    val day = LocalDate.of(2026, 3, 4)
    val shot = phone(1, day, hour = 21, gps = 59.91 to 10.75, place = 3_143_244)
    val screenshot = phone(2, day, hour = 22, source = MediaSource.SCREENSHOT, width = 1080, height = 2400)
    val none = phone(3, day, hour = 12)

    val found = allCandidates(CandidateQuery(), 10).associateBy { it.media }

    val a = found.getValue(shot)
    // 21:00 UTC con +02:00 sono le 23:00 dello stesso giorno; le 22:00 UTC sono mezzanotte del giorno dopo (E-AL10: giorni locali).
    assertThat(a.day).isEqualTo(day)
    assertThat(a.localTime).isEqualTo(LocalTime.of(23, 0))
    assertThat(a.takenAtUtcMs).isEqualTo(utc(day, 21))
    assertThat(a.gps).isEqualTo(GeoPoint(59.91, 10.75))
    assertThat(a.placeId).isEqualTo(3_143_244)
    assertThat(a.pixels).isEqualTo(12_000_000L)
    assertThat(a.kind).isEqualTo(MediaKind.PHOTO)
    assertThat(a.screen).isFalse()
    assertThat(a.originDevice).isEqualTo(OriginDevice.PHONE)
    assertThat(found.getValue(screenshot).screen).isTrue()
    assertThat(found.getValue(screenshot).pixels).isEqualTo(1080L * 2400)
    assertThat(found.getValue(screenshot).day).isEqualTo(day.plusDays(1))
    assertThat(found.getValue(screenshot).localTime).isEqualTo(LocalTime.of(0, 0))
    assertThat(found.getValue(none).gps).isNull()
    assertThat(found.getValue(none).placeId).isNull()
    // Solo con GPS.
    assertThat(allCandidates(CandidateQuery(withGpsOnly = true), 10).map { it.media }).containsExactly(shot)
    // Nessun dato dell'utente in chiaro nel testo.
    assertThat(a.toString()).doesNotContain("59.91")
  }

  @Test
  fun ilDispositivoEIlLuogoGuidanoLaQueryEDannoLoStessoOrdine() = runBlocking<Unit> {
    val oslo = 3_143_244
    val bergen = 3_161_732
    val d1 = LocalDate.of(2026, 3, 1)
    val d2 = LocalDate.of(2026, 3, 2)
    val a = phone(1, d2, 10, gps = 59.9 to 10.7, place = oslo)
    val b = phone(2, d1, 10, gps = 59.9 to 10.7, place = oslo)
    val c = phone(3, d1, 11, gps = 60.4 to 5.3, place = bergen)
    val noPlace = phone(4, d1, 12)
    val tablet = catalog.record(nasObservation("Backup/Tab/2026/03/t.jpg", smallQuick(9))).mediaId!!
    catalog.updateMetadata(
      catalog.versionOf(tablet),
      MediaMetadata(captureTime = CaptureTime(utc(d1, 8), 120, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET), place = PlaceRef(oslo, 1)),
    )

    // Dispositivo: il telefono, la cartella del tablet; con un intervallo di giorni.
    assertThat(allCandidates(CandidateQuery(originDevice = OriginDevice.PHONE), 2).map { it.media }).containsExactly(b, c, noPlace, a).inOrder()
    assertThat(allCandidates(CandidateQuery(originDevice = OriginDevice.backup("tab")), 2).map { it.media }).containsExactly(tablet)
    assertThat(allCandidates(CandidateQuery(originDevice = OriginDevice.PHONE, days = 20260302..20260302), 2).map { it.media }).containsExactly(a)
    // Luogo: un luogo alla volta, in ordine di id, e dentro il luogo per giorno e istante (Bergen 3161732 > Oslo 3143244).
    val byPlace = allCandidates(CandidateQuery(placeIds = setOf(bergen, oslo)), 2)
    assertThat(byPlace.map { it.media }).containsExactly(tablet, b, a, c).inOrder()
    assertThat(allCandidates(CandidateQuery(placeIds = setOf(oslo), days = 20260301..20260301), 1).map { it.media }).containsExactly(tablet, b).inOrder()
    // Luogo e dispositivo insieme: guida il dispositivo, il luogo filtra.
    assertThat(allCandidates(CandidateQuery(placeIds = setOf(oslo), originDevice = OriginDevice.PHONE), 1).map { it.media }).containsExactly(b, a).inOrder()
  }

  @Test
  fun unCandidatoNonCambiaSePerdeLaCopiaDelTelefonoEDiventaDellaCartellaDiBackup() = runBlocking<Unit> {
    val day = LocalDate.of(2026, 3, 4)
    val id = phone(1, day)
    catalog.attachCopy(id, catalog.versionOf(id), nasLocation("Backup/S25/2026/03/IMG_1.jpg"), stampOf(id), null)
    assertThat(allCandidates(CandidateQuery(), 5).single().originDevice).isEqualTo(OriginDevice.PHONE)
    assertThat(db.mediaDao().get(id.value)!!.copyAreas).isEqualTo(CopyAreas.PRIVATE)

    // "Libera spazio": la copia del telefono sparisce, il media e' solo nell'area privata, nella cartella del dispositivo.
    catalog.markPhoneLocationsVanished(catalog.media(id)!!.locations.filter { it.isPhone }.map { it.id })

    val candidate = allCandidates(CandidateQuery(), 5).single()
    assertThat(candidate.originDevice).isEqualTo(OriginDevice.backup("s25"))
    assertThat(candidate.source).isEqualTo(CandidateSource.MY_PRIVATE)
  }

  private suspend fun stampOf(id: MediaId) = FileStamp(catalog.media(id)!!.currentVersion.fingerprint.size, T0)
}
