package hyperionnet;

import com.google.flatbuffers.Constants;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("unused")
public final class Reply extends Table {
  public static void ValidateVersion() { Constants.FLATBUFFERS_23_5_26(); }
  public static Reply getRootAsReply(ByteBuffer _bb) { return getRootAsReply(_bb, new Reply()); }
  public static Reply getRootAsReply(ByteBuffer _bb, Reply obj) { _bb.order(ByteOrder.LITTLE_ENDIAN); return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)); }
  public void __init(int _i, ByteBuffer _bb) { __reset(_i, _bb); }
  public Reply __assign(int _i, ByteBuffer _bb) { __init(_i, _bb); return this; }

  public String error() { int o = __offset(4); return o != 0 ? __string(o + bb_pos) : null; }
  public int video() { int o = __offset(6); return o != 0 ? bb.getInt(o + bb_pos) : -1; }
  public int registered() { int o = __offset(8); return o != 0 ? bb.getInt(o + bb_pos) : -1; }

  public static int createReply(FlatBufferBuilder builder, int errorOffset, int video, int registered) {
    builder.startTable(3);
    Reply.addRegistered(builder, registered);
    Reply.addVideo(builder, video);
    Reply.addError(builder, errorOffset);
    return Reply.endReply(builder);
  }

  public static void startReply(FlatBufferBuilder builder) { builder.startTable(3); }
  public static void addError(FlatBufferBuilder builder, int errorOffset) { builder.addOffset(0, errorOffset, 0); }
  public static void addVideo(FlatBufferBuilder builder, int video) { builder.addInt(1, video, -1); }
  public static void addRegistered(FlatBufferBuilder builder, int registered) { builder.addInt(2, registered, -1); }
  public static int endReply(FlatBufferBuilder builder) { return builder.endTable(); }
  public static void finishReplyBuffer(FlatBufferBuilder builder, int offset) { builder.finish(offset); }
}
