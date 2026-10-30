package dev.pampa.mycloudgallery.core.memories.notify

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.NotifyFrequency
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.notify.NotifyFixtures.dayInt
import dev.pampa.mycloudgallery.core.memories.plan.dayShots
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryNotifyLedgerStore
import dev.pampa.mycloudgallery.core.memories.testing.ProcessDied
import java.time.LocalDate
import java.time.LocalTime
import java.util.Random
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * **La proprieta' delle notifiche** (docs/fase9-progetto.md D15, D20, E-NT3, E-NT5; risposta Q4): in qualunque storia, **al piu' una notifica al
 * giorno** e, con la frequenza di serie, al piu' 3 in 7 giorni e mai due di fila. La storia e' fatta di giorni con un ricordo bello ogni giorno,
 * lavori che partono una, due o tre volte, a qualunque ora (anticipo, finestra, oltre H+3 h), permesso tolto e rimesso, e la **morte del processo**
 * in ognuno dei tre punti pericolosi: prima del registro, fra il registro e la notifica, dopo il registro. Dopo ogni morte il lavoro e' un oggetto
 * nuovo sugli stessi dati (come dopo un riavvio). I numeri sono quelli dell'ultima corsa dei seed.
 */
class NotifyOncePerDayPropertyTest {

  private val rigs = ArrayList<NotifyRig>()
  private val start = LocalDate.of(2026, 1, 5)

  @After
  fun chiudiTutto() {
    rigs.forEach { it.close() }
  }

  /** Una Libreria con, per ogni giorno della storia, dodici foto di un anno prima: ogni giorno ha un ricordo "un anno fa" da notificare. */
  private fun library(days: Int) = (0 until days).flatMap { n -> dayShots(start.plusDays(n.toLong()).minusYears(1), 10, series = 100L + n) }

  private data class Story(val days: Int, val published: List<LocalDate>, val deaths: Int, val skipsByReason: Map<String, Int>)

  private fun runStory(seed: Long, days: Int, frequency: NotifyFrequency, withDeaths: Boolean): Story = kotlinx.coroutines.runBlocking {
    val random = Random(seed)
    val ledger = DyingLedger(InMemoryNotifyLedgerStore())
    val rig = NotifyRig(library(days), start, NotifyPrefs(frequency = frequency, lockScreen = LockScreenMode.entries[random.nextInt(2)]), ledger = ledger).also { rigs += it }
    var deaths = 0
    val published = ArrayList<LocalDate>()
    for (n in 0 until days) {
      val day = start.plusDays(n.toLong())
      val runs = 1 + random.nextInt(3)
      repeat(runs) {
        // L'ora: il 60% nella finestra (19:00-22:00), il 20% prima, il 20% oltre H+3 h.
        val minutes = when (random.nextInt(10)) {
          in 0..5 -> 19 * 60 + random.nextInt(181)
          in 6..7 -> 6 * 60 + random.nextInt(13 * 60)
          else -> 22 * 60 + 1 + random.nextInt(116)
        }
        rig.at(day, LocalTime.of(minutes / 60, minutes % 60))
        rig.access.system = if (random.nextInt(12) == 0) NotifyAccessState.PERMISSION_MISSING else NotifyAccessState.GRANTED
        if (withDeaths && random.nextInt(5) == 0) {
          when (random.nextInt(3)) {
            0 -> ledger.dieBefore = true
            1 -> ledger.dieAfter = true
            else -> rig.publisher.dieAtNext = true
          }
        }
        try {
          rig.run().run()
        } catch (_: ProcessDied) {
          deaths++
        } finally {
          ledger.dieBefore = false
          ledger.dieAfter = false
          rig.publisher.dieAtNext = false
        }
      }
      val sent = rig.publisher.publishedOn(dayInt(day))
      assertWithMessage("seed $seed, giorno $n: notifiche partite").that(sent).isAtMost(1)
      // Il registro e' sempre un passo avanti (o uguale) alla notifica: se e' partita, e' segnata.
      if (sent == 1) {
        published += day
        assertThat(ledger.read().notifiedOn(day)).isTrue()
      }
      assertThat(ledger.read().marks.map { it.day }).containsNoDuplicates()
    }
    Story(days, published, deaths, rig.events.skips.groupingBy { it.code }.eachCount())
  }

  /** Q4 e "al piu' una al giorno" con la frequenza di serie, su 40 storie da 120 giorni con morti, anticipi, ritardi e permessi che vanno e vengono. */
  @Test
  fun alPiuUnaAlGiornoEDueOTreASettimana() {
    var published = 0
    var deaths = 0
    for (seed in 1L..40L) {
      val story = runStory(seed, days = 120, frequency = NotifyFrequency.FEW_PER_WEEK, withDeaths = true)
      published += story.published.size
      deaths += story.deaths
      val set = story.published.toSet()
      for (day in story.published) {
        // Mai due di fila e mai oltre 3 in 7 giorni (qui non ci sono anniversari: tutti i ricordi sono "anni fa").
        assertWithMessage("seed $seed: due giorni di fila").that(day.plusDays(1) in set).isFalse()
        assertWithMessage("seed $seed: oltre il tetto settimanale").that((0L..6L).count { day.minusDays(it) in set }).isAtMost(3)
      }
    }
    // La prova non e' vuota: notifiche, morti e giorni saltati ci sono davvero.
    assertThat(published).isGreaterThan(40 * 120 / 6)
    assertThat(deaths).isGreaterThan(50)
  }

  /** Con "una al giorno" e con "solo anniversari" vale la stessa regola: mai due nello stesso giorno, qualunque cosa succeda ai lavori. */
  @Test
  fun alPiuUnaAlGiornoInOgniFrequenza() {
    for (frequency in listOf(NotifyFrequency.DAILY, NotifyFrequency.ANNIVERSARIES)) {
      for (seed in 100L..115L) {
        val story = runStory(seed, days = 90, frequency = frequency, withDeaths = true)
        assertThat(story.published).containsNoDuplicates()
        if (frequency == NotifyFrequency.ANNIVERSARIES) assertThat(story.published).isEmpty()
      }
    }
  }

  /** Senza morti la frequenza "una al giorno" non perde un giorno in cui il lavoro parte nella finestra: la prova non e' vuota. */
  @Test
  fun unaAlGiornoMandaDavveroUnaAlGiorno() = runTest {
    val days = 30
    val rig = NotifyRig(library(days), start, NotifyPrefs(frequency = NotifyFrequency.DAILY)).also { rigs += it }
    for (n in 0 until days) {
      rig.at(start.plusDays(n.toLong()), LocalTime.of(19, 0).plusMinutes((n % 60).toLong()))
      rig.run().run()
      assertThat(rig.publisher.publishedOn(dayInt(start.plusDays(n.toLong())))).isEqualTo(1)
    }
    assertThat(rig.publisher.published).hasSize(days)
  }
}
