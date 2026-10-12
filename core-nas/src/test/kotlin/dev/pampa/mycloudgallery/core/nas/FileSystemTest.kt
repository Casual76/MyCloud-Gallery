package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.fs.SmbNasHousekeeping
import dev.pampa.mycloudgallery.core.nas.fs.SmbShareProbe
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnectionLostException
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.nas.testing.TcpBehaviour
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class FileSystemTest {

  private fun TestScope.harness() = Harness(backgroundScope, virtualClock())

  private val registry1: SharePath = NasLayout.registryFile(1)
  private val tmp = "${TrustFormat.ROOT_DIR}/tmp"

  // --- publishExclusive ----------------------------------------------------------------------------------------

  @Test
  fun pubblicaUnFileNuovoSenzaLasciareTemporanei() = runTest {
    val h = harness()
    val fs = h.manager.fileSystem(SHARE)
    assertThat(fs.publishExclusive(registry1, byteArrayOf(1, 2, 3))).isEqualTo(PublishResult.CREATED)
    assertThat(h.backend.bytes(SHARE, registry1.value)).isEqualTo(byteArrayOf(1, 2, 3))
    assertThat(h.backend.children(SHARE, tmp)).isEmpty()
  }

  @Test
  fun collisioneNonSovrascriveECancellaIlProprioTemporaneo() = runTest {
    val h = harness()
    val fs = h.manager.fileSystem(SHARE)
    fs.publishExclusive(registry1, byteArrayOf(1))
    assertThat(fs.publishExclusive(registry1, byteArrayOf(9, 9))).isEqualTo(PublishResult.ALREADY_EXISTS)
    assertThat(h.backend.bytes(SHARE, registry1.value)).isEqualTo(byteArrayOf(1))
    assertThat(h.backend.children(SHARE, tmp)).isEmpty()
  }

  @Test
  fun nomiCheDifferisconoSoloPerLeMaiuscoleSonoLoStessoFile() = runTest {
    val h = harness()
    val fs = h.manager.fileSystem(SHARE)
    h.backend.put(SHARE, "Foto/Vacanze.JPG", byteArrayOf(7))
    assertThat(fs.publishExclusive(SharePath.of("foto", "vacanze.jpg"), byteArrayOf(1))).isEqualTo(PublishResult.ALREADY_EXISTS)
    assertThat(h.backend.bytes(SHARE, "Foto/Vacanze.JPG")).isEqualTo(byteArrayOf(7))
    // Si legge con qualunque combinazione; l'elenco riporta il nome come scritto.
    assertThat((fs.read(SharePath.of("FOTO", "vacanze.jpg"), 10) as NasRead.Data).bytes).isEqualTo(byteArrayOf(7))
    assertThat(fs.list(SharePath.of("foto"), 10).names).containsExactly("Vacanze.JPG")
    assertThat(h.backend.children(SHARE, tmp)).isEmpty()
  }

  @Test
  fun cadutaTraScritturaERinominaSiRiprovaESiPulisce() = runTest {
    val h = harness()
    var crashed = false
    h.backend.beforeOp = { op, _ ->
      if (op == SimOp.RENAME && !crashed) {
        crashed = true
        h.backend.dropAllConnections()
        throw SmbConnectionLostException()
      }
    }
    val fs = h.manager.fileSystem(SHARE)
    assertThat(fs.publishExclusive(registry1, byteArrayOf(4, 5, 6))).isEqualTo(PublishResult.CREATED)
    assertThat(h.backend.bytes(SHARE, registry1.value)).isEqualTo(byteArrayOf(4, 5, 6))
    // Il temporaneo del primo tentativo (rimasto: la connessione era caduta) e' stato cancellato.
    assertThat(h.backend.children(SHARE, tmp)).isEmpty()
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(2)
  }

  @Test
  fun rinominaRiuscitaMaRispostaPersaVieneRiconosciuta() = runTest {
    val h = harness()
    val bytes = byteArrayOf(1, 2, 3, 4)
    var crashed = false
    h.backend.beforeOp = { op, _ ->
      if (op == SimOp.RENAME && !crashed) {
        crashed = true
        // Il NAS ha eseguito la rinomina (simulata qui), poi la connessione cade prima della risposta.
        h.backend.put(SHARE, registry1.value, bytes)
        h.backend.dropAllConnections()
        throw SmbConnectionLostException()
      }
    }
    assertThat(h.manager.fileSystem(SHARE).publishExclusive(registry1, bytes)).isEqualTo(PublishResult.CREATED)
    assertThat(h.backend.children(SHARE, tmp)).isEmpty()
  }

  @Test
  fun rinominaIncertaEFileAltruiRestaAlreadyExists() = runTest {
    val h = harness()
    var crashed = false
    h.backend.beforeOp = { op, _ ->
      if (op == SimOp.RENAME && !crashed) {
        crashed = true
        h.backend.put(SHARE, registry1.value, byteArrayOf(9)) // un altro dispositivo e' arrivato prima
        h.backend.dropAllConnections()
        throw SmbConnectionLostException()
      }
    }
    assertThat(h.manager.fileSystem(SHARE).publishExclusive(registry1, byteArrayOf(1))).isEqualTo(PublishResult.ALREADY_EXISTS)
    assertThat(h.backend.bytes(SHARE, registry1.value)).isEqualTo(byteArrayOf(9))
    assertThat(h.backend.children(SHARE, tmp)).isEmpty()
  }

  @Test
  fun processoMortoTraScritturaERinominaLaPuliziaTogliSoloIPropriTemporaneiVecchi() = runTest {
    val h = harness()
    val now = h.backend.nasTime()
    val old = now - 3.hours.inWholeMilliseconds
    val mine = "$DEVICE-0123456789abcdef0123456789abcdef"
    val mineRecent = "$DEVICE-fedcba9876543210fedcba9876543210"
    val other = "zyxwvutsrqponmlkjihgfedcba-0123456789abcdef0123456789abcdef"
    h.backend.put(SHARE, "$tmp/$mine", ByteArray(10), lastWriteMs = old)
    h.backend.put(SHARE, "$tmp/$mineRecent", ByteArray(10), lastWriteMs = now)
    h.backend.put(SHARE, "$tmp/$other", ByteArray(10), lastWriteMs = old)
    h.backend.put(SHARE, "$tmp/$DEVICE-non-esadecimale", ByteArray(10), lastWriteMs = old)
    h.backend.put(SHARE, "$tmp/pending-0123456789abcdef0123456789abcdef", ByteArray(10), lastWriteMs = old)

    val housekeeping = SmbNasHousekeeping(h.manager) { listOf(SHARE, READ_ONLY_SHARE) }
    assertThat(housekeeping.purgeOwnStaleTemps(DEVICE, 1.hours).value()).isEqualTo(1)
    assertThat(h.backend.children(SHARE, tmp)).containsExactly(
      mineRecent,
      other,
      "$DEVICE-non-esadecimale",
      "pending-0123456789abcdef0123456789abcdef",
    )
    assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { housekeeping.purgeOwnStaleTemps("../altro", 1.hours) }
    }
  }

  @Test
  fun pubblicazioneSuShareInSolaLetturaEPermessoNegato() = runTest {
    val h = harness()
    val error = assertThrows(NasIoException::class.java) {
      h.manager.fileSystem(READ_ONLY_SHARE).publishExclusive(registry1, byteArrayOf(1))
    }.error
    assertThat(error).isEqualTo(NasError.PermissionDenied)
  }

  // --- read ---------------------------------------------------------------------------------------------------

  @Test
  fun dimensioneDichiarataEnormeNessunaLetturaNessunaAllocazione() = runTest {
    val h = harness()
    h.backend.put(SHARE, "bomba", ByteArray(16))
    h.backend.declareSize(SHARE, "bomba", 1L shl 50)
    val fs = h.manager.fileSystem(SHARE)
    // Con un tetto di 1 GiB: se si fidasse della dimensione allocherebbe 1 PiB (OutOfMemoryError).
    assertThat(fs.read(SharePath.of("bomba"), 1 shl 30)).isEqualTo(NasRead.TooLarge)
    assertThat(h.backend.readCalls.get()).isEqualTo(0)
    assertThat(h.backend.bytesServed.get()).isEqualTo(0)
  }

  @Test
  fun serverCheMenteSullaDimensioneNonFaLeggereOltreIlTetto() = runTest {
    val h = harness()
    h.backend.put(SHARE, "lungo", ByteArray(100_000) { it.toByte() })
    h.backend.declareSize(SHARE, "lungo", 10)
    val fs = h.manager.fileSystem(SHARE)
    assertThat(fs.read(SharePath.of("lungo"), 4096)).isEqualTo(NasRead.TooLarge)
    assertThat(h.backend.bytesServed.get()).isAtMost(4097L)
  }

  @Test
  fun letturaEsattaETetto() = runTest {
    val h = harness()
    val content = ByteArray(300_000) { (it * 7).toByte() }
    h.backend.put(SHARE, "dati", content)
    val fs = h.manager.fileSystem(SHARE)
    assertThat((fs.read(SharePath.of("dati"), content.size) as NasRead.Data).bytes).isEqualTo(content)
    assertThat(fs.read(SharePath.of("dati"), content.size - 1)).isEqualTo(NasRead.TooLarge)
    assertThat(fs.read(SharePath.of("manca"), 10)).isEqualTo(NasRead.NotFound)
    assertThat(fs.read(SharePath.of("cartella", "manca"), 10)).isEqualTo(NasRead.NotFound)
    h.backend.put(SHARE, "vuoto", ByteArray(0))
    assertThat((fs.read(SharePath.of("vuoto"), 0) as NasRead.Data).bytes).isEmpty()
  }

  // --- list ---------------------------------------------------------------------------------------------------

  @Test
  fun elencoTroncatoAlLimite() = runTest {
    val h = harness()
    repeat(10) { h.backend.put(SHARE, "cartella/f$it", byteArrayOf(1)) }
    val fs = h.manager.fileSystem(SHARE)
    val first = fs.list(SharePath.of("cartella"), 3)
    assertThat(first.names).hasSize(3)
    assertThat(first.truncated).isTrue()
    val all = fs.list(SharePath.of("cartella"), 10)
    assertThat(all.names).hasSize(10)
    assertThat(all.truncated).isFalse()
    assertThat(fs.list(SharePath.of("cartella"), 0).truncated).isTrue()
    assertThat(fs.list(SharePath.of("non-c-e"), 10).names).isEmpty()
    assertThat(fs.list(SharePath.of("cartella", "f1"), 10).names).isEmpty()
  }

  @Test
  fun ilBrowserElencaAPagineInSolaLettura() = runTest {
    val h = harness()
    h.backend.put(SHARE, "album/a.jpg", ByteArray(5), lastWriteMs = 1_000)
    h.backend.put(SHARE, "album/sotto/b.jpg", ByteArray(5))
    val page = h.manager.browser(SHARE).list(SharePath.of("album"), 1).value()
    assertThat(page.entries).hasSize(1)
    assertThat(page.truncated).isTrue()
    val full = h.manager.browser(SHARE).list(SharePath.of("album"), 10).value()
    assertThat(full.entries.associate { it.name to it.isDirectory }).containsExactly("a.jpg", false, "sotto", true)
    assertThat(full.entries.first { it.name == "a.jpg" }.let { it.size to it.modifiedAt }).isEqualTo(5L to 1_000L)
    assertThat(h.manager.browser(SHARE).list(null, 10).value().entries.map { it.name }).containsExactly("album")
    assertThat(h.manager.browser(SHARE).list(SharePath.of("manca"), 10).errorOrNull()).isEqualTo(NasError.NotFound)
  }

  // --- Copia lato server --------------------------------------------------------------------------------------

  @Test
  fun copiaLatoServerTraShareSenzaFarPassareIByteDalTelefono() = runTest {
    val h = harness()
    h.backend.addShare("privata-test", mapOf(USER to dev.pampa.mycloudgallery.core.nas.testing.SimAccess.READ_WRITE))
    val content = ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }
    h.backend.put("privata-test", "foto/IMG_1.jpg", content)
    val copy = dev.pampa.mycloudgallery.core.nas.fs.SmbServerSideCopy(h.manager)
    val from = SharePath.of("foto", "IMG_1.jpg")
    val to = SharePath.of("comune", "IMG_1.jpg")

    assertThat(copy.copy("privata-test", from, SHARE, to).value()).isEqualTo(content.size.toLong())
    assertThat(h.backend.bytes(SHARE, "comune/IMG_1.jpg")).isEqualTo(content)
    assertThat(h.backend.bytes("privata-test", "foto/IMG_1.jpg")).isEqualTo(content)
    assertThat(h.backend.bytesServed.get()).isEqualTo(0)

    // Mai sovrascrivere: il nome esiste gia' (anche con maiuscole diverse).
    assertThat(copy.copy("privata-test", from, SHARE, SharePath.of("COMUNE", "img_1.JPG")).errorOrNull()).isEqualTo(NasError.Exists)
    assertThat(h.backend.children(SHARE, tmp)).isEmpty()
    assertThat(copy.copy("privata-test", SharePath.of("manca.jpg"), SHARE, SharePath.of("x")).errorOrNull()).isEqualTo(NasError.NotFound)
    assertThat(copy.copy("non-esiste", from, SHARE, SharePath.of("y")).errorOrNull()).isEqualTo(NasError.ShareMissing("non-esiste"))
  }

  // --- ShareProbe ---------------------------------------------------------------------------------------------

  @Test
  fun provaDelleShareSenzaCreareFile() = runTest {
    val h = harness()
    val probe = SmbShareProbe(h.manager)
    val before = h.backend.fileCount(SHARE)
    assertThat(probe.probe(SHARE).value()).isEqualTo(ShareAccess.READ_WRITE)
    assertThat(probe.probe(READ_ONLY_SHARE).value()).isEqualTo(ShareAccess.READ_ONLY)
    assertThat(probe.probe(FORBIDDEN_SHARE).value()).isEqualTo(ShareAccess.DENIED)
    assertThat(probe.probe("non-esiste").value()).isEqualTo(ShareAccess.MISSING)
    assertThat(h.backend.fileCount(SHARE)).isEqualTo(before)
    assertThat(h.backend.children(SHARE, "")).isEmpty()
  }

  @Test
  fun unaShareIrraggiungibileNonEUnaShareAssente() = runTest {
    val h = harness()
    h.backend.tcp = TcpBehaviour.REFUSE
    assertThat(SmbShareProbe(h.manager).probe(SHARE).errorOrNull()).isEqualTo(NasError.ServiceDown)
  }

  // --- withShare --------------------------------------------------------------------------------------------

  @Test
  fun withShareDaAlBloccoLaShareGiaConnessaEGliErroriComeRisultato() = runTest {
    val h = harness()
    val result = h.manager.withShare(SHARE, Purpose.BACKGROUND) { scope ->
      scope.fileSystem.publishExclusive(registry1, byteArrayOf(1))
      scope.fileSystem.read(SharePath.of("non-c-e", "x"), 10)
    }
    assertThat(result.value()).isEqualTo(NasRead.NotFound)
    val failed = h.manager.withShare(READ_ONLY_SHARE, Purpose.BACKGROUND) { scope ->
      scope.fileSystem.publishExclusive(registry1, byteArrayOf(1))
    }
    assertThat(failed.errorOrNull()).isEqualTo(NasError.PermissionDenied)
    var ran = false
    assertThat(h.manager.withShare("non-esiste", Purpose.BACKGROUND) { ran = true }.errorOrNull())
      .isEqualTo(NasError.ShareMissing("non-esiste"))
    assertThat(ran).isFalse()
  }
}
