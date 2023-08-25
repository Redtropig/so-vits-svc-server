package models;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Instruction Receiver
 * @responsibility Listening for Instructions.
 * @feature One Instruction per Socket connection:
 * when the instruction is received, the corresponded Socket must be closed & discarded.
 */
public class InstructionReceiver {
    private static final int INSTRUCTION_TRANSFER_FRAGMENT_SIZE = 1024; // bytes
    private static final ExecutionAgent executionAgent = ExecutionAgent.getExecutionAgent();

    private static int port;
    private static Thread workingTask;

    /**
     * Start the InstructionReceiver listening if it's not yet started.
     * @param port the expected port for newly started listening.
     *             Ignored if the port listening has already started.
     * @return the actual port in which the current InstructionReceiver is listening on.
     */
    public static int startInstructionReceiver(int port) {
        if (workingTask == null || !workingTask.isAlive()) {
            InstructionReceiver.port = port;
            workingTask = new Thread(() -> {
                while (true) {
                    receive();
                }
            }, "Instruction-Receiver");
            workingTask.start();
        }

        return InstructionReceiver.port;
    }

    /**
     * Wait for Instruction transfer connection.
     * This will BLOCK the thread until the connection established.
     */
    private static void receive() {
        try (ServerSocket serverSocket = new ServerSocket(InstructionReceiver.port);
             Socket instructionSocket = serverSocket.accept();
             DataInputStream inputStream = new DataInputStream(instructionSocket.getInputStream());
             DataOutputStream outStream = new DataOutputStream(instructionSocket.getOutputStream()))
        {
            InstructionType instructionType = InstructionType.valueOf(inputStream.readUTF());

            System.out.println("[INFO] " + instructionType.name() +
                    " Instruction from: " + instructionSocket.getInetAddress() + ':' + instructionSocket.getPort());

            // InstructionType fetch
            switch (instructionType) {
                case CLEAR -> {

                }
                case SLICE -> {

                }
                case PREPROCESS -> {

                }
                case TRAIN -> {

                }
                case INFER -> {

                }
                default -> {
                    System.err.println("[ERROR] Instruction Stream Received: Illegal Format.");
                    return;
                }
            }



        } catch (IOException e) {
            return;
        }
    }
}
