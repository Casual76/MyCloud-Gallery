package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.library.contract.LibraryIndexSync
import dev.pampa.mycloudgallery.nas.NasGraph
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Pacchetto XN (13/11/2026, decisione dell'utente): **a quando risale l'ultima sincronizzazione dell'indice dell'analisi con il NAS**, per la
 * sezione dell'analisi nel pannello della nuvola. Non inventa niente e non tocca il motore: legge l'istante che la pubblicazione salva gia'
 * (`ai_own_head.published_at`, ora del NAS, scritto da `RoomAiPublishStore.commit` a ogni testa nuova, compattazioni comprese), il piu' recente
 * fra le share.
 */
object AnalysisIndexSync {

  /**
   * - senza NAS (`nasConfigured` falso) l'indice resta sul telefono: [LibraryIndexSync.NotApplicable], la riga non c'e';
   * - una testa pubblicata: [LibraryIndexSync.At] con il suo istante;
   * - nessuna testa, ma il NAS con la sua parte `ai` partita (`aiOnNas`): [LibraryIndexSync.Never], «Non ancora sul NAS»;
   * - nessuna testa e il motore del NAS non (ancora) partito: non si sa se andra' sul NAS, la riga non c'e' (niente frase inventata).
   */
  fun of(nasConfigured: Boolean, aiOnNas: Boolean, lastPublishedAtNas: Long?): LibraryIndexSync = when {
    !nasConfigured -> LibraryIndexSync.NotApplicable
    lastPublishedAtNas != null -> LibraryIndexSync.At(lastPublishedAtNas)
    aiOnNas -> LibraryIndexSync.Never
    else -> LibraryIndexSync.NotApplicable
  }

  /** Lo stato osservato: cambia a ogni testa nuova (Room) e quando il motore del NAS parte o si ferma. */
  fun flow(database: GalleryDatabase, nas: NasGraph?): Flow<LibraryIndexSync> {
    val aiOnNas = nas?.runtimeState?.map { it?.ai != null } ?: flowOf(false)
    return combine(database.aiPublishDao().lastPublishedAt(), aiOnNas) { at, ai -> of(nas != null, ai, at) }.distinctUntilChanged()
  }
}
