package dev.pampa.mycloudgallery.feature.update.policy

import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.compareVersions
import dev.antigravity.fluidengine.foundation.isStableVersionNewer
import dev.pampa.mycloudgallery.feature.update.source.UpdateSourceConfig
import org.junit.Test

/**
 * Pacchetto U: le regole dell'app sopra l'engine (C480, C481, C484, C485). Ogni controllo di sicurezza ha qui una prova che cade se lo si toglie
 * (mutazioni provate e descritte in docs/aggiornamenti-in-app.md, «Prove»).
 */
class UpdateRulesTest {

  private val sha = "a".repeat(64)

  /** Un aggiornamento con la forma che `EngineAppUpdater.check` costruisce da un elenco di `publisher.py` con lo sha256 aggiunto. */
  private fun offered(
    version: String = "1.1.0",
    tag: String = "stable-mycloud-gallery-v$version",
    asset: String = "mycloud-gallery-$version.apk",
    url: String = UpdateSourceConfig.downloadUrl(tag, asset),
    size: Long = 115_000_000L,
    sha256: String = sha,
  ) = AvailableAppUpdate(version = version, changelog = "", releaseTag = tag, apkAsset = asset, downloadUrl = url, sizeBytes = size, sha256 = sha256)

  private val installed = PackageFacts("dev.pampa.mycloudgallery", versionCode = 5, versionName = "1.1.0-beta.2", signers = setOf("ab".repeat(32)))

  // --- L'offerta ------------------------------------------------------------------------------------------------------------------------------------

  @Test
  fun unAggiornamentoBenPubblicatoPassa() {
    assertThat(UpdateRules.offerProblem(offered())).isNull()
    assertThat(UpdateRules.offerProblem(offered(version = "1.1.0-beta.3", tag = "beta-mycloud-gallery-v1.1.0-beta.3"))).isNull()
  }

  @Test
  fun senzaSha256NonSiScarica() {
    assertThat(UpdateRules.offerProblem(offered(sha256 = ""))).isEqualTo(UpdateProblem.CHECKSUM_MISSING)
    assertThat(UpdateRules.offerProblem(offered(sha256 = "   "))).isEqualTo(UpdateProblem.CHECKSUM_MISSING)
    assertThat(UpdateRules.offerProblem(offered(sha256 = "abc123"))).isEqualTo(UpdateProblem.CHECKSUM_MISSING)
    assertThat(UpdateRules.offerProblem(offered(sha256 = "g".repeat(64)))).isEqualTo(UpdateProblem.CHECKSUM_MISSING)
  }

  @Test
  fun senzaPesoNonSiScaricaEOltreIlTettoNemmeno() {
    assertThat(UpdateRules.offerProblem(offered(size = 0))).isEqualTo(UpdateProblem.SIZE_MISSING)
    assertThat(UpdateRules.offerProblem(offered(size = -1))).isEqualTo(UpdateProblem.SIZE_MISSING)
    assertThat(UpdateRules.offerProblem(offered(size = UpdateSourceConfig.MAX_APK_BYTES + 1))).isEqualTo(UpdateProblem.TOO_LARGE)
    assertThat(UpdateRules.offerProblem(offered(size = UpdateSourceConfig.MAX_APK_BYTES))).isNull()
  }

  @Test
  fun nomiConPercorsiOCaratteriStraniNonPassano() {
    for (asset in listOf("../../files/x.apk", "dir/x.apk", "x .apk", "x.zip", "..apk", "", ".hidden.apk")) {
      assertThat(UpdateRules.offerProblem(offered(asset = asset))).isEqualTo(UpdateProblem.BAD_NAME)
    }
    for (tag in listOf("v1/../../other", "", "a b", "../x")) {
      assertThat(UpdateRules.offerProblem(offered(tag = tag))).isEqualTo(UpdateProblem.BAD_NAME)
    }
  }

  @Test
  fun lIndirizzoDeveEssereQuelloDelRepositoryDelleRelease() {
    val other = "https://github.com/qualcuno/altro/releases/download/stable-mycloud-gallery-v1.1.0/mycloud-gallery-1.1.0.apk"
    assertThat(UpdateRules.offerProblem(offered(url = other))).isEqualTo(UpdateProblem.OTHER_SOURCE)
    val http = offered().downloadUrl.replace("https://", "http://")
    assertThat(UpdateRules.offerProblem(offered(url = http))).isEqualTo(UpdateProblem.OTHER_SOURCE)
  }

