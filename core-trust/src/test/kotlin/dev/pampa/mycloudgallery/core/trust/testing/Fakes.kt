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
 *
 * Volutamente scomoda come un NAS vero (revisione 1.4, I3): gli elenchi escono in ordine casuale (con un
 * seme, quindi riproducibile) e i nomi non distinguono maiuscole e minuscole, come Samba per i client
 * Windows: `ABC` e `abc` sono lo stesso file.
 */
class FakeNasFileSystem(override val shareName: String = "Public", seed: Long = 1) : NasFileSystem {
  private val files = LinkedHashMap<String, Pair<String, ByteArray>>() // chiave minuscola -> (nome vero, byte)
  private val random = java.util.Random(seed)

  var reads: Int = 0
    private set

  /**
   * Se vero, l'elenco mette per primi i nomi scritti dall'attaccante: con un'inondazione oltre il tetto i nomi
   * veri escono dall'elenco troncato (il caso peggiore di un ordine che il NAS non garantisce).
   */
  var adversarialListing: Boolean = false

  private val attackerKeys = HashSet<String>()

  private fun key(path: String) = path.lowercase(java.util.Locale.ROOT)

  override fun list(dir: SharePath, limit: Int): NasListing {
    val prefix = key(dir.value) + "/"
    val entries = files.entries.filter { it.key.startsWith(prefix) }
    val names = entries
      .map { it.value.first.substring(prefix.length).substringBefore('/') to (it.key in attackerKeys) }
      .distinctBy { it.first.lowercase(java.util.Locale.ROOT) }
      .shuffled(random)
      .let { all -> if (adversarialListing) all.sortedByDescending { it.second } else all }
      .map { it.first }
    return NasListing(names.take(limit), truncated = names.size > limit)
  }

  override fun read(path: SharePath, maxBytes: Int): NasRead {
    reads++
    val bytes = files[key(path.value)]?.second ?: return NasRead.NotFound
    return if (bytes.size > maxBytes) NasRead.TooLarge else NasRead.Data(bytes.copyOf())
  }

  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult {
    if (files.containsKey(key(path.value))) return PublishResult.ALREADY_EXISTS
    files[key(path.value)] = path.value to bytes.copyOf()
    return PublishResult.CREATED
  }

  // --- Porte di servizio: cio' che puo' fare chiunque abbia SMB in scrittura -----------------------------

  fun bytesAt(path: SharePath): ByteArray? = files[key(path.value)]?.second?.copyOf()

  fun attackerWrite(rawPath: String, bytes: ByteArray) {
    files[key(rawPath)] = rawPath to bytes.copyOf()
    attackerKeys.add(key(rawPath))
  }

  fun attackerDelete(path: SharePath) {
    files.remove(key(path.value))
    attackerKeys.remove(key(path.value))
  }

  /** Riempie [dir] di [count] nomi qualunque (non numeri di versione): l'inondazione di M1/N2. */
  fun attackerFlood(dir: SharePath, count: Int) {
    repeat(count) { attackerWrite(dir.value + "/x" + it.toString().padStart(6, '0'), ByteArray(1)) }
  }

  fun paths(): Set<String> = files.values.map { it.first }.toSet()
}
