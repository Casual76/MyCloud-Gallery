package dev.pampa.mycloudgallery.core.trust.crypto

import javax.crypto.Cipher

/**
 * Per che cosa si apre la cassaforte della chiave di famiglia: il foglio di autenticazione di sistema lo dice con
 * parole sue (le sceglie l'app, che conosce le risorse).
 */
enum class FamilyKeyUse {
  /** Custodire una chiave di famiglia sul telefono: fondazione (passo 12), recupero dell'admin, reset della famiglia. */
  SAVE,

  /** Firmare una modifica del registro: approvare, segnare perso o dismesso, rinominare, cambiare la configurazione, unire rami, ripubblicare. */
  SIGN,
}

/**
 * Perche' la cassaforte non ha dato (o non ha custodito) la chiave di famiglia. In ogni caso **niente e' stato firmato** e
 * niente e' andato perso: la chiave resta nel kit di recupero e, se c'era, nella cassaforte.
 */
enum class VaultRefusal {
  /** L'admin ha chiuso il foglio (o il sistema l'ha chiuso: l'app e' andata in secondo piano). Si riprova con il gesto. */
  CANCELLED,

  /** Troppi tentativi sbagliati: il SISTEMA ha bloccato impronta e PIN per un po' (E-S4). Si riprova piu' tardi. */
  LOCKED_OUT,

  /**
   * Il foglio non si puo' mostrare adesso: nessuna schermata dell'app in primo piano (un lavoro in background non apre mai la
   * chiave di famiglia), hardware occupato, un errore passeggero del sistema.
   */
  UNAVAILABLE,

  /** Il telefono non ha un blocco schermo sicuro (su Android 10 serve anche un'impronta: il PIN non si lega a una chiave per ogni uso). */
  NO_SECURE_LOCK,

  /**
   * La chiave del Keystore che cifra la cassaforte non vale piu' (impronte o blocco schermo cambiati: E-S9, E-H3), o manca, o la
   * cassaforte non si decifra. La chiave di famiglia non si recupera da qui: si passa dal **kit di recupero** o dal **secondo admin**.
   */
  INVALIDATED,
}

/** L'esito di un'operazione della cassaforte. */
sealed interface VaultResult<out T> {
  /** Fatto, dopo l'autenticazione dell'admin. */
  data class Done<out T>(val value: T) : VaultResult<T>

  /** Nella cassaforte non c'e' una chiave di famiglia, o non e' quella attesa: questo telefono non e' l'admin di questa famiglia. */
  data object Absent : VaultResult<Nothing>

  /** Non si e' potuto: [reason]. Niente firmato. */
  data class Refused(val reason: VaultRefusal) : VaultResult<Nothing>
}

/** L'esito del foglio di autenticazione. */
sealed interface VaultAuth {
  /** L'utente si e' autenticato: [cipher] (quello del `CryptoObject`) vale per **una** operazione. */
  class Authenticated(val cipher: Cipher) : VaultAuth

  data class Refused(val reason: VaultRefusal) : VaultAuth
}

/**
 * Il foglio di autenticazione di sistema per la cassaforte (revisione 1.4, M3, decisione 2(a) dell'utente del 31/10): nel telefono
 * vero `BiometricPrompt` con un `CryptoObject` sul [Cipher] della cassaforte, impronta o PIN, e **solo** sopra una schermata
 * dell'app in primo piano (lo monta `:app`). Il Keystore usa la chiave solo per l'operazione autenticata, quindi un
 * autenticatore che mentisse non aprirebbe niente.
 */
fun interface VaultAuthenticator {
  /** Mostra il foglio legato a [cipher] e aspetta. Annullabile. */
  suspend fun authenticate(cipher: Cipher, use: FamilyKeyUse): VaultAuth

  companion object {
    /** Nessun foglio (nessuna interfaccia): la cassaforte non si apre mai. */
    val NONE: VaultAuthenticator = VaultAuthenticator { _, _ -> VaultAuth.Refused(VaultRefusal.UNAVAILABLE) }
  }
}

/**
 * La cassaforte com'era, ancora cifrata: [putBack] la rimette al suo posto **senza** autenticazione (nessuna chiave in chiaro). Serve a
 * un'operazione a piu' passi che custodisce una chiave nuova e poi non finisce (il reset della famiglia), per non chiedere di nuovo
 * l'impronta solo per tornare indietro.
 */
fun interface VaultCopy {
  fun putBack()
}

/**
 * Dove il dispositivo admin conserva la chiave di famiglia tra un'operazione e l'altra. Implementazione reale:
 * [AndroidKeystoreFamilyKeyVault].
 *
 * **Ogni** [store] e ogni [load] passano dal foglio di autenticazione (decisione 2(a)): la chiave di famiglia in chiaro esiste solo in
 * memoria, per l'azione dell'admin che l'ha chiesta, e si chiede di nuovo all'azione successiva. Un lavoro in background non la
 * chiede mai (nessun chiamante fuori dai gesti dell'admin; senza una schermata in primo piano il foglio non si mostra).
 */
interface FamilyKeyVault {
  /** Custodisce [key] al posto di quella che c'era, dopo l'impronta o il PIN. */
  suspend fun store(key: FamilyKey, use: FamilyKeyUse = FamilyKeyUse.SAVE): VaultResult<Unit>

  /**
   * La chiave conservata, se coincide con [expected], dopo l'impronta o il PIN. [VaultResult.Absent] senza chiedere niente se la
   * cassaforte e' vuota o tiene un'altra chiave.
   */
  suspend fun load(expected: EcPublicKey, use: FamilyKeyUse = FamilyKeyUse.SIGN): VaultResult<FamilyKey>

  /** Una copia della cassaforte com'e' adesso (cifrata), da rimettere con [VaultCopy.putBack]; null se e' vuota. */
  fun keep(): VaultCopy?

  fun destroy()
}
