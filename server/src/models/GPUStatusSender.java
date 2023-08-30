package models;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;

import static server.Server.CHARSET_DEFAULT;

/**
 * GPU Status Sender (Util Class)
 *
 * @responsibility Send GPU Status to Client
 * @feature One Refresh per Socket connection:
 *          when the GPU Status is transferred, the corresponded Socket must be closed & discarded.
 * @design UTILITY
 */
public class GPUStatusSender {

    private static final int GPU_STATUS_SERVER_PORT = 43687;

    private static Thread workingTask;

    /**
     * Start GPUStatusSender if it's not yet started.
     */
    public static void startGPUStatusSender() {
        if (workingTask == null || !workingTask.isAlive()) {
            workingTask = new Thread(() -> {
                while (true) {
                    sendStatus();
                }
            }, "GPU-Status-Sender");
            workingTask.start();
        }
    }

    /**
     * This will BLOCK the thread until the connection is established.
     */
    private static void sendStatus() {
        try (ServerSocket gpuStatusServerSocket = new ServerSocket(GPU_STATUS_SERVER_PORT);
             Socket gpuStatusSocket = gpuStatusServerSocket.accept();
             PrintStream outputStream = new PrintStream(gpuStatusSocket.getOutputStream(), true, CHARSET_DEFAULT))
        {
            ProcessBuilder gpuQueryBuilder = new ProcessBuilder("nvidia-smi.exe");

            // Refresh GPU Status
            Process gpuQuery = gpuQueryBuilder.start();

            // transfer new GPU-status from Process InputStream -> Socket OutputStream
            BufferedReader in = new BufferedReader(new InputStreamReader(gpuQuery.getInputStream(),
                    CHARSET_DEFAULT));
            String line;
            while ((line = in.readLine()) != null) {
                outputStream.println(line);
            }
            in.close();
            outputStream.flush();

        } catch (IOException e) {
            return;
        }
    }
}
