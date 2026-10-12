package dev.pampa.mycloudgallery.core.trust.testing

import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.KeyProvider
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/** 12/10/2026 12:00 UTC: l'ora "del NAS" dei test. */
const val NAS_NOON: Long = 1_791_806_400_000L

/** Orologio del NAS finto: si sposta a mano. L'orologio del dispositivo non esiste per core-trust. */
class FakeNasClock(var now: Long = NAS_NOON) : NasClock {
  override fun nowMillis(): Long = now

  fun advance(millis: Long) {
    now += millis
  }
}

/**
 * Chiave del dispositivo in software, per i test JVM: stessa curva e stesso algoritmo del Keystore.
 * Si appoggia a [FamilyKey] che e' gia' una coppia di chiavi JCA.
 */
class SoftwareKeyProvider : KeyProvider {
  private var key: FamilyKey? = null

  override fun deviceSigner(): Signer = key ?: FamilyKey.generate().also { key = it }

  override fun hasDeviceKey(): Boolean = key != null

  override fun destroyDeviceKey() {
    key = null
  }
}

fun newSigner(): Signer = FamilyKey.generate()

val Signer.deviceId: String get() = TrustIds.deviceIdOf(publicKey)

val EcPublicKey.deviceId: String get() = TrustIds.deviceIdOf(this)

/**
 * Una share in memoria con la semantica di [NasFileSystem] (pubblicazione esclusiva, nessuna cancellazione),
 * piu' le porte di servizio di un attaccante con accesso SMB in scrittura ma senza chiavi.
 */
class FakeNasFileSystem(override val shareName: String = "Public") : NasFileSystem {
  private val files = LinkedHashMap<String, ByteArray>()

  var reads: Int = 0
    private set

  override fun list(dir: SharePath, limit: Int): NasListing {
    val prefix = dir.value + "/"
    val names = files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
    return NasListing(names.take(limit), truncated = names.size > limit)
  }

  override fun read(path: SharePath, maxBytes: Int): NasRead {
    reads++
    val bytes = files[path.value] ?: return NasRead.NotFound
    return if (bytes.size > maxBytes) NasRead.TooLarge else NasRead.Data(bytes.copyOf())
  }

  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult {
    if (files.containsKey(path.value)) return PublishResult.ALREADY_EXISTS
    files[path.value] = bytes.copyOf()
    return PublishResult.CREATED
  }

  // --- Porte di servizio: cio' che puo' fare chiunque abbia SMB in scrittura -----------------------------

  fun bytesAt(path: SharePath): ByteArray? = files[path.value]?.copyOf()

  fun attackerWrite(rawPath: String, bytes: ByteArray) {
    files[rawPath] = bytes.copyOf()
  }

  fun attackerDelete(path: SharePath) {
    files.remove(path.value)
  }

  fun paths(): Set<String> = files.keys.toSet()
}
