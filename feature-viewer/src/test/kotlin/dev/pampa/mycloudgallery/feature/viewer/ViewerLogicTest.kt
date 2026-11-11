package dev.pampa.mycloudgallery.feature.viewer

import dev.pampa.mycloudgallery.feature.viewer.info.stripColumns
import android.app.Application
import android.net.Uri
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.library.testing.FakeLocalMediaActions
import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetails
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.metadata.testing.FakeExifDetailsReader
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.MediaTrait
import dev.pampa.mycloudgallery.core.places.testing.FakePlaceData
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerBytes
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat
import dev.pampa.mycloudgallery.feature.viewer.info.InfoInputs
import dev.pampa.mycloudgallery.feature.viewer.info.InfoPanelState
import dev.pampa.mycloudgallery.feature.viewer.info.buildInfoModel
import dev.pampa.mycloudgallery.feature.viewer.page.doubleTapZoomFactor
import dev.pampa.mycloudgallery.feature.viewer.page.fitRect
import dev.pampa.mycloudgallery.feature.viewer.page.maxZoomFactor
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeMediaDetailsSource
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackProblem
import dev.pampa.mycloudgallery.feature.viewer.video.RangeDataSource
import dev.pampa.mycloudgallery.feature.viewer.video.XmpPacket
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La logica del viewer che non ha bisogno di una composizione: i limiti dello zoom, la geometria, le note, il menu a
 * capacita', il pannello informazioni, la cache del ViewModel, lo stato salvato e le sorgenti video.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it")
class ViewerLogicTest {

  private val context get() = ApplicationProvider.getApplicationContext<Application>()
  // Il telefono in Italia: a marzo 2024 lo stesso scarto di Oslo (UTC+1).
  private val format get() = ViewerFormat(context, Locale.ITALIAN, ZoneId.of("Europe/Rome"))

  // --- Zoom ------------------------------------------------------------------------------------------------------------

  @Test
  fun loZoomMassimoEIlPixelVeroDell_Originale() {
    val screen = IntSize(824, 1830)
    // 12 MP: un pixel per pixel e' a ~4,9x la foto intera.
    assertThat(maxZoomFactor(Size(4000f, 3000f), screen)).isWithin(0.01f).of(4.854f)
    // 200 MP: ~19x, e non di piu' di quanto serve.
    assertThat(maxZoomFactor(Size(16000f, 12500f), screen)).isWithin(0.1f).of(19.4f)
  }

  @Test
  fun unaFotoPiccolaSiIngrandisceComunqueEUnaGigantescaHaUnTetto() {
    val screen = IntSize(824, 1830)
    assertThat(maxZoomFactor(Size(640f, 480f), screen)).isEqualTo(4f)
    assertThat(maxZoomFactor(Size(200_000f, 150_000f), screen)).isEqualTo(40f)
    assertThat(maxZoomFactor(Size.Zero, screen)).isEqualTo(4f)
  }

  @Test
  fun ilDoppioTocco_RiempieLoSchermoSenzaArrivareAlPixelDiUna200MP() {
    val screen = IntSize(824, 1830)
    val photo = Size(4000f, 3000f)
    val fill = doubleTapZoomFactor(photo, screen, maxZoomFactor(photo, screen))
    // Una 4:3 larga quanto lo schermo e' alta 618 px: per riempire i 1830 serve circa 3x.
    assertThat(fill).isWithin(0.05f).of(2.96f)
    val big = Size(16000f, 12500f)
    assertThat(doubleTapZoomFactor(big, screen, maxZoomFactor(big, screen))).isAtMost(maxZoomFactor(big, screen))
    // Mai meno di 2x, anche per una foto gia' quasi a schermo.
    val tall = Size(824f, 1830f)
    assertThat(doubleTapZoomFactor(tall, screen, maxZoomFactor(tall, screen))).isAtLeast(2f)
  }

