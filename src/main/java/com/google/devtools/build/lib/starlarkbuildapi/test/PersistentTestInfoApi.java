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

package com.google.devtools.build.lib.starlarkbuildapi.test;

import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.docgen.annot.StarlarkConstructor;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.starlarkbuildapi.CommandLineArgsApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkThread;

/**
 * Provider that enables tests to run in persistent worker processes, avoiding startup overhead for
 * JVM tests and simulators.
 */
@StarlarkBuiltin(
    name = "PersistentTestInfo",
    category = DocCategory.PROVIDER,
    doc =
        "A provider that can be returned from test rules to enable running tests in persistent"
            + " worker processes. This avoids startup overhead for JVM tests, iOS/Android"
            + " simulators, and other test environments with significant initialization costs.")
public interface PersistentTestInfoApi extends StructApi {

  @StarlarkMethod(
      name = "multiplex",
      doc =
          "If True, the test worker can handle multiple concurrent test requests in parallel. If"
              + " False, the worker processes test requests sequentially. Multiplexing requires"
              + " that the test runner is thread-safe and can handle concurrent test execution.",
      structField = true)
  Boolean getMultiplex();

  @StarlarkMethod(
      name = "requires_worker_protocol",
      doc =
          "The communication protocol that the test worker uses. Must be either \"proto\" (default)"
              + " for protocol buffer format, or \"json\" for JSON format. This determines how"
              + " WorkRequest/WorkResponse messages are serialized between Bazel and the worker"
              + " process.",
      structField = true)
  String getRequiresWorkerProtocol();

  @StarlarkMethod(
      name = "worker_key_mnemonic",
      doc =
          "Optional mnemonic used to identify this type of test worker. When specified, this"
              + " enables flag-based filtering to selectively enable persistent test workers for"
              + " specific test types. When empty or not specified, the test is always eligible to"
              + " run as a persistent worker (subject to other constraints).",
      structField = true,
      allowReturnNones = true)
  @Nullable
  String getWorkerKeyMnemonic();

  @StarlarkMethod(
      name = "arguments",
      doc =
          "A list of Args objects representing the command-line arguments for the test runner."
              + " These arguments are provided to the test runner for each test execution, either"
              + " directly on the command line (for single-use mode) or via the worker protocol"
              + " (for persistent mode). The constructor accepts strings or Args objects, which are"
              + " then combined and returned as a list of Args objects.",
      structField = true)
  Sequence<CommandLineArgsApi> getArguments();

  @StarlarkMethod(
      name = "worker_executable",
      doc =
          "The persistent worker executable. This is a FilesToRunProvider representing the worker"
              + " binary that will handle test requests. When specified, this executable is used in"
              + " the spawn's tools (affecting WorkerKey) while test inputs are passed separately."
              + " Returns None if not specified.",
      structField = true,
      allowReturnNones = true)
  @Nullable
  Object getWorkerExecutable();

  @StarlarkMethod(
      name = "test_inputs",
      doc =
          "Test-specific input files (test binary, test data, etc.) as a depset. These inputs are"
              + " passed to the worker via WorkRequest for each test execution. Returns None if not"
              + " specified.",
      structField = true,
      allowReturnNones = true)
  @Nullable
  Depset getTestInputsForStarlark();

  /** Provider for {@link PersistentTestInfoApi}. */
  @StarlarkBuiltin(name = "Provider", category = DocCategory.PROVIDER, documented = false, doc = "")
  interface PersistentTestInfoApiProvider extends ProviderApi {

    @StarlarkMethod(
        name = "PersistentTestInfo",
        doc = "",
        documented = false,
        parameters = {
          @Param(
              name = "multiplex",
              defaultValue = "False",
              named = true,
              positional = true,
              doc =
                  "If True, the test worker can handle multiple concurrent test requests in"
                      + " parallel. If False (default), the worker processes test requests"
                      + " sequentially."),
          @Param(
              name = "requires_worker_protocol",
              defaultValue = "\"proto\"",
              named = true,
              positional = true,
              doc =
                  "The communication protocol for the test worker. Must be either \"proto\""
                      + " (default) for protocol buffer format, or \"json\" for JSON format."),
          @Param(
              name = "worker_key_mnemonic",
              defaultValue = "\"\"",
              named = true,
              positional = true,
              doc =
                  "Optional mnemonic to identify this type of test worker for flag-based"
                      + " filtering. Empty string (default) means always eligible for persistent"
                      + " worker mode."),
          @Param(
              name = "arguments",
              defaultValue = "[]",
              named = true,
              positional = false,
              doc = "Arguments for the test runner. Can be strings or Args objects."),
          @Param(
              name = "worker_executable",
              defaultValue = "None",
              positional = false,
              named = true,
              doc =
                  "The persistent worker executable. Should be a FilesToRunProvider representing"
                      + " the worker binary that will handle test requests. If not specified, falls"
                      + " back to standard test execution tools."),
          @Param(
              name = "test_inputs",
              defaultValue = "None",
              positional = false,
              named = true,
              doc =
                  "Optional depset of test-specific input files (test binary, test data, etc.). If"
                      + " not specified, uses all action inputs.")
        },
        selfCall = true,
        useStarlarkThread = true)
    @StarlarkConstructor
    PersistentTestInfoApi constructor(
        Boolean multiplex,
        String requiresWorkerProtocol,
        @Nullable String workerKeyMnemonic,
        Sequence<?> arguments,
        @Nullable Object workerExecutable,
        @Nullable Depset testInputs,
        StarlarkThread thread)
        throws EvalException;
  }
}
