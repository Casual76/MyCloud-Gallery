package dev.pampa.mycloudgallery.core.network.contract

import kotlinx.coroutines.flow.StateFlow

/**
 * L'impronta della rete di casa, raccolta una volta con il consenso dell'utente (passo 2 dell'onboarding) e
 * firmata nella configurazione di famiglia.
 *
 * **Solo l'ULA e' un indizio forte.** Il prefisso IPv6 locale (/48) e' casuale per ogni FRITZ!Box: il
 * FRITZ!Box di un amico con la stessa sottorete IPv4 e lo stesso IP del NAS ha un ULA diverso (E-S10).
 * Gateway e dominio (`fritz.box`) sono uguali in mezza Italia: indizi deboli, mai sufficienti da soli.
 *
 * @property ulaPrefixes i prefissi ULA /48 visti sul Wi-Fi di casa, in forma testuale (`fd12:3456:789a::/48`).
 * @property gatewayV4 il gateway IPv4 (indizio debole).
 * @property dnsDomain il dominio DNS della rete (indizio debole).
 * @property capturedAt quando e' stata raccolta (ora del dispositivo, ms).
 */
data class HomeFingerprint(
  val ulaPrefixes: Set<String>,
  val gatewayV4: String?,
  val dnsDomain: String?,
  val capturedAt: Long,
)

/**
 * Cio' che il Wi-Fi attuale offre come candidato a "casa" (passo 2, o E-R16 dopo un reset del router). Non
 * e' ancora un'impronta: lo diventa solo dopo il "Si', e' casa" dell'utente e la conferma del GUID del NAS.
 *
 * @property networkId la `Network` di Android da cui e' stato letto: il percorso dell'onboarding si lega a
 *   questa ([NasRouteGate.onboardingRoute]).
 */
data class HomeCandidate(
  val networkId: String,
  val ulaPrefixes: Set<String>,
  val gatewayV4: String?,
  val dnsDomain: String?,
  val ipv4Subnet: String?,
) {
  /** Senza ULA non si puo' confermare "casa" (va spiegato all'utente, §2.1 passo 2). */
  val canBeConfirmed: Boolean get() = ulaPrefixes.isNotEmpty()
}

/** Un indizio debole che coincide. */
enum class HomeClue { GATEWAY_V4, DNS_DOMAIN, IPV4_SUBNET }

/** Il confronto fra la rete attuale e l'impronta di casa. Solo [Match] vale come casa. */
sealed interface HomeMatch {
  /** Un prefisso ULA coincide: e' casa. */
  data object Match : HomeMatch

  /** Coincidono solo indizi deboli: NON e' casa (potrebbe essere la rete di un amico). */
  data class Weak(val clues: Set<HomeClue>) : HomeMatch

  /** Niente coincide. */
  data object NoMatch : HomeMatch

  /** Non c'e' un'impronta, o non c'e' un Wi-Fi da confrontare. */
  data object Unknown : HomeMatch
}

/** Dove si conserva l'impronta di casa (DataStore in `noBackupFilesDir`: nessun backup la copia). */
interface HomeFingerprintStore {
  val fingerprint: StateFlow<HomeFingerprint?>

  suspend fun save(fingerprint: HomeFingerprint)

  suspend fun clear()
}

/** Legge dal Wi-Fi attuale un candidato a "casa" (solo onboarding e nuova raccolta, E-R16). */
interface HomeCapture {
  /** Null se non c'e' un Wi-Fi (o c'e' solo una VPN). */
  suspend fun candidateFromCurrentWifi(): HomeCandidate?
}
