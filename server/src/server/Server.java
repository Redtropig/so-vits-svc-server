package server;

import models.FileReceiver;
import models.InstructionReceiver;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class Server {
    public static final Charset CHARSET_DEFAULT = StandardCharsets.UTF_8;

    public Server() {
        Scanner in = new Scanner(System.in);
        System.out.println("set Service Port (1025~65535):");

        // Stand-By Listening
        int actualPort = InstructionReceiver.startInstructionReceiver(in.nextInt());
        FileReceiver.startFileReceiver();

        System.out.println("[INFO] Server started listening on port:" + actualPort);

        /* Server Console Interactions */
        while (in.hasNext()) {
            String command = in.next();

            switch (command) {
                case "quit" -> {
                    System.out.println("[INFO] Server Terminating...");
                    System.exit(0);
                }
                case "help" -> {
                    System.out.println("Commands: ");
                    System.out.println("quit - terminate the Server.");
                    System.out.println("help - print out this help message.");
                }
                default -> {
                    System.out.println("[!] Command not found.");
                }
            }
        }
        /* End Server Console Interactions */

    }
}
