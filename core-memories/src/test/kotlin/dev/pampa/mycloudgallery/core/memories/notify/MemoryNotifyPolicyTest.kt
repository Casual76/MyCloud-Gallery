package dev.pampa.mycloudgallery.core.memories.notify

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryNotifyDecisions
import dev.pampa.mycloudgallery.core.memories.contract.NotifyContent
import dev.pampa.mycloudgallery.core.memories.contract.NotifyFrequency
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedger
import dev.pampa.mycloudgallery.core.memories.contract.NotifyMark
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPolicyContract
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.NotifyRules
import dev.pampa.mycloudgallery.core.memories.contract.NotifyTexts
import dev.pampa.mycloudgallery.core.memories.contract.SlotDecision
import dev.pampa.mycloudgallery.core.memories.notify.NotifyFixtures.candidate
import dev.pampa.mycloudgallery.core.memories.notify.NotifyFixtures.dayInt
import dev.pampa.mycloudgallery.core.memories.notify.NotifyFixtures.ledgerOf
import dev.pampa.mycloudgallery.core.memories.testing.ReferenceNotifyPolicy
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Random
import org.junit.Test

/** La suite di contratto di P0 sulle decisioni vere (D15, D16, D20, E-NT3-E-NT6, E-NT13; Q4, Q5 (a)). */
class MemoryNotifyPolicyContractTest : NotifyPolicyContract() {
  override fun policy(): MemoryNotifyDecisions = MemoryNotifyPolicy()
}

/**
 * Il calendario, le frequenze di Q4 e il contenuto delle notifiche (docs/fase9-progetto.md §2.6, D15, D16, D20; E-NT3, E-NT4, E-NT12, E-NT13;
 * risposte Q4, Q5 (a), Q9): finestra oraria, salto oltre H+3 h, fusi e ora legale, 2-3 a settimana, nessun testo del ricordo nella modalita'
 * generica, nessun nome in nessuna modalita'. Date relative a un "oggi" fisso, nessun dato personale.
 */
class MemoryNotifyPolicyTest {

  private val policy = MemoryNotifyPolicy()
  private val today = LocalDate.of(2026, 11, 3)

  // --- Il calendario (D20, E-NT3) -----------------------------------------------------------------------------------------------------

  /** La finestra: da H a H+2 h, e in ritardo fino a H+3 h; oltre quel giorno si salta; la soglia "in ritardo" e' H+1 h. */
  @Test
  fun lafinestraEIlSaltoOltreHPiuTreOre() {
    val prefs = NotifyPrefs()
    val h = today.atTime(19, 0)
    assertThat(policy.slot(h, prefs)).isEqualTo(SlotDecision.Deliver(today, late = false))
    assertThat(policy.slot(h.plusMinutes(60), prefs)).isEqualTo(SlotDecision.Deliver(today, late = false))
    assertThat(policy.slot(h.plusMinutes(61), prefs)).isEqualTo(SlotDecision.Deliver(today, late = true))
    assertThat(policy.slot(h.plusMinutes(120), prefs)).isEqualTo(SlotDecision.Deliver(today, late = true))
    assertThat(policy.slot(h.plusMinutes(180), prefs)).isEqualTo(SlotDecision.Deliver(today, late = true))
    assertThat(policy.slot(h.plusMinutes(181), prefs)).isEqualTo(SlotDecision.Wait(today.plusDays(1).atTime(19, 0), skipped = true))
    assertThat(policy.slot(today.atTime(23, 59), prefs)).isEqualTo(SlotDecision.Wait(today.plusDays(1).atTime(19, 0), skipped = true))
  }

  /** Qualche minuto prima dell'orario non e' un giorno saltato: si aspetta l'orario (un ritardo arrotondato, un orologio rimesso a posto). */
  @Test
  fun unaCorsaInAnticipoNonEUnGiornoSaltato() {
    val prefs = NotifyPrefs()
    assertThat(policy.slot(today.atTime(18, 56), prefs)).isEqualTo(SlotDecision.Wait(today.atTime(19, 0), skipped = false))
    assertThat(policy.slot(today.atTime(18, 59, 59), prefs)).isEqualTo(SlotDecision.Wait(today.atTime(19, 0), skipped = false))
    // Con piu' di cinque minuti d'anticipo e' un orario passato da molto: si salta.
    assertThat(policy.slot(today.atTime(18, 40), prefs)).isEqualTo(SlotDecision.Wait(today.atTime(19, 0), skipped = true))
    // Con le notifiche spente si aspetta e basta.
    assertThat(policy.slot(today.atTime(19, 5), prefs.copy(enabled = false))).isEqualTo(SlotDecision.Wait(today.plusDays(1).atTime(19, 0), skipped = false))
  }

