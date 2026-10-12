package dev.pampa.mycloudgallery.core.security.credentials

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.crypto.SecretKey

/**
 * L'archivio delle credenziali del NAS (piano 1.5, docs/sicurezza.md C14, L4, E-S9).
 *
 * - **Cifratura**: AES-256-GCM con una chiave dell'Android Keystore, in StrongBox se il telefono lo ha,
 *   altrimenti nel TEE ([KeyProtectionLevel]). I blocchi cifrati stanno in un DataStore dentro
 *   `noBackupFilesDir` (esclusa da qualunque backup, oltre alle regole di `data_extraction_rules.xml`).
 * - **Uso in background senza sblocco**: la chiave non richiede ne' autenticazione ne' telefono
 *   sbloccato, perche' il backup notturno non puo' chiedere un'impronta. E' una scelta dichiarata, con il
 *   suo limite (L4: un malware con root potrebbe usarla). Una nuova impronta o un nuovo PIN non la toccano.
 * - **Invalidazione (E-S9)**: se la chiave sparisce o non e' piu' utilizzabile, o l'archivio e'
 *   danneggiato, [load] risponde [LoadResult.NeedsPassword] e butta il materiale inutilizzabile: l'app
 *   chiede di nuovo la password. Un guasto *passeggero* (Keystore occupato, archiviazione non ancora
 *   sbloccata dopo il riavvio) invece risponde [LoadResult.Unavailable] e non cancella niente.
 * - **Cancellazione sicura**: [wipeAll] distrugge PRIMA la chiave e POI i dati (cosi' anche una copia
 *   dei blocchi cifrati resta illeggibile); non si puo' interrompere a meta'. Serve alla segnalazione
 *   di smarrimento (2.4).
 * - **Mai nei log**: nessun messaggio contiene utente, password o byte; solo categorie di errore.
 *
 * Un solo vault per processo (DataStore non ammette due istanze sullo stesso file): usa [getInstance].
 */
