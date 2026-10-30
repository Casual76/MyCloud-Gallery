package dev.pampa.mycloudgallery.core.memories.eval

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.memories.contract.PickSpec
import dev.pampa.mycloudgallery.core.memories.pick.MemoryPhotoPicker
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePicker
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * La corsa del riferimento sulla Libreria sintetica "di una famiglia" (docs/fase9-progetto.md §5.3 punto 1, riga P11 del §4.4), **con i suoi numeri attesi**.
 * "Oggi" e' il 30/10/2026 e gli ultimi 45 giorni sono simulati uno per uno, come fara' lo strumento sul telefono. Il riferimento (`InMemoryMemories`, tutte
 * le coppie, nessuna ottimizzazione) e il motore vero (`MemoryEngine` con la scelta di P7) devono dare **gli stessi ricordi**.
 *
 * I numeri del sintetico non dicono se i ricordi piacciono (lo sa solo l'utente, sulle sue foto: F125): dicono che la macchina misura quello che deve, che il
 * motore non fa errori di regola (nessuna esclusione di D5, nessun nascosto) e quanto valgono i difetti seminati. Sono l'ancora a cui confrontare la misura vera.
 */
class MemoriesEvalReferenceRunTest {

  private val world = familyWorld(EVAL_TODAY)

  private fun run(executor: Executor): List<EvalMemory> = runBlocking { FamilyRig(world, executor).use { it.run() } }

  private fun shape(m: EvalMemory) = listOf(m.id, m.kind, m.day, m.cover, m.members)

  @Test
  fun ilRiferimentoEIlMotoreVeroDannoGliStessiRicordi() {
    val reference = run(Executor.REFERENCE)
    val real = run(Executor.REAL)
    assertThat(real.map(::shape)).isEqualTo(reference.map(::shape))
    assertThat(reference).isNotEmpty()
  }

  @Test
  fun iNumeriAttesiDelRiferimento() {
    val run = run(Executor.REFERENCE)
    // §5.3: circa 40-60 ricordi negli ultimi 45 giorni.
    assertThat(run.size).isEqualTo(EXPECTED_MEMORIES)
    assertThat(run.groupingBy { it.kind }.eachCount()).containsExactlyEntriesIn(EXPECTED_BY_KIND)
    val n = MemoriesEvalMetrics.of(FamilyIdealUser.label(world, run))
    assertThat(n.memories).isEqualTo(EXPECTED_MEMORIES)
    assertThat(n.photos).isEqualTo(EXPECTED_PHOTOS)
    assertThat(listOf(n.good, n.soSo, n.dontShow, n.dontShowSad, n.dontShowWrong)).containsExactly(45, 5, 1, 1, 0).inOrder()
    assertThat(n.faults).isEqualTo(EXPECTED_FAULTS)
    assertThat(n.faultsByKind).containsExactlyEntriesIn(EXPECTED_FAULTS_BY_KIND)
    assertThat(n.clean).isEqualTo(EXPECTED_CLEAN)
    // Le quote contro gli obiettivi di §5.3 (non e' la misura vera: e' l'ancora). Tre su quattro reggono; le foto da togliere in media stanno appena sopra 1 su 12
    // (8,9% contro 8,3%): i difetti seminati sono l'8,6% delle foto e il punteggio ordina senza escludere, quindi un ricordo piccolo se li tiene (vedi la calibrazione).
    val targets = MemoriesEvalTargets()
    assertThat(n.goodShare).isAtLeast(targets.good)
    assertThat(n.dontShowShare).isAtMost(targets.dontShow)
    assertThat(n.duplicateShare).isAtMost(targets.duplicates)
    assertThat(n.meanFaultShare).isWithin(0.0006).of(0.089)
    assertThat(n.meanFaultShare).isGreaterThan(targets.removePerPhoto)
    assertThat(n.meets(targets)).isFalse()
  }

  @Test
  fun nessunEsclusoDiD5NeiRicordiENessunNascosto() {
    // §5.3 punto 1: nessun escluso di D5 nei ricordi (100%); D31: un nascosto perfetto non esce mai.
    for (executor in Executor.entries) {
      val run = run(executor)
      val members = run.flatMap { it.members }.map { MediaId(it) }
      assertThat(members.mapNotNull { world.shots[it] }.filter { it.excluded }).isEmpty()
      assertThat(members.filter { it in world.hiddenPerfect }).isEmpty()
      assertThat(members.filter { it in world.library.visibility.hidden }).isEmpty()
      // Screenshot, documenti riconosciuti e immagini animate, uno per uno.
      for (m in members) {
        val shot = world.shots.getValue(m)
        assertThat(shot.source).isNotEqualTo(MediaSource.SCREENSHOT)
        assertThat(CellFlags.has(shot.flags, CellFlags.DOCUMENT)).isFalse()
        assertThat(shot.kind).isNotEqualTo(MediaKind.ANIMATED_IMAGE)
      }
    }
  }

  @Test
  fun leDimensioniStannoDentroILimitiDiD7ELaCopertinaEBuona() {
    val specs = MemoryKind.entries.associateWith { MemoryRules.spec(it) }
    for (m in run(Executor.REFERENCE)) {
      val spec = specs.getValue(MemoryKind.ofCode(m.kind)!!)
      assertWithMessage("${m.kind}: ${m.members.size}").that(m.members.size).isAtLeast(spec.minSize)
      assertThat(m.members.size).isAtMost(spec.maxSize)
      val cover = world.shots.getValue(MediaId(m.cover))
      assertThat(cover.kind).isNotEqualTo(MediaKind.VIDEO)
      assertThat(cover.source).isNotEqualTo(MediaSource.WHATSAPP)
      // Gli elementi sono in ordine di scatto.
      val times = m.members.map { world.shots.getValue(MediaId(it)).let { s -> s.day.atTime(0, 0).toEpochSecond(ZoneOffset.UTC) + s.secondOfDay } }
      assertThat(times).isInOrder()
    }
  }

  @Test
  fun stessaLibreriaEStessoGiornoDannoLoStessoRisultato() {
    // Deterministico: stessa Libreria, stesso giorno = stessi ricordi, stessi elementi, stesso ordine (§4.3).
    val first = run(Executor.REAL)
    val second = run(Executor.REAL)
    assertThat(second.map(::shape)).isEqualTo(first.map(::shape))
    // E la stessa Libreria costruita due volte e' la stessa Libreria.
    val again = familyWorld(EVAL_TODAY)
    assertThat(again.shots.keys.toList()).isEqualTo(world.shots.keys.toList())
    assertThat(again.vectors.size).isEqualTo(world.vectors.size)
  }

  @Test
  fun laCorsaNonScriveNellaLibreria() {
    val before = world.library.snapshot().seq
    val hidden = world.library.visibility.hidden.toSet()
    run(Executor.REAL)
    assertThat(world.library.snapshot().seq).isEqualTo(before)
    assertThat(world.library.visibility.hidden).isEqualTo(hidden)
    // Il giro del banco sta in un dispositivo suo: scaffale e scelte in memoria, mai quelli di un altro giro.
    FamilyRig(world, Executor.REAL).use { rig ->
      assertThat(rig.store.peek()).isNull()
      runBlocking { rig.run() }
      assertThat(rig.store.peek()).isNotNull()
    }
    FamilyRig(world, Executor.REAL).use { assertThat(it.store.peek()).isNull() }
  }

  @Test
  fun iRicordiDaNonMostrareSonoSoloUnaMisuraNonUnFiltro() {
    // Q6 (a): nessun filtro dei ricordi tristi. Il giorno triste che cade in un anniversario della finestra ESCE come ricordo, e l'utente ideale lo giudica
    // "Da non mostrare" per tristezza: e' il numero che la misura vera deve tenere sotto il 3%.
    val run = run(Executor.REFERENCE)
    val labels = FamilyIdealUser.label(world, run)
    val sad = run.filter { labels.judgement(it.id)?.why == EvalWhy.SAD }
    assertThat(sad).hasSize(1)
    val members = sad.single().members.map { world.shots.getValue(MediaId(it)) }
    assertThat(members.count { it.fault == EvalFault.SAD }.toDouble() / members.size).isAtLeast(SAD_SHARE)
    val n = MemoriesEvalMetrics.of(labels)
    assertThat(n.dontShowShare).isLessThan(MemoriesEvalTargets().dontShow)
    assertThat(n.sadFaults).isAtLeast(members.count { it.fault == EvalFault.SAD })
  }

  @Test
  fun alMenoIlNovantacinquePerCentoDeiDoppioniSeminatiVieneTolto() {
    // §5.3 punto 1, a livello di scelta: per ogni giorno con riprese ripetute (coseno >= 0,92, o entro la finestra senza vettori) la scelta tiene al piu' una
    // ripresa del gruppo. Il tetto di dimensione e' largo, cosi' conta solo il doppione. Con la finestra di D7 (3 minuti) erano il 94,0%: vedi la calibrazione.
    val figures = dedupeFigures(world)
    assertThat(figures.groups).isEqualTo(EXPECTED_DUPLICATE_GROUPS)
    assertWithMessage(figures.toString()).that(figures.recall).isAtLeast(0.95)
    assertThat(figures.removed).isEqualTo(EXPECTED_DUPLICATES_REMOVED)
    assertThat(figures.lost).isEqualTo(EXPECTED_UNIQUES_LOST)
  }

  @Test
  fun ilRiferimentoEIlMotoreVeroSceglieLeStesseFotoSuiGiorniDelleRiprese() {
    // La parita' di P7 sulla Libreria della famiglia: stessi elementi dal riferimento (tutte le coppie) e dalla scelta vera, giorno per giorno.
    val spec = PickSpec(MemoryKind.ON_THIS_DAY, 3, 40, perDayMin = 0, perDayMax = 40, perHourMax = 40, dateBound = false)
    var days = 0
    for ((_, shots) in world.visibleShots.filter { !it.excluded }.groupBy { it.day }) {
      if (shots.size < 3) continue
      val cells = shots.map { s ->
        CandidateCell(s.id, s.day.atTime(0, 0).toInstant(ZoneOffset.UTC).toEpochMilli() + s.secondOfDay * 1_000L, s.day.year * 10_000 + s.day.monthValue * 100 + s.day.dayOfMonth,
          s.kind, s.source, s.flags, 0, 1_333, null, false)
      }
      val ids = cells.map { it.id }.toSet()
      val signals = PickSignals(vectors = world.vectors.filterKeys { it in ids }, pixels = world.probes)
      assertThat(MemoryPhotoPicker().pick(cells, spec, signals).members).isEqualTo(ReferencePicker().pick(cells, spec, signals).members)
      days++
    }
    assertThat(days).isGreaterThan(500)
  }
  private companion object {
    const val EXPECTED_MEMORIES = 51
    const val EXPECTED_PHOTOS = 929
    const val EXPECTED_FAULTS = 70
    const val EXPECTED_CLEAN = 13
    const val EXPECTED_DUPLICATE_GROUPS = 453
    const val EXPECTED_DUPLICATES_REMOVED = 431
    const val EXPECTED_UNIQUES_LOST = 108
    val EXPECTED_BY_KIND = mapOf("otd" to 37, "trip" to 7, "person" to 5, "month" to 1, "event" to 1)
    val EXPECTED_FAULTS_BY_KIND = mapOf(
      EvalFault.DUPLICATE to 4, EvalFault.BLURRY_OR_DARK to 37, EvalFault.SCREENSHOT_OR_DOC to 6, EvalFault.UNRELATED to 13, EvalFault.SAD to 10,
    )
  }
}
