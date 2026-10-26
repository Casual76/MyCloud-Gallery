package dev.pampa.mycloudgallery.core.albums.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il banco della condivisione DIETRO LA GUARDIA dell'area di prova (docs/prova-500.md Parte E, §E4), senza rete: lo stesso codice che gira sul NAS vero
 * (`ShareArea`, trasporto recintato, oracolo, pulizia) sopra il simulatore SMB. Prova che
 *  - la guardia rifiuta tutto cio' che esce dall'area (account, share, percorsi) e un rifiuto non arriva mai al trasporto vero (autotest);
 *  - il motore vero lavora SOLO sotto `mcg-it/<giro>/r<N>/` (nessun file alla radice di nessuna share, nemmeno la cartella dell'app);
 *  - un login rifiutato ferma il banco subito: UN solo tentativo arriva al NAS, mai un secondo;
 *  - a fine prova la cartella del giro e' pulita e il rapporto dice cosa e' rimasto.
 */
class ShareAreaRunnerTest {

  @Test
  fun lAutotestDellaGuardiaRifiutaTuttoConZeroChiamateAlVero() {
    val credentials = ShareAreaCredentials("192.168.178.58", "test", "x".toCharArray(), "test", "test-comune", null)
    val guard = ShareAreaGuard.of(credentials, ShareAreas.SIM_RUN)
    val result = ShareAreaSelfTest.run(guard)
    assertWithMessage(result.failures.joinToString("\n")).that(result.failures).isEmpty()
    assertThat(result.cases).isAtLeast(80)
  }

  @Test
  fun unFileDelleCredenzialiChePuntaAltroFaFallireLaProvaNonAllargaIlRecinto() {
    fun credentials(user: String = "test", private: String = "test", common: String = "test-comune") =
      ShareAreaCredentials("192.168.178.58", user, "x".toCharArray(), private, common, null)
    val run = ShareAreas.SIM_RUN
    for (bad in listOf(
      credentials(user = "alessio"), credentials(user = "admin"), credentials(user = "test2"), credentials(private = "alessio"), credentials(private = "NAS"),
      credentials(private = "test2"), credentials(common = "Public"), credentials(common = "pampa"), credentials(common = "test2"), credentials(common = "test"),
      credentials(private = "test-comune"),
    )) {
      var refused = false
      try {
        ShareAreaGuard.of(bad, run)
      } catch (e: AreaViolation) {
        refused = true
      }
      assertThat(refused).isTrue()
    }
    ShareAreaGuard.of(credentials(), run)
    ShareAreaGuard.of(credentials(user = "TEST", private = "Test", common = "Test-Comune"), run)
    var hostRefused = false
    try {
      ShareAreaGuard.of(ShareAreaCredentials("nas.local", "test", "x".toCharArray(), "test", "test-comune", null), run)
    } catch (e: AreaViolation) {
      hostRefused = true
    }
    assertThat(hostRefused).isTrue()
  }

  @Test
  fun ilBancoSulSimulatoreDietroLaGuardiaTocca_soloIlSuoMondo_ePulisce() = runBlocking {
    val (area, backend) = ShareAreas.simulated()
    val settings = ShareAreaRunner.Settings(interruptions = 60, baseSeed = 20261026L, config = ShareAreaRunner.defaultConfig(real = false))
    val result = ShareAreaRunner.run(area, settings)
    println(result.summary())
    assertWithMessage(result.failureText()).that(result.failures).isEmpty()
    assertThat(result.stats.totalFired).isAtLeast(60)
    // Tutto e' sotto mcg-it/<giro>/: nessuna cartella dell'app, nessun media alla radice di una share di prova.
    for (share in listOf("test", "test-comune")) {
      assertWithMessage("alla radice di $share").that(backend.children(share, "")).containsExactly("mcg-it")
    }
    // Ogni mondo si e' ripulito da solo alla chiusura, e la pulizia finale toglie la cartella del giro.
    assertThat(area.cleanups).isNotEmpty()
    assertWithMessage(area.cleanups.filterNot { it.clean }.joinToString { it.text() }).that(area.cleanups.all { it.clean }).isTrue()
    val finish = area.finish()
    println("pulizia finale: ${finish.text()}")
    assertThat(finish.clean).isTrue()
    for (share in listOf("test", "test-comune")) assertThat(backend.children(share, "mcg-it")).isEmpty()
  }

  @Test
  fun unLoginRifiutatoFermaIlBancoSubito_unSoloTentativo() = runBlocking {
    val (area, backend) = ShareAreas.simulated(password = "sbagliata")
    val settings = ShareAreaRunner.Settings(interruptions = 50, baseSeed = 1L, config = ShareAreaRunner.defaultConfig(real = false))
    val result = ShareAreaRunner.run(area, settings)
    println(result.summary())
    assertThat(result.stoppedBecause).contains("login rifiutato")
    // Il banco si e' fermato al primo giro: il resto dei 205 giri possibili non e' partito.
    assertThat(result.rounds).isEqualTo(1)
    assertThat(area.latch.failed).isTrue()
    assertWithMessage("tentativi di login arrivati al NAS").that(backend.authenticateCalls.get()).isEqualTo(1)
  }
}
