package dev.pampa.mycloudgallery.core.security

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * Le regole di backup dell'app (docs/sicurezza.md C16, E-O8): ESCLUDONO tutto sia dal backup cloud sia dal
 * trasferimento da telefono a telefono, e non includono niente. Le credenziali cifrate stanno in
 * `noBackupFilesDir`, che nessun backup copia, ma le regole sono la seconda barriera: se qualcuno le
 * allenta, questo test lo dice.
 */
class DataExtractionRulesTest {

  private val rules = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    .parse(File("../app/src/main/res/xml/data_extraction_rules.xml")).documentElement

  private fun excludedDomains(section: String): Set<String> {
    val nodes = rules.getElementsByTagName(section)
    assertThat(nodes.length).isEqualTo(1)
    val sectionElement = nodes.item(0) as Element
    assertThat(sectionElement.getElementsByTagName("include").length).isEqualTo(0)
    val excludes = sectionElement.getElementsByTagName("exclude")
    return (0 until excludes.length).map { i ->
      val e = excludes.item(i) as Element
      assertThat(e.getAttribute("path")).isEqualTo(".") // tutto il dominio, non un sottoinsieme
      e.getAttribute("domain")
    }.toSet()
  }

  @Test
  fun backupCloud_escludeOgniDominio() {
    assertThat(excludedDomains("cloud-backup")).containsExactly("root", "file", "database", "sharedpref", "external")
  }

  @Test
  fun trasferimentoTraDispositivi_escludeOgniDominio_ancheQuelliProtettiDelDispositivo() {
    assertThat(excludedDomains("device-transfer")).containsExactly(
      "root", "file", "database", "sharedpref", "external",
      "device_root", "device_file", "device_database", "device_sharedpref",
    )
  }

  @Test
  fun nonCiSonoAltreSezioni() {
    val children = (0 until rules.childNodes.length).map { rules.childNodes.item(it) }.filterIsInstance<Element>().map { it.tagName }
    assertThat(children).containsExactly("cloud-backup", "device-transfer")
  }
}
