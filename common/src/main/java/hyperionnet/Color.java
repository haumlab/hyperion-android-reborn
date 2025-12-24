package hyperionnet;

import com.google.flatbuffers.Constants;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("unused")
public final class Color extends Table {
  public static void ValidateVersion() { Constants.FLATBUFFERS_23_5_26(); }
  public static Color getRootAsColor(ByteBuffer _bb) { return getRootAsColor(_bb, new Color()); }
  public static Color getRootAsColor(ByteBuffer _bb, Color obj) { _bb.order(ByteOrder.LITTLE_ENDIAN); return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)); }
  public void __init(int _i, ByteBuffer _bb) { __reset(_i, _bb); }
  public Color __assign(int _i, ByteBuffer _bb) { __init(_i, _bb); return this; }

  public int data() { int o = __offset(4); return o != 0 ? bb.getInt(o + bb_pos) : -1; }
  public int duration() { int o = __offset(6); return o != 0 ? bb.getInt(o + bb_pos) : -1; }

  public static int createColor(FlatBufferBuilder builder, int data, int duration) {
    builder.startTable(2);
    Color.addDuration(builder, duration);
    Color.addData(builder, data);
    return Color.endColor(builder);
  }

  public static void startColor(FlatBufferBuilder builder) { builder.startTable(2); }
  public static void addData(FlatBufferBuilder builder, int data) { builder.addInt(0, data, -1); }
  public static void addDuration(FlatBufferBuilder builder, int duration) { builder.addInt(1, duration, -1); }
  public static int endColor(FlatBufferBuilder builder) { return builder.endTable(); }
}
