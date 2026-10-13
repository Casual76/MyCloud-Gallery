package dev.pampa.mycloudgallery.fixtures;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Un piccolo scrittore di byte (big e little endian) per costruire i file di prova. */
public final class Bytes {

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  public Bytes u8(int v) {
    out.write(v & 0xFF);
    return this;
  }

  public Bytes u16be(int v) {
    return u8(v >> 8).u8(v);
  }

  public Bytes u16le(int v) {
    return u8(v).u8(v >> 8);
  }

  public Bytes u24le(int v) {
    return u8(v).u8(v >> 8).u8(v >> 16);
  }

  public Bytes u32be(long v) {
    return u8((int) (v >> 24)).u8((int) (v >> 16)).u8((int) (v >> 8)).u8((int) v);
  }

  public Bytes u32le(long v) {
    return u8((int) v).u8((int) (v >> 8)).u8((int) (v >> 16)).u8((int) (v >> 24));
  }

  public Bytes u64be(long v) {
    return u32be(v >>> 32).u32be(v & 0xFFFFFFFFL);
  }

  public Bytes u16(int v, boolean bigEndian) {
    return bigEndian ? u16be(v) : u16le(v);
  }

  public Bytes u32(long v, boolean bigEndian) {
    return bigEndian ? u32be(v) : u32le(v);
  }

  public Bytes raw(byte[] b) {
    out.write(b, 0, b.length);
    return this;
  }

  public Bytes ascii(String s) {
    return raw(s.getBytes(StandardCharsets.ISO_8859_1));
  }

  public Bytes utf8(String s) {
    return raw(s.getBytes(StandardCharsets.UTF_8));
  }

  public Bytes zeros(int n) {
    for (int i = 0; i < n; i++) out.write(0);
    return this;
  }

  public int size() {
    return out.size();
  }

  public byte[] toBytes() {
    return out.toByteArray();
  }

  public static byte[] concat(byte[]... parts) {
    Bytes b = new Bytes();
    for (byte[] p : parts) b.raw(p);
    return b.toBytes();
  }

  public static byte[] ascii4(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  /** I primi {@code n} byte (o tutti, se il file è più corto): un file tagliato. */
  public static byte[] prefix(byte[] data, int n) {
    return java.util.Arrays.copyOf(data, Math.min(n, data.length));
  }
}
