package dev.pampa.mycloudgallery.tools.release

import dev.pampa.mycloudgallery.core.release.codec.EcdsaP256
import dev.pampa.mycloudgallery.core.release.codec.ManifestCodec
import dev.pampa.mycloudgallery.core.release.codec.SignedManifestVerifier
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel
import dev.pampa.mycloudgallery.core.release.contract.ReleaseKeyIds
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.Verified
import dev.pampa.mycloudgallery.core.release.keys.ReleaseKeys
import dev.pampa.mycloudgallery.core.release.keys.ReleaseTrust
import dev.pampa.mycloudgallery.core.release.state.ReleaseRules
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestKeys
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestSigner
import dev.pampa.mycloudgallery.core.release.testkeys.ReleaseTestPrivateKey
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.PrivateKey
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.system.exitProcess

// LO STRUMENTO DEL MANIFEST (docs/fase10-progetto.md §2.8, D29-D31; P1). Gira sul PC dell'admin, mai nell'app (`tools/check.ps1` passo 7b);
// si compila con i test di core-release (stesso codec e stesso verificatore dell'app) e si lancia con `tools\release\manifest-tool.ps1`.
//   keys    genera K1 e K2 (P-256) in un PKCS#12 con frase segreta, stampa le righe per `ReleaseKeys` e `docs/sicurezza.md` e il foglio di K2
//   sign    firma `release/manifest.json` (K1, o K2 con --key k2; KT con --test-key) con un `seq` che solo cresce, RIVERIFICA la busta con il
//           verificatore dell'app e le chiavi fissate, e solo allora scrive `manifest.mcgr`
//   verify  verifica una busta con le chiavi fissate (e KT con --test-key) e la racconta
//   seq     dice l'ultimo `seq` firmato e il prossimo
//   restore ricostruisce K2 dal foglio offline (D31, E-REL8)
// Gli agenti firmano solo con KT (--test-key), mai con K1 o K2.

/** Ingresso e uscita dello strumento: la console vera, o quella finta dei test. */
internal interface ToolIo {
  fun out(line: String)

  fun err(line: String)

  /** Una frase segreta o un dato da non mostrare (non ripetuto sullo schermo); null se non c'e' chi risponde. */
  fun secret(prompt: String): CharArray?
}

internal class ManifestTool(private val io: ToolIo, private val clock: Clock = Clock.systemUTC(), private val home: File = File(System.getProperty("user.home"))) {

  private class Args(list: List<String>) {
    val flags = HashSet<String>()
    val values = HashMap<String, String>()

    init {
      var i = 0
      while (i < list.size) {
        val arg = list[i]
        require(arg.startsWith("--")) { "Argomento inatteso: $arg" }
        if (arg in FLAGS) flags += arg else {
          require(i + 1 < list.size) { "Manca il valore di $arg" }
          values[arg] = list[++i]
        }
        i++
      }
    }

    fun file(name: String): File? = values[name]?.let(::File)
  }

  fun run(argv: List<String>): Int {
    if (argv.isEmpty() || argv[0] in setOf("-h", "--help", "help")) return usage(0)
    return try {
      val args = Args(argv.drop(1))
      when (argv[0]) {
        "keys" -> keys(args)
        "sign" -> sign(args)
        "verify" -> verify(args)
        "seq" -> seq(args)
        "restore" -> restore(args)
        else -> usage(2)
      }
    } catch (e: IllegalArgumentException) {
      io.err("RIFIUTATO: ${e.message}")
      1
    }
  }

  private fun usage(code: Int): Int {
    listOf(
      "ManifestTool keys    [--dir <cartella>]",
      "ManifestTool sign    --in release/manifest.json --out release/manifest.mcgr [--key k1|k2] [--dir <cartella>] [--ledger <file>]",
      "ManifestTool sign    --test-key --in <json> --out <mcgr> [--ledger <file>] [--issued <2026-10-31T12:00:00Z>]",
      "ManifestTool verify  --in <mcgr> [--test-key] [--version-code <n>]",
      "ManifestTool seq     [--dir <cartella>] [--ledger <file>] [--out <mcgr>] [--test-key]",
      "ManifestTool restore [--dir <cartella>]",
      "Cartella di base delle chiavi: %USERPROFILE%\\.mycloudgallery\\release (mai nel repository).",
    ).forEach(io::out)
    return code
  }

