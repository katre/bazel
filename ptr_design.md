# Persistent Test Runners Implementation

Last modified: July 20, 2026
Status: Draft
Authors: John Cater

## Background

In Bazel’s architecture, tests are actions just like any other: Bazel test rules define test actions which are run as separate processes and therefore benefit from bazel’s caching and remote execution features. There’s no need to re-run a test if the answer is already known, and there’s no need to run it locally when there is a remote execution system available.

However, this does lead to some challenges: because process startup has overhead, every test action has the same overhead. In the case of some types of tests, this can be quite large: Java tests require the JVM to start and initialize, and re-running tests don’t benefit from the JVM’s existing JIT cache. Tests that require simulators or emulators (like iOS and Android tests) can be even worse, as they require a heavyweight process to begin and become ready before the test case can start.

Bazel has a solution for this for non-test actions: by opting in to use [persistent workers](https://bazel.build/remote/persistent#usage) rules can declare that they work with a specialized tool that starts once and stays active, servicing multiple actions without paying the overhead to start a new process (but with some overhead: message parsing isn’t free, and attention needs to be paid to ensure that state doesn’t leak from one action to the next). At one point, Bazel had the [option of persistent test runners](https://github.com/bazelbuild/bazel/commit/1f33504af3ebe3a43aa91e607e94edc07b1807f8), but this unfortunately didn’t work well: it only supported Java tests, and the JVM did not do a good job of isolating subsequent runs, and so the code was entirely removed in March, 2022.

The benefits of persistent test runners are still interesting, however, so this proposal addresses a way to enable Bazel test rules to opt in to the same persistent test worker API that is available to non-test actions. Rules will need to be modified to take advantage of the new API, but this opens room for test optimizations that are currently not possible.

## Bazel Changes

Directly bringing back [the previous version](https://github.com/bazelbuild/bazel/commit/02f8da948bf3955304a4cef9399bd3907430bbc4) won’t work: it assumed the test runner was part of Bazel and that `java_test` was a native implementation, so it was easy for Bazel to know whether the runner was persistent-capable or not.

None of this is true in Bazel 9.0: we want to support more than just JVM-based tests, test rules are implemented in Starlark, and Bazel cannot just assume that all tests of a specific type will use the persistent runner.

Because of this, we need an API for Bazel and test rules to:

1. Let the rule discover if it can use a persistent runner
2. Tell bazel that a specific test runner can be persistent
3. Tell the runner that Bazel wants it to stay persistent
4. Bazel to send new work to a persistent test runner

The current API for Starlark tests is very simple: a test rule creates an executable at a predefined output (`ctx.outputs.executable`), and then Bazel invokes it with the proper environment when the time is right to execute the test. See [a standard example](https://github.com/bazelbuild/examples/blob/main/rules/test_rule/line_length.bzl) of a simple test rule to see how this works.

### Bazel Flags

To gate the new feature, we will add a new flag, `--enable_persistent_test_runners`, to tell Bazel that persistent test runners should be enabled if the test rule cooperates. If the flag is disabled, then even if the test rule attempts to use the new APIs, tests will be run one-at-a-time.

The flag will be a regexp filter matching worker key mnemonics, allowing fine-grained control over which persistent test workers can be used. For example, `--enable_persistent_test_runners=.*,-GoTestWorker` means that persistent runners are enabled for all worker mnemonics except `GoTestWorker`. The filter will work by directly matching against the `WorkerKeyMnemonic` field from the `PersistentTestInfo` provider. If multiple test rules share the same `WorkerKeyMnemonic`, they will all be controlled by the same flag setting. If a test rule does not provide a `WorkerKeyMnemonic` or provides an empty string, persistent test runners will not be enabled for that rule regardless of the flag value.

Regardless of this flag setting, if the test rule cannot support persistent test runners, the tests will continue to execute using the older non-persistent system.

### Rule API

We will define a new provider type, `PersistentTestInfo`, which test rules can return to tell Bazel whether they support persistent test runners. The provider will have the following fields:

* `Multiplex`: True if the worker supports the multiplex worker protocol. The default is `false`, meaning that the worker can only handle a single request at a time.
* `RequiresWorkerProtocol`: Either `json` or `proto`, to distinguish between the protocol format. The default is `proto`, to match the non-test persistent workers.
* `WorkerKeyMnemonic`: A distinct name for the worker, which can be useful if the same worker can be used for multiple types of tests.
    * Regardless of the `WorkerKeyMnemonic` value, a single worker cannot handle both test and non-test actions, and may end up with multiple instances running at once.
* `Arguments`: A Starlark array of the test runner arguments, either Strings or `args` objects. This will be passed to the test runner for each test executed, either directly on the command line (for single-use mode) or via the worker protocol (for persistent mode).

The test rule then creates the executable for the persistent worker binary as the test output, then returns all the standard providers (including runfiles, `OutputGroupInfo`, `RunEnvironmentInfo`, etc) and an instance of the new `PersistentTestInfo` provider.

When the test worker is executed, there are two ways that Bazel may invoke it:

1. With the `--persistent_worker` flag, Bazel will expect the runner to enter persistent mode, where it reads requests from stdin and writes responses to stdout. Argument data describing the specific test will be in the `WorkRequest` object
2. Without the `--persistent_worker` flag, Bazel will expect the runner to read further command line arguments to decide what test to execute, to execute the test, and then exit.

Note that accepting command line arguments is a major change from previous test binaries, which are expected to have all configuration information as part of the binary and not to accept any arguments.

## Test Runner Changes

As with [standard persistent workers](https://bazel.build/remote/creating), Bazel will start the test executable, passing the flag `--persistent_worker`, which should cause the runner to remain persistent and listen for control messages on stdin. The persistent test runner is responsible for properly handling test isolation and cleanup.

## Open Questions

### Test Environment

We need to audit all of the [initial test setup environment variables](https://bazel.build/reference/test-encyclopedia#initial-conditions), and decide which are global for the persistent test runner, and which are per-test and need to be added to the control message.

Does Bazel need to reset `TEST_TMPDIR` for different tests in the same persistent runner? Etc etc.

#### Sharded Tests/Runs Per Test

How do we handle that? Just run more persistent test workers in parallel? Somehow indicate over the control message what the `TEST_SHARD_INDEX` and `TEST_TOTAL_SHARDS` environment variables should be?

