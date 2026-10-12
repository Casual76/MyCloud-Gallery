package dev.pampa.mycloudgallery.core.family.invite

import dev.pampa.mycloudgallery.core.family.contract.RegistryService
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * L'attesa dell'approvazione del dispositivo nuovo (§2.2, `AwaitingApproval`): il registro si rilegge ogni
 * [interval] (10 secondi) **solo finche' la schermata e' aperta**, cioe' finche' qualcuno raccoglie il flusso
 * (nessun lavoro in background: la rete si interrompe e le notifiche all'admin hanno la loro finestra, E-R15).
 *
 * Ogni giro e' un `RegistryService.refresh(INTERACTIVE)` seguito dallo stato che ne esce. Il flusso continua mentre
 * il dispositivo e' in attesa ([TrustStatus.Pending]) o il registro non si e' potuto rileggere ([TrustStatus.Stale]: NAS
 * momentaneamente irraggiungibile) e finisce da solo all'esito: approvato, revocato, perso, dismesso. L'ultimo
 * valore emesso e' quello finale. Se l'admin dice che la finestra di 15 minuti e' scaduta, il dispositivo rifa' la
 * richiesta con una chiave nuova ([DefaultJoinFlow.start] di nuovo, finche' e' in attesa).
 */
class ApprovalWatcher(
  private val registry: RegistryService,
  private val interval: Duration = DEFAULT_INTERVAL,
) {

  fun watch(): Flow<TrustStatus> = flow {
    while (true) {
      registry.refresh(Purpose.INTERACTIVE)
      val status = registry.status.value
      emit(status)
      if (status !is TrustStatus.Pending && status !is TrustStatus.Stale) return@flow
      delay(interval)
    }
  }

  companion object {
    val DEFAULT_INTERVAL: Duration = 10.seconds
  }
}
