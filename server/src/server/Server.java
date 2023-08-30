package server;

import models.ExecutionAgent;
import models.FileReceiver;
import models.GPUStatusSender;
import models.InstructionReceiver;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class Server {
    public static final Charset CHARSET_DEFAULT = StandardCharsets.UTF_8;
    private static final ExecutionAgent EXECUTION_AGENT = ExecutionAgent.getExecutionAgent();

    /**
     * Server Entry Point.
     */
    public static void startServer() {

        /* Create Stand-By Listening */
        FileReceiver.startFileReceiver();
        GPUStatusSender.startGPUStatusSender();

        // get an auto allocated port
        int port;
        try (ServerSocket probSocket = new ServerSocket(0)) {
            port = probSocket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // use auto allocated port to start Instruction Receiver
        int actualPort = InstructionReceiver.startInstructionReceiver(port);

        System.out.println("[SERVER] Server started on port: " + actualPort);
        /* End Create Stand-By Listening */

        /* Server Console Interactions */
        Scanner in = new Scanner(System.in);
        while (in.hasNext()) {
            String command = in.next();

            switch (command) {
                case "quit" -> {
                    System.out.println("[SERVER] Server Terminating...");
                    EXECUTION_AGENT.cancelAllTasks();
                    System.exit(0);
                }
                case "help" -> {
                    System.out.println("Commands: ");
                    System.out.println("quit - terminate the Server.");
                    System.out.println("help - print out this help message.");
                }
                default -> {
                    System.out.println("[!] Command not found.");
                    in.nextLine(); // clear invalid command input line
                }
            }
        }
        /* End Server Console Interactions */
    }
}
