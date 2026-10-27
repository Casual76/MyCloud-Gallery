package dev.pampa.mycloudgallery.core.faces.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublishState
import dev.pampa.mycloudgallery.core.faces.sync.FacesReplicaState
import dev.pampa.mycloudgallery.core.faces.sync.FacesSyncWorld
import dev.pampa.mycloudgallery.core.faces.sync.SyncStateSamples
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Fase 8 (P6, JVM, Robolectric; il dispositivo si misura sul campo): quanto costano la replica e il pubblicatore sul database. Il pubblicatore
 * salva a ogni modifica in coda (P4 ne mette una per media) e la replica a ogni dispositivo letto: [save] scrive **solo la differenza** e lo
 * stato si tiene in memoria, quindi un salvataggio costa quanto il suo cambiamento e non quanto lo stato intero; [load] non decodifica niente
 * dopo la prima volta. I tempi vanno in `docs/decisioni.md`.
 */
@RunWith(AndroidJUnit4::class)
class RoomFacesSyncPerformanceTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val world by lazy { StoreWorld(File(folder.root, "faces")) }
  private val generation = FaceGeneration.fromBytes(ByteArray(16) { 51 })

  /** Quanto dura [block], in millisecondi con i decimali (un salvataggio dura meno di un millisecondo). */
  private fun millis(block: suspend () -> Unit): Double {
    val started = System.nanoTime()
    runBlocking { block() }
    return (System.nanoTime() - started) / 1_000_000.0
  }

  @Test
  fun ilPubblicatoreSalvaOgniModificaInPocoTempoAncheConTremilaChiavi() = runBlocking<Unit> {
    val store = world.ready(generation)
    var state = FacesPublishState()
    val times = ArrayList<Double>()
    for (n in 1..3_000) {
      state = state.copy(keys = state.keys + FacesSyncWorld.key(n), pending = state.pending + 1)
      val next = state
      times += millis { store.publishStore.save(generation, next) }
    }
    val first = times.take(100).average()
    val last = times.takeLast(100).average()
    println("[sync] pubblicatore: 3.000 salvataggi di una chiave in piu': i primi 100 in media ${"%.2f".format(first)} ms, gli ultimi 100 ${"%.2f".format(last)} ms")
    assertWithMessage("ultimi 100 salvataggi: $last ms in media").that(last).isLessThan(50.0)
    // Un salvataggio che cambia solo gli scalari (la modifica in coda) non scrive le chiavi.
    val scalars = (1..50).map { n ->
      state = state.copy(pending = 3_000 + n)
      val next = state
      millis { store.publishStore.save(generation, next) }
    }.average()
    println("[sync] pubblicatore: salvataggio dei soli scalari con 3.000 chiavi: ${"%.2f".format(scalars)} ms in media")
    assertWithMessage("solo gli scalari: $scalars ms").that(scalars).isLessThan(25.0)
    store.close()
    val back = world.ready(generation).publishStore.load(generation)
    assertThat(back.keys).hasSize(3_000)
    assertThat(back.pending).isEqualTo(3_050)
  }

  @Test
  fun laReplicaConUnaIstantaneaGrandeSiSalvaSiRileggeESiAggiornaInPocoTempo() = runBlocking<Unit> {
    val store = world.ready(generation)
    // 3.000 persone con il centroide e 30.000 assegnazioni nell'istantanea di un dispositivo, due dispositivi piccoli accanto.
    val big = SyncStateSamples.deviceWithSnapshot(seed = 3, persons = 3_000, perPerson = 10)
    val state = FacesReplicaState(mapOf("dev-big" to big, "dev-small" to SyncStateSamples.deviceInProgress(4), "dev-other" to SyncStateSamples.deviceWithSnapshot(5)), setOf("dev-x"))
    val firstSave = millis { store.replicaStore.save(generation, state) }
    // Un giro che aggiorna un dispositivo piccolo: l'istantanea grande non si riscrive.
    val small = state.devices.getValue("dev-small")
    var current = state
    val updates = (1..30).map { n ->
      current = current.copy(devices = current.devices + ("dev-small" to small.copy(mark = HeadMark(100L + n, "testa-$n"))))
      val next = current
      millis { store.replicaStore.save(generation, next) }
    }.average()
    store.close()
    val reopened = world.ready(generation)
    var loaded: FacesReplicaState? = null
    val coldLoad = millis { loaded = reopened.replicaStore.load(generation) }
    val warmLoad = millis { reopened.replicaStore.load(generation) }
    println("[sync] replica: istantanea da 3.000 persone e 30.000 assegnazioni: primo salvataggio ${"%.0f".format(firstSave)} ms, lettura a freddo ${"%.0f".format(coldLoad)} ms, a caldo ${"%.2f".format(warmLoad)} ms, aggiornare un altro dispositivo ${"%.2f".format(updates)} ms in media")
    assertThat(loaded!!.devices.getValue("dev-big").snapshot!!.persons).hasSize(3_000)
    assertThat(loaded!!.devices.getValue("dev-big").snapshot!!.assignments).hasSize(30_000)
    assertWithMessage("aggiornare un altro dispositivo: $updates ms").that(updates).isLessThan(50.0)
    assertWithMessage("lettura a caldo: $warmLoad ms").that(warmLoad).isLessThan(20.0)
    assertWithMessage("primo salvataggio: $firstSave ms").that(firstSave).isLessThan(30_000.0)
    assertWithMessage("lettura a freddo: $coldLoad ms").that(coldLoad).isLessThan(30_000.0)
    reopened.close()
  }
}
