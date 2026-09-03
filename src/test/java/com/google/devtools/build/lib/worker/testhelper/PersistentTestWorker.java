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

import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsClass;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A test worker that compares two files and returns pass/fail exit code.
 *
 * <p>This worker can run in both single-shot and persistent modes, and is used to test the
 * persistent test runner infrastructure.
 */
public final class PersistentTestWorker extends WorkerBase<PersistentTestWorker.PersistentTestWorkerOptions> {

  // A UUID that uniquely identifies this running worker process.
  static final UUID WORKER_UUID = UUID.randomUUID();

  // A counter that increases with each work unit processed.
  int workUnitCounter = 1;

  /** Options for the PersistentTestWorker. */
  @OptionsClass
  public abstract static class PersistentTestWorkerOptions extends OptionsBase {
    @Option(
        name = "persistent_worker",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "Run in persistent worker mode")
    public abstract boolean getPersistentWorker();
  }

  public static void main(String[] args) throws Exception {
    new PersistentTestWorker().run(args);
  }

  @Override
  protected Class<PersistentTestWorkerOptions> getOptionsClass() {
    return PersistentTestWorkerOptions.class;
  }

  @Override
  protected int runSingleShot(List<String> args) throws Exception {
    return runTest(args, new PrintWriter(System.err, true));
  }

  @Override
  protected int runWork(List<String> args, PrintWriter err) {
    return runTest(args, err);
  }

  @Override
  protected boolean isPersistentMode(PersistentTestWorkerOptions options) {
    return options.getPersistentWorker();
  }

  @Override
  protected WorkerProtocolFormat getProtocolFormat(PersistentTestWorkerOptions options) {
    // PersistentTestWorker only supports PROTO format
    return WorkerProtocolFormat.PROTO;
  }

  /**
   * Runs the test comparison logic.
   *
   * @param args Expected to contain exactly 2 file paths to compare
   * @param err PrintWriter for error output
   * @return 0 if files match (test passes), 1 if files differ (test fails)
   */
  private int runTest(List<String> args, PrintWriter err) {
    // Expect exactly 2 positional arguments: path to file1 and file2
    if (args.size() != 2) {
      err.println("Error: Expected exactly 2 arguments (file paths), got " + args.size());
      return 1;
    }

    String file1Path = args.get(0);
    String file2Path = args.get(1);

    // Read both files and compare contents
    try {
      byte[] content1 = Files.readAllBytes(Paths.get(file1Path));
      byte[] content2 = Files.readAllBytes(Paths.get(file2Path));

      boolean match = Arrays.equals(content1, content2);

      // Print diagnostic output to the provided PrintWriter
      err.println("Worker UUID: " + WORKER_UUID);
      err.println("Work unit counter: " + workUnitCounter++);
      err.println(
          "Comparing " + file1Path + " and " + file2Path + ": " + (match ? "PASS" : "FAIL"));

      return match ? 0 : 1;
    } catch (IOException e) {
      err.println("Error reading files: " + e.getMessage());
      return 1;
    }
  }

  private PersistentTestWorker() {}
}
