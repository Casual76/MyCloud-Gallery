package dev.pampa.mycloudgallery.core.faces.compute

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.model.ContentKey
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * O1 (Fase 8, correzione dopo il banco delle interruzioni, docs/prova-500.md Parte F): un errore del database che **non e' un `IOException`** (SQLite pieno o bloccato:
 * Room lo lancia come `SQLiteException`) nel segno di unita' finita, nella messa in coda del pubblicatore o nella pubblicazione non fa cadere la tappa del calcolo con
 * un'eccezione: si guarda come il salvataggio del corpo. Uno solo non ferma (la tappa dopo riparte da li'), due di fila fermano la tappa con `STORAGE_LOW`; niente si
 * perde e nessun media si rifa' (invariante 1 del §5.5): dopo la guarigione la ripresa, anche in un processo nuovo, rimette in coda cio' che manca e segna le unita'.
 */
class ErroriDelDatabaseTest {
  private val forever = SimWorld.FOREVER

  /** Sei media in tre unita' da due (stessa data: le parti sono 0, 1 e 2). */
  private class World(val w: SimWorld, val phone: SimDevice, val keys: Set<ContentKey>)

  private fun world(): World {
    val w = SimWorld()
    val keys = (1..6).map { w.nas.add(w.anna.privateShare, faces = listOf(Faces.big(it))) }.toSet()
    val phone = w.device(w.anna, "telefono-anna")
    phone.restart(FacesComputeConfig(unitSize = 2, batch = 2))
    return World(w, phone, keys)
  }

  private fun assertNothingRedoneAndAllPublished(world: World) {
    assertThat(world.phone.archive.perKey.keys).containsExactlyElementsIn(world.keys)
    assertWithMessage("un media gia' salvato e' stato rifatto").that(world.phone.archive.perKey.values.toSet()).containsExactly(1)
    assertThat(world.phone.ticking.detects).isEqualTo(world.keys.size)
    assertThat(world.w.published(world.w.anna, "telefono-anna")).containsExactlyElementsIn(world.keys)
    assertThat(world.phone.publisher.staged).isEmpty()
  }

  @Test
  fun unErroreDelDatabaseNelSegnoDiUnitaFinita_FermaLaTappaConIlSuoCodiceSenzaLanciare() = runTest {
    val world = world()
    world.phone.units.failMarks = 2

    val first = world.phone.engine.runAll(forever)

    // Due errori di fila nel segno di finita: la tappa si ferma con il suo codice (come per i corpi), la terza unita' non e' partita.
    assertThat(first.last().second.stop).isEqualTo(FacesStop.STORAGE_LOW)
    assertThat(world.phone.engine.counters.storeFailures.get()).isEqualTo(2)
    assertThat(world.phone.ticking.detects).isEqualTo(4)
    assertThat(world.phone.units.marks).isEqualTo(0)

    // Il database guarisce: la ripresa nello stesso processo segna le unita' e finisce, senza rifare niente.
    world.phone.engine.runAll(forever)
    assertThat(world.phone.units.marks).isEqualTo(3)
    assertNothingRedoneAndAllPublished(world)
  }

  @Test
  fun unSoloErroreNelSegnoDiUnitaFinita_NonFermaLaTappa_LaVisitaDopoLoRifa() = runTest {
    val world = world()
    world.phone.units.failMarks = 1

    val first = world.phone.engine.runAll(forever)

    assertThat(first.last().second.stop).isEqualTo(FacesStop.DONE)
    assertThat(world.phone.units.marks).isEqualTo(2)
    assertThat(world.phone.engine.counters.storeFailures.get()).isEqualTo(1)
    world.phone.engine.runAll(forever)
    assertThat(world.phone.units.marks).isEqualTo(3)
    assertNothingRedoneAndAllPublished(world)
  }

  @Test
  fun unErroreDelDatabaseNellaMessaInCoda_FermaLaTappaConIlSuoCodice_EUnProcessoNuovoRimetteInCodaSenzaRifareNiente() = runTest {
    val world = world()
    world.phone.publisher.failStage = 2

    val first = world.phone.engine.runAll(forever)

    assertThat(first.last().second.stop).isEqualTo(FacesStop.STORAGE_LOW)
    assertThat(world.phone.engine.counters.storeFailures.get()).isEqualTo(2)
    assertThat(world.phone.engine.counters.publishFailures.get()).isEqualTo(2)
    // I due corpi sono salvati (si e' fermata dopo il secondo), la terza unita' non e' partita.
    assertThat(world.phone.archive.perKey.keys).hasSize(2)
    assertThat(world.phone.units.marks).isEqualTo(0)

    // Il processo muore e riparte (la memoria si perde, il resto sta su disco): nessun media si rifa', i due corpi si rimettono in coda e il lavoro arriva in fondo.
    world.phone.restart(FacesComputeConfig(unitSize = 2, batch = 2))
    world.phone.engine.runAll(forever)
    assertThat(world.phone.engine.counters.restaged.get()).isEqualTo(2)
    assertNothingRedoneAndAllPublished(world)
    assertThat(world.phone.units.marks).isEqualTo(3)
  }

  @Test
  fun unErroreDelDatabaseNellaPubblicazione_NonFaCadereLaTappa_LAltraOccasioneAFineUnitaPubblica() = runTest {
    val world = world()
    world.phone.publisher.failFlush = 1

    val first = world.phone.engine.runAll(forever)

    assertThat(first.last().second.stop).isEqualTo(FacesStop.DONE)
    assertThat(world.phone.engine.counters.publishFailures.get()).isEqualTo(1)
    assertThat(world.phone.engine.counters.storeFailures.get()).isEqualTo(1)
    assertNothingRedoneAndAllPublished(world)
  }

  @Test
  fun dueErroriDiFilaNellaPubblicazioneAFineTappa_FermanoLaTappaConIlSuoCodice() = runTest {
    val world = world()
    // Il primo fallisce a fine della prima unita', il secondo a fine della seconda.
    world.phone.publisher.failFlush = 2

    val first = world.phone.engine.runAll(forever)

    assertThat(first.last().second.stop).isEqualTo(FacesStop.STORAGE_LOW)
    world.phone.engine.runAll(forever)
    assertNothingRedoneAndAllPublished(world)
  }
}
