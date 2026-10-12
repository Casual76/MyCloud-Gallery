package dev.pampa.mycloudgallery.core.security.credentials

import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.UnrecoverableKeyException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

/**
 * Perche' un'operazione col Keystore e' fallita, ridotta alle uniche tre cose che cambiano cosa fa
 * il vault (E-S9):
 *
 * - [INVALIDATED]: la chiave non c'e' piu' o non e' piu' utilizzabile (ripristino, aggiornamento del
 *   sistema, chiave invalidata). Non tornera': serve di nuovo la password.
 * - [BAD_DATA]: la chiave c'e' ma il blocco non si decifra (manomesso, danneggiato, copiato altrove).
 *   Anche questo non si recupera: serve di nuovo la password.
 * - [TRANSIENT]: guasto passeggero (Keystore occupato, archiviazione non ancora sbloccata dopo un
 *   riavvio, errore di I/O). Si riprova piu' tardi e **non si cancella nulla**: scambiare un guasto
 *   passeggero per un'invalidazione farebbe perdere credenziali buone.
 */
internal enum class KeyFailure {
  INVALIDATED,
  BAD_DATA,
  TRANSIENT,
  ;

  companion object {

    /** Classifica guardando tutta la catena delle cause (il Keystore avvolge le eccezioni vere). */
    fun of(throwable: Throwable): KeyFailure {
      val chain = generateSequence(throwable) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CHAIN).toList()
      return when {
        chain.any { it is KeyPermanentlyInvalidatedException } -> INVALIDATED
        // Un guasto che il sistema dichiara passeggero vince sul resto (UnrecoverableKeyException puo'
        // avvolgerne uno).
        chain.any { isTransientKeystoreFailure(it) } -> TRANSIENT
        chain.any { it is UnrecoverableKeyException } -> INVALIDATED
        chain.any { it is BadPaddingException || it is IllegalBlockSizeException } -> BAD_DATA
        chain.any { it is SealedFormatException || it is CredentialFormatException } -> BAD_DATA
        else -> TRANSIENT
      }
    }

    private fun isTransientKeystoreFailure(throwable: Throwable): Boolean =
      Build.VERSION.SDK_INT >= 33 && throwable is android.security.KeyStoreException && throwable.isTransientFailure

    private const val MAX_CHAIN = 8
  }
}
