package models;

import server.Server;

import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static server.Server.CHARSET_DEFAULT;

/**
 * Execution Agent
 * @responsibility Execute sequence of commands in a new Thread.
 * @design SINGLETON
 */
public class ExecutionAgent {

    public static final File SO_VITS_SVC_DIR = new File(".\\so-vits-svc-4.1-Stable");

    public static final File PYTHON_EXE = new File(".\\workenv\\python.exe");
    public static final File SLICER_PY = new File(".\\audio-slicer-main\\slicer2.py");
    public static final File RESAMPLER_PY = new File(SO_VITS_SVC_DIR + "\\resample.py");
    public static final File FLIST_CONFIGER_PY = new File(SO_VITS_SVC_DIR + "\\preprocess_flist_config.py");
    public static final File HUBERT_F0_GENERATOR_PY = new File(SO_VITS_SVC_DIR + "\\preprocess_hubert_f0.py");
    public static final File TRAIN_PY = new File(SO_VITS_SVC_DIR + "\\train.py");
    public static final File INFERENCE_PY = new File(SO_VITS_SVC_DIR + "\\inference_main.py");

    private static ExecutionAgent executionAgent;

    private final Queue<Runnable> taskQueue;

    private Process currentProcess;
    private PrintStream printStream = System.out; // default

    private ExecutionAgent() {
        taskQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * Get the singleton ExecutionAgent instance.
     * @return the ExecutionAgent instance if present, otherwise create new.
     */
    public static ExecutionAgent getExecutionAgent() {
        return (executionAgent == null) ? (executionAgent = new ExecutionAgent()) : (executionAgent);
    }

    public Process getCurrentProcess() {
        return currentProcess;
    }

    public void setPrintStream(OutputStream outputStream) {
        printStream = new PrintStream(outputStream, true, CHARSET_DEFAULT);
    }

    /**
     * Schedule a task to execute the command and then its afterExecution.
     * If afterExecution == null, the same effect as afterExecution is ()->{}.
     * @param command NON-EMPTY command with its arguments. If EMPTY, task is not to be scheduled.
     * @param workDirectory the working directory of the task process. If NULL, use dir of the current Java process.
     * @param afterExecution to run AFTER the command execution.
     * @return true -> task scheduled, false otherwise.
     */
    public boolean executeLater(List<String> command, File workDirectory, Consumer<Process> afterExecution) {
        if (command.isEmpty()) {
            return false;
        }
        // exist-check is embedded in directory-check
        if (workDirectory != null && !workDirectory.isDirectory()) {
            workDirectory = null;
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command).directory(workDirectory).redirectErrorStream(true);

        // Schedule in Queue
        return taskQueue.offer(() -> {
            try {
                // Run the process
                currentProcess = processBuilder.start();
                // Schedule after-task execution
                currentProcess.onExit().thenAccept(afterExecution == null ? (process)->{} : afterExecution);
                // Redirect process output
                BufferedReader in = new BufferedReader(new InputStreamReader(currentProcess.getInputStream(),
                        Server.CHARSET_DEFAULT));
                String line;
                while ((line = in.readLine()) != null) {
                    printStream.println(line);
                    printStream.flush();
                }
                in.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Schedule a task to execute the command and then its afterExecution.
     * If afterExecution == null, the same effect as afterExecution is ()->{}.
     * @param command NON-EMPTY command with its arguments. If EMPTY, task is not to be scheduled.
     * @param workDirectory the working directory of the task process. If NULL, use dir of the current Java process.
     * @param afterExecution to run AFTER the command execution.
     * @return true -> task scheduled, false otherwise.
     */
    public boolean executeLater(String[] command, File workDirectory, Consumer<Process> afterExecution) {
        return executeLater(Arrays.stream(command).toList(), workDirectory, afterExecution);
    }

    /**
     * Execute all scheduled tasks orderly.
     * When this method is in RUNNABLE_STATE -> this ExecutionAgent is in EXECUTABLE_STATE.
     */
    private synchronized void execute() {
        while (!taskQueue.isEmpty()) {
            taskQueue.remove().run();
        }
    }

    /**
     * Turn this ExecutionAgent into EXECUTABLE_STATE.
     * EXECUTABLE_STATE: execute all scheduled tasks orderly ASAP.
     */
    public void invokeExecution() {
        new Thread(this::execute, "Execute-Agent").start();
    }

}