class CredentialVault internal constructor(
  private val keys: SecretKeyStore,
  private val store: DataStore<Preferences>,
  private val allowSoftwareKey: Boolean = false,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {

  private val mutex = Mutex()

  /**
   * Lo stato delle credenziali, aggiornato a ogni modifica. Guarda solo cosa c'e' nell'archivio: la
   * chiave sparita si scopre al primo [load] o con [verify]. Se l'archivio non si legge emette
   * [CredentialStatus.UNAVAILABLE] e riprova da solo.
   */
  val status: Flow<CredentialStatus> = flow {
    var attempt = 0
    while (true) {
      var failure: IOException? = null
      store.data
        .map { statusOf(it) }
        .catch { if (it is IOException) failure = it else throw it }
        .collect {
          attempt = 0
          emit(it)
        }
      if (failure == null) return@flow
      emit(CredentialStatus.UNAVAILABLE)
      delay((RETRY_BASE_MILLIS shl attempt.coerceAtMost(MAX_RETRY_SHIFT)).coerceAtMost(RETRY_MAX_MILLIS))
      attempt++
    }
  }.distinctUntilChanged()

  /** Salva (o sostituisce) le credenziali della voce [slot]. Crea la chiave se manca. */
  suspend fun save(slot: String, credentials: NasCredentials): SaveResult = withContext(dispatchers.io) {
    requireValidSlot(slot)
    val plaintext = CredentialCodec.encode(credentials)
    try {
      mutex.withLock { saveLocked(slot, plaintext) }
    } finally {
      plaintext.fill(0)
    }
  }

  /** Legge e decifra le credenziali della voce [slot]. Vedi [LoadResult]. */
  suspend fun load(slot: String): LoadResult = withContext(dispatchers.io) {
    requireValidSlot(slot)
    mutex.withLock { loadLocked(slot) }
  }

  /**
   * Prova a decifrare tutto quello che c'e' e restituisce lo stato vero. Da chiamare all'avvio e prima
   * del lavoro notturno: se la chiave e' sparita, lo scopre qui e passa a [CredentialStatus.NEEDS_PASSWORD].
   */
  suspend fun verify(): CredentialStatus = withContext(dispatchers.io) {
    val prefs = try {
      store.data.first()
    } catch (e: IOException) {
      return@withContext CredentialStatus.UNAVAILABLE
    }
    val slots = prefs.asMap().keys.map { it.name }.filter { it.startsWith(BLOB_PREFIX) }.map { it.removePrefix(BLOB_PREFIX) }
    for (slot in slots) {
      when (val result = mutex.withLock { loadLocked(slot) }) {
        is LoadResult.Found -> result.credentials.close()
        LoadResult.Unavailable -> return@withContext CredentialStatus.UNAVAILABLE
        LoadResult.Absent, LoadResult.NeedsPassword -> Unit
      }
    }
    try {
      statusOf(store.data.first())
    } catch (e: IOException) {
      CredentialStatus.UNAVAILABLE
    }
  }

  /** Cancella la voce [slot] (non la chiave). */
  suspend fun remove(slot: String) = withContext(dispatchers.io) {
    requireValidSlot(slot)
    mutex.withLock {
      store.edit { it.remove(blobKey(slot)) }
      Unit
    }
  }

  /**
   * Cancellazione sicura: distrugge la chiave e solo dopo i dati. Non si interrompe nemmeno se il
   * chiamante viene annullato. Se [WipeResult.keyDestroyed] e' falso, riprovare.
   */
  suspend fun wipeAll(): WipeResult = withContext(NonCancellable + dispatchers.io) {
    mutex.withLock {
      // 1. La chiave. Da qui in poi nessun blocco cifrato (nemmeno una copia fatta altrove) si apre piu'.
      val keyDestroyed = try {
        keys.delete(keyAlias)
        keys.find(keyAlias) == null
      } catch (e: Throwable) {
        GalleryLog.e(TAG) { "chiave non distrutta (${e.javaClass.simpleName})" }
        false
      }
      // 2. I dati. Anche se la chiave e' ancora li' si cancellano lo stesso: peggio non puo' essere.
      val dataCleared = try {
        store.edit { it.clear() }
        true
      } catch (e: IOException) {
        GalleryLog.e(TAG) { "archivio non cancellato (${e.javaClass.simpleName})" }
        false
      }
      WipeResult(keyDestroyed, dataCleared)
    }
  }

  /** Dove vive la chiave adesso (StrongBox, TEE...), o `null` se non c'e' nessuna chiave. Per la Diagnostica. */
  suspend fun protectionLevel(): KeyProtectionLevel? = withContext(dispatchers.io) {
    mutex.withLock {
      try {
        keys.find(keyAlias)?.let { keys.protectionLevel(it) }
      } catch (e: Exception) {
        null
      }
    }
  }

  // --- implementazione -----------------------------------------------------------------------------------

  private sealed interface KeyResolution {
    class Ok(val key: SecretKey, val level: KeyProtectionLevel) : KeyResolution
    data object HardwareRequired : KeyResolution
    data object Transient : KeyResolution
  }

  /** La chiave per cifrare: quella che c'e', o una nuova. */
  private fun resolveKeyForWrite(): KeyResolution {
    val existing = try {
      keys.find(keyAlias)
    } catch (e: Exception) {
      if (KeyFailure.of(e) == KeyFailure.TRANSIENT) {
        GalleryLog.w(TAG) { "Keystore non risponde (${e.javaClass.simpleName})" }
        return KeyResolution.Transient
      }
      // Chiave inutilizzabile: si butta e se ne crea una nuova.
      GalleryLog.w(TAG) { "chiave non recuperabile: ne creo una nuova" }
      runCatching { keys.delete(keyAlias) }
      null
    }
    if (existing != null) return KeyResolution.Ok(existing, keys.protectionLevel(existing))
    return try {
      val generated = keys.create(keyAlias)
      if (generated.level == KeyProtectionLevel.SOFTWARE && !allowSoftwareKey) {
        runCatching { keys.delete(keyAlias) }
        GalleryLog.e(TAG) { "il telefono non ha un Keystore hardware: credenziali non salvate" }
        KeyResolution.HardwareRequired
      } else {
        GalleryLog.i(TAG) { "chiave creata: ${generated.level}" }
        KeyResolution.Ok(generated.key, generated.level)
      }
    } catch (e: Exception) {
      GalleryLog.e(TAG) { "chiave non creata (${e.javaClass.simpleName})" }
      KeyResolution.Transient
    }
  }

  private suspend fun saveLocked(slot: String, plaintext: ByteArray): SaveResult {
    var keyWasReset = false
    var attempt = 0
    while (true) {
      attempt++
      val resolution = resolveKeyForWrite()
      val ok = when (resolution) {
        is KeyResolution.Ok -> resolution
        KeyResolution.HardwareRequired -> return SaveResult.HardwareKeyUnavailable
        KeyResolution.Transient -> return SaveResult.Unavailable
      }
      try {
        val sealed = AesGcm.seal(ok.key, plaintext, aadFor(slot))
        persist(slot, sealed, clearOthers = keyWasReset)
        return SaveResult.Saved(ok.level)
      } catch (e: CancellationException) {
        throw e
      } catch (e: IOException) {
        GalleryLog.w(TAG) { "archivio non scrivibile (${e.javaClass.simpleName})" }
        return SaveResult.Unavailable
      } catch (e: Exception) {
        val failure = KeyFailure.of(e)
        GalleryLog.w(TAG) { "cifratura fallita: $failure (${e.javaClass.simpleName})" }
        if (failure == KeyFailure.TRANSIENT || attempt >= 2) return SaveResult.Unavailable
        // La chiave che c'era non funziona piu': nuova chiave, e i blocchi vecchi (illeggibili) se ne vanno.
        runCatching { keys.delete(keyAlias) }
        keyWasReset = true
      }
    }
  }

  private suspend fun persist(slot: String, sealed: ByteArray, clearOthers: Boolean) {
    val encoded = Base64.getEncoder().encodeToString(sealed)
    store.edit { prefs ->
      if (clearOthers) prefs.asMap().keys.filter { it.name.startsWith(BLOB_PREFIX) }.forEach { prefs.remove(it) }
      prefs[blobKey(slot)] = encoded
      prefs.remove(NEEDS_PASSWORD)
    }
  }

  private suspend fun loadLocked(slot: String): LoadResult {
    val prefs = try {
      store.data.first()
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "archivio non leggibile (${e.javaClass.simpleName})" }
      return LoadResult.Unavailable
    }
    val encoded = prefs[blobKey(slot)]
      ?: return if (prefs[NEEDS_PASSWORD] == true) LoadResult.NeedsPassword else LoadResult.Absent

    val failure: KeyFailure = try {
      val sealed = try {
        Base64.getDecoder().decode(encoded)
      } catch (e: IllegalArgumentException) {
        throw SealedFormatException("base64 non valido")
      }
      val key = keys.find(keyAlias) ?: throw KeyMissingException()
      val plaintext = AesGcm.open(key, sealed, aadFor(slot))
      try {
        return LoadResult.Found(CredentialCodec.decode(plaintext))
      } finally {
        plaintext.fill(0)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      val kind = if (e is KeyMissingException) KeyFailure.INVALIDATED else KeyFailure.of(e)
      GalleryLog.w(TAG) { "credenziali non utilizzabili: $kind (${e.javaClass.simpleName})" }
      kind
    }

    return when (failure) {
      KeyFailure.TRANSIENT -> LoadResult.Unavailable
      KeyFailure.INVALIDATED -> {
        discardUnusable(slot = null, destroyKey = true)
        LoadResult.NeedsPassword
      }
      KeyFailure.BAD_DATA -> {
        discardUnusable(slot = slot, destroyKey = false)
        LoadResult.NeedsPassword
      }
    }
  }

  /** Butta il materiale inutilizzabile (prima la chiave, poi i blocchi) e ricorda che serve la password. */
  private suspend fun discardUnusable(slot: String?, destroyKey: Boolean) {
    if (destroyKey) runCatching { keys.delete(keyAlias) }
    try {
      store.edit { prefs ->
        val victims = prefs.asMap().keys.filter { key -> key.name.startsWith(BLOB_PREFIX) && (slot == null || key == blobKey(slot)) }
        victims.forEach { prefs.remove(it) }
        prefs[NEEDS_PASSWORD] = true
      }
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "archivio non aggiornato (${e.javaClass.simpleName})" }
    }
  }

  private class KeyMissingException : Exception("chiave assente")

  companion object {
    const val DEFAULT_KEY_ALIAS = "dev.pampa.mycloudgallery.credentials.v1"

    private const val TAG = "CredentialVault"
    private const val BLOB_PREFIX = "cred."
    private const val RETRY_BASE_MILLIS = 1_000L
    private const val RETRY_MAX_MILLIS = 30_000L
    private const val MAX_RETRY_SHIFT = 5
    private val SLOT_PATTERN = Regex("^[a-z0-9._-]{1,64}$")
    private val NEEDS_PASSWORD = booleanPreferencesKey("needs_password")
    private val AAD_PREFIX = "dev.pampa.mycloudgallery.credentials.v1:".toByteArray(Charsets.UTF_8)

    internal fun blobKey(slot: String) = stringPreferencesKey(BLOB_PREFIX + slot)

    internal fun aadFor(slot: String): ByteArray = AAD_PREFIX + slot.toByteArray(Charsets.UTF_8)

    internal fun requireValidSlot(slot: String) {
      require(SLOT_PATTERN.matches(slot)) { "nome della voce non valido" }
    }

    internal fun statusOf(prefs: Preferences): CredentialStatus = when {
      prefs[NEEDS_PASSWORD] == true -> CredentialStatus.NEEDS_PASSWORD
      prefs.asMap().keys.any { it.name.startsWith(BLOB_PREFIX) } -> CredentialStatus.READY
      else -> CredentialStatus.EMPTY
    }

    @Volatile
    private var instance: CredentialVault? = null

    /**
     * L'unico vault del processo. [allowSoftwareKey] va a `true` solo nelle build di debug (emulatori
     * senza Keystore hardware): in release un telefono senza hardware non salva le credenziali.
     */
    fun getInstance(context: Context, allowSoftwareKey: Boolean = false): CredentialVault {
      instance?.let { return it }
      return synchronized(this) {
        instance ?: create(context.applicationContext, allowSoftwareKey).also { instance = it }
      }
    }

    private fun create(context: Context, allowSoftwareKey: Boolean): CredentialVault {
      val hasStrongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
      val dispatchers = DefaultDispatcherProvider
      val store = PreferenceDataStoreFactory.create(
        // File danneggiato: si riparte da zero ma si ricorda che serve di nuovo la password.
        corruptionHandler = ReplaceFileCorruptionHandler { preferencesOf(NEEDS_PASSWORD to true) },
        scope = CoroutineScope(SupervisorJob() + dispatchers.io),
        // noBackupFilesDir: fuori da qualunque backup e trasferimento, per costruzione.
        produceFile = { File(context.noBackupFilesDir, "datastore/credentials.preferences_pb") },
      )
      return CredentialVault(AndroidSecretKeyStore(hasStrongBox), store, allowSoftwareKey, dispatchers)
    }
  }
}
