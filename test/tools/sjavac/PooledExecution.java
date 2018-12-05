/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug 8044131
 * @summary Makes sure sjavac poolsize option is honored.
 * @build Wrapper
 * @run main Wrapper PooledExecution
 */
import java.io.File;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.tools.sjavac.comp.PooledSjavac;
import com.sun.tools.sjavac.server.CompilationResult;
import com.sun.tools.sjavac.server.Sjavac;
import com.sun.tools.sjavac.server.SysInfo;


public class PooledExecution {

    public static void main(String[] args) throws InterruptedException {
        new PooledExecutionTest().runTest();
    }

    static class PooledExecutionTest {

        final int POOL_SIZE = 15;
        final int NUM_REQUESTS = 100;

        // Number of tasks that has not yet started
        CountDownLatch leftToStart = new CountDownLatch(NUM_REQUESTS);

        // Highest number of concurrently active request seen
        int highWaterMark = 0;

        public void runTest() throws InterruptedException {
            ConcurrencyLoggingService loggingService = new ConcurrencyLoggingService();
            final Sjavac service = new PooledSjavac(loggingService, POOL_SIZE);

            // Keep track of the number of finished tasks so we can make sure all
            // tasks finishes gracefully upon shutdown.
            Thread[] tasks = new Thread[NUM_REQUESTS];
            final AtomicInteger tasksFinished = new AtomicInteger(0);

            for (int i = 0; i < NUM_REQUESTS; i++) {
                tasks[i] = new Thread() {
                    public void run() {
                        service.compile("",
                                        "",
                                        new String[0],
                                        Collections.<File>emptyList(),
                                        Collections.<URI>emptySet(),
                                        Collections.<URI>emptySet());
                        tasksFinished.incrementAndGet();
                    }
                };
                tasks[i].start();
            }

            // Wait for all tasks to start (but not necessarily run to completion)
            leftToStart.await();

            // Shutdown before all tasks are completed
            System.out.println("Shutting down!");
            service.shutdown();

            // Wait for all tasks to complete
            for (Thread t : tasks)
                t.join();

            if (tasksFinished.get() != NUM_REQUESTS) {
                throw new AssertionError(tasksFinished.get() + " out of " +
                        NUM_REQUESTS + " finished. Broken shutdown?");
            }

            if (highWaterMark > POOL_SIZE) {
                throw new AssertionError("Pool size overused: " + highWaterMark +
                                         " used out of " + POOL_SIZE + " allowed.");
            }

            // Assuming more than POOL_SIZE requests can be processed within 1 sek:
            if (highWaterMark < POOL_SIZE) {
                throw new AssertionError("Pool size underused: " + highWaterMark +
                                         " used out of " + POOL_SIZE + " allowed.");
            }
        }


        private class ConcurrencyLoggingService implements Sjavac {

            // Keeps track of currently active requests
            AtomicInteger activeRequests = new AtomicInteger(0);

            @Override
            public CompilationResult compile(String protocolId,
                                             String invocationId,
                                             String[] args,
                                             List<File> explicitSources,
                                             Set<URI> sourcesToCompile,
                                             Set<URI> visibleSources) {
                leftToStart.countDown();
                int numActiveRequests = activeRequests.incrementAndGet();
                System.out.printf("Left to start: %2d / Currently active: %2d%n",
                                  leftToStart.getCount(),
                                  numActiveRequests);
                highWaterMark = Math.max(highWaterMark, numActiveRequests);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    throw new RuntimeException("Interrupted", ie);
                }
                activeRequests.decrementAndGet();
                System.out.println("Task completed");
                return null;
            }

            @Override
            public SysInfo getSysInfo() {
                return null;
            }

            @Override
            public void shutdown() {
            }

            @Override
            public String serverSettings() {
                return "";
            }
        }
    }
}
