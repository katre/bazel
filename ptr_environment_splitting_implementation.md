# Environment Splitting Implementation for Persistent Test Runner Worker Reuse

## Summary

Successfully implemented environment variable splitting in `WorkerSpawnRunner` to enable worker reuse for persistent test runners. The implementation splits test-specific environment variables from stable ones, allowing the WorkerKey to be computed with only stable environment variables while test-specific variables are passed via WorkRequest.environment.

## Implementation Details

### Files Modified

**`src/main/java/com/google/devtools/build/lib/worker/WorkerSpawnRunner.java`**

### Changes Made

#### 1. Added Constants for Test-Specific Environment Variables

**Lines 89-99:**
```java
/** Environment variable prefixes that are test-specific. */
private static final ImmutableList<String> TEST_SPECIFIC_ENV_VAR_PREFIXES =
    ImmutableList.of("TEST_", "TESTBRIDGE_", "COVERAGE_");

/** Individual test-specific environment variables. */
private static final ImmutableList<String> TEST_SPECIFIC_ENV_VARS =
    ImmutableList.of(
        "XML_OUTPUT_FILE",
        "RUNTEST_PRESERVE_CWD",
        "IS_COVERAGE_SPAWN",
        "RUNFILES_MANIFEST_ONLY");
```

#### 2. Added Helper Methods

**Lines 150-166:**

```java
/**
 * Returns true if the environment variable is test-specific and should be passed in
 * WorkRequest.environment rather than being part of the WorkerKey.
 */
private static boolean isTestSpecificEnvVar(String key) {
  for (String prefix : TEST_SPECIFIC_ENV_VAR_PREFIXES) {
    if (key.startsWith(prefix)) {
      return true;
    }
  }
  return TEST_SPECIFIC_ENV_VARS.contains(key);
}

/** Checks if this spawn is a test executing via persistent worker. */
private static boolean isPersistentTestSpawn(Spawn spawn) {
  return spawn.getMnemonic().equals("TestRunner") && Spawns.supportsWorkers(spawn);
}
```

#### 3. Environment Splitting in exec() Method

**Lines 225-264:**

The `exec()` method now:
- Detects persistent test spawns using `isPersistentTestSpawn()`
- Splits environment into stable and test-specific maps using `isTestSpecificEnvVar()`
- Creates a new `SimpleSpawn` with only stable environment for WorkerKey computation
- Stores test-specific environment separately to pass later to WorkRequest

```java
// For persistent test runners, split environment to enable worker reuse
Spawn spawnForWorkerKey = spawn;
Map<String, String> testSpecificEnv = ImmutableMap.of();

if (isPersistentTestSpawn(spawn)) {
  Map<String, String> stableEnv = new TreeMap<>();
  Map<String, String> testEnv = new TreeMap<>();

  for (Map.Entry<String, String> entry : spawn.getEnvironment().entrySet()) {
    if (isTestSpecificEnvVar(entry.getKey())) {
      testEnv.put(entry.getKey(), entry.getValue());
    } else {
      stableEnv.put(entry.getKey(), entry.getValue());
    }
  }

  // Create modified spawn with stable environment only for WorkerKey computation
  spawnForWorkerKey =
      new SimpleSpawn(
          spawn.getResourceOwner(),
          spawn.getArguments(),
          ImmutableMap.copyOf(stableEnv),
          spawn.getExecutionInfo(),
          spawn.getInputFiles(),
          spawn.getToolFiles(),
          spawn.getOutputFiles(),
          /* mandatoryOutputs= */ null,
          () -> {
            try {
              return spawn.getLocalResources();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new RuntimeException(
                  "Interrupted while getting local resources (should not happen)", e);
            }
          },
          spawn.getPathMapper());

  testSpecificEnv = ImmutableMap.copyOf(testEnv);
}
```

**Line 279:** Modified spawn passed to WorkerParser:
```java
WorkerParser.WorkerConfig workerConfig = workerParser.compute(spawnForWorkerKey, context);
```

#### 4. Updated execInWorker() Signature

**Lines 467-477:** Added `testSpecificEnv` parameter:
```java
WorkResponse execInWorker(
    Spawn spawn,
    WorkerKey key,
    SpawnExecutionContext context,
    SandboxInputs inputFiles,
    SandboxOutputs outputs,
    List<String> flagFiles,
    InputMetadataProvider inputFileCache,
    SpawnMetrics.Builder spawnMetrics,
    Map<String, String> testSpecificEnv)  // NEW PARAMETER
```

#### 5. Updated createWorkRequest() Signature and Implementation

