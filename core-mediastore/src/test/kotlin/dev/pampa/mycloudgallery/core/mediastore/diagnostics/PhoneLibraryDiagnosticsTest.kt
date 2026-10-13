package dev.pampa.mycloudgallery.core.mediastore.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PauseReason
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.mediastore.sync.IgnoreReason
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneSyncTestBase
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneLibrarySync
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Il contributo alla Diagnostica: le voci giuste per gli edge case, e nell'esportazione solo numeri ed enumerati. */
@RunWith(AndroidJUnit4::class)
class PhoneLibraryDiagnosticsTest : PhoneSyncTestBase() {

  @Test
  fun dopoUnGiroVeroLeVociContanoSenzaDatiPersonali() = runBlocking<Unit> {
    store.addVolume(SD, version = "sd1")
    store.put(photo(1, taken = T0, name = "Mario Rossi al mare.jpg", path = "DCIM/Vacanze Sardegna/"))
    store.put(photo(2, taken = T0 + MINUTE, size = 2_000), ByteArray(2_000))
    store.put(photo(3, taken = T0 + 2 * MINUTE, volume = SD))
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    // La scheda viene tolta e il permesso della posizione ritirato.
    store.setMounted(SD, false)
    access.set(PhoneAccess(MediaAccess.FULL, mediaLocation = false, volumes = setOf(PRIMARY)))
    store.mediaLocationGranted = false
    store.put(photo(4, taken = T0 + 3 * MINUTE))
    sync.syncNow(SyncReason.CONTENT_CHANGE)

    val items = PhoneLibraryDiagnostics(sync, access, sync.stats) { catalog.mediaStoreCursors() }.collect()

    assertThat(items.map { it.section }.toSet()).containsExactly(DiagSection.PHONE_LIBRARY)
    val byCode = items.associateBy { it.code }
    assertThat(byCode.getValue(PhoneLibraryDiagCodes.ACCESS).severity).isEqualTo(DiagSeverity.OK)
    val location = byCode.getValue(PhoneLibraryDiagCodes.MEDIA_LOCATION)
    assertThat(location.severity).isEqualTo(DiagSeverity.WARNING)
    assertThat(location.export["pendingPhotos"]).isEqualTo(ExportValue.Num(1))
    val volumes = byCode.getValue(PhoneLibraryDiagCodes.VOLUMES)
    assertThat(volumes.export["known"]).isEqualTo(ExportValue.Num(2))
    assertThat(volumes.export["unavailable"]).isEqualTo(ExportValue.Num(1))
    val ignored = byCode.getValue(PhoneLibraryDiagCodes.IGNORED)
    assertThat(ignored.severity).isEqualTo(DiagSeverity.INFO)
    assertThat(ignored.export["notMedia"]).isEqualTo(ExportValue.Num(1))
    val syncItem = byCode.getValue(PhoneLibraryDiagCodes.SYNC)
    assertThat(syncItem.export["state"]).isEqualTo(ExportValue.Enum.of(PhoneSyncState.PAUSED))
    assertThat(syncItem.export["pauseReason"]).isEqualTo(ExportValue.Enum.of(PauseReason.MEDIA_LOCATION_DENIED))
    assertThat(byCode).containsKey(PhoneLibraryDiagCodes.OTHER_PROFILES)
    assertThat(sync.stats.snapshot.value.ignored[IgnoreReason.NOT_MEDIA]).isEqualTo(1L)

    // Nell'esportazione non c'e' testo libero: niente nomi di file, cartelle o volumi.
    val exported = items.flatMap { it.export.values }.joinToString { it.toString() }
    listOf("Mario", "Rossi", "Sardegna", "DCIM", SD, PRIMARY).forEach { assertThat(exported).doesNotContain(it) }
    items.flatMap { it.export.values }.forEach { value ->
      assertThat(value is ExportValue.Num || value is ExportValue.Flag || value is ExportValue.Enum).isTrue()
    }
    // Ogni codice dichiara gli edge case che copre (il catalogo della Diagnostica li raccoglie).
    PhoneLibraryDiagCodes.ALL.forEach { assertThat(it.edgeCases).isNotEmpty() }
  }

  @Test
  fun discoPienoESbloccoHannoLeLoroVoci() = runBlocking<Unit> {
    val sync = FakePhoneLibrarySync()
    val contributor = PhoneLibraryDiagnostics(sync, access, newSync().stats) { emptyList() }

    sync.setStatus(SyncStatus.Paused(PauseReason.DISK_FULL))
    val full = contributor.collect().associateBy { it.code }
    assertThat(full.getValue(PhoneLibraryDiagCodes.DISK_FULL).severity).isEqualTo(DiagSeverity.ERROR)

    sync.setStatus(SyncStatus.Paused(PauseReason.STORAGE_LOCKED))
    assertThat(contributor.collect().map { it.code }).contains(PhoneLibraryDiagCodes.STORAGE_LOCKED)

    access.set(PhoneAccess(MediaAccess.PARTIAL, mediaLocation = true, volumes = setOf(PRIMARY)))
    val partial = contributor.collect().associateBy { it.code }.getValue(PhoneLibraryDiagCodes.ACCESS)
    assertThat(partial.severity).isEqualTo(DiagSeverity.INFO)
    assertThat(partial.export["media"]).isEqualTo(ExportValue.Enum.of(MediaAccess.PARTIAL))
  }
}
