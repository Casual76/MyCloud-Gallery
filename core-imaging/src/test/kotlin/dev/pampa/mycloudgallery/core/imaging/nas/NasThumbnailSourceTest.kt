package dev.pampa.mycloudgallery.core.imaging.nas

import android.graphics.Color
import android.os.CancellationSignal
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbContext
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbResult
import dev.pampa.mycloudgallery.core.imaging.nas.NasTestSupport.center
import dev.pampa.mycloudgallery.core.imaging.nas.NasTestSupport.key
import dev.pampa.mycloudgallery.core.imaging.nas.NasTestSupport.near
import dev.pampa.mycloudgallery.core.imaging.nas.NasTestSupport.ref
import dev.pampa.mycloudgallery.core.imaging.source.RoutingThumbnailSource
import dev.pampa.mycloudgallery.core.index.contract.BlobLocation
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.index.contract.SpaceEstimate
import dev.pampa.mycloudgallery.core.index.testing.InMemoryReplica
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * La catena delle miniature del NAS (D17, P7): replica, miniatura provvisoria, originale **solo se visibile**; la scelta
 * della classe per taglia; l'annullamento; i blob ostili. Con Skia vero (`GraphicsMode.NATIVE`) dove si decodifica
 * davvero, con un decodificatore finto dove interessa l'ordine dei gradini.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NasThumbnailSourceTest {

  private val replica = InMemoryReplica()

  /** Un "originale" del NAS che registra le richieste e dà una bitmap blu (provvisoria). */
  private class FakeOriginals(var result: ThumbResult? = ThumbResult.Loaded(NasTestSupport.solid(Color.BLUE, 8, 6), provisional = true)) :
    NasOriginalThumbnails {
    val calls = AtomicInteger()

    override suspend fun thumbnail(ref: MediaRef.Nas, px: Int, cancel: CancellationSignal): ThumbResult? {
      calls.incrementAndGet()
      return result
    }
  }

  private fun TestScope.source(
    decoder: BlobDecoder = NasTestSupport.ColorByFirstByte(),
    originals: NasOriginalThumbnails = NoNasOriginalThumbnails,
  ) = NasThumbnailSource(replica, replica, originals, decoder, StandardTestDispatcher(testScheduler))

  private fun put(n: Int, cls: SegmentClass, tag: Int) = replica.putBlob(key(n), cls, byteArrayOf(tag.toByte(), 0, 0, 0))

  private suspend fun NasThumbnailSource.result(n: Int, bucket: ThumbBucket, context: ThumbContext = ThumbContext.Visible, mime: String? = "image/jpeg") =
    loadResult(ref(n, mime = mime), bucket.px, CancellationSignal(), context)

  // --- La catena ---------------------------------------------------------------------------------------------------

  @Test
  fun laReplicaVinceSuTutto_eNonELeggeAltro() = runTest {
    val originals = FakeOriginals()
    val s = source(originals = originals)
    put(1, SegmentClass.THUMB_S, tag = 1)
    put(1, SegmentClass.THUMB_M, tag = 2)
    replica.put(key(1), byteArrayOf(3, 0, 0, 0))
    val loaded = s.result(1, ThumbBucket.M) as ThumbResult.Loaded
    assertThat(near(center(loaded.bitmap), Color.GREEN)).isTrue()
    // Quella preparata e' definitiva: niente da sostituire dopo.
    assertThat(loaded.provisional).isFalse()
    assertThat(originals.calls.get()).isEqualTo(0)
  }

  @Test
  fun laClassePerTaglia_SPerLeCellePiccole_MPerLeAltre() = runTest {
    val s = source()
    put(1, SegmentClass.THUMB_S, tag = 1)
    put(1, SegmentClass.THUMB_M, tag = 2)
    fun color(bucket: ThumbBucket) = center((runCurrentResult(s, 1, bucket) as ThumbResult.Loaded).bitmap)
    assertThat(near(color(ThumbBucket.S), Color.RED)).isTrue()
    assertThat(near(color(ThumbBucket.M), Color.GREEN)).isTrue()
    assertThat(near(color(ThumbBucket.L), Color.GREEN)).isTrue()
    assertThat(NasThumbnailSource.classesFor(ThumbBucket.S.px)).containsExactly(SegmentClass.THUMB_S, SegmentClass.THUMB_M).inOrder()
    assertThat(NasThumbnailSource.classesFor(ThumbBucket.L.px)).containsExactly(SegmentClass.THUMB_M, SegmentClass.THUMB_S).inOrder()
  }

  private fun TestScope.runCurrentResult(s: NasThumbnailSource, n: Int, bucket: ThumbBucket): ThumbResult? {
    var out: ThumbResult? = null
    val job = launch { out = s.result(n, bucket) }
    advanceUntilIdle()
    check(job.isCompleted)
    return out
  }

  @Test
  fun conIlSoloLivelloRisparmioSiRipiegaSullaS() = runTest {
    val s = source()
    put(1, SegmentClass.THUMB_S, tag = 1)
    // Una cella grande chiede la M, ma il livello Risparmio ha solo la S: meglio una miniatura piccola che il segnaposto.
    val loaded = runCurrentResult(s, 1, ThumbBucket.L) as ThumbResult.Loaded
    assertThat(near(center(loaded.bitmap), Color.RED)).isTrue()
    assertThat(loaded.provisional).isFalse()
    // E al contrario, se manca la S ma c'e' la M.
    put(2, SegmentClass.THUMB_M, tag = 2)
    assertThat(near(center((runCurrentResult(s, 2, ThumbBucket.S) as ThumbResult.Loaded).bitmap), Color.GREEN)).isTrue()
  }

  @Test
  fun senzaReplicaVaLaMiniaturaProvvisoriaEdE_provvisoria() = runTest {
    val originals = FakeOriginals()
    val s = source(originals = originals)
    replica.put(key(5), byteArrayOf(3, 0, 0, 0))
    val loaded = runCurrentResult(s, 5, ThumbBucket.M) as ThumbResult.Loaded
    assertThat(near(center(loaded.bitmap), Color.BLUE)).isTrue()
    assertThat(loaded.provisional).isTrue()
    // Basta la provvisoria: l'originale (costoso) non si tocca, nemmeno per il contenuto visibile.
    assertThat(originals.calls.get()).isEqualTo(0)
  }

  @Test
  fun l_originaleSoloSeVisibile_altrimentiRimandato() = runTest {
    val originals = FakeOriginals()
    val s = source(originals = originals)
    // In arrivo ma non visibile: niente lettura, e non e' un errore.
    val ahead = s.loadResult(ref(7), ThumbBucket.M.px, CancellationSignal(), ThumbContext.Ahead)
    assertThat(ahead).isEqualTo(ThumbResult.Deferred)
    assertThat(originals.calls.get()).isEqualTo(0)

    // Visibile: l'originale. La visibilita' si legge quando serve, non quando e' nata la richiesta.
    var visible = false
    val late = ThumbContext { visible }
    assertThat(s.loadResult(ref(7), ThumbBucket.M.px, CancellationSignal(), late)).isEqualTo(ThumbResult.Deferred)
    visible = true
    val loaded = s.loadResult(ref(7), ThumbBucket.M.px, CancellationSignal(), late) as ThumbResult.Loaded
    assertThat(loaded.provisional).isTrue()
    assertThat(originals.calls.get()).isEqualTo(1)

    // La richiesta diretta (anteprima del viewer) e' visibile.
    assertThat(s.load(ref(7), ThumbBucket.M.px, CancellationSignal())).isNotNull()
    assertThat(originals.calls.get()).isEqualTo(2)
  }

  @Test
  fun iVideoNonSiScaricanoMai_eUnFormatoNonDecodificabileNeppure() = runTest {
    val originals = FakeOriginals()
    val s = source(originals = originals)
    assertThat(s.result(1, ThumbBucket.M, mime = "video/mp4")).isNull()
    assertThat(s.result(2, ThumbBucket.M, mime = "image/x-adobe-dng")).isNull()
    assertThat(originals.calls.get()).isEqualTo(0)
    // Un video con la copertina preparata la mostra.
    put(3, SegmentClass.THUMB_M, tag = 2)
    assertThat(s.result(3, ThumbBucket.M, mime = "video/mp4")).isInstanceOf(ThumbResult.Loaded::class.java)
  }

  @Test
  fun unOriginaleCheNonSiLeggeEUnFallimento_nonUnNonAdesso() = runTest {
    val originals = FakeOriginals(result = null)
    val s = source(originals = originals)
    assertThat(s.result(1, ThumbBucket.M)).isNull()
    assertThat(originals.calls.get()).isEqualTo(1)
  }

  @Test
  fun unRiferimentoDiUnAltroTipoNonELaSuaSorgente() = runTest {
    val s = source()
    assertThat(s.loadResult(MediaRef.Synthetic(1, 10, 10), 160, CancellationSignal(), ThumbContext.Visible)).isNull()
    assertThat(s.load(MediaRef.Phone("external_primary", 1, null), 160, CancellationSignal())).isNull()
    assertThat(s.canUpgrade(MediaRef.Synthetic(1, 10, 10), 160)).isFalse()
  }

  // --- Cio' che va storto ----------------------------------------------------------------------------------------------

  @Test
  fun unBlobCheNonSiDecodificaOUnPaccoSparitoPassanoAlGradinoDopo() = runTest {
    val tags = NasTestSupport.ColorByFirstByte()
    val s = source(decoder = tags)
    // La M e' rovinata (il decodificatore finto rifiuta il tag 0), la S e' buona: si usa la S.
    put(1, SegmentClass.THUMB_M, tag = 0)
    put(1, SegmentClass.THUMB_S, tag = 1)
    val loaded = runCurrentResult(s, 1, ThumbBucket.M) as ThumbResult.Loaded
    assertThat(near(center(loaded.bitmap), Color.RED)).isTrue()
    assertThat(tags.calls).containsExactly(0, 1).inOrder()

    // Un pacco che non c'e' piu' (replica azzerata mentre si leggeva): come se il blob non ci fosse, mai un'eccezione.
    val gone = object : ReplicaBlobs {
      override fun locate(contentKey: Long, cls: SegmentClass) = BlobLocation(packId = 1, offset = 0, length = 4, cls = cls)
      override fun read(location: BlobLocation): ByteBuffer = throw IOException("pacco assente")
      override suspend fun estimate(quality: QualityLevel) = SpaceEstimate(0, 0)
    }
    replica.put(key(2), byteArrayOf(2, 0, 0, 0))
    val s2 = NasThumbnailSource(gone, replica, NoNasOriginalThumbnails, tags, StandardTestDispatcher(testScheduler))
    val provisional = s2.loadResult(ref(2), ThumbBucket.M.px, CancellationSignal(), ThumbContext.Visible) as ThumbResult.Loaded
    assertThat(provisional.provisional).isTrue()
  }

  @Test
  fun canUpgrade_diceSeLaReplicaHaOraQualcosaDiMeglio() = runTest {
    val s = source()
    assertThat(s.canUpgrade(ref(1), ThumbBucket.M.px)).isFalse()
    put(1, SegmentClass.THUMB_S, tag = 1)
    assertThat(s.canUpgrade(ref(1), ThumbBucket.M.px)).isTrue()
    assertThat(s.canUpgrade(ref(2), ThumbBucket.M.px)).isFalse()
  }

  // --- Annullamento -----------------------------------------------------------------------------------------------------

  @Test
  fun unaRichiestaGiaAnnullataNonCostaNiente() = runTest {
    val tags = NasTestSupport.ColorByFirstByte()
    val s = source(decoder = tags)
    put(1, SegmentClass.THUMB_M, tag = 2)
    val signal = CancellationSignal().also { it.cancel() }
    var thrown: Throwable? = null
    val job = launch {
      try {
        s.loadResult(ref(1), ThumbBucket.M.px, signal, ThumbContext.Visible)
      } catch (e: CancellationException) {
        thrown = e
      }
    }
    advanceUntilIdle()
    assertThat(job.isCompleted).isTrue()
    // Un annullamento e' un annullamento per le coroutine, non un fallimento della miniatura.
    assertThat(thrown).isInstanceOf(CancellationException::class.java)
    assertThat(tags.calls).isEmpty()
  }

  @Test
  fun ilSegnaleScattatoFraUnGradinoELAltroFermaLaCatena() = runTest {
    val signal = CancellationSignal()
    var decodes = 0
    val s = source(decoder = { _, _, cancel ->
      decodes++
      // La prima decodifica (la M, rovinata) fa scattare il segnale: la S non si prova nemmeno.
      signal.cancel()
      cancel.throwIfCanceled()
      null
    })
    put(1, SegmentClass.THUMB_M, tag = 2)
    put(1, SegmentClass.THUMB_S, tag = 1)
    var thrown: Throwable? = null
    launch {
      try {
        s.loadResult(ref(1), ThumbBucket.M.px, signal, ThumbContext.Visible)
      } catch (e: CancellationException) {
        thrown = e
      }
    }
    advanceUntilIdle()
    assertThat(thrown).isInstanceOf(CancellationException::class.java)
    assertThat(decodes).isEqualTo(1)
  }

  @Test
  fun laRouterInoltraIlContestoEIlNas() = runTest {
    val originals = FakeOriginals()
    val nas = source(originals = originals)
    val router = RoutingThumbnailSource(phone = dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailSource, nas = nas)
    // Il contesto "in arrivo" raggiunge la sorgente del NAS: nessuna lettura dell'originale.
    assertThat(router.loadResult(ref(1), 320, CancellationSignal(), ThumbContext.Ahead)).isEqualTo(ThumbResult.Deferred)
    assertThat(originals.calls.get()).isEqualTo(0)
    put(1, SegmentClass.THUMB_S, tag = 1)
    assertThat(router.canUpgrade(ref(1), 320)).isTrue()
    assertThat(router.canUpgrade(MediaRef.Phone("external_primary", 1, null), 320)).isFalse()
    // E la richiesta diretta del telefono, senza sorgente, resta vuota (come prima).
    assertThat(router.load(MediaRef.Phone("external_primary", 1, null), 160, CancellationSignal())).isNull()
  }

  // --- R1 (revisione 4A): la share del media e la chiave intera --------------------------------------------------------

  /**
   * Una replica con due blob per gli stessi primi 64 bit: quello dell'attaccante (rosso) per chi cerca senza perimetro, quello
   * dell'area del media (verde) per chi cerca nella share giusta con la chiave intera.
   */
  private class TwoAreas : ReplicaBlobs {
    val scoped = AtomicInteger()
    val red = byteArrayOf(1, 0, 0, 0)
    val green = byteArrayOf(2, 0, 0, 0)

    override fun locate(contentKey: Long, cls: SegmentClass): BlobLocation? =
      if (cls == SegmentClass.THUMB_S) BlobLocation(1, 0, 4, cls) else null

    override fun locate(contentKey: Long, cls: SegmentClass, scope: dev.pampa.mycloudgallery.core.index.contract.BlobScope): BlobLocation? {
      scoped.incrementAndGet()
      if (cls != SegmentClass.THUMB_S) return null
      return if (scope.shareKeys.firstOrNull() == "test" && scope.fullKey == key(1)) BlobLocation(2, 0, 4, cls) else null
    }

    override fun read(location: BlobLocation): ByteBuffer = ByteBuffer.wrap(if (location.packId == 2L) green else red)

    override suspend fun estimate(quality: QualityLevel): SpaceEstimate = SpaceEstimate(0, 0)
  }

  @Test
  fun r1_conIlPerimetroDelMediaLaMiniaturaVieneSoloDallaSuaAreaEConLaChiaveIntera() = runTest {
    val blobs = TwoAreas()
    val lookups = AtomicInteger()
    val scopes = NasBlobScopes { r ->
      lookups.incrementAndGet()
      if (r.locationId == ref(1).locationId) dev.pampa.mycloudgallery.core.index.contract.BlobScope(listOf("Test", "test-comune"), key(1)) else null
    }
    val s = NasThumbnailSource(blobs, replica, NoNasOriginalThumbnails, NasTestSupport.ColorByFirstByte(), StandardTestDispatcher(testScheduler), scopes)

    val loaded = s.loadResult(ref(1), ThumbBucket.S.px, CancellationSignal(), ThumbContext.Visible) as ThumbResult.Loaded
    assertThat(near(center(loaded.bitmap), Color.GREEN)).isTrue()
    // Il perimetro si risolve una volta e poi resta in memoria: `canUpgrade` non legge il catalogo.
    assertThat(s.canUpgrade(ref(1), ThumbBucket.S.px)).isTrue()
    s.loadResult(ref(1), ThumbBucket.S.px, CancellationSignal(), ThumbContext.Visible)
    assertThat(lookups.get()).isEqualTo(1)

    // Senza chi risolve il perimetro (benchmark) si cerca come prima.
    val plain = NasThumbnailSource(blobs, replica, NoNasOriginalThumbnails, NasTestSupport.ColorByFirstByte(), StandardTestDispatcher(testScheduler))
    val red = plain.loadResult(ref(1), ThumbBucket.S.px, CancellationSignal(), ThumbContext.Visible) as ThumbResult.Loaded
    assertThat(near(center(red.bitmap), Color.RED)).isTrue()
  }
}
