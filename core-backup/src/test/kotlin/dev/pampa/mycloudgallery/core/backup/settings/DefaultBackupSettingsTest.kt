package dev.pampa.mycloudgallery.core.backup.settings

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.plan.ProvaFence
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Le impostazioni del backup (Fase 5A, P4; D7, D13, E-B7, E-B12, E-B16): del dispositivo, salvate, lette in modo difensivo, mai nei log.
 */
class DefaultBackupSettingsTest {

  private val prova = BackupSource.Folder("external_primary", "DCIM/mcg-prova/")
  private val telegram = BackupSource.Folder("external_primary", "Pictures/Telegram/")

  private fun TestScope.settings(
    storage: BackupSettingsStorage = MemoryBackupSettingsStorage(),
    fence: dev.pampa.mycloudgallery.core.backup.contract.BackupFence? = null,
    account: String? = null,
  ) = DefaultBackupSettings(storage, backgroundScope, fence) { account }

  // --- Prima della lettura e a magazzino vuoto: si fallisce chiusi ------------------------------------------------------------------

  @Test
  fun aMagazzinoVuoto_nessunaSorgenteENessunaCartella_ilBackupDiceCheManca() = runTest {
    val settings = settings()

    assertThat(settings.sources.value).isEqualTo(BackupSources.NONE)
    assertThat(settings.deviceFolder.value).isNull()
    assertThat(settings.loaded.value).isFalse()

    settings.awaitLoaded()

    assertThat(settings.loaded.value).isTrue()
    assertThat(settings.sources.value).isEqualTo(BackupSources.NONE) // mai scelte: nessuna, non "quelle predefinite"
    assertThat(settings.deviceFolder.value).isNull()
  }

  @Test
  fun leSorgentiDellOnboarding_sonoFotocameraEScreenshotSeSpuntate() {
    assertThat(BackupSourceCodec.fromOnboarding(camera = true, screenshots = true)).isEqualTo(BackupSources.DEFAULT)
    assertThat(BackupSourceCodec.fromOnboarding(camera = true, screenshots = false).sources).containsExactly(BackupSource.Camera)
    assertThat(BackupSourceCodec.fromOnboarding(camera = false, screenshots = true).sources).containsExactly(BackupSource.Screenshots)
    assertThat(BackupSourceCodec.fromOnboarding(camera = false, screenshots = false)).isEqualTo(BackupSources.NONE)
  }

  // --- Sorgenti ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun leSorgentiScelteSiSalvano_eSopravvivonoAlRiavvio() = runTest {
    val storage = MemoryBackupSettingsStorage()
    val first = settings(storage)
    first.awaitLoaded()

    first.setSources(BackupSources(setOf(BackupSource.Camera, telegram)))

