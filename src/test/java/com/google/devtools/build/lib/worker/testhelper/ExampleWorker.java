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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat;
import com.google.devtools.build.lib.worker.WorkRequestHandler;
import com.google.devtools.build.lib.worker.WorkRequestHandler.WorkerMessageProcessor;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/** An example implementation of a worker process that is used for integration tests. */
public final class ExampleWorker extends WorkerBase<ExampleWorkerOptions> {

  // A UUID that uniquely identifies this running worker process.
  static final UUID WORKER_UUID = UUID.randomUUID();

  // A counter that increases with each work unit processed.
  int workUnitCounter = 1;

  // If true, returns corrupt responses instead of correct protobufs.
  boolean poisoned = false;

  final LinkedHashMap<String, String> inputs = new LinkedHashMap<>();

  // Contains the request currently being worked on.
  private WorkRequest currentRequest;

  // The options passed to this worker on a per-worker-lifetime basis.
  private ExampleWorkerOptions workerOptions;
  private WorkerMessageProcessor messageProcessor;
  // Store expanded args for single-shot mode
  private List<String> singleShotArgs;

  private class InterruptableWorkRequestHandler extends WorkRequestHandler {

    InterruptableWorkRequestHandler(
        BiFunction<List<String>, PrintWriter, Integer> callback,
        PrintStream stderr,
        WorkerMessageProcessor messageProcessor) {
      super(callback, stderr, messageProcessor);
    }

    @Override
    @SuppressWarnings("SystemExitOutsideMain")
    public void processRequests() throws IOException {
      ByteArrayOutputStream captured = new ByteArrayOutputStream();
      WorkerIO workerIO = new WorkerIO(System.in, System.out, System.err, captured, captured);

      while (true) {
        WorkRequest request = getMessageProcessor().readWorkRequest();
        if (request == null) {
          break;
        }

        currentRequest = request;
        inputs.clear();
        for (Input input : request.getInputsList()) {
          inputs.put(input.getPath(), input.getDigest().toStringUtf8());
        }
        if (poisoned && workerOptions.getHardPoison()) {
          throw new IllegalStateException("I'm a very poisoned worker and will just crash.");
        }
        if (request.getCancel()) {
          respondToCancelRequest(request);
        } else {
          startResponseThread(workerIO, request);
        }
        if (workerOptions.getExitAfter() > 0) {
          try {
            while (!activeRequests.isEmpty()) {
              Thread.sleep(1);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          if (workUnitCounter > workerOptions.getExitAfter()) {
            System.exit(0);
          }
        }
      }

      try {
        // Unwrap the system streams placing the original streams back
        workerIO.close();
      } catch (Exception e) {
        workerIO.getOriginalErrorStream().println(e.getMessage());
      }
    }
  }

  @Override
  protected Class<ExampleWorkerOptions> getOptionsClass() {
    return ExampleWorkerOptions.class;
  }

  @Override
  protected boolean isPersistentMode(ExampleWorkerOptions options) {
    return options.getPersistentWorker();
  }

  @Override
  protected WorkerProtocolFormat getProtocolFormat(ExampleWorkerOptions options) {
    return options.getWorkerProtocol();
  }

  @Override
  protected int runSingleShot(List<String> args) throws Exception {
    // Use stored expanded args which include options, not just the residue
    parseOptionsAndLog(singleShotArgs);
    return 0;
  }

  @Override
  protected int runWork(List<String> args, PrintWriter err) {
    return doWork(args, err);
  }

  @Override
  public void run(String[] args) throws Exception {
    List<String> expandedArgs = expandParamfiles(Arrays.asList(args));

    if (ImmutableSet.copyOf(args).contains("--persistent_worker")) {
      // Persistent mode: parse and store worker options
      System.err.printf("Worker args: %s\n", String.join(" ", args));
      OptionsParser parser = createOptionsParser(false);
      parser.parse(expandedArgs);
      workerOptions = parser.getOptions(ExampleWorkerOptions.class);
    } else {
      // Single-shot mode: store expanded args for use in runSingleShot
      singleShotArgs = expandedArgs;
    }
    super.run(args);
  }

  @Override
  protected void runPersistentWorker(WorkerMessageProcessor messageProcessor) throws Exception {
    // Use custom handler with all the special test behaviors
    WorkRequestHandler handler =
        new InterruptableWorkRequestHandler(this::doWork, System.err, messageProcessor);
    handler.processRequests();
  }

  public static void main(String[] args) throws Exception {
    ExampleWorker worker = new ExampleWorker();
    worker.run(args);
  }

  private int doWork(List<String> args, PrintWriter err) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    PrintStream originalStdOut = System.out;
    PrintStream originalStdErr = System.err;

    if (workerOptions.getWaitForCancel()) {
      try {
        WorkRequest workRequest = messageProcessor.readWorkRequest();
        if (workRequest.getRequestId() != currentRequest.getRequestId()) {
          System.err.format(
              "Got cancel request for %d while expecting cancel request for %d%n",
              workRequest.getRequestId(), currentRequest.getRequestId());
          return 1;
        }
        if (!workRequest.getCancel()) {
          System.err.format(
              "Got non-cancel request for %d while expecting cancel request%n",
              workRequest.getRequestId());
          return 1;
        }
      } catch (IOException e) {
        throw new RuntimeException("Exception while waiting for cancel request", e);
      }
    }
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
        try {
          System.out.write(b);
        } catch (IOException e) {
          e.printStackTrace();
          return 1;
        }
      } else {
        try {
          if (currentRequest.getVerbosity() > 0) {
            originalStdErr.println("VERBOSE: Pretending to do work.");
            originalStdErr.println("VERBOSE: Running in " + new File(".").getAbsolutePath());
          }
          parseOptionsAndLog(args);
        } catch (Exception e) {
          e.printStackTrace();
          return 1;
        }
      }
    } finally {
      System.setOut(originalStdOut);
      System.setErr(originalStdErr);
      currentRequest = null;
    }

