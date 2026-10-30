package dev.pampa.mycloudgallery.core.faces.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.FacesComputeLimits
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La stima dal LAVORO VERO (§2.3, §2.12, D10: "Analizzare le tue 12.000 foto richiede circa mezz'ora") e il buffer che tiene le anteprime chieste
 * (D17, [FacesBufferKeepers]): il backlog si conta con le stesse porte e le stesse regole del calcolo, i ritmi si misurano sul lavoro fatto.
 */
class EstimateAndKeepersTest {
  private val forever = SimWorld.FOREVER

  private class Case {
    val w = SimWorld()
    val phone = w.device(w.anna, "telefono-anna")
    val priv = w.anna.privateShare

    val backlog = FacesBacklog(phone.switch, phone.requirements, phone.catalog, { phone.rawArchive })

    suspend fun run() = phone.engine.run(phone.area(AreaKind.PRIVATE), SimWorld.FOREVER)
  }

  // ---- Il backlog ----------------------------------------------------------------------------------------------------------------------

  @Test
  fun ilBacklogContaIlLavoroVero_ConLeRegoleDelCalcolo() = runTest {
    val c = Case()
    val shared = c.w.nas.makeScene(listOf(Faces.big(1)))
    val sharedKey = c.w.nas.add(c.priv, scene = shared)
    c.w.nas.add(c.w.nas.commonShare, scene = shared, key = sharedKey) // lo stesso contenuto nelle due aree: una volta sola (D23)
    repeat(5) { c.w.nas.add(c.priv, month = "2024-0${1 + it % 3}") }
    repeat(2) { c.w.nas.add(c.priv, kind = MediaKind.VIDEO) }
    c.w.nas.add(c.priv, source = MediaSource.SCREENSHOT)
    c.w.nas.add(c.priv, document = true)
    c.w.nas.add(c.priv, cutout = true)
    c.w.nas.add(c.priv, kind = MediaKind.ANIMATED_IMAGE)
    c.w.nas.add(c.w.nas.commonShare)
    val onPhone = c.w.nas.phoneMedia()
    c.phone.phone[onPhone.key] = onPhone

    val before = c.backlog.count()
    assertThat(before).isEqualTo(FacesWork(photos = 1 + 5 + 1 + 1, videos = 2)) // la condivisa, 5 private, 1 comune, 1 del telefono

    c.run()
    assertThat(c.backlog.count()).isEqualTo(FacesWork(photos = 1 + 1, videos = 0)) // restano la comune e quella del telefono
    c.phone.engine.runAll(forever)
    assertThat(c.backlog.count()).isEqualTo(FacesWork.NONE)
  }

  @Test
  fun ilBacklogEZeroDoveNonSiCalcola() = runTest {
    val c = Case()
    c.w.nas.add(c.priv)
    assertThat(c.backlog.count().total).isEqualTo(1)

    c.phone.requirements.indexers = c.phone.requirements.indexers.copy(thisDevice = false)
    assertThat(c.backlog.count()).isEqualTo(FacesWork.NONE)
    c.phone.requirements.indexers = c.phone.requirements.indexers.copy(thisDevice = true)

    c.phone.switch.state.value = FacesState.On(c.phone.generation, HereState.EXCLUDED)
    assertThat(c.backlog.count()).isEqualTo(FacesWork.NONE)
    c.phone.switch.state.value = FacesState.Off
    assertThat(c.backlog.count()).isEqualTo(FacesWork.NONE)
    c.phone.switch.state.value = FacesState.On(c.phone.generation, HereState.ACTIVE)

    val noArchive = FacesBacklog(c.phone.switch, c.phone.requirements, c.phone.catalog, { null })
    assertThat(noArchive.count()).isEqualTo(FacesWork.NONE)
    c.phone.rawArchive.close()
    assertThat(c.backlog.count()).isEqualTo(FacesWork.NONE) // archivio chiuso: niente da contare, nessun crollo
  }