  private fun dir(args: Args): File = args.file("--dir") ?: File(home, ".mycloudgallery/release")

  private fun ledger(args: Args): SeqLedger {
    val test = "--test-key" in args.flags
    return SeqLedger(args.file("--ledger") ?: if (test) File("build/manifest-tool/kt-seq.txt") else File(dir(args), "manifest-seq.txt"))
  }

  private fun passphrase(prompt: String): CharArray = io.secret(prompt) ?: throw IllegalArgumentException("Nessuna frase segreta: lancia lo strumento da un terminale.")

  // --- keys ----------------------------------------------------------------------------------------------------------------------

  private fun keys(args: Args): Int {
    val file = File(dir(args), ManifestKeyStore.FILE_NAME)
    require(!file.exists()) { "Le chiavi ci sono gia' (${file.path}): non le rigenero. Per ruotarle segui D31 in docs/fase10-progetto.md." }
    val first = passphrase("Frase segreta nuova (almeno ${ManifestKeyStore.MIN_PASSPHRASE} caratteri): ")
    require(first.size >= ManifestKeyStore.MIN_PASSPHRASE) { "La frase segreta e' troppo corta." }
    require(first.contentEquals(passphrase("Ripeti la frase segreta: "))) { "Le due frasi non coincidono." }
    val k1 = ManifestKeyStore.generate()
    val k2 = ManifestKeyStore.generate()
    ManifestKeyStore.write(file, mapOf("k1" to k1, "k2" to k2), first, clock.instant())
    first.fill(' ')
    io.out("Chiavi scritte in ${file.path} (PKCS#12, voci k1 e k2). Fanne subito una copia offline (chiavetta in un cassetto).")
    io.out("")
    io.out("1) In core-release/.../keys/ReleaseKeys.kt (poi PROVISIONAL = false):")
    for ((name, pair) in listOf("K1" to k1, "K2" to k2)) {
      val spki = pair.public.encoded
      io.out("  private const val ${name}_SPKI: String = \"${Base64.getEncoder().encodeToString(spki)}\"")
      io.out("  private const val ${name}_FINGERPRINT: String = \"${EcdsaP256.sha256Hex(spki)}\"")
    }
    io.out("2) In docs/sicurezza.md §5.6, le impronte (togli \"segnaposto\"):")
    io.out("  K1: ${EcdsaP256.sha256Hex(k1.public.encoded)}")
    io.out("  K2: ${EcdsaP256.sha256Hex(k2.public.encoded)}")
    io.out("")
    io.out("3) IL FOGLIO DI K2 (scrivilo a mano o stampalo, tienilo offline, poi pulisci questo terminale):")
    io.out("  impronta K2: ${EcdsaP256.sha256Hex(k2.public.encoded)}")
    ManifestKeyStore.sheet(k2.private).forEach { io.out("  $it") }
    return 0
  }

  // --- sign ----------------------------------------------------------------------------------------------------------------------

