package models;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static models.ExecutionAgent.*;
import static models.FileReceiver.FOLDER_TO_INFER;
import static models.FileReceiver.FOLDER_TO_SLICE;
import static server.Server.CHARSET_DEFAULT;

/**
 * Instruction Receiver (Util Class)
 * @responsibility Listening for Instructions, Construct corresponded Command, and Schedule Execution.
 * @feature One Instruction per Socket connection:
 *          when the instruction is received, the corresponded Socket must be closed & discarded.
 * @design UTILITY
 */
public class InstructionReceiver {

    private static final int INSTRUCTION_TRANSFER_FRAGMENT_SIZE = 1024; // bytes
    private static final int PITCH_SHIFT_INFER_DEFAULT = 0;
    private static final int JSON_STR_INDENT_FACTOR = 2;
    private static final float CLIP_INFER_DEFAULT = 0;
    private static final File SLICING_OUT_DIR_DEFAULT = new File(SO_VITS_SVC_DIR + "\\dataset_raw");
    private static final File PREPROCESS_OUT_DIR_DEFAULT = new File(SO_VITS_SVC_DIR + "\\dataset\\44k");
    private static final File INFERENCE_INPUT_DIR_DEFAULT = new File(SO_VITS_SVC_DIR + "\\raw");
    private static final File TRAINING_LOG_DIR_DEFAULT = new File(SO_VITS_SVC_DIR + "\\logs\\44k");
    private static final File TRAINING_CONFIG = new File(SO_VITS_SVC_DIR + "\\configs\\config.json");
    private static final File TRAINING_CONFIG_LOG = new File(TRAINING_LOG_DIR_DEFAULT + "\\config.json");
    private static final File RESULTS_DIR = new File(".\\results");
    private static final String REGEX_TRAINED_MODEL_NAME = "^G_[1-9]\\d*\\.pth$";
    private static final String AUDIO_FILE_EXTENSIONS_DESCRIPTION = "Wave File(s)(*.wav)";
    private static final String AUDIO_FILE_OUT_FORMAT = "wav";
    private static final String[] AUDIO_FILE_EXTENSIONS_ACCEPTED = {"wav"};
    private static final ExecutionAgent EXECUTION_AGENT = ExecutionAgent.getExecutionAgent();

    private static int port;
    private static Thread workingTask;

