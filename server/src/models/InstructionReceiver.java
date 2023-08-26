package models;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static models.ExecutionAgent.*;

/**
 * Instruction Receiver
 * @responsibility Listening for Instructions.
 * @feature One Instruction per Socket connection:
 * when the instruction is received, the corresponded Socket must be closed & discarded.
 */
public class InstructionReceiver {
    private static final int INSTRUCTION_TRANSFER_FRAGMENT_SIZE = 1024; // bytes
    private static final File SLICING_OUT_DIR_DEFAULT = new File(SO_VITS_SVC_DIR + "\\dataset_raw");
    private static final String[] AUDIO_FILE_EXTENSIONS_ACCEPTED = {"wav"};
    private static final String AUDIO_FILE_EXTENSIONS_DESCRIPTION = "Wave File(s)(*.wav)";
    private static final ExecutionAgent EXECUTION_AGENT = ExecutionAgent.getExecutionAgent();

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
        try (ServerSocket serverSocket = new ServerSocket(InstructionReceiver.port)) {
            Socket instructionSocket = serverSocket.accept();
            DataInputStream inputStream = new DataInputStream(instructionSocket.getInputStream());
            PrintStream outputStream = new PrintStream(instructionSocket.getOutputStream(), true, StandardCharsets.UTF_8);

            // Read Instruction JSONString
            String instructionJSONString = inputStream.readUTF();

            // InstructionType fetch
            JSONObject instructionJSONObject;
            InstructionType instructionType;
            try {
                // Parse to JSONObject
                instructionJSONObject = new JSONObject(instructionJSONString);
                instructionType = InstructionType.valueOf(instructionJSONObject.getString("INSTRUCTION"));
            } catch (IllegalArgumentException | JSONException ex) {
                System.err.println("[ERROR] Instruction Stream Received: Illegal Format.");
                return;
            }

            System.out.println("[INFO] " + instructionType +
                    " Instruction from: " + instructionSocket.getInetAddress() + ':' + instructionSocket.getPort());

            switch (instructionType) {
                case CLEAR -> {

                }
                case SLICE -> {
                    outputStream.println("[INFO] Slicing Audio(s)...");
                    File[] voiceAudioFiles = FileReceiver.FOLDER_TO_SLICE.listFiles();
                    // slice each voice file
                    assert voiceAudioFiles != null;
                    for (int i = voiceAudioFiles.length - 1; i >= 0; i--) {
                        File voiceFile = voiceAudioFiles[i];

                        // Command construction
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
                                        outputStream.println("[INFO] Slicing completed: " + voiceFile.getName());
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
                                        outputStream.println("[INFO] All Slicing Done.");

                                        // enable related interactions after batch execution
                                        try {
                                            instructionSocket.close();
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    }
                                }
                        );
                    }

                    // execute ASAP
                    EXECUTION_AGENT.invokeExecution();
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


        } catch (IOException e) {
            return;
        }
    }
}
