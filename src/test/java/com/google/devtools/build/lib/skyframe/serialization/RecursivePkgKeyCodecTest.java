// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.skyframe.RecursivePkgKey;
import com.google.devtools.build.lib.skyframe.serialization.testutils.FsUtils;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RecursivePkgKey}'s codec. */
@RunWith(JUnit4.class)
public class RecursivePkgKeyCodecTest {

  @Test
  public void testCodec() throws Exception {
    SerializationTester serializationTester =
        new SerializationTester(
            new RecursivePkgKey(
                RepositoryName.MAIN, FsUtils.TEST_ROOTED_PATH, IgnoredSubdirectories.EMPTY),
            new RecursivePkgKey(
                RepositoryName.MAIN,
                FsUtils.TEST_ROOTED_PATH,
                IgnoredSubdirectories.of(
                    ImmutableSet.of(
                        FsUtils.rootPathRelative("here"), FsUtils.rootPathRelative("there")))));
    FsUtils.addDependencies(serializationTester);
    serializationTester.runTests();
  }
}
