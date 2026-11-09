package dev.pampa.mycloudgallery.core.rules.platform.crash

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Il registro locale dei crash (C472, OA-2 / OB-7): cosa si registra e cosa **mai**. La regola e' quella di C467: nessun messaggio di eccezione, nessun percorso, nessun
 * nome di file dell'utente. Il file e' un dato: la rilettura applica la stessa grammatica della scrittura.
 */
class CrashLogTest {

  private lateinit var dir: File
  private lateinit var file: File
  private lateinit var store: CrashLogStore

  @Before
  fun setUp() {
    dir = Files.createTempDirectory("crashlog").toFile()
    file = File(dir, "crash/crashes.log")
    store = CrashLogStore(file)
  }

  @After
  fun tearDown() {
    dir.deleteRecursively()
  }

  private fun frame(cls: String, method: String, file: String, line: Int) = StackTraceElement(cls, method, file, line)

  private fun error(message: String, cause: Throwable? = null, vararg frames: StackTraceElement): Throwable =
    IllegalStateException(message, cause).also { it.stackTrace = frames.toList().toTypedArray() }

  private fun record(at: Long, chain: List<CrashCause> = listOf(CrashCause("java.lang.IllegalStateException", listOf("dev.pampa.mycloudgallery.Foo.bar(Foo.kt:1)")))) =
    CrashRecord(CrashKind.CRASH, at, versionCode = 42, thread = ThreadKind.MAIN, mainProcess = true, chain = chain)

  // --- Cosa si registra --------------------------------------------------------------------------------------------------

  @Test
  fun ilMessaggioDiUnEccezioneNonEntraMai_NeNellaCatenaNeNelleCause() {
    val cause = IOException("STATUS_ACCESS_DENIED su \\\\NAS\\famiglia\\Foto\\Mare 2019\\IMG_0001.jpg")
    cause.stackTrace = arrayOf(frame("org.example.net.Foo", "read", "Foo.java", 10), frame("dev.pampa.mycloudgallery.core.nas.NasRepo", "open", "NasRepo.kt", 88))
    val outer = error(
      "Errore SMB su Foto/Mare 2019/IMG_0001.jpg per mario@example.com a 192.168.178.58",
      cause,
      frame("dev.pampa.mycloudgallery.feature.Screen", "load", "Screen.kt", 12),
    )

    store.record(record(1_000, CrashDigest.chain(outer)))
    val onDisk = file.readText()
    val reread = store.read().single()

    for (secret in listOf("IMG_0001", "Mare 2019", "STATUS_ACCESS_DENIED", "mario@example.com", "192.168.178.58", "NAS", "famiglia")) {
      assertThat(onDisk).doesNotContain(secret)
      assertThat(reread.toString()).doesNotContain(secret)
      assertThat(reread.chain.flatMap { it.frames + it.className }.joinToString("\n")).doesNotContain(secret)
    }
    // Resta il dove: le classi della catena e le cornici nostre (non quelle di smbj).
    assertThat(reread.chain.map { it.className }).containsExactly("java.lang.IllegalStateException", "java.io.IOException").inOrder()
    assertThat(reread.chain[0].frames).containsExactly("dev.pampa.mycloudgallery.feature.Screen.load(Screen.kt:12)")
    assertThat(reread.chain[1].frames).containsExactly("dev.pampa.mycloudgallery.core.nas.NasRepo.open(NasRepo.kt:88)")
    assertThat(reread.where).isEqualTo("dev.pampa.mycloudgallery.feature.Screen.load(Screen.kt:12)")
  }

  @Test
  fun leCornicheSonoSoloQuelleNostre_IlMotoreCompreso_EleprimeVentiCinque() {
    val frames = buildList {
      add(frame("java.util.ArrayList", "get", "ArrayList.java", 5))
      add(frame("androidx.compose.runtime.Composer", "x", "Composer.kt", 5))
      add(frame("dev.antigravity.fluidengine.ui.FluidList", "draw", "FluidList.kt", 7))
      repeat(60) { add(frame("dev.pampa.mycloudgallery.A$it", "m", "A.kt", it + 1)) }
    }.toTypedArray()

    val chain = CrashDigest.chain(error("m", null, *frames))

    assertThat(chain.single().frames).hasSize(CrashDigest.MAX_FRAMES)
    assertThat(chain.single().frames.first()).isEqualTo("dev.antigravity.fluidengine.ui.FluidList.draw(FluidList.kt:7)")
    assertThat(chain.single().frames.none { it.startsWith("java.") || it.startsWith("androidx.") }).isTrue()
  }

