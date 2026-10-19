package dev.pampa.mycloudgallery.feature.integrations

import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.integrations.share.IntegrationFileProvider
import java.io.File
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.xmlpull.v1.XmlPullParser

/**
 * Fase 5B (P0-B, D29, C17): il `FileProvider` delle integrazioni serve **solo** `cacheDir/share/` e `cacheDir/edit/`. Se qualcuno allarga
 * `res/xml/integration_file_paths.xml` (o lo sostituisce con una radice come `files-path`) questi test falliscono: un file fuori da queste due
 * cartelle (`filesDir`, il database, `cacheDir/rewrite/` con le copie di sicurezza di `PhoneMediaRewriter`, `noBackupFilesDir/incoming/`) non
 * deve poter avere un URI.
 *
 * Non si prova con `FileProvider.getUriForFile` su file "fuori radice": su JVM/Windows il `FileProvider` di androidx confronta i percorsi con
 * `/` come separatore e rifiuta anche i file giusti, quindi l'asserzione sarebbe vuota. Si controllano invece le **radici configurate**, che
 * sono cio' che decide.
 */
@RunWith(AndroidJUnit4::class)
class FileProviderPathsTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  @Before
  fun registerTheProvider() {
    val info = ProviderInfo().apply {
      authority = AUTHORITY
      exported = false
      grantUriPermissions = true
      name = IntegrationFileProvider::class.java.name
      packageName = context.packageName
      applicationInfo = context.applicationInfo
      metaData = Bundle().apply { putInt(PATHS_META_DATA, R.xml.integration_file_paths) }
    }
    // Il PackageManager di Robolectric non conosce questo provider (l'autorita' di prova non e' quella del manifest): lo si dichiara come farebbe
    // il manifest, e poi lo si crea. Il FileProvider legge le path dal suo meta-data.
    shadowOf(context.packageManager).addOrUpdateProvider(info)
    Robolectric.buildContentProvider(IntegrationFileProvider::class.java).create(info)
  }

  @Test
  fun lePathDichiaranoSoloShareEEditInCache() {
    val parser = context.resources.getXml(R.xml.integration_file_paths)
    val entries = ArrayList<Triple<String, String, String>>()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG && parser.name != "paths") {
        entries += Triple(parser.name, parser.getAttributeValue(null, "name"), parser.getAttributeValue(null, "path"))
      }
      event = parser.next()
    }
    assertThat(entries).containsExactly(Triple("cache-path", "share", "share/"), Triple("cache-path", "edit", "edit/"))
  }

  @Test
  fun leRadiciConfigurateDelProviderSonoSoloLeDueCartelleDellaCache() {
    // Una prima richiesta fa leggere al FileProvider le path (e mette la strategia nella sua cache); poi si guardano le radici.
    val probe = File(context.cacheDir, "share/probe.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
    try {
      FileProvider.getUriForFile(context, AUTHORITY, probe)
    } catch (_: IllegalArgumentException) {
      // Su JVM/Windows rifiuta anche i file giusti (separatore): conta la strategia, non il risultato.
    }
    val strategies = try {
      FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as? Map<*, *>
    } catch (_: ReflectiveOperationException) {
      null
    }
    val strategy = strategies?.get(AUTHORITY)
    assumeNotNull(strategy)
    val roots = try {
      strategy!!.javaClass.getDeclaredField("mRoots").apply { isAccessible = true }.get(strategy) as? Map<*, *>
    } catch (_: ReflectiveOperationException) {
      null
    }
    assumeNotNull(roots)
    val cache = context.cacheDir.canonicalFile
    val configured = roots!!.entries.associate { (it.key as String) to (it.value as File).canonicalFile }
    assertThat(configured.keys).containsExactly("share", "edit")
    assertThat(configured.getValue("share")).isEqualTo(File(cache, "share"))
    assertThat(configured.getValue("edit")).isEqualTo(File(cache, "edit"))
    // Niente che arrivi alle copie di sicurezza delle riscritture, ai file in arrivo o ai dati dell'app.
    for (root in configured.values) {
      assertThat(root.path).doesNotContain("rewrite")
      assertThat(root.canonicalPath.startsWith(cache.canonicalPath + File.separator)).isTrue()
    }
  }

  private companion object {
    const val AUTHORITY = "test.integrations.files"

    /** Il nome del meta-data che il FileProvider cerca (`FileProvider.META_DATA_FILE_PROVIDER_PATHS`, privato). */
    const val PATHS_META_DATA = "android.support.FILE_PROVIDER_PATHS"
  }
}
