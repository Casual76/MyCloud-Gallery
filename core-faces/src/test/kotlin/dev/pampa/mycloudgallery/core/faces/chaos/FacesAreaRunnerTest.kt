package dev.pampa.mycloudgallery.core.faces.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il codice del NAS vero (guardia, trasporto recintato, oracolo, pulizia, autotest, runner) sopra il simulatore SMB, senza rete (docs/prova-500.md Parte F,
 * §F8): lo stesso che `FacesNasIntegrationTest` lancia contro il NAS di casa. Prova che il runner fa i suoi giri, che tocca solo `mcg-it/<giro>/` della share
 * privata di prova, che l'area comune resta senza niente, che un login rifiutato ferma tutto al primo tentativo e che la cartella del giro si toglie.
 */
class FacesAreaRunnerTest {

  @Test
  fun lAutotestDellaGuardiaRifiutaTuttoSenzaToccareIlVero() {
    val (area, _) = FacesAreas.simulated()
    val self = FacesAreaSelfTest.run(area.guard)
    assertWithMessage(self.failures.joinToString("\n")).that(self.failures).isEmpty()
    assertThat(self.cases).isAtLeast(80)
  }

  @Test
  fun ilRunnerFaIGiriTocca_soloIlMondoDelGiroEPuliscePoi(): Unit = runBlocking {
    val (area, backend) = FacesAreas.simulated()
    val result = FacesAreaRunner.run(area, FacesAreaRunner.Settings(interruptions = 4, baseSeed = 20261029L, config = FacesAreaRunner.defaultConfig(real = false)))
    assertWithMessage(result.failureText()).that(result.failures).isEmpty()
    assertThat(result.stoppedBecause).isNull()
    assertThat(result.stats.totalFired).isAtLeast(4)
    // L'area comune: niente, mai. La privata: niente fuori da mcg-it/<giro>/.
    assertThat(backend.fileCount("test-comune")).isEqualTo(0)
    val outside = backend.children("test", "").filter { it != FacesAreaGuard.ROOT }
    assertWithMessage("cartelle fuori da mcg-it nella share privata: $outside").that(outside).isEmpty()
    val report = area.finish()
    assertWithMessage(report.text()).that(report.clean).isTrue()
    assertThat(backend.fileCount("test")).isEqualTo(0)
    assertThat(NasLayout.FACES_DIR.value).isEqualTo(".mycloudgallery/faces")
  }

  @Test
  fun unLoginRifiutatoFermaTuttoAlPrimoTentativo(): Unit = runBlocking {
    val (area, backend) = FacesAreas.simulated(password = "sbagliata")
    val before = backend.authenticateCalls.get()
    val result = FacesAreaRunner.run(area, FacesAreaRunner.Settings(interruptions = 4, baseSeed = 1L, config = FacesAreaRunner.defaultConfig(real = false)))
    assertThat(result.stoppedBecause).isNotNull()
    assertThat(area.latch.failed).isTrue()
    // Un tentativo solo: nessun nuovo tentativo dopo il rifiuto.
    assertThat(backend.authenticateCalls.get() - before).isEqualTo(1)
  }

  @Test
  fun ilVerificatoreDalManifestoVedeUnAreaSanaEUnaRotta() {
    val (area, backend) = FacesAreas.simulated()
    val sha = ChaosWorld.sha256("ritratto-sintetico".toByteArray())
    backend.put("test", "mcg-campo/Backup/ritratto-01.jpg", "ritratto-sintetico".toByteArray())
    val manifest = FacesManifest.parse(
      listOf(
        "# mcg-faces-manifest 1",
        "run=${FacesAreas.SIM_RUN}",
        "private=test|mcg-campo/Backup",
        "phone=$sha|18|ritratto-01",
      ),
    )
    fun verify(phase: String): FacesManifestVerification {
      val latch = FacesAuthLatch()
      val connection = FacesConnection("test", FacesAreas.SIM_PASSWORD.toCharArray(), "192.168.178.58", backend.serverGuid, backend.binding("pc"))
      val readOnly = FacesReadOnlyArea(FacesReadOnlyTransport(backend, area.guard, FacesManifestVerifier.readable(manifest), latch), connection, latch)
      return readOnly.open().use { FacesManifestVerifier.verify(it, manifest, phase) }
    }
    assertThat(verify(FacesManifestVerifier.COMPUTED).ok).isTrue()
    assertThat(verify(FacesManifestVerifier.ERASED).ok).isTrue()
    // Un file dei volti nell'area comune, un media cambiato e un nome estraneo sotto faces/: tre violazioni.
    backend.put("test-comune", ".mycloudgallery/faces/AAAA/x", ByteArray(4))
    backend.put("test", "mcg-campo/Backup/ritratto-01.jpg", "altro".toByteArray())
    backend.put("test", ".mycloudgallery/faces/estraneo.txt", ByteArray(4))
    val broken = verify(FacesManifestVerifier.COMPUTED)
    assertThat(broken.violations.map { it.invariant }).containsAtLeast(Invariant.PLACE, Invariant.OUTSIDE)
    assertThat(broken.violations.size).isAtLeast(3)
  }
}
