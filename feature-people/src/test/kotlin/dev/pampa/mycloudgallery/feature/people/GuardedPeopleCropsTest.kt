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
    crops.clear()
    assertThat(crops.cachedBytes).isEqualTo(0)
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
