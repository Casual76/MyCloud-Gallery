package dev.pampa.mycloudgallery.core.security.noop

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.security.faces.FacesDataKey
import dev.pampa.mycloudgallery.core.security.faces.FacesKeys

/**
 * Fase 8 (P0, D14): nessun Keystore per i volti. Fallisce CHIUSO: nessuna chiave si crea (chi salva i dati dei volti non li salva in
 * chiaro: aspetta) e nessuna si apre; distruggere riesce sempre (non c'e' niente da distruggere).
 */
object NoFacesKeys : FacesKeys {
  override suspend fun create(generation: FaceGeneration): Boolean = false

  override suspend fun open(generation: FaceGeneration): FacesDataKey? = null

  override suspend fun destroy(generation: FaceGeneration): Boolean = true

  override suspend fun destroyAll(): Boolean = true
}
