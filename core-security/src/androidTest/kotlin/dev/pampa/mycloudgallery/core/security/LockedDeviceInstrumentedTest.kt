package dev.pampa.mycloudgallery.core.security

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.security.credentials.AndroidSecretKeyStore
import dev.pampa.mycloudgallery.core.security.credentials.CredentialVault
import dev.pampa.mycloudgallery.core.security.credentials.LoadResult
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La prova che sostiene la scelta dichiarata del piano 1.5 (E-S9, docs/sicurezza.md L4): le credenziali sono
 * utilizzabili DAL BACKUP NOTTURNO, cioe' a schermo spento e telefono bloccato.
 *
 * Non parte da sola, perche' blocca il telefono: serve `-e screenOff true` e qualcuno (o `adb shell input
 * keyevent KEYCODE_SLEEP`) che lo blocchi mentre il test aspetta. Vedi la procedura nel registro decisioni di
 * docs/sicurezza.md.
 */
@RunWith(AndroidJUnit4::class)
class LockedDeviceInstrumentedTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun credenzialiUsabiliConSchermoSpentoETelefonoBloccato() {
    assumeTrue(
      "prova manuale: richiede -e screenOff true",
      InstrumentationRegistry.getArguments().getString("screenOff") == "true",
    )
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    val power = context.getSystemService(PowerManager::class.java)
    assumeTrue("il telefono deve avere un blocco schermo", keyguard.isDeviceSecure)

    val alias = "mcg.test.locked." + UUID.randomUUID()
    val dir = File(context.cacheDir, "locked-test-" + UUID.randomUUID()).apply { mkdirs() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val keys = AndroidSecretKeyStore(context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE))
    val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "c.preferences_pb") }
    val vault = CredentialVault(keys, store, allowSoftwareKey = false, keyAlias = alias)
    try {
      // 1. con il telefono in uso: si salva
      val first = runBlocking { NasCredentials("utente", "prima-del-blocco".toCharArray()).use { vault.save("nas", it) } }
      assertTrue(first is SaveResult.Saved)
      Log.i(TAG, "salvato con il telefono in uso (interattivo=${power.isInteractive}, bloccato=${keyguard.isDeviceLocked})")

      // 2. si aspetta che il telefono si blocchi (lo fa l'esterno)
      val deadline = System.currentTimeMillis() + 120_000
      while (!keyguard.isDeviceLocked && System.currentTimeMillis() < deadline) Thread.sleep(250)
      assertTrue("il telefono non si e' bloccato entro 2 minuti", keyguard.isDeviceLocked)
      Log.i(TAG, "telefono BLOCCATO (interattivo=${power.isInteractive}, bloccato=${keyguard.isDeviceLocked}): provo il vault")

      // 3. a telefono bloccato: si legge, si salva, si rilegge
      val loaded = runBlocking { vault.load("nas") }
      assertTrue("a telefono bloccato la lettura deve funzionare, invece: $loaded", loaded is LoadResult.Found)
      (loaded as LoadResult.Found).credentials.use { assertEquals("prima-del-blocco", String(it.password.value)) }

      val second = runBlocking { NasCredentials("utente", "durante-il-blocco".toCharArray()).use { vault.save("nas", it) } }
      assertTrue("a telefono bloccato il salvataggio deve funzionare, invece: $second", second is SaveResult.Saved)
      (runBlocking { vault.load("nas") } as LoadResult.Found).credentials.use {
        assertEquals("durante-il-blocco", String(it.password.value))
      }
      assertTrue("il telefono doveva restare bloccato per tutta la prova", keyguard.isDeviceLocked)
      Log.i(TAG, "OK: cifratura e decifratura riuscite a schermo spento e telefono bloccato")
    } finally {
      runCatching { keys.delete(alias) }
      scope.cancel()
      dir.deleteRecursively()
    }
  }

  private companion object {
    const val TAG = "LockedDeviceIT"
  }
}
