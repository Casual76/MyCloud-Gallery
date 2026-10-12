package dev.pampa.mycloudgallery.core.network.contract

import java.net.InetAddress
import java.net.Socket
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * L'indirizzo del NAS.
 *
 * **Sempre un IP letterale, mai un nome da risolvere** (E-R7): il DNS di una rete estranea potrebbe far
 * puntare `nas.fritz.box` a chiunque. [name] e' solo informativo (lo si mostra, non lo si usa per
 * connettersi). L'indirizzo non cambia mai da solo: un cambio si propone e si conferma (C4).
 *
 * Sta in `core-network` (e non in `core-nas`, come nella tabella del §1.2) perche' anche la sonda TCP e il
 * Wake-on-LAN di questo modulo lo usano, e `core-nas` dipende da `core-network`, non il contrario.
 */
class NasAddress(val ip: String, val name: String? = null, val port: Int = DEFAULT_PORT) {

  private val bytes: ByteArray = IpLiteral.parse(ip) ?: throw IllegalArgumentException("non e' un IP letterale")

  init {
    require(port in 1..MAX_PORT) { "porta non valida" }
    require(name == null || (name.isNotBlank() && name.length <= MAX_NAME_CHARS)) { "nome non valido" }
  }

  /** L'indirizzo per il socket: costruito dai byte, quindi senza nessuna risoluzione DNS. */
  fun toInetAddress(): InetAddress = InetAddress.getByAddress(bytes.copyOf())

  val isIpv6: Boolean get() = bytes.size == IPV6_BYTES

  override fun equals(other: Any?): Boolean =
    other is NasAddress && other.bytes.contentEquals(bytes) && other.port == port && other.name == name

  override fun hashCode(): Int = (bytes.contentHashCode() * 31 + port) * 31 + (name?.hashCode() ?: 0)

  /** Mai l'IP nei log (§4.2): solo la famiglia dell'indirizzo. */
  override fun toString(): String = if (isIpv6) "NasAddress(IPv6)" else "NasAddress(IPv4)"

  companion object {
    const val DEFAULT_PORT = 445
    private const val MAX_PORT = 65535
    private const val MAX_NAME_CHARS = 253
    private const val IPV6_BYTES = 16

    /** Null se [ip] non e' un IP letterale valido (per i campi digitati a mano). */
    fun parseOrNull(ip: String, name: String? = null, port: Int = DEFAULT_PORT): NasAddress? =
      try {
        NasAddress(ip.trim(), name, port)
      } catch (_: IllegalArgumentException) {
        null
      }
  }
}

/**
 * Lettura di un IP letterale in byte, senza passare da `InetAddress.getByName` (che con un nome farebbe una
 * richiesta DNS). IPv4 in forma decimale puntata; IPv6 nelle forme di RFC 4291 (con `::` e con l'IPv4 in coda),
 * senza zona (`%wlan0`).
 */
internal object IpLiteral {

  fun parse(text: String): ByteArray? = if (text.contains(':')) parseV6(text) else parseV4(text)

  private fun parseV4(text: String): ByteArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val out = ByteArray(4)
    for ((i, part) in parts.withIndex()) {
      if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
      if (part.length > 1 && part[0] == '0') return null // niente zeri iniziali (ambigui: ottale?)
      val value = part.toInt()
      if (value > 255) return null
      out[i] = value.toByte()
    }
    return out
  }

  private fun parseV6(text: String): ByteArray? {
    if (text.length > MAX_V6_CHARS || text.contains('%')) return null
    val doubleColon = text.indexOf("::")
    if (doubleColon >= 0 && text.indexOf("::", doubleColon + 1) >= 0) return null
    val all: List<Int> = if (doubleColon >= 0) {
      val head = words(text.substring(0, doubleColon), allowV4Tail = false) ?: return null
      val tail = words(text.substring(doubleColon + 2), allowV4Tail = true) ?: return null
      // "::" vale almeno un gruppo di zeri (RFC 4291).
      if (head.size + tail.size > V6_WORDS - 1) return null
      head + List(V6_WORDS - head.size - tail.size) { 0 } + tail
    } else {
      words(text, allowV4Tail = true)?.takeIf { it.size == V6_WORDS } ?: return null
    }
    val out = ByteArray(V6_WORDS * 2)
    for ((i, word) in all.withIndex()) {
      out[2 * i] = (word ushr 8).toByte()
      out[2 * i + 1] = word.toByte()
    }
    return out
  }

  /**
   * Le parole da 16 bit di una sequenza `a:b:c` (vuota = nessuna parola). Con [allowV4Tail] l'ultimo gruppo
   * puo' essere un IPv4 (`::ffff:1.2.3.4`), che vale due parole.
   */
  private fun words(text: String, allowV4Tail: Boolean): List<Int>? {
    if (text.isEmpty()) return emptyList()
    val groups = text.split(':')
    val out = ArrayList<Int>(V6_WORDS)
    for ((i, group) in groups.withIndex()) {
      if (group.contains('.')) {
        if (!allowV4Tail || i != groups.lastIndex) return null
        val v4 = parseV4(group) ?: return null
        out += ((v4[0].toInt() and 0xff) shl 8) or (v4[1].toInt() and 0xff)
        out += ((v4[2].toInt() and 0xff) shl 8) or (v4[3].toInt() and 0xff)
      } else {
        if (group.isEmpty() || group.length > 4) return null
        if (!group.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        out += group.toInt(16)
      }
    }
    return out
  }

  private const val MAX_V6_CHARS = 45
  private const val V6_WORDS = 8
}

