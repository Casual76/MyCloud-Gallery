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
 * I test d'integrazione dal PC contro il NAS vero (docs/fase2-progetto.md §5.2). Girano solo con
 * `gradlew :core-nas:testDebugUnitTest -PnasIntegration` (proprieta' di sistema `nasIntegration`, vedi
 * build.gradle); quelli autenticati solo se esiste il file delle credenziali scritto dall'utente.
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
    fun value(key: String): String? = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
    val user = value("user")
    val password = value("password")
    val share = value("share")
    assumeTrue("file delle credenziali incompleto (user, password, share)", user != null && password != null && share != null)
    return ItCredentials(
      host = value("host") ?: DEFAULT_HOST,
      user = checkNotNull(user),
      password = checkNotNull(password).toCharArray(),
      share = checkNotNull(share),
      share2 = value("share2"),
      guid = value("guid"),
      wrongPasswordConsent = value("m9.consent") == "yes",
    )
  }

  /** Dove i test scrivono cio' che hanno misurato (mai credenziali). */
  fun reportFile(name: String): File = File("build/nas-it", name).apply { parentFile?.mkdirs() }
}

/** Le credenziali di prova (solo account e share di prova, §5.4). [toString] non mostra niente. */
internal class ItCredentials(
  val host: String,
  val user: String,
  val password: CharArray,
  val share: String,
  val share2: String?,
  val guid: String?,
  val wrongPasswordConsent: Boolean,
) {
  override fun toString(): String = "ItCredentials(nascoste)"
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
