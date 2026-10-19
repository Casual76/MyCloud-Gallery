package dev.pampa.mycloudgallery.debugtools.probes

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** In che modo la prova T-14 consegna un media all'altra app (la scelta che T-14 deve decidere, `docs/android.md` 5.3 punto 3). */
internal enum class FieldProbeForm(val code: Long) {
  /** Il descrittore diretto del MediaStore (come per un media del telefono). */
  MEDIASTORE(1),

  /** Un descrittore a richiesta (`StorageManager.openProxyFileDescriptor`): il modo di servire un media solo-NAS in streaming, con la latenza simulata. */
  PROXY(2),

  /** Una copia in cache fatta prima e servita da file (il ripiego "scaricare prima"). */
  CACHE_COPY(3),
  ;

  companion object {
    fun of(code: Long?): FieldProbeForm = entries.firstOrNull { it.code == code } ?: PROXY
  }
}

/**
 * SOLO build debug (Fase 5B, P0-B; docs/android.md T-14): cio' che serve un media di prova a un'altra app, nelle tre forme di [FieldProbeForm], e
 * **misura come lo legge l'altra app** (Gmail, WhatsApp, Telegram...): quante aperture, quante letture, quanti byte, dopo quanto tempo la prima,
 * quanti salti all'indietro, se e' arrivata in fondo al file e quanto e' durata la copia. Sono i numeri che decidono se un media solo-NAS si serve
 * in streaming o scaricandolo prima. La [latencyMs] simula la rete: ogni lettura dorme quel tanto (il NAS vero dara' ~9-16 MB/s con 20-40 ms di
 * latenza per richiesta).
 *
 * Serve solo righe di [FieldProbePhone] (le cartelle di prova): un `_ID` che non c'e' li' non si apre mai.
 */
internal class FieldProbeServer(context: Context, private val phone: FieldProbePhone, private val store: FieldProbeStore) {

  private val appContext = context.applicationContext
  private val resolver = appContext.contentResolver

  private val thread: HandlerThread by lazy { HandlerThread("field-probe-serve").also { it.start() } }
  private val handler: Handler by lazy { Handler(thread.looper) }

  /** La forma scelta nella schermata, e la latenza simulata per ogni lettura (ms). */
  val form: FieldProbeForm get() = FieldProbeForm.of(store.get(FieldProbeKey.T14_FORM))

  private val latencyMs: Long get() = store.get(FieldProbeKey.T14_LATENCY_MS) ?: 0L

  /** La riga di prova con questo `_ID`, o null. */
  fun rowOf(id: Long): ProbeRow? = phone.rows().firstOrNull { it.id == id && !it.isTrashed }

  /** Il nome finto che l'altra app vede: mai il nome vero del file. */
  fun displayNameOf(row: ProbeRow): String = "probe-${row.id}.${extensionOf(row.mime)}"

  /** Apre [row] come vuole la forma scelta. `FileNotFoundException` se non si riesce. */
  @Throws(FileNotFoundException::class)
  fun open(row: ProbeRow, caller: Long): ParcelFileDescriptor {
    store.add(FieldProbeKey.T14_SERVE_OPENS)
    store.set(FieldProbeKey.T14_DOCS_CALLER, caller)
    return when (form) {
      FieldProbeForm.MEDIASTORE -> direct(row)
      FieldProbeForm.CACHE_COPY -> copied(row)
      FieldProbeForm.PROXY -> proxied(row)
    }
  }

  private fun direct(row: ProbeRow): ParcelFileDescriptor {
    val uri = phone.uriOf(row)
    val target = if (row.isVideo) uri else android.provider.MediaStore.setRequireOriginal(uri)
    return resolver.openFileDescriptor(target, "r") ?: throw FileNotFoundException("Nessun descrittore.")
  }

  private fun copied(row: ProbeRow): ParcelFileDescriptor {
    val dir = File(appContext.cacheDir, "field-probe").also { it.mkdirs() }
    val file = File(dir, "serve-${row.id}.bin")
    val started = SystemClock.elapsedRealtime()
    phone.open(row).use { input -> file.outputStream().use { out -> input.copyTo(out) } }
    store.max(FieldProbeKey.T14_SERVE_COPY_MS, SystemClock.elapsedRealtime() - started)
    store.add(FieldProbeKey.T14_SERVE_BYTES, file.length())
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
  }

  private fun proxied(row: ProbeRow): ParcelFileDescriptor {
    val uri = phone.uriOf(row)
    val source = resolver.openFileDescriptor(if (row.isVideo) uri else android.provider.MediaStore.setRequireOriginal(uri), "r")
      ?: throw FileNotFoundException("Nessun descrittore.")
    val channel = FileInputStream(source.fileDescriptor).channel
    val fileSize = row.size
    val openedAt = SystemClock.elapsedRealtime()
    val callback = object : ProxyFileDescriptorCallback() {
      private var lastEnd = 0L
      private var firstRead = true

      @Throws(ErrnoException::class)
      override fun onGetSize(): Long = fileSize

      @Throws(ErrnoException::class)
      override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        try {
          if (latencyMs > 0) Thread.sleep(latencyMs)
          if (firstRead) {
            firstRead = false
            store.setIfAbsent(FieldProbeKey.T14_SERVE_FIRST_READ_MS, SystemClock.elapsedRealtime() - openedAt)
          }
          if (offset < lastEnd) store.add(FieldProbeKey.T14_SERVE_BACKWARD_SEEKS)
          val read = channel.read(ByteBuffer.wrap(data, 0, size), offset).coerceAtLeast(0)
          lastEnd = offset + read
          store.add(FieldProbeKey.T14_SERVE_READS)
          store.add(FieldProbeKey.T14_SERVE_BYTES, read.toLong())
          if (lastEnd >= fileSize) store.set(FieldProbeKey.T14_SERVE_REACHED_EOF, 1)
          store.max(FieldProbeKey.T14_SERVE_DURATION_MS, SystemClock.elapsedRealtime() - openedAt)
          return read
        } catch (e: java.io.IOException) {
          throw ErrnoException("onRead", OsConstants.EIO)
        } catch (e: InterruptedException) {
          throw ErrnoException("onRead", OsConstants.EINTR)
        }
      }

      override fun onRelease() {
        store.max(FieldProbeKey.T14_SERVE_DURATION_MS, SystemClock.elapsedRealtime() - openedAt)
        try {
          channel.close()
          source.close()
        } catch (_: java.io.IOException) {
          // gia' chiuso
        }
      }
    }
    val manager = appContext.getSystemService(StorageManager::class.java)
    return try {
      manager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, handler)
    } catch (e: java.io.IOException) {
      source.close()
      throw FileNotFoundException("Descrittore a richiesta non aperto.")
    }
  }

  private fun extensionOf(mime: String?): String = when (mime) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/heic" -> "heic"
    "video/mp4" -> "mp4"
    else -> "bin"
  }

  companion object {
    @Volatile
    private var instance: FieldProbeServer? = null

    /** Il servitore del processo: un solo thread per i descrittori a richiesta, condiviso da provider e documenti. */
    fun of(context: Context): FieldProbeServer =
      instance ?: synchronized(this) {
        instance ?: FieldProbeServer(context, FieldProbePhone(context), FieldProbeStore.of(context)).also { instance = it }
      }
  }
}
