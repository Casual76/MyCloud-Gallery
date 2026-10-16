package dev.pampa.mycloudgallery.core.library.similar

import android.graphics.Bitmap
import android.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.decode.ByteArrayImageBytes
import dev.pampa.mycloudgallery.core.imaging.decode.ImageBytes
import dev.pampa.mycloudgallery.core.imaging.nas.NasBlobScopes
import dev.pampa.mycloudgallery.core.imaging.viewer.ImageOrigin
import dev.pampa.mycloudgallery.core.imaging.viewer.OriginResult
import dev.pampa.mycloudgallery.core.index.contract.BlobLocation
import dev.pampa.mycloudgallery.core.index.contract.BlobScope
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.index.contract.SpaceEstimate
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Da dove vengono i pixel (Fase 5A, P15; §2.12 "Firma visiva"): la foto del telefono dall'originale decodificato ridotto e dritto, la
 * foto del NAS dalla **miniatura dell'indice** e niente altro. Skia nativo: JPEG e WebP veri.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignatureSourcesTest : SimilarCatalogTestBase() {

  private val budget = ImagingBudget.forMemoryClass(256)

  /** Un'origine dei byte del telefono scritta dal test: per `_ID` i byte del file. */
  private class FakeOrigin(val files: Map<Long, ByteArray>) : ImageOrigin {
    val opened = ArrayList<Long>()

    override suspend fun open(ref: MediaRef): OriginResult = OriginResult.Missing

    override fun openLocal(ref: MediaRef): ImageBytes? {
      val phone = ref as? MediaRef.Phone ?: return null
      opened += phone.mediaStoreId
      return files[phone.mediaStoreId]?.let { ByteArrayImageBytes(it) }
    }
  }

  private fun phoneRef(id: Long) = MediaRef.Phone(Fx.VOLUME, id, "image/jpeg")

  private fun withExif(jpeg: ByteArray, orientation: Int? = null, make: String? = null, model: String? = null, date: String? = null): ByteArray {
    val file = File.createTempFile("exif", ".jpg")
    try {
      file.writeBytes(jpeg)
      val exif = ExifInterface(file.absolutePath)
      orientation?.let { exif.setAttribute(ExifInterface.TAG_ORIENTATION, it.toString()) }
      make?.let { exif.setAttribute(ExifInterface.TAG_MAKE, it) }
      model?.let { exif.setAttribute(ExifInterface.TAG_MODEL, it) }
      date?.let { exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it) }
      exif.saveAttributes()
      return file.readBytes()
    } finally {
      file.delete()
    }
  }

  private fun distance(a: Raster, b: Raster): SignatureDistance = SignatureComparison.distance(VisualSignatures.of(a), VisualSignatures.of(b))!!

  // --- Il telefono -----------------------------------------------------------------------------------------------------------------

  @Test
  fun lOriginaleDelTelefonoSiDecodificaRidottoEDritto() = runBlocking<Unit> {
    val scene = Scene(11, Subject(0.4, 0.78))
    val big = SceneRenderer.render(scene, 2048, 1536)
    val jpeg = JpegCodec.encode(big, 95)
    val pixels = ImageOriginPhonePixels(FakeOrigin(mapOf(1L to jpeg)), budget)
    val raster = pixels.decode(phoneRef(1))!!
    // Ridotto al lato lungo di 512 (mai la bitmap intera di un JPEG da 3 MP... o da 200 MP).
    assertThat(maxOf(raster.width, raster.height)).isAtMost(ImageOriginPhonePixels.SIDE)
    assertThat(raster.width * 3).isEqualTo(raster.height * 4)
    // E ha la stessa firma della scena disegnata a un'altra taglia.
    val d = distance(raster, RasterOps.resizeArea(big, 512, 384))
    assertThat(SimilarityRules.verdict(d)).isEqualTo(VisualVerdict.SAME)
  }

  @Test
  fun lOrientamentoExifSiApplicaEUnaCopiaConLePosizioniGiaApplicateHaLaStessaFirma() = runBlocking<Unit> {
    val scene = Scene(12, Subject(0.3, 0.78))
    val landscape = SceneRenderer.render(scene, 1200, 900)
    // Il telefono tiene il sensore in orizzontale e scrive Orientation=6 (da ruotare di 90 gradi in senso orario): i pixel veri sono
    // la scena ruotata di 90 gradi in senso antiorario. Il cloud ha applicato la rotazione e tolto il tag.
    val sensor = rotate(landscape, clockwise = false)
    val withTag = withExif(JpegCodec.encode(sensor, 95), orientation = 6, make = "Samsung", model = "SM-S938B", date = "2026:10:14 10:15:30")
    val baked = JpegCodec.encode(landscape, 90)
    val origin = FakeOrigin(mapOf(1L to withTag, 2L to baked))
    val pixels = ImageOriginPhonePixels(origin, budget)
    val a = pixels.decode(phoneRef(1))!!
    val b = pixels.decode(phoneRef(2))!!
    assertThat(a.width).isGreaterThan(a.height) // dritta: orizzontale
    assertThat(a.width).isEqualTo(b.width)
    assertThat(a.height).isEqualTo(b.height)
    assertThat(SimilarityRules.verdict(distance(a, b))).isEqualTo(VisualVerdict.SAME)
    // Senza applicare l'orientamento sarebbero due foto diverse: la prova che il tag conta.
    val sensorOnly = ImageOriginPhonePixels(FakeOrigin(mapOf(1L to JpegCodec.encode(sensor, 95))), budget).decode(phoneRef(1))!!
    assertThat(sensorOnly.height).isGreaterThan(sensorOnly.width)
  }

  @Test
  fun unExifRiscrittoOTolto_LaFirmaNonCambia() = runBlocking<Unit> {
    val scene = Scene(13, Subject(0.5, 0.78))
    val jpeg = JpegCodec.encode(SceneRenderer.render(scene, 1000, 750), 92)
    val original = withExif(jpeg, make = "Samsung", model = "SM-S938B", date = "2026:10:14 10:15:30")
    val rewritten = withExif(jpeg, make = "Altra app", model = "Convertitore 1.0", date = "2026:10:14 10:15:30")
    val stripped = jpeg
    assertThat(original).isNotEqualTo(rewritten)
    assertThat(original).isNotEqualTo(stripped) // i byte sono diversi: e' proprio un caso "stessa foto, byte diversi"
    val pixels = ImageOriginPhonePixels(FakeOrigin(mapOf(1L to original, 2L to rewritten, 3L to stripped)), budget)
    val sigs = listOf(1L, 2L, 3L).map { VisualSignatures.of(pixels.decode(phoneRef(it))!!) }
    assertThat(sigs[0]).isEqualTo(sigs[1])
    assertThat(sigs[0]).isEqualTo(sigs[2])
  }

  @Test
  fun unFileCheNonSiLeggeEUnFormatoCheNonSiFirmaDannoNull() = runBlocking<Unit> {
    val garbage = ByteArray(2_000) { (it * 31).toByte() }
    val gif = "GIF89a".toByteArray() + ByteArray(64)
    val pixels = ImageOriginPhonePixels(FakeOrigin(mapOf(1L to garbage, 2L to gif, 3L to ByteArray(0))), budget)
    assertThat(pixels.decode(phoneRef(1))).isNull()
    assertThat(pixels.decode(phoneRef(2))).isNull()
    assertThat(pixels.decode(phoneRef(3))).isNull()
    assertThat(pixels.decode(phoneRef(99))).isNull() // il file non c'e'
  }

  private fun rotate(src: Raster, clockwise: Boolean): Raster {
    val out = IntArray(src.width * src.height)
    // L'immagine ruotata ha larghezza = altezza di src.
    for (y in 0 until src.height) for (x in 0 until src.width) {
      val nx = if (clockwise) src.height - 1 - y else y
      val ny = if (clockwise) x else src.width - 1 - x
      out[ny * src.height + nx] = src.at(x, y)
    }
    return Raster(src.height, src.width, out)
  }

  // --- La miniatura dell'indice del NAS -----------------------------------------------------------------------------------------------

  /** Una replica scritta dal test; ricorda quali classi si sono chieste e quali blob si sono letti: niente altro deve succedere. */
  private class FakeBlobs(val blobs: Map<Pair<Long, SegmentClass>, ByteArray>, val failReads: Boolean = false) : ReplicaBlobs {
    val located = ArrayList<SegmentClass>()
    val read = ArrayList<SegmentClass>()
    private val byPack = HashMap<Long, ByteArray>()
    private var nextPack = 1L
    private val packOf = HashMap<Pair<Long, SegmentClass>, Long>()

    override fun locate(contentKey: Long, cls: SegmentClass): BlobLocation? {
      located += cls
      val bytes = blobs[contentKey to cls] ?: return null
      val pack = packOf.getOrPut(contentKey to cls) { nextPack++.also { byPack[it] = bytes } }
      return BlobLocation(pack, 0, bytes.size, cls)
    }

    override fun read(location: BlobLocation): ByteBuffer {
      read += location.cls
      if (failReads) throw IOException("pacco sparito")
      return ByteBuffer.wrap(byPack.getValue(location.packId))
    }

    override suspend fun estimate(quality: QualityLevel): SpaceEstimate = SpaceEstimate(0, 0)
  }

  private fun webp(raster: Raster, quality: Int): ByteArray {
    val bitmap: Bitmap = JpegCodec.toBitmap(raster)
    val out = ByteArrayOutputStream()
    check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out))
    bitmap.recycle()
    return out.toByteArray()
  }

  private fun nasRef(key: Long) = MediaRef.Nas(5L, key, "image/jpeg")

  @Test
  fun laMiniaturaDelNasVieneDallaReplicaPrimaLaMEPoiLaS() = runBlocking<Unit> {
    val scene = Scene(14, Subject(0.6, 0.78))
    val big = SceneRenderer.render(scene, 1066, 800)
    val m = webp(RasterOps.resizeArea(big, 533, 400), 78)
    val s = webp(RasterOps.resizeArea(big, 267, 200), 75)
    val onlyS = FakeBlobs(mapOf((7L to SegmentClass.THUMB_S) to s))
    val both = FakeBlobs(mapOf((7L to SegmentClass.THUMB_S) to s, (7L to SegmentClass.THUMB_M) to m))

    val fromBoth = NasThumbnailPixels(both).thumbnail(nasRef(7)) as SignaturePixels.Ready
    assertThat(both.read).containsExactly(SegmentClass.THUMB_M) // la M basta
    val fromS = NasThumbnailPixels(onlyS).thumbnail(nasRef(7)) as SignaturePixels.Ready
    assertThat(onlyS.located).containsExactly(SegmentClass.THUMB_M, SegmentClass.THUMB_S).inOrder()
    assertThat(onlyS.read).containsExactly(SegmentClass.THUMB_S)
    // La stessa scena dall'originale grande e dalle due miniature: sempre lo stesso scatto.
    val reference = RasterOps.resizeArea(big, 533, 400)
    assertThat(SimilarityRules.verdict(distance(fromBoth.frames.single(), reference))).isEqualTo(VisualVerdict.SAME)
    assertThat(SimilarityRules.verdict(distance(fromS.frames.single(), reference))).isEqualTo(VisualVerdict.SAME)
  }

  @Test
  fun senzaMiniaturaIlCandidatoAspettaEChiedeLaPrioritaENonSiLeggeAltro() = runBlocking<Unit> {
    val none = FakeBlobs(emptyMap())
    val later = NasThumbnailPixels(none).thumbnail(nasRef(9))
    assertThat(later).isInstanceOf(SignaturePixels.Later::class.java)
    assertThat((later as SignaturePixels.Later).contentKey).isEqualTo(9L)
    // Solo le due miniature della griglia: mai l'analisi, l'HQ, i fotogrammi, e mai una lettura.
    assertThat(none.located).containsExactly(SegmentClass.THUMB_M, SegmentClass.THUMB_S)
    assertThat(none.read).isEmpty()

    // Un pacco sparito (replica azzerata) vale come un blob mancante.
    val scene = Scene(15, Subject(0.6, 0.78))
    val s = webp(RasterOps.resizeArea(SceneRenderer.render(scene, 533, 400), 267, 200), 75)
    val gone = FakeBlobs(mapOf((9L to SegmentClass.THUMB_S) to s), failReads = true)
    assertThat(NasThumbnailPixels(gone).thumbnail(nasRef(9))).isInstanceOf(SignaturePixels.Later::class.java)

    // Un blob che non si decodifica passa alla classe dopo, poi aspetta.
    val broken = FakeBlobs(mapOf((9L to SegmentClass.THUMB_M) to ByteArray(300) { 3 }, (9L to SegmentClass.THUMB_S) to ByteArray(300) { 5 }))
    assertThat(NasThumbnailPixels(broken).thumbnail(nasRef(9))).isInstanceOf(SignaturePixels.Later::class.java)
    assertThat(broken.read).containsExactly(SegmentClass.THUMB_M, SegmentClass.THUMB_S).inOrder()
  }

  @Test
  fun ilPerimetroDelMediaSiPassaAllaReplica() = runBlocking<Unit> {
    val scene = Scene(16, Subject(0.6, 0.78))
    val s = webp(RasterOps.resizeArea(SceneRenderer.render(scene, 533, 400), 267, 200), 75)
    var asked: MediaRef.Nas? = null
    val scopes = NasBlobScopes { ref ->
      asked = ref
      BlobScope(listOf("alessio"))
    }
    val result = NasThumbnailPixels(FakeBlobs(mapOf((4L to SegmentClass.THUMB_S) to s)), scopes).thumbnail(nasRef(4))
    assertThat(result).isInstanceOf(SignaturePixels.Ready::class.java)
    assertThat(asked?.contentKey).isEqualTo(4L)
    // Un risolutore che sbaglia non ferma il confronto: si cerca come senza perimetro.
    val broken = NasBlobScopes { error("guasto") }
    assertThat(NasThumbnailPixels(FakeBlobs(mapOf((4L to SegmentClass.THUMB_S) to s)), broken).thumbnail(nasRef(4)))
      .isInstanceOf(SignaturePixels.Ready::class.java)
  }

  // --- La sorgente del catalogo ----------------------------------------------------------------------------------------------------

  @Test
  fun laSorgenteDelCatalogoUsaIlTelefonoSeCeEIlNasSoloConLaMiniatura() = runBlocking<Unit> {
    val phone = phoneShot()
    val nas = nasShot("Cloud/IMG_1.jpg")
    val nasRefs = catalog.nasRefs(listOf(nas))
    val key = nasRefs.getValue(nas).contentKey
    val scene = Scene(17, Subject(0.5, 0.78))
    val jpeg = JpegCodec.encode(SceneRenderer.render(scene, 800, 600), 95)
    val s = webp(RasterOps.resizeArea(SceneRenderer.render(scene, 400, 300), 267, 200), 75)
    val origin = FakeOrigin(mapOf(catalog.phoneRefs(listOf(phone)).getValue(phone).mediaStoreId to jpeg))
    val blobs = FakeBlobs(mapOf((key to SegmentClass.THUMB_S) to s))
    val source = CatalogSignatureSource(catalog, ImageOriginPhonePixels(origin, budget), NasThumbnailPixels(blobs))
    val candidates = catalog.similarRunsFor(listOf(phone, nas)).flatMap { it.candidates }.associateBy { it.mediaId }

    // Una foto del telefono si legge dal telefono: la replica non si tocca.
    assertThat(source.pixelsOf(candidates.getValue(phone))).isInstanceOf(SignaturePixels.Ready::class.java)
    assertThat(blobs.located).isEmpty()
    assertThat(origin.opened).hasSize(1)
    // Una solo-NAS si legge dalla miniatura dell'indice: il telefono non si tocca e della replica si legge una sola classe.
    assertThat(source.pixelsOf(candidates.getValue(nas))).isInstanceOf(SignaturePixels.Ready::class.java)
    assertThat(origin.opened).hasSize(1)
    assertThat(blobs.read).containsExactly(SegmentClass.THUMB_S)
    // Un video senza la porta dei fotogrammi non si firma, e non tocca niente.
    assertThat(NoVideoFrames.frames(candidates.getValue(nas), VideoInstants.of(8_000))).isEqualTo(SignaturePixels.Never)
  }

  @Test
  fun gliIstantiDiUnVideoDipendonoDallaSolaDurata() {
    assertThat(VideoInstants.of(8_000).atMs).containsExactly(1_000L, 4_000L).inOrder()
    assertThat(VideoInstants.of(1_200).atMs).containsExactly(600L) // copertina e centro coincidono
    assertThat(VideoInstants.of(2_000).atMs).containsExactly(1_000L).inOrder()
    assertThat(VideoInstants.of(0).atMs).containsExactly(0L)
    assertThat(VideoInstants.of(null).atMs).containsExactly(0L)
    // Due copie a meno di mezzo secondo di differenza guardano a meno di 250 ms di distanza.
    val a = VideoInstants.of(60_000).atMs
    val b = VideoInstants.of(60_400).atMs
    assertThat(kotlin.math.abs(a[1] - b[1])).isAtMost(250L)
    assertThat(a[0]).isEqualTo(b[0])
  }
}
