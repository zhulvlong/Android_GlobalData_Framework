package com.oem.globaldata.client;

import static org.junit.Assert.assertEquals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.junit.Test;

public final class SerialExecutorTest {
    @Test public void callbackOrderSurvivesAnUnorderedDelegate() {
        Queue<Runnable> pending = new ArrayDeque<>();
        Executor unordered = pending::add;
        SerialExecutor callbacks = new SerialExecutor(unordered);
        List<Integer> delivered = new ArrayList<>();
        callbacks.execute(() -> delivered.add(1));
        callbacks.execute(() -> delivered.add(2));
        callbacks.execute(() -> delivered.add(3));
        assertEquals(1, pending.size());
        while (!pending.isEmpty()) pending.remove().run();
        assertEquals(Arrays.asList(1, 2, 3), delivered);
    }
}
