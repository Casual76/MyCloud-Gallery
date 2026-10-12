package dev.pampa.mycloudgallery.core.security.credentials

/** Cosa sa l'app sulle credenziali salvate (E-S9). Non decifra niente: guarda solo cosa c'e'. */
enum class CredentialStatus {
  /** Nessuna credenziale salvata (mai inserita, o cancellata con [CredentialVault.wipeAll]). */
  EMPTY,

  /** C'e' almeno un blocco cifrato. Che sia ancora decifrabile lo dice [CredentialVault.verify]. */
  READY,

  /**
   * "Serve di nuovo la password": la chiave e' sparita o invalidata, oppure l'archivio era danneggiato.
   * Le credenziali vecchie sono gia' state buttate. Si esce da qui salvandone di nuove.
   */
  NEEDS_PASSWORD,

  /** L'archivio non e' leggibile adesso (tipicamente: telefono appena riavviato, non ancora sbloccato). */
  UNAVAILABLE,
}

/** Esito di [CredentialVault.load]. */
sealed interface LoadResult {

  /** Le credenziali. Chi le riceve le chiude con `use { }` o `close()`. */
  class Found(val credentials: NasCredentials) : LoadResult {
    override fun toString(): String = "Found(credenziali nascoste)"
  }

  /** Non ce ne sono. */
  data object Absent : LoadResult

  /** C'erano ma non sono piu' utilizzabili: chiedere di nuovo la password (E-S9). Gia' ripulite. */
  data object NeedsPassword : LoadResult

  /** Guasto passeggero: riprovare piu' tardi. Non e' stato cancellato niente. */
  data object Unavailable : LoadResult
}

/** Esito di [CredentialVault.save]. */
sealed interface SaveResult {

  /** Salvato; [protection] dice dove vive la chiave (StrongBox o TEE). */
  data class Saved(val protection: KeyProtectionLevel) : SaveResult

  /** Il telefono non ha un Keystore hardware e l'app non ammette chiavi software: non si salva niente. */
  data object HardwareKeyUnavailable : SaveResult

  /** Guasto passeggero: non salvato, riprovare. */
  data object Unavailable : SaveResult
}

/**
 * Esito di [CredentialVault.wipeAll]. L'ordine e' sempre: prima la chiave, poi i dati. Se [keyDestroyed] e'
 * falso (il Keystore non ha risposto) i dati sono stati cancellati lo stesso, ma chi chiama deve
 * riprovare finche' la chiave non risulta distrutta.
 */
data class WipeResult(val keyDestroyed: Boolean, val dataCleared: Boolean) {
  val complete: Boolean get() = keyDestroyed && dataCleared
}
