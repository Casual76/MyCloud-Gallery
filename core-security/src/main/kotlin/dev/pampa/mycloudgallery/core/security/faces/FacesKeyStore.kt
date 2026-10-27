package dev.pampa.mycloudgallery.core.security.faces

import android.content.Context
import android.content.pm.PackageManager
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.files.Patience
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.security.credentials.AesGcm
import dev.pampa.mycloudgallery.core.security.credentials.AndroidSecretKeyStore
import dev.pampa.mycloudgallery.core.security.credentials.KeyFailure
import dev.pampa.mycloudgallery.core.security.credentials.KeyProtectionLevel
import dev.pampa.mycloudgallery.core.security.credentials.SecretKeyStore
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Le chiavi dei volti sul Keystore (Fase 8, P6; `docs/fase8-progetto.md` D14, D29, E-F19, E-F35; `docs/sicurezza.md` C39, C45).
 *
 * **Come e' fatta.** Per ogni generazione G c'e' una chiave AES-256-GCM `mcg-faces-<G>` nell'Android Keystore (StrongBox se il telefono lo
 * ha, altrimenti il TEE; **senza** autenticazione dell'utente e senza telefono sbloccato: il calcolo dei volti gira di notte, come le
 * credenziali del NAS, C14, L4) e una **chiave dati** casuale di 256 bit che cifra davvero (`FacesDataKey`, in memoria). La chiave dati
 * non e' mai su disco in chiaro: sta in un piccolo file (`<alias>.key` in [directory], di norma `noBackupFilesDir/faces-keys/`)
 * **avvolta** dalla chiave del Keystore (stesso formato dei blocchi delle credenziali, `aad` legato all'alias). Il Keystore fa cosi' poche
 * operazioni (una per apertura), non una per riga, e la chiave che conta non esce dall'hardware.
 *
 * **Distruggere.** [destroy] toglie **per prima** la chiave del Keystore (poi il file avvolto): senza quella chiave nessuna copia del file
 * avvolto, di una pagina libera di SQLite o di un blocco della flash si apre piu' (cancellazione crittografica, C39). Vero solo se la chiave
 * non c'e' piu' **e** il file avvolto e' sparito (verificato con `StateFiles`); falso = riprovare.
 *
 * **Chiave sparita (E-F19).** Un ripristino del telefono o un aggiornamento che rende la chiave inutilizzabile fanno [open] -> null: chi
 * salva i dati cifrati li cancella e li riscarica o li ricalcola, senza crollo. [state] distingue "sparita" da "non risponde adesso": solo la
 * prima autorizza a cancellare.
 *
 * **Mai nei log** (D37): ne' la generazione, ne' l'alias, ne' un byte della chiave; solo il tipo dell'errore.
 */
class FacesKeyStore internal constructor(
  private val keystore: SecretKeyStore,
  private val keystoreAliases: () -> Collection<String>,
  private val directory: File,
  private val allowSoftwareKey: Boolean,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  private val random: SecureRandom = SecureRandom(),
  private val patience: Patience = Patience.STANDARD,
) : FacesKeys, FacesKeyProbe {

  private val mutex = Mutex()

  override suspend fun create(generation: FaceGeneration): Boolean = withContext(dispatchers.io) {
    mutex.withLock { createLocked(generation) }
  }

  override suspend fun open(generation: FaceGeneration): FacesDataKey? = withContext(dispatchers.io) {
    mutex.withLock {
      when (val found = inspect(generation)) {
        is Inspection.Usable -> AesGcmDataKey(found.material, random)
        else -> null
      }
    }
  }

  override suspend fun state(generation: FaceGeneration): FacesKeyState = withContext(dispatchers.io) {
    mutex.withLock {
      when (val found = inspect(generation)) {
        is Inspection.Usable -> {
          found.material.fill(0)
          FacesKeyState.USABLE
        }
        Inspection.Missing -> FacesKeyState.MISSING
        Inspection.Unusable -> FacesKeyState.UNUSABLE
        Inspection.Unavailable -> FacesKeyState.UNAVAILABLE
      }
    }
  }

  override suspend fun destroy(generation: FaceGeneration): Boolean = withContext(NonCancellable + dispatchers.io) {
    mutex.withLock { destroyLocked(generation) }
  }

  override suspend fun destroyAll(): Boolean = withContext(NonCancellable + dispatchers.io) {
    mutex.withLock {
      // Le generazioni che si conoscono da un alias del Keystore o da un file avvolto; un alias dei volti che non e' una generazione
      // (nome storpiato) si toglie lo stesso: "tutte le chiavi mcg-faces-*".
      val aliases = try {
        keystoreAliases().filter { it.startsWith(FacesKeyAliases.PREFIX) }
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "elenco delle chiavi non letto (${e.javaClass.simpleName})" }
        return@withLock false
      }
      val files = directory.listFiles { f -> f.name.endsWith(FILE_SUFFIX) }.orEmpty().map { it.name.removeSuffix(FILE_SUFFIX) }
      var allGone = true
      for (alias in (aliases + files.filter { it.startsWith(FacesKeyAliases.PREFIX) }).toSortedSet()) {
        if (!destroyAlias(alias)) allGone = false
      }
      allGone
    }
  }

  /** Dove vive la chiave di [generation] adesso (StrongBox, TEE...), o null se non c'e' o non si sa. Per la Diagnostica e le prove. */
  suspend fun protectionLevel(generation: FaceGeneration): KeyProtectionLevel? = withContext(dispatchers.io) {
    mutex.withLock {
      try {
        keystore.find(FacesKeyAliases.aliasOf(generation))?.let { keystore.protectionLevel(it) }
      } catch (e: Exception) {
        null
      }
    }
  }

  // --- implementazione -----------------------------------------------------------------------------------------------------------

  private sealed interface Inspection {
    /** La chiave dati in chiaro (chi la riceve la azzera). */
    class Usable(val material: ByteArray) : Inspection

    data object Missing : Inspection

    data object Unusable : Inspection

    data object Unavailable : Inspection
  }

  private fun wrappedFile(alias: String): File = File(directory, alias + FILE_SUFFIX)

  private fun aadOf(alias: String): ByteArray = (AAD_PREFIX + alias).toByteArray(Charsets.UTF_8)

  private fun inspect(generation: FaceGeneration): Inspection {
    val alias = FacesKeyAliases.aliasOf(generation)
    val blob = try {
      StateFiles.readBytes(wrappedFile(alias), label = FILE_LABEL, patience = patience)
    } catch (e: IOException) {
      return Inspection.Unavailable
    }
    val key: SecretKey? = try {
      keystore.find(alias)
    } catch (e: Exception) {
      return when (KeyFailure.of(e)) {
        KeyFailure.TRANSIENT -> {
          GalleryLog.w(TAG) { "Keystore non risponde (${e.javaClass.simpleName})" }
          Inspection.Unavailable
        }
        // La chiave c'e' ma e' invalidata o irrecuperabile: per i dati e' come se non ci fosse.
        KeyFailure.INVALIDATED, KeyFailure.BAD_DATA -> Inspection.Unusable
      }
    }
    if (blob == null && key == null) return Inspection.Missing
    if (blob == null || key == null) return Inspection.Unusable
    return try {
      val material = AesGcm.open(key, blob, aadOf(alias))
      if (material.size == AesGcmDataKey.KEY_BYTES) {
        Inspection.Usable(material)
      } else {
        material.fill(0)
        Inspection.Unusable
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      when (KeyFailure.of(e)) {
        KeyFailure.TRANSIENT -> {
          GalleryLog.w(TAG) { "Keystore non risponde (${e.javaClass.simpleName})" }
          Inspection.Unavailable
        }
        KeyFailure.INVALIDATED, KeyFailure.BAD_DATA -> Inspection.Unusable
      }
    }
  }

  private fun createLocked(generation: FaceGeneration): Boolean {
    when (val found = inspect(generation)) {
      is Inspection.Usable -> {
        // C'e' gia' e funziona: si tiene (rifarla renderebbe illeggibili i dati cifrati con quella).
        found.material.fill(0)
        return true
      }
      Inspection.Unavailable -> return false
      Inspection.Missing, Inspection.Unusable -> Unit
    }
    val alias = FacesKeyAliases.aliasOf(generation)
    // Quel che resta di una chiave inutilizzabile (la chiave del Keystore senza il suo file, o il file senza la chiave) se ne va.
    if (!destroyAlias(alias)) return false
    val generated = try {
      keystore.create(alias)
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "chiave non creata (${e.javaClass.simpleName})" }
      return false
    }
    if (generated.level == KeyProtectionLevel.SOFTWARE && !allowSoftwareKey) {
      runCatching { keystore.delete(alias) }
      GalleryLog.e(TAG) { "il telefono non ha un Keystore hardware: chiave dei volti non creata" }
      return false
    }
    val material = ByteArray(AesGcmDataKey.KEY_BYTES).also { random.nextBytes(it) }
    try {
      val wrapped = AesGcm.seal(generated.key, material, aadOf(alias))
      StateFiles.writeBytes(wrappedFile(alias), wrapped, label = FILE_LABEL, patience = patience)
    } catch (e: CancellationException) {
      runCatching { keystore.delete(alias) }
      throw e
    } catch (e: Exception) {
      // Senza il file avvolto la chiave del Keystore non serve a niente: via anche lei, non resta un alias orfano.
      GalleryLog.w(TAG) { "chiave dati non avvolta (${e.javaClass.simpleName})" }
      runCatching { keystore.delete(alias) }
      return false
    } finally {
      material.fill(0)
    }
    GalleryLog.i(TAG) { "chiave creata: ${generated.level}" }
    return true
  }

  private fun destroyLocked(generation: FaceGeneration): Boolean = destroyAlias(FacesKeyAliases.aliasOf(generation))

  /** Prima la chiave del Keystore, poi il file avvolto; vero solo se non resta ne' l'una ne' l'altro. */
  private fun destroyAlias(alias: String): Boolean {
    val keyGone = try {
      keystore.delete(alias)
      keystore.find(alias) == null
    } catch (e: Exception) {
      GalleryLog.e(TAG) { "chiave non distrutta (${e.javaClass.simpleName})" }
      false
    }
    // Anche con la chiave ancora li' si toglie il file: la chiave dati non esiste altrove, peggio non puo' essere.
    val fileGone = StateFiles.delete(wrappedFile(alias), label = FILE_LABEL, patience = patience)
    return keyGone && fileGone
  }

  /** D37: mai chiavi, alias o generazioni. */
  override fun toString(): String = "FacesKeyStore"

  companion object {
    private const val TAG = "FacesKeyStore"
    private const val FILE_LABEL = "faces-key"
    private const val FILE_SUFFIX = ".key"
    private const val AAD_PREFIX = "dev.pampa.mycloudgallery.faces.key.v1:"

    /** La cartella (sotto `noBackupFilesDir`) dei file che avvolgono le chiavi dati. */
    const val DIRECTORY: String = "faces-keys"

    /**
     * Le chiavi vere: Keystore del telefono, file avvolti in `noBackupFilesDir/faces-keys/` (fuori da qualunque backup e trasferimento).
     * [allowSoftwareKey] solo nelle build di debug sugli emulatori senza Keystore hardware: in release un telefono senza non crea la chiave.
     */
    fun forContext(context: Context, allowSoftwareKey: Boolean = false): FacesKeyStore {
      val app = context.applicationContext
      val hasStrongBox = app.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
      return FacesKeyStore(
        keystore = AndroidSecretKeyStore(hasStrongBox),
        keystoreAliases = { androidAliases() },
        directory = File(app.noBackupFilesDir, DIRECTORY),
        allowSoftwareKey = allowSoftwareKey,
      )
    }

    private fun androidAliases(): List<String> =
      KeyStore.getInstance(AndroidSecretKeyStore.PROVIDER).apply { load(null) }.aliases().toList()
  }
}
