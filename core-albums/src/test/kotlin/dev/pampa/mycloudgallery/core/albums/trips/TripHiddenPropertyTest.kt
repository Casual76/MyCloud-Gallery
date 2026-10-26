package dev.pampa.mycloudgallery.core.albums.trips

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * NESSUN NASCOSTO NEI VIAGGI, CON LA PROPRIETA' (docs/fase7-progetto.md D22, E-H5..E-H7, C27; riga P7 del §4.4). Per 60 storie casuali con il 20%
 * di media nascosti e il 6% "contagiati" (un membro non nascosto di un gruppo in cui un altro lo e': le query dei candidati non lo sanno,
 * `VisibleMedia` si'):
 *  - **un nascosto e' indistinguibile da un media che non esiste**: le proposte sono identiche a quelle di una Libreria costruita senza quei
 *    media (impronte, giorni, conteggi, copertine, titoli, casa); un viaggio di soli nascosti non c'e';
 *  - **nascosti dopo la rilevazione**: al ricontrollo nessuna copertina e nessun conteggio li contiene, una proposta senza piu' media sparisce;
 *  - il filtro finale (`HiddenMask`) e' davvero chiamato.
 * La stessa regola sul catalogo vero di Room e' nel registro delle superfici di `:app` (`HiddenSurfaces`, riga `trips`).
 */
class TripHiddenPropertyTest {

  private val today = LocalDate.of(2026, 10, 22)

  private fun story(seed: Long): Story {
    val random = Random(seed)
    val b = StoryBuilder("nascosti-$seed", seed, today)
    val dests = listOf(Places.OSLO, Places.ROMA, Places.PARIGI, Places.LONDRA, Places.MONACO, Places.LISBONA)
    var start = b.first.plusDays(30L + random.nextInt(60))
    repeat(3 + random.nextInt(3)) {
      b.trip(start, 2 + random.nextInt(6), dests[random.nextInt(dests.size)], perDay = 4..10, expected = false)
      start = start.plusDays(90L + random.nextInt(60))
    }
    repeat(1 + random.nextInt(2)) { b.event(b.first.plusDays(100L + random.nextInt(500)), 19, 0, 35 + random.nextInt(20), 150, Places.FIRENZE, expected = false) }
    b.residence(b.first, today, Habits(Places.FIRENZE))
    return b.build()
  }

  private fun pick(shots: List<AlbumCandidate>, percent: Int, random: Random, except: Set<MediaId> = emptySet()): Set<MediaId> =
    shots.filter { it.media !in except && random.nextInt(100) < percent }.mapTo(HashSet()) { it.media }

  private fun visibleOf(shots: List<AlbumCandidate>, invisible: Set<MediaId>) = shots.filter { it.mine && !it.screen && it.media !in invisible }

  @Test
  fun unNascostoEIndistinguibileDaUnMediaCheNonEsiste() = runBlocking<Unit> {
    val problems = ArrayList<String>()
    for (seed in 1L..60L) {
      val s = story(seed)
      val random = Random(seed * 31)
      val hidden = pick(s.shots, 20, random)
      val contagion = pick(s.shots, 6, random, except = hidden)
      val invisible = hidden + contagion

      val withHidden = StoryLibrary(s.shots).also { it.hidden += hidden; it.contagion += contagion }
      val without = StoryLibrary(s.shots.filter { it.media !in invisible })
      TripsHarness(withHidden, today).use { a ->
        TripsHarness(without, today).use { b ->
          val ra = a.trips.detect(1.minutes)
          val rb = b.trips.detect(1.minutes)
          if (a.trips.proposals.value != b.trips.proposals.value) problems += "[$seed] le proposte con i nascosti non sono quelle senza"
          if (ra.trips != rb.trips || ra.events != rb.events || ra.homeKnown != rb.homeKnown) problems += "[$seed] il resoconto cambia"
          for (p in a.trips.proposals.value.proposals) {
            if (p.cover in invisible) problems += "[$seed] una copertina nascosta"
            val members = visibleOf(s.shots, invisible).count { it.day in p.days }
            if (p.count != members) problems += "[$seed] conteggio ${p.count} invece di $members"
          }
          if (a.library.maskCalls == 0 && a.trips.proposals.value.proposals.isNotEmpty()) problems += "[$seed] il filtro finale non e' stato chiamato"
        }
      }
    }
    assertWithMessage("nascosti che si vedono nei viaggi").that(problems).isEmpty()
  }

  @Test
  fun nascostiDopoLaRilevazioneNonCompaionoNeSiContano() = runBlocking<Unit> {
    val problems = ArrayList<String>()
    var vanished = 0
    for (seed in 1L..60L) {
      val s = story(seed)
      val random = Random(seed * 17)
      val library = StoryLibrary(s.shots)
      TripsHarness(library, today).use { h ->
        h.trips.detect(1.minutes)
        val before = h.trips.proposals.value.proposals
        // Poi l'utente nasconde il 40% dei media (e un altro 8% e' "contagiato" da un gruppo): nessuna rilevazione nuova, solo il ricontrollo.
        val victim = before.lastOrNull()
        val late = pick(s.shots, 40, random) + s.shots.filter { victim != null && it.day in victim.days }.map { it.media }
        val lateContagion = pick(s.shots, 8, random, except = late)
        library.hidden += late
        library.contagion += lateContagion
        h.trips.refresh()
        val after = h.trips.proposals.value.proposals
        val invisible = late + lateContagion
        for (p in after) {
          if (p.cover in invisible) problems += "[$seed] copertina nascosta dopo"
          val members = visibleOf(s.shots, invisible).count { it.day in p.days }
          if (p.count != members) problems += "[$seed] conteggio ${p.count} invece di $members"
          if (p.id !in before.map { it.id }) problems += "[$seed] una proposta nuova dal ricontrollo"
        }
        val gone = before.filter { b -> visibleOf(s.shots, invisible).none { it.day in b.days } }
        vanished += gone.size
        if (after.map { it.id }.toSet() != before.map { it.id }.toSet() - gone.map { it.id }.toSet()) problems += "[$seed] le proposte senza piu' media non sono sparite"
      }
    }
    assertWithMessage("nascosti dopo la rilevazione").that(problems).isEmpty()
    // La prova ha di che provare: qualche proposta e' sparita davvero perche' tutti i suoi media sono diventati nascosti.
    assertThat(vanished).isAtLeast(1)
  }
}
