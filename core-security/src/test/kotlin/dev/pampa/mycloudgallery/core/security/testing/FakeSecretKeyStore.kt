package dev.pampa.mycloudgallery.core.security.testing

import dev.pampa.mycloudgallery.core.security.credentials.GeneratedKey
import dev.pampa.mycloudgallery.core.security.credentials.KeyProtectionLevel
import dev.pampa.mycloudgallery.core.security.credentials.SecretKeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Un Keystore in memoria per i test unitari: chiavi AES normali, piu' i guasti che il Keystore vero
 * puo' dare (chiave sparita, chiave che non si recupera, guasto passeggero), e un registro degli eventi per
 * controllare l'ordine delle operazioni (es. "prima la chiave, poi i dati").
 */
internal class FakeSecretKeyStore(
  var level: KeyProtectionLevel = KeyProtectionLevel.STRONGBOX,
) : SecretKeyStore {

  val keys = mutableMapOf<String, SecretKey>()
  val events = mutableListOf<String>()

  /** Se valorizzato, `find` lancia questa eccezione (il Keystore non risponde, o non recupera la chiave). */
  var findFailure: Throwable? = null
  var createFailure: Throwable? = null
  var deleteFailure: Throwable? = null

  /** Se vero, `delete` non fa niente (il Keystore dice di aver cancellato ma la chiave resta). */
  var deleteDoesNothing = false

  /** Se valorizzato, `create` restituisce questa chiave invece di una nuova (per simulare chiavi che non cifrano). */
  var keyFactory: (() -> SecretKey)? = null

  override fun find(alias: String): SecretKey? {
    findFailure?.let { throw it }
    return keys[alias]
  }

  override fun create(alias: String): GeneratedKey {
    events += "create"
    createFailure?.let { throw it }
    val key = keyFactory?.invoke() ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    keys[alias] = key
    return GeneratedKey(key, level)
  }

  override fun delete(alias: String) {
    events += "delete-key"
    deleteFailure?.let { throw it }
    if (!deleteDoesNothing) keys.remove(alias)
  }

  override fun protectionLevel(key: SecretKey): KeyProtectionLevel = level

  /** Simula "ripristino del telefono": le chiavi non ci sono piu', i dati cifrati si. */
  fun loseAllKeys() {
    keys.clear()
  }
}
