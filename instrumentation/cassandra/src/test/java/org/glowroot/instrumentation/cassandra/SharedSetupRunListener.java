/*
 * Copyright 2015-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.instrumentation.cassandra;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

import org.glowroot.instrumentation.test.harness.Container;
import org.glowroot.instrumentation.test.harness.Containers;

public class SharedSetupRunListener extends RunListener {

    private static volatile Container sharedContainer;

    private static volatile int cassandraPort;

    static Container getContainer() throws Exception {
        if (sharedContainer == null) {
            cassandraPort = CassandraWrapper.start();
            Container container = Containers.create();
            return container;
        }
        return sharedContainer;
    }

    static int getCassandraPort() {
        return cassandraPort;
    }

    static void close(Container container) throws Exception {
        if (sharedContainer == null) {
            container.close();
            CassandraWrapper.stop();
        }
    }

    @Override
    public void testRunStarted(Description description) throws Exception {
        cassandraPort = CassandraWrapper.start();
        sharedContainer = Containers.create();
    }

    @Override
    public void testRunFinished(Result result) throws Exception {
        sharedContainer.close();
        CassandraWrapper.stop();
    }
}