  @Test
  fun laFotoIntera_EntraNelloSchermoCentrata() {
    val box = fitRect(Size(1000f, 2000f), aspect = 1.5f)
    assertThat(box.width).isWithin(0.01f).of(1000f)
    assertThat(box.height).isWithin(0.01f).of(666.67f)
    assertThat(box.center.x).isWithin(0.01f).of(500f)
    assertThat(box.center.y).isWithin(0.01f).of(1000f)
    val tall = fitRect(Size(1000f, 2000f), aspect = 0.25f)
    assertThat(tall.height).isWithin(0.01f).of(2000f)
    assertThat(tall.width).isWithin(0.01f).of(500f)
    // Senza proporzione si usa tutto lo spazio.
    assertThat(fitRect(Size(1000f, 2000f), aspect = 0f).height).isEqualTo(2000f)
  }

  // --- Note e stati ----------------------------------------------------------------------------------------------------

  @Test
  fun l_AnteprimaResta_QuandoArrivaUnErroreOLaNitida() {
    val preview = bitmap()
    var image = PageImage().reduce(ViewerImageState.Preview(preview))
    image = image.reduce(ViewerImageState.Loading(0.3f))
    assertThat(image.preview).isSameInstanceAs(preview)
    assertThat(image.isWaiting).isTrue()
    image = image.reduce(ViewerImageState.Unavailable(UnavailableReason.NAS_UNREACHABLE))
    assertThat(image.preview).isSameInstanceAs(preview)
    assertThat(image.loading).isNull()
    assertThat(image.isWaiting).isFalse()
    // Se poi l'originale arriva, l'avviso sparisce e l'anteprima resta sotto.
    val sharp = bitmap()
    image = image.reduce(ViewerImageState.Sharp(sharp, hdr = true, colorSpace = null))
    assertThat(image.best).isSameInstanceAs(sharp)
    assertThat(image.hdr).isTrue()
    assertThat(image.unavailable).isNull()
    assertThat(image.failed).isNull()
  }

  @Test
  fun ogniStatoHaLaSuaNota() {
    fun notice(vararg states: ViewerImageState, video: PlaybackProblem? = null): PageNoticeKind? =
      PageNoticeKind.of(states.fold(PageImage()) { acc, s -> acc.reduce(s) }, video)

    assertThat(notice()).isNull()
    assertThat(notice(ViewerImageState.Preview(bitmap()))).isNull()
    assertThat(notice(ViewerImageState.Loading(null))).isEqualTo(PageNoticeKind.Loading(null))
    assertThat(notice(ViewerImageState.Loading(0.5f))).isEqualTo(PageNoticeKind.Loading(0.5f))
    assertThat(notice(ViewerImageState.Loading(0.5f), ViewerImageState.Sharp(bitmap(), false, null))).isNull()
    assertThat(notice(ViewerImageState.Unavailable(UnavailableReason.NAS_UNREACHABLE))).isEqualTo(PageNoticeKind.NasUnreachable)
    assertThat(notice(ViewerImageState.Unavailable(UnavailableReason.NOT_ALLOWED_HERE))).isEqualTo(PageNoticeKind.NotAllowedHere)
    assertThat(notice(ViewerImageState.Unavailable(UnavailableReason.VOLUME_UNMOUNTED))).isEqualTo(PageNoticeKind.VolumeUnmounted)
    assertThat(notice(ViewerImageState.Unavailable(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION, 5L)))
      .isEqualTo(PageNoticeKind.MobileData(5L))
    assertThat(notice(ViewerImageState.Failed(ImageFailure.OUT_OF_MEMORY))).isEqualTo(PageNoticeKind.ImageFailed(ImageFailure.OUT_OF_MEMORY))
    // Un video che non parte vince su tutto.
    assertThat(notice(ViewerImageState.Preview(bitmap()), video = PlaybackProblem.UNSUPPORTED))
      .isEqualTo(PageNoticeKind.VideoProblem(PlaybackProblem.UNSUPPORTED))
  }

  // --- Menu a capacita' ------------------------------------------------------------------------------------------------

  private val phone = MediaRef.Phone("external_primary", 7L, "image/jpeg")
  private val handlers = ViewerActionHandlers({}, {}, {}, {}, {}, {})

  private fun labels(entries: List<GalleryMenuEntry>) = entries.map { if (it is GalleryMenuEntry.Item) it.label else "--" }

