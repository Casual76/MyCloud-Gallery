package dev.pampa.mycloudgallery.core.trust.security

import java.io.File
import kotlin.random.Random

// Il corpus e le mutazioni della voce S3 (a) della revisione finale (docs/fase10-progetto.md §2.11, D36). Il corpus sta nel repository, in
// `tools/security/corpus/<codec>/`: file validi scritti dagli stessi codec dell'app (li riscrive `S3CorpusGenerator` con
// `MCG_SECURITY_WRITE_CORPUS=1`). Le mutazioni partono da quei file; un caso che fa cadere un codec si salva in `build/security/crashers/`
// del modulo per diventare un caso nuovo del corpus.

/** Dove sta il corpus e dove vanno i casi che rompono. */
internal object SecurityCorpus {

  /** La radice del repository: la prima cartella, salendo, con `settings.gradle`. */
  val root: File by lazy {
    var dir: File? = File("").absoluteFile
    while (dir != null && !File(dir, "settings.gradle").isFile) dir = dir.parentFile
    checkNotNull(dir) { "radice del repository non trovata" }
  }

  fun dir(codec: String): File = File(root, "tools/security/corpus/$codec")

  /** I file del corpus di [codec], in ordine di nome (i file `*.bin`; il contesto, se serve, in `contesto.txt`). */
  fun read(codec: String): List<ByteArray> {
    val files = dir(codec).listFiles { f -> f.isFile && f.name.endsWith(".bin") }.orEmpty().sortedBy { it.name }
    check(files.isNotEmpty()) { "corpus di $codec vuoto: rigenerarlo con MCG_SECURITY_WRITE_CORPUS=1 (S3CorpusGenerator)" }
    return files.map { it.readBytes() }
  }

  /** Le righe `chiave=valore` del contesto di [codec]. */
  fun context(codec: String): Map<String, String> =
    File(dir(codec), "contesto.txt").readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

  fun write(codec: String, entries: List<ByteArray>, context: Map<String, String> = emptyMap()) {
    val dir = dir(codec)
    dir.mkdirs()
    dir.listFiles().orEmpty().forEach { it.delete() }
    entries.forEachIndexed { i, bytes -> File(dir, "%03d.bin".format(i)).writeBytes(bytes) }
    if (context.isNotEmpty()) File(dir, "contesto.txt").writeText(context.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
  }

  fun saveCrasher(codec: String, index: Int, bytes: ByteArray): File =
    File("build/security/crashers/$codec-$index.bin").apply { parentFile.mkdirs(); writeBytes(bytes) }

  /** Quante mutazioni per codec: 2.000 in `check.ps1`, **100.000** con `MCG_SECURITY_FULL=1` (`run-all.ps1 -Livello jvm`). */
  val mutationsPerCodec: Int get() = if (System.getenv("MCG_SECURITY_FULL") == "1") 100_000 else 2_000

  val full: Boolean get() = System.getenv("MCG_SECURITY_FULL") == "1"
}

/**
 * Le mutazioni: bit, byte, valori "interessanti" a 8 e 32 bit (zero, massimi, segno, lunghezze vicine al vero), tagli, aggiunte,
 * inserti, buchi, ripetizioni, innesti da un altro file del corpus e azzeramenti. Deterministiche dato il seme.
 */
internal class Mutator(seed: Long) {
  private val random = Random(seed)

  private val bytes8 = intArrayOf(0x00, 0x01, 0x7F, 0x80, 0xFF, 0x22, 0x5C, 0x2F, 0x2E, 0x3A, 0x7B, 0x5B)

  fun mutate(original: ByteArray, others: List<ByteArray>): ByteArray {
    var b = original
    repeat(1 + if (random.nextInt(4) == 0) random.nextInt(4) else 0) { b = once(b, others) }
    return b
  }

