package dev.pampa.mycloudgallery.feature.integrations.provider

import android.content.Context
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentReader
import java.util.concurrent.CancellationException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

// Fase 5B (P10, docs/fase5-progetto.md §2.7, D23, E-X1, E-X4, E-X6): il **descrittore a richiesta**. Un media solo-NAS non si scarica: l'altra
// app riceve un descrittore di file che il sistema legge a pezzi (`StorageManager.openProxyFileDescriptor`) chiamando [ReaderProxyCallback],
// che a sua volta legge solo quel pezzo dal [DocumentReader] (il NAS). Mai tutto il file in memoria: la memoria in volo e' il buffer che il
// sistema passa a `onRead` (qualche decina di KiB) per ogni descrittore aperto, e i descrittori aperti sono al massimo
// [DocumentPolicy.MAX_OPEN_DESCRIPTORS].
//
// Tre garanzie:
//  - **scadenza a ogni lettura** (E-X1): una lettura che non finisce entro [DocumentPolicy.READ_DEADLINE_MS] fallisce con `ETIMEDOUT`;
//  - **annullamento** (E-X4): il segnale di annullamento dell'apertura (e la chiusura del descrittore) ferma le letture in volo e le successive
//    falliscono con `ECANCELED`; alla chiusura il lettore si chiude, il thread esce, il posto si libera;
//  - **tetto** (E-X6): al massimo [DocumentPolicy.MAX_OPEN_DESCRIPTORS] aperti insieme; le aperture oltre aspettano un posto fino alla scadenza,
//    quelle oltre [DocumentPolicy.MAX_WAITING_OPENS] in attesa sono rifiutate subito.
//
// Il sistema chiama `onRead` su un thread nostro (uno per descrittore): qui si puo' bloccare, ma mai oltre la scadenza.

/** Il thread su cui il sistema chiama le letture di un descrittore, e come chiuderlo. */
class ProxyHandler(val handler: Handler, private val onClose: () -> Unit) {
  fun close() = onClose()
}

/** Fabbrica dei thread dei descrittori: una per i test (nessun thread vero). */
fun interface ProxyHandlerFactory {
  fun create(): ProxyHandler
}

/** Un thread vero per descrittore, con un nome generico (nessun dato dell'utente). */
object HandlerThreads : ProxyHandlerFactory {
  private val counter = AtomicInteger()

  override fun create(): ProxyHandler {
    val thread = HandlerThread("mcg-doc-" + counter.incrementAndGet()).also { it.start() }
    return ProxyHandler(Handler(thread.looper)) { thread.quitSafely() }
  }
}

/** Chi apre un descrittore che il sistema legge chiamando un callback nostro. Dietro un'interfaccia: Robolectric non ha un kernel che legga. */
fun interface ProxyOpener {
  fun open(callback: ProxyFileDescriptorCallback, handler: Handler): ParcelFileDescriptor
}

/** L'apertura vera: `StorageManager.openProxyFileDescriptor` in sola lettura (Android 8+). */
class StorageProxyOpener(context: Context) : ProxyOpener {
  private val storage = context.applicationContext.getSystemService(StorageManager::class.java)

  override fun open(callback: ProxyFileDescriptorCallback, handler: Handler): ParcelFileDescriptor =
    storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, handler)
}

/**
 * I posti dei descrittori aperti (E-X6). Una semaforo con un tetto sulle attese: ogni attesa blocca un thread del binder, quindi oltre
 * [maxWaiting] il rifiuto e' immediato.
 */
