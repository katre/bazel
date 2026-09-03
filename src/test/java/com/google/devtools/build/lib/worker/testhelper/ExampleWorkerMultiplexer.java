// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.worker.testhelper;

import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat;
import com.google.devtools.build.lib.worker.WorkRequestHandler.WorkerMessageProcessor;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * An example implementation of a multiplex worker process that is used for integration tests. By
 * default, it concatenates writes the options residue and outputs it on stdout. {@link
 * ExampleWorkerMultiplexerOptions} specifies ways the behaviour can be modofied.
 */
public class ExampleWorkerMultiplexer extends WorkerBase<ExampleWorkerMultiplexerOptions> {

  // Creating Executor Service with a thread pool of Size 3.
  static final int CONCURRENT_THREAD_NUMBER = 3;

  // A UUID that uniquely identifies this running worker process.
  static final UUID WORKER_UUID = UUID.randomUUID();
  public static final String FILE_INPUT_PREFIX = "FILE:";

  // A counter that increases with each work unit processed.
  static int workUnitCounter = 1;

  static int counterOutput = workUnitCounter;

  static Semaphore protectResponse = new Semaphore(1);

  // Keep state across multiple builds.
  static final LinkedHashMap<String, String> inputs = new LinkedHashMap<>();

  // Store expanded args for single-shot mode
  private List<String> singleShotArgs;

  @Override
  protected Class<ExampleWorkerMultiplexerOptions> getOptionsClass() {
    return ExampleWorkerMultiplexerOptions.class;
  }

  @Override
  protected boolean isPersistentMode(ExampleWorkerMultiplexerOptions options) {
    return options.getPersistentWorker();
  }

  @Override
  protected WorkerProtocolFormat getProtocolFormat(ExampleWorkerMultiplexerOptions options) {
    // ExampleWorkerMultiplexer only supports PROTO
    return WorkerProtocolFormat.PROTO;
  }

  @Override
  protected int runSingleShot(List<String> args) throws Exception {
    // Use stored expanded args which include options, not just the residue
    OptionsParser parser = parserHelper(singleShotArgs);
    processRequest(parser, WorkRequest.getDefaultInstance());
    return 0;
  }

  @Override
  protected int runWork(List<String> args, PrintWriter err) {
    // Not directly used since we override runPersistentWorker
    throw new UnsupportedOperationException("Use runPersistentWorker instead");
  }

  @Override
  public void run(String[] args) throws Exception {
    List<String> expandedArgs = expandParamfiles(Arrays.asList(args));

    if (ImmutableSet.copyOf(args).contains("--persistent_worker")) {
      // Persistent mode: store args for use in runPersistentWorker
      System.err.printf("Worker args: %s\n", String.join(" ", args));
      System.setProperty("worker.args", String.join(" ", args));
    } else {
      // Single-shot mode: store expanded args for use in runSingleShot
      singleShotArgs = expandedArgs;
    }
    super.run(args);
  }

  private ExampleWorkerMultiplexer() {}

  public static void main(String[] args) throws Exception {
    ExampleWorkerMultiplexer worker = new ExampleWorkerMultiplexer();
    worker.run(args);
  }

  @Override
  protected void runPersistentWorker(WorkerMessageProcessor messageProcessor) throws Exception {
    // Parse worker options from command line
    List<String> expandedArgs = expandParamfiles(Arrays.asList(System.getProperty("worker.args", "").split(" ")));
    OptionsParser workerParser = createOptionsParser(false);
    workerParser.parse(expandedArgs);
    ExampleWorkerMultiplexerOptions workerOptions =
        workerParser.getOptions(ExampleWorkerMultiplexerOptions.class);
    Preconditions.checkState(workerOptions.getPersistentWorker());

    PrintStream originalStdOut = System.out;
    PrintStream originalStdErr = System.err;

    ExecutorService executorService = Executors.newFixedThreadPool(CONCURRENT_THREAD_NUMBER);
    List<Future<?>> results = new ArrayList<>();

    while (true) {
      try {
        WorkRequest request = messageProcessor.readWorkRequest();
        if (request == null) {
          break;
        }
        int requestId = request.getRequestId();

        inputs.clear();
        for (Input input : request.getInputsList()) {
          inputs.put(input.getPath(), input.getDigest().toStringUtf8());
        }

        // If true, returns corrupt responses instead of correct protobufs.
        boolean poisoned = false;
        if (workerOptions.getPoisonAfter() > 0
            && workUnitCounter > workerOptions.getPoisonAfter()) {
          poisoned = true;
        }

        if (poisoned && workerOptions.getHardPoison()) {
          System.err.println("I'm a very poisoned worker and will just crash.");
          System.exit(1);
        } else {
          int exitCode = 0;
          try {
            OptionsParser parser = parserHelper(request.getArgumentsList());
            ExampleWorkerMultiplexerOptions options =
                parser.getOptions(ExampleWorkerMultiplexerOptions.class);
            if (options.getWriteCounter()) {
              counterOutput = workUnitCounter++;
            }
            results.add(
                executorService.submit(
                    createTask(
                        originalStdOut, originalStdErr, requestId, parser, poisoned, request)));
          } catch (Exception e) {
            e.printStackTrace();
            exitCode = 1;
            WorkResponse.newBuilder()
                .setRequestId(requestId)
                .setOutput(new ByteArrayOutputStream().toString())
                .setExitCode(exitCode)
                .build()
                .writeDelimitedTo(System.out);
          }
        }

        if (workerOptions.getExitAfter() > 0 && workUnitCounter > workerOptions.getExitAfter()) {
          System.in.close();
        }
      } finally {
        // Be a good worker process and consume less memory when idle.
        System.gc();
      }
    }

    for (Future<?> result : results) {
      result.get();
    }
  }

