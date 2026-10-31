package dev.pampa.mycloudgallery.core.release.testing

import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.ManifestRead
import dev.pampa.mycloudgallery.core.release.contract.ManifestSource
import dev.pampa.mycloudgallery.core.release.contract.ManifestVerifier
import dev.pampa.mycloudgallery.core.release.contract.RejectReason
import dev.pampa.mycloudgallery.core.release.contract.ReleaseClock
import dev.pampa.mycloudgallery.core.release.contract.ReleaseKeyIds
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStateStore
import dev.pampa.mycloudgallery.core.release.contract.StoredRelease
import dev.pampa.mycloudgallery.core.release.contract.Verified
import java.io.IOException
import java.time.Instant

// Fase 10 (P0, §5.2): i fake del manifest. **Nessuna chiave vera e nessuna firma vera qui**: [TableManifestVerifier] sostituisce codec e
// firma con una tabella (buste opache "firmate" dal test), cosi' lo stato si prova senza crittografia. Da P1 il fake della sorgente lavora
// con il codec e il verificatore di produzione (KT): il fake sostituisce solo la lettura.
// P1: [ReleaseSealer] e [FragileReleaseStore] sono le due capacita' che la suite `ReleaseStateContract` chiede al banco di prova, cosi' la
// stessa suite gira sul riferimento (tabella e memoria) e sullo stato vero (firma vera con `ReleaseTestSigner` e deposito su file).

/** Chi "firma" manifest per le prove: [seal] da' una busta accettata dal verificatore del banco, [tamper] la stessa con la firma che non torna. */
interface ReleaseSealer {
  fun seal(manifest: ReleaseManifest, keyId: Int = ReleaseKeyIds.K1): ByteArray

  fun tamper(sealed: ByteArray): ByteArray
}

/** Un deposito che sa morire fra la scrittura del temporaneo e la rinomina ([dieDuringNextWrite]) e farsi trovare rovinato ([corrupt]). */
interface FragileReleaseStore : ReleaseStateStore {
  fun dieDuringNextWrite()

  fun corrupt()
}

/**
 * Il verificatore a tabella: [seal] registra un manifest e da' una busta opaca; [verify] la riconosce. Le buste si possono [tamper] (firma non
 * valida), una chiave non fidata da' `UNKNOWN_KEY`, un'app diversa `WRONG_APP`, una busta sconosciuta `MALFORMED`, oltre il tetto `TOO_LARGE`.
 */
class TableManifestVerifier(val trusted: Set<Int> = setOf(ReleaseKeyIds.K1, ReleaseKeyIds.K2, ReleaseKeyIds.KT)) : ManifestVerifier, ReleaseSealer {

  private class Entry(val manifest: ReleaseManifest, val keyId: Int, val tampered: Boolean)

  private val table = HashMap<String, Entry>()
  private var next = 0

  override fun seal(manifest: ReleaseManifest, keyId: Int): ByteArray = register(Entry(manifest, keyId, tampered = false))

  /** La stessa busta con un byte cambiato: la firma non torna piu'. */
  override fun tamper(sealed: ByteArray): ByteArray {
    val entry = table[String(sealed, Charsets.US_ASCII)] ?: error("busta sconosciuta")
    return register(Entry(entry.manifest, entry.keyId, tampered = true))
  }

  private fun register(entry: Entry): ByteArray {
    val key = "MCGR-TABLE:${next++}"
    table[key] = entry
    return key.toByteArray(Charsets.US_ASCII)
  }

  override fun verify(bytes: ByteArray, app: String): Verified {
    if (bytes.size > ManifestLimits.MAX_BYTES) return Verified.Rejected(RejectReason.TOO_LARGE)
    val entry = table[String(bytes, Charsets.US_ASCII)] ?: return Verified.Rejected(RejectReason.MALFORMED)
    if (entry.keyId !in trusted) return Verified.Rejected(RejectReason.UNKNOWN_KEY)
    if (entry.tampered) return Verified.Rejected(RejectReason.SIGNATURE)
    if (entry.manifest.app != app) return Verified.Rejected(RejectReason.WRONG_APP)
    return Verified.Ok(entry.manifest, entry.keyId)
  }
}

/** Il deposito in memoria con la morte fra scrittura e rinomina ([dieDuringNextWrite]) e il file rovinato ([corrupt]: si legge come assente). */
class InMemoryReleaseStateStore : FragileReleaseStore {
  var saved: StoredRelease? = null
    private set
  var writes: Int = 0
    private set
  private var dieNext = false
  private var damaged = false

  override fun dieDuringNextWrite() {
    dieNext = true
  }

  override fun corrupt() {
    damaged = true
  }

  override suspend fun read(): StoredRelease? = if (damaged) null else saved

  override suspend fun write(state: StoredRelease) {
    if (dieNext) {
      dieNext = false
      throw IOException("morte simulata fra la scrittura del temporaneo e la rinomina")
    }
    damaged = false
    saved = state
    writes++
  }

  override suspend fun clear() {
    saved = null
    damaged = false
  }
}

/** L'ora del NAS finta (E-REL9), spostabile a mano. */
class FakeReleaseClock(var now: Instant = Instant.parse("2026-10-31T12:00:00Z")) : ReleaseClock {
  override fun bestNow(): Instant = now
}

/** La sorgente finta: risponde [next] (un file, assente, troppo grande, NAS irraggiungibile); [reads] conta le letture. */
class FakeManifestSource(var next: ManifestRead = ManifestRead.Absent) : ManifestSource {
  override val origin: ManifestOrigin = ManifestOrigin.NAS
  var reads: Int = 0
    private set

  override suspend fun read(maxBytes: Int): ManifestRead {
    reads++
    val current = next
    if (current is ManifestRead.Found && current.bytes.size > maxBytes) return ManifestRead.TooLarge
    return current
  }
}
