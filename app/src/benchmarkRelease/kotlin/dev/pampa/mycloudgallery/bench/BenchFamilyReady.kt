package dev.pampa.mycloudgallery.bench

import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReport
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I fake della variante di misura (§2.6: "lega dei fake di famiglia 'pronta', senza rete"). Sono i due contratti che
// separano la Libreria dal resto dell'app: la famiglia (senza, la prima schermata e' l'onboarding della Fase 2, che
// chiede un NAS) e la lettura del telefono (con quella vera, la scansione del MediaStore riconcilierebbe le 100 mila
// righe sintetiche con i pochi media veri e le segnerebbe assenti).
//
// Li lega `BenchMeasurementHooks` (BenchMeasurementHooks.kt) con un modulo Hilt che sta SOLO in questa variante: `:app`
// riceve le `MeasurementHooks` come `Optional` (vuoto altrove) e preferisce questi ai veri quando ci sono, vedi
// docs/bench/README.md. Niente `ServiceLoader` ne' `META-INF/services` (revisione di sicurezza, R6).

/** La famiglia e' fondata e il dispositivo approvato: la Libreria si raggiunge subito, senza NAS e senza rete. */
class BenchFamilySetupRepository : FamilySetupRepository {
  override val state: StateFlow<SetupState> = MutableStateFlow(SetupState.Ready(admin = false))
}

/** La lettura del telefono spenta: il dataset sintetico non va riconciliato con il MediaStore. Ferma, nessuna rete. */
class BenchPhoneLibrarySync : PhoneLibrarySync {
  override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Idle(atMs = 0L))

  override suspend fun syncNow(reason: SyncReason): SyncReport = SyncReport()

  override fun attach(scope: CoroutineScope) = Unit

  override fun detach() = Unit
}
