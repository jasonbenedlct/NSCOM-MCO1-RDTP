import java.io.*;
import java.net.*;
import java.util.*;

public class Client {

    public static void main (String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("Usage: java Client <host> <port> <download/upload> <filename>");
            return;
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String operation = args[2];
        String filename = args[3];

        DatagramSocket socket = new DatagramSocket();
        InetAddress serverAddress = InetAddress.getByName(host);

        System.out.println("Connecting to " + host + ":" + port);

        // handshake

        int[] seqNums = doHandshake(socket, serverAddress, port);
        if (seqNums == null) {
            System.out.println("Handshake failed. Exiting");
            return;
        }

        int sessionID = seqNums[0];
        int clientSeq = seqNums[1];

        if (operation.equalsIgnoreCase("download")) {
            handleDownload(socket, serverAddress, port, sessionID, clientSeq, filename);
        } else if (operation.equalsIgnoreCase("upload")) {
            handleUpload(socket, serverAddress, port, sessionID, clientSeq, filename);
        } else {
            System.out.println("Unknown operation: " + operation);
        }

        socket.close();
    }

    private static int[] doHandshake(DatagramSocket socket, InetAddress serverAddress, int port) throws Exception {

        // send syn
        int clientSeq = new Random().nextInt();
        byte[] synPacket = Protocol.buildPacket(Protocol.MSG_SYN, 0, clientSeq, 0, null);
        sendPacket(socket, synPacket, serverAddress, port);
        System.out.println("Sent syn");

        // wait for syn-ack
        socket.setSoTimeout(Protocol.TIMEOUT_MS);
        byte[] buffer = new byte[Protocol.HEADER_SIZE + Protocol.SEGMENT_SIZE];

        for (int attempt = 0; attempt < Protocol.MAX_RETRIES; attempt++) {
            try {
                DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                socket.receive(incoming);
                Packet response = Protocol.parsePacket(incoming.getData());

                if (response == null || response.type != Protocol.MSG_SYN_ACK) {
                    System.out.println("Expected SYN-ACK, got something else. Retrying... :D");
                    continue;
                }

                // make sure server acknowledged syn
                if (response.ackNum != clientSeq + 1) {
                    System.out.println("Wrong ACK number. Retrying... :D");
                    continue;
                }

                int sessionID = response.sessionId;
                System.out.println("Handshake complete! :D Session ID: " + sessionID);

                return new int[] {sessionID, clientSeq+1};
            } catch (SocketTimeoutException e) {

                byte[] errorPayload = new byte[]{Protocol.ERR_SESSION_TIMEOUT};
                byte[] errorPacket = Protocol.buildPacket(
                        Protocol.MSG_ERROR, 0, clientSeq, 0, errorPayload
                );
                sendPacket(socket, errorPacket, serverAddress, port);

                System.out.println("Timeout waiting for SYN-ACK, retrying... (" + (attempt + 1) + "/" + Protocol.MAX_RETRIES + ")");
                sendPacket(socket, synPacket, serverAddress, port);
            }

        }
        return null;
    }

