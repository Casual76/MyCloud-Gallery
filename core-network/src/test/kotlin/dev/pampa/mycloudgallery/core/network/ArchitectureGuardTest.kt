package dev.pampa.mycloudgallery.core.network

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Guardie sul codice di produzione di questo modulo, dalle regole di D1 (docs/fase2-progetto.md) e di
 * docs/rete-casa-vpn.md: scrivono nel codice cio' che non si deve MAI fare, cosi' una modifica futura che lo faccia
 * rompe un test invece di passare in silenzio.
 */
class ArchitectureGuardTest {

  /** Il codice di produzione senza commenti ne' stringhe (i commenti dicono "mai bindProcessToNetwork"). */
  private fun productionCode(): Map<String, String> {
    val root = listOf(File("src/main/kotlin"), File("core-network/src/main/kotlin")).first { it.isDirectory }
    return root.walkTopDown()
      .filter { it.isFile && it.extension == "kt" && "/testing/" !in it.invariantSeparatorsPath }
      .associate { it.invariantSeparatorsPath.substringAfter("core/network/") to stripCommentsAndStrings(it.readText()) }
  }

  private fun stripCommentsAndStrings(text: String): String {
    var s = text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
    s = s.replace(Regex("//[^\\n]*"), "")
    s = s.replace(Regex("\"\"\"[\\s\\S]*?\"\"\""), "\"\"")
    s = s.replace(Regex("\"(\\\\.|[^\"\\\\\\n])*\""), "\"\"")
    return s
  }

  private fun offenders(pattern: Regex, allowedIn: Set<String> = emptySet()): List<String> =
    productionCode().filter { (file, code) -> file !in allowedIn && pattern.containsMatchIn(code) }.keys.toList()

  @Test
  fun maiBindProcessToNetwork() {
    assertThat(offenders(Regex("bindProcessToNetwork"))).isEmpty()
  }

  @Test
  fun maiRisoluzioneDnsSullaReteDiDefault() {
    // `InetAddress.getByName/getAllByName` risolverebbero un nome con il DNS della rete predefinita (E-R7).
    assertThat(offenders(Regex("InetAddress\\s*\\.\\s*(getByName|getAllByName)"))).isEmpty()
    // `Network.getAllByName` (il DNS di UNA rete) e' ammesso solo nella scoperta dell'onboarding.
    assertThat(offenders(Regex("\\bgetAllByName\\s*\\("), allowedIn = setOf("discovery/NsdNasDiscovery.kt"))).isEmpty()
    assertThat(offenders(Regex("\\bgetByName\\s*\\("))).isEmpty()
  }

  @Test
  fun iSocketNascono_dallaFabbricaDellaRete_nonConnessi() {
    // `Socket(host, port)` e `createSocket(host, port)` si connetterebbero (e risolverebbero) subito.
    assertThat(offenders(Regex("\\bSocket\\s*\\(\\s*[^)\\s]"))).isEmpty()
    assertThat(offenders(Regex("createSocket\\s*\\(\\s*[^)\\s]"))).isEmpty()
  }

  @Test
  fun nessunPacchettoWolSenzaLegameAllaRete() {
    // L'unico `DatagramSocket` deve passare da `network.bindSocket`.
    val users = productionCode().filter { (_, code) -> code.contains("DatagramSocket(") }
    assertThat(users.keys).containsExactly("wol/WakeOnLan.kt")
    assertThat(users.getValue("wol/WakeOnLan.kt")).contains("bindSocket(")
  }

  @Test
  fun soloIlCancelloCreaIPercorsiConsentiti() {
    // `NasRoute.Allowed(` si costruisce solo nel cancello (il costruttore e' pubblico per i fake in `testing`).
    assertThat(offenders(Regex("NasRoute\\.Allowed\\s*\\("), allowedIn = setOf("gate/DefaultNasRouteGate.kt"))).isEmpty()
  }

  @Test
  fun nessunLogDiIndirizziNelCodice() {
    // Nessun uso del logger con interpolazioni di indirizzi: il modulo non logga, e se un giorno lo fara' non
    // dovra' stampare IP (docs/fase2-progetto.md 4.2).
    assertThat(offenders(Regex("GalleryLog\\.[a-z]\\("))).isEmpty()
    assertThat(offenders(Regex("\\b(println|Log\\.[dviwe])\\s*\\("))).isEmpty()
  }
}
