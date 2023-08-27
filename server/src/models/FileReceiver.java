package models;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * File Receiver (Util Class)
 * @responsibility Listening for File transfer.
 * @feature One File per Socket connection:
 * when the File is transferred, the corresponded Socket must be closed & discarded.
 */
public class FileReceiver {

    private static final int FILE_TRANSFER_FRAGMENT_SIZE = 1024; // bytes
    private static final int FILE_TRANSFER_SERVER_PORT = 23333;
    protected static final File FOLDER_TO_SLICE = new File(".\\.tmp\\toSlice");
    protected static final File FOLDER_TO_INFER = new File(".\\.tmp\\toInfer");

    private static Thread workingTask;

    /**
     * Start FileReceiver listening if it's not yet started.
     */
    public static void startFileReceiver() {
        if (workingTask == null || !workingTask.isAlive()) {
            workingTask = new Thread(() -> {
                while (true) {
                    try {
                        receive();
                    } catch (IllegalArgumentException ex){
                        System.err.println("[ERROR] File Data Stream Received: Illegal Format.");
                        continue;
                    }
                }
            }, "File-Receiver");
            workingTask.start();
        }
    }

    /**
     * Wait for File transfer connection.
     * This will BLOCK the thread until the connection is established.
     * @throws IllegalArgumentException File Stream data is in illegal format.
     */
    private static void receive() throws IllegalArgumentException {
        try (ServerSocket fileServerSocket = new ServerSocket(FILE_TRANSFER_SERVER_PORT);
             Socket fileTransferSocket = fileServerSocket.accept();
             DataInputStream inputStream = new DataInputStream(fileTransferSocket.getInputStream()))
        {
            // receive FileUsage metadata
            FileUsage fileUsage = FileUsage.valueOf(inputStream.readUTF());

            File destFile;
            // FileUsage determines where to store the file
            switch (fileUsage) {
                case TO_SLICE -> {
                    destFile = new File(FOLDER_TO_SLICE, inputStream.readUTF());
                }
                case TO_INFER -> {
                    destFile = new File(FOLDER_TO_INFER, inputStream.readUTF());
                }
                default -> { // should never reach
                    destFile = null;
                }
            }
            destFile.deleteOnExit(); // register auto-deletion

            DataOutputStream fileOutStream = new DataOutputStream(new FileOutputStream(destFile));

            // Write to File
            byte[] in = new byte[FILE_TRANSFER_FRAGMENT_SIZE];
            int len;
            while ((len = inputStream.read(in)) != -1) {
                fileOutStream.write(in, 0, len);
            }
            fileOutStream.close();

            System.out.println("[SERVER] File Received: " + destFile);
        } catch (IOException e) {
            return;
        }
    }
}
