package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.PeopleClass
import dev.pampa.mycloudgallery.core.albums.contract.SmartMediaType
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate

// LA CLASSIFICAZIONE DEGLI ALBUM SMART (docs/fase7-progetto.md §2.2 "Classificazione", D14, E-AL1-E-AL17). **Pura**: regole, candidati e risultati
// della ricerca danno un verdetto per ogni candidato; niente database, niente rete, niente orologio.
//
// **Il verdetto di un candidato dipende solo dai candidati dei tre giorni locali attorno al suo** (il giorno prima, il suo, il giorno dopo): la
// regola B guarda i sicuri entro 6 ore (al piu' fino al giorno accanto) e S5 i dispositivi dei sicuri di quei tre giorni. E' la proprieta' che
// rende il ricalcolo incrementale uguale a quello da zero (D16): un cambiamento nei giorni T ricalcola T +- 1 e nient'altro.
//
// Le regole, in breve (la tabella intera e' nel §2.2):
//  - A  sicuro  data nel periodo e GPS entro il raggio (o nella regione o nel paese), di un media mio; senza luogo e senza descrizione la sola
//               data (il luogo "non richiesto" e' sempre soddisfatto);
//  - B  sicuro  senza GPS, stesso dispositivo, fra due sicuri A dello stesso dispositivo a meno di 6 ore da ciascuno;
//  - C  sicuro  album senza luogo: nel periodo (se c'e') e descrizione "sicura" per il motore di ricerca;
//  - S1-S5      suggeriti (vedi [SmartReason]); uno screenshot e' sempre e solo S2, un familiare mai sicuro (E-AL17).
//  - persone    (Fase 8, D27) la regola delle persone si **combina in "e"** con le altre: un media che non e' di nessuna persona della regola e' fuori; con una persona
//               solo "forse" un sicuro diventa suggerito (S6); un suggerito resta com'e'. Il verdetto di un candidato dipende ancora solo dai tre giorni attorno al suo
//               (la classe di una persona e' del media stesso): l'incrementale resta uguale alla valutazione intera.
// **Fuori**: nascosti (non arrivano nemmeno qui), esclusi (E-AL5), sorgente o tipo non ammessi, fuori periodo, GPS che contraddice il luogo.

/** Cio' che la ricerca per contenuto dice dell'album: i media "sicuri" e "meno probabili" per la descrizione e le foto d'esempio (C e S4). */
class ContentMatches(val sure: Set<MediaId>, val likely: Set<MediaId>, val used: Boolean) {
  override fun toString(): String = "ContentMatches(used=$used, ${sure.size} sicuri, ${likely.size} meno probabili)"

  companion object {
    /** Il motore non c'e' (o non e' pronto): le regole C e S4 tacciono (D15). */
    val NONE = ContentMatches(emptySet(), emptySet(), used = false)
  }
}

