package dev.pampa.mycloudgallery.debugtools.probes

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.core.content.IntentCompat

/**
 * SOLO build debug (Fase 5B, P0-B; docs/android.md T-15): la **ricezione di prova**. Risponde a `SEND` e `SEND_MULTIPLE` di immagini e video come
 * risponderebbe `ShareReceiverActivity` (D24), ma senza interfaccia e senza salvare niente: **registra numeri** su cosa arriva (quanti flussi, quanti
 * elementi nella `ClipData`, se c'e' `FLAG_GRANT_READ_URI_PERMISSION`, `content://` o `file://`, chi ha condiviso, se il permesso di lettura c'e'),
 * poi apre e legge **subito** ogni flusso per misurare tempi e byte (la copia immediata di D24). Con "chiudi prima" invece non legge e chiude
 * subito l'activity, e lascia gli indirizzi alla schermata delle prove, che li riprova dopo (il permesso di lettura dura oltre l'activity? e dopo la
 * morte del processo?). Nel foglio di condivisione compare come "MCG prova (T-15)". Solo in `app/src/debug`.
 */
class FieldProbeShareActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val store = FieldProbeStore.of(this)
    val received = intent
    val uris = streamsOf(received)
    store.add(FieldProbeKey.T15_RECEIVED)
    store.set(FieldProbeKey.T15_ACTION, if (received?.action == Intent.ACTION_SEND_MULTIPLE) 2L else 1L)
    store.set(FieldProbeKey.T15_STREAMS, uris.size.toLong())
    store.set(FieldProbeKey.T15_CLIP_ITEMS, (received?.clipData?.itemCount ?: 0).toLong())
    store.set(FieldProbeKey.T15_GRANT_FLAG, if (received != null && received.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) 1L else 0L)
    store.set(FieldProbeKey.T15_SCHEME_CONTENT, uris.count { it.scheme == "content" }.toLong())
    store.set(FieldProbeKey.T15_SCHEME_FILE, uris.count { it.scheme == "file" }.toLong())
    store.set(FieldProbeKey.T15_SCHEME_OTHER, uris.count { it.scheme != "content" && it.scheme != "file" }.toLong())
    store.set(
      FieldProbeKey.T15_PERM_GRANTED,
      uris.count { checkUriPermission(it, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED }.toLong(),
    )
    store.set(FieldProbeKey.T15_CALLER, FieldProbeCaller.codeOf(callingPackage ?: referrer?.takeIf { it.scheme == "android-app" }?.host))
    store.addShares(uris.map { it.toString() }, SystemClock.elapsedRealtime())

    val closeFirst = store.get(FieldProbeKey.T15_CLOSED_FIRST) == 1L
    if (closeFirst || uris.isEmpty()) {
      finish()
      return
    }
    // Si legge fuori dal thread principale, con l'activity ancora aperta: e' la copia immediata di D24.
    Thread({
      var ok = 0L
      var fail = 0L
      var bytes = 0L
      var openMs = 0L
      var readMs = 0L
      val buffer = ByteArray(BUFFER)
      for (uri in uris) {
        val opened = SystemClock.elapsedRealtime()
        try {
          contentResolver.openInputStream(uri)?.use { input ->
            openMs += SystemClock.elapsedRealtime() - opened
            val started = SystemClock.elapsedRealtime()
            while (true) {
              val n = input.read(buffer)
              if (n < 0) break
              bytes += n
            }
            readMs += SystemClock.elapsedRealtime() - started
            ok++
          } ?: run { fail++ }
        } catch (_: Exception) {
          fail++
        }
      }
      store.set(FieldProbeKey.T15_OPEN_OK, ok)
      store.set(FieldProbeKey.T15_OPEN_FAIL, fail)
      store.set(FieldProbeKey.T15_BYTES, bytes)
      store.set(FieldProbeKey.T15_OPEN_MS_AVG, if (ok > 0) openMs / ok else 0L)
      store.set(FieldProbeKey.T15_READ_MS_TOTAL, readMs)
      runOnUiThread { finish() }
    }, "field-probe-share").start()
  }

  /** Gli indirizzi dei flussi: `EXTRA_STREAM` (uno o una lista), senza fidarsi del resto dell'intento. */
  private fun streamsOf(intent: Intent?): List<Uri> {
    if (intent == null) return emptyList()
    val many = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    if (!many.isNullOrEmpty()) return many.take(MAX_STREAMS)
    return listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)).take(MAX_STREAMS)
  }

  private companion object {
    const val BUFFER = 256 * 1024

    /** 500 elementi per condivisione (D24): la prova non legge oltre. */
    const val MAX_STREAMS = 500
  }
}
