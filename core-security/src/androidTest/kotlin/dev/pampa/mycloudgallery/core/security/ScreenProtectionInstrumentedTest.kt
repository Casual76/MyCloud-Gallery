package dev.pampa.mycloudgallery.core.security

import android.app.KeyguardManager
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Window
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.security.device.ScreenLockCheck
import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus
import dev.pampa.mycloudgallery.core.security.input.SensitiveClipboard
import dev.pampa.mycloudgallery.core.security.screen.ProtectionHandle
import dev.pampa.mycloudgallery.core.security.screen.RecentsPrivacy
import dev.pampa.mycloudgallery.core.security.screen.WindowProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le protezioni di schermo e di input sul telefono vero: il sistema accetta le chiamate (compreso
 * `setHideOverlayWindows`, che esige il permesso HIDE_OVERLAY_WINDOWS portato dal manifest del modulo), le
 * flag arrivano sulla finestra, la copia sensibile e il controllo del blocco schermo rispondono.
 */
@RunWith(AndroidJUnit4::class)
class ScreenProtectionInstrumentedTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun Window.secure() = (attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

  private inline fun withActivity(block: (ActivityScenario<TestHostActivity>) -> Unit) {
    ActivityScenario.launch(TestHostActivity::class.java).use { block(it) }
  }

  private fun dumpWindows(): String {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val descriptor = automation.executeShellCommand("dumpsys window windows")
    return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
  }

  /** La porzione del dump che descrive la finestra della nostra activity di prova. */
  private fun ourWindowDump(): String {
    val dump = dumpWindows()
    val start = dump.indexOf("Window #").let { first ->
      Regex("Window #\\d+ Window\\{[^}]*TestHostActivity\\}").find(dump)?.range?.first ?: first
    }
    val end = dump.indexOf("Window #", start + 10).let { if (it < 0) dump.length else it }
    return dump.substring(start, end)
  }

  @Test
  fun permessoHideOverlayWindows_ePresenteNelManifestFusoEConcesso() {
    val granted = context.checkSelfPermission("android.permission.HIDE_OVERLAY_WINDOWS")
    assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, granted)
  }

  /** Le flag della nostra finestra come le vede il WindowManager (`fl=` e `pfl=` del dump, in esadecimale). */
  private fun systemFlags(): Pair<Long, Long> {
    val section = ourWindowDump()
    fun hex(name: String) = Regex("\\b$name=([0-9a-f]+)").find(section)?.groupValues?.get(1)?.toLong(16)
      ?: error("campo $name non trovato nel dump della finestra:\n$section")
    return hex("fl") to hex("pfl")
  }

  @Test
  fun flagSecure_sullaFinestraVera_eilSistemaLaRiconosce() {
    withActivity { scenario ->
      var handle: ProtectionHandle? = null
      scenario.onActivity { handle = WindowProtection.of(it.window).acquireSecureContent() }
      scenario.onActivity { assertTrue(it.window.secure()) }
      Thread.sleep(500) // il WindowManager applica le flag al prossimo ciclo

      val (flags, _) = systemFlags()
      Log.i(TAG, "flag di sistema con FLAG_SECURE: fl=" + flags.toString(16))
      assertTrue("il sistema non vede FLAG_SECURE sulla finestra (fl=${flags.toString(16)})", (flags and FLAG_SECURE) != 0L)

      scenario.onActivity { handle!!.close() }
      Thread.sleep(500)
      val (after, _) = systemFlags()
      assertTrue("FLAG_SECURE e' rimasta dopo il rilascio (fl=${after.toString(16)})", (after and FLAG_SECURE) == 0L)
    }
  }

  @Test
  fun antiSovrapposizioni_ilSistemaAccettaSetHideOverlayWindows_esiTolgono() {
    withActivity { scenario ->
      var handle: ProtectionHandle? = null
      // se mancasse il permesso, il sistema lancerebbe SecurityException (qui la proteggiamo, ma non deve scattare)
      scenario.onActivity { handle = WindowProtection.of(it.window).acquireOverlayShield() }
      scenario.onActivity { assertTrue(it.window.decorView.filterTouchesWhenObscured) }
      Thread.sleep(500)

      val (_, privateFlags) = systemFlags()
      Log.i(TAG, "flag private di sistema con la protezione: pfl=" + privateFlags.toString(16))
      assertTrue(
        "il sistema non nasconde gli overlay (pfl=${privateFlags.toString(16)})",
        (privateFlags and PRIVATE_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS) != 0L,
      )

      scenario.onActivity { handle!!.close() }
      scenario.onActivity { assertFalse(it.window.decorView.filterTouchesWhenObscured) }
      Thread.sleep(500)
      val (_, after) = systemFlags()
      assertTrue("gli overlay restano nascosti dopo il rilascio", (after and PRIVATE_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS) == 0L)
    }
  }

  @Test
  fun anteprimaNeiRecenti_siPuoNascondereEMostrare() {
    withActivity { scenario ->
      scenario.onActivity {
        RecentsPrivacy.instance.setHidden(it, true)
        // da Android 13 NON si usa FLAG_SECURE: gli screenshot normali restano permessi
        if (Build.VERSION.SDK_INT >= 33) assertFalse(it.window.secure())
        RecentsPrivacy.instance.setHidden(it, false)
        assertFalse(it.window.secure())
      }
    }
  }

  @Test
  fun copiaSensibile_arrivaNegliAppuntiConLaMarcaGiusta() {
    withActivity { scenario ->
      scenario.onActivity { activity ->
        assertTrue(SensitiveClipboard.copy(activity, "Codice di prova", "ABCD-1234"))
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        val description = clipboard.primaryClipDescription!!
        assertTrue(description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        assertEquals(ClipDescription.EXTRA_IS_SENSITIVE, SensitiveClipboard.EXTRA_IS_SENSITIVE)
        clipboard.clearPrimaryClip()
      }
    }
  }

  @Test
  fun bloccoSchermo_ilControlloCoincideConIlSistema() {
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    val expected = if (keyguard.isDeviceSecure) ScreenLockStatus.SECURE else ScreenLockStatus.NOT_SECURE
    val status = ScreenLockCheck.status(context)
    Log.i(TAG, "blocco schermo dell'S25: $status")
    assertEquals(expected, status)
    assertEquals(!keyguard.isDeviceSecure, status.needsWarning)
  }

  private companion object {
    const val TAG = "ScreenProtectionIT"
    const val FLAG_SECURE = 0x00002000L

    /** `PRIVATE_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` (campo nascosto del WindowManager). */
    const val PRIVATE_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS = 0x00080000L
  }
}
