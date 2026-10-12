package dev.pampa.mycloudgallery.core.qr.contract

/**
 * La matrice di un QR: [size] x [size] moduli, vero = modulo scuro. E' il tipo con cui il contratto parla
 * al posto di `BitMatrix` di ZXing, cosi' ZXing resta un dettaglio di `core-qr` (`implementation`) e chi
 * disegna o verifica un QR non dipende dalla libreria.
 */
class QrBits(val size: Int, bits: BooleanArray) {

  private val bits: BooleanArray = bits.copyOf()

  init {
    require(size > 0 && bits.size == size * size) { "matrice non quadrata" }
  }

  operator fun get(x: Int, y: Int): Boolean = bits[y * size + x]

  override fun equals(other: Any?): Boolean = other is QrBits && other.size == size && other.bits.contentEquals(bits)

  override fun hashCode(): Int = 31 * size + bits.contentHashCode()
}

/**
 * Codifica e decodifica dei QR (P5: ZXing core). Su JVM, senza fotocamera: e' cio' che rende possibile la
 * prova "andata e ritorno" (codifica → rendering → decodifica con rotazione, scala e rumore).
 */
interface QrCodec {
  /** Il QR di [text] (correzione d'errore e margine decisi da chi implementa). */
  fun encode(text: String): QrBits

  /** Il testo del QR trovato in un'immagine in scala di grigi (un byte per pixel), o null. */
  fun decode(luma: ByteArray, width: Int, height: Int): String?
}

/** Perche' un QR letto non va bene: ogni motivo ha il suo messaggio sotto il mirino. */
enum class ScanRejection {
  /** Non e' un QR di MyCloud Gallery. */
  NOT_OURS,

  /** E' il QR di configurazione di WireGuard: contiene una chiave privata, va trattato come una password (E-S15). */
  WIREGUARD_SECRET,

  /** Di un'altra famiglia, o con un'impronta che non corrisponde (E-O5). */
  OTHER_FAMILY,

  /** Invito scaduto. */
  EXPIRED,

  /** Formato riconosciuto ma contenuto non valido. */
  MALFORMED,
}

/** Cosa fare del testo appena letto (lo decide chi usa lo scanner, con il proprio codec). */
sealed interface ScanVerdict {
  /** Valido: lo scanner si ferma. */
  data object Accept : ScanVerdict

  /** Non valido: lo scanner mostra il motivo e continua a cercare. */
  data class Reject(val reason: ScanRejection) : ScanVerdict

  /** Da ignorare in silenzio (lo stesso testo appena rifiutato): si continua. */
  data object Ignore : ScanVerdict
}
