package dev.pampa.mycloudgallery.feature.update.install

import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.AppUpdateErrorKind
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.pampa.mycloudgallery.feature.update.policy.PackageFacts
import dev.pampa.mycloudgallery.feature.update.policy.UpdateProblem
import dev.pampa.mycloudgallery.feature.update.source.UpdateSourceConfig
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * Pacchetto U (C480, C481, C484, C485): la sequenza dell'installazione fra lo scaricamento e la sessione dell'engine. L'installatore dell'engine e' finto
 * ([FakeTransfer]): ogni controllo dell'app si prova guardando che `installFile` NON venga chiamato e che il file scaricato sparisca.
 */
class GuardedUpdateInstallerTest {

  private val dir: File = Files.createTempDirectory("installazione").toFile()
  private val sha = "e".repeat(64)

  @After
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private fun update(sha256: String = sha, size: Long = 10L) = AvailableAppUpdate(
    version = "1.1.0",
    changelog = "",
    releaseTag = "stable-mycloud-gallery-v1.1.0",
    apkAsset = "mycloud-gallery-1.1.0.apk",
    downloadUrl = UpdateSourceConfig.downloadUrl("stable-mycloud-gallery-v1.1.0", "mycloud-gallery-1.1.0.apk"),
    sizeBytes = size,
    sha256 = sha256,
  )

  private inner class FakeTransfer(
    private val failDownload: Boolean = false,
    private val engineSteps: List<AppUpdateInstallState> = listOf(
      AppUpdateInstallState.Verifying("Verifica APK..."),
      AppUpdateInstallState.Installing("Installazione aggiornamento..."),
      AppUpdateInstallState.AwaitingUserAction("Conferma l'installazione sul dispositivo."),
      AppUpdateInstallState.Installed(""),
    ),
  ) : ApkTransfer {
    var downloads = 0
    val installed = mutableListOf<Triple<File, String, String>>()
    var file: File? = null

    override suspend fun download(update: AvailableAppUpdate, onProgress: (AppUpdateInstallState.Downloading) -> Unit): File {
      downloads++
      if (failDownload) throw IOException("Download non riuscito (500).")
      onProgress(AppUpdateInstallState.Downloading(0.5f, 5, 10))
      onProgress(AppUpdateInstallState.Downloading(1f, 10, 10))
      return File(dir, update.apkAsset).apply { writeBytes(ByteArray(10)) }.also { file = it }
    }

    override fun installFile(file: File, expectedVersionName: String, sha256: String): Flow<AppUpdateInstallState> {
      installed += Triple(file, expectedVersionName, sha256)
      return flowOf(*engineSteps.toTypedArray())
    }
  }

  private class FakeFacts(var installed: PackageFacts, var archive: PackageFacts?) : PackageFactsSource {
    override fun installed(): PackageFacts = installed

    override fun archive(file: File): PackageFacts? = archive
  }

  private class FakePermission(var granted: Boolean = true) : InstallPermission {
    var opened = 0

    override fun granted(): Boolean = granted

    override fun open(): Boolean {
      opened++
      return true
    }
  }

  private val me = PackageFacts("dev.pampa.mycloudgallery", 5, "1.0.0", setOf("ab".repeat(32)))
  private val good = me.copy(versionCode = 6, versionName = "1.1.0")

  private fun run(
    transfer: FakeTransfer = FakeTransfer(),
    facts: FakeFacts = FakeFacts(me, good),
    permission: FakePermission = FakePermission(),
    actualSha: String = sha,
    update: AvailableAppUpdate = update(),
  ): List<InstallStep> = runBlocking {
    GuardedUpdateInstaller(transfer, facts, permission, checksum = { actualSha }, io = Dispatchers.Unconfined).run(update).toList()
  }

  @Test
  fun tuttoInRegola_scaricaVerificaEPassaAllEngine() {
    val transfer = FakeTransfer()
    val steps = run(transfer = transfer)
    assertThat(steps.first()).isEqualTo(InstallStep.Preparing)
    assertThat(steps).contains(InstallStep.Downloading(1f, 10, 10))
    assertThat(steps.takeLast(4)).containsExactly(InstallStep.Verifying, InstallStep.Installing, InstallStep.AwaitingUser, InstallStep.Installed).inOrder()
    // All'engine arrivano la versione e lo sha256 dell'elenco (che ricontrolla).
    assertThat(transfer.installed.single().second).isEqualTo("1.1.0")
    assertThat(transfer.installed.single().third).isEqualTo(sha)
    // Installata: il file nella cache non resta.
    assertThat(transfer.file!!.exists()).isFalse()
  }

  @Test
  fun senzaSha256NelElencoNonSiScaricaNemmeno() {
    val transfer = FakeTransfer()
    val steps = run(transfer = transfer, update = update(sha256 = ""))
    assertThat(steps.last()).isEqualTo(InstallStep.Failed(UpdateProblem.CHECKSUM_MISSING))
    assertThat(transfer.downloads).isEqualTo(0)
    assertThat(transfer.installed).isEmpty()
  }

