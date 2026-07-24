# Plan: Implement Persistent Test Runners in Bazel

## Context

Bazel currently runs each test in a fresh process, incurring startup overhead (especially for JVM tests, iOS/Android simulators). The ptr_design.md proposes enabling test rules to opt into persistent worker mode - tests run in long-lived worker processes that handle multiple test executions without restart overhead.

This implementation adds:
- `PersistentTestInfo` Starlark provider for test rules to declare persistent worker support
- `--enable_persistent_test_runners` flag with regex filtering by worker mnemonic
- Integration with existing persistent worker infrastructure (WorkerPool, WorkRequest/WorkResponse proto)
- Test environment variable handling for persistent mode

**Design doc**: `ptr_design.md`

## Phase 1A: PersistentTestInfo Provider (Without Arguments)

Create basic Starlark provider with first 3 fields, following RunEnvironmentInfo pattern.

### Files to Create

**`src/main/java/com/google/devtools/build/lib/starlarkbuildapi/test/PersistentTestInfoApi.java`**
- Interface with `@StarlarkBuiltin(name = "PersistentTestInfo")`
- 3 fields as `@StarlarkMethod(structField=true)`:
  - `multiplex`: Boolean, default false - supports multiplex protocol
  - `requires_worker_protocol`: String ("json" or "proto"), default "proto"
  - `worker_key_mnemonic`: String - distinct worker name (optional, can be null/empty)
- Inner `PersistentTestInfoApiProvider` interface with `@StarlarkConstructor` method
- Pattern: RunEnvironmentInfoApi.java lines 35-111

**`src/main/java/com/google/devtools/build/lib/analysis/test/PersistentTestInfo.java`**
- Extend `NativeInfo`, implement `PersistentTestInfoApi`
- Store immutable fields (boolean, String, String)
- Static `PROVIDER` singleton
- Inner `BuiltinProvider<PersistentTestInfo>` class with constructor accepting 3 parameters
- Validate: protocol is "json" or "proto", worker_key_mnemonic can be null/empty (optional)
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
- Test null/empty worker_key_mnemonic handling

## Phase 1B: Add Arguments Field

Add `arguments` field with complex CommandLines handling.

### Files to Modify

**`src/main/java/com/google/devtools/build/lib/starlarkbuildapi/test/PersistentTestInfoApi.java`**
- Add 4th field as `@StarlarkMethod(structField=true)`:
  - `arguments`: Sequence<CommandLineArgsApi> - test runner args (read-only view for Starlark)

**`src/main/java/com/google/devtools/build/lib/analysis/test/PersistentTestInfo.java`**
- Modify to store CommandLines field for arguments
- Update constructor to accept Sequence<?> containing Strings and Args objects (4th parameter)
- Use StarlarkActionFactory.buildCommandLine() pattern to build CommandLines from mixed inputs
- Provide getCommandLines() method for internal usage
- Implement getArguments() to unpack CommandLines into Sequence<CommandLineArgsApi> for Starlark

#### Argument Handling Implementation

**Constructor pattern** (following StarlarkActionFactory.buildCommandLine):
```java
public static PersistentTestInfo create(
    boolean multiplex,
    String requiresWorkerProtocol,
    String workerKeyMnemonic,
    Sequence<?> arguments,
    RepositoryMapping repoMapping) {

  CommandLines.Builder builder = CommandLines.builder();
  ImmutableList.Builder<String> stringArgs = null;

  for (Object arg : arguments) {
    if (arg instanceof String) {
      if (stringArgs == null) {
        stringArgs = ImmutableList.builder();
      }
      stringArgs.add((String) arg);
    } else if (arg instanceof Args) {
      if (stringArgs != null) {
        builder.addCommandLine(CommandLine.of(stringArgs.build()));
        stringArgs = null;
      }
      Args argsObj = (Args) arg;
      builder.addCommandLine(
          argsObj.build(repoMapping),
          argsObj.getParamFileInfo());
    } else {
      throw new EvalException("arguments must be strings or Args objects");
    }
  }

  if (stringArgs != null) {
    builder.addCommandLine(CommandLine.of(stringArgs.build()));
  }

  return new PersistentTestInfo(
      multiplex,
      requiresWorkerProtocol,
      workerKeyMnemonic,
      builder.build());
}
```

**Storage:**
```java
private final CommandLines commandLines;
```

**Internal accessor** (for spawn creation):
```java
public CommandLines getCommandLines() {
  return commandLines;
}
```

