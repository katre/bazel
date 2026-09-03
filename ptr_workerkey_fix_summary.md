# WorkerKey Reuse Fix - Implementation Summary

## Problem
Persistent test runners were creating a new worker for each test instead of reusing workers. Root cause: test-specific environment variables (TEST_TARGET, TEST_TMPDIR, TEST_SHARD_INDEX, etc.) were included in the spawn environment, which is part of the WorkerKey hash. Different WorkerKeys = new worker process created.

## Solution Implemented
Split environment into stable (WorkerKey) and test-specific (WorkRequest) parts:
- **Stable environment** → Used for WorkerKey (enables worker reuse)
- **Test-specific environment** → Passed in each WorkRequest.environment field

## Changes Made

### 1. StandaloneTestStrategy.java
**Location**: `src/main/java/com/google/devtools/build/lib/exec/StandaloneTestStrategy.java`

**Added**:
- `TEST_SPECIFIC_ENV_VAR_PREFIXES`: Set of prefixes (TEST_, TESTBRIDGE_, COVERAGE_)
- `TEST_SPECIFIC_ENV_VARS`: Set of individual vars (XML_OUTPUT_FILE, IS_COVERAGE_SPAWN, etc.)
- `isTestSpecificEnvVar()`: Helper method to identify test-specific variables

**Modified** `createTestRunnerSpawn()`:
- Split environment into `stableEnv` and `testSpecificEnv` for persistent test runners
- Encode test-specific env into `ExecutionRequirements.WORKER_REQUEST_ENVIRONMENT`
- Use stable env for spawn (affects WorkerKey)
- Non-persistent tests use full environment as before (no behavior change)

### 2. ExecutionRequirements.java
**Location**: `src/main/java/com/google/devtools/build/lib/actions/ExecutionRequirements.java`

**Added**:
- `WORKER_REQUEST_ENVIRONMENT`: New constant for passing per-request environment

### 3. WorkerSpawnRunner.java
**Location**: `src/main/java/com/google/devtools/build/lib/worker/WorkerSpawnRunner.java`

**Added**:
- `decodeRequestEnvironment()`: Decodes null-separated key=value pairs

**Modified** `createWorkRequest()`:
- Populate `WorkRequest.environment` field from encoded execution info
- Workers receive test-specific variables per-request

## Environment Variable Encoding
Format: `key1=value1\0key2=value2\0...` (null-separated)

## Test-Specific Variables Identified
- **Prefixes**: TEST_*, TESTBRIDGE_*, COVERAGE_*
- **Individual**: XML_OUTPUT_FILE, RUNTEST_PRESERVE_CWD, IS_COVERAGE_SPAWN, RUNFILES_MANIFEST_ONLY

## Worker-Side Requirements
Workers MUST merge `WorkRequest.environment` with their base environment when executing tests:

```java
WorkRequest request = messageProcessor.readWorkRequest();
Map<String, String> testEnv = new HashMap<>(System.getenv());
testEnv.putAll(request.getEnvironmentMap());
// Use testEnv when executing test
```

## Expected Behavior
- Single worker process handles multiple tests with different TEST_TARGET, TEST_TMPDIR, etc.
- Each test receives correct per-test environment variables
- Worker reuse confirmed by single process ID across all tests

## Next Steps
1. **Testing**: Write unit and integration tests
2. **Documentation**: Update ptr_design.md with environment handling
3. **Worker Updates**: Ensure test workers merge WorkRequest.environment
4. **Verification**: Run integration tests with --worker_verbose to confirm reuse

## Files Modified
- `src/main/java/com/google/devtools/build/lib/exec/StandaloneTestStrategy.java`
- `src/main/java/com/google/devtools/build/lib/actions/ExecutionRequirements.java`
- `src/main/java/com/google/devtools/build/lib/worker/WorkerSpawnRunner.java`
