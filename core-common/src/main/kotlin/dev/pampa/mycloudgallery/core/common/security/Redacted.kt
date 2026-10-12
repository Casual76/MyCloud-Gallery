package dev.pampa.mycloudgallery.core.common.security

/**
 * Avvolge un valore che non deve mai finire in un log, in un messaggio d'errore o nel `toString()`
 * di una classe che lo contiene: password, token, chiavi, percorsi personali.
 *
 * Il valore si legge solo in modo esplicito con [value]; tutto il resto (stringhe di debug, log,
 * `data class` che lo tengono in un campo) vede soltanto [PLACEHOLDER].
 */
class Redacted<T>(val value: T) {

  override fun toString(): String = PLACEHOLDER

  override fun equals(other: Any?): Boolean = other is Redacted<*> && other.value == value

  override fun hashCode(): Int = value.hashCode()

  companion object {
    const val PLACEHOLDER: String = "[nascosto]"
  }
}
