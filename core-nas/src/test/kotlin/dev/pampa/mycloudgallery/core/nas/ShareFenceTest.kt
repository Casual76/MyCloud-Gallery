package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.fence.ShareFence
import dev.pampa.mycloudgallery.core.nas.fs.SmbNasHousekeeping
import dev.pampa.mycloudgallery.core.nas.fs.SmbServerSideCopy
import dev.pampa.mycloudgallery.core.nas.fs.SmbShareProbe
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * La recinzione D33 (`ShareFence`): con un account di prova, ogni share fuori da `test`, `test2`, `test-comune` e'
 * rifiutata **prima di qualunque richiesta** (nessun socket, nessun `TREE_CONNECT`), per tutte le vie d'accesso a una
 * share. `NAS` e `Public` sono pubbliche e gli account di prova ci scrivono davvero.
 */
class ShareFenceTest {

  /** Il NAS con le share di prova e quelle di famiglia, tutte scrivibili dall'account `test` (come sul NAS vero). */
  private fun backend() = InMemorySmbBackend().apply {
    addUser("test", GOOD_PASSWORD)
    addUser("alessio", "password-di-alessio")
    for (share in listOf("test", "test2", "test-comune", "NAS", "Public", "alessio", "pampa")) {
      addShare(share, mapOf("test" to SimAccess.READ_WRITE, "alessio" to SimAccess.READ_WRITE))
    }
    put("Public", "foto/a.jpg", byteArrayOf(1))
    put("test", "mcg-it/a.jpg", byteArrayOf(1))
  }

  private fun TestScope.fenced(user: String = "test"): Harness {
    val backend = backend()
    val h = Harness(backgroundScope, virtualClock(), backend = backend, shareGuard = ShareFence)
    h.account = NasAccount(NasAddress("192.168.178.58"), user, "slot-nas", backend.serverGuid)
    h.password = if (user.trim().lowercase() == "alessio") "password-di-alessio" else GOOD_PASSWORD
    return h
  }

  /** Ogni via d'accesso a [share]: tutte rifiutate con `PermissionDenied`. */
  private suspend fun everyRouteIsRefused(manager: SmbNasSessionManager, share: String) {
    val path = SharePath.of("foto", "a.jpg")
    assertThat(manager.withShare(share, Purpose.INTERACTIVE) { error("non deve partire") }.errorOrNull()).isEqualTo(NasError.PermissionDenied)
    val fs = manager.fileSystem(share)
    assertThat(assertThrows(NasIoException::class.java) { fs.read(path, 10) }.error).isEqualTo(NasError.PermissionDenied)
    assertThat(assertThrows(NasIoException::class.java) { fs.list(SharePath.of("foto"), 10) }.error).isEqualTo(NasError.PermissionDenied)
    assertThat(assertThrows(NasIoException::class.java) { fs.publishExclusive(SharePath.of("x"), byteArrayOf(1)) }.error)
      .isEqualTo(NasError.PermissionDenied)
    assertThat(manager.browser(share).list(null, 10).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    val reader = manager.mediaReader()
    assertThat(reader.list(share, null, Purpose.BACKGROUND) { error("niente pagine") }.errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(reader.stat(share, path, Purpose.BACKGROUND).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(reader.open(share, path, Purpose.BACKGROUND).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    val copy = SmbServerSideCopy(manager)
    assertThat(copy.copy("test", SharePath.of("mcg-it", "a.jpg"), share, SharePath.of("b.jpg")).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(copy.copy(share, path, "test", SharePath.of("mcg-it", "b.jpg")).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    // La prova della share la dice "negata" (senza toccarla).
    assertThat(SmbShareProbe(manager).probe(share)).isEqualTo(NasResult.Ok(ShareAccess.DENIED))
    assertThat(SmbNasHousekeeping(manager) { listOf(share) }.purgeOwnStaleTemps(DEVICE, 1.minutes).errorOrNull())
      .isEqualTo(NasError.PermissionDenied)
    // Anche dentro un'operazione gia' partita su una share ammessa (copia: la seconda share), prima del TREE_CONNECT.
    assertThat(manager.runOp("test", Purpose.BACKGROUND, idempotent = true, touchesDisk = false) { it.otherShare(share) }.errorOrNull())
      .isEqualTo(NasError.PermissionDenied)
  }

  @Test
  fun lesharePubblicheEDiFamigliaSonoRifiutatePrimaDiQualunqueRichiesta() = runTest {
    val h = fenced()
    for (share in listOf("Public", "NAS", "public", "ＮＡＳ", "alessio", "pampa", "Alessio")) {
      everyRouteIsRefused(h.manager, share)
    }
    // Solo la share "test" ha visto richieste (la copia e il runOp con la seconda share rifiutata).
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(1)
    assertThat(h.backend.treeConnects.get()).isEqualTo(1)
    assertThat(h.backend.exists("Public", "b.jpg")).isFalse()
  }

  @Test
  fun nessunSocketSeLaPrimaRichiestaEVersoUnaSharePubblica() = runTest {
    val h = fenced()
    assertThat(h.manager.withShare("Public", Purpose.INTERACTIVE) { }.errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(h.manager.mediaReader().list("NAS", null, Purpose.BACKGROUND) {}.errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(0)
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(0)
    assertThat(h.backend.shareOps.get()).isEqualTo(0)
  }

  @Test
  fun leShareDiProvaPassanoAncheConLeMaiuscoleMaNonInAltreForme() = runTest {
    val h = fenced()
    for (share in listOf("test", "Test", "TEST2", "test-comune")) {
      assertThat(h.manager.withShare(share, Purpose.INTERACTIVE) { }).isInstanceOf(NasResult.Ok::class.java)
    }
    // Lettere a larghezza piena, spazi: per Samba sono altre share, quindi non di prova.
    for (share in listOf("ｔｅｓｔ", "test ", " test", "test-comune2")) {
      assertThat(h.manager.withShare(share, Purpose.INTERACTIVE) { }.errorOrNull()).isEqualTo(NasError.PermissionDenied)
    }
  }

  @Test
  fun unAccountDiProvaScrittoInAltreFormeERecintatoGliAltriNo() = runTest {
    assertThat(ShareFence.isTestAccount("TEST")).isTrue()
    assertThat(ShareFence.isTestAccount(" test2 ")).isTrue()
    assertThat(ShareFence.isTestAccount("ｔｅｓｔ")).isTrue()
    assertThat(ShareFence.isTestAccount("alessio")).isFalse()
    assertThat(ShareFence.allows("Test", "Public")).isFalse()
    assertThat(ShareFence.allows("alessio", "Public")).isTrue()

    val family = fenced(user = "alessio")
    assertThat(family.manager.withShare("Public", Purpose.INTERACTIVE) { }).isInstanceOf(NasResult.Ok::class.java)
  }

  @Test
  fun senzaRecinzioneNienteCambia() = runTest {
    val backend = backend()
    val h = Harness(backgroundScope, virtualClock(), backend = backend)
    assertThat(h.manager.withShare("Public", Purpose.INTERACTIVE) { }).isInstanceOf(NasResult.Ok::class.java)
  }
}
