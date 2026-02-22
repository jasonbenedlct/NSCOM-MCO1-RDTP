public class Packet {
    public byte type;
    public int sessionId;
    public int seqNum;
    public int ackNum;
    public int length;
    public byte[] payload;
}
