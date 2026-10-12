package dev.pampa.mycloudgallery.core.security

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * Il manifest di :core-security dichiara SOLO i due permessi documentati in docs/sicurezza.md 4-bis, toglie
 * quello vecchio di androidx.biometric e non ha nessun componente (activity, service, receiver, provider):
 * questo modulo non puo' aggiungere superficie esposta senza che un test lo dica.
 */
class ModuleManifestTest {

  private val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement

  private fun names(tag: String): List<Pair<String, String?>> =
    (0 until manifest.getElementsByTagName(tag).length).map { i ->
      val e = manifest.getElementsByTagName(tag).item(i) as Element
      e.getAttributeNS(ANDROID, "name") to e.getAttributeNS(TOOLS, "node").ifEmpty { null }
    }

  @Test
  fun permessi_soloQuelliDocumentati_eUSE_FINGERPRINT_rimosso() {
    assertThat(names("uses-permission")).containsExactly(
      "android.permission.HIDE_OVERLAY_WINDOWS" to null,
      "android.permission.USE_BIOMETRIC" to null,
      "android.permission.USE_FINGERPRINT" to "remove",
    )
  }

  @Test
  fun nessunComponente() {
    for (tag in listOf("application", "activity", "activity-alias", "service", "receiver", "provider", "intent-filter")) {
      assertThat(manifest.getElementsByTagName(tag).length).isEqualTo(0)
    }
  }

  private companion object {
    const val ANDROID = "http://schemas.android.com/apk/res/android"
    const val TOOLS = "http://schemas.android.com/tools"
  }
}
