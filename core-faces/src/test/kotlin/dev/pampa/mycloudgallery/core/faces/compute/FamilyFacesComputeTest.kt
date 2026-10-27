package dev.pampa.mycloudgallery.core.faces.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.FacesScope
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La tabella "UNA VOLTA PER ACCOUNT" del §2.3 (il "Fatto quando" di P4), scenario per scenario, con due account (Anna, Bruno) e i loro
 * dispositivi sul NAS finto ([SimFacesNas]): le prenotazioni sono sotto la generazione, la replica dei dati dei volti porta a un dispositivo i
 * corpi degli altri **dello stesso account** e di nessun altro.
 */
class FamilyFacesComputeTest {
  private val forever = SimWorld.FOREVER

  /** Riga 1: foto nuova dal backup nell'area privata; il telefono che ha caricato ha gia' l'anteprima nel buffer; la ricevono i dispositivi dell'account. */
  @Test
  fun riga1_fotoNuovaDalBackupNellAreaPrivata() = runTest {
    val w = SimWorld()
    val key = w.nas.add(w.anna.privateShare, faces = listOf(Faces.big(1), Faces.big(2, x = 0.55f)))
    val phone = w.device(w.anna, "telefono-anna")
    phone.buffer += key

    val results = phone.engine.runAll(forever)

    assertThat(results).hasSize(3) // privata, comune, telefono
    assertThat(results.first().second.analyzed).isEqualTo(1)
    assertThat(results.first().second.faces).isEqualTo(2)
    assertThat(w.nas.previewBytesRead).isEqualTo(0L) // nessuna anteprima dal NAS
    assertThat(w.published(w.anna, "telefono-anna")).containsExactly(key)
    assertThat(w.nas.leases).isEmpty()

    // Il tablet dello stesso account: la replica gli porta il corpo, non c'e' niente da rifare.
    val tablet = w.device(w.anna, "tablet-anna")
    tablet.replicate()
    tablet.engine.runAll(forever)
    assertThat(tablet.ticking.detects).isEqualTo(0)
    assertThat(tablet.inputs.previewCalls).isEqualTo(0)
    assertThat(tablet.analyzed()).containsExactly(key)

    // Il telefono di Bruno non vede niente dell'area privata di Anna, nemmeno se glielo si chiede.
    val bruno = w.device(w.bruno, "telefono-bruno")
    bruno.replicate()
    bruno.engine.runAll(forever)
    assertThat(bruno.ticking.detects).isEqualTo(0)
    assertThat(bruno.engine.run(FacesScope.Area(w.anna.privateShare, AreaKind.PRIVATE), forever).stop).isEqualTo(FacesStop.DONE)
    assertThat(bruno.engine.counters.foreignScopes.get()).isEqualTo(1)
    assertThat(w.nas.writes.map { it.second }.toSet()).containsExactly(w.anna.generation)
  }

  /** Riga 2: foto dell'area comune; **ogni account** che ha attivato Persone la calcola per se', e la riceve solo il suo account. */
  @Test
  fun riga2_fotoDellAreaComune_ogniAccountPerSe() = runTest {
    val w = SimWorld()
    val keys = (1..4).map { w.nas.add(w.nas.commonShare, faces = listOf(Faces.big(it)), month = "2024-0$it") }
    val anna = w.device(w.anna, "telefono-anna")
    val bruno = w.device(w.bruno, "telefono-bruno")

    anna.engine.runAll(forever)
    bruno.engine.runAll(forever)

    assertThat(anna.ticking.detects).isEqualTo(4)
    assertThat(bruno.ticking.detects).isEqualTo(4) // lo stesso lavoro, rifatto per sé
    assertThat(w.published(w.anna, "telefono-anna")).containsExactlyElementsIn(keys)
    assertThat(w.published(w.bruno, "telefono-bruno")).containsExactlyElementsIn(keys)
    // Le prenotazioni di Anna e di Bruno sono in generazioni diverse: non si vedono, non si contendono.
    assertThat(anna.leases.busy + bruno.leases.busy).isEqualTo(0)

    val tablet = w.device(w.anna, "tablet-anna")
    tablet.replicate()
    tablet.engine.runAll(forever)
    assertThat(tablet.ticking.detects).isEqualTo(0)
    assertThat(tablet.rawArchive.winners(keys).values.map { it.author }.toSet()).containsExactly("telefono-anna")
  }

