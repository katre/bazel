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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions;
import com.google.devtools.build.lib.util.RegexFilter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link TestConfiguration} persistent test runners flag. */
@RunWith(JUnit4.class)
public final class TestConfigurationPersistentRunnersTest {

  private TestConfiguration createTestConfiguration(String... args) throws Exception {
    BuildOptions buildOptions = BuildOptions.of(ImmutableList.of(TestOptions.class), args);
    return new TestConfiguration(buildOptions);
  }

  @Test
  public void defaultValue_isEmptyString() throws Exception {
    TestConfiguration config = createTestConfiguration();
    RegexFilter filter = config.getEnablePersistentTestRunners();
    assertThat(filter.toString()).isEmpty();
  }

  @Test
  public void defaultValue_excludesNonNullMnemonics() throws Exception {
    TestConfiguration config = createTestConfiguration();
    assertThat(config.isPersistentTestRunnerEnabled("JavaTest")).isFalse();
    assertThat(config.isPersistentTestRunnerEnabled("GoTest")).isFalse();
    assertThat(config.isPersistentTestRunnerEnabled("AnyTest")).isFalse();
  }

  @Test
  public void defaultValue_excludesNullMnemonic() throws Exception {
    TestConfiguration config = createTestConfiguration();
    assertThat(config.isPersistentTestRunnerEnabled(null)).isFalse();
  }

  @Test
  public void defaultValue_excludesEmptyMnemonic() throws Exception {
    TestConfiguration config = createTestConfiguration();
    assertThat(config.isPersistentTestRunnerEnabled("")).isFalse();
  }

  @Test
  public void enableAll_matchesDotStar() throws Exception {
    TestConfiguration config = createTestConfiguration("--enable_persistent_test_runners=.*");
    assertThat(config.isPersistentTestRunnerEnabled("JavaTest")).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("GoTest")).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("PythonTest")).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("AnyTest")).isTrue();
  }

  @Test
  public void enableAll_stillIncludesNullAndEmpty() throws Exception {
    TestConfiguration config = createTestConfiguration("--enable_persistent_test_runners=.*");
    assertThat(config.isPersistentTestRunnerEnabled(null)).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("")).isTrue();
  }

  @Test
  public void enableSpecific_matchesOnlySpecified() throws Exception {
    TestConfiguration config = createTestConfiguration("--enable_persistent_test_runners=JavaTest");
    assertThat(config.isPersistentTestRunnerEnabled("JavaTest")).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("GoTest")).isFalse();
    assertThat(config.isPersistentTestRunnerEnabled("PythonTest")).isFalse();
  }

  @Test
  public void enablePattern_matchesPrefix() throws Exception {
    TestConfiguration config = createTestConfiguration("--enable_persistent_test_runners=Java.*");
    assertThat(config.isPersistentTestRunnerEnabled("JavaTest")).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("JavaIntegrationTest")).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("GoTest")).isFalse();
  }

  @Test
  public void enableWithExclusion_excludesTakesPrecedence() throws Exception {
    TestConfiguration config =
        createTestConfiguration("--enable_persistent_test_runners=.*,-GoTest");
    assertThat(config.isPersistentTestRunnerEnabled("JavaTest")).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("PythonTest")).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("GoTest")).isFalse();
  }

  @Test
  public void enableWithExclusion_stillIncludesNullAndEmpty() throws Exception {
    TestConfiguration config =
        createTestConfiguration("--enable_persistent_test_runners=.*,-GoTest");
    assertThat(config.isPersistentTestRunnerEnabled(null)).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("")).isTrue();
  }

  @Test
  public void complexPattern_multipleInclusionsAndExclusions() throws Exception {
    TestConfiguration config =
        createTestConfiguration(
            "--enable_persistent_test_runners=Java.*,Python.*,-JavaIntegrationTest");
    assertThat(config.isPersistentTestRunnerEnabled("JavaTest")).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("JavaUnitTest")).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("JavaIntegrationTest")).isFalse();
    assertThat(config.isPersistentTestRunnerEnabled("PythonTest")).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("GoTest")).isFalse();
  }

  @Test
  public void nullMnemonic_enabledWhenFlagSet() throws Exception {
    // When flag is set (even with exclusions), null mnemonic is always enabled
    TestConfiguration config = createTestConfiguration("--enable_persistent_test_runners=-.*");
    assertThat(config.isPersistentTestRunnerEnabled(null)).isTrue();
  }

  @Test
  public void emptyMnemonic_enabledWhenFlagSet() throws Exception {
    // When flag is set (even with exclusions), empty mnemonic is always enabled
    TestConfiguration config = createTestConfiguration("--enable_persistent_test_runners=-.*");
    assertThat(config.isPersistentTestRunnerEnabled("")).isTrue();
  }

  @Test
  public void exclusionOnly_excludesMatching() throws Exception {
    TestConfiguration config =
        createTestConfiguration("--enable_persistent_test_runners=-GoTest");
    // Exclusion-only pattern includes everything except the exclusions
    assertThat(config.isPersistentTestRunnerEnabled("JavaTest")).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("PythonTest")).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("GoTest")).isFalse();
  }

  @Test
  public void multipleValues_lastOneWins() throws Exception {
    TestConfiguration config =
        createTestConfiguration(
            "--enable_persistent_test_runners=JavaTest", "--enable_persistent_test_runners=GoTest");
    // The second flag should override the first
    assertThat(config.isPersistentTestRunnerEnabled("JavaTest")).isFalse();
    assertThat(config.isPersistentTestRunnerEnabled("GoTest")).isTrue();
  }

  @Test
  public void regexFilterGetter_returnsCorrectFilter() throws Exception {
    TestConfiguration config = createTestConfiguration("--enable_persistent_test_runners=Java.*");
    RegexFilter filter = config.getEnablePersistentTestRunners();
    assertThat(filter.isIncluded("JavaTest")).isTrue();
    assertThat(filter.isIncluded("GoTest")).isFalse();
  }

  @Test
  public void caseSensitive_matchesCaseSensitively() throws Exception {
    TestConfiguration config = createTestConfiguration("--enable_persistent_test_runners=JavaTest");
    assertThat(config.isPersistentTestRunnerEnabled("JavaTest")).isTrue();
    assertThat(config.isPersistentTestRunnerEnabled("javatest")).isFalse();
    assertThat(config.isPersistentTestRunnerEnabled("JAVATEST")).isFalse();
  }
}
