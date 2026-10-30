package dev.pampa.mycloudgallery.core.memories.shelf

import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryOrigin
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.PersonWindow
import dev.pampa.mycloudgallery.core.memories.contract.ShelfFile
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

// Gli attrezzi delle prove dello scaffale: la morte del processo a meta' scrittura e uno scaffale ricco di ogni tipo di ricordo.

/** Il punto in cui muore il processo durante un salvataggio. */
internal enum class DeathPoint {
  /** Il temporaneo e' scritto a meta': il file vero non e' stato toccato. */
  PARTIAL_TEMP,

  /** Il temporaneo e' intero ma la rinomina non e' avvenuta. */
  FULL_TEMP,

  /** La rinomina e' avvenuta: il file nuovo c'e', ma il chiamante non lo sa. */
  AFTER_RENAME,
}

class CrashException : RuntimeException("morte simulata del processo")

/** Il disco vero con la morte del processo al comando: lascia i resti che lascerebbe un processo ucciso nel punto scelto. */
internal class FaultyIo(private val delegate: ShelfIo) : ShelfIo {
  var dieAt: DeathPoint? = null
  var failReads: Boolean = false

  /** Quante scritture sono state chieste (anche quella che muore). */
  var writes: Int = 0
    private set

  /** Se > 0: la scrittura numero [dieOnWrite] (contando da 1) muore nel punto [dieOnWritePoint]. */
  var dieOnWrite: Int = 0
  var dieOnWritePoint: DeathPoint = DeathPoint.PARTIAL_TEMP

  /** Dopo ogni scrittura riuscita: cosa c'e' sul file (per chi vuole sapere lo scaffale dopo ogni salvataggio). */
  var onWritten: (File) -> Unit = {}

  override fun read(file: File): ByteArray? {
    if (failReads) throw IOException("lettura non riuscita")
    return delegate.read(file)
  }

  override fun write(file: File, bytes: ByteArray) {
    writes++
    if (writes == dieOnWrite) dieAt = dieOnWritePoint
    val point = dieAt ?: return delegate.write(file, bytes).also { onWritten(file) }
    dieAt = null
    val temporary = File(file.absoluteFile.parentFile, file.name + ".tmp")
    temporary.parentFile.mkdirs()
    when (point) {
      DeathPoint.PARTIAL_TEMP -> temporary.writeBytes(bytes.copyOf(bytes.size / 2))
      DeathPoint.FULL_TEMP -> temporary.writeBytes(bytes)
      DeathPoint.AFTER_RENAME -> delegate.write(file, bytes)
    }
    throw CrashException()
  }

  override fun delete(file: File): Boolean = delegate.delete(file)
}

/** Uno scaffale con ogni tipo di chiave, l'album, le etichette, i luoghi e le persone: tutto cio' che il formato deve portare. */
internal fun richShelf(seed: Int = 0, lastRunDay: Int = 20261027): ShelfFile {
  fun media(n: Int, k: Int) = MediaId(UUID(0x1000L + seed, n * 1000L + k).toString())
  fun memory(n: Int, key: MemoryKey, members: Int, title: MemoryTitle, origin: MemoryOrigin? = null): Memory {
    val ids = List(members) { media(n, it) }
    return Memory(MemoryIds.of(key), key, title, ids, ids[members / 2], publishedDay = 20261027 - n, origin = origin)
  }
  val day = LocalDate.of(2024, 10, 27)
  val person = PersonId.fromBytes(ByteArray(16) { (it + 1 + seed).toByte() })
  val trip = MemoryKey.Trip(DayRange(LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 6)))
  val list = listOf(
    memory(1, MemoryKey.OnThisDay(day.plusDays(seed.toLong())), 8, MemoryTitle(2, DayRange.of(day), listOf(3_100_000, 3_100_001, 3_100_002), null, null, null)),
    memory(2, trip, 12, MemoryTitle(null, trip.days, listOf(3_200_000), "Viaggio sintetico, maggio 2024", null, null), MemoryOrigin.TripAlbum(AlbumId.random())),
    memory(3, MemoryKey.Event(day, 41), 10, MemoryTitle(2, DayRange.of(day), emptyList(), "Evento sintetico", null, null)),
    memory(4, MemoryKey.Person(person, PersonWindow.Year(2024)), 15, MemoryTitle(null, DayRange(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)), emptyList(), null, person, null)),
    memory(5, MemoryKey.Person(person, PersonWindow.Recent), 15, MemoryTitle(null, DayRange(day, day.plusDays(100)), emptyList(), null, person, null)),
    memory(6, MemoryKey.Month(YearMonth.of(2026, 9)), 40, MemoryTitle(null, DayRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), listOf(3_000_001), null, null, YearMonth.of(2026, 9))),
  )
  return ShelfFile(
    list,
    lastPublished = mapOf(MemoryKind.TRIP to 20261025, MemoryKind.PERSON to 20261020, MemoryKind.MONTH to 20261001),
    lastRediscovery = 20261001,
    lastRunDay = lastRunDay,
  )
}