  @Test
  fun lecapacitaDipendonoDaiServiziEDalTipoDiRiferimento() {
    val full = ViewerServices(actions = FakeLocalMediaActions(), intents = FakePhoneMediaIntents(canTrash = true))
    assertThat(ViewerCapabilities.of(full, phone)).isEqualTo(ViewerCapabilities(favorite = true, hide = true, share = true, copy = true, trash = true))
    // Un elemento che non e' del telefono (sintetico, e dalla Fase 4 del NAS) non si condivide ne' si elimina dal telefono.
    assertThat(ViewerCapabilities.of(full, MediaRef.Synthetic(1, 10, 10))).isEqualTo(ViewerCapabilities(true, true, share = false, copy = false, trash = false))
    assertThat(ViewerCapabilities.of(ViewerServices(), phone)).isEqualTo(ViewerCapabilities(false, false, false, false, false))
    assertThat(ViewerCapabilities.of(ViewerServices(intents = FakePhoneMediaIntents(canTrash = false)), phone).trash).isFalse()
  }

  @Test
  fun ilMenuHaTreGruppiConL_EliminaInFondoDopoUnaInterruzione() {
    val caps = ViewerCapabilities(favorite = true, hide = true, share = true, copy = true, trash = true)
    val entries = viewerMenuEntries(context.resources, caps, isFavorite = false, secure = false, handlers = handlers)
    assertThat(labels(entries)).containsExactly(
      "Aggiungi ai preferiti", "Nascondi", "--", "Condividi", "Copia", "Informazioni", "--", "Elimina dal telefono",
    ).inOrder()
    assertThat((entries.last() as GalleryMenuEntry.Item).destructive).isTrue()
    assertThat(entries.filterIsInstance<GalleryMenuEntry.Item>().count { it.destructive }).isEqualTo(1)
  }

  @Test
  fun iGruppiVuotiNonLasciano_InterruzioniDoppie() {
    val onlyInfo = viewerMenuEntries(context.resources, ViewerCapabilities(false, false, false, false, false), false, false, handlers)
    assertThat(labels(onlyInfo)).containsExactly("Informazioni")
    val noTrash = viewerMenuEntries(context.resources, ViewerCapabilities(true, true, true, true, false), true, false, handlers)
    assertThat(labels(noTrash)).containsExactly("Togli dai preferiti", "Nascondi", "--", "Condividi", "Copia", "Informazioni").inOrder()
    assertThat(noTrash.last()).isNotEqualTo(GalleryMenuEntry.GroupBreak)
  }

  @Test
  fun neiNascostiLaVoceERiportaNellaLibreria() {
    val caps = ViewerCapabilities(favorite = false, hide = true, share = false, copy = false, trash = false)
    val entries = viewerMenuEntries(context.resources, caps, isFavorite = false, secure = true, handlers = handlers)
    assertThat(labels(entries)).contains("Mostra nella Libreria")
    assertThat(labels(entries)).doesNotContain("Nascondi")
  }

  @Test
  fun laBarraInBassoHaLeQuattroAzioniNell_OrdineDiApple() {
    val caps = ViewerCapabilities(true, true, true, true, true)
    val actions = viewerBarActions(context.resources, caps, isFavorite = true, handlers = handlers)
    assertThat(actions.map { it.contentDescription }).containsExactly(
      "Condividi", "Togli dai preferiti", "Informazioni", "Elimina dal telefono",
    ).inOrder()
    assertThat(actions.last().destructive).isTrue()
    assertThat(viewerBarActions(context.resources, ViewerCapabilities(false, false, false, false, false), false, handlers).map { it.contentDescription })
      .containsExactly("Informazioni")
  }

  // --- Pannello informazioni -------------------------------------------------------------------------------------------

  private fun model(inputs: InfoInputs) = buildInfoModel(format, inputs)

