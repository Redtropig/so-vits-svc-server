package models;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;

import static models.ExecutionAgent.*;
import static models.FileReceiver.FOLDER_TO_SLICE;
import static server.Server.CHARSET_DEFAULT;

/**
 * Instruction Receiver (Util Class)
 * @responsibility Listening for Instructions, Construct corresponded Command, and Schedule Execution.
 * @feature One Instruction per Socket connection:
 * when the instruction is received, the corresponded Socket must be closed & discarded.
 */
public class InstructionReceiver {
    private static final int INSTRUCTION_TRANSFER_FRAGMENT_SIZE = 1024; // bytes
    private static final File SLICING_OUT_DIR_DEFAULT = new File(SO_VITS_SVC_DIR + "\\dataset_raw");
    private static final File PREPROCESS_OUT_DIR_DEFAULT = new File(SO_VITS_SVC_DIR + "\\dataset\\44k");
    private static final File INFERENCE_INPUT_DIR_DEFAULT = new File(SO_VITS_SVC_DIR + "\\raw");
    private static final File TRAINING_LOG_DIR_DEFAULT = new File(SO_VITS_SVC_DIR + "\\logs\\44k");
    private static final String[] AUDIO_FILE_EXTENSIONS_ACCEPTED = {"wav"};
    private static final String AUDIO_FILE_EXTENSIONS_DESCRIPTION = "Wave File(s)(*.wav)";
    private static final ExecutionAgent EXECUTION_AGENT = ExecutionAgent.getExecutionAgent();

