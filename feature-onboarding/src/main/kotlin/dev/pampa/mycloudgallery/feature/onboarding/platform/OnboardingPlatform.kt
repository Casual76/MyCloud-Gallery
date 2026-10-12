package dev.pampa.mycloudgallery.feature.onboarding.platform

import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus

/** I permessi che il passo 9 chiede (la fotocamera la chiede lo scanner dei QR, solo quando serve). */
internal enum class PermissionKind { NOTIFICATIONS, MEDIA, MEDIA_LOCATION, LOCAL_NETWORK }

/** Lo stato di un permesso, come lo vede Android adesso (si rilegge a ogni ritorno: E-O4, E-P1). */
internal enum class PermissionState {
  GRANTED,

  /** "Foto selezionate" (E-P1): l'app vede solo cio' che l'utente sceglie. */
  PARTIAL,
  DENIED,

  /** Non esiste su questa versione di Android (le notifiche prima della 13) o non serve (rete locale prima del target 37). */
  NOT_NEEDED,
}

/** Cio' che il passo 9 mostra: lo stato di ogni permesso, quali sono gia' stati chiesti, e il blocco schermo (E-S3). */
internal data class PermissionsSnapshot(
  val states: Map<PermissionKind, PermissionState>,
  /** Quelli che l'utente ha gia' visto chiedere: se sono ancora negati, il secondo tocco apre le impostazioni. */
  val requested: Set<PermissionKind> = emptySet(),
  val screenLock: ScreenLockStatus = ScreenLockStatus.SECURE,
) {
  fun state(kind: PermissionKind): PermissionState = states[kind] ?: PermissionState.NOT_NEEDED

  /** L'app funziona in modalita' ridotta (E-O4): manca l'accesso completo ai media o le notifiche. */
  val reducedMode: Boolean
    get() = state(PermissionKind.MEDIA) != PermissionState.GRANTED && state(PermissionKind.MEDIA) != PermissionState.NOT_NEEDED ||
      state(PermissionKind.NOTIFICATIONS) == PermissionState.DENIED

  companion object {
    /** Tutto concesso e blocco schermo presente: il caso piu' tranquillo. */
    val ALL_GRANTED = PermissionsSnapshot(
      states = mapOf(
        PermissionKind.NOTIFICATIONS to PermissionState.GRANTED,
        PermissionKind.MEDIA to PermissionState.GRANTED,
        PermissionKind.MEDIA_LOCATION to PermissionState.GRANTED,
        PermissionKind.LOCAL_NETWORK to PermissionState.NOT_NEEDED,
      ),
    )
  }
}

/**
 * Tutto cio' che le schermate chiedono ad Android e che non e' stato dell'app: i permessi, le impostazioni di
 * sistema da aprire, WireGuard, il tipo di dispositivo. Un'interfaccia sola, cosi' le schermate si provano con un
 * finto (nessun dialogo di sistema in un test) e l'implementazione vera sta in [AndroidOnboardingPlatform].
 *
 * Ogni "apri" restituisce se e' riuscito ad aprire qualcosa: un'impostazione che su quel telefono non esiste non
 * deve far cadere la schermata, che ripiega sulle impostazioni dell'app.
 */
internal interface OnboardingPlatform {
  /** Lo stato di adesso. Legge uno stato osservabile: chi la chiama durante la composizione si aggiorna quando Android cambia qualcosa. */
  fun permissions(): PermissionsSnapshot

  /** Mostra il dialogo di sistema del permesso (o, per quelli senza dialogo, le impostazioni dell'app). */
  fun requestPermission(kind: PermissionKind)

  fun openAppSettings(): Boolean

  fun openSecuritySettings(): Boolean

  fun openWifiSettings(): Boolean

  /** Apre un'attivita' di un'altra app (WireGuard, dall'intent di `VpnController`); falso se non si apre. */
  fun open(intent: android.content.Intent): Boolean

  /** "Batteria: Illimitata" (esclusione dall'ottimizzazione). */
  fun openBatteryExemption(): Boolean

  /** "App mai in sospensione" (One UI): dove Samsung tiene l'elenco. */
  fun openBackgroundLimits(): Boolean

  /** "Pausa attivita' se inutilizzata". */
  fun openUnusedAppSettings(): Boolean

  /** Un tablet (larghezza minima da 600 dp): cambia il consiglio sui ruoli (E-A4). */
  fun isTablet(): Boolean
}
