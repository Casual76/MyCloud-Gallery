package dev.pampa.mycloudgallery.core.nas.breaker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Cio' che l'interruttore ricorda per un account del NAS (§3.3).
 *
 * @property generation la generazione della credenziale: sale a ogni password nuova salvata dall'utente.
 * @property openReason non null = interruttore aperto.
 * @property openedAt ora del dispositivo (ms) dell'apertura, per la Diagnostica.
 * @property unconfirmed autenticazioni partite e mai concluse (processo morto a meta' tentativo): oltre
 *   [PersistentAuthCircuitBreaker.MAX_UNCONFIRMED] l'interruttore si apre, cosi' un ciclo di riavvii non
 *   diventa una raffica di tentativi.
 */
data class BreakerRecord(
  val generation: Long = FIRST_GENERATION,
  val openReason: AuthBlockReason? = null,
  val openedAt: Long = 0L,
  val unconfirmed: Int = 0,
) {
  fun toState(): BreakerState =
    openReason?.let { BreakerState.Open(it, openedAt, generation) } ?: BreakerState.Closed(generation)

  companion object {
    const val FIRST_GENERATION: Long = 1L
  }
}

/** Dove l'interruttore e' persistente. Ogni [update] e' atomico (lettura-modifica-scrittura). */
interface BreakerStore {
  suspend fun read(key: String): BreakerRecord

  suspend fun update(key: String, transform: (BreakerRecord) -> BreakerRecord): BreakerRecord

  fun observe(key: String): Flow<BreakerRecord>
}

/**
 * La chiave di un interruttore: `(nasId, utente)`. Nel file ci finisce solo un'impronta, mai il nome
 * dell'utente o il GUID del NAS (§4.2). La generazione della credenziale sta nel valore.
 */
object BreakerKeys {
  private const val KEY_HEX_CHARS = 32

  fun of(nasId: String, user: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest("mycloudgallery/nas-breaker\u0000${nasId.lowercase()}\u0000${user.lowercase()}".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }.take(KEY_HEX_CHARS)
  }
}

/**
 * L'interruttore su DataStore, in `noBackupFilesDir` (§3.3): nessun backup lo copia, e sopravvive al riavvio
 * del processo.
 */
class DataStoreBreakerStore(private val dataStore: DataStore<Preferences>) : BreakerStore {

  override suspend fun read(key: String): BreakerRecord = dataStore.data.first().record(key)

  override suspend fun update(key: String, transform: (BreakerRecord) -> BreakerRecord): BreakerRecord {
    var result: BreakerRecord? = null
    dataStore.edit { prefs ->
      val next = transform(prefs.record(key))
      prefs[generationKey(key)] = next.generation
      prefs[openedAtKey(key)] = next.openedAt
      prefs[unconfirmedKey(key)] = next.unconfirmed
      val reason = next.openReason
      if (reason == null) prefs.remove(reasonKey(key)) else prefs[reasonKey(key)] = reason.name
      result = next
    }
    return checkNotNull(result)
  }

  override fun observe(key: String): Flow<BreakerRecord> = dataStore.data.map { it.record(key) }.distinctUntilChanged()

  private fun Preferences.record(key: String): BreakerRecord {
    val reasonName = this[reasonKey(key)]
    // Un motivo sconosciuto (file scritto da una versione futura) vale come aperto: si sbaglia dal lato sicuro.
    val reason = reasonName?.let { name -> AuthBlockReason.entries.firstOrNull { it.name == name } ?: AuthBlockReason.AUTH_FAILED }
    return BreakerRecord(
      generation = this[generationKey(key)] ?: BreakerRecord.FIRST_GENERATION,
      openReason = reason,
      openedAt = this[openedAtKey(key)] ?: 0L,
      unconfirmed = this[unconfirmedKey(key)] ?: 0,
    )
  }

  private fun generationKey(key: String) = longPreferencesKey("$key.gen")

  private fun reasonKey(key: String) = stringPreferencesKey("$key.reason")

  private fun openedAtKey(key: String) = longPreferencesKey("$key.at")

  private fun unconfirmedKey(key: String) = intPreferencesKey("$key.unconfirmed")

  companion object {
    const val FILE_NAME: String = "nas_auth_breaker.preferences_pb"

    /** Il file di produzione: `noBackupFilesDir/datastore/nas_auth_breaker.preferences_pb`. Uno per processo. */
    fun create(context: Context, scope: CoroutineScope): DataStoreBreakerStore =
      DataStoreBreakerStore(
        PreferenceDataStoreFactory.create(scope = scope) {
          File(File(context.noBackupFilesDir, "datastore"), FILE_NAME)
        },
      )
  }
}