    private static int port;
    private static Thread workingTask;
    private static Socket instructionSocket; // current Socket

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
                    try {
                        receive();
                    } catch (IllegalArgumentException | JSONException ex) {
                        System.err.println("[ERROR] Instruction Stream Received: Illegal Format.");
                        continue;
                    }
                }
            }, "Instruction-Receiver");
            workingTask.start();
        }

        return InstructionReceiver.port;
    }

    /**
     * Wait for Instruction transfer connection.
     * This will BLOCK the thread until the connection established.
     * @throws IllegalArgumentException Instruction Stream data is in illegal format.
     * @throws JSONException Instruction Stream data is in illegal format.
     */
    private static void receive() throws IllegalArgumentException, JSONException {
        try (ServerSocket serverSocket = new ServerSocket(InstructionReceiver.port)) {
            instructionSocket = serverSocket.accept();
            DataInputStream inputStream = new DataInputStream(instructionSocket.getInputStream());
            PrintStream outputStream = new PrintStream(instructionSocket.getOutputStream(), true, CHARSET_DEFAULT);

            // Read Instruction JSONString
            String instructionJSONString = inputStream.readUTF();

            // InstructionType fetch
            JSONObject instructionJSONObject = new JSONObject(instructionJSONString);
            InstructionType instructionType = InstructionType.valueOf(instructionJSONObject.getString("INSTRUCTION"));

            System.out.println("[SERVER] " + instructionType +
                    " Instruction from: " + instructionSocket.getInetAddress() + ':' + instructionSocket.getPort());

            // Parse Instruction
            switch (instructionType) {

                case CLEAR -> {
                    // determine which directory to be cleared
                    String dirToClear = instructionJSONObject.getString("dir");
                    switch (InstructionType.valueOf(dirToClear.toUpperCase())) {
                        case SLICE -> {
                            outputStream.println("[SERVER] Clearing Slice Output Folder...");
                            scheduleRemoveSubDirectories(SLICING_OUT_DIR_DEFAULT);
                        }
                        case PREPROCESS -> {
                            outputStream.println("[SERVER] Clearing Preprocess Output Folder...");
                            scheduleRemoveSubDirectories(PREPROCESS_OUT_DIR_DEFAULT);
                        }
                        case TRAIN -> {
                            outputStream.println("[SERVER] Clearing Train Log Folder...");
                            /* Clear Train Log */
                            for (File subFile : Objects.requireNonNull(TRAINING_LOG_DIR_DEFAULT.listFiles((f) ->
                                    !(f.getName().equals("diffusion") ||
                                            f.getName().equals("D_0.pth") ||
                                            f.getName().equals("G_0.pth"))))) {
                                if (subFile.isDirectory()) {
                                    scheduleRemoveDirectory(subFile);
                                } else {
                                    // schedule a file deletion
                                    String[] command = {"cmd.exe", "/c"};
                                    EXECUTION_AGENT.executeLater(
                                            command,
                                            null,
                                            (process) -> {
                                                subFile.delete();
                                                System.out.println("[SERVER] File Removed: \"" + subFile.getPath() + "\"");
                                                outputStream.println("[SERVER] File Removed: \"" + subFile.getName() + "\"");
                                            });
                                }
                            }
                            // End Instruction Execution
                            String[] command = {"cmd.exe", "/c"};
                            EXECUTION_AGENT.executeLater(
                                    command,
                                    null,
                                    (process) -> {
                                        try {
                                            outputStream.println("[SERVER] Deletion Complete.");
                                            instructionSocket.close();
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
                            /* End Clear Train Log */
                        }
                        default -> {
                            throw new IllegalArgumentException("dirToClear is Invalid");
                        }
                    }
                }
                case SLICE -> {
                    outputStream.println("[SERVER] Slicing Audio(s)...");
                    File[] voiceAudioFiles = FOLDER_TO_SLICE.listFiles();
                    // slice each voice file
                    assert voiceAudioFiles != null;
                    for (int i = voiceAudioFiles.length - 1; i >= 0; i--) {
                        File voiceFile = voiceAudioFiles[i];

                        // command construction
                        String[] command = {
                                PYTHON_EXE.getAbsolutePath(),
                                SLICER_PY.getAbsolutePath(),
                                voiceFile.getPath(),
                                "--out",
                                SLICING_OUT_DIR_DEFAULT.getPath() + "\\" + instructionJSONObject.getString("spk"),
                                "--min_interval",
                                String.valueOf(instructionJSONObject.getInt("min_interval"))
                        };

                        // schedule a task
                        int finalI = i;
                        EXECUTION_AGENT.executeLater(
                                command,
                                null,
                                (process) -> {
                                    if (process.exitValue() == 0) {
                                        outputStream.println("[SERVER] Slicing completed: " + voiceFile.getName());
                                    } else {
                                        outputStream.println("[ERROR] \"" +
                                                SLICER_PY.getName() +
                                                "\" terminated unexpectedly, exit code: " +
                                                process.exitValue()
                                        );
                                    }
                                    // delete tmp voice file
                                    voiceFile.delete();

                                    if (finalI == 0) {
                                        outputStream.println("[SERVER] All Slicing Done.");

                                        // discard this Socket
                                        try {
                                            instructionSocket.close();
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    }
                                }
                        );
                    }
                }
                case PREPROCESS -> {

                }
                case TRAIN -> {

                }
                case INFER -> {

                }
                default -> {

                }
            }
            // execute ASAP
            EXECUTION_AGENT.invokeExecution();

        } catch (IOException e) {
            return;
        }
    }

    /**
     * Schedule the removal of a directory.
     *
     * @param directory directory to be removed
     * @dependency Windows OS
     */
    private static void scheduleRemoveDirectory(File directory) throws IOException {
        PrintStream outputStream = new PrintStream(instructionSocket.getOutputStream());
        if (directory.isDirectory()) {
            String[] command = {"cmd.exe", "/c", "rmdir", "/s", "/q", directory.getAbsolutePath()};

            // schedule a task
            EXECUTION_AGENT.executeLater(
                    command,
                    null,
                    (process) -> {
                        if (process.exitValue() == 0) {
                            System.out.println("[SERVER] Directory Removed: \"" + directory.getPath() + "\"");
                            outputStream.println("[SERVER] Directory Removed: \"" + directory.getName() + "\"");
                        } else {
                            outputStream.println("[ERROR] \"" +
                                    command[0] +
                                    "\" terminated unexpectedly, exit code: " +
                                    process.exitValue()
                            );
                        }
                    });
        }
    }

    /**
     * Schedule the removal of all Sub Directories of the given directory.
     *
     * @param directory the parent directory of directories to be removed
     * @dependency Windows OS
     */
    private static void scheduleRemoveSubDirectories(File directory) throws IOException {
        for (File subDir : Objects.requireNonNull(directory.listFiles(File::isDirectory))) {
            scheduleRemoveDirectory(subDir);
        }

        // End Instruction Execution
        String[] command = {"cmd.exe", "/c"};
        EXECUTION_AGENT.executeLater(
                command,
                null,
                (process) -> {
                    // discard this Socket
                    try {
                        new PrintStream(instructionSocket.getOutputStream()).println("[SERVER] Deletion Complete.");
                        instructionSocket.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

}
