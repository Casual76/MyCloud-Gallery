package dev.pampa.mycloudgallery.core.release.codec

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.release.contract.RejectReason
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fase 10 (P1, D29; docs/formato-nas.md §3.2): il JSON canonico del manifest ha una sola forma, e la lettura stretta rifiuta tutte le altre. */
class ReleaseJsonTest {

  private fun reason(text: String): RejectReason =
    assertThrows(CodecException::class.java) { ReleaseJson.parseCanonical(text.toByteArray(Charsets.UTF_8)) }.reason

  private fun reason(bytes: ByteArray): RejectReason = assertThrows(CodecException::class.java) { ReleaseJson.parseCanonical(bytes) }.reason

  @Test
  fun laFormaCanonicaSiRilegge() {
    val text = """{"a":[1,-2,true,false,"x\"\\\n\u0001é"],"b":{},"c":[]}"""
    val value = ReleaseJson.parseCanonical(text.toByteArray(Charsets.UTF_8))
    assertThat(String(ReleaseJson.encode(value), Charsets.UTF_8)).isEqualTo(text)
    val obj = JObj(mapOf("z" to JInt(1), "a" to JStr("é")))
    assertThat(String(ReleaseJson.encode(obj), Charsets.UTF_8)).isEqualTo("""{"a":"é","z":1}""")
  }

  @Test
  fun ogniAltraFormaERifiutata() {
    val malformed = listOf(
      """{"a": 1}""", """ {"a":1}""", """{"b":1,"a":2}""", """{"a":1,"a":1}""", """{"a":"\/"}""", """{"a":"\u0041"}""",
      """{"a":"\u001F"}""", """{"a":01}""", """{"a":-0}""", """{"a":1.0}""", """{"a":1e3}""", """{"a":null}""", """{"a":1}x""",
      """{"a":1""", """{"1a":1}""", """{"a-b":1}""", """{"a":"x""", """{"a":"\q"}""", """{"a":[1,]}""", """{"a":tru}""",
      """{"a":99999999999999999999}""", "\uFEFF{\"a\":1}", """{"a":"x""" + "\u0007" + """"}""",
    )
    for (text in malformed) {
      val thrown = runCatching { ReleaseJson.parseCanonical(text.toByteArray(Charsets.UTF_8)) }.exceptionOrNull()
      assertWithMessage(text).that((thrown as? CodecException)?.reason).isEqualTo(RejectReason.MALFORMED)
    }
    assertThat(reason(byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), '"'.code.toByte(), '}'.code.toByte()))).isEqualTo(RejectReason.MALFORMED)
    assertWithMessage("surrogato spaiato").that(reason("""{"a":"\ud800"}""")).isEqualTo(RejectReason.MALFORMED)
    assertWithMessage("non NFC").that(reason("{\"a\":\"e\u0301\"}")).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun iTettiSonoTooLarge() {
    assertThat(reason("""{"a":{"b":{"c":{"d":{"e":1}}}}}""")).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(ReleaseJson.parseCanonical("""{"a":{"b":{"c":{"d":1}}}}""".toByteArray())).isInstanceOf(JObj::class.java)
    assertThat(reason("[" + List(33) { "1" }.joinToString(",") + "]")).isEqualTo(RejectReason.TOO_LARGE)
    val members = (0 until 33).joinToString(",") { "\"k${it.toString().padStart(2, '0')}\":1" }
    assertThat(reason("{$members}")).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(reason("{\"a\":\"" + "x".repeat(ReleaseJson.MAX_STRING_CHARS + 1) + "\"}")).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(ReleaseJson.parseCanonical(("{\"a\":\"" + "x".repeat(ReleaseJson.MAX_STRING_CHARS) + "\"}").toByteArray())).isInstanceOf(JObj::class.java)
  }

  @Test
  fun laLetturaLeggibileAmmetteGliSpaziMaNonIDoppioni() {
    val value = ReleaseJson.parseLenient("\uFEFF{\n  \"b\" : [ 1 , 2 ],\r\n\t\"a\": \"x\"\n}\n") as JObj
    assertThat(value.members.keys).containsExactly("a", "b").inOrder()
    assertThat(assertThrows(CodecException::class.java) { ReleaseJson.parseLenient("""{"a":1, "a":2}""") }.reason).isEqualTo(RejectReason.MALFORMED)
    assertThat(assertThrows(CodecException::class.java) { ReleaseJson.parseLenient("""{"a":null}""") }.reason).isEqualTo(RejectReason.MALFORMED)
  }
}