  private fun once(original: ByteArray, others: List<ByteArray>): ByteArray {
    if (original.isEmpty()) return random.nextBytes(1 + random.nextInt(16))
    val b = original.copyOf()
    val at = random.nextInt(b.size)
    return when (random.nextInt(12)) {
      0 -> b.also { it[at] = (it[at].toInt() xor (1 shl random.nextInt(8))).toByte() }
      1 -> b.also { repeat(2 + random.nextInt(7)) { _ -> val i = random.nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl random.nextInt(8))).toByte() } }
      2 -> b.also { it[at] = random.nextInt(256).toByte() }
      3 -> b.also { it[at] = bytes8[random.nextInt(bytes8.size)].toByte() }
      4 -> {
        val values = longArrayOf(0, 1, 0x7FFFFFFF, 0x80000000L, 0xFFFFFFFFL, 0x10000, b.size.toLong(), b.size + 1L, b.size - 1L, 65_535)
        val v = values[random.nextInt(values.size)]
        val p = random.nextInt(maxOf(1, b.size - 3))
        b.also { for (k in 0 until minOf(4, it.size - p)) it[p + k] = (v ushr (24 - 8 * k)).toByte() }
      }
      5 -> b.copyOf(random.nextInt(b.size))
      6 -> b + random.nextBytes(1 + random.nextInt(64))
      7 -> b.copyOfRange(0, at) + random.nextBytes(1 + random.nextInt(32)) + b.copyOfRange(at, b.size)
      8 -> b.copyOfRange(0, at) + b.copyOfRange(minOf(b.size, at + 1 + random.nextInt(32)), b.size)
      9 -> {
        val len = 1 + random.nextInt(minOf(64, b.size - at))
        b.copyOfRange(0, at + len) + b.copyOfRange(at, at + len) + b.copyOfRange(at + len, b.size)
      }
      10 -> {
        val other = others[random.nextInt(others.size)]
        if (other.isEmpty()) b else b.copyOfRange(0, at) + other.copyOfRange(random.nextInt(other.size), other.size)
      }
      else -> b.also { for (k in at until minOf(it.size, at + 1 + random.nextInt(16))) it[k] = 0 }
    }
  }
}

/** I conti di un codec sotto mutazione (il resoconto ha solo numeri). */
internal class MutationTally(val codec: String) {
  var mutants = 0
  var accepted = 0
  var rejected = 0
  var skipped = 0
  var maxMillis = 0.0
  val failures = ArrayList<String>()
  val reasons = HashMap<String, Int>()

  fun line(): String =
    "MCG-SEC S3a codec=$codec mutazioni=$mutants accettate=$accepted rifiutate=$rejected saltate=$skipped crolli=${failures.size} " +
      "ms_max=${"%.1f".format(java.util.Locale.ROOT, maxMillis)}"
}

/**
 * Fa girare [decode] su [count] mutazioni del corpus [corpus] di [codec]. Un `Throwable` qualunque che esce dal codec (anche un
 * `OutOfMemoryError` o uno `StackOverflowError`) o una decodifica oltre [maxMillis] e' un crollo: si salva il caso e si continua.
 * [decode] risponde `null` se il codec accetta, il motivo del rifiuto come stringa, oppure lancia [Skip] per una mutazione non applicabile.
 */
internal fun mutateAndDecode(
  codec: String,
  corpus: List<ByteArray>,
  count: Int,
  seed: Long,
  maxMillis: Double = 2_000.0,
  decode: (ByteArray) -> String?,
): MutationTally {
  val tally = MutationTally(codec)
  val mutator = Mutator(seed)
  for (i in 0 until count) {
    val original = corpus[i % corpus.size]
    val mutant = mutator.mutate(original, corpus)
    tally.mutants++
    val started = System.nanoTime()
    try {
      when (val reason = decode(mutant)) {
        null -> tally.accepted++
        else -> {
          tally.rejected++
          tally.reasons.merge(reason, 1, Int::plus)
        }
      }
    } catch (s: Skip) {
      tally.skipped++
    } catch (t: Throwable) {
      tally.failures += "$codec #$i: ${t.javaClass.simpleName} (${SecurityCorpus.saveCrasher(codec, i, mutant).path})"
    }
    val ms = (System.nanoTime() - started) / 1e6
    if (ms > tally.maxMillis) tally.maxMillis = ms
    if (ms > maxMillis) tally.failures += "$codec #$i: ${"%.0f".format(java.util.Locale.ROOT, ms)} ms (${SecurityCorpus.saveCrasher(codec, i, mutant).path})"
  }
  return tally
}

/** Una mutazione che il banco non sa presentare al codec (per esempio un file vuoto, che nessuna testa puo' elencare). */
internal class Skip : RuntimeException()
