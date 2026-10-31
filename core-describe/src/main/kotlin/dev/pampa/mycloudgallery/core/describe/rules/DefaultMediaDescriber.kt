package dev.pampa.mycloudgallery.core.describe.rules

import dev.pampa.mycloudgallery.core.describe.contract.A11yActivity
import dev.pampa.mycloudgallery.core.describe.contract.DescribeFact
import dev.pampa.mycloudgallery.core.describe.contract.DescribeLimits
import dev.pampa.mycloudgallery.core.describe.contract.DescribePeople
import dev.pampa.mycloudgallery.core.describe.contract.DescribeStrings
import dev.pampa.mycloudgallery.core.describe.contract.DescribeSurface
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
import kotlin.coroutines.cancellation.CancellationException

/**
 * Il descrittore vero (D22, §2.6; P7). Una sola regola per tutte le superfici: concatena i fatti nell'ordine di `GridText.describe` di prima
 * (tipo, data, ora, luogo, durata, persone, testo letto, preferita, modificata, data stimata, non leggibile, gruppo, presenza) e dice solo cio'
 * che la superficie ammette (`DescriptionRules.facts`): il widget tipo e data, la notifica niente, dentro l'app e nei Nascosti tutto.
 *
 * - [describe] e' **sincrona**, usa solo dati in memoria e non scrive niente: costa meno di 1 ms anche con le persone e il testo caldi.
 * - **Persone e testo letto** compaiono solo se [warm] li ha caricati, e [warm] non fa niente senza uno strumento di accessibilita' attivo
 *   ([A11yActivity]): senza TalkBack o simili la descrizione e' fatta dei soli fatti e non costa niente (E-AC1). Le porte danno gia' solo le
 *   persone con un nome, non nascoste, non dimenticate (Persone attiva qui) e il testo se la lettura e' attiva e non esclusa qui (E-AC2,
 *   E-AC3): il descrittore non puo' aggiungere nulla di suo, e ripulisce quello che arriva (nomi a 40 caratteri, 3 nomi poi "e altri N",
 *   testo a 12 parole, niente controlli ne' direzioni).
 * - **La cache** di [warm] e' una LRU di [DescribeLimits.WARM_CACHE] voci, solo in memoria: [forget] la vuota (blocco dell'app, secondo
 *   piano: lo chiama :app, P13). Se lo strumento si spegne, `describe` smette di usarla. Un errore delle porte = niente nomi e niente testo.
 * - Niente log, niente orologio, nessun id nel testo.
 */
class DefaultMediaDescriber(
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
    val ids = visible.distinct()
    val names = try {
      people.names(ids)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      emptyMap()
    }
    // Una parola in piu' del tetto: se arriva, il testo era piu' lungo e la descrizione lo dice con "…".
    val words = try {
      text.firstWords(ids, DescribeLimits.MAX_WORDS + 1)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      emptyMap()
    }
    synchronized(cache) {
      for (id in ids) {
        val cleanNames = names[id].orEmpty().asSequence().mapNotNull { DescriptionRules.cleanName(it) }.take(DescriptionRules.MAX_NAMES_KEPT).toList()
        val cleanWords = words[id]?.let { DescriptionRules.cleanText(it, DescribeLimits.MAX_WORDS) }
        // Un media senza niente da dire non occupa posto (e un nome tolto da Persone dopo l'ultimo warm non resta in cache).
        if (cleanNames.isEmpty() && cleanWords == null) cache.remove(id) else cache[id] = Warm(cleanNames, cleanWords)
      }
    }
  }

  override fun forget() {
    synchronized(cache) { cache.clear() }
  }

  override fun describe(id: MediaId, facts: MediaFacts, surface: DescribeSurface): String {
    val allowed = DescriptionRules.facts(surface)
    if (allowed.isEmpty()) return ""
    val out = StringBuilder(96)
    fun part(value: String) {
      if (out.isNotEmpty()) out.append(", ")
      out.append(value)
    }
    val video = facts.kind == MediaKind.VIDEO
    if (DescribeFact.KIND in allowed) part(kind(facts.kind))
    if (DescribeFact.DATE in allowed) part(GalleryDates.date(facts.day))
    if (DescribeFact.TIME in allowed) facts.time?.let { part(GalleryDates.time(facts.day.atTime(it))) }
    if (DescribeFact.PLACE in allowed) facts.placeId?.let { places.labelOfId(it) }?.let { part(it) }
    if (DescribeFact.DURATION in allowed && video) part(strings.duration.format(duration(facts.durationMs ?: 0)))
    if (DescriptionRules.allowsPersonalFacts(surface) && a11y.toolEnabled()) {
      val warm = synchronized(cache) { cache[id] }
      if (warm != null) {
        if (DescribeFact.PEOPLE in allowed && warm.names.isNotEmpty()) part(strings.withPeople.format(joinNames(warm.names)))
        if (DescribeFact.TEXT in allowed && warm.words != null) part(strings.withText.format(warm.words))
      }
    }
    if (DescribeFact.STATE in allowed) {
      if (CellFlags.has(facts.flags, CellFlags.FAVORITE)) part(if (video) strings.favoriteMasculine else strings.favoriteFeminine)
      if (CellFlags.has(facts.flags, CellFlags.EDITED)) part(if (video) strings.editedMasculine else strings.editedFeminine)
      if (CellFlags.has(facts.flags, CellFlags.DATE_ESTIMATED)) part(strings.estimated)
      if (CellFlags.has(facts.flags, CellFlags.UNREADABLE)) part(strings.unreadable)
      // Una pila di copie simili (D36) si dice "N copie simili", una raffica "N scatti": il bit distingue le due pile.
      if (facts.groupSize > 1) part((if (facts.similarCopies) strings.similarCopies else strings.group).format(GalleryNumbers.format(facts.groupSize)))
      when (facts.presence) {
        PresenceBadge.PHONE_ONLY -> part(strings.phoneOnly)
        PresenceBadge.NAS_ONLY -> part(strings.nasOnly)
        PresenceBadge.BOTH -> part(strings.both)
        PresenceBadge.SAFE -> part(strings.safe)
        PresenceBadge.TO_VERIFY -> part(strings.toVerify)
        // Fase 5 (D15): un problema del backup chiede l'utente e vince su "al sicuro"; il motivo preciso sta nel viewer e nella pagina Backup.
        PresenceBadge.PROBLEM -> part(strings.backupProblem)
        null -> Unit
      }
    }
    return out.toString()
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
    /** "0:42", "12:05", "1:03:20": le cifre come le scrive Foto, senza `String.format` (si chiama per ogni video in vista). */
    fun duration(durationMs: Long): String {
      val total = (durationMs / 1_000).coerceAtLeast(0)
      val hours = total / 3_600
      val minutes = (total % 3_600) / 60
      val seconds = total % 60
      val builder = StringBuilder(8)
      if (hours > 0) {
        builder.append(hours).append(':')
        if (minutes < 10) builder.append('0')
      }
      builder.append(minutes).append(':')
      if (seconds < 10) builder.append('0')
      return builder.append(seconds).toString()
    }
  }
}