  /** Riga 3: due indicizzatori dello stesso account; le prenotazioni (sotto la generazione) dividono le unita': nessun media si calcola due volte. */
  @Test
  fun riga3_dueIndicizzatoriDelloStessoAccount() = runTest {
    val w = SimWorld()
    val keys = (1..30).map { w.nas.add(w.nas.commonShare, faces = listOf(Faces.big(it % 7 + 1)), month = "2024-0${1 + it % 3}") }
    val phone = w.device(w.anna, "telefono-anna")
    val tablet = w.device(w.anna, "tablet-anna")
    phone.restart(FacesComputeConfig(unitSize = 10))
    tablet.restart(FacesComputeConfig(unitSize = 10))

    val a = launch { phone.engine.runAll(forever) }
    val b = launch { tablet.engine.runAll(forever) }
    a.join()
    b.join()

    assertThat(phone.ticking.detects + tablet.ticking.detects).isEqualTo(30)
    assertThat(phone.ticking.detects).isGreaterThan(0)
    assertThat(tablet.ticking.detects).isGreaterThan(0)
    val all = w.published(w.anna, "telefono-anna") + w.published(w.anna, "tablet-anna")
    assertThat(all).containsExactlyElementsIn(keys)
    assertThat(phone.leases.busy + tablet.leases.busy).isGreaterThan(0) // si sono davvero incontrati
    assertThat(w.nas.leases).isEmpty() // tutte lasciate a fine unita'
  }

  /** Riga 3 bis: un'unita' finita da un altro dispositivo e rilasciata non si rifa': preso il lease gia' tenuto da altri si aggiornano i dati e non resta niente. */
  @Test
  fun riga3bis_unUnitaFinitaDaAltriNonSiRifa() = runTest {
    val w = SimWorld()
    val keys = (1..6).map { w.nas.add(w.nas.commonShare, faces = listOf(Faces.big(it)), month = "2024-0${1 + it % 2}") }
    val phone = w.device(w.anna, "telefono-anna")
    val tablet = w.device(w.anna, "tablet-anna")
    phone.engine.runAll(forever)
    assertThat(phone.ticking.detects).isEqualTo(6)

    // Il tablet non ha ancora sincronizzato: vede tutto da fare, ma le prenotazioni gli dicono che altri le hanno tenute.
    tablet.engine.runAll(forever)

    assertThat(tablet.ticking.detects).isEqualTo(0)
    // Preso il lease della prima unita' (gia' tenuto da altri) si aggiornano i dati: la seconda unita' risulta gia' fatta e nemmeno si prenota.
    assertThat(tablet.engine.counters.refreshes.get()).isEqualTo(1)
    assertThat(tablet.leases.acquired).isEqualTo(1)
    assertThat(tablet.analyzed()).containsExactlyElementsIn(keys)
    assertThat(w.published(w.anna, "tablet-anna")).isEmpty()
  }

  /** Riga 4: lo stesso contenuto in area privata e comune si calcola una volta sola (chiave di contenuto) e si pubblica una volta sola. */
  @Test
  fun riga4_stessoContenutoInAreaPrivataEComune() = runTest {
    val w = SimWorld()
    val scene = w.nas.makeScene(listOf(Faces.big(3)))
    val key = w.nas.add(w.anna.privateShare, scene = scene)
    w.nas.add(w.nas.commonShare, scene = scene, key = key)
    val phone = w.device(w.anna, "telefono-anna")

    phone.engine.runAll(forever)

    assertThat(phone.ticking.detects).isEqualTo(1)
    assertThat(phone.archive.perKey[key]).isEqualTo(1)
    assertThat(w.published(w.anna, "telefono-anna")).containsExactly(key)
    assertThat(phone.publisher.published).isEqualTo(1)
    assertThat(phone.engine.counters.restaged.get()).isEqualTo(0)
  }

  /** Riga 5: un media solo sul telefono di un indicizzatore: lui, dai byte del telefono; risultati solo locali; poi, sul NAS, si pubblicano senza ricalcolo. */
  @Test
  fun riga5_mediaSoloSulTelefonoDiUnIndicizzatore() = runTest {
    val w = SimWorld()
    val phone = w.device(w.anna, "telefono-anna")
    val media = w.nas.phoneMedia(listOf(Faces.big(1)))
    phone.phone[media.key] = media

    val first = phone.engine.runAll(forever)

    assertThat(first.last().first).isEqualTo(FacesScope.PhoneOnly)
    assertThat(first.last().second.analyzed).isEqualTo(1)
    assertThat(phone.ticking.detects).isEqualTo(1)
    assertThat(phone.analyzed()).containsExactly(media.key)
    // Solo locale: niente prenotazioni, niente in coda, niente sul NAS.
    assertThat(phone.leases.calls).isEqualTo(0)
    assertThat(phone.publisher.stageCalls).isEqualTo(0)
    assertThat(phone.publisher.flushCalls).isEqualTo(0)
    assertThat(w.nas.published).isEmpty()
    assertThat(w.nas.writes).isEmpty()
    // Il raggruppamento locale c'e' (assegnazione provvisoria, D21).
    assertThat(phone.grouping.incrementals).isEqualTo(1)

    // Il backup lo porta sul NAS: si pubblica senza ricalcolare.
    w.nas.moveToNas(media, w.anna.privateShare)
    phone.phone.remove(media.key)
    phone.engine.runAll(forever)
    assertThat(phone.ticking.detects).isEqualTo(1)
    assertThat(w.published(w.anna, "telefono-anna")).containsExactly(media.key)
    assertThat(phone.engine.counters.restaged.get()).isEqualTo(1)
  }

