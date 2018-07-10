// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.ToolchainContextBuilder.NoMatchingPlatformException;
import com.google.devtools.build.lib.analysis.ToolchainContextBuilder.UnresolvedToolchainsException;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.platform.ToolchainTestCase;
import com.google.devtools.build.lib.skyframe.BuildConfigurationValue.Key;
import com.google.devtools.build.lib.skyframe.ConstraintValueLookupFunction.InvalidConstraintValueException;
import com.google.devtools.build.lib.skyframe.PlatformLookupFunction.InvalidPlatformException;
import com.google.devtools.build.lib.skyframe.ToolchainException;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ToolchainContextBuilder}. */
@RunWith(JUnit4.class)
public class ToolchainContextBuilderTest extends ToolchainTestCase {

  /**
   * An {@link AnalysisMock} that injects {@link CreateToolchainContextBuilderFunction} into the
   * Skyframe executor.
   */
  private static final class AnalysisMockWithCreateToolchainContextFunction
      extends AnalysisMock.Delegate {
    AnalysisMockWithCreateToolchainContextFunction() {
      super(AnalysisMock.get());
    }

    @Override
    public ImmutableMap<SkyFunctionName, SkyFunction> getSkyFunctions(
        BlazeDirectories directories) {
      return ImmutableMap.<SkyFunctionName, SkyFunction>builder()
          .putAll(super.getSkyFunctions(directories))
          .put(
              CREATE_TOOLCHAIN_CONTEXT_BUILDER_FUNCTION,
              new CreateToolchainContextBuilderFunction())
          .build();
    }
  }

  @Override
  protected AnalysisMock getAnalysisMock() {
    return new AnalysisMockWithCreateToolchainContextFunction();
  }

