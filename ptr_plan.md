# Plan: Implement Persistent Test Runners in Bazel

## Context

Bazel currently runs each test in a fresh process, incurring startup overhead (especially for JVM tests, iOS/Android simulators). The ptr_design.md proposes enabling test rules to opt into persistent worker mode - tests run in long-lived worker processes that handle multiple test executions without restart overhead.

This implementation adds:
- `PersistentTestInfo` Starlark provider for test rules to declare persistent worker support
- `--enable_persistent_test_runners` flag with regex filtering by worker mnemonic
- Integration with existing persistent worker infrastructure (WorkerPool, WorkRequest/WorkResponse proto)
- Test environment variable handling for persistent mode

**Design doc**: `ptr_design.md`

## Phase 1: PersistentTestInfo Provider

Create Starlark provider following RunEnvironmentInfo pattern.

### Files to Create

**`src/main/java/com/google/devtools/build/lib/starlarkbuildapi/test/PersistentTestInfoApi.java`**
- Interface with `@StarlarkBuiltin(name = "PersistentTestInfo")`
- 4 fields as `@StarlarkMethod(structField=true)`:
  - `multiplex`: Boolean, default false - supports multiplex protocol
  - `requires_worker_protocol`: String ("json" or "proto"), default "proto"
  - `worker_key_mnemonic`: String - distinct worker name
  - `arguments`: Sequence - test runner args (Strings or Args objects)
- Inner `PersistentTestInfoApiProvider` interface with `@StarlarkConstructor` method
- Pattern: RunEnvironmentInfoApi.java lines 35-111

**`src/main/java/com/google/devtools/build/lib/analysis/test/PersistentTestInfo.java`**
- Extend `NativeInfo`, implement `PersistentTestInfoApi`
- Store immutable fields (ImmutableList for arguments, validate protocol format)
- Static `PROVIDER` singleton
- Inner `BuiltinProvider<PersistentTestInfo>` class with constructor
- Validate: protocol is "json" or "proto", worker_key_mnemonic non-empty if provider returned
- Pattern: RunEnvironmentInfo.java lines 36-106

### Files to Modify

**`src/main/java/com/google/devtools/build/lib/analysis/starlark/StarlarkGlobalsImpl.java`**
- Add to `getFixedBzlToplevels()` after line 104:
  ```java
  env.put("PersistentTestInfo", PersistentTestInfo.PROVIDER);
  ```

**BUILD files**
- `src/main/java/com/google/devtools/build/lib/starlarkbuildapi/test/BUILD`: Add PersistentTestInfoApi target
- `src/main/java/com/google/devtools/build/lib/analysis/test/BUILD`: Add starlarkbuildapi dependency

### Tests

Create `src/test/java/com/google/devtools/build/lib/analysis/test/PersistentTestInfoTest.java`:
- Test provider construction with valid/invalid parameters
- Test defaults (multiplex=false, requires_worker_protocol="proto")
- Test protocol validation
- Test Starlark field access

## Phase 2: Flag Implementation

Add `--enable_persistent_test_runners` flag with regex filter.

### Files to Modify

**`src/main/java/com/google/devtools/build/lib/analysis/test/TestConfiguration.java`**

In `TestOptions` class after line 200:
```java
@Option(
    name = "enable_persistent_test_runners",
    defaultValue = "",
    converter = RegexFilter.RegexFilterConverter.class,
    documentationCategory = OptionDocumentationCategory.TESTING,
    effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.TEST_RUNNER},
    help = "Enables persistent test runners. Value is regex filter matching worker mnemonics. "
        + "Examples: '.*' (all), 'JavaTest' (only JavaTest), '.*,-GoTest' (all except GoTest).")
public RegexFilter enablePersistentTestRunners;
```

