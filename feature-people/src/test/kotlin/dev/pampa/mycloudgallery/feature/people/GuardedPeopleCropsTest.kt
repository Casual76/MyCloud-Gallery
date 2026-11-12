package dev.pampa.mycloudgallery.feature.people

import android.app.Application
import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FaceCropGate
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.crops.ByteLru
import dev.pampa.mycloudgallery.feature.people.crops.GuardedPeopleCrops
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.google.common.truth.Truth.assertWithMessage

/**
 * I ritagli dei volti (Fase 8, P9; D25, D28, §2.6 "Copertine e ritagli", E-F38): il cancello prima di ogni ritaglio (un media nascosto un attimo fa non da' una
 * copertina, nemmeno dalla memoria) e la cache **solo in memoria** (LRU da 8 MB). `NoDiskSourcesTest` prova che non c'e' nessun file; qui si prova il
 * comportamento.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class GuardedPeopleCropsTest {

  private val faces: List<FaceLocator> = PeopleFixture().faces.map { it.locator }

  /** Il cancello che si apre e si chiude a comando, e quante volte e' stato chiesto. */
  private class Gate(@Volatile var open: Boolean = true) : FaceCropGate {
    val asked = AtomicInteger()

    override suspend fun mayShow(face: FaceLocator): Boolean {
      asked.incrementAndGet()
      return open
    }
  }

  private class Counting(private val bitmap: (Int) -> Bitmap?) : PeopleCrops {
    val calls = AtomicInteger()

    override suspend fun crop(face: FaceLocator, sizePx: Int): Bitmap? {
      calls.incrementAndGet()
      return bitmap(sizePx)
    }
  }

  private fun square(px: Int): Bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)

  @Test
  fun conIlCancelloChiuso_nonSiRitagliaNiente() = runBlocking {
    val gate = Gate(open = false)
    val source = Counting { square(it) }
    val crops = GuardedPeopleCrops(source, gate)
    assertThat(crops.crop(faces[0], 96)).isNull()
    assertThat(source.calls.get()).isEqualTo(0) // il ritaglio non e' nemmeno cominciato
    assertThat(crops.cachedBytes).isEqualTo(0)
  }

  @Test
  fun conIlCancelloAperto_ilRitaglioSiFaUnaVoltaSola() = runBlocking {
    val gate = Gate()
    val source = Counting { square(it) }
    val crops = GuardedPeopleCrops(source, gate)
    val first = crops.crop(faces[0], 96)!!
    val second = crops.crop(faces[0], 96)!!
    assertThat(second).isSameInstanceAs(first)
    assertThat(source.calls.get()).isEqualTo(1)
    assertThat(gate.asked.get()).isEqualTo(2) // il cancello si chiede anche con l'immagine in cache
    assertThat(crops.cachedBytes).isEqualTo(first.allocationByteCount.toLong())
  }

  /** D28: il media e' stato nascosto dopo il primo ritaglio; il ritaglio che e' in memoria non esce piu' e si toglie. */
  @Test
  fun unMediaNascostoDopo_ilRitaglioInMemoriaNonEsce() = runBlocking {
    val gate = Gate()
    val source = Counting { square(it) }
    val crops = GuardedPeopleCrops(source, gate)
    assertThat(crops.crop(faces[0], 96)).isNotNull()
    assertThat(crops.cachedBytes).isGreaterThan(0)
    gate.open = false
    assertThat(crops.crop(faces[0], 96)).isNull()
    assertThat(crops.cachedBytes).isEqualTo(0)
    gate.open = true // lo mostra di nuovo: si ritaglia da capo, non torna da dove non c'e'
    assertThat(crops.crop(faces[0], 96)).isNotNull()
    assertThat(source.calls.get()).isEqualTo(2)
  }

  @Test
  fun misureDiverseSonoRitagliDiversi_eUnaFonteVuotaNonSiRicorda() = runBlocking {
    val source = Counting { if (it == 96) square(it) else null }
    val crops = GuardedPeopleCrops(source, Gate())
    assertThat(crops.crop(faces[0], 96)).isNotNull()
    assertThat(crops.crop(faces[0], 192)).isNull()
    assertThat(crops.crop(faces[0], 192)).isNull()
    assertThat(source.calls.get()).isEqualTo(3) // il 96 una volta, il 192 due: il vuoto non entra in cache
  }

  @Test
  fun lacacheNonSuperaMaiOttoMegabyte_eSiSvuota() = runBlocking {
    val source = Counting { square(it) }
    val crops = GuardedPeopleCrops(source, Gate())
    // 256x256 a 4 byte = 256 KiB: venti volti in quattro misure sono 80 ritagli, circa 20 MiB, ben oltre il tetto.
    for (face in faces) for (size in 256..259) crops.crop(face, size)
    assertThat(crops.cachedBytes).isAtMost(GuardedPeopleCrops.CACHE_BYTES)
    assertThat(crops.cachedBytes).isGreaterThan(0)
    val before = crops.clears.value
    crops.clear()
    assertThat(crops.cachedBytes).isEqualTo(0)
    // CU-19 (fase H, giro 3): chi tiene una copia dei ritagli (le Raccolte) lo sa e la butta.
    assertThat(crops.clears.value).isEqualTo(before + 1)
  }

  /** La sorgente che si ferma dentro il ritaglio finche' la prova non la lascia andare. */
  private class Barrier(private val px: Int) : PeopleCrops {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val calls = AtomicInteger()

    override suspend fun crop(face: FaceLocator, sizePx: Int): Bitmap? {
      calls.incrementAndGet()
      entered.complete(Unit)
      release.await()
      return Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    }
  }

  /** I6: svuotare per memoria non e' una revoca: la cache si svuota, [GuardedPeopleCrops.clears] non cambia (le Raccolte tengono i loro volti). */
  @Test
  fun svuotarePerMemoria_nonRevoca() = runBlocking {
    val source = Counting { square(it) }
    val crops = GuardedPeopleCrops(source, Gate())
    crops.crop(faces[0], 96)
    val before = crops.clears.value
    crops.trim(uiHidden = false)
    assertThat(crops.cachedBytes).isEqualTo(0)
    assertThat(crops.clears.value).isEqualTo(before)
    // In primo piano (poca memoria) si ritaglia subito di nuovo.
    assertThat(crops.crop(faces[0], 96)).isNotNull()
    assertThat(source.calls.get()).isEqualTo(2)
  }

  /**
   * CG-I1-01: uscita e rientro di corsa con lo svuotamento in mezzo (visibile -> nascosta -> svuotamento -> visibile, senza che niente giri fra un
   * passo e l'altro). Il ritorno non si perde: il ritaglio guarda lo **stato** di adesso, e finisce.
   */
  @Test
  fun ilRitornoVisibileNonSiPerde_ancheSeNessunoLoHaVisto() = runTest {
    val visible = MutableStateFlow(true)
    val source = Counting { square(it) }
    val crops = GuardedPeopleCrops(source, Gate(), visible = visible)
    assertThat(visible.value).isTrue()
    visible.value = false
    crops.trim(uiHidden = true)
    visible.value = true
    val job = async { crops.crop(faces[0], 96) }
    runCurrent()
    assertWithMessage("CG-I1-01: con l'app visibile il ritaglio non resta fermo").that(job.isCompleted).isTrue()
    assertThat(job.await()).isNotNull()
    assertThat(source.calls.get()).isEqualTo(1)
  }

  /** I6: dopo uno svuotamento per memoria con l'app nascosta non si ritaglia niente finche' l'app non torna visibile; poi una volta sola. */
  @Test
  fun adAppNascosta_nonSiRitagliaFinoAlRitorno() = runTest {
    val visible = MutableStateFlow(false)
    val gate = Gate()
    val source = Counting { square(it) }
    val crops = GuardedPeopleCrops(source, gate, visible = visible)
    crops.trim(uiHidden = true)
    val job = async { crops.crop(faces[0], 96) }
    runCurrent()
    // Il primo cancello e' passato e il ritaglio e' fermo sullo stato "visibile": niente sorgente.
    assertThat(gate.asked.get()).isEqualTo(1)
    assertThat(job.isCompleted).isFalse()
    assertThat(source.calls.get()).isEqualTo(0)
    assertThat(crops.produced).isEqualTo(0)
    visible.value = true
    runCurrent()
    assertThat(job.await()).isNotNull()
    assertThat(source.calls.get()).isEqualTo(1)
    // Un altro svuotamento con l'app nascosta: si aspetta di nuovo (gli svuotamenti ripetuti non si sommano ne' si perdono).
    visible.value = false
    crops.trim(uiHidden = true)
    crops.trim(uiHidden = true)
    val again = async { crops.crop(faces[1], 96) }
    runCurrent()
    assertThat(again.isCompleted).isFalse()
    visible.value = true
    runCurrent()
    assertThat(again.await()).isNotNull()
    assertThat(source.calls.get()).isEqualTo(2)
  }

  /** I6: una foto nascosta mentre il ritaglio aspetta l'app: al ritorno il cancello si richiede e non si ritaglia (sempre, non per caso). */
  @Test
  fun unaFotoNascostaDuranteLAttesa_nonDaRitaglio() = runTest {
    val visible = MutableStateFlow(false)
    val gate = Gate()
    val source = Counting { square(it) }
    val crops = GuardedPeopleCrops(source, gate, visible = visible)
    crops.trim(uiHidden = true)
    val job = async { crops.crop(faces[0], 96) }
    runCurrent()
    assertWithMessage("il primo cancello e' gia' passato").that(gate.asked.get()).isEqualTo(1)
    assertThat(job.isCompleted).isFalse()
    gate.open = false
    visible.value = true
    runCurrent()
    assertThat(job.await()).isNull()
    assertThat(gate.asked.get()).isEqualTo(2)
    assertThat(source.calls.get()).isEqualTo(0)
  }

  /**
   * CG-I1-02: uno svuotamento per memoria mentre la sorgente lavora (l'app va in secondo piano a meta' ritaglio). Il ritaglio torna a chi l'ha
   * chiesto (vale), ma non rientra nella cache liberata; la richiesta dopo, ad app nascosta, aspetta invece di trovarlo in memoria.
   */
  @Test
  fun unoSvuotamentoDuranteIlRitaglio_nonRiempieLaCache() = runTest {
    val visible = MutableStateFlow(true)
    val source = Barrier(96)
    val crops = GuardedPeopleCrops(source, Gate(), visible = visible)
    val job = async { crops.crop(faces[0], 96) }
    runCurrent()
    assertThat(source.entered.isCompleted).isTrue()
    visible.value = false
    crops.trim(uiHidden = true)
    source.release.complete(Unit)
    runCurrent()
    assertThat(job.await()).isNotNull()
    assertWithMessage("CG-I1-02: la cache liberata resta libera").that(crops.cachedBytes).isEqualTo(0)
    val next = async { crops.crop(faces[0], 96) }
    runCurrent()
    assertThat(next.isCompleted).isFalse()
    assertThat(source.calls.get()).isEqualTo(1)
    visible.value = true
    runCurrent()
    assertThat(next.await()).isNotNull()
    assertThat(source.calls.get()).isEqualTo(2)
    assertThat(crops.cachedBytes).isGreaterThan(0)
  }

  /** CG-I1-02: una revoca vera (cancellazione, Persone spenta) mentre la sorgente lavora: il ritaglio non torna a nessuno e non entra in cache. */
  @Test
  fun unaRevocaDuranteIlRitaglio_nonTornaANessuno() = runTest {
    val source = Barrier(96)
    val crops = GuardedPeopleCrops(source, Gate())
    val job = async { crops.crop(faces[0], 96) }
    runCurrent()
    assertThat(source.entered.isCompleted).isTrue()
    crops.clear()
    source.release.complete(Unit)
    runCurrent()
    assertThat(job.await()).isNull()
    assertThat(crops.cachedBytes).isEqualTo(0)
  }

  /** I6: una revoca mentre il ritaglio aspetta l'app: al ritorno non si ritaglia (la revoca vale anche se il cancello di adesso direbbe si'). */
  @Test
  fun unaRevocaDuranteLAttesa_nonDaRitaglio() = runTest {
    val visible = MutableStateFlow(false)
    val source = Counting { square(it) }
    val crops = GuardedPeopleCrops(source, Gate(), visible = visible)
    crops.trim(uiHidden = true)
    val job = async { crops.crop(faces[0], 96) }
    runCurrent()
    crops.clear()
    visible.value = true
    runCurrent()
    assertThat(job.await()).isNull()
    assertThat(source.calls.get()).isEqualTo(0)
  }

  @Test
  fun iLimitiDellaCacheSonoQuelliDelProgetto() {
    assertThat(GuardedPeopleCrops.CACHE_BYTES).isEqualTo(8L * 1024 * 1024)
  }

  @Test
  fun byteLru_togliePrimoIlMenoUsato() {
    val cache = ByteLru<String, String>(maxBytes = 10) { it.length }
    cache.put("a", "1234")
    cache.put("b", "1234")
    assertThat(cache.get("a")).isEqualTo("1234") // "a" e' ora il piu' usato
    cache.put("c", "1234") // 12 byte: esce "b"
    assertThat(cache.get("b")).isNull()
    assertThat(cache.get("a")).isNotNull()
    assertThat(cache.get("c")).isNotNull()
    assertThat(cache.weight).isEqualTo(8)
    assertThat(cache.size).isEqualTo(2)
  }

  @Test
  fun byteLru_unValorePiuGrossoDelTettoNonEntra_eSostituireNonDoppiaIlPeso() {
    val cache = ByteLru<String, String>(maxBytes = 10) { it.length }
    cache.put("big", "x".repeat(11))
    assertThat(cache.size).isEqualTo(0)
    cache.put("a", "12345")
    cache.put("a", "123") // sostituisce
    assertThat(cache.weight).isEqualTo(3)
    cache.put("a", "x".repeat(11)) // non entra, e il vecchio valore sotto la stessa chiave esce
    assertThat(cache.get("a")).isNull()
    assertThat(cache.weight).isEqualTo(0)
    cache.put("k", "12")
    assertThat(cache.remove("k")).isEqualTo("12")
    assertThat(cache.weight).isEqualTo(0)
    cache.put("z", "12")
    cache.clear()
    assertThat(cache.size).isEqualTo(0)
    assertThat(cache.weight).isEqualTo(0)
  }

  @Test
  fun byteLru_unTettoNonPositivoNonSiCrea() {
    assertThrows(IllegalArgumentException::class.java) { ByteLru<String, String>(0) { it.length } }
  }
}