  /** Per ogni ora a passi di 15 minuti e ogni momento a caso: il prossimo orario e' il primo H dopo adesso, mai oltre 24 ore, sempre a passi di 15. */
  @Test
  fun ilProssimoOrarioPerOgniOraScelta() {
    val random = Random(21)
    for (quarter in 0 until 96) {
      val hour = LocalTime.of(quarter / 4, quarter % 4 * 15)
      val prefs = NotifyPrefs(hour = hour)
      repeat(40) {
        val now = LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(random.nextInt(365 * 24 * 60).toLong()).plusSeconds(random.nextInt(60).toLong())
        val next = policy.nextSlot(now, prefs)!!
        assertThat(next.toLocalTime()).isEqualTo(hour)
        assertThat(next.isAfter(now)).isTrue()
        assertThat(Duration.between(now, next)).isAtMost(Duration.ofHours(24))
        // E' il primo: un giorno prima, quell'orario non e' piu' dopo adesso.
        assertThat(next.minusDays(1).isAfter(now)).isFalse()
      }
    }
  }

  // --- Fusi orari e ora legale (E-NT4) ----------------------------------------------------------------------------------------------

  private val rome = ZoneId.of("Europe/Rome")

  private fun delay(now: LocalDateTime, prefs: NotifyPrefs, zone: ZoneId = rome): Duration =
    MemoryNotifyPolicy.delayUntil(now, policy.nextSlot(now, prefs)!!, zone)

  /** L'ora scelta resta l'ora locale: il giorno dell'ora legale che salta un'ora il ritardo e' di 21h55m, quello d'autunno di 24h55m (non 24h). */
  @Test
  fun ilRitardoSegueLOraLegale() {
    val prefs = NotifyPrefs()
    assertThat(delay(LocalDateTime.of(2026, 3, 27, 19, 5), prefs)).isEqualTo(Duration.ofHours(24).minusMinutes(5))
    // 28 marzo 19:05 CET -> 29 marzo 19:00 CEST: la notte del 29 salta un'ora.
    assertThat(delay(LocalDateTime.of(2026, 3, 28, 19, 5), prefs)).isEqualTo(Duration.ofHours(23).minusMinutes(5))
    // 24 ottobre 19:05 CEST -> 25 ottobre 19:00 CET: la notte del 25 dura un'ora in piu'.
    assertThat(delay(LocalDateTime.of(2026, 10, 24, 19, 5), prefs)).isEqualTo(Duration.ofHours(25).minusMinutes(5))
    // Fuori da Roma, nessun cambio: sempre 24 ore.
    assertThat(delay(LocalDateTime.of(2026, 3, 28, 19, 5), prefs, ZoneId.of("Asia/Tokyo"))).isEqualTo(Duration.ofHours(24).minusMinutes(5))
  }

  /** Un orario che non esiste (la lacuna di primavera: le 02:30 del 29 marzo) vale l'ora dopo; uno doppio (autunno) vale la prima volta. */
  @Test
  fun lacunaEOraDoppiaDellOraLegale() {
    val prefs = NotifyPrefs(hour = LocalTime.of(2, 30))
    // 28 marzo 20:00 CET -> le 02:30 del 29 non esistono -> 03:30 CEST (01:30 UTC): 6h30m.
    assertThat(delay(LocalDateTime.of(2026, 3, 28, 20, 0), prefs)).isEqualTo(Duration.ofHours(6).plusMinutes(30))
    // 24 ottobre 20:00 CEST -> prima delle due 02:30 del 25 (CEST, 00:30 UTC): 6h30m.
    assertThat(delay(LocalDateTime.of(2026, 10, 24, 20, 0), prefs)).isEqualTo(Duration.ofHours(6).plusMinutes(30))
    // Alle 04:00 la differenza fra ora locale e ora vera si vede: una notte corta (7 ore invece di 8) e una lunga (9 invece di 8).
    val four = NotifyPrefs(hour = LocalTime.of(4, 0))
    assertThat(delay(LocalDateTime.of(2026, 3, 28, 20, 0), four)).isEqualTo(Duration.ofHours(7))
    assertThat(delay(LocalDateTime.of(2026, 10, 24, 20, 0), four)).isEqualTo(Duration.ofHours(9))
    // Il lavoro che parte alle 03:30 CEST (oltre la lacuna) e' in ritardo di un'ora: si consegna lo stesso (la finestra e' in ora locale).
    assertThat(policy.slot(LocalDateTime.of(2026, 3, 29, 3, 30), prefs)).isEqualTo(SlotDecision.Deliver(LocalDate.of(2026, 3, 29), late = false))
  }

