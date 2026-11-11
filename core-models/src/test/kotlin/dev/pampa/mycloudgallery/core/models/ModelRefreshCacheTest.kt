package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.*
import dev.pampa.mycloudgallery.core.models.download.FileDigest
import dev.pampa.mycloudgallery.core.models.store.ModelStore
import dev.pampa.mycloudgallery.core.models.support.TestModels
import dev.pampa.mycloudgallery.core.models.verify.ModelVerifier
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ModelRefreshCacheTest {
  @Test fun secondoProcessoNonLeggeIByteMaUnFileToccatoSi() = runBlocking {
    val bytes = TestModels.bytes(1024)
    val spec = TestModels.spec(bytes)
    val dir = TestModels.tempDir()
    File(dir, spec.sha256.hex).writeBytes(bytes)
    val file = File(dir, spec.sha256.hex)
    var stat = FileStat(1, 2, bytes.size.toLong(), 3, 4, 5, 6)
    var hashes = 0
    fun verifier() = ModelVerifier(ModelStore(dir, listOf(spec), { 1L }, ModelCounters()), FileStatReader { stat.copy(mtimeSeconds = file.lastModified()) },
      digest = { channel, size -> hashes++; FileDigest.sha256(channel, size) })
    verifier().refreshVerified(spec)
    assertThat(hashes).isEqualTo(1)
    verifier().refreshVerified(spec) // nuovo oggetto: nessuna memoria del processo precedente
    assertThat(hashes).isEqualTo(1)
    assertThat(file.setLastModified(file.lastModified() + 2000)).isTrue()
    stat = stat.copy(ctimeNanos = 8, mtimeNanos = 9)
    verifier().refreshVerified(spec)
    assertThat(hashes).isEqualTo(2)
    (verifier().openVerified(spec) as ModelVerifier.Opened.Verified).model.close()
    assertThat(hashes).isEqualTo(3) // PATH_CHECKED/apertura non usa la cache del refresh
  }
  @Test fun senzaStatNonSiSaltaMaiLaVerifica() = runBlocking {
    val spec = TestModels.spec(TestModels.bytes(10))
    val dir = TestModels.tempDir()
    File(dir, spec.sha256.hex).writeBytes(TestModels.bytes(10))
    var hashes = 0
    val verifier = ModelVerifier(ModelStore(dir, listOf(spec), { 1L }, ModelCounters()), digest = { channel, size -> hashes++; FileDigest.sha256(channel, size) })
    repeat(2) { verifier.refreshVerified(spec) }
    assertThat(hashes).isEqualTo(2)
  }
}