**Starlark accessor** (for inspection):
```java
@Override
public Sequence<CommandLineArgsApi> getArguments() {
  ImmutableList.Builder<CommandLineArgsApi> result = ImmutableList.builder();
  // Note: Empty directoryInputs for test arguments
  ImmutableSet<Artifact> directoryInputs = ImmutableSet.of();

  for (CommandLineAndParamFileInfo cmdLine : commandLines.unpack()) {
    result.add(Args.forRegisteredAction(cmdLine, directoryInputs));
  }
  return StarlarkList.immutableCopyOf(result.build());
}
```

**BUILD files**
- `src/main/java/com/google/devtools/build/lib/analysis/test/BUILD`:
  - Add dependency on `//src/main/java/com/google/devtools/build/lib/analysis/actions:commandline`
  - Add dependency on `//src/main/java/com/google/devtools/build/lib/starlarkbuildapi:args`

### Tests

Update `src/test/java/com/google/devtools/build/lib/analysis/test/PersistentTestInfoTest.java`:
- Test arguments field with Strings
- Test arguments field with Args objects
- Test mixed Strings and Args objects
- Test empty arguments
- Test invalid argument types (should fail)

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
  // If no mnemonic is provided (null or empty), always enable persistent test runners
  if (workerKeyMnemonic == null || workerKeyMnemonic.isEmpty()) {
    return true;
  }
  // If mnemonic is provided, check against the flag filter
  return testOptions.enablePersistentTestRunners.isIncluded(workerKeyMnemonic);
}
```

### Tests

Create test for flag parsing and regex matching:
- Test empty string (disabled by default)
- Test `.*` (enable all)
- Test specific mnemonics
- Test exclusions with `-`
- Test null/empty mnemonic (always enabled)

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
  // Only set WORKER_KEY_MNEMONIC if provided
  String mnemonic = testInfo.getWorkerKeyMnemonic();
  if (mnemonic != null && !mnemonic.isEmpty()) {
    executionInfo.put(ExecutionRequirements.WORKER_KEY_MNEMONIC, mnemonic);
  }

  // Arguments for WorkRequest: expand CommandLines from provider
  ImmutableList.Builder<String> workRequestArgs = ImmutableList.builder();

  // Use CommandLines.expand() to get actual argument strings
  // Note: No artifact expansion needed for test arguments
  ArtifactExpander artifactExpander = null;
  PathMapper pathMapper = PathMapper.NOOP;

  try {
    ExpandedCommandLines expanded = testInfo.getCommandLines().expand(
        artifactExpander,
        action.getPrimaryOutput().getExecPath(),
        pathMapper,
        CommandLineLimits.UNLIMITED);

    workRequestArgs.addAll(expanded.arguments());
  } catch (CommandLineExpansionException e) {
    throw new UserExecException(e, Code.COMMAND_LINE_EXPANSION_FAILURE);
  }

  // Add per-test environment as arguments (since WorkRequest doesn't have env field)
  // Add to WorkRequest.environment

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

### Step 3C: Worker Integration

**`src/main/java/com/google/devtools/build/lib/worker/WorkerSpawnRunner.java`**

Modify `canExec` (line 135) to accept test spawns with worker execution requirements.

In `exec` method, when creating WorkRequest:
- Extract test-specific arguments from spawn
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
   - Executes test
   - Writes WorkResponse JSON to stdout
   - In non-persistent mode: executes once and exits

3. Test scenarios:
   - Multiple tests through same worker
   - Worker restart on crash
   - Flag filtering (enable/disable by mnemonic)
   - **Tests with no WorkerKeyMnemonic (always enabled)**
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

**Test Environment Variables**: Extend WorkRequest proto with `map<string, string> environment` field. Recommendation: Start with arguments approach, consider proto extension if needed.

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

**From StarlarkActionFactory.buildCommandLine()** (`src/main/java/com/google/devtools/build/lib/analysis/starlark/StarlarkActionFactory.java` lines 688-716):
- Accept mixed Sequence of Strings and Args objects
- Build CommandLines.Builder by iterating and type-checking
- Accumulate consecutive strings, then add as single CommandLine
- Convert Args objects via args.build(repoMapping)
- Handle ParamFileInfo from Args objects

**From SpawnAction** (`src/main/java/com/google/devtools/build/lib/analysis/actions/SpawnAction.java`):
- Store arguments as CommandLines (line 108)
- getStarlarkArgs() unpacks and wraps with Args.forRegisteredAction() (lines 218-227)
- Execution uses commandLines.expand() directly, not getStarlarkArgs()
- Memory optimization via CommandLines multiple implementations (One/Two/Three/NPartCommandLines)

**From CommandLines** (`src/main/java/com/google/devtools/build/lib/analysis/actions/CommandLines.java`):
- Builder pattern for accumulating command lines
- expand() method for execution-time argument expansion
- unpack() method for iteration over CommandLineAndParamFileInfo
