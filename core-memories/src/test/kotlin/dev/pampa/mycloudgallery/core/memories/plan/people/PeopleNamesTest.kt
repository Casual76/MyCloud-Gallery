package dev.pampa.mycloudgallery.core.memories.plan.people

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.settings.less.PeoplePhase
import dev.pampa.mycloudgallery.core.memories.shelf.FileShelfStore
import dev.pampa.mycloudgallery.core.personal.codec.PersonalRecords
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **Mai un nome di persona** nei dati personali, nello scaffale, nei log e nei `toString()` (docs/fase9-progetto.md D11, D12, F8-D13, C300; P8):
 * una prova **sui byte**. Quattro persone con un nome ben riconoscibile (anche con lettere non ASCII), ricordi `PERSON` veri su un file di
 * scaffale vero, "Mostra meno" e "Mai", un'unione, la cancellazione di Persone; poi si cerca ogni nome, nelle forme UTF-8, UTF-16 e minuscola, in
 * ogni byte che l'app salva (il file dello scaffale, ogni voce dei dati personali nella forma che viaggia sul NAS), in ogni riga di log e in ogni
 * `toString()` dei tipi che i ricordi espongono. Il nome si legge solo da `label`, al momento di mostrarlo.
 */
class PeopleNamesTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val author = TrustIds.random()
  private val names = listOf("Zorbaldo", "Quintilia", "Wenceslao", "Müller-Ñandú")

  private fun forms(name: String): List<ByteArray> = listOf(
    name.toByteArray(Charsets.UTF_8), name.toByteArray(Charsets.UTF_16LE), name.toByteArray(Charsets.UTF_16BE),
    name.lowercase().toByteArray(Charsets.UTF_8), name.uppercase().toByteArray(Charsets.UTF_8),
  )

  private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
    if (needle.isEmpty() || haystack.size < needle.size) return false
    outer@ for (i in 0..haystack.size - needle.size) {
      for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
      return true
    }
    return false
  }

  private fun assertNoName(where: String, bytes: ByteArray) {
    for (name in names) for (form in forms(name)) assertWithMessage("$where contiene un nome").that(contains(bytes, form)).isFalse()
  }

  @Test
  fun nessunNomeInNessunByte() = runTest {
    val lines = ArrayList<String>()
    GalleryLog.install { _, tag, message, error -> lines += "$tag $message ${error?.javaClass?.simpleName.orEmpty()}" }
    val dir = temp.newFolder("memories")
    val rig = PeopleRig(store = FileShelfStore(dir, Dispatchers.Unconfined))
    try {
      val (a) = rig.person(names[0], PeopleRig.DAYS_2025)
      val (b) = rig.person(names[1], (1..5).map { LocalDate.of(2024, 6, it * 4) })
      val (c) = rig.person(names[2], (1..5).map { LocalDate.of(2023, 8, it * 4) })
      val (d) = rig.person(names[3], (1..5).map { LocalDate.of(2022, 9, it * 4) })
      // Una persona a settimana: ricordi `PERSON` veri sullo scaffale (al piu' una a settimana, e restano 30 giorni).
      repeat(5) {
        rig.refresh()
        rig.clock.today = rig.clock.today.plusDays(8)
      }
      assertThat(rig.personMemories().map { it.title.person }.toSet().size).isAtLeast(2)

      // 1. Il file dello scaffale (quello vero, scritto da `FileShelfStore`), con i ricordi `PERSON` dentro: l'id della persona, mai il nome.
      val shelfBytes = File(dir, "shelf.bin").readBytes()
      assertThat(shelfBytes.size).isGreaterThan(300)
      assertNoName("shelf.bin", shelfBytes)
      val withName = rig.personMemories().first()
      // I nomi servono solo a mostrare: li' si leggono (e vanno al momento).
      assertThat(rig.engine.memory(withName.id).first()!!.personName).isNotNull()

      rig.engine.setLess(LessTarget.Person(c), LessLevel.LESS)
      rig.engine.setLess(LessTarget.Person(d), LessLevel.NEVER)
      rig.people.merge(b, a)
      rig.settings.setLess(b, MemoryLessLevel.LESS)
      rig.janitor.sweep()
      rig.refresh()
      assertThat(rig.engine.lessChoices.value.mapNotNull { it.personName }.toSet()).isNotEmpty()

      // 2. Ogni voce dei dati personali nella forma che viaggia: chiave, soggetto, campo e valore.
      val entries = rig.register.entries(author)
      assertThat(entries.keys.map { it.field }.any { it.startsWith("memories_less:person:") }).isTrue()
      for ((key, entry) in entries) {
        assertNoName("voce ${key.kind}", PersonalRecords.encode(entry))
        assertNoName("chiave ${key.kind}", "${key.subject}|${key.field}".toByteArray())
      }
      assertNoName("shelf.bin dopo le scelte", File(dir, "shelf.bin").readBytes())

      // 3. Persone spenta mette le lapidi, e le lapidi non portano un nome.
      rig.switch.set(PeoplePhase.ERASING)
      rig.janitor.sweep()
      for ((key, entry) in rig.register.entries(author)) assertNoName("lapide ${key.kind}", PersonalRecords.encode(entry))
      assertNoName("shelf.bin dopo la cancellazione", File(dir, "shelf.bin").readBytes())
      assertThat(rig.personMemories()).isEmpty()

      // 4. I `toString()` dei tipi che i ricordi espongono, e le righe di log di tutta la storia.
      val shown = buildList {
        add(rig.engine.toString()); add(rig.engine.status.value.toString()); add(rig.engine.shelf.value.toString())
        add(rig.engine.lessChoices.value.toString()); add(rig.raw.current().toString()); add(rig.janitor.toString()); add(rig.settings.toString())
        add(rig.people.toString()); add(rig.switch.toString())
        for (card in rig.engine.shelf.value.items) { add(card.toString()); add(card.title.toString()); rig.engine.memory(card.id).first()?.let { add(it.toString()) } }
        for (memory in rig.engine.current()?.memories.orEmpty()) { add(memory.toString()); add(memory.key.toString()); add(memory.title.toString()) }
        for (id in listOf(a, b, c, d)) { add(id.toString()); add(LessTarget.Person(id).toString()) }
      }
      for (text in shown) assertNoName("toString", text.toByteArray())
      assertThat(lines).isNotEmpty()
      for (line in lines) assertNoName("log", line.toByteArray())
      assertThat(MemoryKind.PERSON.code).isEqualTo("person")
    } finally {
      GalleryLog.uninstall()
      rig.close()
    }
  }
}
