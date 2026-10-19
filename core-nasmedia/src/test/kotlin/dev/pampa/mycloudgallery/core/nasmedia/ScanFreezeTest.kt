package dev.pampa.mycloudgallery.core.nasmedia

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.nasmedia.contract.MassChangeRule
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStatus
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanStop
import dev.pampa.mycloudgallery.core.nasmedia.contract.UserGesture
import dev.pampa.mycloudgallery.core.nasmedia.contract.headlineCount
import dev.pampa.mycloudgallery.core.nasmedia.freeze.StoreFreezeState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Il congelamento per cambiamenti di massa (D10, risposta Q6; C11; F9): spariti, cambiati o "non piu' media" ad almeno
 * `max(50, 5% dei file presenti)` fermano le assenze e le scritture automatiche; alla soglia si', sotto no.
 */
@RunWith(AndroidJUnit4::class)
class ScanFreezeTest {

  private lateinit var h: ScanHarness

  @After
  fun close() {
    if (::h.isInitialized) h.close()
  }

  /** 1.000 foto in `Foto/`: la soglia e' max(50, 5% di 1.000) = 50. */
  private suspend fun thousand(): List<String> {
    h = ScanHarness(writeMetadata = false)
    val paths = (0 until 1_000).map { "Foto/f$it.jpg" }
    paths.forEachIndexed { i, p -> h.photo(p, seed = i.toLong()) }
    val first = h.scan(ScanMode.Full)
    assertThat(first.detected).isEqualTo(1_000)
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(1_000)
    return paths
  }

  @Test
  fun unRansomwareCheRinomina500FileInLockedCongelaENonSegnaAssenze() = runTest {
    val paths = thousand()
    h.synthetic.ransomware("test", paths, count = 500)
    val vanishCallsBefore = h.store.vanishCalls

    val report = h.scan()

    val freeze = checkNotNull(report.freeze)
    assertThat(freeze.reason).isEqualTo(FreezeReason.VANISHED)
    assertThat(freeze.diff.knownPresent).isEqualTo(1_000)
    assertThat(freeze.diff.vanished).isEqualTo(500)
    assertThat(freeze.diff.notMedia).isEqualTo(500)
    assertThat(freeze.diff.changed).isEqualTo(0)
    assertThat(report.stoppedBy).isEqualTo(ScanStop.FROZEN)
    assertThat(report.vanished).isEqualTo(0)
    // Nessuna assenza segnata: i 1.000 media restano come prima (da verificare) e la Libreria li mostra.
    assertThat(h.store.vanishCalls).isEqualTo(vanishCallsBefore)
    assertThat(h.store.vanishedTotal).isEqualTo(0)
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(1_000)
    // Lo stato e' salvato e lo vedono tutti: la share, lo scanner, il cursore.
    assertThat(h.scanner.status.value).isEqualTo(NasScanStatus.Frozen(freeze))
    assertThat(h.scanner.freezeState.frozen.value).isEqualTo(freeze)
    assertThat(h.store.cursor(h.me, "test")!!.freeze).isEqualTo(freeze)
    assertThat(h.store.cursor(h.me, "test")!!.epoch).isNull() // il giro e' chiuso
    // Il numero da riscrivere per scongelare e' quello mostrato.
    assertThat(freeze.headlineCount).isEqualTo(500)
  }

  @Test
  fun lAvvisoNonDipendeDaUnProcessoVivoESiRileggeDalCursore() = runTest {
    val paths = thousand()
    h.synthetic.ransomware("test", paths, count = 500)
    val freeze = checkNotNull(h.scan().freeze)
    // Un'altra istanza (l'app riavviata) legge lo stesso congelamento.
    val reborn = StoreFreezeState(h.store, h.me, "test")
    assertThat(reborn.frozen.value).isNull()
    reborn.load()
    assertThat(reborn.frozen.value).isEqualTo(freeze)
    assertThat(reborn.release(UserGesture(freeze.headlineCount))).isTrue()
    assertThat(h.store.cursor(h.me, "test")!!.freeze).isNull()
  }