/** Come si arriva al NAS su un percorso consentito. */
enum class Via {
  /** Wi-Fi di casa, senza VPN. */
  HOME_LAN,

  /** Wi-Fi di casa ma attraverso la VPN di casa accesa e non controllabile (piu' lento, con avviso). */
  HOME_LAN_THROUGH_VPN,

  /** Fuori casa, attraverso la VPN di casa. */
  VPN,
}

/** Perche' il NAS non si contatta da qui. Ogni motivo ha il suo messaggio (§3.4, riga "percorso negato"). */
enum class DenyReason {
  OFFLINE,
  CAPTIVE_PORTAL,

  /** Rete non riconosciuta (E-S10): nessuna connessione, nemmeno TCP. */
  UNTRUSTED_NETWORK,

  /** Fuori casa con la VPN spenta (E-R3). */
  VPN_OFF_AWAY,

  /** Una VPN che non e' quella di casa (E-S16). */
  FOREIGN_VPN,

  /** Android non permette all'app di usare la rete locale (target 37). */
  LOCAL_NETWORK_DENIED,

  /** Non c'e' ancora un'impronta di casa ne' un NAS configurato. */
  NOT_CONFIGURED,
}

/**
 * Il socket legato a una rete precisa (D1): Wi-Fi di casa o VPN, **mai la rete predefinita del processo**.
 * Se la VPN cade, il traffico non scivola sul Wi-Fi di una rete estranea.
 *
 * Su Android: `Network.socketFactory.createSocket()` non connesso, poi `connect(addr, timeout)`. Mai
 * `bindProcessToNetwork`, mai DNS.
 */
interface SocketBinding {
  /** La `Network` a cui il socket e' legato (opaco): se cambia, la sessione si invalida. */
  val networkId: String

  /** Un socket gia' connesso a [addr]:[port] entro [timeout]. Lancia `IOException` (anche `SocketTimeoutException`). */
  fun connect(addr: InetAddress, port: Int, timeout: Duration): Socket
}

/**
 * Il permesso di contattare il NAS. **Un `Allowed` lo crea solo `core-network`** (il costruttore e'
 * pubblico per i fake, ma nessun altro modulo di produzione ne costruisce uno): una connessione SMB si apre
 * solo da un percorso consentito.
 */
sealed interface NasRoute {
  class Allowed(val via: Via, val binding: SocketBinding) : NasRoute {
    override fun toString(): String = "Allowed($via)"
  }

  data class Denied(val reason: DenyReason) : NasRoute
}

/**
 * Il cancello di rete (P2): decide in ogni momento se e come si contatta il NAS.
 *
 * Una rete non riconosciuta da' `Denied` e **non parte nemmeno la connessione TCP** (§3.2, punto 4).
 */
interface NasRouteGate {
  /** Il percorso corrente, che cambia con la rete (un `NetworkCallback`). */
  val routes: StateFlow<NasRoute>

  /** Il percorso adesso (lo stesso valore di [routes], letto senza sospendere). */
  fun current(): NasRoute

  /**
   * Il percorso per l'onboarding (passo 2), prima che esista un'impronta di casa: il Wi-Fi da cui e' stato
   * letto [candidate], senza VPN. Fuori da quel caso e' `Denied`.
   */
  fun onboardingRoute(candidate: HomeCandidate): NasRoute
}
