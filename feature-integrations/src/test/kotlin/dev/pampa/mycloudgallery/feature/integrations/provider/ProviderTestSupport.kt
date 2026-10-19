package dev.pampa.mycloudgallery.feature.integrations.provider

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import org.robolectric.Robolectric

// Fase 5B (P10): il supporto dei test del provider dei documenti. Il chiamante e' scelto dal test (un'altra app, il selettore di sistema, chi ha
// un permesso su un documento), il "kernel" che legge un descrittore a richiesta e' il test stesso, e i thread dei descrittori non esistono.

/** Chi sta chiamando, scelto dal test. */
class ScriptedCaller : CallerChecks {
  @Volatile var own: Boolean = false
  @Volatile var manageDocuments: Boolean = false
  val grants: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

  override fun isOwnProcess(): Boolean = own

  override fun hasManageDocuments(): Boolean = manageDocuments

  override fun hasReadGrant(uri: Uri): Boolean = uri.toString() in grants

  /** Un'altra app qualunque: niente di niente. */
  fun stranger() {
    own = false
    manageDocuments = false
    grants.clear()
  }

  /** Il selettore di documenti di sistema. */
  fun documentsUi() {
    own = false
    manageDocuments = true
  }
}

/** L'apertura finta del descrittore: tiene il callback che il sistema leggerebbe e un file vero come descrittore. */
class FakeProxyOpener : ProxyOpener {
  @Volatile var fails: Boolean = false
  val callbacks: MutableList<ProxyFileDescriptorCallback> = java.util.Collections.synchronizedList(mutableListOf())
  private val files = mutableListOf<File>()

  override fun open(callback: ProxyFileDescriptorCallback, handler: Handler): ParcelFileDescriptor {
    if (fails) throw IOException("non si apre")
    callbacks += callback
    val file = File.createTempFile("proxy", ".bin").also { synchronized(files) { files += it } }
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
  }

  fun cleanup() = synchronized(files) { files.forEach { it.delete() } }
}

/** I thread dei descrittori sul looper principale, con il conto delle chiusure. */
class CountingHandlers : ProxyHandlerFactory {
  @Volatile var created = 0
  @Volatile var closed = 0

  override fun create(): ProxyHandler {
    created++
    return ProxyHandler(Handler(Looper.getMainLooper())) { closed++ }
  }
}

object ProviderTestSupport {

  /**
   * Esegue [block] in un altro thread e fallisce se non finisce entro [seconds]: una scadenza che manca (E-X1) deve far **fallire** il test,
   * non lasciarlo appeso fino al tetto di venti minuti di Gradle. Un'eccezione del blocco si rilancia cosi' com'e'.
   */
  fun <T> within(seconds: Long = 15, block: () -> T): T {
    var result: Result<T>? = null
    val thread = Thread { result = runCatching(block) }.also { it.isDaemon = true; it.start() }
    thread.join(seconds * 1_000)
    val done = result ?: throw AssertionError("Non e' finita entro $seconds secondi: manca una scadenza.")
    return done.getOrThrow()
  }
  val context: Context get() = ApplicationProvider.getApplicationContext()

  val authority: String get() = DocumentsAuthority.of(context)

  /** Un provider creato come lo crea il sistema, con le protezioni che pretende (`MANAGE_DOCUMENTS` e i permessi sugli URI). */
  fun provider(): MediaDocumentsProvider {
    val info = android.content.pm.ProviderInfo().apply {
      authority = DocumentsAuthority.of(context)
      exported = true
      grantUriPermissions = true
      readPermission = AndroidCallerChecks.MANAGE_DOCUMENTS
      writePermission = AndroidCallerChecks.MANAGE_DOCUMENTS
    }
    return Robolectric.buildContentProvider(MediaDocumentsProvider::class.java).create(info).get()
  }

  fun documentUri(documentId: String): Uri = DocumentsContract.buildDocumentUri(authority, documentId)

  fun childrenUri(documentId: String): Uri = DocumentsContract.buildChildDocumentsUri(authority, documentId)

  fun rootsUri(): Uri = DocumentsContract.buildRootsUri(authority)
}
