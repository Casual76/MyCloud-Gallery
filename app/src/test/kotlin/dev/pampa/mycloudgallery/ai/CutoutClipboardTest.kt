package dev.pampa.mycloudgallery.ai

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.VerifiedModel
import dev.pampa.mycloudgallery.feature.integrations.share.CacheUris
import dev.pampa.mycloudgallery.feature.integrations.share.ShareCache
import dev.pampa.mycloudgallery.feature.integrations.share.ShareHolder
import dev.pampa.mycloudgallery.feature.integrations.share.ShareLease
import dev.pampa.mycloudgallery.feature.integrations.share.UriRevoker
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutClipMarker
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R18 e VR-3: il PNG del ritaglio che Copia e Condividi mettono in `cacheDir/share/` **non resta per sempre** e gli appunti non puntano a un file sparito;
 * VR-2: l'ultimo esito del modello del ritaglio (poco spazio). Tempo virtuale, appunti di Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CutoutClipboardTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val manager: ClipboardManager get() = app.getSystemService(ClipboardManager::class.java)
  private val revoked = ArrayList<Uri>()
  private val revoker = UriRevoker { revoked += it }

  private val cache by lazy { ShareCache(temp.newFolder("cache")) }

  private class Staged(val lease: ShareLease, val dir: File)

  private fun staged(): Staged {
    val dir = cache.newShareDir()
    val file = File(dir, "ritaglio.png").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
    return Staged(ShareLease(listOf(Uri.fromFile(file)), listOf(dir), revoker, cache), dir)
  }

  /** Gli appunti come li lascia `AndroidCutoutSaver.copy`: un indirizzo, il segno "sensibile" e il segno "ritaglio nostro". */
  private fun ourClip(): ClipData = ClipData.newRawUri("Ritaglio", Uri.parse("content://prova/ritaglio.png")).also {
    it.description.extras = PersistableBundle().apply { putBoolean(CutoutClipMarker.EXTRA, true) }
  }

  private fun otherClip(): ClipData = ClipData.newPlainText("altro", "una cosa copiata dall'utente")

  @Test
  fun unRitaglioCopiatoNuovoToglieIlFileDelPrecedente_R18() = runTest {
    val clipboard = CutoutClipboard(app, backgroundScope)
    val first = staged()
    val second = staged()

    clipboard.place(first.lease)
    assertThat(first.dir.exists()).isTrue()
    clipboard.place(second.lease)

    // Negli appunti ne vale uno solo: il file del primo non serve piu' (e il suo permesso si revoca), quello del secondo c'e'.
    assertThat(first.lease.isReleased).isTrue()
    assertThat(first.dir.exists()).isFalse()
    assertThat(revoked).contains(first.lease.granted.single())
    assertThat(second.lease.isReleased).isFalse()
    assertThat(second.dir.exists()).isTrue()
  }

  @Test
  fun passatoIlTempoIlFileSiTogliEGliAppuntiConIlNostroRitaglioSiSvuotano_R18() = runTest {
    manager.setPrimaryClip(ourClip())
    val clipboard = CutoutClipboard(app, backgroundScope, ttlMs = 60_000)
    val one = staged()

    clipboard.place(one.lease)
    advanceTimeBy(59_000)
    runCurrent()
    assertThat(one.lease.isReleased).isFalse()
    assertThat(manager.hasPrimaryClip()).isTrue()

    advanceTimeBy(2_000)
    runCurrent()
    assertThat(one.lease.isReleased).isTrue()
    assertThat(one.dir.exists()).isFalse()
    assertThat(manager.hasPrimaryClip()).isFalse()
  }

  /** Alla scadenza il file si toglie comunque, ma gli appunti che l'utente ha riempito con altro non si toccano: non sono nostri. */
  @Test
  fun allaScadenzaGliAppuntiDiAltroRestanoCosiComeSono_R18() = runTest {
    val clipboard = CutoutClipboard(app, backgroundScope, ttlMs = 1_000)
    val one = staged()
    clipboard.place(one.lease)
    manager.setPrimaryClip(otherClip())

    advanceTimeBy(2_000)
    runCurrent()

    assertThat(one.lease.isReleased).isTrue()
    assertThat(manager.hasPrimaryClip()).isTrue()
    assertThat(manager.primaryClip?.getItemAt(0)?.text?.toString()).isEqualTo("una cosa copiata dall'utente")
  }

  @Test
  fun seGliAppuntiCambianoIlFileNonServePiuESiTogliSubito_R18() = runTest {
    manager.setPrimaryClip(ourClip())
    val clipboard = CutoutClipboard(app, backgroundScope)
    val one = staged()
    clipboard.place(one.lease)

    manager.setPrimaryClip(otherClip())

    assertThat(one.lease.isReleased).isTrue()
    assertThat(one.dir.exists()).isFalse()
  }

  /** All'avvio la pulizia di `share/` ha tolto il file: gli appunti con un ritaglio nostro non devono puntare al niente (VR-3). */
  @Test
  fun alRiavvioGliAppuntiConUnRitaglioNostroSiSvuotanoQuelliDiAltriNo_VR3() {
    manager.setPrimaryClip(ourClip())
    CutoutClipboard.clearIfOurs(app)
    assertThat(manager.hasPrimaryClip()).isFalse()

    manager.setPrimaryClip(otherClip())
    CutoutClipboard.clearIfOurs(app)
    assertThat(manager.hasPrimaryClip()).isTrue()
  }

  /**
   * VR-3: la descrizione degli appunti si legge solo da un'app in primo piano; a processo appena nato non c'e' ancora una schermata. Alla schermata che torna
   * davanti, senza un ritaglio nostro in corso, gli appunti che ne citano uno (il file non c'e' piu') si svuotano; con un ritaglio in corso non si toccano.
   */
  @Test
  fun quandoUnaSchermataTornaDavantiGliAppuntiOrfaniSiSvuotano_VR3() = runTest {
    val clipboard = CutoutClipboard(app, backgroundScope)
    manager.setPrimaryClip(ourClip())

    clipboard.onForeground()
    assertThat(manager.hasPrimaryClip()).isFalse()

    // Con un ritaglio in corso (appena copiato) gli appunti sono giusti cosi'.
    manager.setPrimaryClip(ourClip())
    val one = staged()
    clipboard.place(one.lease)
    clipboard.onForeground()
    assertThat(manager.hasPrimaryClip()).isTrue()
    assertThat(one.lease.isReleased).isFalse()

    // Quelli di altre app non si toccano mai.
    clipboard.release()
    manager.setPrimaryClip(otherClip())
    clipboard.onForeground()
    assertThat(manager.hasPrimaryClip()).isTrue()
  }

  @Test
  fun laRegistrazioneAlCicloDiVitaDelleSchermateFaLoStessoDaSola_VR3() = runTest {
    CutoutClipboard(app, backgroundScope)
    manager.setPrimaryClip(ourClip())

    org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()

    assertThat(manager.hasPrimaryClip()).isFalse()
  }

  private fun TestScope.files(clipboard: CutoutClipboard?, holder: ShareHolder?) =
    CutoutFiles(cache, CacheUris { Uri.fromFile(it) }, io = StandardTestDispatcher(testScheduler), holder = holder, revoker = revoker, clipboard = clipboard)

  @Test
  fun ilFileDelRitaglioSiTogliSecondoComeFinisce_R18() = runTest {
    val holder = ShareHolder(backgroundScope, StandardTestDispatcher(testScheduler), afterReturnMs = 100, maxHoldMs = 100_000)
    val clipboard = CutoutClipboard(app, backgroundScope, ttlMs = 100_000)
    val files = files(clipboard, holder)

    // Non servito: subito.
    val unused = checkNotNull(files.stage(byteArrayOf(1)))
    unused.lifecycle.discard()
    assertThat(cache.shareRoot.listFiles().orEmpty()).isEmpty()

    // Condiviso: lo tiene il custode delle condivisioni, finche' l'altra app non ha finito (ritorno nell'app, poi il margine).
    val shared = checkNotNull(files.stage(byteArrayOf(2)))
    shared.lifecycle.shared()
    assertThat(holder.count).isEqualTo(1)
    assertThat(cache.shareRoot.listFiles().orEmpty()).hasLength(1)
    holder.appLeft()
    holder.appReturned()
    advanceTimeBy(101)
    runCurrent()
    assertThat(holder.count).isEqualTo(0)
    assertThat(cache.shareRoot.listFiles().orEmpty()).isEmpty()

    // Copiato: lo tiene la cartella degli appunti, uno solo alla volta.
    val first = checkNotNull(files.stage(byteArrayOf(3)))
    first.lifecycle.copied()
    val second = checkNotNull(files.stage(byteArrayOf(4)))
    second.lifecycle.copied()
    assertThat(cache.shareRoot.listFiles().orEmpty()).hasLength(1)
  }

  // --- VR-2 ---------------------------------------------------------------------------------------------------------------------

  private val modelId: ModelId = ModelId("prova")

  private class NoFile : ModelFiles {
    var present: VerifiedModel? = null

    override suspend fun open(id: ModelId): VerifiedModel? = present
  }

  @Test
  fun ilModelloDelRitaglioRicordaSeUltimoTentativoERiuscitoPerMancanzaDiSpazio_VR2() = runTest {
    val files = NoFile()
    val results = ArrayDeque(listOf<EnsureResult>(EnsureResult.Failed(ModelFailCode.NO_SPACE), EnsureResult.Failed(ModelFailCode.FETCH_FAILED), EnsureResult.Ready))
    val ensuring = EnsuringModelFiles(files) { results.removeFirst() }

    assertThat(ensuring.lastNoSpace).isFalse()
    assertThat(ensuring.open(modelId)).isNull()
    assertThat(ensuring.lastNoSpace).isTrue()

    // Un altro guasto non e' "poco spazio".
    assertThat(ensuring.open(modelId)).isNull()
    assertThat(ensuring.lastNoSpace).isFalse()

    // Tornato lo spazio il modello si apre e il segno resta spento.
    files.present = null
    assertThat(ensuring.open(modelId)).isNull()
    assertThat(ensuring.lastNoSpace).isFalse()
  }
}
