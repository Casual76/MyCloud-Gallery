package dev.pampa.mycloudgallery.core.common.testing

import java.io.File

/**
 * Il controllo "nessuna rete" della Fase 3 (D24, checklist "nessun traffico" di docs/sicurezza.md): legge i
 * sorgenti di produzione di un modulo e riporta ogni uso di rete.
 *
 * **Perche' un controllo sui sorgenti e non sul manifest.** L'app ha il permesso `INTERNET` (parla con il NAS,
 * Fase 2), quindi il manifest non puo' vietare niente al codice della galleria del telefono. Il rischio vero e' un
 * `DefaultDataSource` di Media3 che, per comodita', apre anche `https://`, o un caricatore generico di immagini:
 * entrano con una riga e con la rete spenta nessuno se ne accorge. Qui si vieta il nome stesso.
 *
 * Ogni modulo nuovo della Fase 3 ha un `NoNetworkSourcesTest` che chiama [violations] sulla propria `src/main`.
 * Commenti e KDoc non contano (devono poter spiegare cosa si vieta); le stringhe si.
 *
 * Sta nel package `testing` di core-common come i fake: `tools/check.ps1` vieta questi package nel dex di release.
 */
object NoNetworkSources {

  /** Un nome vietato e il perche'. */
  class Forbidden(val pattern: Regex, val why: String)

  /** I nomi vietati nei sorgenti di produzione dei moduli della Fase 3. */
  val FORBIDDEN: List<Forbidden> = listOf(
    Forbidden(Regex("""\bjava\.net\b"""), "java.net (socket, URL, InetAddress)"),
    Forbidden(Regex("""\bjavax\.net\b"""), "javax.net (SSL)"),
    Forbidden(Regex("""\bandroid\.net\.http\b"""), "android.net.http (HttpEngine)"),
    Forbidden(Regex("""\b(HttpURLConnection|HttpsURLConnection|URLConnection)\b"""), "connessioni HTTP di java.net"),
    Forbidden(Regex("""\bokhttp3\b"""), "OkHttp"),
    Forbidden(Regex("""\bretrofit2\b"""), "Retrofit"),
    Forbidden(Regex("""\bio\.ktor\b"""), "Ktor"),
    Forbidden(Regex("""\bDefaultDataSource\b"""), "DefaultDataSource di Media3 (apre anche http/https)"),
    Forbidden(Regex("""\bDefaultHttpDataSource\b"""), "DefaultHttpDataSource di Media3"),
    Forbidden(Regex("""\b(OkHttpDataSource|CronetDataSource)\b"""), "sorgenti di rete di Media3"),
    Forbidden(Regex("""\b(coil3?|com\.bumptech\.glide)\b"""), "caricatori di immagini con rete (D5: pipeline propria)"),
  )

  /**
   * Le violazioni sotto [sourceRoot] (di solito `File("src/main")`, relativa alla cartella del modulo, che e' la
   * cartella di lavoro dei test): una riga `percorso:riga: perche'` per ognuna. Vuoto = nessuna rete.
   * Una cartella che non esiste da' un errore: un controllo che non vede i sorgenti non controlla niente.
   */
  fun violations(sourceRoot: File): List<String> {
    require(sourceRoot.isDirectory) { "Non trovo i sorgenti da controllare: ${sourceRoot.path}" }
    val found = mutableListOf<String>()
    sourceRoot.walkTopDown()
      .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
      .sortedBy { it.path }
      .forEach { file ->
        val code = stripComments(file.readText())
        code.lineSequence().forEachIndexed { index, line ->
          FORBIDDEN.filter { it.pattern.containsMatchIn(line) }.forEach {
            found += "${file.relativeTo(sourceRoot).path.replace('\\', '/')}:${index + 1}: ${it.why}"
          }
        }
      }
    return found
  }

  /**
   * Toglie i commenti (`//` e `/* */`, anche KDoc) lasciando le righe al loro posto, cosi' i numeri di riga restano
   * quelli del file. Le stringhe sono rispettate: un `//` dentro `"https://..."` non e' un commento.
   */
  internal fun stripComments(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    var inString = false
    var inChar = false
    var blockDepth = 0
    var lineComment = false
    while (i < source.length) {
      val c = source[i]
      val next = if (i + 1 < source.length) source[i + 1] else '\u0000'
      when {
        lineComment -> {
          if (c == '\n') {
            lineComment = false
            out.append(c)
          }
        }
        blockDepth > 0 -> {
          when {
            c == '/' && next == '*' -> { blockDepth++; i++ }
            c == '*' && next == '/' -> { blockDepth--; i++ }
            c == '\n' -> out.append(c)
          }
        }
        inString -> {
          out.append(c)
          if (c == '\\' && next != '\u0000') { out.append(next); i++ } else if (c == '"') inString = false
        }
        inChar -> {
          out.append(c)
          if (c == '\\' && next != '\u0000') { out.append(next); i++ } else if (c == '\'') inChar = false
        }
        c == '/' && next == '/' -> { lineComment = true; i++ }
        c == '/' && next == '*' -> { blockDepth = 1; i++ }
        c == '"' -> { inString = true; out.append(c) }
        c == '\'' -> { inChar = true; out.append(c) }
        else -> out.append(c)
      }
      i++
    }
    return out.toString()
  }
}