  @Test
  fun ilPannelloHaSoloCioCheSi() {
    // H2: il pannello compatto. Senza dettagli e senza giorno dell'indice non c'e' niente da dire.
    val id = MediaId.random()
    val empty = model(InfoInputs(kind = MediaKind.PHOTO, details = null))
    assertThat(empty.date).isNull()
    assertThat(empty.timeLine).isNull()
    assertThat(empty.place).isNull()
    assertThat(empty.hasShot).isFalse()
    assertThat(empty.specs).isEmpty()
    assertThat(empty.hasCard).isFalse()
    assertThat(empty.fileName).isNull()

    val full = model(
      InfoInputs(
        kind = MediaKind.PHOTO,
        details = sampleDetails(id),
        exif = ExifDetails(make = "SAMSUNG", model = "Samsung Galaxy S25", fNumber = 1.8, exposureTime = "1/250", iso = 50),
        place = FakePlaces().place(FakePlaceData.OSLO.place.id),
        placeName = "Oslo",
      ),
    )
    // La data grande in testa (il giorno con la maiuscola), l'ora e il fuso sotto, il luogo.
    assertThat(full.date).isEqualTo("Martedì 12 marzo 2024")
    // CU-5: lo stesso fuso del telefono non si scrive.
    assertThat(full.timeLine).isEqualTo("12:41")
    assertThat(full.estimated).isFalse()
    assertThat(full.place).isEqualTo("Oslo, Norvegia")
    // La scheda dello scatto: la fotocamera in testa con il formato, la striscia dei valori (ISO · focale · apertura · tempo, quelli noti).
    assertThat(full.camera).isEqualTo("Samsung Galaxy S25")
    assertThat(full.format).isEqualTo("JPEG")
    assertThat(full.strip).containsExactly("ISO 50", "f/1,8", "1/250 s").inOrder()
    // La riga delle misure: megapixel, misure, peso.
    assertThat(full.specs.take(2)).containsExactly("12 MP", "4000 × 3000").inOrder()
    assertThat(full.specs.size).isEqualTo(3)
    // In fondo, piccoli: il nome del file e l'ultima cartella (non il percorso).
    assertThat(full.fileName).isEqualTo("IMG_20240312_124100.jpg")
    assertThat(full.folder).isEqualTo("Camera")
    assertThat(full.note).isNull()
  }

  @Test
  fun laStrisciaVaSuDueRigheSoloSeUnValoreNonEntraIntero() {
    // CU-16: quattro valori in una riga se ognuno entra nel suo quarto; altrimenti due righe da due; una colonna se neanche cosi'.
    assertThat(stripColumns(intArrayOf(120, 110, 90, 140), width = 576, rule = 2)).isEqualTo(4)
    assertThat(stripColumns(intArrayOf(120, 110, 90, 160), width = 576, rule = 2)).isEqualTo(2)
    assertThat(stripColumns(intArrayOf(120, 300), width = 576, rule = 2)).isEqualTo(1)
    assertThat(stripColumns(intArrayOf(120, 110, 90), width = 576, rule = 2)).isEqualTo(3)
    assertThat(stripColumns(intArrayOf(120), width = 576, rule = 2)).isEqualTo(1)
  }

  @Test
  fun ilFusoSiDiceSoloSeEDiversoDaQuelloDelTelefono() {
    // CU-5: un telefono a New York guarda una foto scattata a Oslo: l'ora e' quella del posto, e lo si dice (il luogo sta nella riga sotto).
    val id = MediaId.random()
    val elsewhere = ViewerFormat(context, Locale.ITALIAN, ZoneId.of("America/New_York"))
    assertThat(buildInfoModel(elsewhere, InfoInputs(kind = MediaKind.PHOTO, details = sampleDetails(id))).timeLine).isEqualTo("12:41 · ora locale, UTC+1")
    assertThat(model(InfoInputs(kind = MediaKind.PHOTO, details = sampleDetails(id))).timeLine).isEqualTo("12:41")
  }

  @Test
  fun finCheIDettagliNonArrivanoLaDataVieneDalGiornoDellIndice() {
    // Il pannello non salta: la testa c'e' gia' dal giorno della griglia, l'ora arriva con i dettagli.
    val early = model(InfoInputs(kind = MediaKind.PHOTO, details = null, localDay = 20240312))
    assertThat(early.date).isEqualTo("Martedì 12 marzo 2024")
    assertThat(early.timeLine).isNull()
  }