    assertThat(first.sources.value.sources).containsExactly(BackupSource.Camera, telegram)
    // Un altro "processo" sullo stesso magazzino: le stesse sorgenti.
    val second = settings(storage)
    second.awaitLoaded()
    assertThat(second.sources.value.sources).containsExactly(BackupSource.Camera, telegram)
  }

  @Test
  fun togliereUnaSorgente_E_B12_cambiaSoloLeSorgenti() = runTest {
    val settings = settings()
    settings.awaitLoaded()
    settings.setSources(BackupSources.DEFAULT)
    settings.chooseFolder(DeviceFolder("S25 di Mario"))

    settings.setSources(BackupSources(setOf(BackupSource.Camera)))

    assertThat(settings.sources.value.sources).containsExactly(BackupSource.Camera)
    assertThat(settings.deviceFolder.value).isEqualTo(DeviceFolder("S25 di Mario")) // la cartella non si tocca
  }

  @Test
  fun iValoriIllegibiliSiIgnorano_unaSorgenteInventataNonEsiste() = runTest {
    val storage =
      MemoryBackupSettingsStorage(
        StoredBackupSettings(sourceIds = setOf("camera", "boh", "folder:", "folder:vol", "folder::path", "folder:vol: ", "screenshots"), deviceFolder = "a/b"),
      )
    val settings = settings(storage)
    settings.awaitLoaded()

    assertThat(settings.sources.value.sources).containsExactly(BackupSource.Camera, BackupSource.Screenshots)
    assertThat(settings.deviceFolder.value).isNull() // un nome che SharePath non accetta vale "non scelta"
  }

  // --- Cartella del dispositivo (D7) ------------------------------------------------------------------------------------------------------

  @Test
  fun laCartellaSiSceglieUnaVolta_esiRicorda() = runTest {
    val storage = MemoryBackupSettingsStorage()
    val settings = settings(storage)
    settings.awaitLoaded()

    settings.chooseFolder(DeviceFolder("S25 di Mario"))

    assertThat(settings.deviceFolder.value).isEqualTo(DeviceFolder("S25 di Mario"))
    val restarted = settings(storage)
    restarted.awaitLoaded()
    assertThat(restarted.deviceFolder.value).isEqualTo(DeviceFolder("S25 di Mario"))
    // Ripetere la stessa scelta (una ripresa dopo un'interruzione) e' innocuo.
    restarted.chooseFolder(DeviceFolder("S25 di Mario"))
  }

  @Test
  fun rinominareIlDispositivo_nonSpostaLaCartella_E_B7() = runTest {
    val settings = settings()
    settings.awaitLoaded()
    settings.chooseFolder(DeviceFolder("S25 di Mario"))

    // Il dispositivo si chiama ora "S25 di Anna": il nome nuovo darebbe un'altra cartella, ma la scelta di D7 e' una sola.
    val renamed = DeviceFolderPolicy.folderFor("S25 di Anna")
    assertThat(renamed).isNotEqualTo(settings.deviceFolder.value)
    assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { settings.chooseFolder(renamed) } }

    assertThat(settings.deviceFolder.value).isEqualTo(DeviceFolder("S25 di Mario"))
  }

  @Test
  fun dopoUnaReinstallazione_ilMagazzinoEVuoto_esiPropone_diContinuareNellaCartellaCheCe_E_B16() = runTest {
    // Reinstallazione: il magazzino locale non c'e' piu' (l'azzeramento lo cancella), la cartella sul NAS si'.
    val settings = settings(MemoryBackupSettingsStorage())
    settings.awaitLoaded()
    assertThat(settings.deviceFolder.value).isNull()

    val decision = DeviceFolderPolicy.decide("S25 di Mario", existingFolders = listOf("S25 di Mario", "Area comune"))
    decision as FolderDecision.AskExisting
    assertThat(decision.suggested).isEqualTo(FolderChoice.CONTINUE_THERE)

    // "Continua li'": si sceglie la cartella che c'e'.
    settings.chooseFolder(decision.existing)
    assertThat(settings.deviceFolder.value).isEqualTo(DeviceFolder("S25 di Mario"))
  }

  @Test
  fun duePosti_dueMagazzini_E_B6_cartelleSeparate() = runTest {
    val phone = settings(MemoryBackupSettingsStorage())
    val tablet = settings(MemoryBackupSettingsStorage())
    phone.awaitLoaded()
    tablet.awaitLoaded()

    phone.chooseFolder(DeviceFolder("Galaxy di Mario"))
    val decision = DeviceFolderPolicy.decide("Galaxy di Mario", existingFolders = listOf("Galaxy di Mario"), usedByOtherDevice = true)
    decision as FolderDecision.AskExisting
    tablet.chooseFolder(decision.newFolder)

    assertThat(phone.deviceFolder.value).isEqualTo(DeviceFolder("Galaxy di Mario"))
    assertThat(tablet.deviceFolder.value).isEqualTo(DeviceFolder("Galaxy di Mario (2)"))
  }

  // --- D13: la recinzione del telefono --------------------------------------------------------------------------------------------------------

  @Test
  fun conLaRecinzione_leSorgentiSonoSoloQuelleAmmesse_ilValoreScelto_restaCom() = runTest {
    val storage = MemoryBackupSettingsStorage()
    val settings = settings(storage, ProvaFence(), account = "test")
    settings.awaitLoaded()

    settings.setSources(BackupSources(setOf(BackupSource.Camera, BackupSource.Screenshots, telegram, prova)))

    assertThat(settings.fenced).isTrue()
    assertThat(settings.sources.value.sources).containsExactly(prova)
    assertThat(settings.chosenSources.value.sources).containsExactly(BackupSource.Camera, BackupSource.Screenshots, telegram, prova)
  }

  @Test
  fun conLaRecinzioneESenzaAccount_nessunaSorgente() = runTest {
    val settings = settings(fence = ProvaFence(), account = null)
    settings.awaitLoaded()

    settings.setSources(BackupSources.DEFAULT)

    assertThat(settings.fenced).isTrue()
    assertThat(settings.sources.value).isEqualTo(BackupSources.NONE)
  }

  @Test
  fun conUnAccountVero_laRecinzioneNonSiApplica_ESenzaRecinzioneNeppure() = runTest {
    val real = settings(fence = ProvaFence(), account = "alessio")
    real.awaitLoaded()
    real.setSources(BackupSources.DEFAULT)
    assertThat(real.fenced).isFalse()
    assertThat(real.sources.value).isEqualTo(BackupSources.DEFAULT)

    val release = settings(fence = null, account = "test")
    release.awaitLoaded()
    release.setSources(BackupSources.DEFAULT)
    assertThat(release.fenced).isFalse()
    assertThat(release.sources.value).isEqualTo(BackupSources.DEFAULT)
  }

  // --- Dati dell'utente: mai nei log ------------------------------------------------------------------------------------------------------------

  @Test
  fun nessunNomeNeiToString() {
    val stored = StoredBackupSettings(setOf("folder:external_primary:Pictures/Telegram/"), "S25 di Mario")

    assertThat(stored.toString()).doesNotContain("Mario")
    assertThat(stored.toString()).doesNotContain("Telegram")
    assertThat(DeviceFolder("S25 di Mario").toString()).doesNotContain("Mario")
    assertThat(telegram.toString()).doesNotContain("Telegram")
  }
}