In `TestConfiguration` class around line 300:
```java
public RegexFilter getEnablePersistentTestRunners() {
  return testOptions.enablePersistentTestRunners;
}

public boolean isPersistentTestRunnerEnabled(String workerKeyMnemonic) {
  return workerKeyMnemonic != null && !workerKeyMnemonic.isEmpty()
      && testOptions.enablePersistentTestRunners.isIncluded(workerKeyMnemonic);
}
```

### Tests

Create test for flag parsing and regex matching:
- Test empty string (disabled by default)
- Test `.*` (enable all)
- Test specific mnemonics
- Test exclusions with `-`

## Phase 3: Test Execution Integration

### Step 3A: Pass Provider Through Test Action Creation

**`src/main/java/com/google/devtools/build/lib/analysis/test/TestRunnerAction.java`**

Add field around line 150:
```java
@Nullable private final PersistentTestInfo persistentTestInfo;
```

Add to constructor (around line 200), initialize field.

Add methods around line 1000:
```java
@Nullable
public PersistentTestInfo getPersistentTestInfo() {
  return persistentTestInfo;
}

public boolean usesPersistentTestRunner() {
  return persistentTestInfo != null
      && testConfiguration.isPersistentTestRunnerEnabled(
          persistentTestInfo.getWorkerKeyMnemonic());
}
```

**`src/main/java/com/google/devtools/build/lib/analysis/test/TestActionBuilder.java`**

Add field around line 80:
```java
private PersistentTestInfo persistentTestInfo;
```

Add setter around line 140:
```java
@CanIgnoreReturnValue
public TestActionBuilder setPersistentTestInfo(@Nullable PersistentTestInfo info) {
  this.persistentTestInfo = info;
  return this;
}
```

Pass to TestRunnerAction constructor in `createTestAction` method (around line 250).

**Extract provider from test targets**: Modify test rule analysis to check for PersistentTestInfo and call `builder.setPersistentTestInfo()`.

### Step 3B: Create Persistent Test Spawns

**`src/main/java/com/google/devtools/build/lib/exec/StandaloneTestStrategy.java`**

Modify `createTestRunnerSpawn` (lines 104-150):
```java
@Override
public TestRunnerSpawn createTestRunnerSpawn(...) {
  if (action.usesPersistentTestRunner()) {
    return createPersistentTestRunnerSpawn(action, actionExecutionContext);
  }
  // existing non-persistent code
}

private TestRunnerSpawn createPersistentTestRunnerSpawn(...) {
  PersistentTestInfo testInfo = action.getPersistentTestInfo();

  // Partition environment: worker-global vs per-test
  Map<String, String> workerEnv = new HashMap<>();
  Map<String, String> perTestEnv = new HashMap<>();

  // Worker environment (set at worker startup):
  workerEnv.put("TEST_BINARY", executable.getExecPathString());
  workerEnv.put("TEST_WORKSPACE", action.getRunfilesPrefix());
  // ... other global vars

  // Per-test environment (sent with each WorkRequest):
  perTestEnv.put("TEST_TMPDIR", tmpDir.toString());
  perTestEnv.put("XML_OUTPUT_FILE", action.getXmlOutputPath().toString());
  perTestEnv.put("TEST_SHARD_INDEX", String.valueOf(action.getShardNum()));
  perTestEnv.put("TEST_TOTAL_SHARDS", String.valueOf(action.getExecutionSettings().getTotalShards()));
  perTestEnv.put("TEST_RUN_NUMBER", String.valueOf(action.getRunNumber() + 1));
  // ... all per-test vars from TestRunnerAction.setupEnvVariables lines 721-803

  // Build execution requirements
  Map<String, String> executionInfo = new TreeMap<>(action.getExecutionInfo());
  executionInfo.put(
      testInfo.getMultiplex()
          ? ExecutionRequirements.SUPPORTS_MULTIPLEX_WORKERS
          : ExecutionRequirements.SUPPORTS_WORKERS,
      "1");
  executionInfo.put(ExecutionRequirements.REQUIRES_WORKER_PROTOCOL,
      testInfo.getRequiresWorkerProtocol());
  executionInfo.put(ExecutionRequirements.WORKER_KEY_MNEMONIC,
      testInfo.getWorkerKeyMnemonic());

  // Arguments for WorkRequest: test-specific args from provider
  ImmutableList.Builder<String> workRequestArgs = ImmutableList.builder();
  for (Object arg : testInfo.getArguments()) {
    if (arg instanceof String) {
      workRequestArgs.add((String) arg);
    } else if (arg instanceof CommandLineItem) {
      // Expand Args object
      workRequestArgs.addAll(((CommandLineItem) arg).arguments());
    }
  }

  // Add per-test environment as arguments (since WorkRequest doesn't have env field)
  for (Map.Entry<String, String> entry : perTestEnv.entrySet()) {
    workRequestArgs.add("--test_env:" + entry.getKey() + "=" + entry.getValue());
  }

  // Create spawn with worker args
  ImmutableList<String> spawnArgs = ImmutableList.of(
      executable.getExecPathString(),
      "--persistent_worker");

  Spawn spawn = new SimpleSpawn(
      action,
      spawnArgs,
      ImmutableMap.copyOf(workerEnv),
      executionInfo,
      /* inputs */ action.getInputs(),
      /* outputs */ action.getOutputs(),
      action.getResourceSet());

  // Store workRequestArgs in spawn metadata for WorkerSpawnRunner
  // (may need custom Spawn subclass or use execution info)

  return new TestRunnerSpawnImpl(spawn, policy, ...);
}
```

