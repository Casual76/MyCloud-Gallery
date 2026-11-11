package dev.pampa.mycloudgallery.describe

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.io.FileOutputStream
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * P2 (prestazioni sull'S25, 10/11): [SystemA11yActivity] sopra l'`AccessibilityManager` (quello di Robolectric). La memoria della risposta e' provata
 * in [RememberedA11yToolTest]; qui il legame con il sistema e **niente su disco** (D39: e' un dato sulla salute di una persona).
 */
@RunWith(RobolectricTestRunner::class)
class SystemA11yActivityTest {

  private val app: Application get() = ApplicationProvider.getApplicationContext()

  private fun manager(): AccessibilityManager = app.getSystemService(AccessibilityManager::class.java)

  @Test
  fun senzaServiziAccesi_no() {
    Shadows.shadowOf(manager()).setEnabled(false)
    val a11y = SystemA11yActivity(app)
    assertThat(a11y.toolEnabled()).isFalse()
    assertThat(a11y.toolEnabled()).isFalse()
  }

  @Test
  fun conUnServizioCheNonEUnoStrumento_no() {
    val shadow = Shadows.shadowOf(manager())
    shadow.setEnabled(true)
    shadow.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
    assertThat(SystemA11yActivity(app).toolEnabled()).isFalse()
  }

  @Test
  fun conUnoStrumentoAcceso_si() {
    val tool = AccessibilityServiceInfo()
    // `setAccessibilityTool` e' nascosto nell'SDK pubblico: se la piattaforma di prova non lo ha, la prova si salta.
    val marked = runCatching {
      AccessibilityServiceInfo::class.java.getMethod("setAccessibilityTool", Boolean::class.javaPrimitiveType).invoke(tool, true)
      tool.isAccessibilityTool
    }.getOrDefault(false)
    assumeTrue("AccessibilityServiceInfo.setAccessibilityTool non disponibile su questa piattaforma di prova", marked)
    val shadow = Shadows.shadowOf(manager())
    shadow.setEnabled(true)
    shadow.setEnabledAccessibilityServiceList(listOf(tool))
    assertThat(SystemA11yActivity(app).toolEnabled()).isTrue()
  }

  /** Un contesto che conta ogni richiesta di un posto dove scrivere (file, preferenze, database). */
  private class CountingContext(base: Context) : ContextWrapper(base) {
    var storageRequests = 0

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = super.getSharedPreferences(name, mode).also { storageRequests++ }

    override fun getFilesDir(): File = super.getFilesDir().also { storageRequests++ }

    override fun getNoBackupFilesDir(): File = super.getNoBackupFilesDir().also { storageRequests++ }

    override fun getCacheDir(): File = super.getCacheDir().also { storageRequests++ }

    override fun getDataDir(): File = super.getDataDir().also { storageRequests++ }

    override fun getDir(name: String?, mode: Int): File = super.getDir(name, mode).also { storageRequests++ }

    override fun getDatabasePath(name: String?): File = super.getDatabasePath(name).also { storageRequests++ }

    override fun getExternalFilesDir(type: String?): File? = super.getExternalFilesDir(type).also { storageRequests++ }

    override fun openFileOutput(name: String?, mode: Int): FileOutputStream = super.openFileOutput(name, mode).also { storageRequests++ }
  }

  @Test
  fun nienteSuDisco() {
    val shadow = Shadows.shadowOf(manager())
    shadow.setEnabled(true)
    shadow.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
    val context = CountingContext(app)
    val a11y = SystemA11yActivity(context)
    repeat(50) { a11y.toolEnabled() }
    shadow.setEnabled(false)
    repeat(50) { a11y.toolEnabled() }
    assertWithMessage("la risposta sta solo in memoria: nessuna cartella, preferenza o database chiesti al contesto").that(context.storageRequests).isEqualTo(0)
  }
}