  /** Cambio di fuso (E-NT4): l'orario e' sempre quello locale; con il telefono sei ore indietro il lavoro si rifa' alla corsa dopo. */
  @Test
  fun alCambioDiFusoLOraRestaLocale() {
    val prefs = NotifyPrefs()
    val rome = LocalDateTime.of(2026, 11, 3, 19, 5)
    // In viaggio, a New York, lo stesso istante e' le 13:05: il prossimo orario e' le 19:00 di New York, fra 5h55m.
    val newYork = rome.minusHours(6)
    assertThat(policy.nextSlot(newYork, prefs)).isEqualTo(LocalDateTime.of(2026, 11, 3, 19, 0))
    assertThat(delay(newYork, prefs, ZoneId.of("America/New_York"))).isEqualTo(Duration.ofHours(5).plusMinutes(55))
    // Tornato a Roma con il lavoro ancora pianificato per le 19:00 di New York (le 01:00 a Roma): e' alle 01:00, fuori finestra, si salta e si ripianifica.
    assertThat(policy.slot(LocalDateTime.of(2026, 11, 4, 1, 0), prefs)).isEqualTo(SlotDecision.Wait(LocalDateTime.of(2026, 11, 4, 19, 0), skipped = true))
  }

  /** Mai un ritardo negativo, anche con un orario nella lacuna e una "adesso" gia' dopo. */
  @Test
  fun ilRitardoNonEMaiNegativo() {
    // Le 02:30 del 29 marzo non esistono e valgono le 03:30: a "adesso" = 03:40 sono gia' passate.
    val now = LocalDateTime.of(2026, 3, 29, 3, 40)
    assertThat(MemoryNotifyPolicy.delayUntil(now, LocalDateTime.of(2026, 3, 29, 2, 30), rome)).isEqualTo(Duration.ZERO)
    assertThat(MemoryNotifyPolicy.delayUntil(now, now.minusDays(2), rome)).isEqualTo(Duration.ZERO)
  }

  // --- Le frequenze di Q4 -----------------------------------------------------------------------------------------------------------

  /** Una storia di [days] giorni con un ricordo "anni fa" ogni giorno e, ogni [specialEvery] giorni, anche un anniversario: i giorni con notifica. */
  private fun simulate(frequency: NotifyFrequency, days: Int, specialEvery: Int = 0): List<Pair<LocalDate, Boolean>> {
    var ledger = NotifyLedger()
    val sent = ArrayList<Pair<LocalDate, Boolean>>()
    for (n in 0 until days) {
      val day = today.plusDays(n.toLong())
      val offered = ArrayList(listOf(candidate(NotifyFixtures.otd(day, years = 1 + n % 5))))
      if (specialEvery > 0 && n % specialEvery == specialEvery - 1) offered += candidate(NotifyFixtures.tripAnniversary(day))
      val chosen = policy.choose(offered, NotifyPrefs(frequency = frequency), ledger, day, openedToday = false) ?: continue
      val special = NotifyRules.isSpecial(offered.first { it.memory.id == chosen }.memory)
      ledger = ledger.with(NotifyMark(dayInt(day), special), day)
      sent += day to special
    }
    return sent
  }

  /** Q4, la frequenza di base: con un ricordo bello ogni giorno si va da 2 a 3 a settimana, mai due giorni di fila, mai piu' di 3 in 7 giorni. */
  @Test
  fun dueOTreASettimana() {
    val sent = simulate(NotifyFrequency.FEW_PER_WEEK, days = 365).map { it.first }
    val set = sent.toSet()
    for (start in 0..358) {
      val inWeek = (0 until 7).count { today.plusDays((start + it).toLong()) in set }
      assertWithMessage("settimana $start").that(inWeek).isIn(2..3)
    }
    for (day in sent) assertThat(day.plusDays(1) in set).isFalse()
  }

