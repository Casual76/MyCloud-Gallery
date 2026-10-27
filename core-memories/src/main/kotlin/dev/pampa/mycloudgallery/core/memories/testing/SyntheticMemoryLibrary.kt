package dev.pampa.mycloudgallery.core.memories.testing

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.ThumbHash
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

// La Libreria sintetica dei ricordi (Fase 9, P0, §5.1, §5.2): pochi scatti messi a mano su giorni scelti (anniversari, 29 febbraio, giorni di
// soli screenshot, viaggi, raffiche), con una visibilita' finta fedele (i nascosti escono dall'istantanea e da `VisibleMedia`). Per le suite di
// contratto di P0 e per P6, P7, P9, P10, P11. In src/main come gli altri fake: mai nella release (tools/check.ps1). Nessun dato vero: id casuali
// deterministici dal seme, luoghi finti (id GeoNames inventati), nessuna coordinata.

/** Uno scatto della Libreria sintetica. L'ora e' locale e qui coincide con UTC (i fusi sono di E-MEM4, provati a parte). */
data class SyntheticShot(
  val id: MediaId,
  val day: LocalDate,
  val time: LocalTime,
  val source: MediaSource,
  val kind: MediaKind,
  val flags: Int,
  val durationMs: Long,
  val place: Int,
  val aspectMilli: Int,
  val thumbHash: ThumbHash?,
) {
  val sortTime: Long get() = day.atTime(time).toInstant(ZoneOffset.UTC).toEpochMilli()

  override fun toString(): String = "SyntheticShot(${kind.code})"
}

class SyntheticMemoryLibrary(seed: Long = 7) {
  private val random = java.util.Random(seed)
  private val shots = ArrayList<SyntheticShot>()
  private val removed = HashSet<MediaId>()
  private var seq = 1L
  private var batching = 0

  /** La visibilita' finta: i nascosti stanno qui e fuori dall'istantanea. */
  val visibility: FakeVisibility = FakeVisibility()

  init {
    // Una Libreria vuota ha un'istantanea vuota (pronta), non nessuna istantanea.
    publish()
  }

  val all: List<SyntheticShot> get() = shots

  fun add(
    day: LocalDate,
    time: LocalTime = LocalTime.NOON,
    source: MediaSource = MediaSource.CAMERA,
    kind: MediaKind = MediaKind.PHOTO,
    flags: Int = 0,
    durationMs: Long = 0,
    place: Int = 0,
    aspectMilli: Int = 1_333,
    thumbHash: ThumbHash? = null,
  ): MediaId {
    val id = MediaId(UUID(random.nextLong(), random.nextLong()).toString())
    shots += SyntheticShot(id, day, time, source, kind, flags, durationMs, place, aspectMilli, thumbHash)
    visibility.present += id
    publish()
    return id
  }

  /** [count] foto del giorno [day], una ogni [everyMinutes] minuti dalle [start]. */
  fun photos(
    day: LocalDate,
    count: Int,
    start: LocalTime = LocalTime.of(9, 0),
    everyMinutes: Long = 20,
    source: MediaSource = MediaSource.CAMERA,
    flags: Int = 0,
    place: Int = 0,
  ): List<MediaId> = batch { List(count) { add(day, start.plusMinutes(everyMinutes * it), source = source, flags = flags, place = place) } }

  /** Molte aggiunte, una sola istantanea nuova alla fine. */
  fun <T> batch(block: () -> T): T {
    batching++
    try {
      return block()
    } finally {
      batching--
      publish()
    }
  }

  /** Nasconde [id]: esce dall'istantanea e `VisibleMedia` non lo da' piu'. */
  fun hide(id: MediaId) {
    visibility.hidden += id
    publish()
  }

  /** Rimette visibile [id]. */
  fun unhide(id: MediaId) {
    visibility.hidden -= id
    publish()
  }

  /** Elimina [id] (E-MEM6): non c'e' piu' ne' nel catalogo ne' nell'istantanea. */
  fun delete(id: MediaId) {
    removed += id
    visibility.present -= id
    publish()
  }

  fun shot(id: MediaId): SyntheticShot = shots.first { it.id == id }

  fun snapshot(): TimelineSnapshot = visibility.snapshot.value ?: build()

  private fun publish() {
    if (batching == 0) visibility.snapshot.value = build()
  }

  private fun build(): InMemoryTimelineSnapshot {
    val live = shots.filter { it.id !in removed && it.id !in visibility.hidden }
      .sortedWith(compareByDescending<SyntheticShot> { it.sortTime }.thenByDescending { it.id.value })
    val uuids = live.map { UUID.fromString(it.id.value) }
    val thumbs = live.map { it.thumbHash }
    return InMemoryTimelineSnapshot(
      seq = seq++,
      idMsb = LongArray(live.size) { uuids[it].mostSignificantBits },
      idLsb = LongArray(live.size) { uuids[it].leastSignificantBits },
      sortTime = LongArray(live.size) { live[it].sortTime },
      localDay = IntArray(live.size) { live[it].day.let { d -> d.year * 10_000 + d.monthValue * 100 + d.dayOfMonth } },
      kinds = ByteArray(live.size) { live[it].kind.ordinal.toByte() },
      sources = ByteArray(live.size) { live[it].source.ordinal.toByte() },
      flags = IntArray(live.size) { live[it].flags },
      aspectMilli = IntArray(live.size) { live[it].aspectMilli },
      durationMs = LongArray(live.size) { live[it].durationMs },
      place = IntArray(live.size) { live[it].place },
      groupSize = IntArray(live.size) { 1 },
      thumbHashOf = { thumbs[it] },
    )
  }
}
