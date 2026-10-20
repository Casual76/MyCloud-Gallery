package dev.pampa.mycloudgallery.core.nasmedia

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.nasmedia.contract.OwnChanges
import dev.pampa.mycloudgallery.core.nasmedia.contract.OwnRemovals
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R8 della revisione 4B: le operazioni dell'app stessa (eliminare nel cestino del NAS, spostare in un'altra share) non fanno scattare il
 * congelamento anti-ransomware (D10). La franchigia viene dal diario locale delle operazioni ([OwnRemovals], per `fileId`); un file
 * sparito per altra via conta come prima.
 */
@RunWith(AndroidJUnit4::class)
class Review4bScanTest {

  private lateinit var h: ScanHarness

  @After
  fun close() {
    if (::h.isInitialized) h.close()
  }

  /** 1.000 foto in `Foto/` (soglia max(50, 5%) = 50); restituisce i loro `fileId`, nell'ordine. */
  private suspend fun thousand(): List<Long> {
    h = ScanHarness(writeMetadata = false)
    val ids = (0 until 1_000).map { h.photo("Foto/f$it.jpg", seed = it.toLong()) }
    assertThat(h.scan(ScanMode.Full).detected).isEqualTo(1_000)
    return ids
  }

  /** "L'app" mette nel cestino del NAS (cartella dell'app, mai scansionata) le prime [n] foto: una rinomina, stesso `fileId`. */
  private fun trashByApp(n: Int) {
    repeat(n) { h.nas.rename("test", "Foto/f$it.jpg", ".mycloudgallery/trash/dev/files/f$it") }
  }

  @Test
  fun r8_sessantaFotoEliminateDallAppNonCongelanoLaShare() = runTest {
    val ids = thousand()
    trashByApp(60)
    h.ownRemovals = OwnRemovals { share -> if (share == "test") ids.take(60).toSet() else emptySet() }
    val report = h.scan()
    assertThat(report.freeze).isNull()
    // Spariscono come tutte (la Libreria non le mostra piu'), senza allarme.
    assertThat(report.vanished).isEqualTo(60)
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(940)
  }

  @Test
  fun r8_leStesseSessantaFotoSparitePerAltraViaCongelanoComePrima() = runTest {
    val ids = thousand()
    trashByApp(60)
    // Il diario ricorda altre foto (non queste): la franchigia non vale per file con un'altra identita'.
    h.ownRemovals = OwnRemovals { ids.drop(900).toSet() }
    val freeze = checkNotNull(h.scan().freeze)
    assertThat(freeze.reason).isEqualTo(FreezeReason.VANISHED)
    assertThat(freeze.diff.vanished).isEqualTo(60)
  }

  // --- D32 (5B, P14): la franchigia delle versioni (`VersionFranchise` di P8) collegata alla guardia ----------------------------------

  /** Le prime [n] foto riscritte al loro posto (stesso nome e `fileId`, byte nuovi): come una versione messa dall'app o un ransomware. */
  private fun rewriteInPlace(n: Int) {
    h.synthetic.encryptInPlace("test", (0 until n).map { "Foto/f$it.jpg" })
  }

  @Test
  fun d32_centoVersioniMesseDalleVersioniDiQuestoDispositivoNonCongelanoLaShare() = runTest {
    val ids = thousand()
    rewriteInPlace(100)
    h.ownChanges = OwnChanges { share -> if (share == "test") ids.take(100).toSet() else emptySet() }
    assertThat(h.scan().freeze).isNull()
  }

  @Test
  fun d32_leStesseCentoRiscritturePerAltraViaCongelanoComePrima() = runTest {
    val ids = thousand()
    rewriteInPlace(100)
    // Il diario delle versioni ricorda altri file: la franchigia non vale per identita' diverse.
    h.ownChanges = OwnChanges { ids.drop(900).toSet() }
    val freeze = checkNotNull(h.scan().freeze)
    assertThat(freeze.reason).isEqualTo(FreezeReason.CHANGED)
    assertThat(freeze.diff.changed).isEqualTo(100)
  }
}