  /** Q4: nei giorni speciali (anniversari) arriva sempre, anche oltre il tetto, ma mai due al giorno; per gli altri giorni valgono tetto e distanza. */
  @Test
  fun neiGiorniSpecialiFinoAUnaAlGiorno() {
    val sent = simulate(NotifyFrequency.FEW_PER_WEEK, days = 400, specialEvery = 5)
    assertThat(sent.map { it.first }).containsNoDuplicates()
    // Un anniversario ogni 5 giorni: tutti arrivano (anche il giorno dopo un'altra notifica, anche con il tetto raggiunto).
    assertThat(sent.count { it.second }).isEqualTo(80)
    // Fuori dagli anniversari, mai due di fila e mai oltre 3 in 7 giorni (contando tutto cio' che c'e' stato prima).
    val all = sent.map { it.first }.toSet()
    val ordinary = sent.filter { !it.second }
    assertThat(ordinary).isNotEmpty()
    for ((day, _) in ordinary) {
      assertThat(day.minusDays(1) in all).isFalse()
      assertThat((1L..6L).count { day.minusDays(it) in all }).isLessThan(NotifyRules.WEEKLY_CAP)
    }
  }

  /** Q4: `ANNIVERSARIES` manda solo anniversari; `DAILY` una al giorno, ogni giorno. */
  @Test
  fun soloAnniversariEOgniGiorno() {
    val anniversaries = simulate(NotifyFrequency.ANNIVERSARIES, days = 120, specialEvery = 10)
    assertThat(anniversaries).hasSize(12)
    assertThat(anniversaries.all { it.second }).isTrue()
    val daily = simulate(NotifyFrequency.DAILY, days = 120)
    assertThat(daily).hasSize(120)
  }

  /** Q4: in nessuna frequenza due nello stesso giorno, qualunque cosa ci sia nel registro. */
  @Test
  fun maiDueNelloStessoGiornoInNessunaFrequenza() {
    for (frequency in NotifyFrequency.entries) {
      val prefs = NotifyPrefs(frequency = frequency)
      val offered = listOf(candidate(NotifyFixtures.tripAnniversary(today)), candidate(NotifyFixtures.otd(today)))
      assertWithMessage(frequency.code).that(policy.choose(offered, prefs, ledgerOf(today, 0), today, openedToday = false)).isNull()
    }
  }

  // --- Il contenuto (D16, Q5 (a), Q9) -----------------------------------------------------------------------------------------------

  private fun everyKind(): List<Memory> = listOf(
    NotifyFixtures.otd(today, years = 3), NotifyFixtures.tripAnniversary(today), NotifyFixtures.rediscovery(today), NotifyFixtures.month(today), NotifyFixtures.person(today),
  )

  /** La modalita' generica non ha **niente** del ricordo: lo stesso contenuto per ogni ricordo, e nessun testo, numero, titolo, luogo o immagine. */
  @Test
  fun laModalitaGenericaNonHaNienteDelRicordo() {
    val contents = everyKind().map { policy.content(it, LockScreenMode.GENERIC) }
    assertThat(contents.toSet()).hasSize(1)
    val content = contents.first()
    assertThat(content.image).isNull()
    for (text in listOf(content.title, content.text, content.publicTitle, content.publicText)) {
      assertThat(text.key).isIn(NotifyTexts.GENERIC)
      assertThat(text.numbers).isEmpty()
      assertThat(text.label).isNull()
    }
    assertThat(content.title.key).isEqualTo(NotifyTexts.GENERIC_TITLE)
    assertThat(content.text.key).isEqualTo(NotifyTexts.GENERIC_TEXT)
    // Nessuna traccia del titolo, del luogo o della persona di prova, in nessuna forma testuale del contenuto.
    for (m in everyKind()) assertThat(textOf(policy.content(m, LockScreenMode.GENERIC), m)).isEmpty()
  }

  /** "Titolo e foto": mai un nome (una persona resta "Un ricordo per te" nel titolo) e la versione pubblica e' sempre quella generica. */
  @Test
  fun titoloEFotoMaiUnNomeELaVersionePubblicaGenerica() {
    val generic = policy.content(everyKind().first(), LockScreenMode.GENERIC)
    for (m in everyKind()) {
      val content = policy.content(m, LockScreenMode.TITLE_AND_PHOTO)
      assertThat(content.publicTitle).isEqualTo(generic.publicTitle)
      assertThat(content.publicText).isEqualTo(generic.publicText)
      assertThat(content.image).isEqualTo(m.cover)
      m.title.person?.let { person ->
        assertThat(content.title.key).isEqualTo(NotifyTexts.GENERIC_TITLE)
        assertThat(content.title.label).isNull()
        assertThat(content.title.numbers).isEmpty()
        assertThat(person).isEqualTo(NotifyFixtures.PERSON)
      }
      // Il solo testo dell'utente ammesso e' il titolo di un viaggio della Fase 7 (mai di una persona).
      assertThat(content.title.label == null || m.title.tripLabel == content.title.label).isTrue()
      assertThat(content.text.label).isNull()
    }
    val trip = policy.content(NotifyFixtures.tripAnniversary(today), LockScreenMode.TITLE_AND_PHOTO)
    assertThat(trip.title.label).isEqualTo(NotifyFixtures.LABEL)
    assertThat(policy.content(NotifyFixtures.otd(today, years = 2), LockScreenMode.TITLE_AND_PHOTO).title.numbers).containsExactly(2, NotifyFixtures.PLACE).inOrder()
  }

