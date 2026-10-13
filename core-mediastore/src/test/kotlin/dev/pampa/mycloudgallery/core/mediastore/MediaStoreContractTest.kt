package dev.pampa.mycloudgallery.core.mediastore

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.PauseReason
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.contract.RunResult
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.mediastore.noop.NoMediaStoreSource
import dev.pampa.mycloudgallery.core.mediastore.noop.NoMetaPriority
import dev.pampa.mycloudgallery.core.mediastore.noop.NoMetadataPass
import dev.pampa.mycloudgallery.core.mediastore.noop.NoPhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.noop.NoPhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.noop.NoPhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.noop.NoPhoneSyncRunner
import dev.pampa.mycloudgallery.core.mediastore.noop.NoRecentlyDeletedSource
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreContractTest {

  private fun row(id: Long, taken: Long, name: String = "IMG_$id.jpg") = MediaStoreRow(
    volume = "external_primary", id = id, type = RowType.IMAGE, displayName = name, relativePath = "DCIM/Camera/",
    mime = "image/jpeg", size = 2_000_000, dateModifiedMs = taken + 1, dateTakenMs = taken,
  )

  @Test
  fun unaRigaSiTraduceInPostoTimboEIndizi() {
    val row = row(7, taken = 1_700_000_000_000L)
    val location = row.phoneLocation(mediaStoreVersion = "v1")
    assertThat(location.mediaStoreId).isEqualTo(7L)
    assertThat(location.mediaStoreVersion).isEqualTo("v1")
    assertThat(location.relativePath).isEqualTo("DCIM/Camera/")
    assertThat(row.stamp().size).isEqualTo(2_000_000L)
    assertThat(row.toExtractHints(ZoneId.of("Europe/Rome")).msDateTakenMs).isEqualTo(1_700_000_000_000L)
    // DATE_MODIFIED arriva all'estrattore come ultimo ripiego per la data (E-M8).
    assertThat(row.toExtractHints(ZoneId.of("Europe/Rome")).msDateModifiedMs).isEqualTo(1_700_000_000_001L)
    // Nome e percorso sono dati personali: non escono nel toString.
    assertThat(row.toString()).doesNotContain("IMG_7")
    assertThat(row.toString()).doesNotContain("DCIM")
    assertThrows(IllegalArgumentException::class.java) { row(0, taken = 1L) }
  }

  @Test
  fun ilMediaStoreFintoDaLePiuRecentiPrimaEHaGenerazioni() = runTest {
    val store = FakeMediaStore()
    store.addVolume("external_primary")
    store.put(row(1, taken = 1_000))
    store.put(row(2, taken = 3_000))
    store.put(row(3, taken = 2_000))

    // Dalle piu' recenti: DATE_TAKEN decrescente.
    assertThat(store.query("external_primary", null, null, 10).map { it.id }).containsExactly(2L, 3L, 1L).inOrder()
    // Ripresa dopo la riga 2 (E-P4) e lotti da 1.
    assertThat(store.query("external_primary", null, afterId = 2, limit = 1).map { it.id }).containsExactly(3L)

    // Incrementale per generazione: la generazione si legge prima, poi cambia una riga sola.
    val before = store.generation("external_primary")!!
    store.put(row(1, taken = 1_000, name = "IMG_1_edit.jpg"))
    assertThat(store.query("external_primary", before, null, 10).map { it.id }).containsExactly(1L)
    store.remove("external_primary", 3)
    assertThat(store.deletedSince("external_primary", before)!!.map { it.id }).containsExactly(3L)
    assertThat(store.allIds("external_primary").toList()).containsExactly(1L, 2L)

    store.supportsGenerations = false
    assertThat(store.generation("external_primary")).isNull()
    assertThat(store.deletedSince("external_primary", 0)).isNull()
  }

  @Test
  fun laVersioneCambiaGliIdSiScambianoEIVolumiSparisconoSenzaEssereCancellati() = runTest {
    val store = FakeMediaStore()
    store.addVolume("external_primary")
    store.addVolume("1a2b-3c4d")
    store.put(row(1, taken = 2_000))
    store.put(row(2, taken = 1_000))

    // E-P12 / E-P22: stessa libreria, versione nuova, id scambiati.
    store.bumpVersion("external_primary", "v2", renumber = mapOf(1L to 2L, 2L to 1L))
    assertThat(store.version("external_primary")).isEqualTo("v2")
    assertThat(store.query("external_primary", null, null, 10).map { it.displayName }).containsExactly("IMG_1.jpg", "IMG_2.jpg").inOrder()
    assertThat(store.query("external_primary", null, null, 10).map { it.id }).containsExactly(2L, 1L).inOrder()

    // E-P6: la microSD tolta non e' piu' tra i volumi.
    assertThat(store.volumes()).containsExactly("external_primary", "1a2b-3c4d")
    store.setMounted("1a2b-3c4d", false)
    assertThat(store.volumes()).containsExactly("external_primary")
  }

  @Test
  fun scorciatoiaDelTimboNonApreNessunFile() = runTest {
    val store = FakeMediaStore()
    store.addVolume("external_primary")
    val stored = store.put(row(1, taken = 1_000))
    store.query("external_primary", null, null, 10)
    assertThat(store.opens).isEqualTo(0)
    store.open(stored)
    assertThat(store.opens).isEqualTo(1)
  }

  @Test
  fun iNullObjectFallisconoChiusiENonDeduconoAssenze() = runTest {
    assertThat(NoMediaStoreSource.volumes()).isEmpty()
    assertThat(NoMediaStoreSource.query("external_primary", null, null, 500)).isEmpty()
    assertThat(NoPhoneAccessReader.state.value).isEqualTo(PhoneAccess.NONE)
    assertThat(NoPhoneAccessReader.state.value.media).isEqualTo(MediaAccess.NONE)
    // Senza permessi la sincronizzazione e' ferma (E-P15), non "il telefono e' vuoto": giro incompleto.
    assertThat(NoPhoneLibrarySync.status.value).isEqualTo(SyncStatus.Paused(PauseReason.NO_PERMISSION))
    assertThat(NoPhoneLibrarySync.syncNow(SyncReason.MANUAL).completed).isFalse()
    assertThat(NoPhoneSyncRunner.run(8.minutes)).isEqualTo(RunResult.Paused(PauseReason.NO_PERMISSION))
    assertThat(NoMetadataPass.run(8.minutes, NoMetaPriority)).isEqualTo(RunResult.Done)
    assertThat(NoPhoneMediaIntents.canTrash).isFalse()
    assertThat(NoPhoneMediaIntents.share(listOf(MediaRef.Phone("external_primary", 1, "image/jpeg")))).isNull()
    assertThat(NoRecentlyDeletedSource.list()).isEmpty()
  }

  @Test
  fun iFakeDiAccessoESincronizzazioneRegistranoLeRichieste() = runTest {
    val access = FakePhoneAccessReader()
    access.refresh()
    access.set(PhoneAccess(MediaAccess.PARTIAL, mediaLocation = false, volumes = setOf("external_primary")))
    assertThat(access.refreshes).isEqualTo(1)
    assertThat(access.state.value.media).isEqualTo(MediaAccess.PARTIAL)

    val sync = FakePhoneLibrarySync()
    sync.syncNow(SyncReason.FOREGROUND)
    sync.pause(PauseReason.MEDIA_LOCATION_DENIED)
    assertThat(sync.requests).containsExactly(SyncReason.FOREGROUND)
    assertThat(sync.status.value).isEqualTo(SyncStatus.Paused(PauseReason.MEDIA_LOCATION_DENIED))

    val intents = FakePhoneMediaIntents()
    val ref = MediaRef.Phone("external_primary", 5, "image/jpeg")
    assertThat(intents.uriOf(ref).toString()).isEqualTo("content://media/external_primary/5")
    assertThat(intents.share(emptyList())).isNull()
    assertThat(intents.share(listOf(ref))).isNotNull()
    assertThat(intents.trashRequest(listOf(ref), trash = true)).isNull()
    assertThat(intents.trashRequests).containsExactly(listOf(ref) to true)
  }
}
