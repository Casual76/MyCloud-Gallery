package dev.pampa.mycloudgallery.core.imaging.cache

import android.graphics.Bitmap
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.model.MediaId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.concurrent.thread

/** La cache a budget (D5): LRU in byte, voci visibili protette, stato osservabile e `trim` per livello. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailCacheTest {

  private fun key(index: Int, bucket: ThumbBucket = ThumbBucket.M): ThumbKey =
    ThumbKey(MediaId("00000000-0000-4000-8000-%012d".format(index)), versionKey = 1, bucket = bucket)

  /** Una bitmap da 100 KB esatti (160 x 160 ARGB_8888 = 102.400 byte). */
  private fun bitmap(): Bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)

  private val unit = 160L * 160 * 4

  /**
   * Il crash della scheda Album dell'11/11/2026 («Reading a state that was created after the snapshot was taken», in
   * `AlbumCoverThumb`): una composizione prende la sua istantanea, poi il caricatore (un altro filo) mette in cache una
   * miniatura che nessuna cella aveva ancora chiesto, e la composizione la chiede e la legge. Prima `put` creava li' lo stato
   * di Compose, nato dopo l'istantanea: la lettura lanciava. Ora lo stato nasce solo in `stateFor`, sul filo di chi legge.
   */
  @Test
  fun unaMiniaturaMessaDaUnAltroFiloDopoLIstantaneaSiLeggeSenzaCadere() {
    val cache = ThumbnailCache(budgetBytes = unit * 10)
    val composition = Snapshot.takeMutableSnapshot()
    try {
      var image: ImageBitmap? = null
      thread { image = cache.put(key(1), bitmap()) }.join()
      val read = composition.enter { cache.stateFor(key(1)).value }
      assertThat(read).isSameInstanceAs(image)
    } finally {
      composition.dispose()
    }
  }

  /** Lo stato chiesto prima da una cella riceve la miniatura che arriva dopo da un altro filo, e torna null allo scarto. */
  @Test
  fun loStatoGiaChiestoRiceveLaMiniaturaDaUnAltroFiloETornaNullAlloScarto() {
    val cache = ThumbnailCache(budgetBytes = unit * 10)
    val state = cache.stateFor(key(1))
    assertThat(state.value).isNull()
    var image: ImageBitmap? = null
    thread { image = cache.put(key(1), bitmap()) }.join()
    assertThat(state.value).isSameInstanceAs(image)
    assertThat(cache.stateFor(key(1))).isSameInstanceAs(state)
    thread { cache.clear() }.join()
    assertThat(state.value).isNull()
  }

  @Test
  fun lAccessoSegnaLUsoERestituisceLaStessaBitmap() {
    val cache = ThumbnailCache(budgetBytes = unit * 10)
    val image = cache.put(key(1), bitmap())
    assertThat(cache.peek(key(1))).isSameInstanceAs(image)
    assertThat(cache.stateFor(key(1)).value).isSameInstanceAs(image)
    assertThat(cache.counters().usedBytes).isEqualTo(unit)
    assertThat(cache.counters().hits).isEqualTo(2)
    assertThat(cache.peek(key(2))).isNull()
    assertThat(cache.counters().misses).isEqualTo(1)
  }

  @Test
  fun ilSuperamentoDelBudgetScartaLeMenoUsateDiRecente() {
    val cache = ThumbnailCache(budgetBytes = unit * 3)
    for (i in 1..3) cache.put(key(i), bitmap())
    // Si usa la 1: la meno usata diventa la 2.
    cache.peek(key(1))
    cache.put(key(4), bitmap())
    assertThat(cache.peek(key(2))).isNull()
    assertThat(cache.peek(key(1))).isNotNull()
    assertThat(cache.peek(key(3))).isNotNull()
    assertThat(cache.peek(key(4))).isNotNull()
    assertThat(cache.counters().usedBytes).isAtMost(unit * 3)
  }

  @Test
  fun leVociVisibiliSiScartanoPerUltime() {
    val cache = ThumbnailCache(budgetBytes = unit * 3)
    for (i in 1..3) cache.put(key(i), bitmap())
    // La 1 e' la meno usata, ma e' visibile: se ne va la 2.
    cache.pin(setOf(key(1)))
    cache.put(key(4), bitmap())
    assertThat(cache.touch(key(1))).isTrue()
    assertThat(cache.touch(key(2))).isFalse()
    assertThat(cache.touch(key(3))).isTrue()
    assertThat(cache.touch(key(4))).isTrue()
  }

  @Test
  fun leVisibiliNonSiScartanoMaiPerFarePostoEIlPianificatoreLoSa() {
    val cache = ThumbnailCache(budgetBytes = unit * 2)
    cache.pin(visible = setOf(key(1), key(2), key(3)))
    cache.put(key(1), bitmap())
    cache.put(key(2), bitmap())
    // Il budget e' pieno delle sole voci visibili: per una visibile e per una in arrivo non c'e' piu' posto.
    assertThat(cache.hasRoom(forVisible = true)).isFalse()
    assertThat(cache.hasRoom(forVisible = false)).isFalse()
    // Caricare comunque la terza (era gia' in volo) sfora, ma non caccia nessuna visibile.
    cache.put(key(3), bitmap())
    assertThat(cache.counters().entries).isEqualTo(3)
    assertThat(cache.counters().usedBytes).isEqualTo(unit * 3)
    // Appena la finestra cambia, le voci uscite si possono scartare e torna il posto.
    cache.pin(visible = setOf(key(3)))
    assertThat(cache.hasRoom(forVisible = true)).isTrue()
  }

  @Test
  fun leInArrivoSiScartanoSoloPerFarPostoAUnaVisibile() {
    val cache = ThumbnailCache(budgetBytes = unit * 3)
    cache.pin(visible = setOf(key(10)), ahead = setOf(key(1), key(2), key(3)))
    cache.put(key(1), bitmap())
    cache.put(key(2), bitmap())
    cache.put(key(11), bitmap()) // fuori dalla finestra
    // Una in arrivo non fa uscire le altre in arrivo: cade la voce fuori finestra.
    cache.put(key(3), bitmap())
    assertThat(cache.touch(key(11))).isFalse()
    assertThat(cache.touch(key(1)) && cache.touch(key(2)) && cache.touch(key(3))).isTrue()
    // Per una in arrivo non c'e' posto, per una visibile si.
    assertThat(cache.hasRoom(forVisible = false)).isFalse()
    assertThat(cache.hasRoom(forVisible = true)).isTrue()
    // La visibile arriva e fa uscire la meno usata fra quelle in arrivo.
    cache.put(key(10), bitmap())
    assertThat(cache.touch(key(10))).isTrue()
    assertThat(cache.touch(key(1))).isFalse()
    assertThat(cache.counters().usedBytes).isAtMost(unit * 3)
  }

  @Test
  fun unaFinestraPiuGrandeDelBudgetNonFaGirareIlCaricatoreAVuoto() {
    // Il caso che faceva girare il caricatore all'infinito: ogni miniatura nuova cacciava un'altra della finestra.
    val cache = ThumbnailCache(budgetBytes = unit * 5)
    val window = (1..50).map(::key)
    cache.pin(visible = window.toSet())
    var loaded = 0
    for (k in window) {
      if (!cache.hasRoom(forVisible = true)) break
      cache.put(k, bitmap())
      loaded++
    }
    assertThat(loaded).isEqualTo(5)
    assertThat(window.take(5).all { cache.touch(it) }).isTrue()
  }

  @Test
  fun loStatoDellaCellaSiAzzeraAlloScartoESiRiempieAlRitorno() {
    val cache = ThumbnailCache(budgetBytes = unit)
    // La cella tiene lo stato prima che la miniatura arrivi.
    val state = cache.stateFor(key(1))
    assertThat(state.value).isNull()
    val first = cache.put(key(1), bitmap())
    assertThat(state.value).isSameInstanceAs(first)
    // Una seconda miniatura fa uscire la prima: lo stesso stato torna null, senza che la cella debba rileggerlo.
    cache.put(key(2), bitmap())
    assertThat(state.value).isNull()
    // Al nuovo caricamento lo STESSO stato si riempie: la cella che lo tiene continua a vedere gli aggiornamenti.
    cache.pin(emptySet())
    val again = cache.put(key(1), bitmap())
    assertThat(cache.stateFor(key(1))).isSameInstanceAs(state)
    assertThat(state.value).isSameInstanceAs(again)
  }

  @Test
  fun unaChiaveConUnaVersioneNuovaNonEQuellaVecchia() {
    val cache = ThumbnailCache(budgetBytes = unit * 4)
    val old = key(1)
    val edited = old.copy(versionKey = 2)
    cache.put(old, bitmap())
    assertThat(cache.stateFor(edited).value).isNull()
    assertThat(cache.touch(edited)).isFalse()
  }

  @Test
  fun ilTrimRiduceSecondoIlLivello() {
    val budget = unit * 8
    fun filled(): ThumbnailCache = ThumbnailCache(budget).also { for (i in 1..8) it.put(key(i), bitmap()) }

    assertThat(ThumbnailCache.keepFraction(0)).isEqualTo(1.0)
    assertThat(ThumbnailCache.keepFraction(TrimLevels.RUNNING_MODERATE)).isEqualTo(0.75)
    assertThat(ThumbnailCache.keepFraction(TrimLevels.RUNNING_LOW)).isEqualTo(0.5)
    assertThat(ThumbnailCache.keepFraction(TrimLevels.RUNNING_CRITICAL)).isEqualTo(0.25)
    assertThat(ThumbnailCache.keepFraction(TrimLevels.UI_HIDDEN)).isEqualTo(0.25)
    assertThat(ThumbnailCache.keepFraction(TrimLevels.BACKGROUND)).isEqualTo(0.0)
    assertThat(ThumbnailCache.keepFraction(80)).isEqualTo(0.0)

    val moderate = filled().also { it.trim(TrimLevels.RUNNING_MODERATE) }
    assertThat(moderate.counters().usedBytes).isAtMost((budget * 0.75).toLong())
    val low = filled().also { it.trim(TrimLevels.RUNNING_LOW) }
    assertThat(low.counters().usedBytes).isAtMost(budget / 2)
    val critical = filled().also { it.trim(TrimLevels.RUNNING_CRITICAL) }
    assertThat(critical.counters().usedBytes).isAtMost(budget / 4)
    val background = filled().also { it.trim(40) }
    assertThat(background.counters().usedBytes).isEqualTo(0)
    assertThat(background.counters().entries).isEqualTo(0)
  }

  @Test
  fun ilTrimLieveRisparmiaLeVociVisibili() {
    val cache = ThumbnailCache(unit * 8)
    for (i in 1..8) cache.put(key(i), bitmap())
    val visible = (1..6).map(::key).toSet()
    cache.pin(visible)
    // Con 6 visibili su 8, un trim al 50% (4 voci) non basta senza toccarle: i livelli lievi le risparmiano.
    val touched = cache.trim(TrimLevels.RUNNING_LOW)
    assertThat(touched).isFalse()
    assertThat(visible.all { cache.touch(it) }).isTrue()
    assertThat(cache.touch(key(7))).isFalse()
    assertThat(cache.touch(key(8))).isFalse()
    // Una pressione critica le scarta e lo dice, cosi' il caricatore le richiede.
    assertThat(cache.trim(TrimLevels.RUNNING_CRITICAL)).isTrue()
    assertThat(cache.counters().usedBytes).isAtMost(unit * 2)
  }

  @Test
  fun dopoUnaMemoriaEsauritaSiTieneMetaBudget() {
    val cache = ThumbnailCache(unit * 8)
    for (i in 1..8) cache.put(key(i), bitmap())
    cache.pin((1..8).map(::key).toSet())
    cache.shrinkForMemoryPressure()
    // Le visibili sono protette: la riduzione non le tocca.
    assertThat(cache.counters().entries).isEqualTo(8)
    cache.pin(emptySet())
    cache.shrinkForMemoryPressure()
    assertThat(cache.counters().usedBytes).isAtMost(unit * 4)
  }

  @Test
  fun clearSvuotaETornaNullGliStati() {
    val cache = ThumbnailCache(unit * 8)
    val state = cache.stateFor(key(1))
    cache.put(key(1), bitmap())
    cache.clear()
    assertThat(state.value).isNull()
    assertThat(cache.counters().usedBytes).isEqualTo(0)
  }

  @Test
  fun unaBitmapNuovaSullaStessaChiaveNonRaddoppiaIByte() {
    val cache = ThumbnailCache(unit * 8)
    cache.put(key(1), bitmap())
    cache.put(key(1), bitmap())
    assertThat(cache.counters().usedBytes).isEqualTo(unit)
    assertThat(cache.counters().entries).isEqualTo(1)
  }
}
