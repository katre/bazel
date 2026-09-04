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

package com.google.devtools.build.lib.analysis.test;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.CommandLines.CommandLineAndParamFileInfo;
import com.google.devtools.build.lib.analysis.starlark.Args;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.StarlarkThreadContext;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.starlarkbuildapi.CommandLineArgsApi;
import com.google.devtools.build.lib.starlarkbuildapi.test.PersistentTestInfoApi;
import com.google.devtools.build.lib.supplier.InterruptibleSupplier;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;

/**
 * Provider that enables tests to run in persistent worker processes, avoiding startup overhead for
 * JVM tests and simulators.
 */
@Immutable
public final class PersistentTestInfo extends NativeInfo implements PersistentTestInfoApi {

  /** Singleton instance of the provider type for {@link PersistentTestInfo}. */
  public static final PersistentTestInfoProvider PROVIDER = new PersistentTestInfoProvider();

  private final boolean multiplex;
  private final String requiresWorkerProtocol;
  @Nullable private final String workerKeyMnemonic;
  private final CommandLines commandLines;

  /**
   * Constructs a new provider with the specified worker configuration.
   *
   * @param multiplex whether the worker can handle multiple concurrent test requests
   * @param requiresWorkerProtocol the protocol format ("proto" or "json")
   * @param workerKeyMnemonic optional mnemonic for flag-based filtering (null if not set)
   * @param commandLines command lines for test runner arguments
   */
  public PersistentTestInfo(
      boolean multiplex,
      String requiresWorkerProtocol,
      @Nullable String workerKeyMnemonic,
      CommandLines commandLines) {
    this.multiplex = multiplex;
    this.requiresWorkerProtocol = Preconditions.checkNotNull(requiresWorkerProtocol);
    if (!requiresWorkerProtocol.equals("proto") && !requiresWorkerProtocol.equals("json")) {
      throw new IllegalArgumentException(
          "requires_worker_protocol must be either \"proto\" or \"json\", got: "
              + requiresWorkerProtocol);
    }
    // Store null if empty string or null is passed
    this.workerKeyMnemonic =
        (workerKeyMnemonic == null || workerKeyMnemonic.isEmpty()) ? null : workerKeyMnemonic;
    this.commandLines = Preconditions.checkNotNull(commandLines);
  }

  @Override
  public PersistentTestInfoProvider getProvider() {
    return PROVIDER;
  }

  /** Returns whether the worker can handle multiple concurrent test requests. */
  @Override
  public Boolean getMultiplex() {
    return multiplex;
  }

  /** Returns the communication protocol format ("proto" or "json"). */
  @Override
  public String getRequiresWorkerProtocol() {
    return requiresWorkerProtocol;
  }

  /**
   * Returns the optional mnemonic for flag-based filtering. Null means the test is always eligible
   * for persistent worker mode.
   */
  @Override
  @Nullable
  public String getWorkerKeyMnemonic() {
    return workerKeyMnemonic;
  }

  /** Returns the arguments as a Starlark-visible sequence of Args objects. */
  @Override
  public Sequence<CommandLineArgsApi> getArguments() {
    ImmutableList.Builder<CommandLineArgsApi> result = ImmutableList.builder();
    // Empty set since test arguments typically don't involve directory artifacts
    ImmutableSet<Artifact> directoryInputs = ImmutableSet.of();

    for (CommandLineAndParamFileInfo cmdLine : commandLines.unpack()) {
      result.add(Args.forRegisteredAction(cmdLine, directoryInputs));
    }
    return StarlarkList.immutableCopyOf(result.build());
  }

  /** Returns the CommandLines for internal use during spawn creation. */
  public CommandLines getCommandLines() {
    return commandLines;
  }

  /** Provider implementation for {@link PersistentTestInfoApi}. */
  public static class PersistentTestInfoProvider extends BuiltinProvider<PersistentTestInfo>
      implements PersistentTestInfoApi.PersistentTestInfoApiProvider {

    private PersistentTestInfoProvider() {
      super("PersistentTestInfo", PersistentTestInfo.class);
    }

    @Override
    public PersistentTestInfoApi constructor(
        Boolean multiplex,
        String requiresWorkerProtocol,
        @Nullable String workerKeyMnemonic,
        Sequence<?> arguments,
        StarlarkThread thread)
        throws EvalException {
      // Validate protocol
      if (!requiresWorkerProtocol.equals("proto") && !requiresWorkerProtocol.equals("json")) {
        throw new EvalException(
            "requires_worker_protocol must be either \"proto\" or \"json\", got: "
                + requiresWorkerProtocol);
      }

      // Get RepositoryMapping from thread context
      StarlarkThreadContext threadContext = thread.getThreadLocal(StarlarkThreadContext.class);
      InterruptibleSupplier<RepositoryMapping> repoMappingSupplier;
      if (threadContext != null) {
        repoMappingSupplier = () -> threadContext.getMainRepoMapping();
      } else {
        // Fallback for contexts without thread context
        repoMappingSupplier = () -> null;
      }

      // Build CommandLines from the arguments sequence
      CommandLines.Builder builder = CommandLines.builder();
      ImmutableList.Builder<String> stringArgs = null;

      for (Object arg : arguments) {
        if (arg instanceof String) {
          if (stringArgs == null) {
            stringArgs = ImmutableList.builder();
          }
          stringArgs.add((String) arg);
        } else if (arg instanceof Args) {
          // Flush any accumulated strings first
          if (stringArgs != null) {
            builder.addCommandLine(CommandLine.of(stringArgs.build()));
            stringArgs = null;
          }
          // Add the Args object's command line
          Args argsObj = (Args) arg;
          try {
            builder.addCommandLine(argsObj.build(repoMappingSupplier), argsObj.getParamFileInfo());
          } catch (InterruptedException e) {
            throw new EvalException("Interrupted while building arguments", e);
          }
        } else {
          throw new EvalException(
              "arguments must contain only strings or Args objects, got: " + Starlark.type(arg));
        }
      }

      // Flush any remaining strings
      if (stringArgs != null) {
        builder.addCommandLine(CommandLine.of(stringArgs.build()));
      }

      return new PersistentTestInfo(
          multiplex, requiresWorkerProtocol, workerKeyMnemonic, builder.build());
    }
  }
}