  @Test
  fun unaDataStimataPortaLaPillolaEUnVideoLaDurata() {
    val id = MediaId.random()
    val estimated = model(InfoInputs(kind = MediaKind.PHOTO, details = sampleDetails(id, estimated = true)))
    assertThat(estimated.estimated).isTrue()
    assertThat(estimated.timeLine).endsWith("Data ricavata dal nome del file")
    val video = model(InfoInputs(kind = MediaKind.VIDEO, details = sampleDetails(id, MediaKind.VIDEO)))
    assertThat(video.specs).contains("2:14")
    assertThat(video.video).isTrue()
    // CU-7: niente megapixel su un video.
    assertThat(video.specs.none { it.endsWith("MP") }).isTrue()
    val uhd = dev.pampa.mycloudgallery.feature.viewer.details.MediaDetails(id = id, kind = MediaKind.VIDEO, width = 3840, height = 2160, mime = "video/mp4", durationMs = 9_000L)
    assertThat(model(InfoInputs(kind = MediaKind.VIDEO, details = uhd)).specs.take(2)).containsExactly("4K", "3840 × 2160").inOrder()
    assertThat(format.videoQuality(1920, 1080)).isEqualTo("Full HD")
    assertThat(format.videoQuality(1080, 1920)).isEqualTo("Full HD")
    assertThat(format.videoQuality(1280, 720)).isEqualTo("HD")
    assertThat(format.videoQuality(640, 480)).isNull()
  }

  @Test
  fun loScattoMostraSoloIDatiNotiEIlFlashSoloSeScattato() {
    val id = MediaId.random()
    val few = model(InfoInputs(kind = MediaKind.PHOTO, details = sampleDetails(id), exif = ExifDetails(iso = 400, flashFired = false)))
    assertThat(few.camera).isNull()
    assertThat(few.strip).containsExactly("ISO 400")
    assertThat(few.traits).doesNotContain("Flash")
    val flash = model(InfoInputs(kind = MediaKind.PHOTO, details = sampleDetails(id), exif = ExifDetails(focalLengthMm = 6.3, flashFired = true)))
    assertThat(flash.strip).containsExactly("6,3 mm")
    // Il flash scattato e' un tratto della scheda, non un valore della striscia.
    assertThat(flash.traits).contains("Flash")
    // Senza EXIF (illeggibile): niente valori dello scatto; mentre si legge, i segnaposto (niente salti quando arriva).
    assertThat(model(InfoInputs(kind = MediaKind.PHOTO, details = sampleDetails(id))).hasShot).isFalse()
    val pending = model(InfoInputs(kind = MediaKind.PHOTO, details = sampleDetails(id), exifPending = true))
    assertThat(pending.shotPending).isTrue()
    assertThat(pending.hasCard).isTrue()
    assertThat(model(InfoInputs(kind = MediaKind.VIDEO, details = sampleDetails(id, MediaKind.VIDEO), exifPending = true)).shotPending).isFalse()
  }

  @Test
  fun senzaLaPosizioneIlLuogoEIlNomeDelLuogoOppureNiente() {
    val id = MediaId.random()
    val named = model(InfoInputs(kind = MediaKind.PHOTO, details = sampleDetails(id), placeName = "Oslo"))
    assertThat(named.place).isEqualTo("Oslo")
    assertThat(model(InfoInputs(kind = MediaKind.PHOTO, details = sampleDetails(id))).place).isNull()
  }

  @Test
  fun unaMotionPhotoDiFormatoSconosciutoLoDiceELeCaratteristicheSiElencano() {
    val id = MediaId.random()
    val motion = model(
      InfoInputs(
        kind = MediaKind.MOTION_PHOTO,
        details = sampleDetails(id, MediaKind.MOTION_PHOTO).let {
          dev.pampa.mycloudgallery.feature.viewer.details.MediaDetails(
            id = it.id, kind = it.kind, width = 4000, height = 3000, mime = "image/jpeg",
            traits = setOf(MediaTrait.ULTRA_HDR, MediaTrait.PANORAMA),
          )
        },
        motionUnplayable = true,
      ),
    )
    assertThat(motion.traits).containsAtLeast("Ultra HDR", "Panorama", "Foto in movimento").inOrder()
    assertThat(motion.note).startsWith("Il formato di questa foto in movimento")
  }

  // --- ViewModel: la cache ---------------------------------------------------------------------------------------------