  @Test
  fun unaCatenaCheSiRichiudeSuSeStessaSiScriveUnaVoltaSolaEConUnTetto() {
    val first = RuntimeException("primo con un nome: segreto.jpg")
    val second = IllegalArgumentException("secondo", first)
    first.initCause(second)

    val chain = CrashDigest.chain(second)

    assertThat(chain.map { it.className }).containsExactly("java.lang.IllegalArgumentException", "java.lang.RuntimeException").inOrder()

    var deep: Throwable = RuntimeException("fondo")
    repeat(20) { deep = RuntimeException("livello $it", deep) }
    assertThat(CrashDigest.chain(deep)).hasSize(CrashDigest.MAX_CAUSES)
  }

  @Test
  fun unaCorniceSenzaFileORigaSiScriveSenzaIlPezzoTraParentesi() {
    val noFile = StackTraceElement("dev.pampa.mycloudgallery.Foo", "bar", null, -1)
    val chain = CrashDigest.chain(error("m", null, noFile))
    assertThat(chain.single().frames).containsExactly("dev.pampa.mycloudgallery.Foo.bar")

    // Un nome di file con spazi o barre non e' un identificativo: il punto resta, il file no (e il percorso non passa).
    assertThat(CrashDigest.frameOf("dev.pampa.mycloudgallery.Foo", "bar", "Mare 2019/IMG.kt", 3)).isEqualTo("dev.pampa.mycloudgallery.Foo.bar")
    // Un metodo strano di Kotlin (nome "mangled", costruttore) e' un identificativo.
    assertThat(CrashDigest.frameOf("dev.pampa.mycloudgallery.Foo\$bar\$1", "<init>", "Foo.kt", 4)).isEqualTo("dev.pampa.mycloudgallery.Foo\$bar\$1.<init>(Foo.kt:4)")
    assertThat(CrashDigest.frameOf("dev.pampa.mycloudgallery.Foo", "load-abc123", "Foo.kt", 4)).isEqualTo("dev.pampa.mycloudgallery.Foo.load-abc123(Foo.kt:4)")
  }

  @Test
  fun ilThreadSiRiduceAUnaFamigliaChiusa_ILNomiDiRetePerdonoOgniDato() {
    assertThat(ThreadKind.of("main")).isEqualTo(ThreadKind.MAIN)
    assertThat(ThreadKind.of("DefaultDispatcher-worker-3")).isEqualTo(ThreadKind.COROUTINE)
    assertThat(ThreadKind.of("pool-5-thread-1")).isEqualTo(ThreadKind.POOL)
    assertThat(ThreadKind.of("WM.task-2")).isEqualTo(ThreadKind.WORK)
    assertThat(ThreadKind.of("Binder:1234_5")).isEqualTo(ThreadKind.BINDER)
    // smbj chiama i suoi thread con l'indirizzo del NAS: non ne resta niente.
    assertThat(ThreadKind.of("Packet Reader for 192.168.178.58")).isEqualTo(ThreadKind.OTHER)
    assertThat(ThreadKind.of("OkHttp nas.local")).isEqualTo(ThreadKind.OTHER)
    assertThat(ThreadKind.of(null)).isEqualTo(ThreadKind.OTHER)
  }

  // --- Il registro -------------------------------------------------------------------------------------------------------

  @Test
  fun ilRegistroTieneGliUltimiDieci_IlPiuRecenteEPrimo() {
    for (i in 1..14) store.record(record(at = i * 1_000L))

    val read = store.read()

    assertThat(read).hasSize(CrashLogStore.MAX_RECORDS)
    assertThat(read.map { it.atMillis }).containsExactlyElementsIn((14 downTo 5).map { it * 1_000L }).inOrder()
  }

