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

    }

}
