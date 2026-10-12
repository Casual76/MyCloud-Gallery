package dev.pampa.mycloudgallery.core.security

/**
 * Come si blocca l'app (piano 1.5). Segnaposto della Fase 1.1.
 *
 * Il blocco, quando c'e', usa **sempre la credenziale di sistema** (impronta, volto, PIN o sequenza
 * del telefono): mai un PIN proprio dell'app, che sarebbe un segreto in piu' da custodire e da
 * dimenticare. Per questo l'elenco ha due voci e non ne avra' una terza.
 */
enum class AppLockMode {
  /** Nessun blocco (default). */
  OFF,

  /** Credenziale di sistema (biometria o PIN del telefono). */
  SYSTEM_CREDENTIAL,
}
