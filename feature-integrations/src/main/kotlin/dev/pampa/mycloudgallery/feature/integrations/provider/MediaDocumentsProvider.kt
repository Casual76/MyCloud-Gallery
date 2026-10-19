package dev.pampa.mycloudgallery.feature.integrations.provider

import android.app.AuthenticationRequiredException
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsProvider
import dev.pampa.mycloudgallery.feature.integrations.R
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentEntry
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentIds
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentReader
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.IntegrationPorts
import java.io.FileNotFoundException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Il provider dei documenti (Fase 5B, D23, C17; docs/fase5-progetto.md §2.7): la radice "MyCloud Gallery" (Recenti, Cartelle NAS) nei selettori
 * di documenti (Gmail "Allega file", "Documento" di WhatsApp e Telegram). Esportato ma protetto da `MANAGE_DOCUMENTS` (solo DocumentsUI), con
 * `grantUriPermissions` (l'altra app entra solo con un permesso su uno URI, dato dal sistema dopo un tocco o dal nostro selettore), id opachi,
 * sola lettura, e mai i nascosti.
 *
 * **Cosa fa un'altra app qui**, e cosa no:
 *  - il sistema (`MANAGE_DOCUMENTS`) sfoglia radice e cartelle e apre i documenti; un'altra app qualunque non ottiene niente: `SecurityException`
 *    per ogni radice, elenco, documento e tipo, a meno che abbia un permesso di lettura su **quel** documento ([CallerGuard]);
 *  - con il blocco dell'app acceso lo sfoglio non c'e': `AuthenticationRequiredException` con un tocco che apre l'app (E-X7). I documenti gia'
 *    **consegnati** dal nostro selettore (dopo uno sblocco e un tocco) si servono anche con l'app richiusa, finche' l'id vale;
 *  - i dati sono di sola lettura: ogni modo di apertura diverso da "r" e' rifiutato; nessun documento ha flag di scrittura;
 *  - un media solo-NAS si apre con un **descrittore a richiesta** ([ProxyDescriptors]): letture a intervalli, scadenza a ogni lettura (E-X1),
 *    annullabili, mai tutto il file in memoria;
 *  - **un id che non e' nostro, scaduto o non opaco** risponde `FileNotFoundException`, mai un'altra cosa (nessun oracolo sui media).
 *
 * Le porte (`IntegrationPorts`) le installa `:app` (P14); finche' sono i null-object non c'e' radice e niente si apre.
 */
class MediaDocumentsProvider : DocumentsProvider() {

  private val env: Env by lazy { Env(requireNotNull(context) { "Provider non collegato." }) }

  override fun onCreate(): Boolean = true

  // --- Radici ed elenchi: solo il sistema -------------------------------------------------------------------------------

  override fun queryRoots(projection: Array<out String>?): Cursor {
    env.guard.requireSystem()
    IntegrationCounters.add(IntegrationCounter.PROVIDER_QUERIES)
    val locked = when (IntegrationPorts.entryGate.decide()) {
      EntryDecision.NOT_READY -> return DocumentRows.roots(projection, emptyList(), "", null, 0)
      EntryDecision.NEEDS_UNLOCK -> true
      EntryDecision.ALLOWED -> false
    }
    val roots = try {
      blocking(null) { IntegrationPorts.documents.roots() }
    } catch (e: Exception) {
      emptyList()
    }
    val context = env.context
    return DocumentRows.roots(
      projection,
      roots,
      title = context.getString(R.string.integrations_documents_root_title),
      summary = if (locked) context.getString(R.string.integrations_documents_locked_summary) else null,
      icon = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_gallery,
    )
  }

  override fun queryChildDocuments(parentDocumentId: String?, projection: Array<out String>?, sortOrder: String?): Cursor {
    env.guard.requireSystem()
    IntegrationCounters.add(IntegrationCounter.PROVIDER_QUERIES)
    val parent = parse(parentDocumentId)
    authorize(parent, listing = true)
    val documents = IntegrationPorts.documents
    val context = env.context
    try {
      val folder = blocking(null) { documents.entry(parent) }
      if (folder == null || !folder.isFolder) throw FileNotFoundException("Cartella non trovata.")
      val children = blocking(null) { documents.children(parent, DocumentPolicy.MAX_CHILDREN) }
      return DocumentRows.documents(projection, children)
    } catch (e: TimeoutException) {
      return DocumentRows.documents(projection, emptyList(), context.getString(R.string.integrations_documents_error_nas))
    } catch (e: FileNotFoundException) {
      throw e
    } catch (e: SecurityException) {
      throw e
    } catch (e: Exception) {
      return DocumentRows.documents(projection, emptyList(), context.getString(R.string.integrations_documents_error_generic))
    }
  }

  // --- Un documento: il sistema, o chi ha un permesso su QUESTO documento -----------------------------------------------

  override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
    env.guard.requireDocument(documentId)
    IntegrationCounters.add(IntegrationCounter.PROVIDER_QUERIES)
    val id = parse(documentId)
    authorize(id, listing = false)
    val entry: DocumentEntry = try {
      blocking(null) { IntegrationPorts.documents.entry(id) }
    } catch (e: TimeoutException) {
      throw FileNotFoundException("Il NAS non risponde.")
    } catch (e: SecurityException) {
      throw e
    } catch (e: Exception) {
      null
    } ?: throw FileNotFoundException("Documento non trovato.")
    return DocumentRows.documents(projection, listOf(entry))
  }

  override fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?): ParcelFileDescriptor {
    env.guard.requireDocument(documentId)
    if (mode != "r") {
      IntegrationCounters.add(IntegrationCounter.PROVIDER_OPEN_REFUSED)
      throw SecurityException("Solo lettura.")
    }
    signal?.throwIfCanceled()
    val id = parse(documentId)
    authorize(id, listing = false)
    if (!env.descriptors.acquire(env.openDeadlineMs)) {
      IntegrationCounters.add(IntegrationCounter.PROVIDER_OPEN_REFUSED)
      throw FileNotFoundException("Troppe letture insieme: riprova fra poco.")
    }
    var handedOver = false
    try {
      val reader: DocumentReader = try {
        blocking(signal) { IntegrationPorts.documents.open(id) }
      } catch (e: TimeoutException) {
        IntegrationCounters.add(IntegrationCounter.PROVIDER_OPEN_REFUSED)
        throw FileNotFoundException("Il NAS non risponde.")
      } catch (e: OperationCanceledException) {
        throw e
      } catch (e: SecurityException) {
        throw e
      } catch (e: Exception) {
        null
      } ?: run {
        IntegrationCounters.add(IntegrationCounter.PROVIDER_OPEN_REFUSED)
        throw FileNotFoundException("Documento non disponibile.")
      }
      signal?.throwIfCanceled()
      handedOver = true
      val descriptor = env.descriptors.open(reader, signal)
      IntegrationCounters.add(IntegrationCounter.PROVIDER_OPENED)
      return descriptor
    } finally {
      if (!handedOver) env.descriptors.release()
    }
  }

  // --- Cio' che non si fa -----------------------------------------------------------------------------------------------
  // Nessuna miniatura, nessuna ricerca, nessun "recente" di sistema, nessuna scrittura: le versioni di base lanciano
  // UnsupportedOperationException e i flag della radice e dei documenti non li annunciano.

  // --- Dentro ------------------------------------------------------------------------------------------------------------

  private fun parse(documentId: String?): DocumentId {
    if (documentId == null || !DocumentIds.isOpaque(documentId)) throw FileNotFoundException("Documento non trovato.")
    return DocumentId(documentId)
  }

  /**
   * Il cancello d'ingresso (E-X7): con l'app pronta e sbloccata si passa. Con l'app non pronta niente esiste. Con il blocco acceso lo sfoglio
   * chiede lo sblocco; un documento **consegnato** (sbloccato e toccato nel nostro selettore) si serve comunque finche' l'id vale.
   */
  private fun authorize(id: DocumentId, listing: Boolean) {
    when (IntegrationPorts.entryGate.decide()) {
      EntryDecision.ALLOWED -> Unit
      EntryDecision.NOT_READY -> {
        IntegrationCounters.add(IntegrationCounter.PROVIDER_OPEN_REFUSED)
        throw FileNotFoundException("Non disponibile.")
      }
      EntryDecision.NEEDS_UNLOCK -> {
        val delivered = !listing && (IntegrationPorts.documents as? DocumentIssuer)?.isDelivered(id) == true
        if (!delivered) {
          IntegrationCounters.add(IntegrationCounter.PROVIDER_LOCKED)
          throw env.authenticationRequired()
        }
      }
    }
  }

  /** Chiama una funzione sospesa dal thread del binder, con la scadenza (E-X1). `TimeoutException` alla scadenza; [signal] la annulla. */
  private fun <T> blocking(signal: CancellationSignal?, block: suspend () -> T): T {
    val job = SupervisorJob()
    signal?.setOnCancelListener { job.cancel() }
    try {
      return runBlocking(job) { withTimeout(env.openDeadlineMs) { block() } }
    } catch (e: TimeoutCancellationException) {
      throw TimeoutException("Scaduto.")
    } catch (e: CancellationException) {
      throw OperationCanceledException()
    } finally {
      signal?.setOnCancelListener(null)
    }
  }

  /** Tutto cio' che il provider si costruisce alla prima chiamata (il contesto c'e' solo dopo `attachInfo`). */
  private class Env(val context: Context) {
    val authority: String = DocumentsAuthority.of(context)
    val openDeadlineMs: Long = ProviderTuning.openDeadlineMs
    val guard = CallerGuard(ProviderTuning.callerChecks?.invoke(context) ?: AndroidCallerChecks(context), authority)
    val descriptors = ProxyDescriptors(
      opener = ProviderTuning.proxyOpener?.invoke(context) ?: StorageProxyOpener(context),
      handlers = ProviderTuning.handlers ?: HandlerThreads,
      slots = ProviderTuning.slots?.invoke() ?: DescriptorSlots(),
      readDeadlineMs = ProviderTuning.readDeadlineMs,
    )

    /** "Sblocca": un tocco che apre l'app (immutabile ed esplicito sul nostro pacchetto: C17). */
    fun authenticationRequired(): AuthenticationRequiredException =
      AuthenticationRequiredException(SecurityException("Sblocca l'app."), unlockPendingIntent(context))
  }
}