  @Test
  public void createToolchainContext() throws Exception {
    // This should select platform mac, toolchain extra_toolchain_mac, because platform
    // mac is listed first.
    addToolchain(
        "extra",
        "extra_toolchain_linux",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    addToolchain(
        "extra",
        "extra_toolchain_mac",
        ImmutableList.of("//constraints:mac"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_linux', '//extra:extra_toolchain_mac')",
        "register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    CreateToolchainContextBuilderKey key =
        CreateToolchainContextBuilderKey.create(
            "test", ImmutableSet.of(testToolchainType), targetConfigKey);

    EvaluationResult<CreateToolchainContextBuilderValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasNoError();
    ToolchainContextBuilder toolchainContextBuilder = result.get(key).toolchainContextBuilder();
    assertThat(toolchainContextBuilder).isNotNull();

    assertThat(toolchainContextBuilder.requiredToolchainTypes()).containsExactly(testToolchainType);
    assertThat(toolchainContextBuilder.resolvedToolchainLabels())
        .containsExactly(Label.parseAbsoluteUnchecked("//extra:extra_toolchain_mac_impl"));

    assertThat(toolchainContextBuilder.executionPlatform()).isNotNull();
    assertThat(toolchainContextBuilder.executionPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//platforms:mac"));

    assertThat(toolchainContextBuilder.targetPlatform()).isNotNull();
    assertThat(toolchainContextBuilder.targetPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//platforms:linux"));
  }

  @Test
  public void createToolchainContext_noToolchainType() throws Exception {
    scratch.file("host/BUILD", "platform(name = 'host')");
    rewriteWorkspace("register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--host_platform=//host:host", "--platforms=//platforms:linux");
    CreateToolchainContextBuilderKey key =
        CreateToolchainContextBuilderKey.create("test", ImmutableSet.of(), targetConfigKey);

    EvaluationResult<CreateToolchainContextBuilderValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasNoError();
    ToolchainContextBuilder toolchainContextBuilder = result.get(key).toolchainContextBuilder();
    assertThat(toolchainContextBuilder).isNotNull();

    assertThat(toolchainContextBuilder.requiredToolchainTypes()).isEmpty();

    // With no toolchains requested, should fall back to the host platform.
    assertThat(toolchainContextBuilder.executionPlatform()).isNotNull();
    assertThat(toolchainContextBuilder.executionPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//host:host"));

    assertThat(toolchainContextBuilder.targetPlatform()).isNotNull();
    assertThat(toolchainContextBuilder.targetPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//platforms:linux"));
  }

  @Test
  public void createToolchainContext_noToolchainType_hostNotAvailable() throws Exception {
    scratch.file("host/BUILD", "platform(name = 'host')");
    scratch.file(
        "sample/BUILD",
        "constraint_setting(name='demo')",
        "constraint_value(name = 'demo_a', constraint_setting=':demo')",
        "constraint_value(name = 'demo_b', constraint_setting=':demo')",
        "platform(name = 'sample_a',",
        "  constraint_values = [':demo_a'],",
        ")",
        "platform(name = 'sample_b',",
        "  constraint_values = [':demo_b'],",
        ")");
    rewriteWorkspace(
        "register_execution_platforms('//platforms:mac', '//platforms:linux',",
        "    '//sample:sample_a', '//sample:sample_b')");

    useConfiguration("--host_platform=//host:host", "--platforms=//platforms:linux");
    CreateToolchainContextBuilderKey key =
        CreateToolchainContextBuilderKey.create(
            "test",
            ImmutableSet.of(),
            ImmutableSet.of(Label.parseAbsoluteUnchecked("//sample:demo_b")),
            targetConfigKey);

    EvaluationResult<CreateToolchainContextBuilderValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasNoError();
    ToolchainContextBuilder toolchainContextBuilder = result.get(key).toolchainContextBuilder();
    assertThat(toolchainContextBuilder).isNotNull();

    assertThat(toolchainContextBuilder.requiredToolchainTypes()).isEmpty();

    // With no toolchains requested, should fall back to the host platform.
    assertThat(toolchainContextBuilder.executionPlatform()).isNotNull();
    assertThat(toolchainContextBuilder.executionPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//sample:sample_b"));

    assertThat(toolchainContextBuilder.targetPlatform()).isNotNull();
    assertThat(toolchainContextBuilder.targetPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//platforms:linux"));
  }

  @Test
  public void createToolchainContext_unavailableToolchainType_single() throws Exception {
    useConfiguration(
        "--host_platform=//platforms:linux",
        "--platforms=//platforms:mac");
    CreateToolchainContextBuilderKey key =
        CreateToolchainContextBuilderKey.create(
            "test",
            ImmutableSet.of(
                testToolchainType, Label.parseAbsoluteUnchecked("//fake/toolchain:type_1")),
            targetConfigKey);

    EvaluationResult<CreateToolchainContextBuilderValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(UnresolvedToolchainsException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("no matching toolchains found for types //fake/toolchain:type_1");
  }

  @Test
  public void createToolchainContext_unavailableToolchainType_multiple() throws Exception {
    useConfiguration(
        "--host_platform=//platforms:linux",
        "--platforms=//platforms:mac");
    CreateToolchainContextBuilderKey key =
        CreateToolchainContextBuilderKey.create(
            "test",
            ImmutableSet.of(
                testToolchainType,
                Label.parseAbsoluteUnchecked("//fake/toolchain:type_1"),
                Label.parseAbsoluteUnchecked("//fake/toolchain:type_2")),
            targetConfigKey);

    EvaluationResult<CreateToolchainContextBuilderValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(UnresolvedToolchainsException.class);
    // Only one of the missing types will be reported, so do not check the specific error message.
  }

  @Test
  public void createToolchainContext_invalidTargetPlatform() throws Exception {
    scratch.file("invalid/BUILD", "filegroup(name = 'not_a_platform')");
    useConfiguration("--platforms=//invalid:not_a_platform");
    CreateToolchainContextBuilderKey key =
        CreateToolchainContextBuilderKey.create(
            "test", ImmutableSet.of(testToolchainType), targetConfigKey);

    EvaluationResult<CreateToolchainContextBuilderValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidPlatformException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("//invalid:not_a_platform");
  }

  @Test
  public void createToolchainContext_invalidHostPlatform() throws Exception {
    scratch.file("invalid/BUILD", "filegroup(name = 'not_a_platform')");
    useConfiguration("--host_platform=//invalid:not_a_platform");
    CreateToolchainContextBuilderKey key =
        CreateToolchainContextBuilderKey.create(
            "test", ImmutableSet.of(testToolchainType), targetConfigKey);

    EvaluationResult<CreateToolchainContextBuilderValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidPlatformException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("//invalid:not_a_platform");
  }

  @Test
  public void createToolchainContext_invalidExecutionPlatform() throws Exception {
    scratch.file("invalid/BUILD", "filegroup(name = 'not_a_platform')");
    useConfiguration("--extra_execution_platforms=//invalid:not_a_platform");
    CreateToolchainContextBuilderKey key =
        CreateToolchainContextBuilderKey.create(
            "test", ImmutableSet.of(testToolchainType), targetConfigKey);

    EvaluationResult<CreateToolchainContextBuilderValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidPlatformException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("//invalid:not_a_platform");
  }

  @Test
  public void createToolchainContext_execConstraints() throws Exception {
    // This should select platform linux, toolchain extra_toolchain_linux, due to extra constraints,
    // even though platform mac is registered first.
    addToolchain(
        /* packageName= */ "extra",
        /* toolchainName= */ "extra_toolchain_linux",
        /* execConstraints= */ ImmutableList.of("//constraints:linux"),
        /* targetConstraints= */ ImmutableList.of("//constraints:linux"),
        /* data= */ "baz");
    addToolchain(
        /* packageName= */ "extra",
        /* toolchainName= */ "extra_toolchain_mac",
        /* execConstraints= */ ImmutableList.of("//constraints:mac"),
        /* targetConstraints= */ ImmutableList.of("//constraints:linux"),
        /* data= */ "baz");
    rewriteWorkspace(
        "register_toolchains('//extra:extra_toolchain_linux', '//extra:extra_toolchain_mac')",
        "register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    CreateToolchainContextBuilderKey key =
        CreateToolchainContextBuilderKey.create(
            "test",
            ImmutableSet.of(testToolchainType),
            ImmutableSet.of(Label.parseAbsoluteUnchecked("//constraints:linux")),
            targetConfigKey);

    EvaluationResult<CreateToolchainContextBuilderValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasNoError();
    ToolchainContextBuilder toolchainContextBuilder = result.get(key).toolchainContextBuilder();
    assertThat(toolchainContextBuilder).isNotNull();

    assertThat(toolchainContextBuilder.requiredToolchainTypes()).containsExactly(testToolchainType);
    assertThat(toolchainContextBuilder.resolvedToolchainLabels())
        .containsExactly(Label.parseAbsoluteUnchecked("//extra:extra_toolchain_linux_impl"));

    assertThat(toolchainContextBuilder.executionPlatform()).isNotNull();
    assertThat(toolchainContextBuilder.executionPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//platforms:linux"));

    assertThat(toolchainContextBuilder.targetPlatform()).isNotNull();
    assertThat(toolchainContextBuilder.targetPlatform().label())
        .isEqualTo(Label.parseAbsoluteUnchecked("//platforms:linux"));
  }

  @Test
  public void createToolchainContext_execConstraints_invalid() throws Exception {
    CreateToolchainContextBuilderKey key =
        CreateToolchainContextBuilderKey.create(
            "test",
            ImmutableSet.of(testToolchainType),
            ImmutableSet.of(Label.parseAbsoluteUnchecked("//platforms:linux")),
            targetConfigKey);

    EvaluationResult<CreateToolchainContextBuilderValue> result = createToolchainContext(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidConstraintValueException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("//platforms:linux");
  }

  @Test
  public void createToolchainContext_noMatchingPlatform() throws Exception {
    // Write toolchain A, and a toolchain implementing it.
    scratch.appendFile(
        "a/BUILD",
        "toolchain_type(name = 'toolchain_type_A')",
        "toolchain(",
        "    name = 'toolchain',",
        "    toolchain_type = ':toolchain_type_A',",
        "    exec_compatible_with = ['//constraints:mac'],",
        "    target_compatible_with = [],",
        "    toolchain = ':toolchain_impl')",
        "filegroup(name='toolchain_impl')");
    // Write toolchain B, and a toolchain implementing it.
    scratch.appendFile(
        "b/BUILD",
        "load('//toolchain:toolchain_def.bzl', 'test_toolchain')",
        "toolchain_type(name = 'toolchain_type_B')",
        "toolchain(",
        "    name = 'toolchain',",
        "    toolchain_type = ':toolchain_type_B',",
        "    exec_compatible_with = ['//constraints:linux'],",
        "    target_compatible_with = [],",
        "    toolchain = ':toolchain_impl')",
        "filegroup(name='toolchain_impl')");

    rewriteWorkspace(
        "register_toolchains('//a:toolchain', '//b:toolchain')",
        "register_execution_platforms('//platforms:mac', '//platforms:linux')");

    useConfiguration("--platforms=//platforms:linux");
    CreateToolchainContextBuilderKey key =
        CreateToolchainContextBuilderKey.create(
            "test",
            ImmutableSet.of(
                Label.parseAbsoluteUnchecked("//a:toolchain_type_A"),
                Label.parseAbsoluteUnchecked("//b:toolchain_type_B")),
            targetConfigKey);

    EvaluationResult<CreateToolchainContextBuilderValue> result = createToolchainContext(key);
    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(NoMatchingPlatformException.class);
  }

  // Calls ToolchainUtil.createToolchainContext.
  private static final SkyFunctionName CREATE_TOOLCHAIN_CONTEXT_BUILDER_FUNCTION =
      SkyFunctionName.create("CREATE_TOOLCHAIN_CONTEXT_BUILDER_FUNCTION");

  @AutoValue
  abstract static class CreateToolchainContextBuilderKey implements SkyKey {
    @Override
    public SkyFunctionName functionName() {
      return CREATE_TOOLCHAIN_CONTEXT_BUILDER_FUNCTION;
    }

    abstract String targetDescription();

    abstract ImmutableSet<Label> requiredToolchainTypes();

    abstract ImmutableSet<Label> execConstraintLabels();

    abstract Key configurationKey();

    public static CreateToolchainContextBuilderKey create(
        String targetDescription, Set<Label> requiredToolchains, Key configurationKey) {
      return create(
          targetDescription,
          requiredToolchains,
          /* execConstraintLabels= */ ImmutableSet.of(),
          configurationKey);
    }

    public static CreateToolchainContextBuilderKey create(
        String targetDescription,
        Set<Label> requiredToolchains,
        Set<Label> execConstraintLabels,
        Key configurationKey) {
      return new AutoValue_ToolchainContextBuilderTest_CreateToolchainContextBuilderKey(
          targetDescription,
          ImmutableSet.copyOf(requiredToolchains),
          ImmutableSet.copyOf(execConstraintLabels),
          configurationKey);
    }
  }

  private EvaluationResult<CreateToolchainContextBuilderValue> createToolchainContext(
      CreateToolchainContextBuilderKey key) throws InterruptedException {
    try {
      // Must re-enable analysis for Skyframe functions that create configured targets.
      skyframeExecutor.getSkyframeBuildView().enableAnalysis(true);
      return SkyframeExecutorTestUtils.evaluate(
          skyframeExecutor, key, /*keepGoing=*/ false, reporter);
    } finally {
      skyframeExecutor.getSkyframeBuildView().enableAnalysis(false);
    }
  }

  // TODO(blaze-team): implement equals and hashcode for ToolchainContextBuilder and convert this to
  // autovalue.
  static class CreateToolchainContextBuilderValue implements SkyValue {
    private final ToolchainContextBuilder toolchainContextBuilder;

    private CreateToolchainContextBuilderValue(ToolchainContextBuilder toolchainContextBuilder) {
      this.toolchainContextBuilder = toolchainContextBuilder;
    }

    static CreateToolchainContextBuilderValue create(
        ToolchainContextBuilder toolchainContextBuilder) {
      return new CreateToolchainContextBuilderValue(toolchainContextBuilder);
    }

    ToolchainContextBuilder toolchainContextBuilder() {
      return toolchainContextBuilder;
    }
  }

  private static final class CreateToolchainContextBuilderFunction implements SkyFunction {

    @Nullable
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env)
        throws SkyFunctionException, InterruptedException {
      CreateToolchainContextBuilderKey key = (CreateToolchainContextBuilderKey) skyKey;
      try {
        ToolchainContextBuilder toolchainContextBuilder =
            ToolchainContextBuilder.create(
                env,
                key.targetDescription(),
                key.requiredToolchainTypes(),
                key.execConstraintLabels(),
                key.configurationKey());
        if (toolchainContextBuilder == null) {
          return null;
        }
        return CreateToolchainContextBuilderValue.create(toolchainContextBuilder);
      } catch (ToolchainException e) {
        throw new CreateToolchainContextFunctionException(e);
      }
    }

    @Nullable
    @Override
    public String extractTag(SkyKey skyKey) {
      return null;
    }
  }

  private static class CreateToolchainContextFunctionException extends SkyFunctionException {
    CreateToolchainContextFunctionException(ToolchainException e) {
      super(e, Transience.PERSISTENT);
    }
  }
}
