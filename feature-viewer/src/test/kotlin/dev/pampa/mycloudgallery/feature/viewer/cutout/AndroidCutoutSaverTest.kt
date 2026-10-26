package dev.pampa.mycloudgallery.feature.viewer.cutout

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaWriter
import dev.pampa.mycloudgallery.core.mediastore.write.CutoutPhoneSaver
import dev.pampa.mycloudgallery.core.mediastore.write.PhoneMediaFolders
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.security.input.SensitiveClipboard
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Copia, Condividi e "Salva come nuova foto" del ritaglio (Fase 6, P13; D47, E-V11; riga P13 del §4.4): Copia con `ClipData` **sensibile** e
 * l'indirizzo del `FileProvider` esistente (`cacheDir/share/`); Condividi con `ACTION_SEND`, `FLAG_GRANT_READ_URI_PERMISSION` e `ClipData`
 * (Android 17 e 18); "Salva come nuova foto" fino al catalogo con la regola dei Nascosti. Il PNG e il file in cache sono finti: qui conta
 * cio' che esce verso il sistema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AndroidCutoutSaverTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val id = MediaId("00000000-0000-4000-8000-00000000000a")
  private val ref = MediaRef.Synthetic(seed = 1, width = 1600, height = 900)
  private val subject = CutoutTestImage.subject(64, 36)
  private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
  private val uri: Uri = Uri.parse("content://dev.pampa.mycloudgallery.test.integrations.files/share/0123456789abcdef/ritaglio.png")

  private class Calls {
    val pngs = mutableListOf<Boolean>()
    val staged = mutableListOf<ByteArray>()
    val saved = mutableListOf<Triple<ByteArray, String?, Boolean>>()
  }

  private val calls = Calls()
  private var pngResult: ByteArray? = pngBytes
  private var stageResult: StagedCutout? = StagedCutout(uri)
  private var newPhotoResult: CutoutSaveResult = CutoutSaveResult.Saved(null)
  private var newPhotoThrows: Throwable? = null

  private fun saver(source: (MediaRef) -> String? = { "IMG_0042.jpg" }) = AndroidCutoutSaver(
    context = context,
    png = CutoutPngSource { _, _, _, outline ->
      calls.pngs += outline
      pngResult
    },
    files = CutoutShareFiles { bytes ->
      calls.staged += bytes
      stageResult
    },
    newPhoto = CutoutNewPhoto { bytes, name, hidden ->
      newPhotoThrows?.let { throw it }
      calls.saved += Triple(bytes, name, hidden)
      newPhotoResult
    },
    sourceName = source,
  )

  private fun clip(): ClipData? = context.getSystemService(ClipboardManager::class.java).primaryClip

  // --- Copia ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun copiaMetteNegliAppuntiLIndirizzoDelFileInCacheSegnatoSensibile() = runBlocking<Unit> {
    assertThat(saver().copy(id, ref, subject, outline = true)).isTrue()
    val clip = clip()!!
    assertThat(clip.getItemAt(0).uri).isEqualTo(uri)
    // Segnato sensibile (Android 13+: niente anteprima negli appunti).
    assertThat(clip.description.extras?.getBoolean(SensitiveClipboard.EXTRA_IS_SENSITIVE)).isTrue()
    assertThat(clip.description.label).isEqualTo("Soggetto dalla foto")
    // Il PNG e' stato chiesto con il Contorno che si vedeva, e messo nella cartella di condivisione.
    assertThat(calls.pngs).containsExactly(true)
    assertThat(calls.staged.single()).isEqualTo(pngBytes)
  }

  @Test
  fun copiaNonRiesceSeIlPngOLaCartellaDiCondivisioneMancano() = runBlocking<Unit> {
    pngResult = null
    assertThat(saver().copy(id, ref, subject, outline = false)).isFalse()
    assertThat(clip()).isNull()
    pngResult = pngBytes
    stageResult = null
    assertThat(saver().copy(id, ref, subject, outline = false)).isFalse()
    assertThat(clip()).isNull()
  }

  @Test
  fun unaEccezioneDelleSorgentiEUnaCopiaNonRiuscita() = runBlocking<Unit> {
    val broken = AndroidCutoutSaver(
      context = context,
      png = CutoutPngSource { _, _, _, _ -> error("dentro core-ai") },
      files = CutoutShareFiles { StagedCutout(uri) },
      newPhoto = CutoutNewPhoto { _, _, _ -> CutoutSaveResult.Saved(null) },
    )
    assertThat(broken.copy(id, ref, subject, outline = false)).isFalse()
    assertThat(broken.share(id, ref, subject, outline = false)).isFalse()
    assertThat(broken.saveAsNewPhoto(id, ref, subject, outline = false, fromHidden = false)).isEqualTo(CutoutSaveResult.Failed)
  }

  @Test
  fun laCancellazioneNonSiTrasformaInUnFallimento() {
    val cancelling = AndroidCutoutSaver(
      context = context,
      png = CutoutPngSource { _, _, _, _ -> throw CancellationException("annullato") },
      files = CutoutShareFiles { StagedCutout(uri) },
      newPhoto = CutoutNewPhoto { _, _, _ -> CutoutSaveResult.Saved(null) },
    )
    assertThrows(CancellationException::class.java) { runBlocking { cancelling.copy(id, ref, subject, outline = false) } }
    assertThrows(CancellationException::class.java) { runBlocking { cancelling.share(id, ref, subject, outline = false) } }
    assertThrows(CancellationException::class.java) { runBlocking { cancelling.saveAsNewPhoto(id, ref, subject, outline = false, fromHidden = false) } }
  }

  // --- Condividi -----------------------------------------------------------------------------------------------------------------------

  @Test
  fun condividiApreIlSelettoreConPermessoDiLetturaEClipData() = runBlocking<Unit> {
    assertThat(saver().share(id, ref, subject, outline = false)).isTrue()
    val started = shadowOf(context as Application).nextStartedActivity
    // Il selettore di sistema, con il permesso e il ClipData anche su di lui (Android 17 e 18).
    assertThat(started.action).isEqualTo(Intent.ACTION_CHOOSER)
    assertThat(started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    assertThat(started.clipData!!.getItemAt(0).uri).isEqualTo(uri)
    // Il contesto non e' un'activity: serve il nuovo task.
    assertThat(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    @Suppress("DEPRECATION")
    val send = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
    assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
    assertThat(send.type).isEqualTo("image/png")
    assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    @Suppress("DEPRECATION")
    assertThat(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)).isEqualTo(uri)
    assertThat(send.clipData!!.getItemAt(0).uri).isEqualTo(uri)
    // Nessun permesso di scrittura, nessun testo, nessuna posizione.
    assertThat(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
    assertThat(send.hasExtra(Intent.EXTRA_TEXT)).isFalse()
  }

  @Test
  fun condividiSenzaAppCheLaRiceveDiceDiNo() = runBlocking<Unit> {
    shadowOf(context as Application).checkActivities(true)
    assertThat(saver().share(id, ref, subject, outline = true)).isFalse()
  }

  @Test
  fun ilSelettoreEIlComandoDelSistemaSenzaDatiInPiu() {
    val chooser = CutoutSystem.shareIntent(uri)
    assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
    assertThat(CutoutSystem.MIME).isEqualTo("image/png")
  }

  // --- Salva come nuova foto -----------------------------------------------------------------------------------------------------------

  @Test
  fun salvaPassaIlPngIlNomeDelFileDiOrigineELaRegolaDeiNascosti() = runBlocking<Unit> {
    assertThat(saver().saveAsNewPhoto(id, ref, subject, outline = true, fromHidden = false)).isEqualTo(CutoutSaveResult.Saved(null))
    assertThat(calls.saved.single().first).isEqualTo(pngBytes)
    assertThat(calls.saved.single().second).isEqualTo("IMG_0042.jpg")
    assertThat(calls.saved.single().third).isFalse()
    assertThat(calls.pngs).containsExactly(true)
    saver { null }.saveAsNewPhoto(id, ref, subject, outline = false, fromHidden = true)
    assertThat(calls.saved.last().second).isNull()
    assertThat(calls.saved.last().third).isTrue()
  }

  @Test
  fun salvaNonRiesceSeIlPngMancaOSeIlSalvataggioFallisce() = runBlocking<Unit> {
    pngResult = null
    assertThat(saver().saveAsNewPhoto(id, ref, subject, outline = false, fromHidden = false)).isEqualTo(CutoutSaveResult.Failed)
    assertThat(calls.saved).isEmpty()
    pngResult = pngBytes
    newPhotoResult = CutoutSaveResult.Failed
    assertThat(saver().saveAsNewPhoto(id, ref, subject, outline = false, fromHidden = false)).isEqualTo(CutoutSaveResult.Failed)
    newPhotoThrows = java.io.IOException("disco pieno")
    assertThat(saver().saveAsNewPhoto(id, ref, subject, outline = false, fromHidden = false)).isEqualTo(CutoutSaveResult.Failed)
  }

  // --- Fino al catalogo (E-V11) --------------------------------------------------------------------------------------------------------

  private lateinit var db: GalleryDatabase

  @Before
  fun openCatalog() {
    db = GalleryDatabase.build(context, name = null)
  }

  @After
  fun closeCatalog() {
    db.close()
  }

  @Test
  fun dalNascostoLaFotoNuovaNasceNascostaFinoAlCatalogo_E_V11() = runBlocking<Unit> {
    val catalog = MediaCatalog(db, AccountId.of("alessio"), zone = { ZoneId.of("Europe/Rome") }) { 1_760_000_000_000L }
    val writer = FakePhoneMediaWriter()
    val real = AndroidCutoutSaver(
      context = context,
      png = CutoutPngSource { _, _, _, _ -> pngBytes },
      files = CutoutShareFiles { StagedCutout(uri) },
      newPhoto = PhoneCutoutNewPhoto(CutoutPhoneSaver(writer, catalog)) { 1_760_000_000_000L },
      sourceName = { "segreta.jpg" },
    )
    val result = real.saveAsNewPhoto(id, ref, subject, outline = false, fromHidden = true) as CutoutSaveResult.Saved
    // I6: dai Nascosti il nome e' generico, non quello del file d'origine.
    assertThat(writer.visible.keys).containsExactly("Ritaglio.png")
    val media = result.newMedia!!
    assertThat(db.personalDao().get(media.value)!!.hidden).isTrue()

    // Un ritaglio da una foto in chiaro nasce in chiaro.
    val plain = real.saveAsNewPhoto(id, ref, subject, outline = true, fromHidden = false) as CutoutSaveResult.Saved
    assertThat(plain.newMedia).isNotNull()
    assertThat(writer.pendingLeft).isEqualTo(0)
  }

  @Test
  fun lePostazioniDelRitaglioSonoLeStesseDelBackup() {
    // La cartella dei ritagli del telefono (core-mediastore) e la sorgente "Ritagli" del backup (core-backup) sono la stessa cartella.
    assertThat(PhoneMediaFolders.CUTOUTS).isEqualTo(dev.pampa.mycloudgallery.core.backup.contract.CutoutBackup.RELATIVE_PATH)
    assertThat(PhoneMediaFolders.FENCED_CUTOUTS).isEqualTo(dev.pampa.mycloudgallery.core.backup.contract.CutoutBackup.FENCED_RELATIVE_PATH)
    assertThat(PhoneMediaFolders.cutouts(fenced = false)).isEqualTo(dev.pampa.mycloudgallery.core.backup.contract.CutoutBackup.source().relativePath)
    assertThat(PhoneMediaFolders.cutouts(fenced = true)).isEqualTo(dev.pampa.mycloudgallery.core.backup.contract.CutoutBackup.source(fenced = true).relativePath)
  }

  // --- Il file del ritaglio non resta per sempre (R18, VR-3) ------------------------------------------------------------------------

  private class Lifecycle : StagedLifecycle {
    val events = mutableListOf<String>()

    override fun shared() {
      events += "shared"
    }

    override fun copied() {
      events += "copied"
    }

    override fun discard() {
      events += "discard"
    }
  }

  /**
   * R18: il PNG copiato o condiviso ha un ciclo di vita. Copia riuscita: "copied" (chi lo tiene lo toglie quando gli appunti cambiano o dopo poco);
   * scelta dell'app partita: "shared" (passa al custode delle condivisioni); scelta non partita: "discard" (file e permesso subito via).
   */
  @Test
  fun ilFileDelRitaglioPassaAChiLoTieneSecondoComeFinisce_R18() = runBlocking<Unit> {
    val lifecycle = Lifecycle()
    stageResult = StagedCutout(uri, lifecycle)
    assertThat(saver().copy(id, ref, subject, outline = false)).isTrue()
    assertThat(lifecycle.events).containsExactly("copied")
    assertThat(saver().share(id, ref, subject, outline = false)).isTrue()
    assertThat(lifecycle.events).containsExactly("copied", "shared").inOrder()
    // Nessuna app che riceva: la scelta non parte e il file si toglie subito.
    shadowOf(context as Application).checkActivities(true)
    assertThat(saver().share(id, ref, subject, outline = false)).isFalse()
    assertThat(lifecycle.events.last()).isEqualTo("discard")
  }

  /** VR-3: il `ClipData` degli appunti porta il segno del ritaglio nostro, leggibile dalla descrizione (da fuori primo piano il contenuto non si legge). */
  @Test
  fun gliAppuntiPortanoIlSegnoDelRitaglioNostro_VR3() = runBlocking<Unit> {
    assertThat(saver().copy(id, ref, subject, outline = false)).isTrue()
    val extras = clip()!!.description.extras!!
    assertThat(extras.getBoolean(CutoutClipMarker.EXTRA)).isTrue()
    assertThat(extras.getBoolean(SensitiveClipboard.EXTRA_IS_SENSITIVE)).isTrue()
  }
}