  /** Riga 6: un media solo sul telefono di un NON indicizzatore: nessuno lo calcola finche' non e' sul NAS; non si apre niente. */
  @Test
  fun riga6_mediaSoloSulTelefonoDiUnNonIndicizzatore() = runTest {
    val w = SimWorld()
    val tablet = w.device(w.anna, "tablet-anna", indexer = false)
    val media = w.nas.phoneMedia(listOf(Faces.big(1)))
    tablet.phone[media.key] = media

    val results = tablet.engine.runAll(forever)

    assertThat(results.single().second.stop).isEqualTo(FacesStop.NOT_INDEXER)
    assertThat(tablet.ticking.detects).isEqualTo(0)
    assertThat(tablet.inputs.previewCalls).isEqualTo(0)
    assertThat(tablet.opens).isEqualTo(0)
    assertThat(tablet.analyzed()).isEmpty()
  }

  /** Riga 7: share vere con la recinzione (D34): ogni indicizzatore per se', tutto locale, niente sul NAS (ne' prenotazioni ne' pubblicazioni). */
  @Test
  fun riga7_shareVereConLaRecinzione_tuttoLocale() = runTest {
    val w = SimWorld()
    val keys = (1..4).map { w.nas.add(w.nas.commonShare, faces = listOf(Faces.big(it)), month = "2024-0$it") }
    val phone = w.device(w.anna, "telefono-anna", here = HereState.LOCAL_ONLY)
    val tablet = w.device(w.anna, "tablet-anna", here = HereState.LOCAL_ONLY)

    phone.engine.runAll(forever)
    tablet.engine.runAll(forever)

    assertThat(phone.ticking.detects).isEqualTo(4)
    assertThat(tablet.ticking.detects).isEqualTo(4) // ognuno per sé: senza NAS non si dividono il lavoro
    assertThat(phone.analyzed()).containsExactlyElementsIn(keys)
    for (device in listOf(phone, tablet)) {
      assertThat(device.leases.calls).isEqualTo(0)
      assertThat(device.publisher.flushCalls).isEqualTo(0)
      // Pronte per quando la sincronizzazione si accendera' (Q5): in coda, non pubblicate.
      assertThat(device.publisher.staged).containsExactlyElementsIn(keys)
    }
    assertThat(w.nas.leases).isEmpty()
    assertThat(w.nas.published).isEmpty()
    assertThat(w.nas.writes).isEmpty()
  }

  /** Riga 8: un account senza indicizzatori: nessuno calcola; il lavoro resta in attesa e non si perde niente (E-F10). */
  @Test
  fun riga8_accountSenzaIndicizzatori() = runTest {
    val w = SimWorld()
    w.nas.add(w.anna.privateShare, faces = listOf(Faces.big(1)))
    val phone = w.device(w.anna, "telefono-anna", indexer = false)
    val tablet = w.device(w.anna, "tablet-anna", indexer = false)

    phone.engine.runAll(forever)
    tablet.engine.runAll(forever)

    assertThat(phone.ticking.detects + tablet.ticking.detects).isEqualTo(0)
    assertThat(w.nas.published).isEmpty()
    assertThat(w.nas.leases).isEmpty()

    // Appena un dispositivo diventa indicizzatore il lavoro parte.
    tablet.requirements.indexers = tablet.requirements.indexers.copy(thisDevice = true)
    assertThat(tablet.engine.runAll(forever).first().second.analyzed).isEqualTo(1)
  }

  /** D4, E-F7, E-F8: in attesa dell'avviso, esclusi, spenti o con una voce sconosciuta (E-F12) niente si calcola, niente si apre, niente si tocca. */
  @Test
  fun senzaLAttivazioneQuiNienteSiTocca() = runTest {
    val w = SimWorld()
    w.nas.add(w.anna.privateShare, faces = listOf(Faces.big(1)))
    val phone = w.device(w.anna, "telefono-anna")
    val g = w.anna.generation
    val states = listOf(
      FacesState.AwaitingNotice(g, 1), FacesState.On(g, HereState.EXCLUDED), FacesState.Off, FacesState.NeedsUpdate, FacesState.Erasing(g),
    )
    for (state in states) {
      phone.switch.state.value = state
      val result = phone.engine.run(phone.area(AreaKind.PRIVATE), forever)
      assertThat(result.stop).isIn(listOf(FacesStop.NOT_ACTIVE, FacesStop.ERASING))
    }
    assertThat(phone.opens).isEqualTo(0)
    assertThat(phone.inputs.previewCalls).isEqualTo(0)
    assertThat(phone.leases.calls).isEqualTo(0)
    assertThat(phone.publisher.stageCalls + phone.publisher.flushCalls).isEqualTo(0)
    assertThat(phone.ticking.detects).isEqualTo(0)
  }
}
