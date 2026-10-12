package dev.pampa.mycloudgallery.core.security.credentials

/**
 * Dove vive davvero la chiave che cifra le credenziali (docs/sicurezza.md C14).
 *
 * `STRONGBOX` e' il chip dedicato (sull'S25 c'e'), `TRUSTED_ENVIRONMENT` e' l'area protetta del
 * processore (TEE): in entrambi i casi la chiave non esce mai dall'hardware e non e' leggibile
 * nemmeno con root. `SOFTWARE` vuol dire che il telefono non ha un Keystore hardware (succede negli
 * emulatori): il vault lo rifiuta, salvo che l'app lo permetta di proposito (solo build di debug).
 */
enum class KeyProtectionLevel {
  STRONGBOX,
  TRUSTED_ENVIRONMENT,
  SOFTWARE,

  /** Il sistema non ha saputo dirlo (Android prima della 12 senza `isInsideSecureHardware`, o un valore nuovo). */
  UNKNOWN;

  val isHardwareBacked: Boolean get() = this == STRONGBOX || this == TRUSTED_ENVIRONMENT
}
