package dev.pampa.mycloudgallery.core.trust.crypto

import android.security.keystore.KeyProperties
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.GeneralSecurityException
import java.security.InvalidAlgorithmParameterException
import java.util.Collections
import java.util.IdentityHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 10, correzione 1.4 M3 (decisione 2(a) dell'utente del 31/10): la cassaforte della chiave di famiglia con un Keystore finto che,
 * come quello vero, non cifra ne' decifra con una chiave "con autenticazione" se il `Cipher` non e' passato da un'autenticazione
 * riuscita, e un foglio di sistema finto che conferma, annulla o mente.
 *
 * - nessuna chiave di famiglia (quindi nessuna firma) senza il foglio, a ogni uso, e nemmeno con un foglio che mente;
 * - annullare non da' la chiave e non cambia la cassaforte;
 * - la migrazione delle cassaforti della versione 1 dopo un'autenticazione, senza perdere la chiave, e il kit come via se non si puo';
 * - la chiave del Keystore invalidata (impronte o blocco cambiati): un messaggio, nessuna perdita, il recupero la sostituisce;
 * - la politica della chiave come dati, per Android 10 e da Android 11 (quella vera la prova `KeystoreDeviceTest`).
 */
class FamilyKeyVaultTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val keystore = FakeVaultKeystore()
  private val sheet = FakeSheet(keystore)
  private val file: File by lazy { File(temp.root, "family-key.vault") }
  private var deviceSecure = true
  private val vault by lazy { AndroidKeystoreFamilyKeyVault(file, { deviceSecure }, sheet, keystore, ALIAS, LEGACY) }

  // --- Nessuna firma senza autenticazione ---------------------------------------------------------------

  @Test
  fun ogniUsoChiedeIlFoglio_eLaChiaveFirmaSoloDopo() = runBlocking<Unit> {
    val key = FamilyKey.generate()

    assertThat(vault.store(key)).isEqualTo(VaultResult.Done(Unit))
    assertThat(sheet.shown).containsExactly(FamilyKeyUse.SAVE)
    val first = vault.load(key.publicKey) as VaultResult.Done
    val second = vault.load(key.publicKey) as VaultResult.Done

    // Un foglio per ogni uso: nessuna chiave tenuta aperta fra un'azione e l'altra.
    assertThat(sheet.shown).containsExactly(FamilyKeyUse.SAVE, FamilyKeyUse.SIGN, FamilyKeyUse.SIGN).inOrder()
    assertThat(keystore.finishes).isEqualTo(3)
    val message = "registro".toByteArray()
    assertThat(key.publicKey.verify(message, first.value.sign(message))).isTrue()
    assertThat(key.publicKey.verify(message, second.value.sign(message))).isTrue()
  }

  @Test
  fun unFoglioCheMente_nonApreNiente_ilKeystoreVuoleLAutenticazione() = runBlocking<Unit> {
    val key = FamilyKey.generate()
    vault.store(key)
    val before = file.readBytes()

    sheet.lie = true
    assertThat(vault.load(key.publicKey)).isEqualTo(VaultResult.Refused(VaultRefusal.UNAVAILABLE))
    assertThat(vault.store(FamilyKey.generate())).isEqualTo(VaultResult.Refused(VaultRefusal.UNAVAILABLE))

    assertThat(file.readBytes()).isEqualTo(before)
    assertThat(keystore.refused).isEqualTo(2)
  }

  @Test
  fun annullareIlFoglio_nienteChiave_nienteCambia_poiSiRiprova() = runBlocking<Unit> {
    val key = FamilyKey.generate()
    vault.store(key)
    val before = file.readBytes()

    sheet.answer = VaultRefusal.CANCELLED
    assertThat(vault.load(key.publicKey)).isEqualTo(VaultResult.Refused(VaultRefusal.CANCELLED))
    assertThat(vault.store(FamilyKey.generate())).isEqualTo(VaultResult.Refused(VaultRefusal.CANCELLED))
    sheet.answer = VaultRefusal.LOCKED_OUT
    assertThat(vault.load(key.publicKey)).isEqualTo(VaultResult.Refused(VaultRefusal.LOCKED_OUT))

    assertThat(file.readBytes()).isEqualTo(before)
    assertThat(keystore.finishes).isEqualTo(1) // solo la prima custodia
    sheet.answer = null
    assertThat((vault.load(key.publicKey) as VaultResult.Done).value.publicKey).isEqualTo(key.publicKey)
  }

  @Test
  fun unAltraChiave_oCassaforteVuota_nessunFoglio() = runBlocking<Unit> {
    assertThat(vault.load(FamilyKey.generate().publicKey)).isEqualTo(VaultResult.Absent)
    vault.store(FamilyKey.generate())

    assertThat(vault.load(FamilyKey.generate().publicKey)).isEqualTo(VaultResult.Absent)
    assertThat(sheet.shown).containsExactly(FamilyKeyUse.SAVE)
  }

  @Test
  fun senzaBloccoSchermoSicuro_nessunFoglioENessunaChiaveCreata() = runBlocking<Unit> {
    deviceSecure = false

    assertThat(vault.store(FamilyKey.generate())).isEqualTo(VaultResult.Refused(VaultRefusal.NO_SECURE_LOCK))
    assertThat(sheet.shown).isEmpty()
    assertThat(keystore.entries).isEmpty()
  }

  @Test
  fun androidNonCreaLaChiaveConAutenticazione_bloccoSchermoMancante() = runBlocking<Unit> {
    // Android 11+ senza blocco schermo sicuro, o Android 10 senza impronte: la generazione lancia.
    keystore.createFails = InvalidAlgorithmParameterException("Secure lock screen must be enabled")

    assertThat(vault.store(FamilyKey.generate())).isEqualTo(VaultResult.Refused(VaultRefusal.NO_SECURE_LOCK))
    assertThat(sheet.shown).isEmpty()
    assertThat(file.exists()).isFalse()
  }

  // --- Biometria cambiata: chiave invalidata (E-S9, E-H3) ------------------------------------------------

  @Test
  fun chiaveInvalidata_ilMessaggioDiceIlKit_nienteSiPerde_eIlRecuperoLaSostituisce() = runBlocking<Unit> {
    val key = FamilyKey.generate()
    vault.store(key)
    keystore.entries.getValue(ALIAS).invalidated = true

    assertThat(vault.load(key.publicKey)).isEqualTo(VaultResult.Refused(VaultRefusal.INVALIDATED))
    assertThat(sheet.shown).containsExactly(FamilyKeyUse.SAVE) // nessun foglio inutile
    assertThat(file.exists()).isTrue() // nessuna perdita silenziosa: niente si cancella da solo

    // Il recupero con il kit custodisce di nuovo la chiave: una chiave del Keystore nuova, dopo il foglio.
    assertThat(vault.store(key)).isEqualTo(VaultResult.Done(Unit))
    assertThat(keystore.entries.getValue(ALIAS).invalidated).isFalse()
    assertThat((vault.load(key.publicKey) as VaultResult.Done).value.publicKey).isEqualTo(key.publicKey)
  }

  @Test
  fun chiaveDelKeystoreSparita_ilKit() = runBlocking<Unit> {
    val key = FamilyKey.generate()
    vault.store(key)
    keystore.entries.remove(ALIAS)

    assertThat(vault.load(key.publicKey)).isEqualTo(VaultResult.Refused(VaultRefusal.INVALIDATED))
  }

  @Test
  fun cassaforteRovinata_ilKit() = runBlocking<Unit> {
    val key = FamilyKey.generate()
    vault.store(key)
    val bytes = file.readBytes()
    bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
    file.writeBytes(bytes)

    assertThat(vault.load(key.publicKey)).isEqualTo(VaultResult.Refused(VaultRefusal.INVALIDATED))
    file.writeBytes("spazzatura".toByteArray())
    assertThat(vault.load(key.publicKey)).isEqualTo(VaultResult.Refused(VaultRefusal.INVALIDATED))
  }

  // --- Migrazione dalla versione 1 ------------------------------------------------------------------------

  @Test
  fun migrazione_dopoIlFoglio_siRicifraConLaChiaveNuova_senzaPerdereLaChiaveDiFamiglia() = runBlocking<Unit> {
    val key = FamilyKey.generate()
    writeLegacyVault(key)

    val opened = vault.load(key.publicKey) as VaultResult.Done

    assertThat(opened.value.publicKey).isEqualTo(key.publicKey)
    assertThat(sheet.shown).containsExactly(FamilyKeyUse.SIGN)
    assertThat(file.readBytes()[4]).isEqualTo(2.toByte())
    assertThat(keystore.entries.keys).containsExactly(ALIAS) // la chiave vecchia, senza autenticazione, e' tolta
    assertThat(keystore.entries.getValue(ALIAS).authBound).isTrue()
    // Da qui ogni uso chiede il foglio, come per le cassaforti nuove.
    assertThat((vault.load(key.publicKey) as VaultResult.Done).value.publicKey).isEqualTo(key.publicKey)
    assertThat(sheet.shown).hasSize(2)
  }

  @Test
  fun migrazione_annullata_nienteCambia_eSiRiprovaAlGestoDopo() = runBlocking<Unit> {
    val key = FamilyKey.generate()
    val legacy = writeLegacyVault(key)

    sheet.answer = VaultRefusal.CANCELLED
    assertThat(vault.load(key.publicKey)).isEqualTo(VaultResult.Refused(VaultRefusal.CANCELLED))
    assertThat(file.readBytes()).isEqualTo(legacy)
    assertThat(keystore.entries).containsKey(LEGACY)

    sheet.answer = null
    assertThat(vault.load(key.publicKey)).isInstanceOf(VaultResult.Done::class.java)
    assertThat(file.readBytes()[4]).isEqualTo(2.toByte())
  }

  @Test
  fun migrazione_conUnFoglioCheMente_nonDaLaChiave_eNonCambiaNiente() = runBlocking<Unit> {
    // La chiave vecchia non vuole l'autenticazione: la prova e' la cifratura con quella nuova, che il Keystore fa solo dopo il foglio vero.
    val key = FamilyKey.generate()
    val legacy = writeLegacyVault(key)
    sheet.lie = true

    assertThat(vault.load(key.publicKey)).isEqualTo(VaultResult.Refused(VaultRefusal.UNAVAILABLE))
    assertThat(file.readBytes()).isEqualTo(legacy)
    assertThat(keystore.entries).containsKey(LEGACY)
  }

  @Test
  fun migrazione_impossibile_chiaveVecchiaSparita_ilKitELaVia_eIlFileResta() = runBlocking<Unit> {
    val key = FamilyKey.generate()
    val legacy = writeLegacyVault(key)
    keystore.entries.remove(LEGACY)

    assertThat(vault.load(key.publicKey)).isEqualTo(VaultResult.Refused(VaultRefusal.INVALIDATED))
    assertThat(file.readBytes()).isEqualTo(legacy)
  }

  @Test
  fun migrazione_conUnAltraChiave_nonScriveNiente() = runBlocking<Unit> {
    val legacy = writeLegacyVault(FamilyKey.generate())

    assertThat(vault.load(FamilyKey.generate().publicKey)).isEqualTo(VaultResult.Absent)
    assertThat(file.readBytes()).isEqualTo(legacy)
  }

  // --- Copia per tornare indietro (reset) -----------------------------------------------------------------

  @Test
  fun laCopiaRimetteLaCassaforteDiPrima_senzaFoglio() = runBlocking<Unit> {
    val old = FamilyKey.generate()
    vault.store(old)
    val copy = vault.keep()!!
    vault.store(FamilyKey.generate())
    val shown = sheet.shown.size

    copy.putBack()

    assertThat(sheet.shown).hasSize(shown)
    assertThat((vault.load(old.publicKey) as VaultResult.Done).value.publicKey).isEqualTo(old.publicKey)
  }

  @Test
  fun distruggere_toglieFileEChiavi_ancheQuellaVecchia() = runBlocking<Unit> {
    writeLegacyVault(FamilyKey.generate())
    vault.store(FamilyKey.generate())

    vault.destroy()

    assertThat(file.exists()).isFalse()
    assertThat(keystore.entries).isEmpty()
    assertThat(vault.keep()).isNull()
  }

  // --- Politica della chiave -------------------------------------------------------------------------------

  @Test
  fun politica_daAndroid11_aOgniUso_improntaForteOCredenziale() {
    for (sdk in listOf(30, 34, 36, 37)) {
      val policy = WrappingPolicy.forSdk(sdk)
      assertThat(policy.userAuthenticationRequired).isTrue()
      assertThat(policy.authTimeoutSeconds).isEqualTo(0)
      assertThat(policy.authTypes).isEqualTo(KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
      assertThat(policy.unlockedDeviceRequired).isTrue()
      assertThat(policy.invalidatedByBiometricEnrollment).isTrue()
    }
  }

  @Test
  fun politica_suAndroid10_aOgniUso_soloImprontaForte() {
    val policy = WrappingPolicy.forSdk(29)

    assertThat(policy.userAuthenticationRequired).isTrue()
    assertThat(policy.authTimeoutSeconds).isEqualTo(-1)
    assertThat(policy.authTypes).isEqualTo(KeyProperties.AUTH_BIOMETRIC_STRONG)
    assertThat(policy.unlockedDeviceRequired).isTrue()
  }

  // ---------------------------------------------------------------------------------------------------------

  /** Una cassaforte della versione 1 come la scriveva il codice di prima (chiave AES senza autenticazione). */
  private fun writeLegacyVault(key: FamilyKey): ByteArray {
    keystore.createLegacy(LEGACY)
    val cipher = keystore.encrypter(LEGACY)!!
    val pkcs8 = key.exportPkcs8()
    val plain = KeyMaterial.pack(key.publicKey, pkcs8).also { pkcs8.fill(0) }
    val sealed = keystore.finish(cipher, AndroidKeystoreFamilyKeyVault.HEADER_V1, plain, 0, plain.size)
    val bytes = AndroidKeystoreFamilyKeyVault.HEADER_V1 + cipher.iv + sealed
    file.writeBytes(bytes)
    return bytes
  }

  private companion object {
    const val ALIAS = "test.family-key-wrap.v2"
    const val LEGACY = "test.family-key-wrap.v1"
  }
}

/**
 * Il Keystore finto: AES-GCM in software, ma con la regola del Keystore vero per le chiavi "con autenticazione": il `Cipher` si prepara
 * (init) liberamente, l'operazione ([finish]) solo se un'autenticazione l'ha sbloccato, e una sola volta.
 */
internal class FakeVaultKeystore : VaultKeystore {
  class Entry(val key: SecretKey, val authBound: Boolean) {
    var invalidated: Boolean = false
  }

  val entries = LinkedHashMap<String, Entry>()
  var createFails: Exception? = null
  var finishes = 0
    private set
  var refused = 0
    private set

  private val issued = IdentityHashMap<Cipher, String>()
  private val unlocked: MutableSet<Cipher> = Collections.newSetFromMap(IdentityHashMap())

  fun createLegacy(alias: String) {
    entries[alias] = Entry(newKey(), authBound = false)
  }

  /** Quello che fa un'autenticazione riuscita su un `CryptoObject`: una sola operazione permessa a quel `Cipher`. */
  fun unlock(cipher: Cipher) {
    unlocked += cipher
  }

  override fun create(alias: String) {
    createFails?.let { throw it }
    entries[alias] = Entry(newKey(), authBound = true)
  }

  override fun encrypter(alias: String): Cipher? = cipher(alias) { key -> init(Cipher.ENCRYPT_MODE, key) }

  override fun decrypter(alias: String, iv: ByteArray): Cipher? = cipher(alias) { key -> init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }

  override fun finish(cipher: Cipher, aad: ByteArray, input: ByteArray, offset: Int, length: Int): ByteArray {
    val entry = issued[cipher]?.let(entries::get) ?: throw GeneralSecurityException("chiave sparita")
    if (entry.authBound && !unlocked.remove(cipher)) {
      refused++
      throw GeneralSecurityException("Key user not authenticated")
    }
    finishes++
    cipher.updateAAD(aad)
    return cipher.doFinal(input, offset, length)
  }

  override fun delete(alias: String) {
    entries.remove(alias)
  }

  private fun cipher(alias: String, init: Cipher.(SecretKey) -> Unit): Cipher? {
    val entry = entries[alias] ?: return null
    if (entry.invalidated) throw WrappingKeyInvalidated()
    return Cipher.getInstance("AES/GCM/NoPadding").apply { init(entry.key) }.also { issued[it] = alias }
  }

  private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}

/** Il foglio di sistema finto: conferma (sblocca il `Cipher` nel Keystore finto), annulla con [answer], o mente con [lie]. */
internal class FakeSheet(private val keystore: FakeVaultKeystore) : VaultAuthenticator {
  var answer: VaultRefusal? = null
  var lie = false
  val shown = ArrayList<FamilyKeyUse>()

  override suspend fun authenticate(cipher: Cipher, use: FamilyKeyUse): VaultAuth {
    shown += use
    answer?.let { return VaultAuth.Refused(it) }
    if (!lie) keystore.unlock(cipher)
    return VaultAuth.Authenticated(cipher)
  }
}
