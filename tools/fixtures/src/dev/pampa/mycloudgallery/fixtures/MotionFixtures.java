package dev.pampa.mycloudgallery.fixtures;

import java.util.ArrayList;
import java.util.List;

/** Motion photo sintetiche: JPEG + MP4 nei tre formati (Google v1, MicroVideo, trailer SEF di Samsung). */
public final class MotionFixtures {

  private MotionFixtures() {}

  /** Un MP4 di prova per la parte video: comincia con {@code ftyp} come un video vero. */
  public static byte[] video(int mdatBytes) {
    IsoFixtures.Mp4Spec spec = new IsoFixtures.Mp4Spec();
    spec.mdatBytes = mdatBytes;
    spec.creationSeconds = IsoFixtures.mp4Seconds(1_710_238_530_000L);
    return IsoFixtures.mp4(spec);
  }

  /** Google v1 con la directory a attributi (come un Pixel): il video è l'ultimo elemento del file. */
  public static byte[] googleV1(Img still, ExifWriter.Spec exif, byte[] mp4) {
    return Bytes.concat(ImageFixtures.jpeg(still, exif, XmpPackets.googleMotionAttributes(mp4.length, 1_500_000)), mp4);
  }

  /** Google v1 come lo descrive la specifica (prefisso {@code Camera:}, voci a elementi). */
  public static byte[] googleV1Elements(Img still, ExifWriter.Spec exif, byte[] mp4) {
    return Bytes.concat(ImageFixtures.jpeg(still, exif, XmpPackets.googleMotionElements(mp4.length)), mp4);
  }

  /** MicroVideo: {@code MicroVideoOffset} dalla fine del file. */
  public static byte[] microVideo(Img still, ExifWriter.Spec exif, byte[] mp4) {
    return Bytes.concat(ImageFixtures.jpeg(still, exif, XmpPackets.microVideo(mp4.length, 1_500_000)), mp4);
  }

  private static final class Block {
    final int type;
    final String name;
    final byte[] value;

    Block(int type, String name, byte[] value) {
      this.type = type;
      this.name = name;
      this.value = value;
    }

    byte[] bytes() {
      return new Bytes().u16le(0).u16le(type).u32le(name.length()).ascii(name).raw(value).toBytes();
    }
  }

  /** Il trailer SEF: blocchi, directory {@code SEFH} e piede (lunghezza della directory + "SEFT"). */
  private static byte[] sefTrailer(long startOffset, List<Block> blocks) {
    long cursor = startOffset;
    List<long[]> placed = new ArrayList<>();
    Bytes body = new Bytes();
    for (Block b : blocks) {
      byte[] bytes = b.bytes();
      placed.add(new long[] {cursor, bytes.length});
      body.raw(bytes);
      cursor += bytes.length;
    }
    long directoryStart = cursor;
    Bytes dir = new Bytes().ascii("SEFH").u32le(101).u32le(blocks.size());
    for (int i = 0; i < blocks.size(); i++) {
      dir.u16le(0).u16le(blocks.get(i).type).u32le(directoryStart - placed.get(i)[0]).u32le(placed.get(i)[1]);
    }
    byte[] directory = dir.toBytes();
    return new Bytes().raw(body.toBytes()).raw(directory).u32le(directory.length).ascii("SEFT").toBytes();
  }

  /** Samsung: il video è il valore del blocco 0x0A30 "MotionPhoto_Data". Il JPEG non dichiara nulla in XMP. */
  public static byte[] samsungSef(Img still, ExifWriter.Spec exif, byte[] mp4) {
    byte[] jpeg = ImageFixtures.jpeg(still, exif, null);
    List<Block> blocks = new ArrayList<>();
    blocks.add(new Block(0x0A01, "Image_UTC_Data", Bytes.ascii4("1710238530000")));
    blocks.add(new Block(0x0A30, "MotionPhoto_Data", mp4));
    blocks.add(new Block(0x0A31, "MotionPhoto_Version", Bytes.ascii4("1")));
    return Bytes.concat(jpeg, sefTrailer(jpeg.length, blocks));
  }

  /** Samsung, variante con puntatore: il blocco 0x0A30 vale 12 byte, {@code mpv2} + offset + dimensione assoluti del video. */
  public static byte[] samsungSefPointer(Img still, ExifWriter.Spec exif, byte[] mp4) {
    byte[] jpeg = ImageFixtures.jpeg(still, exif, null);
    long videoOffset = jpeg.length;
    byte[] pointer = new Bytes().ascii("mpv2").u32le(videoOffset).u32le(mp4.length).toBytes();
    List<Block> blocks = new ArrayList<>();
    blocks.add(new Block(0x0A30, "MotionPhoto_Data", pointer));
    return Bytes.concat(jpeg, mp4, sefTrailer(jpeg.length + mp4.length, blocks));
  }
}