internal class SmartClassifier(
  private val rules: SmartRules,
  private val place: PlaceMatcher?,
  private val devices: DeviceNormalizer,
  private val content: ContentMatches,
  private val excluded: Set<MediaId>,
  private val owned: Set<MediaId>,
  /** Fase 8: la classe di ogni candidato per la regola delle persone ([PeopleMatches.NONE] senza la regola, o dove i volti non ci sono: allora il motore non valuta). */
  private val people: PeopleMatches = PeopleMatches.NONE,
) {
  /** La descrizione o le foto d'esempio ci sono (anche se il motore non c'e'). */
  val contentRequested: Boolean = !rules.text.isNullOrBlank() || rules.examples.isNotEmpty()

  /** La regola delle persone c'e' (Fase 8). */
  val peopleRequested: Boolean = rules.people != null

  /** Un album senza periodo, luogo, descrizione ne' persone non ha regole: e' vuoto (E-AL9). */
  val hasConstraints: Boolean = rules.period != null || place != null || contentRequested || peopleRequested

  private val contentActive: Boolean = contentRequested && content.used

  private fun inPeriod(c: SmartCandidate): Boolean = rules.period?.contains(c.c.day) ?: true

  private fun typeOk(c: SmartCandidate): Boolean =
    if (c.c.kind.isVideo) SmartMediaType.VIDEO in rules.types else SmartMediaType.PHOTO in rules.types

  /** Il candidato puo' contare (anche come punto fermo di B): sorgente e tipo ammessi, non escluso. */
  private fun usable(c: SmartCandidate): Boolean = c.media !in excluded && c.c.source in rules.sources && typeOk(c)

  /** Un sicuro A con luogo: il punto fermo della regola B. Mai uno screenshot, mai un familiare. */
  private fun isAnchor(c: SmartCandidate): Boolean {
    val matcher = place ?: return false
    if (!usable(c) || !c.mine || c.c.screen || c.c.gps == null || !inPeriod(c)) return false
    return matcher.fit(c.c) == PlaceFit.IN
  }

  /** I punti fermi dei tre giorni: per dispositivo gli istanti in ordine, e l'insieme dei dispositivi. */
  private class Anchors(val byDevice: Map<String, LongArray>) {
    val devices: Set<String> get() = byDevice.keys

    /**
     * B: **due** punti fermi (distinti), uno non dopo [t] e uno non prima, entrambi a meno di [window] da [t]. Un punto fermo solo, anche se
     * scattato nello stesso istante, non e' "fra due sicuri".
     */
    fun between(device: String, t: Long, window: Long): Boolean {
      val times = byDevice[device] ?: return false
      var lo = 0
      var hi = times.size
      while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (times[mid] < t) lo = mid + 1 else hi = mid
      }
      fun ok(before: Long, after: Long): Boolean = t - before < window && after - t < window
      return if (lo < times.size && times[lo] == t) {
        // Un punto fermo esattamente a [t]: l'altro lato e' il precedente o il successivo.
        (lo > 0 && ok(times[lo - 1], t)) || (lo + 1 < times.size && ok(t, times[lo + 1]))
      } else {
        lo > 0 && lo < times.size && ok(times[lo - 1], times[lo])
      }
    }
  }

  private fun anchorsOf(window: List<SmartCandidate>): Anchors {
    val grouped = HashMap<String, MutableList<Long>>()
    for (c in window) {
      if (!isAnchor(c)) continue
      val device = devices.of(c.c.originDevice) ?: continue
      grouped.getOrPut(device) { ArrayList() } += c.c.takenAtUtcMs
    }
    return Anchors(grouped.mapValues { (_, times) -> times.sorted().toLongArray() })
  }

  /**
   * I verdetti dei candidati del giorno [core] ([prev] e [next] sono il giorno prima e quello dopo, per il contesto). Un candidato fuori non ha
   * un verdetto. Nessuno dei tre elenchi deve contenere un nascosto: li toglie chi li carica (D22).
   */
  fun classifyDay(prev: List<SmartCandidate>, core: List<SmartCandidate>, next: List<SmartCandidate>): Map<MediaId, SmartVerdict> {
    if (!hasConstraints) return emptyMap()
    val out = HashMap<MediaId, SmartVerdict>()
    val live = core.filter { usable(it) && it.media !in owned }
    if (live.isEmpty()) return out
    val anchors = if (place != null) anchorsOf(prev + core + next) else EMPTY_ANCHORS
    for (c in live) verdict(c, anchors)?.let { out[c.media] = it }
    return out
  }

  private fun verdict(c: SmartCandidate, anchors: Anchors): SmartVerdict? {
    val base = baseVerdict(c, anchors)
    if (!peopleRequested || base == null) return base
    // Fase 8: "e" con le persone. Un media senza nessuna persona della regola e' fuori; con una persona solo "forse" un sicuro diventa suggerito.
    return when (people.classOf(c.media)) {
      null -> null
      PeopleClass.SURE -> base
      PeopleClass.LIKELY -> if (base is SmartVerdict.Sure) SmartVerdict.Suggested(SmartReason.S6) else base
    }
  }

  private fun baseVerdict(c: SmartCandidate, anchors: Anchors): SmartVerdict? {
    if (!inPeriod(c)) return null
    val matcher = place
    val fit = if (matcher != null && c.c.gps != null) matcher.fit(c.c) else null
    val base = if (matcher != null) placeVerdict(c, fit, anchors) else noPlaceVerdict(c)
    if (base != null) return base
    // Un luogo il cui GPS contraddice quello della regola non rientra dalla descrizione (S4 vale per chi il luogo non lo contraddice).
    if (contentActive && fit != PlaceFit.FAR && (c.media in content.sure || c.media in content.likely)) return SmartVerdict.Suggested(SmartReason.S4)
    return null
  }

  private fun placeVerdict(c: SmartCandidate, fit: PlaceFit?, anchors: Anchors): SmartVerdict? {
    val screen = c.c.screen
    if (fit != null) {
      return when (fit) {
        PlaceFit.FAR -> null
        PlaceFit.NEAR -> SmartVerdict.Suggested(if (screen) SmartReason.S2 else SmartReason.S3)
        PlaceFit.IN -> when {
          screen -> SmartVerdict.Suggested(SmartReason.S2)
          c.mine -> SmartVerdict.Sure(SureRule.A)
          else -> SmartVerdict.Suggested(SmartReason.S1)
        }
      }
    }
    // Senza GPS.
    val device = devices.of(c.c.originDevice)
    if (!screen && c.mine && device != null && anchors.between(device, c.c.takenAtUtcMs, SmartTuning.RULE_B_WINDOW_MS)) return SmartVerdict.Sure(SureRule.B)
    // Senza un periodo non c'e' modo di legare a questo luogo una foto senza GPS (S1, S2 e S5 sono "nel periodo").
    if (rules.period == null) return null
    return when {
      screen -> SmartVerdict.Suggested(SmartReason.S2)
      c.mine && device != null && device in anchors.devices -> SmartVerdict.Suggested(SmartReason.S5)
      else -> SmartVerdict.Suggested(SmartReason.S1)
    }
  }

  private fun noPlaceVerdict(c: SmartCandidate): SmartVerdict? {
    val screen = c.c.screen
    if (!contentRequested) {
      // Solo il periodo: la regola A senza luogo (sempre soddisfatto); un familiare mai sicuro.
      return when {
        screen -> SmartVerdict.Suggested(SmartReason.S2)
        c.mine -> SmartVerdict.Sure(SureRule.A)
        else -> SmartVerdict.Suggested(SmartReason.S1)
      }
    }
    // C e S4 vivono della ricerca: senza il motore l'album resta com'e' (D15, E-AL9).
    if (!content.used) return null
    val sure = c.media in content.sure
    return when {
      sure && screen -> SmartVerdict.Suggested(SmartReason.S2)
      sure && c.mine -> SmartVerdict.Sure(SureRule.C)
      sure -> SmartVerdict.Suggested(SmartReason.S1)
      c.media in content.likely -> SmartVerdict.Suggested(SmartReason.S4)
      else -> null
    }
  }

  private companion object {
    val EMPTY_ANCHORS = Anchors(emptyMap())
  }
}

