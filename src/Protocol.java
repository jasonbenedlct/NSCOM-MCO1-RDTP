import java.nio.ByteBuffer;

public class Protocol {
    // Message types
    public static final byte MSG_SYN = 0x01;
    public static final byte MSG_SYN_ACK = 0x02;
    public static final byte MSG_ACK = 0x03;
    public static final byte MSG_DATA = 0x04;
    public static final byte MSG_FIN = 0x05;
    public static final byte MSG_FIN_ACK = 0x06;
    public static final byte MSG_ERROR = 0x07;
    public static final byte MSG_UPLOAD = 0x08;
    public static final byte MSG_DOWNLOAD = 0x09;

    // Error handling
    public static final byte ERR_FILE_NOT_FOUND = 0x01;
    public static final byte ERR_SESSION_MISMATCH = 0x02;
    public static final byte ERR_SESSION_TIMEOUT = 0x03;

    // settings
    public static final int HEADER_SIZE = 15;
    public static final int SEGMENT_SIZE = 1500;
    public static final int MAX_RETRIES = 10;
    public static final int TIMEOUT_MS = 2000;

    // build byte packet based on given parameters
    public static byte[] buildPacket (byte type, int sessionId,
                                      int seqNum, int ackNum, byte[] payload) {
        int payloadLen;

        if (payload == null) payloadLen = 0;
        else payloadLen = payload.length;

        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payloadLen);

        buf.put(type);
        buf.putInt(sessionId);
        buf.putInt(seqNum);
        buf.putInt(ackNum);
        buf.putShort((short) payloadLen);

        if (payload!=null) buf.put(payload);

        return buf.array();
    }

    // parse packet data based on given byte array
    public static Packet parsePacket(byte[] data) {
        if (data.length < HEADER_SIZE) return null; // not our packet!

        ByteBuffer buf = ByteBuffer.wrap(data);

        Packet p = new Packet();

        p.type = buf.get();
        p.sessionId = buf.getInt();
        p.seqNum = buf.getInt();
        p.ackNum = buf.getInt();
        p.length = buf.getShort() & 0xFFFF;
        p.payload = new byte[p.length];
        buf.get(p.payload);

        return p;
    }
}
