package dev.pampa.mycloudgallery.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.security.credentials.LoadResult
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VaultNasCredentialSourceTest {

  @Test
  fun gliEsitiDelVaultSiTraduconoUnoAUno() = runTest {
    val credentials = NasCredentials("test2", "una-password-lunga".toCharArray())
    val results = mapOf(
      "trovata" to LoadResult.Found(credentials),
      "assente" to LoadResult.Absent,
      "da-reinserire" to LoadResult.NeedsPassword,
      "non-ora" to LoadResult.Unavailable,
    )
    val source = VaultNasCredentialSource { slot -> results.getValue(slot) }

    val found = source.load("trovata") as CredentialLoad.Found
    assertThat(found.credentials).isSameInstanceAs(credentials)
    assertThat(found.toString()).doesNotContain("password")
    assertThat(source.load("assente")).isEqualTo(CredentialLoad.Absent)
    assertThat(source.load("da-reinserire")).isEqualTo(CredentialLoad.NeedsPassword)
    assertThat(source.load("non-ora")).isEqualTo(CredentialLoad.Unavailable)
    credentials.close()
  }
}