/**
 * I punti in cui i test sostituiscono le dipendenze di sistema del provider (il sistema lo crea con un costruttore senza argomenti: non si
 * inietta). In produzione sono tutti vuoti. Si leggono alla prima chiamata del provider, quindi si impostano **prima** di crearlo.
 */
internal object ProviderTuning {
  @Volatile var callerChecks: ((Context) -> CallerChecks)? = null
  @Volatile var proxyOpener: ((Context) -> ProxyOpener)? = null
  @Volatile var handlers: ProxyHandlerFactory? = null
  @Volatile var slots: (() -> DescriptorSlots)? = null
  @Volatile var readDeadlineMs: Long = DocumentPolicy.READ_DEADLINE_MS
  @Volatile var openDeadlineMs: Long = DocumentPolicy.OPEN_DEADLINE_MS

  fun reset() {
    callerChecks = null
    proxyOpener = null
    handlers = null
    slots = null
    readDeadlineMs = DocumentPolicy.READ_DEADLINE_MS
    openDeadlineMs = DocumentPolicy.OPEN_DEADLINE_MS
  }
}

/**
 * Il tocco che accompagna "serve lo sblocco" (E-X7): apre l'app (la sua schermata di blocco). `PendingIntent` **immutabile** e con un intento
 * **esplicito** sul nostro pacchetto: l'altra app non puo' cambiarne la destinazione (C17).
 */
internal fun unlockPendingIntent(context: Context): PendingIntent {
  val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
  return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
