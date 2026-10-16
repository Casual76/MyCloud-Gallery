package dev.pampa.mycloudgallery.core.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.CandidateQuery
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.PhoneLookup
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.backup.noop.ClosedBackupGate
import dev.pampa.mycloudgallery.core.backup.noop.NoBackupCatalog
import dev.pampa.mycloudgallery.core.backup.noop.NoBackupEngine
import dev.pampa.mycloudgallery.core.backup.noop.NoBackupStore
import dev.pampa.mycloudgallery.core.backup.noop.NoPhoneBytes
import dev.pampa.mycloudgallery.core.backup.noop.UnknownExistingCopies
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupSettings
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupEngine
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupStore
import dev.pampa.mycloudgallery.core.backup.testing.ReferenceBackupPlanner
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/** I fake e i null-object di core-backup fanno cio' che il contratto promette (P2-P6 e P15 ci si appoggiano). */
class FakesTest {

  private fun newItem(version: VersionId = VersionId.random(), kind: UploadKind = UploadKind.NEW_COPY) = NewBackupItem(
    kind = kind, mediaId = MediaId.random(), versionId = version, size = 10, phone = PhoneRef("external_primary", 1, "v1"),
    expected = FileStamp(10, 1, 1), share = "test", planned = SharePath.parse("Backup/S25/2026/10/IMG_1.jpg"),
  )

