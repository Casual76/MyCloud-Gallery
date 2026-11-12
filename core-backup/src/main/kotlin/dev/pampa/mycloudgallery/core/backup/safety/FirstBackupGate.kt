package dev.pampa.mycloudgallery.core.backup.safety

import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState

/**
 * PU-2 della revisione del passo unico (12/11/2026): il cancello che `:app` da' al guardiano delle copie private ([BackupPrivateCopyGuardian]).
 * Finche' il primo backup non e' stato scelto ([chosen] falso: la cartella del dispositivo non c'e' ancora, la pagina dice "Primo backup pronto")
 * risponde [BackupGateState.NOT_READY]; dopo vale [gate] com'e', con le sue ragioni nel loro ordine.
 *
 * Il guardiano guarda il cancello all'inizio del giro, prima di ogni candidato e prima di ogni passo di ogni elemento: prima di "Inizia ora" o
 * "Stanotte" nessuna copia, nessun temporaneo, nessuna pubblicazione, nemmeno per le richieste dopo una condivisione (D28, restano nel registro
 * e partono dopo la scelta) o per "Libera spazio". Le verifiche, che leggono soltanto, hanno il cancello di sempre.
 */
class FirstBackupGate(private val gate: BackupGate, private val chosen: () -> Boolean) : BackupGate {
  override fun stateOf(share: String): BackupGateState = if (!chosen()) BackupGateState.NOT_READY else gate.stateOf(share)
}
