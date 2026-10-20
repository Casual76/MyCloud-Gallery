package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.settings.BackupSettingsStorage
import dev.pampa.mycloudgallery.core.nas.contract.ShareProbe
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore

/**
 * Cio' che [BackupGraph] riceve dal grafo dell'app oltre a quello del NAS (`NasInputs`): dove si salvano le impostazioni del backup, le
 * regole (per la protezione delle foto nuove), la prova di una share, il nome del dispositivo per la cartella sul NAS, la recinzione
 * del telefono in debug (D13) e i lavori avviati dall'utente (UIDT). Funzioni e non valori: cambiano con l'onboarding e con un
 * azzeramento.
 *
 * @property fence **D13**: la recinzione del telefono del backup con gli account di prova (`DeveloperTools.backupFence()`). Null in
 *   release: la classe non esiste nel dex.
 * @property deviceName il nome del dispositivo cosi' com'e' (null = non noto): la cartella `Backup/<nome>/` si propone da qui (D7).
 * @property fiveB Fase 5B (P14): i dialoghi e il riscrittore del telefono, il controllo della copia corretta, il conteggio dei riavvii; null =
 *   quelli veri di Android (la galleria li costruisce), i test d'integrazione passano i finti.
 */
class BackupInputs(
  val settingsStorage: BackupSettingsStorage,
  val ruleSettings: RuleSettingsStore,
  val shareProbe: ShareProbe,
  val deviceName: suspend () -> String?,
  val fence: BackupFence?,
  val jobs: dev.pampa.mycloudgallery.work.BackupJobs,
  val fiveB: FiveBInputs? = null,
)
