package dev.alvo.pieria.tools;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Little-endian float32 storage used by SQLite vector columns. */
public final class EmbeddingCodec {
  private EmbeddingCodec() {}

  public static byte[] encode(float[] embedding) {
    if (embedding == null) throw new IllegalArgumentException("embedding is required");
    ByteBuffer buffer=ByteBuffer.allocate(embedding.length*Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    for(float value:embedding) buffer.putFloat(value);
    return buffer.array();
  }

  public static float[] decode(byte[] blob) {
    if(blob==null || blob.length%Float.BYTES!=0)
      throw new IllegalArgumentException("embedding blob has invalid length");
    ByteBuffer buffer=ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
    float[] values=new float[blob.length/Float.BYTES];
    for(int i=0;i<values.length;i++) values[i]=buffer.getFloat();
    return values;
  }
}
