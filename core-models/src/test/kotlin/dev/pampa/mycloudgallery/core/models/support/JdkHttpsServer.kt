package dev.pampa.mycloudgallery.core.models.support

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext

// Il server HTTPS di prova del JDK (`com.sun.net.httpserver.HttpsServer`), pilotato per riflessione. Perche' per riflessione: il modulo
// `jdk.httpserver` non fa parte di `java.se`, quindi ne' il compilatore Kotlin ne' javac (con il bootclasspath di Android che AGP
// mette ai test) vedono `com.sun.net.httpserver`; a RUNTIME, nella JVM che esegue i test, c'e' e il suo pacchetto e' esportato. Nessun
// tipo `com.sun` compare nelle firme qui sotto: il resto dei test non sa che e' per riflessione. Solo per i test della fase 6 (P3).

/** Lo scambio HTTP di una richiesta: le sole cose di `HttpExchange` che servono ai test, con gli stessi nomi. */
class TestExchange internal constructor(private val delegate: Any) : Closeable {
  private fun call(name: String, vararg args: Any?): Any? = Api.invoke(delegate, name, *args)

  val requestMethod: String get() = call("getRequestMethod") as String
  val requestURI: URI get() = call("getRequestURI") as URI
  val requestHeaders: RequestHeaders get() = RequestHeaders(call("getRequestHeaders"))
  val responseHeaders: ResponseHeaders get() = ResponseHeaders(call("getResponseHeaders"))
  val responseBody: OutputStream get() = call("getResponseBody") as OutputStream

  /** Come `HttpExchange.sendResponseHeaders`: lunghezza -1 = nessun corpo, 0 = a pezzi, altrimenti fissa. */
  fun sendResponseHeaders(status: Int, length: Long) {
    call("sendResponseHeaders", status, length)
  }

  override fun close() {
    call("close")
  }

  class RequestHeaders internal constructor(private val headers: Any?) {
    @Suppress("UNCHECKED_CAST")
    fun asMap(): Map<String, List<String>> =
      (headers as Map<String, List<String>>).entries.associate { it.key to it.value.toList() }

    fun getFirst(name: String): String? = asMap().entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
  }

  class ResponseHeaders internal constructor(private val headers: Any?) {
    @Suppress("UNCHECKED_CAST")
    fun add(name: String, value: String) {
      (headers as MutableMap<String, MutableList<String>>).getOrPut(name) { mutableListOf() }.add(value)
    }
  }

  /** I metodi pubblici delle classi esportate di `com.sun.net.httpserver`, trovati una volta. */
  internal object Api {
    private val exchangeClass = Class.forName("com.sun.net.httpserver.HttpExchange")
    private val methods = HashMap<String, Method>()

    fun invoke(target: Any, name: String, vararg args: Any?): Any? {
      val method = synchronized(methods) {
        methods.getOrPut(name) { exchangeClass.methods.first { it.name == name } }
      }
      try {
        return method.invoke(target, *args)
      } catch (e: InvocationTargetException) {
        throw e.targetException
      }
    }
  }
}

/** Un server HTTPS su 127.0.0.1 (porta casuale); [handle] decide la risposta di ogni richiesta. Un [IOException] e' un guasto voluto. */
class JdkHttpsServer(context: SSLContext, private val handle: (TestExchange) -> Unit) : Closeable {
  private val executor: ExecutorService = Executors.newCachedThreadPool()
  private val serverBase = Class.forName("com.sun.net.httpserver.HttpServer")
  private val server: Any

  init {
    val httpsServer = Class.forName("com.sun.net.httpserver.HttpsServer")
    val configurator = Class.forName("com.sun.net.httpserver.HttpsConfigurator")
    val handlerType = Class.forName("com.sun.net.httpserver.HttpHandler")
    server = httpsServer.getMethod("create", InetSocketAddress::class.java, Int::class.javaPrimitiveType)
      .invoke(null, InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    httpsServer.getMethod("setHttpsConfigurator", configurator).invoke(server, configurator.getConstructor(SSLContext::class.java).newInstance(context))
    serverBase.getMethod("setExecutor", java.util.concurrent.Executor::class.java).invoke(server, executor)
    val handler = Proxy.newProxyInstance(
      handlerType.classLoader ?: ClassLoader.getSystemClassLoader(),
      arrayOf(handlerType),
      InvocationHandler { _: Any, method: Method, args: Array<out Any?>? ->
        if (method.name == "handle") {
          val exchange = TestExchange(args!![0]!!)
          try {
            handle(exchange)
          } catch (e: IOException) {
            // Connessione chiusa a meta' corpo: voluto, il client lo vede come un'interruzione.
          } finally {
            exchange.close()
          }
        }
        null
      },
    )
    serverBase.getMethod("createContext", String::class.java, handlerType).invoke(server, "/", handler)
    serverBase.getMethod("start").invoke(server)
  }

  val port: Int
    get() = (serverBase.getMethod("getAddress").invoke(server) as InetSocketAddress).port

  override fun close() {
    serverBase.getMethod("stop", Int::class.javaPrimitiveType).invoke(server, 0)
    executor.shutdownNow()
  }
}
