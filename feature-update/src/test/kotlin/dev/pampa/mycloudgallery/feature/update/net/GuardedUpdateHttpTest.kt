package dev.pampa.mycloudgallery.feature.update.net

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.update.source.UpdateSourceConfig
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pacchetto U (C482, C483): il client avvolto. Connessioni finte (nessuna rete): ogni indirizzo che il client apre si registra, cosi' si vede che un
 * rimando fuori elenco non viene **nemmeno aperto**.
 */
class GuardedUpdateHttpTest {

  /** Una risposta finta: codice, intestazioni, corpo. */
  private class Reply(val code: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0), val declaredLength: Long = body.size.toLong())

  private class FakeConnection(url: URL, private val reply: Reply) : HttpURLConnection(url) {
    val sent = mutableMapOf<String, String>()
    var disconnected = false

    override fun setRequestProperty(key: String, value: String) {
      sent[key] = value
    }

    override fun getRequestProperty(key: String): String? = sent[key]

    override fun getResponseCode(): Int = reply.code

    override fun getHeaderField(name: String): String? = reply.headers[name]

    override fun getContentLengthLong(): Long = reply.declaredLength

    override fun getInputStream(): InputStream = ByteArrayInputStream(reply.body)

    override fun connect() = Unit

    override fun disconnect() {
      disconnected = true
    }

    override fun usingProxy(): Boolean = false
  }

  private val opened = mutableListOf<FakeConnection>()
  private val replies = mutableMapOf<String, Reply>()
  private var cookies = false
  private val dir: File = Files.createTempDirectory("aggiornamento").toFile()

  @After
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private fun http(maxManifest: Int = 1024, maxDownload: Long = 1_000_000L) = GuardedUpdateHttp(
    agent = UpdateSourceConfig.userAgent("1.0.0"),
    manifestHosts = UpdateSourceConfig.MANIFEST_HOSTS,
    downloadHosts = UpdateSourceConfig.DOWNLOAD_HOSTS,
    maxManifestBytes = maxManifest,
    maxDownloadBytes = maxDownload,
    maxRedirects = UpdateSourceConfig.MAX_REDIRECTS,
    opener = { url ->
      val reply = replies[url] ?: error("indirizzo non previsto dalla prova: $url")
      FakeConnection(URL(url), reply).also { opened += it }
    },
    cookieHandlerInstalled = { cookies },
    io = Dispatchers.Unconfined,
  )

  private val manifest = UpdateSourceConfig.MANIFEST_URL
  private val asset = UpdateSourceConfig.downloadUrl("stable-mycloud-gallery-v1.1.0", "mycloud-gallery-1.1.0.apk")
  private val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc?sp=r&sig=xyz"

  // --- Il cancello degli host ----------------------------------------------------------------------------------------------------------------------

  @Test
  fun ilCancelloAmmetteSoloHttpsVersoGliHostDellElenco() {
    val hosts = UpdateSourceConfig.DOWNLOAD_HOSTS
    assertThat(HostGate.admit(asset, hosts)).isEqualTo(asset)
    assertThat(HostGate.admit(asset.replace("https://", "http://"), hosts)).isNull()
    assertThat(HostGate.admit("https://github.com.evil.example/x.apk", hosts)).isNull()
    assertThat(HostGate.admit("https://evilgithub.com/x.apk", hosts)).isNull()
    assertThat(HostGate.admit("https://user:pw@github.com/x.apk", hosts)).isNull()
    assertThat(HostGate.admit("https://github.com:8443/x.apk", hosts)).isNull()
    assertThat(HostGate.admit("https://github.com:443/x.apk", hosts)).isNotNull()
    assertThat(HostGate.admit("https://github.com/x.apk#frammento", hosts)).isNull()
    assertThat(HostGate.admit("https://GITHUB.com/x.apk", hosts)).isNotNull()
    // L'elenco non scarica dagli host dello scaricamento, e viceversa.
    assertThat(HostGate.admit(manifest, hosts)).isNull()
    assertThat(HostGate.admit(asset, UpdateSourceConfig.MANIFEST_HOSTS)).isNull()
  }

  @Test
  fun unRimandoRelativoSiRisolveSullIndirizzoDiAdesso() {
    val hosts = UpdateSourceConfig.DOWNLOAD_HOSTS
    assertThat(HostGate.redirect(asset, "/Casual76/MyCloud-Gallery/x.apk", hosts)).isEqualTo("https://github.com/Casual76/MyCloud-Gallery/x.apk")
    assertThat(HostGate.redirect(asset, "https://evil.example/x.apk", hosts)).isNull()
    assertThat(HostGate.redirect(asset, "http://release-assets.githubusercontent.com/x", hosts)).isNull()
    assertThat(HostGate.redirect(asset, null, hosts)).isNull()
    assertThat(HostGate.redirect(asset, "", hosts)).isNull()
  }

  // --- L'elenco ------------------------------------------------------------------------------------------------------------------------------------

  @Test
  fun lElencoSiLeggeConUnoUserAgentGenericoESenzaAltriHeader() = runBlocking {
    replies[manifest] = Reply(200, body = """{"app":{}}""".toByteArray())
    val text = http().readText(manifest, mapOf("Authorization" to "token segreto", "Cookie" to "a=b"))
    assertThat(text).isEqualTo("""{"app":{}}""")
    val sent = opened.single().sent
    assertThat(sent["User-Agent"]).isEqualTo("MyCloudGallery/1.0.0")
    assertThat(sent.keys).containsExactly("User-Agent", "Accept")
    assertThat(opened.single().instanceFollowRedirects).isFalse()
    assertThat(opened.single().useCaches).isFalse()
  }

  @Test
  fun unElencoChiRimandaFuoriElencoNonSiSegue() {
    replies[manifest] = Reply(302, mapOf("Location" to "https://evil.example/manifest.json"))
    val failure = assertThrows(UpdateNetException::class.java) { runBlocking { http().readText(manifest) } }
    assertThat(failure.refusal).isEqualTo(UpdateNetRefusal.HOST)
    // L'indirizzo cattivo non e' stato nemmeno aperto.
    assertThat(opened.map { it.url.toString() }).containsExactly(manifest)
  }

  @Test
  fun unElencoAssente_404_eUnErrore() {
    // Finche' il repository e' privato l'elenco risponde 404: un errore (che all'avvio non si vede), mai «nessun aggiornamento» inventato.
    replies[manifest] = Reply(404)
    val failure = assertThrows(UpdateNetException::class.java) { runBlocking { http().readText(manifest) } }
    assertThat(failure.refusal).isEqualTo(UpdateNetRefusal.HTTP_STATUS)
  }

  @Test
  fun unElencoTroppoGrandeSiFerma() {
    replies[manifest] = Reply(200, body = ByteArray(2048) { 'a'.code.toByte() })
    val failure = assertThrows(UpdateNetException::class.java) { runBlocking { http(maxManifest = 1024).readText(manifest) } }
    assertThat(failure.refusal).isEqualTo(UpdateNetRefusal.TOO_LARGE)
  }

  @Test
  fun conUnGestoreDeiCookieInstallatoNonSiParte() {
    cookies = true
    replies[manifest] = Reply(200, body = "{}".toByteArray())
    val failure = assertThrows(UpdateNetException::class.java) { runBlocking { http().readText(manifest) } }
    assertThat(failure.refusal).isEqualTo(UpdateNetRefusal.COOKIE_HANDLER)
    assertThat(opened).isEmpty()
  }

  // --- L'APK ---------------------------------------------------------------------------------------------------------------------------------------

  @Test
  fun lApkSiScaricaDaGithubAttraversoIlRimandoAmmesso() = runBlocking {
    val bytes = ByteArray(300_000) { (it % 251).toByte() }
    replies[asset] = Reply(302, mapOf("Location" to cdn))
    replies[cdn] = Reply(200, body = bytes)
    val seen = mutableListOf<Float>()
    val file = http().download(asset, File(dir, "mycloud-gallery-1.1.0.apk"), bytes.size.toLong(), emptyMap()) { progress, _, _ -> seen += progress }
    assertThat(file.readBytes()).isEqualTo(bytes)
    assertThat(seen.last()).isEqualTo(1f)
    assertThat(opened.map { it.url.toString() }).containsExactly(asset, cdn).inOrder()
    assertThat(opened.last().sent["Accept-Encoding"]).isEqualTo("identity")
    assertThat(File(dir, "mycloud-gallery-1.1.0.apk.part").exists()).isFalse()
  }

  @Test
  fun unRimandoDelloScaricamentoFuoriElencoNonSiSegue() {
    replies[asset] = Reply(302, mapOf("Location" to "https://evil.example/app.apk"))
    val failure = assertThrows(UpdateNetException::class.java) {
      runBlocking { http().download(asset, File(dir, "a.apk"), 10, emptyMap()) { _, _, _ -> } }
    }
    assertThat(failure.refusal).isEqualTo(UpdateNetRefusal.HOST)
    assertThat(opened.map { it.url.toString() }).containsExactly(asset)
    assertThat(dir.listFiles().orEmpty().toList()).isEmpty()
  }

  @Test
  fun troppiRimandiSiFermano() {
    val second = "https://github.com/b"
    val third = "https://github.com/c"
    val fourth = "https://github.com/d"
    replies[asset] = Reply(302, mapOf("Location" to second))
    replies[second] = Reply(302, mapOf("Location" to third))
    replies[third] = Reply(302, mapOf("Location" to fourth))
    replies[fourth] = Reply(302, mapOf("Location" to asset))
    val failure = assertThrows(UpdateNetException::class.java) {
      runBlocking { http().download(asset, File(dir, "a.apk"), 10, emptyMap()) { _, _, _ -> } }
    }
    assertThat(failure.refusal).isEqualTo(UpdateNetRefusal.REDIRECT_LIMIT)
  }

  @Test
  fun unFileCheSuperaIlPesoAnnunciatoSiFermaENonResta() {
    replies[asset] = Reply(200, body = ByteArray(20), declaredLength = -1)
    val failure = assertThrows(UpdateNetException::class.java) {
      runBlocking { http().download(asset, File(dir, "a.apk"), 10, emptyMap()) { _, _, _ -> } }
    }
    assertThat(failure.refusal).isEqualTo(UpdateNetRefusal.TOO_LARGE)
    assertThat(dir.listFiles().orEmpty().toList()).isEmpty()
  }

  @Test
  fun unFileTroncatoOConUnPesoDichiaratoDiversoNonPassa() {
    replies[asset] = Reply(200, body = ByteArray(5), declaredLength = -1)
    val short = assertThrows(UpdateNetException::class.java) {
      runBlocking { http().download(asset, File(dir, "a.apk"), 10, emptyMap()) { _, _, _ -> } }
    }
    assertThat(short.refusal).isEqualTo(UpdateNetRefusal.SIZE)
    replies[asset] = Reply(200, body = ByteArray(10), declaredLength = 11)
    val declared = assertThrows(UpdateNetException::class.java) {
      runBlocking { http().download(asset, File(dir, "a.apk"), 10, emptyMap()) { _, _, _ -> } }
    }
    assertThat(declared.refusal).isEqualTo(UpdateNetRefusal.SIZE)
    assertThat(dir.listFiles().orEmpty().toList()).isEmpty()
  }

  @Test
  fun senzaUnPesoAmmessoNonSiApreNiente() {
    for (expected in listOf(0L, -1L, 1_000_001L)) {
      val failure = assertThrows(UpdateNetException::class.java) {
        runBlocking { http(maxDownload = 1_000_000L).download(asset, File(dir, "a.apk"), expected, emptyMap()) { _, _, _ -> } }
      }
      assertThat(failure.refusal).isEqualTo(UpdateNetRefusal.SIZE)
    }
    assertThat(opened).isEmpty()
  }

  @Test
  fun unIndirizzoDiPartenzaFuoriElencoNonSiApre() {
    val failure = assertThrows(UpdateNetException::class.java) {
      runBlocking { http().download("https://evil.example/app.apk", File(dir, "a.apk"), 10, emptyMap()) { _, _, _ -> } }
    }
    assertThat(failure.refusal).isEqualTo(UpdateNetRefusal.HOST)
    assertThat(opened).isEmpty()
  }
}
