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

    private static void handleDownload (DatagramSocket socket, InetAddress clientAddr, int clientPort,
                                        int sessionID, Packet request, int serverSeq) throws Exception {
        // get the file name
        String filename = new String(request.payload).trim();
        File file = new File(FILE_DIRECTORY, filename);
        System.out.println("Client wants to download : " + filename);


        // check if file exists
        if (!file.exists()) {
            byte[] errorPayload = new byte[]{Protocol.ERR_FILE_NOT_FOUND};
            byte [] errorPacket = Protocol.buildPacket(Protocol.MSG_ERROR, sessionID, serverSeq, 0, errorPayload);

            sendPacket(socket, errorPacket, clientAddr, clientPort);
            System.out.println("File not found: " + filename);
            return;
        }

        // tell client file size
        long fileSize = file.length();
        String meta = "size:" + fileSize;
        byte [] ackPacket = Protocol.buildPacket(Protocol.MSG_ACK, sessionID, serverSeq, 0, meta.getBytes());

        sendPacket(socket, ackPacket, clientAddr, clientPort);
        serverSeq++;

        //  read and send file in chunks
        FileInputStream fis = new FileInputStream(file);
        byte[] chunk = new byte[Protocol.SEGMENT_SIZE];
        int bytesRead;
        int totalSent = 0;

        while((bytesRead = fis.read(chunk)) != -1) {
            byte[] data = Arrays.copyOf(chunk, bytesRead);

            boolean success = sendReliable (socket, clientAddr, clientPort, sessionID, serverSeq, data);

            if (!success) {
                System.out.println("Client stopped responding. Aborting. :( ");
                fis.close();
                return;
            }

            totalSent += bytesRead;
            serverSeq++;

            int percent = (int)(totalSent * 100 / fileSize);
            System.out.println("\rSending... " + percent + "%");
        }

        fis.close();
        System.out.println("\nFile sent: " + filename);

        // send fin
        byte[] finPacket = Protocol.buildPacket(Protocol.MSG_FIN, sessionID, serverSeq, 0, null);

        sendPacket(socket, finPacket, clientAddr,  clientPort);
    }

    private static void handleUpload (DatagramSocket socket, InetAddress clientAddr, int clientPort,
                                      int sessionID, Packet request, int serverSeq) throws Exception {
        // step 1: filename and file size
        String meta = new String(request.payload).trim();
        String[] parts = meta.split("\\|");

        String filename = parts[0].split(":")[1];
        long fileSize = Long.parseLong(parts[1].split(":")[1]);

        System.out.println("Client wants to upload: " + filename + " (" + fileSize + " bytes)");

        byte[] readyPacket = Protocol.buildPacket(Protocol.MSG_ACK, sessionID, serverSeq, 0, "ready".getBytes());

        sendPacket(socket, readyPacket, clientAddr, clientPort);
        serverSeq++;

        File file = new File (FILE_DIRECTORY, filename);
        FileOutputStream fos = new FileOutputStream(file);

        long totalReceived = 0;
        int expectedSeq = request.seqNum + 1;
        socket.setSoTimeout(Protocol.TIMEOUT_MS);

        while (totalReceived < fileSize) {
            try {
                byte[] buffer = new byte[Protocol.HEADER_SIZE + Protocol.SEGMENT_SIZE];
                DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                socket.receive(incoming);

                Packet dataPacket = Protocol.parsePacket(incoming.getData());

                // ignore broken packets
                if (dataPacket == null || dataPacket.sessionId != sessionID) continue;

                // check if client is done early
                if (dataPacket.type == Protocol.MSG_FIN) break;

                // resend last ack
                if (dataPacket.type != Protocol.MSG_DATA || dataPacket.seqNum != expectedSeq) {
                    byte[] ackPacket = Protocol.buildPacket(
                            Protocol.MSG_ACK, sessionID, serverSeq, expectedSeq, null
                    );
                    sendPacket(socket, ackPacket, clientAddr, clientPort);
                    continue;
                }

                //write chunk to file
                fos.write(dataPacket.payload);
                totalReceived += dataPacket.payload.length;
                expectedSeq++;

                int percent = (int)(totalReceived * 100 / fileSize);
                System.out.println("\rReceiving..." + percent + "%");

                byte[] ackPacket = Protocol.buildPacket(
                        Protocol.MSG_ACK, sessionID, serverSeq, expectedSeq, null
                );

                sendPacket(socket, ackPacket, clientAddr, clientPort);

            } catch (SocketTimeoutException e) {
                System.out.println("\nTimeout waiting for data. Aborting");
                break;
            }
        }
    }

    private static void sendPacket(DatagramSocket socket, byte[] data, InetAddress address, int port) throws Exception {
        DatagramPacket p = new DatagramPacket(data, data.length, address, port);
        socket.send(p);
    }

    private static boolean sendReliable (DatagramSocket socket, InetAddress address, int port,
                                         int sessionID, int seqNum, byte[] payload) throws Exception {
        byte [] packet = Protocol.buildPacket(Protocol.MSG_DATA, sessionID, seqNum, 0, payload);

        socket.setSoTimeout(Protocol.TIMEOUT_MS);

        for (int attempt = 0; attempt < Protocol.MAX_RETRIES; attempt++) {
            sendPacket(socket, packet, address, port);

            try {
                byte[] buffer = new byte[Protocol.HEADER_SIZE + Protocol.SEGMENT_SIZE];
                DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                socket.receive(incoming);
                Packet ack = Protocol.parsePacket(incoming.getData());

                if (ack != null && ack.type == Protocol.MSG_ACK &&
                    ack.sessionId == sessionID && ack.ackNum == seqNum + 1) {
                    return true;
                }
            } catch (SocketTimeoutException e) {
                System.out.println("\nTimeout, retrying... (" + (attempt+1) +"/" + Protocol.MAX_RETRIES +")");
            }
        }
        return false;
    }


}
