package dev.pampa.mycloudgallery.core.memories.pick

import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.LookAlike
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.memories.contract.PixelProbe
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticVectors
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Random
import java.util.UUID

// Le celle e i segnali sintetici delle prove di P7: nessun dato vero, id da un contatore, vettori da concetti seminati.

/** Costruisce celle a mano: un giorno, i minuti dalla mezzanotte, la sorgente, i flag. Ogni cella ha un id nuovo. */
internal class Cells(private val tag: Long = 1) {
  private var seq = 0L
  val day: LocalDate = LocalDate.of(2025, 10, 27)

  fun cell(
    minute: Int,
    on: LocalDate = day,
    source: MediaSource = MediaSource.CAMERA,
    flags: Int = 0,
    kind: MediaKind = MediaKind.PHOTO,
    aspect: Int = 1_333,
    durationMs: Long = 0,
    cutout: Boolean = false,
    thumbHash: ThumbHash? = null,
  ): CandidateCell {
    val id = MediaId(UUID(tag, ++seq).toString())
    val time = on.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + minute * 60_000L
    return CandidateCell(id, time, MemoryDays.dayOf(on), kind, source, flags, durationMs, aspect, thumbHash, cutout)
  }

  /** [n] celle a 10 minuti l'una dall'altra (mai doppioni per la finestra di 3 minuti, al piu' 6 all'ora). */
  fun spaced(n: Int, from: Int = 0, on: LocalDate = day, every: Int = 10): List<CandidateCell> = List(n) { cell(from + it * every, on) }
}

internal fun person(n: Int): PersonId = PersonId(UUID(9L, n.toLong()).toString())

/** Un `ThumbHash` la cui luminanza media e' [level] su 63 (il formato vero ha altro dopo: qui conta solo l'intestazione). */
internal fun thumbWithLuma(level: Int): ThumbHash = ThumbHash.of(byteArrayOf(level.toByte(), 0, 0, 0, 0))

/** Un ingresso casuale della scelta: celle, segnali e specifica. */
internal class RandomPick(val candidates: List<CandidateCell>, val signals: PickSignals, val kind: MemoryKind) {
  override fun toString(): String = "RandomPick(${kind.code}, ${candidates.size})"

  companion object {
    /**
     * Un ingresso a caso dal seme [seed], con la varieta' che la scelta deve reggere: sorgenti e flag misti, giorni di piu' viaggi, scatti
     * ravvicinati (finestra di 3 minuti e tetto per ora), vettori di pochi concetti con rumore (doppioni), persone, "Mai", album, misure
     * dei pixel, firma visiva a tre valori. Meta' delle volte senza vettori (E-MEM16).
     */
    fun of(seed: Long, size: Int = 120): RandomPick {
      val random = Random(seed)
      val cells = Cells(tag = seed)
      val kind = MemoryKind.entries[random.nextInt(MemoryKind.entries.size)]
      val days = 1 + random.nextInt(6)
      val concepts = List(1 + random.nextInt(12)) { SyntheticVectors.concept(seed * 31 + it) }
      val useVectors = random.nextBoolean()
      val people = List(4) { person(it + 1) }
      val list = ArrayList<CandidateCell>()
      val vectors = HashMap<MediaId, FloatArray>()
      val pixels = HashMap<MediaId, PixelProbe>()
      val albums = HashSet<MediaId>()
      val inPhoto = HashMap<MediaId, Set<PersonId>>()
      repeat(size) {
        val roll = random.nextInt(100)
        val source = when {
          roll < 4 -> MediaSource.SCREENSHOT
          roll < 22 -> MediaSource.WHATSAPP
          roll < 25 -> MediaSource.QUICK_SHARE
          else -> MediaSource.CAMERA
        }
        var flags = 0
        if (random.nextInt(100) < 12) flags = flags or CellFlags.FAVORITE
        if (random.nextInt(100) < 10) flags = flags or CellFlags.EDITED
        if (random.nextInt(100) < 3) flags = flags or CellFlags.DOCUMENT
        if (random.nextInt(100) < 4) flags = flags or CellFlags.PANORAMA
        if (random.nextInt(100) < 6) flags = flags or CellFlags.MOTION
        if (random.nextInt(100) < 3) flags = flags or CellFlags.DATE_ESTIMATED
        if (random.nextInt(100) < 1) flags = flags or CellFlags.UNREADABLE
        val kindOf = when (random.nextInt(100)) {
          in 0..9 -> MediaKind.VIDEO
          in 10..11 -> MediaKind.ANIMATED_IMAGE
          else -> MediaKind.PHOTO
        }
        val duration = if (kindOf == MediaKind.VIDEO) 1_000L + random.nextInt(400_000) else 0L
        // Gli scatti si accalcano: blocchi di pochi minuti dentro poche ore.
        val minute = random.nextInt(4) * 180 + random.nextInt(4) * 20 + random.nextInt(4)
        val aspect = intArrayOf(750, 1_000, 1_333, 1_778)[random.nextInt(4)]
        val thumb = if (random.nextInt(4) == 0) thumbWithLuma(random.nextInt(64)) else null
        val cell = cells.cell(
          minute, on = cells.day.plusDays(random.nextInt(days).toLong()), source = source, flags = flags, kind = kindOf, aspect = aspect,
          durationMs = duration, cutout = random.nextInt(100) < 2, thumbHash = thumb,
        )
        list += cell
        if (useVectors && random.nextInt(10) < 9) {
          val concept = concepts[random.nextInt(concepts.size)]
          vectors[cell.id] = SyntheticVectors.withCosine(concept, 0.80 + random.nextDouble() * 0.2, random.nextLong())
        }
        if (random.nextInt(10) < 3) pixels[cell.id] = PixelProbe(random.nextFloat() * 300f, random.nextFloat())
        if (random.nextInt(10) < 2) albums += cell.id
        if (random.nextInt(10) < 3) inPhoto[cell.id] = setOf(people[random.nextInt(people.size)])
      }
      val choices = MemoriesChoices(
        people = buildMap {
          if (random.nextInt(3) == 0) put(people[0], MemoryLessLevel.NEVER)
          if (random.nextInt(3) == 0) put(people[1], MemoryLessLevel.LESS)
        },
        neverDays = if (random.nextInt(4) == 0) setOf(MemoryDays.of(cells.day.plusDays(1), cells.day.plusDays(1))) else emptySet(),
      )
      val lookAlike = when (random.nextInt(3)) {
        0 -> null
        else -> {
          val salt = random.nextInt()
          LookAlike { a, b ->
            when (Math.floorMod((a.value.hashCode() xor b.value.hashCode()) + salt, 5)) {
              0 -> true
              1 -> null
              else -> false
            }
          }
        }
      }
      return RandomPick(list.shuffled(random), PickSignals(vectors, lookAlike, pixels, albums, inPhoto, choices), kind)
    }
  }
}
