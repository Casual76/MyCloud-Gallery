package dev.pampa.mycloudgallery.feature.widget

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * Cio' che il widget dichiara (Fase 10, P3, D6, D8, D10, D40, Q4 (a)), letto dai **sorgenti**: `verifyReleaseManifest` pretende le stesse cose sul manifest fuso dell'APK
 * (`widgetPolicy`, `gradle/verify-release-manifest.gradle`). Il ricevitore e' **uno solo, non esportato, senza permessi**; il widget dichiara **solo la Home**
 * (`home_screen`, mai `keyguard`), nessuna sveglia (`updatePeriodMillis="0"`) e ha un'anteprima disegnata.
 */
class WidgetManifestTest {

  private val android = "http://schemas.android.com/apk/res/android"

  private fun parse(path: String): org.w3c.dom.Document {
    val file = File(path)
    assertThat(file.isFile).isTrue()
    return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
  }

  private fun Element.attr(name: String): String? = if (hasAttributeNS(android, name)) getAttributeNS(android, name) else null

  @Test
  fun ilManifestHaSoloUnRicevitoreNonEsportatoESenzaPermessi() {
    val doc = parse("src/main/AndroidManifest.xml")
    val receivers = doc.getElementsByTagName("receiver")
    assertThat(receivers.length).isEqualTo(1)
    val receiver = receivers.item(0) as Element
    assertThat(receiver.attr("name")).isEqualTo("dev.pampa.mycloudgallery.feature.widget.glance.GalleryWidgetReceiver")
    assertThat(receiver.attr("exported")).isEqualTo("false")
    assertThat(receiver.attr("permission")).isNull()
    // Nient'altro: nessuna activity, servizio, provider, permesso.
    for (tag in listOf("activity", "activity-alias", "service", "provider", "uses-permission", "permission", "uses-feature", "queries")) {
      assertThat(doc.getElementsByTagName(tag).length).isEqualTo(0)
    }
    // Un solo intent-filter, quello del sistema dei widget, e il file del widget come metadato.
    val actions = receiver.getElementsByTagName("action")
    assertThat((0 until actions.length).map { (actions.item(it) as Element).attr("name") }).containsExactly("android.appwidget.action.APPWIDGET_UPDATE")
    val meta = receiver.getElementsByTagName("meta-data").item(0) as Element
    assertThat(meta.attr("name")).isEqualTo("android.appwidget.provider")
    assertThat(meta.attr("resource")).isEqualTo("@xml/gallery_widget_info")
  }

  @Test
  fun ilWidgetStaSoloSullaHomeESenzaSveglie() {
    for (path in listOf("src/main/res/xml/gallery_widget_info.xml", "src/main/res/xml-v31/gallery_widget_info.xml")) {
      val provider = parse(path).documentElement
      // Solo la Home, mai la schermata di blocco ne' lo hub dei widget a telefono bloccato (Q4 (a), D8).
      assertThat(provider.attr("widgetCategory")).isEqualTo("home_screen")
      assertThat(provider.attr("widgetCategory")).doesNotContain("keyguard")
      // Nessuna sveglia di Android: il ridisegno e' del lavoro periodico e degli eventi (D6).
      assertThat(provider.attr("updatePeriodMillis")).isEqualTo("0")
      // L'anteprima e' un disegno fisso (E-W18), e si puo' ridimensionare (le tre misure).
      assertThat(provider.attr("previewImage")).isEqualTo("@drawable/widget_preview")
      assertThat(provider.attr("resizeMode")).isEqualTo("horizontal|vertical")
      assertThat(provider.attr("minWidth")).isEqualTo("110dp")
      assertThat(provider.attr("minHeight")).isEqualTo("110dp")
      // Nessuna activity di configurazione: il widget nasce gia' configurato (le scelte sono nelle Impostazioni).
      assertThat(provider.attr("configure")).isNull()
    }
    val v31 = parse("src/main/res/xml-v31/gallery_widget_info.xml").documentElement
    assertThat(v31.attr("previewLayout")).isEqualTo("@layout/widget_preview")
    assertThat(v31.attr("targetCellWidth")).isEqualTo("2")
    assertThat(v31.attr("targetCellHeight")).isEqualTo("2")
  }

  @Test
  fun lAnteprimaEIlLayoutInizialeNonHannoFotoNeDati() {
    // L'anteprima e' fatta di forme (un vettore) e il layout a layout ne mostra solo l'immagine: nessun file di foto in `res/`.
    val res = File("src/main/res")
    val files = res.walkTopDown().filter { it.isFile }.map { it.extension.lowercase() }.toSet()
    assertThat(files - setOf("xml")).isEmpty()
    assertThat(File("src/main/res/drawable/widget_preview.xml").readText()).contains("<vector")
  }
}