  @Test
  fun ilViewModelNonRileggeCioCheHaGia() = runTest {
    val id = MediaId.random()
    val ref = MediaRef.Phone("external_primary", 9L, "image/jpeg")
    val source = FakeMediaDetailsSource(mapOf(id to sampleDetails(id)))
    val services = ViewerServices(
      details = source,
      exif = FakeExifDetailsReader(ExifDetails(model = "X")),
      bytes = ViewerBytes { ByteArrayMediaBytes(ByteArray(4)) },
      dispatchers = ImmediateDispatchers,
    )
    val viewModel = ViewerViewModel()
    val first = viewModel.load(services, id, ref, seq = 1, wantExif = false)
    assertThat(first.details).isNotNull()
    assertThat(first.exif).isNull()
    viewModel.load(services, id, ref, seq = 1, wantExif = false)
    assertThat(source.requests[id]).isEqualTo(1)
    // L'EXIF si legge quando serve, una volta sola.
    val withExif = viewModel.load(services, id, ref, seq = 1, wantExif = true)
    assertThat(withExif.exif?.model).isEqualTo("X")
    viewModel.load(services, id, ref, seq = 1, wantExif = true)
    val readsAfter = viewModel.sourceReads
    viewModel.load(services, id, ref, seq = 1, wantExif = true)
    assertThat(viewModel.sourceReads).isEqualTo(readsAfter)
    // Dopo una scrittura del catalogo i dettagli si rileggono.
    viewModel.load(services, id, ref, seq = 2, wantExif = false)
    assertThat(source.requests[id]).isEqualTo(2)
  }

  @Test
  fun unFileCheNonSiApreNonFermaIlPannello() = runTest {
    val id = MediaId.random()
    val ref = MediaRef.Phone("external_primary", 9L, "image/jpeg")
    val services = ViewerServices(
      details = FakeMediaDetailsSource(mapOf(id to sampleDetails(id))),
      bytes = ViewerBytes { throw java.io.IOException("file sparito") },
      dispatchers = ImmediateDispatchers,
    )
    val loaded = ViewerViewModel().load(services, id, ref, seq = 1, wantExif = true)
    assertThat(loaded.details).isNotNull()
    assertThat(loaded.exif).isEqualTo(ExifDetails())
  }

  @Test
  fun unaRigaCheIlCatalogoRifiutaNonChiudeIlViewer() = runTest {
    // S25, 13/10: un valore fuori forma nel catalogo faceva lanciare la lettura dei dettagli, e l'eccezione chiudeva l'app.
    val id = MediaId.random()
    val ref = MediaRef.Phone("external_primary", 9L, "image/jpeg")
    val services = ViewerServices(
      details = object : dev.pampa.mycloudgallery.feature.viewer.details.MediaDetailsSource {
        override suspend fun details(id: MediaId) = throw IllegalArgumentException("riga fuori forma")
      },
      bytes = ViewerBytes { ByteArrayMediaBytes(ByteArray(4)) },
      dispatchers = ImmediateDispatchers,
    )
    val loaded = ViewerViewModel().load(services, id, ref, seq = 1, wantExif = false)
    assertThat(loaded.details).isNull()
    assertThat(loaded.place).isNull()
  }

  // --- Stato salvato ---------------------------------------------------------------------------------------------------

  @Test
  fun lStatoSiSalvaERitrova() {
    val id = MediaId.random()
    val state = ViewerState(id, chromeVisible = false, infoOpen = true)
    val saved = with(ViewerState.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(state) }
    val restored = ViewerState.Saver.restore(saved!!)!!
    assertThat(restored.currentId).isEqualTo(id)
    assertThat(restored.chromeVisible).isFalse()
    assertThat(restored.infoOpen).isTrue()
    assertThat(restored.restored).isTrue()
    assertThat(state.restored).isFalse()
  }

  // --- Sorgenti video --------------------------------------------------------------------------------------------------