**Key design decision**: Per-test environment variables passed as `--test_env:VAR=value` arguments (WorkRequest proto doesn't have environment field). Worker binary must parse these.

### Step 3C: Worker Integration

**`src/main/java/com/google/devtools/build/lib/worker/WorkerSpawnRunner.java`**

Modify `canExec` (line 135) to accept test spawns with worker execution requirements.

In `exec` method, when creating WorkRequest:
- Extract test-specific arguments from spawn (including `--test_env:` args)
- Set `WorkRequest.arguments` to these args
- Worker binary receives arguments, parses test environment, executes test

Existing WorkerPool/WorkerKey infrastructure handles:
- Worker lifecycle (start/stop/reuse)
- Protocol handling (JSON or proto based on REQUIRES_WORKER_PROTOCOL)
- Multiplex support (based on SUPPORTS_MULTIPLEX_WORKERS)

### Step 3D: Fallback Support

In `StandaloneTestStrategy`, if `WorkerSpawnRunner.canExec()` returns false or worker unavailable:
- Fall back to non-persistent mode
- Create spawn without `--persistent_worker` flag
- Pass arguments directly on command line
- Worker binary must support both modes (design requirement)

## Phase 4: Testing

### Unit Tests

**`PersistentTestInfoTest.java`**: Provider construction and validation

**`TestConfigurationPersistentRunnersTest.java`**: Flag parsing and regex matching

**`TestRunnerActionTest.java`**: Test `usesPersistentTestRunner()` logic

**`StandaloneTestStrategyTest.java`**: Environment partitioning, spawn creation

### Integration Tests

**`persistent_test_runners_integration_test.sh`** (in `src/test/shell/integration/`):

Create test scenario:
1. Define Starlark test rule returning PersistentTestInfo:
   ```python
   def _my_test_impl(ctx):
       ctx.actions.write(
           output = ctx.outputs.executable,
           content = "#!/usr/bin/env python3\n" + worker_script)
       return [
           DefaultInfo(executable = ctx.outputs.executable),
           PersistentTestInfo(
               multiplex = False,
               requires_worker_protocol = "json",
               worker_key_mnemonic = "MyTestWorker",
               arguments = ["--test_target=" + str(ctx.label)])
       ]
   ```

2. Create worker binary (Python script):
   - Accepts `--persistent_worker` flag
   - Reads WorkRequest JSON from stdin
   - Parses `--test_env:VAR=value` arguments
   - Executes test
   - Writes WorkResponse JSON to stdout
   - In non-persistent mode: executes once and exits

3. Test scenarios:
   - Multiple tests through same worker
   - Worker restart on crash
   - Flag filtering (enable/disable by mnemonic)
   - Sharded tests (TEST_SHARD_INDEX handling)
   - runs_per_test (TEST_RUN_NUMBER handling)
   - Fallback to standalone mode

### Sample Implementation

Create reference worker in `tools/test/persistent_test_worker_example.py`:
- Demonstrates WorkRequest/WorkResponse handling
- Shows environment variable parsing
- Shows proper test isolation
- Serves as template for rule authors

## Verification

After implementation:
1. All unit tests pass
2. Integration tests demonstrate:
   - Worker processes multiple tests without restart
   - Correct environment variables per test
   - Test isolation (no state leakage)
   - Sharding and runs_per_test work correctly
3. Existing tests unaffected (flag disabled by default)
4. Performance improvement for startup-heavy tests (measure with benchmark)
5. Worker crashes handled gracefully (fallback to standalone)

## Implementation Order

1. **Phase 1** (independent): Provider definition
2. **Phase 2** (depends on Phase 1): Flag in TestConfiguration
3. **Phase 3** in sequence:
   - 3A: Pass provider through test action creation
   - 3B: Create persistent spawns in StandaloneTestStrategy
   - 3C: Worker integration (WorkerSpawnRunner)
   - 3D: Fallback handling
4. **Phase 4** (continuous): Test each phase as implemented

## Critical Files

**Create:**
- `src/main/java/com/google/devtools/build/lib/starlarkbuildapi/test/PersistentTestInfoApi.java`
- `src/main/java/com/google/devtools/build/lib/analysis/test/PersistentTestInfo.java`
- `tools/test/persistent_test_worker_example.py`
- Unit and integration tests

**Modify:**
- `src/main/java/com/google/devtools/build/lib/analysis/test/TestConfiguration.java`
- `src/main/java/com/google/devtools/build/lib/analysis/test/TestRunnerAction.java`
- `src/main/java/com/google/devtools/build/lib/analysis/test/TestActionBuilder.java`
- `src/main/java/com/google/devtools/build/lib/exec/StandaloneTestStrategy.java`
- `src/main/java/com/google/devtools/build/lib/analysis/starlark/StarlarkGlobalsImpl.java`
- BUILD files for dependencies

## Open Design Questions

**Test Environment Variables**: Current approach passes per-test env vars as `--test_env:VAR=value` arguments. Alternative: Extend WorkRequest proto with `map<string, string> environment` field. Recommendation: Start with arguments approach, consider proto extension if needed.

**Sharding**: Single worker can handle multiple shards (receives TEST_SHARD_INDEX per request) or run separate worker per shard. Recommendation: Single worker, more efficient.

**Worker Tmpdir**: Worker creates/cleans TEST_TMPDIR per test, or Bazel creates and passes path. Recommendation: Bazel creates, passes in per-test environment.

## Reusable Patterns

**From RunEnvironmentInfo** (`src/main/java/com/google/devtools/build/lib/analysis/RunEnvironmentInfo.java`):
- Provider structure with API interface and implementation
- Field definition with @StarlarkMethod(structField=true)
- BuiltinProvider pattern with static singleton

**From WorkerSpawnRunner** (`src/main/java/com/google/devtools/build/lib/worker/WorkerSpawnRunner.java`):
- WorkerKey creation from spawn execution requirements
- WorkerPool integration
- WorkRequest/WorkResponse handling

**From TestRunnerAction** (`src/main/java/com/google/devtools/build/lib/analysis/test/TestRunnerAction.java`):
- Test environment variable setup (lines 721-803)
- Partition variables into global and per-test categories
