package dev.pampa.mycloudgallery.core.security

import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.mycloudgallery.core.security.lock.AuthAvailability
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.core.security.lock.BiometricPromptAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo sblocco con la credenziale di sistema sul telefono vero. Non si puo' (e non si deve) inserire il PIN da un
 * test: si prova che `BiometricPrompt` si apre davvero con le versioni di fragment/appcompat che il modulo
 * porta, che il sistema riconosce la combinazione "biometria debole + credenziale" e che l'annullamento chiude
 * la schermata senza lasciare nulla in giro.
 */
@RunWith(AndroidJUnit4::class)
class BiometricPromptInstrumentedTest {

  private fun FragmentActivity.promptFragment() = supportFragmentManager.findFragmentByTag(PROMPT_TAG)

  private fun waitFor(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return true
      Thread.sleep(100)
    }
    return condition()
  }

  @Test
  fun ilSistemaRispondeSullaDisponibilita_conCredenzialeDiSistema() {
    ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        val availability = BiometricPromptAuthenticator(activity).availability()
        Log.i(TAG, "disponibilita' sullo S25: $availability")
        // Con la credenziale di sistema ammessa le sole risposte sensate sono "si" o "nessun blocco schermo".
        assertTrue(availability == AuthAvailability.AVAILABLE || availability == AuthAvailability.NO_SCREEN_LOCK)
      }
    }
  }

  @Test
  fun laSchermataDiSistemaSiApre_esiAnnullaSenzaResiduiNeSbloccareNiente() {
    ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
      lateinit var activity: TestHostActivity
      var availability: AuthAvailability? = null
      scenario.onActivity {
        activity = it
        availability = BiometricPromptAuthenticator(it).availability()
      }
      if (availability != AuthAvailability.AVAILABLE) {
        Log.w(TAG, "nessun blocco schermo sul telefono: la schermata non si puo' aprire, prova saltata")
        // il comportamento giusto, in quel caso, e' dirlo senza aprire niente
        scenario.onActivity {
          val result = kotlinx.coroutines.runBlocking { BiometricPromptAuthenticator(it).authenticate(AuthPromptText("t")) }
          assertEquals(AuthResult.NotAvailable(AuthAvailability.NO_SCREEN_LOCK), result)
        }
        return
      }

      var job: Job? = null
      var delivered: AuthResult? = null
      scenario.onActivity {
        job = CoroutineScope(Dispatchers.Main).launch(start = CoroutineStart.UNDISPATCHED) {
          delivered = BiometricPromptAuthenticator(it).authenticate(AuthPromptText("Prova", "Annulla da solo"))
        }
      }

      assertTrue("la schermata di BiometricPrompt non si e' aperta", waitFor { var f = false; scenario.onActivity { f = it.promptFragment() != null }; f })
      Log.i(TAG, "BiometricPrompt aperto con fragment/appcompat del modulo")
      // Si lascia al sistema il tempo di presentare la schermata: annullare nello stesso istante in cui parte
      // (cosa che a un utente non succede) fa arrivare la richiesta di chiusura prima della schermata stessa.
      Thread.sleep(2_000)

      // annullo come farebbe il gate quando esce dalla composizione
      scenario.onActivity { job!!.cancel() }

      assertTrue("la schermata non si e' chiusa dopo l'annullamento", waitFor { var gone = false; scenario.onActivity { gone = it.promptFragment() == null }; gone })
      assertNull("nessun esito deve arrivare a un chiamante annullato (e certo non un successo)", delivered)
      assertFalse(job!!.isActive)
      assertNotNull(activity)
    }
  }

  private companion object {
    const val TAG = "BiometricPromptIT"

    /** Il tag con cui androidx.biometric 1.1.0 registra il suo fragment mentre la schermata e' aperta. */
    const val PROMPT_TAG = "androidx.biometric.BiometricFragment"
  }
}
