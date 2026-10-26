package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.ai.AppViewerSubjectCutter
import dev.pampa.mycloudgallery.ai.CutoutSources
import dev.pampa.mycloudgallery.ai.CutoutShareEnv
import dev.pampa.mycloudgallery.ai.RememberedSourceName
import dev.pampa.mycloudgallery.ai.ReplicaHq
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.CutoutImage
import dev.pampa.mycloudgallery.core.ai.contract.CutoutResult
import dev.pampa.mycloudgallery.core.ai.contract.NormalizedPoint
import dev.pampa.mycloudgallery.core.ai.contract.SubjectCutter
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.CutoutBackup
import dev.pampa.mycloudgallery.core.backup.settings.BackupSourceCodec
import dev.pampa.mycloudgallery.core.backup.settings.BackupSourceFence
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.mediastore.write.PhoneMediaFolders
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepControl
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.debugtools.DebugBackupFence
import dev.pampa.mycloudgallery.feature.integrations.share.CacheUris
import dev.pampa.mycloudgallery.feature.integrations.share.ShareCache
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutFailure
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutSaveResult
import dev.pampa.mycloudgallery.feature.viewer.cutout.ViewerCutout
import dev.pampa.mycloudgallery.feature.viewer.cutout.ViewerMask
import dev.pampa.mycloudgallery.feature.viewer.cutout.ViewerRect
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 6, P11b (docs/fase6-progetto.md §2.10, D44, D45, D47; docs/decisioni.md "Per chi usa questo pacchetto" di P13): il ritaglio del soggetto
 * collegato in `:app`. **Pressione fuori dal testo, ritaglio, "Salva come nuova foto"**, che porta alla riga del telefono nella cartella
 * `Ritagli/` e alla sorgente "Ritagli" del backup; Copia e Condividi dalla cartella del `FileProvider` che c'e' gia'; i codici
 * `CUTOUT_FAILED` e `CUTOUT_SAVE_FAILED` in Diagnostica; il ritagliatore rilasciato a memoria bassa e all'azzeramento.
 *
 * Grafo, galleria, NAS, catalogo, decodifica della sorgente e PNG (nostro codificatore) sono quelli veri; il modello MagicTouch e' il ritagliatore
 * finto di core-ai (regione connessa dello stesso colore) e lo scrittore del MediaStore e' quello finto. Il viewer (arbitro dei gesti,
 * `ViewerController`) e' di feature-viewer e lo provano i suoi test: qui si prova che le sue porte, legate dall'app, fanno il lavoro.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class CutoutIntegrationTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private lateinit var rig: AiRig

  @Before
  fun setUp() {
    rig = AiRig(app, temp.root)
  }

  @After
  fun tearDown() = rig.close()

  private class Shown(val phone: AiRig.Mounted, val id: MediaId, val ref: MediaRef)

  /**
   * Una foto del NAS con un soggetto (un rettangolo rosso su bianco). Il dispositivo non ha l'originale ma ha l'HQ nella replica (qualita' "Alta"):
   * e' la sorgente che il ritaglio usa (D44). Robolectric non decodifica da un `File` (`ImageDecoder.createSource(File)`): la strada dell'originale
   * portato dal viewer si prova sul telefono (`CutoutPipelineDeviceTest`).
   */
  private suspend fun shownPhoto(): Shown {
    val phone = rig.founder()
    rig.backend.put(TestNas.COMMON, "Foto/2024/IMG_0042.jpg", AiRig.jpegWithSubject(), lastWriteMs = rig.time.now() - 2 * AiRig.DAY)
    val nas = checkNotNull(phone.gallery.nas)
    nas.setQuality(QualityLevel.HIGH)
    checkNotNull(nas.scan(ScanMode.Full, 5.minutes))
    phone.test.graph.trustGuard.preflight(Purpose.BACKGROUND)
    assertThat(checkNotNull(nas.prepare(5.minutes, PrepControl.ALWAYS)).prepared).isEqualTo(1)
    val catalog = phone.gallery.core().catalog
    val id = catalog.cellContentKeys().cells.single().mediaId
    val ref = catalog.nasRefs(listOf(id)).getValue(id)
    assertThat(nas.blobs.locate((ref as MediaRef.Nas).contentKey, SegmentClass.HQ)).isNotNull()
    return Shown(phone, id, ref)
  }

  private fun hooksOf(phone: AiRig.Mounted) = phone.stack
    .viewerHooks(phone.test.graph, phone.gallery, CutoutShareEnv(ShareCache(phone.test.context.cacheDir), CacheUris { Uri.fromFile(it) }))
    .also { phone.gallery.searchViewer = it }

  @Test
  fun laPressioneRitagliaESalvaComeFotoNuovaNellaCartellaRitagliDelBackup() = runBlocking<Unit> {
    val shown = shownPhoto()
    val phone = shown.phone
    hooksOf(phone)
    // Il viewer riceve le porte del ritaglio dai servizi della galleria (senza, la funzione non c'e': D13).
    val services = phone.gallery.viewerServices
    val cutter = checkNotNull(services.subjectCutter)
    val saver = checkNotNull(services.cutoutSaver)

    // La pressione prolungata fuori dal testo, al centro: il soggetto e' il rettangolo rosso (un quarto della foto).
    val subject = cutter.cut(shown.id, shown.ref, 0.5f, 0.5f) as ViewerCutout.Subject
    assertThat(subject.mask.width).isEqualTo(640)
    assertThat(subject.mask.height).isEqualTo(480)
    assertThat(subject.bounds.left).isWithin(0.03f).of(0.25f)
    assertThat(subject.bounds.top).isWithin(0.03f).of(0.25f)
    assertThat(subject.bounds.right).isWithin(0.03f).of(0.75f)
    assertThat(subject.bounds.bottom).isWithin(0.03f).of(0.75f)
    assertThat(subject.contains(0.5f, 0.5f)).isTrue()
    assertThat(subject.contains(0.05f, 0.05f)).isFalse()

    // "Salva come nuova foto": un PNG nella cartella dei ritagli (in debug con il conto di prova, quella recintata) col nome dell'origine.
    val saved = saver.saveAsNewPhoto(shown.id, shown.ref, subject, outline = false, fromHidden = false) as CutoutSaveResult.Saved
    val writer = phone.cutouts.single()
    assertThat(writer.visible.keys).containsExactly("IMG_0042-ritaglio.png")
    val png = writer.visible.getValue("IMG_0042-ritaglio.png")
    assertThat(png.take(8)).containsExactly(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()).inOrder()

    // La riga del telefono: un media nuovo, collegato al catalogo, nella cartella dei ritagli.
    val catalog = phone.gallery.core().catalog
    val media = checkNotNull(catalog.media(checkNotNull(saved.newMedia)))
    val where = media.locations.single().location as Location.Phone
    assertThat(where.relativePath).isEqualTo(PhoneMediaFolders.FENCED_CUTOUTS)
    assertThat(where.displayName).isEqualTo("IMG_0042-ritaglio.png")
    assertThat(media.id).isNotEqualTo(shown.id)

    // ...che e' la sorgente "Ritagli" del backup: scelta di serie dall'onboarding (con la recinzione di D13) e ammessa dalla recinzione.
    val storedIds = phone.test.graph.backupSettingsStorage.data.first().sourceIds.orEmpty()
    val sources = BackupSourceCodec.decode(storedIds)
    val cutoutSource = sources.sources.single { CutoutBackup.isCutouts(it) } as BackupSource.Folder
    assertThat(cutoutSource.relativePath).isEqualTo(where.relativePath)
    assertThat(cutoutSource.volume).isEqualTo(where.volume)
    assertThat(BackupSourceFence.restrict(sources, DebugBackupFence, TestNas.ADMIN_USER).sources).contains(cutoutSource)
  }

  @Test
  fun daiNascostiLaFotoNuovaNasceNascostaEUnSecondoRitaglioEUnaFotoAParte() = runBlocking<Unit> {
    val shown = shownPhoto()
    val phone = shown.phone
    hooksOf(phone)
    val services = phone.gallery.viewerServices
    val subject = checkNotNull(services.subjectCutter).cut(shown.id, shown.ref, 0.5f, 0.5f) as ViewerCutout.Subject
    val saver = checkNotNull(services.cutoutSaver)

    val hidden = saver.saveAsNewPhoto(shown.id, shown.ref, subject, outline = true, fromHidden = true) as CutoutSaveResult.Saved
    val visible = saver.saveAsNewPhoto(shown.id, shown.ref, subject, outline = false, fromHidden = false) as CutoutSaveResult.Saved

    // Con il contorno e' un altro file (un altro contenuto): due media. Quello dai Nascosti non e' una cella (E-V11, D29); l'altro si'.
    val cells = phone.gallery.core().catalog.cellContentKeys().cells.map { it.mediaId }
    assertThat(cells).contains(visible.newMedia)
    assertThat(cells).doesNotContain(hidden.newMedia)
    // I6: dai Nascosti il nome e' generico (nel MediaStore il ritaglio e' una foto pubblica: "<origine>-ritaglio.png" direbbe da cosa viene).
    assertThat(phone.cutouts.single().visible.keys).containsExactly("Ritaglio.png", "IMG_0042-ritaglio.png")
  }

  /**
   * VR-1, R19: un ritaglio salvato dai Nascosti nasce nascosto **nella stessa transazione** del catalogo (la Libreria non lo vede mai senza il segno), e il
   * segno viaggia agli altri dispositivi dello stesso account subito (`personalChanged`), non al giro periodico. Un ritaglio da una foto normale, o un
   * salvataggio fallito, non avvia niente.
   */
  @Test
  fun unRitaglioDaiNascostiNasceNascostoEAvvisaLaSincronizzazioneDeiDatiPersonali_VR1() = runBlocking<Unit> {
    val phone = rig.founder()
    val catalog = phone.gallery.core().catalog
    var announced = 0
    val writer = dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaWriter(PhoneMediaFolders.FENCED_CUTOUTS)
    val photos = dev.pampa.mycloudgallery.ai.AppCutoutNewPhoto(
      catalog = { catalog },
      writerFor = { writer },
      folder = { PhoneMediaFolders.FENCED_CUTOUTS },
      nowMillis = { rig.time.now() },
      onHiddenSaved = { announced++ },
    )
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

    val visible = photos.save(png, "IMG_0001.jpg", hidden = false) as CutoutSaveResult.Saved
    assertThat(announced).isEqualTo(0)
    assertThat(catalog.cellContentKeys().cells.map { it.mediaId }).contains(visible.newMedia)

    val other = png + byteArrayOf(9)
    val hidden = photos.save(other, "Passaporto.jpg", hidden = true) as CutoutSaveResult.Saved
    assertThat(announced).isEqualTo(1)
    // Nascosta fin dalla nascita: non e' una cella (E-V11, D29).
    assertThat(catalog.cellContentKeys().cells.map { it.mediaId }).doesNotContain(hidden.newMedia)
    // Il nome della foto nascosta non e' quello dell'origine (I6).
    assertThat(writer.visible.keys).contains("Ritaglio.png")
    assertThat(writer.visible.keys.none { it.contains("Passaporto") }).isTrue()
  }

  @Test
  fun copiaECondividiMettonoIlPngNellaCartellaDiCondivisioneEdAltroNonTocca() = runBlocking<Unit> {
    val shown = shownPhoto()
    val phone = shown.phone
    hooksOf(phone)
    val services = phone.gallery.viewerServices
    val subject = checkNotNull(services.subjectCutter).cut(shown.id, shown.ref, 0.5f, 0.5f) as ViewerCutout.Subject
    val saver = checkNotNull(services.cutoutSaver)

    assertThat(saver.copy(shown.id, shown.ref, subject, outline = false)).isTrue()
    assertThat(saver.share(shown.id, shown.ref, subject, outline = true)).isTrue()

    val shareRoot = File(phone.test.context.cacheDir, "share")
    val files = shareRoot.listFiles().orEmpty().map { File(it, "ritaglio.png") }
    assertThat(files).hasSize(2)
    assertThat(files.all { it.isFile && it.length() > 100 }).isTrue()
    // Negli appunti l'indirizzo del file; la condivisione e' un ACTION_SEND di un PNG.
    val clip = app.getSystemService(ClipboardManager::class.java).primaryClip
    assertThat(clip?.itemCount).isEqualTo(1)
    val started = shadowOf(app).nextStartedActivity
    assertThat(started).isNotNull()
    // Niente e' stato scritto sul telefono per Copia e Condividi.
    assertThat(phone.cutouts).isEmpty()
  }

  @Test
  fun unGuastoDelRitagliatoreEUnSalvataggioFallitoVannoInDiagnostica() = runBlocking<Unit> {
    val shown = shownPhoto()
    val phone = shown.phone
    hooksOf(phone)
    val services = phone.gallery.viewerServices
    val cutter = checkNotNull(services.subjectCutter)
    val saver = checkNotNull(services.cutoutSaver)

    phone.cutter.failNext = AiEngineError.OUT_OF_MEMORY
    val failed = cutter.cut(shown.id, shown.ref, 0.5f, 0.5f)
    // R20: il motore dice che la memoria e' finita: il viewer lo sa e lo dice ("chiudi qualche app"), non un generico "riprova".
    assertThat(failed).isEqualTo(ViewerCutout.Failed("CUTOUT_FAILED", CutoutFailure.LOW_MEMORY))

    // Una maschera che non e' della foto aperta non si rende: il PNG non nasce e il salvataggio fallisce.
    val wrong = ViewerCutout.Subject(ViewerMask(10, 10, ByteArray(100) { -1 }), ViewerRect(0f, 0f, 1f, 1f))
    assertThat(saver.saveAsNewPhoto(shown.id, shown.ref, wrong, outline = false, fromHidden = false)).isEqualTo(CutoutSaveResult.Failed)

    val codes = rig.ring.snapshot().map { it.code.id }
    assertThat(codes).contains("CUTOUT_FAILED")
    assertThat(codes).contains("CUTOUT_SAVE_FAILED")
    assertThat(phone.cutouts.flatMap { it.visible.keys }).isEmpty()
  }

  /** Un ritagliatore che esaurisce la memoria (una foto grande, molte app aperte): un `Error`, non un'eccezione. */
  private class OutOfMemoryCutter : SubjectCutter {
    override suspend fun cut(image: CutoutImage, point: NormalizedPoint): CutoutResult = throw OutOfMemoryError("prova")

    override suspend fun release() = Unit
  }

  /**
   * R20: con poca memoria il ritaglio **non fa cadere l'app**: l'`OutOfMemoryError` del motore diventa un guasto con il suo motivo (il viewer dice di
   * chiudere qualche app) e finisce nella Diagnostica come codice. Prima attraversava `AppViewerSubjectCutter` e il processo moriva.
   */
  @Test
  fun conPocaMemoriaIlRitaglioNonFaCadereLAppELoDice_R20() = runBlocking<Unit> {
    rig.cutterOverride = OutOfMemoryCutter()
    val shown = shownPhoto()
    val phone = shown.phone
    hooksOf(phone)
    val cutter = checkNotNull(phone.gallery.viewerServices.subjectCutter)

    val result = cutter.cut(shown.id, shown.ref, 0.5f, 0.5f)

    assertThat(result).isEqualTo(ViewerCutout.Failed("CUTOUT_FAILED", CutoutFailure.LOW_MEMORY))
    assertThat(rig.ring.snapshot().map { it.code.id }).contains("CUTOUT_FAILED")
  }

  /**
   * VR-2: se il modello del ritaglio (nell'APK) non si e' potuto mettere fra i verificati per **mancanza di spazio**, il guasto lo dice ("Spazio
   * insufficiente"): prima era un generico "riprova" che con il telefono pieno non riesce mai.
   */
  @Test
  fun senzaSpazioPerIlModelloIlGuastoDiceSpazioInsufficiente_VR2() = runBlocking<Unit> {
    val shown = shownPhoto()
    val phone = shown.phone
    val nas = checkNotNull(phone.gallery.nas)
    val sources = CutoutSources(app.contentResolver, phone.gallery.intents::uriOf, nas.imageOrigin, ReplicaHq(nas.blobs, nas.locations))
    var noSpace = true
    val cutter = AppViewerSubjectCutter(
      phone.stack.cutout,
      sources,
      { phone.gallery.coreIfReady()?.catalog },
      RememberedSourceName(),
      {},
      noSpace = { noSpace },
    )

    phone.cutter.failNext = AiEngineError.MODEL_UNAVAILABLE
    assertThat(cutter.cut(shown.id, shown.ref, 0.5f, 0.5f)).isEqualTo(ViewerCutout.Failed("CUTOUT_FAILED", CutoutFailure.NO_SPACE))

    // Con lo spazio c'e', lo stesso guasto e' un guasto qualunque.
    noSpace = false
    phone.cutter.failNext = AiEngineError.MODEL_UNAVAILABLE
    assertThat(cutter.cut(shown.id, shown.ref, 0.5f, 0.5f)).isEqualTo(ViewerCutout.Failed("CUTOUT_FAILED", CutoutFailure.OTHER))
  }

  @Test
  fun senzaUnaSorgenteLocaleNonSiScaricaNienteESiDiceCheNonRiesce() = runBlocking<Unit> {
    val phone = rig.founder()
    rig.backend.put(TestNas.COMMON, "Foto/2024/IMG_0042.jpg", AiRig.jpegWithSubject(), lastWriteMs = rig.time.now() - 2 * AiRig.DAY)
    checkNotNull(checkNotNull(phone.gallery.nas).scan(ScanMode.Full, 5.minutes))
    val catalog = phone.gallery.core().catalog
    val id = catalog.cellContentKeys().cells.single().mediaId
    val ref = catalog.nasRefs(listOf(id)).getValue(id)
    hooksOf(phone)

    // Il viewer non ha aperto la foto e la replica non ha l'HQ: mai uno scaricamento solo per ritagliare (D44, F67).
    val result = checkNotNull(phone.gallery.viewerServices.subjectCutter).cut(id, ref, 0.5f, 0.5f)

    assertThat(result).isEqualTo(ViewerCutout.Failed("CUTOUT_FAILED"))
    assertThat(phone.cutter.cuts).isEqualTo(0)
  }

  @Test
  fun ilRitagliatoreSiRilasciaAMemoriaBassaEAllAzzeramento() = runBlocking<Unit> {
    val shown = shownPhoto()
    val phone = shown.phone
    hooksOf(phone)
    val cutter = checkNotNull(phone.gallery.viewerServices.subjectCutter)
    assertThat(cutter.cut(shown.id, shown.ref, 0.5f, 0.5f)).isInstanceOf(ViewerCutout.Subject::class.java)
    assertThat(phone.stack.cutout.loaded).isTrue()
    assertThat(phone.cutter.releases).isEqualTo(0)

    // Memoria bassa in primo piano: il modello (circa 240 MB) e l'immagine trattenuta si rilasciano; il prossimo ritaglio li riapre.
    phone.stack.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
    withTimeout(Attese.TETTO_MS) { while (phone.cutter.releases == 0) delay(10) }
    assertThat(cutter.cut(shown.id, shown.ref, 0.5f, 0.5f)).isInstanceOf(ViewerCutout.Subject::class.java)

    // L'azzeramento ferma `AiGraph` per primo (§1.4): rilascia anche il ritagliatore, e poi non ritaglia piu'.
    phone.stack.ai.stop()
    assertWithMessage("rilasciato all'azzeramento").that(phone.cutter.releases).isEqualTo(2)
    assertThat(cutter.cut(shown.id, shown.ref, 0.5f, 0.5f)).isEqualTo(ViewerCutout.Failed("CUTOUT_FAILED"))
  }

  @Test
  fun leInstallazioniDiPrimaRicevonoLaSorgenteRitagliUnaVoltaSola() = runBlocking<Unit> {
    val phone = rig.founder()
    val storage = phone.test.graph.backupSettingsStorage
    // Un'installazione di prima dei ritagli: l'utente aveva scelto fotocamera e screenshot e basta.
    storage.update { it.copy(sourceIds = setOf(BackupSource.Camera.id, BackupSource.Screenshots.id)) }

    assertThat(storage.offerCutoutsOnce(CutoutBackup.source())).isTrue()
    val offered = BackupSourceCodec.decode(storage.data.first().sourceIds.orEmpty())
    assertThat(offered.sources).contains(CutoutBackup.source(fenced = false))
    assertThat(offered.sources).contains(BackupSource.Camera)

    // Una volta sola: se l'utente la spegne, non torna.
    storage.update { it.copy(sourceIds = setOf(BackupSource.Camera.id)) }
    assertThat(storage.offerCutoutsOnce(CutoutBackup.source())).isFalse()
    assertThat(BackupSourceCodec.decode(storage.data.first().sourceIds.orEmpty()).sources).containsExactly(BackupSource.Camera)
  }

  @Test
  fun chiHaSpentoTuttoNonRiceveNulla() = runBlocking<Unit> {
    val phone = rig.founder()
    val storage = phone.test.graph.backupSettingsStorage
    storage.update { it.copy(sourceIds = emptySet()) }

    assertThat(storage.offerCutoutsOnce(CutoutBackup.source())).isFalse()
    assertThat(storage.data.first().sourceIds).isEmpty()
  }
}
