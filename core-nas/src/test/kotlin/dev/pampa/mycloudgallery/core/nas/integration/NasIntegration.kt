package dev.pampa.mycloudgallery.core.nas.integration

import dev.pampa.mycloudgallery.core.network.contract.SocketBinding
import dev.pampa.mycloudgallery.core.network.testing.DirectSocketBinding
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.Properties
import kotlin.time.Duration
import org.junit.Assume.assumeTrue

/**
 * I test d'integrazione dal PC contro il NAS vero (docs/fase2-progetto.md §5.2, docs/fase4-progetto.md §5.4). Girano
 * solo con `gradlew :core-nas:testDebugUnitTest -PnasIntegration` (proprieta' di sistema `nasIntegration`, vedi
 * build.gradle); quelli autenticati solo se esiste il file delle credenziali scritto dall'utente, e sempre dietro la
 * guardia dell'area di prova ([TestAreaGuard]).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class NasIntegration

/** Il file delle credenziali: `%USERPROFILE%\.mycloudgallery\nas-it.properties`, mai nel repository ne' nei log. */
internal object NasIntegrationConfig {
  const val DEFAULT_HOST = "192.168.178.58"

  val enabled: Boolean get() = System.getProperty("nasIntegration") == "true"

  val credentialsFile: File get() = File(File(System.getProperty("user.home"), ".mycloudgallery"), "nas-it.properties")

  /** Salta (Assume) se l'integrazione non e' chiesta. */
  fun requireEnabled() = assumeTrue("integrazione col NAS non richiesta (-PnasIntegration)", enabled)

  /** Salta (Assume) se manca il file delle credenziali: nessun errore, nessun tentativo. */
  fun requireCredentials(): ItCredentials {
    requireEnabled()
    val file = credentialsFile
    assumeTrue("file delle credenziali assente: test autenticati saltati", file.isFile)
    val properties = Properties().apply { file.inputStream().use { load(it) } }
    val parsed = ItCredentials.parse(properties)
    assumeTrue("file delle credenziali incompleto (user, password, share)", parsed != null)
    return checkNotNull(parsed)
  }

  /** Dove i test scrivono cio' che hanno misurato (mai credenziali). */
  fun reportFile(name: String): File = File("build/nas-it", name).apply { parentFile?.mkdirs() }
}

/**
 * Le chiavi del file delle credenziali (§5.4). Le prime tre sono obbligatorie; le altre facoltative: senza, i test che
 * le usano risultano *saltati*. Nessuna e' **mai** stampata: [toString] e' neutro. La validazione (account e share
 * ammessi) la fa [TestAreaGuard] prima di qualunque connessione.
 *
 * ```properties
 * user=test                 # account di prova
 * password=...
 * share=test                # area privata di prova
 * host=192.168.178.58       # facoltativa
 * guid=                     # facoltativa: se manca si legge dalla NEGOTIATE
 * share2=test-comune        # area comune di prova: copia lato server tra share
 * m9.consent=yes            # SOLO con consenso esplicito: un tentativo con password sbagliata (M9)
 * user2=test2               # Fase 4: secondo account (privacy fra account, due "dispositivi")
 * password2=...
 * privateShare2=test2       # area privata del secondo account
 * chaos.rounds=400          # P15: giri del runner delle interruzioni (0 = non eseguire)
 * chaos.bigFileMb=200       # P15: dimensione del file grande del runner
 * ```
 */
internal class ItCredentials(
  val host: String,
  val user: String,
  val password: CharArray,
  val share: String,
  val share2: String?,
  val guid: String?,
  val wrongPasswordConsent: Boolean,
  val user2: String? = null,
  val password2: CharArray? = null,
  val privateShare2: String? = null,
  val chaosRounds: Int? = null,
  val chaosBigFileMb: Int? = null,
) {
  override fun toString(): String = "ItCredentials(nascoste)"

  companion object {
    /** Null se mancano le chiavi obbligatorie. I numeri malformati diventano `-1` (li rifiuta la guardia). */
    fun parse(properties: Properties): ItCredentials? {
      fun value(key: String): String? = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
      fun number(key: String): Int? = value(key)?.let { it.toIntOrNull() ?: -1 }
      val user = value("user") ?: return null
      val password = value("password") ?: return null
      val share = value("share") ?: return null
      return ItCredentials(
        host = value("host") ?: NasIntegrationConfig.DEFAULT_HOST,
        user = user,
        password = password.toCharArray(),
        share = share,
        share2 = value("share2"),
        guid = value("guid"),
        wrongPasswordConsent = value("m9.consent") == "yes",
        user2 = value("user2"),
        password2 = value("password2")?.toCharArray(),
        privateShare2 = value("privateShare2"),
        chaosRounds = number("chaos.rounds"),
        chaosBigFileMb = number("chaos.bigFileMb"),
      )
    }
  }
}

/** Il socket diretto dal PC (cablato), con il tempo della connect TCP misurato. */
internal class TimedDirectBinding(networkId: String = "pc-cablato") : SocketBinding {
  private val direct = DirectSocketBinding(networkId)
  override val networkId: String = direct.networkId

  @Volatile
  var lastConnectMs: Double = -1.0
    private set

  override fun connect(addr: InetAddress, port: Int, timeout: Duration): Socket {
    val started = System.nanoTime()
    return direct.connect(addr, port, timeout).also { lastConnectMs = (System.nanoTime() - started) / 1_000_000.0 }
  }
}
