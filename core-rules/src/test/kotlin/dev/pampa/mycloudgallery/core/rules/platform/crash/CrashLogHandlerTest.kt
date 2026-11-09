package dev.pampa.mycloudgallery.core.rules.platform.crash

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Il gestore delle eccezioni non gestite (C472): scrive il registro, **non lancia mai** e passa sempre la mano al gestore di prima.
 */
@RunWith(RobolectricTestRunner::class)
class CrashLogHandlerTest {

  private lateinit var dir: File
  private lateinit var file: File
  private val calls = ArrayList<String>()
  private var previousDefault: Thread.UncaughtExceptionHandler? = null

  @Before
  fun setUp() {
    dir = Files.createTempDirectory("crashhandler").toFile()
    file = File(dir, "crashes.log")
    previousDefault = Thread.getDefaultUncaughtExceptionHandler()
  }

  @After
  fun tearDown() {
    Thread.setDefaultUncaughtExceptionHandler(previousDefault)
    dir.deleteRecursively()
  }

  private val previous = Thread.UncaughtExceptionHandler { _, _ -> calls += "previous" }

  private fun handler(
    store: () -> CrashLogStore = { CrashLogStore(file) },
    previous: Thread.UncaughtExceptionHandler? = this.previous,
  ) = CrashLogHandler(store, versionCode = 7, mainProcess = { true }, clock = { 1_234L }, previous = previous)

  @Test
  fun scriveIlCrashEPoiPassaLaManoAlGestoreDiPrima() {
    val error = IllegalStateException("percorso segreto /storage/emulated/0/DCIM/IMG_0001.jpg")
    error.stackTrace = arrayOf(StackTraceElement("dev.pampa.mycloudgallery.Foo", "bar", "Foo.kt", 9))

    handler().uncaughtException(Thread.currentThread(), error)

    assertThat(calls).containsExactly("previous")
    val record = CrashLogStore(file).read().single()
    assertThat(record.atMillis).isEqualTo(1_234L)
    assertThat(record.versionCode).isEqualTo(7L)
    assertThat(record.kind).isEqualTo(CrashKind.CRASH)
    assertThat(record.chain.single().className).isEqualTo("java.lang.IllegalStateException")
    assertThat(record.where).isEqualTo("dev.pampa.mycloudgallery.Foo.bar(Foo.kt:9)")
    assertThat(file.readText()).doesNotContain("segreto")
    assertThat(file.readText()).doesNotContain("IMG_0001")
  }

  @Test
  fun seIlRegistroNonSiScriveNonLanciaEPassaLaManoLoStesso() {
    // La cartella e' un file: la scrittura fallisce con IOException.
    val blocker = File(dir, "blocker").also { it.writeText("x") }

    handler(store = { CrashLogStore(File(blocker, "crashes.log")) }).uncaughtException(Thread.currentThread(), RuntimeException("x"))

    assertThat(calls).containsExactly("previous")
  }

  @Test
  fun ancheSeLoStoreStessoLancia_OSeCadeLaMemoria_IlGestoreDiPrimaVieneChiamato() {
    handler(store = { throw IOException("nessuna cartella") }).uncaughtException(Thread.currentThread(), RuntimeException("x"))
    handler(store = { throw OutOfMemoryError("finita") }).uncaughtException(Thread.currentThread(), RuntimeException("x"))
    handler(store = { throw StackOverflowError() }).uncaughtException(Thread.currentThread(), RuntimeException("x"))

    assertThat(calls).containsExactly("previous", "previous", "previous")
  }

  @Test
  fun senzaGestoreDiPrimaNonSuccedeNiente() {
    handler(previous = null).uncaughtException(Thread.currentThread(), RuntimeException("x"))

    assertThat(CrashLogStore(file).read()).hasSize(1)
  }

  @Test
  fun ilNomeDelThreadNonSiScrive_SoloLaFamiglia() {
    val named = Thread { }.also { it.name = "Packet Reader for 192.168.178.58" }

    handler().uncaughtException(named, RuntimeException("x"))

    assertThat(file.readText()).doesNotContain("192.168")
    assertThat(file.readText()).doesNotContain("Packet")
    assertThat(CrashLogStore(file).read().single().thread).isEqualTo(ThreadKind.OTHER)
  }

  @Test
  fun siInstallaUnaVoltaSolaIncatenatoAQuelloCheC_ENonSiIncatenaDueVolte() {
    val app: Application = ApplicationProvider.getApplicationContext()
    Thread.setDefaultUncaughtExceptionHandler(previous)

    assertThat(CrashLogHandler.install(app, versionCode = 3)).isTrue()
    val installed = Thread.getDefaultUncaughtExceptionHandler()
    assertThat(installed).isInstanceOf(CrashLogHandler::class.java)

    assertThat(CrashLogHandler.install(app, versionCode = 3)).isFalse()
    assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameInstanceAs(installed)

    // Incatenato: un crash qui, nel processo di prova, arriva anche a "previous". (Scrive nel noBackupFilesDir di Robolectric.)
    installed!!.uncaughtException(Thread.currentThread(), RuntimeException("x"))
    assertThat(calls).containsExactly("previous")
    assertThat(CrashLogStore.inNoBackupDir(app).read()).hasSize(1)
  }
}
