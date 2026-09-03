// Copyright 2026 The Bazel Authors. All rights reserved.
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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat;
import com.google.devtools.build.lib.worker.JsonWorkerMessageProcessor;
import com.google.devtools.build.lib.worker.ProtoWorkerMessageProcessor;
import com.google.devtools.build.lib.worker.WorkRequestHandler;
import com.google.devtools.build.lib.worker.WorkRequestHandler.WorkerMessageProcessor;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import com.google.gson.stream.JsonReader;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abstract base class for test workers that provides common functionality like paramfile expansion,
 * options parsing, and persistent worker protocol handling.
 *
 * <p>Subclasses need to implement methods for:
 *
 * <ul>
 *   <li>Providing the options class ({@link #getOptionsClass()})
 *   <li>Running single-shot work ({@link #runSingleShot(List)})
 *   <li>Running work units in persistent mode ({@link #runWork(List, PrintWriter)})
 *   <li>Detecting persistent mode from options ({@link #isPersistentMode(T)})
 *   <li>Getting the protocol format ({@link #getProtocolFormat(T)})
 * </ul>
 *
 * @param <T> The options class type for this worker
 */
public abstract class WorkerBase<T extends OptionsBase> {

  /** Pattern to match paramfile arguments: @filename or --flagfile=filename. */
  protected static final Pattern FLAG_FILE_PATTERN = Pattern.compile("(?:@|--?flagfile=)(.+)");

  /**
   * Returns the options class for this worker.
   *
   * @return The class object for the options
   */
  protected abstract Class<T> getOptionsClass();

  /**
   * Runs a single work unit in single-shot mode.
   *
   * @param args The arguments for the work unit
   * @return Exit code (0 for success, non-zero for failure)
   * @throws Exception if an error occurs during execution
   */
  protected abstract int runSingleShot(List<String> args) throws Exception;

  /**
   * Runs a single work unit in persistent worker mode.
   *
   * @param args The arguments for the work unit
   * @param err PrintWriter for error/diagnostic output
   * @return Exit code (0 for success, non-zero for failure)
   */
  protected abstract int runWork(List<String> args, PrintWriter err);

  /**
   * Determines if the worker should run in persistent mode based on the parsed options.
   *
   * @param options The parsed options
   * @return true if persistent mode should be used
   */
  protected abstract boolean isPersistentMode(T options);

  /**
   * Returns the protocol format to use for persistent worker communication.
   *
   * @param options The parsed options
   * @return The protocol format (PROTO or JSON)
   */
  protected abstract WorkerProtocolFormat getProtocolFormat(T options);

  /**
   * Expands paramfile arguments (@filename or --flagfile=filename) in the argument list.
   *
   * <p>Paramfiles are text files where each line contains one argument. This method replaces
   * paramfile references with the actual arguments from those files.
   *
   * @param args The original argument list
   * @return A new list with paramfiles expanded
   * @throws IOException if a paramfile cannot be read
   */
  protected List<String> expandParamfiles(List<String> args) throws IOException {
    ImmutableList.Builder<String> expandedArgs = ImmutableList.builder();
    for (String arg : args) {
      Matcher flagFileMatcher = FLAG_FILE_PATTERN.matcher(arg);
      if (flagFileMatcher.matches()) {
        // Read all lines from the paramfile and add them to the expanded args
        expandedArgs.addAll(Files.readAllLines(Paths.get(flagFileMatcher.group(1)), UTF_8));
      } else {
        expandedArgs.add(arg);
      }
    }
    return expandedArgs.build();
  }

  /**
   * Creates an options parser configured for this worker.
   *
   * @param allowResidue Whether to allow residue (non-option arguments)
   * @return A configured OptionsParser
   */
  protected OptionsParser createOptionsParser(boolean allowResidue) {
    return OptionsParser.builder()
        .optionsClasses(getOptionsClass())
        .allowResidue(allowResidue)
        .build();
  }

  /**
   * Parses options from arguments, expanding any paramfiles first.
   *
   * <p>This is a convenience method that combines paramfile expansion and options parsing. It's
   * useful for processing work request arguments that may contain paramfile references.
   *
   * @param args The argument list (may contain paramfile references)
   * @param allowResidue Whether to allow residue (non-option arguments)
   * @return A configured OptionsParser with parsed options
   * @throws Exception if paramfile expansion or option parsing fails
   */
  protected OptionsParser parseOptionsWithParamfiles(List<String> args, boolean allowResidue)
      throws Exception {
    List<String> expandedArgs = expandParamfiles(args);
    OptionsParser parser = createOptionsParser(allowResidue);
    parser.parse(expandedArgs);
    return parser;
  }

  /**
   * Creates a message processor for the specified protocol format.
   *
   * @param format The protocol format (PROTO or JSON)
   * @return A configured WorkerMessageProcessor
   * @throws IOException if the processor cannot be created
   */
  protected WorkerMessageProcessor createMessageProcessor(WorkerProtocolFormat format)
      throws IOException {
    switch (format) {
      case JSON:
        return new JsonWorkerMessageProcessor(
            new JsonReader(new BufferedReader(new InputStreamReader(System.in, UTF_8))),
            new BufferedWriter(new OutputStreamWriter(System.out, UTF_8)));
      case PROTO:
        return new ProtoWorkerMessageProcessor(System.in, System.out);
    }
    throw new IllegalArgumentException("Unknown protocol format: " + format);
  }

  /**
   * Runs the persistent worker loop, processing work requests until the stream is closed.
   *
   * @param messageProcessor The message processor for reading/writing work requests/responses
   * @throws Exception if an error occurs during request processing
   */
  protected void runPersistentWorker(WorkerMessageProcessor messageProcessor) throws Exception {
    WorkRequestHandler handler =
        new WorkRequestHandler(this::runWork, System.err, messageProcessor);
    handler.processRequests();
  }

  /**
   * Main entry point for the worker. Parses arguments, expands paramfiles, and routes to either
   * single-shot or persistent mode.
   *
   * @param args Command-line arguments
   * @throws Exception if an error occurs during execution
   */
  public void run(String[] args) throws Exception {
    // Expand paramfiles first
    List<String> expandedArgs = expandParamfiles(Arrays.asList(args));

    // Parse options
    OptionsParser parser = createOptionsParser(true);
    parser.parse(expandedArgs);
    T options = parser.getOptions(getOptionsClass());

    if (isPersistentMode(options)) {
      // Persistent worker mode
      WorkerProtocolFormat format = getProtocolFormat(options);
      WorkerMessageProcessor messageProcessor = createMessageProcessor(format);
      runPersistentWorker(messageProcessor);
    } else {
      // Single-shot mode
      int exitCode = runSingleShot(parser.getResidue());
      System.exit(exitCode);
    }
  }
}
