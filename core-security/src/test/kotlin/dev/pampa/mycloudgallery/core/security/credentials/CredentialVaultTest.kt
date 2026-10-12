package dev.pampa.mycloudgallery.core.security.credentials

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.testing.FakeSecretKeyStore
import dev.pampa.mycloudgallery.core.security.testing.RecordingLogSink
import java.io.File
import java.io.IOException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * L'archivio delle credenziali con un Keystore finto e un DataStore vero su file temporaneo: copre il ciclo
 * normale, E-S9 (chiave invalidata: serve di nuovo la password), la differenza fra guasto passeggero e
 * invalidazione, la cancellazione sicura (prima la chiave, poi i dati) e l'assenza di segreti nei log.
 */
@RunWith(RobolectricTestRunner::class)
class CredentialVaultTest {

  @get:Rule
  val folder = TemporaryFolder()

  private lateinit var scope: CoroutineScope
  private lateinit var realStore: DataStore<Preferences>
  private lateinit var recordingStore: RecordingDataStore
  private lateinit var keys: FakeSecretKeyStore
  private lateinit var vault: CredentialVault
  private lateinit var logs: RecordingLogSink
  private lateinit var file: File

  /** Registra quando i dati vengono toccati, per verificare l'ordine "prima la chiave, poi i dati". */
  private inner class RecordingDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    override val data = delegate.data
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
      keys.events += "data-update"
      return delegate.updateData(transform)
    }
  }

  @Before
  fun setUp() {
    logs = RecordingLogSink().install()
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    file = File(folder.root, "credentials.preferences_pb")
    realStore = PreferenceDataStoreFactory.create(scope = scope) { file }
    keys = FakeSecretKeyStore()
    recordingStore = RecordingDataStore(realStore)
    vault = CredentialVault(keys, recordingStore, allowSoftwareKey = false)
  }

  @After
  fun tearDown() {
    logs.uninstall()
    scope.cancel()
  }

  private fun creds(user: String = "mamma", password: String = "la-mia-password-segretissima") =
    NasCredentials(user, password.toCharArray())

  private fun statusNow(): CredentialStatus = runBlocking { withTimeout(5_000) { vault.status.first() } }

  private fun load(slot: String = "nas"): LoadResult = runBlocking { vault.load(slot) }

  private fun save(slot: String = "nas", credentials: NasCredentials = creds()): SaveResult =
    runBlocking { credentials.use { vault.save(slot, it) } }

  // --- ciclo normale ------------------------------------------------------------------------------------

  @Test
  fun senzaNiente_ilVaultEVuoto() {
    assertThat(statusNow()).isEqualTo(CredentialStatus.EMPTY)
    assertThat(load()).isEqualTo(LoadResult.Absent)
    assertThat(runBlocking { vault.protectionLevel() }).isNull()
  }

  @Test
  fun salvaERilegge_econServaIlLivelloDellaChiave() {
    assertThat(save()).isEqualTo(SaveResult.Saved(KeyProtectionLevel.STRONGBOX))
    assertThat(statusNow()).isEqualTo(CredentialStatus.READY)

    val found = load() as LoadResult.Found
    found.credentials.use {
      assertThat(it.username).isEqualTo("mamma")
      assertThat(String(it.password.value)).isEqualTo("la-mia-password-segretissima")
    }
    assertThat(runBlocking { vault.protectionLevel() }).isEqualTo(KeyProtectionLevel.STRONGBOX)
  }

  @Test
  fun ilFileSuDiscoNonContieneLeCredenzialiInChiaro() {
    save(credentials = creds(user = "utente-riconoscibile", password = "password-riconoscibile"))
    val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
    assertThat(bytes).doesNotContain("utente-riconoscibile")
    assertThat(bytes).doesNotContain("password-riconoscibile")
    // e nemmeno la loro codifica base64 diretta
    assertThat(bytes).doesNotContain(java.util.Base64.getEncoder().encodeToString("password-riconoscibile".toByteArray()))
  }

  @Test
  fun salvareDueVolte_sostituisce_eUsaSempreLaStessaChiave() {
    save(credentials = creds(password = "prima"))
    save(credentials = creds(password = "seconda"))
    assertThat(keys.events.count { it == "create" }).isEqualTo(1)
    (load() as LoadResult.Found).credentials.use { assertThat(String(it.password.value)).isEqualTo("seconda") }
  }

  @Test
  fun voci_diverse_sonoIndipendenti_eRemoveCancellaSoloLaSua() {
    save("nas-a", creds(user = "a"))
    save("nas-b", creds(user = "b"))
    runBlocking { vault.remove("nas-a") }
    assertThat(load("nas-a")).isEqualTo(LoadResult.Absent)
    (load("nas-b") as LoadResult.Found).credentials.use { assertThat(it.username).isEqualTo("b") }
  }

  @Test
  fun nomiDiVoceNonValidi_sonoRifiutati() {
    for (bad in listOf("", "Nas", "../x", "a b", "x".repeat(65), "a/b", "a\\b", "nas\n")) {
      assertThrows("slot '$bad'", IllegalArgumentException::class.java) { load(bad) }
    }
  }

  @Test
  fun unBloccoCopiatoInUnAltraVoce_nonSiApre() {
    save("nas-a", creds(user = "a"))
    runBlocking {
      realStore.edit { prefs ->
        prefs[CredentialVault.blobKey("nas-b")] = prefs[CredentialVault.blobKey("nas-a")]!!
      }
    }
    assertThat(load("nas-b")).isEqualTo(LoadResult.NeedsPassword)
    // la voce originale era a posto, e il blocco rifiutato era danneggiato solo per "nas-b"
    assertThat(statusNow()).isEqualTo(CredentialStatus.NEEDS_PASSWORD)
  }

  // --- E-S9: chiave invalidata o sparita ------------------------------------------------------------------

  @Test
  fun chiaveSparita_serveDiNuovoLaPassword_eSiRiparte() {
    save()
    keys.loseAllKeys() // ripristino del telefono: i dati ci sono, la chiave no

    assertThat(load()).isEqualTo(LoadResult.NeedsPassword)
    assertThat(statusNow()).isEqualTo(CredentialStatus.NEEDS_PASSWORD)
    // le credenziali inutilizzabili sono state buttate, e la richiesta di password resta finche' non si salva
    assertThat(load()).isEqualTo(LoadResult.NeedsPassword)
    assertThat(runBlocking { vault.verify() }).isEqualTo(CredentialStatus.NEEDS_PASSWORD)

    // l'utente reinserisce la password: nuova chiave, tutto di nuovo a posto
    assertThat(save(credentials = creds(password = "nuova"))).isInstanceOf(SaveResult.Saved::class.java)
    assertThat(statusNow()).isEqualTo(CredentialStatus.READY)
    (load() as LoadResult.Found).credentials.use { assertThat(String(it.password.value)).isEqualTo("nuova") }
  }

  @Test
  fun chiaveNonRecuperabile_serveDiNuovoLaPassword_eLaChiaveInutileVieneDistrutta() {
    save()
    keys.findFailure = UnrecoverableKeyException("Failed to obtain information about key")

    assertThat(load()).isEqualTo(LoadResult.NeedsPassword)
    keys.findFailure = null
    assertThat(keys.keys).isEmpty()
    assertThat(statusNow()).isEqualTo(CredentialStatus.NEEDS_PASSWORD)
  }

  @Test
  fun chiaveInvalidataMentreSiCifra_siCreaUnaChiaveNuova_eIlSalvataggioRiesce() {
    val good = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    var created = 0
    keys.keyFactory = { if (created++ == 0) InvalidatedKey() else good }

    val result = save()

    assertThat(result).isInstanceOf(SaveResult.Saved::class.java)
    assertThat(created).isEqualTo(2)
    (load() as LoadResult.Found).credentials.use { assertThat(it.username).isEqualTo("mamma") }
  }

  /** Una chiave che al primo uso dice "invalidata", come fa il Keystore dopo un evento che la rende inutilizzabile. */
  private class InvalidatedKey : SecretKey {
    override fun getAlgorithm() = "AES"
    override fun getFormat() = "RAW"
    override fun getEncoded(): ByteArray = throw KeyPermanentlyInvalidatedException()
  }

  // --- guasto passeggero: non si cancella niente --------------------------------------------------------------

  @Test
  fun keystoreOccupato_inLettura_nonCancellaNiente() {
    save()
    keys.findFailure = KeyStoreException("Keystore occupato")

    assertThat(load()).isEqualTo(LoadResult.Unavailable)
    assertThat(statusNow()).isEqualTo(CredentialStatus.READY)

    keys.findFailure = null
    (load() as LoadResult.Found).credentials.use { assertThat(it.username).isEqualTo("mamma") }
  }

  @Test
  fun keystoreOccupato_inScrittura_nonSalva_eNonCancellaLeCredenzialiBuone() {
    save(credentials = creds(password = "buona"))
    keys.findFailure = KeyStoreException("Keystore occupato")

    assertThat(save(credentials = creds(password = "nuova"))).isEqualTo(SaveResult.Unavailable)

    keys.findFailure = null
    (load() as LoadResult.Found).credentials.use { assertThat(String(it.password.value)).isEqualTo("buona") }
  }

  @Test
  fun creazioneDellaChiaveFallita_nonSalvaENonLasciaNiente() {
    keys.createFailure = IllegalStateException("tee guasto")
    assertThat(save()).isEqualTo(SaveResult.Unavailable)
    assertThat(statusNow()).isEqualTo(CredentialStatus.EMPTY)
  }

  @Test
  fun bloccoDanneggiato_serveLaPassword_maLaChiaveRestaPerLeAltreVoci() {
    save("nas-a", creds(user = "a"))
    save("nas-b", creds(user = "b"))
    runBlocking { realStore.edit { it[CredentialVault.blobKey("nas-a")] = "AAAA-non-base64-valido!" } }

    assertThat(load("nas-a")).isEqualTo(LoadResult.NeedsPassword)
    assertThat(keys.keys).isNotEmpty()
    (load("nas-b") as LoadResult.Found).credentials.use { assertThat(it.username).isEqualTo("b") }
  }

  // --- hardware obbligatorio --------------------------------------------------------------------------------

  @Test
  fun chiaveSoftware_inRelease_nonSalva_eLaDistrugge() {
    keys.level = KeyProtectionLevel.SOFTWARE
    assertThat(save()).isEqualTo(SaveResult.HardwareKeyUnavailable)
    assertThat(keys.keys).isEmpty()
    assertThat(statusNow()).isEqualTo(CredentialStatus.EMPTY)
  }

  @Test
  fun chiaveSoftware_inDebug_sipuoAmmettere() {
    keys.level = KeyProtectionLevel.SOFTWARE
    val debugVault = CredentialVault(keys, recordingStore, allowSoftwareKey = true)
    val result = runBlocking { creds().use { debugVault.save("nas", it) } }
    assertThat(result).isEqualTo(SaveResult.Saved(KeyProtectionLevel.SOFTWARE))
  }

  @Test
  fun livelloSconosciuto_nonBloccaIlSalvataggio() {
    keys.level = KeyProtectionLevel.UNKNOWN
    assertThat(save()).isEqualTo(SaveResult.Saved(KeyProtectionLevel.UNKNOWN))
  }

  // --- cancellazione sicura ---------------------------------------------------------------------------------

  @Test
  fun wipeAll_distruggePrimaLaChiave_poiIDati() {
    save()
    keys.events.clear()

    val result = runBlocking { vault.wipeAll() }

    assertThat(result).isEqualTo(WipeResult(keyDestroyed = true, dataCleared = true))
    assertThat(result.complete).isTrue()
    assertThat(keys.events).containsExactly("delete-key", "data-update").inOrder()
    assertThat(keys.keys).isEmpty()
    assertThat(statusNow()).isEqualTo(CredentialStatus.EMPTY)
    assertThat(load()).isEqualTo(LoadResult.Absent)
  }

  @Test
  fun wipeAll_cancellaIDatiAncheSeLaChiaveNonRisponde_eLoDice() {
    save()
    keys.deleteFailure = KeyStoreException("Keystore occupato")

    val result = runBlocking { vault.wipeAll() }

    assertThat(result.keyDestroyed).isFalse()
    assertThat(result.dataCleared).isTrue()
    assertThat(result.complete).isFalse()
    assertThat(statusNow()).isEqualTo(CredentialStatus.EMPTY)

    // si riprova e stavolta va
    keys.deleteFailure = null
    assertThat(runBlocking { vault.wipeAll() }.complete).isTrue()
  }

  @Test
  fun wipeAll_nonDaPerDistruttaUnaChiaveCheRestaViva() {
    save()
    keys.deleteDoesNothing = true
    assertThat(runBlocking { vault.wipeAll() }.keyDestroyed).isFalse()
  }

  @Test
  fun wipeAll_nonSiInterrompeSeIlChiamanteVieneAnnullato() {
    save()
    runBlocking {
      val job = launch(start = CoroutineStart.UNDISPATCHED) { vault.wipeAll() }
      job.cancel()
      job.join()
    }
    assertThat(keys.keys).isEmpty()
    assertThat(statusNow()).isEqualTo(CredentialStatus.EMPTY)
  }

  @Test
  fun wipeAll_inUnVaultVuoto_vaBene() {
    assertThat(runBlocking { vault.wipeAll() }.complete).isTrue()
  }

  // --- verify, concorrenza --------------------------------------------------------------------------------------

  @Test
  fun verify_confermaOScopreLaChiaveSparita() {
    assertThat(runBlocking { vault.verify() }).isEqualTo(CredentialStatus.EMPTY)
    save()
    assertThat(runBlocking { vault.verify() }).isEqualTo(CredentialStatus.READY)
    keys.loseAllKeys()
    assertThat(runBlocking { vault.verify() }).isEqualTo(CredentialStatus.NEEDS_PASSWORD)
  }

  @Test
  fun verify_conKeystoreOccupato_diceNonDisponibile_enonCancella() {
    save()
    keys.findFailure = KeyStoreException("occupato")
    assertThat(runBlocking { vault.verify() }).isEqualTo(CredentialStatus.UNAVAILABLE)
    keys.findFailure = null
    assertThat(statusNow()).isEqualTo(CredentialStatus.READY)
  }

  @Test
  fun salvataggiConcorrenti_creanoUnaSolaChiave() {
    runBlocking {
      (1..20).map { i -> async(Dispatchers.Default) { creds(user = "u$i").use { vault.save("nas", it) } } }.awaitAll()
    }
    assertThat(keys.events.count { it == "create" }).isEqualTo(1)
    assertThat(load()).isInstanceOf(LoadResult.Found::class.java)
  }

  @Test
  fun lArchivioNonScrivibile_eUnGuastoPasseggero() {
    val broken = object : DataStore<Preferences> {
      override val data = realStore.data
      override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        throw IOException("disco pieno")
    }
    val brokenVault = CredentialVault(keys, broken)
    assertThat(runBlocking { creds().use { brokenVault.save("nas", it) } }).isEqualTo(SaveResult.Unavailable)
  }

  // --- niente segreti nei log -------------------------------------------------------------------------------------

  @Test
  fun neiLogNonCompaionoMaiUtentePasswordNeByte() {
    val secret = "PASSWORD-DA-NON-LOGGARE-123"
    val user = "UTENTE-DA-NON-LOGGARE"

    // percorso felice, guasti passeggeri, invalidazione, blocco danneggiato, wipe
    save(credentials = creds(user, secret))
    load()
    keys.findFailure = KeyStoreException("occupato $secret")
    load()
    save(credentials = creds(user, secret))
    keys.findFailure = null
    keys.loseAllKeys()
    load()
    keys.createFailure = IllegalStateException("guasto")
    save(credentials = creds(user, secret))
    keys.createFailure = null
    keys.level = KeyProtectionLevel.SOFTWARE
    save(credentials = creds(user, secret))
    keys.level = KeyProtectionLevel.STRONGBOX
    save(credentials = creds(user, secret))
    runBlocking { realStore.edit { it[CredentialVault.blobKey("nas")] = "spazzatura" } }
    load()
    runBlocking { vault.wipeAll() }

    // Il messaggio dell'eccezione "occupato $secret" e' un'eccezione dell'ambiente di prova, non del vault:
    // il vault non deve MAI stampare il messaggio, solo la classe. Se compare, e' un bug.
    assertThat(logs.lines).isNotEmpty()
    assertThat(logs.everything).doesNotContain(secret)
    assertThat(logs.everything).doesNotContain(user)
  }
}
