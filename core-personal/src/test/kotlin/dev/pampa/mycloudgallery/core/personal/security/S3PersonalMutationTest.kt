package dev.pampa.mycloudgallery.core.personal.security

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.personal.NAS_NOW
import dev.pampa.mycloudgallery.core.personal.codec.PersonalSegments
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.flagKey
import dev.pampa.mycloudgallery.core.personal.mediaId
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import java.io.File
import kotlin.random.Random
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Fase 10 P11, voce **S3 (a)** per il codec dei **dati personali** (segmenti `personal`, docs/fase10-progetto.md §2.11; E-I14): le stesse
 * mutazioni del corpus di `core-trust` (bit, byte, valori interessanti, tagli, inserti, innesti), sul corpus del repository in
 * `tools/security/corpus/personal/`: 2.000 in `check.ps1`, **100.000** con `MCG_SECURITY_FULL=1`. L'impronta si ricalcola sui byte mutati.
 * Nessun `Throwable` fuori dal codec, meno di 2 s per segmento, e un segmento accettato si riscrive uguale byte per byte.
 * Il corpus si riscrive con `MCG_SECURITY_WRITE_CORPUS=1`.
 */
class S3PersonalMutationTest {

  private val root: File by lazy {
    var dir: File? = File("").absoluteFile
    while (dir != null && !File(dir, "settings.gradle").isFile) dir = dir.parentFile
    checkNotNull(dir)
  }
  private val corpusDir: File get() = File(root, "tools/security/corpus/personal")

  private fun entries(): List<List<PersonalEntry>> {
    val author = Base32.encode(ByteArray(16) { 1 })
    val fp = PersonalFingerprint(123_456_789L, QuickHash("ab".repeat(32)))
    return listOf(
      listOf(PersonalEntry(flagKey(mediaId(1), PersonalFields.FAVORITE), NAS_NOW, author, PersonalValue.Flag(true), fp)),
      listOf(
        PersonalEntry(flagKey(mediaId(2), PersonalFields.HIDDEN), NAS_NOW - 1, author, PersonalValue.Flag(false)),
        PersonalEntry(PersonalKey(PersonalKind.ALBUM, "album-1", PersonalFields.ALBUM_DEFINITION), NAS_NOW, author, PersonalValue.Text("Oslo 2026")),
        PersonalEntry(PersonalKey(PersonalKind.ALBUM, "album-1", PersonalFields.ALBUM_ORDER), NAS_NOW, author, PersonalValue.Number(-7)),
        PersonalEntry(PersonalKey(PersonalKind.ALBUM, "album-1", PersonalFields.albumItem(mediaId(3))), NAS_NOW, author, PersonalValue.Removed),
        PersonalEntry(PersonalKey(PersonalKind.MEMORY, "memory-2026-10", PersonalFields.MEMORY_SEEN), NAS_NOW, author, PersonalValue.Number(NAS_NOW)),
      ),
    )
  }

  @Test
  fun scriveIlCorpus() {
    assumeTrue("corpus riscritto solo con MCG_SECURITY_WRITE_CORPUS=1", System.getenv("MCG_SECURITY_WRITE_CORPUS") == "1")
    corpusDir.mkdirs()
    corpusDir.listFiles().orEmpty().forEach { it.delete() }
    entries().forEachIndexed { i, list -> File(corpusDir, "%03d.bin".format(i)).writeBytes(PersonalSegments.encode(list).bytes) }
  }