    private static void handleDownload(DatagramSocket socket, InetAddress serverAddress, int port,
                                       int sessionId, int clientSeq, String filename) throws Exception{
        // send download request
        byte[] downloadRequest = Protocol.buildPacket(
                Protocol.MSG_DOWNLOAD, sessionId, clientSeq, 0, filename.getBytes()
        );
        sendPacket(socket, downloadRequest, serverAddress, port);
        System.out.println("Sent DOWNLOAD request for: " + filename);
        clientSeq++;

        // wait for file size ACK
        socket.setSoTimeout(Protocol.TIMEOUT_MS);
        byte[] buffer = new byte[Protocol.HEADER_SIZE + Protocol.SEGMENT_SIZE];
        DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
        socket.receive(incoming);

        Packet response = Protocol.parsePacket(incoming.getData());

        // check for error (file not found etc.)
        if (response == null) return;
        if (response.type == Protocol.MSG_ERROR) {
            byte errorCode = response.payload[0];
            if (errorCode == Protocol.ERR_FILE_NOT_FOUND) {
                System.out.println("Server error: file not found.");
            } else if (errorCode == Protocol.ERR_SESSION_MISMATCH) {
                System.out.println("Server error: session mismatch.");
            } else {
                System.out.println("Server error: unknown error.");
            }
            return;
        }

        // extract file size from payload
        String meta = new String(response.payload).trim();
        long fileSize = Long.parseLong(meta.split(":")[1]);
        System.out.println("File size: " + fileSize + " bytes");

        // receiving chunks and write to file
        // saving to a local downloads folder
        File dir = new File("client_downloads");
        dir.mkdirs();
        File file = new File(dir, filename);
        FileOutputStream fos = new FileOutputStream(file);

        long totalReceived = 0;
        int expectedSeq = response.seqNum + 1;

        while (totalReceived < fileSize) {
            try {
                incoming = new DatagramPacket(buffer, buffer.length);
                socket.receive(incoming);
                Packet dataPacket = Protocol.parsePacket(incoming.getData());

                if (dataPacket == null || dataPacket.sessionId != sessionId) continue;

                // server signals end of file
                if (dataPacket.type == Protocol.MSG_FIN) {
                    // send ACK for the FIN
                    byte[] ackPacket = Protocol.buildPacket(
                            Protocol.MSG_ACK, sessionId, clientSeq, dataPacket.seqNum + 1, null
                    );
                    sendPacket(socket, ackPacket, serverAddress, port);
                    break;
                }

                // ignore wrong sequence numbers
                if (dataPacket.type != Protocol.MSG_DATA
                        || dataPacket.seqNum != expectedSeq) {
                    // re-send last ACK
                    byte[] ackPacket = Protocol.buildPacket(
                            Protocol.MSG_ACK, sessionId, clientSeq, expectedSeq, null
                    );
                    sendPacket(socket, ackPacket, serverAddress, port);
                    continue;
                }

                // write chunk to file
                fos.write(dataPacket.payload);
                totalReceived += dataPacket.payload.length;
                expectedSeq++;

                // print progress
                int percent = (int)(totalReceived * 100 / fileSize);
                System.out.print("\rDownloading... " + percent + "%");

                // --- Step 4: send ACK ---
                byte[] ackPacket = Protocol.buildPacket(
                        Protocol.MSG_ACK, sessionId, clientSeq, expectedSeq, null
                );
                sendPacket(socket, ackPacket, serverAddress, port);

            } catch (SocketTimeoutException e) {
                byte[] errorPayload = new byte[]{Protocol.ERR_SESSION_TIMEOUT};
                byte[] errorPacket = Protocol.buildPacket(
                        Protocol.MSG_ERROR, sessionId, clientSeq, 0, errorPayload
                );
                sendPacket(socket, errorPacket, serverAddress, port);
                System.out.println("\nTimeout waiting for data. Aborting.");
                break;
            }
        }

        fos.close();
        System.out.println("\nDownload complete! Saved to: " + file.getPath());

        // --- Step 5: send FIN to close session ---
        byte[] finPacket = Protocol.buildPacket(
                Protocol.MSG_FIN, sessionId, clientSeq, 0, null
        );
        sendPacket(socket, finPacket, serverAddress, port);

        // wait for FIN-ACK
        try {
            incoming = new DatagramPacket(buffer, buffer.length);
            socket.receive(incoming);
            Packet finAck = Protocol.parsePacket(incoming.getData());
            if (finAck != null && finAck.type == Protocol.MSG_FIN_ACK) {
                System.out.println("Connection closed cleanly.");
            }
        } catch (SocketTimeoutException e) {
            byte[] errorPayload = new byte[]{Protocol.ERR_SESSION_TIMEOUT};
            byte[] errorPacket = Protocol.buildPacket(
                    Protocol.MSG_ERROR, sessionId, clientSeq, 0, errorPayload
            );
            sendPacket(socket, errorPacket, serverAddress, port);
            System.out.println("No FIN-ACK received. Closing anyway.");
        }

    }

