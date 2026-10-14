package dev.pampa.mycloudgallery.core.nasmedia

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.nasmedia.contract.MassChangeRule
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.contract.UserGesture
import dev.pampa.mycloudgallery.core.nasmedia.contract.headlineCount
import dev.pampa.mycloudgallery.core.nasmedia.testing.SyntheticNas
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R9 della revisione 4A: i quattro aggiramenti del congelamento (D10) chiusi.
 * - il gesto vale per i conteggi che l'utente ha visto, non per "tutto" il giro dopo;
 * - le copertine nuove (un file audio in ogni cartella) contano e non escono da una share congelata o sospetta;
 * - le gocce sotto la soglia a ogni giro si sommano in una finestra di 7 giorni;
 * - le share piccole congelano anche con la sola regola percentuale.
 */
@RunWith(AndroidJUnit4::class)
class SecurityFixes4aTest {

  private lateinit var h: ScanHarness

  @After
  fun close() {
    if (::h.isInitialized) h.close()
  }

  private suspend fun share(count: Int, size: Long = 1_500_000): List<String> {
    h = ScanHarness(writeMetadata = false)
    val paths = (0 until count).map { "Foto/f$it.jpg" }
    paths.forEachIndexed { i, p -> h.photo(p, seed = i.toLong(), size = size) }
    assertThat(h.scan(ScanMode.Full).detected).isEqualTo(count)
    return paths
  }

  @Test
  fun r9_ilGestoAccettaSoloIConteggiVistiENonLaCifraturaDiMassaChePoiArriva() = runTest {
    val paths = share(1_000)
    // 60 file in `.locked`: congelata; l'utente controlla e conferma "60".
    h.synthetic.ransomware("test", paths, count = 60)
    val freeze = checkNotNull(h.scan().freeze)
    assertThat(freeze.headlineCount).isEqualTo(60)
    assertThat(h.scanner.freezeState.release(UserGesture(60))).isTrue()

    // Prima del giro dopo si cifra il resto della share: il giro non accetta "tutto", si congela di nuovo.
    h.synthetic.ransomware("test", paths.drop(60), count = 400)
    val next = h.scan()
    assertThat(next.freeze?.reason).isEqualTo(FreezeReason.VANISHED)
    assertThat(next.vanished).isEqualTo(0)
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(1_000)
  }

  @Test
  fun r9_ilGestoLasciaPassareCioCheLUtenteHaVisto() = runTest {
    val paths = share(1_000)
    h.synthetic.ransomware("test", paths, count = 60)
    assertThat(h.scanner.freezeState.release(UserGesture(checkNotNull(h.scan().freeze).headlineCount))).isTrue()
    val accepted = h.scan()
    assertThat(accepted.freeze).isNull()
    assertThat(accepted.vanished).isEqualTo(60)
  }

  @Test
  fun r9_leCopertineNuoveContanoENonSvuotanoUnaShareCongelata() = runTest {
    // 1.000 foto piccole (sotto 300 KB): se nella cartella compare un file audio diventano tutte "copertine".
    share(1_000, size = 100_000)
    h.nas.putFile("test", "Foto/traccia.mp3", SyntheticNas.content(4_000_000, 1), h.nasNow - 86_400_000L)

    val report = h.scan()
    // Prima uscivano subito dalla Libreria, fuori dal conteggio; ora contano come "non piu' media" e congelano.
    assertThat(report.freeze?.reason).isEqualTo(FreezeReason.NOT_MEDIA)
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(1_000)
    // E su una share congelata non escono nemmeno al giro dopo.
    assertThat(h.scan().freeze).isNotNull()
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(1_000)
  }

  @Test
  fun r9_leGocceSottoLaSogliaSiSommanoNellaFinestraDiSetteGiorni() = runTest {
    val paths = share(1_000)
    // 20 a giro (soglia 50): il terzo giro porta la somma della finestra a 60 e congela.
    paths.take(20).forEach { h.synthetic.deleteFromPc("test", it) }
    assertThat(h.scan().freeze).isNull()
    h.advance(60 * 60 * 1000L)
    paths.drop(20).take(20).forEach { h.synthetic.deleteFromPc("test", it) }
    assertThat(h.scan().freeze).isNull()
    h.advance(60 * 60 * 1000L)
    paths.drop(40).take(20).forEach { h.synthetic.deleteFromPc("test", it) }
    val third = h.scan()
    assertThat(third.freeze?.reason).isEqualTo(FreezeReason.VANISHED)
    assertThat(third.freeze?.diff?.vanished).isEqualTo(60)
    assertThat(third.vanished).isEqualTo(0)

    // Dopo il gesto (60, la somma vista) si riparte da zero.
    assertThat(h.scanner.freezeState.release(UserGesture(60))).isTrue()
    assertThat(h.scan().freeze).isNull()
  }

  @Test
  fun r9_oltreLaFinestraLeGocceVecchieNonContanoPiu() = runTest {
    val paths = share(1_000)
    paths.take(40).forEach { h.synthetic.deleteFromPc("test", it) }
    assertThat(h.scan().freeze).isNull()
    h.advance(MassChangeRule.WINDOW_MILLIS + 60_000)
    paths.drop(40).take(40).forEach { h.synthetic.deleteFromPc("test", it) }
    assertThat(h.scan().freeze).isNull()
  }

  @Test
  fun r9_unaSharePiccolaSvuotataSiCongela() = runTest {
    val paths = share(10)
    assertThat(MassChangeRule.effectiveThreshold(10)).isEqualTo(5)
    // Un elenco vuoto della radice (tutto sparito): prima nessun congelamento sotto i 50 file.
    paths.forEach { h.synthetic.deleteFromPc("test", it) }
    val report = h.scan()
    assertThat(report.freeze?.reason).isEqualTo(FreezeReason.VANISHED)
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(10)
    assertThat(h.store.stateOf(h.keyOf(paths[0]))).isEqualTo(LocationState.PRESENT)
  }
}
