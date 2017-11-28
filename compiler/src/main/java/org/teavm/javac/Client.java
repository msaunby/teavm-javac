/*
 *  Copyright 2017 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.teavm.javac;

import com.sun.tools.javac.api.JavacTool;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.teavm.ast.cache.InMemoryRegularMethodNodeCache;
import org.teavm.ast.cache.MethodNodeCache;
import org.teavm.backend.javascript.JavaScriptTarget;
import org.teavm.classlib.impl.JCLPlugin;
import org.teavm.diagnostics.Problem;
import org.teavm.diagnostics.ProblemSeverity;
import org.teavm.interop.Async;
import org.teavm.javac.protocol.CompilableObject;
import org.teavm.javac.protocol.CompileMessage;
import org.teavm.javac.protocol.CompilerDiagnosticMessage;
import org.teavm.javac.protocol.ErrorMessage;
import org.teavm.javac.protocol.LoadStdlibMessage;
import org.teavm.javac.protocol.WorkerMessage;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.ajax.XMLHttpRequest;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.events.MessageEvent;
import org.teavm.jso.impl.JSOPlugin;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.model.ClassHolderSource;
import org.teavm.model.InMemoryProgramCache;
import org.teavm.model.MethodReference;
import org.teavm.model.ProgramCache;
import org.teavm.model.ValueType;
import org.teavm.parsing.CompositeClassHolderSource;
import org.teavm.parsing.DirectoryClasspathClassHolderSource;
import org.teavm.platform.async.AsyncCallback;
import org.teavm.platform.plugin.PlatformPlugin;
import org.teavm.vm.TeaVM;
import org.teavm.vm.TeaVMBuilder;
import org.teavm.vm.TeaVMPhase;
import org.teavm.vm.TeaVMProgressFeedback;
import org.teavm.vm.TeaVMProgressListener;

public final class Client {
    private static boolean isBusy;

    private Client() {
    }

    public static void main(String[] args) {
        Window.current().addEventListener("message", (MessageEvent event) -> {
            WorkerMessage request = (WorkerMessage) event.getData();
            try {
                processMessage(request);
            } catch (Throwable e) {
                log("Error occurred");
                e.printStackTrace();
                Window.current().postMessage(createErrorMessage(request, "Error occurred processing message: "
                        + e.getMessage()));
            }
        });
    }

    private static void processMessage(WorkerMessage message) throws Exception {
        log("Message received: " + message.getId());

        if (isBusy) {
            log("Responded busy status");
            Window.current().postMessage(createErrorMessage(message, "Busy"));
            return;
        }

        isBusy = true;
        boolean errorOccurred = false;
        try {
            switch (message.getCommand()) {
                case "load-classlib":
                    init(((LoadStdlibMessage) message).getUrl());
                    break;
                case "compile":
                    createSourceFile(((CompileMessage) message).getText());
                    doCompile(message);
                    generateJavaScript(message);
                    break;
            }
            if (!errorOccurred) {
                respondOk(message);
            }
        } finally {
            log("Done processing message: " + message.getId());
            isBusy = false;
        }
    }

    private static void respondOk(WorkerMessage message) {
        WorkerMessage response = createMessage();
        response.setCommand("ok");
        response.setId(message.getId());
    }

    private static <T extends ErrorMessage> T createErrorMessage(WorkerMessage request, String text) {
        T message = createMessage();
        message.setId(request.getId());
        message.setCommand("error");
        message.setText(text);
        return message;
    }

    @JSBody(script = "return {};")
    private static native <T extends JSObject> T createMessage();

    private static void init(String url) throws IOException {
        log("Initializing");

        long start = System.currentTimeMillis();
        loadTeaVMClasslib(url);
        createStdlib();
        long end = System.currentTimeMillis();

        log("Initialized in " + (end - start) + " ms");
    }

    private static boolean doCompile(WorkerMessage request) throws IOException {
        JavaCompiler compiler = JavacTool.create();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(
                Arrays.asList(new File("/Hello.java")));
        OutputStreamWriter out = new OutputStreamWriter(System.out);

        new File("/out").mkdirs();
        JavaCompiler.CompilationTask task = compiler.getTask(out, fileManager, createDiagnosticListener(request),
                Arrays.asList("-verbose", "-d", "/out"), null, compilationUnits);
        boolean result = task.call();
        out.flush();

        return result;
    }

    private static DiagnosticListener<? super JavaFileObject> createDiagnosticListener(WorkerMessage request) {
        return diagnostic -> {
            CompilerDiagnosticMessage response = createMessage();
            response.setKind("compiler-diagnostic");
            response.setId(request.getId());

            CompilableObject object = createMessage();
            object.setKind(diagnostic.getSource().getKind().name());
            object.setName(diagnostic.getSource().getName());
            response.setObject(object);

            response.setStartPosition((int) diagnostic.getStartPosition());
            response.setPosition((int) diagnostic.getPosition());
            response.setEndPosition((int) diagnostic.getEndPosition());

            response.setLineNumber((int) diagnostic.getColumnNumber());
            response.setColumnNumber((int) diagnostic.getColumnNumber());

            response.setCode(diagnostic.getCode());
            response.setMessage(diagnostic.getMessage(Locale.getDefault()));

            Window.current().postMessage(response);
        };
    }

    private static long lastPhaseTime = System.currentTimeMillis();
    private static TeaVMPhase lastPhase;
    private static ClassHolderSource stdlibClassSource;
    private static ProgramCache programCache = new InMemoryProgramCache();
    private static MethodNodeCache astCache = new InMemoryRegularMethodNodeCache();

    static {
        Properties stdlibMapping = new Properties();
        stdlibMapping.setProperty("packagePrefix.java", "org.teavm.classlib");
        stdlibMapping.setProperty("classPrefix.java", "T");

        stdlibClassSource = new DirectoryClasspathClassHolderSource(new File("/teavm-stdlib"), stdlibMapping);
    }

    private static boolean generateJavaScript(WorkerMessage message) {
        try {
            long start = System.currentTimeMillis();

            List<ClassHolderSource> classSources = new ArrayList<>();
            classSources.add(stdlibClassSource);
            classSources.add(new DirectoryClasspathClassHolderSource(new File("/out")));

            JavaScriptTarget jsTarget = new JavaScriptTarget();
            jsTarget.setMinifying(false);
            TeaVM teavm = new TeaVMBuilder(jsTarget)
                    .setClassSource(new CompositeClassHolderSource(classSources))
                    .build();
            teavm.setIncremental(true);
            teavm.setProgramCache(programCache);
            jsTarget.setAstCache(astCache);

            long pluginInstallationStart = System.currentTimeMillis();
            new JSOPlugin().install(teavm);
            new PlatformPlugin().install(teavm);
            new JCLPlugin().install(teavm);
            log("Plugins loaded in " + (System.currentTimeMillis() - pluginInstallationStart) + " ms");

            teavm.entryPoint("main", new MethodReference("Hello", "main", ValueType.parse(String[].class),
                    ValueType.VOID))
                    .withArrayValue(1, "java.lang.String");
            File outDir = new File("/js-out");
            outDir.mkdirs();

            log("TeaVM initialized in " + (System.currentTimeMillis() - start) + " ms");

            lastPhase = null;
            lastPhaseTime = System.currentTimeMillis();
            teavm.setProgressListener(new TeaVMProgressListener() {
                @Override
                public TeaVMProgressFeedback phaseStarted(TeaVMPhase phase, int count) {
                    if (phase != lastPhase) {
                        long newPhaseTime = System.currentTimeMillis();
                        if (lastPhase != null) {
                            log(lastPhase.name() + ": " + (newPhaseTime - lastPhaseTime) + " ms");
                        }
                        lastPhaseTime = newPhaseTime;
                        lastPhase = phase;
                    }
                    return TeaVMProgressFeedback.CONTINUE;
                }

                @Override
                public TeaVMProgressFeedback progressReached(int progress) {
                    return null;
                }
            });

            if (lastPhase != null) {
                log(lastPhase.name() + ": " + (System.currentTimeMillis() - lastPhaseTime) + " ms");
            }

            teavm.build(outDir, "classes.js");
            boolean hasSevere = false;
            for (Problem problem : teavm.getProblemProvider().getProblems()) {
                if (problem.getSeverity() == ProblemSeverity.ERROR) {
                    hasSevere = true;
                }
            }
            TeaVMProblemRenderer.describeProblems(teavm);

            long end = System.currentTimeMillis();
            log("TeaVM complete in " + (end - start) + " ms");

            return !hasSevere;
        } catch (RuntimeException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static void loadTeaVMClasslib(String url) throws IOException {
        File baseDir = new File("/teavm-stdlib");
        baseDir.mkdirs();

        byte[] data = downloadFile(url);
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(data))) {
            unzip(input, baseDir);
        }
    }

    private static void createStdlib() throws IOException {
        System.setProperty("sun.boot.class.path", "/stdlib");

        File baseDir = new File("/stdlib");
        baseDir.mkdirs();
        traverseStdlib(new File("/teavm-stdlib"), baseDir, ".");
    }

    private static void traverseStdlib(File sourceDir, File destDir, String path) throws IOException {
        File sourceFile = new File(sourceDir, path);
        File[] files = sourceFile.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                traverseStdlib(sourceDir, destDir, path + "/" + file.getName());
            } else if (file.getName().endsWith(".class")) {
                transformClassFile(file, destDir);
            }
        }
    }

    private static void transformClassFile(File sourceFile, File destDir) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        StdlibConverter converter = new StdlibConverter(writer);
        try (InputStream input = new FileInputStream(sourceFile)) {
            ClassReader reader = new ClassReader(input);
            reader.accept(converter, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        }
        if (converter.visible) {
            File destFile = new File(destDir, converter.className + ".class");
            destFile.getParentFile().mkdirs();
            try (OutputStream output = new FileOutputStream(destFile)) {
                output.write(writer.toByteArray());
            }
        }
    }

    private static void unzip(ZipInputStream input, File toDir) throws IOException {
        while (true) {
            ZipEntry entry = input.getNextEntry();
            if (entry == null) {
                break;
            }
            if (entry.isDirectory()) {
                continue;
            }

            File outputFile = new File(toDir, entry.getName());
            outputFile.getParentFile().mkdirs();
            try (OutputStream output = new FileOutputStream(outputFile)) {
                copy(input, output);
            }
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[1024];
        while (true) {
            int bytesRead = input.read(buffer);
            if (bytesRead < 0) {
                break;
            }
            output.write(buffer, 0, bytesRead);
        }
    }

    @Async
    private static native byte[] downloadFile(String url);

    private static void downloadFile(String url, AsyncCallback<byte[]> callback) {
        XMLHttpRequest xhr = XMLHttpRequest.create();
        xhr.open("GET", url, true);
        xhr.setResponseType("arraybuffer");
        xhr.onComplete(() -> {
            ArrayBuffer buffer = (ArrayBuffer) xhr.getResponse();
            Int8Array jsArray = Int8Array.create(buffer);
            byte[] array = new byte[jsArray.getLength()];
            for (int i = 0; i < array.length; ++i) {
                array[i] = jsArray.get(i);
            }
            callback.complete(array);
        });
        xhr.send();
    }

    private static void createSourceFile(String content) throws IOException {
        File file = new File("/Hello.java");

        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))) {
            writer.write(content);
        }
    }

    private static void log(String message) {
        System.out.println(message);
    }
}
