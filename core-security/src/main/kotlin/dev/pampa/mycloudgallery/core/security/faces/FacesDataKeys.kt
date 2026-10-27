package dev.pampa.mycloudgallery.core.security.faces

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// LA CHIAVE DATI APERTA E LO STATO DELLE CHIAVI DEI VOLTI (docs/fase8-progetto.md D14, E-F19). Contratti di P0 in `FacesKeys.kt`; qui il
// pezzo vero di P6 che cifra (`AesGcmDataKey`) e l'estensione che serve a distinguere una chiave **sparita** da una che **non risponde adesso**
// (`FacesKeyProbe`): chi cancella i dati perche' la chiave e' sparita non deve farlo per un guasto passeggero del Keystore.

/**
 * La condizione della chiave di una generazione (E-F19), vista senza usarla:
 * - [USABLE]: la chiave dati si apre;
 * - [MISSING]: non c'e' niente (mai creata, o distrutta e ripulita);
 * - [UNUSABLE]: c'e' qualcosa ma non si apre piu' (ripristino del telefono, aggiornamento del sistema, chiave invalidata, blocco avvolto
 *   danneggiato o di un'altra chiave): i dati cifrati con quella chiave sono perduti per sempre;
 * - [UNAVAILABLE]: il Keystore o l'archivio non rispondono adesso (guasto passeggero): **non** si sa, non si cancella niente.
 */
enum class FacesKeyState { USABLE, MISSING, UNUSABLE, UNAVAILABLE }

/**
 * Le chiavi dei volti che sanno dire in che condizione e' una chiave senza aprirla ([FacesKeyStore]; il fake di P0 non lo e': chi lo usa
 * ripiega su piu' tentativi). Il negozio dei volti (`store/`) lo chiede prima di dichiarare una chiave sparita.
 */
interface FacesKeyProbe {
  suspend fun state(generation: FaceGeneration): FacesKeyState
}

/**
 * La chiave dati aperta, AES-256-GCM della JVM/Conscrypt in memoria (il Keystore ha solo la chiave che la avvolge, D14): una cifratura per
 * contenuto, non per volto, quindi poche operazioni. Formato di un blocco:
 *
 * ```
 * [versione: 1 byte][nonce: 12 byte casuali][testo cifrato + tag di autenticazione: n + 16 byte]
 * ```
 *
 * Il nonce e' nuovo a ogni cifratura (96 bit casuali: con meno di 2^32 cifrature per chiave la collisione e' trascurabile, e una chiave
 * vive per una generazione). L'`aad` lega il blocco al suo posto (tabella, colonna, riga): spostato in un'altra riga non si apre. Il
 * materiale non esce mai (nessun getter); [close] lo azzera; dopo, [seal] e [open] lanciano `IllegalStateException`.
 */
internal class AesGcmDataKey(material: ByteArray, private val random: SecureRandom) : FacesDataKey {

  private val key: ByteArray = material

  @Volatile private var closed = false

  override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
    check(!closed) { "Chiave chiusa." }
    val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
    cipher.updateAAD(aad)
    val out = ByteArray(HEADER_BYTES + cipher.getOutputSize(plain.size))
    out[0] = VERSION
    System.arraycopy(nonce, 0, out, 1, NONCE_BYTES)
    cipher.doFinal(plain, 0, plain.size, out, HEADER_BYTES)
    return out
  }

  override fun open(sealed: ByteArray, aad: ByteArray): ByteArray? {
    check(!closed) { "Chiave chiusa." }
    if (sealed.size < HEADER_BYTES + TAG_BITS / 8 || sealed[0] != VERSION) return null
    return try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 1, NONCE_BYTES))
      cipher.updateAAD(aad)
      cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES)
    } catch (_: GeneralSecurityException) {
      // Altra chiave, altra riga, byte alterati: mai un'eccezione per un dato ostile.
      null
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  override fun close() {
    closed = true
    key.fill(0)
  }

  /** D37: mai il materiale ne' la generazione. */
  override fun toString(): String = "AesGcmDataKey"

  companion object {
    const val VERSION: Byte = 1
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    private const val HEADER_BYTES = 1 + NONCE_BYTES
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
  }
}
