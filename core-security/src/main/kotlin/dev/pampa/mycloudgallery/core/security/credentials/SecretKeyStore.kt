package dev.pampa.mycloudgallery.core.security.credentials

import javax.crypto.SecretKey

/**
 * Il Keystore visto dal resto del modulo: l'unico punto che sa che esiste Android. Sta dietro
 * un'interfaccia perche' i test unitari usano chiavi AES normali in memoria (e simulano chiave
 * sparita, invalidata o guasto passeggero), mentre la prova vera sul chip si fa sul telefono
 * (test instrumented).
 */
internal interface SecretKeyStore {

  /** La chiave, o `null` se non esiste. Puo' lanciare (es. `UnrecoverableKeyException`). */
  fun find(alias: String): SecretKey?

  /** Crea una chiave nuova con questo alias (StrongBox se c'e', altrimenti TEE). */
  fun create(alias: String): GeneratedKey

  /** Distrugge la chiave. Idempotente: se non esiste non e' un errore. */
  fun delete(alias: String)

  fun protectionLevel(key: SecretKey): KeyProtectionLevel
}

internal class GeneratedKey(val key: SecretKey, val level: KeyProtectionLevel)
