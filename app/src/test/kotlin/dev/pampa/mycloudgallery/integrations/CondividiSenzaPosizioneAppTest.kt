package dev.pampa.mycloudgallery.integrations

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.integrations.share.ShareHolder
import dev.pampa.mycloudgallery.feature.integrations.share.ShareLease
import dev.pampa.mycloudgallery.feature.integrations.share.SharePreparation
import dev.pampa.mycloudgallery.feature.integrations.share.ShareStop
import dev.pampa.mycloudgallery.feature.integrations.share.SharedFile
import dev.pampa.mycloudgallery.feature.integrations.share.testing.RecordingUriRevoker
import dev.pampa.mycloudgallery.feature.library.library.LibraryShare
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupShareUi
import dev.pampa.mycloudgallery.gallery.AppLibraryCommands
import dev.pampa.mycloudgallery.gallery.LibraryCommandHost
import dev.pampa.mycloudgallery.integration.GalleryRig
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Correzioni 5B (R4, MC-1, MC-2) con le condivisioni dell'app vere ([AppShares], [AppLibraryCommands], i servizi del viewer di `GalleryGraph`):
 *  - MC-1: la copia consegnata resta dopo che il viewer spegne l'avviso "Pronta" (3,2 secondi) e finche' l'utente non torna nell'app; una scelta
 *    dell'app che non parte, o annullata prima, la toglie subito;
 *  - R4, MC-2: con "Rimuovi la posizione" acceso la selezione della Libreria e il viewer **senza backup** passano dalla preparazione senza
 *    posizione e non usano mai gli originali del MediaStore; spenta, la selezione resta quella di prima.
 * La preparazione e' finta (la vera e' provata in feature-integrations e nel grafo vero: `FiveBIntegrationTest`): qui conta chi la chiama e
 * quando si toglie la copia.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CondividiSenzaPosizioneAppTest {

  @get:Rule val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val rigs = mutableListOf<GalleryRig>()

  @After
  fun tearDown() {
    rigs.forEach { it.close() }
  }

  /** La preparazione finta: ricorda le richieste e risponde "pronta" con una copia vera in una cartella (per vedere quando si toglie). */
  private inner class FakePrepare {
    val calls = ArrayList<Pair<List<MediaId>, Boolean>>()
    val revoker = RecordingUriRevoker()
    var stop: ShareStop? = null
    val dirs = ArrayList<File>()

    suspend fun invoke(ids: List<MediaId>, removeLocation: Boolean, progress: (Int, Int) -> Unit): SharePreparation {
      calls += ids to removeLocation
      progress(ids.size, ids.size)
      stop?.let { return SharePreparation.Stopped(it, 0) }
      val dir = File(temp.root, "share/${calls.size}").apply { mkdirs() }
      val file = File(dir, "foto.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
      dirs += dir
      val uri = Uri.parse("content://prova/${calls.size}")
      return SharePreparation.Ready(listOf(SharedFile(uri, "image/jpeg", 3, file)), ShareLease(listOf(uri), listOf(dir), revoker, null), if (removeLocation) 1 else 0)
    }
  }

  private fun TestScope.shares(prepare: FakePrepare, removeLocation: Boolean = true): AppShares =
    AppShares(app, prepare::invoke, MutableStateFlow(removeLocation), ShareHolder(backgroundScope, StandardTestDispatcher(testScheduler)), backgroundScope)

  private fun id(n: Int): MediaId = MediaId(String.format("00000000-0000-4000-8000-%012d", n))

  // --- MC-1: quando si toglie la copia --------------------------------------------------------------------------------------------

  @Test
  fun mc1_laCopiaConsegnataRestaDopoLAvvisoEFinoAlRitornoNellApp() = runTest {
    val prepare = FakePrepare()
    val shares = shares(prepare)

    shares.viewer.start(listOf(id(1)))
    runCurrent()
    assertThat(shares.viewer.state.value).isInstanceOf(BackupShareUi.Ready::class.java)
    val launch = checkNotNull(shares.launches.value)
    assertThat(launch.origin).isEqualTo(ShareOrigin.VIEWER)
    assertThat(launch.intent.action).isEqualTo(Intent.ACTION_CHOOSER)
    shares.launched(launch, ok = true)
    shares.appLeft() // si apre la scelta dell'app

    // Il viewer spegne l'avviso dopo 3,2 secondi: e' solo lo stato.
    advanceTimeBy(3_200)
    shares.viewer.hideNotice()
    runCurrent()
    assertThat(shares.viewer.state.value).isEqualTo(BackupShareUi.Idle)
    assertThat(prepare.dirs.single().exists()).isTrue()
    // L'utente sceglie la chat con calma, e chiude un foglio qualunque: la copia c'e' ancora, il permesso pure.
    advanceTimeBy(5 * 60_000L)
    shares.viewer.cancel()
    runCurrent()
    assertThat(prepare.dirs.single().exists()).isTrue()
    assertThat(prepare.revoker.revoked).isEmpty()

    // Torna nell'app: un minuto dopo la copia se ne va e il permesso si revoca.
    shares.appReturned()
    advanceTimeBy(ShareHolder.AFTER_RETURN_MS + 1)
    runCurrent()
    assertThat(prepare.dirs.single().exists()).isFalse()
    assertThat(prepare.revoker.revoked).hasSize(1)
  }

  @Test
  fun unaSceltaDellAppCheNonParteOAnnullataPrimaToglieSubitoLaCopia() = runTest {
    val prepare = FakePrepare()
    val shares = shares(prepare)

    shares.viewer.start(listOf(id(1)))
    runCurrent()
    shares.launched(checkNotNull(shares.launches.value), ok = false)
    assertThat(prepare.dirs[0].exists()).isFalse()
    assertThat(shares.viewer.state.value).isEqualTo(BackupShareUi.Idle)

    shares.viewer.start(listOf(id(2)))
    runCurrent()
    assertThat(shares.launches.value).isNotNull()
    shares.viewer.cancel() // "Annulla" prima che l'activity la lanci
    assertThat(shares.launches.value).isNull()
    assertThat(prepare.dirs[1].exists()).isFalse()
  }

  // --- R4, MC-2: la selezione della Libreria e il viewer senza backup ----------------------------------------------------------

  private class Host : LibraryCommandHost {
    var choosers = 0

    override fun requestMediaAccess() = Unit

    override fun requestMediaLocation() = Unit

    override fun startChooser(intent: Intent): Boolean {
      choosers++
      return true
    }

    override fun launchTrashRequest(sender: IntentSender): Boolean = true
  }

  private fun rig(intents: FakePhoneMediaIntents): GalleryRig = GalleryRig(
    app = app,
    replica = File(temp.root, "replica"),
    preferences = File(temp.root, "library.preferences_pb"),
    store = GalleryRig.newStore(),
    access = GalleryRig.accessOf(MediaAccess.FULL),
    intents = intents,
  ).also { rigs += it }

  @Test
  fun r4_laSelezioneConLImpostazioneAccesaNonUsaMaiGliOriginali() = runTest {
    val intents = FakePhoneMediaIntents()
    val rig = rig(intents)
    val prepare = FakePrepare()
    rig.gallery.shares = shares(prepare, removeLocation = true)
    val host = Host()
    val commands = AppLibraryCommands(rig.gallery) { id -> MediaRef.Phone(GalleryRig.PRIMARY, id.value.takeLast(12).toLong(), "image/jpeg") }
    commands.host = host

    assertThat(commands.share(setOf(id(1), id(2), id(3)))).isTrue()
    runCurrent()

    // Nessun indirizzo del MediaStore, nessun selettore con gli originali: la preparazione senza posizione, e la scelta dell'app della Libreria.
    assertThat(intents.shares).isEmpty()
    assertThat(host.choosers).isEqualTo(0)
    assertThat(prepare.calls.single().first).hasSize(3)
    assertThat(prepare.calls.single().second).isTrue()
    assertThat(rig.gallery.shares!!.launches.value!!.origin).isEqualTo(ShareOrigin.LIBRARY)
  }

  @Test
  fun r4_unaSelezioneFermaHaIlSuoFoglioEConLaPosizioneSoloSeLoSceglie() = runTest {
    val rig = rig(FakePhoneMediaIntents())
    val prepare = FakePrepare().apply { stop = ShareStop.LOCATION_REMAINS }
    rig.gallery.shares = shares(prepare, removeLocation = true)
    val commands = AppLibraryCommands(rig.gallery) { id -> MediaRef.Phone(GalleryRig.PRIMARY, id.value.takeLast(12).toLong(), "image/jpeg") }

    commands.share(setOf(id(1)))
    runCurrent()

    val refused = commands.shareProgress.value as LibraryShare.Refused
    assertThat(refused.canShareWithLocation).isTrue()
    assertThat(rig.gallery.shares!!.launches.value).isNull() // niente parte da solo
    prepare.stop = null
    commands.shareWithLocation()
    runCurrent()
    assertThat(prepare.calls.map { it.second }).containsExactly(true, false).inOrder()
  }

  @Test
  fun conLImpostazioneSpentaLaSelezioneRestaQuellaDiPrima() = runTest {
    val intents = FakePhoneMediaIntents()
    val rig = rig(intents)
    val prepare = FakePrepare()
    rig.gallery.shares = shares(prepare, removeLocation = false)
    val host = Host()
    val commands = AppLibraryCommands(rig.gallery) { id -> MediaRef.Phone(GalleryRig.PRIMARY, id.value.takeLast(12).toLong(), "image/jpeg") }
    commands.host = host

    assertThat(commands.share(setOf(id(1)))).isTrue()

    assertThat(intents.shares).hasSize(1)
    assertThat(host.choosers).isEqualTo(1)
    assertThat(prepare.calls).isEmpty()
  }

  @Test
  fun r4_ilViewerSenzaBackupRispettaLImpostazione() = runTest {
    val rig = rig(FakePhoneMediaIntents())
    assertThat(rig.gallery.backup).isNull()
    val prepare = FakePrepare()
    rig.gallery.shares = shares(prepare, removeLocation = true)

    val actions = checkNotNull(rig.gallery.viewerServices.backupActions)

    // Solo "Condividi": nessun'altra voce del backup compare senza backup.
    assertThat(actions.capabilities.share).isTrue()
    assertThat(actions.capabilities.correct || actions.capabilities.editWith || actions.capabilities.phoneOnlyDelete).isFalse()
    assertThat(actions.removeLocation.value).isTrue()
    actions.share(listOf(id(7)))
    runCurrent()
    assertThat(prepare.calls.single()).isEqualTo(listOf(id(7)) to true)
    assertThat(actions.shareState.value).isInstanceOf(BackupShareUi.Ready::class.java)
  }
}
