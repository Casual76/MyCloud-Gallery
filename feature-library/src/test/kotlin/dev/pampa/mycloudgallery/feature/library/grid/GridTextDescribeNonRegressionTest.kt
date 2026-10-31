package dev.pampa.mycloudgallery.feature.library.grid

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.describe.rules.MediaDescribers
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random
import org.junit.After
import org.junit.Test

/**
 * Fase 10 (P7, D22): la descrizione di una cella passa da `MediaDescriber` ma **per i fatti di oggi il testo e' quello di prima**. Qui sta,
 * copiata alla lettera, la `GridText.describe` della Fase 9 (l'oracolo); il test la confronta con quella nuova su una Libreria sintetica con
 * flag casuali (preferiti, modificate, date stimate, illeggibili, pile, ogni presenza), in tre fusi. Senza porte legate non compare mai una
 * persona ne' un testo letto.
 */
class GridTextDescribeNonRegressionTest {

  private val strings = GridStrings(
    today = "Oggi", yesterday = "Ieri", kindPhoto = "Foto", kindVideo = "Video", kindMotion = "Foto in movimento",
    kindRaw = "Foto RAW", kindAnimated = "Immagine animata", favoriteFeminine = "preferita", favoriteMasculine = "preferito",
    editedFeminine = "modificata", editedMasculine = "modificato", unreadable = "non leggibile", estimated = "data stimata",
    duration = "durata %1\$s", group = "%1\$s scatti", phoneOnly = "solo sul telefono", nasOnly = "solo sul NAS",
    both = "sul telefono e sul NAS", safe = "al sicuro sul NAS", toVerify = "copia sul NAS da verificare",
    backupProblem = "problema con il backup", similarCopies = "%1\$s copie simili",
    open = "Apri", select = "Seleziona", deselect = "Deseleziona", scrubber = "Scorri la timeline",
  )

  @After
  fun noBinding() = MediaDescribers.reset()

  @Test
  fun stessiTestiDiOggiPerIFattiDiOggi() {
    val library = SyntheticLibrary(seed = 7L, count = 1_500, placeIds = intArrayOf(3176959, 3143244, 2267057, 3169070, 3173435))
    val places = FakePlaces()
    val random = Random(21)
    val flagBits = intArrayOf(
      CellFlags.FAVORITE, CellFlags.EDITED, CellFlags.DATE_ESTIMATED, CellFlags.UNREADABLE, CellFlags.SIMILAR_COPIES,
      CellFlags.ON_PHONE, CellFlags.ON_NAS, CellFlags.SAFE, CellFlags.BACKUP_PROBLEM,
    )
    var compared = 0
    var withPlace = 0
    var withTime = 0
    var video = 0
    for (zone in listOf(ZoneId.of("UTC"), ZoneId.of("Europe/Rome"), ZoneId.of("Pacific/Auckland"))) {
      for (index in 0 until library.snapshot.size) {
        var flags = library.snapshot.flagsAt(index)
        for (bit in flagBits) if (random.nextInt(4) == 0) flags = flags xor bit
        val snapshot = library.snapshot.withFlags(index, flags)
        val expected = oldDescribe(snapshot, index, strings, places, zone)
        val actual = GridText.describe(snapshot, index, strings, places, zone)
        assertThat(actual).isEqualTo(expected)
        compared++
        if (snapshot.placeAt(index) > 0 && places.labelOfId(snapshot.placeAt(index)) != null) withPlace++
        if (expected.split(", ").any { Regex("""\d{1,2}:\d{2}""").matches(it) }) withTime++
        if (snapshot.kindAt(index) == MediaKind.VIDEO) video++
      }
    }
    // Il confronto vede davvero i casi: luoghi, ore e video ci sono.
    assertThat(compared).isEqualTo(4_500)
    assertThat(withPlace).isGreaterThan(0)
    assertThat(withTime).isGreaterThan(0)
    assertThat(video).isGreaterThan(0)
  }

  @Test
  fun senzaPorteLegateNonCompareMaUnaPersonaNeUnTesto() {
    val library = SyntheticLibrary(seed = 3L, count = 200)
    val facts = (0 until library.snapshot.size).map { GridText.describe(library.snapshot, it, strings, FakePlaces(), ZoneId.of("UTC")) }
    assertThat(facts.none { it.contains("con la scritta") || it.contains(", con ") }).isTrue()
  }

  // ---- l'oracolo: `GridText.describe` della Fase 9, alla lettera ----

  private fun oldDescribe(snapshot: TimelineSnapshot, index: Int, strings: GridStrings, places: PlaceNames, zone: ZoneId): String {
    val kind = snapshot.kindAt(index)
    val flags = snapshot.flagsAt(index)
    val feminine = kind != MediaKind.VIDEO
    val day = GridText.date(snapshot.localDayAt(index))
    val parts = ArrayList<String>(8)
    parts += when (kind) {
      MediaKind.PHOTO -> strings.kindPhoto
      MediaKind.VIDEO -> strings.kindVideo
      MediaKind.MOTION_PHOTO -> strings.kindMotion
      MediaKind.RAW_PHOTO -> strings.kindRaw
      MediaKind.ANIMATED_IMAGE -> strings.kindAnimated
    }
    parts += GalleryDates.date(day)
    val instant = Instant.ofEpochMilli(snapshot.sortTimeAt(index)).atZone(zone)
    if (instant.toLocalDate() == day && !CellFlags.has(flags, CellFlags.DATE_ESTIMATED)) parts += GalleryDates.time(instant.toLocalDateTime())
    places.labelOfId(snapshot.placeAt(index))?.let { parts += it }
    if (kind == MediaKind.VIDEO) parts += strings.duration.format(GridText.duration(snapshot.durationMsAt(index)))
    if (CellFlags.has(flags, CellFlags.FAVORITE)) parts += if (feminine) strings.favoriteFeminine else strings.favoriteMasculine
    if (CellFlags.has(flags, CellFlags.EDITED)) parts += if (feminine) strings.editedFeminine else strings.editedMasculine
    if (CellFlags.has(flags, CellFlags.DATE_ESTIMATED)) parts += strings.estimated
    if (CellFlags.has(flags, CellFlags.UNREADABLE)) parts += strings.unreadable
    val group = snapshot.groupSizeAt(index)
    if (group > 1) {
      parts += (if (CellFlags.has(flags, CellFlags.SIMILAR_COPIES)) strings.similarCopies else strings.group).format(GalleryNumbers.format(group))
    }
    when (snapshot.presenceAt(index)) {
      PresenceBadge.PHONE_ONLY -> parts += strings.phoneOnly
      PresenceBadge.NAS_ONLY -> parts += strings.nasOnly
      PresenceBadge.BOTH -> parts += strings.both
      PresenceBadge.SAFE -> parts += strings.safe
      PresenceBadge.TO_VERIFY -> parts += strings.toVerify
      PresenceBadge.PROBLEM -> parts += strings.backupProblem
      null -> Unit
    }
    return parts.joinToString(", ")
  }
}
