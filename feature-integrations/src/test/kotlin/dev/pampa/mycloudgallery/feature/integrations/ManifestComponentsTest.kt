package dev.pampa.mycloudgallery.feature.integrations

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Fase 5B (P0-B, D29, Q5 (a), C17): il manifest di questo modulo dichiara **i quattro componenti approvati dall'utente e nient'altro**, con le
 * esposizioni giuste. E' il controllo piu' vicino al sorgente; `Phase5BArchitectureTest` (core-common) fa lo stesso per tutti i moduli e
 * `verifyReleaseManifest` + `tools/check.ps1` lo rifanno sul manifest fuso e sull'APK di release.
 *
 * Cosa vieta: un componente in piu' (servizio, ricevitore, un'altra activity), un permesso, un filtro in piu' (un'azione, un tipo, una categoria),
 * un'activity `singleTask` o con l'affinita' del pacchetto, un provider dei documenti senza `MANAGE_DOCUMENTS`, un `FileProvider` esportato.
 */
class ManifestComponentsTest {

  private val manifest: Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement

  private val application: Element = children(manifest, "application").single()

  private fun children(parent: Element, tag: String): List<Element> =
    (0 until parent.childNodes.length).map { parent.childNodes.item(it) }
      .filter { it.nodeType == Node.ELEMENT_NODE && it.nodeName == tag }
      .map { it as Element }

  private fun attr(element: Element, name: String): String = element.getAttributeNS(ANDROID, name)

  private fun component(kind: String, name: String): Element =
    children(application, kind).single { attr(it, "name") == "$PACKAGE.$name" }

  /** Per ogni intent-filter: (azioni, categorie, tipi). */
  private fun filters(element: Element): List<Triple<Set<String>, Set<String>, Set<String>>> =
    children(element, "intent-filter").map { filter ->
      Triple(
        children(filter, "action").map { attr(it, "name") }.toSet(),
        children(filter, "category").map { attr(it, "name") }.toSet(),
        children(filter, "data").map { attr(it, "mimeType") }.toSet(),
      )
    }

  @Test
  fun ilManifestHaSoloIQuattroComponentiApprovatiENessunPermesso() {
    val kinds = listOf("activity", "activity-alias", "service", "receiver", "provider")
    val declared = kinds.flatMap { kind -> children(application, kind).map { kind to attr(it, "name") } }
    assertThat(declared).containsExactly(
      "activity" to "$PACKAGE.picker.PickerActivity",
      "activity" to "$PACKAGE.receive.ShareReceiverActivity",
      "provider" to "$PACKAGE.provider.MediaDocumentsProvider",
      "provider" to "$PACKAGE.share.IntegrationFileProvider",
    )
    // Nessun permesso Android nuovo (Q5): niente uses-permission, permission, uses-feature, queries, ne' servizi in primo piano.
    for (tag in listOf("uses-permission", "uses-permission-sdk-23", "permission", "uses-feature", "queries")) {
      assertThat(children(manifest, tag)).isEmpty()
    }
    assertThat(File("src/main/AndroidManifest.xml").readText().substringAfter("<application")).doesNotContain("MANAGE_MEDIA")
  }

  @Test
  fun ilSelettoreRispondeSoloAGetContentEPickDiImmaginiEVideo() {
    val picker = component("activity", "picker.PickerActivity")
    assertThat(attr(picker, "exported")).isEqualTo("true")
    val filters = filters(picker)
    assertThat(filters).hasSize(2)
    assertThat(filters.map { it.first }).containsExactly(setOf("android.intent.action.GET_CONTENT"), setOf("android.intent.action.PICK"))
    for ((actions, categories, types) in filters) {
      // IN-13: il `PICK` classico con un indirizzo del MediaStore porta i tipi del MediaStore; `GET_CONTENT` no.
      if (actions == setOf("android.intent.action.PICK")) {
        assertThat(types).containsExactly("image/*", "video/*", "vnd.android.cursor.dir/image", "vnd.android.cursor.dir/video")
      } else {
        assertThat(types).containsExactly("image/*", "video/*")
      }
      assertThat(categories - setOf("android.intent.category.DEFAULT", "android.intent.category.OPENABLE")).isEmpty()
      assertThat(categories).contains("android.intent.category.DEFAULT")
    }
    assertEntryActivity(picker)
  }

  @Test
  fun laRicezioneRispondeSoloASendESendMultipleDiImmaginiEVideo() {
    val receiver = component("activity", "receive.ShareReceiverActivity")
    assertThat(attr(receiver, "exported")).isEqualTo("true")
    val filters = filters(receiver)
    assertThat(filters.map { it.first }).containsExactly(setOf("android.intent.action.SEND"), setOf("android.intent.action.SEND_MULTIPLE"))
    for ((_, categories, types) in filters) {
      assertThat(types).containsExactly("image/*", "video/*")
      assertThat(categories).containsExactly("android.intent.category.DEFAULT")
    }
    assertEntryActivity(receiver, launchMode = "singleTop")
  }

  /**
   * Le due activity d'ingresso: l'affinita' vuota come la MainActivity (StrandHogg), mai `singleTask` (non restituirebbe un risultato). La ricezione e'
   * `singleTop` (campo, gruppo A, D1): resta nel task di chi la chiede, ma una condivisione nuova davanti a una vecchia arriva in `onNewIntent`.
   */
  private fun assertEntryActivity(activity: Element, launchMode: String = "") {
    assertThat(activity.hasAttributeNS(ANDROID, "taskAffinity")).isTrue()
    assertThat(attr(activity, "taskAffinity")).isEmpty()
    assertThat(attr(activity, "launchMode")).isEqualTo(launchMode) // il default (standard): nel task di chi le ha chieste
    assertThat(attr(activity, "excludeFromRecents")).isEqualTo("true")
  }

  @Test
  fun ilProviderDeiDocumentiEEsportatoMaSoloIlSistemaPuoInterrogarlo() {
    val provider = component("provider", "provider.MediaDocumentsProvider")
    assertThat(attr(provider, "exported")).isEqualTo("true")
    assertThat(attr(provider, "grantUriPermissions")).isEqualTo("true")
    assertThat(attr(provider, "permission")).isEqualTo("android.permission.MANAGE_DOCUMENTS")
    assertThat(filters(provider).map { it.first }).containsExactly(setOf("android.content.action.DOCUMENTS_PROVIDER"))
  }

  @Test
  fun ilFileProviderNonEEsportatoENonHaFiltri() {
    val provider = component("provider", "share.IntegrationFileProvider")
    assertThat(attr(provider, "exported")).isEqualTo("false")
    assertThat(attr(provider, "grantUriPermissions")).isEqualTo("true")
    assertThat(children(provider, "intent-filter")).isEmpty()
    val paths = children(provider, "meta-data").single { attr(it, "name") == "android.support.FILE_PROVIDER_PATHS" }
    assertThat(attr(paths, "resource")).isEqualTo("@xml/integration_file_paths")
  }

  private companion object {
    const val ANDROID = "http://schemas.android.com/apk/res/android"
    const val PACKAGE = "dev.pampa.mycloudgallery.feature.integrations"
  }
}
