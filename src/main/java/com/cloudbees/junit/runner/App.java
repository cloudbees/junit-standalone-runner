package com.cloudbees.junit.runner;

import junit.framework.Test;
import junit.framework.TestResult;
import org.apache.tools.ant.taskdefs.optional.junit.JUnitResultFormatter;
import org.apache.tools.ant.taskdefs.optional.junit.JUnitTest;
import org.apache.tools.ant.taskdefs.optional.junit.XMLJUnitResultFormatter;
import org.junit.internal.TextListener;
import org.junit.runner.Computer;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.asm3.AnnotationVisitor;
import org.kohsuke.asm3.ClassReader;
import org.kohsuke.asm3.Type;
import org.kohsuke.asm3.commons.EmptyVisitor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Main test driver.
 */
public class App 
{
    @Option(name="-report",metaVar="DIR",usage="Directory to produce test reports")
    File reportDir;

    @Argument(metaVar="JAR/DIR",required=true)
    List<File> directories = new ArrayList<File>();

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    public static int run(String... args) throws Exception {
        App app = new App();
        CmdLineParser p = new CmdLineParser(app);
        try {
            p.parseArgument(args);
            return app.run();
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            System.err.println("java -jar bigtop-driver.jar [opts] DIR/JAR...");
            p.printUsage(System.err);
            return -1;
        }
    }
    
    protected List<File> findJars() {
        List<File> jars = new ArrayList<File>();
        for (File dir : directories) {
            scan(dir,jars);
        }
        return jars;
    }
    
    private void scan(File dirOrJar, List<File> jars) {
        if (dirOrJar.isDirectory()) {
            for (File child : dirOrJar.listFiles())
                scan(child,jars);
        } else {
            if (dirOrJar.getName().endsWith(".jar") && dirOrJar.isFile())
                jars.add(dirOrJar);
        }
    }
    
    protected List<Class> scanTestClasses(List<File> jars) throws IOException, ClassNotFoundException {
        URL[] urls = new URL[jars.size()];
        for (int i=0; i<urls.length; i++)
            urls[i] = jars.get(i).toURI().toURL();
        URLClassLoader cl = new URLClassLoader(urls,new JUnitOnlyClassLoader(null,getClass().getClassLoader()));

        TestAnnotationFinder finder = new TestAnnotationFinder();
        List<Class> r = new ArrayList<Class>();
        for (File jar : jars) {
            JarFile j = new JarFile(jar);
            try {
                Enumeration<JarEntry> e = j.entries();
                while (e.hasMoreElements()) {
                    JarEntry item = e.nextElement();
                    if (item.getName().endsWith(".class")) {
                        ClassReader cr = new ClassReader(j.getInputStream(item));
                        if (finder.find(cr)) {
                            r.add(cl.loadClass(getClassNameOf(item)));
                        }
                    }
                }
            } finally {
                j.close();
            }
        }
        
        return r;
    }

    private String getClassNameOf(JarEntry item) {
        String s = item.getName();
        s = s.substring(0,s.length()-".class".length());
        return s.replace('/','.');
    }

    public int run() throws Exception {
        JUnitCore junit = new JUnitCore();
        junit.addListener(new TextListener(System.out));
        if (reportDir!=null) {
            reportDir.mkdirs();
            junit.addListener(new JUnitResultFormatterAsRunListener(new XMLJUnitResultFormatter()) {
                @Override
                public void testStarted(Description description) throws Exception {
                    formatter.setOutput(new FileOutputStream(new File(reportDir,"TEST-"+description.getDisplayName()+".xml")));
                    super.testStarted(description);
                }
            });
        }
        return junit.run(new Computer(), scanTestClasses(findJars()).toArray(new Class[0])).getFailureCount();
    }

    /**
     * Wraps {@link Description} into {@link Test} enough to fake {@link JUnitResultFormatter}.
     */
    public static class DescriptionAsTest implements Test {
        private final Description description;

        public DescriptionAsTest(Description description) {
            this.description = description;
        }

        public int countTestCases() {
            return 1;
        }

        public void run(TestResult result) {
            throw new UnsupportedOperationException();
        }

        /**
         * {@link JUnitResultFormatter} determines the test name by reflection.
         */
        public String getName() {
            return description.getDisplayName();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            DescriptionAsTest that = (DescriptionAsTest) o;

            if (!description.equals(that.description)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return description.hashCode();
        }
    }

    /**
     * Adopts {@link JUnitResultFormatter} into {@link RunListener},
     * and also captures stdout/stderr by intercepting the likes of {@link System#out}.
     *
     * Because Ant JUnit formatter uses one stderr/stdout per one test suite,
     * we capture each test case into a separate report file.
     */
    public static class JUnitResultFormatterAsRunListener extends RunListener {
        protected final JUnitResultFormatter formatter;
        private ByteArrayOutputStream stdout,stderr;
        private PrintStream oldStdout,oldStderr;
        private int problem;
        private long startTime;

        private JUnitResultFormatterAsRunListener(JUnitResultFormatter formatter) {
            this.formatter = formatter;
        }

        @Override
        public void testRunStarted(Description description) throws Exception {
        }

        @Override
        public void testRunFinished(Result result) throws Exception {
        }

        @Override
        public void testStarted(Description description) throws Exception {
            formatter.startTestSuite(new JUnitTest(description.getDisplayName()));
            formatter.startTest(new DescriptionAsTest(description));
            problem = 0;
            startTime = System.currentTimeMillis();

            this.oldStdout = System.out;
            this.oldStderr = System.err;
            System.setOut(new PrintStream(stdout = new ByteArrayOutputStream()));
            System.setErr(new PrintStream(stderr = new ByteArrayOutputStream()));
        }

        @Override
        public void testFinished(Description description) throws Exception {
            System.out.flush();
            System.err.flush();
            System.setOut(oldStdout);
            System.setErr(oldStderr);

            formatter.setSystemOutput(stdout.toString());
            formatter.setSystemError(stderr.toString());
            formatter.endTest(new DescriptionAsTest(description));

            JUnitTest suite = new JUnitTest(description.getDisplayName());
            suite.setCounts(1,problem,0);
            suite.setRunTime(System.currentTimeMillis()-startTime);
            formatter.endTestSuite(suite);
        }

        @Override
        public void testFailure(Failure failure) throws Exception {
            testAssumptionFailure(failure);
        }

        @Override
        public void testAssumptionFailure(Failure failure) {
            problem++;
            formatter.addError(new DescriptionAsTest(failure.getDescription()), failure.getException());
        }

        @Override
        public void testIgnored(Description description) throws Exception {
            super.testIgnored(description);
        }
    }

    /**
     * Finds Test annoatation.
     */
    private static class TestAnnotationFinder extends EmptyVisitor {
        String typeAnnotation = Type.getDescriptor(org.junit.Test.class);
        boolean found = false;

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            found |= typeAnnotation.equals(desc);
            return this;
        }
        
        boolean find(ClassReader cr) {
            found = false;
            cr.accept(this,0);
            return found;
        }
    }
}