  // --- Il file scaricato ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun loSha256DelFileDeveEssereQuelloDellElenco() {
    assertThat(UpdateRules.checksumProblem(sha, sha)).isNull()
    assertThat(UpdateRules.checksumProblem(sha.uppercase(), " $sha ")).isNull()
    assertThat(UpdateRules.checksumProblem(sha, "b".repeat(64))).isEqualTo(UpdateProblem.CHECKSUM_MISMATCH)
    assertThat(UpdateRules.checksumProblem(sha, "")).isEqualTo(UpdateProblem.CHECKSUM_MISMATCH)
  }

  @Test
  fun unApkIllegibileODiUnAltraAppNonSiInstalla() {
    assertThat(UpdateRules.archiveProblem(installed, null)).isEqualTo(UpdateProblem.UNREADABLE)
    assertThat(UpdateRules.archiveProblem(installed, installed.copy(packageName = "com.altra.app", versionCode = 9)))
      .isEqualTo(UpdateProblem.OTHER_APP)
  }

  @Test
  fun versionCodeStrettamenteMaggiore_nienteRitorniIndietro() {
    assertThat(UpdateRules.archiveProblem(installed, installed.copy(versionCode = 5))).isEqualTo(UpdateProblem.NOT_NEWER)
    assertThat(UpdateRules.archiveProblem(installed, installed.copy(versionCode = 4))).isEqualTo(UpdateProblem.NOT_NEWER)
    assertThat(UpdateRules.archiveProblem(installed, installed.copy(versionCode = 6))).isNull()
  }

  @Test
  fun laFirmaDeveEssereQuellaDellAppInstallata() {
    val other = installed.copy(versionCode = 6, signers = setOf("cd".repeat(32)))
    assertThat(UpdateRules.archiveProblem(installed, other)).isEqualTo(UpdateProblem.OTHER_SIGNATURE)
    val more = installed.copy(versionCode = 6, signers = installed.signers + "cd".repeat(32))
    assertThat(UpdateRules.archiveProblem(installed, more)).isEqualTo(UpdateProblem.OTHER_SIGNATURE)
    assertThat(UpdateRules.archiveProblem(installed, installed.copy(versionCode = 6, signers = emptySet()))).isEqualTo(UpdateProblem.OTHER_SIGNATURE)
    assertThat(UpdateRules.archiveProblem(installed.copy(signers = emptySet()), installed.copy(versionCode = 6, signers = emptySet())))
      .isEqualTo(UpdateProblem.OTHER_SIGNATURE)
    // La stessa impronta scritta in maiuscolo e' la stessa firma.
    assertThat(UpdateRules.archiveProblem(installed, installed.copy(versionCode = 6, signers = setOf("AB".repeat(32))))).isNull()
  }

  // --- Quando si controlla e quando si annuncia ----------------------------------------------------------------------------------------------------

  @Test
  fun alPiuUnaVoltaOgni24Ore() {
    val day = UpdateSourceConfig.CHECK_INTERVAL_MILLIS
    val now = 1_000_000_000_000L
    assertThat(UpdateRules.isDue(null, now)).isTrue()
    assertThat(UpdateRules.isDue(now - day + 1, now)).isFalse()
    assertThat(UpdateRules.isDue(now - day, now)).isTrue()
    // L'orologio del telefono e' tornato indietro: l'ora salvata e' nel futuro, si controlla (altrimenti mai piu' fino a quel giorno).
    assertThat(UpdateRules.isDue(now + 5 * day, now)).isTrue()
  }

  @Test
  fun lAvvisoUnaVoltaPerVersione() {
    assertThat(UpdateRules.shouldPrompt(null, null)).isFalse()
    assertThat(UpdateRules.shouldPrompt("1.1.0", null)).isTrue()
    assertThat(UpdateRules.shouldPrompt("1.1.0", "1.1.0")).isFalse()
    assertThat(UpdateRules.shouldPrompt("1.2.0", "1.1.0")).isTrue()
  }

  // --- La numerazione delle versioni con le regole dell'engine (docs/aggiornamenti-in-app.md, «Versioni») -------------------------------------------

  @Test
  fun laBetaConIlVersionNameInteroVedeLaStabileOmonima() {
    // La versione corrente e' il versionName INTERO: una 1.1.0-beta.2 riceve la 1.1.0.
    assertThat(isStableVersionNewer("1.1.0", "1.1.0-beta.2")).isTrue()
    // Tagliato il suffisso (come nel KDoc del controllore dell'engine) non la vedrebbe mai: la trappola che qui si evita.
    assertThat(isStableVersionNewer("1.1.0", "1.1.0-beta.2".substringBefore("-"))).isFalse()
    // Il canale Stabile non propone mai una versione con il trattino.
    assertThat(isStableVersionNewer("1.2.0-beta.1", "1.1.0")).isFalse()
  }

  @Test
  fun lePreReleaseSiConfrontanoComeTesto_beta10VienePrimaDiBeta9() {
    // La trappola di `compareVersions` (engine-foundation): dopo la beta.9 il canale Beta non proporrebbe la beta.10. Regola di pubblicazione:
    // al piu' nove beta per versione (poi si passa alla versione dopo), oppure due cifre fin dall'inizio (beta.01 ... beta.99).
    assertThat(compareVersions("1.1.0-beta.10", "1.1.0-beta.9")).isLessThan(0)
    assertThat(compareVersions("1.1.0-beta.10", "1.1.0-beta.09")).isGreaterThan(0)
    assertThat(compareVersions("1.1.0-beta.2", "1.1.0-beta.1")).isGreaterThan(0)
    // La prima beta di oggi si chiama 1.0.0 (senza suffisso, versionCode 1, canale beta): la stabile 1.0.0 dello stesso APK non e' «piu' nuova».
    assertThat(compareVersions("1.0.0", "1.0.0")).isEqualTo(0)
    assertThat(compareVersions("1.0.1", "1.0.0")).isGreaterThan(0)
  }
}
