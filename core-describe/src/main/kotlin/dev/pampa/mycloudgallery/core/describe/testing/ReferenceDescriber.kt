package dev.pampa.mycloudgallery.core.describe.testing

import dev.pampa.mycloudgallery.core.describe.contract.A11yActivity
import dev.pampa.mycloudgallery.core.describe.contract.DescribeFact
import dev.pampa.mycloudgallery.core.describe.contract.DescribeLimits
import dev.pampa.mycloudgallery.core.describe.contract.DescribePeople
import dev.pampa.mycloudgallery.core.describe.contract.DescribeStrings
import dev.pampa.mycloudgallery.core.describe.contract.DescribeSurface
import dev.pampa.mycloudgallery.core.describe.contract.DescribeSurfaces
import dev.pampa.mycloudgallery.core.describe.contract.DescribeText
import dev.pampa.mycloudgallery.core.describe.contract.MediaDescriber
import dev.pampa.mycloudgallery.core.describe.contract.MediaFacts
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import java.text.Normalizer

/**
 * Il descrittore di riferimento (§5.2): la concatenazione diretta dei fatti, con le regole di D22 scritte qui una per una. P7 confronta con lui
 * `DefaultMediaDescriber` su 10.000 combinazioni di fatti e superfici: stessi testi. L'ordine e' quello di `GridText.describe` di oggi (tipo,
 * data, ora, luogo, durata, preferita, modificata, data stimata, non leggibile, gruppo, presenza), con le persone e il testo letto dopo la
 * durata: "Foto, 12 marzo 2024, 10:15, Oslo, con Anna e Marco, con la scritta «Biglietto del treno…», preferita, solo sul NAS".
 */
class ReferenceDescriber(
  private val places: PlaceNames,
  private val people: DescribePeople,
  private val text: DescribeText,
  private val strings: DescribeStrings,
  private val a11y: A11yActivity,
) : MediaDescriber {

  private class Warm(val names: List<String>, val words: String?)

  private val cache = object : LinkedHashMap<MediaId, Warm>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MediaId, Warm>?): Boolean = size > DescribeLimits.WARM_CACHE
  }

  /** Quante voci ha la cache adesso (per le prove del tetto). */
  val cached: Int get() = synchronized(cache) { cache.size }

  override suspend fun warm(visible: List<MediaId>) {
    if (visible.isEmpty() || !a11y.toolEnabled()) return
    val names = people.names(visible)
    // Una parola in piu' del tetto: se arriva, il testo era piu' lungo e la descrizione lo dice con "…".
    val words = text.firstWords(visible, DescribeLimits.MAX_WORDS + 1)
    synchronized(cache) {
      for (id in visible) cache[id] = Warm(names[id].orEmpty().mapNotNull { cleanName(it) }, words[id]?.let { cleanWords(it) })
    }
  }

  override fun forget() {
    synchronized(cache) { cache.clear() }
  }

  override fun describe(id: MediaId, facts: MediaFacts, surface: DescribeSurface): String {
    val allowed = DescribeSurfaces.facts(surface)
    if (allowed.isEmpty()) return ""
    val parts = ArrayList<String>(10)
    val feminine = facts.kind != MediaKind.VIDEO
    if (DescribeFact.KIND in allowed) parts += kind(facts.kind)
    if (DescribeFact.DATE in allowed) parts += GalleryDates.date(facts.day)
    if (DescribeFact.TIME in allowed) facts.time?.let { parts += GalleryDates.time(facts.day.atTime(it)) }
    if (DescribeFact.PLACE in allowed) facts.placeId?.let { places.labelOfId(it) }?.let { parts += it }
    if (DescribeFact.DURATION in allowed && facts.kind == MediaKind.VIDEO) parts += strings.duration.format(duration(facts.durationMs ?: 0))
    val warm = synchronized(cache) { cache[id] }
    if (DescribeFact.PEOPLE in allowed && warm != null && warm.names.isNotEmpty()) parts += strings.withPeople.format(joinNames(warm.names))
    if (DescribeFact.TEXT in allowed && warm?.words != null) parts += strings.withText.format(warm.words)
    if (DescribeFact.STATE in allowed) {
      if (CellFlags.has(facts.flags, CellFlags.FAVORITE)) parts += if (feminine) strings.favoriteFeminine else strings.favoriteMasculine
      if (CellFlags.has(facts.flags, CellFlags.EDITED)) parts += if (feminine) strings.editedFeminine else strings.editedMasculine
      if (CellFlags.has(facts.flags, CellFlags.DATE_ESTIMATED)) parts += strings.estimated
      if (CellFlags.has(facts.flags, CellFlags.UNREADABLE)) parts += strings.unreadable
      if (facts.groupSize > 1) parts += (if (facts.similarCopies) strings.similarCopies else strings.group).format(GalleryNumbers.format(facts.groupSize))
      when (facts.presence) {
        PresenceBadge.PHONE_ONLY -> parts += strings.phoneOnly
        PresenceBadge.NAS_ONLY -> parts += strings.nasOnly
        PresenceBadge.BOTH -> parts += strings.both
        PresenceBadge.SAFE -> parts += strings.safe
        PresenceBadge.TO_VERIFY -> parts += strings.toVerify
        PresenceBadge.PROBLEM -> parts += strings.backupProblem
        null -> Unit
      }
    }
    return parts.joinToString(", ")
  }

  private fun kind(kind: MediaKind): String = when (kind) {
    MediaKind.PHOTO -> strings.kindPhoto
    MediaKind.VIDEO -> strings.kindVideo
    MediaKind.MOTION_PHOTO -> strings.kindMotion
    MediaKind.RAW_PHOTO -> strings.kindRaw
    MediaKind.ANIMATED_IMAGE -> strings.kindAnimated
  }

  /** "Anna", "Anna e Marco", "Anna, Marco e Luca", "Anna, Marco, Luca e altri 2". */
  private fun joinNames(names: List<String>): String {
    val shown = names.take(DescribeLimits.MAX_NAMES)
    val others = names.size - shown.size
    if (others > 0) return shown.joinToString(", ") + " " + strings.others.format(GalleryNumbers.format(others))
    if (shown.size == 1) return shown.first()
    return shown.dropLast(1).joinToString(", ") + " " + strings.and + " " + shown.last()
  }

  companion object {
    /** "0:42", "12:05", "1:03:20", come `GridText.duration` di oggi. */
    fun duration(durationMs: Long): String {
      val total = (durationMs / 1_000).coerceAtLeast(0)
      val hours = total / 3_600
      val minutes = (total % 3_600) / 60
      val seconds = total % 60
      return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    /** Toglie controlli, direzioni, formati e virgolette basse; spazi uniti; NFC. Null se non resta niente. */
    fun clean(raw: String): String? {
      val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
      val kept = buildString {
        for (ch in normalized) {
          val type = Character.getType(ch)
          val drop = type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() ||
            type == Character.PRIVATE_USE.toInt() || ch == '«' || ch == '»'
          append(if (drop || Character.isWhitespace(ch)) ' ' else ch)
        }
      }
      return kept.split(' ').filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }

    /** Un nome ripulito (al piu' 40 caratteri). */
    fun cleanName(raw: String): String? = clean(raw)?.take(40)

    /** Le prime [DescribeLimits.MAX_WORDS] parole ripulite, con "…" se ce n'erano di piu'. */
    fun cleanWords(raw: String): String? {
      val words = clean(raw)?.split(' ') ?: return null
      val head = words.take(DescribeLimits.MAX_WORDS).joinToString(" ")
      return if (words.size > DescribeLimits.MAX_WORDS) "$head…" else head
    }
  }
}