  /** `toString()` sicuri (D33): nessun titolo, luogo, id o persona. */
  @Test
  fun iToStringNonDiconoNiente() {
    for (m in everyKind()) {
      val content = policy.content(m, LockScreenMode.TITLE_AND_PHOTO)
      assertThat(content.toString()).doesNotContain(NotifyFixtures.LABEL)
      assertThat(content.toString()).doesNotContain(NotifyFixtures.PLACE.toString())
      assertThat(content.title.toString()).doesNotContain(NotifyFixtures.LABEL)
      assertThat(content.toString()).doesNotContain(m.cover.value)
    }
  }

  /** Tutto il testo di un contenuto, con gli argomenti, cercato per i marcatori di prova (titolo, luogo, id e persona del ricordo). */
  private fun textOf(content: NotifyContent, m: Memory): List<String> {
    val all = listOf(content.title, content.text, content.publicTitle, content.publicText)
    val haystack = all.joinToString(" ") { "${it.key} ${it.numbers.joinToString(" ")} ${it.label.orEmpty()}" } + " ${content.image?.value.orEmpty()}"
    val markers = listOf(NotifyFixtures.LABEL, NotifyFixtures.PLACE.toString(), m.cover.value, m.id.value, NotifyFixtures.PERSON.base32())
    return markers.filter { it in haystack }
  }

  // --- Parita' con il riferimento ---------------------------------------------------------------------------------------------------

  /** Stesse risposte del riferimento di P0 su 3.000 situazioni a caso (frequenza, registro, candidati, aperto oggi, preferenze). */
  @Test
  fun staCoStessoRiferimento() {
    val random = Random(5)
    val reference = ReferenceNotifyPolicy()
    val frequencies = NotifyFrequency.entries
    repeat(3_000) {
      val day = today.plusDays(random.nextInt(400).toLong())
      val kinds = listOf(
        NotifyFixtures.otd(day, 1 + random.nextInt(6), 5 + random.nextInt(10)), NotifyFixtures.tripAnniversary(day, 6 + random.nextInt(10)), NotifyFixtures.rediscovery(day),
        NotifyFixtures.month(day), NotifyFixtures.person(day),
      )
      val offered = kinds.filter { random.nextInt(3) > 0 }.map { m ->
        candidate(m, seen = random.nextInt(8) == 0, dismissed = random.nextInt(10) == 0, visible = if (random.nextInt(6) == 0) 7 else m.members.size, less = random.nextInt(5) == 0)
      }
      val ledger = NotifyLedger((0L..13L).filter { random.nextInt(4) == 0 }.map { NotifyMark(dayInt(day.minusDays(it)), random.nextBoolean()) }.sortedBy { it.day })
      val prefs = NotifyPrefs(enabled = random.nextInt(10) > 0, frequency = frequencies[random.nextInt(frequencies.size)], lockScreen = LockScreenMode.entries[random.nextInt(2)])
      val opened = random.nextInt(8) == 0
      assertThat(policy.choose(offered, prefs, ledger, day, opened)).isEqualTo(reference.choose(offered, prefs, ledger, day, opened))
      for (m in kinds) for (mode in LockScreenMode.entries) assertThat(policy.content(m, mode)).isEqualTo(reference.content(m, mode))
      val now = day.atTime(random.nextInt(24), random.nextInt(60), random.nextInt(60))
      val slotPrefs = NotifyPrefs(hour = LocalTime.of(random.nextInt(24), random.nextInt(4) * 15), enabled = prefs.enabled)
      assertThat(policy.nextSlot(now, slotPrefs)).isEqualTo(reference.nextSlot(now, slotPrefs))
      // L'unica differenza voluta e' la corsa in anticipo di pochi minuti (qui non e' un giorno saltato).
      val expected = reference.slot(now, slotPrefs)
      val early = now.toLocalDate().atTime(slotPrefs.hour).let { slot -> now.isBefore(slot) && Duration.between(now, slot).toMinutes() < MemoryNotifyPolicy.EARLY_TOLERANCE_MINUTES }
      if (!early) assertThat(policy.slot(now, slotPrefs)).isEqualTo(expected)
    }
  }
}
