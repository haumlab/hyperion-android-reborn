package hyperionnet;

import com.google.flatbuffers.BaseVector;
import com.google.flatbuffers.Constants;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("unused")
public final class RawImage extends Table {
  public static void ValidateVersion() { Constants.FLATBUFFERS_23_5_26(); }
  public static RawImage getRootAsRawImage(ByteBuffer _bb) { return getRootAsRawImage(_bb, new RawImage()); }
  public static RawImage getRootAsRawImage(ByteBuffer _bb, RawImage obj) { _bb.order(ByteOrder.LITTLE_ENDIAN); return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)); }
  public void __init(int _i, ByteBuffer _bb) { __reset(_i, _bb); }
  public RawImage __assign(int _i, ByteBuffer _bb) { __init(_i, _bb); return this; }

  public int data(int j) { int o = __offset(4); return o != 0 ? bb.get(__vector(o) + j * 1) & 0xFF : 0; }
  public int dataLength() { int o = __offset(4); return o != 0 ? __vector_len(o) : 0; }
  public ByteBuffer dataAsByteBuffer() { return __vector_as_bytebuffer(4, 1); }
  public int width() { int o = __offset(6); return o != 0 ? bb.getInt(o + bb_pos) : -1; }
  public int height() { int o = __offset(8); return o != 0 ? bb.getInt(o + bb_pos) : -1; }

  public static int createRawImage(FlatBufferBuilder builder, int dataOffset, int width, int height) {
    builder.startTable(3);
    RawImage.addHeight(builder, height);
    RawImage.addWidth(builder, width);
    RawImage.addData(builder, dataOffset);
    return RawImage.endRawImage(builder);
  }

  public static void startRawImage(FlatBufferBuilder builder) { builder.startTable(3); }
  public static void addData(FlatBufferBuilder builder, int dataOffset) { builder.addOffset(0, dataOffset, 0); }
  public static int createDataVector(FlatBufferBuilder builder, byte[] data) { return builder.createByteVector(data); }
  public static void addWidth(FlatBufferBuilder builder, int width) { builder.addInt(1, width, -1); }
  public static void addHeight(FlatBufferBuilder builder, int height) { builder.addInt(2, height, -1); }
  public static int endRawImage(FlatBufferBuilder builder) { return builder.endTable(); }
}