  @Test
  fun senzaPesoNelElencoNonSiScaricaNemmeno() {
    val transfer = FakeTransfer()
    val steps = run(transfer = transfer, update = update(size = 0))
    assertThat(steps.last()).isEqualTo(InstallStep.Failed(UpdateProblem.SIZE_MISSING))
    assertThat(transfer.downloads).isEqualTo(0)
  }

  @Test
  fun senzaPermessoCiSiFermaPrimaDelloScaricamentoSenzaAprireNiente() {
    val transfer = FakeTransfer()
    val permission = FakePermission(granted = false)
    val steps = run(transfer = transfer, permission = permission)
    assertThat(steps.last()).isEqualTo(InstallStep.NeedsPermission)
    assertThat(transfer.downloads).isEqualTo(0)
    // La pagina di Android la apre la pagina dell'app, dopo la riga che spiega (non l'installatore da solo).
    assertThat(permission.opened).isEqualTo(0)
  }

  @Test
  fun unoScaricamentoFallitoEUnErroreCorto() {
    val transfer = FakeTransfer(failDownload = true)
    assertThat(run(transfer = transfer).last()).isEqualTo(InstallStep.Failed(UpdateProblem.DOWNLOAD_FAILED))
    assertThat(transfer.installed).isEmpty()
  }

  @Test
  fun unFileConUnAltroSha256NonArrivaAdAndroid() {
    val transfer = FakeTransfer()
    val steps = run(transfer = transfer, actualSha = "f".repeat(64))
    assertThat(steps.last()).isEqualTo(InstallStep.Failed(UpdateProblem.CHECKSUM_MISMATCH))
    assertThat(transfer.installed).isEmpty()
    assertThat(transfer.file!!.exists()).isFalse()
  }

  @Test
  fun unApkDiUnAltraAppNonArrivaAdAndroid() {
    val transfer = FakeTransfer()
    val steps = run(transfer = transfer, facts = FakeFacts(me, good.copy(packageName = "com.altra")))
    assertThat(steps.last()).isEqualTo(InstallStep.Failed(UpdateProblem.OTHER_APP))
    assertThat(transfer.installed).isEmpty()
    assertThat(transfer.file!!.exists()).isFalse()
  }

  @Test
  fun unApkNonPiuNuovoNonArrivaAdAndroid() {
    val transfer = FakeTransfer()
    val steps = run(transfer = transfer, facts = FakeFacts(me, good.copy(versionCode = 5)))
    assertThat(steps.last()).isEqualTo(InstallStep.Failed(UpdateProblem.NOT_NEWER))
    assertThat(transfer.installed).isEmpty()
  }

  @Test
  fun unApkFirmatoDaUnAltroNonArrivaAdAndroid() {
    val transfer = FakeTransfer()
    val steps = run(transfer = transfer, facts = FakeFacts(me, good.copy(signers = setOf("cd".repeat(32)))))
    assertThat(steps.last()).isEqualTo(InstallStep.Failed(UpdateProblem.OTHER_SIGNATURE))
    assertThat(transfer.installed).isEmpty()
    assertThat(transfer.file!!.exists()).isFalse()
  }

  @Test
  fun unApkIllegibileNonArrivaAdAndroid() {
    val transfer = FakeTransfer()
    assertThat(run(transfer = transfer, facts = FakeFacts(me, null)).last()).isEqualTo(InstallStep.Failed(UpdateProblem.UNREADABLE))
    assertThat(transfer.installed).isEmpty()
  }

  @Test
  fun quandoAndroidNonInstallaSiMostraIlSuoMessaggio() {
    val transfer = FakeTransfer(engineSteps = listOf(AppUpdateInstallState.Error("Installazione interrotta: annullata, oppure bloccata da Play Protect.")))
    val steps = run(transfer = transfer)
    assertThat(steps.last())
      .isEqualTo(InstallStep.Failed(UpdateProblem.INSTALL_FAILED, "Installazione interrotta: annullata, oppure bloccata da Play Protect."))
    assertThat(transfer.file!!.exists()).isFalse()
  }

  @Test
  fun ilPermessoTolto_traIlControlloELaSessione_tornaComePermesso() {
    val transfer = FakeTransfer(engineSteps = listOf(AppUpdateInstallState.Error("Abilita...", AppUpdateErrorKind.InstallPermission)))
    assertThat(run(transfer = transfer).last()).isEqualTo(InstallStep.NeedsPermission)
  }

  @Test
  fun laCacheSiSvuotaAllAvvio() {
    val updates = File(dir, UpdateCache.DIR).apply { mkdirs() }
    File(updates, "mycloud-gallery-1.1.0.apk").writeBytes(ByteArray(3))
    File(updates, "mycloud-gallery-1.1.0.apk.part").writeBytes(ByteArray(3))
    assertThat(UpdateCache.clear(dir)).isEqualTo(2)
    assertThat(updates.listFiles().orEmpty().toList()).isEmpty()
    assertThat(UpdateCache.clear(File(dir, "non-esiste"))).isEqualTo(0)
  }
}
