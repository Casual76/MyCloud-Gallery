package dev.pampa.mycloudgallery.fixtures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DNG e TIFF di prova (little-endian). Un DNG vero ha nell'IFD0 una piccola anteprima e l'immagine grezza in un SubIFD:
 * qui l'IFD0 dichiara 256 x 192, il SubIFD la dimensione grezza, e {@code DefaultCropSize} quella sviluppata. I dati di
 * immagine sono zeri: il file ha la struttura giusta e non si decodifica.
 */
public final class TiffFixtures {

  private TiffFixtures() {}

  private static final class Tag {
    final int tag;
    final int type;
    final int count;
    byte[] value;

    Tag(int tag, int type, int count, byte[] value) {
      this.tag = tag;
      this.type = type;
      this.count = count;
      this.value = value;
    }
  }

  private static Tag longs(int tag, long... values) {
    Bytes b = new Bytes();
    for (long v : values) b.u32le(v);
    return new Tag(tag, 4, values.length, b.toBytes());
  }

  private static Tag shorts(int tag, int... values) {
    Bytes b = new Bytes();
    for (int v : values) b.u16le(v);
    return new Tag(tag, 3, values.length, b.toBytes());
  }

  private static Tag ascii(int tag, String s) {
    byte[] v = new Bytes().ascii(s).u8(0).toBytes();
    return new Tag(tag, 2, v.length, v);
  }

  private static Tag bytesTag(int tag, byte... v) {
    return new Tag(tag, 1, v.length, v);
  }

  private static int ifdSize(List<Tag> tags) {
    int size = 2 + 12 * tags.size() + 4;
    for (Tag t : tags) if (t.value.length > 4) size += (t.value.length + 1) & ~1;
    return size;
  }

  private static void writeIfd(Bytes out, List<Tag> tags, int start) {
    tags.sort(Comparator.comparingInt(t -> t.tag));
    out.u16le(tags.size());
    int dataAt = start + 2 + 12 * tags.size() + 4;
    Bytes data = new Bytes();
    for (Tag t : tags) {
      out.u16le(t.tag).u16le(t.type).u32le(t.count);
      if (t.value.length <= 4) {
        out.raw(t.value).zeros(4 - t.value.length);
      } else {
        out.u32le(dataAt + data.size());
        data.raw(t.value);
        if ((t.value.length & 1) == 1) data.u8(0);
      }
    }
    out.u32le(0);
    out.raw(data.toBytes());
  }

  public static final class DngSpec {
    public String make = "samsung";
    public String model = "SM-S931B";
    public int orientation = 1;
    public String dateOriginal = "2024:03:12 10:15:30";
    public String offsetOriginal = "+01:00";
    public int rawWidth = 4000;
    public int rawHeight = 3000;
    /** {@code DefaultCropSize}: la dimensione dell'immagine sviluppata (null = nessuna). */
    public int[] cropSize = {3984, 2988};
    public int rawBytes = 3000;
    public boolean isDng = true;
    /** Un JPEG da mettere come anteprima nell'IFD0 (immagine ridotta, compressione 7, una striscia); null = 64 byte di zeri. */
    public byte[] previewJpeg;
  }

  /** Un DNG (o, con {@code isDng = false}, un TIFF semplice con la stessa struttura ma senza {@code DNGVersion}). */
  public static byte[] dng(DngSpec s) {
    // I valori dei puntatori hanno sempre 4 byte: le dimensioni non cambiano con i valori, quindi si misura prima.
    List<Tag> exif = new ArrayList<>();
    exif.add(ascii(0x9003, s.dateOriginal));
    if (s.offsetOriginal != null) exif.add(ascii(0x9011, s.offsetOriginal));
    List<Tag> sub = new ArrayList<>();
    sub.add(longs(0x00FE, 0));
    sub.add(longs(0x0100, s.rawWidth));
    sub.add(longs(0x0101, s.rawHeight));
    sub.add(longs(0x0111, 0));
    sub.add(longs(0x0117, s.rawBytes));
    if (s.cropSize != null) sub.add(longs(0xC620, s.cropSize[0], s.cropSize[1]));
    List<Tag> ifd0 = new ArrayList<>();
    ifd0.add(longs(0x00FE, 1));
    ifd0.add(longs(0x0100, 256));
    ifd0.add(longs(0x0101, 192));
    ifd0.add(ascii(0x010F, s.make));
    ifd0.add(ascii(0x0110, s.model));
    ifd0.add(shorts(0x0112, s.orientation));
    ifd0.add(longs(0x0111, 0));
    int previewLength = s.previewJpeg != null ? s.previewJpeg.length : 64;
    ifd0.add(longs(0x0117, previewLength));
    if (s.previewJpeg != null) ifd0.add(shorts(0x0103, 7));
    ifd0.add(longs(0x014A, 0));
    ifd0.add(longs(0x8769, 0));
    if (s.isDng) ifd0.add(bytesTag(0xC612, (byte) 1, (byte) 4, (byte) 0, (byte) 0));

    int offIfd0 = 8;
    int offExif = offIfd0 + ifdSize(ifd0);
    int offSub = offExif + ifdSize(exif);
    int offThumb = offSub + ifdSize(sub);
    int offRaw = offThumb + previewLength;

    set(ifd0, 0x0111, offThumb);
    set(ifd0, 0x014A, offSub);
    set(ifd0, 0x8769, offExif);
    set(sub, 0x0111, offRaw);

    Bytes out = new Bytes().ascii("II").u16le(42).u32le(offIfd0);
    writeIfd(out, ifd0, offIfd0);
    writeIfd(out, exif, offExif);
    writeIfd(out, sub, offSub);
    if (s.previewJpeg != null) out.raw(s.previewJpeg); else out.zeros(64); // l'anteprima
    out.zeros(s.rawBytes); // i dati grezzi
    return out.toBytes();
  }

  private static void set(List<Tag> tags, int tag, long value) {
    for (Tag t : tags) {
      if (t.tag == tag) {
        t.value = new Bytes().u32le(value).toBytes();
        return;
      }
    }
    throw new IllegalArgumentException("tag " + tag);
  }
}