class DescriptorSlots(
  private val maxOpen: Int = DocumentPolicy.MAX_OPEN_DESCRIPTORS,
  private val maxWaiting: Int = DocumentPolicy.MAX_WAITING_OPENS,
) {
  private val permits = Semaphore(maxOpen, true)
  private val waiting = AtomicInteger()

  /** Quanti descrittori sono aperti adesso. */
  val inUse: Int get() = maxOpen - permits.availablePermits()

  /** Prende un posto, aspettando fino a [waitMs]; falso se non c'e' (scaduta l'attesa, o troppi gia' in attesa). */
  fun acquire(waitMs: Long): Boolean {
    if (permits.tryAcquire()) return true
    if (waiting.incrementAndGet() > maxWaiting) {
      waiting.decrementAndGet()
      return false
    }
    return try {
      permits.tryAcquire(waitMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      false
    } finally {
      waiting.decrementAndGet()
    }
  }

  fun release() = permits.release()
}

/**
 * Le letture di un descrittore a richiesta. Una per descrittore; il sistema la chiama su un thread solo, in serie.
 *
 * @param reader il documento: si chiude alla chiusura del descrittore (mai prima, mai due volte).
 * @param onClosed cio' che si libera alla chiusura: il thread e il posto.
 */
internal class ReaderProxyCallback(
  private val reader: DocumentReader,
  private val deadlineMs: Long,
  private val onClosed: () -> Unit,
) : ProxyFileDescriptorCallback() {

  private val job = SupervisorJob()
  private val cancelled = AtomicBoolean(false)
  private val closed = AtomicBoolean(false)

  override fun onGetSize(): Long = reader.size

  /**
   * Legge [size] byte da [offset] dentro [data] (il buffer del sistema: niente allocazioni per pezzo). Riempie sempre la richiesta, salvo la
   * fine del file: un pezzo piu' corto, per il sistema, e' la fine.
   */
  override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
    if (closed.get() || cancelled.get()) throw ErrnoException("onRead", OsConstants.ECANCELED)
    if (offset < 0 || size < 0 || size > data.size) throw ErrnoException("onRead", OsConstants.EINVAL)
    val total = reader.size
    if (size == 0 || offset >= total) return 0
    val wanted = minOf(size.toLong(), total - offset).toInt()
    var done = 0
    try {
      runBlocking(job) {
        withTimeout(deadlineMs) {
          while (done < wanted) {
            val n = reader.read(offset + done, data, done, wanted - done)
            if (n <= 0) break
            done += n
          }
        }
      }
    } catch (e: TimeoutCancellationException) {
      IntegrationCounters.add(IntegrationCounter.PROVIDER_READ_TIMEOUTS)
      throw ErrnoException("onRead", OsConstants.ETIMEDOUT)
    } catch (e: CancellationException) {
      IntegrationCounters.add(IntegrationCounter.PROVIDER_READ_CANCELLED)
      throw ErrnoException("onRead", OsConstants.ECANCELED)
    } catch (e: Exception) {
      IntegrationCounters.add(IntegrationCounter.PROVIDER_READ_ERRORS)
      throw ErrnoException("onRead", OsConstants.EIO)
    }
    return done
  }

  /** Il sistema ha chiuso il descrittore (o il processo dell'altra app e' morto). */
  override fun onRelease() = finish()

  /** Ferma le letture in volo e rifiuta le successive (E-X4). Non chiude il lettore: lo chiude [onRelease], dopo l'ultima lettura. */
  fun cancel() {
    cancelled.set(true)
    job.cancel()
  }

  /** Chiude tutto una volta sola: lettura in volo annullata, lettore chiuso, thread e posto liberati. */
  fun finish() {
    if (!closed.compareAndSet(false, true)) return
    job.cancel()
    try {
      reader.close()
    } catch (_: Exception) {
      // il lettore e' gia' chiuso o rotto: niente altro da fare
    }
    onClosed()
  }
}

/**
 * Apre i descrittori a richiesta e tiene i posti. Un'istanza per provider.
 *
 * Protocollo dei posti: il chiamante prende un posto con [acquire], apre il documento e chiama [open]; da quel momento [open] ne e' il
 * proprietario (lo libera alla chiusura del descrittore o se fallisce). Se il chiamante non arriva a [open], libera con [release].
 */
class ProxyDescriptors(
  private val opener: ProxyOpener,
  private val handlers: ProxyHandlerFactory = HandlerThreads,
  private val slots: DescriptorSlots = DescriptorSlots(),
  private val readDeadlineMs: Long = DocumentPolicy.READ_DEADLINE_MS,
) {

  /** Quanti descrittori sono aperti adesso. */
  val openCount: Int get() = slots.inUse

  fun acquire(waitMs: Long): Boolean = slots.acquire(waitMs)

  fun release() = slots.release()

  /**
   * Il descrittore di [reader]. Se [signal] scatta (prima o dopo l'apertura) le letture si fermano (E-X4). Su un errore chiude [reader] e libera
   * il posto.
   */
  fun open(reader: DocumentReader, signal: CancellationSignal?): ParcelFileDescriptor {
    val handler = try {
      handlers.create()
    } catch (e: Throwable) {
      reader.close()
      slots.release()
      throw e
    }
    val callback = ReaderProxyCallback(reader, readDeadlineMs) {
      handler.close()
      slots.release()
    }
    val descriptor = try {
      signal?.throwIfCanceled()
      opener.open(callback, handler.handler)
    } catch (e: Throwable) {
      callback.finish()
      throw e
    }
    try {
      signal?.setOnCancelListener { callback.cancel() }
      signal?.throwIfCanceled()
    } catch (e: OperationCanceledException) {
      callback.cancel()
      descriptor.close()
      callback.finish()
      throw e
    }
    return descriptor
  }
}
