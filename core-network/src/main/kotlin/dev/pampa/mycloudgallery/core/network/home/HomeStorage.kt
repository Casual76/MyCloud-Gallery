package dev.pampa.mycloudgallery.core.network.home

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.pampa.mycloudgallery.core.common.files.QuietWrites
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.HomeCapture
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.env.NetworkPathSource
import dev.pampa.mycloudgallery.core.network.link.IpAddr
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * L'impronta di casa, in un DataStore dentro `noBackupFilesDir`: nessun backup e nessun trasferimento da
 * dispositivo a dispositivo la copiano (e' una dato di quella casa e di quell'installazione).
 *
 * Cio' che si salva e si rilegge e' **validato**: si tengono solo prefissi ULA validi (al massimo quattro), un
 * gateway che sia un IPv4 letterale e un dominio non vuoto. Senza nessun ULA valido non c'e' impronta ([fingerprint]
 * e' null): salvarne una vuota equivale a [clear]. Un file danneggiato si ignora e riparte vuoto.
 *
 * Il valore in memoria ([fingerprint]) segue subito [save] e [clear]: chi salva e poi legge non vede il vecchio.
 */
class DataStoreHomeFingerprintStore(
  private val dataStore: DataStore<Preferences>,
  scope: CoroutineScope,
) : HomeFingerprintStore {

  private val state = MutableStateFlow<HomeFingerprint?>(null)

  override val fingerprint: StateFlow<HomeFingerprint?> = state

  init {
    scope.launch {
      dataStore.data.catch { emit(emptyPreferences()) }.map(::decode).collect { state.value = it }
    }
  }

  override suspend fun save(fingerprint: HomeFingerprint) {
    val clean = sanitize(fingerprint)
    if (clean == null) {
      clear()
      return
    }
    // OB-4 (review generale): con la memoria piena la scrittura non lancia piu' (`QuietWrites`). L'impronta resta valida in memoria per questo processo e si riscrive alla prossima cattura.
    QuietWrites.attempt("impronta della rete di casa") {
      dataStore.edit { prefs ->
        prefs[ULA] = clean.ulaPrefixes
        clean.gatewayV4?.let { prefs[GATEWAY] = it } ?: prefs.remove(GATEWAY)
        clean.dnsDomain?.let { prefs[DOMAIN] = it } ?: prefs.remove(DOMAIN)
        prefs[CAPTURED_AT] = clean.capturedAt
      }
    }
    state.value = clean
  }

  override suspend fun clear() {
    QuietWrites.attempt("impronta della rete di casa") { dataStore.edit { it.clear() } }
    state.value = null
  }

  private fun decode(prefs: Preferences): HomeFingerprint? {
    val ula = prefs[ULA] ?: return null
    return sanitize(
      HomeFingerprint(
        ulaPrefixes = ula,
        gatewayV4 = prefs[GATEWAY],
        dnsDomain = prefs[DOMAIN],
        capturedAt = prefs[CAPTURED_AT] ?: 0L,
      ),
    )
  }

  /** Null se non resta nessun ULA valido. */
  private fun sanitize(raw: HomeFingerprint): HomeFingerprint? {
    val ula = HomePrefixes.ula(raw).map { it.toText() }.sorted().take(HomeCandidates.MAX_ULA).toSet()
    if (ula.isEmpty()) return null
    val gateway = raw.gatewayV4?.let(IpAddr::parse)?.takeIf { it.isV4 }?.toText()
    val domain = raw.dnsDomain?.let(HomeMatcher::normalizeDomain)?.takeIf { it.isNotEmpty() && it.length <= MAX_DOMAIN }
    return HomeFingerprint(ula, gateway, domain, raw.capturedAt)
  }

  companion object {
    private val ULA = stringSetPreferencesKey("home_ula")
    private val GATEWAY = stringPreferencesKey("home_gateway_v4")
    private val DOMAIN = stringPreferencesKey("home_dns_domain")
    private val CAPTURED_AT = longPreferencesKey("home_captured_at")
    private const val MAX_DOMAIN = 253

    /** Il file dell'app: `noBackupFilesDir/datastore/network_home.preferences_pb`. */
    fun create(context: Context, scope: CoroutineScope): DataStoreHomeFingerprintStore =
      DataStoreHomeFingerprintStore(
        dataStore = PreferenceDataStoreFactory.create(
          corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
          scope = scope,
          produceFile = { File(context.noBackupFilesDir, "datastore/network_home.preferences_pb") },
        ),
        scope = scope,
      )
  }
}

/**
 * Legge il candidato a "casa" dal Wi-Fi o dall'Ethernet attuale (passo 2 dell'onboarding, ed E-R16 dopo un reset
 * del router). Le reti sono quelle gia' osservate: la lettura non fa traffico. Funziona anche con la VPN accesa
 * (il Wi-Fi sottostante resta visibile), ma [dev.pampa.mycloudgallery.core.network.contract.NasRouteGate.onboardingRoute]
 * non da' un percorso finche' la VPN e' su.
 */
class ObservedHomeCapture(private val paths: NetworkPathSource) : HomeCapture {

  override suspend fun candidateFromCurrentWifi(): HomeCandidate? {
    val observation = paths.observationNow()
    val lan = observation.lans
      .filter { !it.captivePortal }
      .maxWithOrNull(compareBy({ it.networkId == observation.defaultNetworkId }, { it.validated }))
      ?: return null
    return HomeCandidates.from(lan)
  }
}