  @Test
  fun leMutazioniDelCodecPersonaleNonLoFannoCadere() {
    val corpus = corpusDir.listFiles { f -> f.name.endsWith(".bin") }.orEmpty().sortedBy { it.name }.map { it.readBytes() }
    check(corpus.isNotEmpty()) { "corpus personal vuoto: rigenerarlo con MCG_SECURITY_WRITE_CORPUS=1" }
    for ((i, bytes) in corpus.withIndex()) {
      assertWithMessage("corpus personal/$i").that(PersonalSegments.decode(bytes, ref(bytes)!!)).isInstanceOf(Verdict.Accepted::class.java)
    }
    val count = if (System.getenv("MCG_SECURITY_FULL") == "1") 100_000 else 2_000
    val random = Random(201)
    var accepted = 0
    var rejected = 0
    var skipped = 0
    var nonCanonical = 0
    var maxMs = 0.0
    val failures = ArrayList<String>()
    for (i in 0 until count) {
      val mutant = mutate(corpus[i % corpus.size], corpus, random)
      val ref = ref(mutant)
      if (ref == null) {
        skipped++
        continue
      }
      val started = System.nanoTime()
      try {
        when (val v = PersonalSegments.decode(mutant, ref)) {
          is Verdict.Accepted -> {
            accepted++
            if (!PersonalSegments.encode(v.value).bytes.contentEquals(mutant)) nonCanonical++
          }
          is Verdict.Rejected -> rejected++
        }
      } catch (t: Throwable) {
        failures += "personal #$i: ${t.javaClass.simpleName}"
        File("build/security/crashers/personal-$i.bin").apply { parentFile.mkdirs() }.writeBytes(mutant)
      }
      val ms = (System.nanoTime() - started) / 1e6
      maxMs = maxOf(maxMs, ms)
      if (ms > 2_000) failures += "personal #$i: ${ms.toLong()} ms"
    }
    println(
      "MCG-SEC S3a codec=personal mutazioni=$count accettate=$accepted rifiutate=$rejected saltate=$skipped crolli=${failures.size} " +
        "non_canonici=$nonCanonical ms_max=${"%.1f".format(java.util.Locale.ROOT, maxMs)}",
    )
    assertWithMessage(failures.take(10).joinToString("\n")).that(failures).isEmpty()
    assertWithMessage("accettati in una forma non canonica").that(nonCanonical).isEqualTo(0)
    assertWithMessage("le mutazioni arrivano al codec").that(rejected).isGreaterThan(count / 2)
  }

  private fun ref(bytes: ByteArray): SegmentRef? =
    if (bytes.isEmpty() || bytes.size > TrustLimits.MAX_SEGMENT_BYTES) null else SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong())

  /** Le stesse mutazioni di `core-trust` (`security/SecurityCorpus.kt`, che questo modulo non vede dai suoi test). */
  private fun mutate(original: ByteArray, others: List<ByteArray>, random: Random): ByteArray {
    if (original.isEmpty()) return random.nextBytes(8)
    val b = original.copyOf()
    val at = random.nextInt(b.size)
    return when (random.nextInt(11)) {
      0 -> b.also { it[at] = (it[at].toInt() xor (1 shl random.nextInt(8))).toByte() }
      1 -> b.also { repeat(2 + random.nextInt(7)) { _ -> val i = random.nextInt(it.size); it[i] = random.nextInt(256).toByte() } }
      2 -> b.also { it[at] = intArrayOf(0, 1, 0x7F, 0x80, 0xFF, 0x22, 0x5C, 0x7B)[random.nextInt(8)].toByte() }
      3 -> {
        val v = longArrayOf(0, 1, 0x7FFFFFFF, 0x80000000L, 0xFFFFFFFFL, b.size.toLong(), b.size + 1L)[random.nextInt(7)]
        val p = random.nextInt(maxOf(1, b.size - 3))
        b.also { for (k in 0 until minOf(4, it.size - p)) it[p + k] = (v ushr (24 - 8 * k)).toByte() }
      }
      4 -> b.copyOf(random.nextInt(b.size))
      5 -> b + random.nextBytes(1 + random.nextInt(64))
      6 -> b.copyOfRange(0, at) + random.nextBytes(1 + random.nextInt(32)) + b.copyOfRange(at, b.size)
      7 -> b.copyOfRange(0, at) + b.copyOfRange(minOf(b.size, at + 1 + random.nextInt(32)), b.size)
      8 -> others[random.nextInt(others.size)].let { o -> b.copyOfRange(0, at) + o.copyOfRange(random.nextInt(o.size), o.size) }
      9 -> {
        val len = 1 + random.nextInt(minOf(64, b.size - at))
        b.copyOfRange(0, at + len) + b.copyOfRange(at, at + len) + b.copyOfRange(at + len, b.size)
      }
      else -> b.also { for (k in at until minOf(it.size, at + 1 + random.nextInt(16))) it[k] = 0 }
    }
  }
}
