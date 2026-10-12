package dev.pampa.mycloudgallery.core.network.link

import dev.pampa.mycloudgallery.core.network.contract.IpLiteral

/**
 * Un indirizzo IP come sequenza di byte (4 o 16), costruito **solo** da un letterale o da byte: mai da un nome,
 * quindi mai con una richiesta DNS (E-R7).
 *
 * Esiste per due motivi: i test a tabella costruiscono reti sintetiche senza toccare Android, e `toString` non
 * stampa l'indirizzo (§4.2: nessun IP nei log).
 *
 * Un IPv6 "mappato" (`::ffff:a.b.c.d`) diventa l'IPv4 corrispondente: due scritture dello stesso indirizzo sono
 * uguali.
 */
class IpAddr private constructor(private val bytes: ByteArray) {

  val isV4: Boolean get() = bytes.size == V4_BYTES
  val isV6: Boolean get() = bytes.size == V6_BYTES

  /** Il byte in posizione [index] senza segno (0..255). */
  fun byteAt(index: Int): Int = bytes[index].toInt() and 0xff

  fun toBytes(): ByteArray = bytes.copyOf()

  /** Un indirizzo locale unico, `fc00::/7` (RFC 4193): quello che il FRITZ!Box genera a caso per la propria rete. */
  val isUla: Boolean get() = isV6 && (byteAt(0) and 0xFE) == 0xFC

  /** `fe80::/10` oppure `169.254.0.0/16`. */
  val isLinkLocal: Boolean
    get() = if (isV6) byteAt(0) == 0xFE && (byteAt(1) and 0xC0) == 0x80 else byteAt(0) == 169 && byteAt(1) == 254

  /** Un IPv6 globale (`2000::/3`): l'indirizzo che il provider assegna, non quello locale. */
  val isGlobalV6: Boolean get() = isV6 && (byteAt(0) and 0xE0) == 0x20

  /** La forma testuale canonica: IPv4 puntato, IPv6 in minuscolo e compresso (RFC 5952). */
  fun toText(): String = if (isV4) bytes.joinToString(".") { (it.toInt() and 0xff).toString() } else v6Text()

  private fun v6Text(): String {
    val words = IntArray(V6_WORDS) { (byteAt(2 * it) shl 8) or byteAt(2 * it + 1) }
    // La sequenza piu' lunga (almeno due) di gruppi a zero, la prima in caso di parita'.
    var bestStart = -1
    var bestLen = 0
    var i = 0
    while (i < V6_WORDS) {
      if (words[i] != 0) {
        i++
        continue
      }
      var j = i
      while (j < V6_WORDS && words[j] == 0) j++
      if (j - i > bestLen) {
        bestStart = i
        bestLen = j - i
      }
      i = j
    }
    if (bestLen < 2) bestStart = -1
    val out = StringBuilder()
    var k = 0
    while (k < V6_WORDS) {
      if (k == bestStart) {
        out.append("::")
        k += bestLen
        continue
      }
      if (out.isNotEmpty() && !out.endsWith(":")) out.append(':')
      out.append(words[k].toString(HEX))
      k++
    }
    return out.toString()
  }

  override fun equals(other: Any?): Boolean = other is IpAddr && other.bytes.contentEquals(bytes)

  override fun hashCode(): Int = bytes.contentHashCode()

  /** Mai l'indirizzo: solo la famiglia (§4.2). */
  override fun toString(): String = if (isV4) "IpAddr(v4)" else "IpAddr(v6)"

  companion object {
    private const val V4_BYTES = 4
    private const val V6_BYTES = 16
    private const val V6_WORDS = 8
    private const val HEX = 16

    /** Null se [text] non e' un IP letterale (un nome, una zona `%wlan0`, una forma ambigua). */
    fun parse(text: String): IpAddr? = IpLiteral.parse(text.trim())?.let { fromBytes(it) }

    /** Null se [bytes] non sono 4 o 16. */
    fun fromBytes(bytes: ByteArray): IpAddr? =
      when (bytes.size) {
        V4_BYTES -> IpAddr(bytes.copyOf())
        V6_BYTES -> if (isV4Mapped(bytes)) IpAddr(bytes.copyOfRange(12, 16)) else IpAddr(bytes.copyOf())
        else -> null
      }

    private fun isV4Mapped(b: ByteArray): Boolean {
      for (i in 0 until 10) if (b[i].toInt() != 0) return false
      return (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff
    }
  }
}

/**
 * Un prefisso (`indirizzo/lunghezza`). Serve a due cose: dire se un indirizzo sta in una sottorete e riconoscere il
 * prefisso ULA /48 della rete di casa.
 */
class IpPrefix(address: IpAddr, val length: Int) {

  /** L'indirizzo di rete: i bit oltre [length] sono azzerati. */
  val network: IpAddr

  init {
    val max = if (address.isV4) V4_BITS else V6_BITS
    require(length in 0..max) { "lunghezza del prefisso non valida" }
    val masked = address.toBytes()
    for (bit in length until max) masked[bit / 8] = (masked[bit / 8].toInt() and (0x80 ushr (bit % 8)).inv()).toByte()
    network = IpAddr.fromBytes(masked) ?: error("indirizzo non valido")
  }

  val isV4: Boolean get() = network.isV4

  /** `0.0.0.0/0` o `::/0`: la rotta predefinita, che non dice niente su dove porta. */
  val isDefault: Boolean get() = length == 0

  fun contains(ip: IpAddr): Boolean {
    if (ip.isV4 != network.isV4) return false
    for (bit in 0 until length) {
      val mask = 0x80 ushr (bit % 8)
      if ((ip.byteAt(bit / 8) and mask) != (network.byteAt(bit / 8) and mask)) return false
    }
    return true
  }

  /** Vero se [other] sta tutto dentro questo prefisso. */
  fun contains(other: IpPrefix): Boolean = other.length >= length && contains(other.network)

  /** I due prefissi hanno almeno un indirizzo in comune. */
  fun overlaps(other: IpPrefix): Boolean = contains(other) || other.contains(this)

  /** La forma canonica, per esempio `fd12:3456:789a::/48` o `192.168.178.0/24`. */
  fun toText(): String = network.toText() + "/" + length

  override fun equals(other: Any?): Boolean = other is IpPrefix && other.length == length && other.network == network

  override fun hashCode(): Int = network.hashCode() * 31 + length

  override fun toString(): String = if (isV4) "IpPrefix(v4/$length)" else "IpPrefix(v6/$length)"

  companion object {
    private const val V4_BITS = 32
    private const val V6_BITS = 128

    /** La lunghezza del prefisso ULA che si memorizza come impronta forte (RFC 4193: 40 bit casuali + `fd00::/8`). */
    const val ULA_LENGTH = 48

    /** Null se [text] non e' `indirizzo/lunghezza` con un indirizzo letterale e una lunghezza valida. */
    fun parse(text: String): IpPrefix? {
      val slash = text.indexOf('/')
      if (slash <= 0 || slash == text.lastIndex) return null
      val address = IpAddr.parse(text.substring(0, slash)) ?: return null
      val digits = text.substring(slash + 1)
      if (digits.length > 3 || !digits.all { it in '0'..'9' }) return null
      val length = digits.toInt()
      val max = if (address.isV4) V4_BITS else V6_BITS
      if (length > max) return null
      return IpPrefix(address, length)
    }

    /** Il /48 di un indirizzo ULA; null se [ip] non e' un ULA. */
    fun ula48Of(ip: IpAddr): IpPrefix? = if (ip.isUla) IpPrefix(ip, ULA_LENGTH) else null
  }
}
