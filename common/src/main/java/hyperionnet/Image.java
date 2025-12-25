package hyperionnet;

import com.google.flatbuffers.Constants;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("unused")
public final class Image extends Table {
  public static void ValidateVersion() { Constants.FLATBUFFERS_23_5_26(); }
  public static Image getRootAsImage(ByteBuffer _bb) { return getRootAsImage(_bb, new Image()); }
  public static Image getRootAsImage(ByteBuffer _bb, Image obj) { _bb.order(ByteOrder.LITTLE_ENDIAN); return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)); }
  public void __init(int _i, ByteBuffer _bb) { __reset(_i, _bb); }
  public Image __assign(int _i, ByteBuffer _bb) { __init(_i, _bb); return this; }

  public byte dataType() { int o = __offset(4); return o != 0 ? bb.get(o + bb_pos) : 0; }
  public Table data(Table obj) { int o = __offset(6); return o != 0 ? __union(obj, o + bb_pos) : null; }
  public int duration() { int o = __offset(8); return o != 0 ? bb.getInt(o + bb_pos) : -1; }

  public static int createImage(FlatBufferBuilder builder, byte data_type, int dataOffset, int duration) {
    builder.startTable(3);
    Image.addDuration(builder, duration);
    Image.addData(builder, dataOffset);
    Image.addDataType(builder, data_type);
    return Image.endImage(builder);
  }

  public static void startImage(FlatBufferBuilder builder) { builder.startTable(3); }
  public static void addDataType(FlatBufferBuilder builder, byte dataType) { builder.addByte(0, dataType, 0); }
  public static void addData(FlatBufferBuilder builder, int dataOffset) { builder.addOffset(1, dataOffset, 0); }
  public static void addDuration(FlatBufferBuilder builder, int duration) { builder.addInt(2, duration, -1); }
  public static int endImage(FlatBufferBuilder builder) {
    int o = builder.endTable();
    builder.required(o, 6);  // data
    return o;
  }
}