  @Test
  fun siScongelaSoloConIlConteggioGiustoEIlGiroDopoAccettaLaSituazione() = runTest {
    val paths = thousand()
    h.synthetic.ransomware("test", paths, count = 500)
    val freeze = checkNotNull(h.scan().freeze)

    // Un conteggio sbagliato non scongela, nemmeno quello di un'altra grandezza.
    assertThat(h.scanner.freezeState.release(UserGesture(499))).isFalse()
    assertThat(h.scanner.freezeState.release(UserGesture(0))).isFalse()
    assertThat(h.scanner.freezeState.frozen.value).isEqualTo(freeze)
    // Il giro dopo, ancora congelato (la situazione non e' cambiata): ancora nessuna assenza.
    val still = h.scan()
    assertThat(still.freeze).isNotNull()
    assertThat(h.store.vanishedTotal).isEqualTo(0)

    // "Ho controllato, continua" con il numero giusto.
    assertThat(h.scanner.freezeState.release(UserGesture(freeze.headlineCount))).isTrue()
    assertThat(h.scanner.freezeState.frozen.value).isNull()
    assertThat(h.store.cursor(h.me, "test")!!.freeze).isNull()
    // Il giro dopo accetta quello che l'utente ha visto: segna le 500 assenze e non si ricongela.
    val accepted = h.scan()
    assertThat(accepted.freeze).isNull()
    assertThat(accepted.vanished).isEqualTo(500)
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(500) // i 500 originali spariti; i `.locked` non sono media
    // E' un gesto, non un permesso permanente: un nuovo cambiamento di massa congela di nuovo.
    h.synthetic.ransomware("test", paths.drop(500), count = 400)
    assertThat(h.scan().freeze).isNotNull()
  }

  /**
   * NA-4: "Ho controllato, continua" valeva solo in memoria. Il giro dopo e' di solito in un processo nuovo (il lavoro della notte):
   * rivedeva le stesse 500 assenze e ricongelava con lo stesso numero, e l'utente doveva confermare di nuovo cio' che aveva gia'
   * controllato.
   */
  @Test
  fun na4_laConfermaSopravviveAllaMorteDelProcesso() = runTest {
    val paths = thousand()
    h.synthetic.ransomware("test", paths, count = 500)
    val freeze = checkNotNull(h.scan().freeze)
    assertThat(h.scanner.freezeState.release(UserGesture(freeze.headlineCount))).isTrue()

    // Il processo muore prima del giro: ne parte uno nuovo, stesso catalogo e stesso NAS.
    h.restartScanner()
    val accepted = h.scan()

    assertThat(accepted.freeze).isNull()
    assertThat(accepted.vanished).isEqualTo(500)
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(500)
  }

  /** NA-4, la sicurezza: la conferma vale per quel congelamento, non per i successivi. */
  @Test
  fun na4_laConfermaValeSoloPerQuelCongelamento() = runTest {
    val paths = thousand()
    h.synthetic.ransomware("test", paths, count = 500)
    val freeze = checkNotNull(h.scan().freeze)
    assertThat(h.scanner.freezeState.release(UserGesture(freeze.headlineCount))).isTrue()
    h.restartScanner()
    assertThat(h.scan().freeze).isNull()
    assertWithMessage("la franchigia e' stata usata e tolta").that(h.acceptances.load(h.me, "test")).isNull()

    // Un altro cambiamento di massa, dopo (in un altro processo ancora): congela di nuovo, senza sconti.
    h.restartScanner()
    h.synthetic.ransomware("test", paths.drop(500), count = 400)
    val next = h.scan()
    assertThat(next.freeze).isNotNull()
    assertThat(next.vanished).isEqualTo(0)
  }

  /** Se il giro dopo il gesto trova di piu', ricongela, e la vecchia conferma non resta per i congelamenti dopo. */
  @Test
  fun na4_seIlGiroDopoRicongelaLaVecchiaConfermaNonRestaInAttesa() = runTest {
    val paths = thousand()
    h.synthetic.ransomware("test", paths, count = 60)
    val freeze = checkNotNull(h.scan().freeze)
    assertThat(h.scanner.freezeState.release(UserGesture(freeze.headlineCount))).isTrue()
    assertThat(h.acceptances.load(h.me, "test")).isNotNull()

    // Prima del giro dopo si cifra altro: quel giro ricongela, e la conferma di prima e' usata.
    h.synthetic.ransomware("test", paths.drop(60), count = 400)
    h.restartScanner()
    assertThat(h.scan().freeze).isNotNull()
    assertThat(h.acceptances.load(h.me, "test")).isNull()
  }