    if (workerOptions.getExitDuring() > 0 && workUnitCounter > workerOptions.getExitDuring()) {
      System.exit(0);
    }

    if (poisoned) {
      try {
        baos.writeTo(System.out);
        System.out.flush();
        System.exit(1);
      } catch (IOException e) {
        e.printStackTrace();
        System.exit(1);
      }
    }
    if (workerOptions.getPoisonAfter() > 0 && workUnitCounter > workerOptions.getPoisonAfter()) {
      poisoned = true;
    }
    return 0;
  }

  private void parseOptionsAndLog(List<String> args) throws Exception {
    // Use WorkerBase's parseOptionsWithParamfiles to handle paramfile expansion and parsing
    OptionsParser parser = parseOptionsWithParamfiles(args, true);
    ExampleWorkerOptions options = parser.getOptions(ExampleWorkerOptions.class);

    List<String> outputs = new ArrayList<>();

    if (options.getWriteUUID()) {
      outputs.add("UUID " + WORKER_UUID);
    }

    if (options.getWriteCounter()) {
      outputs.add("COUNTER " + workUnitCounter++);
    }

    String residueStr = Joiner.on(' ').join(parser.getResidue());
    if (options.getUppercase()) {
      residueStr = Ascii.toUpperCase(residueStr);
    }
    outputs.add(residueStr);

    if (options.getPrintInputs()) {
      for (Map.Entry<String, String> input : inputs.entrySet()) {
        outputs.add("INPUT " + input.getKey() + " " + input.getValue());
      }
    }

    if (!options.getPrintDirListing().isEmpty()) {
      Path rootDir = Path.of(options.getPrintDirListing());
      try (Stream<Path> paths = Files.walk(rootDir, Integer.MAX_VALUE)) {
        for (Path path : paths.collect(toImmutableList())) {
          outputs.add(String.format("DIRENT %s %s", rootDir.relativize(path), getInode(path)));
        }
      }
    }

    if (options.getPrintRequests()) {
      outputs.add("REQUEST: " + currentRequest);
    }

    if (options.getPrintEnv()) {
      for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
        outputs.add(entry.getKey() + "=" + entry.getValue());
      }
    }

    if (options.getWorkTime() != null) {
      try {
        Thread.sleep(options.getWorkTime().toMillis());
      } catch (InterruptedException e) {
        System.err.printf(
            "Interrupted while pretending to work for %d millis%n",
            options.getWorkTime().toMillis());
      }
    }

    String outputStr = Joiner.on('\n').join(outputs);
    if (options.getOutputFile().isEmpty()) {
      System.out.println(outputStr);
    } else {
      try (PrintStream outputFile = new PrintStream(options.getOutputFile())) {
        outputFile.println(outputStr);
      }
    }
  }

  private static long getInode(Path path) throws IOException {
    return (long) Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS);
  }

  private ExampleWorker() {}
}