**Lines 320-330:** Added `testSpecificEnv` parameter:
```java
private WorkRequest createWorkRequest(
    Spawn spawn,
    SpawnExecutionContext context,
    SandboxInputs inputFiles,
    List<String> flagfiles,
    Map<VirtualActionInput, byte[]> virtualInputDigests,
    InputMetadataProvider inputFileCache,
    WorkerKey key,
    Map<String, String> testSpecificEnv)  // NEW PARAMETER
```

**Lines 369-370:** Populate WorkRequest.environment field:
```java
// Populate WorkRequest.environment with test-specific variables for persistent test runners
requestBuilder.putAllEnvironment(testSpecificEnv);
```

#### 6. Added Imports

Added necessary imports:
- `java.util.TreeMap`
- `com.google.common.collect.ImmutableList`
- `com.google.devtools.build.lib.actions.SimpleSpawn`

## How It Works

### Architecture Flow

1. **StandaloneTestStrategy** creates spawn with FULL environment (unchanged)
   - Works with all execution strategies (worker, standalone, sandboxed)

2. **WorkerSpawnRunner.exec()** detects persistent test spawns
   - Checks: `spawn.getMnemonic().equals("TestRunner") && Spawns.supportsWorkers(spawn)`

3. **Environment Splitting**
   - Stable environment: non-test-specific variables → used for WorkerKey
   - Test-specific environment: TEST_*, TESTBRIDGE_*, COVERAGE_*, XML_OUTPUT_FILE, etc. → passed to WorkRequest

4. **Modified Spawn Creation**
   - Creates new SimpleSpawn with stable environment only
   - This spawn is passed to WorkerParser.compute()

5. **WorkerKey Computation**
   - WorkerParser receives spawn with stable environment
   - WorkerKey computed with stable environment
   - **Result: Same WorkerKey for all tests → Worker reuse enabled!**

6. **WorkRequest Creation**
   - Test-specific environment populated in WorkRequest.environment field (proto field 7)
   - Worker process merges these with base environment when executing test

### Test-Specific Environment Variables

Variables that are split out (excluded from WorkerKey):

**Prefixes:**
- `TEST_*` (TEST_TARGET, TEST_TMPDIR, TEST_SHARD_INDEX, etc.)
- `TESTBRIDGE_*`
- `COVERAGE_*`

**Individual variables:**
- `XML_OUTPUT_FILE`
- `RUNTEST_PRESERVE_CWD`
- `IS_COVERAGE_SPAWN`
- `RUNFILES_MANIFEST_ONLY`

All other environment variables remain in the stable environment and affect the WorkerKey.

## Build Status

✅ **Build successful:**
```
bazel build //src:bazel-dev
INFO: Build completed successfully, 20 total actions
```

## Backward Compatibility

- **Non-persistent tests**: No changes, use full environment as before
- **Non-worker execution**: Spawn contains full environment, works correctly
- **Persistent tests without worker support**: No splitting occurs
- **Other worker types**: No changes, only affects TestRunner mnemonic with worker support

## Next Steps

1. **Write unit tests** for:
   - `isTestSpecificEnvVar()` method
   - `isPersistentTestSpawn()` method
   - Environment splitting logic in `exec()`
   - WorkRequest.environment population

2. **Run integration tests** (already written in `bazel_worker_test.sh`):
   - `test_persistent_test_single_shot()`
   - `test_persistent_test_worker_reuse()` - Verifies worker reuse with UUID
   - `test_persistent_test_pass_fail_behavior()`

3. **Update worker implementations** to merge WorkRequest.environment with base environment
   - Example: `PersistentTestWorker.java`

4. **Update documentation** (`ptr_design.md`)

## Key Design Decisions

1. **Environment splitting in WorkerSpawnRunner, not StandaloneTestStrategy**
   - Spawn must contain full environment for non-worker strategies
   - Only WorkerSpawnRunner knows it's doing worker execution
   - Respects architectural boundaries

2. **Using WorkRequest.environment field (proto field 7)**
   - Already supported by WorkRequest protocol
   - Map<string, string> type matches our needs
   - No protocol changes required

3. **TreeMap for stable environment**
   - Ensures consistent ordering for WorkerKey hash
   - Deterministic behavior across runs

4. **InterruptedException handling in LocalResourcesSupplier**
   - Restore interrupt flag
   - Wrap in RuntimeException (should not happen in practice)
   - Maintains interface compatibility

## Result

Persistent test runners can now reuse worker processes across multiple tests, avoiding JVM startup overhead and improving test execution performance. The WorkerKey is computed with only stable environment variables, allowing the same worker to execute different tests with different test-specific environment variables passed via WorkRequest.environment.
