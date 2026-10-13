package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.time.LocalDate
import org.junit.Test

/** La Libreria sintetica da 100 mila (§5.2): deterministica, con la composizione del progetto. */
class SyntheticLibraryTest {

  private val places = IntArray(40) { 3_000_000 + it * 17 }
  private val library = SyntheticLibrary(seed = 1, count = SyntheticLibrary.FULL_COUNT, placeIds = places)

  @Test
  fun eDeterministicaEHaUnImprontaFissa() {
    val again = SyntheticLibrary(seed = 1, count = SyntheticLibrary.FULL_COUNT, placeIds = places)
    val other = SyntheticLibrary(seed = 2, count = SyntheticLibrary.FULL_COUNT, placeIds = places)
    val print = fingerprint(library)
    println("SYNTHETIC_FINGERPRINT=$print")
    assertThat(fingerprint(again)).isEqualTo(print)
    assertThat(fingerprint(other)).isNotEqualTo(print)
    // L'impronta fissata: una modifica del generatore cambia gli screenshot e i budget, e deve essere voluta.
    assertThat(print).isEqualTo(EXPECTED_FINGERPRINT)
  }

  @Test
  fun haLaComposizioneDelProgetto() {
    val s = library.snapshot
    val n = s.size.toDouble()
    assertThat(s.size).isEqualTo(100_000)
    fun pct(count: Int) = count * 100.0 / n
    val kinds = IntArray(MediaKind.entries.size)
    val sources = IntArray(MediaSource.entries.size)
    var favorites = 0
    var estimated = 0
    var withPlace = 0
    var covers = 0
    val aspects = HashSet<Int>()
    for (i in 0 until s.size) {
      kinds[s.kindAt(i).ordinal]++
      sources[s.sourceAt(i).ordinal]++
      if (s.hasFlag(i, CellFlags.FAVORITE)) favorites++
      if (s.hasFlag(i, CellFlags.DATE_ESTIMATED)) estimated++
      if (s.placeAt(i) != 0) withPlace++
      if (s.groupSizeAt(i) > 1) covers++
      aspects += s.aspectMilliAt(i)
    }
    val composition = "video ${pct(kinds[MediaKind.VIDEO.ordinal])}%, screenshot ${pct(sources[MediaSource.SCREENSHOT.ordinal])}%, " +
      "WhatsApp ${pct(sources[MediaSource.WHATSAPP.ordinal])}%, Quick Share ${pct(sources[MediaSource.QUICK_SHARE.ordinal])}%, " +
      "gruppi ${pct(covers)}%, preferiti ${pct(favorites)}%, stimate ${pct(estimated)}%, luoghi ${pct(withPlace)}%, " +
      "nascosti ${library.hidden.size * 100.0 / n}%"
    println("SYNTHETIC_COMPOSITION: $composition")
    assertThat(pct(kinds[MediaKind.VIDEO.ordinal])).isWithin(0.5).of(8.0)
    assertThat(pct(sources[MediaSource.SCREENSHOT.ordinal])).isWithin(0.5).of(10.0)
    assertThat(pct(sources[MediaSource.WHATSAPP.ordinal])).isWithin(0.7).of(20.0)
    assertThat(pct(sources[MediaSource.QUICK_SHARE.ordinal])).isWithin(0.3).of(2.0)
    assertThat(pct(covers)).isWithin(0.3).of(1.0)
    assertThat(pct(favorites)).isWithin(0.3).of(2.0)
    assertThat(pct(estimated)).isWithin(0.4).of(3.0)
    assertThat(pct(withPlace)).isWithin(3.0).of(60.0)
    assertThat(library.hidden.size).isEqualTo(500)
    assertThat(aspects).containsAtLeast(1333, 750, 1778, 562, 1000, 3000)
    // Durate solo per i video.
    assertThat((0 until s.size).all { (s.kindAt(it) == MediaKind.VIDEO) == (s.durationMsAt(it) > 0) }).isTrue()
  }

