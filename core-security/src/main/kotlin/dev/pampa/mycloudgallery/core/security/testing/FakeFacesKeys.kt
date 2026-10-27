package dev.pampa.mycloudgallery.core.security.testing

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.security.faces.FacesDataKey
import dev.pampa.mycloudgallery.core.security.faces.FacesKeyAliases
import dev.pampa.mycloudgallery.core.security.faces.FacesKeys
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Fase 8 (P0, §5.2, `FakeFacesKeys`): le chiavi dei volti in memoria, fedeli al Keystore per cio' che conta: AES-256-GCM vero (della JVM),
 * una chiave per generazione, distruzione che rende illeggibile tutto cio' che era cifrato, chiave sparita (E-F19, [lose]) e guasti
 * passeggeri ([failNext]). Passa la stessa suite della chiave vera ([FacesKeysContract]; P6 la fa girare sul Keystore, strumentato). In
 * src/main per i test di core-faces (P4, P6, P8, P12); mai nella release (tools/check.ps1).
 *
 * [events] registra l'ordine delle operazioni ("destroy:<alias>"...): il banco della cancellazione (P8, P12) prova che la chiave si
 * distrugge **prima** dei file.
 */
class FakeFacesKeys : FacesKeys {
  private val random = SecureRandom()
  private val keys = LinkedHashMap<String, ByteArray>()

  /** Il Keystore non risponde alla prossima chiamata (una volta): create e destroy rispondono false, open null. */
  @Volatile var failNext: Boolean = false

  /** L'ordine delle operazioni, con l'alias (mai la chiave). */
  val events: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

  /** Le chiavi aperte non ancora chiuse (un test di P6 controlla che il negozio le chiuda). */
  val openKeys: Int get() = synchronized(opened) { opened.count { !it.closed } }

  private val opened = mutableListOf<FakeDataKey>()

  /** La chiave di [generation] sparisce senza una distruzione (ripristino, aggiornamento del sistema: E-F19). */
  fun lose(generation: FaceGeneration) = synchronized(this) {
    keys.remove(FacesKeyAliases.aliasOf(generation))
    events += "lost:${FacesKeyAliases.aliasOf(generation)}"
  }

  /** Esiste la chiave di [generation]? (per gli invarianti del §2.8: nessuna chiave `mcg-faces-<G>` dopo `LOCAL_DONE`). */
  fun exists(generation: FaceGeneration): Boolean = synchronized(this) { FacesKeyAliases.aliasOf(generation) in keys }

  fun aliases(): Set<String> = synchronized(this) { keys.keys.toSet() }

  private fun consumeFailure(): Boolean = synchronized(this) {
    val fail = failNext
    failNext = false
    fail
  }

  override suspend fun create(generation: FaceGeneration): Boolean {
    if (consumeFailure()) return false
    synchronized(this) {
      val alias = FacesKeyAliases.aliasOf(generation)
      if (alias !in keys) keys[alias] = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
      events += "create:$alias"
    }
    return true
  }

  override suspend fun open(generation: FaceGeneration): FacesDataKey? {
    if (consumeFailure()) return null
    val material = synchronized(this) { keys[FacesKeyAliases.aliasOf(generation)]?.copyOf() } ?: return null
    return FakeDataKey(material, random).also { synchronized(opened) { opened += it } }
  }

  override suspend fun destroy(generation: FaceGeneration): Boolean {
    if (consumeFailure()) return false
    synchronized(this) {
      val alias = FacesKeyAliases.aliasOf(generation)
      keys.remove(alias)?.fill(0)
      events += "destroy:$alias"
    }
    return true
  }

  override suspend fun destroyAll(): Boolean {
    if (consumeFailure()) return false
    synchronized(this) {
      for (alias in keys.keys.filter { it.startsWith(FacesKeyAliases.PREFIX) }) {
        keys.remove(alias)?.fill(0)
        events += "destroy:$alias"
      }
    }
    return true
  }

  private class FakeDataKey(material: ByteArray, private val random: SecureRandom) : FacesDataKey {
    private val key = material
    @Volatile var closed = false

    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
      check(!closed) { "Chiave chiusa." }
      val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
      cipher.updateAAD(aad)
      return nonce + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray? {
      check(!closed) { "Chiave chiusa." }
      if (sealed.size < NONCE_BYTES + TAG_BITS / 8) return null
      return try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, NONCE_BYTES)))
        cipher.updateAAD(aad)
        cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
      } catch (e: GeneralSecurityException) {
        null
      }
    }

    override fun close() {
      closed = true
      key.fill(0)
    }

    override fun toString(): String = "FakeDataKey"
  }

  private companion object {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    const val TRANSFORMATION = "AES/GCM/NoPadding"
  }
}