    /**
     * Start InstructionReceiver listening if it's not yet started.
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
     * This will BLOCK the thread until the connection is established.
     * @throws IllegalArgumentException Instruction Stream data is in illegal format.
     * @throws JSONException Instruction Stream data is in illegal format.
     */
    private static void receive() throws IllegalArgumentException, JSONException {
        Socket instructionSocket = null;
        try (ServerSocket serverSocket = new ServerSocket(InstructionReceiver.port)) {
            Socket finalInstructionSocket = instructionSocket = serverSocket.accept(); // Typically closed in <afterExecution>
            DataInputStream inputStream = new DataInputStream(instructionSocket.getInputStream());
            DataOutputStream outputStream = new DataOutputStream(instructionSocket.getOutputStream());
            PrintStream printOut = new PrintStream(instructionSocket.getOutputStream(), true, CHARSET_DEFAULT);

            // Read Instruction JSONString
            String instructionJSONString = inputStream.readUTF();

            // InstructionType fetch
            JSONObject instructionJSONObject = new JSONObject(instructionJSONString);
            InstructionType instructionType = InstructionType.valueOf(instructionJSONObject.getString("INSTRUCTION"));

            System.out.println("[SERVER] " + instructionType +
                    " Instruction from: [" + instructionSocket.getInetAddress() + "]:" + instructionSocket.getPort());

            // Parse Instruction & Schedule Execution Tasks
            switch (instructionType) {

                case CLEAR -> {
                    // determine which directory to be cleared
                    String dirToClear = instructionJSONObject.getString("dir");
                    switch (InstructionType.valueOf(dirToClear.toUpperCase())) {
                        case SLICE -> {
                            printOut.println("[SERVER] Clearing Slice Output Folder...");
                            scheduleRemoveSubDirectories(SLICING_OUT_DIR_DEFAULT, instructionSocket);
                        }
                        case PREPROCESS -> {
                            printOut.println("[SERVER] Clearing Preprocess Output Folder...");
                            scheduleRemoveSubDirectories(PREPROCESS_OUT_DIR_DEFAULT, instructionSocket);
                        }
                        case TRAIN -> {
                            printOut.println("[SERVER] Clearing Train Log Folder...");
                            /* Clear Train Log */
                            for (File subFile : Objects.requireNonNull(TRAINING_LOG_DIR_DEFAULT.listFiles((f) ->
                                    !(f.getName().equals("diffusion") ||
                                            f.getName().equals("D_0.pth") ||
                                            f.getName().equals("G_0.pth"))))) {
                                if (subFile.isDirectory()) {
                                    scheduleRemoveDirectory(subFile, instructionSocket);
                                } else {
                                    // schedule a file deletion
                                    String[] command = {"cmd.exe", "/c"};
                                    EXECUTION_AGENT.executeLater(
                                            command,
                                            null,
                                            (process) -> {
                                                subFile.delete();
                                                System.out.println("[SERVER] File Removed: \"" + subFile.getPath() + "\"");
                                                printOut.println("[SERVER] File Removed: \"" + subFile.getName() + "\"");
                                            },
                                            printOut
                                    );
                                }
                            }
                            // End Instruction Execution
                            String[] command = {"cmd.exe", "/c"};
                            EXECUTION_AGENT.executeLater(
                                    command,
                                    null,
                                    (process) -> {
                                        try {
                                            printOut.println("[SERVER] Deletion Complete.");
                                            finalInstructionSocket.close();
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    },
                                    null
                            );
                            /* End Clear Train Log */
                        }
                        default -> {
                            instructionSocket.close();
                            throw new IllegalArgumentException("dirToClear is Invalid");
                        }
                    }
                }
                case SLICE -> {
                    printOut.println("[SERVER] Slicing Audio(s)...");
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
                                        System.out.println("[SERVER] Audio Sliced: \"" + voiceFile + "\"");
                                        printOut.println("[SERVER] Slicing completed: \"" + voiceFile.getName() + "\"");
                                    } else {
                                        String errorMessage = buildTerminationErrorMessage(process, SLICER_PY);
                                        System.err.println(errorMessage);
                                        printOut.println("[ERROR] Failed to Slice: \"" + voiceFile.getName() + "\"");
                                    }
                                    // delete tmp voice file
                                    voiceFile.delete();

                                    if (finalI == 0) {
                                        printOut.println("[SERVER] All Slicing Done.");

                                        // discard this Socket
                                        try {
                                            finalInstructionSocket.close();
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    }
                                },
                                printOut
                        );
                    }
                }
                case PREPROCESS -> {
                    // dataset_raw: nothing is prepared
                    if (Objects.requireNonNull(SLICING_OUT_DIR_DEFAULT.listFiles(File::isDirectory)).length == 0) {
                        printOut.println("[!] Please SLICE at least 1 VOICE file.");
                        throw new FileNotFoundException("No Folder (sliced) in: \"" + SLICING_OUT_DIR_DEFAULT + "\"");
                    }

                    printOut.println("[SERVER] Preprocessing Dataset...");

                    // params retrival
                    String encoder = instructionJSONObject.getString("encoder");
                    String f0Predictor = instructionJSONObject.getString("f0_predictor");
                    boolean loudnessEnbedding = instructionJSONObject.getBoolean("loudness_embedding");

                    // schedules
                    scheduleResampleAudio(instructionSocket);
                    scheduleSplitDatasetAndGenerateConfig(encoder, loudnessEnbedding, instructionSocket);
                    scheduleGenerateHubertAndF0(f0Predictor, instructionSocket);
                }
                case TRAIN -> {
                    // Restore train configJSONObject = instructionJSONObject (with additional "gpu_id" entry)
                    instructionJSONObject.remove("INSTRUCTION");
                    int gpuId = (int) instructionJSONObject.remove("gpu_id");

                    // Write config JSON to TRAINING_CONFIG
                    try (FileWriter configJsonWriter = new FileWriter(TRAINING_CONFIG)) {
                        configJsonWriter.write(instructionJSONObject.toString(JSON_STR_INDENT_FACTOR));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    // schedule
                    scheduleTraining(gpuId, instructionSocket);
                }
                case INFER -> {
                    // Get Inference Input Files
                    File[] vocalAudioFiles = FOLDER_TO_INFER.listFiles(new FileFilter() {
                        @Override
                        public boolean accept(File file) {
                            return !file.isHidden(); // except hidden Files
                        }
                    });

                    // Get JSON Objects
                    JSONObject configJsonObject = getConfigJsonObject();
                    JSONObject modelJsonObject = configJsonObject.getJSONObject("model");

                    // Construct command
                    List<String> command = new ArrayList<>();
                    command.add("cmd.exe");
                    command.add("/c");
                    command.add("set");
                    command.add("CUDA_VISIBLE_DEVICES=" + instructionJSONObject.getInt("gpu_id"));
                    command.add("&&");
                    command.add(PYTHON_EXE.getAbsolutePath());
                    command.add(INFERENCE_PY.getAbsolutePath());

                    command.add("--model_path");
                    File[] trainedModels = TRAINING_LOG_DIR_DEFAULT.listFiles((dir, name) ->
                            name.matches(REGEX_TRAINED_MODEL_NAME));
                    assert trainedModels != null;
                    // logs: no trained model
                    if (trainedModels.length == 0) {
                        printOut.println("[ERROR] Model not Trained.");
                        throw new FileNotFoundException("No Model Trained in: \"" + TRAINING_LOG_DIR_DEFAULT + "\"");
                    }
                    assert trainedModels.length == 1;
                    command.add(trainedModels[0].getAbsolutePath());

                    command.add("--config_path");
                    command.add(TRAINING_CONFIG_LOG.getAbsolutePath());

                    command.add("--wav_format");
                    command.add(AUDIO_FILE_OUT_FORMAT);

                    command.add("--trans");
                    command.add(String.valueOf(PITCH_SHIFT_INFER_DEFAULT));

                    command.add("--spk_list");
                    command.add(instructionJSONObject.getString("spk"));

                    command.add("--clean_names");
                    command.addAll(Arrays.stream(vocalAudioFiles).map(File::getName).toList());

                    command.add("--f0_predictor");
                    command.add(instructionJSONObject.getString("f0_predictor"));

                    if (instructionJSONObject.getBoolean("nsf_hifigan")) {
                        command.add("--enhance");
                    }

                    // whisper-ppg speech encoder need to set --clip to 25 and -lg to 1
                    if ("whisper-ppg".equals(modelJsonObject.getString("speech_encoder"))) {
                        command.add("--clip");
                        command.add(String.valueOf(25));
                        command.add("-lg");
                        command.add(String.valueOf(1));
                    } else {
                        command.add("--clip");
                        command.add(String.valueOf(CLIP_INFER_DEFAULT));
                    }

                    // Schedule inference task
                    EXECUTION_AGENT.executeLater(
                            command,
                            SO_VITS_SVC_DIR,
                            (process) -> {
                                if (process.exitValue() == 0) {
                                    System.out.println("[SERVER] Inference Complete.");
                                    printOut.println("[SERVER] Inference Complete.");
                                } else {
                                    System.err.println("[WARNING] \"" +
                                            INFERENCE_PY.getName() +
                                            "\" interrupted, exit code: " +
                                            process.exitValue()
                                    );
                                    printOut.println("[WARNING] \"" +
                                            INFERENCE_PY.getName() +
                                            "\" interrupted."
                                    );
                                }

                                try {
                                    finalInstructionSocket.close();
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }

                                // delete all to-infer temp Files
                                Arrays.stream(vocalAudioFiles).forEach(File::delete);
                            },
                            printOut
                    );
                }
                case ABORT -> {
                    EXECUTION_AGENT.cancelAllTasks();
                    instructionSocket.close(); // Close this Abort Socket
                }
                case GET_CONF -> {
                    // determine which config to get
                    String configToGet = instructionJSONObject.getString("config");
                    switch (InstructionType.valueOf(configToGet.toUpperCase())) {
                        case TRAIN -> {
                            String configJSONString = getConfigJsonObject().toString();
                            printOut.println(configJSONString);

                            System.out.println("[SERVER] Sent Train Config to: [" +
                                    instructionSocket.getInetAddress() + "]:" + instructionSocket.getPort());
                        }
                        default -> {
                            instructionSocket.close();
                            throw new IllegalArgumentException("configToGet is Invalid");
                        }
                    }
                    // socket normal closure
                    instructionSocket.close();
                }
                case GET_RESULTS -> {
                    // Get Result Files
                    File[] resultFiles = RESULTS_DIR.listFiles(new FileFilter() {
                        @Override
                        public boolean accept(File file) {
                            return !file.isHidden(); // except hidden Files
                        }
                    });

                    // Send each File
                    Arrays.stream(resultFiles).forEach(file -> {
                        FileInputStream fileInStream = null;
                        synchronized (outputStream) {
                            try {
                                fileInStream = new FileInputStream(file);

                                outputStream.writeUTF(file.getName());
                                outputStream.writeInt((int) file.length());
                                outputStream.write(fileInStream.readAllBytes());
                            } catch (IOException e) {
                                System.err.println("[ERROR] Failed to Send File: \"" + file + "\" -> [" +
                                        finalInstructionSocket.getInetAddress() + "]:" + finalInstructionSocket.getPort());
                                throw new RuntimeException(e); // Abort Sending
                            } finally {
                                try {
                                    fileInStream.close();
                                } catch (IOException e) {} // Not to override the original Exception
                            }

                            System.out.println("[SERVER] Sent File: \"" + file + "\" -> [" +
                                    finalInstructionSocket.getInetAddress() + "]:" + finalInstructionSocket.getPort());
                        }

                        // delete the result File which was just sent to Client (secured Delete after Sent)
                        file.delete();
                    });

                    instructionSocket.close();
                }
                default -> {
                    throw new IllegalArgumentException("INSTRUCTION: \"" + instructionType + "\" is not supported");
                }
            }
            // execute ASAP
            EXECUTION_AGENT.invokeExecution();

        } catch (IOException e) {
            // try to close Socket on return
            if (instructionSocket != null) {
                try {
                    instructionSocket.close();
                } catch (IOException ex) {
                    return;
                }
            }
            return;
        }
    }

    /**
     * Schedule: the removal of a directory.
     *
     * @param directory directory to be removed.
     * @param instructionSocket the Instruction Socket which this Schedule associated with.
     * @dependency Windows OS
     */
    private static void scheduleRemoveDirectory(File directory, Socket instructionSocket) throws IOException {
        PrintStream printOut = new PrintStream(instructionSocket.getOutputStream());

        if (directory.isDirectory()) {
            String[] command = {"cmd.exe", "/c", "rmdir", "/s", "/q", directory.getAbsolutePath()};

            // schedule removal
            EXECUTION_AGENT.executeLater(
                    command,
                    null,
                    (process) -> {
                        if (process.exitValue() == 0) {
                            System.out.println("[SERVER] Directory Removed: \"" + directory.getPath() + "\"");
                            printOut.println("[SERVER] Directory Removed: \"" + directory.getName() + "\"");
                        } else {
                            System.err.println("[ERROR] \"" +
                                    command[0] +
                                    "\" terminated unexpectedly, exit code: " +
                                    process.exitValue()
                            );
                            printOut.println("[ERROR] Failed to Remove Directory: \"" + directory.getName() + "\"");
                        }
                    },
                    printOut
            );
        }
    }

    /**
     * Schedule: the removal of all Sub Directories of the given directory.
     *
     * @param directory the parent directory of directories to be removed.
     * @param instructionSocket the Instruction Socket which this Schedule associated with.
     * @feature Socket Terminal Operation:
     *          close instructionSocket on exit of the execution.
     * @dependency Windows OS
     */
    private static void scheduleRemoveSubDirectories(File directory, Socket instructionSocket) throws IOException {
        PrintStream printOut = new PrintStream(instructionSocket.getOutputStream());

        // schedule removals
        for (File subDir : Objects.requireNonNull(directory.listFiles(File::isDirectory))) {
            scheduleRemoveDirectory(subDir, instructionSocket);
        }

        // End Instruction Execution
        String[] command = {"cmd.exe", "/c"};
        EXECUTION_AGENT.executeLater(
                command,
                null,
                (process) -> {
                    // discard this Socket
                    try {
                        printOut.println("[SERVER] Deletion Complete.");
                        instructionSocket.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                null
        );
    }

    /**
     * Schedule: Resample audios @src -> @dest, to 44100Hz mono.
     *
     * @param instructionSocket the Instruction Socket which this Schedule associated with.
     * @src .\dataset_raw
     * @dest .\dataset\44k
     */
    private static void scheduleResampleAudio(Socket instructionSocket) throws IOException {
        PrintStream printOut = new PrintStream(instructionSocket.getOutputStream(), true, CHARSET_DEFAULT);

        String[] command = {
                PYTHON_EXE.getAbsolutePath(),
                RESAMPLER_PY.getAbsolutePath(),
        };

        EXECUTION_AGENT.executeLater(
                command,
                SO_VITS_SVC_DIR,
                (process) -> {
                    if (process.exitValue() == 0) {
                        System.out.println("[SERVER] Audio Resampled -> 44100Hz mono.");
                        printOut.println("[SERVER] Resampled to 44100Hz mono.");
                    } else {
                        String errorMessage = buildTerminationErrorMessage(process, RESAMPLER_PY);
                        System.err.println(errorMessage);
                        printOut.println("[ERROR] Failed to Resample Audio.");
                    }
                },
                printOut
        );
    }

    /**
     * Schedule: Split the dataset into training and validation sets, and generate configuration files.
     * @param encoder the chosen speech encoder.
     * @param loudnessEmbedding if loudness embedding is enabled.
     * @param instructionSocket the Instruction Socket which this Schedule associated with.
     */
    private static void scheduleSplitDatasetAndGenerateConfig(
            String encoder,
            boolean loudnessEmbedding,
            Socket instructionSocket
    ) throws IOException {

        PrintStream printOut = new PrintStream(instructionSocket.getOutputStream(), true, CHARSET_DEFAULT);

        List<String> command = new ArrayList<>();
        command.add(PYTHON_EXE.getAbsolutePath());
        command.add(FLIST_CONFIGER_PY.getAbsolutePath());
        command.add("--speech_encoder");
        command.add(encoder);
        if (loudnessEmbedding) {
            command.add("--vol_aug");
        }

        EXECUTION_AGENT.executeLater(
                command,
                SO_VITS_SVC_DIR,
                (process) -> {
                    if (process.exitValue() == 0) {
                        System.out.println("[SERVER] Training Set, Validation Set, Configuration Files Created.");
                        printOut.println("[SERVER] Training Set, Validation Set, Configuration Files Created.");
                    } else {
                        String errorMessage = buildTerminationErrorMessage(process, FLIST_CONFIGER_PY);
                        System.err.println(errorMessage);
                        printOut.println("[ERROR] Failed to Split Dataset and Generate Config.");
                    }
                },
                printOut
        );
    }

    /**
     * Schedule: Generate hubert and f0.
     * @param f0Predictor the chosen f0Predictor.
     * @param instructionSocket the Instruction Socket which this Schedule associated with.
     * @feature Socket Terminal Operation:
     *          close instructionSocket on exit of the execution.
     */
    private static void scheduleGenerateHubertAndF0(String f0Predictor, Socket instructionSocket) throws IOException {
        PrintStream printOut = new PrintStream(instructionSocket.getOutputStream(), true, CHARSET_DEFAULT);

        String[] command = {
                PYTHON_EXE.getAbsolutePath(),
                HUBERT_F0_GENERATOR_PY.getAbsolutePath(),
                "--f0_predictor",
                f0Predictor
        };

        EXECUTION_AGENT.executeLater(
                command,
                SO_VITS_SVC_DIR,
                (process) -> {
                    if (process.exitValue() == 0) {
                        System.out.println("[SERVER] Hubert & F0 Predictor Generated.");
                        printOut.println("[SERVER] Hubert & F0 Predictor Generated.");
                    } else {
                        String errorMessage = buildTerminationErrorMessage(process, HUBERT_F0_GENERATOR_PY);
                        System.err.println(errorMessage);
                        printOut.println("[ERROR] Failed to Generate Hubert and F0.");
                    }

                    printOut.println("[SERVER] Preprocessing Done.");

                    try {
                        instructionSocket.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                printOut
        );
    }

    /**
     * Schedule: Training with config.json
     * @param gpuId the chosen GPU ID.
     * @param instructionSocket the Instruction Socket which this Schedule associated with.
     * @feature Socket Terminal Operation:
     *          close instructionSocket on exit of the execution.
     */
    private static void scheduleTraining(int gpuId, Socket instructionSocket) throws IOException {
        PrintStream printOut = new PrintStream(instructionSocket.getOutputStream(), true, CHARSET_DEFAULT);

        String[] command = {
                "cmd.exe",
                "/c",
                "set",
                "CUDA_VISIBLE_DEVICES=" + gpuId,
                "&&",
                PYTHON_EXE.getAbsolutePath(),
                TRAIN_PY.getAbsolutePath(),
                "-c",
                TRAINING_CONFIG.getAbsolutePath(),
                "-m",
                "44k"
        };

        EXECUTION_AGENT.executeLater(
                command,
                SO_VITS_SVC_DIR,
                (process) -> {
                    if (process.exitValue() == 0) {
                        printOut.println("[SERVER] Training Complete.");
                    } else {
                        String errorMessage = "[WARNING] \"" +
                                TRAIN_PY.getName() +
                                "\" interrupted, exit code: " +
                                process.exitValue();
                        System.err.println(errorMessage);
                        printOut.println("[WARNING] \"" +
                                TRAIN_PY.getName() +
                                "\" interrupted."
                        );
                    }

                    try {
                        instructionSocket.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                printOut
        );
    }

    /**
     * Get Training Config JSONObject.
     *
     * @return Config JSONObject from TRAINING_CONFIG_LOG if it exists, otherwise from TRAINING_CONFIG.
     */
    private static JSONObject getConfigJsonObject() {
        File loadSource = TRAINING_CONFIG_LOG.exists() ? TRAINING_CONFIG_LOG : TRAINING_CONFIG;

        // Load JSON String from loadSource
        try (BufferedReader in = Files.newBufferedReader(loadSource.toPath())) {
            // Parse JSON String to JSONObject
            return new JSONObject(in.lines().reduce("", String::concat));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Build general termination error message about process's unexpected termination.
     * @param process the Process which ran into a unexpected termination.
     * @param executable the executable File associated with that process.
     * @return termination error message
     */
    private static String buildTerminationErrorMessage(Process process, File executable) {
        String errorMessage = "[ERROR] \"" +
                executable.getName() +
                "\" terminated unexpectedly, exit code: " +
                process.exitValue();
        return errorMessage;
    }

}