  @Test
  fun ordineIdentitaNascostiEMembriFuoriDallIndice() {
    val s = library.snapshot
    assertThat((1 until s.size).all { s.sortTimeAt(it - 1) >= s.sortTimeAt(it) }).isTrue()
    val ids = HashSet<String>(s.size * 2)
    for (i in 0 until s.size) ids += s.idAt(i).value
    assertThat(ids).hasSize(s.size)
    assertThat(s.indexOf(s.idAt(54_321))).isEqualTo(54_321)
    // E-H1 e D20: nascosti e membri non stanno nell'indice.
    assertThat(library.hidden.all { it.hidden && s.indexOf(it.id) < 0 && it.id.value !in ids }).isTrue()
    assertThat(library.groupMembers.all { s.indexOf(it.id) < 0 && CellFlags.has(it.flags, CellFlags.GROUP_MEMBER) }).isTrue()
    // Ogni copertina conta i suoi membri.
    val membersByGroup = library.groupMembers.groupingBy { it.groupId!! }.eachCount()
    for (i in 0 until s.size) {
      val cell = library.cellAt(i)
      if (cell.groupId != null) assertThat(s.groupSizeAt(i)).isEqualTo(1 + membersByGroup.getValue(cell.groupId!!))
    }
    assertThat(library.media().count()).isEqualTo(s.size + library.groupMembers.size + library.hidden.size)
  }

  @Test
  fun viaggiConUnAltroFusoAncheIVideo() {
    val s = library.snapshot
    var tripItems = 0
    var tripVideos = 0
    for (i in 0 until s.size) {
      val cell = library.cellAt(i)
      // Il giorno locale e' sempre quello del fuso dello scatto (E-P5, E-M14).
      val expected = LocalDate.ofEpochDay(Math.floorDiv(cell.sortTimeMs + cell.zoneOffsetMinutes * 60_000L, 86_400_000L))
      assertThat(cell.localDay).isEqualTo(expected.year * 10_000 + expected.monthValue * 100 + expected.dayOfMonth)
      if (cell.zoneOffsetMinutes !in setOf(60, 120)) {
        tripItems++
        if (cell.kind == MediaKind.VIDEO) tripVideos++
      }
    }
    assertThat(tripItems).isGreaterThan(5_000)
    assertThat(tripVideos).isGreaterThan(100)
    // Ci sono elementi il cui giorno locale non e' il giorno UTC (il viaggio sposta la data).
    val shifted = (0 until s.size).count { i ->
      val utc = LocalDate.ofEpochDay(Math.floorDiv(s.sortTimeAt(i), 86_400_000L))
      utc.year * 10_000 + utc.monthValue * 100 + utc.dayOfMonth != s.localDayAt(i)
    }
    assertThat(shifted).isGreaterThan(100)
  }

  @Test
  fun laLibreriaPiccolaDegliScreenshotResta() {
    val small = SyntheticLibrary(seed = 3, count = 50)
    assertThat(small.snapshot.size).isEqualTo(50)
    assertThat(small.view().size).isEqualTo(50)
    assertThat(SyntheticLibrary.DEFAULT_COUNT).isEqualTo(2_000)
    assertThat(small.ref(small.snapshot.idAt(4))).isEqualTo(small.refAt(4))
  }

  private fun fingerprint(l: SyntheticLibrary): Long {
    val s = l.snapshot
    var h = 1125899906842597L
    fun mix(v: Long) {
      h = (h xor v) * 0x100000001b3L
    }
    for (i in 0 until s.size) {
      mix(s.idAt(i).value.hashCode().toLong())
      mix(s.sortTimeAt(i))
      mix(s.localDayAt(i).toLong())
      mix(s.kindAt(i).ordinal.toLong())
      mix(s.sourceAt(i).ordinal.toLong())
      mix(s.flagsAt(i).toLong())
      mix(s.aspectMilliAt(i).toLong())
      mix(s.durationMsAt(i))
      mix(s.placeAt(i).toLong())
      mix(s.groupSizeAt(i).toLong())
    }
    mix(l.hidden.size.toLong())
    mix(l.groupMembers.size.toLong())
    return h
  }

  private companion object {
    const val EXPECTED_FINGERPRINT = 8460701881406806433L
  }
}