    private static void handleUpload (DatagramSocket socket, InetAddress serverAddress, int port,
                                      int sessionId, int clientSeq, String filename) throws Exception {
        // check if file exists
        File file = new File(filename);
        if (!file.exists()) {
            System.out.println("File not found: " + filename);
            return;
        }

        long fileSize = file.length();

        //  send upload request with metadata
        String meta = "filename:" + file.getName() + "|size:" + fileSize;
        byte[] uploadRequest = Protocol.buildPacket(
                Protocol.MSG_UPLOAD, sessionId, clientSeq, 0, meta.getBytes()
        );
        sendPacket(socket, uploadRequest, serverAddress, port);
        System.out.println("Sent UPLOAD request for: " + filename);
        clientSeq++;

        // wait for server ready
        socket.setSoTimeout(Protocol.TIMEOUT_MS);
        byte[] buffer = new byte[Protocol.HEADER_SIZE + Protocol.SEGMENT_SIZE];
        DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
        socket.receive(incoming);

        Packet response = Protocol.parsePacket(incoming.getData());

        if (response == null) return;
        if (response.type == Protocol.MSG_ERROR) {
            byte errorCode = response.payload[0];
            if (errorCode == Protocol.ERR_SESSION_MISMATCH) {
                System.out.println("Server error: session mismatch.");
            } else {
                System.out.println("Server error: unknown error.");
            }
            return;
        }

        if (response.type != Protocol.MSG_ACK
                || !new String(response.payload).trim().equals("ready")) {
            System.out.println("Server not ready. Aborting.");
            return;
        }
        System.out.println("Server ready. Starting upload...");

        //  read and send file in chunks
        FileInputStream fis = new FileInputStream(file);
        byte[] chunk = new byte[Protocol.SEGMENT_SIZE];
        int bytesRead;
        long totalSent = 0;

        while ((bytesRead = fis.read(chunk)) != -1) {
            byte[] data = Arrays.copyOf(chunk, bytesRead);

            // send reliably with retransmission
            boolean success = sendReliable(socket, serverAddress, port,
                    sessionId, clientSeq, data);
            if (!success) {
                System.out.println("\nServer stopped responding. Aborting.");
                fis.close();
                return;
            }

            totalSent += bytesRead;
            clientSeq++;

            int percent = (int)(totalSent * 100 / fileSize);
            System.out.print("\rUploading... " + percent + "%");
        }

        fis.close();
        System.out.println("\nUpload complete: " + filename);

        // send FIN
        byte[] finPacket = Protocol.buildPacket(
                Protocol.MSG_FIN, sessionId, clientSeq, 0, null
        );
        sendPacket(socket, finPacket, serverAddress, port);

        // wait for FIN-ACK
        try {
            incoming = new DatagramPacket(buffer, buffer.length);
            socket.receive(incoming);
            Packet finAck = Protocol.parsePacket(incoming.getData());
            if (finAck != null && finAck.type == Protocol.MSG_FIN_ACK) {
                System.out.println("Connection closed cleanly.");
            }
        } catch (SocketTimeoutException e) {
            byte[] errorPayload = new byte[]{Protocol.ERR_SESSION_TIMEOUT};
            byte[] errorPacket = Protocol.buildPacket(
                    Protocol.MSG_ERROR, sessionId, clientSeq, 0, errorPayload
            );
            sendPacket(socket, errorPacket, serverAddress, port);
            System.out.println("No FIN-ACK received. Closing anyway.");
        }

    }

    private static void sendPacket(DatagramSocket socket, byte[] data,
                                   InetAddress address, int port) throws Exception {
        DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
        socket.send(packet);
    }

    private static boolean sendReliable(DatagramSocket socket, InetAddress address,
                                        int port, int sessionId,
                                        int seq, byte[] payload) throws Exception {
        byte[] packet = Protocol.buildPacket(
                Protocol.MSG_DATA, sessionId, seq, 0, payload
        );

        socket.setSoTimeout(Protocol.TIMEOUT_MS);

        for (int attempt = 0; attempt < Protocol.MAX_RETRIES; attempt++) {
            sendPacket(socket, packet, address, port);
            try {
                byte[] buffer = new byte[Protocol.HEADER_SIZE + Protocol.SEGMENT_SIZE];
                DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                socket.receive(incoming);
                Packet ack = Protocol.parsePacket(incoming.getData());

                if (ack != null && ack.type == Protocol.MSG_ACK
                        && ack.sessionId == sessionId
                        && ack.ackNum == seq + 1) {
                    return true;
                }
            } catch (SocketTimeoutException e) {
                System.out.println("\nTimeout, retrying... (" + (attempt + 1) + "/" + Protocol.MAX_RETRIES + ")");
            }
        }
        return false;
    }
}
