package dev.pampa.mycloudgallery.core.family.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lo stato del magazzino dell'avanzamento. */
sealed interface StoreState {

  /** Non si e' ancora letto niente: la schermata non mostra nulla (meglio di un lampo di benvenuto). */
  data object Loading : StoreState

  /** L'archivio non si legge adesso (telefono appena riavviato e non sbloccato, BFU): si riprova da solo. */
  data object Unavailable : StoreState

  /** Letto: [snapshot] vale [OnboardingSnapshot.EMPTY] se non c'e' niente (o non si capisce). */
  data class Ready(val snapshot: OnboardingSnapshot) : StoreState
}

/**
 * Dove si salva l'avanzamento dell'onboarding (E-O9). Una sola copia per dispositivo (o profilo del tablet):
 * l'onboarding, l'ingresso con invito e `FamilySetupRepository` la condividono.
 */
interface OnboardingStateStore {

  val state: StateFlow<StoreState>

  /**
   * Legge, modifica e salva in un colpo solo. Restituisce il nuovo snapshot, o null se l'archivio non e'
   * disponibile (e allora non e' stato scritto niente).
   */
  suspend fun update(transform: (OnboardingSnapshot) -> OnboardingSnapshot): OnboardingSnapshot?

  /** Dimentica tutto: si riparte dal benvenuto. */
  suspend fun clear()
}

/**
 * [OnboardingStateStore] su DataStore Preferences (in `noBackupFilesDir`: fuori da backup e trasferimenti, quindi
 * un telefono nuovo non eredita un avanzamento che non ha le chiavi per portare avanti).
 *
 * **E-O8, trasferimento da dispositivo a dispositivo** (Smart Switch e simili, che su alcuni telefoni copiano
 * i dati anche con `allowBackup=false`): se l'avanzamento dice "finito" ma la chiave del dispositivo non c'e'
 * nel Keystore ([deviceKeyPresent] falso), quei dati arrivano da un altro telefono e non valgono nulla: nessuna
 * chiave sopravvive a un trasferimento (C16), quindi si riparte dal benvenuto e il dispositivo chiede una
 * nuova approvazione. Se [deviceKeyPresent] non sa rispondere (lancia), si presume che la chiave ci sia: nel
 * dubbio non si butta niente.
 *
 * Un archivio illeggibile (BFU) da' [StoreState.Unavailable] e si riprova con attese crescenti; uno
 * danneggiato vale "mai iniziato".
 */
class DataStoreOnboardingStateStore(
  private val dataStore: DataStore<Preferences>,
  private val scope: CoroutineScope,
  private val deviceKeyPresent: () -> Boolean = { true },
  private val retryBaseMillis: Long = 1_000L,
) : OnboardingStateStore {

  private val mutable = MutableStateFlow<StoreState>(StoreState.Loading)
  override val state: StateFlow<StoreState> = mutable
  private val writes = Mutex()

  init {
    scope.launch { observe() }
  }

  private suspend fun observe() {
    var attempt = 0
    while (true) {
      try {
        dataStore.data.collect { prefs ->
          attempt = 0
          mutable.value = StoreState.Ready(effective(prefs[KEY]))
        }
        return
      } catch (e: CancellationException) {
        throw e
      } catch (e: IOException) {
        GalleryLog.w(TAG) { "avanzamento non leggibile (${e.javaClass.simpleName})" }
        mutable.value = StoreState.Unavailable
        delay((retryBaseMillis shl attempt.coerceAtMost(MAX_RETRY_SHIFT)).coerceAtMost(MAX_RETRY_MILLIS))
        attempt++
      }
    }
  }

  private fun effective(raw: String?): OnboardingSnapshot {
    val snapshot = raw?.let(OnboardingSnapshotCodec::decode) ?: OnboardingSnapshot.EMPTY
    if (!snapshot.finished || keyPresent()) return snapshot
    GalleryLog.w(TAG) { "avanzamento finito ma senza la chiave del dispositivo: ignorato (E-O8)" }
    scope.launch { runCatching { clear() } }
    return OnboardingSnapshot.EMPTY
  }

  private fun keyPresent(): Boolean =
    try {
      deviceKeyPresent()
    } catch (e: Exception) {
      true
    }

  override suspend fun update(transform: (OnboardingSnapshot) -> OnboardingSnapshot): OnboardingSnapshot? =
    writes.withLock {
      try {
        var result: OnboardingSnapshot? = null
        dataStore.edit { prefs ->
          val updated = transform(prefs[KEY]?.let(OnboardingSnapshotCodec::decode) ?: OnboardingSnapshot.EMPTY)
          prefs[KEY] = OnboardingSnapshotCodec.encode(updated)
          result = updated
        }
        result
      } catch (e: IOException) {
        GalleryLog.w(TAG) { "avanzamento non salvato (${e.javaClass.simpleName})" }
        null
      }
    }

  override suspend fun clear() {
    writes.withLock {
      try {
        dataStore.edit { it.remove(KEY) }
      } catch (e: IOException) {
        GalleryLog.w(TAG) { "avanzamento non cancellato (${e.javaClass.simpleName})" }
      }
    }
  }

  private companion object {
    const val TAG = "OnboardingStore"
    const val MAX_RETRY_SHIFT = 5
    const val MAX_RETRY_MILLIS = 30_000L
    val KEY = stringPreferencesKey("onboarding.snapshot")
  }
}
