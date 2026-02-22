import java.io.*;
import java.net.*;
import java.util.*;

public class Server {
    private static final String FILE_DIRECTORY = "server_files";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java Server <port>");
            return;
        }

        int port = Integer.parseInt(args[0]);
        DatagramSocket sock = new DatagramSocket(port);
        System.out.println("Server listening on port " + port);
        System.out.println("Serving files from : " + FILE_DIRECTORY);

        while (true) {
            handleClient(sock);
        }
    }

    private static void handleClient(DatagramSocket socket) throws Exception {
        // receiving syn
        byte[] buffer = new byte[Protocol.HEADER_SIZE + Protocol.SEGMENT_SIZE];
        DatagramPacket incomingPacket = new DatagramPacket(buffer, buffer.length);
        socket.receive(incomingPacket);

        // remember client
        InetAddress clientAddr = incomingPacket.getAddress();
        int clientPort = incomingPacket.getPort();

        // parse bytes into packet obj
        Packet synPacket = Protocol.parsePacket(incomingPacket.getData());

        if (synPacket == null || synPacket.type != Protocol.MSG_SYN) {
            System.out.println("Did not get expected SYN packet. Ignoring :)");
            return;
        }

        System.out.println("Received SYN from " + clientAddr + ":" + clientPort);

        // sending syn-ack
        int sessionId = new Random().nextInt();
        int serverSeq = new Random().nextInt();

        byte[] synAck = Protocol.buildPacket(
                Protocol.MSG_SYN_ACK,
                sessionId,
                serverSeq,
                synPacket.seqNum + 1,
                null
        );

        DatagramPacket outgoingPacket = new DatagramPacket(synAck, synAck.length, clientAddr, clientPort);
        socket.send(outgoingPacket);
        System.out.println("Sent SYN-ACK. Session ID: " + sessionId);

        // user request either download or upload file
        socket.receive(incomingPacket);
        Packet request = Protocol.parsePacket(incomingPacket.getData());

        if (request == null) return; // no request from user, just return

        if (request.sessionId != sessionId) {
            System.out.println("Given session ID does not match current session ID. Ignoring :)");
            return;
        }

        if (request.type == Protocol.MSG_DOWNLOAD) {
            System.out.println("handle download implement tomorrow plz");
        }
        else if (request.type == Protocol.MSG_UPLOAD) {
            System.out.println("handle upload implement tomorrow plz");
        }
        else {
            System.out.println("Unknown request type. Ignoring :)");
        }
    }

}
