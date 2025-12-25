package hyperionnet;

import com.google.flatbuffers.Constants;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("unused")
public final class Request extends Table {
  public static void ValidateVersion() { Constants.FLATBUFFERS_23_5_26(); }
  public static Request getRootAsRequest(ByteBuffer _bb) { return getRootAsRequest(_bb, new Request()); }
  public static Request getRootAsRequest(ByteBuffer _bb, Request obj) { _bb.order(ByteOrder.LITTLE_ENDIAN); return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)); }
  public void __init(int _i, ByteBuffer _bb) { __reset(_i, _bb); }
  public Request __assign(int _i, ByteBuffer _bb) { __init(_i, _bb); return this; }

  public byte commandType() { int o = __offset(4); return o != 0 ? bb.get(o + bb_pos) : 0; }
  public Table command(Table obj) { int o = __offset(6); return o != 0 ? __union(obj, o + bb_pos) : null; }

  public static int createRequest(FlatBufferBuilder builder, byte command_type, int commandOffset) {
    builder.startTable(2);
    Request.addCommand(builder, commandOffset);
    Request.addCommandType(builder, command_type);
    return Request.endRequest(builder);
  }

  public static void startRequest(FlatBufferBuilder builder) { builder.startTable(2); }
  public static void addCommandType(FlatBufferBuilder builder, byte commandType) { builder.addByte(0, commandType, 0); }
  public static void addCommand(FlatBufferBuilder builder, int commandOffset) { builder.addOffset(1, commandOffset, 0); }
  public static int endRequest(FlatBufferBuilder builder) {
    int o = builder.endTable();
    builder.required(o, 6);  // command
    return o;
  }
  public static void finishRequestBuffer(FlatBufferBuilder builder, int offset) { builder.finish(offset); }
}
