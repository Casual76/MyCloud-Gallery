package dev.pampa.mycloudgallery.feature.integrations.provider

import android.app.AuthenticationRequiredException
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsProvider
import android.widget.Toast
import dev.pampa.mycloudgallery.feature.integrations.R
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentEntry
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentIds
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentReader
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.IntegrationPorts
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerActivity
import dev.pampa.mycloudgallery.feature.integrations.share.ShareStop
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
    val locked = when (decision()) {
      // CA-2 (Fase 10): con la versione fermata nessuna radice, come con l'app non pronta.
      EntryDecision.NOT_READY, EntryDecision.LOADING, EntryDecision.STOPPED -> return DocumentRows.roots(projection, emptyList(), "", null, 0)
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
      val documents = IntegrationPorts.documents
      // "Rimuovi la posizione" acceso: l'apertura puo' dover preparare la copia, che dura piu' di una lettura (e si annulla con il segnale).
      val deadline = if (documents.preparesOnOpen()) env.prepareDeadlineMs else env.openDeadlineMs
      val reader: DocumentReader = try {
        blocking(signal, deadline) { documents.open(id) }
      } catch (e: TimeoutException) {
        IntegrationCounters.add(IntegrationCounter.PROVIDER_OPEN_REFUSED)
        throw FileNotFoundException(if (documents.preparesOnOpen()) env.noLocationText(null) else "Il NAS non risponde.")
      } catch (e: OperationCanceledException) {
        throw e
      } catch (e: SecurityException) {
        throw e
      } catch (e: LocationRemovalRefused) {
        // Fallire chiusi: la posizione non si e' potuta togliere, quindi niente si consegna (mai l'originale). L'altra app riceve un errore e l'utente
        // vede perche'.
        IntegrationCounters.add(IntegrationCounter.PROVIDER_OPEN_REFUSED)
        val text = env.noLocationText(e.stop)
        env.tell(text)
        throw FileNotFoundException(text)
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
    when (decision()) {
      EntryDecision.ALLOWED -> Unit
      // CA-2 (Fase 10): con la versione fermata niente, nemmeno un documento gia' consegnato.
      EntryDecision.NOT_READY, EntryDecision.LOADING, EntryDecision.STOPPED -> {
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

  /**
   * Il cancello d'ingresso, **dopo** che l'app ha letto il suo stato (IN-3): a processo freddo il provider risponde a DocumentsUI mentre la
   * configurazione e le impostazioni del blocco si stanno ancora leggendo, e prima rispondeva "non pronta" (nessuna radice) senza che nessuno lo
   * correggesse. Qui si aspetta fino a [ProviderTuning.loadWaitMs] (il thread del binder puo' aspettare un attimo, mai a lungo); se ancora non
   * c'e' risposta vale "non pronta" e la radice appare appena lo stato e' letto (le radici si notificano a ogni cambio: `IntegrationsGraph`).
   */
  private fun decision(): EntryDecision {
    val gate = IntegrationPorts.entryGate
    val now = gate.decide()
    if (now != EntryDecision.LOADING) return now
    val waited = try {
      blocking(null) { gate.decideAfterLoading(ProviderTuning.loadWaitMs) }
    } catch (e: TimeoutException) {
      EntryDecision.LOADING
    } catch (e: OperationCanceledException) {
      EntryDecision.LOADING
    }
    return if (waited == EntryDecision.LOADING) EntryDecision.NOT_READY else waited
  }

  /** Chiama una funzione sospesa dal thread del binder, con la scadenza (E-X1). `TimeoutException` alla scadenza; [signal] la annulla. */
  private fun <T> blocking(signal: CancellationSignal?, deadlineMs: Long = env.openDeadlineMs, block: suspend () -> T): T {
    val job = SupervisorJob()
    signal?.setOnCancelListener { job.cancel() }
    try {
      return runBlocking(job) { withTimeout(deadlineMs) { block() } }
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
    val prepareDeadlineMs: Long = ProviderTuning.prepareDeadlineMs
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

    /** La frase per chi non riceve un file perche' la posizione non si toglie (senza nomi: solo il motivo). */
    fun noLocationText(stop: ShareStop?): String = context.getString(
      when (stop) {
        null -> R.string.integrations_documents_error_no_location_slow
        ShareStop.NO_SPACE, ShareStop.TOO_BIG, ShareStop.TOO_MANY -> R.string.integrations_documents_error_no_location_space
        ShareStop.NAS_UNREACHABLE -> R.string.integrations_documents_error_no_location_nas
        ShareStop.CANNOT_REMOVE_LOCATION, ShareStop.LOCATION_REMAINS -> R.string.integrations_documents_error_no_location
        else -> R.string.integrations_documents_error_no_location_failed
      },
    )

    /** Lo dice anche all'utente, che e' nell'altra app: un avviso breve (il testo non porta nomi). Mai un errore se non si puo'. */
    fun tell(text: String) {
      try {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
      } catch (_: RuntimeException) {
        // Resta l'errore all'altra app.
      }
    }
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
  @Volatile var prepareDeadlineMs: Long = DocumentPolicy.PREPARE_DEADLINE_MS
  @Volatile var loadWaitMs: Long = DocumentPolicy.LOAD_WAIT_MS

  fun reset() {
    callerChecks = null
    proxyOpener = null
    handlers = null
    slots = null
    readDeadlineMs = DocumentPolicy.READ_DEADLINE_MS
    openDeadlineMs = DocumentPolicy.OPEN_DEADLINE_MS
    prepareDeadlineMs = DocumentPolicy.PREPARE_DEADLINE_MS
    loadWaitMs = DocumentPolicy.LOAD_WAIT_MS
  }
}

/**
 * Il tocco che accompagna "serve lo sblocco" (E-X7): apre la schermata di sblocco del selettore (`PickerActivity` con [PickerActivity.ACTION_UNLOCK]),
 * non la `MainActivity` (IN-4: con il blocco "Subito" quella, tornando a DocumentsUI, richiudeva il blocco senza tolleranza e lo sblocco non finiva
 * mai; l'activity d'ingresso usa la finestra di tolleranza e restituisce un risultato a DocumentsUI). `PendingIntent` **immutabile** e con un intento
 * **esplicito** sul nostro componente: l'altra app non puo' cambiarne la destinazione (C17).
 */
internal fun unlockPendingIntent(context: Context): PendingIntent {
  val unlock = Intent(PickerActivity.ACTION_UNLOCK).setClassName(context.packageName, PickerActivity::class.java.name)
  return PendingIntent.getActivity(context, 0, unlock, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
