package dev.pampa.mycloudgallery.core.trust

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

/**
 * Runner minimo per l'unico test strumentato del modulo (Keystore vero sul dispositivo).
 *
 * Perche' non `androidx.test.runner.AndroidJUnitRunner`: `androidx.test:runner` non e' nell'elenco delle
 * dipendenze dell'app (`versions-app.gradle`) e aggiungerla e' una decisione fuori dal perimetro di questo
 * modulo. Questo runner esegue le classi con JUnit 4 (gia' ammesso) e riporta l'esito nello stesso formato
 * di stato di `am instrument` che legge `connectedDebugAndroidTest`. Se un giorno `androidx.test:runner`
 * entra in elenco, questo file si cancella e il build.gradle torna al runner standard.
 */
class KeystoreTestRunner : Instrumentation() {

  override fun onCreate(arguments: Bundle?) {
    super.onCreate(arguments)
    testContext = context
    start()
  }

  override fun onStart() {
    super.onStart()
    val request = Request.classes(KeystoreDeviceTest::class.java)
    val total = request.runner.testCount()
    val core = JUnitCore()
    val reporter = StatusReporter(this, total)
    core.addListener(reporter)
    val result = core.run(request)
    val summary = Bundle().apply {
      putString(REPORT_KEY_STREAMRESULT, "\nTest: ${result.runCount}, falliti: ${result.failureCount}\n")
    }
    finish(Activity.RESULT_OK, summary)
  }

  /** Riporta ogni test come fa AndroidJUnitRunner: stato 1 all'inizio, 0 / -2 / -4 / -3 alla fine. */
  private class StatusReporter(private val instrumentation: Instrumentation, private val total: Int) : RunListener() {
    private var current = 0
    private var code = OK
    private var bundle = Bundle()

    override fun testStarted(description: Description) {
      current++
      code = OK
      bundle = Bundle().apply {
        putString(REPORT_KEY_IDENTIFIER, "AndroidJUnitRunner")
        putInt("numtests", total)
        putInt("current", current)
        putString("class", description.className)
        putString("test", description.methodName)
        putString(REPORT_KEY_STREAMRESULT, "")
      }
      instrumentation.sendStatus(START, Bundle(bundle))
    }

    override fun testFailure(failure: Failure) {
      code = FAILURE
      bundle.putString("stack", failure.trace)
      bundle.putString(REPORT_KEY_STREAMRESULT, "\n${failure.description.methodName}:\n${failure.trace}")
    }

    override fun testAssumptionFailure(failure: Failure) {
      code = ASSUMPTION_FAILURE
      bundle.putString("stack", failure.trace)
    }

    override fun testIgnored(description: Description) {
      testStarted(description)
      code = IGNORED
      testFinished(description)
    }

    override fun testFinished(description: Description) {
      if (code == OK) bundle.putString(REPORT_KEY_STREAMRESULT, ".")
      instrumentation.sendStatus(code, bundle)
    }

    private companion object {
      const val START = 1
      const val OK = 0
      const val FAILURE = -2
      const val IGNORED = -3
      const val ASSUMPTION_FAILURE = -4
    }
  }

  companion object {
    /** Il contesto dell'APK di test, per i file temporanei dei test. */
    lateinit var testContext: Context
      private set
  }
}
