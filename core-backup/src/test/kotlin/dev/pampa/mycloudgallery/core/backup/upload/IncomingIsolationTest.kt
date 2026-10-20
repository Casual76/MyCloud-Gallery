package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupGate
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupCatalog
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * R5 della revisione di sicurezza 5B: un file in arrivo diretto a una share chiusa (l'area comune congelata da un incidente) o che la share rifiuta
 * ferma **solo se stesso**, mai il backup privato. Prima, nei giri normali (foto, video, ripresa) l'arrivo entrava nella coda e, trovando il
 * cancello della sua share chiuso, il giro si chiudeva con `Problem(FROZEN)`: nessuna foto nuova andava nell'area privata finche' durava il
 * congelamento (e con la sola lettura scoperta dopo la messa in coda, per sempre: l'arrivo restava aperto e andava per primo ad ogni giro).
 */
class IncomingIsolationTest {

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 31 + seed * 17 + (it shr 9)).toByte() }

  private class LocalFiles : IncomingLocalFiles {
    val files = HashMap<Long, ByteArray>()

    override suspend fun open(id: BackupItemId): IncomingLocalFile? {
      val data = files[id.value] ?: return null
      return object : IncomingLocalFile {
        override val bytes: MediaBytes = InMemoryMediaBytes(data)

        override fun close() = Unit
      }
    }

    override suspend fun finished(id: BackupItemId, state: UploadState) {
      if (state == UploadState.DONE || state == UploadState.LINKED_EXISTING) files.remove(id.value)
    }
  }

  private class Bench {
    val kit = BackupTestKit()
    val local = LocalFiles()
    val incomingGate = FakeBackupGate(BackupGateState.OPEN)
    val engine: SafeUploadEngine = SafeUploadEngine(
      deviceId = kit.deviceId,
      share = kit.share,
      ops = kit.ops,
      reader = kit.nas.reader,
      store = kit.store,
      catalog = kit.catalog,
      phone = kit.phone.port,
      gate = kit.gate,
      clock = kit.clock,
      planner = kit.planner,
      folder = { kit.folder },
      existing = RereadingCopyFinder(kit.catalog, kit.nas.reader, kit.clock),
      facts = KitFacts(kit.catalog),
      // Un file alla volta: l'ordine delle chiamate al NAS e' quello del diario (l'arrivo, piu' vecchio, per primo).
      config = UploadConfig(chunkBytes = kit.chunkBytes, checkpointBytes = kit.checkpointBytes, parallel = 1, lockedFirstWaitMs = 10, lockedRetries = 3),
      sleep = { ms -> kit.clock.advance(ms) },
      incoming = IncomingUploadPorts(local, incomingGate, null),
    )

    /** Un file ricevuto, messo in coda per [share]/[folder]/[name] prima di ogni foto del telefono (il suo `item_id` e' il piu' basso). */
    suspend fun enqueue(share: String, folder: String, name: String, data: ByteArray): BackupItem {
      val sha = FakePhoneBytes.sha256Of(NasContent.of(data))
      val media = kit.catalog.put(
        InMemoryBackupCatalog.Media(
          mediaId = MediaId.random(), versionId = VersionId.random(), kind = MediaKind.PHOTO, size = data.size.toLong(), quickHash = null, phone = null,
          phoneStamp = null, source = BackupSource.Camera, localDay = null, fileName = name, sortTime = 0, fullHash = null,
        ),
      )
      val item = kit.store.createIncoming(
        NewBackupItem(
          kind = UploadKind.INCOMING, mediaId = media.mediaId, versionId = media.versionId, size = data.size.toLong(), phone = null,
          expected = null, share = share, planned = SharePath.parse("$folder/$name"), sha256 = sha,
        ),
      )
      local.files[item.id.value] = data.copyOf()
      return item
    }

    suspend fun photoPaths(): List<String> = kit.nas.filePaths(kit.share).filter { it.startsWith("Backup/") }
  }

  @Test
  fun r5_unArrivoVersoLAreaComuneCongelataNonFermaLaFotoNuova() = runTest {
    val b = Bench()
    val arrival = b.enqueue(b.kit.commonShare, "Famiglia/Gite", "ricevuta.jpg", bytes(20_000, 1))
    b.incomingGate.byShare[b.kit.commonShare] = BackupGateState.FROZEN // un incidente: l'area comune e' congelata
    b.kit.addPhoneMedia("IMG_NUOVA.jpg", NasContent.of(bytes(30_000, 2)))

    // Un giro normale delle foto: guida anche gli arrivi (sono nel diario), piu' vecchi della foto che il giro pianifica adesso.
    val result = b.engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)

    assertWithMessage("giro: $result").that(result.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(result.problem).isNull()
    assertThat(result.done).isEqualTo(1)
    assertThat(b.photoPaths()).hasSize(1) // la foto e' nell'area privata
    // L'arrivo e' fermo da solo: aperto, con la sua copia locale, niente scritto nell'area comune.
    val still = checkNotNull(b.kit.store.item(arrival.id))
    assertThat(still.state.terminal).isFalse()
    assertThat(b.local.files).containsKey(arrival.id.value)
    assertThat(b.kit.nas.filePaths(b.kit.commonShare)).isEmpty()
    assertThat(result.waiting).isEqualTo(1)

    // Scongelata, l'arrivo riparte con il giro dopo e arriva nell'area comune.
    b.incomingGate.byShare.remove(b.kit.commonShare)
    val again = b.engine.resumeAll(1.hours)
    assertThat(again.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(b.kit.nas.bytes(b.kit.commonShare, "Famiglia/Gite/ricevuta.jpg")?.toList()).isEqualTo(bytes(20_000, 1).toList())
    assertThat(checkNotNull(b.kit.store.item(arrival.id)).state).isEqualTo(UploadState.DONE)
    assertThat(b.local.files).doesNotContainKey(arrival.id.value)
  }

  @Test
  fun r5_ConIlBackupInProvaOLaSolaLetturaDellaShareDiDestinazioneL_ArrivoAspettaDaSolo() = runTest {
    for (closed in listOf(BackupGateState.TEST_ONLY, BackupGateState.READ_ONLY_AREA, BackupGateState.NO_PRIVATE_AREA, BackupGateState.FROZEN)) {
      val b = Bench()
      b.enqueue(b.kit.commonShare, "Famiglia", "x.jpg", bytes(10_000, 3))
      b.incomingGate.byShare[b.kit.commonShare] = closed
      b.kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(12_000, 4)))
      val result = b.engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
      assertWithMessage("$closed: $result").that(result.stoppedBy).isEqualTo(RunStop.COMPLETE)
      assertWithMessage("$closed").that(b.photoPaths()).hasSize(1)
    }
  }

  /** Il giro dei soli arrivi (il lavoro `INCOMING`): un arrivo bloccato non ferma gli altri arrivi diretti a una share aperta. */
  @Test
  fun r5_NelGiroDeiSoliArriviUnaShareChiusaNonFermaGliAltri() = runTest {
    val b = Bench()
    val blocked = b.enqueue(b.kit.commonShare, "Famiglia", "bloccato.jpg", bytes(10_000, 5))
    b.enqueue(b.kit.share, "Ricevute", "libero.jpg", bytes(11_000, 6))
    b.incomingGate.byShare[b.kit.commonShare] = BackupGateState.FROZEN

    val result = b.engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.INCOMING)

    assertThat(result.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(result.done).isEqualTo(1)
    assertThat(b.kit.nas.bytes(b.kit.share, "Ricevute/libero.jpg")?.toList()).isEqualTo(bytes(11_000, 6).toList())
    assertThat(checkNotNull(b.kit.store.item(blocked.id)).state.terminal).isFalse()
  }

  /** L'emergenza ("Ferma le scritture"), la pausa dell'utente e la famiglia non pronta valgono per tutto: l'arrivo li segue come ogni altro elemento. */
  @Test
  fun r5_LePausaEL_EmergenzaRestanoGlobali() = runTest {
    for (closed in listOf(BackupGateState.PAUSED, BackupGateState.WRITES_STOPPED, BackupGateState.NOT_READY)) {
      val b = Bench()
      b.enqueue(b.kit.share, "Ricevute", "x.jpg", bytes(10_000, 3))
      b.incomingGate.default = closed
      b.kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(12_000, 4)))
      val result = b.engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
      assertWithMessage("$closed: $result").that(result.stoppedBy).isNotEqualTo(RunStop.COMPLETE)
      assertWithMessage("$closed").that(b.photoPaths()).isEmpty()
    }
  }

  /**
   * R5: una share che rifiuta la scrittura (`PermissionDenied`: sola lettura per questo account) scoperta mentre si carica l'arrivo non ferma il
   * giro (prima: `READ_ONLY_AREA` per tutti). L'arrivo aspetta; la foto del telefono, nell'area privata, parte.
   */
  @Test
  fun r5_UnaShareCheRifiutaLaScritturaFermaSoloL_Arrivo() = runTest {
    val b = Bench()
    val arrival = b.enqueue(b.kit.commonShare, "Famiglia", "x.jpg", bytes(10_000, 7))
    b.kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(12_000, 8)))
    // La prima chiamata al NAS e' quella dell'arrivo (e' il piu' vecchio del diario): "accesso negato".
    val denied = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.Status(0xC0000022L))
    b.kit.nas.opsFaults = denied
    b.kit.nas.readFaults = denied

    val result = b.engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
    b.kit.nas.opsFaults = null
    b.kit.nas.readFaults = null

    assertWithMessage("il guasto e' scattato: ${denied.fired}").that(denied.fired).hasSize(1)
    assertWithMessage("giro: $result").that(result.problem).isNotEqualTo(BackupProblemCode.READ_ONLY_AREA)
    assertThat(result.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(b.photoPaths()).hasSize(1)
    assertThat(checkNotNull(b.kit.store.item(arrival.id)).state.terminal).isFalse()
  }

  /**
   * R15 della revisione di sicurezza 5B: un arrivo che non riesce (qui: la copia locale non ha piu' i byte del tocco) finisce `FAILED` con la sua
   * copia locale, ma **non mette il segno del problema sul media**. Il media di un arrivo puo' essere uno che esisteva gia' (gli stessi byte, ritrovati
   * per SHA-256): il segno lo avrebbe tolto dal piano del backup delle foto, per colpa di un file che non c'entra.
   */
  @Test
  fun r15_UnArrivoCheNonRiesceNonMetteIlSegnoDelProblemaSulMedia() = runTest {
    val b = Bench()
    val good = bytes(10_000, 9)
    val arrival = b.enqueue(b.kit.share, "Ricevute", "x.jpg", good)
    b.local.files[arrival.id.value] = bytes(10_000, 10) // stessa dimensione, altri byte: la copia locale e' cambiata dopo il tocco

    val result = b.engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.INCOMING)

    val after = checkNotNull(b.kit.store.item(arrival.id))
    assertWithMessage("giro: $result").that(after.state).isEqualTo(UploadState.FAILED)
    assertThat(after.problem).isEqualTo(BackupProblemCode.UNREADABLE)
    assertThat(b.kit.catalog.problemOf(arrival.mediaId)).isNull()
    assertThat(b.local.files).containsKey(arrival.id.value) // la copia locale resta
    assertThat(b.kit.nas.filePaths(b.kit.share).filter { it.startsWith("Ricevute/") }).isEmpty()
  }
}
