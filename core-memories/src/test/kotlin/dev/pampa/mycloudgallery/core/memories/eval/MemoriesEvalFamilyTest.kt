package dev.pampa.mycloudgallery.core.memories.eval

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.PhotoScore
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticVectors
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * La Libreria sintetica "di una famiglia" (docs/fase9-progetto.md §5.3 punto 1, riga P11 del §4.4): la forma che deve avere, le date **relative al giorno della
 * prova**, i concetti nei vettori finti, le misure finte dei pixel, nessun dato personale.
 */
class MemoriesEvalFamilyTest {

  private val world = familyWorld(EVAL_TODAY)

  @Test
  fun haLaFormaDiUnaFamigliaDiCinqueAnni() {
    assertThat(world.shots.size).isIn(4_500..7_000)
    val days = world.shots.values.map { it.day }
    assertThat(days.min()).isAtLeast(EVAL_TODAY.minusYears(5))
    assertThat(days.max()).isLessThan(EVAL_TODAY)
    // Cinque anni di giorni con foto, non una manciata.
    assertThat(days.toSet().size).isGreaterThan(600)
    val sources = world.shots.values.groupingBy { it.source }.eachCount()
    assertThat(sources.keys).containsAtLeast(MediaSource.CAMERA, MediaSource.SCREENSHOT, MediaSource.WHATSAPP)
    assertThat(world.shots.values.count { CellFlags.has(it.flags, CellFlags.DOCUMENT) }).isGreaterThan(50)
    assertThat(world.shots.values.count { it.kind == MediaKind.VIDEO }).isGreaterThan(100)
    assertThat(world.shots.values.count { CellFlags.has(it.flags, CellFlags.FAVORITE) }).isGreaterThan(100)
    // Le riprese ripetute: centinaia di gruppi, ognuno con almeno due scatti.
    val groups = world.shots.values.filter { it.dupGroup != null }.groupBy { it.dupGroup }
    assertThat(groups.size).isGreaterThan(300)
    assertWithMessage("gruppi con un solo scatto: " + groups.values.count { it.size < 2 }).that(groups.values.all { it.size >= 2 }).isTrue()
    // Tre persone, ognuna in una quota diversa; viaggi (sette) ed evento (uno), senza album e senza coordinate (il tipo della porta non le porta).
    assertThat(world.personMedia.keys).hasSize(3)
    assertThat(world.personMedia.values.map { it.size }.distinct()).hasSize(3)
    assertThat(world.trips.count { it.kind == TripKind.TRIP }).isEqualTo(7)
    assertThat(world.trips.count { it.kind == TripKind.EVENT }).isEqualTo(1)
    assertWithMessage(world.trips.joinToString { "${it.kind}:${it.members.size}" }).that(world.trips.all { it.album == null && it.members.size >= 10 }).isTrue()
  }

  @Test
  fun iGiorniDiSoliScreenshotCiSono() {
    val byDay = world.shots.values.groupBy { it.day }
    val screenshotOnly = byDay.filterValues { shots -> shots.all { it.source == MediaSource.SCREENSHOT } }
    assertThat(screenshotOnly.size).isAtLeast(4)
  }

  @Test
  fun iNascostiPerfettiCiSonoESonoFuoriDallIstantanea() {
    assertThat(world.hiddenPerfect).hasSize(54)
    assertThat(world.library.visibility.hidden).containsAtLeastElementsIn(world.hiddenPerfect)
    val snapshot = world.library.snapshot()
    val inSnapshot = (0 until snapshot.size).map { snapshot.idAt(it) }.toSet()
    assertThat(inSnapshot.intersect(world.hiddenPerfect)).isEmpty()
    // Perfetti davvero: preferiti e modificati, e una persona; e il banco non li da' fra le foto visibili.
    for (id in world.hiddenPerfect) {
      assertThat(CellFlags.has(world.shots.getValue(id).flags, CellFlags.FAVORITE or CellFlags.EDITED)).isTrue()
    }
    assertThat(world.visibleShots.map { it.id }.intersect(world.hiddenPerfect)).isEmpty()
    assertThat(world.trips.flatMap { it.members }.intersect(world.hiddenPerfect)).isEmpty()
  }

