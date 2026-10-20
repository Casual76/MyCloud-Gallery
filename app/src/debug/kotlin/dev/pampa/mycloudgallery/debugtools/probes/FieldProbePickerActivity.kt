package dev.pampa.mycloudgallery.debugtools.probes

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * SOLO build debug (Fase 5B, P0-B; docs/android.md T-14): il **selettore di prova**. Risponde a `GET_CONTENT` e `PICK` di immagini e video come
 * risponderebbe `PickerActivity` (D23), ma senza interfaccia: **conta cosa gli chiede l'altra app** (azione, tipo, selezione multipla, chi e') e
 * le risponde subito con 1 o 3 media delle cartelle di prova, nella forma scelta nella schermata delle prove ([FieldProbeForm]): l'indirizzo
 * diretto del MediaStore, oppure un indirizzo del provider di prova ([FieldProbeFilesProvider]) servito a richiesta o da una copia in cache. Con
 * `FLAG_GRANT_READ_URI_PERMISSION` e `ClipData`, come vuole Android 17 e 18. Nel selettore di sistema compare come "MCG prova (T-14)".
 * Solo in `app/src/debug`; il vero e' `feature-integrations` (scheletro in P0-B, P10 lo riempie).
 */
class FieldProbePickerActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val store = FieldProbeStore.of(this)
    store.add(FieldProbeKey.T14_PICKER_LAUNCHED)
    // LA-8: ogni apertura ha la sua riga (`t14.picker_caller.2`...): con una riga sola ogni app nuova cancellava chi aveva chiesto prima.
    val n = (store.get(FieldProbeKey.T14_PICKER_LAUNCHED) ?: 1L).toInt()
    store.set(
      FieldProbeKey.T14_PICKER_ACTION,
      when (intent?.action) {
        Intent.ACTION_GET_CONTENT -> 1L
        Intent.ACTION_PICK -> 2L
        else -> 0L
      },
      n,
    )
    store.set(FieldProbeKey.T14_PICKER_MULTIPLE, if (intent?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) == true) 1L else 0L, n)
    store.set(FieldProbeKey.T14_PICKER_MIME, FieldProbeMath.mimeCode(intent?.type), n)
    store.set(FieldProbeKey.T14_PICKER_CALLER, FieldProbeCaller.codeOf(callingPackage ?: referrer?.takeIf { it.scheme == "android-app" }?.host), n)

    val phone = FieldProbePhone(this)
    val wanted = if (intent?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) == true) (store.get(FieldProbeKey.T14_COUNT) ?: 1L).toInt() else 1
    val rows = FieldProbeMath.pick(phone.rows(), wanted.coerceIn(1, MAX_PICKED))
    if (rows.isEmpty()) {
      setResult(RESULT_CANCELED)
      finish()
      return
    }
    val form = FieldProbeForm.of(store.get(FieldProbeKey.T14_FORM))
    val uris: List<Uri> = rows.map { row ->
      if (form == FieldProbeForm.MEDIASTORE) phone.uriOf(row) else FieldProbeFilesProvider.uriOf(this, row)
    }
    val clip = ClipData.newRawUri(null, uris.first()).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
    val result = Intent().apply {
      data = uris.first()
      clipData = clip
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    setResult(RESULT_OK, result)
    finish()
  }

  private companion object {
    const val MAX_PICKED = 5
  }
}
