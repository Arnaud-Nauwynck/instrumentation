/*
 * Copyright 2016-2019 the original author or authors.
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
package org.glowroot.instrumentation.grails;

import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.ning.http.client.AsyncHttpClient;
import grails.artefact.Artefact;
import grails.boot.config.GrailsAutoConfiguration;
import org.apache.catalina.Context;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.apache.naming.resources.VirtualDirContext;
import org.grails.boot.context.web.GrailsAppServletInitializer;
import org.grails.boot.internal.EnableAutoConfiguration;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.web.WebApplicationInitializer;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import org.glowroot.instrumentation.test.harness.AppUnderTest;
import org.glowroot.instrumentation.test.harness.Container;
import org.glowroot.instrumentation.test.harness.Containers;
import org.glowroot.instrumentation.test.harness.IncomingSpan;
import org.glowroot.instrumentation.test.harness.impl.JavaagentContainer;
import org.glowroot.instrumentation.test.harness.util.Ports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.glowroot.instrumentation.test.harness.util.HarnessAssertions.assertSingleLocalSpanMessage;

public class GrailsIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        String javaVersion = StandardSystemProperty.JAVA_VERSION.value();
        if (Containers.useJavaagent()
                && (javaVersion.startsWith("1.6") || javaVersion.startsWith("1.7"))) {
            // grails loads lots of classes
            container = JavaagentContainer
                    .createWithExtraJvmArgs(ImmutableList.of("-XX:MaxPermSize=128m"));
        } else {
            container = Containers.create();
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.resetAfterEachTest();
    }

    @Test
    public void shouldCaptureNonDefaultAction() throws Exception {
        // when
        IncomingSpan incomingSpan = container.executeForType(GetHelloAbc.class, "Web");

        // then
        assertThat(incomingSpan.transactionName()).isEqualTo("Hello#abc");

        assertSingleLocalSpanMessage(incomingSpan).isEqualTo(
                "grails controller: org.glowroot.instrumentation.grails.HelloController.abc()");
    }

    @Test
    public void shouldCaptureDefaultAction() throws Exception {
        // when
        IncomingSpan incomingSpan = container.executeForType(GetHello.class, "Web");

        // then
        assertThat(incomingSpan.transactionName()).isEqualTo("Hello#index");

        assertSingleLocalSpanMessage(incomingSpan).isEqualTo("grails controller:"
                + " org.glowroot.instrumentation.grails.HelloController.index()");
    }

    public static class ApplicationLoader extends GrailsAppServletInitializer
            implements WebApplicationInitializer {

        @Override
        protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
            application.sources(Application.class);
            return application;
        }
    }

    @Artefact("Application")
    @EnableWebMvc
    @EnableAutoConfiguration
    public static class Application extends GrailsAutoConfiguration {

        @Override
        public Collection<String> packageNames() {
            return Lists.newArrayList("org.glowroot.instrumentation.grails");
        }

        @Override
        @SuppressWarnings("rawtypes")
        public Collection<Class> classes() {
            Collection<Class> classes = super.classes();
            List<Class> classesInCorrectClassLoader = Lists.newArrayList();
            for (Class clazz : classes) {
                try {
                    classesInCorrectClassLoader.add(Class.forName(clazz.getName(), false,
                            Application.class.getClassLoader()));
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException(e);
                }
            }
            return classesInCorrectClassLoader;
        }
    }

    public static class GetHelloAbc extends RenderInTomcat {

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode =
                    asyncHttpClient.prepareGet("http://localhost:" + port + "/hello/abc/xyz")
                            .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class GetHello extends RenderInTomcat {

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient.prepareGet("http://localhost:" + port + "/hello")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public abstract static class RenderInTomcat implements AppUnderTest {

        @Override
        public void executeApp(Serializable... args) throws Exception {
            int port = Ports.getAvailable();
            Tomcat tomcat = new Tomcat();
            tomcat.setBaseDir("target/tomcat");
            tomcat.setPort(port);
            Context context =
                    tomcat.addWebapp("", new File("src/test/resources").getAbsolutePath());

            WebappLoader webappLoader = new WebappLoader(RenderInTomcat.class.getClassLoader());
            context.setLoader(webappLoader);

            // this is needed in order for Tomcat to find annotated classes
            VirtualDirContext resources = new VirtualDirContext();
            resources.setExtraResourcePaths("/WEB-INF/classes=target/test-classes");
            context.setResources(resources);

            tomcat.start();

            doTest(port);

            tomcat.stop();
            tomcat.destroy();
        }

        protected abstract void doTest(int port) throws Exception;
    }
}