  private fun sign(args: Args): Int {
    val input = args.file("--in") ?: throw IllegalArgumentException("Manca --in (release/manifest.json).")
    val output = args.file("--out") ?: throw IllegalArgumentException("Manca --out (release/manifest.mcgr).")
    require(input.isFile) { "Non trovo ${input.path}." }
    val test = "--test-key" in args.flags
    val human = HumanManifest.parse(input.readText(Charsets.UTF_8))
    val ledger = ledger(args)
    val last = maxOf(ledger.last(), seqOf(output))
    val seq = human.seq ?: (last + 1)
    require(seq > last) { "seq $seq non crescente: l'ultimo firmato e' $last. Un manifest con seq non piu' alto l'app lo ignora (anti-rigioco)." }
    val issued = args.values["--issued"]?.let {
      require(test) { "--issued solo con --test-key (le prove della scadenza)." }
      Instant.parse(it)
    } ?: clock.instant().truncatedTo(ChronoUnit.SECONDS)
    val manifest = human.build(seq, issued)
    val (keyId, key, trust) = signingKey(args, test)
    val sealed = sealChecked(manifest, keyId, key, trust)
    ledger.record(seq)
    output.absoluteFile.parentFile?.mkdirs()
    val temp = File(output.absoluteFile.parentFile, output.name + ".tmp")
    temp.writeBytes(sealed)
    Files.move(temp.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    io.out("Firmato con ${keyName(keyId)}: seq $seq, ${sealed.size} byte, valido fino a ${manifest.expires}. Riverificato. Scritto in ${output.path}.")
    io.out("Copialo sul NAS in <area comune>/${ManifestLimits.NAS_PATH} (temporaneo e rinomina).")
    return 0
  }

  private fun signingKey(args: Args, test: Boolean): Triple<Int, PrivateKey, ReleaseTrust> {
    if (test) {
      require(args.values["--key"] == null) { "--test-key firma solo con KT." }
      return Triple(ReleaseKeyIds.KT, ReleaseTestPrivateKey.kt(), ReleaseTestKeys.DEBUG_TRUST.forChannel(ReleaseChannel.DEBUG))
    }
    val alias = args.values["--key"] ?: "k1"
    require(alias == "k1" || alias == "k2") { "--key vale k1 o k2." }
    if (alias == "k2") io.err("ATTENZIONE: K2 e' la chiave di riserva. Usala solo se K1 e' persa o rubata (D31, E-REL8).")
    val (key, spki) = ManifestKeyStore.load(File(dir(args), ManifestKeyStore.FILE_NAME), alias, passphrase("Frase segreta delle chiavi: "))
    val keyId = if (alias == "k1") ReleaseKeyIds.K1 else ReleaseKeyIds.K2
    val pinned = ReleaseKeys.ALL.first { it.id == keyId }
    require(pinned.spki.contentEquals(spki)) {
      "La chiave $alias del file (impronta ${EcdsaP256.sha256Hex(spki).take(16)}...) non e' quella fissata nell'app " +
        "(${pinned.fingerprint.take(16)}...)" + (if (ReleaseKeys.PROVISIONAL) ": l'app ha ancora i segnaposto, aggiorna ReleaseKeys e rilascia." else ".")
    }
    return Triple(keyId, key, ReleaseTrust.RELEASE)
  }

  /**
   * Firma [manifest] con [key] come [keyId] e **riverifica** la busta con il verificatore dell'app e le chiavi fissate di [trust]: una busta
   * che l'app non accetterebbe (chiave non fissata, contenuto che non si rilegge uguale) non esce di qui.
   */
  fun sealChecked(manifest: ReleaseManifest, keyId: Int, key: PrivateKey, trust: ReleaseTrust): ByteArray {
    val sealed = ReleaseTestSigner.sealWith(key, ManifestCodec.canonical(manifest), keyId)
    val check = SignedManifestVerifier(trust).verify(sealed, ManifestLimits.APP_ID)
    require(check is Verified.Ok && check.manifest == manifest && check.keyId == keyId) {
      "La busta firmata non passa il verificatore dell'app (${(check as? Verified.Rejected)?.reason?.code ?: "contenuto diverso"}): " +
        "la chiave non e' quella fissata nell'app? Niente e' stato scritto."
    }
    return sealed
  }

  /** Il `seq` di una busta che c'e' gia' (anche non verificabile qui: conta solo per non tornare indietro), o 0. */
  private fun seqOf(file: File): Long {
    if (!file.isFile) return 0
    val bytes = file.readBytes()
    return runCatching { ManifestCodec.decode(ManifestCodec.open(bytes).json, ManifestLimits.APP_ID).seq }.getOrDefault(0L)
  }

  // --- verify, seq, restore ------------------------------------------------------------------------------------------------------

  private fun verify(args: Args): Int {
    val input = args.file("--in") ?: throw IllegalArgumentException("Manca --in.")
    require(input.isFile) { "Non trovo ${input.path}." }
    val trust = if ("--test-key" in args.flags) ReleaseTestKeys.DEBUG_TRUST.forChannel(ReleaseChannel.DEBUG) else ReleaseTrust.RELEASE
    when (val verified = SignedManifestVerifier(trust).verify(input.readBytes(), ManifestLimits.APP_ID)) {
      is Verified.Rejected -> {
        io.err("RIFIUTATO dall'app: ${verified.reason.code}")
        return 1
      }
      is Verified.Ok -> describe(verified.manifest, verified.keyId, args.values["--version-code"]?.toIntOrNull())
    }
    return 0
  }

  private fun describe(m: ReleaseManifest, keyId: Int, versionCode: Int?) {
    io.out("Valido, firmato con ${keyName(keyId)}: seq ${m.seq}, emesso ${m.issued}, scade ${m.expires}${if (clock.instant().isAfter(m.expires)) " (SCADUTO per questo PC)" else ""}")
    io.out("Versione minima: ${m.minVersionCode ?: "nessuna"}")
    m.blocks.forEach { io.out("Blocco: versionCode ${it.fromCode}-${it.toCode}, ${it.level.code}, messaggio ${it.message.code}") }
    m.models.forEach { hint -> io.out("Modello ${hint.id} (${hint.sha256.take(12)}...): ${hint.urls.joinToString()}") }
    if (versionCode != null) io.out("Per versionCode $versionCode: ${ReleaseRules.statusFor(m, versionCode)}")
  }

  private fun seq(args: Args): Int {
    val ledger = ledger(args)
    val last = maxOf(ledger.last(), args.file("--out")?.let(::seqOf) ?: 0)
    io.out("Ultimo seq firmato: $last (registro ${ledger.file.path}). Il prossimo: ${last + 1}.")
    return 0
  }

  private fun restore(args: Args): Int {
    val file = File(dir(args), ManifestKeyStore.FILE_NAME)
    require(!file.exists()) { "Il file delle chiavi c'e' gia' (${file.path}): spostalo prima di ricostruire K2." }
    val digits = String(passphrase("Le 64 cifre del foglio di K2 (non si vedono): "))
    val check = String(passphrase("Il codice di controllo del foglio: "))
    val key = ManifestKeyStore.fromSheet(digits, check)
    require(ManifestKeyStore.matches(key, ReleaseKeys.K2.spki)) { "Il foglio non corrisponde a K2 fissata nell'app (impronta ${ReleaseKeys.K2.fingerprint.take(16)}...)." }
    val pass = passphrase("Frase segreta nuova (almeno ${ManifestKeyStore.MIN_PASSPHRASE} caratteri): ")
    require(pass.size >= ManifestKeyStore.MIN_PASSPHRASE) { "La frase segreta e' troppo corta." }
    require(pass.contentEquals(passphrase("Ripeti la frase segreta: "))) { "Le due frasi non coincidono." }
    val public = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(ReleaseKeys.K2.spki))
    ManifestKeyStore.write(file, mapOf("k2" to java.security.KeyPair(public, key)), pass, clock.instant())
    io.out("K2 ricostruita in ${file.path} (solo la voce k2). Firma con --key k2 e prepara l'aggiornamento con una coppia nuova (D31).")
    return 0
  }