  @Test
  fun iConcettiTristiStannoNeiVettoriENelMotoreNonCeNiente() {
    val prototypes = listOf(777_101L, 777_102L, 777_103L).map { SyntheticVectors.concept(it) }
    fun nearestSad(v: FloatArray): Double = prototypes.maxOf { PhotoScore.cosine(v, it) }
    // I giorni tristi sono pochi (cinque in cinque anni) e quasi tutte le loro foto sono tristi.
    val sadDays = world.shots.values.groupBy { it.day }.filterValues { shots -> shots.count { it.fault == EvalFault.SAD } >= 4 }
    assertThat(sadDays.size).isEqualTo(5)
    val sad = world.shots.values.filter { it.fault == EvalFault.SAD && world.vectors[it.id] != null }
    val others = world.shots.values.filter { it.fault != EvalFault.SAD && world.vectors[it.id] != null }
    assertThat(sad.size).isAtLeast(30)
    // Le foto tristi sono vicine a un prototipo triste; le altre no (concetti diversi sono quasi ortogonali), salvo le riprese ripetute e le foto "simili" di una foto
    // triste, che stanno nei giorni tristi (l'utente ideale le segna come doppioni o le lascia).
    assertThat(sad.count { nearestSad(world.vectors.getValue(it.id)) >= 0.7 }.toDouble() / sad.size).isAtLeast(0.95)
    val near = others.filter { nearestSad(world.vectors.getValue(it.id)) >= 0.7 }
    assertThat(near.all { it.day in sadDays.keys }).isTrue()
    // Con Q6 (a) nel motore non c'e' nessun filtro: i vettori finti dei concetti tristi li legge solo la misura (`FamilyIdealUser`).
  }

  @Test
  fun leMisureDeiPixelSonoFinteEDistinguonoMosseENitide() {
    val probed = world.shots.values.filter { world.probes[it.id] != null }
    val blurry = probed.filter { it.blurry }
    val sharp = probed.filter { !it.blurry }
    assertThat(blurry.size).isGreaterThan(80)
    // Le mosse stanno quasi tutte sotto 100 (qualcuna no: nessuna soglia e' perfetta); le nitide quasi tutte sopra.
    val blurryBelow = blurry.count { world.probes.getValue(it.id).sharpness < 100f }.toDouble() / blurry.size
    assertThat(blurryBelow).isAtLeast(0.80)
    assertThat(blurryBelow).isAtMost(0.97)
    assertThat(sharp.count { world.probes.getValue(it.id).sharpness < 100f }.toDouble() / sharp.size).isAtMost(0.02)
    // Le foto buie hanno poca luce; le altre quasi tutte no.
    val dark = probed.filter { it.dark }
    assertThat(dark.count { world.probes.getValue(it.id).luma < 0.12f }.toDouble() / dark.size).isAtLeast(0.8)
    assertThat(probed.filter { !it.dark }.count { world.probes.getValue(it.id).luma < 0.12f }).isEqualTo(0)
  }

  @Test
  fun leDateSonoRelativeAlGiornoDellaProva() {
    // Gli anniversari seminati cadono nei giorni giusti per qualunque "oggi" (anche un 29 febbraio): i quattro viaggi con l'anniversario nella finestra di 45
    // giorni compaiono come ricordi di viaggio, e il riferimento da' un numero di ricordi dello stesso ordine.
    for (today in listOf(EVAL_TODAY, LocalDate.of(2027, 3, 5), LocalDate.of(2028, 2, 29))) {
      val other = familyWorld(today)
      val firsts = other.trips.filter { it.kind == TripKind.TRIP }.map { it.days.from }
      for ((offset, years) in listOf(2 to 1, 10 to 2, 26 to 3, 40 to 4)) assertThat(firsts).contains(today.minusDays(offset.toLong()).minusYears(years.toLong()))
      FamilyRig(other, Executor.REAL).use { rig ->
        val keys = runBlocking { rig.run(describe = { it.key.text }) }.map { it.title }
        assertWithMessage("$today: ${keys.size} ricordi").that(keys.size).isIn(35..80)
        val trips = keys.filter { it.startsWith("trip:") }
        for ((offset, years) in listOf(2 to 1, 10 to 2, 26 to 3, 40 to 4)) {
          val from = today.minusDays(offset.toLong()).minusYears(years.toLong())
          assertWithMessage("$today: il viaggio di $offset giorni fa, $years anni").that(trips.any { it.startsWith("trip:$from:") }).isTrue()
        }
        assertThat(keys.count { it.startsWith("otd:") }).isAtLeast(25)
      }
    }
  }

  @Test
  fun nienteDiPersonaleNelMondoFinto() {
    // I luoghi sono id inventati (GeoNames finti, da 9.000.000 in su), le persone hanno nomi neutri, le etichette dei viaggi sono "viaggio-N" ed "evento-N".
    assertThat(world.shots.values.map { world.library.shot(it.id).place }.filter { it != 0 }.all { it >= 9_000_000 }).isTrue()
    assertThat(world.trips.all { it.placeIds.all { p -> p >= 9_000_000 } }).isTrue()
    assertThat(world.trips.all { it.label.matches(Regex("(viaggio|evento)-[0-9]+")) }).isTrue()
    assertThat(world.personMedia.keys.map { world.personName(it) }).containsExactly("persona-a", "persona-b", "persona-c")
    // Nessun toString mostra id o giorni.
    val shot = world.shots.values.first()
    assertThat(shot.toString()).doesNotContain(shot.id.value)
    assertThat(shot.toString()).doesNotContain(shot.day.toString())
  }
}
