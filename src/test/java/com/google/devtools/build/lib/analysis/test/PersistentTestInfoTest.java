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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.analysis.starlark.Args;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.StarlarkThreadContext;
import com.google.devtools.build.lib.starlarkbuildapi.CommandLineArgsApi;
import com.google.devtools.build.lib.supplier.InterruptibleSupplier;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link PersistentTestInfo}. */
@RunWith(JUnit4.class)
public final class PersistentTestInfoTest {

  private static Object getattr(Object x, String name) throws Exception {
    return Starlark.getattr(/*mu=*/ null, StarlarkSemantics.DEFAULT, x, name, null);
  }

  private static StarlarkThread createThreadWithContext() {
    Mutability mu = Mutability.create("test");
    StarlarkThread thread = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT);

    // Set up minimal StarlarkThreadContext with a RepositoryMapping
    InterruptibleSupplier<RepositoryMapping> repoMappingSupplier = () -> null;
    StarlarkThreadContext context =
        new StarlarkThreadContext(repoMappingSupplier) {};
    context.storeInThread(thread);

    return thread;
  }

  @Test
  public void testDefaultValues() throws Exception {
    PersistentTestInfo info =
        new PersistentTestInfo(
            false,
            "proto",
            "",
            CommandLines.builder().build(),
            CommandLines.builder().build(),
            null,
            null,
            null);

    assertThat(info.getMultiplex()).isFalse();
    assertThat(info.getRequiresWorkerProtocol()).isEqualTo("proto");
    assertThat(info.getWorkerKeyMnemonic()).isNull();
    assertThat(info.getWorkerArgs()).isEmpty();
    assertThat(info.getTestArgs()).isEmpty();
  }

  @Test
  public void testAllFieldsSpecified() throws Exception {
    PersistentTestInfo info =
        new PersistentTestInfo(
            true,
            "json",
            "MyTestWorker",
            CommandLines.builder().build(),
            CommandLines.builder().build(),
            null,
            null,
            null);

    assertThat(info.getMultiplex()).isTrue();
    assertThat(info.getRequiresWorkerProtocol()).isEqualTo("json");
    assertThat(info.getWorkerKeyMnemonic()).isEqualTo("MyTestWorker");
  }

  @Test
  public void testMultiplexTrue() throws Exception {
    PersistentTestInfo info = new PersistentTestInfo(
        true, "proto", "", CommandLines.builder().build(), CommandLines.builder().build(), null, null, null);

    assertThat(info.getMultiplex()).isTrue();
  }

  @Test
  public void testJsonProtocol() throws Exception {
    PersistentTestInfo info = new PersistentTestInfo(
        false, "json", "", CommandLines.builder().build(), CommandLines.builder().build(), null, null, null);

    assertThat(info.getRequiresWorkerProtocol()).isEqualTo("json");
  }

  @Test
  public void testProtoProtocol() throws Exception {
    PersistentTestInfo info = new PersistentTestInfo(
        false, "proto", "", CommandLines.builder().build(), CommandLines.builder().build(), null, null, null);

    assertThat(info.getRequiresWorkerProtocol()).isEqualTo("proto");
  }

  @Test
  public void testInvalidProtocol() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> new PersistentTestInfo(
                false,
                "invalid",
                "",
                CommandLines.builder().build(),
                CommandLines.builder().build(),
                null,
                null,
                null));

    assertThat(exception)
        .hasMessageThat()
        .contains("requires_worker_protocol must be either \"proto\" or \"json\"");
    assertThat(exception).hasMessageThat().contains("invalid");
  }

  @Test
  public void testNullMnemonic() throws Exception {
    PersistentTestInfo info = new PersistentTestInfo(
        false, "proto", null, CommandLines.builder().build(), CommandLines.builder().build(), null, null, null);

    assertThat(info.getWorkerKeyMnemonic()).isNull();
  }

  @Test
  public void testEmptyMnemonic() throws Exception {
    PersistentTestInfo info = new PersistentTestInfo(
        false, "proto", "", CommandLines.builder().build(), CommandLines.builder().build(), null, null, null);

    assertThat(info.getWorkerKeyMnemonic()).isNull();
  }

  @Test
  public void testNonEmptyMnemonic() throws Exception {
    PersistentTestInfo info =
        new PersistentTestInfo(
            false,
            "proto",
            "JUnitRunner",
            CommandLines.builder().build(),
            CommandLines.builder().build(),
            null,
            null,
            null);

    assertThat(info.getWorkerKeyMnemonic()).isEqualTo("JUnitRunner");
  }

  @Test
  public void testStarlarkFieldAccess() throws Exception {
    PersistentTestInfo info =
        new PersistentTestInfo(
            true,
            "json",
            "TestMnemonic",
            CommandLines.builder().build(),
            CommandLines.builder().build(),
            null,
            null,
            null);

    assertThat(getattr(info, "multiplex")).isEqualTo(true);
    assertThat(getattr(info, "requires_worker_protocol")).isEqualTo("json");
    assertThat(getattr(info, "worker_key_mnemonic")).isEqualTo("TestMnemonic");
  }

  @Test
  public void testProviderConstructorDefaultValues() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    PersistentTestInfo.PersistentTestInfoProvider provider = PersistentTestInfo.PROVIDER;
    PersistentTestInfo info =
        (PersistentTestInfo)
            provider.constructor(
                null, false, "proto", "", StarlarkList.empty(), StarlarkList.empty(), null, null, null, thread);

    assertThat(info.getMultiplex()).isFalse();
    assertThat(info.getRequiresWorkerProtocol()).isEqualTo("proto");
    assertThat(info.getWorkerKeyMnemonic()).isNull();
    assertThat(info.getTestArgs()).isEmpty();
  }

  @Test
  public void testProviderConstructorAllFields() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    PersistentTestInfo.PersistentTestInfoProvider provider = PersistentTestInfo.PROVIDER;
    PersistentTestInfo info =
        (PersistentTestInfo)
            provider.constructor(
                null, true, "json", "Worker", StarlarkList.empty(), StarlarkList.empty(), null, null, null, thread);

    assertThat(info.getMultiplex()).isTrue();
    assertThat(info.getRequiresWorkerProtocol()).isEqualTo("json");
    assertThat(info.getWorkerKeyMnemonic()).isEqualTo("Worker");
  }

  @Test
  public void testProviderConstructorInvalidProtocol() {
    StarlarkThread thread = createThreadWithContext();
    PersistentTestInfo.PersistentTestInfoProvider provider = PersistentTestInfo.PROVIDER;

    EvalException exception =
        assertThrows(
            EvalException.class,
            () -> provider.constructor(
                null, false, "xml", "", StarlarkList.empty(), StarlarkList.empty(), null, null, null, thread));

    assertThat(exception)
        .hasMessageThat()
        .contains("requires_worker_protocol must be either \"proto\" or \"json\"");
    assertThat(exception).hasMessageThat().contains("xml");
  }

  @Test
  public void testProviderType() {
    PersistentTestInfo info = new PersistentTestInfo(
        false, "proto", "", CommandLines.builder().build(), CommandLines.builder().build(), null, null, null);

    assertThat(info.getProvider()).isSameInstanceAs(PersistentTestInfo.PROVIDER);
  }

  @Test
  public void testEmptyArguments() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    Sequence<?> args = StarlarkList.empty();

    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(
                null, false, "proto", null, StarlarkList.empty(), args, null, null, null, thread);

    assertThat(info.getTestArgs()).isEmpty();
  }

  @Test
  public void testArgumentsWithStringsOnly() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    Sequence<?> args = StarlarkList.immutableOf("--test-flag", "value", "--another-flag");

    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(
                null, false, "proto", null, StarlarkList.empty(), args, null, null, null, thread);

    assertThat(info.getTestArgs()).hasSize(1); // One CommandLine containing all strings
    Sequence<CommandLineArgsApi> argsResult = info.getTestArgs();
    assertThat(argsResult).isNotEmpty();
  }

  @Test
  public void testArgumentsWithArgsObject() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    // Create an empty Args object (don't add to it, as addAll requires many parameters)
    Args argsObj = Args.newArgs(Mutability.create("test"), StarlarkSemantics.DEFAULT);
    Sequence<?> args = StarlarkList.immutableOf(argsObj);

    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(
                null, false, "proto", null, StarlarkList.empty(), args, null, null, null, thread);

    assertThat(info.getTestArgs()).hasSize(1); // One Args object
  }

  @Test
  public void testArgumentsMixedStringsAndArgs() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    // Create an empty Args object
    Args argsObj = Args.newArgs(Mutability.create("test"), StarlarkSemantics.DEFAULT);
    Sequence<?> args = StarlarkList.immutableOf("string1", argsObj, "string2");

    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(
                null, false, "proto", null, StarlarkList.empty(), args, null, null, null, thread);

    // Should have 3 command lines: [string1], [empty args], [string2]
    assertThat(info.getTestArgs()).hasSize(3);
  }

  @Test
  public void testArgumentsInvalidType() {
    StarlarkThread thread = createThreadWithContext();
    // Create a tuple with an integer (tuples allow any object)
    Sequence<?> args = Tuple.of(123); // Invalid: integer

    EvalException ex = assertThrows(
        EvalException.class,
        () -> PersistentTestInfo.PROVIDER.constructor(
                null, false, "proto", null, StarlarkList.empty(), args, null, null, null, thread));
    assertThat(ex).hasMessageThat().contains("test_args must contain only strings or Args objects");
  }

  @Test
  public void testArgumentsFieldAccess() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    Sequence<?> args = StarlarkList.immutableOf("--flag", "value");

    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(
                null, false, "proto", null, StarlarkList.empty(), args, null, null, null, thread);

    Object argsAttr = getattr(info, "test_args");
    assertThat(argsAttr).isInstanceOf(Sequence.class);
  }

  @Test
  public void testArgumentsReturnsArgsObjects() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    Sequence<?> args = StarlarkList.immutableOf("--test");

    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(
                null, false, "proto", null, StarlarkList.empty(), args, null, null, null, thread);

    Sequence<CommandLineArgsApi> argsResult = info.getTestArgs();
    for (CommandLineArgsApi arg : argsResult) {
      assertThat(arg).isInstanceOf(CommandLineArgsApi.class);
    }
  }

  @Test
  public void testWorkerArgsWithStrings() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    Sequence<?> workerArgs = StarlarkList.immutableOf("--worker_protocol=json", "--verbose");

    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(
                null, false, "proto", null, workerArgs, StarlarkList.empty(), null, null, null, thread);

    assertThat(info.getWorkerArgs()).hasSize(1); // One CommandLine containing all strings
    Sequence<CommandLineArgsApi> argsResult = info.getWorkerArgs();
    assertThat(argsResult).isNotEmpty();
  }

  @Test
  public void testWorkerArgsAndTestArgs() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    Sequence<?> workerArgs = StarlarkList.immutableOf("--worker_protocol=json");
    Sequence<?> testArgs = StarlarkList.immutableOf("--test-flag", "value");

    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(
                null, false, "proto", null, workerArgs, testArgs, null, null, null, thread);

    assertThat(info.getWorkerArgs()).hasSize(1);
    assertThat(info.getTestArgs()).hasSize(1);
  }

  @Test
  public void testWorkerArgsFieldAccess() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    Sequence<?> workerArgs = StarlarkList.immutableOf("--flag");

    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(
                null, false, "proto", null, workerArgs, StarlarkList.empty(), null, null, null, thread);

    Object workerArgsAttr = getattr(info, "worker_args");
    assertThat(workerArgsAttr).isInstanceOf(Sequence.class);
  }

  @Test
  public void testWorkerArgsInvalidType() {
    StarlarkThread thread = createThreadWithContext();
    // Create a tuple with an integer (tuples allow any object)
    Sequence<?> workerArgs = Tuple.of(456); // Invalid: integer

    EvalException ex =
        assertThrows(
            EvalException.class,
            () ->
                PersistentTestInfo.PROVIDER.constructor(
                    null, false, "proto", null, workerArgs, StarlarkList.empty(), null, null, null, thread));
    assertThat(ex).hasMessageThat().contains("worker_args must contain only strings or Args objects");
  }

  @Test
  public void testWorkerToolsEmpty() throws Exception {
    PersistentTestInfo info =
        new PersistentTestInfo(
            false,
            "proto",
            "",
            CommandLines.builder().build(),
            CommandLines.builder().build(),
            null,
            null,
            null);

    assertThat(info.getWorkerTools()).isNull();
    assertThat(info.getWorkerToolsForStarlark()).isNull();
  }

  @Test
  public void testWorkerToolsFieldAccess() throws Exception {
    StarlarkThread thread = createThreadWithContext();

    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(
                null,
                false,
                "proto",
                null,
                StarlarkList.empty(),
                StarlarkList.empty(),
                null,
                null,
                null,
                thread);

    Object workerToolsAttr = getattr(info, "worker_tools");
    assertThat(workerToolsAttr).isEqualTo(Starlark.NONE);
  }

  @Test
  public void testWorkerToolsExplicitNone() throws Exception {
    StarlarkThread thread = createThreadWithContext();

    // Explicitly pass Starlark.NONE for worker_tools
    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(
                null,
                false,
                "proto",
                null,
                StarlarkList.empty(),
                StarlarkList.empty(),
                null,
                Starlark.NONE,
                null,
                thread);

    assertThat(info.getWorkerTools()).isNull();
    assertThat(info.getWorkerToolsForStarlark()).isNull();
    Object workerToolsAttr = getattr(info, "worker_tools");
    assertThat(workerToolsAttr).isEqualTo(Starlark.NONE);
  }

  @Test
  public void testWorkerToolsEmptyList() throws Exception {
    StarlarkThread thread = createThreadWithContext();

    // Pass an empty list for worker_tools
    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(
                null,
                false,
                "proto",
                null,
                StarlarkList.empty(),
                StarlarkList.empty(),
                null,
                StarlarkList.empty(),
                null,
                thread);

    // Empty list should create an empty NestedSet (not null)
    assertThat(info.getWorkerTools()).isNotNull();
    assertThat(info.getWorkerTools().isEmpty()).isTrue();
  }

  @Test
  public void testWorkerToolsInvalidType() {
    StarlarkThread thread = createThreadWithContext();
    Sequence<?> workerTools = Tuple.of(123); // Invalid: integer

    EvalException ex =
        assertThrows(
            EvalException.class,
            () ->
                PersistentTestInfo.PROVIDER.constructor(
                    null,
                    false,
                    "proto",
                    null,
                    StarlarkList.empty(),
                    StarlarkList.empty(),
                    null,
                    workerTools,
                    null,
                    thread));
    assertThat(ex).hasMessageThat().contains("worker_tools must contain Files or FilesToRunProvider");
  }
}