  @Test
  fun ilDiarioRifiutaLeTransizioniFuoriDallaMacchinaEINonFaDoppioni() = runTest {
    val store = InMemoryBackupStore()
    val version = VersionId.random()
    val item = store.create(listOf(newItem(version))).single()
    // Idempotente per versione: nessun secondo elemento aperto.
    assertThat(store.create(listOf(newItem(version))).single().id).isEqualTo(item.id)
    assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { store.update(item.copy(state = UploadState.PUBLISHING)) } }
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { store.update(item.copy(share = "alessio")) } }
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { store.create(listOf(newItem(kind = UploadKind.VERSION))) } }
    val temp = SharePath.parse(".mycloudgallery/tmp/x-bk-1")
    store.update(item.copy(state = UploadState.HASHED))
    store.update(item.copy(state = UploadState.UPLOADING, temp = temp))
    assertThat(store.citedTemps()).containsExactly(temp)
    store.update(item.copy(state = UploadState.GONE, temp = temp))
    assertThat(store.citedTemps()).isEmpty()
    assertThat(store.itemsToResume(10)).isEmpty()
    // Uno stato terminale non si lascia.
    assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { store.update(item.copy(state = UploadState.PLANNED)) } }
  }

  @Test
  fun ilTelefonoFintoSimulaSparizioniRiscrittureERinumerazioni() = runTest {
    val phone = FakePhoneBytes()
    val ref = phone.add(1, NasContent.of(ByteArray(100) { it.toByte() }))
    assertThat(phone.port.lookup(ref)).isInstanceOf(PhoneLookup.Present::class.java)
    phone.rewrite(1, NasContent.of(ByteArray(100) { 7 }), modifiedMs = 9)
    assertThat((phone.port.lookup(ref) as PhoneLookup.Present).stamp.modifiedAtMillis).isEqualTo(9)
    phone.rebuild("v2", mapOf(1L to 5L))
    assertThat(phone.port.lookup(ref)).isEqualTo(PhoneLookup.Stale)
    assertThat(phone.port.lookup(phone.ref(5)!!)).isInstanceOf(PhoneLookup.Present::class.java)
    phone.delete(5)
    assertThat(phone.port.lookup(PhoneRef(ref.volume, 5, "v2"))).isEqualTo(PhoneLookup.Gone)
    // Sparita a meta' lettura: la lettura dopo l'evento fallisce.
    val other = phone.add(2, NasContent.of(ByteArray(1000) { 3 }))
    phone.duringRead(2, afterBytes = 10, event = FakePhoneBytes.MidRead.Vanished)
    val file = (phone.port.open(other) as PhoneOpen.Opened).file
    assertThat(file.bytes.read(0L until 100L)).hasLength(100)
    assertThrows(java.io.IOException::class.java) { kotlinx.coroutines.runBlocking { file.bytes.read(100L until 200L) } }
    assertThat(phone.firedEvents()).isEqualTo(1)
  }

  @Test
  fun alSicuroSoloConUnaCopiaPrivataVerificata() = runTest {
    val kit = BackupTestKit()
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(ByteArray(10) { 1 }))
    val m = kit.catalog.media(media)!!
    val copy = VerifiedCopy(media, m.versionId, kit.share, SharePath.parse("Backup/a.jpg"), NasStamp(10, 1, 1), 5)
    // Senza l'hash completo della versione non si attacca una copia verificata (come `attachCopy`).
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { kit.catalog.attachVerifiedCopies(listOf(copy)) } }
    kit.catalog.recordFullHash(media, m.versionId, Sha256.of(ByteArray(10) { 1 }))
    // Una copia nell'area comune non fa "al sicuro" (D9, Q4).
    kit.catalog.attachVerifiedCopies(listOf(VerifiedCopy(media, m.versionId, kit.commonShare, SharePath.parse("F/a.jpg"), NasStamp(10, 1, 2), 5)))
    assertThat(kit.catalog.isSafe(media)).isFalse()
    kit.catalog.attachVerifiedCopies(listOf(copy))
    assertThat(kit.catalog.isSafe(media)).isTrue()
    assertThat(kit.catalog.candidates(CandidateQuery(BackupSources.DEFAULT, BackupKind.PHOTOS, null, 10))).isEmpty()
  }

  @Test
  fun ilPianoDiRiferimentoMetteOgniCosaAlSuoPosto() {
    val planner = ReferenceBackupPlanner(NoBackupCatalog)
    val folder = DeviceFolder("S25 di prova")
    fun candidate(source: BackupSource, day: Int?) = BackupCandidate(
      mediaId = MediaId.random(), versionId = VersionId.random(), phone = PhoneRef("v", 1, "1"), stamp = FileStamp(1, 1), size = 1,
      kind = MediaKind.PHOTO, source = source, localDay = day, dateEstimated = false, fileName = "IMG_1.jpg", sortTime = 0, quickHash = null,
    )
    assertThat(planner.destination(candidate(BackupSource.Camera, 20261012), folder).value).isEqualTo("Backup/S25 di prova/2026/10/IMG_1.jpg")
    assertThat(planner.destination(candidate(BackupSource.Screenshots, 20261012), folder).value)
      .isEqualTo("Backup/S25 di prova/Screenshot/2026/10/IMG_1.jpg")
    assertThat(planner.destination(candidate(BackupSource.Camera, 19790101), folder).value).isEqualTo("Backup/S25 di prova/Senza data/IMG_1.jpg")
    assertThat(planner.destination(candidate(BackupSource.Camera, null), folder).value).isEqualTo("Backup/S25 di prova/Senza data/IMG_1.jpg")
    assertThat(InMemoryBackupEngine.nameFor(SharePath.parse("A/IMG_1.jpg"), 1).value).isEqualTo("A/IMG_1 (2).jpg")
    assertThat(InMemoryBackupEngine.nameFor(SharePath.parse("A/LEGGIMI"), 2).value).isEqualTo("A/LEGGIMI (3)")
  }

  @Test
  fun iNullObjectFallisconoChiusi() = runTest {
    assertThat(NoBackupStore.create(listOf(newItem()))).isEmpty()
    assertThat(NoPhoneBytes.lookup(PhoneRef("v", 1, "1"))).isEqualTo(PhoneLookup.Stale)
    assertThat(ClosedBackupGate.stateOf("test")).isEqualTo(BackupGateState.NOT_READY)
    assertThat(UnknownExistingCopies.current().settled).isFalse()
    assertThat(NoBackupCatalog.attachVerifiedCopies(emptyList())).isEqualTo(0)
    assertThat(NoBackupEngine.status.value).isInstanceOf(dev.pampa.mycloudgallery.core.backup.contract.BackupStatus.NotConfigured::class.java)
    val settings = FakeBackupSettings()
    settings.chooseFolder(DeviceFolder("S25"))
    assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { settings.chooseFolder(DeviceFolder("S25 nuovo")) } }
  }
}
