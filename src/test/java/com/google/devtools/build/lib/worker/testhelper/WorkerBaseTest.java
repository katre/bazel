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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat;
import com.google.devtools.build.lib.worker.WorkRequestHandler.WorkerMessageProcessor;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsClass;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link WorkerBase}. */
@RunWith(JUnit4.class)
public class WorkerBaseTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private Path testDir;

  @Before
  public void setUp() throws IOException {
    testDir = temporaryFolder.getRoot().toPath();
  }

  /** Test options class for the test worker. */
  @OptionsClass
  public abstract static class TestWorkerOptions extends OptionsBase {
    @Option(
        name = "persistent_worker",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "Run in persistent worker mode")
    public abstract boolean getPersistentWorker();
  }

  /** Simple test worker implementation. */
  private static class TestWorker extends WorkerBase<TestWorkerOptions> {
    int singleShotCalls = 0;
    int workCalls = 0;
    List<String> lastArgs = null;

    @Override
    protected Class<TestWorkerOptions> getOptionsClass() {
      return TestWorkerOptions.class;
    }

    @Override
    protected int runSingleShot(List<String> args) {
      singleShotCalls++;
      lastArgs = args;
      return 0;
    }

    @Override
    protected int runWork(List<String> args, PrintWriter err) {
      workCalls++;
      lastArgs = args;
      return 0;
    }

    @Override
    protected boolean isPersistentMode(TestWorkerOptions options) {
      return options.getPersistentWorker();
    }

    @Override
    protected WorkerProtocolFormat getProtocolFormat(TestWorkerOptions options) {
      return WorkerProtocolFormat.PROTO;
    }
  }

  @Test
  public void testExpandParamfiles_noParamfiles() throws Exception {
    TestWorker worker = new TestWorker();
    List<String> args = ImmutableList.of("arg1", "arg2", "arg3");
    List<String> expanded = worker.expandParamfiles(args);

    assertThat(expanded).containsExactly("arg1", "arg2", "arg3").inOrder();
  }

  @Test
  public void testExpandParamfiles_atSymbol() throws Exception {
    // Create a paramfile with arguments
    Path paramfile = testDir.resolve("args.txt");
    Files.write(paramfile, ImmutableList.of("file1.txt", "file2.txt", "file3.txt"));

    TestWorker worker = new TestWorker();
    List<String> args = ImmutableList.of("@" + paramfile.toString(), "extra-arg");
    List<String> expanded = worker.expandParamfiles(args);

    assertThat(expanded).containsExactly("file1.txt", "file2.txt", "file3.txt", "extra-arg");
  }

  @Test
  public void testExpandParamfiles_flagfileFormat() throws Exception {
    // Create a paramfile with arguments
    Path paramfile = testDir.resolve("flags.txt");
    Files.write(paramfile, ImmutableList.of("--option1", "--option2=value", "arg1"));

    TestWorker worker = new TestWorker();
    List<String> args = ImmutableList.of("--flagfile=" + paramfile.toString(), "extra-arg");
    List<String> expanded = worker.expandParamfiles(args);

    assertThat(expanded).containsExactly("--option1", "--option2=value", "arg1", "extra-arg");
  }

  @Test
  public void testExpandParamfiles_multipleParamfiles() throws Exception {
    // Create multiple paramfiles
    Path paramfile1 = testDir.resolve("args1.txt");
    Files.write(paramfile1, ImmutableList.of("arg1", "arg2"));

    Path paramfile2 = testDir.resolve("args2.txt");
    Files.write(paramfile2, ImmutableList.of("arg3", "arg4"));

    TestWorker worker = new TestWorker();
    List<String> args =
        ImmutableList.of("@" + paramfile1.toString(), "middle", "@" + paramfile2.toString());
    List<String> expanded = worker.expandParamfiles(args);

    assertThat(expanded).containsExactly("arg1", "arg2", "middle", "arg3", "arg4").inOrder();
  }

  @Test
  public void testExpandParamfiles_emptyParamfile() throws Exception {
    // Create an empty paramfile
    Path paramfile = testDir.resolve("empty.txt");
    Files.write(paramfile, ImmutableList.of());

    TestWorker worker = new TestWorker();
    List<String> args = ImmutableList.of("before", "@" + paramfile.toString(), "after");
    List<String> expanded = worker.expandParamfiles(args);

    assertThat(expanded).containsExactly("before", "after").inOrder();
  }

  @Test
  public void testExpandParamfiles_utf8Encoding() throws Exception {
    // Create a paramfile with UTF-8 characters
    Path paramfile = testDir.resolve("utf8.txt");
    Files.write(paramfile, ImmutableList.of("日本語", "中文", "한글"));

    TestWorker worker = new TestWorker();
    List<String> args = ImmutableList.of("@" + paramfile.toString());
    List<String> expanded = worker.expandParamfiles(args);

    assertThat(expanded).containsExactly("日本語", "中文", "한글").inOrder();
  }

  @Test
  public void testExpandParamfiles_nonexistentFile() {
    TestWorker worker = new TestWorker();
    Path nonexistent = testDir.resolve("nonexistent.txt");
    List<String> args = ImmutableList.of("@" + nonexistent.toString());

    assertThrows(IOException.class, () -> worker.expandParamfiles(args));
  }

  @Test
  public void testExpandParamfiles_mixedArgs() throws Exception {
    Path paramfile = testDir.resolve("mixed.txt");
    Files.write(paramfile, ImmutableList.of("fromfile1", "fromfile2"));

    TestWorker worker = new TestWorker();
    List<String> args =
        ImmutableList.of(
            "regular-arg", "--option=value", "@" + paramfile.toString(), "--another-flag");
    List<String> expanded = worker.expandParamfiles(args);

    assertThat(expanded)
        .containsExactly("regular-arg", "--option=value", "fromfile1", "fromfile2", "--another-flag")
        .inOrder();
  }

  @Test
  public void testCreateOptionsParser() throws Exception {
    TestWorker worker = new TestWorker();

    // Test with residue allowed
    var parser = worker.createOptionsParser(true);
    assertThat(parser).isNotNull();

    // Parse some options
    parser.parse("--persistent_worker", "residue1", "residue2");
    TestWorkerOptions options = parser.getOptions(TestWorkerOptions.class);
    assertThat(options.getPersistentWorker()).isTrue();
    assertThat(parser.getResidue()).containsExactly("residue1", "residue2");
  }

  @Test
  public void testCreateMessageProcessor_proto() throws Exception {
    TestWorker worker = new TestWorker();
    WorkerMessageProcessor processor = worker.createMessageProcessor(WorkerProtocolFormat.PROTO);
    assertThat(processor).isNotNull();
  }

  @Test
  public void testCreateMessageProcessor_json() throws Exception {
    TestWorker worker = new TestWorker();
    WorkerMessageProcessor processor = worker.createMessageProcessor(WorkerProtocolFormat.JSON);
    assertThat(processor).isNotNull();
  }
}
