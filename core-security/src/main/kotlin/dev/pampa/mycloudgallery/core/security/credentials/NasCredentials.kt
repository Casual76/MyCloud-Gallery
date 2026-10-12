package dev.pampa.mycloudgallery.core.security.credentials

import dev.pampa.mycloudgallery.core.common.security.Redacted

/**
 * Utente e password di un account del NAS (mai l'account admin: docs/sicurezza.md §8).
 *
 * La password sta in un `CharArray` dentro un [Redacted]: non finisce in `toString()`, nei log o in un
 * messaggio d'errore, e [close] la azzera appena non serve piu' (le `String` non si possono azzerare,
 * per questo non si usano). Chi riceve un'istanza da [CredentialVault.load] la chiude dopo l'uso:
 *
 * ```
 * (vault.load(slot) as? LoadResult.Found)?.credentials?.use { c -> smb.login(c.username, c.password.value) }
 * ```
 *
 * Limite noto (docs/sicurezza.md L4): la libreria SMB tiene comunque una copia della password in
 * memoria per la durata della sessione.
 */
class NasCredentials(val username: String, password: CharArray) : AutoCloseable {

  /** La password. `value` e' l'array vero (non una copia): non tenerlo oltre il [close]. */
  val password: Redacted<CharArray> = Redacted(password.copyOf())

  init {
    // Il chiamante puo' azzerare subito il proprio array: noi ne teniamo una copia.
    try {
      CredentialCodec.requireValid(username, this.password.value)
    } catch (e: IllegalArgumentException) {
      close()
      throw e
    }
  }

  override fun close() {
    password.value.fill('\u0000')
  }

  override fun toString(): String = "NasCredentials(${Redacted.PLACEHOLDER})"

  override fun equals(other: Any?): Boolean =
    other is NasCredentials && other.username == username && other.password.value.contentEquals(password.value)

  override fun hashCode(): Int = 31 * username.hashCode() + password.value.contentHashCode()
}
