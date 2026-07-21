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
        new PersistentTestInfo(false, "proto", "", CommandLines.builder().build());

    assertThat(info.getMultiplex()).isFalse();
    assertThat(info.getRequiresWorkerProtocol()).isEqualTo("proto");
    assertThat(info.getWorkerKeyMnemonic()).isNull();
    assertThat(info.getArguments()).isEmpty();
  }

  @Test
  public void testAllFieldsSpecified() throws Exception {
    PersistentTestInfo info =
        new PersistentTestInfo(true, "json", "MyTestWorker", CommandLines.builder().build());

    assertThat(info.getMultiplex()).isTrue();
    assertThat(info.getRequiresWorkerProtocol()).isEqualTo("json");
    assertThat(info.getWorkerKeyMnemonic()).isEqualTo("MyTestWorker");
  }

  @Test
  public void testMultiplexTrue() throws Exception {
    PersistentTestInfo info = new PersistentTestInfo(true, "proto", "", CommandLines.builder().build());

    assertThat(info.getMultiplex()).isTrue();
  }

  @Test
  public void testJsonProtocol() throws Exception {
    PersistentTestInfo info = new PersistentTestInfo(false, "json", "", CommandLines.builder().build());

    assertThat(info.getRequiresWorkerProtocol()).isEqualTo("json");
  }

  @Test
  public void testProtoProtocol() throws Exception {
    PersistentTestInfo info = new PersistentTestInfo(false, "proto", "", CommandLines.builder().build());

    assertThat(info.getRequiresWorkerProtocol()).isEqualTo("proto");
  }

  @Test
  public void testInvalidProtocol() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> new PersistentTestInfo(false, "invalid", "", CommandLines.builder().build()));

    assertThat(exception)
        .hasMessageThat()
        .contains("requires_worker_protocol must be either \"proto\" or \"json\"");
    assertThat(exception).hasMessageThat().contains("invalid");
  }

  @Test
  public void testNullMnemonic() throws Exception {
    PersistentTestInfo info = new PersistentTestInfo(false, "proto", null, CommandLines.builder().build());

    assertThat(info.getWorkerKeyMnemonic()).isNull();
  }

  @Test
  public void testEmptyMnemonic() throws Exception {
    PersistentTestInfo info = new PersistentTestInfo(false, "proto", "", CommandLines.builder().build());

    assertThat(info.getWorkerKeyMnemonic()).isNull();
  }

  @Test
  public void testNonEmptyMnemonic() throws Exception {
    PersistentTestInfo info =
        new PersistentTestInfo(false, "proto", "JUnitRunner", CommandLines.builder().build());

    assertThat(info.getWorkerKeyMnemonic()).isEqualTo("JUnitRunner");
  }

  @Test
  public void testStarlarkFieldAccess() throws Exception {
    PersistentTestInfo info =
        new PersistentTestInfo(true, "json", "TestMnemonic", CommandLines.builder().build());

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
            provider.constructor(false, "proto", "", StarlarkList.empty(), thread);

    assertThat(info.getMultiplex()).isFalse();
    assertThat(info.getRequiresWorkerProtocol()).isEqualTo("proto");
    assertThat(info.getWorkerKeyMnemonic()).isNull();
    assertThat(info.getArguments()).isEmpty();
  }

  @Test
  public void testProviderConstructorAllFields() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    PersistentTestInfo.PersistentTestInfoProvider provider = PersistentTestInfo.PROVIDER;
    PersistentTestInfo info =
        (PersistentTestInfo)
            provider.constructor(true, "json", "Worker", StarlarkList.empty(), thread);

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
            () -> provider.constructor(false, "xml", "", StarlarkList.empty(), thread));

    assertThat(exception)
        .hasMessageThat()
        .contains("requires_worker_protocol must be either \"proto\" or \"json\"");
    assertThat(exception).hasMessageThat().contains("xml");
  }

  @Test
  public void testProviderType() {
    PersistentTestInfo info = new PersistentTestInfo(false, "proto", "", CommandLines.builder().build());

    assertThat(info.getProvider()).isSameInstanceAs(PersistentTestInfo.PROVIDER);
  }

  @Test
  public void testEmptyArguments() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    Sequence<?> args = StarlarkList.empty();

    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(false, "proto", null, args, thread);

    assertThat(info.getArguments()).isEmpty();
  }

  @Test
  public void testArgumentsWithStringsOnly() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    Sequence<?> args = StarlarkList.immutableOf("--test-flag", "value", "--another-flag");

    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(false, "proto", null, args, thread);

    assertThat(info.getArguments()).hasSize(1); // One CommandLine containing all strings
    Sequence<CommandLineArgsApi> argsResult = info.getArguments();
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
            PersistentTestInfo.PROVIDER.constructor(false, "proto", null, args, thread);

    assertThat(info.getArguments()).hasSize(1); // One Args object
  }

  @Test
  public void testArgumentsMixedStringsAndArgs() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    // Create an empty Args object
    Args argsObj = Args.newArgs(Mutability.create("test"), StarlarkSemantics.DEFAULT);
    Sequence<?> args = StarlarkList.immutableOf("string1", argsObj, "string2");

    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(false, "proto", null, args, thread);

    // Should have 3 command lines: [string1], [empty args], [string2]
    assertThat(info.getArguments()).hasSize(3);
  }

  @Test
  public void testArgumentsInvalidType() {
    StarlarkThread thread = createThreadWithContext();
    // Create a tuple with an integer (tuples allow any object)
    Sequence<?> args = Tuple.of(123); // Invalid: integer

    EvalException ex = assertThrows(
        EvalException.class,
        () -> PersistentTestInfo.PROVIDER.constructor(false, "proto", null, args, thread));
    assertThat(ex).hasMessageThat().contains("arguments must contain only strings or Args objects");
  }

  @Test
  public void testArgumentsFieldAccess() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    Sequence<?> args = StarlarkList.immutableOf("--flag", "value");

    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(false, "proto", null, args, thread);

    Object argsAttr = getattr(info, "arguments");
    assertThat(argsAttr).isInstanceOf(Sequence.class);
  }

  @Test
  public void testArgumentsReturnsArgsObjects() throws Exception {
    StarlarkThread thread = createThreadWithContext();
    Sequence<?> args = StarlarkList.immutableOf("--test");

    PersistentTestInfo info =
        (PersistentTestInfo)
            PersistentTestInfo.PROVIDER.constructor(false, "proto", null, args, thread);

    Sequence<CommandLineArgsApi> argsResult = info.getArguments();
    for (CommandLineArgsApi arg : argsResult) {
      assertThat(arg).isInstanceOf(CommandLineArgsApi.class);
    }
  }
}
