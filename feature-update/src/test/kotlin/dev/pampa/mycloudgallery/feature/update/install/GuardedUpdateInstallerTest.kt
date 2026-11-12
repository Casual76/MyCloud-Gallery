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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * Pacchetto U (C480, C481, C484, C485, C487): la sequenza dell'installazione in due tempi, fra lo scaricamento e la sessione dell'engine. L'installatore
 * dell'engine e' finto ([FakeTransfer]): ogni controllo dell'app si prova guardando che `installFile` NON venga chiamato e che il file scaricato sparisca;
 * ogni messaggio dell'engine diventa un problema dell'app (UU-6). Giro 2 (US2-1): il cancello dell'app davanti si guarda DOPO le verifiche, subito prima di
 * `installFile`: un'app che va dietro mentre il file si verifica non passa niente ad Android.
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

  private fun installer(
    transfer: FakeTransfer,
    facts: FakeFacts = FakeFacts(me, good),
    permission: FakePermission = FakePermission(),
    inFront: () -> Boolean = { true },
    actualSha: () -> String = { sha },
  ) = GuardedUpdateInstaller(transfer, facts, permission, inFront = inFront, checksum = { actualSha() }, io = Dispatchers.Unconfined)

  /** Il primo tempo: dall'offerta al file verificato. */
  private fun prepare(
    transfer: FakeTransfer = FakeTransfer(),
    facts: FakeFacts = FakeFacts(me, good),
    permission: FakePermission = FakePermission(),
    actualSha: String = sha,
    update: AvailableAppUpdate = update(),
  ): List<InstallStep> = runBlocking { installer(transfer, facts, permission) { actualSha }.prepare(update).toList() }

  /** I due tempi di seguito, come quando l'app e' davanti. */
  private fun both(transfer: FakeTransfer, facts: FakeFacts = FakeFacts(me, good)): List<InstallStep> = runBlocking {
    val guarded = installer(transfer, facts)
    val first = guarded.prepare(update()).toList()
    val ready = first.last() as InstallStep.Ready
    first + guarded.commit(update(), ready.file).toList()
  }

  @Test
  fun tuttoInRegola_scaricaVerificaPoiPassaAllEngine() {
    val transfer = FakeTransfer()
    val steps = both(transfer)
    assertThat(steps.first()).isEqualTo(InstallStep.Preparing)
    assertThat(steps).contains(InstallStep.Downloading(1f, 10, 10))
    assertThat(steps).contains(InstallStep.Ready(transfer.file!!))
    assertThat(steps.takeLast(3)).containsExactly(InstallStep.Installing, InstallStep.AwaitingUser, InstallStep.Installed).inOrder()
    // All'engine arrivano la versione e lo sha256 dell'elenco (che ricontrolla).
    assertThat(transfer.installed.single().second).isEqualTo("1.1.0")
    assertThat(transfer.installed.single().third).isEqualTo(sha)
    // Installata: il file nella cache non resta.
    assertThat(transfer.file!!.exists()).isFalse()
  }

  @Test
  fun ilPrimoTempoSiFermaAlFilePronto_senzaSessione() {
    val transfer = FakeTransfer()
    val steps = prepare(transfer = transfer)
    assertThat(steps.last()).isEqualTo(InstallStep.Ready(transfer.file!!))
    assertThat(transfer.installed).isEmpty()
    assertThat(transfer.file!!.exists()).isTrue()
  }

  @Test
  fun senzaSha256NelElencoNonSiScaricaNemmeno() {
    val transfer = FakeTransfer()
    val steps = prepare(transfer = transfer, update = update(sha256 = ""))
    assertThat(steps.last()).isEqualTo(InstallStep.Failed(UpdateProblem.CHECKSUM_MISSING))
    assertThat(transfer.downloads).isEqualTo(0)
    assertThat(transfer.installed).isEmpty()
  }

  @Test
  fun senzaPesoNelElencoNonSiScaricaNemmeno() {
    val transfer = FakeTransfer()
    val steps = prepare(transfer = transfer, update = update(size = 0))
    assertThat(steps.last()).isEqualTo(InstallStep.Failed(UpdateProblem.SIZE_MISSING))
    assertThat(transfer.downloads).isEqualTo(0)
  }

  @Test
  fun senzaPermessoCiSiFermaPrimaDelloScaricamentoSenzaAprireNiente() {
    val transfer = FakeTransfer()
    val permission = FakePermission(granted = false)
    val steps = prepare(transfer = transfer, permission = permission)
    assertThat(steps.last()).isEqualTo(InstallStep.NeedsPermission)
    assertThat(transfer.downloads).isEqualTo(0)
    // La pagina di Android la apre la pagina dell'app, dopo la riga che spiega (non l'installatore da solo).
    assertThat(permission.opened).isEqualTo(0)
  }

  @Test
  fun unoScaricamentoFallitoEUnErroreCorto() {
    val transfer = FakeTransfer(failDownload = true)
    assertThat(prepare(transfer = transfer).last()).isEqualTo(InstallStep.Failed(UpdateProblem.DOWNLOAD_FAILED))
    assertThat(transfer.installed).isEmpty()
  }

  @Test
  fun unFileConUnAltroSha256NonArrivaAdAndroid() {
    val transfer = FakeTransfer()
    val steps = prepare(transfer = transfer, actualSha = "f".repeat(64))
    assertThat(steps.last()).isEqualTo(InstallStep.Failed(UpdateProblem.CHECKSUM_MISMATCH))
    assertThat(transfer.installed).isEmpty()
    assertThat(transfer.file!!.exists()).isFalse()
  }

  @Test
  fun unApkDiUnAltraAppNonArrivaAdAndroid() {
    val transfer = FakeTransfer()
    val steps = prepare(transfer = transfer, facts = FakeFacts(me, good.copy(packageName = "com.altra")))
    assertThat(steps.last()).isEqualTo(InstallStep.Failed(UpdateProblem.OTHER_APP))
    assertThat(transfer.installed).isEmpty()
    assertThat(transfer.file!!.exists()).isFalse()
  }

  @Test
  fun unApkDiUnAltraVersioneNonArrivaAdAndroid() {
    val transfer = FakeTransfer()
    val steps = prepare(transfer = transfer, facts = FakeFacts(me, good.copy(versionName = "1.2.0")))
    assertThat(steps.last()).isEqualTo(InstallStep.Failed(UpdateProblem.WRONG_VERSION))
    assertThat(transfer.file!!.exists()).isFalse()
  }

  @Test
  fun unApkNonPiuNuovoNonArrivaAdAndroid() {
    val transfer = FakeTransfer()
    val steps = prepare(transfer = transfer, facts = FakeFacts(me, good.copy(versionCode = 5)))
    assertThat(steps.last()).isEqualTo(InstallStep.Failed(UpdateProblem.NOT_NEWER))
    assertThat(transfer.installed).isEmpty()
  }

  @Test
  fun unApkFirmatoDaUnAltroNonArrivaAdAndroid() {
    val transfer = FakeTransfer()
    val steps = prepare(transfer = transfer, facts = FakeFacts(me, good.copy(signers = setOf("cd".repeat(32)))))
    assertThat(steps.last()).isEqualTo(InstallStep.Failed(UpdateProblem.OTHER_SIGNATURE))
    assertThat(transfer.installed).isEmpty()
    assertThat(transfer.file!!.exists()).isFalse()
  }

  @Test
  fun unApkIllegibileNonArrivaAdAndroid() {
    val transfer = FakeTransfer()
    assertThat(prepare(transfer = transfer, facts = FakeFacts(me, null)).last()).isEqualTo(InstallStep.Failed(UpdateProblem.UNREADABLE))
    assertThat(transfer.installed).isEmpty()
  }

  // --- Il cancello (giro 2, US2-1) -------------------------------------------------------------------------------------------------------------------

  /** Un file gia' scaricato, pronto per il secondo tempo. */
  private fun readyFile(): File = File(dir, "mycloud-gallery-1.1.0.apk").apply { writeBytes(ByteArray(10)) }

  /**
   * Il secondo tempo con un verificatore SOSPESO: lo SHA-256 parte con l'app davanti e si ferma finche' la prova non lo lascia andare; nel frattempo
   * [whileVerifying] cambia il cancello (Home, schermo spento, oppure il ritorno davanti). Thread veri: il verificatore gira su un suo esecutore.
   */
  private fun commitWhileVerifying(transfer: FakeTransfer, gate: ForegroundGate, whileVerifying: () -> Unit): List<InstallStep> {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val pool = Executors.newSingleThreadExecutor()
    val io = pool.asCoroutineDispatcher()
    val guarded = GuardedUpdateInstaller(
      transfer = transfer,
      facts = FakeFacts(me, good),
      permission = FakePermission(),
      inFront = gate::inFront,
      checksum = {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "il verificatore non e' stato lasciato andare" }
        sha
      },
      io = io,
    )
    return try {
      runBlocking {
        val steps = async(Dispatchers.Default) { guarded.commit(update(), readyFile()).toList() }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        whileVerifying()
        release.countDown()
        steps.await()
      }
    } finally {
      io.close()
    }
  }

  @Test
  fun lAppVaDietroMentreIlFileSiVerifica_nienteAdAndroid_resta_Pronta() {
    // Davanti all'inizio della verifica, dietro alla fine: zero passaggi a installFile, il file resta «Pronta» (e c'e' ancora).
    val transfer = FakeTransfer()
    val gate = ForegroundGate().apply { open() }
    val steps = commitWhileVerifying(transfer, gate) { gate.close() }
    assertThat(transfer.installed).isEmpty()
    assertThat(steps.last()).isEqualTo(InstallStep.Ready(readyFile()))
    assertThat(steps).doesNotContain(InstallStep.Installing)
    assertThat(readyFile().exists()).isTrue()
  }

  @Test
  fun lAppTornaDavantiMentreIlFileSiVerifica_ilFilePassa() {
    // Il rovescio: dietro all'inizio, davanti alla fine. Conta lo stato DOPO le verifiche, una volta sola.
    val transfer = FakeTransfer()
    val gate = ForegroundGate()
    val steps = commitWhileVerifying(transfer, gate) { gate.open() }
    assertThat(transfer.installed).hasSize(1)
    assertThat(steps).contains(InstallStep.Installing)
  }

  @Test
  fun conLAppDietro_nessunaSessione_ePassatoIlCancello_StoInstallando() {
    // L'engine finto resta al suo «Verifica APK...»: «Sto installando» deve venire dall'app, appena passato il cancello.
    val transfer = FakeTransfer(engineSteps = listOf(AppUpdateInstallState.Verifying("Verifica APK...")))
    var front = false
    val guarded = installer(transfer, inFront = { front })
    runBlocking {
      val ready = guarded.prepare(update()).toList().last() as InstallStep.Ready
      assertThat(guarded.commit(update(), ready.file).toList()).containsExactly(InstallStep.Verifying, InstallStep.Ready(ready.file)).inOrder()
      assertThat(transfer.installed).isEmpty()
      // Davanti: subito «Sto installando», prima di qualunque stato dell'engine (giro 2, UU2-1).
      front = true
      val steps = guarded.commit(update(), ready.file).toList()
      assertThat(steps).containsExactly(InstallStep.Verifying, InstallStep.Installing).inOrder()
    }
    assertThat(transfer.installed).hasSize(1)
  }

  @Test
  fun primaDellaSessioneIlFileSiRicontrolla() {
    // Fra «Pronta» e «Installa» il file e' cambiato (o la firma non e' piu' quella): non arriva ad Android.
    val transfer = FakeTransfer()
    var actual = sha
    val guarded = installer(transfer) { actual }
    runBlocking {
      val ready = guarded.prepare(update()).toList().last() as InstallStep.Ready
      actual = "0".repeat(64)
      assertThat(guarded.commit(update(), ready.file).toList().last()).isEqualTo(InstallStep.Failed(UpdateProblem.CHECKSUM_MISMATCH))
    }
    assertThat(transfer.installed).isEmpty()
    assertThat(transfer.file!!.exists()).isFalse()
  }

  @Test
  fun senzaPermessoAllaSessione_ilFileResta() {
    val transfer = FakeTransfer()
    val permission = FakePermission()
    val guarded = installer(transfer, permission = permission)
    runBlocking {
      val ready = guarded.prepare(update()).toList().last() as InstallStep.Ready
      permission.granted = false
      assertThat(guarded.commit(update(), ready.file).toList().last()).isEqualTo(InstallStep.NeedsPermission)
    }
    assertThat(transfer.installed).isEmpty()
    assertThat(transfer.file!!.exists()).isTrue()
  }

  @Test
  fun annullataInAndroid_nonEUnGuasto_siTornaAPronta() {
    val transfer = FakeTransfer(engineSteps = listOf(AppUpdateInstallState.Error(EngineMessages.ABORTED)))
    val steps = both(transfer)
    assertThat(steps.last()).isEqualTo(InstallStep.Ready(transfer.file!!, interrupted = true))
    // Il file verificato resta: «Installa» non riscarica.
    assertThat(transfer.file!!.exists()).isTrue()
  }

  @Test
  fun nessunaFraseDellEngineArrivaAllUtente() {
    // Ogni messaggio dell'engine 3.0.4 ha il suo problema dell'app; uno sconosciuto (anche il testo inglese di Android) diventa quello generico.
    val table = mapOf(
      EngineMessages.BLOCKED to UpdateProblem.BLOCKED,
      EngineMessages.CONFLICT to UpdateProblem.OTHER_SIGNATURE,
      EngineMessages.INCOMPATIBLE to UpdateProblem.INCOMPATIBLE,
      EngineMessages.INVALID to UpdateProblem.UNREADABLE,
      EngineMessages.STORAGE to UpdateProblem.NO_SPACE,
      EngineMessages.UNREADABLE to UpdateProblem.UNREADABLE,
      EngineMessages.OTHER_APP to UpdateProblem.OTHER_APP,
      "Versione APK inattesa: 9.9.9." to UpdateProblem.WRONG_VERSION,
      EngineMessages.CHECKSUM to UpdateProblem.CHECKSUM_MISMATCH,
      "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match" to UpdateProblem.INSTALL_FAILED,
      "Installazione non riuscita." to UpdateProblem.INSTALL_FAILED,
      "" to UpdateProblem.INSTALL_FAILED,
    )
    for ((message, problem) in table) assertThat(EngineMessages.problemOf(message)).isEqualTo(problem)
    assertThat(EngineMessages.problemOf(EngineMessages.ABORTED)).isNull()
  }

  @Test
  fun unGuastoDiAndroidChiudeIlFileSoloSeServe() {
    // Lo spazio: il file resta per «Riprova» (senza riscaricare).
    val space = FakeTransfer(engineSteps = listOf(AppUpdateInstallState.Error(EngineMessages.STORAGE)))
    assertThat(both(space).last()).isEqualTo(InstallStep.Failed(UpdateProblem.NO_SPACE))
    assertThat(space.file!!.exists()).isTrue()
    // Un'altra firma per Android: il file non serve piu'.
    val conflict = FakeTransfer(engineSteps = listOf(AppUpdateInstallState.Error(EngineMessages.CONFLICT)))
    assertThat(both(conflict).last()).isEqualTo(InstallStep.Failed(UpdateProblem.OTHER_SIGNATURE))
    assertThat(conflict.file!!.exists()).isFalse()
  }

  @Test
  fun ilPermessoTolto_traIlControlloELaSessione_tornaComePermesso() {
    val transfer = FakeTransfer(engineSteps = listOf(AppUpdateInstallState.Error("Abilita...", AppUpdateErrorKind.InstallPermission)))
    assertThat(both(transfer).last()).isEqualTo(InstallStep.NeedsPermission)
  }

  @Test
  fun chiPassaDallEngine_passaDagliStessiControlli() {
    // `EngineAppUpdater.install` delega qui: un APK firmato da un altro si ferma prima della sessione anche da li'.
    val transfer = FakeTransfer()
    val guarded = installer(transfer, FakeFacts(me, good.copy(signers = setOf("cd".repeat(32)))))
    val states = runBlocking { guarded.install(update()).toList() }
    assertThat(states.last()).isEqualTo(AppUpdateInstallState.Error(UpdateProblem.OTHER_SIGNATURE.code))
    assertThat(transfer.installed).isEmpty()
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
