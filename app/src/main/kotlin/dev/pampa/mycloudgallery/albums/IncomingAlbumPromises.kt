package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.backup.IncomingStates
import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStageId
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.albums.contract.AddToAlbumRequest
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingAlbumChoice
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingAlbums
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// "AGGIUNGI AD ALBUM" DALLA CONDIVISIONE IN ARRIVO (Fase 7, P13; P10-10, D35, D12). La ricezione salva i file sul NAS con "Salva sul NAS" (la sua
// conferma) e registra qui una **promessa**: quando i file di quello stage sono nel catalogo (caricati e riletti: `incoming_item` UPLOADED, il media del
// suo elemento `INCOMING`), vanno nell'album scelto. Un album personale li riceve subito (niente si sposta); per uno **condiviso** non si aggiunge mai
// da soli: si apre la scelta con l'album gia' scelto, che porta alla conferma col conteggio (D12), e la promessa si chiude quando l'utente ha deciso.
// La promessa sta su disco (`albums/incoming-promises`): sopravvive alla morte del processo. Mai nomi, percorsi o id nei log.

/** Una promessa: l'album e se e' condiviso (cosi' com'era quando l'utente l'ha scelto). */
internal data class IncomingPromise(val album: AlbumId, val shared: Boolean)

internal class FileIncomingPromises(file: File) {
  private val records = object : DurableRecords<String, IncomingPromise>(file, "promesse della ricezione verso gli album") {
    override fun encode(out: DataOutputStream, records: Map<String, IncomingPromise>) {
      out.writeInt(VERSION)
      out.writeInt(records.size)
      for ((stage, promise) in records) {
        out.writeUTF(stage)
        out.writeUTF(promise.album.value)
        out.writeBoolean(promise.shared)
      }
    }

    override fun decode(input: DataInputStream): LinkedHashMap<String, IncomingPromise> {
      if (input.readInt() != VERSION) throw IOException("versione")
      val out = LinkedHashMap<String, IncomingPromise>()
      repeat(input.readInt()) {
        val stage = input.readUTF()
        val album = AlbumId(input.readUTF())
        out[stage] = IncomingPromise(album, input.readBoolean())
      }
      return out
    }
  }

  suspend fun all(): Map<String, IncomingPromise> = records.read { it.toMap() }

  suspend fun put(stage: String, promise: IncomingPromise) = records.update { it[stage] = promise }

  suspend fun remove(stage: String) = records.update { it.remove(stage) }

  private companion object {
    const val VERSION = 1
  }
}

/**
 * La porta `IncomingAlbums` della ricezione e la sua seconda meta': [resolve] mantiene le promesse quando i file sono nel catalogo. La chiamano il giro
 * degli album dopo un cambio del catalogo e l'avvio. [stageMedia]: per uno stage, i media dei file **caricati** e se ne restano ancora in coda.
 */
internal class AppIncomingAlbums(
  private val albumsPort: () -> Albums,
  private val shared: () -> SharedAlbums,
  private val promises: FileIncomingPromises,
  private val stageMedia: suspend (IncomingStageId) -> StageMedia,
  private val requests: AlbumRequests,
) : IncomingAlbums {

  /** I media caricati di uno stage, e se qualche file aspetta ancora (in coda: la promessa resta). */
  class StageMedia(val uploaded: List<MediaId>, val waiting: Boolean)

  /** Le richieste aperte verso un album condiviso in questo processo: lo stage di ognuna (si chiude quando l'utente ha deciso). */
  private val asked = ConcurrentHashMap<AddToAlbumRequest, String>()

  /** Gli stage la cui richiesta e' finita: la promessa si toglie dal disco al prossimo [resolve] (la richiesta finisce sul thread dell'interfaccia). */
  private val pendingClosures = ConcurrentLinkedQueue<String>()

  init {
    val previous = requests.onDone
    requests.onDone = { request ->
      previous(request)
      asked.remove(request)?.let { stage -> pendingClosures += stage }
    }
  }

  override suspend fun albums(): List<IncomingAlbumChoice> = try {
    albumsPort().all.value.albums.mapNotNull { album ->
      when (album) {
        is Album.Manual -> IncomingAlbumChoice(album.id.value, album.title, shared = false)
        is Album.Smart -> null
        is Album.Shared -> IncomingAlbumChoice(album.id.value, album.title, shared = true)
          .takeIf { album.state == SharedState.ACTIVE && shared().allowed(album.id, AlbumOp.ADD_MEDIA) }
      }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    GalleryLog.w(TAG) { "album per la ricezione: ${e.javaClass.simpleName}" }
    emptyList()
  }

  override suspend fun willAdd(stage: IncomingStageId, album: IncomingAlbumChoice): Boolean = try {
    promises.put(stage.value, IncomingPromise(AlbumId(album.id), album.shared))
    true
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    GalleryLog.w(TAG) { "promessa non registrata: ${e.javaClass.simpleName}" }
    false
  }

  /**
   * Mantiene le promesse i cui file sono arrivati. Personale: `Albums.add` e la promessa si chiude (anche se l'album non c'e' piu': i file restano
   * nella cartella). Condiviso: la scelta con l'album gia' scelto, una volta per processo; la promessa si chiude quando l'utente ha deciso. Uno stage
   * senza file caricati e senza file in coda (tutti falliti o scartati) chiude la promessa senza fare niente.
   */
  suspend fun resolve() {
    while (true) promises.remove(pendingClosures.poll() ?: break)
    val open = promises.all()
    for ((stage, promise) in open) {
      if (asked.containsValue(stage)) continue
      val media = try {
        stageMedia(IncomingStageId(stage))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "file della ricezione non letti: ${e.javaClass.simpleName}" }
        continue
      }
      if (media.waiting) continue
      if (media.uploaded.isEmpty()) {
        promises.remove(stage)
        continue
      }
      if (promise.shared) {
        requests.add(media.uploaded, album = promise.album)?.let { asked[it] = stage }
      } else {
        when (val out = albumsPort().add(promise.album, media.uploaded)) {
          is AddOutcome.Blocked -> GalleryLog.d(TAG) { "aggiunta rimandata: ${out.reason}" }
          // Un personale non chiede mai la conferma; se la chiedesse (album diventato condiviso) si passa dalla scelta, mai da soli.
          is AddOutcome.NeedsShareConfirmation -> requests.add(media.uploaded, album = promise.album)?.let { asked[it] = stage }
          else -> promises.remove(stage)
        }
      }
    }
  }

  companion object {
    private const val TAG = "IncomingAlbums"

    /** I media caricati di uno stage dal database: `incoming_item` UPLOADED e il media del suo elemento `INCOMING` del diario. */
    suspend fun stageMediaOf(database: GalleryDatabase, stage: IncomingStageId): StageMedia = withContext(Dispatchers.IO) {
      val rows = database.incomingDao().ofStage(stage.value)
      val waiting = rows.any { it.state == IncomingStates.QUEUED }
      val uploaded = rows.filter { it.state == IncomingStates.UPLOADED }.mapNotNull { it.backupItemId }
      val media = if (uploaded.isEmpty()) {
        emptyList()
      } else {
        val marks = uploaded.joinToString(",") { "?" }
        database.openHelper.readableDatabase.query("SELECT media_id FROM backup_item WHERE item_id IN ($marks)", uploaded.toTypedArray()).use { c ->
          buildList { while (c.moveToNext()) add(MediaId(c.getString(0))) }
        }
      }
      StageMedia(media.distinct(), waiting)
    }
  }
}
