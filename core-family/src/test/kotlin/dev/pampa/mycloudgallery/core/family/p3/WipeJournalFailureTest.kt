package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.TrustSnapshot
import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.family.contract.WipeReason
import dev.pampa.mycloudgallery.core.family.lifecycle.CredentialWiper
import dev.pampa.mycloudgallery.core.family.lifecycle.DeviceSelfWipe
import dev.pampa.mycloudgallery.core.family.lifecycle.WipeJournal
import dev.pampa.mycloudgallery.core.family.lifecycle.WipeRecord
import dev.pampa.mycloudgallery.core.family.lifecycle.WorkCanceller
import dev.pampa.mycloudgallery.core.family.testing.FakeTrustStateStore
import dev.pampa.mycloudgallery.core.family.testing.OperationLog
import dev.pampa.mycloudgallery.core.family.testing.RecordingLocalDataEraser
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Revisione di sicurezza della Fase 2, F7: con un diario che non si puo' scrivere (disco pieno), un azzeramento
 * interrotto dopo la chiave del dispositivo deve comunque riprendere al riavvio. Prima la ripresa dipendeva solo dal
 * diario: chiave distrutta, credenziali del NAS e dati locali rimasti per sempre.
 */
class WipeJournalFailureTest {

  /** Un diario rotto: non si legge niente e ogni scrittura fallisce. */
  private class BrokenJournal : WipeJournal {
    override suspend fun read(): WipeRecord? = null

    override suspend fun write(record: WipeRecord) = throw IOException("disco pieno")

    override suspend fun clear() = Unit
  }

  private class Process(val log: OperationLog, val keys: SoftwareKeys, val store: FakeTrustStateStore, var credentialsFail: Boolean) {
    val wipe = DeviceSelfWipe(
      journal = BrokenJournal(),
      keys = keys,
      familyKeys = MemoryFamilyKeyVault(log).apply { key = FamilyKey.generate() },
      credentials = CredentialWiper {
        if (credentialsFail) throw IllegalStateException("processo ucciso")
        log.record("credentials:wipeAll")
        true
      },
      sessions = HostileSessions(HostileNasServer(), log),
      trustState = store,
      eraser = RecordingLocalDataEraser(log),
      work = WorkCanceller { log.record("work:cancelAll") },
      deviceClock = { 1L },
    )
  }

  private fun configuredStore() = FakeTrustStateStore(TrustSnapshot(ByteArray(8), "firmatario", "famiglia", "impronta", 1, emptyMap()))

  @Test
  fun senzaDiarioUnAzzeramentoInterrottoDopoLaChiaveRiprendeAlRiavvio() = runTest {
    val log = OperationLog()
    val keys = SoftwareKeys().apply { deviceSigner() }
    val store = configuredStore()

    // Prima corsa: la chiave sparisce, le credenziali no (processo ucciso), il diario non si scrive.
    val first = Process(log, keys, store, credentialsFail = true)
    assertThat(first.wipe.run(WipeReason.MARKED_LOST)).isEqualTo(WipePhase.FAMILY_KEY_DESTROYED)
    assertThat(keys.hasDeviceKey()).isFalse()
    assertThat(log.all()).doesNotContain("credentials:wipeAll")

    // Riavvio: diario vuoto, ma la chiave manca e lo stato fidato c'e': si finisce.
    val second = Process(log, keys, store, credentialsFail = false)
    second.wipe.resumeIfInterrupted()
    assertThat(log.all()).contains("credentials:wipeAll")
    assertThat(log.all()).contains("work:cancelAll")
    assertThat(store.snapshot).isNull()
    assertThat(second.wipe.progress.value).isEqualTo(WipePhase.DONE)
  }

  @Test
  fun unDispositivoMaiConfiguratoONormaleNonSiAzzeraDaSolo() = runTest {
    val log = OperationLog()
    // Nessuna chiave e nessuno stato: dispositivo mai configurato.
    Process(log, SoftwareKeys(), FakeTrustStateStore(), credentialsFail = false).wipe.resumeIfInterrupted()
    // Chiave e stato presenti: dispositivo configurato e in salute.
    Process(log, SoftwareKeys().apply { deviceSigner() }, configuredStore(), credentialsFail = false).wipe.resumeIfInterrupted()
    assertThat(log.all()).isEmpty()
  }
}
