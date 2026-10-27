package dev.pampa.mycloudgallery.core.database

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.entity.AlbumExclusionEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumRuleEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumSuggestionEntity
import dev.pampa.mycloudgallery.core.database.entity.SharedAlbumHeadMarkEntity
import org.junit.Test

/**
 * Le righe v6 degli album non stampano dati dell'utente (Fase 7, revisione di fine fase, I7). Erano `data class` col `toString` di serie, che elenca ogni
 * campo: `AlbumRuleEntity` stampava `value` (il centro di un luogo, la descrizione, il periodo), le altre l'id dell'album e del media e le impronte. Oggi
 * nessun log le stampa; questa barriera impedisce che un log futuro, o un messaggio di errore con la riga dentro, lo faccia.
 */
class AlbumRowsToStringTest {

  private val album = "9a1f0000-0000-4000-8000-00000000a1b1"
  private val media = "7c2e0000-0000-4000-8000-0000000000c2"
  private val fp = "deadbeefcafe:123456"

  private fun assertSafe(text: String) {
    for (secret in listOf(album, media, fp, "Oslo", "Compleanno", "59.91", "10.75", "neve", "dev-segreto")) {
      assertThat(text).doesNotContain(secret)
    }
  }

  @Test
  fun unaRegolaNonStampaIlSuoValore() {
    val place = AlbumRuleEntity(albumId = album, rule = "place", value = "Oslo:59.91:10.75:25:around")
    val text = AlbumRuleEntity(albumId = album, rule = "text", value = "neve").toString()
    assertSafe(place.toString())
    assertSafe(text)
    // Resta il tipo di regola, un codice fisso utile a chi legge un log.
    assertThat(place.toString()).contains("place")
    assertThat(AlbumRuleEntity(albumId = album, rule = "example", mediaId = media).toString()).doesNotContain(media)
  }

  @Test
  fun unElementoUnaEsclusioneEUnSuggeritoNonStampanoIdEImpronte() {
    assertSafe(AlbumItemEntity(album, media, 1_000L, 5L, origin = "auto", fp = fp, addedBy = "dev-segreto").toString())
    assertSafe(AlbumExclusionEntity(album, media, fp).toString())
    assertSafe(AlbumSuggestionEntity(album, media, "S2", 5L).toString())
    // Del suggerito resta la regola (S1-S5), che e' un codice fisso.
    assertThat(AlbumSuggestionEntity(album, media, "S2", 5L).toString()).contains("S2")
  }

  @Test
  fun ilSegnoDiUnaTestaNonStampaDispositivoEImpronta() {
    val text = SharedAlbumHeadMarkEntity(album, "dev-segreto", version = 7, contentHash = "deadbeefcafe", registryVersion = 3, createdAt = 5L).toString()
    assertSafe(text)
    assertThat(text).doesNotContain("deadbeefcafe")
  }
}