/** I candidati raggruppati per giorno locale, ognuno in ordine (istante, id): il materiale della classificazione giorno per giorno. */
internal class DayIndex(candidates: Collection<SmartCandidate>) {
  private val byDay: Map<LocalDate, List<SmartCandidate>> =
    candidates.groupBy { it.c.day }.mapValues { (_, list) -> list.sortedWith(compareBy({ it.c.takenAtUtcMs }, { it.media.value })) }

  val days: List<LocalDate> = byDay.keys.sorted()

  val size: Int = candidates.size

  fun group(day: LocalDate): List<SmartCandidate> = byDay[day].orEmpty()

  /** Tutti i candidati, nell'ordine dei giorni. */
  fun all(): List<SmartCandidate> = days.flatMap { group(it) }
}

/** Classifica [days] (o tutti i giorni dell'indice se null): il verdetto di ogni candidato che ne ha uno. */
internal fun SmartClassifier.classify(index: DayIndex, days: Collection<LocalDate>? = null): Map<MediaId, SmartVerdict> {
  val out = HashMap<MediaId, SmartVerdict>()
  for (day in days ?: index.days) {
    val core = index.group(day)
    if (core.isEmpty()) continue
    out += classifyDay(index.group(day.minusDays(1)), core, index.group(day.plusDays(1)))
  }
  return out
}