  @Test
  fun ilTettoDiDimensioneTogliePrimaIPiuVecchi_IlNuovoCeSempre() {
    val big = (1..CrashDigest.MAX_CAUSES).map { c ->
      CrashCause("java.lang.RuntimeException", (1..CrashDigest.MAX_FRAMES).map { "dev.pampa.mycloudgallery.${"Classe".repeat(8)}$c.metodoLungo$it(${"File".repeat(5)}.kt:$it)" })
    }
    for (i in 1..CrashLogStore.MAX_RECORDS) store.record(record(at = i * 1_000L, chain = big))

    assertThat(file.length()).isAtMost(CrashLogStore.MAX_BYTES.toLong())
    val read = store.read()
    assertThat(read.first().atMillis).isEqualTo(CrashLogStore.MAX_RECORDS * 1_000L)
    assertThat(read.size).isAtMost(CrashLogStore.MAX_RECORDS)
    assertThat(read.size).isAtLeast(1)
  }

  @Test
  fun unFileCheNonCeOChePesaTroppoVale_NienteRegistrato() {
    assertThat(store.read()).isEmpty()

    file.parentFile.mkdirs()
    file.writeBytes(ByteArray(CrashLogStore.MAX_BYTES * 5))
    assertThat(store.read()).isEmpty()
    // E il crash nuovo si scrive lo stesso: il file illeggibile si sostituisce, non blocca.
    store.record(record(5))
    assertThat(store.read().map { it.atMillis }).containsExactly(5L)
  }

  @Test
  fun ilFileEUnDato_UnaRigaFuoriGrammaticaFaScartareIlSuoCrash_EGliAltriRestano() {
    store.record(record(1_000))
    store.record(record(2_000))
    // Qualcuno (o un bug di domani) scrive nel file un "messaggio": non e' un identificativo, quindi quel crash non si legge.
    val text = file.readText()
    val at = text.indexOf("F dev.pampa")
    file.writeText(text.substring(0, at) + "F Mario Rossi ha cancellato /storage/emulated/0/DCIM/IMG_0001.jpg\n" + text.substring(at))

    val read = store.read()

    assertThat(read.map { it.atMillis }).containsExactly(1_000L)
    assertThat(read.toString()).doesNotContain("Mario")
  }

  @Test
  fun leIntestazioniEICampiRovinatiNonFannoCadereLaLettura() {
    file.parentFile.mkdirs()
    file.writeText("non e' il nostro formato\nR CRASH 1 2 MAIN main\nC java.lang.Error\n")
    assertThat(store.read()).isEmpty()

    file.writeText("${CrashLogFormat.HEADER}\nR CRASH ieri 2 MAIN main\nC java.lang.Error\nR BOOM 1 2 MAIN main\nC java.lang.Error\nR CRASH 3 4 NOPE main\nC java.lang.Error\nF orfana\n\nR CRASH 5 6 MAIN main\n")
    assertThat(store.read()).isEmpty()
  }

  @Test
  fun lAnrEIlCrashSiRileggonoComeSonoStati() {
    val anr = CrashRecord(
      CrashKind.ANR, 9_000, versionCode = 0, thread = ThreadKind.MAIN, mainProcess = true,
      chain = listOf(CrashCause("android.app.ApplicationNotResponding", listOf("dev.pampa.mycloudgallery.Foo.bar(Foo.kt:1)"))),
    )
    store.record(anr)
    store.record(record(10_000))

    val read = store.read()

    assertThat(read.map { it.kind }).containsExactly(CrashKind.CRASH, CrashKind.ANR).inOrder()
    assertThat(read[1]).isEqualTo(anr)
  }

  @Test
  fun leggereNonLanciaMaiEIlRegistroSiPuoVuotare() {
    store.record(record(1))
    assertThat(store.clear()).isTrue()
    assertThat(file.exists()).isFalse()
    assertThat(store.read()).isEmpty()
  }
}
