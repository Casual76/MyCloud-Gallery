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
 * @property account l'account NAS di questo dispositivo, null = non noto (R2: serve a sapere se e' un account di prova).
 * @property writesStopped "Ferma le scritture sul NAS" di Diagnostica (emergenza, C11): puo' solo restringere.
 * @property frozen la share e' congelata per cambiamenti di massa (F4-D10, E-B19): la chiave e' il nome della share.
 * @property readOnly l'account legge l'area ma non ci scrive (E-N9, E-I19): lo dice il NAS (prova della share o rifiuto di una scrittura).
 * @property paused R4: l'utente ha toccato "Ferma" e non ha ancora ripreso (`BackupRunMemory.isPaused`).
 */
class BackupGateInputs(
  val flags: FeatureFlags,
  val familyReady: () -> Boolean,
  val privateShare: () -> String?,
  val account: () -> String?,
  val writesStopped: () -> Boolean,
  val frozen: (String) -> Boolean,
  val readOnly: (String) -> Boolean,
  val paused: () -> Boolean,
)

/**
 * **Il cancello del backup** (§1.4, D12, D32): dice se il backup puo' scrivere nella share adesso, nell'ordine in cui le ragioni pesano.
 *
 * 1. famiglia non pronta o dispositivo senza permesso di scrittura: [BackupGateState.NOT_READY];
 * 2. nessuna area privata, **o la share chiesta non e' (piu') quell'area privata** (R7 della revisione 5A: l'admin ha corretto la
 *    configurazione a motore costruito, o un elemento del diario e' di una share di prima; il confronto non distingue le maiuscole,
 *    come Samba): [BackupGateState.NO_PRIVATE_AREA];
 * 3. **D12**: la share non e' di prova ([FeatureFlags.backupMayWriteTo], vero in release e non spegnibile da `forBuild`):
 *    [BackupGateState.TEST_ONLY], "Backup in prova". **E' la regola piu' importante di questo cancello**: sulle share vere non si
 *    scrive niente, anche con la release, finche' l'utente non chiude la 5B (risposta Q3 (b));
 * 4. **R2**: in release un account di prova ([FeatureFlags.backupMayWriteAs]) non scrive in **nessuna** share, nemmeno di prova: D13, la
 *    recinzione del telefono, c'e' solo in debug, e senza tutta la libreria vera finirebbe nella share di prova. Anche questo e'
 *    [BackupGateState.TEST_ONLY]; in debug (dove D13 recinta) non blocca;
 * 5. emergenza: [BackupGateState.WRITES_STOPPED];
 * 6. share congelata: [BackupGateState.FROZEN];
 * 7. area in sola lettura: [BackupGateState.READ_ONLY_AREA];
 * 8. **R4**: l'utente ha toccato "Ferma" e non ha ripreso: [BackupGateState.PAUSED] (non e' un problema: la pagina dice "In pausa");
 * 9. altrimenti [BackupGateState.OPEN].
 *
 * Il motore lo controlla prima di ogni elemento (D32: "si ferma al passo successivo"), le verifiche in sola lettura lo guardano per
 * sapere se il backup c'e' (si fermano con 1, 2, 3 e 4, **non** con l'emergenza, il congelamento, la sola lettura o la pausa: leggere
 * non puo' far male e durante un incidente e' proprio cio' che serve). Nessuna eccezione, nessuna attesa: e' una funzione pura delle sue
 * domande.
 */
class AppBackupGate(private val inputs: BackupGateInputs) : BackupGate {

  override fun stateOf(share: String): BackupGateState = when {
    !inputs.familyReady() -> BackupGateState.NOT_READY
    inputs.privateShare()?.equals(share, ignoreCase = true) != true -> BackupGateState.NO_PRIVATE_AREA
    !inputs.flags.backupMayWriteTo(share) -> BackupGateState.TEST_ONLY
    !inputs.flags.backupMayWriteAs(inputs.account()) -> BackupGateState.TEST_ONLY
    inputs.writesStopped() -> BackupGateState.WRITES_STOPPED
    inputs.frozen(share) -> BackupGateState.FROZEN
    inputs.readOnly(share) -> BackupGateState.READ_ONLY_AREA
    inputs.paused() -> BackupGateState.PAUSED
    else -> BackupGateState.OPEN
  }
}