  private fun keyName(keyId: Int): String = when (keyId) {
    ReleaseKeyIds.K1 -> "K1"
    ReleaseKeyIds.K2 -> "K2"
    ReleaseKeyIds.KT -> "KT (chiave di prova)"
    else -> "chiave $keyId"
  }

  companion object {
    private val FLAGS = setOf("--test-key")

    @JvmStatic
    fun main(argv: Array<String>) {
      val console = System.console()
      val io = object : ToolIo {
        override fun out(line: String) = println(line)

        override fun err(line: String) = System.err.println(line)

        override fun secret(prompt: String): CharArray? = console?.readPassword(prompt)
      }
      exitProcess(ManifestTool(io).run(argv.toList()))
    }
  }
}

/** L'ultimo `seq` firmato (un numero in un file, scritto con temporaneo e rinomina): lo strumento non firma mai un `seq` non piu' alto. */
internal class SeqLedger(val file: File) {
  fun last(): Long = if (file.isFile) file.readText().trim().toLongOrNull() ?: throw IllegalArgumentException("Registro dei seq illeggibile: ${file.path}") else 0

  fun record(seq: Long) {
    require(seq > last()) { "seq $seq non crescente." }
    file.absoluteFile.parentFile?.mkdirs()
    val temp = File(file.absoluteFile.parentFile, file.name + ".tmp")
    temp.writeText("$seq\n")
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }
}
