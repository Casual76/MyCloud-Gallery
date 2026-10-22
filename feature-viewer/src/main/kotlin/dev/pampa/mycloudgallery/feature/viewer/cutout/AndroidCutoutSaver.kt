package dev.pampa.mycloudgallery.feature.viewer.cutout

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import dev.pampa.mycloudgallery.core.mediastore.write.CutoutPhoneSaver
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.security.input.SensitiveClipboard
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.findActivity
import kotlin.coroutines.cancellation.CancellationException

// COPIA, CONDIVIDI E "SALVA COME NUOVA FOTO" DEL RITAGLIO (docs/fase6-progetto.md D47, §2.10; Fase 6, P13). Il PNG lo fa `core-ai` (il viewer non
// lo vede: arriva da [CutoutPngSource], che `:app` lega a `SubjectCutting.png`); Copia e Condividi passano da `cacheDir/share/` del `FileProvider`
// esistente (nessun percorso nuovo del provider: [CutoutShareFiles]); "Salva come nuova foto" da [CutoutNewPhoto] (`CutoutPhoneSaver` di core-mediastore).
// Il PNG nasce senza metadati: niente posizione nel file, niente nella copia, niente nella condivisione.

/** Il PNG trasparente del soggetto (con il contorno se [outline]) della foto di cui si e' ritagliato; null se la sorgente non si legge piu' o manca la memoria. */
fun interface CutoutPngSource {
  suspend fun png(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean): ByteArray?
}

/** Un file del ritaglio nella cartella di condivisione e il suo indirizzo `content://` (il `FileProvider` esistente). */
class StagedCutout(val uri: Uri)

/**
 * Dove si mette il PNG per le altre app: `cacheDir/share/<token>/ritaglio.png`, servito dal `FileProvider` esistente. Il file lo toglie la pulizia
 * all'avvio (`ShareCache.sweepAtStart`) o chi tiene il permesso (`ShareHolder`); mai un nome dell'utente nel percorso. Null = non si e' potuto scrivere.
 */
fun interface CutoutShareFiles {
  suspend fun stage(png: ByteArray): StagedCutout?
}

/** Salva un PNG come foto nuova del telefono (D47): dai Nascosti ([hidden]) nasce nascosta (E-V11). */
fun interface CutoutNewPhoto {
  suspend fun save(png: ByteArray, sourceName: String?, hidden: Boolean): CutoutSaveResult
}

/** Il salvataggio vero: `CutoutPhoneSaver` di core-mediastore, con la data di adesso dalla porta dell'app. */
class PhoneCutoutNewPhoto(
  private val saver: CutoutPhoneSaver,
  private val nowMillis: () -> Long,
) : CutoutNewPhoto {
  override suspend fun save(png: ByteArray, sourceName: String?, hidden: Boolean): CutoutSaveResult =
    when (val outcome = saver.save(png, sourceName, hidden, nowMillis())) {
      is CutoutPhoneSaver.Outcome.Saved -> CutoutSaveResult.Saved(outcome.mediaId)
      is CutoutPhoneSaver.Outcome.Failed -> CutoutSaveResult.Failed
      CutoutPhoneSaver.Outcome.Cancelled -> CutoutSaveResult.Failed
    }
}

/**
 * Il [CutoutSaver] del viewer sulle porte di sopra. Un errore e' sempre `false` / [CutoutSaveResult.Failed]: l'avviso lo da' il controller (mai
 * un messaggio d'eccezione, mai un nome di file nei log, D40); la cancellazione passa com'e'.
 *
 * @param sourceName il nome del file del media (per dare il nome al ritaglio salvato): dato dell'utente, non si logga.
 */
class AndroidCutoutSaver(
  private val context: Context,
  private val png: CutoutPngSource,
  private val files: CutoutShareFiles,
  private val newPhoto: CutoutNewPhoto,
  private val sourceName: (MediaRef) -> String? = { null },
) : CutoutSaver {

  override suspend fun copy(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean): Boolean {
    val staged = stage(id, ref, cutout, outline) ?: return false
    return CutoutSystem.copy(context, context.getString(R.string.cutout_clip_label), staged.uri)
  }

  override suspend fun share(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean): Boolean {
    val staged = stage(id, ref, cutout, outline) ?: return false
    val chooser = CutoutSystem.shareIntent(staged.uri)
    if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
      context.startActivity(chooser)
      true
    } catch (_: ActivityNotFoundException) {
      false
    } catch (_: SecurityException) {
      false
    }
  }

  override suspend fun saveAsNewPhoto(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean, fromHidden: Boolean): CutoutSaveResult {
    val bytes = try {
      png.png(id, ref, cutout, outline)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    } ?: return CutoutSaveResult.Failed
    return try {
      newPhoto.save(bytes, sourceName(ref), fromHidden)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      CutoutSaveResult.Failed
    }
  }

  private suspend fun stage(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean): StagedCutout? =
    try {
      png.png(id, ref, cutout, outline)?.let { files.stage(it) }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    }
}

/** Le azioni di sistema del ritaglio: tutte esplicite, tutte a un tocco dell'utente. */
internal object CutoutSystem {

  const val MIME: String = "image/png"

  /**
   * L'immagine negli appunti: l'indirizzo `content://` del file in `cacheDir/share/` come [ClipData] **segnata come sensibile**
   * (`ClipDescription.EXTRA_IS_SENSITIVE`, Android 13+: niente anteprima negli appunti e nei suggerimenti della tastiera). Il sistema da' il
   * permesso di lettura all'app che incolla (il `FileProvider` ha `grantUriPermissions`). Falso se il sistema non ha gli appunti.
   */
  fun copy(context: Context, label: String, uri: Uri): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(clip(context, label, uri))
    return true
  }

  fun clip(context: Context, label: String, uri: Uri): ClipData {
    // `newUri` chiede il tipo al provider; se non risponde l'appunto resta valido, con il tipo che conosciamo.
    val clip = try {
      ClipData.newUri(context.contentResolver, label, uri)
    } catch (_: RuntimeException) {
      ClipData.newRawUri(label, uri)
    }
    clip.description.extras = PersistableBundle().apply { putBoolean(SensitiveClipboard.EXTRA_IS_SENSITIVE, true) }
    return clip
  }

  /**
   * `ACTION_SEND` dell'immagine nel selettore di sistema, con `FLAG_GRANT_READ_URI_PERMISSION` e `ClipData` (obbligatori dalle versioni recenti di
   * Android: il permesso passa dal `ClipData`, non da `EXTRA_STREAM`), anche sul selettore. Nessun permesso di scrittura.
   */
  fun shareIntent(uri: Uri): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
      type = MIME
      putExtra(Intent.EXTRA_STREAM, uri)
      clipData = ClipData.newRawUri(null, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, null).apply {
      clipData = ClipData.newRawUri(null, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }
}