  private OptionsParser parserHelper(List<String> args) throws Exception {
    // Use WorkerBase's parseOptionsWithParamfiles to handle paramfile expansion and parsing
    return parseOptionsWithParamfiles(args, true);
  }

  private static Runnable createTask(
      PrintStream originalStdOut,
      PrintStream originalStdErr,
      int requestId,
      OptionsParser parser,
      boolean poisoned,
      WorkRequest request) {
    return () -> {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      int exitCode = 0;

      try {
        try (PrintStream ps = new PrintStream(baos)) {
          System.setOut(ps);
          System.setErr(ps);

          if (poisoned) {
            System.out.println("I'm a poisoned worker and this is not a protobuf.");
            System.out.println("Here's a fake stack trace for you:");
            System.out.println("    at com.example.Something(Something.java:83)");
            System.out.println("    at java.lang.Thread.run(Thread.java:745)");
            System.out.print("And now, 8k of random bytes: ");
            byte[] b = new byte[8192];
            new Random().nextBytes(b);
            System.out.write(b);
          } else {
            try {
              if (request.getVerbosity() > 0) {
                originalStdErr.println("VERBOSE: Pretending to do work.");
                originalStdErr.println("VERBOSE: Running in " + new File(".").getAbsolutePath());
                originalStdErr.println("VERBOSE: Args " + request.getArgumentsList());
              }
              processRequest(parser, request);
            } catch (Exception e) {
              e.printStackTrace();
              exitCode = 1;
            }
          }
        } finally {
          System.setOut(originalStdOut);
          System.setErr(originalStdErr);
        }

        if (poisoned) {
          baos.writeTo(System.out);
        } else {
          protectResponse.acquire();
          WorkResponse.newBuilder()
              .setRequestId(requestId)
              .setOutput(baos.toString())
              .setExitCode(exitCode)
              .build()
              .writeDelimitedTo(System.out);
          protectResponse.release();
        }
        System.out.flush();
      } catch (IOException | InterruptedException e) {
        throw new IllegalStateException(e);
      }
    };
  }

  private static void processRequest(OptionsParser parser, WorkRequest request) throws Exception {
    ExampleWorkerMultiplexerOptions options =
        parser.getOptions(ExampleWorkerMultiplexerOptions.class);

    List<String> outputs = new ArrayList<>();

    if (options.getDelay()) {
      Integer randomDelay = new Random().nextInt(200) + 100;
      TimeUnit.MILLISECONDS.sleep(randomDelay);
      outputs.add("DELAY " + randomDelay + " MILLISECONDS");
    }

    if (options.getWriteUUID()) {
      outputs.add("UUID " + WORKER_UUID.toString());
    }

    if (options.getWriteCounter()) {
      outputs.add("COUNTER " + counterOutput);
    }

    List<String> residue = parser.getResidue();
    List<String> paths =
        residue.stream().filter(s -> s.startsWith(FILE_INPUT_PREFIX)).collect(Collectors.toList());
    residue =
        residue.stream().filter(p -> !paths.contains(p)).collect(ImmutableList.toImmutableList());

    String residueStr = Joiner.on(' ').join(residue);
    if (options.getUppercase()) {
      residueStr = Ascii.toUpperCase(residueStr);
    }
    outputs.add(residueStr);
    String prefix = options.getIgnoreSandbox() ? "" : request.getSandboxDir();
    while (prefix.endsWith("/")) {
      prefix = prefix.substring(0, prefix.length() - 1);
    }
    for (String p : paths) {
      Path path = Paths.get(prefix, p.substring(FILE_INPUT_PREFIX.length()));
      List<String> lines = Files.readAllLines(path);
      String content = Joiner.on("\n").join(lines);
      if (options.getUppercase()) {
        content = Ascii.toUpperCase(content);
      }
      outputs.add(content);
    }

    if (options.getPrintInputs()) {
      for (Map.Entry<String, String> input : inputs.entrySet()) {
        outputs.add("INPUT " + input.getKey() + " " + input.getValue());
      }
    }

    if (options.getPrintEnv()) {
      for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
        outputs.add(entry.getKey() + "=" + entry.getValue());
      }
    }

    String outputStr = Joiner.on('\n').join(outputs);
    if (options.getOutputFile().isEmpty()) {
      System.out.println(outputStr);
    } else {
      String actualFile =
          prefix.isEmpty() ? options.getOutputFile() : prefix + "/" + options.getOutputFile();
      try (PrintStream outputFile = new PrintStream(actualFile)) {
        outputFile.println(outputStr);
      }
    }
  }
}