  @Test
  fun xmp_SiTrovaNellaTestaDelFile() {
    val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF>Foto è bella</rdf:RDF></x:xmpmeta>"
    val file = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x10) + xmp.toByteArray(Charsets.UTF_8) + ByteArray(40) { 7 }
    assertThat(XmpPacket.find(file)).isEqualTo(xmp)
    assertThat(XmpPacket.find(ByteArray(100))).isNull()
    assertThat(XmpPacket.find("<x:xmpmeta aperto".toByteArray())).isNull()
  }

  @Test
  fun unIntervalloDiByteSiLeggeComeUnFileASe() {
    val source = ByteArray(100) { it.toByte() }
    val range = RangeDataSource(ByteArrayDataSource(source), start = 40L, length = 30L)
    val reported = range.open(DataSpec(Uri.parse("content://media/external_primary/1")))
    assertThat(reported).isEqualTo(30L)
    val buffer = ByteArray(64)
    var total = 0
    while (true) {
      val read = range.read(buffer, total, buffer.size - total)
      if (read == C.RESULT_END_OF_INPUT) break
      total += read
    }
    range.close()
    assertThat(total).isEqualTo(30)
    assertThat(buffer.copyOf(30).toList()).isEqualTo(source.copyOfRange(40, 70).toList())
  }

  @Test
  fun unIntervalloAPartireDaUnaPosizioneNonEsceDaiSuoiConfini() {
    val source = ByteArray(100) { it.toByte() }
    val range = RangeDataSource(ByteArrayDataSource(source), start = 40L, length = 30L)
    val reported = range.open(DataSpec(Uri.parse("content://media/external_primary/1"), 10L, C.LENGTH_UNSET.toLong()))
    assertThat(reported).isEqualTo(20L)
    val buffer = ByteArray(64)
    val read = range.read(buffer, 0, buffer.size)
    assertThat(read).isEqualTo(20)
    assertThat(buffer[0]).isEqualTo(50.toByte())
    // Una richiesta piu' lunga dell'intervallo si accorcia.
    val bounded = RangeDataSource(ByteArrayDataSource(source), start = 40L, length = 30L)
    assertThat(bounded.open(DataSpec(Uri.parse("content://media/external_primary/1"), 0L, 500L))).isEqualTo(30L)
  }

  // --- Pannello informazioni: dove si ferma quando il dito si alza ---------------------------------------------------------

  @Test
  fun unoSwipeInSuApreIlFoglioGiaDopoUnTerzoDiCorsaAncheSenzaLancio() = runTest {
    val panel = InfoPanelState(initialOpen = false)
    panel.dragBy(0.4f)
    assertThat(panel.release(0f, reducedMotion = true, openBias = InfoPanelState.OpenBias)).isTrue()
    assertThat(panel.progress.value).isEqualTo(1f)
    val short = InfoPanelState(initialOpen = false)
    short.dragBy(0.15f)
    assertThat(short.release(0f, reducedMotion = true, openBias = InfoPanelState.OpenBias)).isFalse()
    assertThat(short.progress.value).isEqualTo(0f)
  }

  @Test
  fun unoSwipeInGiuChiudeIlFoglioDopoUnTerzoDiCorsaAncheSenzaLancio() = runTest {
    val panel = InfoPanelState(initialOpen = true)
    panel.dragBy(-0.4f)
    assertThat(panel.release(0f, reducedMotion = true, openBias = InfoPanelState.CloseBias)).isFalse()
    val barely = InfoPanelState(initialOpen = true)
    barely.dragBy(-0.1f)
    assertThat(barely.release(0f, reducedMotion = true, openBias = InfoPanelState.CloseBias)).isTrue()
  }

  @Test
  fun unLancioDecisoVinceSullaPosizione() = runTest {
    val panel = InfoPanelState(initialOpen = false)
    panel.dragBy(0.1f)
    // Veloce verso l'alto: si apre anche con poca corsa. Veloce verso il basso: si chiude anche con molta.
    assertThat(panel.release(2f, reducedMotion = true, openBias = InfoPanelState.OpenBias)).isTrue()
    val open = InfoPanelState(initialOpen = true)
    open.dragBy(-0.1f)
    assertThat(open.release(-2f, reducedMotion = true, openBias = InfoPanelState.CloseBias)).isFalse()
  }

  private fun bitmap(): ImageBitmap = androidx.compose.ui.graphics.ImageBitmap(2, 2)
}
