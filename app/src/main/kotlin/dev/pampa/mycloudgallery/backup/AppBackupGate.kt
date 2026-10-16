package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags

/**
 * Cio' che il cancello del backup guarda (Fase 5A, P7): quattro domande sincrone, risposte da `:app`. Funzioni e non valori, perche'
 * cambiano mentre il processo vive (l'emergenza, un congelamento, una famiglia che si azzera).
 *
 * @property familyReady la famiglia e' pronta e questo dispositivo ha il permesso di scrivere (`SetupState.Ready` e `TrustGuard.writePermit`).
 * @property privateShare l'area privata dichiarata per l'account del dispositivo (`LocalDeviceProfile.privateShare`), null = nessuna.
 * @property writesStopped "Ferma le scritture sul NAS" di Diagnostica (emergenza, C11): puo' solo restringere.
 * @property frozen la share e' congelata per cambiamenti di massa (F4-D10, E-B19): la chiave e' il nome della share.
 * @property readOnly l'account legge l'area ma non ci scrive (E-N9, E-I19): lo dice il NAS (prova della share o rifiuto di una scrittura).
 */
class BackupGateInputs(
  val flags: FeatureFlags,
  val familyReady: () -> Boolean,
  val privateShare: () -> String?,
  val writesStopped: () -> Boolean,
  val frozen: (String) -> Boolean,
  val readOnly: (String) -> Boolean,
)

/**
 * **Il cancello del backup** (§1.4, D12, D32): dice se il backup puo' scrivere nella share adesso, nell'ordine in cui le ragioni pesano.
 *
 * 1. famiglia non pronta o dispositivo senza permesso di scrittura: [BackupGateState.NOT_READY];
 * 2. nessuna area privata: [BackupGateState.NO_PRIVATE_AREA];
 * 3. **D12**: la share non e' di prova ([FeatureFlags.backupMayWriteTo], vero in release e non spegnibile da `forBuild`):
 *    [BackupGateState.TEST_ONLY], "Backup in prova". **E' la regola piu' importante di questo cancello**: sulle share vere non si
 *    scrive niente, anche con la release, finche' l'utente non chiude la 5B (risposta Q3 (b));
 * 4. emergenza: [BackupGateState.WRITES_STOPPED];
 * 5. share congelata: [BackupGateState.FROZEN];
 * 6. area in sola lettura: [BackupGateState.READ_ONLY_AREA];
 * 7. altrimenti [BackupGateState.OPEN].
 *
 * Il motore lo controlla prima di ogni elemento (D32: "si ferma al passo successivo"), le verifiche in sola lettura lo guardano per
 * sapere se il backup c'e' (si fermano con 1, 2 e 3, **non** con l'emergenza, il congelamento o la sola lettura: leggere non puo' far
 * male e durante un incidente e' proprio cio' che serve). Nessuna eccezione, nessuna attesa: e' una funzione pura delle sue domande.
 */
class AppBackupGate(private val inputs: BackupGateInputs) : BackupGate {

  override fun stateOf(share: String): BackupGateState = when {
    !inputs.familyReady() -> BackupGateState.NOT_READY
    inputs.privateShare() == null -> BackupGateState.NO_PRIVATE_AREA
    !inputs.flags.backupMayWriteTo(share) -> BackupGateState.TEST_ONLY
    inputs.writesStopped() -> BackupGateState.WRITES_STOPPED
    inputs.frozen(share) -> BackupGateState.FROZEN
    inputs.readOnly(share) -> BackupGateState.READ_ONLY_AREA
    else -> BackupGateState.OPEN
  }
}
