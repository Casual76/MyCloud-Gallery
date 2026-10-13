package dev.pampa.mycloudgallery.feature.settings.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Il catalogo degli screenshot e' coerente: nessun nome doppio (un PNG ne coprirebbe un altro) e nessuna schermata dimenticata. */
class ShotCatalogTest {

  @Test
  fun iNomiDegliScreenshotSonoUnici() {
    val names = ShotCatalog.names
    assertThat(names).containsNoDuplicates()
  }

  @Test
  fun ogniPaginaDelleImpostazioniHaAlmenoUnoScreenshot() {
    val prefixes = listOf(
      "home-", "dispositivi-", "dispositivo-", "perso-", "approvazione-", "famiglia-", "invito-", "kit-", "stato-famiglia-",
      "secondo-admin-", "recupero-", "reset-", "account-", "password-", "indirizzo-", "rete-", "sicurezza-", "backup-", "informazioni-",
    )
    prefixes.forEach { prefix ->
      assertThat(ShotCatalog.names.any { it.startsWith(prefix) }).isTrue()
    }
  }

  @Test
  fun ilTabletHaUnaSchermataRappresentativaPerOgniPagina() {
    val tablet = ShotCatalog.names.filter { it !in ShotCatalog.phoneOnly }
    listOf("home-", "dispositivi-", "dispositivo-", "perso-", "approvazione-", "famiglia-", "invito-", "kit-", "stato-famiglia-", "secondo-admin-", "recupero-", "reset-", "account-", "password-", "indirizzo-", "rete-", "sicurezza-", "backup-", "informazioni-")
      .forEach { prefix -> assertThat(tablet.any { it.startsWith(prefix) }).isTrue() }
  }

  @Test
  fun gliStatiSecondariDelTabletEsistonoDavveroNelCatalogo() {
    assertThat(ShotCatalog.names).containsAtLeastElementsIn(ShotCatalog.phoneOnly)
  }
}
