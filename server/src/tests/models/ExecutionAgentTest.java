package models;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

class ExecutionAgentTest {
    private ExecutionAgent executionAgent;

    @BeforeEach
    void setUp() {
        executionAgent = ExecutionAgent.getExecutionAgent();
    }

    @Test
    void getExecutionAgentSingletonTest() {
        ExecutionAgent executionAgent2 = ExecutionAgent.getExecutionAgent();
        ExecutionAgent executionAgent3 = ExecutionAgent.getExecutionAgent();

        Assertions.assertSame(executionAgent, executionAgent2);
        Assertions.assertSame(executionAgent2, executionAgent3);
    }

    @Test
    void executeLaterScheduleTest() {
        String[] command = {"help"};

        Assertions.assertTrue(executionAgent.executeLater(Arrays.stream(command).toList(), null, null, System.out));
        Assertions.assertTrue(executionAgent.executeLater(command, new File("."), null, System.out));
    }


    /**
     * This is a UNSTABLE test that may fail on extremely poor CPU performance allocated to this process
     */
    @Test
    void invokeExecutionTest() throws InterruptedException {
        String[] command = {"help"};
        AtomicBoolean valid1 = new AtomicBoolean(false);
        AtomicBoolean valid2 = new AtomicBoolean(false);

        executionAgent.executeLater(command, null, (p) -> valid1.set(true), System.out);
        executionAgent.executeLater(command, null, (p) -> valid2.set(true), System.out);

        // wait for no execution occur
        Thread.sleep(100);

        Assertions.assertFalse(valid1.get());
        Assertions.assertFalse(valid2.get());

        // invoke execution
        executionAgent.invokeExecution();

        // wait for execution done
        Thread.sleep(100);

        Assertions.assertTrue(valid1.get());
        Assertions.assertTrue(valid2.get());
    }

}