package dev.pampa.mycloudgallery.feature.update.source

import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.update.AppUpdateInstaller
import dev.antigravity.fluidengine.update.EngineAppUpdater
import dev.pampa.mycloudgallery.feature.update.policy.UpdateProblem
import dev.pampa.mycloudgallery.feature.update.policy.UpdateRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Pacchetto U: l'elenco che scrive `publisher.py` del Pampa Store (`upsert_app_manifest`, `build_version_info`; letto il 12/11/2026, non lanciato)
 * contro cio' che legge `EngineAppUpdater.check` dell'engine 3.0.4 con la sorgente di questa app. Le chiavi combaciano (`app.repository.repoOwner/
 * repoName`, `stable`/`beta` con `version`, `releaseTag`, `apkAsset`, `sizeBytes`, `sha256`, `changelog`). Lo `sha256` il publisher lo scrive dalla
 * correzione del 12/11 (decisa dall'utente: obbligatorio, 64 cifre minuscole, accanto a `sizeBytes`); un elenco scritto prima, senza, non installa
 * niente (fallisce chiuso). Tag e nomi dei file sono quelli del publisher con `--app-id mycloud-gallery`.
 */
class PublisherManifestTest {

  private class FakeHttp(private val body: String?) : EngineHttp() {
    val asked = mutableListOf<String>()

    override suspend fun readText(url: String, headers: Map<String, String>): String {
      asked += url
      return body ?: error("Richiesta non riuscita (404).")
    }
  }

  private object NoInstaller : AppUpdateInstaller {
    override fun install(update: AvailableAppUpdate): Flow<AppUpdateInstallState> = emptyFlow()
  }

  /** Un rilascio come lo scrive `build_version_info` (con lo sha256 dalla correzione del 12/11; null = come lo scriveva prima). */
  private fun release(version: String, channel: String, sha256: String? = "c".repeat(64)): String {
    val sha = if (sha256 != null) ""","sha256":"$sha256"""" else ""
    return """{"version":"$version","releaseDate":"2026-11-20","changelog":"- Novita' della $version","releaseTag":"$channel-mycloud-gallery-v$version",""" +
      """"apkAsset":"mycloud-gallery-$version.apk","sizeBytes":115343360$sha}"""
  }

  /** L'elenco come lo scrive `upsert_app_manifest` (con `history`, `screenshots` e gli altri campi che l'engine ignora). */
  private fun manifest(stable: String?, beta: String?): String {
    val parts = mutableListOf(
      """"id":"mycloud-gallery"""",
      """"name":"MyCloud Gallery"""",
      """"description":"Galleria"""",
      """"packageName":"dev.pampa.mycloudgallery"""",
      """"iconUrl":""""",
      """"category":"Foto"""",
      """"developer":"Pampa"""",
      """"screenshots":[]""",
      """"repository":{"repoOwner":"Casual76","repoName":"MyCloud-Gallery","manifestPath":"manifest.json","ref":"main"}""",
      """"history":[]""",
    )
    if (stable != null) parts += """"stable":$stable"""
    if (beta != null) parts += """"beta":$beta"""
    return """{"app":{${parts.joinToString(",")}}}"""
  }

  private fun check(body: String?, current: String, channel: UpdateChannel): Result<AvailableAppUpdate?> = runBlocking {
    val http = FakeHttp(body)
    val updater = EngineAppUpdater(http, UpdateSourceConfig.engineSource("dev.pampa.mycloudgallery"), NoInstaller)
    updater.check(currentVersionName = current, channel = channel, ignoredVersion = "").also {
      assertThat(http.asked).containsExactly(UpdateSourceConfig.MANIFEST_URL)
    }
  }

  @Test
  fun lElencoDiPublisherSiLeggeEPortaAllApkDelleReleaseDiQuestoRepository() {
    val beta = release("1.0.1", "beta", sha256 = "c".repeat(64))
    val found = check(manifest(stable = null, beta = beta), current = "1.0.0", channel = UpdateChannel.BETA).getOrThrow()!!
    assertThat(found.version).isEqualTo("1.0.1")
    assertThat(found.sizeBytes).isEqualTo(115343360L)
    assertThat(found.downloadUrl)
      .isEqualTo("https://github.com/Casual76/MyCloud-Gallery/releases/download/beta-mycloud-gallery-v1.0.1/mycloud-gallery-1.0.1.apk")
    assertThat(UpdateRules.offerProblem(found)).isNull()
  }

  @Test
  fun unElencoScrittoPrimaDellaCorrezione_senzaSha256_nonInstallaNiente() {
    val found = check(manifest(stable = null, beta = release("1.0.1", "beta", sha256 = null)), current = "1.0.0", channel = UpdateChannel.BETA).getOrThrow()!!
    assertThat(found.sha256).isEmpty()
    assertThat(UpdateRules.offerProblem(found)).isEqualTo(UpdateProblem.CHECKSUM_MISSING)
  }

  @Test
  fun ilCanaleStabileLeggeSoloLaStabile_laBetaRipiegaSullaStabile() {
    val onlyBeta = manifest(stable = null, beta = release("1.0.1", "beta", "c".repeat(64)))
    assertThat(check(onlyBeta, current = "1.0.0", channel = UpdateChannel.STABLE).getOrThrow()).isNull()
    // `publisher.py` con il canale stable scrive la stessa versione in `stable` e in `beta`.
    val stable = release("1.1.0", "stable", "d".repeat(64))
    val both = manifest(stable = stable, beta = stable)
    assertThat(check(both, current = "1.0.0", channel = UpdateChannel.STABLE).getOrThrow()?.version).isEqualTo("1.1.0")
    assertThat(check(both, current = "1.1.0-beta.03", channel = UpdateChannel.BETA).getOrThrow()?.version).isEqualTo("1.1.0")
    val onlyStable = manifest(stable = stable, beta = null)
    assertThat(check(onlyStable, current = "1.0.0", channel = UpdateChannel.BETA).getOrThrow()?.version).isEqualTo("1.1.0")
  }

  @Test
  fun nienteRitorniIndietroCambiandoCanale() {
    // Chi passa da Beta a Stabile con una beta piu' nuova della stabile non si vede proporre la stabile vecchia.
    val stable = release("1.0.0", "stable", "d".repeat(64))
    assertThat(check(manifest(stable, stable), current = "1.1.0-beta.02", channel = UpdateChannel.STABLE).getOrThrow()).isNull()
  }

  @Test
  fun laProssimaBetaDopoLaPrima_1_1_0_beta_01_siVede() {
    // La prima beta di oggi e' 1.0.0 (versionCode 1). La prossima si chiama 1.1.0-beta.01: il canale Beta la vede, il tag del publisher passa.
    val next = release("1.1.0-beta.01", "beta")
    val found = check(manifest(stable = null, beta = next), current = "1.0.0", channel = UpdateChannel.BETA).getOrThrow()!!
    assertThat(found.releaseTag).isEqualTo("beta-mycloud-gallery-v1.1.0-beta.01")
    assertThat(UpdateRules.offerProblem(found)).isNull()
    // Una «1.0.0-beta.02» invece sembrerebbe un passo indietro e non arriverebbe a nessuno.
    assertThat(check(manifest(stable = null, beta = release("1.0.0-beta.02", "beta")), current = "1.0.0", channel = UpdateChannel.BETA).getOrThrow()).isNull()
  }

  @Test
  fun unElencoChiusoORottoEUnErroreNonUnaVersione() {
    // Finche' il repository e' privato: 404. Un errore che all'avvio resta in silenzio (AppUpdatesTest), mai una versione inventata.
    assertThat(check(null, current = "1.0.0", channel = UpdateChannel.STABLE).isFailure).isTrue()
    assertThat(check("<html>", current = "1.0.0", channel = UpdateChannel.STABLE).isFailure).isTrue()
  }
}
