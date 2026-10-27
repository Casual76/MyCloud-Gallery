package dev.pampa.mycloudgallery.core.faces.compute

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ripresa e guasti (§4.3 "ogni passo in piu' tappe", §5.5 invarianti 1, 2, 3, 7 e 8, D16, E-A13, E-I2): la **morte del processo dopo ogni
 * salvataggio** e il `FaultPlan` a **ogni chiamata** al NAS (prima e dopo l'effetto) non fanno rifare nessun media gia' salvato, e il lavoro
 * arriva in fondo; e la generazione che cambia a meta' ferma la tappa senza salvare ne' pubblicare altro.
 */
class ResumeAndFaultsTest {
  private val forever = SimWorld.FOREVER
  private val config = FacesComputeConfig(unitSize = 3, batch = 2, reducedBatch = 1)

  private class World(val w: SimWorld, val phone: SimDevice, val expected: Set<ContentKey>)

  /** Sette media da analizzare in quattro unita' da 3 (con un HQ, un video e uno screenshot che si salta). */
  private fun world(cfg: FacesComputeConfig = config): World {
    val w = SimWorld()
    val p = w.anna.privateShare
    val keys = LinkedHashSet<ContentKey>()
    keys += w.nas.add(p, faces = listOf(Faces.big(1)), month = "2024-02")
    keys += w.nas.add(p, faces = listOf(Faces.small(2)), month = "2024-02")
    keys += w.nas.add(p, faces = Faces.group(5), month = "2024-02")
    keys += w.nas.add(p, faces = (0..2).map { Faces.big(3, frame = it) }, kind = MediaKind.VIDEO, month = "2024-02")
    keys += w.nas.add(p, faces = listOf(Faces.big(4)), month = "2024-01")
    keys += w.nas.add(p, faces = listOf(Faces.big(5), Faces.big(6, x = 0.55f)), month = "2024-01")
    keys += w.nas.add(p, faces = listOf(Faces.big(7)), month = "2024-01")
    w.nas.add(p, source = MediaSource.SCREENSHOT, month = "2024-01")
    val phone = w.device(w.anna, "telefono-anna")
    phone.restart(cfg)
    phone.inputs.prefetching = true
    phone.publisher.faultyStage = true
    return World(w, phone, keys)
  }

  /** Esegue tappe (ripartendo dopo ogni morte) finche' tutto e' pubblicato. Un `IOException` che esce dal motore e' un difetto: la prova cade. */
  private suspend fun untilDone(world: World, cfg: FacesComputeConfig = config, rounds: Int = 40, label: String = "") {
    repeat(rounds) {
      try {
        world.phone.engine.runAll(forever)
        val published = world.w.nas.publishedBy(world.w.anna.generation, world.phone.id).keys
        if (published.containsAll(world.expected) && world.phone.publisher.staged.isEmpty()) return
      } catch (e: ProcessDeath) {
        world.phone.restart(cfg)
      }
    }
    val published = world.w.nas.publishedBy(world.w.anna.generation, world.phone.id).keys
    error("il lavoro non arriva in fondo ($label): pubblicati ${published.size}/${world.expected.size}, in coda ${world.phone.publisher.staged.size}")
  }

  private fun assertNothingRedoneAndAllPublished(world: World) {
    val saved = world.phone.archive.perKey
    assertThat(saved.keys).containsExactlyElementsIn(world.expected)
    assertWithMessage("un media gia' salvato e' stato rifatto").that(saved.values.toSet()).containsExactly(1)
    assertThat(world.phone.archive.unsupported).isEmpty()
    assertThat(world.w.nas.publishedBy(world.w.anna.generation, world.phone.id).keys).containsAtLeastElementsIn(world.expected)
  }

  @Test
  fun morteDelProcessoDopoOgniSalvataggio_nessunMediaRifatto() = runTest {
    for (n in 1..7) {
      val world = world()
      world.phone.archive.dieAfterSave = n
      untilDone(world)
      assertNothingRedoneAndAllPublished(world)
      // I media morti prima del salvataggio si rifanno (non erano salvati): al piu' uno per morte, mai uno salvato.
      assertThat(world.phone.archive.saves).isEqualTo(7)
    }
  }

  @Test
  fun guastoAOgniChiamataDelNas_primaEDopo() = runTest {
    val clean = world()
    untilDone(clean)
    val calls = clean.w.nas.faults.calls
    assertThat(calls).isGreaterThan(10)
    println("FaultPlan: $calls chiamate al NAS per un giro pulito, ${calls * 2} giri con un guasto")
    for (after in listOf(false, true)) {
      for (at in 1..calls) {
        val world = world()
        world.w.nas.faults.at = at
        world.w.nas.faults.after = after
        untilDone(world, label = "guasto $at dopo=$after")
        assertWithMessage("il guasto $at (dopo=$after) non e' scattato").that(world.w.nas.faults.tripped).isTrue()
        assertNothingRedoneAndAllPublished(world)
      }
    }
  }

  /** Morte E guasto insieme: il guasto del NAS numero k con la morte dopo il salvataggio n. */
  @Test
  fun morteEGuastoInsieme() = runTest {
    for (n in listOf(2, 5)) {
      for (at in listOf(1, 4, 9, 14)) {
        val world = world()
        world.phone.archive.dieAfterSave = n
        world.w.nas.faults.at = at
        world.w.nas.faults.after = at % 2 == 0
        untilDone(world)
        assertNothingRedoneAndAllPublished(world)
      }
    }
  }

  /** E-I2: chi muore tiene l'unita' fino alla scadenza (1 ora d'ora del NAS); poi un altro dispositivo dello stesso account la prende. */
  @Test
  fun unIndicizzatoreMortoLiberaLUnitaEntroLOra() = runTest {
    val w = SimWorld()
    val keys = (1..4).map { w.nas.add(w.anna.privateShare, faces = listOf(Faces.big(it))) }
    val phone = w.device(w.anna, "telefono-anna")
    val tablet = w.device(w.anna, "tablet-anna")
    phone.archive.dieAfterSave = 2
    assertThat(runCatching { phone.engine.runAll(forever) }.exceptionOrNull()).isInstanceOf(ProcessDeath::class.java)

    // Il tablet trova l'unita' prenotata (con i soli 2 media gia' pubblicati? no: morto prima di pubblicare): aspetta la scadenza.
    tablet.replicate()
    tablet.engine.runAll(forever)
    assertThat(tablet.ticking.detects).isEqualTo(0)
    assertThat(tablet.leases.busy).isEqualTo(1)

    w.nas.now += 60L * 60 * 1000 + 1
    tablet.engine.runAll(forever)
    assertThat(w.published(w.anna, "tablet-anna")).containsExactlyElementsIn(keys)
    // Il telefono, ripartito, non rifa' i due che aveva salvato: ricevono dal NAS (la replica) cio' che ha fatto il tablet.
    phone.restart()
    phone.replicate()
    phone.engine.runAll(forever)
    assertThat(phone.archive.perKey.values.toSet()).containsExactly(1)
  }

  // ---- La generazione che cambia a meta' (D3, D30, §2.8) -----------------------------------------------------------------------------------

  private class Change(val label: String, val stop: FacesStop, val apply: (SimDevice, FaceGeneration) -> Unit)

  @Test
  fun laGenerazioneCambiaAMeta_laTappaSiFermaSenzaSalvareNePubblicare() = runTest {
    val other = FaceGeneration.fromBytes(ByteArray(16) { 99 })
    val changes = listOf(
      Change("attivazione nuova", FacesStop.GENERATION_CHANGED) { d, _ -> d.switch.state.value = FacesState.On(other, HereState.ACTIVE) },
      Change("avviso di una generazione nuova", FacesStop.GENERATION_CHANGED) { d, _ -> d.switch.state.value = FacesState.AwaitingNotice(other, 1) },
      Change("spenta", FacesStop.NOT_ACTIVE) { d, _ -> d.switch.state.value = FacesState.Off },
      Change("esclusione", FacesStop.NOT_ACTIVE) { d, g -> d.switch.state.value = FacesState.On(g, HereState.EXCLUDED) },
      Change("cancellazione", FacesStop.ERASING) { d, g -> d.switch.state.value = FacesState.Erasing(g) },
      Change("cancellazione nel diario locale", FacesStop.ERASING) { d, g -> runBlocking { d.localStore.update { it.copy(erasing = g) } } },
      Change("dati locali di un'altra generazione", FacesStop.GENERATION_CHANGED) { d, _ -> runBlocking { d.localStore.update { it.copy(dataGeneration = other) } } },
    )
    for (change in changes) {
      val world = world(FacesComputeConfig(unitSize = 10, batch = 2, reducedBatch = 1))
      val phone = world.phone
      // Il quarto rilevamento e' il primo del terzo media (il secondo ne fa due: anteprima e HQ): i primi due sono salvati, il terzo cambia sotto le mani.
      phone.ticking.onDetect = { n -> if (n == 4) change.apply(phone, phone.generation) }

      val r = phone.engine.run(phone.area(AreaKind.PRIVATE), forever)

      assertWithMessage(change.label).that(r.stop).isEqualTo(change.stop)
      // I due media finiti prima del cambio sono salvati; il terzo, calcolato mentre cambiava, no. Poi niente: ne' coda, ne' NAS, ne' segno.
      assertWithMessage(change.label).that(phone.archive.saves).isEqualTo(2)
      assertWithMessage(change.label).that(phone.publisher.flushCalls).isEqualTo(0)
      assertWithMessage(change.label).that(phone.publisher.stageCalls).isEqualTo(2)
      assertWithMessage(change.label).that(phone.grouping.incrementals).isEqualTo(0)
      assertWithMessage(change.label).that(phone.units.marks).isEqualTo(0)
      assertWithMessage(change.label).that(phone.leases.released).isEqualTo(0)
      assertWithMessage(change.label).that(world.w.nas.published).isEmpty()
    }
  }

  /** La generazione che cambia fra due lotti: il lotto dopo non parte nemmeno (le regole non sono neppure chieste). */
  @Test
  fun laGenerazioneCambiaFraDueLotti() = runTest {
    val world = world(FacesComputeConfig(unitSize = 10, batch = 2, reducedBatch = 1))
    val phone = world.phone
    phone.control.onAsk = { n -> if (n == 2) phone.switch.state.value = FacesState.Off }
    val r = phone.engine.run(phone.area(AreaKind.PRIVATE), forever)
    assertThat(r.stop).isEqualTo(FacesStop.NOT_ACTIVE)
    assertThat(r.analyzed).isEqualTo(2)
    assertThat(phone.control.asked).isEqualTo(2)
    assertThat(phone.publisher.flushCalls).isEqualTo(0)
  }

  /** L'archivio chiuso a meta' (chiave distrutta, generazione cambiata): niente piu' salvato, niente pubblicato, nessun crollo. */
  @Test
  fun archivioChiusoAMeta() = runTest {
    val world = world(FacesComputeConfig(unitSize = 10, batch = 2, reducedBatch = 1))
    val phone = world.phone
    phone.ticking.onDetect = { n -> if (n == 2) phone.rawArchive.close() }
    val r = phone.engine.run(phone.area(AreaKind.PRIVATE), forever)
    assertThat(r.stop).isEqualTo(FacesStop.GENERATION_CHANGED)
    assertThat(phone.archive.saves).isEqualTo(1)
    assertThat(phone.publisher.flushCalls).isEqualTo(0)
    assertThat(phone.grouping.incrementals).isEqualTo(0)
    assertThat(phone.engine.status.value).isEqualTo(FacesStatus.Off)
  }

  // ---- Prenotazioni, ruolo, database, tempo --------------------------------------------------------------------------------------------------

  /** Prenotazione persa al rinnovo (un altro l'ha presa): si pubblica quel che e' fatto, si lascia l'unita' e si passa oltre (E-I2). */
  @Test
  fun prenotazionePersaAlRinnovo() = runTest {
    val world = world(FacesComputeConfig(unitSize = 10, batch = 2, reducedBatch = 1, keepAliveEvery = 1.nanoseconds))
    val phone = world.phone
    phone.control.onAsk = { n ->
      if (n == 2) world.w.nas.leases.keys.toList().forEach { world.w.nas.leases[it] = "tablet-anna" to world.w.nas.now + 3_600_000 }
    }

    val r = phone.engine.run(phone.area(AreaKind.PRIVATE), forever)

    assertThat(phone.engine.counters.unitsLost.get()).isEqualTo(1)
    // Dell'unita' persa restano salvati i due media del primo lotto; si passa all'unita' dopo (tre media) e si fa per intero.
    assertThat(r.stop).isEqualTo(FacesStop.DONE)
    assertThat(r.analyzed).isEqualTo(2 + 3)
    assertThat(phone.archive.perKey.values.toSet()).containsExactly(1)
    assertThat(world.w.nas.publishedBy(world.w.anna.generation, phone.id)).hasSize(5) // quel che era fatto si pubblica
    assertThat(phone.units.marks).isEqualTo(1) // solo l'unita' dopo e' finita
    assertThat(phone.leases.released).isEqualTo(1) // quella persa non e' piu' sua
  }

  /** E-A13: il ruolo di indicizzatore si spegne a meta' unita': ci si ferma al lotto dopo, il fatto si pubblica, la prenotazione si lascia. */
  @Test
  fun ruoloSpentoAMetaUnita() = runTest {
    val world = world(FacesComputeConfig(unitSize = 10, batch = 2, reducedBatch = 1))
    val phone = world.phone
    phone.control.onAsk = { n -> if (n == 1) phone.requirements.indexers = phone.requirements.indexers.copy(thisDevice = false) }

    val r = phone.engine.run(phone.area(AreaKind.PRIVATE), forever)

    assertThat(r.stop).isEqualTo(FacesStop.NOT_INDEXER)
    assertThat(r.analyzed).isEqualTo(2)
    assertThat(world.w.nas.publishedBy(world.w.anna.generation, phone.id)).hasSize(2)
    assertThat(phone.leases.released).isEqualTo(1)
    assertThat(world.w.nas.leases).isEmpty()
    assertThat(phone.engine.status.value).isEqualTo(FacesStatus.Waiting(FacesWait.NOT_INDEXER))
  }

  /** P6: un errore del database a meta' di un salvataggio (disco pieno) e' un errore del media: resta da fare; due di fila fermano la tappa. */
  @Test
  fun erroreDelDatabaseASalvataggio() = runTest {
    val world = world(FacesComputeConfig(unitSize = 10, batch = 20))
    val phone = world.phone
    phone.archive.failSaves = 1
    val first = phone.engine.run(phone.area(AreaKind.PRIVATE), forever)
    assertThat(first.analyzed).isEqualTo(6) // sette meno quello che non si e' salvato
    assertThat(phone.engine.counters.storeFailures.get()).isEqualTo(1)
    assertThat(phone.units.marks).isEqualTo(1) // l'unita' del media che non si e' salvato non e' finita, l'altra si'

    val second = phone.engine.run(phone.area(AreaKind.PRIVATE), forever)
    assertThat(second.analyzed).isEqualTo(1)
    assertThat(phone.archive.perKey.values.toSet()).containsExactly(1)

    val full = world(FacesComputeConfig(unitSize = 10, batch = 20))
    full.phone.archive.failSaves = 2
    val stopped = full.phone.engine.run(full.phone.area(AreaKind.PRIVATE), forever)
    assertThat(stopped.stop).isEqualTo(FacesStop.STORAGE_LOW)
    assertThat(stopped.analyzed).isEqualTo(0)
    assertThat(full.phone.engine.status.value).isEqualTo(FacesStatus.Waiting(FacesWait.STORAGE))
  }

  /** Il tempo della tappa (8 minuti di `StagedRun`): finito, si pubblica il fatto, si lascia l'unita' e si riprende dal media dopo senza rifare niente. */
  @Test
  fun tempoFinito_siRiprendeDalMediaDopo() = runTest {
    val world = world(FacesComputeConfig(unitSize = 10, batch = 20))
    val phone = world.phone

    val first = phone.engine.run(phone.area(AreaKind.PRIVATE), 100.milliseconds)

    assertThat(first.stop).isEqualTo(FacesStop.BUDGET)
    assertThat(first.analyzed).isIn(2..4)
    assertThat(phone.engine.status.value).isEqualTo(FacesStatus.Waiting(FacesWait.NEXT_STAGE))
    assertThat(world.w.nas.publishedBy(world.w.anna.generation, phone.id)).hasSize(first.analyzed)
    assertThat(phone.leases.released).isEqualTo(1)

    val second = phone.engine.run(phone.area(AreaKind.PRIVATE), forever)
    assertThat(second.stop).isEqualTo(FacesStop.DONE)
    assertThat(first.analyzed + second.analyzed).isEqualTo(7)
    assertThat(phone.archive.perKey.values.toSet()).containsExactly(1)
  }
}
