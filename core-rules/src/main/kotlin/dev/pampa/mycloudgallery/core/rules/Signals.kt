package dev.pampa.mycloudgallery.core.rules

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I segnali che il motore NON ricava da Android ma da altri moduli o fasi. Qui c'e' solo il contratto
// (e un fake per i test e per far girare l'app prima che esistano le implementazioni vere).

/**
 * "Sono sulla rete di casa" (piano 2.1 / docs/android.md 7.3: piu' indizi senza permesso di posizione).
 * Lo implementa la Fase 2. Vero solo se la rete in uso e' riconosciuta come quella di casa.
 */
interface HomeNetworkSignal {
  val isAtHome: StateFlow<Boolean>
}

/** "Il NAS risponde" (piano 2.1). Lo implementa la Fase 2. */
interface NasReachabilitySignal {
  val isReachable: StateFlow<Boolean>
}

/**
 * "La rete in uso e' scadente" (E-R4: VPN su una rete lenta, trasferimenti che si bloccano senza errori,
 * E-R14). Lo misura la Fase 2 con il NAS (latenza, throughput, blocchi): Android non lo dice in modo
 * affidabile su tutte le reti.
 */
interface NetworkQualitySignal {
  val isPoor: StateFlow<Boolean>
}

/** Finche' la Fase 2 non misura niente: la rete si considera buona. */
object NoNetworkQualityInfo : NetworkQualitySignal {
  override val isPoor: StateFlow<Boolean> = MutableStateFlow(false)
}

/** I ruoli di questo dispositivo (Indicizzatore / Preparatore), dalle impostazioni. */
interface DeviceRolesSignal {
  val roles: StateFlow<DeviceRoles>
}

/** I limiti di Android raggiunti (quote dei job e dei servizi in primo piano), da chi esegue i lavori. */
interface AndroidLimitsSignal {
  val limits: StateFlow<AndroidLimits>
}

/** App in primo piano e profilo attivo: lo implementa `:app` (ProcessLifecycleOwner / UserManager). */
interface AppContextSignal {
  val context: StateFlow<AppContext>
}

// --- Fake -------------------------------------------------------------------------------------------
// In produzione servono come implementazione provvisoria (cosi' l'app compila e il motore gira prima
// della Fase 2); nei test servono a pilotare ogni scenario.

class FakeHomeNetworkSignal(initial: Boolean = false) : HomeNetworkSignal {
  override val isAtHome: MutableStateFlow<Boolean> = MutableStateFlow(initial)
}

class FakeNasReachabilitySignal(initial: Boolean = true) : NasReachabilitySignal {
  override val isReachable: MutableStateFlow<Boolean> = MutableStateFlow(initial)
}

class FakeNetworkQualitySignal(initial: Boolean = false) : NetworkQualitySignal {
  override val isPoor: MutableStateFlow<Boolean> = MutableStateFlow(initial)
}

class FakeDeviceRolesSignal(initial: DeviceRoles = DeviceRoles()) : DeviceRolesSignal {
  override val roles: MutableStateFlow<DeviceRoles> = MutableStateFlow(initial)
}

class FakeAndroidLimitsSignal(initial: AndroidLimits = AndroidLimits()) : AndroidLimitsSignal {
  override val limits: MutableStateFlow<AndroidLimits> = MutableStateFlow(initial)
}

class FakeAppContextSignal(initial: AppContext = AppContext()) : AppContextSignal {
  override val context: MutableStateFlow<AppContext> = MutableStateFlow(initial)
}