  /** Una conferma dimenticata per piu' di sette giorni non si applica a un fatto di un'altra settimana. */
  @Test
  fun na4_unaConfermaVecchiaDiSetteGiorniScade() = runTest {
    val paths = thousand()
    h.synthetic.ransomware("test", paths, count = 500)
    val freeze = checkNotNull(h.scan().freeze)
    assertThat(h.scanner.freezeState.release(UserGesture(freeze.headlineCount))).isTrue()

    // Il NAS e' spento per piu' di una settimana: quando torna, la vecchia conferma non vale piu'.
    h.advance(MassChangeRule.WINDOW_MILLIS + 60_000)
    h.restartScanner()
    val later = h.scan()

    assertThat(later.freeze?.reason).isEqualTo(FreezeReason.VANISHED)
    assertThat(later.vanished).isEqualTo(0)
  }

  @Test
  fun quandoLaSituazioneTornaNormaleLaShareSiScongelaDaSola() = runTest {
    val paths = thousand()
    h.synthetic.ransomware("test", paths, count = 500)
    assertThat(h.scan().freeze).isNotNull()
    // Il ripristino da un backup: via i `.locked`, tornano i file con la stessa dimensione e la stessa data.
    paths.take(500).forEachIndexed { i, p ->
      h.nas.delete("test", "$p.locked")
      h.photo(p, seed = i.toLong())
    }
    val report = h.scan()
    assertThat(report.freeze).isNull()
    assertThat(report.complete).isTrue()
    assertThat(report.stoppedBy).isNull()
    assertThat(report.vanished).isEqualTo(0)
    assertThat(h.scanner.freezeState.frozen.value).isNull()
    assertThat(h.store.cursor(h.me, "test")!!.freeze).isNull()
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(1_000)
  }

  @Test
  fun alla_soglia_si_congela_e_sotto_no() = runTest {
    // 49 spariti su 1.000 (soglia 50): niente congelamento, le assenze si segnano.
    val paths = thousand()
    paths.take(49).forEach { h.synthetic.deleteFromPc("test", it) }
    val below = h.scan()
    assertThat(below.freeze).isNull()
    assertThat(below.vanished).isEqualTo(49)
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(951)
    h.close()

    // 50 spariti su 1.000: congelata.
    val paths2 = thousand()
    paths2.take(50).forEach { h.synthetic.deleteFromPc("test", it) }
    val at = h.scan()
    assertThat(at.freeze?.reason).isEqualTo(FreezeReason.VANISHED)
    assertThat(at.freeze?.diff?.vanished).isEqualTo(50)
    assertThat(at.vanished).isEqualTo(0)
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(1_000)
  }

  @Test
  fun centoFileCifratiSulPostoSuMilleCongelanoEIFileCambiatiNonSiAssorbono() = runTest {
    val paths = thousand()
    // Un ransomware che cifra sul posto: stesso nome e stesso fileId, byte nuovi, data di adesso.
    h.synthetic.encryptInPlace("test", paths.take(100))
    val report = h.scan()
    val freeze = checkNotNull(report.freeze)
    assertThat(freeze.reason).isEqualTo(FreezeReason.CHANGED)
    assertThat(freeze.diff.changed).isEqualTo(100)
    assertThat(freeze.diff.vanished).isEqualTo(0)
    // Nessuna versione nuova: la versione buona resta l'unica.
    for (p in paths.take(3)) {
      val id = checkNotNull(h.store.mediaIdAt(h.keyOf(p)))
      assertThat(h.catalog.media(id)!!.versions).hasSize(1)
    }
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(1_000)
  }

  @Test
  fun treGrandezzeSottoSogliaInsiemeNonCongelano() = runTest {
    val paths = thousand()
    paths.take(49).forEach { h.synthetic.deleteFromPc("test", it) }
    h.synthetic.encryptInPlace("test", paths.drop(100).take(40))
    val report = h.scan()
    assertThat(report.freeze).isNull()
    assertThat(report.vanished).isEqualTo(49)
    assertThat(report.changed).isEqualTo(40)
  }

  @Test
  fun unaShareCongelataNonRegistraCoseNuoveFinoAlGestoMaLaLibreriaRestaComePrima() = runTest {
    val paths = thousand()
    h.synthetic.ransomware("test", paths, count = 500)
    assertThat(h.scan().freeze).isNotNull()
    val vanishCallsBefore = h.store.vanishCalls
    h.photo("Foto/arrivata dopo.jpg")
    val report = h.scan()
    assertThat(report.freeze).isNotNull()
    assertThat(h.store.stateOf(h.keyOf("Foto/arrivata dopo.jpg"))).isNull()
    assertThat(h.store.stateOf(h.keyOf(paths[600]))).isEqualTo(LocationState.PRESENT)
    assertThat(h.store.vanishCalls).isEqualTo(vanishCallsBefore)
  }
}
