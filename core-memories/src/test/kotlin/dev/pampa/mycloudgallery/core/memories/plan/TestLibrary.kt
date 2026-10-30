package dev.pampa.mycloudgallery.core.memories.plan

import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Una Libreria sintetica a id **espliciti** (`SyntheticMemoryLibrary` li inventa dal seme): per provare che lo stesso insieme di scatti in un
 * ordine diverso da' lo stesso risultato, per i fusi (il giorno locale non e' quello di UTC) e per le Librerie grandi (100 mila celle senza una
 * istantanea per ogni aggiunta). Le colonne sono ordinate come l'istantanea vera: dal piu' recente, a parita' l'id maggiore.
 */
internal class TestShot(
  val id: MediaId,
  /** Il giorno locale dello scatto (`aaaammgg`). */
  val localDay: Int,
  /** Il momento assoluto (UTC, millisecondi): puo' cadere in un altro giorno del giorno locale. */
  val sortTime: Long,
  val source: MediaSource = MediaSource.CAMERA,
  val kind: MediaKind = MediaKind.PHOTO,
  val flags: Int = 0,
  val place: Int = 0,
)

internal fun testId(n: Long, k: Long = 0): MediaId = MediaId(UUID(0x7000_0000L + n, k).toString())

internal fun dayInt(date: LocalDate): Int = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth

/** Il momento UTC di [time] nel giorno [date] visto con uno scarto di [offsetHours] ore da UTC (il giorno locale e' [date]). */
internal fun instantOf(date: LocalDate, time: LocalTime, offsetHours: Int = 0): Long =
  date.atTime(time).toInstant(ZoneOffset.ofHours(offsetHours)).toEpochMilli()

internal fun snapshotOf(shots: List<TestShot>, seq: Long = 1): InMemoryTimelineSnapshot {
  val live = shots.sortedWith(compareByDescending<TestShot> { it.sortTime }.thenByDescending { it.id.value })
  val uuids = live.map { UUID.fromString(it.id.value) }
  return InMemoryTimelineSnapshot(
    seq = seq,
    idMsb = LongArray(live.size) { uuids[it].mostSignificantBits },
    idLsb = LongArray(live.size) { uuids[it].leastSignificantBits },
    sortTime = LongArray(live.size) { live[it].sortTime },
    localDay = IntArray(live.size) { live[it].localDay },
    kinds = ByteArray(live.size) { live[it].kind.ordinal.toByte() },
    sources = ByteArray(live.size) { live[it].source.ordinal.toByte() },
    flags = IntArray(live.size) { live[it].flags },
    aspectMilli = IntArray(live.size) { 1_333 },
    durationMs = LongArray(live.size),
    place = IntArray(live.size) { live[it].place },
    groupSize = IntArray(live.size) { 1 },
  )
}

/** La visibilita' finta sopra uno di questi insiemi di scatti. */
internal fun visibilityOver(shots: List<TestShot>): FakeVisibility =
  FakeVisibility(snapshot = snapshotOf(shots), present = shots.map { it.id }.toSet())

/** [count] scatti di un giorno, uno ogni [everyMinutes] minuti, con id derivati da ([series], posizione). */
internal fun dayShots(date: LocalDate, count: Int, series: Long, everyMinutes: Long = 20, offsetHours: Int = 0, place: Int = 0, source: MediaSource = MediaSource.CAMERA): List<TestShot> =
  List(count) { k ->
    TestShot(testId(series, k.toLong()), dayInt(date), instantOf(date, LocalTime.of(9, 0).plusMinutes(everyMinutes * k), offsetHours), source = source, place = place)
  }