  // ---- I ritmi misurati ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun iRitmiNascono_dalLavoroVero() = runTest {
    val c = Case()
    val meter = c.phone.engine.rates
    assertThat(meter.samples()).isEqualTo(0)
    assertThat(meter.rates()).isEqualTo(FacesRates.DEFAULT)

    repeat(10) { c.w.nas.add(c.priv, faces = listOf(Faces.big(it % 9 + 1))) }
    c.run()

    // Ogni foto con un volto costa: rilevamento (40 ms) + un vettore (5 ms) sull'orologio monotono del dispositivo.
    val rates = meter.rates()
    assertThat(meter.samples()).isEqualTo(10)
    assertThat(rates.secondsPerPhoto).isWithin(1e-9).of(0.045)
    assertThat(rates.hqShare).isEqualTo(0.0) // nessuna seconda passata fin qui
    // 12.000 foto a 0,045 s: 9 minuti. I ritmi di partenza (la misura dell'S25, 8,8 foto al secondo, e il 20% con l'HQ da 344 ms: CA-2) ne dicono 37.
    val work = FacesWork(photos = 12_000, videos = 0)
    assertThat(FacesEstimate.minutes(work, rates)).isEqualTo(9)
    assertThat(FacesEstimate.minutes(work, FacesRates.DEFAULT)).isEqualTo(37)
  }

  @Test
  fun laSecondaPassataEIVideoSiMisuranoSeparatamente() = runTest {
    val c = Case()
    // Dal mese piu' recente: prima le foto semplici (stabiliscono il costo di una foto), poi quella con l'HQ, infine il video.
    repeat(4) { c.w.nas.add(c.priv, faces = listOf(Faces.big(it + 1)), month = "2024-0${6 - it}") }
    c.w.nas.add(c.priv, faces = listOf(Faces.small(5)), month = "2024-02") // seconda passata
    c.w.nas.add(c.priv, faces = (0..2).map { Faces.big(6, frame = it) }, kind = MediaKind.VIDEO, month = "2024-01")

    c.run()

    val rates = c.phone.engine.rates.rates()
    assertThat(rates.secondsPerPhoto).isWithin(1e-9).of(0.045)
    // La foto con l'HQ costa l'anteprima (40 ms) e l'HQ (40 ms) piu' un vettore (5 ms): 85 ms, cioe' 40 ms piu' di una foto semplice.
    assertThat(rates.secondsPerHq).isWithin(1e-9).of(0.040)
    assertThat(rates.hqShare).isWithin(1e-9).of(1.0 / 5.0)
    // Un video: tre fotogrammi da 40 ms e tre vettori da 5 ms.
    assertThat(rates.secondsPerVideo).isWithin(1e-9).of(0.135)
    assertThat(FacesEstimate.minutes(FacesWork.NONE, rates)).isEqualTo(0)
    assertThat(FacesEstimate.minutes(FacesWork(1, 0), rates)).isEqualTo(1) // almeno un minuto se c'e' lavoro
  }

  @Test
  fun ilLavoroNonMisuratoUsaLeIpotesi() {
    val meter = FacesRateMeter()
    meter.record(MediaKind.PHOTO, hqUsed = false, nanos = 100_000_000)
    val rates = meter.rates()
    assertThat(rates.secondsPerPhoto).isWithin(1e-9).of(0.1)
    assertThat(rates.secondsPerHq).isEqualTo(FacesRates.DEFAULT.secondsPerHq) // mai misurato: l'ipotesi
    assertThat(rates.secondsPerVideo).isEqualTo(FacesRates.DEFAULT.secondsPerVideo)
    meter.record(MediaKind.PHOTO, hqUsed = false, nanos = -5) // un tempo negativo non entra
    assertThat(meter.samples()).isEqualTo(1)
    assertThat(FacesComputeLimits.FRAMES_PER_VIDEO).isEqualTo(3)
  }

  // ---- Il buffer che tiene le anteprime chieste (D17) ---------------------------------------------------------------------------------------------

  private fun keepers(c: Case, lookup: FacesItemLookup? = null) = FacesBufferKeepers(
    c.phone.switch,
    c.phone.requirements,
    lookup ?: FacesItemLookup { keys -> keys.mapNotNull { c.w.nas.find(it)?.item(dev.pampa.mycloudgallery.core.faces.contract.FacesArea.PRIVATE) } },
    { c.phone.rawArchive },
  )

  @Test
  fun ilBufferTieneCioCheIVoltiChiedonoAncora() = runTest {
    val c = Case()
    val todo = c.w.nas.add(c.priv)
    val done = c.w.nas.add(c.priv, month = "2024-05")
    val screenshot = c.w.nas.add(c.priv, source = MediaSource.SCREENSHOT)
    val document = c.w.nas.add(c.priv, document = true)
    val cutout = c.w.nas.add(c.priv, cutout = true)
    val gif = c.w.nas.add(c.priv, kind = MediaKind.ANIMATED_IMAGE)
    val unknown = ContentKey("ee".repeat(32))
    c.run() // fa tutti quelli che si analizzano: todo e done
    // Un media nuovo, non ancora analizzato.
    val fresh = c.w.nas.add(c.priv, month = "2024-06")

    val asked = listOf(todo, done, screenshot, document, cutout, gif, unknown, fresh)
    val wanted = keepers(c).stillWanted(asked)

    assertThat(wanted).containsExactly(fresh) // solo cio' che si analizza e non e' ancora fatto
    assertThat(keepers(c).stillWanted(emptyList())).isEmpty()
    assertThat(keepers(c).stillWanted(listOf(fresh))).containsExactly(fresh)
  }

  @Test
  fun doveNonSiCalcolaNessunoTieneNiente() = runTest {
    val c = Case()
    val fresh = c.w.nas.add(c.priv)
    assertThat(keepers(c).stillWanted(listOf(fresh))).containsExactly(fresh)

    c.phone.switch.state.value = FacesState.On(c.phone.generation, HereState.EXCLUDED)
    assertThat(keepers(c).stillWanted(listOf(fresh))).isEmpty()
    c.phone.switch.state.value = FacesState.AwaitingNotice(c.phone.generation, 1)
    assertThat(keepers(c).stillWanted(listOf(fresh))).isEmpty()
    c.phone.switch.state.value = FacesState.Off
    assertThat(keepers(c).stillWanted(listOf(fresh))).isEmpty()
    c.phone.switch.state.value = FacesState.On(c.phone.generation, HereState.LOCAL_ONLY) // la recinzione non cambia cosa serve
    assertThat(keepers(c).stillWanted(listOf(fresh))).containsExactly(fresh)

    c.phone.requirements.indexers = c.phone.requirements.indexers.copy(thisDevice = false) // non calcola lui
    assertThat(keepers(c).stillWanted(listOf(fresh))).isEmpty()
    c.phone.requirements.indexers = c.phone.requirements.indexers.copy(thisDevice = true)

    val noArchive = FacesBufferKeepers(c.phone.switch, c.phone.requirements, { emptyList() }, { null })
    assertThat(noArchive.stillWanted(listOf(fresh))).isEmpty() // archivio non aperto: non lo apre lui
  }

  @Test
  fun unGuastoDelCatalogoOChiudeLArchivioNonFermaIlBuffer() = runTest {
    val c = Case()
    val fresh = c.w.nas.add(c.priv)
    assertThat(keepers(c, FacesItemLookup { throw IOException("catalogo non raggiungibile") }).stillWanted(listOf(fresh))).isEmpty()
    c.phone.rawArchive.close()
    assertThat(keepers(c).stillWanted(listOf(fresh))).isEmpty()
  }
}
